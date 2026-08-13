# Technology Baseline

- **Baseline date:** 2026-08-13
- **Status:** Active production/application baseline
- **Update policy:** Reviewed compatible patch/minor updates may use the baseline process when permitted by the governing ADR; architecture/security-semantic changes require a superseding ADR.
- **Local companion:** `docs/technology/local-development-baseline.md`

This file records the approved production technology baseline. Local workstation/container/kind pins are maintained separately in `docs/technology/local-development-baseline.md`. Repository
wrappers, lock files, image digests, Helm/Kustomize values, and CI verification
metadata remain authoritative for the exact artifact digest actually deployed.

Agents MUST NOT silently select newer versions merely because upstream has a
newer release.

## 1. Application/runtime baseline

| Area | Approved baseline | Authority / notes |
| --- | --- | --- |
| JDK / Language | Eclipse Temurin 25.0.4 / Java 25 LTS | JDK image pinned by digest in repo |
| Framework | Spring Boot 4.1.0 | current stable project baseline |
| HTTP model | Spring MVC | WebFlux/Reactor prohibited without superseding ADR |
| Request/I/O concurrency | Virtual Threads | `spring.threads.virtual.enabled=true`, keep-alive enabled |
| Build | Gradle Wrapper 9.6.1 + Kotlin DSL | wrapper per independently deployable service |
| DI | Spring IoC + constructor injection | sole DI container |
| Password hashing | Argon2id: m=19 MiB, t=2, p=1; 16-byte random salt; >=32-byte hash | versioned/self-describing storage; benchmark and bounded hash bulkhead required |
| Access-token signing | RS256 / RSA-3072 / 90-day key rotation | ADR-0052; Identity private key local from OpenBao, public verifier bundle via GitOps |
| Internal synchronous API | gRPC + Protobuf | fixed architecture choice |
| gRPC Java | 1.81.0 | explicit service baseline; dependency locks must align runtime/stubs/codegen |
| External/browser API | REST + OpenAPI through BFF | fixed architecture choice |
| Async/event transport | Apache Kafka 4.2.1 + Spring Kafka 4.1.0 | ADR-0044 durability applies |
| Event/API schema | Protobuf | Git + Buf governance |
| Runtime Schema Registry | none in v1 | ADR-0026 |
| Database | PostgreSQL 18.4 | ADR-0048 |
| PostgreSQL operator | CloudNativePG 1.30.0 | ADR-0048 |
| PostgreSQL backup | Barman Cloud CNPG-I plugin 0.13.0 | ADR-0048 |
| Barman plugin TLS dependency | cert-manager 1.20.3 | approved with Kubernetes 1.35 baseline |
| PostgreSQL JDBC | 42.7.13 | fixes CVE-2026-54291 affecting 42.7.4-42.7.11; service dependency lock authority |
| Migration | Flyway 12.4.0 | sole schema-change mechanism |
| Pool | HikariCP managed/aligned with Spring Boot | connection budget governed by architecture |
| Persistence | Spring Data JPA/Hibernate or jOOQ by service responsibility | separate Domain/persistence models |
| Notification persistence | jOOQ/JDBC, no JPA | fixed service decision |
| Security/session/quota Redis | Redis 8.2.8 + Lettuce 7.5.2 | Sentinel topology; service-isolated ACL/keyspaces |
| Resilience | Resilience4j | ADR-0055 semantic circuit-breaker/bulkhead policy; no layered duplicate retry |
| Observability | OpenTelemetry + Micrometer Observation | current dependency locks govern exact libs |
| Logging | structured JSON stdout + Semgrep policy rules | ADR-0061 allow-list/redaction/canary/runtime detection |
| Backend test | JUnit 5 + Testcontainers | service dependency locks |
| Architecture test | ArchUnit | mandatory for Java service architecture rules |
| Formatting | Spotless + one approved pinned formatter | ADR-0069; exact plugin/formatter pinned in service build/tool metadata |
| Java bug analysis | SpotBugs | ADR-0069; strict production-code gate, exact plugin/tool pinned in build metadata |
| Source policy/SAST | Semgrep custom repository rules + approved SAST | ADR-0061/ADR-0069; exact CLI/ruleset revision pinned in CI tools lock |
| CI orchestration | GitHub Actions | ADR-0069; reusable required-check workflows, third-party actions pinned by commit SHA |
| BDD | Cucumber-JVM + Gherkin | critical acceptance behavior only |
| Frontend unit/component | Vitest + React Testing Library | frontend lockfile |
| Browser E2E | Playwright Test + TypeScript | frontend lockfile |

## 2. Platform baseline

