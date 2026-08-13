# Java Coding Standards

This document is the canonical implementation-level coding standard for Java backend services. It complements `../architecture/backend-engineering.md` and accepted ADRs. When a rule here conflicts with a later accepted ADR, the Decision Register and the superseding ADR win.

The goal is not stylistic uniformity for its own sake. These rules keep Domain/Application code framework-independent, make reviews predictable, reduce hidden runtime risk, and make important rules enforceable by automated quality gates.

## 1. Architecture and dependency direction

Backend services use DDD + Hexagonal Architecture. Clean Architecture is used only to enforce inward dependency direction.

Allowed high-level direction:

```text
infrastructure -> application -> domain
interfaces     -> application -> domain
configuration  -> application/domain + adapters for composition
```

Mandatory rules:

- Domain code depends only on the JDK and explicitly approved domain primitives.
- Domain MUST NOT depend on Spring, JPA/Hibernate, jOOQ, Kafka, Redis, gRPC, Protobuf, PostgreSQL, Kubernetes, Istio, HTTP clients, or concrete adapters.
- Application may depend on Domain and abstract ports; it MUST NOT depend on Infrastructure, Interfaces, concrete adapters, Spring context lookup, persistence entities, generated jOOQ records, or transport DTOs.
- Infrastructure implements persistence, messaging, external-client, cache, worker, security, and technical outbound responsibilities.
- Interfaces contain inbound REST/gRPC/Kafka adapters and mapping/validation only.
- Business logic in controllers, gRPC handlers, Kafka listeners, repositories, persistence mappers, serialization mappers, or configuration classes is prohibited.
- A domain event and a Kafka/Protobuf integration event are separate types with explicit mapping.
- Transport, persistence, and provider DTOs MUST NOT become Domain models.
- Cross-service business/domain model sharing is prohibited. Shared libraries are limited to technical primitives with stable ownership and no bounded-context business semantics.

## 2. Canonical package structure

ADR-0007 is authoritative. Packages are feature-first and nature-separated:

```text
com.sajtech.<service>/
├── domain/<feature>/
│   ├── aggregate/
│   ├── entity/
│   ├── valueobject/
│   ├── event/
│   ├── exception/
│   ├── repository/
│   └── service/
├── application/<feature>/
│   ├── command/
│   ├── query/
│   ├── dto/
│   ├── port/in/
│   ├── port/out/
│   ├── usecase/
│   └── saga/              # only when a real saga exists
├── infrastructure/<feature>/
│   ├── persistence/
│   ├── cache/
│   ├── messaging/
│   ├── security/
│   ├── observability/
│   ├── client/
│   └── worker/
├── interfaces/<feature>/
│   ├── rest/
│   ├── grpc/
│   └── kafka/
└── configuration/<feature>/
```

Rules:

- Packages are created only when real code exists; empty taxonomy trees and `.gitkeep` placeholders are prohibited.
- Dumping-ground packages such as `common`, `util`, `helper`, `manager`, `misc`, or `generic` are prohibited for business code.
- Ambiguous business type names such as `Manager`, `Helper`, `Util`, and `GenericService` are prohibited.
- Lowercase package names are required; hyphens and underscores in Java package segments are prohibited.
- A service repository must not invent a second competing architecture such as global `adapters/in` and `adapters/out` when ADR-0007's current package structure applies.

## 3. Files and type size

Keep files small enough to review and single-purpose enough to own one reason for change.

Normally each meaningful type has its own file, including:

- aggregate;
- entity;
- value object;
- domain event;
- domain exception;
- command/query;
- DTO;
- port/interface;
- persistence entity/record wrapper;
- mapper;
- repository adapter;
- controller/handler/listener;
- worker;
- configuration class.

A Java file normally contains one public top-level type. Nested/private types are acceptable when they are implementation details of the owning type and do not hide an independent responsibility.

Persistence models follow aggregate/query needs. A mandatory one-table/one-persistence-model mapping is prohibited; do not manufacture types merely to mirror tables when the aggregate/query model calls for a different shape.

