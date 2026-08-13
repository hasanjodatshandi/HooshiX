# ADR-0029: Notification v1 Semantic Contract

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

## Decision

### Stable request identity and conflict detection

`request_id` is stable for one caller-owned delivery intent. Notification stores a 32-byte HMAC-SHA-256 intent fingerprint together with `fingerprint_version` and `fingerprint_key_id`.

The fingerprint key is a dedicated random 256-bit local key sourced from OpenBao through the approved secret-delivery boundary. Fingerprint generation performs no OpenBao network call. Keys rotate every 90 days; retired verification keys remain available for at least the 35-day dedup window plus seven days.

`fingerprint-v1` is versioned, domain-separated, and length-prefixed. It includes authenticated caller identity, channel, semantic type, canonical recipient, canonical locale, canonical-microsecond `message_not_after` when present, and every typed semantic parameter. Protobuf deterministic serialization is not used as a canonical fingerprint format. Comparison is constant-time.

After lookup by `UNIQUE(caller_service, request_id)`:

- equal fingerprint -> return original accepted/result outcome;
- different fingerprint -> `ALREADY_EXISTS / REQUEST_ID_CONFLICT`;
- duplicate resolution occurs before current-time/expiry validation and does not decrypt sensitive escrow.

### Submission and caller dispatcher

`SubmitNotification` uses:

```text
overall deadline: 900 ms
attempts:         1
wait-for-ready:   off
gRPC retry:       none
```

A durable caller dispatcher may replay the same `request_id` after infrastructure/transport ambiguity; it never invents a new logical intent. Cancellation before durable acceptance aborts work where possible; cancellation/deadline after commit cannot undo `ACCEPTED`, so the caller resolves the outcome by replaying the same ID.

Identity dispatcher baseline:

```text
claim: FOR UPDATE SKIP LOCKED
lease: 30s
batch: 32
busy poll: 250ms
idle poll: 1s
retry: 1s, 2s, 5s, 10s, then <=30s ±20%
time-bound cutoff: no new RPC at/after message_not_after - 5s
non-time-bound automatic retry: <=30m, then HANDOFF_FAILED + alert
```

### Result callback

`ReportNotificationResult` uses:

```text
deadline:       750 ms
attempts:       1
wait-for-ready: off
gRPC retry:     none
```

The durable result-outbox dispatcher retries outside the RPC with bounded jittered backoff up to a seven-day automatic retry age. Duplicate callback delivery returns success only after proving the original caller-side effect is already committed.

Callback destinations are reviewed GitOps configuration. The caller cannot provide a callback URL/URI/host/IP/scheme/redirect/port/method. Identity v1 callback is the internal `identity-service` endpoint/method defined by deployment configuration.

### Workload identity and authorization

Identity and Notification use distinct `platform-apps` ServiceAccounts. Submission is authorized only from the approved Identity workload identity; result callback is authorized only from Notification. Both paths use Istio Ambient strict mTLS and least-privilege authorization. No JWT/API key/application-managed TLS is added to these L4-only internal paths.

Before a second caller is enabled on a shared L4 submission boundary, caller-to-semantic authorization must be redesigned/reviewed.

### Typed semantic contract

Initial channels:

```text
CHANNEL_UNSPECIFIED = 0
EMAIL               = 1
SMS                 = 2
```

Semantic content is an explicit Protobuf `oneof`; arbitrary parameter maps, caller-supplied subject/body/HTML, arbitrary brand names, or arbitrary URLs are prohibited.

Initial Identity semantic types:

- `REGISTRATION_VERIFICATION_CODE` -> Email/SMS;
- `PASSWORD_RECOVERY_CODE` -> Email/SMS;
- `MFA_VERIFICATION_CODE` -> Email/SMS;
- `PASSWORD_CHANGED_NOTICE` -> Email.

Verification messages expose only their explicit code parameter, with bounded flow-specific format/size. `PasswordChanged` exposes only explicitly versioned typed fields.

### Locale and template resolution

Canonical locales are `en` and `fa`; compatible region variants normalize to their supported primary locale. Unknown primary languages return `INVALID_ARGUMENT / UNSUPPORTED_LOCALE` rather than silently changing language.

Notification PostgreSQL is authoritative for templates. Definitions, immutable versions, activation pointer, and audit are database-owned. Version states are `DRAFT`, `PUBLISHED`, `RETIRED`; every edit creates a new version. Activation validates syntax, allow-listed placeholders, channel shape, and content limits before atomically changing the active pointer.

The active version is resolved during durable acceptance. Version ID, digest, and exact rendered content are fixed for the Notification lifetime; retry never re-resolves or re-renders a newer version.

The renderer is intentionally bounded: no general expression language, loops, conditions, includes, functions, arbitrary variables, or unsafe raw-HTML parameters.

Time-bound classification is immutable template/semantic metadata, not a caller-controlled flag.

### Recipient canonicalization

Email accepts one mailbox, no display-name/comment input, bounded canonical form, and provider-neutral normalization; provider-specific Gmail dot/plus rewriting is prohibited. SMS input is E.164 and is validated without locale-based national-number inference.

The canonical provider destination is computed once, encrypted in escrow, and reused unchanged for retry/reconciliation.

### Sensitive material

Caller handoff escrow and Notification delivery escrow use independent local AES-256-GCM key rings sourced from OpenBao through External Secrets. Each encryption uses a random 96-bit nonce and strong AAD binding to stable intent/context identifiers. Keys are purpose-separated, versioned, rotated, and retained only while dependent ciphertext requires decryption.

No OpenBao Transit/hot-path RPC is used for Notification acceptance, dispatch, retry, or reconciliation.

Sensitive ciphertext has a 24-hour hard maximum and is erased earlier at applicable terminal/cutoff states. Raw recipient, code, rendered content, and arbitrary sensitive parameters have no long-term retention.

### Retention

```text
request_id + dedup/fingerprint:       35d
non-PII notification metadata:       90d
provider attempts/correlation IDs:   30d
provider receipt evidence:           30d
result outbox after ACK:              7d
unacked/exhausted callback metadata: 90d
security/audit evidence:             365d
sensitive escrow ciphertext:         <=24h hard maximum
```

Retention cleanup is bounded and observable.

## Security and verification requirements

Required tests cover fingerprint versioning/key rotation/constant-time comparison, equal replay/conflicting reuse, deadline/cancellation-after-commit, dispatcher cutoffs, callback idempotency and destination allow-listing, positive/negative Istio policy, semantic/channel permissions, locale behavior, bounded rendering, recipient canonicalization, local key-ring rotation/corruption/erasure, retention bounds, and PII-safe telemetry.

Logs/traces/metrics/errors/outboxes MUST NOT expose raw recipient, code, rendered content, key material, provider payloads, or ciphertext.

## Rollback considerations

Schema/contracts evolve additively. Mixed versions must preserve stable identifiers, fingerprint semantics/version, original accepted outcome, ciphertext key version, exact accepted template/content, deadlines, and callback idempotency. Rollback cannot recreate erased caller escrow, extend a deadline, or alter an already accepted message.
