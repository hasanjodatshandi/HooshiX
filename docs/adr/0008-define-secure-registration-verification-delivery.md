# ADR-0008: Define secure registration verification delivery constraints

## Status

Accepted

## Date

2026-08-08

## Context

Identity local registration and verification already define the following
security and transaction invariants:

- verification codes are exactly eight decimal digits generated with
  `java.security.SecureRandom`;
- raw verification codes are never persisted or logged;
- persisted verification-code protection uses challenge-bound HMAC-SHA-256;
- provider communication occurs outside database transactions;
- when a state change requires an integration event, aggregate persistence and
  the outbox record occur in the same local database transaction;
- publishing directly to Kafka after repository persistence is prohibited.

The current Application seam,
`RegistrationVerificationSender`, receives the raw verification code only in
memory and is called after the registration or resend transaction completes.
That keeps provider network I/O outside the database transaction, but it is a
direct synchronous side effect and is not the production event-driven delivery
architecture required by the backend architecture.

The repository currently has no production implementation of
`RegistrationVerificationSender`, no Kafka or outbox implementation for this
flow, and no gRPC server runtime registration for the registration adapter.

Two repository-wide decisions that materially affect a durable production
delivery implementation are still pending:

- selection of Schema Registry and its compatibility policy;
- selection of Secret Manager and GitOps controller.

A naive migration of the current sender call to Kafka would require placing the
raw verification code, or a reversible equivalent, into durable messaging or
outbox state. That would violate the current verification security baseline or
prematurely choose a secret-storage design.

A worker that generates a new code for every retry without retaining recoverable
secret state is also unsafe. If a provider accepted the first delivery but its
acknowledgement was lost, a retry could replace the protected code and
invalidate the code already received by the user.

The registration gRPC inbound adapter therefore exists as a plain adapter but
is intentionally not registered as a runtime gRPC service yet.

## Decision

Until a complete verification-delivery design is accepted, production runtime
registration of the registration and resend RPCs remains blocked.

Any accepted production verification-delivery design must satisfy all of the
following constraints.

### Secret handling

Raw verification codes:

- exist only in process memory for the minimum required lifetime;
- are never persisted in PostgreSQL, an outbox table, Kafka, logs, traces,
  metrics, dead-letter payloads, or audit records;
- are never represented as immutable `String` values in sensitive transport or
  delivery paths;
- are cleared from mutable temporary buffers where the implementation controls
  those buffers.

A reversible representation of a verification code must not be introduced
until its key-management and operational model is explicitly approved. The
pending Secret Manager decision must not be bypassed.

### Transaction and event boundaries

Database state changes and any required outbox intent are committed in the same
local database transaction.

Email, SMS, Kafka, Redis, Secret Manager, and external-provider network I/O do
not occur inside that transaction.

Direct Kafka publication after repository save or after transaction commit is
not a substitute for the outbox pattern.

### Delivery intent

A durable delivery intent or integration event may contain only non-secret
delivery metadata, such as:

- event or intent ID;
- user ID;
- verification challenge ID;
- target type and internal target/contact identifier;
- creation time;
- versioned non-secret policy or contract metadata when required.

It must not contain the raw verification code or another field from which the
code can be recovered without an separately approved secret-management design.

Contact PII is not duplicated into an event when an internal identifier is
sufficient for the owning Identity worker to resolve the target.

### Accepted durable delivery design

The accepted production design separates verification from delivery by using two
independent secret protections.

The existing challenge-bound HMAC remains the one-way verification value used to
check a code presented by the user.

A second, independently keyed protection is used only to make the same
short-lived verification code recoverable for durable asynchronous delivery
retries. This second protection is an encrypted delivery-secret escrow and must
not reuse the verification-code MAC key.

The registration or resend flow performs these steps:

```text
Generate raw verification code in memory
        |
        +--> HMAC-protect for verification
        |
        +--> AEAD-encrypt for short-lived delivery escrow
        |
        v
Single local database transaction
        |
        +--> persist user / credential / challenge changes
        +--> persist encrypted delivery-secret escrow
        +--> persist non-secret delivery outbox intent
        |
        v
Commit
        |
        v
Outbox publisher -> Kafka
        |
        v
Identity-owned delivery consumer
        |
        +--> load challenge and internal contact identifier
        +--> load encrypted delivery secret
        +--> decrypt code in memory
        +--> resolve immutable contact value inside Identity
        +--> invoke email or SMS provider outside a database transaction
        +--> clear mutable plaintext buffers
```

