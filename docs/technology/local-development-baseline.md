# Local Development Baseline

- **Baseline date:** 2026-08-19
- **Status:** Active local-development baseline
- **Scope:** WSL-native HooshiX application engineering, Windows-hosted independent Context/Ops/Desktop MCP tooling, fast service loop, and optional production-fidelity kind integration foundation.
- **Evidence rule:** a pin is a repository target, not proof it is installed.

Production Technology Baseline remains authoritative for production versions.

## 1. Development host/tooling

| Component | Version/policy |
| --- | --- |
| Host OS | Windows 11 Pro host for WSL2 and Windows-only MCP/UI tooling |
| Application execution OS | Ubuntu 26.04 LTS on WSL2; canonical checkout `/home/coder/workspace/Hooshix` on native Linux filesystem |
| Architecture | `linux/amd64` primary local target |
| Java | Eclipse Temurin 25.0.4 LTS |
| Git | 2.55.0 |
| Shell | Bash inside WSL2 |
| Docker Engine | 29.6.2 |
| containerd | 2.2.6 through approved Docker packaging |
| Docker Compose | 5.1.4 |
| Docker Buildx | 0.34.1 |
| Docker cgroup | v2 + systemd driver |
| Secret scanner | Gitleaks CLI 8.30.0; reviewed fallback from defective 8.30.1; same rule/config intent and positive-control requirement as CI |
| Dependency advisory scanner | OSV-Scanner 2.4.0; early declared/locked dependency feedback, not final-image authority |
| ChatGPT Web MCP tunnel client | OpenAI `tunnel-client` 0.0.11; developer-only ADR-0047 bridge to existing read-only stdio Context MCP; official release archive/digest verification required |
| HooshiX developer-host Ops MCP | independent Git runtime `hasanjodatshandi/HooshiXMcpRuntime` under ADR-0048/0051; mandatory local policy, separate tunnel/profile/key, synchronous `process.run` plus bounded persistent start/status/log/cancel jobs, WSL bridge for project commands, no production authority |
| Microsoft WinApp CLI | 0.6.0; WinGet `Microsoft.WinAppCli`; developer-only ADR-0049 Desktop MCP dependency; reviewed x64 MSIX SHA-256 `dc5d323f6d1601ef3342420746f0163651176f4cc183690f0354546a36648eec`; public-preview upgrades require review |
| HooshiX developer-host Desktop MCP | independent Windows runtime under ADR-0049/0050/0051 with fixed Windows helpers; interactive non-elevated session by default; separate policy/tunnel/profile/key; no production authority |

Local convenience cannot silently change architecture-sensitive production versions or weaken CI/security semantics.

ADR-0047 tunnel-client is not production infrastructure and does not change the production Technology Baseline. On Windows, use the official `windows-amd64` or `windows-arm64` archive matching the actual host architecture and verify the published release SHA-256 before use. Runtime tunnel credentials stay outside Git and are restricted to Tunnels `Read` + `Use` for the long-lived daemon.

## 2. Fast application lane

Normal edit/test work does **not** require a full local cluster.

Preferred local path:

```text
Gitleaks current-tree check
-> unit/ArchUnit/Semgrep/static + Gradle integrity
-> OSV-Scanner declared/locked dependency advisory
-> focused adapter tests
-> Testcontainers/contract/dataset/quota tests
-> service runtime + local/test telemetry exporter
```

Local Git-history secret scanning should run before push/review when history changes; protected CI/release history scanning remains authoritative.

Local OSV-Scanner is fast feedback. Protected CI/scheduled scanning remains authoritative for the implemented service gate, and neither local nor CI OSV lockfile scanning replaces final-image Syft/Grype release evidence.

Local fakes/substitutes are allowed only where architecture permits and must be impossible in staging/production.

ADR-0044 Day-One observability still applies to implementation code. A developer may use an in-memory/test OTLP exporter or local Collector fixture instead of starting Loki/Tempo/Prometheus for every edit. This does not replace staging/release evidence against the real stack.

### Integrated WSL application runtime

The canonical WSL checkout implements a repeatable fast-lane integrated runtime under `infrastructure/local/` and `scripts/local/runtime.py`. It runs pinned PostgreSQL/Redis plus all five current executable Spring Boot services together without claiming Kubernetes/mesh/edge fidelity.

