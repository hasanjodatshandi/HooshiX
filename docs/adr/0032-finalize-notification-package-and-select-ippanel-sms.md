# ADR-0032: Finalize Notification Package and Select IPPanel for Iran SMS

## Status

Accepted

## Date

2026-08-10

## Supersedes

This ADR supersedes only these parts of ADR-0030:

- the Twilio IE1/Dublin SMS-provider selection and its geography gate; and
- the assumption that a public provider-webhook adapter is a general v1
  requirement for every Notification provider.

ADR-0030 remains historical and unchanged. Its Email provider, PostgreSQL,
worker, fence, observability, and legacy-cutover decisions remain in force.
ADR-0012 through ADR-0015 and ADR-0029 continue to define exact-content retry,
provider ambiguity, delivery evidence, and terminal-state invariants.

## Context

The v1 launch market is Iran. Twilio is therefore not an eligible v1 SMS
provider. IPPanel Edge exposes a Pattern send API for OTP-style messages and
report APIs that can be polled by the provider correlation identifier. A
second active provider is not required in v1.

The Notification Java base package was also the last unresolved service
foundation input. The repository namespace is `com.sajtech`, and Identity uses
`com.sajtech.identity`; a `java.com.sajtech` package would conflict with the
accepted namespace and current source tree.

## Decision

### Service package

The Notification Service base Java package is `com.sajtech.notification` and
its Gradle group remains `com.sajtech`. The service follows the same independent
build/release ownership and DDD, Hexagonal, dependency-direction, constructor-
injection, package-size, ArchUnit, and quality-gate standards as Identity.

### SMS provider and geography

The only deployed v1 SMS provider is IPPanel Edge API:

| Property | Decision |
| --- | --- |
| Target geography | Iran (`+98`) |
| OTP submission | IPPanel Pattern SMS |
| Adapter | `ippanel-sms-adapter` inside `notification-service` |
| Authentication | Dedicated production API key/token stored in OpenBao |
| Recipient | Canonical E.164, including `+98...` for Iran |
| Credential rotation | 90 days |
| Provider receipt ingress | Polling/reconciliation through Report APIs |
| Provider idempotency | Treated as unsupported until explicitly proven |

Twilio is rejected for the Iran v1 deployment. Kavenegar is only a candidate
secondary adapter for a later decision; it is not deployed, configured,
credentialed, or used for automatic failover in v1. The provider port remains
an abstraction so a later accepted ADR can add another adapter without moving
provider concerns into callers.

### Submission acceptance and correlation

Only a successful IPPanel submission response containing a non-empty provider
outbox identifier is `DEFINITIVE_ACCEPTED`. The adapter normalizes that value
to Notification's canonical `provider_message_id`. IPPanel documentation uses
both singular `message_outbox_id` and plural `messages_outbox_id` in different
API surfaces; the exact submission-response field and type must be pinned by a
sandbox contract fixture before runtime enablement and must not leak into the
internal Protobuf contract.

An HTTP success without the required identifier is not
`DEFINITIVE_ACCEPTED`. A timeout, connection loss, malformed response, or
otherwise unresolved submission result remains `AMBIGUOUS`. Blind submission
retry after ambiguity is prohibited. Notification's durable handoff,
`request_id` idempotency, dispatch fence, and immutable attempt identity remain
the authoritative controls; they do not create provider-side exactly-once
delivery.

The vendor-reported behavior that an identical sender, recipient, and text
request within 40 seconds may return HTTP 429 is recorded as an adapter
contract-test input. It is not treated as provider idempotency, proof that an
earlier request was accepted, or permission to retry an ambiguous attempt. The
production adapter must verify this behavior against the pinned provider
contract/sandbox before depending on its exact interval or status mapping.

### Delivery evidence and reconciliation

IPPanel v1 has no assumed webhook. Notification polls the provider Report APIs
using the normalized provider message/outbox identifier. The authenticated
HTTPS response must correlate to the same Notification attempt before the
adapter maps it to a canonical outcome.

