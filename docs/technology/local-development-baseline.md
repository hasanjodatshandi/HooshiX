# Local Development Baseline

- **Baseline date:** 2026-08-14
- **Status:** Active local-development baseline
- **Scope:** Developer workstation, WSL2/Linux toolchain, local container tooling,
  and the production-fidelity kind foundation used for platform integration tests.
- **Update policy:** Patch/minor tool changes require reviewed baseline updates and
  compatibility checks. Architecture/security semantics and production technology
  changes still follow the ADR/Technology Baseline rules.
- **Evidence rule:** A value marked `Pinned` is the required repository target, not
  proof that a developer machine currently has it installed. Installed/observed
  versions become verified only through repository bootstrap/verification output.

This document is the local companion to
`docs/technology/technology-baseline.md`. The production Technology Baseline and
Production Compatibility Matrix remain authoritative for production runtime
versions.

## 1. Development host

| Component | Version or policy | Status |
| --- | --- | --- |
| Host OS | Windows 11 Pro | Primary developer target; verify on host |
| Linux environment | Ubuntu 26.04 LTS on WSL2 | Primary local Linux environment; verify on host |
| CPU architecture | `linux/amd64` | Primary local target |
| Java vendor | Eclipse Temurin | Pinned to production vendor |
| Java | 25.0.4 LTS | Pinned |
| Git | 2.55.0 | Local tool pin |
| Shell | Bash inside WSL2 | Required for repository `make`/shell automation |
| PowerShell | Supported host launcher | Must not replace Linux-path CI validation |

Developer machines MAY use newer host OS servicing updates. They MUST NOT use a
newer Java major, Kubernetes minor, Istio minor, or other architecture-sensitive
runtime merely because it is available upstream.

## 2. Container tooling

| Component | Version or policy | Status |
| --- | --- | --- |
| Docker Engine | 29.6.2 | Pinned locally |
| containerd | 2.2.6 | Pinned through approved Docker Engine packaging |
| Docker Compose | 5.1.4 | Pinned locally |
| Docker Buildx | 0.34.1 | Pinned locally |
| Docker cgroup mode | cgroup v2 with systemd driver | Required locally |
| Image policy | immutable digest where infrastructure images are repository-pinned | Required |

Local tooling must preserve the same security assumptions used by CI: BuildKit
builds, no secret material baked into layers, deterministic inputs where
possible, and no `latest` image tags for repository-managed infrastructure.

## 3. Local Kubernetes foundation

The local production-fidelity cluster uses kind and intentionally tracks the
same Kubernetes **minor** as production. A kind patch may differ when the kind
release does not publish an image for the exact production patch.

| Component | Version or policy | Status |
| --- | --- | --- |
| `kubectl` | 1.35.6 | Pinned to production patch |
| kind | 0.32.0 | Pinned |
| kind node image | `kindest/node:v1.35.5@sha256:ce977ae6d65918d0b58a5f8b5e940429c2ce42fa3a5619ec2bbc60b949c0ac95` | Pinned by digest |
| Local cluster name | `platform-local` | Required |
| kube context | `kind-platform-local` | Required |
| CNI / NetworkPolicy | Calico OSS 3.32.1 | Pinned; kind default CNI disabled |
| Helm | 4.2.3 | Pinned to production baseline |
| Istio Ambient | 1.30.3 | Pinned to production baseline |
| Kubernetes Gateway API | 1.5.1 Standard channel | Pinned |
| Traefik | Proxy 3.7.10; Helm chart 41.2.0 | Pinned; chart-41 logging/file-provider key migration must be reflected in repository values |
| Local WAF | Caddy 2.11.4 + coraza-caddy 2.5.0 + Coraza 3.7.0 + OWASP CRS 4.25.1 LTS | Production-fidelity pin |

### Cluster topology

The default local integration cluster is:

```text
1 kind control-plane node
2 kind worker nodes
Calico as CNI/NetworkPolicy implementation
Istio Ambient secure overlay
Gateway API CRDs
Traefik north-south gateway
Caddy/Coraza local WAF
```

This is an integration environment, not a simulation of production HA. It MUST
NOT be used as evidence for production control-plane redundancy, PostgreSQL HA,
Kafka durability, Redis Sentinel failover, DDoS protection, or production RPO/RTO.

### kind networking

The kind configuration disables the default CNI so Calico can provide the same
NetworkPolicy implementation family as production:

```yaml
networking:
  disableDefaultCNI: true
  podSubnet: 192.168.0.0/16
```

The control-plane node maps local developer ports:

```text
localhost:8080 -> kind control-plane port 80
localhost:8443 -> kind control-plane port 443
```

The exact kind cluster configuration belongs in `infrastructure/kind/cluster.yaml` and must be versioned/reviewed.

## 4. Local mesh and edge security profile

Local platform integration tests preserve the production identity path:

```text
browser/curl
-> localhost:8080/8443
-> Traefik
-> Caddy/Coraza WAF
-> Web BFF
-> internal services
```

Rules:

- no Istio ingress gateway is installed;
- Traefik remains the north-south gateway;
- Traefik, the WAF, BFF, and application services use independent ServiceAccounts;
- Traefik is explicitly enrolled in Ambient for local integration so its outbound
  traffic to the WAF has workload identity and mTLS;
