##  Performance and Reliability

Java alone does not solve timeouts. The following items must be designed and measured.

### 9.1 Synchronous Chains

Dangerous model:

```
BFF --> A --> B --> C --> D

```

Problems:

- Increased tail latency
- Cascading failures
- Cascading timeouts
- Retry storms
- Runtime coupling

Rules:

- At most two consecutive synchronous hops after the BFF
- Independent calls must be parallelized with virtual threads and stable `java.util.concurrent` APIs. Structured Concurrency is preview in Java 25 and is allowed only through an ADR.
- Work not required for the immediate response must use a durable asynchronous boundary. Analytics and audit integration events use Kafka. Human-channel delivery uses the caller's durable handoff intent plus the idempotent `SubmitNotification` gRPC handoff defined by ADR-0012; Kafka is not that handoff transport.
- The BFF must not execute long-running business workflows.
- N+1 service calls are prohibited.

### 9.2 Deadline Budget

Timeouts must decrease from the outside toward internal dependencies:

```
Client/Gateway:       3000 ms
BFF total budget:     2600 ms
gRPC downstream:      1500 ms
Database statement:    800 ms
Pool acquisition:      200 ms

```

These values are baselines and must be adjusted through SLOs and load testing.

Rules:

- Every gRPC call has a deadline.
- Every HTTP client has connect and read timeouts.
- Every sensitive query has `statement_timeout`; lock-sensitive operations also define `lock_timeout`.
- Unlimited timeouts are prohibited.
- A downstream timeout must not exceed the remaining request budget.
- Increasing timeouts without root-cause analysis is prohibited.
- Cancellation must be propagated downstream.

### 9.3 Database Connection Pool

Virtual threads do not increase the number of available database connections.

Rules:

- Hikari pool sizing must be based on database capacity, not request count.
- The combined pool size of all pods must remain below database capacity.
- A large `maximumPoolSize` is not a remedy for slow queries.
- Pool acquisition time must be monitored.
- Leak detection is enabled only temporarily for diagnosis and with controlled overhead.
- Slow queries must be fixed before enlarging the pool.
- PgBouncer is used only when operationally required and after benchmarking.

Mandatory metrics:

```
db.pool.active
db.pool.idle
db.pool.pending
db.pool.acquire.duration
db.query.duration
db.transaction.duration

```

### 9.4 Virtual Threads

- Virtual threads are the default for request and I/O concurrency.
- They do not improve CPU-heavy workloads.
- Downstream concurrency must be bounded with semaphores/bulkheads.
- CPU-heavy work must be moved to a dedicated worker or job.
- Pinning and long blocking operations must be inspected with JFR.
- Creating concurrency without a bulkhead for constrained dependencies such as databases, Redis, or downstream services is prohibited. A virtual-thread executor does not replace downstream limits.
- Shared mutable state without explicit ownership, synchronization, and tests is prohibited.

### Baseline Virtual Thread Configuration

```
spring:
  threads:
    virtual:
      enabled: true
  main:
    keep-alive: true

```

Virtual-thread enablement must be accompanied by load tests, JFR, and pinning analysis.

### 9.5 Retry and Circuit Breaker

- Retry only idempotent operations.
- Keep retry counts limited.
- Use exponential backoff with jitter.
- Retrying in multiple layers at the same time is prohibited.
- Use a circuit breaker only for high-risk dependencies.
- Use bulkheads to limit concurrent calls.
- A fallback must not produce fabricated or incorrect data.

### 9.6 Production SLO Classes and Error Budgets

Class A covers login/authentication, OTP/MFA verification, registration completion, password-reset completion, and password-change completion. Over a rolling 30 days it targets 99.90-percent availability, p95 server latency at most 500 milliseconds, p99 at most 1500 milliseconds, and a 2-second end-to-end server timeout ceiling. Every dependency has a smaller budget and nested retries must not exceed the end-to-end ceiling.

Class B covers critical internal security dependencies, including `compromised-password-service`, the Identity Redis semantic limiter, and Notification durable acceptance. Over a rolling 30 days it targets 99.95-percent availability, p95 latency at most 250 milliseconds, and p99 at most 750 milliseconds. Stricter dependency-specific deadlines remain valid; the compromised-password lookup retains its 900-millisecond overall deadline.

Class C covers asynchronous Notification processing. For 99.9 percent of durably accepted intents, the first provider attempt begins within 5 seconds. `SubmitNotification` success is durable acceptance, not provider acceptance or Email/SMS delivery. Actual provider delivery uses separate channel- and provider-dependent SLIs.

A 99.90-percent objective over 30 days has approximately 43 minutes and 12 seconds of error budget. Below 25-percent consumption, software delivery is normal. At least 25-percent consumption within 24 hours requires reliability review and stopping risky releases. At least 50 percent freezes feature releases. At 100 percent, only security, incident, and reliability changes are permitted. A freeze remains until burn rate is controlled and a remediation plan exists. Planned maintenance counts against availability. ADR-0028 defines these SLO classes and release controls.

