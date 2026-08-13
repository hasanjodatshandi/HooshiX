# Java Coding Standards — Current Canonical Standard

This is the canonical implementation standard for Java backend services. Architecture ownership/boundaries remain in current architecture documents/retained ADRs; SQL/Flyway details are in `sql-and-flyway-coding-standards.md`; executable gates are in `build-and-ci-quality-enforcement.md`.

Machine-checkable rules SHOULD be enforced by ArchUnit, Spotless, SpotBugs, Semgrep, dependency verification, contract/schema checks, or CI rather than agent memory.

## 1. Architecture and dependency direction

Backend services use DDD + Hexagonal Architecture. Clean Architecture is used only to enforce inward dependency direction.

```text
infrastructure -> application -> domain
interfaces     -> application -> domain
configuration  -> application/domain + adapters for composition
```

Mandatory:

- Domain depends only on JDK/explicit approved domain primitives.
- Domain MUST NOT depend on Spring, JPA/Hibernate, jOOQ, Kafka, Redis, gRPC, Protobuf, PostgreSQL, Kubernetes, Istio, HTTP clients, transport/generated/provider types, or concrete adapters.
- Application depends on Domain + abstract ports only; no Infrastructure/Interfaces/concrete adapters/Spring context lookup/persistence entities/generated jOOQ/transport DTO dependencies.
- Infrastructure implements persistence, messaging, cache, clients/providers, workers, security, observability, and other technical outbound concerns.
- Interfaces contains inbound REST/gRPC/Kafka adapters, boundary validation/mapping/trusted-context extraction, and use-case invocation only.
- Configuration composes real adapters/use cases and contains no business policy.
- Business logic in controllers/gRPC handlers/Kafka listeners/repositories/mappers/configuration is prohibited.
- Domain events and Protobuf/Kafka integration events are distinct types with explicit mapping.
- Cross-service business/domain/persistence model sharing is prohibited; shared libraries are limited to product-neutral technical primitives with stable ownership.

## 2. Canonical package structure