- the WAF and BFF are Ambient-enrolled and protected by explicit authorization;
- direct Traefik -> BFF application access is denied;
- internal service traffic remains STRICT mTLS;
- L7 waypoints are installed only for a test/service that actually requires L7
  mesh policy, routing, or telemetry;
- local ingress exposure does not claim upstream volumetric DDoS protection.

See:

- `docs/runbooks/local-istio-ambient.md`
- `docs/runbooks/local-traefik-edge.md`

## 5. Application platform

Local application code uses the same language/framework/build contract as
production.

| Component | Version or policy | Status |
| --- | --- | --- |
| Java | Eclipse Temurin 25.0.4 / Java 25 LTS | Pinned |
| Spring Boot | 4.1.0 | Pinned project baseline |
| Gradle Wrapper | 9.6.1 | Pinned per independently deployable service |
| Build DSL | Kotlin DSL | Required |
| HTTP model | Spring MVC + Virtual Threads | Required |
| Internal synchronous transport | gRPC + Protobuf | Required |
| Event transport | Apache Kafka | Required where the bounded context uses async integration |
| Event/API schema governance | Protobuf in Git + Buf | Required; runtime Schema Registry absent in v1 |
| Primary mutable relational database | PostgreSQL | Required for mutable service relational persistence; ADR-0040 immutable Compromised Password reference dataset is the narrow exception |
| Database migration | Flyway | Required for mutable service relational persistence; ADR-0040 dataset is built offline as a new immutable artifact rather than runtime-migrated |
| Compromised-password local reference dataset | Xerial SQLite JDBC 3.53.2.1 / SQLite 3.53.2 | Pinned to production ADR-0040 baseline; read-only embedded dataset, no runtime external provider |
| Cache/security state | Redis with service-specific ownership/ACL/keyspace | When applicable; not used for ADR-0040 compromised-password dataset |

Compromised Password local testing uses the same immutable SQLite format and query contract as production. Small deterministic fixtures may be generated for unit/integration work, but production data/source material is never required for the normal developer inner loop. A fixture or local dataset substitute cannot weaken read-only/path/query/security behavior in staging or production.

Inner-loop tests MAY use Testcontainers and approved local fakes rather than the
full local Kubernetes foundation. A local substitute is never a production
fallback and never proves production readiness.

## 6. Code-quality and repository tooling

Every Java service must expose repository-defined equivalents of these quality
controls:

| Control | Policy |
| --- | --- |
| `build.gradle.kts` | Kotlin DSL, Java 25 toolchain, dependency verification, test source sets/tasks |
| Spotless | formatting gate; `spotlessCheck` blocks CI |
| SpotBugs | blocking static-analysis gate with reviewed narrow suppressions only |
| ArchUnit | architecture/dependency/package enforcement |
| Semgrep | security/logging/PII/prohibited-pattern rules |
| Gradle dependency verification | required; metadata reviewed with dependency changes |
| GitHub Actions | required CI implementation for blocking repository checks |
| Source compliance | must be proven by executed gates; documentation alone is not evidence |

The normal developer pre-push path is defined by
`docs/engineering/developer-workflow.md`. Production/release gates remain in
`docs/architecture/testing-and-quality-gates.md`.

## 7. Required repository pin files

Local platform implementation should converge on these versioned interfaces:

```text
infrastructure/kind/pins.env
infrastructure/kind/cluster.yaml
infrastructure/calico/pins.env
infrastructure/istio/pins.env
infrastructure/istio/chart/1.30.3/
infrastructure/traefik/pins.env
infrastructure/traefik/chart/41.2.0/
infrastructure/waf/pins.env
infrastructure/waf/
```

Service-local application dependencies such as Xerial SQLite JDBC are pinned through the independently deployable service build, dependency lock, and verification metadata rather than shared infrastructure pin files.

Image digests, chart checksums, and downloaded/vendored artifacts are verified
before cluster mutation. Remote mutable URLs are not trusted as the deployment
source after an artifact has been admitted into the repository baseline.

## 8. Verification interface

The repository should provide a single local-baseline verifier, for example:

```bash
make baseline-verify
```

It must report at least:

```text
java -version
./gradlew --version
git --version
docker version
docker compose version
docker buildx version
containerd --version (when directly exposed)
kubectl version --client
kind version
helm version
kubectl --context kind-platform-local version
installed Calico version/image digests
installed Istio release/image digests
installed Gateway API CRD version
installed Traefik release/image digests
installed WAF image/rule-set digests
```

Service-specific verification additionally proves the Compromised Password Xerial artifact/embedded SQLite version and read-only fixture compatibility when that service is implemented.

Until this check exists and passes, local installed-version compliance is
`NOT VERIFIED` even when this document contains desired pins.

## 9. Version governance

- Production runtime versions remain governed by `technology-baseline.md`, the
  compatibility matrix, accepted ADRs, and immutable deployment metadata.
- Local Kubernetes should remain on the same production minor unless an explicit
  compatibility test requires a skew scenario.
- A local tool update must not silently update production versions.
- Security-sensitive tool updates require changelog/security review before pin
  changes.
- Xerial SQLite JDBC local tests use the exact approved service dependency line unless a dedicated compatibility test intentionally exercises an upgrade candidate.
- No agent may infer an unlisted patch version.
- Host-local convenience never weakens CI, staging, or production gates.
