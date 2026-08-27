# Identity Service Architecture

## 1. Ownership

Identity Service owns identity/account lifecycle and authentication state:

- User/Profile/Contact;
- password Credential;
- authentication and account recovery;
- Session/RefreshFamily and access-token issuance;
- MFA/TOTP/recovery codes;
- ExternalIdentity binding;
- Tenant/TenantMembership/Invitation lifecycle;
- active-tenant/session context;
- Identity-owned registration/recovery challenges;
- data-subject erasure coordination.

Authorization owns tenant/platform permission policy. Notification owns delivery. Compromised Password owns the immutable compromised-password reference lookup. Identity never reads another service database.

Implementation target:

```text
services/identity-service
base package: com.sajtech.identity
```

Domain/Application remain framework-independent. Mutable relational state uses Identity-owned PostgreSQL database/runtime role/migration role/Flyway history and current forced-RLS rules.

## 2. Primary authority references

Detailed current semantics are owned by:

- ADR-0008/0009 — registration/locale/resend;
- ADR-0012 — account/session/MFA/external identity/tenant lifecycle;
- ADR-0023 — JWT signing-key lifecycle;
- ADR-0024 — semantic quotas;
- ADR-0028 — erasure;
- ADR-0040 — compromised-password HIBP/SQLite contract;
- ADR-0042 — production profile;
- ADR-0043 — trusted client-address context;
- ADR-0044 — Day-One observability.

This service document adds implementation routing/context and MUST NOT redefine those authorities with divergent copies.

## 3. Authentication and password rules

Password storage uses the Technology Baseline Argon2id profile. Password-only authentication uses the current Identity validation/normalization rules and non-enumerating outcomes from ADR-0012.

Create/change/reset performs compromised-password screening outside DB transactions:

```text
NFC password
-> UTF-8
-> SHA-1 locally only for HIBP screening
-> first 20 bits / five uppercase hex chars
-> Compromised Password gRPC lookup
-> returned 35-hex suffix + positive count rows
-> exact full SHA-1 comparison inside Identity
```

Raw password and full screening SHA-1 never leave Identity. SHA-1 is **not** password storage and is not reused as a credential verifier. Malformed/truncated/stale/unavailable lookup fails closed. There is no runtime HIBP call from Identity or Compromised Password.

Compromised Password dependency remains 900ms overall, one attempt, no automatic retry/fallback, bounded concurrency and cancellation where supported.

Password change/recovery retains current reauthentication/MFA/session-revocation semantics from ADR-0012. The executable slice uses purpose-separated HMAC-only eight-digit recovery challenges with a 10-minute lifetime, five failed proofs, 60-second resend spacing, encrypted Notification outbox handoff, all-family reset revocation, and current-family rotation plus other-family revocation for an authenticated change. Compromised-password I/O and Argon2id hashing remain outside the committing transaction.

## 4. Sessions, tokens, external identity, MFA

Current ADR-0012/0016/0023 rules remain unchanged, including:

- server-side session/refresh authority;
- bounded idle/absolute lifetimes and rotation/reuse detection;
- browser never receives provider/internal refresh credentials;
- exact JWT issuer/audience/time/key lifecycle;
- BFF-only audience token brokerage under server-owned allow-list;
- Google/external identity binds stable issuer+subject, not email-only auto-link;
- provider authentication is primary proof only; active TOTP still requires current MFA continuation;
- no user-selectable Email/SMS downgrade around active TOTP;
- recovery-code/TOTP anti-replay and current assurance-age rules.

Implementation MUST NOT invent a local permission snapshot, stale authorization cache, or provider email auto-link shortcut.
Current repository implementation of this bounded slice includes local-password `AuthenticateLocal`, rotating `RefreshSession`, current/all logout, Session/RefreshFamily persistence, and the `IssueAudienceAccessToken` contract boundary. Local-password authentication applies the ADR-0024 exact-IP hard gate before credential proof, charges the contact/subject failure bucket only after failed proof, performs an Argon2id dummy verification for an unknown Contact, and keeps Redis outside the PostgreSQL transaction.

The implemented Session/RefreshFamily model uses 256-bit CSPRNG session/refresh secrets, Base64URL without padding, digest-only purpose-separated HMAC-SHA-256 refresh persistence, seven-day idle and 30-day absolute expiry, deterministic oldest-family revocation at the 20-active-family limit, rotation with predecessor retention, family-wide reuse revocation, and bounded post-expiry security-evidence cleanup. Raw refresh credentials are returned only across the internal BFF contract and never persisted or emitted to telemetry.