No provider network call, Kafka publication, Secret Manager call, or other
network I/O occurs inside the registration/resend database transaction.

Key material required to encrypt the delivery secret must already be available
to the process through an approved provider-neutral key-loading boundary before
the transaction begins. A Secret Manager refresh must not be performed from
inside the transaction.

### Encrypted delivery-secret escrow

The escrow is separate from the transactional outbox. The outbox never carries
ciphertext for the verification code.

The escrow contains data equivalent to:

```text
challenge_id
encryption_key_id
encryption_format_version
nonce
ciphertext_and_authentication_tag
created_at
expires_at
```

It contains no email address, phone number, password, raw verification code, or
provider credential.

The cryptographic baseline for this escrow is AES-256-GCM with:

- an encryption key independent from the verification HMAC key;
- a unique 96-bit nonce for every encryption operation under a key;
- a 128-bit authentication tag;
- associated data binding at least the challenge ID, user ID, target type,
  internal contact ID, and encryption format version;
- a persisted key ID so active challenges remain decryptable during controlled
  key rotation.

The concrete Secret Manager or key-management product remains controlled by the
pending platform decision. No escrow implementation is permitted until the
production key-loading, rotation, compromise, access-control, and destruction
model is approved.

### Escrow lifetime and erasure

The encrypted delivery secret is temporary operational state, not long-term
identity data.

It remains available only while both conditions are true:

- the verification challenge is still open; and
- delivery remains retryable.

The escrow becomes eligible for immediate irreversible deletion after any of:

- acknowledged successful provider delivery;
- terminal delivery failure when no replay remains permitted;
- challenge consumption;
- challenge supersession;
- challenge expiry.

A background cleanup process must also remove expired escrow records if an
earlier lifecycle transition was missed.

Deletion of the escrow does not delete the challenge HMAC. Verification and
delivery-secret lifecycles are intentionally separate.

### Delivery intent and PII resolution

The durable outbox intent and Kafka integration event contain only non-secret
metadata.

For registration verification, the payload contains data equivalent to:

```text
delivery_id
user_id
challenge_id
target_type
contact_id
created_at
```

The normal platform integration-event envelope additionally supplies the
required event ID, event type, event version, producer, subject, correlation and
causation identifiers, tenant treatment according to ADR-0002, and trace context
in Kafka headers.

The event does not contain the email address or phone number.

The Identity delivery consumer resolves the contact value from the owning User
aggregate by the internal contact ID already bound to the challenge. Delivery
must fail closed if that contact cannot be resolved consistently.

No separate notification service is introduced by this ADR. A future service
boundary may be defined separately if required.

### Retry, idempotency, and provider ambiguity

Kafka and the outbox path are at-least-once. Exactly-once external provider
delivery is not assumed.

Every delivery intent has a stable `delivery_id`. When the selected provider
supports idempotency keys, the delivery adapter uses that stable identifier.

When a provider accepts a message but its acknowledgement is lost, a retry uses
the same challenge and decrypts the same escrowed code. It must not generate a
replacement code for that retry.

A duplicate email or SMS containing the same still-valid code is therefore an
accepted failure-mode under provider ambiguity. Sending a different code for the
same challenge is prohibited.

Retries are bounded. The final implementation must persist enough non-secret
delivery state to distinguish at least pending, acknowledged, retryable failure,
and terminal failure. It must define retry count/backoff and dead-letter or
operator-replay behavior before production enablement.

A dead-letter payload contains only the non-secret event. It never contains the
plaintext code, escrow ciphertext, provider secret, email address, or phone
number.

### Application and adapter migration

The current direct `RegistrationVerificationSender` call is transitional.

The production migration replaces direct post-transaction sending from
registration and resend use cases with a transactionally persisted delivery
request. The exact Application port names may be chosen during implementation,
but the dependency direction remains:

```text
Application
    |
    v
provider-neutral delivery scheduling / secret-protection ports
    |
    v
Infrastructure persistence + cryptography + outbox adapters
```

The Kafka consumer is an inbound Interface adapter and invokes an Application
delivery use case. Provider-specific email/SMS clients remain outbound
Infrastructure adapters.

The raw code is cleared after both one-way verification protection and delivery
escrow encryption have completed. Provider delivery later decrypts a fresh
mutable plaintext buffer and clears it after use.

### Retry and ambiguity safety