---

## 10. Kafka Reliability

- Kafka runs as one platform-managed shared cluster per environment.
- TLS, authentication, ACLs, quotas, and topic ownership are mandatory.
- In v1 no runtime Schema Registry is deployed. Git is the Protobuf source of truth, and every pull request runs Buf `STANDARD` lint and `FILE` breaking checks against `main`. Protobuf field numbers must never be reused.
- Event flows requiring runtime or dynamic schema discovery remain gated on a later Schema Registry ADR with an actual consumer and operating model.

### Transactional Outbox

The following code is prohibited:

```
repository.save(entity);
kafkaTemplate.send(topic, event);

```

Correct approach:

```
Database Transaction
├── Save Aggregate
└── Save Outbox Record

Outbox Relay
└── Publish to Kafka

```

### Consumer

- Assume at-least-once delivery.
- The inbox/processed-message record must be updated in the same local transaction as the business effect.
- The consumer must be idempotent.
- The offset is committed only after a durable result.
- Retries are finite and retry topics are explicit.
- A dead-letter topic is mandatory.
- Poison messages must have an owner and a runbook.
- Consumer lag, retry count, and DLQ size must be monitored.
- A replay procedure must be defined before production.
- The partition key is based on aggregate ID.
- Ordering is guaranteed only within the same key.
- The default outbox relay uses polling with `SKIP LOCKED`. Debezium/CDC is introduced only through an ADR and a proven scale requirement.

---

## 11. Redis

- Redis ownership belongs to one service, and shared business caches across microservices are prohibited.
- A shared physical cluster is allowed only with independent credentials/ACLs, namespaces, quotas, and eviction policies per service.
- Keys must have an explicit namespace.
- TTL must be explicit.
- Cache misses must have valid behavior.
- Redis is not a source of truth unless defined by a separate ADR.
- Distributed locks are allowed only with a proven need and a fencing strategy.
- Cache stampedes must be controlled.
- Very large objects must not be stored in Redis.
- Sensitive data must not be stored without encryption and a retention policy.

---

## 12. Prohibited Coding Practices

- Business logic in a controller or Kafka listener
- Importing a JPA entity from another service or module
- Shared database or shared schema between microservices
- Cross-service database joins
- Network calls inside transactions
- Long synchronous chains
- Kafka request/reply for ordinary queries
- Publishing directly to Kafka after commit without an outbox
- Non-idempotent consumers
- Unlimited retries
- Unlimited timeouts
- Arbitrarily enlarging the connection pool
- Broad EAGER mappings
- Enabling Open Session in View
- `SELECT *`
- Queries without bounds or pagination
- Using `Thread.sleep`
- Sending logs directly to a network/remote sink on the request thread, or logging sensitive payloads
- Metric labels containing `userId`, `tenantId`, `traceId`, or raw URLs
- A shared `common` package for business models
- Ambiguous names such as `Manager`, `Helper`, `Util`, or `GenericService`
- WebFlux, Reactor, `Mono`, or `Flux`
- A framework-dependent domain model
- Field injection or service locator
- Circular dependencies or using `@Lazy` to hide them
- Logging `Authorization`, cookies, tokens, passwords, secrets, PII, or full payloads
- Preview/Incubator APIs in production without an ADR
- Shared business caches in Redis across microservices
- Trusting user/tenant context supplied by the frontend without validation by the BFF and service
- Implementing a WAF in a Spring filter, interceptor, or controller
- Directly exposing an internal microservice to the internet
- Making the Traefik dashboard public
- Using a container image tagged `latest`
- Running a container as root without an approved necessity
- Placing secrets in Helm values, Git, or images
- Deploying without probes, resource requests/limits, and a rollback strategy
- Using HPA without load tests and a valid metric
- Running a production workload outside Istio Ambient Mesh without an approved exception
- Permanently using mTLS in `PERMISSIVE` mode
- Using the Kubernetes `default` ServiceAccount
- An allow-all `AuthorizationPolicy`

---

## 13. Observability

Every service must provide the three signals:

- Metrics
- Structured logs
- Distributed traces

Standards:

- OpenTelemetry
- Micrometer Observation
- W3C `traceparent` and `tracestate`
- JSON logs on stdout
- Trace context across REST, gRPC, Kafka, and workers
- Low-cardinality, bounded metric labels
- SLI/SLO, error budgets, and burn-rate alerts for critical journeys
- Configurable trace sampling and bounded baggage/header sizes

Base log fields:

```
timestamp
level
service.name
service.version
environment
traceId
spanId
correlationId
eventCode

```

Sensitive data, tokens, passwords, PII, SQL parameters, and full payloads must not be recorded in logs.

### 13.1 Logging and PII Redaction Rules

The project logging principle is **allow-listing**: only pre-approved fields may be logged. Failure to recognize a field does not make it safe to log.

#### Information That Must Never Be Logged Raw

