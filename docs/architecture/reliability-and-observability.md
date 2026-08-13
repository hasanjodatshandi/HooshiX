# Reliability and Observability Architecture

## 1. Reliability principle

Reliability is designed around bounded work, explicit deadlines, durable local
state, idempotency, and failure isolation. Retrying blindly or increasing
timeouts is not a reliability strategy.

## 2. Synchronous call depth

Avoid long chains such as:

```text
BFF -> A -> B -> C -> D
```

Normal design allows at most two consecutive synchronous hops after the BFF.
Deeper chains require explicit architecture justification.

Independent downstream calls may run concurrently using Virtual Threads and
stable Java concurrency primitives when correctness permits. N+1 service calls
are prohibited.

## 3. Baseline request budgets

General baseline:

```text
Client/Gateway:       3000 ms
BFF total budget:     2600 ms
gRPC downstream:      1500 ms
Database statement:    800 ms
Pool acquisition:      200 ms
```

More specific accepted ADR deadlines override these generic ceilings.

Every network/database call has a finite deadline/timeout. HTTP clients define explicit finite connect and response/read budgets. Child deadlines fit
inside remaining parent budget. Cancellation is propagated where supported.

## 4. Critical dependency contracts

### Authorization `CheckPermission`

ADR-0039/ADR-0056/ADR-0062/ADR-0066:

```text
client deadline: 300 ms
attempts: 1
wait-for-ready: off
retry: none
cache: none
stale fallback: none
service SLO availability: >=99.95% rolling 30d
p95 <=100 ms
p99 <=200 ms
steady-state engineering target: p95<=75 ms / p99<=150 ms
```

Authorization pages on paired multi-window burn, not an isolated percentile sample. Availability uses 14.4x (5m+1h) and 6x (30m+6h) paired page conditions; 3x (2h+24h) creates a reliability action. The p99 latency objective is represented as >=99% of eligible calls <=200ms and uses the same burn discipline.

Caller breakers recover through real half-open `CheckPermission` probes, not a separate health endpoint. ADR-0066 de-correlates repeated OPEN intervals across replicas, permits only one half-open probe in flight per caller breaker, and requires three consecutive infrastructure-successful real probes to close. Timeout/unavailable/overload immediately reopens. Authoritative deny is an infrastructure-successful response. Breaker semantics never vary by tenant tier.

Resource-owning service performs the one final online check. BFF does not add a
routine duplicate check on the same protected request path.

### Semantic security quota Redis

ADR-0041:

```text
Redis budget: 75 ms
attempts: 1
retry: none
security failure mode: fail closed
```

### Notification handoff

```text
SubmitNotification: 900 ms, one attempt, no automatic retry
Result callback:    750 ms, one attempt, durable dispatcher retry outside RPC
```

Unknown handoff outcomes recover by stable idempotent replay.

## 5. Retry policy

Retry only safe/idempotent work and only at one clearly owned layer.

- finite attempt count;
- exponential/bounded backoff + jitter where useful;
- no duplicate retry in application + gRPC + Istio + gateway;
- no retry merely because a deadline is large enough;
- poison/terminal outcomes are explicit.

ADR-0039 Authorization is a strict no-retry exception. Notification/provider
retry semantics follow the canonical lifecycle and never retry ambiguous sends
blindly.

## 6. Circuit breakers, bulkheads, overload

ADR-0055/ADR-0063/ADR-0066 are current. Every synchronous remote call has a finite deadline,
bounded in-flight concurrency, bounded/zero queue, explicit retry owner, and
stable failure mapping. Circuit breakers are mandatory only where repeated
dependency failures would otherwise consume scarce caller resources and the
open-state behavior is semantically safe; they are **not** a blanket annotation
around every gRPC call.

The canonical operation-level dependency registry is `dependency-criticality.yaml`; `dependency-criticality-matrix.md` is its generated human-readable view. Criticality belongs to an operation -> dependency edge, not a whole service name. An unspecified fallback means no fallback. Composite operations preserve each edge independently: authoritative failures block, while optional reads degrade only through an explicitly registered fallback.

Authoritative security dependencies such as Authorization use a fail-closed
breaker: open state returns the same availability error and never cached/stale
allow data. External providers use breakers together with their durable retry/
reconciliation owner. Database/Redis clients rely on bounded pools/timeouts/HA
behavior rather than a generic breaker that could obscure transaction outcome.

Bulkheads protect constrained resources such as Authorization DB capacity,
provider clients, password hashing, and expensive external calls. Application
queues are bounded; overload fails fast rather than accumulating work past useful
deadlines.

Virtual Threads make blocking I/O cheap in thread terms; they do **not** make
PostgreSQL connections, Redis commands, provider quotas, CPU, or memory
unbounded. Critical adapters must have explicit concurrency/capacity controls.

## 7. PostgreSQL HA

ADR-0048 runs 3 CloudNativePG PostgreSQL instances with required quorum
synchronous replication and failover quorum. A safe simple-primary failure is
expected to restore a writable primary within the operational <=60s objective,
subject to preserving acknowledged durability.

If safe promotion cannot be proven, write availability may stop rather than
risk acknowledged data loss.

Authorization and Notification failover/load testing must be performed against
this real HA behavior, not mocks alone.

## 8. Notification simplification

ADR-0047 removes the previous bespoke clock-health sidecar/gRPC/fence subsystem.
Notification keeps PostgreSQL-authoritative immutable deadlines and uses a
short synchronously durable `DISPATCHING` transaction before provider I/O.
Unknown/in-flight outcomes reconcile and are never blindly re-sent.

