# Identity Service Architecture

## 1. Ownership

Identity owns global User, Tenant, TenantMembership, membership lifecycle, profile/contact methods, local credentials, external identities, MFA enrollments/recovery material, authentication, sessions, token signing, and active-tenant selection. Authorization roles/permissions are separate.

Base package: `com.sajtech.identity`.

## 2. Registration and profile

User begins `PENDING` and becomes `ACTIVE` only after required verified identity/contact evidence, required profile completion, and absence of blocking security/deletion conditions.

Profile initially contains required `firstName`, required `lastName`, optional `fatherName`. Email/phone are contact methods, not duplicated profile fields.

Email normalization is provider-neutral; provider-specific Gmail dot/plus rewriting is prohibited. Phone is canonical E.164.

Verification challenges are short-lived, single-use, semantically rate/attempt limited. Raw OTP/code/password/recovery values are never logged or persisted plaintext.

ADR-0009 is the implementation authority for the exact v1 profile/contact canonicalization and registration-challenge policy: name trim/NFC/control-character and length rules, provider-neutral canonical email, E.164 phone, verified-contact uniqueness/reservation, exactly eight-digit CSPRNG challenge, HMAC-only persistence, 10-minute TTL, five failed attempts, 60-second resend spacing, previous-challenge invalidation, and single use. These values are server-owned and are not request policy.

## 3. Registration locale

`RegisterLocalRequest` field 5 is required `RegistrationLocale`; canonical values are `fa` and `en`. UNSPECIFIED/unrecognized -> `INVALID_ARGUMENT`.

Locale is persisted immutably with each registration challenge. Resend accepts no locale override and reuses the prior challenge locale.

## 4. Registration runtime and Notification handoff

ADR-0009 enables the current registration composition; ADR-0006 defines the durable Notification semantic contract.

- registration gRPC internal port configurable; local default 9090;
- Notification result callback port 9091;
- inbound message cap 64KiB; metadata cap 16KiB;
- canonical Notification-owned Protobuf generates consumer stubs;
- `SubmitNotification`: 900ms, one attempt, wait-for-ready off, no gRPC retry.

Identity local transaction persists business state + encrypted handoff escrow + durable delivery intent/outbox. Notification RPC occurs only after commit.

Dispatcher:

- `FOR UPDATE SKIP LOCKED`;
- lease 30s; batch 32;
- busy poll 250ms; idle 1s;
- durable retry 1s, 2s, 5s, 10s, then <=30s ±20%;
- time-bound cutoff at `message_not_after - 5s`;
- non-time-bound automatic retry <=30m, then `HANDOFF_FAILED` + alert.

After Notification `ACCEPTED`, caller handoff recipient/code material is irreversibly removed while authoritative contact and one-way challenge state remain.

## 5. Tenant v1

Tenant create is self-service or platform-admin. Creator becomes initial owner. Tenant remains `PROVISIONING` until idempotent Authorization owner-provisioning ACK, then activates transactionally.

Tenant + creator membership + audit + stable owner-provisioning outbox commit in one local transaction without network I/O.

Owner provisioning command: one invocation per durable claim, 900ms deadline, wait-for-ready off, no immediate transport retry; durable delay 1s, 5s, 30s, 2m, 10m, then <=10m ±20%. Fifteen minutes pending warns; one hour pages.

Production tenant-owned tables use forced RLS with non-owner `NOSUPERUSER NOBYPASSRLS` runtime roles in addition to application tenant checks. Tenant context comes only from validated authenticated context and is installed through the canonical parameterized transaction-local mechanism; session-scoped tenant state on pooled connections is prohibited. Missing/malformed context fails closed, and pooled-connection reuse across tenants after commit/rollback must prove no context leakage.

ADR-0012 fixes the current invitation rules: an invitation targets an existing non-erased User through a verified Identity contact reference, acceptance belongs to that same authenticated target User, TTL is seven days, and at most one pending invitation exists for a tenant/target pair. Unregistered-contact invitation/linking is outside v1.

## 6. Sessions and tokens

- RS256 access JWT, 5-minute lifetime;
- validate issuer, audience, subject, active tenant, membership, session, `jti`, `iat`, `exp`;
- opaque rotating refresh credentials;
- 7-day idle / 30-day absolute refresh lifetime;
- keyed secure digest persistence;
- predecessor invalidated on rotation;
- refresh reuse revokes the family;
- browser retains only secure BFF session cookie, never Identity tokens.

