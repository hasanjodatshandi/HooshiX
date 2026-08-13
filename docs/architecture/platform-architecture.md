# Platform Architecture

## 1. Architectural baseline

The platform is an enterprise Java system built as independently deployable microservices. Service boundaries follow Domain-Driven Design and map to real business capabilities/bounded contexts.

Each backend service uses Hexagonal Architecture. Clean Architecture is used only to enforce inward dependency direction:

```text
Infrastructure --> Application --> Domain
Interfaces     --> Application --> Domain
```

Domain business logic MUST NOT depend on Spring, Kafka, PostgreSQL, Redis, gRPC, Protobuf, JPA/Hibernate, jOOQ, Kubernetes, Istio, or other infrastructure technology.

## 2. Service ownership

Every microservice owns its:

- domain model/business rules;
- distinct service-owned PostgreSQL database and independent credentials when relational persistence is used;
- independent Flyway history;
- synchronous and asynchronous contracts;
- deployment/release lifecycle;
- observability and service-level authorization enforcement.

Every persistent production microservice also owns a dedicated CloudNativePG cluster and independent backup identity/namespace. Direct cross-service database access, cross-database joins/foreign keys, shared ORM/jOOQ persistence models, and shared business-model libraries are prohibited. Tenant-owned relational tables use forced PostgreSQL RLS as defense in depth in addition to application tenant enforcement.

A service is not created merely around a table, CRUD screen, UI page, entity, or framework component.

## 3. Repository and build ownership

Independently deployable services have independent builds/releases. The root Gradle build is repository governance only and does not aggregate independently deployable services as one release unit.

Each service owns `settings.gradle.kts`, `build.gradle.kts`, Gradle Wrapper, dependency verification, contracts, source sets, container build, and deployment package.

Organization Java namespace/Gradle group: `com.sajtech`.

Implementation layout follows the current feature-first/nature-separated coding standard in `../engineering/coding-standards.md`; architecture/package boundaries are machine-enforced where practical.

## 4. High-level topology

```text
React + TypeScript
        |
        | HTTPS / REST / OpenAPI
        v
CDN / External Load Balancer
        |
        v
Traefik Gateway
        |
        v
Dedicated Caddy + Coraza WAF
        |
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
        +--> service-owned PostgreSQL
```

Only the BFF and explicitly approved public adapters/APIs are externally reachable. Internal microservices are ClusterIP-only and are not directly Internet-exposed.

## 5. Protocol boundaries

### External/browser boundary

- HTTPS;
- REST;
- OpenAPI;
- Web BFF as the browser-facing backend boundary.

### Internal synchronous boundary

- gRPC + Protobuf;
- finite deadlines;
- cancellation propagation where supported;
- one retry owner and only safe/idempotent retry;
- Istio workload identity and least-privilege authorization;
- operation-level dependency/fallback policy in `dependency-criticality.yaml`.

REST is not the default for new internal service-to-service communication.

### Asynchronous boundary

- Apache Kafka + Spring Kafka for Java services;
- Protobuf events;
- Git-owned schemas/contracts;
- Buf `STANDARD` lint + `FILE` breaking checks;
- no runtime Schema Registry in v1;
- transactional outbox when local state + event publication are one business effect;
- at-least-once/idempotent consumer semantics.

Kafka is used only when asynchronous semantics are appropriate; it is not request/reply transport.

## 6. Bounded contexts and platform capabilities

Current/approved boundaries include:

- Identity Service;
- Authorization Service;
- Notification Service;
- Subscription Service;
- Billing Service;
- Web BFF;
- Compromised Password Service;
- Reference Data Service as a planned capability boundary;
- Workflow Service only as a future capability boundary that MUST NOT absorb business rules from owning contexts.

The first executable backend service is `services/identity-service`; the second executable backend component is `web-bff`.

## 7. Architecture-level technology baseline

