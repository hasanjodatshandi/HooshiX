# ADR-0006: Define Identity Registration, External Identity, and MFA

## Status

Accepted

## Date

2026-08-06

## Context

The Identity Service owns global users according to ADR-0002 and must support
registration through verified local contact methods and Google OpenID Connect.
A newly created user cannot be treated as active before the required identity
evidence and profile data exist.

The service must also support optional multi-factor authentication through SMS
one-time passwords and authenticator applications using TOTP. These
capabilities handle identity evidence, credentials, recovery material, and PII,
so activation, linking, and persistence rules must be explicit before
implementation.

The backend architecture permits JPA/Hibernate for aggregate persistence and
controlled CRUD operations while prohibiting framework dependencies in the
Domain model. Flyway remains the only source of schema changes.

## Decision

### Ownership and aggregate boundary

The Identity Service owns:

- the global `User` aggregate;
- user profile data;
- email and mobile contact methods and verification state;
- external identities linked to a user;
- MFA enrollments and recovery material;
- registration and activation lifecycle state.

Tenant memberships, tenant roles, and permissions remain outside this
aggregate and continue to follow ADR-0002, ADR-0004, and ADR-0005.

### User lifecycle

`UserStatus` initially contains:

```text
PENDING
ACTIVE
DELETED
```

A user is created as `PENDING`.

A user becomes `ACTIVE` only when:

1. at least one accepted primary identity or contact method is verified;
2. required profile fields are complete; and
3. no security or deletion rule blocks activation.

Logical deletion changes the user to `DELETED` and follows ADR-0003. Normal
queries exclude deleted users. Restoration, retention, erasure, purge, and
legal-hold behavior are not redefined here.

### Profile and contact data

The initial `UserProfile` contains:

- required `firstName`;
- required `lastName`;
- optional `fatherName`.

Email addresses and mobile phone numbers are contact methods owned by the User
aggregate. They are not duplicated as profile fields. Each contact method owns
its normalized value, verification state, lifecycle, and usage rules.

A phone number is optional for the base profile and becomes required only for a
flow that depends on it, including mobile registration or SMS MFA.

Phone numbers are normalized and stored in E.164 form. Email addresses are
normalized for lookup and uniqueness by trimming and lowercasing with
`Locale.ROOT`. Provider-specific rewriting, including Gmail dot removal or
plus-address rewriting, is prohibited.

Names, email addresses, and phone numbers are PII and follow the logging,
retention, access, and erasure requirements in the backend architecture and
ADR-0003.

### Local registration and verification

Email registration creates a `PENDING` user and requires successful email
verification before email can satisfy activation.

Mobile registration creates a `PENDING` user and requires successful mobile
verification before phone can satisfy activation.

Verification challenges are short-lived, single-use, rate-limited, and limited
by an attempt counter. Raw OTP values, passwords, tokens, and recovery codes
must never be logged.

### Local credentials

Local email or mobile registration may create one local password credential
linked to the user. A local credential is separate from `UserProfile`, contact
methods, external identities, and MFA enrollments.

A raw password is accepted only at the trusted inbound boundary and crosses one
explicit hashing boundary. It is never persisted, logged, included in events,
returned by APIs, or encrypted for later recovery.

Only a salted adaptive one-way password hash and the parameters needed to verify
it are persisted. Reversible password encryption is prohibited.

Password hashing and verification are exposed to Application through an
outbound security port and implemented in Infrastructure. Domain code does not
depend on Spring Security or `PasswordEncoder`.

The approved algorithm and work parameters are pinned in the Technology
Baseline before credential implementation. The persisted format must support
safe parameter upgrades and rehashing after successful authentication.

Password creation, change, and reset are high-risk operations. They are
rate-limited and audited without sensitive values. A successful reset must
invalidate applicable sessions and tokens through the accepted authentication
revocation flow.

### Google OpenID Connect

Google registration and login use OpenID Connect Authorization Code Flow
through the Web BFF.

The Web BFF is the OpenID Connect relying party. It validates the provider
response and ID token, including signature, issuer, audience, expiry, nonce, and
request correlation, before calling the Identity Service through a trusted
internal interface.

React does not store Google, access, refresh, or identity tokens in
`localStorage` or `sessionStorage`. The BFF owns the secure browser session.

A Google identity is identified by:

```text
issuer + subject
```

The OpenID Connect `sub` claim is the provider subject. Email is not the stable
external-identity key.

External identities have a unique persistence constraint on:

```text
(issuer, subject)
```

A successfully validated Google identity with `email_verified=true` satisfies
the email-verification requirement. The user remains `PENDING` until required
profile data are complete. Google claims may prefill profile fields, but the
Identity Service remains the source of truth.

### External-identity linking

An email match alone must not automatically link a Google identity to an
existing local user.

Linking is allowed only through:

- an authenticated account-settings flow;
- an explicitly verified account-recovery flow; or
- a later accepted ADR defining equivalent security.

Creating or linking the local user and external identity is atomic. Failure must
not leave a partial identity record.

