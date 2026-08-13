# Notification Service Architecture

## 1. Ownership and package

Notification Service is an independent internal platform service at
`services/notification-service` with base package `com.sajtech.notification`.

It owns semantic template resolution/rendering, localization, Email/SMS provider
adapters, delivery status, bounded retry policy, provider-specific telemetry,
durable Notification persistence, encrypted retry escrow, and result callbacks.

Callers retain business workflow/domain state, recipient source data,
authorization, and semantic intent. Notification never reads another service's
database.

## 2. Internal-only boundary

Notification has no public application ingress. Callers use versioned gRPC +
Protobuf over Istio Ambient strict mTLS/workload identity.

For Identity v1:

- `SubmitNotification`: Notification port 9090, only `identity-service` allowed;
- `ReportNotificationResult`: Identity callback port 9091, only
  `notification-service` allowed;
- no JWT/API key/application TLS/waypoint for these L4-only v1 paths.

Before adding another caller to the same L4-only submission port, caller-to-
semantic authorization must be redesigned/reviewed.

## 3. Durable handoff

`SubmitNotification` is idempotent. Caller supplies stable `request_id`;
Notification assigns stable `notification_id`.

`ACCEPTED` means durable responsibility transfer only after validation,
template/version resolution, exact rendering, encryption, and PostgreSQL
commit. It is not provider acceptance or delivery.

```text
Deadline:        900 ms
Attempts:        1
Wait-for-ready:  off
Automatic retry: none
```

Unknown outcomes are recovered by durable replay of the same `request_id`, not
transport retry.

Conflict detection uses `UNIQUE(caller_service, request_id)` plus the versioned
HMAC-SHA-256 intent fingerprint from ADR-0029. Equal replay returns the original
outcome; conflicting content returns `ALREADY_EXISTS / REQUEST_ID_CONFLICT`.
Duplicate resolution occurs before current-time validation.

## 4. Lifecycle and provider outcomes

Canonical states:

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

Terminal states are `DELIVERED`, `FAILED_PERMANENT`, `EXPIRED`, and
`DELIVERY_STATUS_UNKNOWN`; terminal states are immutable.

Provider-attempt classifications:

```text
DEFINITIVE_ACCEPTED
DEFINITIVE_TRANSIENT_FAILURE
DEFINITIVE_PERMANENT_FAILURE
AMBIGUOUS
```

`RETRY_EXHAUSTED` is a failure category, never a lifecycle state. Ambiguous
provider submission is never blindly retried.

## 5. Delivery evidence

`PROVIDER_ACCEPTED` is not `DELIVERED`.

`DELIVERED` requires authenticated, correlated, channel-specific provider
receipt evidence bound to the same provider/notification attempt. Submission
success or existence of a provider identifier alone is insufficient.

Email delivery means destination mail-system acceptance, not human open/read.
Inconclusive final evidence becomes `DELIVERY_STATUS_UNKNOWN`, never inferred
success.

## 6. Retry and observation

Email and SMS each allow at most 4 provider submission attempts including the
initial attempt.

```text
SMS:   retry 2s, 10s, 30s ±20%; delivery deadline 2m; observation 12h
Email: retry 5s, 30s, 120s ±20%; delivery deadline 5m; observation 72h
```

Only definitive transient failure follows the retry schedule. Provider
acceptance and ambiguous outcomes are not submission-retried.

Operator replay of a terminal Notification is prohibited. A resend is a new
caller-authorized intent with new IDs.

## 7. PostgreSQL-authoritative time

ADR-0047 removes the bespoke application clock-health control plane but keeps
ADR-0017's time semantics.

`accepted_at` and lifecycle transition time use PostgreSQL `clock_timestamp()`.
Contract/persistence comparison precision is canonical UTC microseconds; finer
Protobuf timestamps truncate downward, never round upward.

```text
SMS channel_deadline   = accepted_at + 2m
Email channel_deadline = accepted_at + 5m
effective_delivery_deadline = min(channel_deadline, message_not_after)
```

Time-bound semantic messages require `message_not_after`. Callers use the
accepted credential-expiry minus 5-second safety margin. Persisted acceptance
and effective deadlines are immutable and never extended by retry, restart,
reconciliation, or failover.

## 8. Simplified dispatch safety

The v1 runtime does **not** deploy the former `clock-health-agent`, Chrony
`hostPath` sidecar, 2-second health RPC, database dispatch fence,
FenceCoordinator, or re-arm generation from ADR-0018 through ADR-0023/ADR-0031.
Those current-v1 mechanisms are superseded by ADR-0047.

Immediately before provider I/O, Notification performs a short local transaction
that locks/reloads the attempt, uses PostgreSQL-authoritative time, checks
lifecycle/deadline eligibility, persists immutable execution identity and
`DISPATCHING`, and commits.

Provider I/O starts only after commit. No network I/O occurs inside the
transaction.

After `DISPATCHING`, worker crash/lease expiry/failover/unknown provider result
never authorizes blind redispatch. The attempt enters reconciliation under the
existing ambiguity/evidence rules.

## 9. PostgreSQL HA and persistence

Notification owns the dedicated PostgreSQL database `notification` on its own dedicated production CloudNativePG cluster. ADR-0057 supersedes the earlier production shared-cluster allowance from ADR-0053; ADR-0064/ADR-0067 standardize the dedicated-cluster fleet, backup/restore evidence, and upgrade safety. Notification-only runtime/migration roles have no privileges on other service databases or clusters, and its internal schemas remain Notification-owned only.

The production cluster has 3 PostgreSQL instances with synchronous quorum and
failover quorum. The `DISPATCHING` transaction is synchronously committed before
provider I/O. Permitted automatic failover must therefore retain acknowledged
dispatch state; otherwise failover is refused in favor of durability.

