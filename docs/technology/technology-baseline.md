# Technology Baseline

- **Baseline date:** 2026-08-16
- **Status:** Active production/application baseline
- **Update policy:** Reviewed compatible patch/minor updates may use the baseline process when permitted by current architecture; architecture/security-semantic changes require a current ADR before implementation depends on them.
- **Local companion:** `docs/technology/local-development-baseline.md`

This file is the exact approved production technology/version authority. Repository wrappers, lock files, image digests, Helm/Kustomize values, host-provisioning package locks, and CI verification metadata remain authoritative for the exact artifact actually deployed.

A baseline pin is not proof that no unknown/new vulnerability exists. Continuous SBOM/advisory correlation and release-time support/security revalidation remain mandatory.

Agents MUST NOT silently select a newer version because upstream published one.

## 1. Application/runtime baseline

| Area | Approved baseline | Authority / notes |
| --- | --- | --- |
| JDK / Language | Eclipse Temurin 25.0.4 / Java 25 LTS | official runtime archive SHA-256 and runtime base-image digest pinned by owning service; CI verifies exact runtime build |
| Framework | Spring Boot 4.1.0 | current stable project baseline |
| HTTP model | Spring MVC | WebFlux/Reactor prohibited without revised decision |
| Request/I/O concurrency | Virtual Threads | `spring.threads.virtual.enabled=true` |
| Build | Gradle Wrapper 9.6.1 + Kotlin DSL | wrapper per independently deployable service |
| DI | Spring IoC + constructor injection | sole DI container |
| Password hashing | Argon2id: m=19 MiB, t=2, p=1; 16-byte random salt; >=32-byte hash | password-storage authority; benchmark/bounded hash bulkhead required |
| Compromised-password corpus | HIBP Pwned Passwords SHA-1 corpus, offline acquisition | ADR-0040; SHA-1 only for compromised-password lookup, never credential storage; dataset age <=35d for production readiness |
| Compromised-password dataset runtime | Xerial SQLite JDBC 3.53.2.1 / embedded SQLite 3.53.2 | immutable read-only local dataset; exact artifact/native dependency verified; upgrade when compatible reviewed Xerial bundles required newer SQLite/security fix |
| Access-token signing | RS256 / RSA-3072 / 90-day rotation | ADR-0023 |
| Internal synchronous API | gRPC + Protobuf | current architecture |
| gRPC Java | 1.83.1 | runtime/Netty/stubs/codegen/testing aligned in locks; includes upstream Netty server stream-limit bypass fix |
| Protobuf Java/compiler | 4.34.2 | contract code generation and Spring Boot-managed service runtime aligned |
| Protobuf validation | Protovalidate Java 1.2.2 | schema annotations plus fail-closed server interceptor; dependency locks and checksums required |
| External/browser API | REST + OpenAPI through BFF | current architecture |
| Async/event transport | Apache Kafka 4.2.1 + Spring Kafka 4.1.0 + LZ4 Java 1.11.1 | ADR-0015 profile-aware durability; the direct LZ4 constraint overrides Kafka Clients 4.2.1 transitive 1.10.1 because GHSA-xx22-p4ch-683r is fixed in 1.11.1 |
| Event/API schema | Protobuf | Git + Buf governance |
| Runtime Schema Registry | none in v1 | ADR-0003 |
| Database | PostgreSQL 18.4 | profile-aware mutable relational persistence |
| PostgreSQL operator | CloudNativePG 1.30.0 | ADR-0019/0034 |
| PostgreSQL backup | Barman Cloud CNPG-I plugin 0.13.0 | WAL/PITR model |
| Barman plugin TLS dependency | cert-manager 1.20.3 | approved with Kubernetes 1.35 |
| PostgreSQL JDBC | 42.7.13 | must not regress below security-fixed line 42.7.12 |
| Migration | Flyway 12.4.0 | sole mutable relational schema-change mechanism |
| Pool | HikariCP managed/aligned with Spring Boot | profile-aware pool budget |
| Persistence | Spring Data JPA/Hibernate or jOOQ by service responsibility | separate Domain/persistence models |
| Notification persistence | jOOQ/JDBC, no JPA | fixed service decision |
| Security/session/quota Redis | Redis 8.2.8 + Lettuce 7.5.2.RELEASE | single-server TLS/ACL/noeviction/AOF; HA Sentinel |
| Resilience | Resilience4j | breaker/bulkhead; no layered retry |
| Observability API | Spring Boot 4.1 Micrometer Observation/Tracing + OpenTelemetry | ADR-0044; Day-1 service requirement |
| Trace export protocol | OTLP | services -> approved internal Collector |
| Logging | structured JSON stdout + ADR-0031 controls | allow-list/redaction/canary/runtime detection |
| Backend test | JUnit 5 + Testcontainers | service locks |
| Java coverage | JaCoCo 0.8.15 | combined unit/integration risk thresholds in every Java service |
| Selective mutation | Gradle PIT plugin 1.19.0 / PIT 1.22.1 / PIT JUnit 5 plugin 1.2.3 | Identity cryptography/erasure and BFF browser-edge security only; measured baselines are blocking |
| Architecture test | ArchUnit | mandatory Java architecture rules |
| Formatting | Spotless + one approved pinned formatter | exact plugin/formatter in build metadata |
| Java bug analysis | SpotBugs | blocking production-code gate |
| Source policy/SAST | repository Semgrep CLI/rules | ADR-0039/0045; exact Semgrep image/tool and rules pinned in CI; separate Semgrep products are not implied |
| Secret scanning | Gitleaks CLI 8.30.0 | ADR-0045; reviewed fallback from defective 8.30.1; current-tree + Git-history scan; immutable official GHCR image digest pinned in CI; positive detection control required; no raw secret output |
| Dependency advisory scan | OSV-Scanner 2.4.0 | ADR-0045; early declared/locked dependency advisory feedback; exact Linux/x64 artifact SHA-256 pinned in implemented service CI; not final-image authority |
| Repository source lint | ShellCheck 0.11.0 + actionlint 1.7.12 + Ruff 0.16.5 | checksum-pinned official Linux/x64 artifacts; selected high-signal shell, GitHub Actions, and Python source checks in repository baseline CI |
| CI orchestration | GitHub Actions | required checks; third-party actions pinned by SHA |
| BDD | Cucumber-JVM + Gherkin | critical behavior only |
| Frontend unit/component | Vitest 4.1.11 + `@vitest/coverage-v8` 4.1.11 + React Testing Library | exact frontend lockfile; global baseline plus higher risk-module thresholds |
| Browser E2E | Playwright Test + TypeScript | frontend lockfile |

