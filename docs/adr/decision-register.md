# Architecture Decision Register — Current State

This register identifies current effective ADRs. ADR identifiers are stable after merge under `docs/engineering/current-only-documentation-policy.md`; gaps are permitted and IDs are never renumbered/reused.

## Current effective ADRs

| ADR | Current decision area |
| --- | --- |
| ADR-0001 | dedicated Caddy/Coraza edge WAF |
| ADR-0002 | production Istio trust/enrollment |
| ADR-0003 | Git/Buf schema governance without runtime Schema Registry |
| ADR-0004 | initial cold disaster recovery |
| ADR-0005 | production SLO classes/error budgets |
| ADR-0006 | Notification durable handoff/semantic contract |
| ADR-0007 | Notification runtime |
| ADR-0008 | registration locale persistence/resend reuse |
| ADR-0009 | Identity registration runtime |
| ADR-0010 | versioned database Notification templates/Liara email |
| ADR-0011 | GitOps/OpenBao production secret authority |
| ADR-0012 | Identity tenant/session/external identity/MFA |
| ADR-0013 | online Authorization without permission cache/Kafka |
| ADR-0014 | local Notification delivery key ring |
| ADR-0015 | Kafka durability/rebuildable DR |
| ADR-0016 | Web BFF browser OIDC/session security |
| ADR-0017 | signed artifacts/provenance/admission; Kyverno CEL policy APIs |
| ADR-0018 | Notification clock/dispatch safety |
| ADR-0019 | CloudNativePG/Barman persistence baseline |
| ADR-0020 | IPPanel SMS provider |
| ADR-0021 | production platform compatibility/CNI |
| ADR-0022 | self-hosted Kubernetes HA expansion topology |
| ADR-0023 | Identity JWT signing-key lifecycle |
| ADR-0024 | semantic security quotas: exact-IP, aggregate pressure, time/cardinality fail-closed safety |
| ADR-0025 | synchronous dependency failure containment |
| ADR-0026 | online Authorization overload/SLO |
| ADR-0027 | PostgreSQL service isolation/tenant RLS |
| ADR-0028 | data-subject erasure execution/evidence |
| ADR-0029 | upstream volumetric DDoS protection |
| ADR-0030 | production human JIT access |
| ADR-0031 | PII-safe logging detection pipeline |
| ADR-0032 | Authorization SLO alerting/breaker recovery |
| ADR-0033 | operation-level dependency criticality/degradation |
| ADR-0034 | CloudNativePG fleet operations |
| ADR-0035 | SBOM/vulnerability response/deployment gates |
| ADR-0036 | Authorization breaker/dependency governance |
| ADR-0037 | PostgreSQL restore/upgrade evidence |
| ADR-0038 | vulnerability exceptions/threat intelligence/ownership |
| ADR-0039 | Java coding/executable quality gates |
| ADR-0040 | independent Compromised Password service with offline HIBP SHA-1 immutable SQLite corpus |
| ADR-0041 | Reference Data capability; independent service remains evidence-gated |
| ADR-0042 | selected single-server production profile + HA expansion profile |
| ADR-0043 | production network/client-address/management trust boundaries |
| ADR-0044 | Day-One observability runtime: Micrometer/OpenTelemetry/Prometheus/Loki/Tempo/Grafana/external host-down signal |
| ADR-0045 | DevSecOps responsibility map: Semgrep, Gitleaks, OSV-Scanner, Syft, Grype, Cosign, and Kyverno |
| ADR-0046 | Git-native Agent Context Engine: verified bootstrap, task routing, commit-bound checkpoints, bounded retrieval, read-only MCP |
| ADR-0047 | OpenAI Secure MCP Tunnel bridge for ChatGPT Web access to the existing read-only stdio Context MCP |
| ADR-0048 | policy-gated developer-host Ops MCP for separate local filesystem/process authority |

## Superseded ADR identifiers

None currently retained as fully superseded tombstones.

When a current ADR becomes fully superseded, keep its stable original file/ID with `Status: Superseded by ...` and list it here. A superseded entry is provenance, not implementation authority.