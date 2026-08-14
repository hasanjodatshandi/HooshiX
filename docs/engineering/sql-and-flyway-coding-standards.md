# SQL and Flyway Coding Standards

This document is the canonical SQL/Flyway implementation standard for mutable service relational persistence. Architecture and data-ownership decisions remain authoritative when they are stricter.

ADR-0040 defines one narrow exception: Compromised Password Service's embedded SQLite database is an **immutable, read-only, rebuildable reference-data artifact** built offline as a complete versioned dataset. It has no runtime schema migration or mutable business persistence. This exception is governed by ADR-0040 and `../architecture/services/compromised-password-service.md`; it does not authorize mutable SQLite business persistence or weaken any PostgreSQL/Flyway rule below.

## 1. Naming and formatting

For PostgreSQL mutable service persistence:

- PostgreSQL identifiers use lowercase `snake_case` and remain unquoted unless an unavoidable external compatibility constraint requires otherwise.
- Tables use singular domain nouns unless a bounded context has a documented established convention.
- Primary keys normally use `id`; references use `<concept>_id`.
- Constraint/index names are deterministic and reviewable: `pk_`, `fk_`, `uq_`, `ck_`, `ix_` prefixes.
- Explicit column lists are mandatory. `SELECT *` is prohibited in production application SQL.
- SQL formatting/linting uses one repository-approved PostgreSQL-aware formatter/linter such as SQLFluff, pinned in repository tooling.

ADR-0040 SQLite schema/query text is fixed by its dataset format/service contract, stays Infrastructure/offline-compiler owned, uses explicit columns and parameterized values, and must not be copied as a second general SQL style authority.

## 2. Durable schema rules

For mutable relational business persistence:

- Constraints enforce invariants that the owning database can safely own; application validation does not replace durable constraints.
- Instants use timezone-aware PostgreSQL types and unambiguous names such as `created_at`/`updated_at`.
- Money uses integer minor units or documented fixed precision; floating-point monetary storage/calculation is prohibited.
- JSON/JSONB is used for genuinely flexible/document-shaped data, not to avoid relational modeling.
- Tenant-owned tables follow current forced-RLS requirements and service-owned role boundaries.

The ADR-0040 dataset contains no tenant/subject-owned state and is rebuilt as a complete immutable artifact. Its internal schema constraints validate reference-data format only; they are not application business-state transaction authority.

### Tenant RLS context and pooled connections

Production tenant context is transaction-local and derived only from validated authenticated context. With pooled connections, application code MUST NOT use session-scoped `SET` for tenant identity because a reused connection can leak context across requests/tenants.

The canonical pattern is a parameterized transaction-local setting such as:

```sql
SELECT set_config('app.tenant_id', ?, true);
```

where the final `true` makes the value local to the current transaction. Equivalent reviewed mechanisms are allowed only when they prove the same transaction-local/no-pool-leakage property. Raw string concatenation into `SET` statements is prohibited.

RLS policies read the trusted setting with fail-closed missing-context semantics, for example through `current_setting('app.tenant_id', true)` plus the required type/validity checks. Repository/service code must establish tenant context before tenant-owned queries in the same transaction; absence, malformed context, or transaction loss must not broaden access.

Tests MUST reuse pooled connections across different tenants and prove that tenant context never survives commit/rollback into the next borrower. Forced RLS remains enabled and runtime roles remain non-owner `NOSUPERUSER NOBYPASSRLS`.

ADR-0040 global hash reference data has no tenant context and cannot be used to store tenant rows; RLS non-applicability there does not create a general exemption.

## 3. Flyway and schema evolution

For mutable service relational persistence:

- Flyway is the only schema-change authority; Hibernate is `ddl-auto=validate`.
- Applied migrations are immutable. Corrections use a new migration.
- Zero-downtime change follows expand -> deploy compatible readers/writers -> bounded backfill -> verify -> contract.
- Data backfills are resumable, bounded, observable, rate/concurrency controlled, and do not hide long blocking work inside ordinary application startup.
- Large-table index creation uses the safest supported online/concurrent PostgreSQL strategy when required by measured table size/availability constraints.
- Destructive contraction requires evidence that old application versions and old data usage are absent and that rollback/restore implications are understood.
- A migration must not depend on nondeterministic external network calls.

