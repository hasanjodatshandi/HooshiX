# Data and Messaging Architecture

## 1. Data ownership

PostgreSQL is the primary durable database. ADR-0053 requires every independently deployable microservice with relational persistence to own a **distinct PostgreSQL database**, independent application/migration credentials, Flyway history, schemas, repository/query adapters, and access policy.

A service may use multiple schemas inside its own database when justified, but no database or schema is shared between microservices. Service database roles have no `CONNECT` or object privileges on other service databases.

ADR-0057 strengthens this boundary for production: every persistent microservice
uses a dedicated physical CloudNativePG cluster and independent backup namespace/
credentials/encryption context. Non-production may consolidate infrastructure
without sharing service databases or roles.

No environment permits:

- a database or schema shared by multiple services;
- direct cross-service SQL or cross-database joins;
- cross-service foreign keys, FDWs, `dblink`, or database views as integration APIs;
- shared ORM/JPA entities or jOOQ-generated persistence models;
- using another service's database as an integration API.

Cross-bounded-context data moves only through approved versioned service contracts/events.

## 2. Production PostgreSQL topology

ADR-0048 + ADR-0057 are current. Each persistent production microservice owns
its own HA cluster. Exact patches are owned by the Technology Baseline and
deployment metadata; the architectural compatibility line is:

```text
per persistent service:
  CloudNativePG 1.30.x
  PostgreSQL 18.x
  3 instances for critical production services
  quorum synchronous replication: ANY 1 equivalent
  data durability: required
  failover quorum: enabled
  automatic safe failover
  independent WAL/PITR backup namespace and credentials
```

Safe failover prioritizes acknowledged data durability. If the operator cannot
prove a safe promotion quorum, write availability may stop instead of promoting
an unsafe replica.

Services use their own cluster's primary/read-write endpoint for transactions.
Read replicas are not introduced into a business read path unless consistency
semantics are explicitly acceptable.

Every tenant-owned production table also uses forced PostgreSQL RLS with a
transaction-local tenant context derived only from validated security context.
Runtime roles are `NOSUPERUSER NOBYPASSRLS`, are not table owners, and retain the
application/repository tenant checks required by ADR-0002.

## 3. Flyway and schema evolution

Flyway is the **only** schema-change mechanism.

- executed/released migrations are immutable;
- schema evolution uses expand -> migrate -> contract;
- application rollback must remain compatible with the expanded schema;
- automatic database rollback is never assumed;
- destructive changes require explicit retention/legal-hold/rollback review.

JPA `ddl-auto` remains `validate` and OSIV is prohibited.

## 4. JPA/Hibernate

Use JPA/Hibernate for aggregate persistence and controlled CRUD where it fits.

Mandatory rules:

- Domain and JPA models are separate;
- persistence entities stay in Infrastructure;
- associations LAZY by default;
- no broad EAGER graphs;
- explicit fetch plans/projections/entity graphs/join fetches;
- cascades/orphan removal reviewed against aggregate ownership;
- N+1 prohibited;
- `equals`/`hashCode`/`toString` must not traverse lazy graphs unsafely;
- persistence models follow aggregate/query needs; a mandatory one-table/one-persistence-model mapping is prohibited;
- batch size, fetch size, flush behavior, and bulk-write strategy are measured for performance-sensitive paths rather than configured as unreviewed global magic values.

## 5. jOOQ

Use jOOQ for complex read/query models, reporting, bulk operations, CTEs,
window functions, and performance-sensitive SQL. Generated jOOQ types remain
Infrastructure concerns.

Notification persistence is explicitly jOOQ/JDBC without JPA.

## 6. Query and transaction rules

- every multi-row production query has deterministic pagination or a hard bound;
- `SELECT *` is prohibited in application production code;
- expensive/sensitive queries require reviewed indexes and execution plans;
- transactions are short;
- network I/O inside a DB transaction is prohibited;
- no DB lock is held during gRPC/Kafka/HTTP/Redis/provider I/O;
- retries occur outside the transaction;
- database lock/statement timeouts are bounded where contention matters.

## 7. Connection capacity

Virtual Threads do not create database capacity.

Hikari pools are sized from each service's own PostgreSQL cluster capacity. For
each cluster, aggregate application Hikari `maximumPoolSize` across that service's
HPA maximum is capped at no more than 70% of PostgreSQL `max_connections`,
retaining at least 30% for failover, replication, migrations, monitoring,
administration, and emergency headroom.

Increasing pool size is not a fix for slow SQL. Monitor:

```text
db.pool.active
db.pool.idle
db.pool.pending
db.pool.acquire.duration
db.query.duration
db.transaction.duration
```

PgBouncer is not a v1 default. Add it only after measured connection-count/
churn pressure proves value.

## 8. PostgreSQL backup/PITR

ADR-0027 targets remain current, with ADR-0048's simpler backup shape:

- PostgreSQL RPO <=5m;
- platform RTO <=4h;
- continuous WAL archive to encrypted off-site object storage;
- daily online physical base backup using Barman Cloud CNPG-I plugin;
- PITR 35 days;
- monthly retained backup set 12 months;
- versioning/object lock when supported;
- verify every backup cycle;
- isolated restore monthly;
- full DR exercise quarterly.

A restored environment does not serve traffic until data integrity and logical
deletion/erasure/legal-hold/identifier-release decisions are reconciled.

## 9. Kafka platform

Kafka is the asynchronous/event-driven inter-service transport. Each environment
uses one shared platform-managed cluster with domain topic ownership, ACLs,
quotas, and consumer groups.

Production v1 uses the ADR-0044 Kafka 4.2.x KRaft line; the exact patch is owned
by the Technology Baseline and immutable deployment metadata:

```text
Kafka 4.2.x / KRaft
3 brokers
3 dedicated controllers
```

Critical topics:

```text
replication.factor=3
min.insync.replicas=2
acks=all
idempotent producer enabled
unclean leader election disabled
```

Kafka native TLS/auth/ACLs remain required even when clients are in Istio.

## 10. Protobuf governance

Git is the source of truth; no runtime Schema Registry exists in v1.

CI runs equivalent:

```text
buf lint                 # STANDARD
buf breaking --against main  # FILE policy
```

Field numbers are never reused; removed names/numbers are reserved as needed.
Dynamic/runtime schema discovery requires a future ADR.

## 11. Transactional Outbox

When local state change + durable event publication are one business effect,
persist both in the same local transaction.

Prohibited:

```java
repository.save(entity);
kafkaTemplate.send(topic, event);
```

Default relay is polling + `SKIP LOCKED`. CDC/Debezium requires a proven scale
need and ADR.

For `OUTBOX_REPLAYABLE` critical events, the published transactional outbox
record or equivalent immutable publication evidence is retained for at least
35 days, aligned with the current PITR/recovery horizon. It preserves the stable
event identity and approved replay payload or deterministic reconstruction
reference. Privacy, erasure, and legal-hold rules still apply; secrets are never
retained merely for replay.

## 12. Consumer semantics

Assume at-least-once delivery.

- consumer business effect is idempotent;
- Inbox/processed-message record is committed with the local business effect
  where required;
- consumer Inbox/dedup evidence is retained for at least the full 35-day critical replay/recovery horizon when that consumer participates in `OUTBOX_REPLAYABLE` recovery;
- offsets commit after durable result;
- retry is finite and explicit;
- retry/DLQ topics are explicit and critical retry/DLQ retention >=14 days;
- poison-message ownership/runbook is mandatory;
- replay procedure exists before production;
- partition key follows aggregate ID only where ordering requires it;
- ordering is only guaranteed within the same partition key.

## 13. Kafka disaster recovery

Kafka broker disks are not the off-site business source-of-truth backup in v1.
Cold DR rebuilds a clean Kafka cluster from GitOps after PostgreSQL restore and
replays retained service-owned outboxes/publication records.

Event/business IDs remain stable while broker offsets are new. Consumers rely on
idempotency/Inbox semantics. Traffic opens only after critical event replay and
consumer-lag reconciliation succeed.

## 14. Security Redis

ADR-0041 defines one shared physical `security-redis` with separate service ACL
identities/namespaces:

```text
1 primary
2 replicas
3 Sentinel voters
TLS
noeviction
```

Approved v1 uses include semantic security quotas and BFF session state. It is
not a cross-service business cache and is not a durable source of truth.

Raw PII/IDs are prohibited in quota keys; HMAC pseudonyms are used. Service
business caches, when needed, remain independently owned and must not share
security state merely for convenience.

If security session and quota workloads compete materially, split the physical
Redis deployments before adopting Redis Cluster complexity.

## 15. Redis coding/ownership rules

For any current or future Redis-backed cache/state:

- ownership belongs to one service/security subsystem; cross-service shared business caches are prohibited;
- keys use explicit stable namespaces and bounded key material; raw PII/IDs are not used when a pseudonymous key is required;
- cache/state TTL is explicit where expiry is part of the data lifecycle, but TTL must not become an authoritative security reset unless an ADR explicitly defines it;
- cache-miss behavior is defined and correct; a miss never fabricates authorization/business truth;
- cache stampedes are controlled with bounded concurrency/coalescing or another reviewed strategy where regeneration is expensive;
- very large objects/collections are prohibited without measured memory/latency justification;
- sensitive Redis values require explicit encryption/retention review;
- Redis is not a business source of truth unless a dedicated ADR explicitly changes that rule;
- distributed locks require a proven need and fencing; a lock alone does not establish correctness.

## 16. Redis failure semantics

Security quota evaluations use ADR-0041 + ADR-0054: 75ms, one attempt,
fail-closed, dual trusted clocks with <=2s skew, monotonic effective bucket time,
and no security budget reset based solely on Redis TTL expiry. Session behavior
follows BFF session security and cannot invent a stateless fallback that exposes
tokens to the browser.

Distributed locks require a proven need plus fencing; Redis locks are not a
substitute for PostgreSQL transaction/uniqueness guarantees.
