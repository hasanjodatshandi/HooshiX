# Architecture Documentation

This directory contains the **current-state architecture** of the platform. It is written for implementation, review, security analysis, and production-readiness work.

## How to read the architecture

Start with:

1. `../../AGENTS.md`
2. `../engineering/current-only-documentation-policy.md`
3. `../engineering/repository-change-workflow.md`
4. `SOURCES.md`
5. `TASK-REVIEW-MATRIX.md` for targeted navigation
6. `../adr/decision-register.md`
7. the current-state document(s) relevant to the task
8. the retained current ADRs identified by the register/source map

All repository changes follow the PR-first workflow: branch -> Draft PR -> task changes -> complete review against current `main` -> applicable verification -> merge. Normal work does not commit directly to `main`.

For targeted implementation work, read only the current-state documents and retained current ADRs that are clearly applicable. For service-boundary, security, infrastructure, or architecture work, perform a full read of the applicable architecture set.

## Current-state documents

- `platform-architecture.md` — system topology, service ownership, protocol boundaries, architectural principles.
- `backend-engineering.md` — Java/Spring, DDD/Hexagonal, package structure, DI, coding constraints.
- `../engineering/coding-standards.md` — canonical implementation-level Java coding standard.
- `../engineering/build-and-ci-quality-enforcement.md` — executable Gradle/Spotless/SpotBugs/ArchUnit/Semgrep/GitHub Actions enforcement baseline.
- `../engineering/repository-change-workflow.md` — mandatory branch/PR/review/merge workflow.
- `../engineering/current-only-documentation-policy.md` — active owner directive for keeping only effective documentation/decisions.
- `../engineering/agent-communication-and-reporting.md` — mandatory evidence/reporting contract.
- `security-architecture.md` — tenancy, deletion/retention, authentication, sessions, MFA, authorization, secrets, quotas.
- `data-and-messaging.md` — PostgreSQL, Flyway, JPA/jOOQ, transactions, Kafka, Protobuf, Redis.
- `reliability-and-observability.md` — deadlines, retries, Virtual Threads, SLOs, DR, telemetry, logging/PII.
- `runtime-and-deployment.md` — Kubernetes, Helm, GitOps, Argo CD, Traefik, WAF, Istio, OpenBao.
- `testing-and-quality-gates.md` — testing matrix, CI/CD ordering, architecture enforcement, Definition of Done.
- `TASK-REVIEW-MATRIX.md` — task-to-source routing for targeted reviews.
- `PRODUCTION-READINESS-CHECKLIST.md` — implementation/evidence production gates.
- `performance-and-bottlenecks.md` — current performance/operational bottleneck register and scale/split triggers.
- `dependency-criticality.yaml` — canonical machine-readable operation/dependency criticality registry.
- `dependency-criticality.schema.json` — CI validation schema for the registry.
- `dependency-criticality-matrix.md` — generated human-readable view of the registry.
- `PRODUCTION-DECISION-SUMMARY.md` — concise current production decision summary.
- `PRODUCTION-ARCHITECTURE-REVIEW.md` — production review outcome and bottleneck summary.
- `services/` — service-specific current architecture.

## Current-only ADR rule

The active repository-owner directive is defined in `../engineering/current-only-documentation-policy.md`.

Only ADRs with still-effective scope are retained. Completely superseded ADRs and raw historical source material are removed after their still-current semantics are confirmed to exist in retained ADRs/current-state documents. Partially stale ADRs are normalized so obsolete alternatives are not presented as active architecture.

When current-state documentation and a retained ADR disagree, inspect `../adr/decision-register.md` and correct the stale source in the same change rather than inferring a historical precedence chain.

## Technology versions

Do not encode patch-version decisions in current-state architecture unless the version itself is architecturally significant. Use `../technology/technology-baseline.md` for production/application pins, `../technology/local-development-baseline.md` for workstation/container/kind pins, `../technology/production-compatibility-matrix.md` for supported production combinations, and repository lock/wrapper/image files for exact deployed artifacts. Local mesh/edge operations use `../runbooks/local-istio-ambient.md` and `../runbooks/local-traefik-edge.md`.
