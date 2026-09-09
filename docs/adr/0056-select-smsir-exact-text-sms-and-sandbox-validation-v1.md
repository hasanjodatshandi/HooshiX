# ADR-0056: SMS.ir Exact-Text SMS and Sandbox Validation v1

## Status

Accepted — current effective decision

## Date

2026-09-09

## Decision

### Provider boundary

SMS.ir replaces IPPanel as the selected Iran SMS provider and fully supersedes ADR-0020. Notification
remains the sole semantic template and rendering authority. The production-capable adapter sends one
canonical `+98` recipient and the exact immutable accepted text through the official SMS.ir bulk-send
contract:

```text
POST https://api.sms.ir/v1/send/bulk
X-API-KEY: <secret>
Content-Type: application/json

lineNumber:  server-owned approved sender line
messageText: exact Notification-rendered content
mobiles:     exactly one canonical recipient
```

Provider-managed Verify templates are not a production semantic authority. Callers cannot select the
provider, endpoint, sender line, template, or API key.

### Authentication and configuration

- the API base URI is fixed to `https://api.sms.ir`; configuration cannot replace its scheme, host,
  port, or path;
- the production sender line is a validated positive decimal value and remains server-owned;
- the API key is read from mounted secret-file state, never Git, Helm values, command arguments,
  ordinary environment variables, logs, traces, metrics, or evidence;
- production obtains the dedicated credential from OpenBao/External Secrets and rotates/revokes it
  under the current secret policy;
- provider egress is limited to the reviewed HTTPS destination and no HTTP redirect is followed.

### Submission outcomes

The adapter uses a 500 ms connection timeout, 1,500 ms total request timeout, a 64 KiB response bound,
and no HTTP-client retry. The Notification durable retry owner remains the only submission-retry owner.

`DEFINITIVE_ACCEPTED` requires HTTP 200, integral provider `status=1`, exactly one positive integral
`data.messageIds` value, and records that message ID as the correlation identity. A `null` or zero
single message ID is a definitive recipient/content rejection under the published contract. HTTP 429,
HTTP 5xx, provider status `0`, and provider status `20` are definitive transient failures. Other
explicit HTTP/business rejections are definitive permanent failures. Timeout, connection loss,
oversized/malformed response, or any unproven acceptance is `AMBIGUOUS` and is never blindly resent.

### Delivery evidence

Only the authenticated message-specific report is delivery evidence:

```text
GET https://api.sms.ir/v1/send/{messageId}
```

The returned `data.messageId` must exactly match the attempt correlation identity. The published
`deliveryState` values map as follows:

```text
1       -> DELIVERED
2/4/6/7 -> FAILED_PERMANENT
3/5     -> PENDING
null    -> PENDING within the observation window
unknown, malformed, mismatched, or missing -> INCONCLUSIVE
```

Bulk/pack acceptance, a message ID alone, or SMS.ir Sandbox output never proves delivery. Polling is
bounded by the existing 12-hour SMS observation policy and never submits a message.

### Sandbox boundary

The owner-selected current staging credential is an SMS.ir Sandbox key. The official Sandbox uses
the same API shape with simulated data, makes no real delivery, charges no credit, and retains no
report. Its only predefined Verify template is ID `123456` with the `Code` parameter.

The repository sandbox probe therefore calls `POST /v1/send/verify` only to verify TLS hostname,
authentication, input validation, the simulated-success response shape, and explicit authentication
failure. Its aggregate receipt contains no API key, phone number, message ID, response body, or other
identifier and always records `real_delivery_claimed=false`. Sandbox success cannot activate the
production exact-text delivery runtime, satisfy recipient-delivery evidence, or establish Production
readiness.

### Google OIDC separation

Google OIDC is a separate optional Web BFF end-user login method. It is unrelated to SMTP Email or
SMS.ir and is not a Notification-provider evidence requirement. Its owner-approved local staging
configuration, when present, uses a separate Google OAuth client Secret and exact localhost callback;
it never consumes the SMS.ir or Gmail SMTP credential. Disabling it does not weaken local-password
authentication or provider testing.

## Verification requirements

Verify fixed endpoint/sender configuration, exact-key parsing, one canonical recipient, exact text,
header authentication, bounded response parsing, published response/status fixtures, no redirect or
transport retry, ambiguity safety, correlation equality, delivery-state mapping, PII-safe telemetry,
restricted deployment egress, and inability of the local logging adapter or Sandbox Verify path to
activate as production delivery. Run the live Sandbox probe with a mode-`0600` owner file and retain
only its identifier-free aggregate receipt outside Git.

Production enablement additionally requires a production SMS.ir key, approved sender line, bounded
recipient authorization, real acceptance/delivery/failure/reconciliation evidence, credential
revocation evidence, and the unchanged Notification lifecycle/security gates.

## Rollback considerations

Rollback disables SMS delivery and revokes the SMS.ir credential. It must preserve accepted attempt
identity, exact content, ambiguity/reconciliation, observation, and terminal-state invariants. It may
not restore IPPanel as current authority, switch production semantics to Verify templates, fabricate
Sandbox delivery, blindly resend an ambiguous attempt, or activate a local logging adapter outside
local development.