Notification persistence uses jOOQ/JDBC without JPA. Core relational structures
include Notification, attempt, provider receipt evidence, result outbox, template
definition/version/activation/audit, and bounded retention metadata. The former
`notification_dispatch_fence` is not part of the current v1 model.

Flyway is the only schema-change mechanism. Executed migrations are immutable.
Retention cleanup uses bounded batch deletion; v1 does not require table
partitioning.

Provider worker baseline remains:

- claim lease 30s;
- claim batch 25;
- busy poll 250ms;
- idle poll 1s;
- isolation `READ COMMITTED`;
- `lock_timeout` 100ms;
- general worker `statement_timeout` 500ms;
- claims use `FOR UPDATE SKIP LOCKED`.

A stale `DISPATCHING` attempt reconciles; it is not reclaimed as a new send.

## 10. Templates — current state

ADR-0036 is current. Notification PostgreSQL is authoritative for templates.
Definitions, immutable versions, activation pointer, and append-only audit are
stored in Notification's database.

Version states are `DRAFT`, `PUBLISHED`, `RETIRED`; activation pointer is the
only active-version authority. Every edit creates a new version. Activation
validates syntax, allow-listed placeholders, channel shape, and content limits
before atomically moving the pointer.

The active version is resolved during durable acceptance. Version ID, digest,
and exact rendered content are fixed for that notification. Retry never
re-resolves/re-renders a newer version.

Renderer is intentionally bounded: no Pebble/expression language/functions/
loops/conditions/includes/arbitrary variables/raw HTML parameters.

## 11. Sensitive escrow — current state

ADR-0043 supersedes Notification's OpenBao Transit hot path.

Sensitive recipient/exact rendered authentication content is encrypted with an
independent local AES-256-GCM key ring sourced from OpenBao through External
Secrets Operator and mounted read-only.

No OpenBao network call occurs during `SubmitNotification`, provider dispatch,
retry, or reconciliation.

Key rules include random 96-bit nonce, 128-bit tag, persisted immutable key ID/
format version, strong AAD binding, 90-day normal rotation, overlapping
historical decrypt keys, and no key-ID reuse.

Sensitive ciphertext has a 24-hour hard maximum and is deleted earlier at
terminal lifecycle points where required. Raw recipient/code/rendered content
has no long-term retention.

## 12. Email provider

Iranian production Email uses Liara Transactional Email via authenticated SMTP
with STARTTLS.

```text
domain       = hooshix.com
from         = no-reply@hooshix.com
display name = Hooshix
reply-to     = omitted
```

SPF, DKIM, and DMARC must pass before readiness. Final SMTP `2xx/250` is
`DEFINITIVE_ACCEPTED` -> `PROVIDER_ACCEPTED`, never `DELIVERED`. Without
authenticated provider-correlated delivery evidence, final reconciliation after
72h produces `DELIVERY_STATUS_UNKNOWN`.

## 13. SMS provider

ADR-0049 selects IPPanel Edge Webservice sending mode for Iran production. Notification renders the exact versioned SMS content itself; provider-managed Pattern rendering is not used.

Provider HTTP uses 500ms connect / 1500ms total request timeout and no automatic client retry. Timeout/connection loss/unproven acceptance is `AMBIGUOUS` and is never blindly resubmitted. `DEFINITIVE_ACCEPTED` requires a sandbox-pinned successful response containing the provider correlation identifier.

Delivery evidence is polled from the authenticated recipient-level report: pinned provider status `2` maps to `DELIVERED`, `3`/`4` to permanent non-delivery mappings, and `0`/`1` remain non-terminal. Bulk/outbox-level sent status is never delivery evidence. Polling is bounded and follows the existing 12-hour SMS observation window.

Local development may still use `LoggingSmsProviderAdapter` only under `local & !staging & !production`; it is never a production fallback and `SIMULATED` never maps to a canonical provider outcome.

## 14. Result callback

Notification terminal transaction deletes sensitive escrow and creates the
non-PII result outbox. Callback is idempotent and at-least-once.

`ReportNotificationResult` uses the accepted 750ms deadline, one attempt,
wait-for-ready off, no automatic gRPC retry; durable dispatcher retry schedule
and 7-day maximum automatic retry age remain from ADR-0029.

Callback destination is the GitOps allow-list, never caller-controlled URL/host.

## 15. Observability and SLO

`SubmitNotification` durable acceptance remains Class B. First provider-attempt
scheduling remains Class C: 99.9% of accepted intents begin their first provider
attempt within 5 seconds.

Current bounded telemetry focuses on submit latency/outcomes, PostgreSQL
availability/failover, dispatch transaction latency, provider attempts,
ambiguity/reconciliation, receipt lag, escrow age, and callback backlog.

Former clock-cycle/fence metrics are removed. Infrastructure NTP/Chrony health
is monitored at the node/platform layer.

Metric labels never contain recipient, raw/pseudonymous request identifiers,
codes, provider message IDs, ciphertext, or free-form errors.

## 16. Verification

Notification changes require applicable tests for:

- state machine and terminal immutability;
- ambiguity/retry/deadline behavior;
- PostgreSQL-authoritative timestamp boundaries;
- concurrent `SKIP LOCKED` claims and dispatch transaction locking;
- crash immediately before/after `DISPATCHING` commit;
- CloudNativePG primary failover around dispatch commit and proof of no blind
  redispatch;
- jOOQ/Flyway/migration compatibility;
- template versioning/activation/concurrency/rendering;
- Liara SMTP STARTTLS/auth/outcome classification;
- local key-ring rotation/refresh/corruption/erasure;
- callback idempotency;
- PII-safe telemetry;
- Istio/NetworkPolicy positive and negative authorization;
- load/chaos behavior for critical paths.
