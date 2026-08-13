# ADR-0016: Anchor Notification Deadlines at Durable Acceptance

## Status

Accepted

## Date

2026-08-09

## Relationship to Earlier Decisions

This ADR extends ADR-0015 by defining the delivery-deadline clock anchor,
immutable acceptance timestamps, persisted effective deadlines, semantic
`message_not_after` requirements, and validation errors.

It does not change the channel retry schedules, deadline precedence, attempt
budgets, receipt-observation windows, or terminal-state rules established by
ADR-0013 through ADR-0015.

The authoritative clock source, timestamp precision, clock-skew handling, and
the contract representation of semantic time-bound classification remain
implementation decisions that require explicit approval before runtime work.

## Context

A retry worker must evaluate a stable delivery deadline after process restarts,
clock advancement, and repeated claims. Recomputing a relative deadline from
the current time would extend the useful delivery window and could deliver an
expired OTP, MFA, verification, or recovery message.

The caller also owns the business-validity horizon of time-bound content.
Notification must reject an absent, invalid, or already expired semantic
deadline before it durably accepts delivery responsibility.

## Decision

### Immutable acceptance time

Notification records `accepted_at` in the acceptance transaction that creates
the durable Notification state. `accepted_at` is immutable after commit.

The channel deadline is anchored to that value:

```text
SMS channel_deadline   = accepted_at + 2 minutes
Email channel_deadline = accepted_at + 5 minutes
```

The effective deadline is:

```text
effective_delivery_deadline = min(channel_deadline, message_not_after)
```

When `message_not_after` is optional and absent, the channel deadline is the
effective delivery deadline.

Notification persists the final `effective_delivery_deadline` in the same
acceptance transaction as `accepted_at` and the accepted Notification state.
The persisted value is immutable. Retry scheduling and evaluation read that
value; they must never recompute a relative deadline from the current time.

`message_not_after` can only shorten the channel delivery window. It cannot
extend the SMS or Email channel deadline.

### Semantic requirement

`message_not_after` is mandatory for semantic messages that are time-bound,
including:

- OTP;
- MFA;
- registration verification;
- recovery and reset.

It is optional for semantic notifications that do not expire. Whether a
semantic template is time-bound belongs to the approved Notification contract
and template definition; callers cannot downgrade a time-bound semantic message
to bypass this requirement.

### Validation and error contract

Notification applies these validation outcomes before durable acceptance:

| Condition | gRPC status | Stable error code |
| --- | --- | --- |
| Required `message_not_after` is absent | `INVALID_ARGUMENT` | `MESSAGE_NOT_AFTER_REQUIRED` |
| Timestamp is invalid | `INVALID_ARGUMENT` | `MESSAGE_NOT_AFTER_INVALID` |
| `message_not_after <= accepted_at` | `INVALID_ARGUMENT` | `MESSAGE_NOT_AFTER_EXPIRED` |

A request that fails any of these checks:

- does not commit Notification state;
- does not receive `ACCEPTED`;
- does not create a provider submission attempt;
- does not establish durable delivery responsibility in Notification.

The existing request, response, and gRPC metadata size limits remain in force.
Error descriptions must be sanitized and must not include recipient data,
rendered content, codes, or arbitrary request payloads.

### Transaction and retry invariants

The acceptance transaction atomically persists at least the accepted
Notification identity, `accepted_at`, `effective_delivery_deadline`, resolved
template version, encrypted-payload reference/ciphertext, and initial retry
state required by ADR-0012.

OpenBao Transit and other network I/O remain outside that database transaction.
No retry, process restart, reconciliation, or operator action can modify
`accepted_at` or extend `effective_delivery_deadline`.

### Implementation gate

This ADR resolves the deadline anchor and `message_not_after` validation
semantics. Runtime implementation remains gated on:

- authoritative time source, precision, and clock-skew behavior;
- contract representation for semantic time-bound classification;
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

- The delivery window cannot slide forward during retries or restarts.
- Caller business expiry can shorten but never lengthen channel policy.
- Time-bound messages fail before durable handoff when their semantic expiry is
  missing, malformed, or already expired.
- Persistence must enforce immutability of acceptance and deadline fields.
- Test clocks and boundary tests are required for equality, precision, restart,
  and retry scheduling behavior.

## Alternatives considered

### Anchor the deadline to the first provider attempt

Rejected because dispatch delay or worker outage would extend the delivery
window after Notification had already accepted responsibility.

### Recompute the deadline on every retry

Rejected because it creates a sliding deadline and can deliver expired
security-sensitive content.

### Let callers extend channel deadlines

Rejected because caller semantics may narrow platform delivery policy but may
not weaken it.

### Accept a time-bound message without `message_not_after`

Rejected because Notification would not know the caller-owned business-validity
horizon.

## Rollback or Migration Considerations

This ADR creates no runtime migration by itself.

Future schema changes must add immutable `accepted_at` and
`effective_delivery_deadline` fields through an expand-migrate-contract plan.
Rollback must preserve their committed values and must never derive a later
deadline from current time. Existing accepted rows must not be backfilled with
a deadline that lengthens their original delivery window.