- Passwords, PINs, OTPs, and security answers
- Access tokens, refresh tokens, ID tokens, and API keys
- `Authorization`, `Cookie`, `Set-Cookie`, and session IDs
- Private keys, encryption keys, secrets, and database connection strings
- Payment-card data, bank-account data, and payment payloads
- Government identifiers, health information, biometric data, and data about vulnerable individuals
- Full request/response bodies
- SQL parameters and bind values
- Complete gRPC metadata or Kafka headers
- Exception messages received from third parties when they may contain payloads or secrets

#### Ordinary PII

Names, email addresses, phone numbers, IP addresses, physical addresses, and user identifiers may be logged only for an approved operational or audit use case.

Allowed methods:

- Masking, such as `a***@example.com`
- Showing the last four digits in approved cases
- Tokenization
- Pseudonymization using HMAC with a managed key when correlation is required

A plain hash without a salt/key is insufficient for guessable data such as email addresses or phone numbers.

#### Operational Rules

- Redaction must happen as close as possible to log creation.
- A centralized redaction filter must also exist in the logging pipeline as defense in depth.
- Logging APIs must be structured. String concatenation for objects or payloads is prohibited.
- Input data must be sanitized or encoded for `CR`, `LF`, and malicious delimiters to prevent log injection.
- MDC/context may contain only bounded fields such as `traceId`, `spanId`, `correlationId`, and `eventCode`.
- Raw `userId` and `tenantId` are prohibited in metric labels and may appear in logs only according to the data-classification policy.
- Stack traces are allowed for unexpected errors, but exception messages and nested causes must be checked for secrets and PII.
- Debug logging is disabled by default in production. Temporary enablement must be time-bound and audited.
- Retention, access, encryption at rest/in transit, and data residency for logs must be based on data classification.
- Access to the log repository must follow least privilege, and access itself must be audited.
- Audit, security, and operational logs may require separate retention and access policies for compliance.
- A logging failure must not fail the primary request, but abnormal stopping or dropping of logs must trigger an alert.
- Applications write only structured JSON to stdout. An agent/collector handles buffering and delivery to the central backend.

#### Mandatory Tests

- Unit tests for masking and redaction
- Tests confirming tokens, passwords, cookies, PII, and payment data are not logged
- CR/LF and log-injection tests
- Error-handler and exception-mapper tests
- Periodic scanning of staging logs using synthetic canary secrets/PII
- A quality gate preventing raw request/response-body logging

---

## 14. Testing and Quality Gates

Every service must have at least the following tests:

- Domain unit tests
- Application use-case tests with fake ports
- Architecture tests with ArchUnit
- PostgreSQL integration tests with Testcontainers
- Kafka integration tests
- gRPC contract tests
- OpenAPI/Protobuf/schema compatibility tests
- Outbox and idempotency tests
- Duplicate, retry, restart, and DLQ tests
- Migration tests
- Authorization tests
- Logging/PII-redaction and log-injection tests
- Istio mTLS, workload-identity, and `AuthorizationPolicy` tests
- Timeout and cancellation tests
- Load tests for critical paths
- BDD acceptance tests for critical business flows
- Frontend unit/component tests with Vitest and React Testing Library
- Playwright UI/E2E tests for critical user journeys

## 14.1 BDD and Acceptance-Test Policy

BDD is a **process for analyzing and specifying system behavior**, not a replacement for unit, integration, or contract tests.

Rules:

- Only important scenarios understandable by Product, QA, and Development are written in Gherkin.
- Scenarios describe business behavior, not selectors, button IDs, SQL, or implementation details.
- BDD is used for critical happy paths, important rules, permissions, billing, subscriptions, and multi-service workflows.
- Using Cucumber for every endpoint, simple CRUD operation, or every edge case is prohibited.
- BDD acceptance tests should run at the API/system level whenever possible. Running every scenario through the UI makes the suite slow and flaky.
- Features use a limited tag set such as `@critical`, `@smoke`, and `@regression`.
- Step definitions must be thin, with test business behavior kept in reusable drivers/clients.
- Java uses Cucumber-JVM on the JUnit Platform.
- Feature files are part of the feature acceptance contract and must be updated when business behavior changes.

Example:

```
Feature: Subscription activation

  Scenario: Activate subscription after successful payment
    Given a customer has a pending subscription
    When the related payment is completed
    Then the subscription becomes active
    And the activation business effect is applied once despite duplicate delivery

```

## 14.2 Playwright for UI and E2E

**Playwright Test + TypeScript** is the primary browser-testing tool for the frontend.

Use cases:

- Critical user journeys such as login, registration, checkout, subscription, and account management
- Real React-to-BFF integration testing
- UI smoke tests after deployment
- Cross-browser testing on Chromium, Firefox, and WebKit for selected paths
- Authentication, redirect, error-state, and UI-permission checks

Playwright rules:

