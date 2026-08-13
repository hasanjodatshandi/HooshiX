# Identity Service Architecture

## 1. Ownership

Identity owns global User, Tenant, TenantMembership, membership lifecycle,
profile/contact methods, local credentials, external identities, MFA enrollments
and recovery material, authentication, sessions, and active-tenant selection.
Authorization roles/permissions are separate.

Base package: `com.sajtech.identity`.

## 2. Registration and profile

User begins `PENDING` and becomes `ACTIVE` only after required verified identity/
contact evidence, required profile completion, and absence of blocking security/
deletion conditions.

Profile initially: required `firstName`, required `lastName`, optional
`fatherName`. Email/phone are contact methods, not duplicated profile fields.

Email normalization is trim + `Locale.ROOT` lowercase; provider-specific Gmail
dot/plus rewriting is prohibited. Phone is E.164.

Verification challenges are short-lived, single-use, rate/attempt limited. Raw
OTP/code/password/recovery values are never logged or persisted plaintext.

## 3. Registration locale

`RegisterLocalRequest` field 5 is required `RegistrationLocale`; canonical values
are `fa` and `en`. UNSPECIFIED/unrecognized -> `INVALID_ARGUMENT`.

Locale is persisted immutably with each registration challenge. Resend accepts
no locale override and reuses the prior challenge locale.

## 4. Registration runtime and Notification handoff

ADR-0035 enables registration runtime.

- registration gRPC internal port configurable; local default 9090;
- Notification result callback port 9091;
- inbound cap 64KiB; metadata cap 16KiB;
- canonical Notification-owned Protobuf generates consumer stubs;
- `SubmitNotification`: 900ms, one attempt, wait-for-ready off, no gRPC retry.

Identity transaction persists business state + encrypted handoff escrow +
delivery intent/outbox. Notification gRPC occurs after commit.

Dispatcher:

- `FOR UPDATE SKIP LOCKED`;
- lease 30s; batch 32;
- busy poll 250ms; idle 1s;
- durable retry 1s, 2s, 5s, 10s, then max 30s ±20%;
- time-bound cutoff at `message_not_after - 5s`;
- non-time-bound automatic retry max 30m, then `HANDOFF_FAILED` + alert.

After Notification `ACCEPTED`, caller handoff recipient/code material is
irreversibly removed while authoritative contact and one-way challenge state
remain.

## 5. Tenant v1

Tenant create is self-service or platform-admin. Creator becomes initial owner.
Tenant remains `PROVISIONING` until idempotent Authorization owner-provisioning
ACK, then activates transactionally.

Tenant + creator membership + audit + stable owner-provisioning outbox commit in
one local transaction without network I/O.

Owner provisioning gRPC: one invocation/claim, 900ms, wait-for-ready off, no
immediate retry; durable retry 1s, 5s, 30s, 2m, 10m then 10m capped ±20%.
15m warns; 1h pages.

## 6. Sessions and tokens

- RS256 access JWT, 5-minute lifetime;
- validate issuer, audience, subject, active tenant, membership, session, `jti`,
  `iat`, `exp`;
- opaque rotating refresh credentials;
- 7-day idle / 30-day absolute refresh lifetime;
- keyed secure digest persistence;
- predecessor invalidated on rotation;
- refresh reuse revokes the family;
- browser retains only the secure BFF cookie, never Identity tokens.

### Access-token signing-key lifecycle

ADR-0052 completes the RS256 trust model. Identity signs access tokens locally with RSA-3072 private keys sourced from OpenBao through External Secrets and mounted read-only. Every signing key has an immutable random `kid`; private signing material never enters Git, environment variables, events, databases, logs, traces, or metrics.

BFF and resource services verify tokens against a bounded public JWK bundle distributed as reviewed non-secret GitOps configuration. Normal verification performs no network call to Identity, OpenBao, or a remote JWKS endpoint. Rotation pre-publishes the next public key before Identity switches signing `kid`; normal rotation is 90 days with at least 24 hours of previous-public-key overlap. Unknown `kid`, algorithm confusion, invalid signature/issuer/audience, and invalid token-time claims fail closed.

