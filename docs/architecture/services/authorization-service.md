# Authorization Service Architecture

## 1. Ownership and implementation boundary

`authorization-service` is the authoritative bounded context for tenant authorization policy and platform capability assignments. It owns:

- exact permission-definition registry/projection used for evaluation;
- tenant SYSTEM and custom Role/RolePermission state;
- MembershipRole assignments;
- direct Membership permission overrides (`GRANT` / `DENY`);
- online tenant `CheckPermission` evaluation;
- authoritative platform `CheckPlatformPermission` evaluation;
- tenant authorization management commands and bounded management reads;
- authorization-management/security audit and idempotency evidence;
- Identity-driven tenant/Membership lifecycle projections and owner-safety removal reservations;
- platform capability-profile assignments;
- private PostgreSQL persistence.

Identity owns User, Tenant, TenantMembership, membership lifecycle, authentication, sessions, active-tenant selection, and data-subject erasure coordination. Permission-key **meaning**, resource ownership, and final resource/domain invariants remain owned by the protected bounded context.

Authorization never reads the Identity database, copies Identity persistence models, trusts caller-supplied role/permission snapshots, or accepts caller-supplied owner counts. Cross-context synchronization uses versioned typed gRPC contracts with stable request identity.

Implementation defaults:

```text
service path:   services/authorization-service
base package:   com.sajtech.authorization
persistence:    jOOQ/JDBC only; no JPA/Hibernate entity model
```

Domain/Application code remains independent of jOOQ/generated/transport/framework types. Generated jOOQ records/types remain Infrastructure-only.

## 2. Permission catalog and lifecycle

Permission keys are exact stable contracts. The canonical catalog is Git-owned under the Authorization contract boundary and records at least:

```text
permission_key
scope: TENANT | PLATFORM
owner_bounded_context
lifecycle: ACTIVE | DEPRECATED | RETIRED
```

The implementation repository path is:

```text
services/authorization-service/contracts/permissions/permission-catalog.yaml
```

with a versioned schema and CI validation. Authorization projects the reviewed catalog into its private persistence for bounded evaluation/query purposes; the Git contract remains the definition/change authority and the protected bounded context remains the semantic owner.

Permission-key syntax is fixed:

```text
^[a-z][a-z0-9]*(?:\.[a-z][a-z0-9]*)+$
maximum length: 128 characters
```

Lifecycle semantics:

- `ACTIVE` — may be evaluated and newly assigned where the caller is authorized;
- `DEPRECATED` — remains evaluatable for existing assignments during migration, may be removed, but cannot be newly granted/assigned;
- `RETIRED` — cannot be assigned and fails closed if referenced for authorization;
- unknown keys fail closed;
- a key is never reused for a different semantic meaning after deprecation/retirement.

Promotion to `RETIRED` is blocked while an immutable SYSTEM-role definition still requires the key or while rollout compatibility would create an unsafe policy gap.

Current tenant-management permission keys include:

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

There is no tenant-facing `authorization.audit.read` permission/API in v1.

Current platform permission keys are:

```text
platform.tenant.create
platform.tenant.suspend
platform.tenant.resume
platform.tenant.restore
platform.legal_hold.manage
```

Platform permissions are not tenant permissions and never act as wildcard bypass for tenant/resource authorization.

## 3. Tenant Role model

Roles attach to `TenantMembership`, never directly to global User. One Membership may have multiple tenant-scoped Roles. v1 has no role inheritance and no wildcard permission assignment.

Evaluation precedence is fixed:

```text
Direct Membership Deny
> Direct Membership Grant
> Role-derived Grant
> Default Deny
```

Inactive/suspended/removed/deleted Membership has no tenant permissions once the corresponding Authorization lifecycle projection is applied. Tenant authorization lifecycle `SUSPENDED`, `DELETING`, or `DELETED` fails closed regardless of retained restorable/auditable Role rows.

### SYSTEM Roles

SYSTEM Roles are server-owned and immutable to tenant callers. Tenants cannot rename, archive, delete, or alter their permission policy.

Current SYSTEM Roles:

- `tenant_owner` — every current `ACTIVE` tenant permission;
- `tenant_admin` — every current `ACTIVE` tenant permission except `tenant.delete` and `membership.owner.assign`;
- `tenant_member` — `tenant.read`, `membership.read`, `role.read`.

“All tenant permissions” is implemented from the reviewed catalog as explicit materialized/system-managed mappings. It is not a user-defined wildcard assignment.

### Custom Roles

Custom Role technical ID is UUIDv4. Lifecycle:

```text
ACTIVE -> ARCHIVED
```

There is no ordinary hard-delete API. `ARCHIVED` stops contributing authorization immediately, cannot receive new assignments or policy edits, and remains only for bounded audit/history. Archived identifiers and normalized names are not reused.

Custom Role fields:

```text
name:        trim + NFC; 1..80 Unicode code points; control characters rejected
display:     original normalized case preserved
name_key:    normalized display name -> Locale.ROOT lowercase
description: <=500 Unicode code points; control characters rejected
version:     optimistic concurrency version
```

Custom Role name uniqueness is case-insensitive per tenant through `(tenant_id, name_key)`. SYSTEM Role names are reserved. Stale `version` mutation returns `STALE_ROLE_VERSION`.

Hard limits:

```text
custom Roles per tenant:             100
permissions per custom Role:         200
Roles per Membership:                 20
direct overrides per Membership:     100
semantic mutations per bulk request: 100
pagination default:                   50
pagination maximum:                  200
```

All list operations use deterministic pagination/order and bounded fields.

## 4. Direct Membership overrides and privilege-escalation prevention

Direct override scope is exactly:

```text
(tenant_id, membership_id, exact permission_key) -> GRANT | DENY
```

v1 has no resource-instance scope, expression language, conditional policy, expiration, TTL, or caller-selected evaluation script. At most one active override exists for a Membership+permission. Removal is explicit; overrides do not expire into a different authorization result.

Management cannot create authority the actor does not possess:

- adding a permission to a custom Role requires the actor to currently possess that permission;
- assigning a custom Role requires the actor to possess every effective permission the Role would grant, regardless of the target Membership's current direct denies;
- creating a direct `GRANT` requires the actor to possess the granted permission;
- removing a direct `DENY` is privilege-elevating and requires the actor to possess that permission;
- removing a permission from a Role or removing a direct `GRANT` is non-elevating and requires only the corresponding management permission;
- `tenant_owner` assignment/removal additionally requires `membership.owner.assign` and remains subject to owner safety.

No caller can submit a role/permission snapshot to prove these conditions. Authorization evaluates them from current authoritative state in the same local consistency boundary as the mutation.

## 5. gRPC contract surface

The Authorization-owned versioned Protobuf surface is grouped logically as follows.

### Runtime security evaluation

```text
CheckPermission
CheckPlatformPermission
```

### Management reads

```text
ListPermissions
ListRoles
GetRole
GetMembershipAuthorization
```

### Tenant management writes

```text
CreateRole
UpdateRole
ArchiveRole
ReplaceRolePermissions
AssignRoleToMembership
RemoveRoleFromMembership
SetMembershipPermissionOverride
RemoveMembershipPermissionOverride
```

### Identity lifecycle/provisioning commands

```text
ProvisionTenantOwner
ProvisionTenantMember
ApplyTenantLifecycle
PrepareMembershipRemoval
FinalizeMembershipRemoval
CancelMembershipRemovalPreparation
```

No public/admin `ExplainPermission` API exists in v1. Internal telemetry/audit may record bounded safe reason categories but never exposes a complete policy-resolution trace to ordinary callers.

## 6. `CheckPermission` authoritative contract

`CheckPermission` request contains only:

```text
tenant_id
membership_id
permission_key
```

It does not accept `user_id`, Role IDs, permission lists, owner counts, resource attributes, policy expressions, caller-selected cache state, or an `allowed` override.

Only an approved resource-owning workload identity may call the operation for its registered permission namespace. The resource service performs local JWT/context/syntax reject-only checks and final resource/domain invariants; Authorization is authoritative only for the permission decision.

Success semantics are deliberately unambiguous:

```text
RPC success                         = ALLOW
PERMISSION_DENIED / AUTHORIZATION_DENIED = authoritative DENY
```

