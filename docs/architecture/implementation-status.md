# Implementation and Evidence Status — Current State

This file is the canonical repository-level status view for architecture, implementation presence, runtime evidence, and production readiness.

Architecture documents describe approved targets. A target path named in documentation is not proof that executable implementation exists.

## Current repository state

At this revision the repository contains documentation only. These implementation roots are not present:

```text
services/
deploy/
infrastructure/
.github/workflows/
```

Therefore no service, deployment, CI/security gate, telemetry runtime, restore exercise, load test, or production runtime is claimed as implemented/verified.

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
| CI/security/supply-chain gates | DESIGNED | NOT PRESENT | NOT VERIFIED |
| OpenTelemetry Collector | DESIGNED under ADR-0044 | NOT PRESENT | NOT VERIFIED |
| Prometheus/Alertmanager/Grafana | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Loki log backend | DESIGNED under ADR-0044 | NOT PRESENT | NOT VERIFIED |
| Tempo trace backend | DESIGNED under ADR-0044 | NOT PRESENT | NOT VERIFIED |
| External host-down monitoring | REQUIRED / PROVIDER TBD | NOT PRESENT | NOT VERIFIED |
| Authoritative privileged/security audit | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Backup/PITR/cold-DR automation | DESIGNED | NOT PRESENT | NOT VERIFIED |

## Pre-implementation blockers now decided but not evidenced

The architecture now defines, but the repository has not implemented/evidenced:

- Day-One structured logs/Micrometer metrics/OpenTelemetry traces and Collector/Loki/Tempo path;
- HIBP Pwned Passwords SHA-1 corpus acquisition, freshness, full-corpus bounds, and immutable SQLite build;
- ADR-0024 common-mode Clock Safety Guard and high-cardinality allocation protection;
- exact-IP hard vs aggregate-prefix pressure semantics;
- Kyverno CEL-only production policy gate;
- Reference Data deployable trigger enforcement;
- stable ADR identifier governance automation.

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