Repository interfaces are:

```text
make local-runtime-test
make local-runtime-up
make local-runtime-status
make local-runtime-logs
make local-runtime-down
make local-runtime-reset
```

The local runtime generates database credentials, HMAC/AES key rings, an RSA-3072 Identity signing/verifier pair, the Web BFF self-signed HTTPS material, and the generated Compromised Password fixture only under Git-ignored `.local-runtime/`. Identity/Authorization host-time health uses an explicit local fixture and Notification uses only its `local & !staging & !production` simulated providers. These are developer-lane substitutes and are not staging/production evidence.

The Web BFF local public origin is `https://localhost:18443`; PostgreSQL and Redis are loopback-bound on ports `15432` and `16379`. See `docs/runbooks/local-integrated-runtime.md`.

## 3. Application baseline

| Component | Version/policy |
| --- | --- |
| Java | 25.0.4 LTS |
| Spring Boot | 4.1.0 |
| Gradle Wrapper | 9.6.1 + Kotlin DSL |
| HTTP | Spring MVC + Virtual Threads |
| Internal sync | gRPC + Protobuf |
| Async | Kafka only when owning flow requires it |
| Mutable relational data | PostgreSQL + Flyway |
| Security/session state | Redis where applicable |
| Observability API | Micrometer Observation/Tracing + OpenTelemetry |
| Logs | structured JSON stdout |
| Compromised Password local dataset | Xerial SQLite JDBC 3.53.2.1 / SQLite 3.53.2, immutable/read-only |

### Compromised Password local fixtures

Normal PR/local tests use deterministic generated **SHA-1** fixtures that preserve ADR-0040 schema/query/bounds/failure behavior.

- SHA-1 is screening-only; Argon2id remains password storage.
- Normal edit loop does not download the production HIBP corpus.
- Release/dataset validation uses the complete official HIBP Pwned Passwords SHA-1 corpus and current freshness/provenance gates.
- Local fixture behavior cannot enable runtime provider fallback, writes, DDL, ATTACH, extension loading, or a different digest format in staging/production.

### Reference Data local mode

Before ADR-0041 independent-service trigger, use the approved immutable bundle in the owning deployable. Do not start a fake `reference-data-service` merely to mimic a future network boundary.

## 4. Production-fidelity kind lane

Use kind only when real Kubernetes/mesh/edge/policy/telemetry integration is under test.

| Component | Local pin |
| --- | --- |
| `kubectl` | 1.35.6 |
| kind | 0.32.0 |
| kind image | `kindest/node:v1.35.5@sha256:ce977ae6d65918d0b58a5f8b5e940429c2ce42fa3a5619ec2bbc60b949c0ac95` |
| Cluster | `platform-local` / `kind-platform-local` |
| Calico | 3.32.1 |
| Helm | 4.2.4 |
| Istio Ambient | 1.30.3 |
| Gateway API | 1.5.1 Standard |
| Traefik | 3.7.10 / chart 41.2.0 |
| WAF | Caddy 2.11.4 + coraza-caddy 2.5.0 + Coraza 3.7.0 + CRS 4.25.1 LTS |

Default topology:

```text
1 kind control plane
2 kind workers
Calico
Istio Ambient
Gateway API
Traefik
Caddy/Coraza
```

This is integration fidelity, not production HA evidence.

Kind default CNI is disabled. Local ports remain:

```text
localhost:8080 -> control-plane 80
localhost:8443 -> control-plane 443
```

## 5. Local edge/mesh rules

Local integration preserves the logical path:

```text
browser/curl -> Traefik -> Caddy/Coraza -> BFF -> internal services
```

- no Istio ingress gateway;
- distinct ServiceAccounts;
- Traefik/WAF/BFF Ambient-enrolled where required;
- direct Traefik->BFF denied;
- internal traffic strict mTLS;
- waypoints only for an explicit tested L7 need;
- local exposure does not claim upstream volumetric DDoS protection.

## 6. Optional local observability profile

When testing ADR-0044 end-to-end, use the production version family/pins:

```text
otelcol-contrib 0.157.0
Prometheus 3.13.2
Loki 3.7.4
Tempo 3.0.2
Grafana 13.1.3
Alertmanager 0.33.1
```

The local profile verifies:

- OTLP tracing;
- Prometheus scrape;
- JSON log collection;
- safe correlation across implemented gRPC/HTTP paths;
- PII/secret canary absence;
- bounded label/baggage behavior;
- Collector/backend fault behavior.

Local development does not need the production external host-down provider. That check is production/staging environment evidence.

## 7. Code-quality and DevSecOps tooling

Every Java service exposes repository-defined equivalents of:

- Spotless;
- SpotBugs;
- ArchUnit;
- Semgrep/SAST;
- Gitleaks current-tree/Git-history secret scanning;
- Gradle dependency verification/locks;
- OSV-Scanner declared/locked dependency advisory scanning;
- unit/focused integration/contract/dataset/quota/observability tests.

Release tooling follows ADR-0045 and Technology Baseline:

- Syft 1.51.0 produces final-image CycloneDX SBOM;
- Grype 0.117.0 performs final-image/SBOM release vulnerability correlation;
- Cosign 3.0.6 signs/attests the exact release digest;
- Kyverno remains production admission authority.

Normal local edits do not need to run full signing/admission/release promotion. They do not replace CI/staging/release evidence.

OSV-Scanner 2.4.0 provides earlier dependency feedback; Syft+Grype remain required for exact final-image release evidence.

Trivy and OWASP Dependency-Check are not selected baseline tools. Semgrep CLI does not imply separate Semgrep Secrets/Supply Chain products.

GitHub Actions remains required CI target. Documentation alone is not executed evidence.

## 8. Expected repository interfaces

Implemented fast-lane interfaces include:

```text
make baseline-verify
make local-runtime-test
make local-runtime-up
make local-runtime-status
```

Production-fidelity interfaces are added/used when their corresponding infrastructure exists:

```text
make local-cluster-verify
make verify-local-istio-ambient
make verify-local-traefik-edge
make verify-local-observability
```

Context Engine repository interfaces additionally include:

```text
make context-verify
make context-bootstrap
```

Context/Ops/Desktop MCP adapter/runtime tests execute from the independent Windows MCP runtime, not from HooshiX. Project commands issued through Ops use the explicit WSL bridge and `/home/coder/workspace/Hooshix`. Real MCP policies, audit/capture state, tunnel keys, and ADR-0050 credential values stay outside Git.

ChatGPT Web tunnel operation follows `docs/runbooks/chatgpt-web-secure-mcp-tunnel.md`; tunnel-client does not replace repository verification commands.

Expected versioned platform roots may include:

```text
infrastructure/kind/
infrastructure/calico/
infrastructure/istio/
infrastructure/traefik/
infrastructure/waf/
infrastructure/observability/
```

Service dependencies such as Xerial SQLite are pinned in service build/verification metadata, not a shared infrastructure lock.

## 9. Verification/governance

Local verifier should report installed Java/Git/Docker/containerd/kubectl/kind/Helm and installed cluster component versions/digests where applicable.

DevSecOps local/pre-push checks should report Gitleaks/Semgrep/OSV versions and pass/fail status without emitting discovered secret content. Release verification separately reports Syft/Grype/Cosign/Kyverno evidence when that boundary is active.

For ADR-0046/0051, HooshiX repository evidence verifies the project Context Engine and the external-runtime path guard. Independent runtime tests verify the Context adapter protocol surface. Host evidence verifies tunnel-client, restricted credentials, readiness, and live Context calls against the canonical WSL checkout.

For ADR-0048/0051, the independent Windows MCP runtime verifies Ops policy/process/filesystem/audit contracts, including the bounded persistent-job surface. Host evidence verifies protected policy/job-state/key ACLs, separate tunnel state, Windows token state, exact tool discovery, `ops.status`, WSL project-command execution, long-job polling across one response window, and runner-owned cancellation.

For ADR-0049/0050/0051, the independent Windows MCP runtime verifies Desktop policy/app/HWND/input/capture/credential-broker contracts. Host evidence verifies the actual interactive session, WinApp integration, tunnel state, and application-specific credential-use behavior.

Observability integration verifier additionally reports Collector/Prometheus/Loki/Tempo/Grafana/Alertmanager versions/digests when that profile is active.

Until a verifier exists and runs, local compliance remains `NOT VERIFIED`.

No agent infers an unlisted patch version or treats local convenience as permission to weaken production architecture/security.