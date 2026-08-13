# ADR-0017: Use PostgreSQL as Authoritative Notification Time

## Status

Accepted

## Date

2026-08-09

## Relationship to Earlier Decisions

This ADR extends ADR-0016 by defining the authoritative Notification time
source, canonical timestamp precision, expiry comparison, clock-health
thresholds, and duplicate-request validation order.

It does not change ADR-0016's immutable acceptance timestamp, channel deadline,
persisted effective deadline, semantic `message_not_after` requirement, or
validation error contract. It also does not change the retry, observation,
lifecycle, or terminal-state policies established by ADR-0013 through ADR-0016.

## Context

Notification lifecycle decisions must remain stable across application pods,
restarts, and retries. JVM clocks and RPC arrival timestamps can differ between
pods and are not part of the durable acceptance transaction. PostgreSQL already
serializes the acceptance write and can provide the time used by that write.

Protobuf supports nanosecond precision while PostgreSQL lifecycle columns use
microsecond precision. Without one canonical conversion, a value can compare
differently before and after persistence. Clock synchronization failure can
also make a new time-bound handoff unsafe, but it must never extend a deadline
that was already committed.

Idempotent replay presents a separate ordering concern. Revalidating an already
accepted request against the current time would turn a successful durable
handoff into an expiry error after its original business deadline.

## Decision

### Authoritative lifecycle time

The Notification PostgreSQL clock is the authoritative time source for the
Notification lifecycle.

`accepted_at` is generated with PostgreSQL `clock_timestamp()` inside the
durable acceptance transaction. Caller timestamps, JVM wall clocks, and RPC
arrival timestamps are not authoritative acceptance times.

The caller-owned `message_not_after` remains a business deadline derived from a
previously persisted business expiry. A caller must not recompute that expiry
from its wall clock when handing the notification to Notification Service.

Canonical retry, deadline, and observation state transitions compare their
persisted timestamps with PostgreSQL `clock_timestamp()`. Individual
application-pod clocks must not decide those transitions.

### Canonical timestamp precision

All persisted lifecycle timestamps and all timestamps used in contract
comparisons use canonical UTC microsecond precision.

A valid Protobuf Timestamp with precision finer than one microsecond is
truncated downward to the preceding whole microsecond and is never rounded
upward. Validation and comparison occur after this canonicalization.

The following values are persisted as PostgreSQL `timestamptz(6)`:

- `accepted_at`;
- `message_not_after`, when present;
- `effective_delivery_deadline`;
- retry and receipt-observation timestamps.

### Expiry comparison and caller safety margin

No positive clock-skew grace is added to expiry validation. After canonical UTC
microsecond conversion:

```text
message_not_after <= accepted_at
```

is rejected before durable acceptance with `INVALID_ARGUMENT` and stable error
code `MESSAGE_NOT_AFTER_EXPIRED`, as defined by ADR-0016.

For expiring credentials, the caller sets:

```text
message_not_after = credential_expires_at - 5 seconds
```

The safety margin is caller-owned and narrows the delivery window. Notification
does not add a grace period or extend either the credential expiry or the
persisted effective delivery deadline.

### Clock synchronization health

Platform clocks use NTP or chrony synchronization. The operating thresholds
for estimated clock error are:

- target estimated error is at most 250 milliseconds;
- warning threshold is above 500 milliseconds;
- critical threshold is above 2 seconds.

When the authoritative Notification database clock exceeds the critical
synchronization threshold, a new time-bound submission fails with gRPC status
`UNAVAILABLE` and stable error code `TIME_SOURCE_UNHEALTHY`. Such a submission
is not durably accepted.

Existing persisted deadlines are never extended because of clock-health
degradation, clock recovery, retry, restart, reconciliation, or operator action.

The exact source and implementation used to estimate synchronization error for
the authoritative database clock, and the behavior of already accepted retry
and observation workers while the critical condition is active, require
separate explicit decisions before runtime implementation.

### Idempotent duplicate validation order

An idempotent duplicate `request_id` is resolved before current-time expiry
validation. A previously accepted intent returns its original acceptance or
stored result even when its original `message_not_after` has elapsed since
acceptance. It does not create a second delivery intent or recompute any
deadline.

This ordering applies to a duplicate that resolves to the previously accepted
intent. Reuse of a `request_id` with conflicting content remains an unresolved
contract decision from ADR-0012 and must not be inferred from this ADR.

### Observability and implementation gate

Notification must expose bounded operational telemetry for database time-source
health, estimated error, and the warning and critical threshold crossings.
Telemetry must not contain recipient data, message content, codes, raw request
payloads, or high-cardinality notification identifiers.

Runtime implementation remains gated on:

- the authoritative database clock-health measurement mechanism;
- retry and observation worker behavior during critical time-source health;
- attempt claim/lease recovery and safe provider reconciliation;
- `SubmitNotification` and result-callback deadlines, statuses, retries, and
  cancellation;
- conflicting `request_id` behavior;
- OpenBao Transit operational policy;
- caller authentication and Istio authorization;
- schema, indexes, cleanup, migration, and rollback;
- provider selection and provider-specific evidence authentication and
  idempotency behavior.

## Consequences

- Acceptance and lifecycle comparisons have one transactional time authority.
- Canonical microsecond truncation prevents pre-persistence and
  post-persistence boundary disagreement.
- No skew grace can accidentally deliver an already expired credential.
- A critically unsynchronized database clock prevents new time-bound durable
  handoffs instead of making an unsafe expiry decision.
- Idempotent replay remains stable after the original deadline passes.
- PostgreSQL clock health becomes a security-sensitive availability dependency
  for new time-bound submissions.

## Alternatives Considered

### Use the caller or JVM clock for `accepted_at`

Rejected because those clocks are outside the durable acceptance transaction
and can disagree across callers or pods.

### Round Protobuf timestamps to the nearest microsecond

Rejected because rounding upward can make a business deadline later than the
caller supplied.

### Add positive clock-skew grace

Rejected because grace would extend a security-sensitive expiry. Callers use
the explicit five-second safety margin instead.

### Revalidate duplicate accepted requests against current time

Rejected because an idempotent replay must return the outcome of the original
durable handoff rather than create a new expiry result.

### Extend existing deadlines after clock recovery

Rejected because persisted deadlines are immutable and must never become
sliding windows.

## Rollback or Migration Considerations

This ADR creates no runtime migration by itself.

Future schema changes must use `timestamptz(6)` for the specified lifecycle
columns and follow expand-migrate-contract. Mixed-version deployments must not
round timestamps upward, replace database-authoritative transition comparisons
with pod clocks, or extend an accepted deadline. Rollback must preserve the
original canonical timestamps and duplicate-request outcome.
