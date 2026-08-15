# Implementation and Evidence Status — Current State

This file is the canonical repository-level status view for architecture, implementation presence, runtime evidence, and production readiness.

Architecture documents describe approved targets. A target path named in documentation is not proof that executable implementation exists.

## Current repository state

At this revision the repository contains architecture documentation plus a repository-governance baseline.

Implemented repository-governance artifacts are:

```text
Makefile
scripts/baseline/
.github/workflows/repository-baseline.yml
```

The baseline verifies repository file-index consistency, stable ADR identifiers/register coverage, dependency-registry/schema/Markdown-view consistency, current source references, and selected guarded structure rules. It is repository governance only. It is not an executable service, runtime security control, deployment, telemetry runtime, or release/supply-chain pipeline.

These implementation roots are still not present:

```text
services/
deploy/
infrastructure/
```

Therefore no service, deployment, telemetry runtime, restore exercise, load test, artifact-signing pipeline, vulnerability gate, admission policy, or production runtime is claimed as implemented/verified.

## Capability/service status

| Capability | Architecture | Independent implementation | Runtime evidence | Production readiness | Planned target |
| --- | --- | --- | --- | --- | --- |
| Identity Service | DESIGNED | NOT PRESENT | NOT VERIFIED | NOT VERIFIED | `services/identity-service` |
| Authorization Service | DESIGNED | NOT PRESENT | NOT VERIFIED | NOT VERIFIED | `services/authorization-service` |
| Notification Service | DESIGNED | NOT PRESENT | NOT VERIFIED | NOT VERIFIED | `services/notification-service` |
| Web BFF | DESIGNED | NOT PRESENT | NOT VERIFIED | NOT VERIFIED | `services/web-bff` |
| Compromised Password Service | DESIGNED | NOT PRESENT | NOT VERIFIED | NOT VERIFIED | `services/compromised-password-service` after HIBP corpus/build gates |
| Reference Data capability | DESIGNED | local immutable adapter permitted when needed | NOT VERIFIED | NOT VERIFIED | owning deployable bundle/module |
| Reference Data independent service | DESIGNED / GATED | PLANNED / GATED | NOT VERIFIED | NOT VERIFIED | `services/reference-data-service` only after ADR-0041 trigger |

`DESIGNED` does not mean source exists.

## Platform status

| Platform area | Architecture | Implementation | Evidence |
| --- | --- | --- | --- |
| Repository governance baseline | DESIGNED | IMPLEMENTED | CI evidence is commit-specific; `make baseline-verify` is the local entry point |
| K3s/Kubernetes/Calico | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Istio Ambient | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Kyverno CEL policy set | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Traefik + Caddy/Coraza edge | DESIGNED | NOT PRESENT | NOT VERIFIED |
| WireGuard management overlay | DESIGNED | NOT PRESENT | NOT VERIFIED |
| CloudNativePG/PostgreSQL | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Security Redis | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Kafka | DESIGNED | NOT PRESENT | NOT VERIFIED |
| OpenBao + External Secrets | DESIGNED | NOT PRESENT | NOT VERIFIED |
| GitOps/Argo CD | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Service CI/security/supply-chain gates | DESIGNED | NOT PRESENT | NOT VERIFIED |
| OpenTelemetry Collector | DESIGNED under ADR-0044 | NOT PRESENT | NOT VERIFIED |
| Prometheus/Alertmanager/Grafana | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Loki log backend | DESIGNED under ADR-0044 | NOT PRESENT | NOT VERIFIED |
| Tempo trace backend | DESIGNED under ADR-0044 | NOT PRESENT | NOT VERIFIED |
| External host-down monitoring | REQUIRED / PROVIDER TBD | NOT PRESENT | NOT VERIFIED |
| Authoritative privileged/security audit | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Backup/PITR/cold-DR automation | DESIGNED | NOT PRESENT | NOT VERIFIED |

## Repository governance now enforced

The bootstrap baseline now makes these current repository invariants executable:

- `FILE_INDEX.txt` must exactly match the clean repository file set and remain sorted;
- ADR file identifiers, headings, and the Decision Register must remain consistent and non-reused;
- dependency-registry version/classes/required edge fields/policy references must match the current schema constraints enforced by the bootstrap verifier;
- the dependency Markdown operation list must match canonical YAML exactly and in canonical order;
- current architecture source references checked by the baseline must resolve to repository files;
- the ADR-0041-gated `services/reference-data-service` path is rejected until the architecture/trigger evidence is intentionally revised;
- root `services/common` and `services/shared` dumping grounds are rejected.

This is not a substitute for the full schema/coverage/contract/static-analysis/runtime gates required when implementation appears.

## Pre-implementation blockers now decided but not evidenced

The architecture now defines, but the repository has not implemented/evidenced:

- Day-One structured logs/Micrometer metrics/OpenTelemetry traces and Collector/Loki/Tempo path for the first executable service;
- HIBP Pwned Passwords SHA-1 corpus acquisition, freshness, full-corpus bounds, and immutable SQLite build;
- ADR-0024 common-mode Clock Safety Guard and high-cardinality allocation protection;
- exact-IP hard vs aggregate-prefix pressure semantics;
- Kyverno CEL-only production policy gate;
- Reference Data deployable trigger evidence beyond the repository guard;
- service-owned Java build, dependency verification, ArchUnit, Spotless, SpotBugs, Semgrep, contract, container, and release gates.

These are implementation/release gates, not evidence that production is ready.

## Repository-level vocabulary

```text
Architecture:
  DESIGNED
  NOT DESIGNED

Implementation:
  IMPLEMENTED
  PARTIAL
  PLANNED / GATED
  NOT PRESENT
  NOT APPLICABLE

Evidence:
  PASS
  FAIL
  NOT RUN
  NOT VERIFIED
  NOT APPLICABLE
```

`IMPLEMENTED` means required repository artifact exists. It is not runtime proof.

`PASS` requires executed evidence from the applicable build/test/security/restore/load/environment gate.

## Update rule

When implementation is added/removed, update this file in the same coherent PR when repository-level status changes materially.

Runtime evidence remains in owning CI/report/environment artifact. This file may summarize but cannot replace evidence.

`PRODUCTION-READINESS-CHECKLIST.md` remains the traffic gate.
