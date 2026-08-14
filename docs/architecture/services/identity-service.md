# Identity Service Architecture

## 1. Ownership

Identity owns global User, Tenant, TenantMembership, membership lifecycle, profile/contact methods, local credentials, external identities, MFA enrollments/recovery material, authentication, sessions, token signing, active-tenant selection, and platform-global data-subject erasure coordination. Authorization roles/permissions are separate and remain authoritative in Authorization.

Base package: `com.sajtech.identity`.

The first executable implementation lives at `services/identity-service` and follows the independent-service build/release boundary from the engineering standards.

## 2. Registration, User lifecycle, profile, and Contacts

User lifecycle:

```text
PENDING
ACTIVE
SUSPENDED
DELETING
DELETED
```

Local v1 registration supports both EMAIL and PHONE. A new User is `PENDING`. It becomes `ACTIVE` only when the required profile is complete, at least one Contact is verified, and no blocking security/deletion condition exists. The first verified Contact becomes the one active primary Contact automatically.

Phone registration is implemented but remains disabled in staging/production until ADR-0020 provider/Notification/SMS readiness gates pass. The gate is server-owned typed configuration. Local development may enable the path with the approved local-only SMS substitute.

Profile initially contains required `firstName`, required `lastName`, optional `fatherName`. Email/phone are Contact methods, not duplicated profile fields.

ADR-0009 is authoritative for:

- names: trim + NFC, preserve case/internal spacing, reject control characters, first/last 1..120 code points, fatherName <=120;
- email: trim, `Locale.ROOT` lowercase canonical storage/comparison, <=254, one mailbox, no provider-specific dot/plus rewriting;
- phone: canonical E.164 and no locale-based national-number inference;
- verified email/phone global uniqueness and logical-delete reservation;
- at most one active primary Contact total;
- one live pending registration reservation per canonical Contact, bound to the 10-minute challenge;
- repeated same-live registration continuing the same pending registration non-enumeratingly;
- no second User/challenge for an already verified/reserved Contact;
- exactly eight-digit CSPRNG challenge, purpose-HMAC-only persistence, 10-minute TTL, five failed tries, 60-second resend spacing, replacement invalidation, and single use.

Profile/contact gRPC use cases include:

```text
GetProfile
UpdateProfile
AddContact
ResendContactVerification
ConfirmContactVerification
SetPrimaryContact
RemoveContact
```

`UpdateProfile` applies registration name rules. `AddContact` applies the same canonicalization/uniqueness rules and uses a purpose-separated challenge namespace with the ADR-0009 challenge baseline. `SetPrimaryContact` accepts only an active verified Contact. Changing primary or removing a Contact requires authentication age <=5m. An ACTIVE User cannot remove the last verified Contact; removing the primary Contact requires another verified Contact to be made primary first. Account deletion/erasure is the only ordinary v1 path that may remove the last verified Contact.

`SUSPENDED` blocks new login/refresh. `DELETING`/`DELETED` are non-authenticatable and trigger the session revocation rules below.

## 3. Registration locale

`RegisterLocalRequest` field 5 is required `RegistrationLocale`; canonical values are `fa` and `en`. UNSPECIFIED/unrecognized -> `INVALID_ARGUMENT`.

Locale is persisted immutably with each registration challenge. Resend accepts no locale override and reuses the prior challenge locale.

## 4. Registration runtime and Notification handoff

ADR-0009 enables the registration composition; ADR-0006 defines durable Notification semantics.

- application/internal Identity gRPC local convention: 9090;
- Notification result callback: 9091;
- registration/callback inbound message cap 64KiB and metadata cap 16KiB;
- canonical Notification-owned Protobuf generates consumer stubs;
- `SubmitNotification`: 900ms, one attempt, wait-for-ready off, no gRPC retry.

Identity local transaction persists business state + encrypted handoff escrow + durable delivery intent/outbox. Notification RPC occurs only after commit.

Dispatcher baseline:

- `FOR UPDATE SKIP LOCKED`;
- lease 30s; batch 32;
- busy poll 250ms; idle 1s;
- durable retry 1s, 2s, 5s, 10s, then <=30s ±20%;
- time-bound cutoff at `message_not_after - 5s`;
- non-time-bound automatic retry <=30m, then `HANDOFF_FAILED` + alert.

After Notification `ACCEPTED`, caller handoff recipient/code material is irreversibly removed while authoritative Contact and one-way challenge state remain.

