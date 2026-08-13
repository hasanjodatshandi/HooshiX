# ADR-0013: Define Notification Lifecycle and Delivery Evidence

## Status

Accepted

## Date

2026-08-09

## Relationship to Earlier Decisions

This ADR resolves the canonical status names, terminal-state classification,
and delivery-evidence requirements left open by ADR-0012. It does not replace
ADR-0012's durable handoff, encrypted retry escrow, terminal erasure, result
outbox, callback, idempotency, or provider-ambiguity rules.

The exact non-terminal transition graph, retry and backoff schedule, expiry
thresholds, permanent-failure classification, and operator replay policy remain
separate decisions and must not be inferred from this ADR.

## Context

Durable acceptance by Notification Service, submission to a provider, and
delivery to the recipient are different facts. Treating a successful provider
submission request or a provider message identifier as proof of delivery would
produce false delivery outcomes and could cause caller workflows to trust an
event that did not occur.

Provider delivery evidence also differs by channel. Email providers can confirm
acceptance by a destination mail system without proving that a person opened or
read the message. SMS providers can return submission acceptance before a later
delivery receipt establishes delivery to the destination network or device.

The lifecycle therefore needs one canonical vocabulary, immutable terminal
outcomes, and an evidence rule that fails to an explicit unknown outcome rather
than inferring success.

## Decision

### Canonical lifecycle

The canonical Notification lifecycle contains exactly these states:

```text
ACCEPTED
DISPATCHING
RETRY_WAIT
PROVIDER_ACCEPTED
DELIVERED
FAILED_PERMANENT
EXPIRED
DELIVERY_STATUS_UNKNOWN
```

The terminal states are:

```text
DELIVERED
FAILED_PERMANENT
EXPIRED
DELIVERY_STATUS_UNKNOWN
```

Every terminal state is immutable. A notification in a terminal state cannot
transition to another state, including when later provider information arrives.
Terminal-state persistence continues to trigger the ciphertext-erasure and
non-PII result-outbox transaction defined by ADR-0012.

`ACCEPTED` retains the ADR-0012 meaning: Notification has durably accepted
responsibility after encryption and local commit. It is not evidence of
provider acceptance or delivery.

`PROVIDER_ACCEPTED` means only that the provider accepted the message for its
own processing. It is non-terminal and never means that the message was
delivered.

### Evidence required for `DELIVERED`

A transition to `DELIVERED` is allowed only when all of these conditions hold:

- Notification receives authenticated provider delivery evidence;
- the evidence is correlated to the `provider_message_id` belonging to the same
  `notification_id`;
- the channel-specific provider adapter validates the evidence and maps it to
  the canonical delivery-confirmed outcome.

None of the following is sufficient evidence for `DELIVERED`:

- an HTTP success response from provider submission;
- provider submission acceptance;
- a queued or sent status;
- the existence of a `provider_message_id` by itself.

The authentication mechanism, evidence schema, retention, and storage
classification are provider-specific decisions that remain required before a
provider adapter is implemented. Provider evidence and identifiers remain
subject to the existing PII, secret, bounded-payload, and logging rules.

### Channel meaning

For Email, `DELIVERED` means that the provider confirms acceptance of the
message by the destination mail system. It does not mean that the recipient
opened or read the message.

For SMS, only a valid provider delivery receipt that satisfies the authenticated
and correlated evidence rule permits `DELIVERED`.

When conclusive delivery or failure evidence is unavailable by the end of the
approved observation and reconciliation lifecycle, the notification eventually
becomes `DELIVERY_STATUS_UNKNOWN`. It must never be promoted to `DELIVERED` by
inference. The duration and mechanics of that lifecycle remain pending.

### Implementation gate

This ADR defines the status vocabulary and delivery-evidence invariant only. It
does not authorize runtime implementation until the remaining ADR-0012 gates
are resolved, including:

- allowed transitions among `ACCEPTED`, `DISPATCHING`, `RETRY_WAIT`, and
  `PROVIDER_ACCEPTED`;
- exact entry conditions for `FAILED_PERMANENT`, `EXPIRED`, and
  `DELIVERY_STATUS_UNKNOWN`;
- retry count, backoff, observation windows, expiry, reconciliation, and
  operator replay behavior;
- `SubmitNotification` and callback deadlines, cancellation, and retryable
  gRPC statuses;
- request-conflict behavior, Transit operational policy, caller authentication,
  Istio authorization, schema details, and provider selection.

## Consequences

- Durable handoff, provider acceptance, and delivery confirmation cannot be
  conflated in contracts, persistence, callbacks, metrics, or operator views.
- Delivery reporting is conservative: lack of proof produces
  `DELIVERY_STATUS_UNKNOWN`, not a fabricated success.
- A late receipt cannot mutate a terminal unknown or failure into delivered;
  immutable history and callback idempotency remain consistent.
- Provider adapters must authenticate, validate, correlate, and canonically map
  channel-specific evidence before reporting delivery.
- Email delivery does not imply open/read tracking.

## Alternatives considered

### Treat successful provider submission as delivery

Rejected because submission acceptance proves only that the provider accepted a
request, not that the destination accepted the message.

### Treat any provider message identifier as delivery

Rejected because an identifier supports correlation but is not delivery
evidence.

### Infer delivery after a quiet period

Rejected because absence of a failure receipt is not proof of delivery. The
canonical outcome is `DELIVERY_STATUS_UNKNOWN` when evidence remains
inconclusive.

### Allow terminal-state correction after a late receipt

Rejected because terminal states are immutable. Later evidence may be retained
as bounded non-sensitive operational evidence under a future policy, but it
cannot rewrite the canonical outcome.

## Rollback or migration considerations

This ADR creates no runtime schema or contract migration by itself.

When implemented, status fields must use a versioned contract and an
expand-migrate-contract database change. Mixed-version deployments must not map
`PROVIDER_ACCEPTED` to `DELIVERED` or mutate a terminal state. Rollback must
preserve unknown enum values and all terminal-state immutability guarantees.
