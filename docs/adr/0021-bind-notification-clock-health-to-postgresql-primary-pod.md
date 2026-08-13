# ADR-0021: Bind Notification Clock Health to the PostgreSQL Primary Pod

## Status

Accepted

## Date

2026-08-09

## Relationship to Earlier Decisions

This ADR extends ADR-0019 and ADR-0020 by defining PostgreSQL-primary discovery,
direct agent selection, Pod-IP binding, cache invalidation after failover, and
the clock-health-agent sidecar topology.

It supersedes only ADR-0020's requirement that `clock-health-agent` use a
Kubernetes ServiceAccount separate from PostgreSQL. Because the agent is a
sidecar in the PostgreSQL Pod, it shares that Pod's ServiceAccount and workload
identity. Notification Service continues to use its own separate ServiceAccount.

ADR-0020's unary gRPC transport, 2-second cycle, 500-millisecond deadline,
single attempt, monotonic freshness, 5-second stale threshold, strict mTLS, L4
source authorization, and prohibition of application credentials remain in
force.

## Context

Kubernetes Service selection or metadata does not prove that a selected
PostgreSQL endpoint is currently writable. An agent load-balancing Service can
also route a health request to an agent that does not share the system clock of
the database connection used to establish primary status.

Binding the database verification and agent response to one PostgreSQL Pod IP
ensures that the sampled chronyd signal describes the host clock used by the
PostgreSQL server that answered the primary check. Failover can still occur
after this verification and before provider dispatch, so this binding is not a
substitute for distributed fencing.

## Decision

### PostgreSQL sidecar topology

Every PostgreSQL Pod runs one `clock-health-agent` sidecar. PostgreSQL and the
agent are therefore:

- co-scheduled on the same Kubernetes node;
- members of the same Pod network namespace;
- reachable through the same Pod IP;
- governed by the same PostgreSQL Pod ServiceAccount and Istio workload
  identity.

The agent is not deployed behind a load-balancing Kubernetes Service for
Notification clock-health discovery. Notification also does not use Kubernetes
Pod metadata, labels, Endpoints, EndpointSlices, or the Kubernetes API to decide
which PostgreSQL instance is primary.

The exact secure mount and permission model by which the sidecar reads the
node's local chronyd Unix-domain socket remains a deployment/security decision.
Any required `hostPath` use requires the explicit security review mandated by
the canonical architecture.

### Database-authoritative primary discovery

On every clock-health cycle, each Notification replica acquires a connection
through the normal PostgreSQL writer endpoint and executes:

```sql
SELECT pg_is_in_recovery(), inet_server_addr();
```

The connected backend is accepted as the authoritative current primary only
when `pg_is_in_recovery() = false`. A standby result is immediately
`CRITICAL` and cannot authorize deadline-sensitive work.

The returned `inet_server_addr()` is the PostgreSQL server address for that
cycle. Notification does not replace it with Kubernetes discovery data or an
agent Service address.

This verification query and the following agent RPC are operational health
I/O outside Notification lifecycle transactions. The exact database statement,
connection-acquisition, and cycle timeout budgets remain explicit runtime
inputs that must fit inside the 2-second cycle.

### Direct primary-agent binding

After a successful primary verification, Notification calls the dedicated
clock-health gRPC port directly on the address returned by
`inet_server_addr()`. The call retains ADR-0020's 500-millisecond deadline,
single-attempt, and no-retry behavior.

The agent response must identify the same Pod IP returned by
`inet_server_addr()`. Notification also applies the existing
`db_instance_id`, node, boot, sequence, health, and response-validation rules.

Any of the following makes the replica's local signal immediately `CRITICAL`:

- `pg_is_in_recovery() = true`;
- failure to obtain or validate the PostgreSQL primary-verification result;
- failure to reach the agent at the returned server address;
- a response-declared Pod IP that differs from `inet_server_addr()`;
- any other approved source-identity mismatch;
- a malformed response;
- a stale response under the existing 5-second monotonic freshness rule.

There is no fallback to a former primary's agent or to another agent selected
through load balancing.

