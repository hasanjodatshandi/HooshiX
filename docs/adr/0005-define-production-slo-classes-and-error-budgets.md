# ADR-0005: Production SLO Classes and Error Budgets v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-14

## Decision

The platform uses explicit service/operation SLO classes. Dependency-specific ADRs may define stricter objectives and deadlines; the stricter current contract wins for that operation.

ADR-0042 selects `production-single-server` as the initial explicit non-HA topology. Application/service SLIs and error-budget accounting continue to be measured. The profile does **not** claim that node/database/Redis/Kafka/admission failover exists merely because a service availability objective is defined.

### Class A — critical user/security transactions

Includes login/authentication, OTP/MFA verification, registration completion, password reset/change completion, and equivalent critical interactive security flows.

```text
availability: 99.90% rolling 30d
p95: <=500 ms
p99: <=1500 ms
server timeout ceiling: 2 s
```

### Class B — critical internal dependencies

General baseline for critical internal dependencies such as compromised-password checking, semantic quota evaluation, Notification durable acceptance, and Authorization:

```text
availability: >=99.95% rolling 30d
p95: <=250 ms
p99: <=750 ms
```

Authorization uses its stricter current 300ms hard caller deadline and p95<=100ms/p99<=200ms production objectives. Semantic quota Redis uses its current 75ms one-attempt contract. Notification handoff uses its current 900ms idempotent RPC contract. These more-specific values override the generic Class-B latency envelope.

`production-single-server` may be unable to meet redundancy-dependent availability during host/node maintenance/failure because there is intentionally no alternate node/primary/broker/Redis/admission replica. This does not authorize changing fail-closed security semantics or deleting real downtime from SLI accounting. If business requirements demand maintenance/failure tolerance sufficient to meet the availability objective, migrate to `production-ha` or add approved capacity/redundancy.

### Class C — asynchronous processing

For Notification, 99.9% of durably accepted intents begin their first provider attempt within five seconds while required dependencies are available according to the current service contract. External provider delivery is measured separately from internal scheduling. Kafka outage in the single-server profile is recorded as real async transport unavailability; RF=1 does not create a special exclusion.

### Error-budget policy

For a 99.90% rolling-30-day objective, approximate budget is 43m12s.

| Budget consumption | Required response |
| --- | --- |
| <25% | normal delivery |
| >=25% within 24h | reliability review; stop risky releases |
| >=50% | freeze feature releases for the affected capability |
| >=100% | security/incident/reliability changes only until recovered |

Planned maintenance counts when users cannot obtain the service. Single-server host maintenance is not removed from SLI/error-budget accounting simply because the topology is intentionally non-HA.

SLO accounting MUST NOT remove real errors/latency through ad-hoc grace periods. Where a dependency defines burn-rate alerting, paired multi-window burn is used rather than paging on isolated percentile samples.

### Profile-specific interpretation

For `production-single-server`:

- record real user-visible availability and latency;
- do not claim infrastructure failover objectives that physically require redundancy;
- treat repeated/unacceptable host downtime or error-budget exhaustion as evidence that the profile no longer meets business needs;
- use capacity increase or `production-ha` migration rather than timeout inflation/retry/security weakening;
- keep all security/correctness SLO and fail-closed contracts active.

For `production-ha`, redundancy-dependent availability/failover evidence remains required under the applicable platform/service ADRs.

### Measurement

SLIs are defined over eligible operations with stable inclusion/exclusion rules. Service telemetry attributes request/dependency latency, saturation, error outcomes and relevant downstream components without high-cardinality or sensitive labels.

Single-server additionally records host CPU/memory/pressure/storage, shared PostgreSQL/Redis/Kafka/Ambient/Kyverno/edge resource pressure and complete-platform outage windows so topology cost is visible rather than hidden.

## Verification requirements

Verify SLI denominator rules, objective calculations, alert/release-policy behavior, dependency-specific stricter overrides, selected-profile availability interpretation, load/capacity evidence at target throughput, planned-maintenance accounting, and absence of timeout increases/retry amplification/security bypass used merely to hide SLO burn.

## Rollback considerations

Rollback MUST NOT silently weaken an operation's stricter current SLO/deadline, delete real failures/maintenance from SLI accounting, claim HA where the selected profile has no redundancy, weaken fail-closed security to improve availability, or resume risky feature promotion while the applicable error-budget policy requires a freeze.
