# SQL and Flyway Coding Standards

This document is the canonical SQL/Flyway implementation standard. Architecture and data-ownership decisions remain authoritative when they are stricter.

## 1. Naming and formatting

- PostgreSQL identifiers use lowercase `snake_case` and remain unquoted unless an unavoidable external compatibility constraint requires otherwise.
- Tables use singular domain nouns unless a bounded context has a documented established convention.
- Primary keys normally use `id`; references use `<concept>_id`.
- Constraint/index names are deterministic and reviewable: `pk_`, `fk_`, `uq_`, `ck_`, `ix_` prefixes.
- Explicit column lists are mandatory. `SELECT *` is prohibited in production application SQL.
- SQL formatting/linting uses one repository-approved PostgreSQL-aware formatter/linter such as SQLFluff, pinned in repository tooling.

## 2. Durable schema rules

- Constraints enforce invariants that PostgreSQL can safely own; application validation does not replace durable constraints.
- Instants use timezone-aware PostgreSQL types and unambiguous names such as `created_at`/`updated_at`.
- Money uses integer minor units or documented fixed precision; floating-point monetary storage/calculation is prohibited.
- JSON/JSONB is used for genuinely flexible/document-shaped data, not to avoid relational modeling.
- Tenant-owned tables follow current forced-RLS requirements and service-owned role boundaries.

### Tenant RLS context and pooled connections

Production tenant context is transaction-local and derived only from validated authenticated context. With pooled connections, application code MUST NOT use session-scoped `SET` for tenant identity because a reused connection can leak context across requests/tenants.

The canonical pattern is a parameterized transaction-local setting such as:

```sql
SELECT set_config('app.tenant_id', ?, true);
```

where the final `true` makes the value local to the current transaction. Equivalent reviewed mechanisms are allowed only when they prove the same transaction-local/no-pool-leakage property. Raw string concatenation into `SET` statements is prohibited.

RLS policies read the trusted setting with fail-closed missing-context semantics, for example through `current_setting('app.tenant_id', true)` plus the required type/validity checks. Repository/service code must establish tenant context before tenant-owned queries in the same transaction; absence, malformed context, or transaction loss must not broaden access.

Tests MUST reuse pooled connections across different tenants and prove that tenant context never survives commit/rollback into the next borrower. Forced RLS remains enabled and runtime roles remain non-owner `NOSUPERUSER NOBYPASSRLS`.

## 3. Flyway and schema evolution

- Flyway is the only schema-change authority; Hibernate is `ddl-auto=validate`.
- Applied migrations are immutable. Corrections use a new migration.
- Zero-downtime change follows expand -> deploy compatible readers/writers -> bounded backfill -> verify -> contract.
- Data backfills are resumable, bounded, observable, rate/concurrency controlled, and do not hide long blocking work inside ordinary application startup.
- Large-table index creation uses the safest supported online/concurrent PostgreSQL strategy when required by measured table size/availability constraints.
- Destructive contraction requires evidence that old application versions and old data usage are absent and that rollback/restore implications are understood.
- A migration must not depend on nondeterministic external network calls.

## 4. Query rules

- Every multi-row query has deterministic pagination, an explicit hard bound, or a proven aggregate-sized upper limit.
- Pagination ordering is deterministic and backed by suitable indexes.
- Performance-sensitive queries require representative cardinality and `EXPLAIN (ANALYZE, BUFFERS)` or equivalent plan evidence in a safe environment.
- N+1 behavior is prohibited.
- Native SQL is parameterized and encapsulated in Infrastructure query/persistence adapters.
- Domain/Application code never contains database-specific SQL.

## 5. JPA versus jOOQ/JDBC

JPA/Hibernate is suitable for aggregate persistence and ordinary bounded queries. Prefer a dedicated jOOQ/JDBC adapter when one or more of these are true:

- dynamic composable SQL is central to the use case;
- recursive CTEs, advanced window functions, set-based bulk logic, or PostgreSQL-specific features dominate;
- repeated native result mapping has become a maintenance boundary;
- measured performance requires SQL-level control that JPA cannot provide cleanly;
- query shape/plan stability is more important than ORM convenience.

The adapter requires integration tests and representative query-plan/performance evidence. Choosing jOOQ/JDBC never changes database ownership.

## 6. Transactions, locking, and work claims

- Application use cases or dedicated worker operations define transaction boundaries.
- Transactions remain short; external gRPC/HTTP/Kafka/Redis/provider calls are prohibited inside them.
- Retry occurs outside a failed transaction.
- Optimistic/pessimistic locking is selected from the invariant and measured contention model, not habit.
- `SKIP LOCKED` work claims use a short claim transaction; processing occurs after commit.
- Durable work queues define owner/lease or visibility timeout, attempt count, next-attempt time, exhaustion state, and recovery semantics.

## 7. Security

- Dynamic SQL identifiers are allow-listed/mapped, never accepted as raw user input.
- Values are parameterized; string concatenation for untrusted SQL values is prohibited.
- DB credentials are service-scoped, least privilege, and never shared across microservices.
- SQL/logging/telemetry does not expose bind values containing secrets or unreviewed PII.
- Migration roles and runtime roles remain distinct where current architecture requires it.
- Tenant RLS context is transaction-local, parameterized, fail closed when absent/malformed, and verified against pooled-connection reuse.

## 8. Required verification

Applicable PR/release checks include:

- migration naming/order and Flyway validation;
- SQL lint/format check;
- migration integration test against the approved PostgreSQL major;
- RLS/role negative tests for tenant-owned data, including cross-tenant pooled-connection reuse after commit/rollback;
- query bound/index/plan evidence for changed critical queries;
- concurrency/locking/idempotency tests for work claims;
- expand/contract compatibility tests across supported rolling versions.
