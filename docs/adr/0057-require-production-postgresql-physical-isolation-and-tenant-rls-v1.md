# ADR-0057: Require Production PostgreSQL Physical Isolation and Tenant RLS v1

## Status

Accepted

## Date

2026-08-11

## Supersedes

This ADR supersedes ADR-0053 only where ADR-0053 allowed multiple production
service databases to share one physical CloudNativePG cluster. The mandatory
service-owned database, role, credential, Flyway, and no-cross-service-SQL
boundaries remain accepted.

ADR-0002's application/persistence tenant isolation remains mandatory. This ADR
promotes PostgreSQL Row-Level Security from optional defense in depth to a
production requirement for tenant-owned relational tables.

## Decision

### Production physical database isolation

Every independently deployable persistent microservice runs on its own
CloudNativePG cluster in production.

For each service:

```text
1 service
-> 1 dedicated PostgreSQL database
-> 1 dedicated CloudNativePG cluster
-> independent runtime/migration roles
-> independent WAL/PITR backup location and credentials
-> independent backup-encryption context/key
```

The existing three-instance synchronous-HA baseline applies to critical
production service clusters. Non-production environments may consolidate
physical clusters when service databases, credentials, and privilege boundaries
remain separate.

A backup or database-superuser compromise must therefore be scoped to one
service cluster rather than the entire platform database estate.

### Role separation

Runtime roles are:

```text
NOSUPERUSER
NOBYPASSRLS
not table/database owner
no CREATE ROLE
no cross-database CONNECT
```

Migration/owner roles are separate from runtime and are unavailable to normal
application pods. Routine human access never uses PostgreSQL superuser.

### Mandatory tenant RLS

Every tenant-owned table has:

- non-null `tenant_id`;
- `ENABLE ROW LEVEL SECURITY`;
- `FORCE ROW LEVEL SECURITY`;
- a restrictive tenant policy for `SELECT/UPDATE/DELETE`;
- `WITH CHECK` protection for `INSERT/UPDATE`;
- application-level tenant checks in addition to RLS.

The application sets a transaction-local tenant context only from the validated
security context. Repository operations that require tenant scope fail closed
when the tenant context is absent.

Global platform tables are explicit reviewed exceptions and cannot silently
omit tenant ownership.

RLS is defense in depth against application/query defects. It is **not** claimed
to defeat a PostgreSQL superuser or a role with `BYPASSRLS`; those principals
are controlled through physical service isolation and ADR-0060 privileged human
access.

### Backup isolation

Each service cluster writes WAL/base backups to an independent object prefix or
bucket with independent credentials and encryption context. Restore permissions
are least privilege and service scoped. Cross-service bulk backup download is
not granted to application teams by default.

## Verification Requirements

- runtime role cannot connect to another service database/cluster;
- runtime role is `NOSUPERUSER NOBYPASSRLS` and is not table owner;
- every tenant-owned table has forced RLS plus `USING`/`WITH CHECK` coverage;
- deliberately missing application `WHERE tenant_id` still cannot cross tenant
  in integration tests;
- missing tenant context fails closed;
- migration role is absent from runtime pods;
- backup credentials cannot read another service's backup namespace;
- restore/PITR is exercised independently per service;
- break-glass superuser use is JIT, audited, and time bounded.

## Consequences

Security and backup blast radius improve materially at the cost of additional
PostgreSQL pods, storage, backup streams, upgrades, and monitoring. CloudNativePG
keeps the operational model uniform so the isolation cost is predictable rather
than bespoke.
