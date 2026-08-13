# ADR-0012: Define Durable Notification Handoff and Result Callback

## Status

Accepted

## Date

2026-08-09

## Supersedes

This ADR resolves the secure handoff, Notification retry/status, and caller
escrow-erasure gate recorded by ADR-0010.

For Notification delivery, it supersedes ADR-0008 provisions that prohibited
recipient PII in delivery escrow and retained provider-delivery state inside
Identity. ADR-0008's prohibition on plaintext secret persistence, Kafka secret
payloads, network I/O inside transactions, unbounded retries, and exactly-once
provider assumptions remains in force.

## Context

ADR-0010 moved template rendering, provider adapters, retry policy, and delivery
status from Identity to Notification Service. A durable boundary is required so
that callers know when Notification has accepted responsibility without
confusing durable acceptance with provider acceptance or delivery.

Verification and recovery messages contain short-lived secrets and recipient
PII. Notification needs the exact same message for a retry, but neither Kafka nor
plaintext database columns may carry those values. Caller and Notification
escrow lifecycles must transfer responsibility without losing the message or
retaining duplicate sensitive state longer than required.

Provider delivery is inherently ambiguous when a provider accepts a request but
its response is lost. Result propagation back to a caller is also at-least-once
and must remain idempotent.

## Decision

### Durable handoff contract

`SubmitNotification` is an idempotent internal gRPC RPC.

The caller creates one stable `request_id` for each delivery intent and reuses
it for every retry of that logical handoff. Notification creates one stable
`notification_id` for the accepted intent.

A successful response has status `ACCEPTED`. It is returned only after:

1. the semantic template and type-safe parameters are validated;
2. the template and locale are resolved to an exact template version;
3. the exact provider-ready content is rendered and passes content limits;
4. the exact recipient and rendered payload/code are encrypted through OpenBao
   Transit;
5. Notification commits the ciphertext, notification identity, resolved
   template version, and retry state in its PostgreSQL database.

`ACCEPTED` means only that durable delivery responsibility has transferred to
Notification Service. It does not mean `PROVIDER_ACCEPTED` or `DELIVERED`.

The response to an idempotent replay of an already accepted `request_id` returns
the stable accepted notification identity without creating a second delivery
intent. Reuse of a `request_id` with conflicting content requires a separate
contract decision before implementation and must not be guessed.

### Caller handoff escrow

Before calling Notification, Identity retains an encrypted handoff escrow for
the recipient and exact verification/recovery secret required to reconstruct the
request. The caller performs no gRPC or other network I/O inside its business
database transaction.

Identity retains its handoff escrow until it receives `ACCEPTED`. It then
irreversibly removes the recipient/code handoff material immediately. A crash
before local erasure is recovered by replaying the same `request_id`, receiving
the same accepted identity, and completing erasure.

This erasure affects the handoff escrow only. It does not delete the caller's
authoritative contact data or the one-way challenge verification HMAC.

### Notification encrypted retry state

Notification uses OpenBao Transit with a purpose-specific independent key named
`notification-delivery-escrow`.

The Transit key remains inside OpenBao and is not delivered to Notification
through a Kubernetes Secret. Notification's Transit principal has only the
minimum encrypt/decrypt operations for this key.

OpenBao Transit network I/O occurs outside Notification database transactions.
Notification stores only Transit ciphertext and approved non-sensitive
metadata. It has no plaintext column for recipient addresses, rendered subjects
or bodies, verification/recovery codes, or arbitrary template parameters.

Every provider attempt decrypts a fresh in-memory copy outside a database
transaction, invokes the provider outside a database transaction, and clears
mutable plaintext buffers where the runtime controls them.

Every retry uses the same:

- `request_id`;
- `notification_id`;
- recipient;
- resolved template version;
- exact rendered content and verification/recovery code.

A retry must not re-resolve or re-render a newer template and must not generate
a replacement code for the accepted delivery intent.

### Provider semantics

Exactly-once provider delivery is not guaranteed.

When a provider supports an idempotency key, Notification supplies the stable
`notification_id`.

If provider acceptance is ambiguous, bounded retries may create a duplicate of
the same exact message/code. A retry must never create different content for the
same notification.

`PROVIDER_ACCEPTED` and `DELIVERED` are distinct states. `DELIVERED` is used only
when an actual provider delivery receipt exists. Provider request acceptance by
itself is not evidence of delivery.

The complete status enum, terminal-state classification, retry count, backoff,
and replay policy remain explicit decisions required before implementation.

### Terminal erasure and result outbox

When a notification reaches an approved terminal provider state, Notification
performs one local database transaction that:

1. irreversibly deletes the sensitive delivery-escrow ciphertext;
2. persists a non-PII `notification_result_outbox` record.

The result record contains the stable `request_id`, `notification_id`, terminal
status, and only approved non-sensitive operational metadata. It contains no
recipient, message body, code, provider credential, provider response payload,
or escrow ciphertext.

Sensitive ciphertext is deleted at terminal state and does not wait for caller
acknowledgement.

### Idempotent result callback

Notification delivers result-outbox records through an internal idempotent gRPC
callback to the originating caller.

The caller identifies the result by both `request_id` and `notification_id` and
records it transactionally. It returns a successful callback acknowledgement
only after its local commit succeeds.

Notification retries the callback until that acknowledgement is received.
Duplicate callbacks produce no duplicate caller business effect. Callback
retries read only the non-PII result outbox and never require restored sensitive
escrow.

The callback deadline, backoff, maximum delivery interval, authentication,
workload authorization, and behavior for a permanently retired caller remain
explicit decisions required before runtime implementation.

### Transaction boundaries

The lifecycle has separate local transactions and no distributed transaction:

```text
Caller transaction
  -> persist business state + handoff escrow + delivery intent/outbox

Caller dispatcher outside transaction
  -> SubmitNotification

Notification pre-transaction work
  -> validate + resolve/render + Transit encrypt

Notification acceptance transaction
  -> persist ciphertext + notification/retry state
  -> commit
  -> return ACCEPTED

Caller erasure transaction
  -> remove accepted handoff escrow

Notification provider worker outside transaction
  -> Transit decrypt + provider I/O

Notification terminal transaction
  -> delete sensitive ciphertext + insert non-PII result outbox

Notification callback worker outside transaction
  -> caller callback; retry until post-commit ACK
```

No two services share a transaction or database.

### Runtime gate

This ADR resolves the architecture shape of durable handoff and terminal result
return, but does not by itself enable production runtime.

Before implementation, the remaining explicit decisions include:

- the complete provider and notification status model;
- retry count, backoff, expiry, terminal-state, and operator replay policy;
- `SubmitNotification` and callback deadlines, retryable gRPC statuses, and
  cancellation behavior;
- request-conflict behavior for a reused `request_id`;
- Transit timeout, outage, rotation, and ciphertext-retention policies;
- caller authentication and per-method Istio authorization;
- schema details, indexes, cleanup, migration, and rollback;
- provider selection and provider-specific idempotency evidence.

ADR-0008 runtime gating remains until these decisions and the required code,
contracts, migrations, security controls, observability, and tests are complete.

## Consequences

- `ACCEPTED`, provider acceptance, and actual delivery have separate meanings.
- Sensitive retry material has one durable owner after handoff.
- Notification can retry the exact accepted message without Kafka carrying PII
  or a verification/recovery secret.
- OpenBao Transit becomes a runtime availability dependency for accepting and
  attempting sensitive notifications.
- Terminal ciphertext erasure is not delayed by callback failure.
- Result propagation is at-least-once and idempotent rather than exactly-once.
- Ambiguous provider results may create duplicate delivery of the same exact
  content.

## Alternatives considered

### Treat a successful RPC as provider delivery

Rejected because durable responsibility transfer and external-provider outcome
are different lifecycle events.

### Put recipient or verification code in Kafka

Rejected because Kafka, retry topics, and dead letters are durable broadly
observable transports that must not carry these secrets.

### Store plaintext retry columns in Notification PostgreSQL

Rejected because recipient and rendered authentication content require
short-lived encrypted escrow with explicit erasure.

### Keep caller escrow until callback acknowledgement

Rejected because it duplicates sensitive durable state after Notification has
accepted responsibility.

### Keep Notification ciphertext until callback acknowledgement

Rejected because callback availability must not extend sensitive payload
retention after provider delivery becomes terminal.

### Re-render templates on retry

Rejected because a retry must reproduce the exact message/code accepted for the
delivery intent.

### Guarantee exactly-once provider delivery

Rejected because provider acceptance can be ambiguous and providers do not all
offer equivalent idempotency guarantees.

## Rollback or migration considerations

This ADR creates no schema or runtime migration by itself.

The production migration must preserve existing Identity-owned delivery state
until every in-flight record is terminal or has been safely converted. A mixed
version rollout must keep `SubmitNotification` and callback contracts backward
compatible.

Rollback after Notification accepts responsibility cannot restore caller
handoff escrow. It therefore requires Notification to remain capable of
finishing or terminally resolving every accepted notification and delivering
its non-PII result callback.
