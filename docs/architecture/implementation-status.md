# Implementation and Evidence Status — Current State

This file is the canonical repository-level status view for architecture, implementation presence, runtime evidence, and production readiness.

Architecture documents describe the approved target. A target path named in an architecture document is not proof that the executable path exists.

## Current repository state

At this documentation revision, the repository contains architecture/engineering/operations/technology documentation but does not yet contain the planned executable application/platform tree.

The following top-level implementation targets are not present:

```text
services/
deploy/
infrastructure/
.github/workflows/
```

Therefore, no service, deployment, CI, security gate, restore exercise, load test, or production runtime is claimed as implemented or verified by repository contents at this revision.

## Service status

| Capability | Architecture | Implementation | Runtime evidence | Production readiness | Planned target |
| --- | --- | --- | --- | --- | --- |
| Identity Service | DESIGNED | NOT PRESENT | NOT VERIFIED | NOT VERIFIED | `services/identity-service` |
| Authorization Service | DESIGNED | NOT PRESENT | NOT VERIFIED | NOT VERIFIED | `services/authorization-service` |
| Notification Service | DESIGNED | NOT PRESENT | NOT VERIFIED | NOT VERIFIED | `services/notification-service` |
| Web BFF | DESIGNED | NOT PRESENT | NOT VERIFIED | NOT VERIFIED | `services/web-bff` |
| Compromised Password Service | DESIGNED | NOT PRESENT | NOT VERIFIED | NOT VERIFIED | `services/compromised-password-service` |
| Reference Data Service | DESIGNED | PLANNED / GATED | NOT VERIFIED | NOT VERIFIED | `services/reference-data-service` after ADR-0041 trigger |

`DESIGNED` means the current architecture/ADR contract exists. It does not mean source code exists.

## Platform status

| Platform area | Architecture | Implementation | Evidence |
| --- | --- | --- | --- |
| K3s/Kubernetes profile | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Calico | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Istio Ambient | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Kyverno | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Traefik + Caddy/Coraza edge | DESIGNED | NOT PRESENT | NOT VERIFIED |
| WireGuard management overlay | DESIGNED | NOT PRESENT | NOT VERIFIED |
| CloudNativePG/PostgreSQL | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Redis | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Kafka | DESIGNED | NOT PRESENT | NOT VERIFIED |
| OpenBao + External Secrets | DESIGNED | NOT PRESENT | NOT VERIFIED |
| GitOps/Argo CD | DESIGNED | NOT PRESENT | NOT VERIFIED |
| CI/security/supply-chain gates | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Observability/audit pipeline | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Backup/PITR/cold-DR automation | DESIGNED | NOT PRESENT | NOT VERIFIED |

## Repository-level status vocabulary

Use these values for the repository-level summary in this file:

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

Individual readiness checklist items may use their narrower item-level implementation/evidence vocabulary. They do not override the concrete repository-presence statement in this file.

`IMPLEMENTED` means the required repository implementation artifact exists. It is not runtime proof.

`PASS` requires actual executed evidence from the applicable build/test/security/restore/load/environment gate.

## Update rule

When implementation is added or removed, update this file in the same PR when the repository-level status changes materially.

When runtime evidence is produced, the durable evidence remains in the owning CI/report/environment artifact. This file may summarize the state but MUST NOT replace the evidence artifact.

`PRODUCTION-READINESS-CHECKLIST.md` remains the production traffic gate. A status change here cannot bypass an unchecked, failed, not-run, or materially not-verified mandatory production gate.