## 2. Platform baseline

| Area | Approved baseline | Authority / notes |
| --- | --- | --- |
| Containers | OCI immutable images by digest | `latest` prohibited |
| Kubernetes API/minor | 1.35.6 | controlled upgrades |
| `production-single-server` distribution | K3s `v1.35.6+k3s1` | ADR-0042; embedded SQLite control plane |
| `production-ha` distribution | kubeadm-compatible Kubernetes 1.35.6 | ADR-0022 |
| Primary CNI / NetworkPolicy | Calico OSS 3.32.1 | Ambient-aware tests; K3s Flannel/policy controller disabled in single-server |
| Helm | 4.2.4 | chart/tool digest pin |
| GitOps | Argo CD 3.4.2 | security-patched 3.4.x line |
| Edge gateway | Traefik 3.7.10 | Helm chart 41.2.0; bundled K3s Traefik disabled |
| Kubernetes routing API | Gateway API 1.5.1 | Traefik 3.7-supported Standard version |
| WAF server | Caddy 2.11.4 | immutable digest |
| WAF connector | coraza-caddy 2.5.0 | version/digest pinned |
| WAF engine | Coraza 3.7.0 | current choice |
| WAF rules | OWASP CRS 4.25.1 LTS | no automatic rule updates |
| Service mesh | Istio Ambient 1.30.3 | K8s support + single-server capacity benchmark |
| Secrets sync | External Secrets Operator 2.8.0 | namespace-scoped stores preferred |
| Secret authority | OpenBao 2.6.1 | unchanged by ADR-0042/0043/0044/0045 |
| Admission policy | Kyverno 1.18.2 | `policies.kyverno.io/v1` CEL types only for new production controls; legacy `ClusterPolicy`/`CleanupPolicy` rejected by repository gates |
| Image signing | Cosign 3.0.6 | ADR-0017/0045; exact image signature + provenance + signed SBOM attestation |
| SBOM | Syft 1.51.0 -> CycloneDX JSON | ADR-0035/0045; generated from exact final releasable image; signed/indexed by image digest |
| Final-artifact vulnerability correlation | Grype 0.117.0 | ADR-0035/0038/0045; final-image/SBOM findings + owned exceptions; scanner/feed freshness is release authority |
| OpenTelemetry Collector | `otelcol-contrib` 0.157.0 | ADR-0044; internal OTLP + node-local log collection; exact image digest pinned in GitOps |
| Metrics | Prometheus 3.13.2 LTS | application scrape + platform metrics |
| Alerting | Alertmanager 0.33.1 | alert routing |
| Log backend | Grafana Loki 3.7.4 | single-binary/non-HA in single-server; bounded storage/retention |
| Trace backend | Grafana Tempo 3.0.2 | monolithic/non-HA in single-server; no extra Tempo Kafka in this profile |
| Dashboards | Grafana 13.1.3 | Prometheus/Loki/Tempo data sources |
| External host-down monitoring | provider TBD before production | must be outside single-host failure domain; environment/provider decision, not guessed here |
| `production-single-server` management network | host-supported WireGuard | exact host package/kernel pinned; public TCP/22 denied |
| `production-single-server` human access | supported OpenSSH + hardware FIDO2 + JIT + `sudo`/system audit | ADR-0030/0043 |
| `production-ha` human access | Teleport Enterprise Self-Hosted 18.10.0 | JIT/SSO/session evidence |
| Email | Liara Transactional Email, SMTP + STARTTLS | Notification provider |
| SMS | IPPanel Edge Webservice mode for Iran | local logging adapter local-only |