- Use Playwright Test natively. Connecting every test to Cucumber is not the default.
- Only selected BDD scenarios may share a Playwright driver when the organization requires it.
- Prefer locators based on roles, labels, and accessible text.
- Fragile CSS/XPath selectors and fixed `waitForTimeout`/sleep calls are prohibited.
- Use web-first assertions and auto-waiting.
- Tests must be independent, parallel-safe, and use isolated test data.
- Create test data through APIs/fixtures rather than navigating through the UI in every setup.
- Save screenshots, traces, and videos only on failure or retry.
- Retries must not hide flaky tests. Every flaky test requires an owner and a remediation deadline.
- Use page objects only for repeated interactions. Very large page objects are prohibited.
- Playwright does not replace component unit tests, contract tests, or backend tests.

Suggested frontend structure:

```
frontend/
├── src/
├── tests/
│   ├── e2e/
│   ├── smoke/
│   ├── fixtures/
│   └── pages/
├── playwright.config.ts
└── package.json

```

## 14.3 Mandatory CI/CD Path

The service pipeline and system release pipeline run at two levels.

### Per-Service Pipeline

```
Unit Tests
    ↓
Integration Tests
    ↓
Contract Tests
    ↓
Schema Compatibility Check
    ↓
SAST / Dependency / Secret Scan
    ↓
Helm / Kubernetes Validation
    ↓
Container Build
    ↓
SBOM + Image Signing + Container Image Scan

```

### System Acceptance and Release Pipeline

```
Deploy to Staging
    ↓
Backend Smoke Tests
    ↓
BDD Acceptance Tests
    ↓
Playwright Critical UI/E2E Tests
    ↓
Deploy to Production
    ↓
Production Smoke Tests

```

No stage may run if the preceding stage fails.

### Unit Tests

- Test domain models, value objects, aggregates, and domain services.
- Test application use cases with fake or mock ports.
- Test business rules without a Spring context.
- Tests must be fast, deterministic, and independent of networks and databases.

### Integration Tests

- PostgreSQL, Kafka, and Redis with Testcontainers
- Flyway migration tests
- Persistence-adapter tests
- Transactional-outbox tests
- Idempotent-consumer tests
- Timeout, retry, and error-handling tests at the adapter level

### Contract Tests

- Verify BFF compatibility with gRPC providers.
- Test REST/OpenAPI contracts.
- Test producer and consumer Kafka contracts.
- Prevent breaking changes in contracts shared across teams.

### Schema Compatibility Check

This stage controls changes to:

- OpenAPI
- Protobuf
- Avro or event schemas
- Flyway migrations
- Kafka event versions

Removing fields, changing types, reusing Protobuf field numbers, or introducing incompatible event changes without a new version is prohibited.

- Protobuf is checked with `buf lint` and `buf breaking`.
- OpenAPI uses a compatibility-diff tool, and event schemas use registry compatibility checks.

### Security Scan

Pre-build controls:

- SAST
- Dependency vulnerability scan
- Secret scan
- Gradle dependency verification
- License-policy check when required by the organization

Post-build controls:

- Generate an SBOM.
- Sign the image and record provenance.
- Scan the container image.
- Prevent deployment of images with vulnerabilities exceeding the approved policy.

### Helm and Kubernetes Validation

Before container build and deployment:

- `helm lint`
- Render charts for all environments.
- Kubernetes schema validation
- Policy checks for security contexts, resources, probes, and network policies
- Secret scans on rendered manifests
- Gateway API route and Traefik middleware checks
- WAF rule and exception tests
- `istioctl analyze` and validation of `PeerAuthentication`, `AuthorizationPolicy`, and waypoint resources
- Ambient enrollment and independent workload ServiceAccount checks
- Manifest diff against the deployed version

### Container Build

- Build only after all tests and scans pass.
- The build must be reproducible.
- Images must be tagged with version and Git commit.
- Containers run as a non-root user.
- Use an approved minimal base image.
- Secrets must not be embedded in images.

### Deployment

- Deploy first to a non-production environment.
- Run migrations with a controlled strategy.
- Readiness and liveness probes are mandatory.
- Rollouts must support rollback.
- Production uses the exact artifact produced by the same pipeline. Rebuilding is prohibited.

### Smoke Test

After deployment, verify at least:

- Startup and readiness
- Health endpoint
- PostgreSQL, Kafka, and Redis connectivity
- One critical REST or gRPC flow
- Publishing and consuming an isolated test event using a test tenant/topic/marker that does not create real production data
- Basic authentication and authorization
- No increase in error or timeout rates
- Playwright smoke suite on critical UI flows

On smoke-test failure, the rollout must automatically stop or roll back.

Baseline pipeline:

```
./gradlew spotlessCheck compileJava test
./gradlew architectureTest integrationTest contractTest
./gradlew schemaCompatibilityCheck
./gradlew spotbugsMain dependencyCheckAnalyze
docker build -t service:${GIT_SHA} .
# container scan
# deploy
# backend smoke test
# BDD acceptance test
pnpm exec playwright test --grep @critical
# production smoke test

```

Exact task names may vary by project plugins, but the quality-gate order is fixed.

---

## 15. Code-Generation Rules

Before changing code, the AI/engineer must:

1. Identify the bounded context and owner of the use case.
2. Define the required inbound and outbound ports.
3. Place the business rule in Domain/Application.
4. Implement transport and persistence only in adapters.
5. Decide whether the interaction is synchronous or event-driven.
6. Use the outbox for state changes that publish events.
7. Explicitly define timeout, retry, idempotency, and transaction boundaries.
8. Add migrations, tests, and observability in the same change.
9. Update architecture tests.
10. Add no dependency without justification and compatibility review.
11. Keep Dockerfile, Helm chart, probes, resources, and network policies aligned with service changes.
12. Review related Traefik/Gateway API routes and WAF rules.
13. Add or update BDD acceptance scenarios for critical behavior changes.
14. Add or update Playwright tests for critical UI-flow changes.
15. Review and test every new log statement for PII, secrets, tokens, and log injection.
16. Connect dependencies only through constructor injection and architecture ports.
17. Add or update the service ServiceAccount, Ambient enrollment, and required `AuthorizationPolicy`.
18. For every new service-to-service interaction, define source/destination identities and positive/negative policy tests.

## 15.1 Files Must Remain Small and Single-Purpose

### Mandatory Examples

- Each domain entity has its own file.
- Each value object has its own file.
- Each domain event has its own file.
- Each DTO has its own file.
- Each command has its own file.
- Each domain exception has its own file unless it is only a simple alias with no behavior.
- Each port or interface has its own file.
- Persistence models follow aggregate/query needs. A mandatory one-table/one-model mapping is prohibited.
- Each repository implementation stays in its own file.
- Technical adapters such as Kafka, Redis, outbox, and inbox are separated.

Every task output must include:

```
- Changed bounded context/module
- Contracts changed
- Database migration
- Transaction boundary
- Timeout/deadline behavior
- Kafka event and idempotency behavior
- Security impact
- Observability added
- Tests executed
- Rollback considerations

```

---

## 16. Mandatory Production Decisions

The original mandatory production decision gates have accepted ADRs:

1. Multi-tenancy and data isolation follow ADR-0002.
2. The dedicated Caddy and Coraza WAF follows ADR-0024.
3. Istio trust, CA hierarchy, and namespace enrollment follow ADR-0025.
4. The v1 no-registry and Git plus Buf compatibility policy follows ADR-0026.
5. RPO/RTO, backup/PITR, and cold disaster recovery follow ADR-0027.
6. Production SLO classes and error-budget release policy follow ADR-0028.

Their implementation-specific gates remain mandatory where the corresponding ADR explicitly leaves a value, product, credential, runbook, or manifest unresolved. An agent must not infer those details from the existence of the accepted architecture-level decision.

ADR-0036 through ADR-0040 additionally govern versioned database Notification templates, Liara SMTP, in-repository GitOps, OpenBao 2.6.1, Identity Tenant and MFA runtime, online Authorization, and the semantic-quota production gate.

---

## 17. Definition of Done

A capability is complete only when:

- The business rule is in the correct layer.
- The contract is versioned.
- A database migration exists.
- Query and pool behavior have been reviewed.
- Deadlines and timeouts are defined.
- Event publication uses the outbox.
- Consumers are idempotent.
- Metrics, logs, and traces are present.
- Logging and error handling are tested for PII redaction, secret leakage, and log injection.
- DI rules, absence of field injection, and absence of circular dependencies are verified.
- The workload is enrolled in Ambient Mesh and has an independent ServiceAccount.
- mTLS is in `STRICT` mode and positive/negative `AuthorizationPolicy` tests pass.
- Unit, integration, contract, and architecture tests pass.
- Container and security scans pass.
- Helm lint, rendering, and Kubernetes policy checks pass.
- Probes, resources, and network policies are valid. HPA/PDB are required only for workloads with SLA/replication needs.
- If the public surface changed, Traefik routes and WAF policies were tested in staging.
- Related BDD acceptance tests pass.
- Critical Playwright UI/E2E tests pass.
- Post-deployment smoke tests pass.
- No prohibited rule in this document is violated.

## 18. Code Comment Rules

- Comments explain reasons, constraints, trade-offs, or decisions not obvious from the code, rather than restating obvious behavior.
- JavaDoc is used for public APIs, ports, contracts, extension points, and classes with non-trivial lifecycle or invariants.
- There is no requirement for a comment or explanation at the top of **every file**. Such a rule creates noise and stale documentation.
- Comments and JavaDoc inside code are written in English.
- Long architecture explanations belong in ADRs and Markdown documentation.
- Duplicate comments, valueless generated documentation, and comments that merely repeat code names are prohibited.

---

## 19. Kubernetes, Helm, Traefik, WAF, and Istio

### 19.1 Production Runtime Architecture

```
Internet
  -> CDN / External Load Balancer
  -> Traefik Gateway / Ingress Controller
  -> Dedicated Caddy + Coraza Edge WAF
  -> Web BFF
  -> Internal Microservices

```