Large constructor parameter lists, large switch statements over unrelated business concepts, very large mappers/controllers, or files with multiple independent responsibilities require redesign rather than cosmetic extraction into `Helper` classes.

## 4. Dependency injection and object construction

Spring IoC/ApplicationContext is the only DI container.

Mandatory rules:

- Required dependencies use constructor injection.
- Field injection and field `@Autowired` are prohibited.
- A single constructor does not require `@Autowired`.
- Setter injection is allowed only for a truly optional infrastructure-level dependency with a correct default; it is not a workaround for constructor size.
- Domain aggregates/entities/value objects/events are not Spring beans and carry no Spring annotations.
- Application use cases remain plain Java and are normally wired from `configuration` with `@Bean`.
- Adapters and configuration classes may use Spring annotations.
- `ApplicationContext`, `BeanFactory`, service locator, runtime bean lookup, and static global dependency registries are prohibited in Domain/Application.
- Application/use-case code MUST NOT instantiate real adapters with `new`; concrete composition belongs to configuration/bootstrap code.
- Circular dependencies are prohibited. `@Lazy` MUST NOT hide a cycle.
- Singleton beans are stateless or explicitly thread-safe. Mutable request/session state in singleton beans is prohibited.
- Request/session scopes are BFF-only and require explicit justification.
- Related configuration uses typed `@ConfigurationProperties`; scattered `@Value` for one logical configuration group is prohibited.
- Multiple implementations of a port are selected through explicit configuration and meaningful qualifiers, not classpath accident.
- `@Configuration(proxyBeanMethods = false)` is preferred when inter-bean method proxying is unnecessary.

## 5. Domain and application code

- Aggregates enforce their invariants at the mutation boundary.
- Domain services exist only for genuine domain behavior that does not naturally belong to one entity/value object/aggregate.
- Application use cases orchestrate domain behavior, ports, transaction boundaries, authorization invocation, and integration-event/outbox decisions; they do not absorb persistence/provider details.
- Repository interfaces representing aggregate persistence live in Domain under the owning feature. Do not duplicate the same repository abstraction as an Application outbound port merely to satisfy a template.
- Application outbound ports represent technical or cross-boundary dependencies not naturally modeled as Domain repositories.
- Public commands/queries/DTOs are explicit and bounded. Do not pass unstructured maps across application boundaries.
- Trusted tenant/user/workload identity is extracted and validated at the appropriate boundary; caller-supplied tenant context is never trusted by convention.

## 6. REST, gRPC, messaging, and error mapping

- External/browser REST contracts are OpenAPI-first/versioned through the BFF/public API boundary.
- Frontend TypeScript API clients are generated from the approved OpenAPI contract; hand-maintained duplicate transport models/endpoint clients are prohibited except for thin UI/domain wrappers around generated types.
- REST error responses use RFC 9457 Problem Details (or an explicitly versioned extension profile) and MUST NOT expose internal exception classes, stack traces, SQL/provider payloads, secrets, or unreviewed PII.
- Internal gRPC failures use stable gRPC status codes plus bounded reviewed metadata. Complete exception messages/metadata are not copied blindly.
- Every gRPC/HTTP call has a finite deadline/timeout. HTTP clients define finite connect and response/read budgets.
- Child deadlines fit inside the remaining parent request budget; cancellation is propagated where supported.
- Retries are finite, jittered where appropriate, restricted to safe/idempotent semantics, and owned by exactly one layer.
- No ordinary request/reply is implemented through Kafka.
- Integration events are mapped explicitly from Domain events or application decisions and are published through the transactional Outbox when local state and event publication form one business effect.
- Consumers assume at-least-once delivery and are idempotent; durable business effect and Inbox/dedup state commit together where required.

## 7. Persistence and transaction coding rules

Flyway is the only schema-change mechanism and JPA `ddl-auto` remains `validate`.

Mandatory rules:

- Domain models and JPA entities are separate.
- JPA entities/Spring Data repositories/generated jOOQ types remain Infrastructure concerns.
- Associations are LAZY by default; broad EAGER loading is prohibited.
- Fetch plans are explicit through appropriate projection/entity graph/join-fetch/query design.
- N+1 queries are prohibited.
- `SELECT *` is prohibited in production application SQL.
- Every multi-row production query has deterministic pagination or a hard bound.
- Sensitive/expensive queries require reviewed indexes and representative query-plan evidence.
- Transaction boundaries are short and explicit.
- Network I/O to gRPC/HTTP/Kafka/Redis/providers is prohibited inside a database transaction.
- Database locks are never held while performing remote I/O.
- Retries occur outside the failed transaction.
- Cascade/orphan-removal choices are reviewed against aggregate ownership.
- `equals`, `hashCode`, and `toString` MUST NOT traverse lazy associations unsafely.
- Hibernate/JPA batch size, fetch size, flush mode/frequency, and large write behavior are measured for performance-sensitive or bulk paths rather than copied as global magic values.
- Automatic database rollback is never assumed; expand -> migrate -> contract and rollback compatibility remain mandatory.

## 8. Concurrency and Virtual Threads

- Spring MVC + Virtual Threads is the default blocking-I/O model.
- Virtual Threads do not increase PostgreSQL connections, Redis capacity, provider quota, CPU, or memory.
- Constrained downstream work has explicit bounded concurrency/bulkheads and bounded/zero queues.
- CPU-heavy work is moved to bounded workers/jobs rather than multiplied across request virtual threads.
- `Thread.sleep` is prohibited as coordination, polling, synchronization, or test-wait logic. Scheduling/backoff uses the appropriate scheduler/clock/test primitive.
- A blanket ban on `synchronized` is prohibited on Java 25; JFR/load/soak evidence targets remaining native/FFM pinning, contention, and carrier/resource starvation.
- Shared mutable state requires explicit ownership, synchronization strategy, and concurrency tests.

## 9. Configuration and dependency rules

- Each independently deployable service owns `settings.gradle.kts`, `build.gradle.kts`, Gradle Wrapper, dependency verification metadata, contracts, source sets, container build, and deployment package.
- Java toolchain/release is 25 according to the Technology Baseline.
- Dynamic dependency versions (`+`, unbounded ranges) and unapproved production SNAPSHOT dependencies are prohibited.
- Dependencies are added only with a documented purpose and compatibility/security review.
- Spring Boot dependency management/BOM alignment is preferred; explicit version overrides require justification and compatibility tests.
- Secret values never appear in code, Git, Gradle properties committed to the repository, Helm values, images, fixtures, logs, or CI output.
- Configuration defaults must be safe. Production-only security controls cannot be disabled through an undocumented profile/property.

## 10. Logging and telemetry coding rules

Logging follows the allow-list model in `../architecture/reliability-and-observability.md` and ADR-0061.

- Applications write structured JSON to stdout; application request threads do not synchronously send logs directly to a remote log backend.
- Structured fields are used instead of string-concatenating domain/request objects or full payloads.
- Raw credentials, tokens, cookies, secrets, OTP/recovery codes, SQL binds, request/response bodies, complete gRPC/Kafka metadata, provider payloads, and unapproved PII are prohibited.
- Input-derived log fields are sanitized/encoded for CR, LF, and malicious delimiters to prevent log injection.
- Exception messages, nested causes, and third-party exceptions are treated as untrusted/sensitive until reviewed; safe event codes and bounded structured context are preferred.
- Debug/trace logging is disabled by default in production. Temporary elevation is time-bound, access-controlled, audited, and must not enable body/bind/credential logging.
- MDC/context contains only bounded approved fields such as trace/span/correlation IDs and event codes; it is not a general request-object store.
- Logging/collector failure MUST NOT fail the primary business request, but sustained drop/backpressure/export failure MUST be observable and alertable.
- Log-store access is least-privilege and audited; retention/access/residency follow data classification.
- Metric labels remain low-cardinality and never contain raw user/tenant/session/request IDs, trace IDs, raw URLs, or free-form error messages.

Every new or changed log statement is reviewed/tested for PII, secrets, tokens, log injection, cardinality, and operational usefulness.

## 11. Comments and JavaDoc

