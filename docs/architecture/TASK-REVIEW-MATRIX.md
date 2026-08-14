# Task Review Matrix — Current Sources

Use this matrix only for `targeted` architecture review. If scope crosses bounded contexts/security/infrastructure or the applicable set is uncertain, switch to `full-read`.

Always read `../../AGENTS.md`, `../engineering/current-only-documentation-policy.md`, `../engineering/repository-change-workflow.md`, `README.md`, `SOURCES.md`, and `../adr/decision-register.md` first.

| Task | Minimum current sources |
| --- | --- |
| Production profile / single-server / HA topology / host sizing | `runtime-and-deployment.md`, `data-and-messaging.md`, `security-architecture.md`, `reliability-and-observability.md`, `performance-and-bottlenecks.md`, `PRODUCTION-READINESS-CHECKLIST.md`, Technology Baseline, Production Compatibility Matrix, ADR-0015, ADR-0017, ADR-0019, ADR-0022, ADR-0024, ADR-0027, ADR-0030, ADR-0034, ADR-0037, ADR-0042 |
| Java/domain/application code | `backend-engineering.md`, `../engineering/coding-standards.md`, `../engineering/build-and-ci-quality-enforcement.md`, ADR-0039, applicable service/current ADR |
| Build/Gradle/static analysis/CI | `../engineering/coding-standards.md`, `../engineering/build-and-ci-quality-enforcement.md`, `../engineering/developer-workflow.md`, ADR-0039, Technology Baseline |
| Identity registration/password/MFA | `security-architecture.md`, `services/identity-service.md`, ADR-0008, ADR-0009, ADR-0012, ADR-0023, ADR-0024, applicable Notification ADRs; ADR-0042 when deployment profile is involved because it does not weaken MFA |
| Compromised-password dataset/lookup/SQLite | `services/compromised-password-service.md`, `services/identity-service.md` §7, `security-architecture.md` §4, `data-and-messaging.md`, `dependency-criticality.yaml`, `performance-and-bottlenecks.md`, `testing-and-quality-gates.md`, `../technology/technology-baseline.md`, `../technology/production-compatibility-matrix.md`, ADR-0004, ADR-0005, ADR-0012, ADR-0025, ADR-0033, ADR-0035, ADR-0038, ADR-0040; ADR-0042 for production replicas/capacity |
| Reference Data family/import/bundle/gRPC/BFF facade | `services/reference-data-service.md`, `services/web-bff.md`, `platform-architecture.md`, `data-and-messaging.md`, `dependency-criticality.yaml`, `reliability-and-observability.md`, `runtime-and-deployment.md`, `security-architecture.md`, `security-verification-matrix.md`, `performance-and-bottlenecks.md`, `testing-and-quality-gates.md`, `PRODUCTION-READINESS-CHECKLIST.md`, ADR-0005, ADR-0016, ADR-0025, ADR-0033, ADR-0041; ADR-0042 for production topology/capacity |
| Web BFF public API/OIDC/session/CSRF/CORS/token brokerage/reference facade | `services/web-bff.md`, `security-architecture.md`, `security-verification-matrix.md`, `dependency-criticality.yaml`, `reliability-and-observability.md`, `runtime-and-deployment.md`, `performance-and-bottlenecks.md`, `testing-and-quality-gates.md`, `PRODUCTION-READINESS-CHECKLIST.md`, ADR-0012, ADR-0016, ADR-0023, ADR-0024, ADR-0025, ADR-0033, ADR-0041 as applicable; ADR-0042 for profile topology |
| Identity BFF audience-token provider | `services/identity-service.md`, `services/web-bff.md`, `security-architecture.md`, `dependency-criticality.yaml`, ADR-0012, ADR-0016, ADR-0023, ADR-0025 |
| Tenancy/deletion/erasure | `security-architecture.md`, applicable service doc, ADR-0012, ADR-0027, ADR-0028; ADR-0042 when shared physical PostgreSQL is involved |
| Authorization permission/admin/platform path | `services/authorization-service.md`, `security-architecture.md`, `security-verification-matrix.md`, `dependency-criticality.yaml`, ADR-0013, ADR-0024, ADR-0025, ADR-0026, ADR-0032, ADR-0033, ADR-0036; ADR-0042 for profile availability/capacity |
| Semantic security quota | `security-architecture.md`, applicable service doc, ADR-0024, ADR-0042 when production Redis topology/capacity changes |
| Notification handoff/callback | `services/notification-service.md`, `services/identity-service.md`, ADR-0006, ADR-0018 |
| Notification lifecycle/retry/evidence | `services/notification-service.md`, ADR-0006, ADR-0007, ADR-0018; ADR-0042 for profile availability/capacity |
| Notification templates/Email/SMS | `services/notification-service.md`, ADR-0006, ADR-0010, ADR-0014, ADR-0020 |
| PostgreSQL/persistence/migration | `data-and-messaging.md`, `runtime-and-deployment.md`, applicable service doc, ADR-0019, ADR-0027, ADR-0034, ADR-0037, ADR-0042 |
| Kafka/event/outbox/consumer | `data-and-messaging.md`, ADR-0003, ADR-0015, ADR-0042, applicable service doc |
| Synchronous remote dependency | `dependency-criticality.yaml`, `dependency-criticality-matrix.md`, `reliability-and-observability.md`, ADR-0025, ADR-0033, applicable dependency ADR |
| Performance/capacity/scaling | `performance-and-bottlenecks.md`, `reliability-and-observability.md`, applicable service doc, ADR-0005, ADR-0042 when production host/profile is involved, applicable dependency/data ADR |
| SLO/error budget/release freeze | `reliability-and-observability.md`, `PRODUCTION-READINESS-CHECKLIST.md`, ADR-0005, ADR-0042 for single-server availability interpretation, service-specific current ADRs |
| Backup/PITR/restore/DR | `reliability-and-observability.md`, `runtime-and-deployment.md`, `../operations/chaos-engineering-program.md`, ADR-0004, ADR-0019, ADR-0037, ADR-0042 |
| Kubernetes/Helm/GitOps | `runtime-and-deployment.md`, `../engineering/coding-standards.md` §13, `../technology/technology-baseline.md`, ADR-0011, ADR-0021, ADR-0022, ADR-0042 |
| Istio/Ambient/workload identity | `runtime-and-deployment.md`, `security-architecture.md`, `performance-and-bottlenecks.md`, `../runbooks/local-istio-ambient.md`, ADR-0002, ADR-0042 for single-server benchmark, ADR-0025/0033 when dependency behavior changes |
| Traefik/Gateway/WAF/public route | `runtime-and-deployment.md`, `security-architecture.md`, `../runbooks/local-traefik-edge.md`, ADR-0001, ADR-0029, ADR-0042 when K3s bundled edge components are relevant |
| Secrets/OpenBao/ESO | `runtime-and-deployment.md`, `security-architecture.md`, ADR-0011, ADR-0014, ADR-0023 as applicable; ADR-0042 confirms OpenBao is unchanged by single-server simplification |
| Supply-chain/image admission/Kyverno | `security-architecture.md`, `testing-and-quality-gates.md`, `../engineering/build-and-ci-quality-enforcement.md`, ADR-0017, ADR-0035, ADR-0038, ADR-0042 |
| Privileged human production access | `security-architecture.md`, `security-verification-matrix.md`, `../operations/incident-response-runbook.md`, ADR-0030, ADR-0042, Technology Baseline |
| Logging/PII/telemetry | `reliability-and-observability.md`, `../engineering/coding-standards.md`, ADR-0031; ADR-0030/ADR-0042 for privileged-session audit |
| Incident/chaos/recovery exercises | `../operations/incident-response-runbook.md`, `../operations/chaos-engineering-program.md`, ADR-0042 when single-server failure/recovery is involved, applicable service/data/security ADRs |
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
