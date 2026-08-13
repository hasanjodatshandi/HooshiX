# Technology Baseline

- **Baseline date:** 2026-08-13
- **Status:** Active production/application baseline
- **Update policy:** Reviewed compatible patch/minor updates may use the baseline process when permitted by the current architecture decision; architecture/security-semantic changes require a new or revised current ADR before implementation depends on them.
- **Local companion:** `docs/technology/local-development-baseline.md`

This file is the exact approved production technology/version authority. Repository wrappers, lock files, image digests, Helm/Kustomize values, and CI verification metadata remain authoritative for the exact artifact digest actually deployed.

A baseline pin is **not** proof that no unknown or newly disclosed vulnerability exists. Continuous SBOM/vulnerability/advisory correlation, exception-expiry enforcement, and release-time support/security revalidation remain mandatory under ADR-0035/ADR-0038.

Agents MUST NOT silently select newer versions merely because upstream has a newer release.

## 1. Application/runtime baseline

| Area | Approved baseline | Authority / notes |
| --- | --- | --- |
| JDK / Language | Eclipse Temurin 25.0.4 / Java 25 LTS | JDK image pinned by digest in repo |
| Framework | Spring Boot 4.1.0 | current stable project baseline |
| HTTP model | Spring MVC | WebFlux/Reactor prohibited without a revised current architecture decision |
| Request/I/O concurrency | Virtual Threads | `spring.threads.virtual.enabled=true`, keep-alive enabled |
| Build | Gradle Wrapper 9.6.1 + Kotlin DSL | wrapper per independently deployable service |
| DI | Spring IoC + constructor injection | sole DI container |
| Password hashing | Argon2id: m=19 MiB, t=2, p=1; 16-byte random salt; >=32-byte hash | versioned/self-describing storage; benchmark and bounded hash bulkhead required |
| Access-token signing | RS256 / RSA-3072 / 90-day key rotation | ADR-0023; Identity private key local from OpenBao, public verifier bundle via GitOps |
| Internal synchronous API | gRPC + Protobuf | current architecture choice |
| gRPC Java | 1.81.0 | dependency locks align runtime/stubs/codegen |
| External/browser API | REST + OpenAPI through BFF | current architecture choice |
| Async/event transport | Apache Kafka 4.2.1 + Spring Kafka 4.1.0 | ADR-0015 durability applies |
| Event/API schema | Protobuf | Git + Buf governance |
| Runtime Schema Registry | none in v1 | ADR-0003 |
| Database | PostgreSQL 18.4 | ADR-0019/ADR-0027 |
| PostgreSQL operator | CloudNativePG 1.30.0 | ADR-0019/ADR-0034 |
| PostgreSQL backup | Barman Cloud CNPG-I plugin 0.13.0 | current CloudNativePG backup model |
| Barman plugin TLS dependency | cert-manager 1.20.3 | approved with Kubernetes 1.35 baseline |
| PostgreSQL JDBC | 42.7.13 | fixed line for CVE-2026-54291; dependency locks must not regress below 42.7.12 |
| Migration | Flyway 12.4.0 | sole schema-change mechanism |
| Pool | HikariCP managed/aligned with Spring Boot | connection budget governed by architecture |
| Persistence | Spring Data JPA/Hibernate or jOOQ by service responsibility | separate Domain/persistence models |
| Notification persistence | jOOQ/JDBC, no JPA | fixed service decision |
| Security/session/quota Redis | Redis 8.2.8 + Lettuce 7.5.2 | Sentinel topology; service-isolated ACL/keyspaces |
| Resilience | Resilience4j | current semantic circuit-breaker/bulkhead policy; no layered duplicate retry |
| Observability | OpenTelemetry + Micrometer Observation | exact libs in service locks |
| Logging | structured JSON stdout + Semgrep policy rules | allow-list/redaction/canary/runtime detection |
| Backend test | JUnit 5 + Testcontainers | service dependency locks |
| Architecture test | ArchUnit | mandatory for Java service architecture rules |
| Formatting | Spotless + one approved pinned formatter | exact plugin/formatter in build/tool metadata |
| Java bug analysis | SpotBugs | strict production-code gate |
| Source policy/SAST | repository Semgrep rules + approved SAST | exact CLI/ruleset revision pinned in CI tools lock |
| CI orchestration | GitHub Actions | required-check workflows; third-party actions pinned by commit SHA |
| BDD | Cucumber-JVM + Gherkin | critical acceptance behavior only |
| Frontend unit/component | Vitest + React Testing Library | frontend lockfile |
| Browser E2E | Playwright Test + TypeScript | frontend lockfile |

## 2. Platform baseline