ADR-0012 fixes the v1 access-token claim allow-list: standard `iss`, `aud`, `sub`, `jti`, `iat`, `exp` plus private `tenant_id`, `membership_id`, `sid`. Roles, permissions, authorization-version snapshots, and wildcard audiences are prohibited. The issuer is typed deployment configuration; the initial production logical value is `https://identity.sajtech.internal` unless reviewed environment configuration replaces it before rollout.

### Signing-key lifecycle

ADR-0023 defines local RSA-3072/RS256 signing keys sourced through OpenBao/External Secrets and mounted read-only. Every signing key has immutable random `kid`; private signing material never enters Git, ordinary environment variables, events, databases, logs, traces, or metrics.

BFF/resource services verify tokens from a bounded reviewed non-secret GitOps public JWK bundle. Normal verification performs no Identity/OpenBao/remote-JWKS call. Normal rotation is 90 days with next-key prepublication and >=24h previous-key overlap. Unknown `kid`, algorithm confusion, invalid signature/issuer/audience/time claims fail closed.

## 7. Password credential baseline

Local passwords use the Technology Baseline Argon2id profile (`m=19 MiB`, `t=2`, `p=1`, random 16-byte salt, >=32-byte hash) behind an Identity security port. Stored encoding is self-describing/versioned and rehashes on successful authentication when the approved baseline increases.

Password-only authentication accepts 15..128 Unicode code points with NFC normalization. No arbitrary composition rules or periodic forced rotation. Create/change/reset checks the compromised-password service. Verification uses bounded CPU/memory concurrency and current semantic quota anti-lockout semantics.

The compromised-password dependency is prefix/k-anonymous style: raw password remains inside Identity. The remote check has a 900ms overall deadline, one attempt, no automatic retry, and fail-closed behavior; an unchecked password is not committed. The call occurs outside any database transaction.

## 8. External identity

Google uses OIDC Authorization Code + PKCE S256 through Web BFF. Stable identity is `(issuer, subject)`; email equality never auto-links.

Web BFF owns Google protocol validation. Identity does not perform a direct Google login/link call and does not receive provider authorization codes or provider tokens. After successful validation, BFF invokes the typed Identity gRPC contract with the short-lived single-use evidence identity and validated `(issuer, subject)` contract defined by ADR-0012. Identity accepts this only from the authorized BFF workload identity and consumes the evidence atomically.

Linking requires authenticated account settings, an explicitly verified recovery flow, or another current reviewed flow with equivalent assurance.

## 9. MFA

TOTP:

- HMAC-SHA-256;
- 6 digits / 30s / ±1 step;
- issuer `SajTech`;
- AES-256-GCM local versioned key ring sourced through OpenBao/External Secrets;
- 10 independent 80-bit recovery codes shown once, stored as domain-separated HMAC-SHA-256, atomic single-use;
- enroll/disable/replace/recover requires authentication age <=5m;
- no trusted devices in v1.

When TOTP is active, successful password verification produces a pre-auth MFA challenge only. Identity issues no access/refresh credentials until that same challenge is completed with a valid TOTP or one valid recovery code. The challenge is short-lived, server-owned, single-use, and protected by current semantic quota/non-enumeration rules.

Production Iran SMS MFA follows ADR-0020 and is eligible only when current semantic quotas (ADR-0024), provider contract/credential readiness, Notification encrypted exact-content lifecycle, Identity MFA/session controls, and workload/policy/telemetry gates are verified. `LoggingSmsProviderAdapter` is local-only and never production readiness/fallback.

## 10. Semantic quotas

ADR-0024 is the single current semantic security-quota decision. Identity owns/enforces applicable operation quotas through its ACL-isolated `security-redis` namespace rather than a quota service/PostgreSQL.

Covered operations include registration register/resend/confirm, login, Google login/link, tenant create/invite, and MFA lifecycle/recovery according to current versioned policy. Enforcement is one atomic 75ms/one-attempt/no-retry Redis operation with domain-separated pseudonymous keys, trusted application time + Redis `TIME`, <=2s skew, monotonic `effective_now=min(...)`, no security reset from TTL expiry, and fail-closed time/dependency behavior.

