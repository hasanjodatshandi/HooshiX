# SQL and Flyway Coding Standards

This is the canonical SQL/Flyway implementation standard for mutable service relational persistence. Architecture/data ownership remains authoritative when stricter.

ADR-0040 defines one narrow exception: Compromised Password uses an immutable, read-only, rebuildable SQLite reference artifact built offline from the approved HIBP Pwned Passwords SHA-1 corpus. It has no runtime schema migration or mutable business persistence and does not authorize general mutable SQLite use.

## 1. PostgreSQL naming/formatting

- lowercase `snake_case`; avoid quoted identifiers;
- deterministic PK/FK/UQ/CK/index names;
- explicit column lists; production `SELECT *` prohibited;
- one repository-approved PostgreSQL-aware formatter/linter;
- constraints enforce database-owned invariants;
- timezone-aware instant types;
- no floating-point money;
- JSONB only for genuinely flexible/document-shaped data;
- tenant tables follow forced-RLS/role rules.

## 2. Tenant RLS context

Trusted tenant context is transaction-local and comes only from validated authenticated state. Session-scoped `SET` on pooled connections is prohibited.

Canonical pattern:

```sql
SELECT set_config('app.tenant_id', ?, true);
```

Equivalent mechanisms require the same parameterized transaction-local/fail-closed/no-pool-leak property.

Runtime roles are non-owner `NOSUPERUSER NOBYPASSRLS`. Tests reuse pooled connections across tenants after commit/rollback and prove no context leakage.

ADR-0040 reference data is global/non-tenant and does not create a general RLS exemption.

## 3. Flyway/schema evolution

For mutable service persistence:

- Flyway is sole schema-change authority; Hibernate `ddl-auto=validate`;
- applied migrations immutable;
- evolution: expand -> compatible deploy -> bounded migrate/backfill -> verify -> contract;
- backfills resumable/bounded/observable/rate-controlled;
- critical index creation uses safest supported strategy for measured table/availability needs;
- destructive contraction requires rollout/rollback/restore evidence;
- migration does not depend on external network calls.

ADR-0040 has no production SQLite migration. A schema/format change builds a new complete immutable artifact offline and deploys only after compatibility validation.

## 4. Query rules

- multi-row production queries have deterministic pagination/hard/proven aggregate bound;
- pagination ordering is deterministic/index-supported;
- critical queries have representative plan/cardinality evidence;
- N+1 prohibited;
- native SQL parameterized and Infrastructure-owned;
- Domain/Application contains no datastore-specific SQL.

### Compromised Password fixed lookup

ADR-0040 SQLite lookup is one fixed parameterized query by 20-bit prefix over a 20-byte SHA-1 BLOB + positive occurrence count.

The complete approved HIBP corpus is measured during release. A versioned maximum prefix-cardinality/serialized-response compatibility bound is selected from observed data plus safety margin. Build fails if the corpus exceeds it. Runtime MUST NOT truncate a valid result because truncation can create false clean-password output.

Do not copy an old fixed `2048`/`128 KiB` assumption into implementation unless current complete-corpus evidence independently selects those exact limits.

## 5. JPA vs jOOQ/JDBC

JPA/Hibernate is suitable for aggregate persistence/ordinary bounded queries. Use jOOQ/JDBC when advanced/set-based/PostgreSQL-specific query control or measured performance justifies it.

Choosing a query technology never changes data ownership.

ADR-0040 uses Xerial JDBC only for the approved immutable read-only SQLite format. It creates no JPA/jOOQ business-persistence model.

## 6. Transactions/locking/work claims

For mutable business state:

- use cases/workers define transaction boundaries;
- transactions short;
- external gRPC/HTTP/Kafka/Redis/provider I/O prohibited inside DB transactions;
- DB locks never held across remote I/O;
- retry outside failed transaction;
- locking selected from invariant + measured contention;
- `SKIP LOCKED` claims use short claim transaction and process after commit;
- durable work queues define lease/attempt/retry/exhaustion/recovery semantics.

Compromised Password has no write transaction/work queue. Identity calls it outside Identity DB transactions under current one-attempt/no-retry contract.

## 7. Security

- dynamic identifiers allow-listed/mapped;
- values parameterized;
- service-scoped least-privilege DB credentials;
- SQL/log/metric/trace does not expose sensitive bind values;
- migration/runtime roles distinct;
- RLS context parameterized/transaction-local/fail-closed.

ADR-0040 specifically requires:

- dataset path/JDBC URI/query/PRAGMA/extension/ATTACH target server-owned;
- runtime read-only/query-only;
- no write/DDL/ATTACH/extension loading;
- SHA-1 prefix/suffix/full screening digest never logged/traced/labeled;
- SHA-1 is not credential storage; Argon2id remains password verifier/storage authority;
- no runtime HIBP/provider network lookup.

## 8. Verification

Mutable PostgreSQL changes use applicable Flyway/lint/integration/RLS/role/pool/query-plan/concurrency/expand-contract tests.

ADR-0040 instead requires:

- official HIBP SHA-1 source/provenance and complete-corpus evidence;
- deterministic offline build/schema/integrity/count validation;
- positive-count/padding-zero rejection;
- measured full-corpus prefix-cardinality/serialized-size compatibility bound;
- read-only/query-only fixed lookup and path/DDL/ATTACH/extension negatives;
- complete-corpus disk-backed load evidence;
- Java/native dependency security/compatibility;
- <=35-day production dataset readiness age;
- immutable artifact recovery;
- proof that malformed/stale/corrupt/oversized failure never becomes a false clean-password result.