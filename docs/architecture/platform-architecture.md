# Platform Architecture

## 1. Architectural baseline

The platform is designed as an enterprise Java system of independently deployable microservices. Service boundaries follow Domain-Driven Design and map to real business capabilities/bounded contexts.

Each backend service uses Hexagonal Architecture. Clean Architecture is used only to enforce inward dependency direction:

```text
Infrastructure --> Application --> Domain
Interfaces     --> Application --> Domain
```

Domain business logic MUST NOT depend on Spring, Kafka, PostgreSQL, Redis, SQLite, gRPC, Protobuf, JPA/Hibernate, jOOQ, Kubernetes, Istio, or other infrastructure technology.

`implementation-status.md` is the repository-level authority for whether planned source/deployment/CI targets currently exist. Architecture target paths in this document are not implementation evidence.

## 2. Service ownership

Every microservice owns its:

- domain model/business rules;
- distinct service-owned PostgreSQL database and independent credentials when mutable relational business persistence is used;
- independent Flyway history when mutable relational schema evolution is used;
- synchronous and asynchronous contracts;
- deployment/release lifecycle;
- observability and service-level authorization enforcement.

Physical PostgreSQL placement is profile-specific. `production-single-server` uses one shared physical CloudNativePG/PostgreSQL cluster while every service keeps a distinct database, runtime role, migration role, Flyway history and release lifecycle. `production-ha` uses a dedicated CloudNativePG cluster per mutable PostgreSQL service. Direct cross-service database access, cross-database joins/foreign keys, shared ORM/jOOQ persistence models, shared credentials and shared business-model libraries are prohibited in both profiles. Tenant-owned relational tables use forced PostgreSQL RLS as defense in depth in addition to application tenant enforcement.

ADR-0040 defines one narrow storage exception: Compromised Password Service uses a service-local **immutable, read-only, rebuildable SQLite reference-data artifact** for its compromised-password dataset. It is not mutable business persistence, receives no subject-owned application data, is not an integration database, has no runtime SQLite writes/Flyway/CloudNativePG requirement, and does not authorize mutable SQLite persistence for any other service.

ADR-0041 Reference Data uses no database at all in v1. Its small Country/Currency/TimeZone/SupportedLocale bundle is an immutable read-only application resource inside the signed service image; this creates no mutable-persistence or SQLite exception.

A service is not created merely around a table, CRUD screen, UI page, entity, or framework component. Reference Data architecture is decided, but executable service implementation remains deferred until ADR-0041's explicit consumer/production-journey trigger is met.

## 3. Repository and build ownership

Independently deployable services have independent builds/releases. Root Gradle build is repository governance only and does not aggregate independently deployable services as one release unit.

When implemented, each service owns `settings.gradle.kts`, `build.gradle.kts`, Gradle Wrapper, dependency verification, contracts, source sets, container build, and deployment package.

Organization Java namespace/Gradle group: `com.sajtech`.

Implementation layout follows the current feature-first/nature-separated coding standard in `../engineering/coding-standards.md`; architecture/package boundaries are machine-enforced where practical after implementation exists.

## 4. High-level topology

```text
React + TypeScript
        |
        | HTTPS / REST / OpenAPI
        v
Upstream L3/L4 volumetric mitigation/scrubbing
        |
        v
External L4
        |
        | validated client source / PROXY v2
        v
Traefik Gateway
        |
        v
Dedicated Caddy + Coraza WAF
        |
        | server-derived client-network identity
        v
Java Web BFF
        |
        | internal gRPC + Protobuf
        | Istio Ambient mTLS + workload identity
        v
Domain / Platform Microservices
        |                 |
        |                 +--> Apache Kafka
        |
        +--> service-owned PostgreSQL database when mutable relational state exists
        +--> service-local immutable reference artifact only where explicitly decided

Separate management path in production-single-server:
approved operator device -> WireGuard -> host management address -> OpenSSH/FIDO2 -> JIT privilege
```

