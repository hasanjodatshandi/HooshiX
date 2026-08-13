# ADR-0038: Define Identity Tenant, Session, External Identity, and MFA v1

## Status

Accepted

## Date

2026-08-10

## Decision

Identity supports self-service and platform-admin tenant creation. The creator
becomes the initial `tenant_owner`. Tenant contains UUID `tenant_id`, Unicode
`name` of 1..120 code points, immutable slug, status, creator and UTC-microsecond
timestamps, logical-deletion metadata, and optimistic-lock version.

Slug is lowercase ASCII, 3..63 characters, matches
`^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])$`, is canonicalized before lookup, and is
unique even for deleted tenants. Deleted slugs are never reused. The configurable
reserved list initially contains `admin`, `api`, `system`, `platform`, `www`,
and `support`. Name is mutable.

Statuses are `PROVISIONING`, `ACTIVE`, `SUSPENDED`, `DELETING`, and `DELETED`.
Invitation, acceptance, initial-owner provisioning, suspension, deletion, and
append-only lifecycle audit are in v1.

Tenant creation commits Tenant, creator membership, audit, and stable
initial-owner outbox in one transaction without network I/O. Tenant remains
`PROVISIONING` until Authorization ACKs idempotent owner provisioning, then
Identity activates it transactionally. One gRPC invocation per claim has a
900ms deadline, wait-for-ready off, and no immediate retry. Durable delays are
1s, 5s, 30s, 2m, 10m, then 10m capped with plus/minus 20 percent jitter.
Transient `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`, `ABORTED`,
and `INTERNAL` retry. Definitive errors alert. Retry continues until ACK or
cancellation; 15 minutes warns and one hour pages.

Access tokens are RS256 JWTs with five-minute lifetime and validated issuer,
audience, subject, active tenant, membership, session, `jti`, `iat`, and `exp`.
Refresh credentials are opaque and rotating, stored only as keyed secure
digests, with seven-day idle and 30-day absolute lifetime. Rotation invalidates
the predecessor and reuse detection revokes the family. Browsers retain only
the secure BFF cookie.

Google remains OIDC Authorization Code through the BFF. BFF-to-Identity uses
typed gRPC and strict mTLS/workload identity without a second assertion.
Identity validates configured issuer/audience allow-lists. Stable identity is
issuer plus subject; email equality never auto-links.

TOTP uses HMAC-SHA-256, six digits, 30 seconds, plus/minus one step, and issuer
`SajTech`. Secrets use AES-256-GCM with a local versioned key ring materialized
from OpenBao. Each recovery set contains ten independent 80-bit codes shown
once and stored as domain-separated HMAC-SHA-256. Consumption is atomic and
single-use. Enrollment, disable, replacement, and recovery require
authentication no older than five minutes. Trusted devices do not exist in v1.
SMS MFA remains production-disabled until ADR-0033 is superseded.

## Verification Requirements

Tests cover tenant isolation, slug tombstones, last-owner protection, outbox
replay/conflict, JWT validation, refresh rotation/reuse, OIDC binding and no
auto-link, TOTP drift/replay, encryption rotation, recovery-code consumption,
recent authentication, audit, and PII-safe telemetry.

## Rollback Considerations

Forward-only migrations preserve identifiers. Rollback cannot reuse a slug,
resurrect a rotated refresh credential, or bypass MFA/key lifecycle.
