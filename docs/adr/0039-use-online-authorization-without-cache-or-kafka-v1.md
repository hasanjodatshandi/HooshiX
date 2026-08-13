# ADR-0039: Use Online Authorization Without Cache or Kafka in v1

## Status

Accepted

## Date

2026-08-10

## Supersedes

This ADR supersedes ADR-0005's v1 Policy Snapshot cache, Kafka invalidation,
watermark, and authorization-version token model. ADR-0004's ownership,
scoping, exact permissions, deny precedence, resource-service enforcement, and
platform-capability separation remain accepted.

## Decision

`authorization-service` owns roles, assignments, direct grants/denies, online
evaluation, audit, and private PostgreSQL persistence. Every protected
operation uses `CheckPermission` gRPC with a 300ms deadline, one attempt,
wait-for-ready off, no automatic retry, no local cache, and no stale fallback.
An authoritative deny maps to `PERMISSION_DENIED`; dependency failure fails
closed but maps to `UNAVAILABLE / AUTHORIZATION_UNAVAILABLE`.

There is no Kafka invalidation topic in v1. Cross-context provisioning uses the
source service's transactional outbox and Authorization's idempotent gRPC
command. This is durable provisioning, not cache invalidation.

`platform_admin` is a global platform capability profile, not a tenant role,
and every use is audited. It grants all platform permissions:

```text
platform.tenant.create platform.tenant.read platform.tenant.update
platform.tenant.suspend platform.tenant.delete
platform.authorization.read platform.authorization.manage platform.audit.read
```

Tenant permissions are:

```text
tenant.read tenant.update tenant.delete
membership.read membership.invite membership.remove
membership.role.assign membership.owner.assign
role.read role.create role.update role.delete
grant.read grant.create grant.revoke
deny.read deny.create deny.revoke audit.read
```

Tenant SYSTEM roles are immutable. `tenant_owner` has every tenant permission.
`tenant_admin` has all except `tenant.delete` and `membership.owner.assign`.
`tenant_member` has `tenant.read`, `membership.read`, and `role.read`.

Every active tenant has an owner and the last owner cannot be removed/demoted.
Owner assignment requires `membership.owner.assign`. Actors cannot grant a
permission they lack, including through custom roles. Direct deny overrides
direct and role grants. Tenants cannot mutate SYSTEM roles.

Management is internal gRPC with a BFF REST facade. Provisioning uses stable
request IDs and intent fingerprints: equal replay returns the original result;
conflicting reuse returns `ALREADY_EXISTS`.

## Verification Requirements

Tests cover precedence, cross-tenant denial, inactive membership, last-owner
concurrency, privilege escalation, platform audit, idempotency conflict, exact
deadlines, no retry/cache/fallback, deny/outage mapping, workload identity, and
PII-safe logs.

## Rollback Considerations

Rollback cannot introduce stale allow, restore caching silently, lose audit,
or reuse role/permission identifiers.