- Comments explain reasons, constraints, trade-offs, invariants, or non-obvious behavior; they do not restate code.
- JavaDoc is used for public APIs, ports, contracts, extension points, and non-trivial lifecycle/invariant behavior.
- There is no mandatory header comment per file.
- Source comments/JavaDoc are English.
- Long architecture explanations belong in ADRs/Markdown, not source comments.
- TODO/FIXME comments that represent required work have an owner/tracking reference or are removed before production completion; TODO is not a substitute for an accepted deviation or production gate.

## 12. Testability rules

- Domain/Application unit tests instantiate code directly and do not start Spring.
- Tests are deterministic, parallel-safe where intended, and independent of wall-clock sleeps/network dependencies unless that integration is the subject under test.
- Time-sensitive code uses injected/controllable clocks where practical.
- Test-only backdoors and local adapters are impossible to enable in staging/production.
- Flaky tests require an owner and remediation deadline; retries do not convert flakiness into success.
- Test data is isolated and does not rely on shared mutable global state.

## 13. Prohibited practices checklist

The following are prohibited unless a later accepted ADR explicitly permits them:

- business logic in controllers/listeners/repositories/mappers;
- framework-dependent Domain models;
- field injection/service locator/runtime bean lookup in Domain/Application;
- circular dependencies or `@Lazy` cycle hiding;
- business dumping-ground `common`/`util`/`helper`/`manager` packages;
- shared business/domain/persistence models across services;
- WebFlux/Reactor/`Mono`/`Flux` in backend services;
- direct cross-service database access or shared service schema/database;
- remote I/O inside DB transactions;
- unbounded queries/retries/timeouts/queues;
- broad EAGER loading, N+1, OSIV, `SELECT *`;
- direct `repository.save(...); kafkaTemplate.send(...)` instead of Outbox for one atomic business effect;
- non-idempotent consumers where duplicate delivery is possible;
- `Thread.sleep` for coordination/test synchronization;
- raw sensitive logging or request/response body logging;
- direct synchronous network logging from the request thread;
- dynamic/floating production dependency versions;
- Java Preview/Incubator APIs in production without accepted ADR + explicit enablement;
- hiding quality failures with broad suppressions, disabled tests, `ignoreFailures`, or blanket exclusions.

## 14. Automated enforcement mapping

Documentation is not proof of compliance. The executable enforcement contract is defined in `build-and-ci-quality-enforcement.md`.

At minimum:

| Rule class | Primary enforcement |
| --- | --- |
| dependency direction/package/cycles | ArchUnit |
| formatting/import/layout | Spotless |
| Java bug patterns | SpotBugs |
| framework misuse/unsafe logging/high-signal custom rules | Semgrep |
| dependency integrity | Gradle dependency verification/locks |
| tests/contracts/migrations | Gradle test/source-set tasks + Testcontainers/Buf/OpenAPI checks |
| container/Kubernetes/security policy | CI + image/Kubernetes policy gates |
| final evidence | GitHub Actions required checks + release pipeline |

A rule that is reliably machine-checkable SHOULD be enforced automatically rather than left only as prose.

## 15. Code-Generation Rules

Before changing or generating implementation code, the AI/engineer MUST execute this checklist. These requirements are canonical implementation rules; where a task-specific ADR is stricter, the ADR wins.