Only BFF and explicitly approved public adapters/APIs are externally reachable. Internal microservices are ClusterIP-only and are not directly Internet-exposed. A CDN is deployment-specific and does not replace upstream volumetric protection, external L4, Traefik, or the dedicated WAF path.

`network-architecture.md` is the implementation-facing network authority. ADR-0043 defines client-address and management-network trust. `threat-model.md` defines the current cross-cutting abuse/residual-risk analysis.

### Selected production profile

ADR-0042 selects `production-single-server` as the initial production profile:

```text
1 physical server
1 K3s server/workload node
1 application replica per service
HPA disabled
availability PDB disabled
1 shared physical PostgreSQL cluster/instance
1 Redis instance
1 combined KRaft Kafka broker/controller
non-HA by design
```

The `production-ha` topology remains the expansion profile. Service documents can contain replicated HA targets; the selected single-server profile overrides only infrastructure placement and availability settings. It does not override business, security, data-ownership, API, deadline, authorization or idempotency contracts.

## 5. Protocol boundaries

### External/browser boundary

- HTTPS;
- REST;
- OpenAPI;
- Web BFF as browser-facing backend boundary;
- v1 application namespace under `/api/v1` with reviewed `/auth`, `/identity`, `/authorization`, and public read-only `/reference` subspaces;
- `/reference` GET/HEAD may be anonymous and cacheable under ADR-0041, but same-origin CORS and mandatory public edge/WAF path remain;
- browser never receives provider/Identity/downstream access or refresh credentials;
- caller-provided forwarding headers are not client-network authority.

### Internal synchronous boundary

- gRPC + Protobuf;
- finite deadlines;
- cancellation propagation where supported;
- one retry owner and only safe/idempotent retry;
- Istio workload identity and least-privilege authorization;
- operation-level dependency class/failure/fallback policy in `dependency-criticality.yaml`;
- detailed deadline/breaker/idempotency/concurrency semantics in the owning operation contract per ADR-0033.

REST is not default for new internal service-to-service communication.

### Asynchronous boundary

- Apache Kafka + Spring Kafka for Java services;
- Protobuf events;
- Git-owned schemas/contracts;
- Buf `STANDARD` lint + `FILE` breaking checks;
- no runtime Schema Registry in v1;
- Transactional Outbox when local state + event publication are one business effect;
- at-least-once/idempotent consumer semantics.

Kafka is used only when asynchronous semantics are appropriate; it is not request/reply transport or business source of truth.

## 6. Bounded contexts and platform capabilities

Current approved boundaries include:

- Identity Service;
- Authorization Service;
- Notification Service;
- Web BFF;
- Compromised Password Service;
- Reference Data Service — architecture decided by ADR-0041; executable implementation remains planned/evidence-gated;
- Workflow Service only as a future capability boundary that MUST NOT absorb business rules from owning contexts.

Planned implementation targets include `services/identity-service`, `services/web-bff`, `services/authorization-service`, `services/notification-service`, and `services/compromised-password-service`. Their current presence/evidence state is defined only in `implementation-status.md`.

Web BFF is an integration/browser security boundary rather than owner of backend business invariants. It owns public OpenAPI, browser OIDC/pre-auth/session/CSRF, exact-audience credential brokerage mechanics, browser-safe error/request bounds, trusted edge-derived client-network context, and route-to-downstream orchestration. Identity remains token/session identity authority, Authorization remains tenant permission/admin authority, and resource-owning service remains final protected-resource authorization/business-state authority.

Compromised Password Service is an internal security reference-data boundary. Identity computes SHA-256 locally and sends only the current 20-bit prefix; Compromised Password performs a bounded indexed lookup against its immutable read-only SQLite dataset and returns suffix/count candidates; Identity performs the exact full-hash match and owns the final credential decision. The service has no User/Tenant/Contact/session state and no runtime external compromised-password provider/API.