## 5. Tenant, invitation, Membership, and last-owner safety

Tenant create is self-service or platform-admin. Creator becomes initial owner. Tenant remains `PROVISIONING` until idempotent Authorization owner-provisioning ACK, then activates transactionally.

Tenant lifecycle:

```text
PROVISIONING
ACTIVE
SUSPENDED
DELETING
DELETED
```

Tenant + creator Membership + audit + stable owner-provisioning outbox commit in one local transaction without network I/O. Owner provisioning uses 900ms/one invocation/no wait-for-ready/no immediate retry and durable delays 1s, 5s, 30s, 2m, 10m, then <=10m ±20%. Fifteen minutes pending warns; one hour pages.

Invitation lifecycle:

```text
PENDING
ACCEPTED
DECLINED
EXPIRED
REVOKED
```

Membership lifecycle:

```text
ACTIVE
SUSPENDED
REMOVED
```

Invitation targets an existing non-erased User through a verified Identity Contact reference. Acceptance belongs to that authenticated target User, TTL is seven days, and at most one pending invitation exists for `(tenant_id,target_user_id)`. Unregistered-contact invitation/linking is outside v1.

Invitation is not a Membership. Acceptance creates one ACTIVE Membership, transitions the Invitation to ACCEPTED, records audit, and writes a durable Authorization outbox in the same local transaction. Authorization idempotently assigns the default `tenant_member` SYSTEM role. v1 Invitation carries no arbitrary role/permission; privilege elevation is a later Authorization-owned operation. Until provisioning ACK, Authorization default-deny remains safe authority.

Tenant owner with authorized `tenant.delete` may begin deletion. Platform-admin may suspend/resume. `DELETING` rejects new Identity invitation, tenant-selection, and tenant/membership mutations and revokes pending invitations. Authorization tenant-lifecycle cleanup/deny is durable; Identity moves to `DELETED` only after ACK. Restore is platform-admin-only, allowed only before irreversible tenant purge/erasure begins, re-enters `PROVISIONING` for Authorization reconciliation, and never releases the slug/technical IDs.

### Last-owner-safe removal

Authorization owns owner-role state. A read-only owner-count check is race-prone, so Identity uses the ADR-0012 `PrepareMembershipRemoval` reservation protocol.

Identity first commits a stable local removal intent in `PREPARING`, then calls Authorization outside the DB transaction:

```text
PrepareMembershipRemoval
deadline:       300ms
attempts:       1
wait-for-ready: off
retry/cache:    none
fallback:       none
failure:        fail closed
```

Authorization atomically reserves the removal and evaluates effective owner capacity excluding existing reservations. If it could remove the last owner: `FAILED_PRECONDITION / LAST_TENANT_OWNER`. Reservations do not auto-expire into unsafe allow state.

After prepare succeeds, one Identity transaction marks Membership REMOVED + audit + durable finalization outbox. `FinalizeMembershipRemoval` closes Authorization role state/reservation. If the local removal definitively fails, Identity durably resolves `CancelMembershipRemovalPreparation`. Crash between prepare and local commit is recovered by replaying the stable `PREPARING` intent/request ID. Finalize/cancel uses 900ms/one invocation/no immediate retry and durable retry outside transactions.

Production tenant-owned Identity tables use forced RLS with non-owner `NOSUPERUSER NOBYPASSRLS` runtime roles. Tenant context comes only from validated authenticated context and is installed through the canonical parameterized transaction-local mechanism. Session-scoped pooled tenant state is prohibited; missing/malformed context fails closed; pooled-connection cross-tenant reuse after commit/rollback is a mandatory negative test.

## 6. Authentication, tenantless onboarding, sessions, and tokens

Successful password + required MFA proof can establish an Identity Session/RefreshFamily even when the User has no active Tenant. A normal access JWT is tenant-scoped and is not issued until a valid ACTIVE Membership/Tenant is selected.

Selection:

1. one active Membership -> select automatically;
2. multiple -> reuse a stored last-selected Membership only when it is still valid; otherwise require explicit selection;
3. zero -> authenticated onboarding only: profile/tenant-create/invitation-accept/tenant-selection surface, no ordinary resource JWT.

The last-selected Membership is non-authoritative preference/query state and is always revalidated. Web BFF represents the unselected state as `authenticated_onboarding`; it cannot call normal resource services as an authenticated user until Identity issues tenant-scoped credentials.

