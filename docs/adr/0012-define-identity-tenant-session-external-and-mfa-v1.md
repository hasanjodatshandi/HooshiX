# ADR-0012: Identity Tenant, Session, External Identity, and MFA v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

## Decision

### Tenant creation and lifecycle

Identity supports self-service and platform-admin tenant creation. Creator becomes initial `tenant_owner`.

Tenant stores immutable UUID `tenant_id`, mutable Unicode name (1..120 code points), immutable canonical slug, status, creator, UTC-microsecond timestamps, logical-deletion metadata, and optimistic-lock version.

Slug is lowercase ASCII, 3..63 chars, matches:

```text
^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])$
```

It is canonicalized before lookup, unique including deleted tenants, and never reused. Initial reserved names include `admin`, `api`, `system`, `platform`, `www`, `support`.

Lifecycle:

```text
PROVISIONING
ACTIVE
SUSPENDED
DELETING
DELETED
```

Invitation, acceptance, initial-owner provisioning, suspension, deletion, and append-only lifecycle audit are in v1.

Tenant creation commits Tenant + creator membership + audit + stable owner-provisioning outbox in one local transaction without network I/O. Tenant remains `PROVISIONING` until Authorization idempotently acknowledges owner provisioning; Identity then activates it transactionally.

Owner-provisioning transport baseline:

```text
deadline: 900 ms
attempts per claim: 1
wait-for-ready: off
immediate transport retry: none
durable delays: 1s, 5s, 30s, 2m, 10m, then <=10m ±20%
```

Transient `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`, `ABORTED`, `INTERNAL` remain durable-retry candidates. Definitive errors alert. 15 minutes pending warns; one hour pages unless the workflow has been explicitly cancelled/resolved.

### Access and refresh credentials

Access tokens are RS256 JWTs with five-minute lifetime and validated issuer, audience, subject, active tenant, membership, session, `jti`, `iat`, and `exp`.

Refresh credentials are opaque/rotating, stored only as keyed secure digests, with seven-day idle and 30-day absolute lifetime. Rotation invalidates predecessor; reuse detection revokes the credential family. Browsers retain only the secure BFF session cookie.

Signing/private-key lifecycle follows ADR-0023; normal resource verification is local from the approved public verifier bundle and does not call Identity/OpenBao/JWKS per request.

### Google OIDC/external identity

Google uses OIDC Authorization Code + PKCE S256 through Web BFF. BFF-to-Identity uses typed gRPC over strict mTLS/workload identity. Identity validates configured issuer/audience allow-lists.

Stable external identity is `issuer + subject`; email equality never auto-links accounts.

### TOTP and recovery

TOTP uses HMAC-SHA-256, six digits, 30-second step, ±1 step, issuer `SajTech`.

Secrets use a local versioned AES-256-GCM key ring sourced through OpenBao/External Secrets. Recovery set contains ten independent 80-bit codes shown once and stored only as domain-separated HMAC-SHA-256. Consumption is atomic/single-use.

Enrollment, disable, replacement, and recovery require authentication age <=5 minutes. Trusted devices do not exist in v1.

### SMS MFA

Production Iran SMS MFA is enabled only when all current controls are healthy/verified:

- semantic quota enforcement/time safety;
- Notification durable encrypted exact-content handoff;
- IPPanel Webservice production credentials/contract fixtures;
- provider ambiguity/reconciliation/delivery-evidence behavior;
- Identity MFA/session/recent-auth controls;
- workload/network authorization and PII-safe telemetry.

Provider unavailability fails the SMS-dependent operation. The local logging SMS adapter is not a staging/production fallback.

## Verification requirements

Tests cover tenant isolation, slug tombstones, last-owner protection, owner-provisioning outbox replay/conflict, JWT validation, refresh rotation/reuse, OIDC binding/no-auto-link, signing-key compatibility, TOTP drift/replay, encryption rotation, recovery-code atomic single use, SMS MFA readiness/failure behavior, recent authentication, audit, and PII-safe telemetry.

## Rollback considerations

Rollback preserves stable tenant/user/membership/session/external-identity identifiers, slug non-reuse, refresh-family revocation, JWT verifier compatibility, MFA/key lifecycle, and durable provisioning state. It MUST NOT enable SMS through an unverified provider/local logging fallback or weaken external identity binding.