- Only the BFF and genuinely public APIs are accessible from outside the cluster.
- Internal microservices use only `ClusterIP` services and are not directly exposed.
- The WAF is not implemented in Java code. It is a dedicated edge-layer control between Traefik and the BFF.
- Traefik is responsible for routing and must not contain business logic.
- Direct Internet-to-BFF and Traefik-to-BFF application paths are prohibited.

### 19.2 Kubernetes

Each workload has only the resources it requires. The following baseline is applied according to workload type:

```
Deployment
Service
ServiceAccount
ConfigMap
Secret reference
NetworkPolicy
# HPA/PDB only when replication/SLA requires them
# HTTPRoute/GRPCRoute only for the BFF or a public API

```

Mandatory rules:

- Images are immutable and published with a version/Git SHA. The `latest` tag is prohibited.
- `resources.requests` and `resources.limits` are mandatory for CPU and memory.
- `startupProbe`, `readinessProbe`, and `livenessProbe` are defined separately.
- Liveness must not fail because PostgreSQL, Kafka, or Redis is temporarily unavailable.
- Readiness succeeds only when the workload is genuinely ready to receive traffic.
- Sensitive production services have at least two replicas.
- `PodDisruptionBudget` and `topologySpreadConstraints` are defined for availability.
- Containers run as non-root and use a read-only root filesystem where possible.
- Privileged containers and `hostPath` are prohibited without a security ADR.
- Secrets must not exist in Git, images, or Helm values. They are obtained through External Secrets Operator with OpenBao according to ADR-0011.
- RBAC follows least privilege, with an independent ServiceAccount per workload.
- `NetworkPolicy` uses deny-by-default, and the selected CNI must enforce it.
- Graceful shutdown coordinates `terminationGracePeriodSeconds` with Spring shutdown behavior.
- HPA uses valid metrics. Kafka consumers use KEDA or an external metrics adapter rather than raw HPA without a metric source.
- Autoscaling does not replace load testing and capacity planning.

### 19.3 Helm

Helm is the standard tool for deploying and upgrading Kubernetes resources.

Suggested structure:

```
deploy/
└── helm/
    └── service-name/
        ├── Chart.yaml
        ├── values.yaml
        ├── values-dev.yaml
        ├── values-staging.yaml
        ├── values-prod.yaml
        └── templates/
            ├── deployment.yaml
            ├── service.yaml
            ├── serviceaccount.yaml
            ├── configmap.yaml
            ├── hpa.yaml
            ├── pdb.yaml
            ├── networkpolicy.yaml
            ├── httproute.yaml
            └── servicemonitor.yaml

```

Helm rules:

- Use one **Company Application Chart or Helm Library Chart** for shared standards.
- Copying and pasting complete charts across services is prohibited.
- Each environment has separate values.
- Values may contain secret references only, never secret values.
- `helm lint`, template rendering, and Kubernetes schema validation run in CI.
- Application versions and chart versions are managed separately.
- Upgrades use only versioned artifacts.
- Complex migration Helm hooks are prohibited without a rollback plan.
- Final manifests must pass a policy engine before deployment.

### 19.4 Traefik

Traefik has the following edge responsibilities:

- TLS termination and HTTP-to-HTTPS redirect
- Routing to the BFF and public APIs
- HTTP/2 and gRPC routing
- Request-size limits
- Rate limiting
- Security headers
- Access logs, metrics, and trace propagation
- Canary/weighted routing when required
- Routing public application traffic through the dedicated WAF tier

Traefik rules:

- **Kubernetes Gateway API** is the default routing choice for the new project.
- Traefik CRDs are used only for proprietary capabilities such as middleware.
- The Traefik dashboard is not public and is accessible only from the management network/VPN.
- Public catch-all routes are prohibited.
- Routes must define explicit hostnames and paths.
- Traefik timeouts must align with service deadline budgets.
- Full request/response bodies must not be recorded in edge logs.
- Rate limiting must be designed for SaaS requirements. IP-only rate limiting is insufficient.

### 19.5 Software WAF

Production uses a dedicated stateless Caddy and OWASP Coraza v3 tier with OWASP Core Rule Set 4.x LTS. Traefik sends public application traffic to this tier, and the tier forwards only to `web-bff`. NetworkPolicy and Istio authorization deny direct Internet-to-BFF and Traefik-to-BFF application paths.

The WAF uses two replicas when at least two worker nodes are schedulable. A single replica is allowed only when the cluster physically has one worker.

The initial CRS paranoia level is `PL1`. Rollout proceeds from `DetectionOnly`, through bounded tuning, to blocking. Detection-only operation lasts at least 7 days of representative traffic before blocking is eligible.

WAF rules:

- Rule changes require an explicit GitOps pull request.
- Images and rule artifacts are pinned by exact version and digest.
- Automatic CRS updates are prohibited.
- Request-body inspection is bounded; large-upload endpoints require a later endpoint-specific policy.
- False positives use limited, documented exceptions. Disabling the complete rule set is prohibited.
- Logs record bounded rule IDs and dispositions, not sensitive payloads.
- Emergency bypasses are limited, time-bound, and audited.
- The WAF does not replace Traefik coarse rate limiting, Identity semantic rate limiting, validation, authentication, authorization, or secure coding.