Tenant switch validates the target active Membership/Tenant, updates the preference, rotates the refresh credential within the family, issues a new tenant-scoped access JWT, and requires BFF session-ID rotation.

Access JWT:

- RS256;
- five-minute issuance lifetime;
- standard claims `iss aud sub jti iat exp`;
- private claims `tenant_id membership_id sid`;
- no roles/permissions/authorization snapshot;
- exact audience only; wildcard prohibited;
- verifier clock leeway <=30s per ADR-0023.

Issuer is typed config; initial production logical value is `https://identity.sajtech.internal` unless reviewed environment config replaces it.

Refresh credential:

- exactly 32 CSPRNG bytes;
- Base64URL without padding when string encoded;
- stored only as purpose-separated versioned HMAC-SHA-256 digest;
- 7-day idle / 30-day absolute lifetime;
- predecessor invalidated on rotation;
- reuse revokes the family.

A User has at most 20 active RefreshFamilies. Creating the 21st revokes the oldest active family by `created_at` with stable identifier tie-break.

Revocation:

- current logout -> current family;
- logout-all -> all families;
- password reset -> all families;
- User suspension/DELETING -> all families and reject login/refresh;
- password change -> rotate current session/refresh and revoke all other families;
- successful ExternalIdentity unlink -> rotate current session/refresh and revoke all other families.

JWT verification stays local from the approved public bundle with no ordinary blacklist/introspection/JWKS network call. An already-issued signed access JWT can therefore remain cryptographically valid until its remaining five-minute lifetime plus only the configured <=30s clock tolerance. Online Authorization still decides resource permission; no permission snapshot is trusted from JWT.

## 7. Password credential and compromised-password baseline

Local passwords use Technology Baseline Argon2id (`m=19 MiB`, `t=2`, `p=1`, random 16-byte salt, >=32-byte hash) behind an Identity security port. Stored encoding is self-describing/versioned and rehashes on successful authentication when the approved baseline increases.

Password-only authentication accepts 15..128 Unicode code points with NFC normalization. No arbitrary composition rules or periodic forced rotation. No password-history/reuse blacklist exists in v1.

Create/change/reset calls the compromised-password dependency outside DB transactions:

1. NFC normalize password;
2. UTF-8 encode and SHA-256 locally;
3. send only first 20 digest bits / five canonical hex characters;
4. receive bounded remaining SHA-256 suffix + non-negative occurrence-count records;
5. compare full digest locally.

Raw password/full digest never leave Identity. Malformed/oversized/ambiguous response fails closed. Dependency: 900ms overall, one attempt, no automatic retry, bounded concurrency/cancellation. The service is an internal prefix/k-anonymous contract; Identity does not claim direct HIBP wire compatibility.

Change Password requires current password + authentication age <=5m and, when MFA is active, MFA assurance <=5m. It checks compromised-password state before commit.

Forgot/reset uses only primary verified Contact and is non-enumerating. Recovery challenge is purpose-separated but uses eight decimal digits, 10m TTL, five failed tries, 60s resend gap, HMAC-only storage, replacement invalidation and single use. If TOTP is active, reset additionally requires TOTP or one recovery code. Successful reset revokes all RefreshFamilies.

If both password access and active MFA/recovery material are unavailable, v1 has no automated bypass. Manual/support recovery requires a future security decision.

## 8. External identity and Google signup

Google uses OIDC Authorization Code + PKCE S256 through Web BFF. Stable identity is `(issuer, subject)`; email equality never auto-links.

Web BFF owns provider validation. Identity never receives provider authorization/access/refresh/ID tokens and does not call Google login/link endpoints.

Trusted BFF evidence includes:

```text
evidence_id        exactly 256-bit CSPRNG
evidence_issued_at trusted BFF server timestamp after validation
issuer             canonical validated
subject            validated provider subject
request_id         canonical UUIDv4
metadata           versioned/bounded optional provider email + email_verified + name suggestions
```

Identity accepts evidence for exactly two minutes from `evidence_issued_at`, subject only to current clock-skew bounds, and retains spent/replay evidence >=10m after consumption. Evidence fingerprint binds BFF workload identity + evidence ID + issuer + subject + request ID + issued time + metadata.

Exact same replay returns the original committed result. Same `evidence_id` with changed request/payload/fingerprint -> `ALREADY_EXISTS / OIDC_EVIDENCE_REPLAY`.

Google may create a new User:

- known `(issuer,subject)` -> existing User authentication;
- unknown -> new PENDING User permitted;
- verified provider email may create a verified Contact only when canonical email is free;
- verified email collision -> `FAILED_PRECONDITION / ACCOUNT_LINK_REQUIRED`, never auto-link;
- provider email change does not silently change account binding;
- provider names are suggestions only; user confirmation/update is required for Identity profile completion.

Link requires authenticated account settings + auth age <=5m/current MFA assurance where applicable. Unlink requires auth age <=5m and fails `FAILED_PRECONDITION / LAST_AUTHENTICATION_METHOD` when it would remove the last sign-in method. Successful unlink rotates current session/refresh and revokes other families.

## 9. MFA

TOTP:

- HMAC-SHA-256;
- 6 digits / 30s / ±1 step;
- issuer `SajTech`;
- AES-256-GCM local versioned key ring through OpenBao/ESO;
- 10 independent 80-bit recovery codes shown once, stored as purpose-HMAC-SHA-256, atomic single-use;
- enroll/disable/replace/recover requires authentication age <=5m;
- no trusted devices in v1.

When TOTP is active, password success creates only a pre-auth challenge:

```text
TTL: 5m
maximum failed proofs: 5
single use: yes
```

No Session/RefreshFamily/access token is completed until the same challenge receives valid TOTP or one valid recovery code. A new successful password proof invalidates the previous live pre-auth challenge. An accepted TOTP timestep cannot be reused for the same enrollment.

SMS cannot downgrade active TOTP. If TOTP is active, only TOTP/recovery code completes the gate. SMS MFA is available only to accounts without active TOTP and only after ADR-0020/Notification/quota/workload/telemetry production gates pass. `LoggingSmsProviderAdapter` remains local-only and is never production fallback.

## 10. Semantic quotas

ADR-0024 is the current quota authority. Identity uses ACL-isolated `security-redis`, one atomic 75ms/one-attempt/no-retry operation, HMAC pseudonymous keys, trusted app time + Redis TIME, <=2s skew, monotonic effective time, no security reset from TTL expiry, and fail-closed dependency/time semantics.

Registration exact v1 numeric policy:

- REGISTER/contact: capacity 5, refill 1/15m, cleanup horizon 24h;
- REGISTER/network: 60, refill 1/5s, 1h;
- RESEND/contact: 5, refill 1/10m, 2h plus fixed 60s challenge resend gap;
- RESEND/network: 60, refill 1/5s, 1h;
- CONFIRM/network: 120, refill 2/1s, 30m; challenge-local five failures remains subject proof limit.

Authenticated Contact verification reuses this numeric envelope under distinct domain-separated operation names. Password/MFA recovery proof uses the ADR-0024 recovery envelope under distinct namespaces.

Authentication anti-lockout remains mandatory: source gate before credential work; subject failure pressure after failed proof and never sufficient alone to reject a later correct proof once source gate permits evaluation.

## 11. Browser boundary

Identity does not expose internal tokens to React. BFF owns browser session, OIDC transaction/provider validation, PKCE, CSRF, CORS and secure cookies per ADR-0016.

Pre-auth MFA is not an authenticated browser session. `authenticated_onboarding` is authenticated only after full required MFA/session creation, carries no normal resource JWT until tenant selection, and is BFF-allow-listed to Identity onboarding operations.

## 12. PostgreSQL, aggregates, and transactions

Identity owns dedicated PostgreSQL/CloudNativePG under ADR-0027. Runtime is `NOSUPERUSER NOBYPASSRLS`, not table owner. Tenant tables use forced RLS plus application checks. Tenant context uses parameterized transaction-local setting; absent/malformed context fails closed; cross-tenant pool reuse after commit/rollback is tested.

Aggregate boundaries:

- User/Profile/active Contact set;
- Credential;
- Challenge;
- Session/RefreshFamily;
- MFA enrollment/recovery;
- ExternalIdentity;
- Tenant;
- TenantMembership;
- Invitation.

Cross-service lifecycle intents/outboxes are explicit coordination records. JPA is default aggregate CRUD; JDBC/jOOQ only for justified SQL-control/query paths such as `SKIP LOCKED`/outbox. One giant User graph is prohibited.

No remote gRPC/HTTP/Kafka/Redis/provider I/O inside Identity DB transactions. DB locks never span remote I/O. Retry is outside failed transactions.

## 13. Erasure and legal hold

