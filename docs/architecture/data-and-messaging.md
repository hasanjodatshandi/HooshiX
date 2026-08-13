# Data and Messaging Architecture — Current State

This document is the implementation-facing current data/messaging model. Detailed SQL/Flyway implementation rules live in `../engineering/sql-and-flyway-coding-standards.md`; exact versions live in the Technology Baseline.

## 1. Data ownership and isolation

Every independently deployable microservice with relational persistence owns:

- one distinct PostgreSQL database;
- independent runtime/migration credentials and Flyway history;
- its schemas, migrations, repositories/query adapters, connection/capacity budget, and backup identity;
- in production, one dedicated CloudNativePG cluster under ADR-0027.

Non-production may consolidate physical database infrastructure only while database/credential/role/Flyway ownership remains isolated.

Prohibited in every environment:

- database/schema shared by multiple services;
- direct cross-service SQL, joins, foreign keys, FDW/`dblink`, or database views as integration APIs;
- cross-service ORM/JPA entities or jOOQ persistence models;
- another service database used as an integration/read API.

Cross-bounded-context data moves only through approved versioned synchronous contracts or integration events.

### Tenant isolation

Every tenant-owned production table uses forced PostgreSQL RLS plus application/repository tenant enforcement. Trusted tenant context comes only from validated authenticated context and is installed with transaction-local semantics; session-scoped tenant state on pooled connections is prohibited. The canonical SQL/Flyway standard requires a parameterized transaction-local setting such as `set_config(..., true)`, fail-closed absent/malformed context, and pooled-connection reuse tests across commit/rollback. Runtime roles are `NOSUPERUSER NOBYPASSRLS`, are not table owners, and cannot connect/access another service database.

## 2. Production PostgreSQL topology

Current production line:

```text
per persistent service:
  CloudNativePG 1.30.x
  PostgreSQL 18.x
  dedicated cluster/database/roles/backups
  3 instances for critical services
  quorum synchronous replication: ANY 1 equivalent
  required durability + failover quorum
  automatic failover only when acknowledged durability is safe
```

Exact patches are owned by `../technology/technology-baseline.md` and deployment metadata.

Application pools use the service cluster primary/read-write endpoint for transactional work. Read replicas enter business read paths only when their consistency semantics are explicitly acceptable.

Aggregate application Hikari maxima across a service's HPA maximum stay <=70% of that cluster's `max_connections`; >=30% remains for replication/failover, migrations, monitoring, administration, and emergencies. PgBouncer is not a default and requires measured connection-pressure evidence.

## 3. Flyway, schema evolution, JPA, and jOOQ

Flyway is the only schema-change mechanism. Released/executed migrations are immutable. Evolution follows:

```text
expand -> compatible deploy -> bounded/resumable migrate/backfill -> verify -> contract
```

Application rollback must remain compatible with expanded schema; automatic database downgrade is never assumed.

JPA/Hibernate is appropriate for aggregate persistence and bounded CRUD when it fits. Domain and JPA models remain separate; persistence entities stay Infrastructure-only; LAZY is default; broad EAGER/N+1/OSIV are prohibited; fetch plans/cascades/orphan removal are explicit.

jOOQ/JDBC is appropriate for complex read/query models, bulk/set-based work, CTE/window/PostgreSQL-specific SQL, and measured performance-sensitive queries. Generated types remain Infrastructure-only. Notification persistence is jOOQ/JDBC without JPA.

Canonical SQL/query/migration details live in `../engineering/sql-and-flyway-coding-standards.md`.

## 4. Query and transaction rules

- every multi-row production query has deterministic pagination or a hard proven bound;
- explicit column lists; production application `SELECT *` is prohibited;
- sensitive/expensive queries require reviewed indexes and representative plan evidence;
- transactions are short and explicit;
- gRPC/HTTP/Kafka/Redis/provider I/O inside a DB transaction is prohibited;
- DB locks are never held across remote I/O;
- retries run outside the failed transaction;
- lock/statement/acquisition timeouts are finite where contention matters;
- `SKIP LOCKED` work claims use short claim transactions and process work after commit.

