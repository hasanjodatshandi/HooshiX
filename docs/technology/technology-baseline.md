# Technology Baseline

- **Baseline date:** 2026-08-14
- **Status:** Active production/application baseline
- **Update policy:** Reviewed compatible patch/minor updates may use the baseline process when permitted by the current architecture decision; architecture/security-semantic changes require a new or revised current ADR before implementation depends on them.
- **Local companion:** `docs/technology/local-development-baseline.md`

This file is the exact approved production technology/version authority. Repository wrappers, lock files, image digests, Helm/Kustomize values, host-provisioning package locks, and CI verification metadata remain authoritative for the exact artifact digest/package actually deployed.

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
| Compromised-password reference dataset | Xerial SQLite JDBC 3.53.2.1 / embedded SQLite 3.53.2 | ADR-0040; immutable read-only local dataset only; no runtime external provider; service locks verify exact artifact/native components; upgrade when a compatible reviewed Xerial release bundles SQLite 3.53.4+ or a later required security fix |
| Access-token signing | RS256 / RSA-3072 / 90-day key rotation | ADR-0023; Identity private key local from OpenBao, public verifier bundle via GitOps |
| Internal synchronous API | gRPC + Protobuf | current architecture choice |
| gRPC Java | 1.81.0 | dependency locks align runtime/stubs/codegen |
| External/browser API | REST + OpenAPI through BFF | current architecture choice |
| Async/event transport | Apache Kafka 4.2.1 + Spring Kafka 4.1.0 | ADR-0015 profile-aware durability applies |
| Event/API schema | Protobuf | Git + Buf governance |
| Runtime Schema Registry | none in v1 | ADR-0003 |
| Database | PostgreSQL 18.4 | ADR-0019/ADR-0027 profile-aware mutable relational persistence; ADR-0040 immutable SQLite reference-dataset exception |
| PostgreSQL operator | CloudNativePG 1.30.0 | ADR-0019/ADR-0034 |
| PostgreSQL backup | Barman Cloud CNPG-I plugin 0.13.0 | current CloudNativePG backup/PITR model in both production profiles |
| Barman plugin TLS dependency | cert-manager 1.20.3 | approved with Kubernetes 1.35 baseline |
| PostgreSQL JDBC | 42.7.13 | fixed line for CVE-2026-54291; dependency locks must not regress below 42.7.12 |
| Migration | Flyway 12.4.0 | sole schema-change mechanism for mutable service relational persistence; ADR-0040 immutable dataset format is built offline, not runtime-migrated |
| Pool | HikariCP managed/aligned with Spring Boot | profile-aware connection budget governed by architecture |
| Persistence | Spring Data JPA/Hibernate or jOOQ by service responsibility | separate Domain/persistence models; ADR-0040 SQLite adapter remains Infrastructure-only |
| Notification persistence | jOOQ/JDBC, no JPA | fixed service decision |
| Security/session/quota Redis | Redis 8.2.8 + Lettuce 7.5.2 | `production-single-server`: one TLS/ACL/noeviction/AOF instance; `production-ha`: Sentinel topology |
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
| Kubernetes API/minor | 1.35.6 | controlled GitOps upgrades |
| `production-single-server` distribution | K3s `v1.35.6+k3s1` | ADR-0042; exact binary checksum/signature metadata required; embedded SQLite control-plane datastore |
| `production-ha` distribution | kubeadm-compatible Kubernetes 1.35.6 | ADR-0022 HA topology |
| Primary CNI / NetworkPolicy | Calico OSS 3.32.1 | standard dataplane; Ambient-aware policy tests mandatory; K3s Flannel/network-policy controller disabled in single-server profile |
| Helm | 4.2.4 | current reviewed 4.2.x patch; chart/tool digests pinned |
| GitOps | Argo CD 3.4.2 | current security-patched 3.4.x baseline; desired state under repository `deploy/` |
| Edge gateway | Traefik 3.7.10 | chart 41.2.0; K3s bundled Traefik disabled; security-fixed 3.7.x patch; chart 41 logging/file-provider breaking-key migration must render and test before rollout |
| Kubernetes routing API | Gateway API 1.5.1 | Traefik 3.7-supported Standard version; preferred for new public routes |
| WAF server | Caddy 2.11.4 | immutable image digest required |
| WAF connector | coraza-caddy 2.5.0 | version/digest pinned |
| WAF engine | Coraza 3.7.0 | v3 architecture choice |
| WAF rules | OWASP CRS 4.25.1 LTS | no automatic rule updates |
| Service mesh | Istio Ambient 1.30.3 | Kubernetes compatibility matrix mandatory; single-server profile additionally requires complete-stack capacity benchmark |
| Secrets sync | External Secrets Operator 2.8.0 | namespace-scoped stores preferred; current security advisories patched before this line |
| Secret authority | OpenBao 2.6.1 | exact current architecture pin; unchanged by ADR-0042 |
| Admission policy | Kyverno 1.18.2 | stable `policies.kyverno.io/v1`; 1 replica allowed only in explicit non-HA single-server profile; enforcement remains fail closed |
| Image signing | Cosign 3.0.6 | current supply-chain policy |
| SBOM | CycloneDX JSON attestation; Syft pinned in CI tools lock | signed/indexed by image digest |
| Vulnerability correlation | Grype pinned in CI tools lock | final-image SBOM; owned/expiring exceptions |
| `production-single-server` privileged human access | Supported host OpenSSH package + hardware-backed FIDO2 + JIT privilege + `sudo` I/O/system audit | exact host package/version pinned in provisioning; password/root/shared-key access prohibited; no `.bashrc` audit substitute |
| `production-ha` privileged human access | Teleport Enterprise Self-Hosted 18.10.0 | JIT/SSO/session audit exercised before rollout |
| Metrics | Prometheus 3.13.2 LTS | current reviewed 3.13.x patch; GitOps digest pin |
| Alerting | Alertmanager 0.33.1 | current upstream release; GitOps digest pin |
| Dashboards | Grafana 13.1.3 | current reviewed 13.1.x patch; GitOps digest pin |
| Email | Liara Transactional Email, authenticated SMTP + STARTTLS | current Notification provider decision |
| SMS | IPPanel Edge Webservice mode for Iran | local logging adapter local-only |

