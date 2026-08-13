# Identity Service Architecture

## 1. Ownership

Identity owns global User, Tenant, TenantMembership, membership lifecycle, profile/contact methods, local credentials, external identities, MFA enrollments/recovery material, authentication, sessions, token signing, and active-tenant selection. Authorization roles/permissions are separate.

Base package: `com.sajtech.identity`.

## 2. Registration and profile

User begins `PENDING` and becomes `ACTIVE` only after required verified identity/contact evidence, required profile completion, and absence of blocking security/deletion conditions.

Profile initially contains required `firstName`, required `lastName`, optional `fatherName`. Email/phone are contact methods, not duplicated profile fields.

Email normalization is provider-neutral; provider-specific Gmail dot/plus rewriting is prohibited. Phone is canonical E.164.

Verification challenges are short-lived, single-use, semantically rate/attempt limited. Raw OTP/code/password/recovery values are never logged or persisted plaintext.

## 3. Registration locale

`RegisterLocalRequest` field 5 is required `RegistrationLocale`; canonical values are `fa` and `en`. UNSPECIFIED/unrecognized -> `INVALID_ARGUMENT`.

Locale is persisted immutably with each registration challenge. Resend accepts no locale override and reuses the prior challenge locale.

## 4. Registration runtime and Notification handoff

ADR-0035 enables the current registration composition; ADR-0029 defines the durable Notification semantic contract.

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

Production tenant-owned tables use forced RLS with non-owner `NOSUPERUSER NOBYPASSRLS` runtime roles in addition to application tenant checks.

## 6. Sessions and tokens

- RS256 access JWT, 5-minute lifetime;
- validate issuer, audience, subject, active tenant, membership, session, `jti`, `iat`, `exp`;
- opaque rotating refresh credentials;
- 7-day idle / 30-day absolute refresh lifetime;
- keyed secure digest persistence;
- predecessor invalidated on rotation;
- refresh reuse revokes the family;
- browser retains only secure BFF session cookie, never Identity tokens.

### Signing-key lifecycle

ADR-0052 defines local RSA-3072/RS256 signing keys sourced through OpenBao/External Secrets and mounted read-only. Every signing key has immutable random `kid`; private signing material never enters Git, ordinary environment variables, events, databases, logs, traces, or metrics.

BFF/resource services verify tokens from a bounded reviewed non-secret GitOps public JWK bundle. Normal verification performs no Identity/OpenBao/remote-JWKS call. Normal rotation is 90 days with next-key prepublication and >=24h previous-key overlap. Unknown `kid`, algorithm confusion, invalid signature/issuer/audience/time claims fail closed.

## 7. Password credential baseline

Local passwords use the Technology Baseline Argon2id profile (`m=19 MiB`, `t=2`, `p=1`, random 16-byte salt, >=32-byte hash) behind an Identity security port. Stored encoding is self-describing/versioned and rehashes on successful authentication when the approved baseline increases.

Password-only authentication accepts 15..128 Unicode code points with NFC normalization. No arbitrary composition rules or periodic forced rotation. Create/change/reset checks the compromised-password service. Verification uses bounded CPU/memory concurrency and current semantic quota anti-lockout semantics.

## 8. External identity

Google uses OIDC Authorization Code + PKCE S256 through Web BFF. Stable identity is `(issuer, subject)`; email equality never auto-links.

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

Production Iran SMS MFA follows ADR-0049 and is eligible only when current semantic quotas (ADR-0054), provider contract/credential readiness, Notification encrypted exact-content lifecycle, Identity MFA/session controls, and workload/policy/telemetry gates are verified. `LoggingSmsProviderAdapter` is local-only and never production readiness/fallback.

## 10. Semantic quotas

ADR-0054 is the single current semantic security-quota decision. Identity owns/enforces applicable operation quotas through its ACL-isolated `security-redis` namespace rather than a quota service/PostgreSQL.

Covered operations include login, Google login/link, tenant create/invite, and MFA lifecycle/recovery according to the current policy table. Enforcement is one atomic 75ms/one-attempt/no-retry Redis operation with domain-separated pseudonymous keys, trusted application time + Redis `TIME`, <=2s skew, monotonic `effective_now=min(...)`, no security reset from TTL expiry, and fail-closed time/dependency behavior.

Authentication anti-lockout is mandatory: source quota blocks before credential work; subject failure pressure is charged after failure and alone cannot block a subsequently proven correct credential once source controls allow evaluation.

Production remains disabled for each gated entry point until quota atomicity, time safety, PII keying, Redis failover/outage, abuse, and >=2x peak tests pass.

## 11. Browser boundary

Identity does not expose internal tokens to React. BFF owns browser session, OIDC transaction state, PKCE, CSRF, CORS, and secure cookie behavior per ADR-0045.

## 12. PostgreSQL and erasure

Identity owns its dedicated PostgreSQL database on an independent production CloudNativePG cluster under ADR-0057. Runtime is `NOSUPERUSER NOBYPASSRLS`, not table owner; tenant tables use forced RLS plus application checks. Flyway only; Domain/JPA separation; no remote I/O inside DB transactions; cross-tenant negative tests mandatory.

ADR-0058 makes Identity coordinator of platform-global erasure requests while every bounded context owns irreversible erasure/anonymization and non-PII receipts for its own data.

## 13. Verification

Applicable tests include tenant isolation/RLS, slug tombstones, last-owner protection, provisioning replay/conflict, JWT/refresh rotation/reuse, OIDC binding/no-auto-link, PKCE integration, TOTP drift/replay, key rotation, recovery-code use, recent-auth rules, semantic quota time/anti-lockout/failover, registration locale, Notification handoff/callback, PostgreSQL failover, audit, and PII-safe telemetry.
