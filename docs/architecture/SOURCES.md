# Architecture Sources

This file is the canonical source catalog for AI agents and reviewers.

## Mandatory entry points

- `/AGENTS.md`
- `/docs/architecture/README.md`
- `/docs/architecture/SOURCES.md`
- `/docs/architecture/TASK-REVIEW-MATRIX.md`
- `/docs/architecture/PRODUCTION-READINESS-CHECKLIST.md`
- `/docs/architecture/performance-and-bottlenecks.md`
- `/docs/architecture/PRODUCTION-DECISION-SUMMARY.md`
- `/docs/architecture/PRODUCTION-ARCHITECTURE-REVIEW.md`
- `/docs/adr/decision-register.md`
- `/docs/technology/technology-baseline.md`
- `/docs/technology/local-development-baseline.md`
- `/docs/technology/production-compatibility-matrix.md`
- `/docs/engineering/agent-communication-and-reporting.md`
- `/docs/engineering/developer-workflow.md`
- `/docs/engineering/coding-standards.md`
- `/docs/engineering/build-and-ci-quality-enforcement.md`

## Current-state architecture

| Document | Primary scope |
| --- | --- |
| `platform-architecture.md` | Global topology, ownership, protocols, shared platform resilience |
| `backend-engineering.md` | Java/Spring, DDD/Hexagonal, package rules, DI, coding/layering |
| `security-architecture.md` | Tenancy, deletion, authn/authz, browser security, secrets, quotas, supply chain |
| `data-and-messaging.md` | PostgreSQL/CloudNativePG, Flyway, JPA/jOOQ, transactions, Kafka, Redis |
| `reliability-and-observability.md` | Deadlines/retries/bulkheads, SLOs, HA/DR, telemetry, PII-safe logging |
| `dependency-criticality.yaml` | Canonical machine-readable operation/dependency criticality registry |
| `dependency-criticality.schema.json` | CI schema for dependency registry validation |
| `dependency-criticality-matrix.md` | Generated human-readable authoritative/degradable dependency view |
| `runtime-and-deployment.md` | Kubernetes, Helm/GitOps, Calico, WAF, Istio, OpenBao, admission |
| `testing-and-quality-gates.md` | Tests, CI/CD, architecture enforcement, Definition of Done |
| `performance-and-bottlenecks.md` | Runtime/development bottlenecks, mitigations, scale/split triggers |
| `PRODUCTION-DECISION-SUMMARY.md` | Consolidated production review decisions through ADR-0069 |
| `PRODUCTION-ARCHITECTURE-REVIEW.md` | Final production review outcome, bottlenecks and simplification rationale |
| `TASK-REVIEW-MATRIX.md` | Task-to-source routing for targeted review |
| `PRODUCTION-READINESS-CHECKLIST.md` | Implementation/evidence gates for the accepted production design |

## Service-specific architecture

| Document | Primary scope |
| --- | --- |
| `services/identity-service.md` | Registration, tenant, sessions, OIDC integration, MFA, quotas, Notification handoff |
| `services/authorization-service.md` | Roles/grants/denies, online CheckPermission, SLO/capacity, admin quotas |
| `services/notification-service.md` | Durable handoff/lifecycle, PG deadlines, simplified dispatch, templates, Liara/IPPanel providers, local key ring |
| `services/web-bff.md` | OIDC PKCE, browser session/CSRF/CORS, REST/OpenAPI, internal gRPC |

## ADR thematic map

The Decision Register is authoritative for status and supersession. This map is navigation only.

### Foundation / architecture

- ADR-0001 — Identity first; `com.sajtech`; independent service builds.
- ADR-0007 — feature-first/nature-separated Java packages.
- ADR-0032 — current Notification package `com.sajtech.notification`; provider portions superseded later.

### Multi-tenancy / deletion / Identity / browser

- ADR-0002 — global User + tenant membership + service-owned tenant-aware DB.
- ADR-0003 — logical deletion, retention, erasure, legal holds.
- ADR-0006 — registration, external identity, credential/MFA foundations.
- ADR-0034 — immutable registration locale + resend reuse.
- ADR-0035 — registration runtime enabled.
- ADR-0038 — tenant/session/external identity/MFA v1.
- ADR-0040 — historical production semantic-quota decision gate.
- ADR-0041 — semantic quota architecture, resolving ADR-0040.
- ADR-0054 — current quota time-safety/refill/cleanup hardening.
- ADR-0045 — current browser/BFF/OIDC/session/CSRF/CORS security.
- ADR-0049 — current Iran SMS provider baseline used by SMS-dependent flows.
- ADR-0052 — current Identity RS256/RSA-3072 signing-key lifecycle and local verifier-bundle model.

### Authorization

- ADR-0004 — role/permission ownership and evaluation semantics.
- ADR-0005 — historical cached runtime; runtime portion superseded by ADR-0039.
- ADR-0039 — current online CheckPermission, no cache/Kafka/retry/fallback.
- ADR-0042 — historical initial Authorization SLO/HA/capacity details; latency/overload portions superseded by ADR-0056.
- ADR-0055 — current synchronous failure-containment/circuit/bulkhead policy.
- ADR-0056 — current Authorization overload isolation and latency SLO.
- ADR-0062 — current Authorization burn-rate alerting and half-open real-contract recovery.
- ADR-0063 — current operation-level dependency criticality/degradation model.
- ADR-0066 — current Authorization breaker de-correlation/serialized half-open probes plus machine-checkable dependency-policy governance.

