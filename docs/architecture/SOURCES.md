# Architecture Sources — Current State

- **Mode:** current implementation guidance + stable ADR identifiers
- **Policy:** `../engineering/current-only-documentation-policy.md`
- **Decision index:** `../adr/decision-register.md`
- **Selected production profile:** `production-single-server` under ADR-0042

This file is a routing/source index. It does not duplicate normative architecture.

## Repository authorities

| Topic | Primary current source |
| --- | --- |
| Current ADRs/stable IDs | `../adr/decision-register.md` |
| Production profile | ADR-0042 + `PRODUCTION-READINESS-CHECKLIST.md` |
| Network/client address | ADR-0043 + `network-architecture.md` |
| Semantic quota | ADR-0024 |
| Day-One observability | ADR-0044 + `reliability-and-observability.md` |
| PII-safe logging | ADR-0031 |
| Compromised Password corpus/runtime | ADR-0040 + `services/compromised-password-service.md` |
| Reference Data capability/service trigger | ADR-0041 + `services/reference-data-service.md` |
| Authorization | ADR-0013/0026/0032/0036 + service doc |
| Identity/MFA/session | ADR-0012/0023 + service doc |
| BFF/browser security | ADR-0016 + service doc |
| Data/messaging | `data-and-messaging.md` |
| Testing/evidence | `testing-and-quality-gates.md` + security/readiness/fitness matrices |
| Build/CI | `../engineering/build-and-ci-quality-enforcement.md` |
| Technology pins | `../technology/technology-baseline.md` |
| Compatibility | `../technology/production-compatibility-matrix.md` |
| Chaos/DR | `../operations/chaos-engineering-program.md` + `../runbooks/production-cold-dr.md` |

## External primary sources used by current decisions

Use upstream/official primary sources for version, protocol, API, and support claims.

### Compromised Password

- HIBP API / Pwned Passwords documentation: `https://haveibeenpwned.com/API/V3`
  - Pwned Passwords corpus is available as SHA-1 or NTLM;
  - default range protocol uses first five SHA-1 characters;
  - complete corpus can be downloaded for offline use.
- Official HIBP Pwned Passwords Downloader: `https://github.com/HaveIBeenPwned/PwnedPasswordsDownloader`

ADR-0040 converts those facts into HooshiX-specific offline SHA-1 corpus/freshness/provenance policy. HIBP source facts do not change Argon2id password storage.

### Spring application observability

- Spring Boot Observability: `https://docs.spring.io/spring-boot/reference/actuator/observability.html`
- Spring Boot Tracing: `https://docs.spring.io/spring-boot/reference/actuator/tracing.html`
- Spring Boot Metrics: `https://docs.spring.io/spring-boot/reference/actuator/metrics.html`

Current Spring Boot baseline uses Micrometer Observation/Tracing, Prometheus-compatible metrics, and OpenTelemetry/OTLP trace export under ADR-0044.

### OpenTelemetry Collector

- deployment patterns: `https://opentelemetry.io/docs/collector/deploy/`
- agent pattern: `https://opentelemetry.io/docs/collector/deploy/agent/`
- gateway pattern: `https://opentelemetry.io/docs/collector/deploy/gateway/`
- official releases: `https://github.com/open-telemetry/opentelemetry-collector-releases/releases`

Technology Baseline pins `otelcol-contrib` 0.157.0; deployment/security behavior remains governed by ADR-0044.

### Grafana observability backends

- Loki release notes: `https://grafana.com/docs/loki/latest/release-notes/`
- Loki releases: `https://github.com/grafana/loki/releases`
- Tempo release notes: `https://grafana.com/docs/tempo/latest/release-notes/`
- Tempo releases: `https://github.com/grafana/tempo/releases`

Technology Baseline pins Loki 3.7.4 and Tempo 3.0.2 for the reviewed current single-server observability target.

### Kyverno

- Policy Types overview: `https://kyverno.io/docs/policy-types/overview/`
- ImageValidatingPolicy: `https://kyverno.io/docs/policy-types/image-validating-policy/`

Kyverno 1.18 marks `policies.kyverno.io/v1` CEL types stable and legacy ClusterPolicy/CleanupPolicy families deprecated. HooshiX greenfield production policy gates therefore reject new legacy policy manifests.

## Source-use rules

- Do not copy upstream release notes into multiple repository authorities.
- Record exact production versions only in Technology Baseline and deployment locks/digests.
- A source URL is evidence input, not proof that repository implementation exists.
- When an upstream statement changes materially, review affected ADR/baseline/compatibility/evidence gates in one coherent change.