The current TOTP slice implements the ADR-0012 MFA gate. A successful local-password primary proof for a User with active TOTP creates only a five-minute opaque pre-auth challenge and returns no Session, access token, or refresh credential. A new primary proof supersedes the previous active challenge; successful completion or five failed proofs terminates it. TOTP uses a 256-bit secret encrypted at rest with AES-256-GCM and User+enrollment purpose binding, HMAC-SHA-256, six digits, a 30-second step, and a ±1-step window. The last accepted enrollment timestep is updated under the User/enrollment transaction lock so the same step cannot be replayed.

Enrollment, replacement, disable, and recovery-code rotation require primary authentication age <=5 minutes; replacement/disable/rotation also require a current TOTP or single-use recovery proof. Enrollment confirmation returns exactly ten independent 80-bit Base32 recovery codes once, while Identity persists only purpose-bound HMAC digests. Recovery consumption is atomic. Material MFA-state changes rotate the current refresh/browser security state and revoke every other active family with `MFA_CHANGED`. Active MFA also requires current MFA proof during password recovery and <=5-minute MFA assurance for authenticated password change. MFA quota calls remain outside PostgreSQL transactions and retain exact-IP hard identity, separate aggregate pressure, no-TTL/noeviction/capacity/time fail-closed semantics. Identity exposes only bounded MFA operation/outcome/in-flight and aggregate proof-rejection telemetry.

The bounded retention worker deletes expired pending-enrollment rows as soon as their ten-minute lifetime has elapsed, erasing their encrypted TOTP secret material, and deletes expired login-challenge records after the 35-day security-evidence horizon. Both paths use deterministic batches with `FOR UPDATE SKIP LOCKED`; append-only security audit evidence remains separate and retains its governing policy.

The current ExternalIdentity slice accepts only the typed Web BFF Google evidence contract. It binds the canonical Google issuer and provider subject, a UUIDv4 request identity, 256-bit evidence identity, two-minute evidence lifetime, explicit metadata version, trusted client address, and canonical bounded optional metadata into a purpose-separated HMAC fingerprint. Request/evidence advisory locks and unique constraints make committed equal replay deterministic and reject conflicting reuse. Spent evidence and its AES-256-GCM encrypted replay result remain for at least ten minutes; the bounded retention worker removes expired replay material without deleting the durable security audit.

Unknown subjects create a `PENDING` User. A provider-verified email is inserted as a verified primary Contact only after the same canonical Contact lock used by registration/profile flows proves it is unowned and unreserved; collision returns account-link-required and never auto-links. Unverified/missing email creates no Contact. Profile names remain untrusted suggestions and do not activate the account. The pending User becomes `ACTIVE` only after Identity-owned profile confirmation, a verified Contact, and an active external binding all exist. Existing active TOTP produces only the existing MFA continuation with `GOOGLE_OIDC` primary-method binding. Link/unlink requires <=5-minute primary authentication and current MFA assurance where applicable; unlink rejects the last authentication method and successful mutation rotates the current refresh credential and revokes other families.

ADR-0023 signing machinery is local RS256/RSA-3072 with an active private key mounted read-only into Identity and a bounded `current`/`next`/`previous` public verifier bundle. Candidate key snapshots are validated before atomic activation, the active private `kid` must already match the current published public key, and an existing key identifier cannot be rebound to different key material during refresh; rotation uses a new identifier while the last valid snapshot remains authoritative after a rejected candidate. The current repository implementation supports both `authenticated_onboarding` and `tenant_authenticated` Session/RefreshFamily modes. After primary local authentication, Identity automatically selects the only selectable Membership or a still-valid last-selected Membership; otherwise the session remains onboarding. `IssueAudienceAccessToken` requires current tenant-authenticated Session/RefreshFamily state plus an exact server-owned audience allow-list before minting an ordinary tenant/resource JWT.

The authentication listener is separately gated by `IDENTITY_AUTHENTICATION_RUNTIME_ENABLED=false` by default. Tenant/Authorization runtime behavior is additionally gated by `IDENTITY_TENANT_RUNTIME_ENABLED=false`; the Helm policy opens Authorization egress only when that tenant runtime gate is enabled. Inbound gRPC uses both a per-connection transport cap and a separately configurable global zero-queue admission ceiling; saturation fails immediately with bounded `RESOURCE_EXHAUSTED` behavior rather than creating an unbounded application queue. These are additive runtime rollback controls; applied Flyway migrations are not downgraded. Authentication tests cover onboarding and tenant-aware session behavior, while refresh-predecessor reuse remains fail-closed.

## 5. Tenant/Membership lifecycle and Authorization

Identity owns Tenant/TenantMembership lifecycle state, invitation, active-tenant selection, and lifecycle intent.

