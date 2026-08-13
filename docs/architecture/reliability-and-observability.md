# Reliability and Observability Architecture — Current State

## 1. Reliability model

Reliability is built from bounded work, finite deadlines, durable local state, idempotency, explicit dependency semantics, and failure isolation. Blind retry or timeout inflation is not a reliability strategy.

Avoid deep synchronous chains. Normal design allows at most two consecutive synchronous hops after the BFF; deeper chains require explicit architecture justification. N+1 service calls are prohibited.

Independent downstream calls may run concurrently when correctness permits, but Virtual Threads never make PostgreSQL connections, Redis capacity, provider quota, CPU, memory, or queues unbounded.

## 2. Generic synchronous budgets

Generic ceilings:

```text
Client/Gateway:       3000 ms
BFF total budget:     2600 ms
gRPC downstream:      1500 ms
Database statement:    800 ms
Pool acquisition:      200 ms
```

A more-specific current contract/ADR overrides these generic values. Every network/database call has a finite budget; child deadlines fit within remaining parent budget; cancellation propagates where supported.

Retry is finite, safe/idempotent, jittered when appropriate, and owned by exactly one layer. Duplicate application + client + mesh/gateway retry for the same failure is prohibited.

## 3. Authorization critical path

Current Authorization contract:

```text
CheckPermission deadline: 300 ms
attempts: 1
wait-for-ready: off
retry/cache/stale fallback: none
availability: >=99.95% rolling 30d
p95 <=100 ms
p99 <=200 ms
engineering target p95/p99 <=75/150 ms
```

Resource services perform one final authoritative online check. Safe local validation may reject malformed/invalid traffic but never grant authority. Routine duplicate BFF permission checks are prohibited.

Paging uses paired multi-window burn:

- 14.4x: 5m + 1h -> page;
- 6x: 30m + 6h -> page;
- 3x: 2h + 24h -> reliability/release-risk action.

Breaker opening follows current ADR-0062 criteria. Repeated OPEN timing and HALF_OPEN behavior follow ADR-0066: de-correlated bounded reopen backoff, at most one real `CheckPermission` probe in flight, three consecutive infrastructure-successful probes to close, immediate reopen on infrastructure failure/overload, no health-endpoint-authorized closure, and no tenant-tier variation.

## 4. Semantic quota dependency

ADR-0054 is the single current quota decision:

```text
Redis budget: 75 ms
attempts: 1
retry: none
failure: fail closed
```

Quota time uses trusted application time + Redis `TIME`, <=2s permitted skew, monotonic effective time, and no security-significant reset based only on TTL expiry.

## 5. Notification critical contracts

```text
SubmitNotification:        900 ms, one attempt, no transport retry
ReportNotificationResult:  750 ms, one attempt, durable dispatcher retry outside RPC
```

Unknown handoff outcomes recover through stable idempotent replay. Provider ambiguity remains unknown and is reconciled; it never authorizes blind resend.

Notification uses PostgreSQL-authoritative immutable deadlines and a short durable `DISPATCHING` transaction before provider I/O. No application clock-health/fence control plane or request-path OpenBao RPC exists in the current runtime.

## 6. Dependency failure containment

The canonical synchronous edge registry is `dependency-criticality.yaml`; its Markdown matrix is generated.

Every production synchronous edge has finite deadline, bounded in-flight work/queue, one retry owner, explicit fallback/failure action, stable error mapping, and test evidence. Criticality is operation->dependency, not a whole-service label.

- authoritative security/state failure blocks;
- durable commands remain pending only after local durable intent exists;
- external side effects preserve ambiguous outcome semantics;
- optional reads degrade only through an explicitly registered bounded fallback;
- missing fallback means no fallback.

Circuit breakers are used when repeated dependency failures would otherwise consume scarce caller resources and OPEN behavior is semantically safe; they are not blanket annotations around every remote call. Database/Redis correctness relies primarily on bounded pools/timeouts/HA/transaction semantics rather than a generic breaker that obscures transaction outcomes.

## 7. PostgreSQL and Redis HA

Critical service PostgreSQL clusters use the current three-instance CloudNativePG synchronous required-durability/failover model. Safe primary recovery target is <=60s for ordinary failover only when acknowledged durability can be preserved; unsafe promotion fails availability instead of acknowledged data.

Security Redis uses one primary + two replicas + three Sentinel voters. Failover tests cover quota/session semantics and fail-closed behavior where the dependency is authoritative.

## 8. SLO classes and error budgets

ADR-0028 defines current generic classes:

### Class A

Critical interactive security transactions:

```text
availability 99.90% rolling 30d
p95 <=500 ms
p99 <=1500 ms
server timeout ceiling 2s
```

### Class B

Generic critical internal dependency baseline:

```text
availability >=99.95%
p95 <=250 ms
p99 <=750 ms
```

More-specific current contracts such as Authorization, semantic quotas, and Notification handoff override the generic latency envelope.

### Class C

99.9% of durably accepted Notification intents begin first provider attempt within five seconds. External delivery is a separate SLI.

For a 99.90%/30d objective, approximate error budget is 43m12s:

| Consumption | Response |
| --- | --- |
| <25% | normal delivery |
| >=25% within 24h | reliability review; stop risky releases |
| >=50% | freeze affected feature releases |
| >=100% | security/incident/reliability changes only |

Planned maintenance counts when users cannot obtain service. Real errors/latency are never hidden by ad-hoc grace periods.

## 9. Disaster recovery

Cold DR sequence:

```text
clean Kubernetes/GitOps foundation
-> restore OpenBao control plane
-> restore service CloudNativePG/PostgreSQL + PITR
-> reconstruct Kafka + replay/reconstruct retained service evidence
-> reconcile erasure/legal-hold/data integrity
-> verify secrets/contracts/security
-> smoke/security checks
-> enable traffic
```

Targets:

```text
PostgreSQL RPO <=5m
OpenBao RPO <=1h
platform RTO <=4h
PostgreSQL PITR 35d
```

Every backup cycle is verified; isolated restore is monthly per service; full cold DR is quarterly. ADR-0067 requires queryable recovery evidence and freezes ordinary affected-service promotion after a failed restore until replacement evidence passes.

## 10. Observability

Every service emits:

- structured JSON logs to stdout;
- OpenTelemetry traces;
- Micrometer/Prometheus-compatible metrics;
- W3C trace propagation across REST/gRPC/Kafka/workers;
- bounded low-cardinality dimensions;
- relevant SLI/SLO/error-budget/burn and saturation signals.

Base safe operational fields may include timestamp, level, service name/version, environment, trace/span/correlation identity, and stable event code. High-cardinality business/security identifiers are not metric labels.

Logging is allow-list based. Raw credentials, passwords/PIN/OTP/recovery codes, tokens/API keys, authorization/cookie headers, session IDs, private keys/secrets, connection strings, payment/high-risk PII, full request/response bodies, SQL binds, complete gRPC metadata, Kafka headers, and unreviewed provider payload/exception text are prohibited.

Input-derived fields are CR/LF-safe. Production debug elevation is time-bounded/audited and cannot enable sensitive body/bind/credential logging. Logging/export failure does not fail the primary business request, but sustained loss/backpressure is observable/alertable. Log-store access is least privilege and audited.

## 11. Required failure evidence

Critical components run applicable timeout/cancellation, overload/bulkhead, PostgreSQL primary loss, Redis Sentinel failover, Kafka broker/controller/replay, OpenBao restore/unseal, WAF/Istio/NetworkPolicy negative, Authorization fail-closed/recovery, Notification provider ambiguity/crash/failover, backup/PITR/DR, and SLO/burn-alert correctness tests.