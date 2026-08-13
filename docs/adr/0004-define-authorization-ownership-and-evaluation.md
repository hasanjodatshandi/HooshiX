# ADR 0004: Define Authorization Ownership and Permission Evaluation

## Status

Accepted

## Date

2026-08-05

## Context

ADR-0002 defines global users and tenant-scoped memberships. It intentionally
defers Role, Permission, and authorization ownership.

The platform must support:

- multiple roles for one tenant membership
- permissions inherited from roles
- permissions granted directly to one membership
- permissions denied directly to remove an inherited capability
- platform-wide capabilities outside tenant roles
- final enforcement inside the service that owns the protected resource
- authorization without a mandatory network call for every request

Authorization must remain separate from identity ownership. The Identity
Service establishes who the user is, which tenant membership is active, and
whether that membership is active. Authorization determines what that active
membership may do.

The model must also avoid centralizing domain rules in a generic authorization
engine. A permission to request an operation does not override aggregate state,
tenant ownership, invariants, legal holds, or other business rules enforced by
the resource-owning bounded context.

## Decision

### Service ownership

The `authorization-service` owns:

- Role
- RolePermission
- MembershipRole
- MembershipPermissionGrant
- MembershipPermissionDeny
- authorization policy versioning
- authorization assignment audit records

The Identity Service continues to own:

- User
- Tenant
- TenantMembership
- membership lifecycle state
- authentication and active-tenant selection

The Authorization Service references `tenant_id` and `membership_id` as stable
external identifiers. It must not share ORM entities, tables, or database
access with the Identity Service.

The Authorization Service owns its independent database and credentials. Its
tenant-owned records follow ADR-0002 and its deletion behavior follows
ADR-0003.

### Tenant-scoped role assignment

Roles are assigned to `TenantMembership`, not directly to the global User.

One membership may have multiple roles.

The conceptual model is:

```text
TenantMembership
├── MembershipRole
│   └── Role
│       └── RolePermission
├── MembershipPermissionGrant
└── MembershipPermissionDeny
```

A role belongs to one tenant scope. A role from one tenant must never be
assigned to a membership in another tenant.

Role-to-role inheritance is not supported initially. Multiple roles provide
composition without introducing an inheritance graph, cycle detection, or
ambiguous precedence.

### Permission ownership and keys

Each resource-owning bounded context owns the meaning of its permissions.

Permission keys use a stable namespace:

```text
<bounded-context>.<resource>.<action>
```

Examples include:

```text
identity.user.read
identity.user.invite
billing.invoice.read
billing.invoice.refund
subscription.plan.change
```

The bounded context that owns a permission defines:

- the protected operation
- the resource and tenant checks
- the domain conditions that still apply
- whether the operation requires elevated freshness or audit
- the lifecycle of the permission key

The Authorization Service stores and distributes permission identifiers and
assignments. It must not redefine the domain meaning of a permission.

A published permission key is an immutable technical contract. Changing its
meaning incompatibly requires a new permission key and a migration plan.

Stored wildcard permission assignments such as `billing.*` or `*` are
prohibited initially. Assignments use exact permission keys.

### Role types

Roles have one of these types:

```text
SYSTEM
CUSTOM
```

`SYSTEM` roles are platform-defined tenant roles with stable keys and versioned
permission sets. Tenants may assign them but may not silently change their
meaning.

`CUSTOM` roles are created and managed within one tenant.

Role display names may change. Stable Role identifiers and SYSTEM role keys
must not be reused.

Role deletion is logical according to ADR-0003. A deleted or inactive role must
not contribute permissions.

### Role permissions

Role permissions are grants only.

A role does not contain deny rules. Tenant-specific exceptions belong to the
membership through `MembershipPermissionDeny`.

This keeps shared role definitions understandable and makes exceptions visible
at the membership boundary.

### Direct membership grants and denies

A permission may be granted directly to a membership through
`MembershipPermissionGrant`.

A permission may be denied directly to a membership through
`MembershipPermissionDeny`.

Direct grants and denies must record:

- tenant identifier
- membership identifier
- permission key
- reason
- assigning actor
- creation time
- optional validity interval
- revocation or logical-deletion metadata

A direct tenant permission must never be attached to the global User.

### Permission evaluation

The effective permission order is:

```text
Explicit Membership Deny
    >
Explicit Membership Grant
    >
Role-derived Grant
    >
Default Deny
```

For one exact permission key:

1. If an active membership deny exists, access is denied.
2. Otherwise, if an active direct membership grant exists, access is granted.
3. Otherwise, if any active assigned role grants the permission, access is
   granted.
4. Otherwise, access is denied.

Default deny is mandatory.

An explicit deny overrides every direct grant and every role-derived grant for
the same permission.

Expired, revoked, deleted, suspended, or otherwise inactive assignments do not
participate in evaluation.

The evaluator must use the active `tenant_id` and `membership_id`. It must not
evaluate tenant permissions against the global User alone.

### Membership lifecycle

Authorization is valid only for an active TenantMembership.

An invited, suspended, removed, deleted, or otherwise inactive membership has
no tenant permissions, regardless of stored role or direct assignments.

Identity membership state changes must invalidate or supersede authorization
contexts promptly.

The exact propagation, policy snapshot, caching, freshness, and revocation
protocol will be defined in a separate ADR.

### Resource-service enforcement

The service that owns the protected resource performs the final authorization
enforcement.

The BFF may perform an early check for user experience or request rejection,
but a BFF decision is not the final security boundary.

A resource-owning service must verify:

