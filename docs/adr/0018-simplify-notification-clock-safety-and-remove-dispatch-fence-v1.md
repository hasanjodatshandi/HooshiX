# ADR-0018: Notification Clock Safety and Dispatch Commit v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-14

## Decision

Notification uses a deliberately simple clock and dispatch-safety model. Physical PostgreSQL availability/durability topology follows the selected production profile; the duplicate-safety rule does not change.

### Prohibited runtime mechanisms

The current v1 runtime MUST NOT deploy or depend on a bespoke application clock-health control plane, Chrony socket `hostPath` sidecar, per-request/continuous clock-health RPC, dispatch-fence row/epoch, fence coordinator, heartbeat generation, or re-arm protocol.

Infrastructure nodes still use NTP/Chrony and expose bounded platform clock-health telemetry. Warning above 500ms estimated error and critical above 2s are platform operational alerts, not application authorization gates.

### Time semantics

Notification uses PostgreSQL `clock_timestamp()` for acceptance and lifecycle comparisons. Persisted `accepted_at`, `message_not_after`, and effective deadlines use canonical UTC microsecond precision, are immutable after acceptance, and are never extended by retry, restart, reconciliation, recovery, or failover.

Caller credential expiry remains authoritative. A clock anomaly cannot extend OTP/MFA validity in Identity.

### Dispatch transaction

Immediately before external provider I/O, the worker performs one short local PostgreSQL transaction that:

1. locks/reloads the attempt;
2. reads PostgreSQL-authoritative current time;
3. validates non-terminal state and effective deadline;
4. persists immutable attempt/execution identity;
5. transitions to `DISPATCHING`;
6. commits.

Provider I/O starts only after commit and never occurs inside the transaction.

After `DISPATCHING`, process crash, lease expiry, database outage/recovery/failover, timeout, or unknown provider result never permits blind redispatch. The attempt enters reconciliation under the current ambiguity/evidence rules.

### Durability by production profile

`production-single-server` has one PostgreSQL instance and no automatic database failover. A committed `DISPATCHING` transition is durable only to the capability of that instance, its storage, and the current WAL/PITR recovery model. If process/host/storage loss creates uncertainty about the committed transition or provider execution, recovery treats the attempt as ambiguous and reconciliation remains conservative. It MUST NOT infer that the transition was absent merely because there is no surviving local primary.

`production-ha` uses the current CloudNativePG synchronous required-durability model. Acknowledged `DISPATCHING` state must survive every permitted automatic failover; when required durability cannot be proven, automatic failover is refused rather than risking unsafe state loss.

Thus the single-server profile accepts lower availability and a larger local durability failure domain. It does not accept duplicate provider execution by blind retry.

## Verification requirements

Both profiles test boundary timestamps, canonical microsecond truncation, crash immediately before/after dispatch commit, stale claim behavior, provider timeout/ambiguity, no blind redispatch, no remote I/O in the transaction, and absence of prohibited clock/fence components from production desired state.

`production-single-server` additionally tests PostgreSQL process/host-loss recovery around a committed/uncertain `DISPATCHING` state and proves uncertainty enters reconciliation rather than redispatch.

`production-ha` additionally tests CloudNativePG failover around dispatch commit and required acknowledged-state durability.

## Rollback considerations

Rollback MUST NOT reintroduce bespoke clock/fence mechanisms or permit redispatch of an already committed or uncertain `DISPATCHING` attempt. Database/application rollback must preserve immutable accepted/deadline timestamps and the reconciliation contract. Moving to `production-single-server` MUST NOT be represented as retaining synchronous PostgreSQL failover durability.