ADR-0024 defines the selected WAF topology and policy.

### 19.6 In-Repository GitOps Layout

ADR-0037 keeps v1 desired state in this repository under `deploy/`, with `deploy/clusters/staging` and `deploy/clusters/production` as Argo CD roots. Service, infrastructure, WAF, mesh, data-sidecar, and policy configuration is versioned and reviewable there. A later repository extraction must preserve the same directory semantics and history.

### 19.7 Istio Ambient Service Mesh — Mandatory

Istio Ambient Mode is a mandatory production baseline.

Reasons for the decision:

- All internal communication must use automatic mTLS.
- Workload certificates must be issued and rotated automatically.
- Every workload must be identified through a trusted identity.
- Service-to-service authorization must be based on ServiceAccount/workload identity.
- Centralized security policy is required across multiple namespaces and teams.
- The architecture must support Zero Trust and compliance requirements.

Architecture:

```
Istio Ambient Mode
├── istiod
│   ├── Certificate Authority / identity control plane
│   ├── Policy distribution
│   └── Configuration distribution
├── ztunnel on each node
│   ├── transparent L4 mTLS
│   ├── workload identity enforcement
│   ├── L4 authorization
│   └── L4 telemetry
└── Waypoint proxy
    └── only for workloads/services requiring L7 authorization, routing, or telemetry

```

#### Production Trust and CA Hierarchy

Production uses `trustDomain = prod.sajtech.internal` and `meshID = platform-prod`.

The CA hierarchy is an offline Root CA, a Production Cluster Intermediate CA, and Istio workload certificates. The Root CA lifetime is 10 years. Its private key is never stored in Kubernetes or OpenBao; two encrypted offline copies are kept in separate physical locations, and routine production operations have no root-key access.

The cluster intermediate lifetime is 1 year. Rotation begins 90 days before expiry with at least 30 days of CA overlap. Its private key exists only in `istio-system` with Kubernetes encryption at rest and highly restricted RBAC. Workload certificate TTL is 24 hours, with automatic Istio rotation. ADR-0025 defines this trust model.

#### Version Baseline

- Baseline at the time of this review: **Istio 1.30.3**
- Use only supported patches from the `1.30.x` branch.
- Pin the version in the Technology Baseline and Helm values.
- Upgrades use revision-based rollout, canary deployment, and a rollback plan.
- The Kubernetes version must be inside the officially supported range for the selected Istio version.

#### Enrollment

- Namespace enrollment is explicit and opt-in; creating a namespace does not enroll it automatically.
- `platform-edge` and `platform-apps` are enrolled.
- `platform-data` is enrolled selectively after per-workload compatibility and security review.
- `istio-system` hosts the control plane.
- `argocd`, `observability`, and `kube-system` are not enrolled initially.
- A workload required by the accepted enrollment model to be in the mesh may remain outside it only through a security ADR and a time-bound exception.
- Enrollment status must be enforced in CI/CD or through a policy engine.
- The BFF and all internal microservices must be inside the mesh.
- Infrastructure components such as Kafka/PostgreSQL are handled through separate ADRs according to deployment model and compatibility. Their native TLS/SASL/ACL controls remain mandatory.

#### mTLS

- `PeerAuthentication` in production must be `STRICT`.
- `PERMISSIVE` is allowed only during a limited, time-bound migration window.
- Plaintext east-west traffic is prohibited.
- Certificates must not be manually managed by applications.
- Application code must not carry custom file-based certificates for internal mesh communication unless approved through an ADR.
- The trust domain and trust anchors are managed by the Platform/Security team.
- Certificate rotation must occur without manually restarting applications.

Baseline example:

```
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: default
  namespace: istio-system
spec:
  mtls:
    mode: STRICT

```

#### Workload Identity

- Each workload identity is derived from its Kubernetes ServiceAccount.
- Every BFF, microservice, and worker has an independent ServiceAccount.
- Using the `default` ServiceAccount in production is prohibited.
- Two business services must not share a ServiceAccount.
- Authorization is based on namespace, ServiceAccount, and principal, not IP address or pod name.
- Changing a ServiceAccount is a security change and requires review.

Example principal:

```
cluster.local/ns/billing/sa/billing-service

```

#### AuthorizationPolicy

Baseline policy:

```
Default deny
    +
Explicit allow by source workload identity

```

Rules:

- Define deny-by-default for every sensitive namespace or workload.
- Allow policies must be minimal and based on the source principal/namespace.
- Broad policies such as allowing all namespaces are prohibited.
- Policies are versioned, code-reviewed, and tested.
- A new policy is first reviewed in dry-run/audit mode, then enforced in stages.
- Every policy change requires positive and negative tests.
- L4 policies are enforced by ztunnel.
- A policy that matches method, path, header, or claim requires a waypoint and correct `targetRefs`.