Packages are **feature-first + nature-separated**:

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
│   └── saga/                 # only for a real saga
├── infrastructure/<feature>/
│   ├── persistence/jpa/{entity,repository,mapper,specification,adapter}/
│   ├── persistence/query/
│   ├── cache/
│   ├── config/
│   ├── di/
│   ├── messaging/{producer,consumer,outbox,inbox}/
│   ├── observability/
│   ├── security/
│   ├── client/
│   └── worker/
├── interfaces/<feature>/{rest,grpc,kafka}/
└── configuration/<feature>/
```

Rules:

- create packages only for real code; no empty taxonomy trees/`.gitkeep` scaffolding;
- every package segment matches `[a-z][a-z0-9]*`; uppercase, hyphen, underscore, whitespace, non-ASCII segments prohibited;
- business dumping grounds `common`, `util`, `helper`, `manager`, `misc`, `generic` prohibited;
- ambiguous business types such as `Manager`, `Helper`, `Util`, `GenericService` prohibited;
- do not invent a competing global `adapters/in` / `adapters/out` taxonomy;
- aggregate repository abstractions live in `domain/<feature>/repository`; do not duplicate the same contract under Application merely to fit a template;
- JPA/Spring Data/generated query types stay Infrastructure-only;
- `config`/`di` is technical composition only.

## 3. Files/types/responsibility

A Java file normally contains one public top-level type. Distinct aggregate/entity/value object/domain event/command/query/DTO/port/domain exception/persistence entity/mapper/repository adapter/controller/handler/listener/worker/configuration responsibilities normally use separate files.

Nested/private types are allowed only when they are true implementation details of the owning type.

Persistence model shape follows aggregate/query needs; one-table/one-model mapping is never mandatory.

Large constructor lists, unrelated giant switches, giant mapper/controller, god class, or multi-responsibility adapter trigger design review. Do not hide them behind vague helper/manager classes.

## 4. Dependency injection and configuration

Spring IoC/ApplicationContext is the only DI container.

- required dependencies use constructor injection;
- field injection/field `@Autowired` prohibited;
- single constructor does not need `@Autowired`;
- setter injection only for a genuinely optional Infrastructure dependency with a safe default—not to avoid constructor design;
- Domain objects/events are never Spring beans;
- Application use cases remain plain Java and are normally composed in `configuration` with `@Bean`;
- adapters/configuration may use Spring annotations;
- `ApplicationContext`, `BeanFactory`, service locator, runtime bean lookup, static global dependency registry in Domain/Application prohibited;
- use cases never instantiate real adapters directly;
- circular dependencies and `@Lazy` cycle hiding prohibited;
- singleton beans are stateless or explicitly thread-safe; mutable request/session state in singleton beans prohibited;
- request/session scope is BFF-only and justified;
- related configuration uses typed `@ConfigurationProperties`; scattered `@Value` for one logical group prohibited;
- multiple implementations selected explicitly via configuration/meaningful qualifier, never classpath accident;
- prefer `@Configuration(proxyBeanMethods=false)` when proxying is unnecessary.

## 5. Domain/Application behavior

- aggregates enforce invariants at mutation boundaries;
- domain services exist only for genuine domain behavior not naturally owned by an aggregate/entity/value object;
- use cases orchestrate domain behavior, ports, transactions, authorization calls, and Outbox decisions without absorbing provider/persistence details;
- outbound ports model technical/cross-boundary dependencies; Domain repositories model aggregate persistence;
- commands/queries/DTOs are explicit/bounded; unstructured application-boundary maps are prohibited;
- trusted user/tenant/workload identity is extracted/validated at a boundary and passed explicitly; caller-controlled tenant context is never trusted by convention.

## 6. REST, gRPC, events, and error contracts

- browser/public REST is OpenAPI-first through BFF; generated TypeScript transport client/models are canonical, hand-maintained duplicate transport layers prohibited except thin semantic UI wrappers;
- public REST errors use RFC 9457 Problem Details or reviewed versioned extension and never expose stack trace/internal exception/SQL/provider payload/secret/unreviewed PII;
- internal gRPC uses stable status + bounded reviewed metadata; arbitrary exception/cause text is not copied;
- every HTTP/gRPC dependency has finite connect/response/deadline budget;
- child deadlines fit remaining parent budget; cancellation propagates where supported;
- retry is finite, safe/idempotent, jittered where useful, and owned by one layer only; application + gRPC + Istio duplicate retry prohibited;
- ordinary request/reply MUST NOT use Kafka;
- state change + durable integration event as one effect uses Transactional Outbox;
- consumers assume at-least-once and are idempotent; business effect + Inbox/dedup state commit atomically where required.

Every new/changed synchronous production edge is represented in `../architecture/dependency-criticality.yaml` and defines source/destination workload identity, failure action/fallback, deadline, retry owner, cancellation, idempotency, concurrency/bulkhead/queue, authorization tests, and observability.

## 7. Persistence and transaction coding rules

Flyway is the only schema-change mechanism; JPA `ddl-auto=validate`; OSIV prohibited. Full SQL/Flyway rules live in `sql-and-flyway-coding-standards.md`.

Java baseline:

- Domain/JPA models separate;
- JPA/Spring Data/generated jOOQ Infrastructure-only;
- LAZY by default, broad EAGER prohibited;
- explicit fetch plans/projections/entity graphs/join fetch/query design;
- N+1 prohibited;
- explicit production column lists; `SELECT *` prohibited;
- multi-row query deterministic pagination/hard bound;
- sensitive/expensive queries need reviewed index + representative plan evidence;
- transactions short/explicit;
- remote gRPC/HTTP/Kafka/Redis/provider I/O inside DB transaction prohibited;
- DB locks never held across remote I/O;
- retry outside failed transaction;
- cascade/orphan removal reviewed against aggregate ownership;
- `equals`/`hashCode`/`toString` never unsafely traverse lazy associations;
- batch/fetch/flush/bulk strategy measured on performance-sensitive paths;
- released migrations immutable; evolve expand -> migrate -> contract;
- application rollback remains compatible with expanded schema; automatic DB downgrade never assumed.

## 8. Concurrency and Virtual Threads

Spring MVC + Virtual Threads is the backend request/blocking-I/O model.

- Virtual Threads do not create DB connections, Redis/provider quota, CPU, memory, or network capacity;
- constrained dependencies use explicit bounded in-flight concurrency/bulkheads and bounded/zero queues;
- CPU-heavy work uses bounded workers/jobs;
- `Thread.sleep` prohibited for coordination/polling/test synchronization; use scheduler/clock/event/test primitive;
- blanket `synchronized` prohibition is forbidden on Java 25; use JFR/load/soak evidence for remaining native/FFM pinning, contention, carrier/resource starvation;
- shared mutable state requires explicit ownership/synchronization/concurrency tests.

## 9. Build/dependency/configuration rules

Each independently deployable service owns `settings.gradle.kts`, `build.gradle.kts`, Wrapper, dependency verification, contracts, source sets, container build, and deployment package.

- Java toolchain 25;
- dynamic versions/unbounded ranges/unapproved production SNAPSHOTs prohibited;
- dependency/plugin/tool addition requires purpose, owner, compatibility, integrity, security/license review as applicable;
- prefer Spring Boot BOM/dependency alignment; explicit override requires rationale + compatibility tests;
- production secrets never appear in source/Git/Gradle props/Helm values/images/fixtures/logs/traces/CI output;
- production security controls have safe defaults and cannot be disabled by undocumented profile/property;
- generated code lives in explicit generated directories with narrow exclusions;
- release output identifies exact reviewed Git revision and immutable digest;
- production promotes the exact signed digest validated in staging; production rebuild prohibited.

## 10. Logging and telemetry

Logging is structured JSON stdout and allow-list based. Request threads do not synchronously ship logs to remote backends.

Never log raw:

- passwords/PIN/OTP/recovery/security answers;
- access/refresh/ID tokens, API keys, `Authorization`, `Cookie`, `Set-Cookie`, session IDs;
- private/encryption keys/secrets/DB connection strings;
- payment/bank/high-risk government/health/biometric data;
- full request/response bodies, SQL binds, complete gRPC metadata, Kafka headers, unreviewed provider payloads.

Ordinary PII requires approved purpose and masking/tokenization or managed-key HMAC pseudonymization where correlation is needed; unsalted hashing is insufficient for guessable PII.

- structured fields, not whole request/domain/payload string concatenation;
- CR/LF/malicious-delimiter protection for input-derived values;
- exception messages/nested causes/third-party text treated as sensitive/untrusted until reviewed;
- stable event code + bounded safe context preferred;
- production debug/trace off by default; temporary elevation time-bound/access-controlled/audited and cannot enable body/bind/credential logging;
- MDC/context bounded/allow-listed;
- logging/export failure does not fail business request but sustained drop/backpressure is observable/alertable;
- log-store access least privilege/audited;
- metric labels low-cardinality and exclude user/tenant/session/request/resource IDs, trace IDs, raw URLs, free-form errors.

Every materially changed log statement is tested/reviewed for PII/secret/token/cookie/CRLF/cardinality safety. Staging supports synthetic canary leak tests through the real telemetry path.

## 11. Comments and JavaDoc

- explain reason/constraint/trade-off/invariant, not obvious code;
- JavaDoc for public APIs/ports/contracts/extensions/non-trivial lifecycle behavior;
- no mandatory file-header comment;
- source comments/JavaDoc English;
- long architecture rationale belongs in Markdown/current ADR;
- TODO/FIXME for required work has owner/tracking reference or is removed before production completion.

## 12. Testability

- Domain/Application unit tests instantiate directly without Spring;
- deterministic/isolated/parallel-safe where intended;
- no wall-clock sleep/network unless integration itself is under test;
- injected/controllable time where practical;
- test-only backdoor/local adapter impossible in staging/production;
- flaky test has owner + remediation deadline; retries do not redefine pass;
- test data avoids shared mutable global state;
- Playwright uses role/label/accessibility locators, web-first assertions, auto-waiting, isolated data; fragile CSS/XPath/fixed waits prohibited when semantic alternative exists;
- BDD covers critical shared business behavior, not implementation detail/trivial CRUD.

## 13. Container/Kubernetes/Helm/release-facing rules

Production application workload MUST use unless a narrower current security exception exists:

- immutable image digest, no `latest`;
- non-root;
- `allowPrivilegeEscalation=false`;
- capabilities `drop: ["ALL"]` by default;
- `seccompProfile: RuntimeDefault`;
- read-only root filesystem where compatible;
- no privileged/host-network/`hostPath` without explicit current decision;
- CPU/memory requests/limits;
- distinct startup/readiness/liveness; liveness does not fail only because dependency is down; readiness proves safe service;
- dedicated ServiceAccount; no Kubernetes `default` for application workloads;
- deny-by-default NetworkPolicy + least-privilege Istio authorization;
- graceful shutdown aligned with pod termination grace.

Helm/GitOps:

- shared standards belong in one organization application/library chart; no copied full charts;
- env values contain secret references, never secret values;
- chart/app versions independent;
- `helm lint` + render + schema/policy + secret scan;
- non-trivial migration hooks require explicit owner/idempotency/timeout/retry/failure/rollback/fail-forward/test evidence; prefer explicit migration workflow;
- no direct unreviewed production cluster mutation.

Promotion:

- staging/production use identical signed immutable digest;
- no rebuild between environments;
- artifact metadata includes source Git commit;
- smoke failure stops progression; rollback only when schema/data safe.

## 14. Prohibited practices

Unless a current decision explicitly permits them:

- business logic in controllers/listeners/repositories/mappers/config;
- framework-dependent Domain;
- field injection/service locator/runtime bean lookup;
- circular dependency/`@Lazy` cycle hiding;
- business dumping-ground packages/names;
- shared cross-service business/domain/persistence models;
- backend WebFlux/Reactor/`Mono`/`Flux`;
- cross-service DB access/shared database/schema;
- remote I/O in DB transaction/lock across remote I/O;
- unbounded query/retry/timeout/queue;
- broad EAGER/N+1/OSIV/`SELECT *`;
- save-then-direct-Kafka-send for one atomic business effect;
- non-idempotent at-least-once consumers;
- `Thread.sleep` coordination/test waits;
- raw sensitive/full-body logging or synchronous remote logging from request path;
- floating production dependencies;
- Preview/Incubator APIs in production without current approved decision;
- broad analyzer suppressions/disabled tests/`ignoreFailures`;
- public Traefik insecure/dashboard/wildcard route/direct BFF bypass;
- Kubernetes `default` ServiceAccount/allow-all Istio/permanent PERMISSIVE;
- secrets in Git/Helm/image;
- production rebuild after staging validation.

## 15. Automated enforcement mapping

| Rule family | Primary enforcement |
| --- | --- |
| dependency/package/cycles | ArchUnit |
| package regex/forbidden names | ArchUnit/Semgrep |
| formatting/layout | Spotless |
| bytecode bug patterns | SpotBugs |
| source security/logging/framework misuse | Semgrep/SAST |
| dependency integrity | Gradle verification/locks |
| contracts/migrations/tests | Gradle + Testcontainers + Buf/OpenAPI |
| container/Kubernetes/Helm | schema/policy CI |
| digest/SBOM/signature/provenance | release pipeline/admission |
| final required evidence | GitHub Actions + release gates |

## 16. Mandatory code-generation preflight

Before implementation code changes, explicitly review:

1. bounded context/capability/aggregate/use-case/final-enforcement owner;
2. inbound/outbound ports before adapters;
3. Domain/Application framework independence;
4. transport/persistence/messaging/cache/provider placement;
5. sync vs event-driven semantics + dependency class;
6. Transactional Outbox need;
7. deadlines, retry owner, cancellation, idempotency, concurrency/bulkhead/breaker, transaction boundary;
8. migration/tests/metrics/logs/traces/SLO/alerts/runbook/evidence;
9. ArchUnit/architecture tests for boundary changes;
10. dependency/plugin/tool purpose/owner/compatibility/integrity/security/license;
11. Dockerfile/Helm/GitOps/probes/resources/autoscaling/PDB/ServiceAccount/NetworkPolicy/securityContext/shutdown;
12. public route/Gateway/WAF/upstream-DDoS/limits/headers/CORS/CSRF impact;
13. critical BDD impact;
14. critical Playwright impact;
15. logging/error PII/secret/untrusted-text/CRLF/cardinality safety;
16. constructor injection/ports/no runtime lookup;
17. ServiceAccount/Ambient/NetworkPolicy/Istio authorization impact;
18. operation-level dependency registry + positive/negative policy/contract failure tests;
19. same immutable digest staging->production tied to Git commit;
20. Kubernetes security-context + migration-workflow compliance.

Compilation alone is never completion evidence.

## 17. Task report

Every non-trivial implementation report fills the fields required by `AGENTS.md` and `agent-communication-and-reporting.md`, including module/context, contracts, migration, transaction, deadline/retry/cancellation/concurrency, event/idempotency, security/Istio/logging, observability, build/CI enforcement, tests, deviations, and rollback.