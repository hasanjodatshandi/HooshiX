# ADR-0007: Notification v1 Runtime

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

## Decision

### Provider model

Notification owns Email/SMS provider adapters and provider-specific telemetry. Production Email uses Liara Transactional Email via authenticated SMTP + STARTTLS under ADR-0010. Production Iran SMS uses IPPanel Edge Webservice mode under ADR-0020. Local development may use `LoggingSmsProviderAdapter` only under `local & !staging & !production`; it is never a production fallback.

Notification renders exact versioned content itself. Provider-managed SMS pattern rendering is not the production source of message semantics.

Provider receipt/evidence handling is authenticated, correlated, bounded, and never logs raw provider payloads. `PROVIDER_ACCEPTED` and `DELIVERED` remain distinct; inconclusive final evidence becomes `DELIVERY_STATUS_UNKNOWN`.

### PostgreSQL ownership and schema

Notification owns database `notification` on its dedicated production CloudNativePG cluster. Notification-only runtime/migration roles have no privileges on another service database/cluster.

Persistence uses jOOQ/JDBC without JPA. Core relational structures include:

```text
notification
notification_attempt
provider_receipt_evidence
notification_result_outbox
template_definition
template_version
template_activation/audit
bounded retention metadata
```

The obsolete dispatch-fence table/mechanism is not part of current v1.

Core constraints/indexing include stable caller request uniqueness, bounded work-queue indexes, provider-correlation lookup, and result-outbox pending indexes appropriate to the current schema. Exact DDL belongs to Flyway migrations and is validated with representative query plans.

Flyway is the only schema-change mechanism. Executed migrations are immutable. Retention cleanup uses bounded batches/transactions; v1 does not require table partitioning.

### Dispatch safety

Provider workers use:

```text
claim lease:               30s
claim batch:               25
busy poll:                 250ms
idle poll:                 1s
transaction isolation:     READ COMMITTED
lock_timeout:              100ms
general statement_timeout: 500ms
claim pattern:             FOR UPDATE SKIP LOCKED
```

Immediately before provider I/O, Notification performs a short local transaction that locks/reloads the attempt, evaluates eligibility using PostgreSQL-authoritative time, persists immutable execution identity and `DISPATCHING`, and commits.

Provider I/O begins only after commit. No network I/O occurs inside the transaction.

After `DISPATCHING`, worker crash, lease expiry, failover, timeout, or unknown provider result never authorizes blind redispatch. A stale `DISPATCHING` attempt enters reconciliation under the current ambiguity/evidence rules. Late evidence may mutate only a non-terminal Notification through a legal transition; terminal lifecycle states are immutable.

### PostgreSQL HA

Production Notification uses the current dedicated CloudNativePG fleet baseline:

- three PostgreSQL instances for the critical service;
- synchronous required durability/quorum;
- safe automatic failover with failover-quorum refusal when durability cannot be proven;
- continuous WAL archive + daily base backup;
- independent backup credentials/encryption context;
- monthly isolated restore evidence and quarterly DR exercise per current database/operations decisions.

A synchronously committed `DISPATCHING` transition must survive every permitted automatic failover before provider I/O can be considered safe.

### Runtime observability

Prometheus/OpenTelemetry telemetry includes bounded signals for:

```text
submit outcome/latency
PostgreSQL availability/failover
claim/dispatch transaction latency
provider attempt outcome/ambiguity
provider receipt lag
sensitive escrow oldest age
result-outbox backlog/oldest age
callback outcome/latency
reconciliation backlog
```

Former clock-agent/fence-cycle metrics are not part of current runtime. Infrastructure NTP/Chrony health is monitored by the platform/node layer.

Metric labels never contain recipient, raw/pseudonymous request or notification IDs, verification codes, provider message IDs, ciphertext, or free-form error text.

Synthetic checks use organization-owned test identities/provider sandboxes only; customer PII is prohibited.

### Availability and operational gates

`SubmitNotification` durable acceptance remains a critical internal dependency objective. First provider-attempt scheduling is Class C: 99.9% of durably accepted intents begin their first provider attempt within five seconds.

Production readiness requires load/chaos/failover evidence for claim concurrency, dispatch commit durability, provider ambiguity/reconciliation, callback backlog, and database failover. Alert thresholds are based on current SLO/burn policy and service runbooks; obsolete clock-agent/fence thresholds are not retained.

## Security and verification requirements

- provider credentials are least privilege, secret-managed, rotated, and never logged;
- provider HTTP/SMTP settings use finite timeouts and no unsafe layered retry;
- IPPanel accepted/report status fixtures and ambiguous-submission behavior are contract-tested;
- Liara STARTTLS/authentication/outcome mapping is contract-tested;
- persistence tests cover uniqueness, state transitions, terminal immutability, `SKIP LOCKED` concurrency, dispatch locking, bounded cleanup, query plans, and Flyway compatibility;
- crash tests cover immediately before/after the `DISPATCHING` commit;
- CloudNativePG failover tests prove no blind redispatch and no acknowledged state loss;
- local key-ring tests cover rotation/reload/corruption/erasure and prove no OpenBao hot-path RPC;
- Istio/NetworkPolicy tests prove positive and negative workload authorization;
- telemetry tests prove PII/secret-safe output.

## Rollback considerations

Rollback cannot redispatch a committed `DISPATCHING` attempt, mutate a terminal state, restore erased sensitive escrow, or reverse an executed Flyway migration. Application rollback therefore uses backward-compatible expanded schema and preserves the current reconciliation contract for all accepted work.
