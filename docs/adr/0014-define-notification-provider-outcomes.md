# ADR-0014: Define Notification Provider Outcomes and State Transitions

## Status

Accepted

## Date

2026-08-09

## Relationship to Earlier Decisions

This ADR extends ADR-0013 with provider-attempt outcome classifications and the
approved lifecycle transitions. It does not change the canonical state list,
terminal-state list, terminal immutability, or delivery-evidence requirements
defined by ADR-0013.

`RETRY_EXHAUSTED` is not a lifecycle state. It is a `failure_category` for
`FAILED_PERMANENT` under the conditions defined below.

Exact retry counts, delivery and observation deadline durations, backoff,
deadline-versus-budget tie breaking, claim leases, and operator replay remain
separate decisions.

## Context

Provider submission results must distinguish definitive acceptance, definitive
non-acceptance, and ambiguity. A retry budget can safely produce a permanent
failure only when every relevant attempt outcome is known. If an attempt may
have been accepted, classifying budget exhaustion as a definitive failure would
hide possible delivery and could lead callers or operators to retry with unsafe
assumptions.

The lifecycle also needs to distinguish expiry before provider acceptance from
an accepted message whose final receipt never becomes conclusive.

## Decision

### Provider-attempt outcome classifications

Every provider attempt outcome is classified as exactly one of:

```text
DEFINITIVE_ACCEPTED
DEFINITIVE_TRANSIENT_FAILURE
DEFINITIVE_PERMANENT_FAILURE
AMBIGUOUS
```

These classifications describe the outcome of a provider attempt. They are not
additional Notification lifecycle states.

### Pre-acceptance transitions

The approved transitions are:

- `ACCEPTED -> DISPATCHING` when an attempt is claimed before the delivery
  deadline;
- `DISPATCHING -> PROVIDER_ACCEPTED` for `DEFINITIVE_ACCEPTED`;
- `DISPATCHING -> RETRY_WAIT` for `DEFINITIVE_TRANSIENT_FAILURE`;
- `DISPATCHING -> FAILED_PERMANENT` for
  `DEFINITIVE_PERMANENT_FAILURE`;
- `DISPATCHING -> DELIVERY_STATUS_UNKNOWN` for an unresolved `AMBIGUOUS`
  submission.

`RETRY_WAIT -> DISPATCHING` is allowed only while both the retry budget and the
delivery deadline remain available.

When no further attempt is allowed:

- the notification terminates as `EXPIRED` when the delivery deadline is the
  limiting condition;
- the notification terminates as `FAILED_PERMANENT` with
  `failure_category = RETRY_EXHAUSTED` when the retry budget is exhausted first
  and no unresolved ambiguous provider outcome exists;
- any unresolved ambiguity requires `DELIVERY_STATUS_UNKNOWN`, never
  `FAILED_PERMANENT` with `RETRY_EXHAUSTED`.

`EXPIRED` is permitted only before definitive provider acceptance and only when
it is known that the provider has not accepted the message.

### Post-acceptance transitions

`PROVIDER_ACCEPTED` can transition only to:

```text
DELIVERED
FAILED_PERMANENT
DELIVERY_STATUS_UNKNOWN
```

The terminal outcome rules are:

- `DELIVERED` requires the authenticated, correlated, delivery-confirming
  provider evidence required by ADR-0013;
- `FAILED_PERMANENT` requires a definitive non-retryable outcome or retry
  exhaustion without ambiguity;
- `DELIVERY_STATUS_UNKNOWN` is reserved for an unresolved ambiguous submission
  or an accepted message whose final provider status cannot be established by
  the receipt observation deadline.

All terminal states remain immutable.

### `RETRY_EXHAUSTED` invariant

`RETRY_EXHAUSTED` is a `failure_category` associated with the canonical state
`FAILED_PERMANENT`. It must not be emitted as a lifecycle state or terminal
status.

The category is valid only when:

1. the configured retry budget is exhausted; and
2. there is no unresolved ambiguous provider outcome for the notification.

If either the current attempt or a prior relevant attempt remains ambiguous,
the terminal state is `DELIVERY_STATUS_UNKNOWN` instead.

The representation and compatibility rules for `failure_category` in Protobuf
and persistence remain part of the pending contract and schema design. Other
failure categories must not be invented during implementation.

### Implementation gate

This ADR resolves the outcome taxonomy and transition semantics but does not
set operational numeric values. Runtime implementation remains gated on the
remaining ADR-0012 decisions, including:

- retry budget, delivery deadline, receipt observation deadline, backoff,
  claim/lease behavior, tie breaking, and operator replay;
- `SubmitNotification` and result-callback deadlines, retries, statuses, and
  cancellation;
- conflicting `request_id` behavior;
- OpenBao Transit operational policy;
- caller authentication and Istio authorization;
- schema, indexes, cleanup, migration, and rollback;
- provider selection and provider-specific evidence authentication and
  idempotency behavior.

## Consequences

- Provider ambiguity can never be collapsed into retry exhaustion or permanent
  non-delivery.
- `EXPIRED` proves that definitive provider acceptance did not occur before the
  delivery deadline.
- `PROVIDER_ACCEPTED` cannot return to dispatch or retry states.
- State consumers can distinguish the permanent state from its retry-exhaustion
  reason without adding another terminal state.
- Exact retry and deadline values remain explicit production inputs.

## Alternatives considered

### Add `RETRY_EXHAUSTED` as a terminal state

Rejected because retry exhaustion is a reason for `FAILED_PERMANENT`, not a
distinct canonical lifecycle state.

### Map ambiguous submission to retry exhaustion

Rejected because the provider may have accepted the message. The correct
terminal state is `DELIVERY_STATUS_UNKNOWN` while that ambiguity is unresolved.

### Expire a provider-accepted notification

Rejected because `EXPIRED` is restricted to known pre-acceptance expiry. An
accepted message without conclusive final receipt becomes
`DELIVERY_STATUS_UNKNOWN` at its observation deadline.

## Rollback or Migration Considerations

This ADR creates no runtime schema or contract migration by itself.

Future migrations must keep `RETRY_EXHAUSTED` out of the canonical status enum,
preserve unknown enum values during mixed-version rollout, and reject any
transition that would overwrite an immutable terminal state. Rollback must not
reinterpret an ambiguous outcome as definitive failure or delivery.