The final delivery design must explicitly handle provider ambiguity, including
the case where the provider accepted a message but the acknowledgement was
lost.

Retries must not silently invalidate a verification code that may already have
been delivered.

The design must define an idempotency or equivalent duplicate-safety strategy,
bounded retries, dead-letter behavior, and the lifecycle relationship between
delivery attempts and verification challenges.

### Current sender seam

`RegistrationVerificationSender` remains an Application outbound seam for tests
and architecture evolution. It is not, by itself, an approved production
delivery architecture and no provider-specific production bean is selected by
this ADR.

### Runtime gating

`IdentityRegistrationGrpcServiceAdapter` remains a plain, unregistered inbound
adapter while production registration/resend delivery is unresolved.

No gRPC server runtime dependency or runtime service registration is introduced
merely to expose an endpoint whose successful state transition cannot satisfy
the production delivery guarantees above.

Acceptance of this ADR resolves the verification-delivery architecture decision
only. It does not enable registration or resend runtime exposure. Runtime remains
blocked until the encrypted escrow, transactional outbox, Kafka delivery flow,
retry/idempotency state, and production composition required by this decision are
implemented and the separately pending product decisions required by those
components are accepted.

The runtime may be enabled only after the required delivery architecture is
accepted and the composition root can provide all required production ports
without test-only or fail-open substitutes.

### Deferred product selections

This ADR does not select:

- an email provider;
- an SMS provider;
- a Schema Registry product or compatibility mode;
- a Secret Manager product;
- a GitOps controller.

Those remain controlled by their existing pending decisions.

Acceptance of this ADR does not resolve those selections. Schema Registry
selection remains required before Kafka contract implementation; Secret Manager
and GitOps selection remains required before production escrow key delivery;
email and SMS provider selection remains required before production provider
adapters are composed.

## Consequences

- The current plain gRPC adapter and contract can continue to evolve and be
  tested without prematurely exposing registration in a production runtime.
- Registration and resend are not production-ready until the accepted delivery
  design is implemented and all separately required production selections and
  runtime gates are satisfied.
- A temporary no-op sender, logging sender, or provider stub must not be used to
  claim production runtime readiness.
- Kafka/outbox implementation for this flow remains blocked from carrying
  verification secrets.
- The future delivery design must make retry ambiguity a first-class lifecycle
  concern rather than treating provider send as a fire-and-forget call.
- The proposed design introduces short-lived encrypted operational state whose
  retention must never exceed the open/retryable challenge lifecycle.
- Provider ambiguity may cause duplicate delivery of the same code; it must not
  cause generation of a different code for the same challenge.
- The pending Secret Manager and Schema Registry decisions remain independent
  and are not silently resolved here.

## Alternatives considered

### Persist the raw verification code in the outbox

Rejected.

The outbox is durable storage. Persisting the raw code violates the established
verification security invariant.

### Publish the raw verification code to Kafka

Rejected.

Kafka is durable transport and the payload can be retained, replicated,
observed, retried, and dead-lettered. Raw verification codes are prohibited from
that path.

### Persist encrypted delivery state without an approved key-management model

Rejected.

A reversible encrypted secret is the proposed retry mechanism only when its
key-management, rotation, access-control, expiry, erasure, operational, and
incident model is explicitly approved. Introducing ciphertext first and deciding
how to manage its keys later would bypass the Secret Manager decision and create
unreviewed recoverable secret storage.

### Generate a fresh random code on every worker retry

Rejected.

A provider may have delivered a previous code even when the sender did not
receive an acknowledgement. Replacing the protected code on retry can invalidate
a code already received by the user.

### Deterministically derive the OTP from challenge ID and a long-lived key

Rejected for the current registration baseline.

This can avoid reversible escrow, but it replaces the pinned `SecureRandom`
verification-code generation model with a key-derived code-generation model and
moves compromise impact into a long-lived derivation key. That is a materially
different security baseline and is not required to solve the current delivery
problem.

### Keep the current direct sender as the production design

Rejected.

The current call is correctly outside the database transaction, but production
email and SMS side effects are required to use the platform event-driven
architecture rather than a direct synchronous provider call.

### Register the gRPC runtime with a no-op or failing sender

Rejected.

Registration may commit user, credential, and challenge state before delivery is
attempted. Exposing that path with a non-production sender would create a
runtime that accepts state transitions without satisfying the user-facing
verification delivery guarantee.
