# Platform Architecture

## 1. Architectural baseline

The platform is an enterprise Java system built as independently deployable microservices. Service boundaries are determined by **Domain-Driven Design** and map to real business capabilities or bounded contexts.

The internal structure of each backend service uses **Hexagonal Architecture**. Clean Architecture is used only to enforce inward dependency direction; no additional ceremonial nesting is introduced.

Core dependency direction:

```text
Infrastructure --> Application --> Domain
Interfaces     --> Application --> Domain
```

Business logic must not depend on Spring, Kafka, PostgreSQL, Redis, gRPC, Protobuf, JPA/Hibernate, jOOQ, Kubernetes, Istio, or other infrastructure technology.

## 2. Service ownership

Every microservice owns its own:

- domain model and business rules;
- a distinct service-owned PostgreSQL database and independent credentials when relational persistence is used;
- independent Flyway migrations/history;
- synchronous contracts;
- asynchronous contracts;
- deployment and release lifecycle;
- observability and service-level authorization enforcement.

ADR-0053 requires database-per-service. ADR-0057 strengthens production isolation
further: persistent production microservices also use separate physical
CloudNativePG clusters and independent backup namespaces/credentials. Direct
cross-service database access, cross-database joins/foreign keys, shared ORM/
jOOQ persistence models, and shared business-model libraries are prohibited.
Tenant-owned relational tables use forced PostgreSQL RLS as defense in depth in
addition to application tenant enforcement.

A service is not created merely around a database table, CRUD screen, UI page, entity, or framework component.

## 3. Repository and build ownership

Independently deployable services have independent builds and releases. The root Gradle build is repository governance only and must not aggregate independently deployable services as subprojects.

Each service owns its `settings.gradle.kts`, `build.gradle.kts`, Gradle Wrapper, dependency verification, contracts, source sets, container build, and Helm deployment package.

Organization Java namespace and Gradle group: `com.sajtech`.

## 4. High-level topology

