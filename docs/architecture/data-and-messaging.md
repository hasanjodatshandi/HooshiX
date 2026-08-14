# Data and Messaging Architecture — Current State

This document is the implementation-facing current data/messaging model. Detailed SQL/Flyway implementation rules live in `../engineering/sql-and-flyway-coding-standards.md`; exact versions live in the Technology Baseline. Production physical topology is profile-aware under ADR-0042.

## 1. Data ownership and isolation

Every independently deployable microservice with **mutable relational business persistence** owns:

- one distinct PostgreSQL database;
- independent runtime/migration credentials and Flyway history;
- its schemas, migrations, repositories/query adapters, and service-level connection/capacity budget;
- no cross-service SQL/model/credential access.

Physical placement depends on the production profile:

- `production-single-server`: one shared physical CloudNativePG/PostgreSQL cluster contains the distinct service databases;
- `production-ha`: each mutable PostgreSQL service has a dedicated CloudNativePG cluster.

The single-server exception changes physical failure/backup blast radius only. It does not permit shared schemas, shared roles, shared Flyway history, cross-database integration, or cross-service persistence models.

Prohibited in every profile/environment:

- database/schema ownership shared by multiple services;
- direct cross-service SQL, joins, foreign keys, FDW/`dblink`, database views, or logical replication as integration APIs;
- cross-service ORM/JPA entities or jOOQ persistence models;
- another service database used as an integration/read API;
- application or migration credentials that can connect/access another service database.

Cross-bounded-context data moves only through approved versioned synchronous contracts or integration events.

### Immutable local SQLite reference-data exception — ADR-0040

Compromised Password Service uses one narrowly approved embedded SQLite database as an **immutable, read-only, rebuildable reference-data artifact**. It is not mutable service business persistence or cross-service integration storage.

The exception is limited to the compromised-password dataset and has these properties:

- SQLite file is service-local and caller-inaccessible;
- production runtime performs read-only fixed parameterized lookup only;
- no runtime INSERT/UPDATE/DELETE/DDL, `ATTACH`, arbitrary PRAGMA, extension loading, or caller-selected database path/URI;
- dataset is built offline and released as a new immutable version rather than mutated/migrated in place;
- no PostgreSQL/CloudNativePG/Flyway/WAL/PITR is required for this rebuildable artifact;
- no other service may read the SQLite file directly;
- raw password, full Identity SHA-256 digest, User/Tenant/Contact/session identity, and mutable subject-owned state are absent;
- future mutable business/source-of-truth state does not inherit this exception.

Exact schema/query/runtime bounds are in `services/compromised-password-service.md` and ADR-0040. Recovery redeploys/reconstructs the approved immutable artifact and validates it before readiness.

### Immutable application-bundled Reference Data — ADR-0041

Reference Data v1 uses **no database or broker**. Country, Currency, TimeZone, and SupportedLocale are a small immutable read-only application resource packaged inside the same signed service image after reviewed offline import.

- no PostgreSQL, CloudNativePG, Flyway, SQLite, Redis, or Kafka datastore exists for Reference Data v1;
- runtime performs no ISO/IANA/Unicode/CLDR Internet synchronization;
- exact source revisions, provenance/integrity/license evidence and content digest are immutable bundle/release metadata;
- production has no write/update endpoint or separately mutable dataset volume;
- startup may load only these deliberately small bounded families into immutable in-process indexes;
- another service consumes the data only through the approved typed gRPC contract, initially Web BFF;
- caller-selected dataset/schema/query registries and generic shared key/value data are prohibited;
- tenant/user/business configuration and mutable source-of-truth state are outside this boundary.

If Reference Data later gains mutable relational business state, the normal PostgreSQL/Flyway architecture applies after explicit bounded-context/persistence review.

### Tenant isolation

Every tenant-owned production PostgreSQL table uses forced PostgreSQL RLS plus application/repository tenant enforcement. Trusted tenant context comes only from validated authenticated context and is installed with transaction-local semantics; session-scoped tenant state on pooled connections is prohibited. The canonical SQL/Flyway standard requires a parameterized transaction-local setting such as `set_config(..., true)`, fail-closed absent/malformed context, and pooled-connection reuse tests across commit/rollback. Runtime roles are `NOSUPERUSER NOBYPASSRLS`, are not table owners, and cannot connect/access another service database.

The ADR-0040 SQLite dataset and ADR-0041 Reference Data bundle are global reference data, not tenant-owned business state, and contain no tenant identifier; PostgreSQL RLS does not apply to them.

## 2. Production PostgreSQL profiles

Exact patches are owned by `../technology/technology-baseline.md` and deployment metadata.

### `production-single-server`