The initial provider enumeration contains `GOOGLE`, but the model uses a
provider-independent external-identity abstraction.

### Multi-factor authentication

The initial MFA methods are:

```text
SMS_OTP
TOTP
```

TOTP represents authenticator applications and does not depend on a specific
vendor application.

`SMS_OTP` and `TOTP` are distinct assurance methods. Supporting both does not
make them interchangeable. Security policy may require TOTP and reject SMS OTP
for a high-risk operation.

An MFA enrollment has these lifecycle states:

```text
PENDING
ACTIVE
DISABLED
```

An enrollment becomes `ACTIVE` only after successful proof of the new factor.

SMS OTP requirements:

- phone ownership is already verified;
- challenges are short-lived, single-use, rate-limited, and attempt-limited;
- raw OTP values are never persisted or logged;
- provider communication occurs outside database transactions.

TOTP requirements:

- the secret is encrypted at rest with a managed key;
- the plaintext secret is never logged;
- activation requires successful TOTP verification;
- rotation and replacement are explicit operations.

Recovery codes have sufficient entropy, are displayed once, and are stored only
as secure hashes. Enabling, disabling, replacing, or recovering MFA is a
high-risk operation and requires recent authentication.

Production key management depends on the separate Secret Manager decision. The
domain and persistence model may be implemented before provider integration,
but production secret storage must not bypass that pending decision.

### Persistence

Spring Data JPA/Hibernate is used for User aggregate persistence and controlled
CRUD operations.

Mandatory settings are:

```yaml
spring:
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
```

Flyway is the only source of database schema changes.

Domain aggregates, entities, value objects, events, and exceptions remain plain
Java and do not depend on Spring or JPA.

JPA entities, Spring Data repositories, mappings, and fetch plans remain inside
the persistence adapter. Domain objects and JPA entities are separate and use
explicit mappers.

Associations are lazy by default. Aggregate boundaries, cascades, orphan
removal, fetch plans, batching, and transaction boundaries require explicit
review.

Complex reporting, bulk operations, CTEs, window functions, and
performance-sensitive queries use jOOQ when introduced and justified.

### Events and transactions

Domain events are transport-independent and are not Kafka messages.

When a state change requires an integration event, aggregate persistence and
the outbox record occur in the same local database transaction. Publishing
directly to Kafka after repository save is prohibited.

Network calls to Google, email, SMS, Kafka, Redis, or another service do not
occur inside a database transaction.

## Consequences

- Registration has an explicit incomplete state.
- Google identities use stable OpenID Connect identity instead of mutable email.
- Automatic account linking by email is prohibited, reducing takeover risk.
- Profile and contact methods have separate responsibilities and one source of
  truth for each email address and phone number.
- Profile, verification, local credentials, external identity, and MFA become
  explicit Identity concepts.
- Local credentials require a dedicated hashing port, upgradeable hash format,
  and session/token invalidation on reset.
- Persistence requires explicit mapping between Domain and JPA models.
- MFA introduces encryption, recovery, rate limiting, and high-risk-operation
  controls.
- Google login requires coordinated BFF and Identity contracts while browser
  tokens remain outside frontend storage.
- Production SMS and TOTP secret handling remain blocked until provider and
  secret-management decisions are approved.

## Alternatives considered

### Treat every persisted user as active

Rejected because unverified or incomplete registrations would receive active
status.

### Use email as the Google identity key

Rejected because email is mutable and is not the stable OpenID Connect subject.

### Automatically link matching verified emails

Rejected because email equality alone is insufficient authorization for
account linking.

### Put Google columns directly on users

Rejected because it couples User to one provider and limits multiple linked
identities.

### Store mobile number in both profile and contact method

Rejected because duplicated ownership creates conflicting verification,
normalization, deletion, and update state. Mobile numbers remain contact
methods; the profile does not duplicate them.

### Store local passwords reversibly or in profile data

Rejected because passwords are credentials, not profile attributes. Only an
approved salted adaptive one-way hash may be persisted.

### Store Domain objects as JPA entities

Rejected because it violates dependency rules and couples business models to
persistence technology.

### Model MFA as one boolean on User

Rejected because enrollment lifecycle, factor replacement, recovery, and
multiple method types require explicit modeling.

### Require phone and father name for every account

Rejected initially. Phone is required only for dependent flows such as SMS MFA.
Father name remains optional unless a later business or legal rule changes it.

## Rollback or migration considerations

This ADR precedes the first User schema migration, so no existing production
user data require migration.

If implementation is rolled back before release, migrations and code may be
removed only while the migrations remain unexecuted outside disposable
environments.

After a migration is executed in a shared or production-like environment, it
must not be edited or deleted. Reversal uses additive Flyway migrations and the
expand-migrate-contract strategy.

Removing Google or an MFA method later must preserve identity and recovery data
until an explicit migration, user-notification, fallback-access, retention, and
security plan is accepted.

A later ADR may supersede activation, linking, or MFA rules but must not
silently rewrite this historical decision.