There is no successful `allowed=false` response. A successful response returns no Role/permission snapshot.

Runtime contract:

```text
deadline:        300 ms maximum
attempts:        1
wait-for-ready:  off
automatic retry: none
permission cache:none
stale fallback:  none
failure mode:    fail closed
```

Dependency/open-breaker failure maps to `UNAVAILABLE / AUTHORIZATION_UNAVAILABLE`. Healthy service saturation maps to `RESOURCE_EXHAUSTED / AUTHORIZATION_OVERLOADED` and callers treat it as fail-closed dependency unavailability, not permission denial.

No Authorization Kafka invalidation topic, Bloom filter, signed permission-list authority, permission-result cache, or stale allow fallback exists in v1.

## 7. Tenant management authentication and authorization

Browser management traffic reaches Authorization only through the reviewed Web BFF REST facade. The BFF sends the current Identity access JWT with exact audience:

```text
authorization-service
```

Authorization validates the JWT locally using the approved RS256/public-bundle contract and derives trusted `sub`, `tenant_id`, `membership_id`, and `sid`. Actor/tenant/Membership identity supplied in a mutation payload is never authority.

Management access additionally requires the approved BFF workload identity through Istio/NetworkPolicy policy. Roles/permissions are never trusted from the JWT.

Authorization evaluates management permission locally inside the same service/process; it does **not** perform a self-gRPC `CheckPermission` call.

Operation-to-management-permission mapping:

| Operation | Required management permission |
| --- | --- |
| `ListPermissions`, `ListRoles`, `GetRole` | `role.read` |
| `GetMembershipAuthorization` | `membership.read` |
| `CreateRole` | `role.create` |
| `UpdateRole` | `role.update` |
| `ArchiveRole` | `role.archive` |
| `ReplaceRolePermissions` | `role.permission.manage` |
| `AssignRoleToMembership`, `RemoveRoleFromMembership` | `membership.role.assign` |
| `SetMembershipPermissionOverride`, `RemoveMembershipPermissionOverride` | `membership.permission.manage` |

Owner Role assignment/removal additionally requires `membership.owner.assign` and §9 owner safety.

`GetMembershipAuthorization` is explicitly a non-authoritative administration/UX snapshot. It cannot replace `CheckPermission` for access decisions, and its contract marks this status. Read output is bounded/paginated and exposes no secret, JWT, raw audit internals, or unrestricted evaluation trace.

## 8. Administration quota, idempotency, and transaction semantics

ADR-0024 `AUTH_ADMIN_WRITE` quota is evaluated before the PostgreSQL mutation transaction. Bulk cost is:

```text
cost = max(1, semantic_mutation_count)
maximum semantic_mutation_count per request = 100
```

For `ReplaceRolePermissions`, semantic mutation count is the actual set delta (additions + removals), not the final Role size. A delta above 100 is rejected and must be performed through separately authorized bounded requests. The Role's final permission count must still remain <=200.

Quota consumption is not refunded if later database validation/commit fails. This prevents failure/retry patterns from resetting abuse pressure. PostgreSQL mutation itself is atomic: the full request commits or none of its business mutation commits; partial-success mutation responses are prohibited.

Every write uses a canonical lowercase UUIDv4 `request_id`. Security-sensitive intent fingerprint is purpose-separated/versioned HMAC-SHA-256 over canonical actor/scope/operation/payload identity:

- equal request ID + equal fingerprint -> original committed result;
- same request ID + different fingerprint -> `ALREADY_EXISTS / REQUEST_ID_CONFLICT`;
- idempotency/security evidence is retained at least 35 days;
- fingerprint/key material never enters logs, events, or caller-visible errors.

Purpose-specific HMAC key material follows the approved local OpenBao/External-Secrets read-only mounted secret boundary; request processing does not add an OpenBao hot-path RPC.

## 9. Owner-safety membership and Role mutation protocol

Every active tenant retains at least one effective owner. Owner safety has one tenant-scoped serialization domain shared by:

- `PrepareMembershipRemoval` reservation creation/resolution;
- `tenant_owner` assignment;
- `tenant_owner` removal/demotion;
- conflicting owner-role mutations for a reserved Membership.