- authenticated end-user context
- authenticated workload identity
- active tenant context
- the required permission
- ownership of the resource by the active tenant
- domain invariants and current aggregate state

A permission grant allows the caller to request an operation. It does not
guarantee that the operation is valid.

For example, `billing.invoice.refund` does not permit refunding an invoice from
another tenant or an invoice whose state prohibits refund.

### Runtime authorization context

A synchronous call to the Authorization Service is not mandatory for every
business request.

Services may enforce authorization from a locally verifiable authorization
context or a cached policy snapshot.

The runtime context must be bound to:

- user identifier
- tenant identifier
- membership identifier
- policy version or equivalent authorization epoch
- issuance and expiry information
- integrity protection from a trusted issuer

Clients must not supply trusted permission lists or policy versions directly.

Authorization assignment changes must advance a policy version or equivalent
epoch and publish an invalidation signal.

A later ADR will define:

- token versus policy-snapshot contents
- cache and freshness limits
- revocation propagation
- behavior when policy state is unavailable
- elevated checks for high-risk operations
- maximum authorization-context size

### Platform capabilities

Platform capabilities remain outside tenant roles and tenant membership
permissions.

Examples include:

```text
platform.tenant.suspend
platform.legal-hold.manage
platform.support.impersonate
```

Platform capabilities use a separate global assignment model.

A platform capability does not implicitly grant tenant business permissions.

Any platform operation that accesses tenant data must be:

- explicit
- narrowly authorized
- strongly authenticated
- auditable
- bound to a reason or support case when applicable

Platform impersonation or delegated access requires a separate accepted
security design before implementation.

### Provisioning and consistency

Tenant provisioning must not expose an active tenant with no authorized owner.

The provisioning workflow must ensure that:

- the Tenant exists
- the initial owner TenantMembership is active
- the required initial SYSTEM role assignment exists
- partial failure is recoverable or compensatable

Cross-service consistency must use explicit workflow and reliable event
delivery. A shared database transaction between Identity and Authorization is
prohibited.

### Audit

The following actions must be audited:

- role creation, modification, deletion, and restoration
- role assignment and removal
- direct permission grant and revocation
- direct permission deny and revocation
- SYSTEM role version changes
- platform capability assignment and use
- sensitive authorization denials when required by policy
- policy-version changes and invalidation events

Audit records must include actor, tenant or platform scope, target membership,
permission or role, reason, correlation data, and timestamp.

## Consequences

### Positive

- One user can have different access in different tenants.
- Multiple roles provide simple permission composition.
- Direct grants support exceptional access without creating one-off roles.
- Direct denies remove permissions inherited from roles.
- Deny precedence is deterministic.
- Domain services retain control of resource and business-rule enforcement.
- Authorization avoids a required network dependency on every request.
- Platform administration remains separate from tenant business permissions.
- Identity and Authorization have explicit data ownership.

### Negative

- Authorization state must be propagated and invalidated across services.
- Cached authorization introduces bounded staleness that requires explicit
  policy.
- Tenant provisioning spans Identity and Authorization workflows.
- Permission catalog governance requires coordination with bounded contexts.
- Direct grants and denies can become difficult to manage without reporting
  and expiration controls.
- Final enforcement is distributed across resource-owning services.

### Required safeguards

Implementation must include:

- tenant and membership scope on every tenant authorization record
- database constraints preventing cross-tenant role assignment
- default-deny evaluation
- explicit deny precedence tests
- tests for multiple-role union behavior
- tests for inactive membership denial
- tests proving BFF authorization is not sufficient by itself
- policy-version advancement on assignment changes
- immutable audit events for authorization changes
- exact permission-key validation
- no wildcard assignment support
- no role inheritance support unless a later ADR accepts it

## Alternatives considered

### Attach roles directly to User

Rejected because a global User may have different permissions in different
tenants.

### Put Role and Permission ownership in Identity Service

Rejected because authentication and membership lifecycle are distinct from
authorization policy and assignment management.

### Central authorization network call for every request

Rejected as the mandatory default because it adds latency, availability
coupling, and a platform-wide runtime bottleneck.

Online checks may still be required for explicitly classified high-risk
operations.

### Trust BFF authorization as final

Rejected because internal callers, compromised edge components, and alternate
interfaces must not bypass enforcement in the resource-owning service.

### Role inheritance

Not selected initially because it introduces cycles, hidden permission paths,
and more complex invalidation.

Multiple role assignment provides explicit composition.

### Wildcard permissions

Not selected initially because broad patterns make review, migration, audit,
and least-privilege enforcement harder.

### Deny rules inside roles

Rejected initially because shared role semantics become harder to understand.
Membership-level denies provide explicit exceptions without changing the role
for every assignee.

### Store domain conditions in Authorization Service

Rejected because resource state and business invariants belong to the bounded
context that owns the resource.

## Rollback or migration considerations

This accepted ADR is immutable. A later decision must supersede it.

Initial implementation requires:

- a versioned permission catalog contract
- authorization database migrations
- SYSTEM role seed and upgrade behavior
- tenant-safe uniqueness and foreign-key constraints
- reliable Identity membership lifecycle events
- policy-version and invalidation events
- local enforcement libraries limited to technical verification primitives
- service-level authorization tests
- an explicit tenant-owner provisioning workflow

Changing permission-key semantics requires a new key and assignment migration.

Extracting or restructuring the Authorization Service requires preservation of
Role identifiers, membership assignments, direct grants, direct denies,
policy versions, audit history, and tenant boundaries.
