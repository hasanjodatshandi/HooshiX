# Notification Service Architecture

## 1. Ownership and package

Notification Service is an independent internal platform service at `services/notification-service` with base package `com.sajtech.notification`.

It owns semantic template resolution/rendering, localization, Email/SMS provider adapters, delivery status/evidence, bounded retry/reconciliation, provider telemetry, durable Notification persistence, encrypted retry escrow, and result callbacks.

Callers retain business workflow/domain state, recipient source data, authorization, and semantic intent. Notification never reads another service database.

## 2. Internal-only boundary

Notification has no public application ingress. Callers use versioned gRPC + Protobuf over Istio Ambient strict mTLS/workload identity.

Identity v1:

- `SubmitNotification`: Notification port 9090, only `identity-service` workload identity allowed;
- `ReportNotificationResult`: Identity callback port 9091, only `notification-service` allowed;
- no JWT/API key/application-managed TLS/waypoint for these L4-only v1 paths.

Before another caller shares the L4-only submission port, caller-to-semantic authorization must be redesigned/reviewed.

## 3. Durable handoff

`SubmitNotification` is idempotent. Caller supplies stable `request_id`; Notification assigns stable `notification_id`.

`ACCEPTED` means durable responsibility transfer only after validation, template/version resolution, exact rendering, local encryption, and PostgreSQL commit. It is not provider acceptance or delivery.

```text
Deadline:        900 ms
Attempts:        1
Wait-for-ready:  off
Automatic retry: none
```

Unknown outcomes are recovered by durable replay of the same `request_id`, not transport retry.

Conflict detection uses `UNIQUE(caller_service, request_id)` plus the versioned HMAC-SHA-256 intent fingerprint from ADR-0006. Equal replay returns the original outcome; conflicting content returns `ALREADY_EXISTS / REQUEST_ID_CONFLICT`. Duplicate resolution occurs before current-time/expiry validation.

## 4. Lifecycle and provider outcomes

Canonical lifecycle:

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

Terminal states are `DELIVERED`, `FAILED_PERMANENT`, `EXPIRED`, and `DELIVERY_STATUS_UNKNOWN`; terminal states are immutable.

Provider-attempt classifications:

```text
DEFINITIVE_ACCEPTED
DEFINITIVE_TRANSIENT_FAILURE
DEFINITIVE_PERMANENT_FAILURE
AMBIGUOUS
```

`RETRY_EXHAUSTED` is a failure category, never a lifecycle state. Ambiguous provider submission is never blindly retried.

## 5. Delivery evidence

`PROVIDER_ACCEPTED` is not `DELIVERED`.

`DELIVERED` requires authenticated, correlated, channel-specific provider evidence bound to the same provider/attempt. Submission success or existence of a provider ID alone is insufficient.

Email delivery means destination mail-system acceptance, not human open/read. Inconclusive final evidence becomes `DELIVERY_STATUS_UNKNOWN`, never inferred success.

## 6. Retry and observation

Email and SMS each allow at most four provider submission attempts including the initial attempt.

```text
SMS:   retry 2s, 10s, 30s ±20%; delivery deadline 2m; observation 12h
Email: retry 5s, 30s, 120s ±20%; delivery deadline 5m; observation 72h
```

Only definitive transient failure follows the retry schedule. Provider acceptance and ambiguous outcomes are not submission-retried.

Operator replay of a terminal Notification is prohibited. A resend is a new caller-authorized intent with new IDs.

## 7. PostgreSQL-authoritative time

ADR-0018 contains the current Notification clock/deadline/dispatch safety model.

`accepted_at` and lifecycle transition time use PostgreSQL `clock_timestamp()`. Contract/persistence comparison precision is canonical UTC microseconds; finer Protobuf timestamps truncate downward, never round upward.

```text
SMS channel_deadline   = accepted_at + 2m
Email channel_deadline = accepted_at + 5m
effective_delivery_deadline = min(channel_deadline, message_not_after)
```

Time-bound semantic messages require `message_not_after`. Callers use accepted credential expiry minus the five-second safety margin. Persisted acceptance/effective deadlines are immutable and never extended by retry, restart, reconciliation, or failover.

## 8. Dispatch safety

The current runtime deliberately has **no application clock-health control plane, Chrony `hostPath` sidecar, clock-health RPC, database dispatch fence, fence coordinator, heartbeat generation, or re-arm protocol**.

Immediately before provider I/O, Notification performs one short local transaction that:

1. locks/reloads the attempt;
2. reads PostgreSQL-authoritative time;
3. checks non-terminal lifecycle/deadline eligibility;
4. persists immutable execution identity;
5. transitions to `DISPATCHING`;
6. commits durably.

Provider I/O starts only after commit. No network I/O occurs inside the transaction.

After `DISPATCHING`, worker crash, lease expiry, database failover, timeout, or unknown provider result never authorizes blind redispatch. The attempt enters reconciliation under the current ambiguity/evidence rules.

## 9. PostgreSQL HA and persistence

Notification owns database `notification` on its dedicated production CloudNativePG cluster. Notification-only runtime/migration roles have no privileges on another service database/cluster. ADR-0019, ADR-0027, ADR-0034, and ADR-0037 define current HA/isolation/fleet/restore behavior.

The critical production cluster has three PostgreSQL instances with synchronous required durability and failover quorum. The `DISPATCHING` transaction is synchronously committed before provider I/O. Permitted automatic failover must retain acknowledged dispatch state; otherwise failover is refused in favor of durability.

Notification persistence uses jOOQ/JDBC without JPA. Core structures include Notification, attempts, provider receipt evidence, result outbox, template definition/version/activation/audit, and bounded retention metadata. A dispatch-fence table is not part of the current model.