A race-prone read-then-write owner count is prohibited. Local Authorization owner-role mutation locks/serializes the tenant owner-safety guard and current active reservations in the same transaction before deciding/committing. A mutation that could leave zero effective owners returns:

```text
FAILED_PRECONDITION / LAST_TENANT_OWNER
```

No `force=true`, caller-provided owner count, role snapshot, or reservation expiry can bypass the invariant.

### Identity `PrepareMembershipRemoval`

`PrepareMembershipRemoval` is callable only by the approved Identity workload and binds canonical UUIDv4 `request_id`, `tenant_id`, and `membership_id`. Authorization atomically:

1. resolves current authoritative role state;
2. excludes all already-active removal reservations from effective owner capacity;
3. rejects an unsafe final-owner removal;
4. otherwise persists one durable idempotent reservation;
5. blocks conflicting owner-role assignment/demotion/removal for the reserved Membership until resolution.

Contract:

```text
deadline:        300 ms maximum
attempts:        1
wait-for-ready:  off
automatic retry: none
cache/fallback:  none
failure mode:    fail closed
```

A reservation never auto-expires into allow state. Identity resolves it durably through:

- `FinalizeMembershipRemoval` after Identity committed Membership `REMOVED`;
- `CancelMembershipRemovalPreparation` only after Identity durably knows local removal did not commit.

Finalize/cancel use the 900ms one-attempt/no-immediate-retry durable-command baseline. Unresolved reservations remain conservative and alert rather than silently freeing owner capacity.

## 10. Identity provisioning and tenant lifecycle

Identity source-owned durable intent/Transactional Outbox drives Authorization lifecycle commands. Equal idempotent replay returns the original committed result; conflicting request reuse returns `REQUEST_ID_CONFLICT`.

### Initial owner provisioning

`ProvisionTenantOwner` assigns SYSTEM Role `tenant_owner` to the creator Membership. ACK means required owner authorization state is durably committed. Identity keeps Tenant `PROVISIONING` until ACK.

### Invitation member provisioning

`ProvisionTenantMember` assigns SYSTEM Role `tenant_member` to an accepted ACTIVE Membership. Invitation payload never carries arbitrary Role/permission authority. Until provisioning commits, Authorization default deny is safe authority.

### Tenant lifecycle

`ApplyTenantLifecycle` projects the canonical Identity lifecycle. A suspended/deleting/deleted tenant denies authorization and management mutations as defined by lifecycle policy while preserving only bounded restorable/auditable state. Identity does not mark Tenant `DELETED` until the required Authorization cleanup/deny state ACK.

A permitted Identity restore re-enters reconciliation; Authorization never reconstructs custom policy from guesses or Identity role copies.

Provisioning/lifecycle command baseline unless a stricter operation above applies:

```text
deadline:        900 ms
attempts:        1
wait-for-ready:  off
immediate retry: none
```

Durable retry belongs to the Identity dispatcher/outbox; gRPC client and mesh do not duplicate it.

## 11. Platform capability authority

`platform_admin` is a global SYSTEM capability profile, not a tenant Role. It contains the explicit current platform permission set in §2 and has no wildcard meaning.

`CheckPlatformPermission` is the authoritative platform capability check used by Identity for platform-admin-only tenant lifecycle and legal-hold entry points. Request contains exactly:

```text
user_id
permission_key
```

Only the approved Identity workload may call it. `permission_key` must be a current `ACTIVE` PLATFORM permission. Unknown/deprecated-for-new-use/retired/wrong-scope keys fail closed as applicable.

Success/deny behavior mirrors `CheckPermission`:

```text
RPC success                         = ALLOW
PERMISSION_DENIED / AUTHORIZATION_DENIED = authoritative DENY
```

Dependency contract:

```text
deadline:        300 ms maximum
attempts:        1
wait-for-ready:  off
automatic retry: none
cache/fallback:  none
failure mode:    fail closed
```

`platform_admin` never bypasses tenant/resource permission or domain invariants. Platform profile assignment/revocation is excluded from ordinary tenant/BFF APIs and is performed only through a separately privileged, JIT-access-controlled, audited platform administration/bootstrap workflow. Exact environment/operator identity remains reviewed deployment configuration; ordinary application identities cannot grant themselves platform authority.