Authorization owns permission state/evaluation and owner-safety reservations. Identity uses the current typed durable commands and the registered fail-closed `CheckPermission` edge for tenant-lifecycle administration checks such as `membership.role.assign`. Identity does not copy Role/permission authority into its database/JWT/business model.

Lifecycle flows that require Authorization/Notification use current dependency-registry criticality, one-attempt request semantics, and durable Outbox/reconciliation where specified. Remote I/O is outside Identity DB transactions.

A tenant/membership state transition does not become externally authoritative before required local/remote durable conditions from current ADRs are met.

The executable lifecycle slice includes platform-authorized suspend/resume/restore, owner-authorized delete, and Invitation decline/revoke/expire/reissue. Delete first records durable `DELETED` intent, projects `DELETING` to Authorization, revokes pending Invitations only after that deny-state acknowledgement, and reaches local `DELETED` only after the final Authorization acknowledgement. Restore is rejected after irreversible purge has begun, re-enters `PROVISIONING`, and becomes `ACTIVE` only after Authorization reconciliation. Equal request replay is idempotent, conflicting lifecycle intent is rejected, invitation expiry is bounded and observable, and no remote authorization call runs inside an Identity transaction.

## 6. Semantic quota integration

Identity quota-owning operations implement ADR-0024 exactly.

Network context comes only from approved BFF workload under ADR-0043 and contains one exact canonical IP.

Identity derives:

```text
client_ip_exact:
  IPv4 /32
  IPv6 /128
  -> hard pre-auth quota identity where policy requires

client_network_aggregate:
  IPv4 /24
  IPv6 /64
  -> separate abuse/allocation pressure, not sole v1 hard 429 identity
```

Identity MUST implement:

- atomic multi-dimension Redis decision;
- service/operation HMAC domain separation;
- <=2s app/Redis skew;
- local wall-vs-monotonic Clock Safety Guard for common-mode host steps;
- host-time synchronization gate after boot/recovery and 60s safe re-arm after guard trip;
- `noeviction`, no security TTL reset, bounded cleanup;
- low-cardinality new-bucket allocation guard;
- `QUOTA_TIME_SOURCE_UNHEALTHY` / `QUOTA_CAPACITY_UNHEALTHY` as availability failures distinct from normal quota denial;
- >=30% validated Redis memory reserve and adversarial unique-subject/address load evidence.

Quota/network/raw subject identifiers never become ordinary telemetry labels.

## 7. Persistence and RLS

Identity owns one database and current profile-specific physical placement.

Mandatory:

- runtime role non-owner `NOSUPERUSER NOBYPASSRLS`;
- distinct migration/owner role;
- forced RLS for tenant-owned tables;
- transaction-local trusted tenant context;
- no cross-service DB credentials/SQL;
- no remote I/O while DB transaction/locks are held;
- deterministic bounded queries/pagination;
- Flyway expand/migrate/contract and rollback compatibility.

Single-server shares physical PostgreSQL only; logical ownership/isolation remain unchanged.

Current physical RLS classification for the implemented tenant slice is explicit:

- `identity_tenant_membership` and `identity_tenant_invitation` are tenant-owned tables and use forced RLS;
- `identity_tenant` is the global Tenant root/lifecycle registry. Tenant creation and pre-selection lifecycle resolution can occur before a tenant context exists, so it is a reviewed global Identity table rather than a tenant-owned RLS table;
- `identity_user_membership_query`, `identity_invitation_query`, and `identity_user_tenant_preference` are user-scoped global query/preference state required before active-tenant selection;
- `identity_authorization_outbox` and `identity_membership_removal_intent` are global durable coordination state because workers and post-remote-resolution paths must claim/resolve work across tenants without a caller tenant context;
- `identity_tenant_command_dedup` is global request-idempotency state because `CREATE_TENANT` begins before a tenant exists.

These global exceptions do not grant tenant or permission authority. Application queries still use exact validated User/Tenant/Membership/request predicates, bounded result sets, and Authorization-owned permission checks. New tenant-scoped business state MUST NOT be added to a global exception merely to avoid RLS; changing this classification requires explicit architecture review and RLS/pool-isolation evidence.

The current V5 migration also enforces persisted tenant-context identity at the database boundary: selected Session/RefreshFamily context and accepted Invitation context reference the exact `(tenant_id, membership_id, user_id)` Membership tuple. Direct SQL cannot bind another user's Membership even when the Tenant identifier matches.

## 8. Events and side effects

A local state change that must publish integration intent uses Transactional Outbox. Consumers/callbacks use stable request/event identities and idempotency according to owning contracts.

Notification durable acceptance is not delivery. Identity state that depends on verification/delivery follows current challenge/result semantics and does not infer provider delivery from submit success.

Kafka is never Identity business source of truth.