Authentication anti-lockout is mandatory: source quota blocks before credential work; subject failure pressure is charged after failure and alone cannot block a subsequently proven correct credential once source controls allow evaluation.

Production remains disabled for each gated entry point until quota atomicity, time safety, PII keying, Redis failover/outage, abuse, and >=2x peak tests pass.

## 11. Browser boundary

Identity does not expose internal tokens to React. BFF owns browser session, OIDC transaction state/provider validation, PKCE, CSRF, CORS, and secure cookie behavior per ADR-0016. A pre-auth MFA challenge is not an authenticated browser session.

## 12. PostgreSQL and erasure

Identity owns its dedicated PostgreSQL database on an independent production CloudNativePG cluster under ADR-0027. Runtime is `NOSUPERUSER NOBYPASSRLS`, not table owner; tenant tables use forced RLS plus application checks. Tenant context uses the canonical parameterized transaction-local setting from the SQL/Flyway standard, never a session-scoped pooled connection setting; absent/malformed context fails closed and cross-tenant pool reuse is a mandatory negative test. Flyway only; Domain/JPA separation; no remote I/O inside DB transactions.

ADR-0012 fixes aggregate boundaries: User/Profile/Contact form one consistency boundary, while Credential, Challenge, Session/RefreshFamily, MFA, ExternalIdentity, Tenant, Membership, and Invitation remain separate aggregates coordinated by Application transactions/outboxes. JPA is used for aggregate CRUD and JDBC/jOOQ for justified SQL-control paths such as `SKIP LOCKED`/outbox claims; one giant User entity graph is prohibited.

ADR-0028 makes Identity coordinator of platform-global erasure requests while every bounded context owns irreversible erasure/anonymization and non-PII receipts for its own data. Required erasure participants are server-owned and initially include Identity, Authorization, Notification, and Web BFF; coordination is durable asynchronous Kafka + Transactional Outbox rather than synchronous fan-out. Legal hold is an audited `ACTIVE -> RELEASED` ledger and cannot be caller-bypassed.

Critical Kafka publication/Inbox-dedup evidence follows the existing 35-day recovery horizon; it is not reduced to an eight-day critical Inbox window. Retry/DLQ evidence is retained at least 14 days where used, and security audit evidence at least 365 days unless a stricter approved policy applies.

## 13. Runtime/deployment and verification

Identity production workload defaults are `platform-apps/identity-service` for Deployment, Service, and ServiceAccount, with workload principal `prod.sajtech.internal/ns/platform-apps/sa/identity-service`. Application gRPC uses the configured 9090 convention, callback uses 9091, and management runs on a separate configured port.

Production uses deny-by-default NetworkPolicy/Istio authorization, purpose-separated read-only OpenBao/External-Secrets mounts, at least three Identity replicas with PDB/topology spread, and HPA only after load/connection/hash-bulkhead evidence. Liveness is process/local-runtime only; readiness includes usable required local key material and database/entry-point prerequisites. Images use the current Temurin 25.0.4 baseline and immutable digest. Environment overlays remain under `deploy/clusters/staging` and `deploy/clusters/production`; registry/DNS/secret-path/Redis/CNPG/backup/alert destinations remain typed environment placeholders until provisioned.

For the current task, repository-complete means code, contracts, migrations, integration/contract/security tests, Docker/Helm/GitOps/policies, observability, and CI/release artifacts are present and verified as far as repository/local tooling permits. Actual staging/production deployment, external providers/secrets, load/failover/DR and production-readiness evidence remain `NOT VERIFIED` until those systems exist and the release gates run.

Applicable tests include registration canonicalization/challenge boundaries, tenant isolation/RLS including pooled-connection context reuse after commit/rollback, invitation ownership/TTL/single-pending behavior, slug tombstones, last-owner protection, provisioning replay/conflict, exact JWT claim/audience rules, refresh rotation/reuse, compromised-password raw-value non-egress/deadline/fail-closed behavior, BFF-only OIDC binding/evidence replay/no-auto-link, password+MFA pre-auth gating, TOTP drift/replay, key rotation, recovery-code use, recent-auth rules, semantic quota time/anti-lockout/failover, registration locale, Notification handoff/callback, aggregate/transaction boundaries, 35-day critical idempotency/dedup, erasure/legal-hold replay, PostgreSQL failover/restore, NetworkPolicy/Istio positive/negative authorization, deployment render/policy checks, audit, and PII-safe telemetry.