# Java Coding Standards

This document is the canonical implementation-level coding standard for Java backend services. It complements `../architecture/backend-engineering.md`, the current Decision Register, and retained current ADRs.

The objective is enforceable correctness, clear ownership, security, and predictable reviews—not stylistic uniformity for its own sake. Rules that are reliably machine-checkable SHOULD be enforced by ArchUnit, Spotless, SpotBugs, Semgrep, dependency verification, contract checks, or CI.

## 1. Architecture and dependency direction

Backend services use DDD + Hexagonal Architecture. Clean Architecture is used only to enforce inward dependency direction.

```text
infrastructure -> application -> domain
interfaces     -> application -> domain
configuration  -> application/domain + adapters for composition
```

Mandatory rules:

- Domain depends only on the JDK and explicitly approved domain primitives.
- Domain MUST NOT depend on Spring, JPA/Hibernate, jOOQ, Kafka, Redis, gRPC, Protobuf, PostgreSQL, Kubernetes, Istio, HTTP clients, or concrete adapters.
- Application may depend on Domain and abstract ports; it MUST NOT depend on Infrastructure, Interfaces, concrete adapters, Spring context lookup, persistence entities, generated jOOQ records, or transport DTOs.
- Infrastructure implements persistence, messaging, external-client, cache, worker, security, observability, and technical outbound responsibilities.
- Interfaces contains inbound REST/gRPC/Kafka adapters and boundary mapping/validation only.
- Business logic in controllers, gRPC handlers, Kafka listeners, repositories, persistence mappers, serialization mappers, or configuration classes is prohibited.
- Domain events and Kafka/Protobuf integration events are distinct types with explicit mapping.
- Transport, persistence, generated, and provider DTOs MUST NOT become Domain models.
- Cross-service business/domain model sharing is prohibited. Shared libraries are limited to technical primitives with stable ownership and no bounded-context business semantics.

## 2. Canonical package structure

ADR-0007 is the current package decision. Packages are **feature-first and nature-separated**:

```text
architectural layer
  -> business feature
    -> type nature / technical responsibility
```