## 3. Selected production profile: `production-single-server`

```text
1 physical server
K3s v1.35.6+k3s1 / Kubernetes 1.35.6
Calico 3.32.1; K3s Flannel/network-policy controller disabled
Traefik 3.7.10 / chart 41.2.0; bundled Traefik/ServiceLB disabled
1 replica per application service
HPA disabled
availability PDBs disabled
non-HA host/node maintenance/failure may stop complete platform
```

PostgreSQL:

```text
1 CloudNativePG cluster / 1 PostgreSQL 18.4 instance
separate DB/runtime role/migration role/Flyway per owning service
forced RLS where applicable
continuous WAL + daily base backup + encrypted off-site PITR
35-day PITR + monthly retained recovery artifact for 12 months
```

Kafka:

```text
1 combined KRaft broker/controller
RF=1 / minISR=1
acks=all + idempotence
unclean leader election disabled
formal non-HA acceptance
```

Security Redis:

```text
1 Redis 8.2.8
TLS + per-service ACL
noeviction
AOF appendfsync everysec
ADR-0024 exact-IP/common-clock/cardinality fail-closed controls
```

Observability:

```text
applications -> Micrometer/OTLP
Prometheus 3.13.2
otelcol-contrib 0.157.0
Loki 3.7.4 single-binary
Tempo 3.0.2 monolithic
Grafana 13.1.3
Alertmanager 0.33.1
external black-box host-down signal required before production
```

All observability components share the host failure/capacity domain and do not create HA. Their CPU/RAM/IO/disk/cardinality is included in the complete-stack benchmark. Required privileged/security audit remains separately durable/off-host.

Network/security controls remain ADR-0043 trusted PROXY-v2 -> WAF -> BFF, exact trusted client address, WireGuard-only management reachability, FIDO2/JIT privilege, Istio Ambient, blocking Kyverno CEL policies, OpenBao 2.6.1, unchanged end-user MFA, and ADR-0045 pre-runtime DevSecOps gates.

A `2 vCPU / 3-4 GiB RAM` host is not an approved capacity claim. Sizing requires complete-stack evidence with >=30% validated resource headroom.

## 4. Expansion profile: `production-ha`

Retain current redundant Kubernetes, dedicated mutable-service PostgreSQL clusters, Kafka RF3/minISR2, Redis Sentinel, redundant Kyverno/workloads, Teleport, and profile-specific recovery/evidence. ADR-0044 may evolve Collector topology to agent-to-gateway as multi-node evidence requires; telemetry authority/privacy rules remain unchanged.

## 5. Security platform invariants

Both profiles preserve:

- BFF-only browser OAuth/OIDC and server-side session/token custody;
- strict workload identity/mTLS and deny-by-default NetworkPolicy;
- OpenBao -> External Secrets -> local mounted secrets/keys;
- blocking source/secret/dependency-advisory checks and final-artifact SBOM/vulnerability/signing/provenance gates under ADR-0045;
- signed/provenanced immutable artifacts and SBOM admission;
- Kyverno CEL-based v1 production policy APIs;
- trusted public client-address chain and ADR-0024 exact/aggregate quota semantics;
- one online fail-closed no-cache/no-retry Authorization check;
- HIBP-derived offline compromised-password screening under ADR-0040;
- PII-safe Day-One observability under ADR-0031/0044;
- zero-standing-privilege human access.

## 6. Version governance

- exact deployed images/artifacts/packages and build/security tools are digest/integrity pinned by owning deployment/provisioning/CI mechanism;
- Gitleaks 8.30.0 immutable official image digest, OSV-Scanner 2.4.0, ShellCheck 0.11.0, actionlint 1.7.12, Ruff 0.16.5, Syft 1.51.0, Grype 0.117.0, Cosign 3.0.6, and other downloaded security tools verify exact checksums/digests/signatures as applicable before use;
- services own Wrapper/dependency locks/verification metadata;
- Spring Boot dependency management is default; overrides require rationale/alignment tests;
- no agent guesses an unlisted patch;
- security patches update one bounded compatibility set at a time;
- platform upgrades use staging/rollback evidence;
- safe patch/minor changes inside an approved family require current compatibility/security evidence;
- OSV-Scanner owns early declared/locked dependency advisory feedback; Syft+Grype own final-image release/deployed-artifact vulnerability evidence;
- Trivy and OWASP Dependency-Check are not selected current baseline tools; adding them requires ADR-0045 distinct-coverage evidence and a reviewed baseline change;
- Semgrep CLI selection does not imply separate Semgrep Secrets/Supply Chain/hosted-product adoption;
- Collector/Loki/Tempo upgrades must preserve OTLP/log format/storage/query compatibility and the security/cardinality controls in ADR-0044;
- Xerial SQLite remains under Java + bundled-native advisory review;
- unsupported/EOL versions are not production-eligible because an older baseline once named them.