ADR-0028 makes Identity coordinator of global User erasure. Required participants initially: Identity, Authorization, Notification, Web BFF. Coordination is durable async Kafka + Transactional Outbox; critical publication/Inbox dedup evidence remains 35d and retry/DLQ evidence >=14d where used.

Self-erasure requires auth age <=5m and active MFA proof when applicable. Acceptance transaction sets User `DELETING`, revokes every RefreshFamily, persists audit/request and outbox. Legal hold may block irreversible progress but never re-enables authentication. Legal-hold create/release is platform/legal-authorized and audited. v1 exposes no normal self-service erasure undo; irreversible participant work can never be cancelled.

Security audit evidence remains >=365d unless stricter policy. Non-PII erasure receipts follow ADR-0028. Restore replays erasure/legal-hold before traffic.

## 14. Runtime/deployment

Production defaults:

```text
namespace:       platform-apps
Deployment:      identity-service
Service:         identity-service
ServiceAccount:  identity-service
principal:       prod.sajtech.internal/ns/platform-apps/sa/identity-service
application gRPC local convention: 9090
Notification callback:              9091
management: separate configured port
```

Production uses deny-by-default NetworkPolicy/Istio authorization, purpose-separated read-only OpenBao/ESO mounts, >=3 Identity replicas with PDB/topology spread, and HPA only after load/connection/hash-bulkhead evidence. Liveness is process/local-runtime only; readiness includes usable required local key material and DB/entry-point prerequisites.

Image/JDK follows current Technology Baseline (Temurin 25.0.4 at this documentation revision) with immutable digest. Staging/production overlays remain `deploy/clusters/staging` and `deploy/clusters/production`; registry/DNS/secret-path/Redis/CNPG/backup/alert destinations remain typed environment placeholders until provisioned.

## 15. Internal dependency ownership

Current Identity remote edges include:

- semantic quota Redis — authoritative security, 75ms/one attempt/no retry/fallback;
- compromised-password service — authoritative security, 900ms/one attempt/no retry/fail closed;
- Notification SubmitNotification — durable command after local outbox commit;
- Authorization owner/member provisioning — durable commands after local outbox commit;
- Authorization `PrepareMembershipRemoval` — authoritative security, 300ms one attempt/no retry/cache/fallback;
- Authorization membership-removal finalize/cancel — durable command resolution;
- Authorization tenant lifecycle cleanup/reconciliation — durable commands.

Exact operation classes/failure actions are canonical in `../dependency-criticality.yaml`. Google is **not** an Identity dependency; Web BFF owns the Google edge.

## 16. Repository-complete implementation and verification

Repository-complete Identity means code, Protobuf contracts, Flyway migrations, independent Gradle build/wrapper/verification metadata, unit/integration/contract/architecture/security tests, Docker/Helm/GitOps/policies, observability and CI/release artifacts exist and are verified as far as repository/local tooling permits. Actual staging/production external secrets/providers/load/failover/DR remain NOT VERIFIED until those environments and gates execute.

Applicable tests include:

- EMAIL/PHONE registration gates, Contact reservation/canonicalization/uniqueness/primary/profile activation;
- exact registration/contact/recovery challenge and semantic quota boundaries;
- User lifecycle and authentication shutdown;
- profile/contact CRUD + recent-auth/last-contact rules;
- tenant/invitation/Membership lifecycles, default-member provisioning, slug tombstone, tenant delete/restore;
- concurrent last-owner prepare reservations, crash/replay/finalize/cancel safety;
- tenantless onboarding, one/many membership selection, tenant switch;
- JWT exact claims/audience/leeway, key rotation;
- 20 RefreshFamily cap, logout/revocation/reuse and <=5m token residual trade-off;
- password change/recovery, MFA-required reset, no automated password+MFA-loss bypass;
- compromised-password prefix-only SHA-256 protocol and deadline/fail-closed behavior;
- BFF-only Google evidence 256-bit/2m/10m replay, signup/collision/no-auto-link/unlink;
- TOTP 5m/five-proof gate, timestep replay, recovery code, SMS no-downgrade;
- self-erasure/legal hold/session revocation;
- aggregate/transaction boundaries, forced RLS/pool reuse;
- Notification/Authorization outbox replay/conflict;
- 35d critical idempotency/dedup, >=365d audit;
- NetworkPolicy/Istio positive/negative authorization, deployment render/policy, PII-safe telemetry, load/failover/restore where applicable.