## 12. Stable error taxonomy

At minimum the Authorization contract defines these stable machine codes:

```text
AUTHORIZATION_DENIED
AUTHORIZATION_UNAVAILABLE
AUTHORIZATION_OVERLOADED
ROLE_NOT_FOUND
ROLE_NAME_CONFLICT
SYSTEM_ROLE_IMMUTABLE
ROLE_ARCHIVED
PERMISSION_UNKNOWN
PERMISSION_RETIRED
MEMBERSHIP_NOT_ACTIVE
TENANT_NOT_AUTHORIZABLE
PERMISSION_ESCALATION_FORBIDDEN
LAST_TENANT_OWNER
STALE_ROLE_VERSION
REQUEST_ID_CONFLICT
```

Internal exception messages, stack traces, SQL/provider/database text, role contents, and security policy internals are never copied into the stable contract.

## 13. Durable audit

Every Authorization management write, platform-authority assignment/revocation, and privilege-sensitive management/platform rejection writes durable authorization audit evidence according to the current required-security-audit contract.

Audit contains only bounded approved fields such as:

```text
audit_id
request_id when applicable
trusted actor user/workload identity
tenant_id when applicable
action
target technical identifiers
bounded before/after summary or digest
result + stable machine code
UTC microsecond timestamp
policy/catalog version
```

Raw JWTs, tokens, Contact/email/phone values, arbitrary request payloads, SQL binds, HMAC material, or unrestricted exception text are prohibited.

A reason is mandatory for:

- owner assignment/removal;
- direct grant/deny set/remove;
- Role-permission mutation;
- platform authority operation.

Reason policy:

```text
trim + NFC
1..500 Unicode code points
control characters including CR/LF rejected
```

Security audit evidence is retained at least 365 days unless a stricter approved retention/legal-hold policy applies.

Routine hot-path `CheckPermission` allow/deny outcomes are bounded telemetry/metrics/traces and are **not** synchronously appended to the durable management-audit ledger on every request; doing so would make the online authorization SLO depend on an additional write. A future requirement for per-check durable audit needs an explicit reviewed performance/security decision.

## 14. PostgreSQL model and transaction boundaries

Authorization uses jOOQ/JDBC without JPA. Logical structures include at least:

```text
permission_definition
role
role_permission
membership_role
membership_permission_override
tenant_authorization_projection
membership_authorization_projection
owner_safety_guard
membership_removal_reservation
platform_profile_assignment
idempotency_record
authorization_audit
```

Exact migration/table names may evolve only through Flyway while preserving these ownership/invariant semantics.

Tenant-owned tables use forced RLS. Runtime role is service-only, non-owner `NOSUPERUSER NOBYPASSRLS`. Tenant context is derived from validated authentication/workload context and set through the canonical parameterized transaction-local mechanism; session-scoped tenant state on pooled connections is prohibited.

`CheckPermission` uses a very short read transaction/statement boundary with permission SQL budget <=100ms and database acquisition budget <=50ms. Critical query/index plans require representative `EXPLAIN (ANALYZE, BUFFERS)` or equivalent evidence. Owner-safety and management writes use the shortest transaction that atomically enforces their invariants.

No gRPC/HTTP/Kafka/Redis/provider I/O occurs inside Authorization PostgreSQL transactions. In particular, `AUTH_ADMIN_WRITE` quota executes **before** the database transaction; no DB lock is held during Redis I/O.

## 15. Erasure and retention

Authorization is a required ADR-0028 data-subject-erasure participant. For an erased User it removes/anonymizes all service-owned subject-linked authority, including:

- MembershipRole assignments associated with the User's Membership projections;
- direct Membership overrides;
- subject-linked Membership authorization projection data;
- `platform_profile_assignment` for that User;
- other subject-linked operational authorization state not required for tenant-owned policy.

Tenant Role/RolePermission definitions remain because they are tenant-owned policy, not personal profile data.

Where security audit facts must be retained under policy/legal hold, the direct User link is physically removed or irreversibly replaced by a service-scoped erasure pseudonym that cannot restore application authority. Retained audit never keeps Contact/email/phone values because those values do not enter Authorization in the first place.

