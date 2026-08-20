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

Password change/recovery retains current reauthentication/MFA/session-revocation semantics from ADR-0012.

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

ADR-0023 signing machinery is local RS256/RSA-3072 with an active private key mounted read-only into Identity and a bounded `current`/`next`/`previous` public verifier bundle. Candidate key snapshots are validated before atomic activation and the active private `kid` must already match the current published public key. The current branch implements both `authenticated_onboarding` and `tenant_authenticated` Session/RefreshFamily modes. After primary local authentication, Identity automatically selects the only selectable Membership or a still-valid last-selected Membership; otherwise the session remains onboarding. `IssueAudienceAccessToken` requires current tenant-authenticated Session/RefreshFamily state plus an exact server-owned audience allow-list before minting an ordinary tenant/resource JWT.

The authentication listener is separately gated by `IDENTITY_AUTHENTICATION_RUNTIME_ENABLED=false` by default. Tenant/Authorization runtime behavior is additionally gated by `IDENTITY_TENANT_RUNTIME_ENABLED=false`; the Helm policy opens Authorization egress only when that tenant runtime gate is enabled. These are additive runtime rollback controls; applied Flyway migrations are not downgraded. Authentication tests cover onboarding and tenant-aware session behavior, while refresh-predecessor reuse remains fail-closed.

## 5. Tenant/Membership lifecycle and Authorization

Identity owns Tenant/TenantMembership lifecycle state, invitation, active-tenant selection, and lifecycle intent.

Authorization owns permission state/evaluation and owner-safety reservations. Identity uses the current typed durable commands and does not copy Role/permission authority into its database/JWT/business model.

Lifecycle flows that require Authorization/Notification use current dependency-registry criticality, one-attempt request semantics, and durable Outbox/reconciliation where specified. Remote I/O is outside Identity DB transactions.

A tenant/membership state transition does not become externally authoritative before required local/remote durable conditions from current ADRs are met.

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

Identity implements structured allow-listed JSON logs, Micrometer metrics/observations, OpenTelemetry traces, health/readiness, SLO/security alerts, and telemetry fault/privacy tests.

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

The first executable implementation is repository-complete only when source, contracts, migrations, tests, build/dependency locks, container/deployment/security policy, **observability**, and CI gates for the implemented slice exist. Registration and local-password authentication/Session/RefreshFamily/JWT-signing have prior protected evidence through `main@a3766bd`. The current branch adds Tenant/Membership/Invitation lifecycle, selection, Authorization coordination, tenant-authenticated session state, and audience-token behavior with local static/unit/architecture verification; the expanded five-service protected PR baseline passed the current Identity integration/security/deployment-package gates on implementation head `7de8b17` in run `32261626399`; deployed runtime evidence remains `NOT VERIFIED`. MFA/OIDC/password-change/recovery/erasure, load/recovery, production signing-key rotation, and production readiness also remain `NOT VERIFIED` until their owning gates execute.
