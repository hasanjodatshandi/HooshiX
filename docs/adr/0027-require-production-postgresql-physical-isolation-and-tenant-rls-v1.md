# ADR-0027: Production PostgreSQL Service Isolation and Tenant RLS v1

## Status

Accepted — current effective decision

## Date

2026-08-11; consolidated to current-only documentation on 2026-08-14

## Decision

### One service -> one database -> one production cluster

Every independently deployable microservice using PostgreSQL for mutable relational business persistence owns a distinct PostgreSQL database, independent credentials/roles, independent Flyway history, and a dedicated CloudNativePG cluster in production.

```text
1 PostgreSQL-backed mutable-state service
-> 1 dedicated PostgreSQL database
-> 1 dedicated production CloudNativePG cluster
-> independent runtime + migration/owner roles
-> independent WAL/PITR backup namespace and credentials
-> independent backup encryption context/key
```

A service may use multiple schemas inside its own database when technically justified; no schema/database/role is shared with another microservice. Non-production may consolidate physical clusters only while preserving separate databases, credentials, privileges, Flyway histories, and ownership.

Critical PostgreSQL service clusters use the current three-instance synchronous required-durability baseline. Fleet automation/restore/upgrade policy is defined by ADR-0034/ADR-0037.

ADR-0040 Compromised Password Service's immutable, read-only, rebuildable SQLite reference dataset is not mutable PostgreSQL business persistence and is outside this PostgreSQL/CloudNativePG rule. The exception does not authorize mutable SQLite persistence.

### Role and privilege separation

Production PostgreSQL runtime roles are:

```text
NOSUPERUSER
NOBYPASSRLS
not database/table owner
no CREATE ROLE
no cross-service database CONNECT/object privilege
```

Migration/owner roles are separate from runtime and unavailable to normal application pods. Routine human access never uses PostgreSQL superuser; privileged access follows current JIT production-access controls.

Public/default privileges are reviewed/revoked so database creation cannot accidentally create cross-service access.

### Prohibited database integration

The following are prohibited as cross-service integration mechanisms:

- cross-service SQL/cross-database joins;
- cross-service foreign keys;
- direct reads/writes to another service database;
- shared ORM/JPA entities or jOOQ-generated database models;
- database views/FDWs/dblink/logical replication/grants used as undeclared service APIs;
- distributed business transactions spanning service databases;
- shared application database credentials.

Cross-bounded-context integration uses reviewed gRPC/Protobuf or Kafka/Protobuf contracts plus current outbox/idempotency rules.

ADR-0040's SQLite file is service-local and MUST NOT be read directly by another service or used as an integration database.

### Mandatory tenant RLS

Every tenant-owned production PostgreSQL table has:

- non-null `tenant_id`;
- `ENABLE ROW LEVEL SECURITY`;
- `FORCE ROW LEVEL SECURITY`;
- restrictive tenant `USING` policy for `SELECT/UPDATE/DELETE`;
- `WITH CHECK` for `INSERT/UPDATE`;
- application-level tenant validation/predicates in addition to RLS.

The application sets tenant context only from validated authenticated context and only with transaction-local semantics. On pooled connections, session-scoped tenant `SET` is prohibited. The canonical implementation uses a parameterized transaction-local setting such as `SELECT set_config('app.tenant_id', ?, true)` or an equivalent reviewed mechanism proving the same no-pool-leakage property. Missing/malformed tenant context fails closed. Cross-tenant tests MUST reuse pooled connections across commit/rollback boundaries and prove context cannot survive into a later borrower.

Tenant-scoped repository operations fail closed when required tenant context is absent. Raw string concatenation into tenant-context SQL is prohibited.

Global platform PostgreSQL tables are explicit reviewed exceptions; a table cannot silently omit tenant ownership merely for convenience.

RLS is defense in depth against application/query defects. It is not claimed to defeat PostgreSQL superuser or `BYPASSRLS`; those principals are controlled through physical service isolation and privileged-access policy.

ADR-0040's global compromised-password hash reference dataset has no tenant/subject linkage and is not a PostgreSQL tenant table; RLS does not apply to it.

### Backup and restore isolation

Each PostgreSQL service cluster writes WAL/base backups to an independent object prefix/bucket with independent credentials/encryption context. Restore permissions are least privilege and service scoped. Cross-service bulk backup access is not granted to application teams by default.

Recovery must demonstrate restoration of one PostgreSQL service without destructively restoring another service. Monthly PostgreSQL restore evidence and production upgrade/rollback safety follow ADR-0037.

ADR-0040 uses immutable artifact rebuild/redeploy evidence instead of PostgreSQL WAL/PITR restore evidence.

### Capacity and pools

Per-service application pool/HPA maxima remain inside the PostgreSQL service cluster connection budget. Aggregate application Hikari `maximumPoolSize` across production pods is capped at <=70% of PostgreSQL `max_connections`; >=30% remains for failover, replication, migrations, administration, and emergency headroom unless a later measured current decision changes the budget.

## Verification requirements

For every production microservice using PostgreSQL for mutable relational business persistence:

- distinct production database + CloudNativePG cluster;
- distinct runtime/migration credentials;
- runtime role cannot connect/access another service DB/cluster;
- runtime role is `NOSUPERUSER NOBYPASSRLS`, not owner, and cannot create roles;
- every tenant-owned production PostgreSQL table has forced RLS + `USING`/`WITH CHECK` coverage;
- deliberately missing application tenant predicate still cannot cross tenant in integration tests;
- missing/malformed tenant context fails closed;
- pooled connections reused across tenants after commit/rollback do not retain prior tenant context;
- migration role/owner credentials are absent from runtime pods;
- no FK/FDW/dblink/view/grant/shared-model integration crosses service boundaries;
- backup credentials cannot read another service backup namespace;
- independent service restore/PITR evidence;
- pool/HPA connection budgets remain safe during normal scale and failover;
- break-glass/JIT privileged DB access is audited/time-bounded.

ADR-0040 immutable SQLite reference data is verified under its own read-only, rebuild/redeploy, dataset-integrity and no-cross-service-access gates rather than by fabricating a CloudNativePG requirement.

## Rollback considerations

Rollback MUST NOT reconsolidate production PostgreSQL service databases/clusters, restore shared credentials/cross-service SQL, remove forced tenant RLS, restore session-scoped pooled tenant context, expose migration/owner roles to runtime pods, weaken backup isolation, or generalize ADR-0040 into mutable SQLite persistence. Physical/data migration remains forward and compatibility-aware with validation and no permanent cross-database bridge.