## 3. Production profiles

### Selected: `production-single-server`

```text
1 physical server
K3s v1.35.6+k3s1 / Kubernetes 1.35.6
1 control-plane + schedulable workload node
K3s embedded SQLite control-plane datastore
Calico 3.32.1; K3s Flannel/network-policy controller disabled
repository Traefik 3.7.10 / chart 41.2.0; K3s bundled Traefik/ServiceLB disabled
1 replica per application service
HPA disabled
availability PDBs disabled
non-HA: host/node maintenance or failure may stop the complete platform
```

#### PostgreSQL

```text
1 CloudNativePG cluster
1 PostgreSQL 18.4 instance
separate database/runtime role/migration role/Flyway history per service
no cross-service SQL or credentials
forced tenant RLS where applicable
shared physical failure domain and global connection budget
continuous WAL archive + daily base backup + encrypted off-site PITR
35-day PITR + monthly retained recovery artifact for 12 months
```

`pg_dump + cron` is not the production backup strategy. Service-specific recovery begins from an isolated whole-cluster physical PITR restore, then transfers only the required service database through the approved controlled procedure.

#### Kafka

```text
KRaft combined broker/controller
1 broker/controller process
critical RF=1 / minISR=1
producer acks=all + idempotence
unclean leader election disabled
formal non-HA acceptance
```

#### Security Redis

```text
1 Redis 8.2.8 instance
TLS + per-service ACL isolation
noeviction
AOF enabled; appendfsync everysec
fail closed for authoritative security/session decisions
no failover claim
```

#### Security/control-plane profile