Erasure receipt/progress payload is non-PII and idempotent. Legal hold may retain the minimum required audit facts but never preserves or restores a platform/tenant authorization assignment for a non-authenticatable erased User. Restore replays/reconciles erasure evidence before traffic opens.

## 16. Semantic quotas

Authorization owns its management quota state in its ACL-isolated `security-redis` namespace under ADR-0024. `AUTH_ADMIN_WRITE` current numeric policy remains:

```text
actor + scope:          capacity 120; refill 2 / 1s; cleanup horizon 1h
tenant/platform scope:  capacity 600; refill 5 / 1s; cleanup horizon 1h
Redis budget:           75 ms
attempts:               1
retry:                  none
```

Evaluation is atomic, pseudonymous, dual-clock fail-closed (`trusted_app_time` + Redis `TIME`, <=2s skew), monotonic, and has no TTL security reset. Bulk cost follows §8 and therefore cannot turn one arbitrarily large management request into one quota unit.

Production administration remains disabled until atomicity/time-safety/outage/abuse and >=2x peak quota-load evidence passes. `production-single-server` additionally verifies Redis AOF/restart/loss behavior and fail-closed recovery without a Sentinel claim; `production-ha` additionally verifies Sentinel failover.

## 17. Runtime, deployment, and workload identity

Production identity and transport defaults are profile-independent:

```text
namespace:       platform-apps
Deployment:      authorization-service
Service:         authorization-service
ServiceAccount:  authorization-service
principal:       prod.sajtech.internal/ns/platform-apps/sa/authorization-service
application gRPC local convention: 9090
management:      separate configured port
inbound message cap: 64 KiB
metadata cap:        16 KiB
```

Deployment topology follows the selected production profile:

```text
production-single-server:
  replicas: 1
  HPA: disabled
  availability PDB: disabled
  node-failover claim: none

production-ha:
  minimum replicas: 3
  PDB minAvailable: 2
  HPA initial min/max: 3 / 12 after load evidence
  topology spread: required by the HA target
```

Production uses immutable signed image digest, non-root execution, `allowPrivilegeEscalation=false`, default capability drop, `RuntimeDefault` seccomp, read-only root filesystem where compatible, finite resources, deny-by-default NetworkPolicy, Istio Ambient STRICT mTLS, and least-privilege AuthorizationPolicy.

Inbound policy permits only explicitly registered workload identities/operations. In particular, resource services call only their approved `CheckPermission` surface, Identity calls lifecycle/platform-authority operations it owns, and Web BFF calls approved management operations. Workload identity never substitutes for end-user/tenant authorization.

Liveness is process/local-runtime only. Readiness requires safe ability to evaluate intended traffic, including database reachability, compatible loaded permission catalog/projection, usable approved JWT verification bundle for management traffic, and required local security configuration. A transient unrelated remote dependency is not added to liveness/readiness.

Production service target remains:

```text
availability >=99.95% rolling 30d
p95 <=100 ms
p99 <=200 ms
steady engineering target p95<=75 ms / p99<=150 ms
server queue wait <=25 ms before shedding
Hikari acquisition p99 <25 ms under validated steady load
```

Server enforces bounded global and per-caller-workload concurrency, no unbounded application queue, fair overload shedding, and pool/HPA maxima inside the service PostgreSQL connection budget. In `production-single-server`, HPA remains disabled and the validated fixed replica/pool/concurrency envelope stays inside the same database and host budget.

## 18. Verification requirements

Repository and release evidence covers at least:

- Protobuf/Buf compatibility for every §5 operation and stable error metadata;
- exact permission-key syntax, catalog schema/owner/scope/lifecycle, unknown/retired fail-close, deprecated no-new-grant behavior, key non-reuse and SYSTEM-role catalog compatibility;
- SYSTEM Role immutability and exact current SYSTEM Role permissions;
- custom Role name normalization/case-insensitive uniqueness/reserved names/version/limits/archive behavior;
- direct override precedence/uniqueness/no TTL/condition/resource scope;
- exact tenant management permission mapping and local JWT `aud=authorization-service` verification with no role/permission claim trust;
- privilege-escalation prevention for Role permissions, Role assignment, direct grants, deny removal, and owner assignment;
- exact administration hard limits, deterministic pagination, bulk-delta counting, quota-before-DB, no quota refund, and atomic no-partial-success mutation;
- request-id/HMAC fingerprint equal replay/conflict and >=35d retention;
- permission precedence, cross-tenant deny and forced RLS including pooled-connection context reuse;
- `CheckPermission` success-is-ALLOW/deny-status contract, wrong workload/namespace permission negative tests, no cache/retry/Kafka/stale fallback, overload/error mapping, and query plans;
- tenant owner-safety serialization for concurrent local owner mutations plus simultaneous Identity reservations; reservation-vs-owner-assignment races; no unsafe auto-expiry; crash/replay/finalize/cancel recovery;
- provisioning/lifecycle replay/conflict, default `tenant_member`, tenant lifecycle deny and no Identity role-copy authority;
- `CheckPlatformPermission` exact permission scope, Identity-only workload, explicit platform profile, no tenant bypass, outage/overload fail-close, and platform assignment/revocation JIT/audit controls;
- durable audit field/retention/reason/PII controls and proof hot-path checks do not accidentally add synchronous audit writes;
- jOOQ/JDBC-only persistence boundary, Flyway migration safety, no JPA/generated-type leakage to Domain/Application, no remote I/O in DB transactions;
- Authorization participant erasure removes tenant/platform subject authority while preserving tenant-owned policy and emits only non-PII receipts;
- semantic quota atomicity/time and >=2x peak management-quota evidence, plus profile-specific Redis recovery/failover evidence;
- online p95/p99/SLO, bounded queue/fair share, breaker recovery, and >=2x projected peak;
- `production-single-server`: one-replica/no-HPA/no-availability-PDB render, whole-host/reboot/recovery behavior, shared PostgreSQL recovery behavior, and no node-failover claim;
- `production-ha`: replica/node-loss, PostgreSQL failover, Sentinel failover, PDB/HPA/topology-spread behavior;
- hardened Docker/Helm/GitOps render, independent ServiceAccount, NetworkPolicy/Istio positive+negative policy, profile-correct probes/replicas/PDB/HPA/securityContext;
- PII-safe structured telemetry, bounded low-cardinality metrics, SLO/burn/owner-reservation/audit-failure alerts;
- Spotless, SpotBugs, ArchUnit, Semgrep, dependency verification, unit/integration/contract/schema/security checks and immutable artifact/SBOM/signature/provenance release gates once implementation exists.

## 19. Rollback considerations

Rollback must preserve permission-key non-reuse, SYSTEM Role immutability, custom Role/audit identifiers, owner-safety reservations, platform-authority assignments/revocations, idempotency evidence, erasure effects, tenant RLS, and stable error/Protobuf compatibility.

Rollback MUST NOT reintroduce stale/cached allow, Kafka permission invalidation, retry amplification, caller-supplied role/owner snapshots, wildcard/custom inheritance, resource-condition policy, tenant-admin platform bypass, read-only race-prone last-owner checks, self-gRPC management authorization, `allowed=false` ambiguity, JPA model leakage, unsafe database downgrade, erased User authority, or a production profile that bypasses quota/workload/JWT security controls.

## Current repository implementation evidence

The current branch contains the first executable Authorization repository slice under `services/authorization-service/`, including contracts, PostgreSQL/Flyway persistence with forced RLS, online checks and management commands, owner-removal reservation safety, Redis quota, audit/idempotency, readiness/observability, hardened deployment artifacts, dependency locks, Semgrep/security workflow, and unit/architecture/integration test sources. Local strict Gradle/unit/architecture/SpotBugs/bootJar verification passes. Local PostgreSQL/Redis Testcontainers execution is `NOT VERIFIED` on the current Docker-unavailable host. Protected PR baseline run `32261626399` passed the Authorization PostgreSQL/Redis integration, architecture, SpotBugs, Buf, Semgrep, OSV, Gitleaks, Helm/render, observability-artifact, runtime-image, generated-file, and aggregate repository gates on implementation head `7de8b17`. Deployed runtime and production readiness remain `NOT VERIFIED`.
