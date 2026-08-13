# ADR-0022: Use a Database-Backed Notification Dispatch Fence

## Status

Accepted

## Date

2026-08-09

## Relationship to Earlier Decisions

This ADR extends ADR-0018 through ADR-0021 by defining the database-backed
cross-replica fence required before Notification provider dispatch.

It does not change the authoritative PostgreSQL clock, degraded-mode rules,
primary-bound clock-health topology, lifecycle transitions, retry budgets,
provider ambiguity handling, or terminal-state immutability established by
ADR-0013 through ADR-0021.

## Context

ADR-0021 binds each Notification replica's clock-health decision to the
PostgreSQL primary Pod and its co-scheduled agent. A promotion can still occur
after that verification and before provider I/O. Independently healthy local
signals therefore do not create a cross-replica serialization boundary for
dispatch.

Notification needs an epoch that changes across PostgreSQL promotion and
explicit safety re-arming, a single coordinator for shared health progress,
and a database lock boundary between fence changes and dispatch authorization.
The mechanism cannot cancel provider I/O that was already authorized and
committed before a critical transition or failover.

## Decision

### Singleton fence and canonical epoch

Notification uses a database-backed singleton dispatch fence. Its canonical
epoch is the tuple:

```text
(postgresql_system_identifier, wal_timeline_id, rearm_generation)
```

The primary-bound agent obtains the PostgreSQL system identifier and current
WAL timeline ID from that server's `IDENTIFY_SYSTEM` identity. Promotion to a
new primary changes the WAL timeline and automatically makes every epoch from
the former timeline invalid.

`rearm_generation` is a database-persisted `BIGINT`. It is incremented:

- whenever an existing `HEALTHY` fence is tripped to `CRITICAL`;
- again before that same PostgreSQL timeline is re-armed `HEALTHY`.

A PostgreSQL-primary or agent-incarnation change also forces re-arming. No
worker may treat the new incarnation as dispatch-authorized merely because a
previous incarnation was healthy.

### Coordinator and heartbeat

Exactly one `FenceCoordinator` is active across Notification replicas. It is
elected with a session-level PostgreSQL advisory lock and publishes a
monotonically advancing `heartbeat_seq` in the shared fence state.

Each worker measures heartbeat progress with a local monotonic timer. If
`heartbeat_seq` does not advance for 5 seconds, that worker fails closed and
must not claim or authorize provider dispatch. A wall clock, agent timestamp,
or Prometheus sample cannot satisfy this freshness rule.

### Transactional authorization boundary

Attempt-claim and dispatch-authorization transactions acquire `FOR SHARE` on
the singleton fence row. Fence-state transitions acquire `FOR UPDATE` on that
row. These locks are the serialization boundary between dispatch authorization
and a committed fence transition.

Every claimed provider attempt persists the exact canonical fence epoch under
which it was claimed. Immediately before external provider I/O, Notification
runs an `AuthorizeDispatch` transaction that:

1. acquires the required shared fence lock;
2. revalidates that the fence is `HEALTHY` and its exact epoch still matches
   the claimed attempt;
3. transactionally changes that attempt to `DISPATCHING`;
4. commits before any provider I/O starts.

Provider network I/O is prohibited inside the transaction. If a fence
transition commits first, later authorization under the old or unhealthy epoch
is rejected. If `AuthorizeDispatch` commits first, that attempt is already in
flight; a later fence transition does not claim to cancel its external I/O and
the existing provider ambiguity and idempotency rules apply.

### PostgreSQL HA boundary

The Notification fence is not a PostgreSQL split-brain prevention mechanism.
The PostgreSQL HA layer must prevent more than one writable primary.

When PostgreSQL HA is introduced, fence-state and dispatch-authorization
transactions require synchronous replication to at least one
failover-eligible standby when prevention of committed dispatch-state loss
across failover is required. No application-level fence can recover a commit
that the database HA layer loses during promotion.

### Observability and verification

Notification exposes bounded telemetry for fence state, epoch changes,
re-arm transitions, coordinator election, heartbeat progress/staleness,
authorization denial reasons, and in-flight attempts spanning a fence change.
Logs and metric labels must not contain recipient data, rendered content,
codes, raw request payloads, `request_id`, or `notification_id`.

Runtime verification must include:

- one active coordinator under the session advisory lock;
- fail-closed behavior after 5 seconds without heartbeat progress, using a
  monotonic test clock;
- generation increments on healthy-to-critical and same-timeline re-arm;
- epoch invalidation after timeline or agent-incarnation change;
- lock-order races where fence transition commits first and where dispatch
  authorization commits first;
- rejection of provider I/O before the authorization transaction commits;
- failover tests proving that former epochs cannot authorize new dispatch;
- synchronous-replication durability tests when PostgreSQL HA is introduced.

### Implementation gate

This ADR fixes the fencing model but does not select the exact table schema,
constraints, advisory-lock key, heartbeat publication cadence, transaction
isolation level, statement or lock timeout, PostgreSQL HA product, or concrete
synchronous-replication configuration. Those inputs require explicit approval
before their dependent migrations, code, or deployment policy is implemented.

The remaining contract, credentials, sidecar-permission, network-policy,
rollout, and provider decisions from ADR-0012 and ADR-0021 also remain gated.

## Consequences

- PostgreSQL promotion invalidates former dispatch epochs through the WAL
  timeline identity.
- Explicit critical and re-arm transitions cannot silently reuse a healthy
  epoch on the same timeline.
- Database row-lock ordering serializes dispatch authorization with fence
  transitions across Notification replicas.
- Provider I/O remains outside transactions and can begin only after durable
  authorization.
- An already authorized external attempt remains an in-flight ambiguity risk;
  fencing cannot revoke an external side effect.
- Correct failover durability depends on a non-split-brain PostgreSQL HA layer
  and, when required, synchronous replication of authorization state.

## Alternatives Considered

### Use only each replica's local clock-health signal

Rejected because local signals do not serialize a failover or critical fence
transition with dispatch authorization on other replicas.

### Use PostgreSQL server address as the epoch

Rejected because an address is not the canonical database incarnation and WAL
timeline identity and can be reused across promotion.

### Hold a database transaction open during provider I/O

Rejected because network I/O inside a transaction is prohibited and would hold
the singleton fence lock across an unbounded external operation.

### Cancel already authorized I/O after a fence transition

Rejected as a guarantee because the external request may already have crossed
the local process boundary. Such attempts follow the established ambiguity and
provider-idempotency rules.

### Treat the application fence as split-brain protection

Rejected because a database-backed fence cannot establish one authoritative
writable PostgreSQL primary when the HA layer itself permits split brain.

## Rollback or Migration Considerations

This ADR creates no runtime migration by itself. Its future singleton fence,
attempt-epoch, and supporting schema must be introduced only through Flyway and
an expand-migrate-contract rollout.

Production dispatch must remain fail closed until every active Notification
replica uses the approved epoch and authorization transaction. Rollback to a
replica that can dispatch without the fence requires disabling provider
dispatch first. A rollback must not decrement or reuse `rearm_generation`,
accept an epoch from a former timeline or agent incarnation, or move provider
I/O into a database transaction.
