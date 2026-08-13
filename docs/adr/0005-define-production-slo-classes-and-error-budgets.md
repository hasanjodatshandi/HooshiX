# ADR-0005: Production SLO Classes and Error Budgets v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

## Decision

The platform uses explicit service/operation SLO classes. Dependency-specific ADRs may define stricter objectives and deadlines; the stricter current contract wins for that operation.

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

### Class C — asynchronous processing

For Notification, 99.9% of durably accepted intents begin their first provider attempt within five seconds. External provider delivery is measured separately from internal scheduling.

### Error-budget policy

For a 99.90% rolling-30-day objective, approximate budget is 43m12s.

| Budget consumption | Required response |
| --- | --- |
| <25% | normal delivery |
| >=25% within 24h | reliability review; stop risky releases |
| >=50% | freeze feature releases for the affected capability |
| >=100% | security/incident/reliability changes only until recovered |

Planned maintenance counts when users cannot obtain the service.

SLO accounting MUST NOT remove real errors/latency through ad-hoc grace periods. Where a dependency defines burn-rate alerting, paired multi-window burn is used rather than paging on isolated percentile samples.

### Measurement

SLIs are defined over eligible operations with stable inclusion/exclusion rules. Service telemetry must attribute request/dependency latency, saturation, error outcomes, and relevant downstream components without high-cardinality or sensitive labels.

## Verification requirements

Verify SLI denominator rules, objective calculations, alert/release-policy behavior, dependency-specific stricter overrides, load/capacity evidence at target throughput, and absence of timeout increases or retry amplification used merely to hide SLO burn.

## Rollback considerations

Rollback MUST NOT silently weaken an operation's stricter current SLO/deadline, delete real failures from SLI accounting, or resume risky feature promotion while the applicable error-budget policy requires a freeze.