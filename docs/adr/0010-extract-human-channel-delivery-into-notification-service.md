# ADR-0010: Extract Human-Channel Delivery into Notification Service

## Status

Accepted

## Date

2026-08-09

## Supersedes

This ADR supersedes only the Identity-owned delivery-consumer, provider-adapter,
delivery-status, retry-policy, PII-resolution, and "no separate notification
service" decisions in ADR-0008.

The verification-code HMAC, encrypted delivery-secret escrow, transactional
outbox, secret-erasure, no-network-I/O-in-transaction, provider-ambiguity, and
PII/logging constraints in ADR-0008 remain in force until a later ADR explicitly
replaces them.

## Context

ADR-0008 placed registration-verification provider delivery inside Identity and
explicitly deferred a separate Notification Service. Delivery to people is a
cross-cutting platform capability required by Identity, Order, Payment, and
future bounded contexts. Keeping template presentation, provider adapters,
provider-specific telemetry, retry rules, and delivery status inside every
caller would duplicate infrastructure concerns and expose provider behavior to
business services.

The service boundary must not create shared-database coupling. It must also
preserve the existing rule that callers own their domain data and that
notification delivery receives only the minimum PII required for the requested
delivery.

This decision establishes the capability boundary and semantic contract shape.
It does not infer the unresolved durable handoff and verification-secret
lifecycle across the new boundary.

## Decision

### Service boundary and runtime

Create an independently deployable `notification-service` under
`services/notification-service`.

Its bounded capability is delivery of messages to people through supported
channels. It is classified as a Platform Service, but that label does not imply
a shared process, database, deployment, credential, or release lifecycle.

The service is internal only:

- it has no public ingress;
- callers use versioned gRPC and Protobuf contracts;
- it uses an independent Kubernetes ServiceAccount and explicit mesh
  authorization;
- its service endpoint is ClusterIP-only inside Istio Ambient Mesh.

The implementation stack is:

- Java 25;
- Spring Boot 4.1.x;
- gRPC and Protobuf;
- Flyway;
- a service-owned PostgreSQL database when durable persistence is required.

### Capability ownership

Notification Service owns:

- template selection;
- localization;
- rendering and post-render validation;
- email and SMS provider adapters;
- future push delivery when separately approved;
- delivery status;
- bounded retry policy;
- provider-specific behavior and telemetry.

Caller services retain ownership of their business workflow, domain state,
recipient source data, and authorization decisions.

### Semantic contract

A caller supplies only:

- a semantic template identifier;
- a delivery channel;
- a locale;
- the minimum recipient address required by that channel;
- a limited, template-specific, type-safe Protobuf parameter message;
- bounded operational metadata required by the approved contract.

Arbitrary email subjects, plain-text bodies, HTML bodies, provider payloads, or
unbounded `map<string, string>` parameters are prohibited.

Notification Service selects and renders its own templates. Callers do not know
provider presentation details.

For Identity-to-Notification RPCs:

- a request is at most 64 KiB;
- a response is at most 32 KiB;
- gRPC metadata is at most 16 KiB;
- an oversize message is a non-retryable contract violation.

After rendering and before provider submission:

- an email subject is at most 200 Unicode code points and 512 UTF-8 bytes;
- a plain-text email body is at most 32 KiB;
- an HTML email body is at most 64 KiB;
- a verification or recovery SMS v1 is exactly one segment at most: either
  160 GSM-7 septets or 70 UCS-2 code units according to its actual encoding;
- multi-segment verification or recovery SMS is prohibited.

### Data and PII ownership

Notification Service must not access Identity, Order, Payment, or another
caller's database, schema, repository, or internal persistence API.

An API such as `sendToAccount(accountId)` that requires Notification Service to
resolve caller-owned PII is prohibited. The caller resolves and supplies the
minimum recipient value required for delivery.

Recipient PII, verification codes, rendered content, provider responses, and
provider credentials remain subject to the architecture logging and redaction
rules. They must not appear in logs, traces, metric labels, dead-letter payloads,
or unrestricted audit records.

Any persistence used for templates, delivery status, retries, or provider
idempotency belongs exclusively to Notification Service. Flyway is its only
schema-change mechanism. No schema or datastore is shared with a caller.

### Migration and implementation gate

This ADR changes ownership but does not authorize an unsafe mechanical move of
the current Identity delivery implementation.

Before production implementation or runtime exposure, a separate accepted
decision must define:

- the reliable command handoff between caller outbox/Kafka processing and the
  Notification gRPC boundary;
- how a short-lived verification or recovery secret crosses the boundary
  without entering Kafka, logs, traces, dead letters, or unapproved durable
  plaintext storage;
- Notification delivery-status persistence and provider idempotency;
- bounded retry, backoff, terminal failure, replay, and unknown-provider-result
  behavior;
- status/result propagation back to the caller;
- coordination of caller-owned challenge state, encrypted escrow, consumption,
  supersession, expiry, and irreversible erasure;
- the migration or retirement of Identity-owned provider-delivery state;
- deadlines, cancellation, authentication, workload authorization, and PII
  tests for the new interaction.

Until that decision is accepted and implemented, ADR-0008 runtime gating still
blocks production registration and resend exposure. Existing Identity
persistence is not migrated or deleted by this ADR.

### Platform Service classification

The Platform Service classification includes:

```text
notification-service
compromised-password-service
reference-data-service
workflow-service
```

`reference-data-service` is planned to own shared, versioned reference datasets
such as countries, provinces/states, cities, currencies, languages, and
potentially time zones when localization, search, administrative updates,
versioning, multiple consumers, or hierarchy justify the boundary. Candidate
APIs include `ListCountries`, `ListRegions(countryCode)`, and
`ListCities(regionCode)`.

`workflow-service` is listed for future design only. Its bounded capability and
contracts require a separate accepted ADR. It must not absorb business rules
owned by Business Services.

## Consequences

- Human-channel delivery and provider presentation become one reusable bounded
  capability instead of being duplicated across callers.
- Caller services send semantic intent and minimum recipient data without
  sharing their databases.
- Template and provider changes do not require caller-domain presentation logic.
- Notification Service becomes a security-sensitive processor of recipient PII
  and short-lived authentication secrets.
- A new synchronous boundary and a potentially asynchronous delivery lifecycle
  require explicit deadlines, authorization, idempotency, and failure handling.
- The existing Identity delivery workflow cannot be moved until the separate
  secure handoff and lifecycle decision is accepted.

## Alternatives considered

### Keep delivery adapters in every caller

Rejected because template rendering, localization, provider integration, retry,
delivery status, and telemetry would be duplicated across bounded contexts.

### Let Notification Service query caller databases

Rejected because it violates private database ownership, creates cross-service
coupling, and expands PII access.

### Accept arbitrary subject and body content

Rejected because it leaks presentation and provider concerns into callers,
weakens template governance, and creates an unbounded content-injection surface.

### Combine Platform Services in one process

Rejected because `Platform Service` is a classification, not a deployment or
data-sharing boundary.

### Move the existing Identity delivery implementation immediately

Rejected because the durable handoff, short-lived secret transfer, retry/status
protocol, and escrow-erasure coordination have not yet been approved.

## Rollback or migration considerations

This ADR creates no runtime workload, schema, contract, or data migration by
itself.

Before Notification Service is deployed, rollback consists of reverting the
documentation decision while the ADR remains in historical record. After a
contract or runtime is introduced, rollback requires compatibility for caller
contracts and a plan for in-flight delivery state.

Identity-owned provider-delivery tables and adapters must not be deleted until
all in-flight records are terminal or migrated by an explicitly approved,
tested, and reversible plan.
