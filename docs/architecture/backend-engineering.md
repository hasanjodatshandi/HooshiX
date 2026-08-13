# Backend Engineering Architecture

`../engineering/coding-standards.md` is the canonical implementation-level Java standard. `../engineering/build-and-ci-quality-enforcement.md` and ADR-0069 define executable enforcement/evidence. This document is the architecture-level summary.

## 1. Java and Spring model

Backend application code uses Java 25 and Spring Boot 4.1.x. Spring MVC + Virtual Threads is the default request/blocking-I/O model.

Preview/Incubator APIs are prohibited in production without a new current reviewed decision and explicit enablement. WebFlux/Reactor/`Mono`/`Flux` are not part of the approved backend model unless architecture is intentionally changed.

Java 25 includes the JEP 491 monitor improvements delivered in JDK 24, so a blanket ban on `synchronized` is prohibited. Remaining concerns—native/FFM blocking/pinning, lock contention, carrier starvation, and overdriving bounded JDBC/Redis/provider/CPU resources—are measured with JFR/load/soak evidence.

## 2. DDD and Hexagonal Architecture

DDD defines bounded contexts, ubiquitous language, aggregates, entities/value objects, domain services/events, and service/data ownership.

Hexagonal Architecture is the internal structure:

```text
Inbound Adapter
    -> Application Port / Use Case
        -> Domain Model
            -> Domain Repository / Application Outbound Port
                -> Outbound Adapter
```

Dependency direction:

```text
Infrastructure -> Application -> Domain
Interfaces     -> Application -> Domain
Configuration  -> Application/Domain + adapters for composition
```

Domain depends only on JDK/approved domain primitives. Application depends on Domain + abstract ports. Controllers/gRPC handlers/Kafka listeners validate/map/extract trusted context/invoke use cases; business logic remains Domain/Application.

## 3. Canonical package structure

Current coding standard is **feature-first + nature-separated**:

```text
architectural layer
  -> business feature
    -> type nature / technical responsibility
```

Canonical top-level shape:

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

Packages are created only when real code exists. Package segments match `[a-z][a-z0-9]*`. Dumping grounds such as `common`, `util`, `helper`, `manager`, `misc`, `generic` are prohibited for business code.

Aggregate repository interfaces live in `domain/<feature>/repository`; do not duplicate the same contract under Application merely to fit a template. Domain/JPA/generated/provider/transport models remain separate.

## 4. Standard service filesystem

Each independently deployable Java service normally owns:

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

Only directories with real responsibilities are created. The root build remains repository governance rather than one coupled multi-service release.

## 5. Dependency injection

Spring IoC/ApplicationContext is the only DI container.

- required dependencies use constructor injection;
- field injection/field `@Autowired` are prohibited;
- Domain objects/events are not Spring beans;
- Application use cases remain plain Java and are normally composed in `configuration` with `@Bean`;
- ApplicationContext/BeanFactory/service-locator/runtime lookup in Domain/Application is prohibited;
- use cases do not instantiate concrete adapters;
- circular dependencies and `@Lazy` cycle hiding are prohibited;
- singleton beans are stateless or explicitly thread-safe;
- mutable request state in singleton beans is prohibited;
- request/session scope is BFF-only and justified;
- related configuration uses typed `@ConfigurationProperties` rather than scattered `@Value`;
- multiple implementations are selected explicitly through configuration/meaningful qualifiers.

## 6. File, naming, API, and persistence rules

One meaningful public top-level type per file is the default. Ambiguous business names such as `Manager`, `Helper`, `Util`, `GenericService` are prohibited.

External REST errors use RFC 9457 Problem Details or a versioned extension. Internal errors use stable bounded gRPC statuses/metadata and do not copy arbitrary exception/provider text.

HTTP/gRPC calls have finite budgets. Dynamic dependency versions and unapproved production SNAPSHOTs are prohibited. Logging uses structured allow-listed fields with CR/LF-safe input handling.

Persistence follows aggregate/query needs rather than one-table/one-model dogma. JPA entities stay Infrastructure-only; Domain/JPA are separate. OSIV, N+1, broad EAGER loading, unbounded queries, `SELECT *`, remote I/O inside DB transactions, and DB locks held across remote I/O are prohibited. Performance-sensitive batch/fetch/flush/query plans are measured.

## 7. Comments and JavaDoc

Comments explain reasons/constraints/trade-offs/invariants, not code narration. JavaDoc is appropriate for public APIs/ports/contracts/extensions/non-trivial lifecycle behavior. No file-header comment is mandatory. Source comments/JavaDoc use English; long architectural explanations belong in Markdown/current ADRs.

## 8. Automated enforcement

Applicable ArchUnit rules prove Domain/Application dependency direction, persistence package placement, forbidden runtime lookup/field injection, package naming/dumping-ground rules, and absence of cycles.

ADR-0069 additionally requires Spotless, SpotBugs, repository Semgrep/static rules, Gradle dependency verification/locks, applicable test/contract/schema tasks, workload/deployment policy validation, and GitHub Actions required-check/release evidence.

Machine-checkable rules SHOULD be executable. Documentation alone is never source/runtime compliance evidence.
