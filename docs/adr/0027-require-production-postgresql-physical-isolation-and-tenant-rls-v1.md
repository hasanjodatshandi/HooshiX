# ADR-0027: Production PostgreSQL Service Isolation and Tenant RLS v1

## Status

Accepted — current effective decision

## Date

2026-08-11; consolidated to current-only documentation on 2026-08-14

## Decision

### Service ownership is invariant

Every independently deployable microservice using PostgreSQL for mutable relational business persistence owns a distinct PostgreSQL database, independent credentials/roles, independent Flyway history, and independent release lifecycle.

```text
1 PostgreSQL-backed mutable-state service
-> 1 distinct PostgreSQL database
-> independent runtime + migration/owner roles
-> independent Flyway history
-> no cross-service SQL/model/credential access
```

Physical CloudNativePG placement is profile-specific:

- `production-single-server` under ADR-0042: one shared physical CloudNativePG cluster/one PostgreSQL instance contains the separate service databases;
- `production-ha`: one dedicated CloudNativePG cluster per mutable PostgreSQL service.

Physical consolidation in the single-server profile is an explicit availability/blast-radius trade-off. It does not permit shared schemas, shared roles, cross-database access, shared Flyway history, or shared domain/persistence models.

ADR-0040 Compromised Password Service's immutable, read-only, rebuildable SQLite reference dataset is not mutable PostgreSQL business persistence and is outside this PostgreSQL rule.

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

In the shared single-server instance, public/default privileges are explicitly revoked so creation or migration of one database cannot create access to another service database. Runtime and migration roles are scoped to their owning database only.

### Prohibited database integration

The following are prohibited as cross-service integration mechanisms in both profiles:

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

RLS is defense in depth against application/query defects. It is not claimed to defeat PostgreSQL superuser or `BYPASSRLS`; those principals are controlled through role separation, JIT privilege, and, in `production-ha`, dedicated physical clusters. In `production-single-server`, the operator explicitly accepts that PostgreSQL-superuser or physical-host compromise has a larger cross-service blast radius, so those privileges receive stricter access/audit controls.

ADR-0040's global compromised-password hash reference dataset has no tenant/subject linkage and is not a PostgreSQL tenant table; RLS does not apply to it.

### Backup and restore isolation

`production-ha` keeps an independent physical backup namespace/credentials/encryption context per service cluster.

`production-single-server` uses cluster-wide physical WAL/base backups because all service databases share one physical PostgreSQL cluster. Physical backup isolation per service is therefore not claimed. Service-specific recovery MUST still avoid destructive restoration of unrelated current databases: restore the shared physical cluster into an isolated recovery environment, validate it, then transfer only the required service database through the approved recovery procedure.

Application teams are not granted broad shared-cluster backup/recovery credentials. Recovery access is privileged, time-bounded, and audited.

ADR-0040 uses immutable artifact rebuild/redeploy evidence instead of PostgreSQL WAL/PITR restore evidence.

### Capacity and pools

`production-ha` keeps the per-service cluster connection budget.

`production-single-server` uses one global connection budget: sum of all application Hikari `maximumPoolSize` values across all service pods is capped at <=70% of shared PostgreSQL `max_connections`; >=30% remains for migrations, backup/recovery, administration, and emergency headroom. One service MUST NOT consume another service's reserved operational capacity. Per-service pool ceilings are measured and versioned.

## Verification requirements

For every production microservice using PostgreSQL for mutable relational business persistence:

- distinct production database;
- distinct runtime/migration credentials;
- runtime role cannot connect/access another service database;
- runtime role is `NOSUPERUSER NOBYPASSRLS`, not owner, and cannot create roles;
- every tenant-owned production PostgreSQL table has forced RLS + `USING`/`WITH CHECK` coverage;
- deliberately missing application tenant predicate still cannot cross tenant in integration tests;
- missing/malformed tenant context fails closed;
- pooled connections reused across tenants after commit/rollback do not retain prior tenant context;
- migration role/owner credentials are absent from runtime pods;
- no FK/FDW/dblink/view/grant/shared-model integration crosses service boundaries;
- pool budgets remain safe under the selected profile;
- break-glass/JIT privileged DB access is audited/time-bounded.

`production-ha` additionally verifies dedicated physical clusters and independent physical backup identities.

`production-single-server` verifies cross-database privilege negatives inside the shared instance, global connection-budget isolation, isolated whole-cluster PITR restore, service-specific recovery without destructive restoration of another current database, and explicit acceptance of shared process/host/storage failure domain.

ADR-0040 immutable SQLite reference data remains under its own read-only, rebuild/redeploy, dataset-integrity and no-cross-service-access gates.

## Rollback considerations

Rollback MUST NOT restore shared credentials/cross-service SQL, remove forced tenant RLS, restore session-scoped pooled tenant context, expose migration/owner roles to runtime pods, weaken WAL/PITR recovery, or generalize ADR-0040 into mutable SQLite persistence.

Physical consolidation is permitted only by `production-single-server` with its explicit shared-blast-radius and recovery controls. It MUST NOT be silently introduced into the HA profile.
