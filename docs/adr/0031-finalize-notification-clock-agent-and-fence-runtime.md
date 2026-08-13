# ADR-0031: Finalize the Notification Clock Agent and Fence Runtime

## Status

Accepted

## Date

2026-08-10

## Relationship to Earlier Decisions

This ADR extends ADR-0017 through ADR-0023 with the clock-agent runtime,
Protobuf bounds, anti-replay rules, chronyd deployment policy, network policy,
PostgreSQL identity query, and all-submission degraded-mode behavior.

It supersedes:

- ADR-0017 and ADR-0018 only where they leave non-time-bound submission during
  critical clock health unresolved: all new submissions now fail closed;
- ADR-0020's provisional `db_instance_id` response field and separate agent
  ServiceAccount assumption;
- ADR-0022's requirement that the agent run `IDENTIFY_SYSTEM` with PostgreSQL
  replication privilege.

The agent has no PostgreSQL credential. Notification obtains the PostgreSQL
system identifier and current WAL timeline through its normal least-privilege
writer connection and binds those values to the matching agent's Pod identity.

## Decision

### Agent runtime and responsibility

`clock-health-agent` uses Go 1.26.5, builds a static binary, and runs from a
`scratch` final image. It reads and validates local chronyd tracking data and
serves the bounded internal gRPC health contract. It does not connect to
PostgreSQL and receives no PostgreSQL, replication, application, or provider
credential.

Granting the agent `REPLICATION` or access to `IDENTIFY_SYSTEM` is prohibited.
The agent health port is `9095`.

### PostgreSQL-authoritative identity query

On every two-second cycle, Notification acquires the normal writer connection
and executes this bounded read-only query under ADR-0023's acquisition,
statement, and overall-cycle budgets:

```sql
SELECT
  pg_is_in_recovery(),
  inet_server_addr(),
  (pg_control_system()).system_identifier,
  (
    pg_split_walfile_name(
      pg_walfile_name(pg_current_wal_lsn())
    )
  ).timeline_id;
```

The result is accepted only when `pg_is_in_recovery() = false`. It supplies the
PostgreSQL system identifier and current WAL timeline used by ADR-0022's fence
epoch. Function permissions are explicitly least-privilege and verified; no
replication role or broad file-access role is granted.

Notification calls the agent directly on the returned PostgreSQL Pod IP. The
response must identify that same Pod IP. A standby result, address mismatch,
failed or malformed query, invalid timeline/system identity, unreachable
agent, invalid response, replay, or stale signal is immediately critical.

### Clock-health Protobuf contract

The response uses exactly these v1 fields and units:

```proto
string pod_ip                     = 1;  // 7..45 bytes
string pod_uid                    = 2;  // 1..64 bytes
string node_id                    = 3;  // 1..253 bytes
bytes agent_boot_id               = 4;  // exactly 16 bytes
uint64 sample_sequence            = 5;  // at least 1
ClockHealthState state            = 6;
uint64 estimated_max_error_micros = 7;  // <= 3_600_000_000
sint64 system_offset_micros       = 8;  // abs <= 3_600_000_000
sint64 root_delay_micros          = 9;  // abs <= 3_600_000_000
uint64 root_dispersion_micros     = 10; // <= 3_600_000_000
LeapStatus leap_status            = 11;
bool external_reference           = 12;
bool nts_authenticated            = 13;
string selected_source            = 14; // 1..253 bytes
```

Application response size is at most 512 bytes and the gRPC inbound hard cap is
4 KiB. Measurements use integer microseconds. Removed field names and numbers
are permanently reserved; enum values are append-only; an unknown enum is
critical. Buf `FILE` compatibility is mandatory.

`agent_boot_id` is a random UUIDv4 generated at every process start.
`sample_sequence` starts at one, increments for each fresh successful
`GetClockHealth` sample, permits gaps, and must never wrap. A duplicate or
out-of-order sequence at or below the last accepted high-water mark is replay,
does not refresh freshness, and trips the local signal critical.

A boot-ID change invalidates the previous high-water mark, trips the dispatch
fence critical, resets recovery progress, and requires three fresh qualifying
healthy samples before re-arm. A PostgreSQL Pod address or UID change likewise
invalidates prior binding and recovery progress.

### Chronyd socket and source policy

The host chronyd command directory is `/run/chrony`. The read-only sidecar
mount is `/run/chrony-host`, and the agent reads
`/run/chrony-host/chronyd.sock`. The required read-only `hostPath` is an
explicit, narrowly scoped security exception for this PostgreSQL sidecar.

The agent runs as UID 0 solely because chronyd's local command socket is
restricted to root or the chrony user. It is not privileged and uses:

```text
allowPrivilegeEscalation=false
capabilities.drop=[ALL]
readOnlyRootFilesystem=true
seccompProfile=RuntimeDefault
```

The production chronyd source allow-list is:

```text
ptbtime1.ptb.de nts iburst
ptbtime4.ptb.de nts iburst
sth1.nts.netnod.se nts iburst
mmo1.nts.netnod.se nts iburst
```

