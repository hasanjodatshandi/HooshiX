# ADR-0013: Online Authorization and Authorization Policy Model v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13; Authorization implementation contracts finalized on 2026-08-14

## Decision

`authorization-service` owns tenant roles, assignments, direct grants/denies, online evaluation, management audit/idempotency, platform capability-profile assignments, owner-safety reservations, Identity-driven lifecycle projections, and its private PostgreSQL persistence. Identity owns users, tenants, memberships, authentication, sessions, and active-tenant selection. Resource-owning bounded contexts own permission-key meaning and final domain/resource enforcement.

### Tenant runtime authorization

Every protected resource operation uses one authoritative `CheckPermission` gRPC call:

```text
deadline:         300 ms maximum
attempts:         1
wait-for-ready:   off
automatic retry:  none
permission cache: none
stale fallback:   none
failure mode:     fail closed
```

The request contains only `tenant_id`, `membership_id`, and exact `permission_key`. It never accepts role/permission snapshots, owner count, user-supplied policy, resource attributes, or a caller-selected decision.

Successful RPC completion means **ALLOW**. Authoritative deny maps to `PERMISSION_DENIED / AUTHORIZATION_DENIED`; there is no successful `allowed=false` response. A success response contains no Role/permission snapshot.

Dependency failure/open breaker maps to `UNAVAILABLE / AUTHORIZATION_UNAVAILABLE`; healthy service saturation maps to `RESOURCE_EXHAUSTED / AUTHORIZATION_OVERLOADED` and is still treated by callers as fail-closed dependency unavailability.

There is no authorization Kafka invalidation topic, permission-result cache, policy-snapshot authorization authority, signed permission-list authority, Bloom-filter grant path, stale allow fallback, or retry in v1. Safe local token/context/permission/resource syntax checks may reject invalid traffic before the remote call but MUST NOT grant a protected operation.

### Permission catalog

Permission keys are exact stable Git-owned contracts under the Authorization contract boundary. Each key records TENANT/PLATFORM scope, owning bounded context, and lifecycle:

```text
ACTIVE -> DEPRECATED -> RETIRED
```

Syntax:

```text
^[a-z][a-z0-9]*(?:\.[a-z][a-z0-9]*)+$
maximum length: 128 characters
```

`DEPRECATED` remains evaluatable for already-existing assignments during migration but cannot be newly granted/assigned. `RETIRED` or unknown keys fail closed and cannot be assigned. Permission identifiers are never reused for a new meaning.

### Tenant Roles and direct overrides

Tenant permission precedence remains:

```text
Direct Membership Deny
> Direct Membership Grant
> Role-derived Grant
> Default Deny
```

Tenant SYSTEM Roles are server-owned/immutable and have no tenant-controlled rename/archive/delete/policy mutation:

- `tenant_owner`: every current ACTIVE tenant permission;
- `tenant_admin`: every current ACTIVE tenant permission except `tenant.delete` and `membership.owner.assign`;
- `tenant_member`: `tenant.read`, `membership.read`, `role.read`.

SYSTEM Role mappings are catalog-driven explicit policy, not user-defined wildcard assignment.

Custom Role lifecycle is `ACTIVE -> ARCHIVED`; ordinary hard delete does not exist. Custom Role ID is UUIDv4. Name is trim+NFC, 1..80 Unicode code points, control-character-free, case-preserving for display and `Locale.ROOT` lowercase for per-tenant uniqueness. Description is <=500 code points. SYSTEM names are reserved. Mutable Role commands require optimistic versioning; stale mutation returns `STALE_ROLE_VERSION`. Archived Roles stop granting and cannot be reused as a new Role identity/name.

v1 has no Role inheritance and no wildcard permission assignment.

Direct override scope is exactly `(tenant membership, exact permission key) -> GRANT|DENY`; v1 has no resource-instance condition, expression, TTL, expiry, or policy script. One Membership+permission has at most one active override and only explicit removal changes it.

### Management surface and privilege escalation

Authorization owns bounded management reads and writes through versioned gRPC/Protobuf. Browser management reaches them only through Web BFF. BFF presents an Identity access JWT with exact audience `authorization-service`; Authorization verifies it locally and derives trusted `sub`, `tenant_id`, `membership_id`, and `sid`. Roles/permissions are never trusted from the JWT or caller payload.

Management authorization is evaluated locally inside Authorization; a self-gRPC `CheckPermission` call is prohibited.

Current tenant-management permissions include:

```text
tenant.read
tenant.delete
role.read
role.create
role.update
role.archive
role.permission.manage
membership.read
membership.role.assign
membership.permission.manage
membership.owner.assign
```

There is no tenant-facing authorization-audit read API in v1.