| Area | Approved baseline | Authority / notes |
| --- | --- | --- |
| Containers | OCI immutable images by digest | `latest` prohibited |
| Kubernetes | 1.35.6 | platform patch upgrades remain controlled GitOps changes |
| Primary CNI / NetworkPolicy | Calico OSS 3.32.1 | standard dataplane; Ambient-aware NetworkPolicy tests mandatory |
| Helm | 4.2.3 | upstream 4.2.x patch release; chart/tool digests pinned in CI/platform images |
| GitOps | Argo CD 3.4.2 | desired state under repository `deploy/` |
| Edge gateway | Traefik 3.7.1 | chart 40.2.0 |
| Kubernetes routing API | Gateway API 1.5.1 | preferred for new public routes |
| WAF server | Caddy 2.11.4 | immutable image digest required |
| WAF connector | coraza-caddy 2.5.0 | version/digest pinned |
| WAF engine | Coraza 3.7.0 | v3 architecture choice |
| WAF rules | OWASP CRS 4.25.1 LTS | no automatic rule updates; LTS line selected intentionally |
| Service mesh | Istio Ambient 1.30.3 | Kubernetes compatibility matrix mandatory |
| Secrets sync | External Secrets Operator 2.8.0 | namespace-scoped stores preferred |
| Secret authority | OpenBao 2.6.1 | exact version from ADR-0037 |
| Admission policy | Kyverno 1.18.2 | stable `policies.kyverno.io/v1` APIs |
| Image signing | Cosign 3.0.6 | ADR-0046 |
| SBOM | CycloneDX JSON attestation; Syft pinned in CI tools lock | signed and indexed by image digest |
| Vulnerability correlation | Grype pinned in CI tools lock | scans final-image SBOM; exceptions owned + expiring |
| Privileged human access | Teleport Enterprise Self-Hosted 18.10.0 | ADR-0060; supported patch revalidated at rollout |
| Metrics | Prometheus 3.13.1 LTS | GitOps digest pin |
| Alerting | Alertmanager 0.33.1 | GitOps digest pin |
| Dashboards | Grafana 13.1.0 | GitOps digest pin |
| Email | Liara Transactional Email, authenticated SMTP + STARTTLS | ADR-0036 |
| SMS | IPPanel Edge Webservice mode for Iran | ADR-0049; local logging adapter remains local-only |

## 3. Production cluster topology

### Kubernetes active-cluster HA

```text
3 dedicated stacked control-plane/etcd nodes
>=3 schedulable worker nodes
redundant stable L4 controlPlaneEndpoint
N+1 worker capacity for critical request paths
6-hour encrypted off-node etcd snapshots
```

ADR-0051 is authoritative. External etcd is not a v1 requirement.

## 4. Data-service topology baseline

### PostgreSQL

```text
per persistent production microservice:
  CloudNativePG 1.30.0
  PostgreSQL 18.4
  3 instances for critical services
  quorum synchronous replication ANY 1 equivalent
  required durability
  failover quorum enabled
  automatic safe failover
  continuous WAL archive + daily base backup
  independent backup namespace/credentials/encryption context
```

Every persistent microservice owns a distinct PostgreSQL database, runtime/
migration credentials, Flyway history, and production physical CloudNativePG
cluster (ADR-0053/ADR-0057). Tenant-owned production tables use forced RLS;
runtime roles are `NOSUPERUSER NOBYPASSRLS` and are not table owners.

### Kafka

```text
KRaft
3 brokers
3 dedicated controllers
critical RF=3
critical minISR=2
producer acks=all
idempotent producers
unclean leader election disabled
```

### Security Redis

```text
1 primary
2 replicas
3 Sentinel voters
TLS + ACL isolation
noeviction
```

## 5. Security platform baseline

- OAuth 2.0 / OIDC browser login through BFF;
- Authorization Code + PKCE S256;
- browser receives only secure BFF session cookie, no provider/internal tokens;
- service-to-service identity: Istio Ambient strict mTLS + ServiceAccount identity;
- `trustDomain = prod.sajtech.internal`;
- secrets: OpenBao -> External Secrets Operator -> read-only mounted local material;
- production image admission: Cosign signature/provenance + Kyverno verification;
- edge: upstream L3/L4 DDoS mitigation -> Traefik -> dedicated Caddy/Coraza WAF -> Web BFF;
- CNI/NetworkPolicy: Calico OSS 3.32.1 standard dataplane;
- semantic security quotas: service-owned atomic Redis enforcement with ADR-0054 dual-clock time safety;
- Authorization: one online `CheckPermission`, safe local prechecks, fail-closed breaker/overload shedding, no permission cache/retry/stale fallback;
- Iran SMS: IPPanel Webservice mode, exact Notification-rendered content, bounded report polling;
- human production access: Teleport JIT SSO/WebAuthn with approval/recording;
- PII-safe logs: custom Semgrep + telemetry redaction + canary/runtime detection.

## 6. Compatibility matrix

The detailed production support/compatibility relationship is maintained in:

```text
docs/technology/production-compatibility-matrix.md
```

Any upgrade of Kubernetes, Istio, CloudNativePG, PostgreSQL, cert-manager,
Traefik/Gateway API, Kyverno, Kafka, or the WAF stack requires re-running the
applicable compatibility, policy, load, failover, and rollback validation.

## 7. Version governance

- exact production images are pinned by immutable digest;
- services own their Gradle Wrapper and dependency verification metadata;
- Spring Boot dependency management is the default; explicit overrides require
  a reason and alignment tests;
- no agent guesses a patch version;
- security patch work updates one bounded compatibility set at a time;
- platform upgrades use staging/canary/rollback evidence;
- accepted ADR product/version pins are changed only through a superseding ADR
  when the decision itself changes;
- ordinary safe patch upgrades within an ADR-approved line may be performed
  through Technology Baseline + GitOps review when the ADR permits it.
