# ADR 0002: Select the Initial Multi-Tenancy Model

## Status

Accepted

## Date

2026-08-05

## Context

The platform must support multiple organizations while allowing one person to
participate in more than one organization through one platform identity.

ADR-0001 intentionally blocked tenant-aware Identity Service persistence and
schema design until the multi-tenancy and data-isolation model was accepted.

The decision must define:

- the relationship between users and tenants
- the initial data-isolation strategy
- how an active tenant is selected and trusted
- which service owns tenant membership
- how platform-wide administration operates
- how the design may evolve toward stronger isolation

Authorization roles, permissions, and policy evaluation are related concerns,
but their ownership and detailed evaluation model require a separate ADR.

A platform-wide logical-deletion, retention, erasure, restoration, and legal
hold policy also requires a separate ADR.

## Decision

### Global user identity

A user is a global platform identity and is not owned by a single tenant.

One user may be a member of multiple tenants through separate tenant
memberships.

The conceptual relationship is:

```text
User
  └── TenantMembership
        └── Tenant
```

The User identifier is an immutable generated identifier such as a UUID.

Email addresses are login and communication attributes. They must not be used
as immutable database identifiers because an email address may change.

Email normalization, uniqueness, verification, and account-recovery behavior
will be defined by Identity Service implementation decisions.

### Initial isolation model

The initial persistence model is:

```text
Service-owned PostgreSQL Database
Shared Schema across Tenants
Tenant-aware Tables
```

Each microservice continues to own its independent database and credentials.
The word `shared` applies only to tenants within that service-owned database;
it does not permit multiple services to share one database.

Every tenant-owned row must contain a non-null `tenant_id`, unless an accepted
design explicitly classifies the data as global platform data.

Tenant-owned uniqueness constraints must normally include `tenant_id`.

Examples include:

```text
UNIQUE (tenant_id, external_reference)
UNIQUE (tenant_id, normalized_name)
```

Tenant filtering must be enforced at application and persistence boundaries.
A caller must not be able to supply an arbitrary tenant identifier that
bypasses the authenticated tenant context.

PostgreSQL Row-Level Security may later be introduced as defense in depth. It
does not replace application-level tenant isolation.

### Active tenant selection

Authentication establishes the global user identity.

When a user belongs to more than one tenant, the user selects an active tenant.
The Identity Service verifies that the user has an active membership in that
tenant before issuing an active-tenant security context.

A tenant-scoped token must contain trusted claims equivalent to:

```json
{
  "sub": "user-id",
  "tenant_id": "tenant-id",
  "membership_id": "membership-id"
}
```

The exact token format and complete claim set will be defined separately.

Backend services and BFF components derive the active tenant from the validated
security context.

An untrusted request header such as `X-Tenant-Id` must not independently define
the active tenant. A header may be used for routing or correlation only when
its value is validated against the authenticated security context.

Tenant-less operations are allowed only for explicitly defined use cases such
as:

- login
- tenant discovery
- tenant selection
- invitation acceptance
- platform administration

### Identity ownership boundaries

The Identity Service initially owns:

- User
- Tenant
- TenantMembership
- membership lifecycle state

The initial membership lifecycle states are:

```text
INVITED
ACTIVE
SUSPENDED
REMOVED
```

The Identity Service does not initially own the complete Role and Permission
model.

Authorization service boundaries, role ownership, permission ownership, and
policy evaluation will be defined in a separate ADR.

The future authorization model must support tenant-scoped permission exceptions
attached to `TenantMembership`, including:

- permissions granted directly to a membership
- permissions denied directly to a membership
- removal of an effective permission inherited from a role
- default-deny evaluation

Explicit deny takes precedence over direct grants and role-derived permissions.

A tenant-specific direct permission exception must not be attached to the
global User.

### Platform administration

Platform administrators are not represented as members of a synthetic system
tenant.

Platform-wide administrative access is represented by explicit and limited
platform capabilities outside tenant membership.

Every platform-administration operation must be authenticated, authorized, and
audited.

Platform capabilities must not implicitly grant tenant business permissions.
Any tenant access performed by a platform administrator must be explicit and
auditable.

### Tenant lifecycle and deletion

The initial Tenant lifecycle states are:

```text
PROVISIONING
ACTIVE
SUSPENDED
DEACTIVATED
DELETION_PENDING
DELETED
```

Tenant deletion is logical by default.

Physical deletion, anonymization, retention periods, legal holds, restoration,
and data-erasure workflows will be governed by a platform-wide Logical
Deletion and Data Retention ADR.

A subsystem may physically delete data only when an accepted ADR documents the
legal, regulatory, security, or business requirement and the required
safeguards.

## Consequences

### Positive

- A person can use one platform identity across multiple organizations.
- Tenant membership is represented explicitly.
- The initial persistence model remains operationally simple.
- Database migrations remain centralized.
- Connection-pool and provisioning complexity remain manageable.
- Tenant context has a trusted authenticated source.
- Authorization can evolve independently from identity ownership.
- The design permits later movement toward stronger tenant isolation.

### Negative

- Shared tables increase the impact of a missing tenant predicate.
- Application and persistence code require strict tenant-context discipline.
- Large tenants may create noisy-neighbor risks.
- Tenant-specific backup and restore are more complex than database-per-tenant.
- Stronger future isolation requires explicit migration tooling.
- Platform administration requires a separate capability and audit model.

### Required safeguards

Implementation must include:

- non-null `tenant_id` on tenant-owned records
- tenant-aware repository and query boundaries
- tests that deny cross-tenant access
- membership validation before tenant-context issuance
- audit events for tenant switching and platform administration
- no reliance on an arbitrary tenant header as the trust source
- explicit classification of global and tenant-owned data

## Alternatives considered

### Tenant-scoped user accounts

Rejected as the initial model because they create separate identities for the
same person in each organization and complicate login, recovery, invitations,
and cross-organization use.

### Schema per tenant

Not selected initially because it increases provisioning, migration, schema
versioning, and operational complexity.

It may be reconsidered for isolation-sensitive customers.

### Database per tenant

Not selected initially because it substantially increases connection
management, provisioning, migration, backup, observability, and operational
cost.

It may be reconsidered for regulated or high-isolation customers.

### Hybrid isolation

Not selected initially because supporting multiple isolation modes before the
first vertical slice adds unnecessary complexity.

The architecture must not prevent a future hybrid model.

### Synthetic system tenant

Rejected for platform administration because platform-wide capabilities are
not tenant business permissions and must not be hidden inside an artificial
tenant membership.

### Tenant selection from an untrusted header

Rejected because a caller-controlled header is not sufficient proof of tenant
membership or authorization.

## Rollback or migration considerations

This accepted ADR is immutable. A later decision must supersede it.

Before production tenant data exists, this decision may be superseded with
limited migration cost.

After production adoption, changing isolation models requires:

- an inventory of tenant-owned tables
- migration tooling that preserves tenant boundaries
- reconciliation and verification of migrated row counts
- tenant-specific backup and rollback procedures
- token and tenant-context compatibility planning
- staged migration with cross-tenant isolation tests
- explicit handling of global users and tenant memberships

Migration to a hybrid model must preserve stable identifiers for User, Tenant,
and TenantMembership.
