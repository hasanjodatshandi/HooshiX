# Backend Engineering Architecture

`../engineering/coding-standards.md` is the canonical Java implementation standard. Build/CI enforcement and ADR-0039 define executable evidence. This document is the architecture-level summary.

## 1. Runtime model

Backend code uses Java 25 + Spring Boot 4.1.x, Spring MVC, and Virtual Threads for blocking-I/O request handling.

Preview/Incubator APIs and WebFlux/Reactor are not production defaults without a reviewed current decision.

Virtual Threads do not create DB/Redis/provider/CPU/memory capacity. Constrained dependencies use bounded pools/concurrency/queues and are measured.

## 2. DDD + Hexagonal Architecture

DDD owns capability/bounded-context/business ownership. Independently deployable boundaries require real consumer/lifecycle/security/scale/ownership value; do not create a service only for one screen/journey.

```text
Infrastructure -> Application -> Domain
Interfaces     -> Application -> Domain
```

Domain depends only on JDK/approved domain primitives. Application depends on Domain + abstract ports. Adapters validate/map/extract trusted context and invoke use cases; business logic remains Domain/Application.

## 3. Package shape

Feature-first + nature-separated:

```text
com.sajtech.<service>/
├── domain/<feature>/{aggregate,entity,valueobject,event,exception,repository,service}/
├── application/<feature>/{command,query,dto,port/in,port/out,usecase,saga}/
├── infrastructure/<feature>/{persistence,cache,config,di,messaging,observability,security,client,worker,dataset}/
├── interfaces/<feature>/{rest,grpc,kafka}/
└── configuration/<feature>/
```

Create packages only for real code. Package segments match `[a-z][a-z0-9]*`. Business dumping grounds `common`, `util`, `helper`, `manager`, `misc`, `generic` are prohibited.

Domain/persistence/generated/provider/transport models remain separate. Aggregate repository abstractions stay Domain-owned.

## 4. Standard service filesystem

An implemented independent Java service normally owns:

```text
services/<service>/
├── settings.gradle.kts
├── build.gradle.kts
├── gradlew / gradlew.bat
├── gradle/wrapper/
├── gradle/verification-metadata.xml
├── Dockerfile
├── contracts/{grpc,events,openapi}/
├── src/{main,test,integrationTest,contractTest,architectureTest}/
└── deploy/
```

Only real directories are created. Root build/governance does not turn services into one coupled release unit.

Reference Data is not added under `services/` until ADR-0041 independent-service trigger is evidenced.

## 5. Dependency injection

Spring IoC is the only DI container.

- required dependencies use constructor injection;
- no field injection;
- Domain objects/events are not Spring beans;
- Application use cases remain plain Java;
- no ApplicationContext/BeanFactory/service locator/runtime lookup in Domain/Application;
- use cases do not instantiate real adapters;
- no circular dependencies/`@Lazy` cycle hiding;
- singleton beans stateless or explicitly thread-safe;
- related config uses typed `@ConfigurationProperties`.

## 6. API/persistence/failure rules

- REST errors use RFC 9457 or reviewed versioned extension;
- internal gRPC uses stable bounded status/metadata;
- HTTP/gRPC deadlines finite; retry single-owner and only when safe;
- no remote I/O inside DB transactions or while DB locks held;
- OSIV/N+1/broad EAGER/`SELECT *`/unbounded production queries prohibited;
- dynamic dependency versions/unapproved production SNAPSHOTs prohibited;
- Transactional Outbox/Inbox used where current event correctness requires it;
- Authorization, quota, client-address, MFA, and tenant semantics follow their current ADRs without adapter shortcuts.

ADR-0040 HIBP SHA-1 SQLite lookup stays Infrastructure-only. SHA-1 is compromised-password screening only; Argon2id remains credential storage.

## 7. Day-One observability

ADR-0044 is part of the standard service filesystem/Definition of Done, not a later feature.

From the first executable service path implement applicable:

- structured allow-listed JSON logs;
- Micrometer observations/metrics for request/operation/dependency/saturation;
- OpenTelemetry traces/propagation through approved internal Collector;
- health/readiness with correct dependency semantics;
- safe low-cardinality dimensions;
- PII/secret/correlation tests;
- telemetry exporter/backend failure behavior;
- dashboard/alert ownership for defined SLO/security/reliability signals.

Trace/baggage/correlation is telemetry only. It cannot become authentication, tenant, Authorization, quota, idempotency, or audit authority.

Ordinary telemetry export is asynchronous/bounded and does not fail ordinary business work solely because a telemetry backend is down. Required audit remains separate durable authority.

## 8. Automated enforcement

Applicable CI/ArchUnit/static/runtime checks cover:

- Domain/Application dependency direction;
- persistence/adapters package placement;
- forbidden DI/runtime lookup/cycles;
- package names/dumping grounds;
- Spotless/SpotBugs/Semgrep/dependency verification;
- contracts/migrations/reference datasets;
- ADR-0024 quota clock/cardinality/network behavior;
- ADR-0044 observability privacy/context/failure behavior;
- Kyverno CEL-only production policy gate;
- container/Helm/Kubernetes/Istio/NetworkPolicy/security checks.

Machine-checkable rules should be executable. Documentation alone is never source/runtime compliance evidence.