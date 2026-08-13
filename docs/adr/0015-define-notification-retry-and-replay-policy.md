# ADR-0015: Define Notification Retry, Observation, and Replay Policy

## Status

Accepted

## Date

2026-08-09

## Relationship to Earlier Decisions

This ADR extends ADR-0014 with the initial Email and SMS submission-attempt
budgets, retry delays, channel delivery deadlines, receipt-observation windows,
deadline precedence, and operator replay constraints.

It does not change the provider-attempt outcome taxonomy, canonical lifecycle,
terminal-state immutability, or delivery-evidence rules established by
ADR-0013 and ADR-0014.

The clock anchor for the channel delivery deadline, validation and optionality
of caller-supplied `message_not_after`, claim-lease recovery, and concrete
provider reconciliation mechanism remain separate contract decisions.

## Context

Notification submission retry must be bounded independently by attempt count
and semantic delivery usefulness. Email and SMS have different latency and
receipt characteristics, while a caller may impose an earlier business expiry
for a verification, recovery, or other time-sensitive message.

Blindly retrying an ambiguous provider result risks duplicate delivery because
the provider may already have accepted the message. Operator action must not
become a bypass around the same limits or immutable terminal outcomes.

## Decision

### Submission-attempt budgets

Email and SMS each allow at most four provider submission attempts, including
the initial attempt. Therefore each channel permits at most three scheduled
submission retries.

Only `DEFINITIVE_TRANSIENT_FAILURE` is retryable. Neither
`DEFINITIVE_PERMANENT_FAILURE` nor `DEFINITIVE_ACCEPTED` is retried. An
`AMBIGUOUS` outcome is reconciled when the provider supports safe
reconciliation and must never be blindly resubmitted.

After a notification reaches `PROVIDER_ACCEPTED`, no further provider
submission attempt is permitted.

### Channel retry schedules

SMS uses:

```text
maximum attempts: 4, including the initial attempt
retry delays: 2 seconds, 10 seconds, 30 seconds
jitter: plus or minus 20 percent per retry delay
channel delivery deadline: 2 minutes
```

Email uses:

```text
maximum attempts: 4, including the initial attempt
retry delays: 5 seconds, 30 seconds, 120 seconds
jitter: plus or minus 20 percent per retry delay
channel delivery deadline: 5 minutes
```

The retry-delay sequence applies in order after consecutive definitive
transient non-acceptance outcomes. Jitter does not permit scheduling at or
after the effective delivery deadline.

### Effective delivery deadline

The effective delivery deadline is the earlier of:

- the channel delivery deadline; and
- the caller-supplied semantic `message_not_after` timestamp.

No attempt may be claimed at or after the effective deadline. A retry is not
scheduled when its jittered execution time would be at or after that deadline.

At every retry evaluation, deadline expiration takes precedence over attempt
budget exhaustion:

1. if the effective deadline has passed, transition to `EXPIRED`;
2. if the next retry would execute at or after the effective deadline,
   transition to `EXPIRED`;
3. otherwise, if all four attempts have been consumed, transition to
   `FAILED_PERMANENT` with `failure_category = RETRY_EXHAUSTED`.

These expiry transitions remain subject to ADR-0014: `EXPIRED` is valid only
before definitive provider acceptance when it is known that the provider did
not accept the message. Retry exhaustion is valid only without unresolved
provider ambiguity.

### Receipt observation and final reconciliation

After `PROVIDER_ACCEPTED`, submission retry is disabled and receipt observation
begins:

- SMS receipt observation lasts 12 hours;
- Email receipt observation lasts 72 hours.

At the end of the applicable observation window, Notification performs final
provider reconciliation. Authenticated conclusive evidence may produce
`DELIVERED` or `FAILED_PERMANENT` according to ADR-0013 and ADR-0014. If the
final provider status still cannot be established, the notification transitions
to `DELIVERY_STATUS_UNKNOWN`.

Final reconciliation must not submit the message again.

### Operator actions

Direct operator replay of a terminal Notification is prohibited.

A resend after terminal completion must be a new business-authorized delivery
intent created by the owning caller. It requires:

- a new `request_id`;
- a new `notification_id` assigned during durable handoff;
- all applicable quotas, authorization, validation, and security checks.

Operators may trigger reconciliation or recovery of stuck processing. Such
actions must not:

- submit a terminal Notification again;
- bypass the four-attempt budget;
- bypass the effective delivery deadline;
- mutate a terminal state;
- reuse the terminal Notification's identifiers as a new delivery intent.

### Implementation gate

This ADR resolves the initial numeric retry and observation policy. Runtime
implementation remains gated on the unresolved ADR-0012 and ADR-0014 decisions,
including:

- delivery-deadline clock anchor and `message_not_after` contract validation;
- attempt claim/lease recovery and safe provider reconciliation mechanics;
- `SubmitNotification` and result-callback deadlines, retries, statuses, and
  cancellation;
- conflicting `request_id` behavior;
- OpenBao Transit operational policy;
- caller authentication and Istio authorization;
- schema, indexes, cleanup, migration, and rollback;
- provider selection and provider-specific evidence authentication and
  idempotency behavior.

## Consequences

- Submission retries are bounded by both usefulness time and attempt count.
- Deadline precedence prevents a notification from becoming
  `RETRY_EXHAUSTED` when it has already become too late to deliver.
- Ambiguous submission never enters the blind retry schedule.
- Receipt observation can outlive the submission deadline without permitting a
  new submission attempt.
- Operators can repair processing and reconcile provider state but cannot
  create an ungoverned resend path.

## Alternatives considered

### Use one retry schedule for Email and SMS

Rejected because their delivery latency, provider behavior, and useful delivery
windows differ.

### Retry ambiguous provider outcomes

Rejected because an ambiguous attempt may already have been accepted and blind
resubmission can create duplicate delivery.

### Let attempt exhaustion win over deadline expiry

Rejected because the canonical reason is `EXPIRED` when time prevents another
valid attempt.

### Permit operator replay of a terminal notification

Rejected because it bypasses caller authorization, semantic quotas, fresh
identifiers, and terminal-state immutability.

## Rollback or Migration Considerations

This ADR creates no runtime schema or contract migration by itself.

Future persistence must retain the policy inputs and computed effective
deadline required to reproduce decisions for an accepted notification. Mixed
versions must never increase the attempt budget or extend an accepted
notification's effective deadline. Rollback must preserve terminal states and
must not make a previously terminal record retryable.
