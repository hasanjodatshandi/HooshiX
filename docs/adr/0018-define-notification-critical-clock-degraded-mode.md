# ADR-0018: Define Notification Critical-Clock Degraded Mode

## Status

Accepted

## Date

2026-08-09

## Relationship to Earlier Decisions

This ADR extends ADR-0017 by defining Notification worker behavior while the
authoritative PostgreSQL clock is critically unhealthy and the recovery gate
after clock health returns.

It does not change the lifecycle states, transition evidence, retry budgets,
delivery deadlines, receipt-observation deadlines, timestamp precision, or
terminal-state immutability established by ADR-0013 through ADR-0017. It also
does not select the mechanism used to measure authoritative database clock
health.

## Context

When the authoritative database clock exceeds the critical synchronization
threshold, Notification cannot safely start work whose eligibility depends on
the current time. Continuing to claim attempts, expire notifications, or start
scheduled receipt polling could make different decisions while the clock is
known to be unreliable.

Stopping every state update would create a different risk. A provider request
may already be in flight, and authenticated delivery evidence can arrive
without requiring a new deadline decision. Notification must be able to record
those conclusive facts and erase sensitive escrow at a terminal result.

Clock recovery must not act like a pause that grants extra delivery or
observation time. Every pending notification needs a fresh evaluation against
its original immutable deadlines before work resumes.

## Decision

### Entering degraded mode

When authoritative clock health is critical with
`TIME_SOURCE_UNHEALTHY`, Notification enters degraded mode.

While degraded, none of the following work may start:

- a new provider dispatch from `ACCEPTED`;
- a retry provider dispatch from `RETRY_WAIT`;
- deadline-driven expiration;
- scheduled receipt-status polling.

No worker may claim a new provider attempt while this mode is active. This
restriction applies even when a previously computed schedule says that work is
due.

Persisted `message_not_after`, effective delivery deadlines, retry budgets, and
receipt-observation deadlines remain immutable. They are not paused, shifted,
recomputed, replenished, or extended during degraded mode.

ADR-0017's behavior for new time-bound submissions remains in force: they fail
with `UNAVAILABLE / TIME_SOURCE_UNHEALTHY` and are not durably accepted. This
ADR does not select additional acceptance behavior for new non-time-bound
submissions.

### Work allowed during degraded mode

A provider attempt that was already in flight before degraded mode began may
complete. Its definitive provider outcome may be persisted according to the
existing ADR-0014 transition rules. Persisting that outcome does not authorize
a new dispatch or retry while degraded mode remains active.

Authenticated provider evidence remains processable while clock health is
critical. Therefore evidence-backed transitions from `PROVIDER_ACCEPTED` to
`DELIVERED` or `FAILED_PERMANENT` remain permitted. The evidence authentication,
correlation, and canonical mapping requirements from ADR-0013 still apply.

Result callbacks that do not depend on a wall-clock decision may continue.
Callback processing remains idempotent, uses only the non-PII result outbox, and
does not restore or retain sensitive escrow merely because clock health is
critical.

This ADR makes no new classification or persistence rule for an ambiguous
in-flight provider outcome. The existing provider-ambiguity rules remain in
force and must not be weakened by degraded-mode handling.

### Recovery gate

After authoritative clock health recovers, every pending notification is
re-evaluated against its original persisted deadlines using PostgreSQL
`clock_timestamp()` before any dispatch, retry, expiration, or scheduled
receipt-observation work resumes.

Recovery evaluation follows these state-specific rules:

- an `ACCEPTED` or `RETRY_WAIT` notification whose effective delivery deadline
  has elapsed transitions to `EXPIRED` without another provider attempt;
- `PROVIDER_ACCEPTED` never transitions to `EXPIRED`;
- when a `PROVIDER_ACCEPTED` notification's persisted receipt-observation
  deadline has elapsed, Notification performs final reconciliation;
- if final reconciliation cannot establish a conclusive outcome, the
  notification transitions to `DELIVERY_STATUS_UNKNOWN`.

If the applicable persisted deadline has not elapsed, work may resume only
under the original retry budget, attempt schedule, observation policy, and
transition invariants. Clock recovery creates no replacement budget or new
delivery intent.

### Transaction and safety invariants

Recovery evaluation and any resulting lifecycle transition use the
authoritative PostgreSQL clock and a local Notification transaction. Provider
I/O and callback network I/O remain outside database transactions.

Terminal-state immutability remains in force. Recovery or late evidence must
not make a terminal notification dispatchable again or rewrite one terminal
state into another.

### Observability and implementation gate

Notification must expose bounded telemetry for entry into degraded mode,
duration in degraded mode, prohibited work starts, recovery evaluation counts,
and resulting canonical states. Labels must remain low-cardinality and must not
contain recipient data, content, codes, raw payloads, `request_id`, or
`notification_id`.

Runtime implementation remains gated on:

- the authoritative database clock-health measurement mechanism;
- degraded-mode coordination and worker fencing across Notification replicas;
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

- No new time-dependent provider work begins while the authoritative clock is
  critically unhealthy.
- In-flight definitive facts and authenticated evidence are not discarded.
- Clock outages reduce availability but never grant extra credential,
  delivery, retry, or observation lifetime.
- Recovery may immediately expire queued pre-acceptance notifications or
  finalize overdue receipt observation.
- Multi-replica degraded-mode coordination becomes a production correctness
  requirement.

## Alternatives Considered

### Continue dispatch using persisted schedule times

Rejected because deciding whether a schedule is due still requires a trusted
current-time comparison.

### Pause and extend deadlines by outage duration

Rejected because it would lengthen caller-owned business expiry and platform
delivery policy.

### Discard outcomes from attempts already in flight

Rejected because a provider may have definitively accepted or rejected the
message before the clock-health transition.

### Reject authenticated provider evidence while degraded

Rejected because conclusive evidence does not require starting a new
time-dependent provider operation and may permit timely terminal escrow erasure.

### Expire `PROVIDER_ACCEPTED` after recovery

Rejected because ADR-0014 prohibits `EXPIRED` after definitive provider
acceptance. Overdue inconclusive observation resolves through final
reconciliation and `DELIVERY_STATUS_UNKNOWN`.

## Rollback or Migration Considerations

This ADR creates no runtime migration by itself.

Future mixed-version rollouts must prevent any replica that lacks the degraded
mode gate from claiming new work while the authoritative clock is critical.
Rollback must preserve original deadlines and budgets and must not replay work
that a recovery evaluation already made terminal. Deployment and rollback need
a fencing-compatible sequence once the coordination mechanism is approved.
