# Reliability and Observability Architecture — Current State

## 1. Reliability model

Reliability is built from bounded work, finite deadlines, durable local state, idempotency, explicit dependency semantics, failure isolation, measurable SLOs, and tested recovery. Blind retry/timeout inflation is not a reliability strategy.

ADR-0042 selects `production-single-server` initially. That profile accepts infrastructure availability loss but does not weaken correctness/security/durability/fail-closed/recovery evidence. ADR-0043 defines network/client-address/management trust. ADR-0044 defines Day-One ordinary observability.

## 2. Dependency failure semantics

`dependency-criticality.yaml` is the machine-readable authority for operation-level synchronous dependency class, failure action, retry owner, fallback, owner, and policy refs. Exact deadlines/cancellation/attempt/breaker/idempotency/concurrency details remain in owning contracts per ADR-0033.

Every synchronous edge has finite caller/child deadlines, cancellation where supported, one retry owner, bounded concurrency/queues, explicit fallback only when approved, and no fabricated security success.

Authorization remains one authoritative online check, one attempt, no stale cache/fallback/retry, fail closed.

## 3. Single-server reliability profile

No infrastructure HA claim exists for Kubernetes node/control plane, PostgreSQL primary, Redis, Kafka, Kyverno, application replica, or host maintenance.

Host/node/kernel/storage loss may stop the complete platform. Success criteria are safe failure, accurate detection, bounded recovery, and no correctness/security bypass.

Security/correctness examples:

- Authorization failure never becomes ALLOW;
- Redis quota/session failure never becomes local bypass;
- quota time/capacity failure never becomes fabricated denial/success;
- missing trusted client identity never becomes caller-header/shared-proxy identity;
- Kafka outage does not change Outbox/Inbox/idempotency or become business truth;
- PostgreSQL outage does not authorize unsafe state reconstruction;
- Kyverno outage does not create admission bypass;
- Ambient pressure does not authorize disabling identity/mTLS;
- OpenBao outage does not authorize Git/plaintext secret fallback;
- WireGuard failure does not authorize public SSH;
- observability outage does not authorize security/audit downgrade.

## 4. SLO and error budgets

Current SLO ADRs remain authoritative. Real user-visible latency/errors/downtime are measured in both profiles. Single-server does not claim infrastructure redundancy-dependent failover objectives.

Planned maintenance/host outage is not silently removed from application availability measurement when the service SLI counts it. Repeated/unacceptable downtime is evidence to increase capacity or move to HA.

## 5. Data/recovery reliability

PostgreSQL retains continuous WAL archive, encrypted off-site physical backup, daily base backup, 35-day PITR, monthly retained artifacts, monthly restore, and quarterly cold DR. `pg_dump + cron` is not primary recovery.

Single-server physical PITR restores the whole shared cluster in isolation before controlled service-specific extraction/import. Restored traffic waits for integrity/Flyway/RLS/erasure/legal-hold checks.

Kafka is rebuildable transport. RF1 in single-server may lose broker-local state; service-owned publication/replay and Inbox/dedup evidence remain authority.

Redis is ephemeral security/session state. Single-server uses TLS/ACL/`noeviction`/AOF. Session loss means reauthentication. ADR-0024 owns exact-IP/aggregate-pressure, common-mode clock, cardinality allocation, and fail-closed semantics.

## 6. Semantic quota reliability

Quota reliability includes security availability under adversarial state creation.

Required signals/tests include:

- app/Redis skew and local wall-vs-monotonic Clock Safety Guard state;
- host time synchronization readiness and guard re-arm state;
- common-mode app+Redis clock-step test;
- Redis used/max memory and >=30% reserve;
- active security bucket cardinality;
- new-bucket allocation and cleanup rates by bounded operation/dimension enums;
- `QUOTA_CAPACITY_UNHEALTHY` events without subject/IP labels;
- no eviction/OOM during adversarial unique-key pressure;
- exact-IP hard quota and aggregate-prefix pressure behavior across NAT/IPv6 cases.

Capacity/time uncertainty fails closed. It is distinct from a normal user quota denial.

## 7. Day-One observability runtime

ADR-0044 is mandatory from the first executable service commit.

### Application path

```text
structured JSON stdout -> node-local OpenTelemetry Collector -> Loki
Micrometer metrics      -> Prometheus
Micrometer/OTel traces  -> OTLP Collector -> Tempo
Prometheus alerts       -> Alertmanager
Prometheus/Loki/Tempo   -> Grafana
```

Services use Micrometer Observation/Tracing. Trace context/baggage is correlation only and is never authN/authZ/tenant/quota/idempotency/audit authority.

### Single-server deployment

- one `otelcol-contrib` node-local Collector;
- Prometheus current baseline;
- Loki single-binary/non-HA;
- Tempo monolithic/non-HA with no extra Tempo Kafka requirement;
- Grafana and Alertmanager current baseline;
- at least one external black-box availability check outside the host failure domain before production.

Local monitoring cannot prove total-host detection by itself because it disappears with the failed host.

### Collector security

Collector uses dedicated ServiceAccount/RBAC, internal-only OTLP ingress, restricted telemetry-backend egress, memory limiter/batching/finite queues, pre-export redaction/filtering, and no unbounded persistent retry spool.

