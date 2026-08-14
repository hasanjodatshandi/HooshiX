# Authorization Service Architecture

## 1. Ownership

`authorization-service` owns tenant Role/RolePermission, MembershipRole, direct MembershipPermissionGrant/Deny, online permission evaluation, management commands, authorization audit, Identity-driven authorization-lifecycle projections/reservations, and private PostgreSQL persistence.

Identity owns User, Tenant, TenantMembership, membership lifecycle, authentication, sessions, and active-tenant selection. Permission-key meaning and resource/domain invariants remain owned by the protected resource bounded context.

Authorization never relies on Identity database access or a copied Identity persistence model. Cross-context synchronization uses canonical typed gRPC commands with stable request identity.

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

Inactive/suspended/removed/deleted membership has no tenant permissions once the corresponding Authorization-owned lifecycle state/projection is applied. A tenant authorization lifecycle marked suspended/deleting/deleted fails closed regardless of retained role rows required for restore/audit.

## 3. Final enforcement

The resource-owning service is the final security boundary. It verifies applicable end-user context, workload identity, active tenant/membership claim shape, required permission, resource tenant ownership, and domain invariants.

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

Cross-context provisioning/lifecycle synchronization uses source-service Transactional Outbox/durable intent + idempotent Authorization gRPC commands. Stable request identity/fingerprint behavior:

- equal replay -> original committed result;
- conflicting reuse -> `ALREADY_EXISTS` with stable machine code.

Identity-owned durable commands include:

### Initial owner provisioning

Tenant creation provisions the creator's `tenant_owner` role. The command ACK means the required owner role state is durably committed. Identity keeps Tenant `PROVISIONING` until that ACK.

### Invitation member provisioning

Accepted invitation provisions the new active Membership with default SYSTEM role `tenant_member`. Invitation payloads do not carry arbitrary role/permission authority. Until provisioning commits, ordinary Authorization default-deny applies.

### Tenant lifecycle cleanup and restore reconciliation

When Identity begins tenant deletion, a durable lifecycle command makes Authorization's tenant authorization projection deny new permission grants/checks according to the lifecycle contract and preserves only the restorable/auditable authorization state needed before irreversible purge. Identity does not mark Tenant `DELETED` until Authorization ACKs the required cleanup/deny state.

A platform-admin restore that is still permitted by Identity sends an idempotent reconciliation command. Authorization re-enables/reconciles its retained tenant authorization state only after the canonical Identity lifecycle permits it. Custom roles/assignments are not reconstructed from guesses or Identity role copies.

These lifecycle/provisioning commands use the Identity durable-command baseline unless a stricter provider contract is defined:

```text
deadline:       900 ms
attempts:       1
wait-for-ready: off
immediate retry:none
```

Durable retry belongs to the Identity dispatcher/outbox, never the gRPC client/mesh. This is provisioning/lifecycle synchronization, not runtime permission cache invalidation.

## 8. Owner-safety membership removal protocol

Every active tenant must retain at least one effective owner. A read-only `ValidateMembershipRemoval` followed by an independent Identity commit is insufficient under concurrency: two removals could both observe two owners and both commit.

v1 therefore exposes the stronger typed `PrepareMembershipRemoval` protocol to Identity. It is an authoritative security operation and is callable only from the approved Identity workload identity.

Request binds canonical UUIDv4 `request_id`, `tenant_id`, and `membership_id`. Authorization atomically:

1. resolves current role state for the target Membership;
2. evaluates owner safety excluding every already-active membership-removal reservation;
3. rejects if the target removal could leave zero effective owners;
4. otherwise persists one durable idempotent removal reservation bound to request/tenant/membership;
5. treats the reserved Membership as ineligible for conflicting owner-role assignment/demotion/removal mutation until reservation resolution.

Contract:

```text
deadline:       300 ms maximum
attempts:       1
wait-for-ready: off
automatic retry:none
cache:          none
fallback:       none
failure mode:   fail closed
last owner:     FAILED_PRECONDITION / LAST_TENANT_OWNER
```

Equal replay of the same request/fingerprint returns the same preparation result. Conflicting request reuse returns `ALREADY_EXISTS`. Preparation dependency failure maps to stable unavailable state and does not authorize Identity removal.

A reservation does **not** auto-expire into an allow state. Automatic expiry would reopen the concurrency race if Identity had committed removal but finalization were delayed. The reservation stays conservative until one stable resolution command is committed:

- `FinalizeMembershipRemoval` — after Identity durably commits Membership `REMOVED`; retires/removes applicable role state and closes the reservation;
- `CancelMembershipRemovalPreparation` — only when Identity durably determines its removal did not commit; closes the reservation without removing role state.

Identity persists its own PREPARING/finalize-or-cancel intent so crash recovery replays the same request. Finalize/cancel are idempotent durable commands using the 900ms one-attempt/no-immediate-retry baseline; unresolved reservations alert and remain fail-closed rather than silently releasing owner capacity.

Authorization never accepts a caller-provided owner count, role snapshot, expiration, or “force remove last owner” flag.

## 9. Current roles/capabilities

`platform_admin` is a global platform capability profile, not tenant role.

Tenant SYSTEM roles:

- `tenant_owner`: all tenant permissions;
- `tenant_admin`: all except `tenant.delete` and `membership.owner.assign`;
- `tenant_member`: `tenant.read`, `membership.read`, `role.read`.

Every active tenant has an owner; last owner cannot be removed/demoted. Owner assignment requires `membership.owner.assign`. Actors cannot grant permissions they do not possess, including through custom roles.

## 10. Semantic quotas

ADR-0024 is the current semantic quota decision. Authorization owns administration quotas in its ACL-isolated `security-redis` namespace; no quota microservice is called.

Quota evaluation is atomic, pseudonymous, 75ms/one-attempt/no-retry, dual-clock fail-closed (`trusted_app_time` + Redis `TIME`, <=2s skew), monotonic effective time, and never uses TTL expiry as security reset. Bulk administration charges bounded operation cost, not one unit for an arbitrarily large mutation request.

Production administration remains gated until atomicity/time-safety/Redis Sentinel failover/outage/abuse and >=2x peak quota-load evidence pass.

## 11. Verification

Required applicable tests include permission precedence, cross-tenant denial/RLS including pooled-connection context reuse, inactive Membership and tenant lifecycle deny, multiple-role union, last-owner concurrency, simultaneous prepare requests, reservation replay/conflict, reservation-vs-owner-assignment races, no unsafe automatic reservation expiry, crash/replay/finalize/cancel recovery, privilege escalation, platform audit, owner/member/tenant-lifecycle provisioning replay/conflict, default `tenant_member` assignment and no invitation arbitrary-role authority, exact one-call/no-cache/no-retry behavior, deny/outage/overload mapping, breaker recovery, workload identity positive/negative policy, semantic quota time/failover, PostgreSQL failover, capacity/load/bulkhead, query plans, and PII-safe telemetry.
