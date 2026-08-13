# Authorization Service Architecture

## 1. Ownership

`authorization-service` owns:

- Role and RolePermission;
- MembershipRole;
- MembershipPermissionGrant / MembershipPermissionDeny;
- online permission evaluation;
- authorization-management commands;
- authorization audit;
- private PostgreSQL persistence.

Identity owns User, Tenant, TenantMembership, membership lifecycle,
authentication, sessions, and active-tenant selection.

## 2. Role/permission model

Roles attach to `TenantMembership`, not global User. One membership may have
multiple tenant-scoped roles. v1 has no role inheritance and no wildcard
permission assignment.

Permission keys are exact stable contracts whose **meaning** belongs to the
resource-owning bounded context.

Evaluation:

```text
Direct Membership Deny
> Direct Membership Grant
> Role-derived Grant
> Default Deny
```

Inactive/suspended/removed/deleted membership has no tenant permissions.

## 3. Final enforcement

The resource-owning service is the final security boundary. It verifies
applicable end-user context, workload identity, active tenant/membership,
required permission, resource tenant ownership, and domain invariants.

The BFF does not duplicate the routine online permission check when the resource
service will make the authoritative check. A BFF-owned resource or separately
justified UX/read-model check is an exception and never replaces final
enforcement.

## 4. Current online runtime

ADR-0039 + ADR-0056:

```text
CheckPermission deadline: 300 ms
attempts:                 1
wait-for-ready:           off
automatic retry:          none
local cache:              none
stale fallback:           none
fail mode:                fail closed
```

Authoritative deny -> `PERMISSION_DENIED`.
Dependency failure -> `UNAVAILABLE / AUTHORIZATION_UNAVAILABLE`.

There is no v1 authorization Kafka invalidation topic.

## 5. SLO, overload, and capacity

`CheckPermission` production objective (ADR-0056/ADR-0062/ADR-0066):

```text
availability >=99.95% rolling 30d
p95 <=100 ms
p99 <=200 ms
hard caller deadline = 300 ms
steady-state engineering target = p95<=75 ms / p99<=150 ms
```

Short spikes do not page on percentile samples alone. Authorization uses paired
multi-window burn alerts: 14.4x on 5m+1h and 6x on 30m+6h page; 3x on 2h+24h
creates a reliability action. The p99 objective is represented as >=99% of
eligible calls <=200ms for burn accounting.

Before invoking Authorization, the resource service performs only safe local
JWT/claim/tenant-shape validation; those checks may reject invalid traffic but
never grant access. No Bloom filter or permission-result cache is authoritative.

Authorization has global + per-workload-principal bounded concurrency, <=25ms
server queue wait, fair-share load shedding, and `AUTHORIZATION_OVERLOADED`
status. Callers use a short fail-closed circuit breaker for infrastructure
failures; open state returns `AUTHORIZATION_UNAVAILABLE`, never stale allow.
ADR-0066 supersedes ADR-0062's fixed open interval with bounded exponential reopen backoff plus per-instance de-correlation. HALF_OPEN permits only one real `CheckPermission` probe in flight per caller breaker; three consecutive infrastructure-successful probes close, while timeout/unavailable/overload reopens immediately. A separate health endpoint never closes the breaker, and tenant tier never changes breaker semantics.

Production deployment:

- minimum 3 replicas;
- PDB `minAvailable: 2`;
- topology spread;
- HPA min 3 / max 12 initially;
- bounded in-flight concurrency; no unbounded application queue;
- DB acquisition budget <=50ms;
- permission query budget <=100ms;
- pool/HPA maxima included in global PostgreSQL connection budget.

Before production, load testing proves >=2x projected peak with p95<=100ms and
p99<=200ms, Hikari acquire p99<25ms, no sustained queue growth, fair-share
isolation, and adequate DB headroom. Invalid-token floods must be rejected before
Authorization; valid-token abuse must shed safely without retry amplification.

## 6. PostgreSQL availability

Authorization owns a dedicated PostgreSQL database on its own ADR-0057
CloudNativePG production cluster. Runtime/migration roles are Authorization-only;
runtime is `NOSUPERUSER NOBYPASSRLS` and is not the table owner. Tenant-owned
tables use forced RLS in addition to application tenant enforcement. Synchronous
required durability and safe automatic failover remain mandatory. App replicas
alone are not treated as HA evidence.

Failover under sustained `CheckPermission` traffic is a production gate.

## 7. Provisioning

Cross-context provisioning uses source-service transactional outbox + idempotent
Authorization gRPC command. Stable request IDs and intent fingerprints provide:

- equal replay -> original result;
- conflicting reuse -> `ALREADY_EXISTS`.

This is durable provisioning, not runtime authorization cache invalidation.

## 8. Current roles/capabilities

`platform_admin` is a global platform capability profile, not a tenant role.

Tenant SYSTEM roles:

- `tenant_owner`: all tenant permissions;
- `tenant_admin`: all except `tenant.delete` and `membership.owner.assign`;
- `tenant_member`: `tenant.read`, `membership.read`, `role.read`.

Every active tenant has an owner; last owner cannot be removed/demoted. Owner
assignment requires `membership.owner.assign`. Actors cannot grant a permission
they do not possess, including through custom roles.

## 9. Semantic quotas

ADR-0041 resolves ADR-0040's architecture gate for Authorization administration.
Authorization owns and enforces administration quotas through its ACL-isolated
`security-redis` namespace; it does not call a quota microservice.

ADR-0054 hardens the 75ms one-attempt fail-closed Redis contract with dual
trusted clocks, <=2s skew, monotonic effective quota time, and no security reset
from Redis TTL expiry. Bulk administration charges bounded mutation cost, not one
unit per arbitrarily large request.

Production administration remains disabled until the implementation, Redis
Sentinel failover, atomicity, abuse, and >=2x peak quota-load tests pass.

## 10. Verification

Required applicable tests include precedence, cross-tenant denial, inactive
membership, multiple-role union, last-owner concurrency, privilege escalation,
platform audit, provisioning replay/conflict, exact no-cache/no-retry behavior,
deny/outage mapping, workload identity, semantic quotas, PostgreSQL failover,
capacity/load/bulkhead, query plans, and PII-safe telemetry.