## 7. Password credential baseline

Local passwords use the Technology Baseline Argon2id profile (`m=19 MiB`,
`t=2`, `p=1`, random 16-byte salt, >=32-byte hash) behind the Identity security
port. Stored encodings are versioned/self-describing and are rehashed after a
successful authentication when the approved parameters increase.

Password-only authentication requires at least 15 Unicode code points and
accepts up to 128; NFC normalization is applied before hashing. There are no
composition rules or periodic forced rotations. Create/change/reset checks the
compromised-password service. Verification uses a bounded hash bulkhead and the
ADR-0041 failed-attempt quota semantics so a subject-level attack cannot by
itself turn into a permanent account lockout.

## 8. External identity

Google uses OIDC Authorization Code through Web BFF under ADR-0045 PKCE/session
rules. Stable identity is `(issuer, subject)`. Email equality never auto-links.
Linking requires authenticated settings/recovery or later accepted equivalent.

## 9. MFA

TOTP:

- HMAC-SHA-256;
- 6 digits / 30s / ±1 step;
- issuer `SajTech`;
- AES-256-GCM local versioned key ring sourced from OpenBao;
- 10 independent 80-bit recovery codes shown once, stored as domain-separated
  HMAC-SHA-256, atomic single-use;
- enroll/disable/replace/recover requires authentication age <=5m;
- no trusted devices in v1.

ADR-0049 makes SMS MFA production-eligible for Iran through IPPanel Webservice
mode only after ADR-0041 quotas, provider contract/credential readiness,
Notification encrypted exact-content lifecycle, and ADR-0038 MFA controls are
verified. `LoggingSmsProviderAdapter` remains local-only and can never satisfy
production readiness.

## 10. Semantic quotas

ADR-0041 resolves ADR-0040's architecture decision. Identity enforces its own
operation quotas through ACL-isolated `security-redis`, not a quota service or
PostgreSQL.

Covered current production operations include login, Google login/link,
tenant create/invite, and MFA lifecycle/recovery using the exact baseline
dimensions/windows/costs in ADR-0041. ADR-0054 hardens refill time with dual
trusted clocks and fail-closed skew detection; Redis TTL is not security reset
authority.

Authentication anti-lockout behavior is mandatory: source quota blocks before
credential work; identifier failed-attempt quota is charged after failure and
cannot alone block a subsequently proven correct credential once source controls
allow evaluation.

Production remains disabled for each gated entry point until quota atomicity,
PII-keying, Redis failover/outage, abuse, and load tests pass.

## 11. Browser boundary

Identity does not expose internal tokens to React. BFF owns browser sessions,
OIDC transaction state, PKCE, CSRF, CORS, and secure cookie behavior per
ADR-0045.

## 12. PostgreSQL

Identity owns its dedicated PostgreSQL database on an independent ADR-0057
CloudNativePG production cluster. Identity runtime is `NOSUPERUSER NOBYPASSRLS`,
not table owner, and tenant-owned tables use forced RLS in addition to application
tenant checks. Flyway only; Domain/JPA separation; no remote I/O in DB transactions;
cross-tenant negative tests mandatory.

ADR-0058 makes Identity the coordinator of platform-global data-subject erasure
requests while every bounded context remains responsible for irreversible
erasure/anonymization and non-PII receipts for its own data.

## 13. Verification

Applicable tests include tenant isolation, slug tombstones, last-owner
protection, provisioning replay/conflict, JWT/refresh rotation/reuse, OIDC
binding/no auto-link, PKCE contract integration with BFF, TOTP drift/replay,
key rotation, recovery-code use, recent-auth rules, semantic quotas/anti-lockout,
registration locale, Notification handoff, PostgreSQL failover, audit, and
PII-safe telemetry.
