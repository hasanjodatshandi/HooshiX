# ADR-0047: Simplify Notification Clock Safety and Remove the Dispatch Fence v1

## Status

Accepted

## Date

2026-08-10

## Supersedes

For current v1 Notification runtime, this ADR supersedes the application-level clock-health/degraded-mode/agent/primary-binding/dispatch-fence mechanisms introduced by ADR-0018 through ADR-0023 and finalized by ADR-0031, plus the related fence fields in ADR-0030.

ADR-0017 PostgreSQL-authoritative lifecycle time, UTC-microsecond precision, immutable accepted/deadline timestamps, and no positive expiry grace remain accepted. ADR-0013 through ADR-0016 retry/lifecycle/evidence rules remain accepted.

## Context

The Chrony sidecar, root `hostPath`, two-second gRPC health loop, primary identity binding, fence coordinator, epoch/re-arm generation, and row-lock fence protocol create substantial code, privileged-node integration, testing, and on-call complexity.

They protect against a clock/failover edge case but do not make expired credentials usable: Identity remains authoritative for credential expiry. With synchronous PostgreSQL HA and a durable `DISPATCHING` commit before provider I/O, the main duplicate-send/failover risk can be handled much more simply.

## Decision

The current v1 runtime does not deploy:

- `clock-health-agent`;
- Chrony socket `hostPath` sidecar;
- Notification clock-health gRPC polling;
- clock-health application degraded mode;
- Notification dispatch-fence row/epoch;
- FenceCoordinator/heartbeat/re-arm generation.

Infrastructure nodes still use NTP/Chrony and expose bounded platform clock-health telemetry. Warning >500ms estimated error and critical >2s are operational alerts, not an application request authorization protocol. An unhealthy PostgreSQL node is handled by platform/database operations and CloudNativePG failover policy.

### Time semantics

Notification continues to use PostgreSQL `clock_timestamp()` for acceptance and lifecycle comparisons. Persisted `accepted_at`, `message_not_after`, and effective deadlines remain `timestamptz(6)`, immutable, and never extended after restart/failover.

Caller credential expiry remains authoritative. A clock anomaly cannot extend OTP/MFA validity in Identity; at worst it can cause a message to be sent too late/early, which is an availability/UX failure rather than an authorization grant.

### Dispatch transaction

Immediately before external provider I/O, the worker performs one short local PostgreSQL transaction that:

1. locks/reloads the attempt;
2. uses PostgreSQL-authoritative current time;
3. validates non-terminal state and effective deadline;
4. persists immutable attempt/execution identity;
5. transitions to `DISPATCHING`;
6. commits durably before any provider I/O.

Provider I/O occurs after commit and never inside the transaction.

After `DISPATCHING`, process crash, lease expiry, database failover, timeout, or unknown provider outcome never permits blind redispatch. The attempt enters the existing reconciliation/ambiguity path.

CloudNativePG synchronous quorum from ADR-0048 must preserve acknowledged `DISPATCHING` state across permitted automatic failover; otherwise failover is refused in favor of durability.

## Verification Requirements

Boundary timestamp tests, crash before/after commit, stale claim, provider timeout/ambiguity, CloudNativePG failover around dispatch commit, no blind redispatch, no remote I/O in transaction, and proof that removed sidecar/fence components are absent from production desired state.

## Consequences

This removes the largest bespoke operational subsystem in Notification, removes a root/hostPath sidecar and continuous control RPCs, and substantially reduces implementation/on-call complexity without weakening credential verification or provider ambiguity safety.