### Failover and cache invalidation

A change in the PostgreSQL server address returned by the writer endpoint
immediately invalidates all cached health, recovery-sample progress,
`agent_boot_id`, and `sample_sequence` state associated with the previous
instance.

The former instance's health cannot authorize acceptance, dispatch, retry,
expiration, receipt polling, or degraded-mode recovery. The new server must
complete a full primary verification and matching-agent health cycle before its
signal can contribute to the approved recovery hysteresis.

Every Notification replica performs the full database-primary verification and
matching-agent health cycle every 2 seconds. The existing 5-second
`TIME_SIGNAL_STALE` threshold remains unchanged and uses each replica's local
monotonic timer.

### Istio identity and authorization refinement

The PostgreSQL Pod and its agent sidecar share the PostgreSQL Pod
ServiceAccount. This shared identity is required by the approved sidecar
topology and supersedes ADR-0020's separate-agent-ServiceAccount assumption.
It does not permit unrelated workloads or separate services to share that
ServiceAccount.

Notification Service retains its own ServiceAccount. Agent-port ingress remains
protected by `PeerAuthentication STRICT` and an L4 `AuthorizationPolicy` whose
source is the Notification Service principal and whose destination is the
dedicated health gRPC port on the PostgreSQL Pod workload. No waypoint,
application token, shared key, JWT, or second native-TLS layer is introduced.

### Remaining failover race and implementation gate

Primary discovery plus Pod-IP agent binding does not eliminate the race in
which PostgreSQL failover occurs after a replica verifies primary health but
before it claims or dispatches provider work.

Database-backed cross-replica fencing remains mandatory before production
dispatch. Its epoch source, write/claim integration, invalidation semantics,
and failure behavior require a separate accepted architecture decision.

Other remaining implementation gates include:

- exact database and agent cycle timeout budgets;
- Protobuf Pod-IP representation and validation bounds;
- `db_instance_id`, `agent_boot_id`, and `sample_sequence` transition rules;
- PostgreSQL writer-endpoint TLS, credentials, and least-privilege query access;
- sidecar chronyd socket mount and permissions;
- NetworkPolicy and final Istio manifest details;
- rollout, rollback, failover, stale-signal, and chaos tests.

## Consequences

- The database connection establishes primary status without trusting
  Kubernetes routing metadata.
- Direct Pod-IP gRPC binds the health sample to the PostgreSQL backend that
  answered the verification query.
- A load balancer cannot silently pair the primary check with another Pod's
  clock signal.
- PostgreSQL failover immediately discards former-primary health and recovery
  progress.
- Agent isolation is container-level inside the PostgreSQL Pod rather than a
  separate workload identity.
- A database-backed fencing mechanism is still required to close the
  verification-to-dispatch race across replicas.

## Alternatives Considered

### Discover the primary through Kubernetes labels or Endpoints

Rejected because Kubernetes metadata can lag PostgreSQL role transition and is
not the authoritative database role check.

### Put agents behind a load-balancing Service

Rejected because the selected agent could describe a different Pod clock than
the PostgreSQL backend that answered the primary query.

### Run the agent as a separate Deployment or DaemonSet workload

Rejected for this binding because co-scheduling and shared Pod IP provide a
direct identity between the PostgreSQL backend and its clock-health endpoint.

### Keep cached former-primary health after failover

Rejected because it describes a clock that no longer governs authoritative
PostgreSQL lifecycle decisions.

### Treat primary verification as sufficient fencing

Rejected because failover can occur after verification and before a provider
dispatch claim or external side effect.

## Rollback or Migration Considerations

This ADR creates no application database migration by itself.

The sidecar must be rolled out to every eligible PostgreSQL Pod before direct
Pod-IP health binding is enabled. Notification remains fail closed when the
matched agent is absent. Mixed-version rollout must not use agent load
balancing, Kubernetes primary discovery, or former-primary cached health as a
fallback. Rollback must disable deadline-sensitive work before removing an
agent sidecar or the direct-binding validation.
