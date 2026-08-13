# ADR-0004: Authorization Ownership and Permission Evaluation

## Status

Accepted — current effective decision

## Date

2026-08-05; normalized to current-only documentation on 2026-08-13

## Decision

### Ownership

`authorization-service` owns:

- tenant Roles and RolePermission assignments;
- MembershipRole assignments;
- direct MembershipPermissionGrant/Deny assignments;
- policy/evaluation logic;
- authorization assignment/audit evidence.

Identity owns User, Tenant, TenantMembership, membership lifecycle, authentication, and active-tenant selection. Authorization references stable `tenant_id` and `membership_id` only; it does not share Identity tables, ORM entities, database access, or domain models.

Each resource-owning bounded context owns the semantic meaning of its permission keys and remains the final enforcement boundary for resource ownership and domain invariants.

### Tenant-scoped model

Roles and direct permission exceptions attach to `TenantMembership`, never the global User.

```text
TenantMembership
├── MembershipRole
│   └── Role
│       └── RolePermission
├── MembershipPermissionGrant
└── MembershipPermissionDeny
```

Roles are tenant-scoped. Cross-tenant assignment is prohibited by application rules and database constraints. Role-to-role inheritance and wildcard permission assignments are not supported in v1.

Permission keys are exact stable contracts:

```text
<bounded-context>.<resource>.<action>
```

Changing a published permission key's meaning incompatibly requires a new key and migration.

### Evaluation precedence

For one exact permission:

```text
Explicit Membership Deny
> Explicit Membership Grant
> Role-derived Grant
> Default Deny
```

Inactive/expired/revoked/deleted/suspended assignments do not participate. Authorization is valid only for an active TenantMembership.

### Current runtime enforcement

Every protected resource operation performs one final authoritative online `CheckPermission` under ADR-0039/ADR-0056/ADR-0062/ADR-0066.

The current model has:

- one attempt;
- <=300ms caller deadline;
- no permission-result cache;
- no cached policy snapshot used as authorization authority;
- no authorization Kafka invalidation topic;
- no stale allow fallback;
- no automatic retry;
- fail-closed behavior on dependency failure/overload.

Safe local validation/prechecks may reject invalid token/context/permission/resource syntax, but they MUST NOT grant access. A BFF check is never the final security boundary and routine duplicate BFF checks are prohibited when the resource service will perform the authoritative call.

The resource-owning service also verifies authenticated workload/end-user context, active tenant, required permission, resource tenant ownership, and domain invariants. A permission grant authorizes an attempt, not the business outcome.

### Platform capabilities

Platform-wide capabilities are separate from tenant roles/membership permissions. They do not silently grant tenant business access. Platform operations touching tenant data are explicit, narrowly authorized, strongly authenticated, reason-bound where applicable, and audited.

### Provisioning and consistency

Tenant provisioning MUST NOT expose an active tenant without its authorized owner. Identity and Authorization remain independently persistent; cross-service consistency uses explicit idempotent workflow/outbox/event/RPC semantics, never a shared database transaction.

### Audit

Audit covers role creation/change/deletion, assignments, direct grant/deny changes, platform capability use, sensitive authorization denials as policy requires, and material policy changes. Evidence uses bounded non-secret context and current PII-safe logging rules.

## Required safeguards and verification

- tenant/membership scope on every tenant authorization record;
- database constraints preventing cross-tenant role assignment;
- exact-key/default-deny/explicit-deny precedence tests;
- inactive-membership denial tests;
- no wildcard or role-inheritance support unless a new current decision explicitly adds it;
- resource-service final-enforcement tests;
- one-online-call/no-cache/no-Kafka/no-stale-fallback architecture tests;
- fail-closed outage/overload/breaker tests;
- positive/negative workload identity policy tests;
- immutable business/audit identifiers and tenant-safe authorization audit evidence.

## Rollback considerations

A rollback MUST preserve stable Role/permission/assignment identifiers, tenant boundaries, deny precedence, audit evidence, and the current online fail-closed authorization contract. It MUST NOT reintroduce a local/cached authoritative permission context, authorization invalidation topic, wildcard assignment, role inheritance, or shared Identity/Authorization persistence without a new reviewed current decision.
