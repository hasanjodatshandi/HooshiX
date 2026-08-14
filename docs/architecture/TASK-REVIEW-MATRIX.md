# Task Review Matrix — Current Sources

Use this matrix only for `targeted` review. If scope crosses bounded contexts/security/infrastructure or applicable sources are uncertain, switch to `full-read`.

Always read `../../AGENTS.md`, `../engineering/current-only-documentation-policy.md`, `../engineering/repository-change-workflow.md`, `README.md`, `SOURCES.md`, and `../adr/decision-register.md` first.

| Change area | Minimum current sources |
| --- | --- |
| Identity/password/MFA/session/tenant | Identity service; ADR-0012/0023; ADR-0024 when quota; ADR-0040 when compromised-password; security/testing/readiness |
| Compromised Password source/dataset/runtime | ADR-0040; Compromised Password service; Identity password section; data/messaging; Technology Baseline/compatibility; build/CI; testing/security/readiness; Sources |
| Semantic quota/Redis/time/network abuse | ADR-0024; ADR-0043; Identity/BFF affected operations; data/messaging; reliability/performance; security/testing/chaos/readiness; dependency registry if edge changes |
| Public client address/proxy/WAF | ADR-0043; network architecture; BFF; ADR-0024; security/testing/readiness; edge runbook; Technology compatibility |
| Authorization | Authorization service; ADR-0013/0026/0032/0036; dependency registry; security/testing/performance/readiness |
| Notification | Notification service + ADR-0006/0007/0010/0014/0018/0020; data/messaging; testing/recovery |
| Reference Data capability/bundle/service trigger | ADR-0041; Reference Data service; BFF; data/messaging; implementation status; build/testing/readiness; dependency registry if remote edge activates |
| New/change service boundary | full-read required; also performance register and implementation status |
| Day-One application observability | ADR-0044; ADR-0031; reliability/observability; coding/build standards; service doc; Technology Baseline/compatibility; security/testing/readiness/fitness; performance |
| Collector/Loki/Tempo/Prometheus/Grafana/Alertmanager | ADR-0044; runtime/deployment; platform/reliability/performance/security/testing/readiness; Technology Baseline/compatibility; threat model; chaos/DR |
| Logging/PII/security audit | ADR-0031; ADR-0044; coding standard; security architecture/matrix; service doc; testing; incident/chaos when failure behavior changes |
| Kyverno/admission policy | ADR-0017; ADR-0021; Technology Baseline/compatibility; build/CI; testing/security/readiness; platform/runtime; performance |
| K3s/Calico/Istio/edge/platform profile | ADR-0021/0022/0042/0043 as applicable; platform/runtime/network; Technology Baseline/compatibility; performance/security/testing/readiness; runbooks |
| PostgreSQL/RLS/Flyway/backup | ADR-0019/0027/0034/0037/0042; data/messaging; SQL standard; testing/readiness/recovery |
| Kafka/events | ADR-0015/0042; data/messaging; dependency/event contracts; testing/performance/recovery |
| OpenBao/secrets | ADR-0011 plus affected key/security ADR; security/runtime; Technology Baseline; recovery; OpenBao invariance checks |
| Human production access | ADR-0030/0043; network/security/runtime; readiness; incident/chaos/DR |
| ADR/documentation governance | current-only policy; documentation standard; repository workflow; Decision Register; Sources; FILE_INDEX; all inbound references affected by the decision |
| CI/repository workflow | repository-change-workflow; build/CI; developer workflow; AGENTS; affected checks/fitness/readiness |
| Production readiness/release | Production Readiness Checklist; implementation status; fitness/security/testing; Technology Baseline; applicable service/platform/DR/chaos evidence |

## Escalation rules

Use `full-read` when:

- changing bounded-context/deployable boundaries;
- changing authN/authZ/MFA/tenant/security authority;
- changing persistence/consistency/recovery semantics;
- changing public trust boundaries, quota authority, secret authority, or admission architecture;
- introducing/removing a platform technology/control plane;
- current sources disagree;
- the requested task is broad enough that targeted scope may miss a dependency.

Targeted review does not permit skipping executable evidence or current implementation/diff inspection.