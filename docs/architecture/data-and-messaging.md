# Data and Messaging Architecture — Current State

This is the implementation-facing data/messaging model. SQL/Flyway rules live in Engineering standards; exact versions live in Technology Baseline. Physical topology is profile-aware under ADR-0042.

## 1. Mutable service data ownership

Every independently deployable service with mutable relational business state owns:

- one distinct PostgreSQL database;
- independent runtime/migration roles and Flyway history;
- schemas/repositories/query adapters and connection budget;
- no cross-service SQL/model/credential access.

Physical placement:

- `production-single-server`: one shared physical CloudNativePG/PostgreSQL cluster containing distinct service databases;
- `production-ha`: dedicated physical clusters according to current database decisions.

Shared physical placement never permits shared schemas/roles/Flyway, cross-database joins/FKs/views/FDW/`dblink`, another service DB as integration API, or shared persistence models.

Tenant-owned tables use forced RLS + application enforcement. Runtime roles are non-owner `NOSUPERUSER NOBYPASSRLS`. Trusted tenant context is transaction-local and missing/malformed context fails closed.

## 2. Compromised Password immutable SQLite exception

ADR-0040 is the only current SQLite reference-data exception.

The service-local dataset is:

- official HIBP Pwned Passwords **SHA-1** corpus acquired offline;
- immutable/read-only/rebuildable;
- stored as 20-byte SHA-1 values with 20-bit prefix + positive occurrence count;
- queried by fixed parameterized prefix lookup;
- no runtime write/DDL/ATTACH/extension loading;
- no runtime HIBP/provider request;
- no User/Tenant/Contact/session/business state;
- no PostgreSQL/Flyway/WAL/PITR requirement for the dataset artifact.

SHA-1 is only the compromised-password corpus identifier. Password credentials remain Argon2id.

Production dataset freshness <=35 days and complete-corpus cardinality/serialized-response compatibility must be evidenced before readiness. Runtime never truncates a valid result into false clean-password outcome.

## 3. Reference Data immutable bundle

ADR-0041 Reference Data v1 has no PostgreSQL/SQLite/Redis/Kafka state.

Before the independent-service trigger, the approved immutable bundle may live in the owning deployable, initially BFF when needed. A one-journey need does not create a microservice.

Independent `reference-data-service` exists only after evidence of >=2 independent deployable consumers, independent release lifecycle, security boundary, scale/availability need, or independent ownership.

The bundle retains deterministic source/provenance/integrity/version/lifecycle rules in either deployment mode.

## 4. PostgreSQL profiles and connection budget

Single-server:

```text
1 physical CloudNativePG cluster / 1 PostgreSQL instance
separate DB/runtime/migration roles/Flyway per mutable service
forced RLS where applicable
one shared process/host/storage failure domain
```

Sum of application pool maxima stays <=70% of `max_connections`; >=30% remains for migration/backup/recovery/monitoring/admin/emergency work. Per-service ceilings prevent noisy-neighbor exhaustion.

HA uses current dedicated service-cluster topology and profile-specific failover/durability evidence.

## 5. Schema/query/transaction rules

For mutable relational state:

```text
expand -> compatible deploy -> bounded migrate/backfill -> verify -> contract
```

- Flyway only; executed migrations immutable.
- Explicit production column lists; no `SELECT *`.
- Multi-row queries deterministic/bounded.
- Critical queries require reviewed indexes/representative plans.
- Transactions short/explicit.
- No gRPC/HTTP/Kafka/Redis/provider I/O inside DB transaction.
- DB locks never held across remote I/O.
- Retries occur outside failed transaction.

Virtual Threads do not create database capacity.

## 6. Backup/restore/DR

Both profiles retain:

- PostgreSQL RPO <=5m target;
- platform cold-DR RTO <=4h target subject to measured evidence;
- continuous encrypted off-site WAL;
- daily online base backup;
- 35-day PITR;
- monthly retained artifact for 12 months;
- backup verification every cycle;
- monthly isolated restore;
- quarterly full cold DR.

Single-server service-specific recovery starts from isolated whole-cluster physical PITR, validates all service DB/Flyway/role/RLS/erasure state, then extracts/imports only the required service database under controlled maintenance. Unrelated current DBs are not destructively restored.

`pg_dump + cron` is not primary recovery.

Immutable ADR-0040/0041 reference artifacts recover through approved artifact rebuild/redeploy, not PostgreSQL PITR.

## 7. Kafka

Kafka is async integration transport, never business source of truth or ordinary request/reply.

Single-server:

```text
1 combined KRaft broker/controller
RF=1 / minISR=1
acks=all + idempotence
unclean leader election disabled
formal non-HA acceptance
```

HA retains RF3/minISR2 current topology.

Both profiles require TLS/auth/per-service principals/ACLs/quotas, Transactional Outbox for atomic state+event intent, at-least-once consumer assumption, Inbox/idempotency where needed, finite retry/DLQ, stable event identity, offsets after durable effect, and critical replay/dedup evidence for the recovery horizon.

Kafka cold DR rebuilds broker/config from GitOps then replays/reconstructs service-owned evidence.

## 8. Security Redis

Redis is limited to ephemeral security/session capabilities, not business truth.

Shared:

- TLS;
- per-owner ACL/key isolation;
- `noeviction`;
- fail-closed authoritative security behavior;
- pseudonymous keys where required;
- no raw subject/client identifiers in ordinary telemetry.

Single-server uses one instance + AOF `appendfsync everysec`; HA uses current Sentinel topology.

ADR-0024 owns quota behavior:

- exact `/32` IPv4 `/128` IPv6 hard client identity;
- separate `/24`/`/64` aggregate pressure;
- app/Redis time cross-check + wall-vs-monotonic common-clock guard;
- no security TTL reset;
- bounded cleanup;
- high-cardinality new-allocation guard before eviction/OOM;
- distinct fail-closed time/capacity unavailability.

If session/quota workloads materially interfere, split Redis workload/add capacity or move to HA before Redis Cluster complexity.

## 9. Messaging/telemetry interaction

Day-One telemetry under ADR-0044 does not change data authority:

- trace IDs are not event IDs/idempotency keys;
- baggage is not business/tenant/security context;
- Kafka payloads do not carry telemetry-only authority;
- telemetry exporter failure does not change DB/Outbox/Kafka business commit semantics;
- required audit/security evidence remains separately durable/off-host.

Observability IO/storage is included in the single-server complete-stack capacity test together with WAL/AOF/Kafka.

## 10. Verification

Applicable evidence includes:

- DB privilege/RLS/pool-reuse/Flyway/query/transaction negatives;
- pool/noisy-neighbor and PITR/recovery evidence;
- Kafka topology/TLS/ACL/Outbox/Inbox/replay tests;
- Redis TLS/ACL/noeviction/AOF or Sentinel evidence;
- quota exact/aggregate/common-clock/cardinality tests;
- HIBP SHA-1 dataset source/freshness/full-corpus/SQLite/read-only/no-runtime-provider tests;
- Reference Data trigger/bundle/service migration tests;
- PII/secret-safe persistence/events/telemetry and trace-non-authority tests.