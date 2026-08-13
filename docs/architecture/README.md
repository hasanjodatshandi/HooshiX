# Architecture Documentation

This directory contains the **current-state architecture** of the platform. It is written for implementation and review, not as a replacement for ADR history.

## How to read the architecture

Start with:

1. `../../AGENTS.md`
2. `SOURCES.md`
3. `TASK-REVIEW-MATRIX.md` for targeted navigation
4. `../adr/decision-register.md`
5. the current-state document(s) relevant to the task
6. the applicable ADRs identified by the register and source map

For targeted implementation work, read only the current-state documents and ADRs that are clearly applicable. For service-boundary, security, infrastructure, or architecture work, perform a full read of the applicable architecture set.

## Current-state documents

- `platform-architecture.md` — system topology, service ownership, protocol boundaries, architectural principles.
- `backend-engineering.md` — Java/Spring, DDD/Hexagonal, package structure, DI, coding constraints.
- `../engineering/coding-standards.md` — complete implementation-level Java coding standard.
- `../engineering/build-and-ci-quality-enforcement.md` — executable Gradle/Spotless/SpotBugs/ArchUnit/Semgrep/GitHub Actions enforcement baseline.
- `../engineering/agent-communication-and-reporting.md` — mandatory evidence/reporting contract for implementation and review work.
- `security-architecture.md` — tenancy, deletion/retention, authentication, sessions, MFA, authorization, secrets, quotas.
- `data-and-messaging.md` — PostgreSQL, Flyway, JPA/jOOQ, transactions, Kafka, Protobuf, Redis.
- `reliability-and-observability.md` — deadlines, retries, Virtual Threads, SLOs, DR, telemetry, logging/PII.
- `runtime-and-deployment.md` — Kubernetes, Helm, GitOps, Argo CD, Traefik, WAF, Istio, OpenBao.
- `testing-and-quality-gates.md` — testing matrix, CI/CD ordering, architecture enforcement, Definition of Done.
- `TASK-REVIEW-MATRIX.md` — fast task-to-source routing for targeted reviews.
- `PRODUCTION-READINESS-CHECKLIST.md` — implementation/evidence production gates for the accepted production design.
- `performance-and-bottlenecks.md` — current performance/operational bottleneck register and scale/split triggers.
- `dependency-criticality.yaml` — canonical machine-readable operation/dependency criticality registry.
- `dependency-criticality.schema.json` — CI validation schema for the registry.
- `dependency-criticality-matrix.md` — generated human-readable view of the registry.
- `PRODUCTION-DECISION-SUMMARY.md` — concise summary of accepted production-review decisions through ADR-0069.
- `PRODUCTION-ARCHITECTURE-REVIEW.md` — production review outcome and bottleneck summary.
- `services/` — service-specific current architecture.

## ADR rule

ADRs are immutable historical decisions. If current-state documentation conflicts with an ADR, inspect `../adr/decision-register.md` and later superseding ADRs before acting. Current-state documents should be corrected when they are stale.

## Technology versions

Do not encode patch-version decisions in current-state architecture unless the version itself is architecturally significant. Use `../technology/technology-baseline.md` for production/application pins, `../technology/local-development-baseline.md` for workstation/container/kind pins, `../technology/production-compatibility-matrix.md` for supported production combinations, and repository lock/wrapper/image files for exact deployed artifacts. Local mesh/edge operations use `../runbooks/local-istio-ambient.md` and `../runbooks/local-traefik-edge.md`.