Flyway is the only schema-change mechanism. Executed migrations are immutable. Retention cleanup uses bounded batch deletion; v1 does not require table partitioning.

Provider worker baseline:

- claim lease 30s;
- claim batch 25;
- busy poll 250ms;
- idle poll 1s;
- isolation `READ COMMITTED`;
- `lock_timeout` 100ms;
- general worker `statement_timeout` 500ms;
- claims use `FOR UPDATE SKIP LOCKED`.

A stale `DISPATCHING` attempt reconciles; it is not reclaimed as a new send.

## 10. Templates

Notification PostgreSQL is authoritative for templates under ADR-0006/ADR-0010. Definitions, immutable versions, activation pointer, and append-only audit are database-owned.

Version states are `DRAFT`, `PUBLISHED`, `RETIRED`; the activation pointer is the only active-version authority. Every edit creates a new version. Activation validates syntax, allow-listed placeholders, channel shape, and content limits before atomically changing the pointer.

The active version is resolved during durable acceptance. Version ID, digest, and exact rendered content are fixed for the Notification lifetime; retry never re-resolves/re-renders a newer version.

Rendering is intentionally bounded: no general expression language, arbitrary functions/loops/conditions/includes/variables, or unsafe raw-HTML parameters.

## 11. Sensitive escrow

Sensitive recipient/exact rendered authentication content is encrypted with a purpose-specific local AES-256-GCM key ring under ADR-0014, sourced from OpenBao through External Secrets Operator and mounted read-only.

No OpenBao network call occurs during `SubmitNotification`, provider dispatch, retry, or reconciliation.

Key rules include random 96-bit nonce, 128-bit tag, immutable key ID/format version, strong AAD binding, 90-day normal rotation, overlapping decrypt keys while dependent ciphertext exists, and no key-ID reuse.

Sensitive ciphertext has a 24-hour hard maximum and is deleted earlier at applicable terminal/cutoff points. Raw recipient/code/rendered content has no long-term retention.

## 12. Email provider

Production Email uses Liara Transactional Email via authenticated SMTP with STARTTLS.

```text
domain       = hooshix.com
from         = no-reply@hooshix.com
display name = Hooshix
reply-to     = omitted
```

SPF, DKIM, and DMARC must pass before readiness. Final SMTP `2xx/250` is `DEFINITIVE_ACCEPTED` -> `PROVIDER_ACCEPTED`, never `DELIVERED`. Without authenticated provider-correlated delivery evidence, final reconciliation after 72h produces `DELIVERY_STATUS_UNKNOWN`.

## 13. SMS provider

Production Iran SMS uses IPPanel Edge Webservice mode under ADR-0020. Notification renders exact versioned SMS content itself; provider-managed Pattern rendering is prohibited as semantic authority.

Provider HTTP uses 500ms connect / 1500ms total timeout and no automatic client retry. Timeout/connection loss/unproven acceptance is `AMBIGUOUS` and is never blindly resubmitted. `DEFINITIVE_ACCEPTED` requires the sandbox-pinned successful response with provider correlation identity.

Authenticated recipient-level report polling maps pinned provider status `2` to `DELIVERED`, `3`/`4` to permanent non-delivery, and `0`/`1` to non-terminal observation. Bulk/outbox-level sent status is never delivery evidence. Polling is bounded within the 12-hour observation window.

Local development may use `LoggingSmsProviderAdapter` only under `local & !staging & !production`; it is never a staging/production fallback and `SIMULATED` never maps to a canonical provider outcome.

## 14. Result callback

At applicable terminal state, one local Notification transaction erases sensitive escrow and creates a non-PII result outbox entry. Callback is idempotent and at-least-once.

`ReportNotificationResult` uses 750ms deadline, one attempt, wait-for-ready off, and no automatic gRPC retry. Durable result-outbox retry is bounded with a seven-day maximum automatic retry age.

Callback destination is the reviewed GitOps allow-list, never a caller-controlled URL/host.

## 15. Observability and SLO

`SubmitNotification` durable acceptance remains Class B. First provider-attempt scheduling is Class C: 99.9% of accepted intents begin their first provider attempt within five seconds.

Bounded telemetry covers submit latency/outcomes, PostgreSQL availability/failover, dispatch-transaction latency, provider attempts, ambiguity/reconciliation, receipt lag, escrow age, and callback backlog.

Infrastructure NTP/Chrony health is a platform/node signal, not an application dispatch protocol.

Metric labels never contain recipient, raw/pseudonymous request identifiers, codes, provider message IDs, ciphertext, or free-form errors.

## 16. Verification

Notification changes require applicable tests for:

- lifecycle/state transitions and terminal immutability;
- ambiguity/retry/deadline behavior;
- request fingerprint equality/conflict behavior;
- PostgreSQL-authoritative timestamp boundaries;
- concurrent `SKIP LOCKED` claims and dispatch locking;
- crash immediately before/after `DISPATCHING` commit;
- CloudNativePG primary failover around dispatch commit and proof of no blind redispatch;
- jOOQ/Flyway/migration compatibility;
- template versioning/activation/concurrency/rendering;
- Liara SMTP STARTTLS/auth/outcome classification;
- IPPanel submission/report fixtures, polling/backpressure, and ambiguity handling;
- local key-ring rotation/refresh/corruption/erasure and proof of no OpenBao hot-path RPC;
- callback idempotency/destination allow-list;
- PII-safe telemetry;
- Istio/NetworkPolicy positive and negative authorization;
- load/chaos behavior for critical paths.