| Area | Approved baseline | Authority / notes |
| --- | --- | --- |
| Containers | OCI immutable images by digest | `latest` prohibited |
| Kubernetes | 1.35.6 | controlled GitOps upgrades |
| Primary CNI / NetworkPolicy | Calico OSS 3.32.1 | standard dataplane; Ambient-aware policy tests mandatory |
| Helm | 4.2.3 | approved 4.2.x patch; chart/tool digests pinned |
| GitOps | Argo CD 3.4.2 | desired state under repository `deploy/` |
| Edge gateway | Traefik 3.7.1 | chart 40.2.0 |
| Kubernetes routing API | Gateway API 1.5.1 | preferred for new public routes |
| WAF server | Caddy 2.11.4 | immutable image digest required |
| WAF connector | coraza-caddy 2.5.0 | version/digest pinned |
| WAF engine | Coraza 3.7.0 | v3 architecture choice |
| WAF rules | OWASP CRS 4.25.1 LTS | no automatic rule updates |
| Service mesh | Istio Ambient 1.30.3 | Kubernetes compatibility matrix mandatory |
| Secrets sync | External Secrets Operator 2.8.0 | namespace-scoped stores preferred |
| Secret authority | OpenBao 2.6.1 | exact current architecture pin |
| Admission policy | Kyverno 1.18.2 | stable `policies.kyverno.io/v1` APIs; policy-authoring RBAC and bounded HTTP-context egress/SSRF controls apply |
| Image signing | Cosign 3.0.6 | current supply-chain policy |
| SBOM | CycloneDX JSON attestation; Syft pinned in CI tools lock | signed/indexed by image digest |
| Vulnerability correlation | Grype pinned in CI tools lock | final-image SBOM; owned/expiring exceptions |
| Privileged human access | Teleport Enterprise Self-Hosted 18.10.0 | JIT/SSO/session audit exercised before rollout |
| Metrics | Prometheus 3.13.1 LTS | GitOps digest pin |
| Alerting | Alertmanager 0.33.1 | GitOps digest pin |
| Dashboards | Grafana 13.1.0 | GitOps digest pin |
| Email | Liara Transactional Email, authenticated SMTP + STARTTLS | current Notification provider decision |
| SMS | IPPanel Edge Webservice mode for Iran | local logging adapter local-only |

## 3. Production cluster topology

### Kubernetes

```text
3 dedicated stacked control-plane/etcd nodes
>=3 schedulable workers
redundant stable L4 controlPlaneEndpoint
N+1 worker capacity for critical request paths
6-hour encrypted off-node etcd snapshots
```

ADR-0022 is authoritative. External etcd is not a v1 requirement.

### PostgreSQL

```text
per persistent production microservice:
  CloudNativePG 1.30.0
  PostgreSQL 18.4
  dedicated database/runtime+migration roles/Flyway history
  dedicated production cluster + backup identity
  3 instances for critical services
  quorum synchronous replication ANY 1 equivalent
  required durability + failover quorum
  automatic safe failover
  continuous WAL archive + daily base backup
```

ADR-0027 defines service database/physical isolation and forced tenant RLS; ADR-0019/ADR-0034/ADR-0037 define HA, fleet, restore, and upgrade operations. Runtime tenant roles are non-owner `NOSUPERUSER NOBYPASSRLS`.

### Kafka

```text
KRaft
3 brokers
3 dedicated controllers
critical RF=3 / minISR=2
producer acks=all + idempotence
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

## 4. Security platform baseline

- browser OAuth/OIDC through BFF using Authorization Code + PKCE S256;
- secure server-side BFF session; browser receives no provider/internal tokens;
- Istio Ambient STRICT mTLS + dedicated ServiceAccount workload identity;
- `trustDomain = prod.sajtech.internal`;
- OpenBao -> External Secrets -> read-only mounted local key/secret material;
- signed/provenanced immutable production artifacts verified at admission;
- admission-policy authoring limited to controlled GitOps/CI identities; policy-engine external context/egress is bounded and SSRF-tested;
- upstream L3/L4 volumetric mitigation/scrubbing -> redundant external L4 load balancing -> Traefik -> Caddy/Coraza WAF -> Web BFF;
- Calico NetworkPolicy standard dataplane;
- service-owned semantic quotas under ADR-0024;
- one online fail-closed no-cache/no-retry `CheckPermission`;
- IPPanel Webservice exact-content Iran SMS;
- Teleport JIT privileged access;
- PII-safe structured telemetry with static/pipeline/canary/runtime controls.

## 5. Compatibility authority

Detailed support relationships live in `production-compatibility-matrix.md`. Any upgrade of a tightly coupled runtime/platform component reruns applicable official support, render/policy, security, workload-identity, load/failover/backup/restore, and rollback/fail-forward validation.

## 6. Version governance

- exact production images/artifacts are immutable-digest/integrity pinned;
- services own Wrapper/dependency locks/verification metadata;
- Spring Boot dependency management is default; overrides require rationale + alignment tests;
- no agent guesses an unlisted patch;
- security patches update one bounded compatibility set at a time;
- platform upgrades use staging/canary/rollback evidence;
- an exact architecture pin changes only through a new/revised current ADR;
- safe patch/minor changes inside an approved family may use Technology Baseline + GitOps review only when the current decision permits it and compatibility/security evidence passes;
- unsupported/EOL versions are not eligible merely because an older baseline once named them.