```text
1 CloudNativePG cluster
1 PostgreSQL instance
separate database per mutable PostgreSQL service
separate runtime role per service
separate migration/owner role per service
separate Flyway history/release ownership per service
forced RLS where applicable
one shared process/host/storage failure domain
```

The sum of all application Hikari `maximumPoolSize` values across all service pods stays <=70% of shared PostgreSQL `max_connections`; >=30% remains for migrations, backup/recovery, monitoring, administration, and emergencies. Per-service pool ceilings are measured/versioned so one service cannot consume another service's operational headroom.

No PostgreSQL automatic failover is claimed in this profile. Host/process/storage failure can make all PostgreSQL-backed services unavailable.

### `production-ha`

```text
per persistent mutable-state service:
  dedicated CloudNativePG cluster/database/roles/backups
  3 instances for critical services
  quorum synchronous replication: ANY 1 equivalent
  required durability + failover quorum
  automatic failover only when acknowledged durability is safe
```

Aggregate application Hikari maxima across a service's HPA maximum stay <=70% of that service cluster's `max_connections`; >=30% remains for replication/failover, migrations, monitoring, administration, and emergencies.

Applications use the selected cluster writer/read-write endpoint for transactional work. Read replicas enter business read paths only when their consistency semantics are explicitly acceptable. PgBouncer is not a default and requires measured connection-pressure evidence.

ADR-0040 Compromised Password SQLite lookup uses service-owned bounded read concurrency rather than Hikari/PostgreSQL connection budgets. ADR-0041 Reference Data has no DB connection pool.

## 3. Flyway, schema evolution, JPA, jOOQ, and immutable reference data

Flyway is the only schema-change mechanism for mutable service relational persistence. Released/executed migrations are immutable. Evolution follows:

```text
expand -> compatible deploy -> bounded/resumable migrate/backfill -> verify -> contract
```

Application rollback must remain compatible with expanded schema; automatic database downgrade is never assumed.

JPA/Hibernate is appropriate for aggregate persistence and bounded CRUD when it fits. Domain and JPA models remain separate; persistence entities stay Infrastructure-only; LAZY is default; broad EAGER/N+1/OSIV are prohibited; fetch plans/cascades/orphan removal are explicit.

jOOQ/JDBC is appropriate for complex read/query models, bulk/set-based work, CTE/window/PostgreSQL-specific SQL, and measured performance-sensitive queries. Generated types remain Infrastructure-only. Notification persistence is jOOQ/JDBC without JPA.

ADR-0040 SQLite does not use runtime Flyway migrations. Its schema is part of the offline immutable dataset format, versioned and validated before publication. SQLite/JDBC types and SQL stay Infrastructure-only and never enter Domain/Application.

ADR-0041 Reference Data has no runtime schema/migration technology. A format/source change creates a new deterministic immutable application bundle and signed service image. Runtime never performs in-place data/schema migration.

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

Compromised Password runtime uses one fixed indexed SQLite read by exact 20-bit prefix. It has no runtime write transaction, no full-table scan on the normal path, no dynamic SQL, and no full-dataset application-memory cache. Prefix cardinality and response size are hard-bounded by dataset build validation. Runtime failure never truncates a result into a false clean-password decision.

Reference Data is not a database query path. Its typed immutable in-process reads use deterministic ordering, default page size 100, maximum 200, bounded opaque/versioned page tokens and <=128 KiB serialized response.

## 5. PostgreSQL backup, restore, and DR

Both production profiles retain the physical Barman/WAL/PITR recovery model:

- PostgreSQL RPO <=5m;
- platform cold-DR RTO <=4h;
- continuous WAL archive to encrypted off-site object storage;
- daily online physical base backup via approved CloudNativePG/Barman integration;
- 35-day PITR;
- monthly retained recovery artifact for 12 months;
- versioning/object lock where supported;
- verification every backup cycle;
- isolated PostgreSQL restore at least monthly;
- full cold-DR exercise quarterly;
- queryable RPO/RTO/integrity/RLS/erasure evidence.

`pg_dump + cron` is not the production backup strategy.

### Single-server service-specific recovery

Physical backup/WAL covers the complete shared PostgreSQL cluster. A PITR restore therefore starts in an isolated recovery environment:

1. restore the complete shared cluster to the required recovery point;
2. verify PostgreSQL integrity, service Flyway versions, roles, RLS, and erasure/legal-hold state;
3. extract only the required service database through an approved logical transfer procedure;
4. restore/import it through controlled maintenance with compatibility checks;
5. prove unrelated current service databases were not destructively restored.

A logical export is allowed as this controlled recovery step. It does not replace physical off-site backup/WAL/PITR.

### HA recovery

Dedicated service clusters retain independent backup identities/namespaces and service-specific physical PITR/restore evidence.

