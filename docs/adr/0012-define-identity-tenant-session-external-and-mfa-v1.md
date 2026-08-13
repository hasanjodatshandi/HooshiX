# ADR-0012: Identity Tenant, Session, External Identity, and MFA v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13; Identity implementation contracts finalized on 2026-08-13

## Decision

### Identity-owned internal contract surface

Identity owns versioned, feature-scoped gRPC + Protobuf contracts for:

- registration and registration verification;
- password authentication and session/refresh-family lifecycle;
- tenant and tenant-membership lifecycle;
- tenant invitation lifecycle;
- external-identity establish/link/unlink flows;
- MFA enrollment, challenge, recovery, disable, and replacement;
- data-subject erasure coordination entry points.

Contracts are governed by ADR-0003. Identity generates security-sensitive identifiers, challenge/session/token TTLs, and policy values. Callers provide only stable `request_id` values and explicitly allowed business inputs; callers cannot supply token lifetime, challenge lifetime, quota policy, hashing parameters, key identifiers, MFA policy, or other server-owned security policy.

Internal errors use stable typed machine codes mapped to bounded gRPC statuses. Raw exception/cause text, provider payloads, SQL details, credentials, or PII are never copied into transport errors.

Provider-owned contracts remain provider-owned. Identity consumes Notification and Authorization canonical Protobuf sources rather than copying their schemas or implementing those services' runtimes inside Identity.

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

### Tenant invitation

v1 invitation targets an already existing non-erased User through an Identity-owned verified contact reference. Raw email/phone is not placed in the provisioning outbox or Authorization contract.

- target contact must be verified and belong to the target User;
- invitation acceptance requires the authenticated User to be the same target User;
- invitation TTL is seven days;
- at most one pending invitation exists for `(tenant_id, target_user_id)`;
- resend/reissue creates a new reviewed invitation intent rather than extending an already expired credential implicitly;
- inviting an unregistered email/phone is outside v1 and requires an explicit registration-linking workflow decision.

### Persistence and consistency boundaries

Identity uses separate Domain and persistence models. JPA/Hibernate is the default for aggregate CRUD and ordinary bounded queries; JDBC/jOOQ may be used in Infrastructure where SQL-level control is required, including `FOR UPDATE SKIP LOCKED`, durable outbox/inbox work claims, or measured query paths.

The v1 consistency boundaries are:

- `User` + `Profile` + active `Contact` set as one consistency boundary for user/profile/contact invariants;
- `Credential` as a separate aggregate;
- registration/recovery `Challenge` as a separate aggregate;
- `Session` / `RefreshFamily` as a separate aggregate boundary;
- `MfaEnrollment` / recovery material as a separate aggregate boundary;
- `ExternalIdentity` as a separate aggregate;
- `Tenant`, `TenantMembership`, and `Invitation` as tenant lifecycle aggregates coordinated by Application use cases and local transactions/outboxes as required.

One giant JPA entity graph spanning these capabilities is prohibited. Remote gRPC/HTTP/Kafka/Redis/provider I/O never occurs inside an Identity database transaction.

Global Identity tables are explicitly separated from tenant-owned tables. Tenant-owned production tables use forced RLS; runtime roles remain non-owner `NOSUPERUSER NOBYPASSRLS`, and tenant context is parameterized/transaction-local under ADR-0027.

### Access-token contract

Access tokens are RS256 JWTs with five-minute lifetime. The v1 claim contract is intentionally authorization-minimal.

Standard claims:

```text
iss
aud
sub
jti
iat
exp
```

Private claims:

```text
tenant_id
membership_id
sid
```

`roles`, `permissions`, `authorization_version`, or equivalent permission snapshots are prohibited from becoming authorization authority in the access token. Protected resource services use the current online Authorization contract.

`iss` is typed deployment configuration; the initial production logical value is `https://identity.sajtech.internal` unless the reviewed environment configuration replaces it before rollout. `aud` is the exact intended service/audience identifier and wildcard audiences are prohibited.

Verifiers validate algorithm/signature/key, configured issuer, exact audience, `sub`, `jti`, `iat`, `exp`, required active tenant/membership/session claims, and canonical size bounds. Signing/private-key lifecycle follows ADR-0023.

### Refresh credentials

Refresh credentials are opaque/rotating, stored only as keyed secure digests, with seven-day idle and 30-day absolute lifetime. Rotation invalidates predecessor; reuse detection revokes the credential family. Browsers retain only the secure BFF session cookie.

