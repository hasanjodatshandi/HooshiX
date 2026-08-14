# Architecture Documentation

This directory contains the **current-state implementation-facing architecture** of HooshiX.

## How to read

Start with:

1. `../../AGENTS.md`;
2. `../engineering/current-only-documentation-policy.md`;
3. `../engineering/repository-change-workflow.md`;
4. `SOURCES.md`;
5. `../adr/decision-register.md`;
6. `PRODUCTION-DECISION-SUMMARY.md`;
7. applicable service/platform/security/data/reliability documents;
8. `PRODUCTION-READINESS-CHECKLIST.md` for executable evidence.

ADR IDs are stable after merge. Current-state documents remain current-only; a superseded ADR retained for provenance is not current implementation authority.

## Current production profile

Initial profile is `production-single-server` under ADR-0042. `production-ha` remains the expansion profile.

Single-server intentionally accepts one-host availability/failure-domain risk. It does not weaken MFA, Authorization, tenant RLS, OpenBao, WAF/client trust, admission/supply-chain, required audit, backup/PITR, or fail-closed security dependencies.

## Current implementation-readiness decisions

Before the first executable vertical slice, current architecture requires:

- ADR-0040: official offline HIBP Pwned Passwords SHA-1 corpus with provenance/freshness/full-corpus bounds; SHA-1 screening-only, Argon2id storage unchanged;
- ADR-0024: exact-IP hard quota identity, aggregate-prefix pressure, common-mode wall-clock guard, and high-cardinality Redis allocation protection;
- ADR-0041: Reference Data remains local immutable capability until an independent-service trigger is evidenced;
- ADR-0044: structured logging, Micrometer metrics, OpenTelemetry tracing, Collector/Loki/Tempo/Prometheus/Grafana/Alertmanager integration, and external host-down detection from Day-1;
- ADR-0017/build gates: Kyverno new production policies use stable CEL-based `policies.kyverno.io/v1` APIs;
- stable merged ADR identifiers and coherent-change PR governance.

These are target decisions. `implementation-status.md` remains authoritative for whether code/deployment/CI/runtime evidence exists.

## Document map

- `platform-architecture.md` — platform/service topology.
- `network-architecture.md` — public/management/workload trust paths.
- `security-architecture.md` / `threat-model.md` — security objectives, boundaries, threats.
- `data-and-messaging.md` — data ownership/PostgreSQL/Redis/Kafka/reference datasets.
- `reliability-and-observability.md` — reliability and Day-One telemetry runtime.
- `performance-and-bottlenecks.md` — capacity/saturation/evidence triggers.
- `runtime-and-deployment.md` — runtime/GitOps/profile behavior.
- `testing-and-quality-gates.md` — executable verification portfolio.
- `security-verification-matrix.md` / `architecture-fitness-functions.md` — traceable security/architecture properties.
- `PRODUCTION-READINESS-CHECKLIST.md` — production traffic gate.
- `implementation-status.md` — actual repository implementation/evidence presence.
- `services/` — implementation-facing service contracts.

## Authority rule

Do not create a second normative copy merely for convenience. ADR/current architecture/standards have the precedence defined in Documentation Standards. Lower-level documents may add implementation context but cannot weaken higher-level decisions.

A path or component name in documentation is not proof it exists. Only actual repository/runtime evidence may be reported as implemented/passed.