This materially reduces runtime/control-plane failure modes while preserving
conservative external-side-effect semantics.

## 9. SLO classes

ADR-0028 remains the baseline.

### Class A — critical security transactions

Includes login/authentication, OTP/MFA verification, registration completion,
password reset/change completion.

```text
Availability: 99.90% rolling 30d
p95: <=500 ms
p99: <=1500 ms
server timeout ceiling: 2s
```

### Class B — critical internal security dependencies

Includes compromised-password service, Identity semantic limiter, Notification
durable acceptance, and current online Authorization `CheckPermission`.

General Class-B objective remains:

```text
Availability: >=99.95%
p95: <=250 ms
p99: <=750 ms
```

Authorization has ADR-0056/ADR-0062's stricter p95<=100ms/p99<=200ms SLO inside its
300ms hard caller deadline; 75/150ms remains a steady-state engineering target.
Multi-window burn alerts absorb short-lived storage/network noise without deleting it
from SLO accounting. Dependency-specific stricter deadlines remain valid. ADR-0066 refines only breaker recovery/governance, not these SLO objectives.

### Class C — asynchronous Notification processing

99.9% of accepted notification intents begin first provider attempt within 5s
of durable acceptance. Provider delivery itself is a separate external/channel
SLI.

## 10. Error budget

For 99.90%/30d, approximate budget is 43m12s.

| Consumption | Required response |
| --- | --- |
| <25% | normal delivery |
| >=25% within 24h | reliability review; stop risky releases |
| >=50% | freeze feature releases |
| >=100% | security/incident/reliability changes only |

Planned maintenance counts when users cannot obtain service.

## 11. Disaster recovery

Cold DR remains the cross-platform model:

```text
clean Kubernetes
-> Argo CD desired state
-> restore OpenBao
-> restore CloudNativePG/PostgreSQL + PITR
-> reconstruct Kafka + replay retained outboxes
-> verify secrets/data/event integrity
-> replay deletion/erasure/legal-hold/id-release decisions
-> smoke/security checks
-> enable traffic
```

Targets:

```text
PostgreSQL RPO <=5m
OpenBao RPO <=1h
Platform RTO <=4h
PostgreSQL PITR 35d
```

Every backup cycle verifies artifacts; isolated restore monthly; full DR
quarterly. A backup without successful restore evidence is not dependable.

ADR-0067 standardizes per-service monthly restore evidence (backup/WAL source, recovered timestamp, measured RPO/RTO, integrity/RLS/erasure checks, runbook version, owner, PASS/FAIL) and exposes overdue/failed state on the fleet dashboard. A failed restore freezes ordinary promotion for the affected service until a successful replacement drill. Database upgrade rollback is compatibility-aware; irreversible/major transitions are never automatically downgraded to meet an arbitrary rollback timer.

## 12. Observability standards

Every service emits:

- structured JSON logs to stdout;
- OpenTelemetry distributed traces;
- Micrometer/Prometheus-compatible metrics;
- W3C `traceparent` / `tracestate` propagation across REST/gRPC/Kafka/workers;
- bounded low-cardinality labels;
- SLI/SLO/error-budget/burn-rate telemetry for critical paths.

Base log fields include equivalents of:

```text
timestamp
level
service.name
service.version
environment
traceId
spanId
correlationId
eventCode
```

## 13. Logging and PII

Logging is allow-list based.

Never log raw passwords/PIN/OTP/recovery codes, tokens/API keys, Authorization/
Cookie headers, session IDs, private keys/secrets, DB connection strings,
payment/bank data, sensitive government/health/biometric data, full request/
response bodies, SQL bind values, complete gRPC metadata, Kafka headers, or
unreviewed provider exception payloads.

Ordinary PII requires an approved purpose and bounded representation. Plain
unsalted hashing is insufficient for guessable PII; use masking/tokenization or
managed-key HMAC pseudonymization where appropriate.

Metric labels never contain raw user/tenant/session/request identifiers,
trace IDs, raw URLs, or free-form error strings.

Additional operational logging rules:

- applications emit structured JSON to stdout; request threads do not synchronously ship logs directly to a remote backend;
- structured fields are allow-listed; string-concatenating request/domain/payload objects into log messages is prohibited;
- input-derived log fields are sanitized/encoded for CR, LF, and malicious delimiters to prevent log injection;
- third-party exception messages, nested causes, and stack traces are treated as potentially sensitive and are reviewed/redacted before emission; stable event codes plus bounded safe context are preferred;
- production debug/trace logging is disabled by default; temporary elevation is time-bound, access-controlled, audited, and must never enable body/bind/credential logging;
- logging/export failure must not fail the primary business request, but sustained drop/backpressure/export failure is observable and alertable;
- log-store access follows least privilege and is audited; retention, encryption, residency, and access follow data classification;
- every changed log statement is covered by applicable PII/secret/log-injection tests and ADR-0061 static/pipeline controls.

## 14. Required failure testing

Critical components require applicable tests for:

- dependency timeout/cancellation;
- overload/bulkhead behavior;
- PostgreSQL primary failover;
- Redis Sentinel failover;
- Kafka broker/controller failure and replay;
- OpenBao restore/unseal;
- WAF/Istio/NetworkPolicy failures;
- Authorization fail closed;
- Notification provider ambiguity and crash/failover races;
- backup/PITR/DR;
- SLO/burn alert correctness.