Restricted allow example:

```
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: allow-bff
  namespace: billing
spec:
  selector:
    matchLabels:
      app: billing-service
  action: ALLOW
  rules:
    - from:
        - source:
            principals:
              - cluster.local/ns/frontend/sa/web-bff

```

#### Waypoint

A waypoint is not automatically created for every service.

A waypoint is mandatory only when one of the following is required:

- L7 authorization based on gRPC method, HTTP method, path, or header
- `RequestAuthentication` and JWT policy inside the mesh
- L7 traffic routing or traffic splitting
- L7 telemetry enrichment
- L7 external authorization

Rules:

- Waypoint requirements are recorded per service/namespace.
- Services requiring only mTLS and L4 identity use ztunnel.
- Do not create one oversized shared waypoint for unrelated domains.
- Waypoint CPU, memory, and latency costs must be load-tested.

#### Traefik, WAF, and Istio

Responsibility split:

```
North-South:
CDN / Load Balancer
 -> Traefik
 -> Dedicated Caddy + Coraza WAF
 -> Web BFF

East-West:
Istio Ambient Mesh
 -> ztunnel
 -> optional Waypoint

```

- Traefik remains the cluster entry gateway.
- Istio does not replace Traefik or the WAF.
- Traefik routes public application traffic through the dedicated WAF; it does not route that traffic directly to the BFF.
- Internal gRPC does not pass through Traefik.
- Traefik-to-WAF and WAF-to-BFF communication use explicit mesh identity and policy; direct Traefik-to-BFF application traffic is denied.

#### NetworkPolicy

- Istio does not replace Kubernetes NetworkPolicy.
- Both are used as defense in depth.
- NetworkPolicy is deny-by-default and requires a CNI that enforces it.
- Istio controls identity and mTLS. NetworkPolicy controls network reachability.

#### Kafka, PostgreSQL, and Redis

- Istio does not replace Kafka's native TLS/SASL/ACL controls.
- Kafka continues to use independent TLS, authentication, ACLs, and quotas.
- PostgreSQL and Redis continue to use TLS and independent credentials.
- Adding stateful platform components to the mesh requires compatibility, latency, and failure-mode testing.
- Native data-store authentication and authorization must not be removed.

#### Traffic Policy Ownership

To prevent duplicate policies:

- Primary deadlines and timeouts are managed in the application/gRPC contract.
- Primary retries are defined in the application and only for idempotent operations.
- Retrying in both Istio and the application at the same time is prohibited.
- Circuit breaking/outlier detection is enabled only with an explicit owner and ADR.
- Fault injection is allowed only in test environments and chaos engineering.
- Istio must not implement business-level failure handling.

#### Observability

Istio telemetry complements application telemetry; it does not replace it.

- Trace context must be propagated end to end.
- Mesh metrics must be correlated with application SLI/SLOs.
- High-cardinality telemetry is prohibited.
- Proxy access logs are also subject to the Logging and PII Redaction Rules.
- Sensitive payloads and headers must not be recorded in Envoy/waypoint logs.
- ztunnel, waypoint, and istiod require alerts and dashboards.

Required metrics:

```
mTLS success/failure
authorization deny count
ztunnel health
waypoint latency/error
istiod availability
certificate issuance/rotation failure
xDS/config distribution error

```

#### Tests and Quality Gates

Before production:

- Strict mTLS test
- Plaintext-rejection test
- Correct identity test for every ServiceAccount
- Positive authorization test
- Negative authorization test
- Cross-namespace access test
- Certificate-rotation test
- ztunnel failure test
- Waypoint failure test for L7 services
- Control-plane upgrade/rollback test
- Load test for latency and resource overhead
- `istioctl analyze`
- Policy validation in CI
- Smoke test after upgrades

#### Prohibited

- Default sidecar injection for all services without an ADR
- Permanent use of `PERMISSIVE`
- Using the `default` ServiceAccount
- Allow-all `AuthorizationPolicy`
- Trusting IP addresses instead of workload identity
- Creating waypoints for all namespaces without an L7 need
- Retrying simultaneously in Istio and the application
- Removing Kafka's native TLS/SASL/ACL controls because a mesh exists
- Exposing the istiod, ztunnel, or waypoint management surface publicly
- Changing policies directly in the cluster without Git/review

## 20. Agent Communication and Reporting Contract

The mandatory communication and reporting rules are maintained in [Agent Communication and Reporting Contract](https://file+wsl-002elocalhost.vscode-resource.vscode-cdn.net/Ubuntu/home/coder/workspace/platform/docs/engineering/agent-communication-and-reporting.md).

Any agent reading this backend architecture specification must also read and follow that companion document before planning, changing, reviewing, or reporting backend work. Repository-level instructions in [`AGENTS.md`](https://file+wsl-002elocalhost.vscode-resource.vscode-cdn.net/Ubuntu/home/coder/workspace/platform/AGENTS.md) remain mandatory and are intentionally retained there.