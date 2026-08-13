# Task Review Matrix

This is a navigation aid for targeted architecture review. It never replaces `/docs/adr/decision-register.md` or applicable ADR text.

Always start with `/AGENTS.md`, this directory's `README.md`, `SOURCES.md`, and the Decision Register.

| Task type | Current-state documents | Service docs | Typical current ADRs |
| --- | --- | --- | --- |
| Domain/Application behavior | `backend-engineering.md`, `testing-and-quality-gates.md`, `../engineering/coding-standards.md` | affected service | bounded-context ADRs; **0069** quality enforcement |
| Java build/static-analysis/CI | `testing-and-quality-gates.md`, `../engineering/coding-standards.md`, `../engineering/build-and-ci-quality-enforcement.md`, Technology Baseline | affected Java service | **0001, 0007, 0061, 0069** |
| PostgreSQL/schema/query/pool | `data-and-messaging.md`, `reliability-and-observability.md`, `performance-and-bottlenecks.md` | affected service | 0002, 0003, 0027, **0048, 0057, 0064**, service persistence ADRs |
| gRPC/internal sync | `platform-architecture.md`, `reliability-and-observability.md`, `dependency-criticality-matrix.md`, `security-architecture.md` | caller + provider | contract/deadline/auth ADRs; **0055, 0063** failure semantics; **0056, 0062** for Authorization |
| Kafka/event/outbox | `data-and-messaging.md`, `reliability-and-observability.md` | producer + consumer | 0026, **0044**, workflow ADRs |
| Authentication/session/OIDC/MFA | `security-architecture.md` | `identity-service.md`, `web-bff.md` | 0006, 0038, **0041, 0045, 0049, 0052** |
| Authorization/permissions | `security-architecture.md`, `reliability-and-observability.md`, `dependency-criticality-matrix.md`, `performance-and-bottlenecks.md` | `authorization-service.md` + resource owner | 0004, 0039, **0041, 0054, 0055, 0056, 0062, 0063, 0066** |
| Semantic quotas | `security-architecture.md`, `data-and-messaging.md` | Identity or Authorization | 0040 historical gate, **0041 + 0054 current** |
| Notification handoff/lifecycle | `data-and-messaging.md`, `reliability-and-observability.md`, `security-architecture.md` | `notification-service.md` + caller | 0010..0017, 0029, 0030 filtered, 0036, **0043, 0047, 0048, 0049** |
| Historical Notification clock/fence code removal | `reliability-and-observability.md`, `runtime-and-deployment.md` | `notification-service.md` | 0018..0023/0031 historical; **0047 current** |
| Notification Email | `data-and-messaging.md`, `security-architecture.md` | `notification-service.md` | 0012..0017, 0036, **0043, 0047, 0048** |
| Notification SMS / SMS MFA | `security-architecture.md`, `reliability-and-observability.md` | `notification-service.md`, `identity-service.md` | 0038, **0041, 0049** plus Notification lifecycle ADRs |
| Public REST/BFF/browser security | `platform-architecture.md`, `security-architecture.md` | `web-bff.md` | 0006, 0038, **0045** |
| Local kind/toolchain bootstrap | `../technology/local-development-baseline.md`, `runtime-and-deployment.md`, `../runbooks/local-istio-ambient.md`, `../runbooks/local-traefik-edge.md` | platform/local foundation | **0050** production compatibility constraints; no new ADR for compatible local tooling pins |
| Local Istio Ambient | `runtime-and-deployment.md`, `security-architecture.md`, `../technology/local-development-baseline.md`, `../runbooks/local-istio-ambient.md` | affected local workloads | **0025, 0050** |
| Local Traefik/WAF edge | `runtime-and-deployment.md`, `security-architecture.md`, `../technology/local-development-baseline.md`, `../runbooks/local-traefik-edge.md` | `web-bff.md` + edge workloads | **0024, 0025, 0050, 0059** |
| Kubernetes/Helm/GitOps/CNI | `runtime-and-deployment.md`, `testing-and-quality-gates.md`, compatibility matrix | affected service | 0024, 0025, 0037, **0046, 0048, 0050, 0051** |
| WAF/edge/routing | `runtime-and-deployment.md`, `security-architecture.md` | `web-bff.md` | 0024, 0025, 0037, **0050, 0059** |
| Secrets/keys/OpenBao | `security-architecture.md`, `runtime-and-deployment.md` | affected service | 0009, 0011, 0027, 0037, **0043**, service crypto ADRs |
| Supply-chain/admission | `security-architecture.md`, `runtime-and-deployment.md`, `testing-and-quality-gates.md` | affected workload | **0046, 0050, 0065, 0068**; digest-indexed SBOM/advisory continuous-response and exception-escalation gates |
| Privacy/deletion/erasure | `security-architecture.md`, `data-and-messaging.md` | Identity + every owning service | 0003, **0058** |
| Production human access | `security-architecture.md`, `runtime-and-deployment.md` | platform/operations | **0060** |
| Logging/PII telemetry | `security-architecture.md`, `reliability-and-observability.md`, `testing-and-quality-gates.md`, `../engineering/coding-standards.md` | affected service | **0061, 0069** |
| SLO/performance/bottleneck | `reliability-and-observability.md`, `dependency-criticality-matrix.md`, `performance-and-bottlenecks.md` | affected service | 0028, **0055, 0056, 0062, 0063, 0064, 0066, 0067, 0044, 0047, 0048, 0049** |
| DR/recovery | `reliability-and-observability.md`, `data-and-messaging.md`, `runtime-and-deployment.md` | affected services | 0003, 0027, **0044, 0048** |
| Platform version upgrade | compatibility matrix, Technology Baseline, `runtime-and-deployment.md` | affected workloads | **0050** + product ADRs |
| Production-readiness review | all applicable current-state docs + `PRODUCTION-READINESS-CHECKLIST.md` + compatibility matrix | all affected services | Decision Register + all applicable current ADRs |

## Targeted-review rule

Use this matrix to reduce irrelevant reading, never to skip material constraints. If a task crosses rows, review the union. If scope/impact/supersession is uncertain, use `full-read`.
