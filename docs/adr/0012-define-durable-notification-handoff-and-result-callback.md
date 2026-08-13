# ADR-0012: Durable Notification Handoff and Result Callback

## Status

Accepted — current effective decision

## Date

2026-08-09; normalized to current-only documentation on 2026-08-13

## Decision

### Durable submission

`SubmitNotification` is an idempotent internal gRPC operation. The caller creates one stable `request_id`; Notification creates one stable `notification_id`.

```text
Deadline:        900 ms
Attempts:        1
Wait-for-ready:  off
Automatic retry: none
```

`ACCEPTED` is returned only after Notification validates the semantic request, resolves the exact template version/locale, renders exact provider-ready content, encrypts sensitive recipient/content material with its current local AES-256-GCM delivery key ring, and commits the durable Notification record in its PostgreSQL database.

`ACCEPTED` means durable responsibility transfer only. It is not provider acceptance and not delivery evidence.

A replay with the same `request_id` and the same versioned intent fingerprint returns the original accepted identity/outcome. Reuse of the ID with conflicting intent returns `ALREADY_EXISTS / REQUEST_ID_CONFLICT`.

### Caller handoff escrow

The caller persists business state, encrypted handoff escrow, and durable delivery intent/outbox in one local transaction. No remote I/O occurs inside that transaction.

The caller keeps the minimum encrypted handoff material required for replay until Notification returns `ACCEPTED`, then irreversibly removes the recipient/code handoff material. Authoritative contact data and one-way challenge verification state remain under caller ownership.

### Notification sensitive retry state

Notification stores only short-lived ciphertext plus approved metadata. No plaintext database column stores recipient address, rendered authentication content, OTP/recovery secret, or arbitrary sensitive template parameters.

Sensitive content is encrypted/decrypted locally with a purpose-specific AES-256-GCM key ring sourced from OpenBao through External Secrets and mounted read-only. There is no routine OpenBao network call on submission, dispatch, retry, or reconciliation.

Every retry/reconciliation preserves the same stable IDs, recipient, resolved template version, and exact accepted content. Retry never re-resolves a newer template or generates replacement secret content for the same intent.

Sensitive ciphertext has a hard maximum lifetime of 24 hours and is erased earlier at applicable terminal lifecycle points.

### Provider ambiguity and evidence

Exactly-once external delivery is not assumed. Provider acceptance and delivery are separate states. `DELIVERED` requires authenticated, correlated provider evidence; successful submission alone is insufficient.

Ambiguous submission is never blindly retried. Reconciliation follows the current Notification lifecycle/provider policy.

### Terminal result and callback

At an approved terminal Notification state, one local Notification transaction:

1. erases sensitive delivery ciphertext when no longer needed;
2. inserts a non-PII `notification_result_outbox` record.

The result callback is idempotent and at-least-once:

```text
ReportNotificationResult deadline: 750 ms
Attempts:                          1
Wait-for-ready:                    off
Automatic gRPC retry:              none
```

Durable dispatcher retry occurs outside the RPC. The caller records a result transactionally using `request_id` + `notification_id` and acknowledges only after commit. Duplicate callbacks MUST NOT create duplicate business effects.

Callback destinations come from the reviewed GitOps allow-list; a caller-controlled URL/host is prohibited.

### Transaction boundaries

```text
Caller local transaction
  -> business state + encrypted handoff escrow + durable intent/outbox

Caller dispatcher
  -> SubmitNotification outside DB transaction

Notification pre-transaction work
  -> validate + resolve/render + local encrypt

Notification acceptance transaction
  -> durable Notification/ciphertext/retry state
  -> commit
  -> ACCEPTED

Caller erasure transaction
  -> remove accepted handoff escrow

Notification dispatch/reconciliation
  -> local decrypt + provider I/O outside DB transaction

Notification terminal transaction
  -> erase sensitive ciphertext + non-PII result outbox

Notification callback dispatcher
  -> idempotent caller callback outside DB transaction
```

No two services share a database or distributed transaction.

## Security and reliability requirements

- raw recipient/secret/rendered sensitive content never enters Kafka, logs, traces, metrics, or provider-debug output;
- private delivery keys never enter Git or database rows;
- stable idempotency identity survives transport ambiguity;
- retries are bounded and occur only where semantics prove them safe;
- provider I/O and callback I/O never occur while database locks/transactions are held;
- current Istio workload identity/authorization policy restricts submission and callback methods to approved callers;
- result records contain no recipient, body, code, credential, provider payload, or ciphertext.

## Verification requirements

Test idempotent equal replay/conflicting reuse, caller escrow erasure after acceptance, key rotation/reload/corruption, no OpenBao hot-path RPC, exact-content retry, provider ambiguity/no-blind-resend, terminal ciphertext erasure, callback duplicate delivery, crash/restart boundaries, PII-safe telemetry, and positive/negative workload authorization.

## Rollback considerations

Rollback cannot recreate caller handoff escrow after durable responsibility transfers. A rollback must therefore preserve the ability to finish/reconcile every already accepted notification and deliver its non-PII result. Contract changes require mixed-version compatibility until all in-flight accepted work is safe.