ADR-0040 does not run SQLite migrations in production. A format/schema change creates and validates a new complete immutable SQLite dataset artifact offline. Runtime switches only through reviewed deployment of a compatible version; in-place DDL/data mutation is prohibited.

## 4. Query rules

- Every multi-row production query has deterministic pagination, an explicit hard bound, or a proven aggregate-sized upper limit.
- Pagination ordering is deterministic and backed by suitable indexes.
- Performance-sensitive queries require representative cardinality and `EXPLAIN (ANALYZE, BUFFERS)` or equivalent datastore-specific plan/measurement evidence in a safe environment.
- N+1 behavior is prohibited.
- Native SQL is parameterized and encapsulated in Infrastructure query/persistence adapters.
- Domain/Application code never contains database-specific SQL.

ADR-0040 lookup is a fixed parameterized SQLite prefix query with build-time `<=2048` prefix cardinality and `<=128 KiB` response compatibility. Normal runtime does not full-scan the dataset, truncate results, dynamically build SQL, or load the corpus into application heap.

## 5. JPA versus jOOQ/JDBC

For normal mutable service persistence, JPA/Hibernate is suitable for aggregate persistence and ordinary bounded queries. Prefer a dedicated jOOQ/JDBC adapter when one or more of these are true:

- dynamic composable SQL is central to the use case;
- recursive CTEs, advanced window functions, set-based bulk logic, or PostgreSQL-specific features dominate;
- repeated native result mapping has become a maintenance boundary;
- measured performance requires SQL-level control that JPA cannot provide cleanly;
- query shape/plan stability is more important than ORM convenience.

The adapter requires integration tests and representative query-plan/performance evidence. Choosing jOOQ/JDBC never changes database ownership.

ADR-0040 uses a direct Xerial JDBC Infrastructure adapter only because the read-only SQLite file is its approved local reference format. No JPA/jOOQ business persistence model or cross-service SQLite model is created.

## 6. Transactions, locking, and work claims

For mutable service state:

- Application use cases or dedicated worker operations define transaction boundaries.
- Transactions remain short; external gRPC/HTTP/Kafka/Redis/provider calls are prohibited inside them.
- Retry occurs outside a failed transaction.
- Optimistic/pessimistic locking is selected from the invariant and measured contention model, not habit.
- `SKIP LOCKED` work claims use a short claim transaction; processing occurs after commit.
- Durable work queues define owner/lease or visibility timeout, attempt count, next-attempt time, exhaustion state, and recovery semantics.

Compromised Password runtime has no write transaction/work queue. Its SQLite adapter performs bounded read-only lookup. Identity invokes the service outside Identity DB transactions under the existing 900ms/one-attempt/no-retry contract.

## 7. Security

- Dynamic SQL identifiers are allow-listed/mapped, never accepted as raw user input.
- Values are parameterized; string concatenation for untrusted SQL values is prohibited.
- DB credentials are service-scoped, least privilege, and never shared across microservices.
- SQL/logging/telemetry does not expose bind values containing secrets or unreviewed PII.
- Migration roles and runtime roles remain distinct where current architecture requires it.
- Tenant RLS context is transaction-local, parameterized, fail closed when absent/malformed, and verified against pooled-connection reuse.

For ADR-0040 specifically, the database path/JDBC URI/query text/PRAGMA/extension/ATTACH target are server-owned, not request-controlled; runtime is read-only/query-only; extension loading and database attachment are prohibited; SHA-256 prefix/suffix/full-hash material is not logged.

## 8. Required verification

Applicable mutable relational persistence PR/release checks include:

- migration naming/order and Flyway validation;
- SQL lint/format check;
- migration integration test against the approved PostgreSQL major;
- RLS/role negative tests for tenant-owned data, including cross-tenant pooled-connection reuse after commit/rollback;
- query bound/index/plan evidence for changed critical queries;
- concurrency/locking/idempotency tests for work claims;
- expand/contract compatibility tests across supported rolling versions.

ADR-0040 implementation instead requires its dedicated checks: deterministic offline dataset compiler/schema/integrity/cardinality validation, read-only/query-only runtime, fixed parameterized lookup, path/URI/DDL/ATTACH/extension negatives, representative multi-million-row disk-backed load, Java/native dependency security and compatibility, immutable artifact recovery, and proof that failure never becomes a false clean-password result.