1. **Bounded context and owner** — identify the bounded context, capability, aggregate/use-case owner, and the service responsible for final business enforcement.
2. **Ports first** — define the required inbound and outbound ports before selecting concrete adapters or frameworks.
3. **Business rules inward** — place business behavior in Domain/Application; transport, persistence, serialization, framework annotations, and provider details stay outside.
4. **Adapters only for infrastructure** — implement REST/gRPC/Kafka entrypoints under `interfaces` and persistence/messaging/Redis/gRPC/provider integrations under `infrastructure`, composed from `configuration`.
5. **Interaction model** — decide whether each interaction is synchronous or event-driven and document the operation-level dependency criticality/fallback semantics.
6. **Transactional outbox** — when a local state change must emit an integration event as one business effect, persist the aggregate change and outbox record atomically; direct post-commit Kafka publication is not the atomicity mechanism.
7. **Failure and transaction semantics** — explicitly define deadlines/timeouts, cancellation, retry ownership, idempotency, concurrency/bulkheads, circuit-breaker applicability, and transaction boundaries.
8. **Change completeness** — add or update required Flyway migrations, unit/integration/contract/architecture/security tests, metrics, structured logs, traces, SLO/alerts, and runbook/evidence hooks in the same change when applicable.
9. **Architecture tests** — update ArchUnit or other architecture tests whenever package, dependency, module, layering, ownership, or prohibited-practice rules are affected.
10. **Dependency discipline** — add no dependency/plugin/tool without documented need, ownership, compatibility review, dependency verification, and security/license review where applicable. Prefer existing approved capabilities over overlapping libraries.
11. **Runtime/deployment alignment** — keep `Dockerfile`, Helm/GitOps manifests, probes, resource requests/limits, HPA/KEDA/PDB when applicable, ServiceAccount, NetworkPolicy, shutdown behavior, and configuration aligned with the service change.
12. **Public edge alignment** — when the public surface changes, review Traefik/Gateway API routing, Caddy/Coraza WAF rules/exclusions, upstream volumetric-DDoS controls, request limits, security headers, CORS/CSRF behavior, and staging edge tests.
13. **BDD acceptance** — add/update Gherkin/Cucumber scenarios for critical business behavior that Product/QA/Engineering should share; do not model trivial CRUD, selectors, or low-level implementation details as BDD.
14. **Critical UI flows** — add/update Playwright coverage for critical browser journeys affected by the change, using stable semantic locators, isolated fixtures/test data, web-first assertions, and explicit flake ownership.
15. **Log safety** — review/test every new or materially changed log statement and error mapping for secrets, credentials, tokens/cookies, PII/payment data, unsafe provider/exception text, CR/LF injection, payload leakage, and metric-label cardinality.
16. **Constructor injection and ports** — all required dependencies flow through constructors and architecture ports; use cases never create real adapters and Domain/Application never use `ApplicationContext`, `BeanFactory`, service locator, or runtime lookup.
17. **Workload identity and mesh policy** — when communication/runtime identity changes, add/update the dedicated Kubernetes ServiceAccount, Ambient enrollment, NetworkPolicy, and minimal Istio `AuthorizationPolicy`; keep default deny and strict mTLS intact.
18. **Service-to-service evidence** — every new/changed service-to-service edge must define source/destination identities, contract, deadline/failure semantics, dependency-policy registry entry, positive authorization test, negative authorization test, and observability.

A code-generating agent MUST NOT mark work complete merely because generated code compiles. Completion is evaluated against this checklist, the applicable Definition of Done, ADR-0069 enforcement, and the actual CI/test evidence.

### 15.1 Files Must Remain Small and Single-Purpose

The following are mandatory defaults unless a cohesive language/implementation constraint makes a combined file materially clearer:

- each domain entity/aggregate root has its own file;
- each value object has its own file;
- each domain event has its own file;
- each externally meaningful DTO/contract mapping type has its own file;
- each command and query has its own file;
- each domain exception has its own file when it has distinct semantics or behavior;
- each port/interface has its own file;
- each repository implementation has its own file;
- technical adapters for Kafka, Redis, outbox, inbox/idempotency, gRPC, HTTP/provider integration, and persistence remain separated by responsibility;
- persistence models are designed around aggregate/query needs; a mandatory one-table/one-model mapping is prohibited;
- large files, god classes, dumping-ground packages, and multi-responsibility adapters are refactored rather than justified by convenience.

A non-trivial task report MUST explicitly include:

```text
Changed bounded context/module:
Contracts changed:
Database migration:
Transaction boundary:
Timeout/deadline behavior:
Retry/cancellation/concurrency behavior:
Kafka/event and idempotency behavior:
Security impact:
Logging and PII impact:
Observability added or changed:
Build/CI/architecture enforcement changed:
Tests executed:
Rollback considerations:
```

Use `None`, `Not applicable`, or `Not verified` rather than omitting a field.