### Secrets / GitOps / supply chain

- ADR-0009 — provider-neutral key lifecycle.
- ADR-0011 — OpenBao + Argo CD selection; GitOps repo location and Notification Transit exception later superseded in part; other scope remains.
- ADR-0037 — in-repository GitOps + OpenBao 2.6.1.
- ADR-0043 — Notification local mounted key ring, superseding Transit hot path.
- ADR-0046 — Cosign + Kyverno signed/provenanced admission.
- ADR-0065 — continuous digest-indexed SBOM vulnerability response and promotion gates.
- ADR-0068 — current vulnerability-exception expiry, threat-intelligence prioritization, and artifact/dependency ownership.
- ADR-0069 — current Java coding-standard and executable Spotless/SpotBugs/ArchUnit/Semgrep/GitHub Actions quality-gate baseline.
- ADR-0050 — current version compatibility/CNI/upgrade governance.

### Notification

- ADR-0008 — verification-delivery security constraints; ownership/runtime parts superseded by later Notification ADRs.
- ADR-0010 — human-channel delivery belongs to Notification.
- ADR-0012..ADR-0017 — durable handoff, lifecycle/evidence/outcomes/retry, acceptance deadlines, PostgreSQL-authoritative time; current except explicit later supersession.
- ADR-0018..ADR-0023 — historical bespoke clock/degraded/primary/fence runtime; current-v1 mechanism superseded by ADR-0047.
- ADR-0029 — finalized semantic contract/handoff/fingerprint/callback/retention; Transit hot-path portion superseded by ADR-0043.
- ADR-0030 — persistence/workers/operations remain where not superseded; provider/single-primary/fence portions superseded later.
- ADR-0031 — historical clock-agent/fence runtime; superseded for current v1 by ADR-0047.
- ADR-0032 — historical IPPanel Pattern choice; package remains current, provider portion superseded.
- ADR-0033 — historical SMS-provider deferral; `LoggingSmsProviderAdapter` remains local-only.
- ADR-0036 — current PostgreSQL templates + Liara SMTP Email.
- ADR-0043 — current Notification local escrow key ring.
- ADR-0047 — current simplified dispatch safety, no bespoke clock/fence runtime.
- ADR-0048 — current PostgreSQL HA preserving acknowledged dispatch state.
- ADR-0049 — current IPPanel Edge Webservice SMS for Iran, bounded polling, no Pattern rendering.

### Platform / data / production

- ADR-0024 — dedicated Caddy+Coraza WAF.
- ADR-0025 — Istio trust domain/CA/enrollment.
- ADR-0026 — Git+Buf, no runtime Schema Registry v1.
- ADR-0027 — cold DR targets/retention/restore exercises; PostgreSQL backup shape superseded by ADR-0048.
- ADR-0028 — production SLO classes/error budgets.
- ADR-0037 — in-repo GitOps/OpenBao pin.
- ADR-0044 — current Kafka durability + outbox/state-based DR reconstruction.
- ADR-0046 — current artifact admission security.
- ADR-0048 — current CloudNativePG HA + Barman Cloud WAL/PITR backup shape.
- ADR-0050 — current Calico + production compatibility/upgrade baseline.
- ADR-0051 — current self-hosted Kubernetes active-cluster HA topology.
- ADR-0058 — current cross-service data-subject erasure execution/evidence.
- ADR-0059 — current upstream L3/L4 volumetric DDoS requirement.
- ADR-0060 — current JIT privileged human production-access plane.
- ADR-0061 — current PII-safe logging prevention/detection pipeline.
- ADR-0053 — distinct PostgreSQL database/credentials per persistent microservice.
- ADR-0057 — current production physical cluster isolation + mandatory forced tenant RLS.
- ADR-0064 — current standardized dedicated CloudNativePG fleet operations; no planned reconsolidation.
- ADR-0067 — current PostgreSQL restore evidence, drill consequence, and safe upgrade/rollback policy.

## Historical preservation

All accepted ADR files under `/docs/adr/` are immutable historical records. Never edit an old accepted ADR to make it look current. A changed decision is a new superseding ADR plus Decision Register/current-state documentation updates.

## Raw source material

`/docs/source-material/original-architecture-notes.md` is preserved input material only. It is not authoritative when it conflicts with current accepted ADR supersession.

## Engineering workflow

- `/docs/engineering/developer-workflow.md` defines the fast developer inner loop and heavy verification cadence without weakening production gates.
- `/docs/engineering/coding-standards.md` is the canonical implementation-level Java coding standard.
- `/docs/engineering/build-and-ci-quality-enforcement.md` defines required Gradle/Spotless/SpotBugs/ArchUnit/Semgrep/GitHub Actions enforcement and the evidence required before claiming code compliance.

## Operations

- `../operations/incident-response-runbook.md` — minimum production incident command/containment/evidence workflow.
- `../operations/chaos-engineering-program.md` — staging-first failure, restore, failover, and game-day evidence program.
- `../runbooks/local-istio-ambient.md` — pinned local kind Ambient install/verify/remove/diagnostic workflow.
- `../runbooks/local-traefik-edge.md` — local Gateway API/Traefik/WAF edge install/verify/remove/diagnostic workflow.
