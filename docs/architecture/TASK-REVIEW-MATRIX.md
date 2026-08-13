# Task Review Matrix — Current Sources

Use this matrix only for `targeted` architecture review. If scope crosses bounded contexts/security/infrastructure or the applicable set is uncertain, switch to `full-read`.

Always read `../../AGENTS.md`, `../engineering/current-only-documentation-policy.md`, `../engineering/repository-change-workflow.md`, `README.md`, `SOURCES.md`, and `../adr/decision-register.md` first.

| Task | Minimum current sources |
| --- | --- |
| Java/domain/application code | `backend-engineering.md`, `../engineering/coding-standards.md`, `../engineering/build-and-ci-quality-enforcement.md`, ADR-0069, applicable service/current ADR |
| Build/Gradle/static analysis/CI | `../engineering/coding-standards.md`, `../engineering/build-and-ci-quality-enforcement.md`, `../engineering/developer-workflow.md`, ADR-0069, Technology Baseline |
| Identity registration/password/MFA | `security-architecture.md`, `services/identity-service.md`, ADR-0034, ADR-0035, ADR-0038, ADR-0052, ADR-0054, applicable Notification ADRs |
| Browser OIDC/session/CSRF/CORS | `security-architecture.md`, `services/web-bff.md`, ADR-0038, ADR-0045, ADR-0052 |
| Tenancy/deletion/erasure | `security-architecture.md`, applicable service doc, ADR-0038, ADR-0057, ADR-0058 |
| Authorization call/path | `services/authorization-service.md`, `security-architecture.md`, `dependency-criticality.yaml`, ADR-0039, ADR-0055, ADR-0056, ADR-0062, ADR-0063, ADR-0066 |
| Semantic security quota | `security-architecture.md`, applicable service doc, ADR-0054 |
| Notification handoff/callback | `services/notification-service.md`, `services/identity-service.md`, ADR-0029, ADR-0047 |
| Notification lifecycle/retry/evidence | `services/notification-service.md`, ADR-0029, ADR-0030, ADR-0047 |
| Notification templates/Email/SMS | `services/notification-service.md`, ADR-0029, ADR-0036, ADR-0043, ADR-0049 |
| PostgreSQL/persistence/migration | `data-and-messaging.md`, `runtime-and-deployment.md`, applicable service doc, ADR-0048, ADR-0057, ADR-0064, ADR-0067 |
| Kafka/event/outbox/consumer | `data-and-messaging.md`, ADR-0026, ADR-0044, applicable service doc |
| Synchronous remote dependency | `dependency-criticality.yaml`, `dependency-criticality-matrix.md`, `reliability-and-observability.md`, ADR-0055, ADR-0063, applicable dependency ADR |
| Performance/capacity/scaling | `performance-and-bottlenecks.md`, `reliability-and-observability.md`, applicable service doc, ADR-0028, applicable dependency/data ADR |
| SLO/error budget/release freeze | `reliability-and-observability.md`, `PRODUCTION-READINESS-CHECKLIST.md`, ADR-0028, service-specific current ADRs |
| Backup/PITR/restore/DR | `reliability-and-observability.md`, `runtime-and-deployment.md`, `../operations/chaos-engineering-program.md`, ADR-0027, ADR-0048, ADR-0067 |
| Kubernetes/Helm/GitOps | `runtime-and-deployment.md`, `../engineering/coding-standards.md` §13, `../technology/technology-baseline.md`, ADR-0037, ADR-0050, ADR-0051 |
| Istio/Ambient/workload identity | `runtime-and-deployment.md`, `security-architecture.md`, `../runbooks/local-istio-ambient.md`, ADR-0025, ADR-0055/0063 when dependency behavior changes |
| Traefik/Gateway/WAF/public route | `runtime-and-deployment.md`, `security-architecture.md`, `../runbooks/local-traefik-edge.md`, ADR-0024, ADR-0059 |
| Secrets/OpenBao/ESO | `runtime-and-deployment.md`, `security-architecture.md`, ADR-0037, ADR-0043, ADR-0052 as applicable |
| Supply-chain/image admission | `security-architecture.md`, `testing-and-quality-gates.md`, `../engineering/build-and-ci-quality-enforcement.md`, ADR-0046, ADR-0065, ADR-0068 |
| Logging/PII/telemetry | `reliability-and-observability.md`, `../engineering/coding-standards.md`, ADR-0061 |
| Incident/chaos/recovery exercises | `../operations/incident-response-runbook.md`, `../operations/chaos-engineering-program.md`, applicable service/data/security ADRs |
| Technology/version update | `../technology/technology-baseline.md`, `../technology/production-compatibility-matrix.md`, applicable current ADR, official upstream compatibility/security evidence |
| Documentation/ADR cleanup | `../engineering/current-only-documentation-policy.md`, `SOURCES.md`, `../adr/decision-register.md`, all directly affected current-state docs |

## Review output

A targeted review still records:

```text
Architecture review mode: targeted
Architecture document version/commit:
Architecture sections reviewed:
Search terms used:
ADRs reviewed or changed:
```

If review discovers cross-cutting effects or uncertainty about current effective architecture, switch to `full-read`.