- Java 25 LTS;
- Spring Boot 4.1.x;
- Spring MVC + Virtual Threads;
- Gradle Kotlin DSL;
- PostgreSQL 18.x / CloudNativePG 1.30.x;
- Flyway, HikariCP, jOOQ and/or Spring Data JPA according to service responsibility;
- Kafka 4.2.x + Protobuf;
- Redis 8.2.x with service ownership; shared `security-redis` only for approved ephemeral security/session state;
- Resilience4j;
- OpenTelemetry + Micrometer;
- Kubernetes 1.35.x + Calico OSS 3.32.x;
- Helm 4.x + Argo CD 3.x;
- Kyverno + Cosign supply-chain admission;
- Traefik 3.x;
- Caddy + Coraza v3 + CRS 4.x LTS;
- Istio Ambient 1.30.x;
- External Secrets Operator + OpenBao 2.6.1;
- Liara Transactional Email + IPPanel Edge Webservice SMS for Iran;
- JUnit 5 + Testcontainers + ArchUnit;
- Cucumber-JVM for critical BDD;
- Vitest + React Testing Library;
- Playwright Test + TypeScript.

Exact approved patch versions belong in `../technology/technology-baseline.md` and repository locks/wrappers/image/chart metadata except where the exact value is itself a current architecture constraint.

## 8. Production resilience and security boundaries

- **Kubernetes:** three stacked control-plane/etcd nodes + >=3 workers, redundant API endpoint, N+1 critical-worker capacity.
- **Workload hardening:** immutable digest, non-root, `allowPrivilegeEscalation=false`, default capability drop, `RuntimeDefault` seccomp, read-only root filesystem where compatible, bounded resources/probes, dedicated ServiceAccounts, deny-by-default NetworkPolicy.
- **PostgreSQL:** dedicated production CloudNativePG cluster per persistent service; critical services use three-instance synchronous required durability, forced tenant RLS, independent backups/restores, safe failover, and one-cluster upgrade waves.
- **Kafka:** KRaft with three brokers + three controllers; critical RF=3/minISR=2/acks=all; cold DR reconstructs from service-owned evidence.
- **Security Redis:** one primary + two replicas + three Sentinel voters with TLS/ACL isolation.
- **Authorization:** >=3 replicas/PDB/spread; one final online no-cache/no-retry `CheckPermission`, safe local prechecks, fail-closed overload/breaker isolation, >=99.95% availability, p95<=100ms/p99<=200ms SLO.
- **OpenBao:** lean single-Raft authoritative secret source with encrypted snapshots; normal application hot paths use mounted/local validated key material rather than per-request OpenBao calls.
- **Notification:** PostgreSQL-authoritative deadlines + synchronously durable `DISPATCHING` + reconciliation; no bespoke clock/fence control plane.
- **Supply chain:** immutable digest + signed CycloneDX SBOM/provenance/Cosign signature verified by HA Kyverno, with continuous deployed-digest vulnerability/advisory correlation.
- **Public edge:** upstream L3/L4 volumetric mitigation -> Traefik -> dedicated Caddy/Coraza WAF -> Web BFF.
- **Human access:** Teleport JIT SSO/WebAuthn access with approvals, short-lived privilege, and audit/session evidence.
- **Telemetry:** PII/secret-safe structured telemetry with static rules, pipeline redaction, synthetic canaries, and runtime leak detection.

These production controls MUST NOT make local development depend on a full production cluster. The inner loop uses focused unit/architecture/contract tests and Testcontainers; mesh/WAF/HA/DR/chaos/provider evidence runs at the appropriate CI/staging/release cadence.

## 9. Change and decision rule

Implementation MUST NOT silently replace a platform technology, bounded-context boundary, data owner, security invariant, or communication model.

The active repository-owner documentation policy is current-only. When architecture changes:

1. update the applicable current-state document;
2. create or update the retained current ADR when a durable decision record is useful;
3. remove or normalize obsolete decision text after confirming no current invariant/contract/security/SLO/operational rule would be lost;
4. update `../adr/decision-register.md`, `SOURCES.md`, executable architecture/security tests, and technology baselines when applicable;
5. deliver and review the complete change through the PR-first workflow.
