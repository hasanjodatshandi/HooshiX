# ADR-0002: Current Multi-Tenancy Model

## Status

Accepted — current effective decision

## Date

2026-08-05; normalized to current-only documentation on 2026-08-13

## Decision

### Global identity and tenant membership

A user is a global platform identity and may belong to multiple tenants through distinct `TenantMembership` records:

```text
User
  -> TenantMembership
      -> Tenant
```

User, Tenant, and TenantMembership use immutable generated identifiers. Email and phone are mutable contact/login attributes and are never immutable database identity keys.

Identity owns User, Tenant, TenantMembership, membership lifecycle, authentication, and active-tenant selection. Authorization owns roles, permission assignments/evaluation, and authorization audit. The services do not share tables, ORM entities, databases, or domain models.

### Active tenant trust

Authentication establishes the global user identity. Tenant-scoped operations require an active membership selected/validated by Identity and represented in trusted authenticated context. The effective context includes stable user, tenant, membership, and session identity according to the current token/session contract.

A caller-controlled `X-Tenant-Id` or equivalent header never establishes tenant trust. Such a value may only be used after validation against authenticated context for a narrowly defined routing/correlation purpose.

Tenant-less operations are limited to explicitly defined flows such as login, tenant discovery/selection, invitation acceptance, account recovery where applicable, and authorized platform administration.

### Service-owned persistence and tenant isolation

Every relational microservice owns its own PostgreSQL database, credentials, Flyway history, and production CloudNativePG cluster. The word `shared` refers only to multiple tenants inside one service-owned database/schema; it never permits multiple services to share a database.

Every tenant-owned row contains non-null `tenant_id` unless explicitly classified as global platform data. Tenant-owned uniqueness normally includes `tenant_id`.

Production tenant-owned tables use PostgreSQL defense in depth:

- `ENABLE ROW LEVEL SECURITY`;
- `FORCE ROW LEVEL SECURITY`;
- runtime roles are `NOSUPERUSER NOBYPASSRLS`;
- runtime roles do not own tenant tables;
- application/persistence code still enforces tenant predicates/context explicitly.

RLS does not replace application tenant isolation and is not claimed to defend against a PostgreSQL superuser. Negative cross-tenant tests are mandatory at application, persistence, and policy layers.

### Tenant lifecycle

Current v1 Tenant lifecycle is:

```text
PROVISIONING
ACTIVE
SUSPENDED
DELETING
DELETED
```

Tenant creation commits Tenant, creator membership, lifecycle audit, and stable owner-provisioning outbox in one local Identity transaction without remote I/O. The creator becomes initial owner. Tenant remains `PROVISIONING` until Authorization idempotently acknowledges initial-owner provisioning; Identity then activates the tenant transactionally.

Tenant slug is immutable, canonical, globally unique including deleted tenants, and never reused. Current exact slug syntax/reserved-name rules live in ADR-0038 and `security-architecture.md`.

### Membership and tenant authorization boundary

Tenant roles/direct permission exceptions attach to TenantMembership rather than the global User. Authorization must always evaluate tenant permissions against the active tenant + membership. Platform-wide capabilities remain outside tenant roles and never silently imply tenant business access.

The last active tenant owner cannot be removed/demoted while the tenant remains active.

### Deletion, retention, and erasure

Tenant deletion is logical by default and follows the current deletion/retention/erasure/legal-hold model. Irreversible erasure/purge is explicit, authorized, idempotent, audited, and blocked by legal hold where applicable. Backup restore must replay current erasure/deletion evidence before normal traffic.

### Required safeguards

Implementation must include:

- explicit classification of global vs tenant-owned data;
- non-null `tenant_id` and tenant-aware uniqueness for tenant-owned records;
- trusted tenant context derived from authenticated state;
- application/persistence tenant enforcement plus forced production RLS;
- cross-tenant negative tests;
- membership validation before tenant-context issuance;
- audited tenant switching/platform administration/lifecycle operations;
- no cross-service database access or shared persistence model;
- no tenant ID supplied by a client treated as sufficient authorization.

## Rollback considerations

A rollback MUST preserve stable User/Tenant/TenantMembership identifiers, tenant ownership, slug non-reuse, forced-RLS protections, service database isolation, and trusted active-tenant semantics. Moving to a different tenant-isolation topology requires an explicit current migration decision with inventory, reconciliation, backup/restore, token/context compatibility, and cross-tenant test evidence.
