# Backend Engineering Architecture

The detailed implementation-level Java rules are maintained in `/docs/engineering/coding-standards.md`. Executable build/static-analysis/CI enforcement is defined in `/docs/engineering/build-and-ci-quality-enforcement.md` and ADR-0069. This document remains the current-state architectural summary.

## 1. Java and Spring model

Backend application code uses Java 25 and Spring Boot 4.1.x. Spring MVC is the HTTP model. Virtual Threads are the default request/I/O concurrency model.

Java Preview/Incubator APIs are prohibited in production without an accepted ADR and explicit preview enablement. Structured Concurrency remains prohibited while it is a Java 25 Preview API.

WebFlux, Reactor, `Mono`, and `Flux` are not part of the approved backend programming model unless a later ADR explicitly changes this decision.

### Java 25 Virtual Thread safety

Java 25 includes JEP 491 (delivered in JDK 24), so ordinary `synchronized`
methods/statements no longer pin virtual threads merely because they hold or
wait for a monitor. A blanket ArchUnit prohibition on `synchronized` is therefore
prohibited as cargo-cult policy.

Remaining scalability risks are still reviewed:

- native/JNI or Foreign Function & Memory callbacks that block can still produce
  `jdk.VirtualThreadPinned` JFR events;
- long lock contention is still a latency problem even when it does not pin a
  carrier;
- class initialization/native frames can create rare remaining pinning cases;
- Virtual Threads must not overdrive bounded JDBC/Redis/provider/CPU resources.

Critical-path load/soak tests capture JFR and fail review on sustained carrier
starvation, native pinning, unbounded contention, or dependency saturation. JDBC
and other blocking drivers are validated under Java 25 rather than assumed safe
from their API shape.

## 2. DDD and Hexagonal Architecture

DDD is used to define bounded contexts, ubiquitous language, aggregates, entities, value objects, domain services, domain events, and service/data ownership.

Hexagonal Architecture is the primary internal structure. Inbound adapters invoke Application ports/use cases; outbound adapters implement Domain repositories or Application outbound ports.

```text
Inbound Adapter
    -> Application Port / Use Case
        -> Domain Model
            -> Domain Repository / Application Outbound Port
                -> Outbound Adapter
```

Controllers, gRPC handlers, and Kafka listeners may validate/map input, extract trusted security context, and invoke use cases. They do not contain business logic.

## 3. Canonical Java package structure

ADR-0007 is authoritative. The convention is **feature-first and nature-separated**. Packages use:

```text
architectural layer
  -> business feature
    -> type nature / technical responsibility
```

Top-level service packages:

```text
domain
application
infrastructure
interfaces
configuration
```

Example:

```text
com/sajtech/<service>/
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
│   └── worker/
├── interfaces/<feature>/
│   ├── grpc/
│   ├── rest/
│   └── kafka/
└── configuration/<feature>/
```

Packages are created incrementally. Empty package trees and `.gitkeep` placeholders used only to display a future taxonomy are prohibited.

Domain aggregate repository interfaces live in `domain/<feature>/repository` and must not be duplicated as `application/.../port/out` merely to satisfy a template.

## 4. Standard service filesystem

A service normally contains:

```text
service-name/
├── settings.gradle.kts
├── build.gradle.kts
├── gradlew
├── gradle/
├── Dockerfile
├── contracts/
│   ├── grpc/
│   ├── events/
│   └── openapi/
├── src/
│   ├── main/
│   ├── test/
│   ├── integrationTest/
│   ├── contractTest/
│   └── architectureTest/
└── deploy/
```

Only directories with real responsibilities are created.

## 5. Dependency injection

Spring IoC/ApplicationContext is the only DI container.

Mandatory rules:

- constructor injection for required dependencies;
- no field injection;
- no field `@Autowired`;
- one-constructor classes do not need constructor `@Autowired`;
- Domain models, entities, value objects, and domain events are not Spring beans;
- Application use cases remain plain Java and should normally be wired in `configuration` with `@Bean`;
- adapters/configuration may use Spring annotations;
- no `ApplicationContext`, `BeanFactory`, service locator, or bean lookup in Domain/Application;
- a use case never instantiates a real adapter directly;
- circular dependencies are prohibited;
- `@Lazy` may not hide a cycle;
- setter injection is only for a truly optional infrastructure dependency with a safe default;
- large constructor parameter counts trigger responsibility redesign;
- singleton beans are stateless or thread-safe;
- mutable request state in singleton beans is prohibited;
- request/session scope is BFF-only and requires explicit justification;
- related typed configuration uses `@ConfigurationProperties`;
- scattered `@Value` for one logical configuration group is prohibited;
- multiple port implementations use explicit configuration and meaningful `@Qualifier` when required;
- Domain/Application unit tests do not start Spring;
- prefer `@Configuration(proxyBeanMethods = false)` when proxying is unnecessary.

## 6. File and naming rules

Each meaningful aggregate, entity, value object, domain event, command, query, DTO, port, exception, persistence entity, mapper, repository adapter, controller, listener, worker, and configuration class normally has its own file.

A Java file normally contains one public top-level type.

Business dumping-ground packages such as `common`, `util`, `helper`, `manager`, `misc`, or `generic` are prohibited.

Ambiguous names such as `Manager`, `Helper`, `Util`, and `GenericService` are prohibited for business types.

## 7. Comments and JavaDoc

Comments explain reasons, constraints, trade-offs, and non-obvious invariants rather than restating code.

JavaDoc is appropriate for public APIs, ports, contracts, extension points, and non-trivial lifecycle/invariant behavior.

There is no requirement for a header comment in every file. Source comments and JavaDoc are written in English. Long architectural explanations belong in ADRs or Markdown documentation.

## 8. API, configuration, and implementation details

Additional mandatory implementation rules include:

- external REST errors use RFC 9457 Problem Details or an explicitly versioned extension profile;
- HTTP clients define finite connect and response/read timeouts;
- related configuration uses typed `@ConfigurationProperties`;
- dynamic dependency versions and unapproved production SNAPSHOTs are prohibited;
- logging uses structured allow-listed fields and input-derived fields are CR/LF/delimiter safe;
- persistence models follow aggregate/query needs rather than a mandatory one-table/one-model mapping;
- JPA/Hibernate batch/fetch/flush tuning for performance-sensitive paths is measured rather than copied as magic defaults.

See `/docs/engineering/coding-standards.md` for the full normative coding standard.

## 9. Prohibited coding practices

Unless a later accepted ADR explicitly permits them:

- business logic in controllers/listeners;
- framework-dependent Domain models;
- shared business-model `common` packages;
- field injection or service locator;
- circular dependencies or `@Lazy` cycle hiding;
- WebFlux/Reactor programming model;
- `Thread.sleep` for coordination;
- unbounded retries/timeouts;
- network I/O inside database transactions;
- direct cross-service database access;
- raw infrastructure types leaking into Domain/Application;
- preview/incubator APIs in production without ADR approval;
- unrelated large refactors during narrow tasks.

## 10. ArchUnit and quality enforcement

Architecture tests enforce at least:

- Domain has no Spring/JPA/Kafka/Redis/gRPC/Infrastructure/Interfaces dependency;
- Application has no Infrastructure/Interfaces/concrete-adapter dependency;
- JPA entities remain under infrastructure persistence;
- inbound adapters invoke Application ports/use cases;
- prohibited dumping-ground packages do not exist;
- package names follow lowercase/no-hyphen/no-underscore conventions;
- dependency cycles are absent.


ADR-0069 additionally requires Spotless, SpotBugs, repository-owned Semgrep rules, Gradle dependency verification, and GitHub Actions required-check evidence. Documentation alone is not proof that these gates are implemented or that Java source complies.