Reference Data Service is the closed global standard-reference boundary for Country, Currency, TimeZone, and SupportedLocale. It owns canonical code/metadata/lifecycle and the offline deterministic immutable bundle, but it is not a generic dictionary, tenant/business configuration store, authorization service, or universal business validation service. ISO/IANA/stable-CLDR source material is imported only offline; production serving has no source-provider Internet dependency. The initial runtime consumer is Web BFF through the typed bounded read contract, and other callers require separate architecture/dependency review.

## 7. Architecture-level technology families

- Java 25 LTS;
- Spring Boot 4.1.x;
- Spring MVC + Virtual Threads;
- Gradle Kotlin DSL;
- PostgreSQL 18.x / CloudNativePG 1.30.x for mutable relational service persistence;
- Xerial SQLite JDBC + embedded SQLite only for ADR-0040 immutable Compromised Password reference data;
- Flyway, HikariCP, jOOQ and/or Spring Data JPA according to service responsibility;
- Kafka 4.2.x + Protobuf;
- Redis 8.2.x for approved ephemeral security/session state;
- Resilience4j;
- OpenTelemetry + Micrometer;
- Kubernetes 1.35.x; K3s for selected single-server profile; Calico OSS 3.32.x;
- Helm 4.x + Argo CD 3.x;
- Kyverno + Cosign supply-chain admission;
- Traefik 3.x;
- Caddy + Coraza v3 + CRS 4.x LTS;
- Istio Ambient 1.30.x;
- External Secrets Operator + OpenBao 2.6.1;
- WireGuard for selected single-server management network admission;
- Liara Transactional Email + IPPanel Edge Webservice SMS for Iran;
- JUnit 5 + Testcontainers + ArchUnit;
- Cucumber-JVM for critical BDD;
- Vitest + React Testing Library;
- Playwright Test + TypeScript.

Reference Data introduces no new runtime technology family. Exact approved patch versions belong in `../technology/technology-baseline.md` and repository locks/wrappers/image/chart/provisioning metadata.

## 8. Production resilience and security boundaries

Shared invariants and profile-specific topology are owned by ADR-0042/current runtime, security, network, data and reliability documents. This platform summary does not duplicate the complete normative lists.

Key constraints are:

- single-server is non-HA and cannot claim node/control-plane/PostgreSQL/Redis/Kafka failover;
- mutable service ownership/RLS, Kafka idempotency, Redis fail-closed semantics, workload identity/mTLS, WAF path, signed-artifact admission, OpenBao, MFA and Authorization remain unchanged by topology simplification;
- client network identity uses only ADR-0043 trusted edge derivation;
- single-server human network admission is WireGuard and remains separate from FIDO2/JIT privilege;
- insufficient host capacity requires more capacity or `production-ha`, not weaker security/recovery controls;
- cold recovery follows ADR-0004 and `../runbooks/production-cold-dr.md`.

## 9. OpenBao and MFA are outside topology/network simplification

OpenBao remains exactly under ADR-0011 and Technology Baseline. ADR-0042/ADR-0043 do not remove, replace, bypass or weaken it.

End-user MFA remains under ADR-0012. Active TOTP is still required where the current Identity state requires it; Email/SMS verification/recovery is not a freely selectable weaker bypass.

## 10. Change and decision rule

Implementation MUST NOT silently replace a platform technology, bounded-context boundary, data owner, security invariant, communication model, production profile, network trust boundary, or availability claim.

When architecture changes:

1. update applicable current-state document;
2. create or update retained current ADR when durable decision value exists;
3. remove or normalize obsolete decision text after confirming no current invariant/contract/security/SLO/operational rule is lost;
4. update `../adr/decision-register.md`, `SOURCES.md`, executable architecture/security tests, readiness evidence and technology baselines when applicable;
5. update `implementation-status.md` when repository implementation/evidence presence materially changes;
6. deliver and review the complete change through PR-first workflow.