The ADR-0040 SQLite dataset and ADR-0041 Reference Data bundle are recovered by immutable artifact rebuild/redeploy, not PostgreSQL PITR.

## 6. Kafka platform and contracts

Kafka is asynchronous integration transport, not ordinary request/reply and not business source of truth.

Profile topology from ADR-0015/ADR-0042:

`production-single-server`:

```text
Kafka 4.2.x KRaft
1 combined broker/controller
critical RF=1 / minISR=1 / acks=all
idempotent producers
unclean leader election disabled
formal non-HA acceptance
```

`production-ha`:

```text
Kafka 4.2.x KRaft
3 brokers + 3 dedicated controllers
critical RF=3 / minISR=2 / acks=all
idempotent producers
unclean leader election disabled
```

Kafka native TLS/authentication/per-service principals/ACLs/quotas remain mandatory in both profiles. Internal topics/features that require replication above one are explicitly configured for the one-broker topology where used.

Protobuf schemas are Git-owned and validated with Buf `STANDARD` lint + `FILE` breaking compatibility. Field numbers are never reused. No runtime Schema Registry exists in v1.

Compromised Password and Reference Data v1 use no Kafka path.

## 7. Transactional Outbox and publication evidence

When local state change + integration-event publication are one business effect, state and Outbox record commit in the same local transaction. Direct save-then-Kafka-send as an atomicity substitute is prohibited.

Default relay is polling + `SKIP LOCKED`; CDC/Debezium requires measured need and a reviewed current decision.

For critical `OUTBOX_REPLAYABLE` events, published outbox/equivalent immutable publication evidence is retained for **at least 35 days**, aligned with PITR/recovery. It preserves stable event identity and an approved replay payload or deterministic reconstruction reference. Privacy/erasure/legal-hold rules still apply and secrets are never retained merely for replay.

The RF=1 single-server Kafka profile does not weaken this evidence requirement.

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

The approved `security-redis` deployment is restricted to security-ephemeral capabilities such as semantic quotas and BFF session/pre-auth state.

Shared rules:

- TLS;
- independent ACL identities/key namespaces;
- `noeviction`;
- fail-closed authoritative security decisions;
- no raw PII/business/session/pre-auth identifiers where pseudonymous HMAC keys are required;
- state is not durable business truth.

`production-single-server`:

```text
1 Redis instance
AOF enabled
appendfsync everysec
no failover claim
```

`production-ha`:

```text
1 primary
2 replicas
3 Sentinel voters
```

ADR-0024 owns semantic quota behavior: 75ms, one attempt, fail closed, dual trusted time with <=2s skew, monotonic effective time, and no security-budget reset from TTL expiry.

If Redis session state is lost, users reauthenticate; browser cookies never reconstruct authenticated server state. AOF reduces restart loss but does not provide HA.

If session/quota workloads materially interfere, split physical deployments or move to the HA profile before introducing Redis Cluster complexity.

Any business cache remains service-owned and never fabricates authorization or business truth. Distributed locks require proven need plus fencing; a Redis lock alone does not establish correctness.

Compromised Password and Reference Data do not use Redis as dataset caches.

## 10. Verification

Applicable evidence includes:

- database privilege isolation and negative cross-service `CONNECT`/object-access tests;
- forced-RLS negatives including pooled-connection tenant-context reuse;
- Flyway rolling compatibility;
- query-bound/index/plan tests;
- profile-specific pool budgets;
- transaction/no-remote-I/O tests;
- off-site WAL/PITR backup and restore evidence;
- single-server isolated whole-cluster PITR plus service-specific recovery without destructive restoration of another current DB;
- HA PostgreSQL failover/dedicated-backup evidence when HA profile is selected;
- Kafka profile topology, ACL/TLS, durability/rebuild/replay, Outbox/Inbox duplicate/restart tests;
- Protobuf compatibility;
- Redis TLS/ACL/noeviction/failure tests plus AOF/restart evidence in single-server or Sentinel failover in HA;
- PII/secret-safe persistence/telemetry.

ADR-0040 evidence additionally covers offline SQLite dataset compiler determinism/integrity/bounds, exact indexed prefix lookup, read-only/query-only runtime, no write/DDL/ATTACH/extension loading, server-owned path/URI configuration, Java/native dependency security, no external provider/Internet lookup, representative disk-backed latency/load, dataset identity, and rebuild/redeploy recovery.

ADR-0041 evidence, once its implementation trigger is met, additionally covers approved offline source provenance/integrity/license use, deterministic bundle generation, exact source-revision manifest/content digest, typed bounded gRPC/pagination/<=128 KiB behavior, bounded startup memory, no DB/Redis/Kafka/runtime source dependency, BFF-only initial workload path, no server stale/fabricated fallback, and signed-image rebuild/redeploy recovery.