```text
React + TypeScript
        |
        | HTTPS / REST / OpenAPI
        v
CDN / External Load Balancer
        |
        v
Traefik Gateway / Ingress
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

Only the BFF and explicitly approved public adapters/APIs are externally reachable. Internal microservices are ClusterIP-only and are not directly exposed to the internet.

## 5. Protocol boundaries

### External browser/API boundary

- HTTPS
- REST
- OpenAPI
- Web BFF as the browser-facing backend boundary

### Internal synchronous boundary

- gRPC
- Protobuf
- explicit deadlines
- cancellation propagation where supported
- Istio workload identity and authorization

REST is not the default for new internal service-to-service communication.

### Asynchronous boundary

- Apache Kafka
- Spring Kafka for Java services
- Protobuf events
- Git as source of truth
- Buf `STANDARD` lint + `FILE` breaking checks
- no runtime Schema Registry in v1

Asynchronous communication is used when it is semantically appropriate. Kafka being the platform event technology does not mean every interaction should become asynchronous.

## 6. Bounded contexts and platform services

Current or explicitly planned capability boundaries include:

- Identity Service
- Authorization Service
- Notification Service
- Subscription Service
- Billing Service
- Web BFF
- Compromised Password Service
- Reference Data Service (planned capability boundary)
- Workflow Service (future design only; must not absorb business rules from owning bounded contexts)

The first executable backend service is `services/identity-service`. The second executable backend component is `web-bff`.

## 7. Technology baseline at architecture level

- Java 25 LTS
- Spring Boot 4.1.0
- Spring MVC
- Virtual Threads
- Gradle Kotlin DSL
- PostgreSQL 18.4 managed by CloudNativePG 1.30.0
- Flyway
- HikariCP
- jOOQ and/or Spring Data JPA according to service responsibility
- Kafka + Protobuf
- Redis with service ownership; shared `security-redis` only for approved security/session ephemeral state
- Resilience4j
- OpenTelemetry + Micrometer
- Kubernetes 1.35.6
- Calico OSS 3.32.1 NetworkPolicy CNI
- Helm 4.2.0
- Argo CD
- Kyverno + Cosign production artifact admission
- Traefik
- Caddy + Coraza v3 + CRS 4.x LTS
- Istio Ambient Mode 1.30.x production baseline
- External Secrets Operator + OpenBao
- Liara SMTP Email + IPPanel Edge Webservice SMS for Iran
- JUnit 5 + Testcontainers
- ArchUnit
- Cucumber-JVM for critical BDD scenarios
- Vitest + React Testing Library
- Playwright Test + TypeScript

Exact patch versions belong in `../technology/technology-baseline.md` and repository locks/wrappers/images.

## 8. Production infrastructure resilience

Current v1 production decisions strengthen platform resilience and isolation without
changing bounded-context/data ownership:

- **Kubernetes control plane:** 3 stacked control-plane/etcd nodes + >=3 workers, redundant API endpoint, N+1 critical worker capacity (ADR-0051).
- **CNI/NetworkPolicy:** Calico OSS 3.32.1 standard dataplane; upstream Istio Ambient remains the mesh; NetworkPolicy tests account for HBONE/health traffic (ADR-0050).
- **PostgreSQL:** one dedicated CloudNativePG-managed PostgreSQL 18.4 production
  cluster per persistent microservice, with 3-instance HA for critical services,
  quorum synchronous replication, forced tenant RLS, independent backups, and
  safe failover (ADR-0048/ADR-0057/ADR-0064/ADR-0067).
- **Kafka:** KRaft with 3 brokers + 3 dedicated controllers; critical RF=3,
  minISR=2, acks=all; cold DR reconstructs from service-owned outboxes
  (ADR-0044).
- **Security Redis:** 1 primary + 2 replicas + 3 Sentinel voters, TLS/ACL
  isolation for BFF sessions and semantic quotas (ADR-0041/ADR-0045).
- **Authorization:** minimum 3 app replicas, PDB/topology spread, one final
  `CheckPermission` per protected resource operation, safe local prechecks,
  fail-closed overload/circuit isolation, >=99.95% availability, p95<=100ms and
  p99<=200ms SLO; 75/150ms remains engineering target (ADR-0056/ADR-0062/ADR-0066).
- **OpenBao:** intentionally lean single-node Raft authoritative secret source
  with hourly encrypted snapshots; normal application hot paths consume local
  mounted key material, including Notification after ADR-0043.
- **Notification:** bespoke clock-health agent/database dispatch fence removed;
  PostgreSQL-authoritative deadlines + synchronously durable `DISPATCHING` +
  reconciliation are current (ADR-0047/ADR-0048).
- **Supply chain:** immutable digest + signed CycloneDX SBOM/provenance/Cosign
  signature is verified at admission by HA Kyverno; SBOMs are indexed by image
  digest for continuous transitive vulnerability/advisory correlation and exception escalation (ADR-0046/ADR-0065/ADR-0068).
- **Public DDoS:** hosting/network provider supplies upstream L3/L4 volumetric
  mitigation before the origin; Coraza remains L7 WAF (ADR-0059).
- **Human production access:** Teleport Enterprise Self-Hosted JIT access with
  SSO/WebAuthn, approval, short-lived privilege, and audit/session evidence
  (ADR-0060).
- **PII-safe telemetry:** static logging rules + pipeline redaction + canary/runtime
  leak detection are mandatory (ADR-0061).

These production controls must not make local development depend on a full
production cluster. The developer inner loop uses focused unit/architecture/
contract tests and Testcontainers; full mesh/WAF/HA/DR/chaos validation is
performed at the appropriate CI/staging/release cadence.

## 9. Architecture change rule

A local implementation choice must not replace a platform technology or architectural boundary. Intentional deviations require an accepted ADR before implementation depends on the deviation.

Historical decisions remain in `/docs/adr`. Current-state changes must also update the relevant architecture document and Decision Register.