Normal resource verification is local from the approved public verifier bundle and does not call Identity/OpenBao/JWKS per request.

### Google OIDC/external identity handoff

Google Authorization Code + PKCE S256 protocol mechanics belong to Web BFF under ADR-0016. BFF validates `state`, `nonce`, PKCE, exact redirect, signature, issuer, audience, and timestamps before invoking Identity.

Identity does **not** call Google OIDC endpoints during the login/link request path and never receives Google authorization codes, access tokens, refresh tokens, or ID tokens.

After successful provider validation, BFF invokes the typed Identity gRPC contract using its authenticated workload identity and supplies:

- a cryptographically random, short-lived, single-use `evidence_id`;
- canonical validated `issuer`;
- validated provider `subject`;
- stable BFF-owned request identity and only explicitly versioned non-secret evidence metadata required by the contract.

The `evidence_id` is consumed atomically by Identity and cannot be replayed for a second login/link effect. Its TTL is server-owned and bounded by the originating pre-auth transaction; callers cannot extend it. Identity validates the configured provider/issuer allow-list and the BFF workload identity, then binds external identity only by stable `(issuer, subject)`. Email equality never auto-links accounts.

Provider credentials and Google client secret are delivered only through the approved OpenBao/External Secrets boundary and never through Git, chat, browser storage, Identity payloads, or telemetry.

### MFA login gate

TOTP uses HMAC-SHA-256, six digits, 30-second step, ±1 step, issuer `SajTech`.

When an account has active TOTP MFA, successful password verification does **not** issue an access token or refresh credential. It creates a short-lived pre-auth challenge. Access/refresh issuance occurs only after the same challenge is completed by a valid TOTP code or one valid single-use recovery code.

The pre-auth challenge is bound to the intended User/session context, is single-use, expires under server-owned policy, and is invalidated on successful completion or security-relevant cancellation/restart. MFA verification remains subject to semantic quotas and non-enumerating failure behavior.

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

### Idempotency and security evidence

Security-sensitive idempotency/intent fingerprints use purpose-separated, versioned HMAC-SHA-256 with locally available key material delivered through the approved secret boundary. Plain unsalted SHA-256 is not used for guessable security/business intent fingerprints.

For an idempotent command:

- identical `request_id` + equal fingerprint returns the original committed result;
- conflicting reuse of the same `request_id` returns `ALREADY_EXISTS` with a stable machine code;
- comparison does not expose canonical sensitive input;
- retained critical publication/idempotency evidence follows the 35-day recovery horizon where ADR-0015 applies.

Security audit evidence is append-only/durable under the current logging/audit policy and retained at least 365 days unless a stricter approved data-class policy applies.

## Verification requirements

Tests cover:

- feature-scoped Protobuf/Buf compatibility and typed error mapping;
- server-owned identifiers/TTLs/policy fields and rejection of caller-controlled security policy;
- tenant isolation, slug tombstones, invitation target/TTL/single-pending/acceptance ownership;
- owner-provisioning outbox replay/conflict;
- aggregate boundaries and absence of remote I/O inside DB transactions;
- forced RLS and pooled transaction-local tenant-context negatives;
- exact JWT claim allow-list, wildcard-audience rejection, prohibited permission/role snapshot claims, signing-key compatibility;
- refresh rotation/reuse;
- OIDC BFF-only provider validation, provider-token absence from Identity, evidence replay/expiry/workload-identity negatives, no-auto-link;
- password + TOTP pre-auth gate proving no access/refresh issuance before MFA completion;
- TOTP drift/replay, encryption rotation, recovery-code atomic single use;
- SMS MFA readiness/failure behavior;
- recent authentication, idempotency fingerprint replay/conflict, audit, and PII-safe telemetry.

## Rollback considerations

Rollback preserves stable tenant/user/membership/session/external-identity identifiers, slug non-reuse, invitation target binding, refresh-family revocation, JWT claim/verifier compatibility, MFA/key lifecycle, BFF-only provider validation, one-time evidence semantics, and durable provisioning/idempotency state.

It MUST NOT reintroduce provider tokens into Identity, direct Google verification from Identity, wildcard JWT audiences, token permission snapshots, access/refresh issuance before required MFA, unregistered-contact invitation linking, or SMS through an unverified provider/local logging fallback.