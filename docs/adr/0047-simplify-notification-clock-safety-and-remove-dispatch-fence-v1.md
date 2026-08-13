# ADR-0047: Notification Clock Safety and Dispatch Commit v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

## Decision

Notification uses a deliberately simple clock/failover safety model.

### Prohibited runtime mechanisms

The current v1 runtime MUST NOT deploy or depend on a bespoke application clock-health control plane, Chrony socket `hostPath` sidecar, per-request/continuous clock-health RPC, dispatch-fence row/epoch, fence coordinator, heartbeat generation, or re-arm protocol.

Infrastructure nodes still use NTP/Chrony and expose bounded platform clock-health telemetry. Warning above 500ms estimated error and critical above 2s are platform operational alerts, not application authorization gates.

### Time semantics

Notification uses PostgreSQL `clock_timestamp()` for acceptance and lifecycle comparisons. Persisted `accepted_at`, `message_not_after`, and effective deadlines use canonical UTC microsecond precision, are immutable after acceptance, and are never extended by retry, restart, reconciliation, or failover.

Caller credential expiry remains authoritative. A clock anomaly cannot extend OTP/MFA validity in Identity.

### Dispatch transaction

Immediately before external provider I/O, the worker performs one short local PostgreSQL transaction that:

1. locks/reloads the attempt;
2. reads PostgreSQL-authoritative current time;
3. validates non-terminal state and effective deadline;
4. persists immutable attempt/execution identity;
5. transitions to `DISPATCHING`;
6. commits durably.

Provider I/O starts only after commit and never occurs inside the transaction.

After `DISPATCHING`, process crash, lease expiry, database failover, timeout, or unknown provider result never permits blind redispatch. The attempt enters reconciliation under the current ambiguity/evidence rules.

CloudNativePG synchronous required durability must preserve acknowledged `DISPATCHING` state across every permitted automatic failover; when durability cannot be proven, failover is refused rather than risking duplicate delivery.

## Verification requirements

Test boundary timestamps, canonical microsecond truncation, crash immediately before/after dispatch commit, stale claim behavior, provider timeout/ambiguity, CloudNativePG failover around dispatch commit, no blind redispatch, no remote I/O in the transaction, and absence of prohibited clock/fence components from production desired state.

## Rollback considerations

Rollback MUST NOT reintroduce bespoke clock/fence mechanisms or permit redispatch of an already committed `DISPATCHING` attempt. Database/application rollback must preserve immutable accepted/deadline timestamps and the reconciliation contract.