- Istio Ambient 1.30.3 retained; production promotion requires complete-stack benchmark with >=30% validated resource headroom and current workload-identity/mTLS tests;
- waypoints absent unless an explicit L7 requirement is measured and approved;
- Kyverno retained with one replica and reduced high-value policy inventory; digest/signature/provenance/SBOM/security-context enforcement remains blocking;
- OpenBao 2.6.1 remains secret authority with no change;
- human privileged access uses hardened OpenSSH + hardware-backed FIDO2 + time-bounded two-reviewer JIT privilege + OS/`sudo`/boundary audit exported off-host;
- end-user MFA semantics are unchanged.

A `2 vCPU / 3-4 GiB RAM` full-stack host is not an approved production capacity claim. Host sizing is approved only after complete-stack benchmark/load/recovery evidence from ADR-0042/performance/readiness rules.

### Expansion: `production-ha`

```text
3 dedicated stacked control-plane/etcd nodes
>=3 schedulable workers
redundant stable L4 controlPlaneEndpoint
N+1 worker capacity for critical request paths
6-hour encrypted off-node etcd snapshots
```

PostgreSQL-backed mutable services use dedicated CloudNativePG clusters; critical clusters use three instances and current synchronous durability/failover rules. Kafka uses 3 brokers + 3 dedicated controllers with RF=3/minISR=2. Security Redis uses one primary + two replicas + three Sentinel voters. Kyverno uses >=3 replicas. Teleport remains the privileged human access plane.

## 4. Security platform baseline

Both production profiles preserve:

- browser OAuth/OIDC through BFF using Authorization Code + PKCE S256;
- secure server-side BFF session; browser receives no provider/internal tokens;
- Istio Ambient STRICT mTLS + dedicated ServiceAccount workload identity after the selected profile's required validation;
- `trustDomain = prod.sajtech.internal`;
- OpenBao -> External Secrets -> read-only mounted local key/secret material;
- signed/provenanced immutable production artifacts verified at admission;
- admission-policy authoring limited to controlled GitOps/CI identities; policy-engine external context/egress is bounded and SSRF-tested;
- upstream L3/L4 volumetric mitigation/scrubbing -> external L4 -> Traefik -> Caddy/Coraza WAF -> Web BFF;
- Calico NetworkPolicy standard dataplane;
- service-owned semantic quotas under ADR-0024;
- one online fail-closed no-cache/no-retry `CheckPermission`;
- compromised-password screening using ADR-0040 immutable SQLite reference dataset;
- IPPanel Webservice exact-content Iran SMS;
- zero-standing-privilege JIT human access according to the selected production profile;
- PII-safe structured telemetry with static/pipeline/canary/runtime controls.

OpenBao and Identity/MFA semantics are not simplified by `production-single-server`.

## 5. Compatibility authority

Detailed support relationships live in `production-compatibility-matrix.md`. Any upgrade of a tightly coupled runtime/platform component reruns applicable official support, render/policy, security, workload-identity, load/failover/backup/restore, and rollback/fail-forward validation.

## 6. Version governance

- exact production images/artifacts/packages are immutable-digest/integrity/version pinned through their owning deployment/provisioning mechanism;
- services own Wrapper/dependency locks/verification metadata;
- Spring Boot dependency management is default; overrides require rationale + alignment tests;
- no agent guesses an unlisted patch;
- security patches update one bounded compatibility set at a time;
- platform upgrades use staging/canary/rollback evidence;
- an exact architecture pin changes only through a new/revised current ADR;
- safe patch/minor changes inside an approved family may use Technology Baseline + GitOps review only when the current decision permits it and compatibility/security evidence passes;
- the Xerial SQLite JDBC pin remains under continuous Java-artifact + embedded-native-engine advisory correlation; a compatible reviewed driver bundling SQLite 3.53.4+ is the current upgrade target because upstream SQLite is newer than the embedded 3.53.2 engine;
- unsupported/EOL versions are not eligible merely because an older baseline once named them.