# Authorization Service Architecture

## 1. Ownership

`authorization-service` owns tenant Role/RolePermission, MembershipRole, direct MembershipPermissionGrant/Deny, online permission evaluation, management commands, authorization audit, and private PostgreSQL persistence.

Identity owns User, Tenant, TenantMembership, membership lifecycle, authentication, sessions, and active-tenant selection. Permission-key meaning and resource/domain invariants remain owned by the protected resource bounded context.

## 2. Role and permission model

Roles attach to `TenantMembership`, not global User. One membership may have multiple tenant-scoped roles. v1 has no role inheritance and no wildcard permission assignment.

Permission keys are exact stable contracts.

Evaluation:

```text
Direct Membership Deny
> Direct Membership Grant
> Role-derived Grant
> Default Deny
```

Inactive/suspended/removed/deleted membership has no tenant permissions.

## 3. Final enforcement

The resource-owning service is the final security boundary. It verifies applicable end-user context, workload identity, active tenant/membership, required permission, resource tenant ownership, and domain invariants.

The BFF does not duplicate routine online permission checks when the resource service performs the authoritative check. BFF-owned resources or explicitly justified UX/read-model checks are exceptions and never replace final enforcement.

## 4. Current online runtime

ADR-0013 and ADR-0026 define the current request/capacity baseline; ADR-0032/ADR-0036 define current SLI/burn/recovery behavior.

```text
CheckPermission deadline: 300 ms maximum
attempts:                 1
wait-for-ready:           off
automatic retry:          none
permission cache:         none
stale fallback:           none
fail mode:                fail closed
```

Authoritative deny -> `PERMISSION_DENIED`.
Dependency failure/open breaker -> `UNAVAILABLE / AUTHORIZATION_UNAVAILABLE`.
Healthy overload -> `RESOURCE_EXHAUSTED / AUTHORIZATION_OVERLOADED`, treated as fail-closed dependency unavailability by callers.

There is no v1 authorization Kafka invalidation topic/policy-snapshot authority.

## 5. SLO, overload, and capacity

```text
availability >=99.95% rolling 30d
p95 <=100 ms
p99 <=200 ms
hard caller deadline = 300 ms
steady engineering target = p95<=75 ms / p99<=150 ms
```

Paging uses paired multi-window burn rather than isolated percentile samples. The <=200ms objective is represented as >=99% eligible calls meeting the threshold for burn accounting.

Before Authorization, resource services perform only safe local token/claim/tenant/permission/resource-shape rejection. These checks never grant authority. No Bloom filter/local permission result/signed permission list is authoritative.

Server protections:

- global + per-workload-principal bounded concurrency;
- <=25ms server queue wait before shedding;
- fair-share overload isolation;
- bounded PostgreSQL pool/query path;
- no unbounded application queue.

Caller breakers fail closed. Current recovery uses bounded per-instance de-correlated reopen backoff and at most one real half-open `CheckPermission` probe in flight per breaker. Three consecutive infrastructure-successful probes close; any timeout/unavailable/overload reopens. Health endpoints never close caller breakers; tenant/commercial tier never changes security recovery semantics.

Production deployment baseline:

- minimum 3 replicas;
- PDB `minAvailable: 2`;
- topology spread;
- HPA initial min 3 / max 12;
- DB acquisition budget <=50ms;
- permission query budget <=100ms;
- HPA/pool maxima included in global service-cluster PostgreSQL connection budget.

Before production, prove >=2x projected peak with p95<=100ms/p99<=200ms, Hikari acquisition p99<25ms, no sustained queue growth, >=30% validated resource/database headroom, fair-share isolation, one replica/node loss, PostgreSQL failover, invalid-token flood rejection before Authorization, and safe valid-token abuse shedding without retry amplification.

## 6. PostgreSQL

Authorization owns a dedicated PostgreSQL database and dedicated production CloudNativePG cluster under ADR-0027. Runtime/migration roles are service-only; runtime is `NOSUPERUSER NOBYPASSRLS`, not owner. Tenant-owned tables use forced RLS plus application tenant enforcement. Tenant context comes only from validated authenticated context and uses the canonical parameterized transaction-local setting from the SQL/Flyway standard; session-scoped tenant state on pooled connections is prohibited, missing/malformed context fails closed, and cross-tenant pooled-connection reuse after commit/rollback is a mandatory negative test. Synchronous required durability/safe failover apply.

Failover under sustained `CheckPermission` traffic is a production gate.

## 7. Provisioning

Cross-context provisioning uses source-service Transactional Outbox/durable intent + idempotent Authorization gRPC command. Stable request identity/fingerprint behavior:

- equal replay -> original result;
- conflicting reuse -> `ALREADY_EXISTS`.

This is durable provisioning, not runtime authorization invalidation.

## 8. Current roles/capabilities

`platform_admin` is a global platform capability profile, not tenant role.

Tenant SYSTEM roles:

- `tenant_owner`: all tenant permissions;
- `tenant_admin`: all except `tenant.delete` and `membership.owner.assign`;
- `tenant_member`: `tenant.read`, `membership.read`, `role.read`.

Every active tenant has an owner; last owner cannot be removed/demoted. Owner assignment requires `membership.owner.assign`. Actors cannot grant permissions they do not possess, including through custom roles.

## 9. Semantic quotas

ADR-0024 is the current semantic quota decision. Authorization owns administration quotas in its ACL-isolated `security-redis` namespace; no quota microservice is called.

Quota evaluation is atomic, pseudonymous, 75ms/one-attempt/no-retry, dual-clock fail-closed (`trusted_app_time` + Redis `TIME`, <=2s skew), monotonic effective time, and never uses TTL expiry as security reset. Bulk administration charges bounded operation cost, not one unit for an arbitrarily large mutation request.

Production administration remains gated until atomicity/time-safety/Redis Sentinel failover/outage/abuse and >=2x peak quota-load evidence pass.

## 10. Verification

Required applicable tests include precedence, cross-tenant denial/RLS including pooled-connection context reuse, inactive membership, multiple-role union, last-owner concurrency, privilege escalation, platform audit, provisioning replay/conflict, exact one-call/no-cache/no-retry behavior, deny/outage/overload mapping, breaker recovery, workload identity, semantic quota time/failover, PostgreSQL failover, capacity/load/bulkhead, query plans, and PII-safe telemetry.
