# ADR-0006: Identity Registration, External Identity, Credentials, and MFA

## Status

Accepted — current effective decision

## Date

2026-08-06; normalized to current-only documentation on 2026-08-13

## Decision

### Identity aggregate ownership

Identity Service owns:

- global `User` identity and profile;
- Email/mobile contact methods and verification state;
- local password credentials;
- external identities;
- MFA enrollments and recovery material;
- registration/activation lifecycle;
- Tenant and TenantMembership lifecycle according to the current tenancy model.

Authorization roles, permission assignments/evaluation, and authorization audit remain Authorization Service concerns.

Domain objects remain plain Java. JPA/Hibernate, Spring Data, key-store adapters, provider clients, and transport DTOs remain Infrastructure/Interfaces concerns.

### User lifecycle and profile

`UserStatus`:

```text
PENDING
ACTIVE
DELETED
```

A new local registration starts `PENDING`. Activation requires at least one approved verified primary identity/contact method, required profile completion, and no security/deletion block.

Initial profile contains required `firstName`, required `lastName`, and optional `fatherName`. Email/mobile remain contact methods rather than duplicated profile fields.

Logical deletion/retention/erasure/legal hold follow the current platform deletion model.

### Contact normalization

Phone numbers are canonical E.164. Email lookup/uniqueness uses the repository-approved provider-neutral normalization; provider-specific Gmail dot removal/plus rewriting is prohibited.

Names, emails, and phone numbers are PII and follow the current logging/access/retention/erasure policy.

### Local registration and verification

Email/mobile registration creates a `PENDING` user. The corresponding contact method cannot satisfy activation until verified.

Verification challenges are short-lived, single-use, attempt-limited, semantically rate-limited, and delivered through the current durable Notification handoff. Raw OTP/code values are never logged, persisted as plaintext, emitted in events, or returned after generation.

### Password credentials

Raw password exists only at the trusted boundary and one explicit hashing boundary. It is never persisted, logged, emitted, returned, or reversibly encrypted.

Production password baseline is current Technology Baseline/security architecture:

- Argon2id;
- memory 19 MiB;
- iterations 2;
- parallelism 1;
- random 16-byte salt;
- >=32-byte derived hash;
- self-describing/versioned format and rehash-on-success when the approved baseline increases;
- 15..128 Unicode code points with NFC normalization;
- compromised-password screening on create/change/reset;
- bounded hash/verification concurrency;
- no arbitrary composition rules or periodic forced rotation without compromise evidence.

Password hashing is exposed through an Application outbound security port and implemented in Infrastructure. Successful password reset invalidates applicable session/token state according to the current session/revocation contract.

### Google OpenID Connect and external identity linking

Browser Google login/registration uses OIDC Authorization Code + PKCE S256 through Web BFF. Browser code does not store provider, access, refresh, or internal tokens in `localStorage`/`sessionStorage`.

External identity stable key is:

```text
issuer + subject
```

Persistence enforces uniqueness on `(issuer, subject)`. Email is never the external identity key.

A validated external identity may provide verified Email evidence according to provider/current OIDC rules, but required profile/security state still gates activation.

Email equality alone MUST NOT auto-link an external identity to an existing local account. Linking requires an authenticated account-settings flow, explicitly verified recovery flow, or another current reviewed security flow with equivalent assurance. User + external identity creation/linking is atomic.

### MFA

Current methods:

```text
SMS_OTP
TOTP
```

`SMS_OTP` and `TOTP` are distinct assurance methods; policy may require TOTP for higher-risk operations.

Enrollment lifecycle:

```text
PENDING
ACTIVE
DISABLED
```

Activation requires successful proof of the new factor.

TOTP current baseline:

- HMAC-SHA-256;
- 6 digits;
- 30-second step;
- ±1 step;
- issuer `SajTech`;
- purpose-specific local AES-256-GCM key ring sourced through OpenBao/External Secrets;
- recent authentication <=5 minutes for enroll/disable/replace/recovery;
- no trusted-device bypass in v1.

Recovery set contains 10 independent 80-bit codes, shown once and stored only as domain-separated HMAC-SHA-256 digests. Consumption is atomic/single-use.

SMS OTP requires a previously verified phone, current semantic quota controls, current Notification encrypted exact-content lifecycle, and production IPPanel Webservice readiness. Provider failure does not activate a local logging adapter or unreviewed fallback.

### Persistence

Spring Data JPA/Hibernate is approved for Identity aggregate persistence/control-oriented CRUD with:

```yaml
spring:
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
```

Flyway is the only schema-change mechanism. Domain and JPA models remain separate with explicit mapping. Associations are LAZY by default. Cascades/orphan removal/fetch plans/batching/transaction boundaries require aggregate-specific review. jOOQ may be used for justified complex/reporting/performance-sensitive queries.

### Events and transactions

Domain events are transport-independent. When state change + integration event are one business effect, persistence + Transactional Outbox record commit in one local DB transaction.

Google/provider/Notification/Kafka/Redis/other service network I/O MUST NOT occur inside a database transaction.

## Verification requirements

Tests cover activation gating, contact normalization/uniqueness, password boundary/rehash/compromised-password checks/bulkhead, OIDC issuer+subject binding, no email-only auto-link, atomic linking, TOTP drift/replay/key rotation/recovery-code single use, SMS MFA production gating, PII-safe telemetry, JPA/domain separation, tenant isolation, and no remote I/O inside DB transactions.

## Rollback considerations

Rollback MUST preserve immutable user/external-identity IDs, issuer+subject bindings, credential-hash compatibility, MFA/recovery security, executed Flyway migrations, session/token invalidation semantics, and encrypted key-version compatibility. It cannot silently enable email-only linking, plaintext/reversible credentials, weaker MFA storage, or production local-SMS fallback.