Canonical shape:

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
│   └── saga/                 # only when a real saga exists
├── infrastructure/<feature>/
│   ├── persistence/
│   │   ├── jpa/entity/
│   │   ├── jpa/repository/
│   │   ├── jpa/mapper/
│   │   ├── jpa/specification/
│   │   ├── jpa/adapter/
│   │   └── query/
│   ├── cache/
│   ├── config/
│   ├── di/
│   ├── messaging/
│   │   ├── producer/
│   │   ├── consumer/
│   │   ├── outbox/
│   │   └── inbox/
│   ├── observability/
│   ├── security/
│   ├── client/               # only for a real external/client responsibility
│   └── worker/
├── interfaces/<feature>/
│   ├── rest/
│   ├── grpc/
│   └── kafka/
└── configuration/<feature>/
```

Rules:

- Packages are created only when real code exists; empty taxonomy trees and `.gitkeep` placeholders are prohibited.
- Package segments MUST match `[a-z][a-z0-9]*`; uppercase, hyphens, underscores, whitespace, and non-ASCII package segments are prohibited.
- Dumping-ground business packages such as `common`, `util`, `helper`, `manager`, `misc`, or `generic` are prohibited.
- Ambiguous business type names such as `Manager`, `Helper`, `Util`, and `GenericService` are prohibited.
- A service MUST NOT invent a competing global `adapters/in` / `adapters/out` taxonomy when this current structure applies.
- Aggregate repository interfaces live in `domain/<feature>/repository`; do not duplicate the same abstraction under `application/.../port/out` merely to satisfy a template.
- JPA entities and Spring Data repositories stay under Infrastructure persistence.
- Query-specific SQL/jOOQ models remain Infrastructure concerns.
- `config`/`di` packages contain technical configuration/composition only; business policy does not move there.

## 3. Files, types, and responsibility size

A Java file normally contains one public top-level type. Each meaningful aggregate, entity, value object, domain event, command, query, DTO, port, domain exception, persistence entity, mapper, repository adapter, controller/handler/listener, worker, and configuration type normally has its own file.

Nested/private types are acceptable only when they are implementation details of the owning type and do not hide an independent responsibility.

Persistence models follow aggregate/query needs. A mandatory one-table/one-model mapping is prohibited.

Large constructor parameter lists, giant switch statements over unrelated concepts, large mappers/controllers, god classes, or multi-responsibility adapters trigger design review. Do not hide them by extracting vague `Helper`/`Manager` classes.

## 4. Dependency injection and object construction

Spring IoC/ApplicationContext is the only DI container.

- Required dependencies use constructor injection.
- Field injection and field `@Autowired` are prohibited.
- A single constructor does not require `@Autowired`.
- Setter injection is allowed only for a truly optional infrastructure dependency with a safe default; it is not a constructor-size workaround.
- Domain aggregates/entities/value objects/events are not Spring beans and carry no Spring annotations.
- Application use cases remain plain Java and are normally composed in `configuration` with `@Bean`.
- Adapters/configuration may use Spring annotations.
- `ApplicationContext`, `BeanFactory`, service locator, runtime bean lookup, and static global dependency registries are prohibited in Domain/Application.
- Use cases MUST NOT instantiate real adapters directly.
- Circular dependencies are prohibited; `@Lazy` MUST NOT hide a cycle.
- Singleton beans are stateless or explicitly thread-safe. Mutable request/session state in singleton beans is prohibited.
- Request/session scope is BFF-only and requires explicit justification.
- Related configuration uses typed `@ConfigurationProperties`; scattered `@Value` for one logical group is prohibited.
- Multiple port implementations are selected through explicit configuration and meaningful qualifiers, not classpath accident.
- Prefer `@Configuration(proxyBeanMethods = false)` when inter-bean method proxying is unnecessary.

## 5. Domain and Application rules

- Aggregates enforce invariants at mutation boundaries.
- Domain services exist only for genuine domain behavior that does not naturally belong to an aggregate/entity/value object.
- Application use cases orchestrate domain behavior, ports, transaction boundaries, authorization invocation, and outbox/integration decisions; they do not absorb persistence/provider details.
- Application outbound ports represent technical/cross-boundary dependencies not naturally modeled as Domain repositories.
- Commands, queries, and DTOs are explicit and bounded; unstructured maps across application boundaries are prohibited.
- Trusted tenant/user/workload identity is extracted and validated at the appropriate boundary; caller-controlled tenant context is never trusted by convention.

## 6. REST, gRPC, messaging, and error mapping

- External/browser REST contracts are OpenAPI-first/versioned through the BFF/public boundary.
- Frontend TypeScript clients are generated from the approved OpenAPI contract; hand-maintained duplicate transport clients/models are prohibited except thin UI/domain wrappers.
- Public REST errors use RFC 9457 Problem Details or a versioned extension profile and MUST NOT expose internal exceptions, stack traces, SQL/provider payloads, secrets, or unreviewed PII.
- Internal gRPC failures use stable status codes plus bounded reviewed metadata; complete exception text/metadata is not copied blindly.
- Every gRPC/HTTP call has finite deadlines/timeouts. HTTP clients define finite connect and response/read budgets.
- Child deadlines fit inside remaining parent budget; cancellation is propagated where supported.
- Retries are finite, jittered where appropriate, safe/idempotent only, and owned by exactly one layer. Application + Istio/client layered retries for the same failure are prohibited.
- Ordinary request/reply MUST NOT be implemented through Kafka.
- Integration events are explicitly mapped from Domain/application decisions and use Transactional Outbox when local state + publication form one business effect.
- Consumers assume at-least-once delivery and are idempotent; business effect + Inbox/dedup state commit atomically where required.

## 7. Persistence and transaction coding rules

Flyway is the only schema-change mechanism and JPA `ddl-auto` is `validate`; OSIV is prohibited.

- Domain models and JPA entities are separate.
- JPA entities/Spring Data repositories/generated jOOQ types remain Infrastructure concerns.
- Associations are LAZY by default; broad EAGER loading is prohibited.
- Fetch plans are explicit via projection/entity graph/join fetch/query design.
- N+1 queries are prohibited.
- `SELECT *` is prohibited in production application SQL.
- Every multi-row production query has deterministic pagination or a hard bound.
- Sensitive/expensive queries require reviewed indexes and representative query-plan evidence.
- Transaction boundaries are short and explicit.
- Network I/O to gRPC/HTTP/Kafka/Redis/providers is prohibited inside a database transaction.
- Database locks are never held during remote I/O.
- Retries occur outside the failed transaction.
- Cascade/orphan-removal choices are reviewed against aggregate ownership.
- `equals`, `hashCode`, and `toString` MUST NOT traverse lazy associations unsafely.
- Hibernate/JPA batch size, fetch size, flush behavior, and bulk-write strategy are measured for performance-sensitive paths rather than copied as magic defaults.
- Executed/released Flyway migrations are immutable; evolution follows expand -> migrate -> contract.
- Automatic database rollback is never assumed. Application rollback must be compatible with the expanded schema.

## 8. Concurrency and Virtual Threads

- Spring MVC + Virtual Threads is the default blocking-I/O model.
- Virtual Threads do not increase PostgreSQL connections, Redis capacity, provider quota, CPU, or memory.
- Constrained downstream work uses explicit bounded concurrency/bulkheads and bounded/zero queues.
- CPU-heavy work uses bounded workers/jobs rather than multiplying work over request virtual threads.
- `Thread.sleep` is prohibited for coordination, polling, synchronization, or test waiting. Use a scheduler/clock/test primitive.
- A blanket ban on `synchronized` is prohibited on Java 25. JFR/load/soak evidence targets remaining native/FFM pinning, contention, and carrier/resource starvation.
- Shared mutable state requires explicit ownership, synchronization strategy, and concurrency tests.

## 9. Configuration, dependency, and build rules

- Each independently deployable service owns `settings.gradle.kts`, `build.gradle.kts`, Gradle Wrapper, dependency verification metadata, contracts, source sets, container build, and deployment package.
- Java toolchain/release is 25 according to the Technology Baseline.
- Dynamic dependency versions (`+`, unbounded ranges) and unapproved production SNAPSHOT dependencies are prohibited.
- Dependencies/plugins/tools are added only with documented purpose, owner, compatibility review, dependency verification, and security/license review where applicable.
- Prefer Spring Boot dependency management/BOM alignment; explicit version overrides require justification and compatibility tests.
- Secret values never appear in code, Git, committed Gradle properties, Helm values, images, fixtures, logs, traces, or CI output.
- Configuration defaults are safe. Production-only security controls cannot be disabled through an undocumented profile/property.
- Generated code lives in explicit generated directories; analyzer/formatter exclusions are narrow and only where regeneration is authoritative.
- Build/distributable output used for release is reproducible enough to identify the exact source revision and promote the same immutable image digest.
- Release artifacts MUST carry an immutable digest and traceable source identity such as semantic/version metadata plus the Git commit SHA. Production MUST NOT rebuild an artifact already validated in staging.

## 10. Logging and telemetry coding rules

Logging is allow-list based.

- Applications emit structured JSON to stdout; request threads do not synchronously ship logs directly to a remote backend.
- Structured fields are used instead of string-concatenating request/domain/payload objects.
- Never log raw passwords/PIN/OTP/recovery codes/security answers, access/refresh/ID tokens, API keys, `Authorization`/`Cookie`/`Set-Cookie`, session IDs, private/encryption keys, secrets, DB connection strings, payment/bank data, sensitive government/health/biometric data, full request/response bodies, SQL binds, complete gRPC metadata, Kafka headers, or unreviewed provider payloads.
- Ordinary PII is logged only for an approved purpose with masking/tokenization or managed-key HMAC pseudonymization when correlation is required. Plain unsalted hashing is insufficient for guessable PII.
- Input-derived fields are sanitized/encoded for CR, LF, and malicious delimiters.
- Exception messages, nested causes, and third-party exceptions are untrusted/sensitive until reviewed; prefer stable event codes + bounded safe context.
- Debug/trace logging is disabled by default in production. Temporary elevation is time-bound, access-controlled, audited, and MUST NOT enable body/bind/credential logging.
- MDC/context is bounded and allow-listed; it is not a request-object store.
- Logging/export failure MUST NOT fail the primary request, but sustained drops/backpressure/export failure MUST be observable and alertable.
- Log-store access is least privilege and audited; retention/encryption/residency follow classification.
- Metric labels remain low-cardinality and never contain raw user/tenant/session/request/resource identifiers, trace IDs, raw URLs, or free-form errors.

Every new/materially changed log statement is tested/reviewed for PII, secrets, token/cookie leakage, CR/LF injection, cardinality, and operational usefulness. Staging must support synthetic canary secret/PII leak tests through the real telemetry pipeline.

## 11. Comments and JavaDoc

- Comments explain reasons, constraints, trade-offs, invariants, or non-obvious behavior; they do not restate code.
- JavaDoc is appropriate for public APIs, ports, contracts, extension points, and non-trivial lifecycle/invariant behavior.
- There is no mandatory file-header comment.
- Source comments/JavaDoc are English.
- Long architecture explanations belong in ADRs/Markdown.
- TODO/FIXME representing required work has an owner/tracking reference or is removed before production completion.

## 12. Testability rules

- Domain/Application unit tests instantiate code directly and do not start Spring.
- Tests are deterministic, isolated, and parallel-safe where intended.
- Wall-clock sleeps/network dependencies are absent unless the integration itself is under test.
- Time-sensitive code uses injected/controllable clocks where practical.
- Test-only backdoors/local adapters are impossible to activate in staging/production.
- Flaky tests require an owner and remediation deadline; retries never redefine flaky as passing.
- Test data does not depend on shared mutable global state.
- Playwright uses role/label/accessibility locators, web-first assertions, auto-waiting, and isolated data; fragile CSS/XPath and fixed waits are prohibited when stable semantic alternatives exist.
- BDD/Gherkin covers critical business behavior understandable by Product/QA/Engineering, not selectors, SQL, implementation method names, every CRUD endpoint, or trivial edge cases.

## 13. Container, Kubernetes, Helm, and release-facing rules

Runtime manifests are code and follow the same review standard.

### Container/workload hardening

Production application workloads MUST use, unless a current security decision explicitly requires a narrower exception:

- immutable image digest; `latest` prohibited;
- non-root user;
- `allowPrivilegeEscalation: false`;
- Linux capabilities dropped by default (`drop: ["ALL"]`); add only a specifically reviewed minimum capability;
- `seccompProfile: RuntimeDefault`;
- read-only root filesystem where the workload/image permits it;
- no privileged container, host networking, or `hostPath` without an explicit current security decision;
- CPU/memory requests and limits;
- distinct startup/readiness/liveness probes;
- liveness MUST NOT fail merely because PostgreSQL/Kafka/Redis or another dependency is temporarily unavailable;
- readiness reflects whether the workload can safely serve intended traffic;
- dedicated ServiceAccount, never Kubernetes `default` for production application workloads;
- deny-by-default NetworkPolicy plus least-privilege Istio authorization where applicable;
- graceful shutdown aligned with `terminationGracePeriodSeconds`.

### Helm/GitOps

- Shared deployment standards belong in one company application/library chart; copying full charts between services is prohibited.
- Environment values are separate and contain secret references only, never secret values.
- Chart version and application version are managed independently.
- `helm lint`, render, Kubernetes schema/policy validation, and rendered-secret scans run in CI.
- Complex migration Helm hooks are prohibited unless the migration is explicitly idempotent/owned and has a reviewed failure, rollback/fail-forward, timeout, and retry strategy. Prefer explicit migration jobs/workflows when semantics are non-trivial.
- Direct unreviewed cluster mutation is prohibited; production desired state is GitOps-managed.

### Promotion

- Staging and production use the exact same signed immutable artifact/image digest.
- Rebuild between staging validation and production is prohibited.
- Artifact metadata must identify the source Git commit.
- Smoke-test failure stops progression and rolls back only when rollback is safe for the deployed schema/data state.

## 14. Prohibited practices checklist

Unless a current decision explicitly permits them, prohibited practices include:

- business logic in controllers/listeners/repositories/mappers/configuration;
- framework-dependent Domain models;
- field injection/service locator/runtime bean lookup in Domain/Application;
- circular dependencies or `@Lazy` cycle hiding;
- business dumping-ground `common`/`util`/`helper`/`manager` packages;
- shared business/domain/persistence models across services;
- WebFlux/Reactor/`Mono`/`Flux` in backend services;
- direct cross-service DB access/shared service schema/database;
- remote I/O inside DB transactions;
- unbounded queries/retries/timeouts/queues;
- broad EAGER loading, N+1, OSIV, `SELECT *`;
- direct `repository.save(...); kafkaTemplate.send(...)` for one atomic business effect;
- non-idempotent consumers where duplicate delivery is possible;
- `Thread.sleep` coordination/test synchronization;
- raw sensitive or full body logging;
- direct synchronous network logging from request threads;
- dynamic/floating production dependencies;
- Preview/Incubator APIs in production without a current approved decision and explicit enablement;
- broad analyzer suppressions, disabled tests, `ignoreFailures`, or blanket exclusions to obtain green CI;
- public Traefik dashboard/insecure API, wildcard public routes, or direct Traefik -> BFF application bypass;
- using Kubernetes `default` ServiceAccount or allow-all Istio authorization for application workloads;
- production `PERMISSIVE` mTLS as a permanent workaround;
- secrets in Git/Helm values/images;
- rebuilding a release artifact for production after staging validation.

## 15. Automated enforcement mapping

| Rule class | Primary enforcement |
| --- | --- |
| dependency direction/package/cycles | ArchUnit |
| package segment regex / forbidden package names | ArchUnit and/or Semgrep |
| formatting/import/layout | Spotless |
| Java bug patterns | SpotBugs |
| framework misuse/unsafe logging/high-signal source rules | Semgrep |
| dependency integrity | Gradle dependency verification/locks |
| tests/contracts/migrations | Gradle tasks + Testcontainers/Buf/OpenAPI checks |
| container/Kubernetes/Helm/security context | CI + schema/policy validation |
| immutable artifact/SBOM/signature/provenance | release/supply-chain pipeline |
| final evidence | GitHub Actions required checks + release pipeline |

A machine-checkable rule SHOULD be executable. Documentation alone is not proof of source compliance.

## 16. Mandatory code-generation preflight

Before changing/generating implementation code, the AI/engineer MUST:

1. identify bounded context, capability, aggregate/use-case owner, and final enforcement owner;
2. define required inbound/outbound ports before choosing adapters/frameworks;
3. keep business behavior in Domain/Application;
4. place transport/persistence/messaging/Redis/gRPC/provider adapters only in Interfaces/Infrastructure and compose them from Configuration;
5. decide sync vs event-driven interaction and register operation-level dependency criticality/fallback semantics;
6. use Transactional Outbox when local state + event publication are one business effect;
7. define deadline/timeout, cancellation, retry owner, idempotency, concurrency/bulkhead, breaker applicability, and transaction boundaries;
8. add/update Flyway migration, unit/integration/contract/architecture/security tests, metrics/logs/traces, SLO/alerts, and runbook/evidence hooks when applicable;
9. update ArchUnit/architecture tests when package/dependency/module/layering/ownership rules change;
10. add no dependency/plugin/tool without need, owner, compatibility, verification, and security/license review;
11. align Dockerfile, Helm/GitOps, probes, resources, HPA/KEDA/PDB, ServiceAccount, NetworkPolicy, securityContext, shutdown, and configuration;
12. when public surface changes, review Traefik/Gateway API, WAF, upstream volumetric-DDoS controls, limits, headers, CORS/CSRF, and staging edge tests;
13. add/update Gherkin/Cucumber for critical shared business behavior only;
14. add/update Playwright for affected critical browser journeys using stable semantic locators and isolated data;
15. review/test all changed logging/error mappings for sensitive data, unsafe third-party text, CR/LF injection, and cardinality;
16. connect dependencies only through constructor injection and ports;
17. update dedicated ServiceAccount, Ambient enrollment, NetworkPolicy, and minimal Istio `AuthorizationPolicy` when identity/communication changes;
18. for each new/changed service-to-service edge, define identities, contract, deadline/failure semantics, registry entry, positive/negative authorization tests, and observability;
19. verify artifact promotion uses one immutable digest tied to the reviewed Git commit and is never rebuilt between staging and production;
20. verify deployment security context and Helm migration behavior against §13 rather than relying on chart defaults.

A code-generating agent MUST NOT report completion merely because code compiles.

## 17. Task-report contract

Every non-trivial task report explicitly includes:

```text
Changed bounded context/module:
Contracts changed:
Database migration:
Transaction boundary:
Timeout/deadline behavior:
Retry/cancellation/concurrency behavior:
Kafka/event and idempotency behavior:
Security impact:
Istio identity and authorization impact:
Logging and PII impact:
Observability added or changed:
Build/CI/architecture enforcement changed:
Tests executed:
Rollback considerations:
```

Use `None`, `Not applicable`, or `Not verified` rather than omitting a field.