The only approved node-log filesystem exception is the narrow ADR-0044 read-only mount of exact Kubernetes pod/container log paths. No broad host filesystem, host network, privilege escalation, or unrelated `hostPath` is implied.

## 8. Telemetry data contract

Never place secrets/credentials/full bodies/SQL binds/complete gRPC metadata/compromised-password hash material/raw client IP or unapproved PII in logs/metrics/traces.

Metric labels are low-cardinality and exclude user/tenant/session/request/resource/trace IDs, raw URLs/IPs, and free-form errors.

Baggage is allow-list only and excludes subject/contact/tenant/session/raw-IP/secret values.

Logs may carry bounded trace/span correlation fields; trace IDs are not metric labels.

Ordinary telemetry is `OBSERVABILITY` class: bounded buffering/sampling/drop is allowed. Exporter/backend loss does not fail an ordinary business request. Sustained loss/backpressure/drop is itself alerted.

Required security/privileged audit remains authoritative durable/off-host evidence and MUST NOT be silently routed only through Loki/Collector.

## 9. Required service signals

Where applicable each service exposes bounded signals for:

- request rate/latency/error/saturation by low-cardinality operation;
- deadline exhaustion/cancellation;
- bulkhead/queue/pool wait/rejection;
- datastore/provider latency/outcome;
- PostgreSQL pool/query/transaction/WAL/archive/backup/restore pressure;
- Redis latency/memory/AOF/time/cardinality/allocation state;
- Kafka produce/fetch/lag/disk/rebuild/replay;
- edge/Istio/Kyverno/control-plane health;
- host CPU/memory/storage/network/conntrack/FD/listen/ephemeral-port pressure;
- client-address trust health without raw IP;
- backup/restore/DR evidence freshness;
- audit export health.

Every service owns the alerts/dashboard or equivalent query/evidence for its defined SLO/security/reliability signals. A metric with no actionable ownership is not a substitute for required evidence.

## 10. Telemetry capacity

Observability competes for the same single-server CPU/RAM/IO/disk/network as applications, PostgreSQL WAL/backups, Redis AOF, Kafka, WAF, Istio, Kyverno, and OpenBao.

Complete-stack evidence measures Collector receive/export/queues/drops; Prometheus series/scrape/TSDB; Loki ingest/query/storage; Tempo ingest/query/storage; Grafana/Alertmanager overhead; retention growth/free space and IO contention.

If observability pressure violates headroom, first reduce safe cardinality/sampling/retention, externalize ordinary telemetry, or add capacity. Never drop required audit/security evidence or disable security controls to fit telemetry.

## 11. Failure/chaos evidence

Staging/release/scheduled tests prove:

- telemetry backend/Collector outage does not fail ordinary business requests;
- dropped/backpressured telemetry is detected;
- one synthetic critical journey produces expected safe correlated logs/metrics/traces;
- PII/secret canaries do not appear in Loki/Tempo/Prometheus/Grafana-visible data;
- complete-host loss is detected through the external monitor while local stack is unavailable;
- Redis common-mode clock/cardinality faults remain fail closed and observable;
- security/audit evidence remains durable/off-host during local observability loss.

## 12. Recovery order

Full-host recovery follows `../runbooks/production-cold-dr.md`.

Before traffic, establish host/network/time, management/audit, K3s/Calico, OpenBao/secret delivery, PostgreSQL, Redis/Kafka, Istio/Kyverno/edge, applications, and observability. External host-down monitoring must return healthy only after the public path actually satisfies its check.

A health endpoint alone is not recovery evidence.

## 13. Migration triggers to HA/externalization

Review profile/capacity when any of these becomes material:

- business requirement for maintenance without full outage;
- repeated host incidents/unacceptable downtime;
- <30% validated CPU/memory headroom;
- persistent PostgreSQL/Redis/Kafka/telemetry IO contention;
- security/control-plane latency/capacity cannot fit;
- recovery RTO is unacceptable;
- local observability storage/cardinality materially threatens business/security workloads;
- broker/session/security dependency availability need exceeds single-server profile.

Do not mask triggers by weakening correctness/security.

## 14. Conversation/model-run reliability

ADR-0054 separates interactive run acceptance from long provider execution. Acceptance is a short
authorized/cost-reserved PostgreSQL transaction returning a stable run identity. A bounded worker
claims durable state, releases locks, and performs one provider attempt with a 60-second safety
deadline, cancellation propagation, global/per-tenant bulkheads, zero/unbounded-queue prohibition,
and breaker-open suppression. There is no automatic retry or alternate-provider/model fallback.

Required signals are low-cardinality API/run/provider/budget outcomes, queue depth/oldest age/start
delay, worker/bulkhead/breaker saturation, cancellation, `OUTCOME_UNKNOWN`, reservation age,
token/cost totals by safe model alias/price version, RLS/database pool state, erasure lag, and audit
health. Content and subject/resource/request/provider identifiers are prohibited telemetry.

Provider, Authorization, budget, key, lifecycle, database, or required audit uncertainty fails with
the owning stable availability/state result. Telemetry outage does not alter durable run/cost state.
Complete-stack evidence must include provider latency/connection pressure and worst-case accepted-run
queue/cost behavior before production enablement.