Only NTS sources are allowed. Pool and DHCP-provided sources are disabled and
`minsources=2`. A local/non-external reference, unauthenticated selected source,
unsynchronized leap state, source outside the allow-list, unreadable socket,
or invalid tracking signal is critical.

### Network and mesh policy

The agent is a PostgreSQL-Pod sidecar and shares that Pod's ServiceAccount and
Istio identity, as established by ADR-0021. Ingress NetworkPolicy on port 9095
allows only Notification Service pods. Agent container egress is deny-all
because chronyd is accessed through the Unix socket.

Istio `PeerAuthentication STRICT` and L4 `AuthorizationPolicy` allow health
port ingress only from the `notification-service` ServiceAccount. There is no
waypoint, bearer token, API key, JWT, or second native-TLS layer.

### Critical time-source behavior

When the authoritative signal is critical or stale, every new
`SubmitNotification` fails with `UNAVAILABLE / TIME_SOURCE_UNHEALTHY`, including
non-time-bound semantics. Non-time-bound notifications still need trusted
`accepted_at` and channel deadlines.

Lookup and conflict resolution for an already accepted `request_id` occurs
before this health check and returns the original outcome. No new durable
acceptance is created.

ADR-0018's degraded-mode rules remain unchanged for existing work: no new
dispatch, retry dispatch, deadline expiration, or scheduled receipt poll starts;
in-flight definitive outcomes, authenticated evidence, and time-independent
result callbacks may still commit. Recovery never extends a persisted deadline
or budget.

### Fence and cycle integration

The canonical fence epoch remains:

```text
(postgresql_system_identifier, wal_timeline_id, rearm_generation)
```

The first two elements now come from Notification's verified writer query, not
the agent. The matching agent proves chronyd health and Pod identity. A change
in system identifier, timeline, PostgreSQL Pod address/UID, or agent boot ID
trips the fence critical and requires re-arm.

The two-second cycle retains:

- 300-millisecond connection acquisition;
- 150-millisecond session-scoped primary-query `statement_timeout`;
- agent RPC of at most 500 milliseconds, one attempt, no retry;
- 1200-millisecond monotonic overall deadline;
- each step capped by the remaining cycle budget;
- no freshness refresh for any partial or failed cycle;
- five-second monotonic stale threshold;
- next scheduled cycle as a new attempt, with no immediate retry.

The FenceCoordinator remains elected by the ADR-0030 advisory lock, publishes
heartbeat every two seconds, and workers fail closed after five seconds without
monotonic heartbeat progress. Claims and dispatch authorization retain
ADR-0022's row-lock and exact-epoch checks.

## Observability and Verification Requirements

Tests cover Protobuf numeric and byte bounds, 512-byte application and 4-KiB
transport limits, unknown enums, reserved fields, sequence replay, boot and Pod
incarnation changes, chronyd parse/error-bound calculations, NTS/source
validation, three-sample recovery, primary query permissions, pooled
`statement_timeout` containment, direct Pod-IP binding, every timeout, and
fence races.

Deployment verification covers read-only socket mounting, the explicit root
exception with all hardening controls, agent deny-all egress, positive and
negative NetworkPolicy/Istio authorization, NTS loss, chronyd loss, agent
restart, PostgreSQL promotion, and no dispatch under stale/critical health.

Telemetry is bounded and contains no raw chronyd output, credentials,
recipient, content, codes, `request_id`, or `notification_id`.

Runtime validation references are the official
[Go release history](https://go.dev/doc/devel/release),
[PostgreSQL system-information functions](https://www.postgresql.org/docs/current/functions-info.html),
[PostgreSQL WAL functions](https://www.postgresql.org/docs/current/functions-admin.html),
[chronyc Unix-socket contract](https://chrony-project.org/doc/4.8/chronyc.html),
[PTB NTS service](https://www.ptb.de/cms/en/ptb/fachabteilungen/abt9/gruppe-95/ref-952/time-synchronization-of-computers-using-the-network-time-protocol-ntp.html),
and [Netnod NTS service](https://www.netnod.se/nts/network-time-security).

## Consequences

- The clock agent has no database privilege capable of accessing WAL or
  replication.
- The fence epoch and clock sample are bound by the PostgreSQL Pod identity
  without trusting an agent-supplied database identity.
- Critical clock health uniformly closes durable acceptance and dispatch.
- Root and `hostPath` are accepted only for the narrow socket-read sidecar and
  remain visible security exceptions subject to policy tests.

## Rollback or Migration Considerations

Rollout first deploys and verifies chronyd NTS policy and every PostgreSQL
sidecar, then the new contract, then Notification's query and fence behavior.
Notification remains fail closed until a complete matching healthy cycle and
three-sample re-arm succeed.

Rollback must not restore agent replication credentials, agent-supplied
database identity, acceptance of non-time-bound submissions during critical
health, former-primary cache fallback, unbounded socket access, or dispatch
without the exact persisted fence epoch.