Virtual Threads do not create database capacity; pool pending/acquisition and query/transaction latency are observable.

## 5. PostgreSQL backup, restore, and DR

Current service-cluster baseline:

- PostgreSQL RPO <=5m;
- platform cold-DR RTO <=4h;
- continuous WAL archive to encrypted off-site object storage;
- daily online physical base backup via approved CloudNativePG/Barman integration;
- 35-day PITR;
- monthly retained backup set for 12 months;
- versioning/object lock where supported;
- verification every backup cycle;
- isolated restore monthly per service;
- full cold-DR exercise quarterly;
- queryable restore evidence under ADR-0037.

Restored environments MUST reconcile data integrity plus current logical-deletion/erasure/legal-hold requirements before traffic opens.

## 6. Kafka platform and contracts

Kafka is asynchronous integration transport, not ordinary request/reply and not business source of truth.

Current production topology comes from ADR-0015:

```text
Kafka 4.2.x KRaft
3 brokers + 3 dedicated controllers
critical RF=3 / minISR=2 / acks=all
idempotent producers
unclean leader election disabled
```

Kafka native TLS/authentication/per-service principals/ACLs/quotas remain mandatory. Exact patches live in the Technology Baseline.

Protobuf schemas are Git-owned and validated with Buf `STANDARD` lint + `FILE` breaking compatibility. Field numbers are never reused. No runtime Schema Registry exists in v1.

## 7. Transactional Outbox and publication evidence

When local state change + integration-event publication are one business effect, state and Outbox record commit in the same local transaction. Direct save-then-Kafka-send as an atomicity substitute is prohibited.

Default relay is polling + `SKIP LOCKED`; CDC/Debezium requires measured need and a reviewed current decision.

For critical `OUTBOX_REPLAYABLE` events, published outbox/equivalent immutable publication evidence is retained for **at least 35 days**, aligned with PITR/recovery. It preserves stable event identity and an approved replay payload or deterministic reconstruction reference. Privacy/erasure/legal-hold rules still apply and secrets are never retained merely for replay.

## 8. Consumer semantics

Assume at-least-once delivery:

- business effect is idempotent;
- Inbox/processed-message evidence commits atomically with the local business effect when required;
- critical replay participants retain dedup/inbox evidence for the full 35-day recovery horizon;
- offsets commit only after durable effect;
- retry is finite/single-owner and poison outcomes are explicit;
- retry/DLQ topics are explicit; critical retry/DLQ retention >=14 days;
- replay procedure/runbook exists before production;
- ordering is promised only inside the selected partition key.

Kafka cold DR rebuilds infrastructure/configuration from GitOps, then replays/reconstructs critical service-owned evidence. Stable business/event IDs survive reconstruction even when broker offsets change.

## 9. Security Redis

The approved physical `security-redis` deployment is restricted to security-ephemeral capabilities such as semantic quotas and BFF session state:

```text
1 primary
2 replicas
3 Sentinel voters
TLS + independent ACL identities/key namespaces
noeviction
```

ADR-0024 owns semantic quota behavior: 75ms, one attempt, fail closed, dual trusted time with <=2s skew, monotonic effective time, and no security-budget reset from TTL expiry.

Redis is not a shared business cache or durable business source of truth. Raw PII/business identifiers are prohibited in security keys when pseudonymous HMAC keys are required. If session/quota workloads materially interfere, split physical Sentinel deployments before introducing Redis Cluster complexity.

Any business cache remains service-owned, defines correct miss/TTL/stampede/failure behavior, bounds object/key cardinality, and never fabricates authorization or business truth. Distributed locks require proven need plus fencing; a Redis lock alone does not establish correctness.

## 10. Verification

Applicable evidence includes database privilege isolation, forced-RLS negatives including pooled-connection tenant-context reuse, Flyway rolling compatibility, query-bound/index/plan tests, pool budgets, transaction/no-remote-I/O tests, PostgreSQL failover/restore/PITR, Kafka durability/rebuild/replay, Outbox/Inbox duplicate/restart tests, Protobuf compatibility, Redis Sentinel/quota failure tests, and PII/secret-safe persistence/telemetry.