An actor cannot grant authority it does not currently possess. This applies to adding Role permissions, assigning a Role, direct grants, removing a direct deny, and owner assignment. Removing a permission/grant without increasing authority requires only the corresponding management permission.

Hard v1 limits:

```text
custom Roles per tenant:             100
permissions per custom Role:         200
Roles per Membership:                 20
direct overrides per Membership:     100
semantic mutations per bulk request: 100
pagination default / maximum:         50 / 200
```

A management mutation is locally atomic; partial-success mutation is prohibited.

### Platform capability authorization

`platform_admin` is a global SYSTEM capability profile, not a tenant Role and not a wildcard bypass. Current explicit profile permissions are:

```text
platform.tenant.create
platform.tenant.suspend
platform.tenant.resume
platform.tenant.restore
platform.legal_hold.manage
```

Identity uses the separate authoritative `CheckPlatformPermission(user_id, permission_key)` operation for platform-only tenant/legal-hold entry points:

```text
deadline:        300 ms maximum
attempts:        1
wait-for-ready:  off
automatic retry: none
cache/fallback:  none
failure mode:    fail closed
```

Only the approved Identity workload may call it. RPC success means ALLOW; authoritative deny uses `AUTHORIZATION_DENIED`. Platform capability never bypasses tenant resource permission, ownership, or domain invariants.

Platform profile assignment/revocation is excluded from ordinary tenant/browser management APIs and occurs only through a separately privileged JIT-controlled audited platform workflow.

### Owner safety

Every active tenant retains at least one effective owner. Identity Membership-removal preparation and local Authorization `tenant_owner` assignment/removal/demotion share one tenant-scoped serialization/locking domain. Read-only owner-count-then-write, caller-supplied owner count, force-last-owner flags, and reservation auto-expiry into allow state are prohibited.

Identity `PrepareMembershipRemoval` remains an authoritative-security operation with the same 300ms/one-attempt/no-cache/no-retry/no-fallback fail-closed contract. Finalize/cancel are durable idempotent commands after the Identity local commit decision.

### Idempotency and audit

Every management/lifecycle/platform write uses canonical lowercase UUIDv4 `request_id` plus purpose/version HMAC-SHA-256 intent fingerprint over trusted actor/scope/operation/canonical payload. Equal replay returns the original committed result; conflicting reuse returns `ALREADY_EXISTS / REQUEST_ID_CONFLICT`. Security-sensitive idempotency evidence is retained at least 35 days.

Every write and privilege-sensitive management/platform rejection creates durable bounded audit evidence. Owner assignment/removal, direct grant/deny set/remove, Role-permission mutation, and platform-authority operations require a trim+NFC control-character-free reason of 1..500 Unicode code points. Security audit evidence is retained at least 365 days unless a stricter approved policy applies.

Routine online `CheckPermission` calls remain bounded telemetry rather than adding a synchronous durable audit write to every hot-path decision.

### Persistence and erasure

Authorization uses jOOQ/JDBC without JPA. Tenant-owned tables use forced RLS and transaction-local trusted tenant context. Critical `CheckPermission` SQL remains within the current <=100ms query budget with representative plan/index evidence. No remote I/O occurs inside Authorization database transactions.

As an ADR-0028 erasure participant, Authorization removes/anonymizes subject-linked Membership Role/direct-override/projection state and any global platform profile assignment for the erased User. Tenant-owned Role/RolePermission definitions remain. Required retained audit facts remove or irreversibly pseudonymize the direct User link and never preserve application authority.

Cross-context provisioning continues to use source-service durable local intent/Transactional Outbox plus idempotent Authorization command semantics. It is lifecycle synchronization, not runtime authorization cache invalidation.

## Verification requirements

Tests/evidence cover permission catalog syntax/scope/lifecycle/non-reuse; SYSTEM Role immutability; custom Role normalization/version/archive/limits; permission precedence; exact CheckPermission request/success/deny semantics; cross-tenant denial; inactive lifecycle; management JWT/workload authentication; management permission mapping and privilege-escalation negatives; direct override bounds; owner-safety concurrency; platform permission fail-closed/no-bypass behavior; bulk limits/quota interaction; idempotency replay/conflict; durable audit/reason/PII controls; jOOQ/RLS/query-plan/no-remote-I/O behavior; erasure of subject authority; workload identity; and exact one-call/no-retry/no-cache/no-fallback runtime behavior.

## Rollback considerations

Rollback MUST NOT introduce stale allow, local authoritative permission caching/snapshots, Kafka invalidation, automatic retry, caller-supplied role/owner authority, wildcard/custom inheritance, resource-condition policy, platform wildcard bypass, unsafe last-owner races, permission-key reuse, lost audit/idempotency evidence, erased-user authority, or weaker tenant/database isolation.