## 9. Erasure

ADR-0028 is authoritative. Erasure coordinates current Identity-owned state plus required participant actions and owner-safety/legal-hold constraints.

Restored historical state does not regain current authority before erasure/legal-hold reconciliation passes.

## 10. Runtime identity and workload security

```text
namespace:      platform-apps
Deployment:     identity-service
Service:        identity-service
ServiceAccount: identity-service
application:    gRPC 9090
management:     separate configured port
```

Only registered workloads/operations are reachable under deny-by-default NetworkPolicy and Istio authorization. Strict Ambient mTLS remains mandatory. Provider egress is limited to explicitly owned Identity integrations; Compromised Password/HIBP runtime egress is not allowed.

Single-server uses one replica/HPA off/availability PDB off. HA uses the current replicated target.

## 11. Day-One observability

ADR-0044 applies from the first executable commit.

Identity implements structured allow-listed JSON logs, Micrometer metrics/observations, OpenTelemetry traces, health/readiness, SLO/security alerts, and telemetry fault/privacy tests. The implemented Tenant lifecycle exposes bounded operation/outcome duration and in-flight telemetry, while global gRPC admission exposes in-flight, configured-limit, and rejection signals without request or subject identifiers.

Allowed bounded dimensions include operation/outcome/auth-method/MFA state category/dependency result/saturation categories where they do not reveal subject identity.

Never log/trace/label raw or pseudonymous values that reveal:

- password or HIBP SHA-1 prefix/suffix/full hash;
- token/cookie/session/refresh/MFA/recovery secret;
- email/phone/provider subject/User/Tenant/Membership/request ID except an explicitly approved safe audit path;
- raw client IP;
- SQL bind/full request/response/provider payload.

Trace/baggage/correlation is telemetry only. It never becomes authentication, tenant, Authorization, quota, idempotency, or audit authority.

Ordinary telemetry export failure does not fail an otherwise safe Identity operation; required authoritative audit/security state follows its durable contract.

## 12. Verification and Definition of Done

Identity implementation evidence covers, as applicable:

- registration/verification/login/recovery non-enumeration;
- Argon2id storage and HIBP SHA-1 screening-only separation;
- Compromised Password fail-closed source/freshness/lookup behavior;
- semantic quota exact/aggregate/common-clock/cardinality behavior;
- session/refresh/JWT lifecycle and replay/revocation;
- Google/external identity collision/no-auto-link;
- TOTP/recovery and no-MFA-downgrade;
- Tenant/Membership/invitation/owner-safety lifecycle;
- Authorization/Notification dependency failure/idempotency/Outbox semantics;
- forced RLS/cross-tenant/pool-reuse/cross-service privilege negatives;
- erasure/legal-hold/recovery behavior;
- strict mTLS/NetworkPolicy/wrong-workload negatives;
- Day-One logs/metrics/traces, PII canaries, correlation, and telemetry-backend outage;
- profile-correct container/GitOps/render/load/recovery evidence.

The first executable implementation is repository-complete only when source, contracts, migrations, tests, build/dependency locks, container/deployment/security policy, **observability**, and CI gates for the implemented slice exist. The current repository implementation includes registration, local-password and Google OIDC primary authentication, Session/RefreshFamily/JWT signing, ExternalIdentity establish/link/unlink/status, password change/recovery/reset, secure Profile/Contact read/update/add/verify/resend/primary/removal, TOTP MFA/recovery codes, complete Tenant/Membership/Invitation lifecycle, selection, Authorization coordination, tenant-authenticated session state, audience-token behavior, persistence of the Notification service notification identifier after durable handoff, and an idempotent terminal Notification result callback on a dedicated gated listener. Prior protected evidence remains commit-specific. Current local revision evidence covers twelve Flyway migrations through V12 Tenant lifecycle coordination, issuer+subject/email-collision negatives, transaction-local post-authentication tenant selection, non-owner forced-RLS negatives, immutable key identifiers, bounded gRPC admission, Tenant/password/Profile/MFA/ExternalIdentity telemetry, lifecycle acknowledgement/replay/purge and Invitation concurrency/expiry negatives, unit/contract/PostgreSQL/Redis integration tests, replay and quota negatives, Helm render hardening, and Prometheus/dashboard validation. OSV, container construction, protected CI, and deployed runtime evidence remain commit/environment-specific and are not inferred from source review. Existing local production-fidelity evidence predates the password/Profile/MFA/OIDC/Tenant-lifecycle revisions, so those deployed journeys and real Google-provider execution remain `NOT VERIFIED`. Erasure, load/recovery, production signing-key rotation, and production readiness also remain `NOT VERIFIED` until their owning gates execute.