An outbox-level queued, sending, or sent state is not evidence of
`DELIVERED`. Only a recipient-level provider status whose pinned contract
unambiguously means delivery to the recipient may become the authenticated,
correlated delivery evidence required by ADR-0013. The currently documented
recipient `message_status = 2` is a candidate mapping that must be locked by
provider contract tests. Missing, contradictory, or inconclusive status ends
under the existing observation policy as `DELIVERY_STATUS_UNKNOWN`; it is
never inferred as delivered.

Polling follows the existing 12-hour SMS observation window, final
reconciliation, deadline, degraded-clock, and dispatch-fence rules. Polling
must not submit a message or reset an attempt budget.

### Provider-specific webhooks

`notification-provider-webhook-adapter` is not a universal component or a v1
SMS requirement. A webhook adapter is created only for a selected provider
that exposes a trustworthy signature/authentication contract, and only after
its ingress, replay, payload-bound, secret-rotation, WAF, and authorization
controls are approved and tested. Email may use such an adapter when its
selected provider contract satisfies those requirements. Notification Service
itself remains internal-only and has no public ingress.

### Unresolved Pattern rendering invariant

IPPanel Pattern SMS resolves provider-managed pattern content from a pattern
code and parameters. ADR-0012 and ADR-0029 require Notification to resolve,
render, encrypt, and reuse the exact provider-ready content and immutable
template version before durable acceptance.

This ADR does not silently weaken either rule. Production Pattern dispatch is
blocked until a separate explicit decision defines how IPPanel pattern content
is versioned, prevented from mutable out-of-band edits, drift-verified, and
represented in the encrypted exact-content escrow. Caller semantics remain
type-safe and provider-neutral while that decision is pending.

## Security and Verification Requirements

Implementation requires:

- an outbound-only IPPanel adapter with bounded connect/request timeouts and
  bounded response parsing selected before runtime implementation;
- OpenBao-backed credential delivery, least privilege, 90-day rotation, log
  redaction, and emergency revocation tests;
- Pattern request fixtures for E.164 `+98`, one recipient, approved pattern
  identifiers, and type-safe parameters;
- submission-response fixtures covering a valid outbox identifier, success
  without an identifier, malformed responses, timeout after dispatch, HTTP 429,
  definitive transient rejection, and definitive permanent rejection;
- polling fixtures for every documented report status, unknown status,
  contradictory evidence, observation expiry, and final reconciliation;
- proof that outbox-level sent status cannot produce `DELIVERED`;
- proof that an ambiguous submission never enters the blind retry schedule;
- secret/PII logging tests covering API tokens, recipients, codes, Pattern
  parameters, provider identifiers, and raw provider payloads; and
- architecture checks enforcing `com.sajtech.notification` and keeping
  provider-specific code outside Domain and Application.

Official integration references are the
[IPPanel Edge API](https://apidoc.ippanel.com/) and the
[Kavenegar REST API](https://kavenegar.com/rest.html). Provider behavior is
pinned by adapter contract fixtures rather than assumed from an unversioned
live documentation page.

## Consequences

- The Notification service foundation now has an unambiguous Java namespace.
- Iran v1 has one SMS provider and no active-active routing complexity.
- SMS delivery evidence is obtained without exposing public webhook ingress.
- Provider field names and status values remain isolated inside the IPPanel
  adapter.
- Pattern rendering cannot enter production until its mutability and exact-
  content conflict is explicitly resolved.

## Rollback or Migration Considerations

Before provider cutover, the IPPanel adapter and credentials can be disabled
without modifying accepted Notification records. After an IPPanel attempt is
authorized, rollback must preserve its correlation identifier, ambiguity,
receipt observation, terminal immutability, and escrow-erasure rules.

Rollback must not reactivate Twilio, add Kavenegar failover, create a public SMS
webhook, re-render an accepted Pattern with changed provider content, or blind-
retry an ambiguous attempt. Any provider replacement or dual-provider topology
requires another superseding ADR.
