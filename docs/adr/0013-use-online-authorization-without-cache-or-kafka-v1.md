# ADR-0013: Online Authorization Without Cache or Kafka v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

## Decision

`authorization-service` owns tenant roles, assignments, direct grants/denies, online evaluation, authorization audit, and its private PostgreSQL persistence. Identity owns users, tenants, memberships, authentication, sessions, and active-tenant selection. Resource-owning bounded contexts own permission-key meaning and final domain/resource enforcement.

Every protected resource operation uses one authoritative `CheckPermission` gRPC call:

```text
deadline:        300 ms maximum
attempts:        1
wait-for-ready:  off
automatic retry: none
permission cache: none
stale fallback:   none
failure mode:     fail closed
```

Authoritative deny maps to `PERMISSION_DENIED`. Dependency failure/open breaker maps to `UNAVAILABLE / AUTHORIZATION_UNAVAILABLE`; healthy service saturation maps to the current overload code and is still treated as fail-closed dependency unavailability by callers.

There is no authorization Kafka invalidation topic, policy-snapshot authorization authority, signed permission-list authority, Bloom-filter grant path, or stale allow fallback in v1.

Safe local token/context/permission/resource syntax checks may reject invalid traffic before the remote call but MUST NOT grant a protected operation.

Cross-context provisioning uses source-service durable local intent/Transactional Outbox plus idempotent Authorization command semantics; it is provisioning state synchronization, not runtime authorization cache invalidation.

### Roles and permissions

Tenant permission precedence:

```text
Direct Membership Deny
> Direct Membership Grant
> Role-derived Grant
> Default Deny
```

Tenant SYSTEM roles are immutable by tenants. Current baseline:

- `tenant_owner`: every tenant permission;
- `tenant_admin`: all except `tenant.delete` and `membership.owner.assign`;
- `tenant_member`: `tenant.read`, `membership.read`, `role.read`.

Every active tenant has an owner; the last owner cannot be removed/demoted. Owner assignment requires `membership.owner.assign`. Actors cannot grant a permission they do not possess, including through custom roles. Role inheritance and wildcard permission assignment are not part of v1.

`platform_admin` is a global platform capability profile, not a tenant role. Its use is explicit and audited and never silently bypasses tenant resource/domain invariants.

### Management/provisioning

Management is internal gRPC with the approved BFF REST facade where applicable. Idempotent provisioning commands use stable request identity and intent fingerprinting: equal replay returns the original committed result; conflicting ID reuse returns `ALREADY_EXISTS`.

## Verification requirements

Tests cover permission precedence, cross-tenant denial, inactive membership, last-owner concurrency, privilege escalation, platform audit, provisioning replay/conflict, exact one-call/no-retry/no-cache/no-fallback behavior, deny/outage/overload mapping, workload identity authorization, and PII-safe telemetry.

## Rollback considerations

Rollback MUST NOT introduce stale allow, local authoritative permission caching/snapshots, Kafka invalidation, automatic retry, lost audit/tenant boundaries, or reused role/permission identifiers.
