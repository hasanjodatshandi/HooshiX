# ADR-0009: Enable the Identity Registration Runtime

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13; registration invariants finalized on 2026-08-13; v1 registration/contact/authentication-entry lifecycle finalized on 2026-08-14

## Decision

### Runtime composition

Identity enables the registration runtime composition:

- `IdentityRegistrationGrpcServiceAdapter` is served on a configurable internal gRPC port; local development uses port 9090;
- `IdentityNotificationResultGrpcServiceAdapter` is isolated on the ADR-0006 callback port 9091;
- Identity generates its consumer stub from Notification's canonical provider-owned Protobuf source rather than copying the contract;
- `SubmitNotification` uses a 900ms overall deadline, one invocation, wait-for-ready disabled, and no gRPC retry;
- the durable dispatcher uses the current ADR-0006 batch/lease/cutoff/replay/backoff rules;
- registration and callback gRPC servers have independent 64-KiB inbound message and 16-KiB metadata limits;
- caller-side key rings load from read-only filesystem paths, refresh without replacing a valid snapshot on failure, and participate in readiness;
- dispatcher/key-refresh telemetry contains no business identifier or PII.

The runtime uses gRPC Java 1.81.0 aligned across transport, stubs, services, Protobuf generation, dependency locks, and verification metadata.

Production explicitly configures key staleness/refresh policies and mounted key directories. Tests disable default runtime composition except where the runtime itself is under integration test.

This enables the registration/callback runtime composition. The wider Identity feature-scoped gRPC surface is governed by ADR-0012 and ADR-0003. Notification provider dispatch, production SMS, edge routing, semantic quotas, GitOps, Istio/NetworkPolicy, and supply-chain controls remain independent production gates. `LoggingSmsProviderAdapter` is local-development-only and cannot satisfy production SMS readiness; production Iran SMS follows ADR-0020.

### Registration modes and User activation

v1 implements local registration through both `EMAIL` and `PHONE` contact channels. Local registration includes creation of a User-level local Credential and therefore requires the password to pass the current password syntax/Argon2id/compromised-password rules before the credential is committed. The compromised-password remote check occurs outside the Identity DB transaction.

A newly created User starts `PENDING`.

A `PENDING` User becomes `ACTIVE` only when all of the following are true:

- the required profile is complete under the canonical name rules below;
- at least one Contact is verified;
- the local Credential is valid when the registration mode is local password registration;
- no suspension, deletion, legal/security, or other current blocking condition applies.

The first Contact successfully verified for a User becomes that User's active primary Contact automatically. A User still has at most one active primary Contact in total.

Phone-registration code and contracts are part of v1, but staging/production phone registration remains disabled until the current ADR-0020 SMS/provider and Production Readiness SMS gates pass. The production/staging gate is typed server-owned configuration; a caller cannot enable it. Local development may enable the phone path with the approved local provider substitute. Email registration may proceed independently when its own gates pass.

### Profile and contact canonicalization

Registration creates/updates Identity-owned profile/contact state using these canonical rules:

Names:

- `firstName` and `lastName` are required and contain 1..120 Unicode code points after canonicalization;
- `fatherName` is optional and contains at most 120 Unicode code points when present;
- leading/trailing whitespace is trimmed;
- Unicode is normalized to NFC;
- case is preserved;
- internal spacing is preserved rather than collapsed or deleted;
- Unicode control characters are prohibited.

Email:

- leading/trailing whitespace is trimmed;
- canonical comparison/storage form uses `Locale.ROOT` lowercase;
- maximum length is 254 characters/code units under the validated transport/storage representation;
- syntax is validated as one mailbox;
- Gmail/provider-specific dot/plus rewriting is prohibited.

Phone:

- canonical form is E.164;
- national-number inference from locale is prohibited at the Identity contract boundary.

A verified email or verified phone is globally unique among non-erased Users. Logical deletion does not release the canonical contact value; the value remains reserved until an approved irreversible erasure/release policy permits reuse. Each User has at most one active primary Contact in total, enforced transactionally and with database constraints where representable.

### Pending-contact reservation

Before verification, one canonical email/phone value may belong to at most one live registration reservation at a time. The reservation lifetime is exactly the active registration challenge lifetime: 10 minutes. A replacement resend challenge replaces the reservation/challenge generation without extending the original semantic policy outside the newly issued 10-minute challenge.

A repeated registration request for the same canonical Contact while the matching registration remains live continues that same pending registration non-enumeratingly; it does not create a second User or a second independently valid reservation. A different request against the same live reservation MUST NOT overwrite the already committed pending profile/Credential/security-sensitive registration state merely because it knows the Contact value. Equal idempotent replay returns the original result; any continuation that changes protected registration intent requires a new reviewed flow after proof rather than silent replacement.

If the canonical Contact is already verified/reserved by a non-erased User, the caller-visible response remains bounded and non-enumerating and no second User/challenge is created.

When the challenge/reservation expires, the stale challenge cannot regain authority. The old unverified Contact no longer reserves the canonical value. A later registration must acquire a new live reservation and challenge under current uniqueness/quota rules; an expired prior challenge can never activate after another User has obtained verified ownership. Stale `PENDING` registration/User data that has no live reservation is non-authenticatable and becomes bounded cleanup/logical-deletion eligible under the current retention policy rather than retaining the Contact reservation indefinitely.

Reservation acquisition/replacement is concurrency-safe. Database uniqueness/locking is used where representable so two concurrent registration attempts cannot both obtain a live reservation for one canonical Contact.

### Local password authentication identifier

v1 has no separate username. Local password authentication accepts exactly one canonical Contact identifier plus the User-level password:

- any active **verified** email Contact may identify the User after the same provider-neutral canonicalization used at registration;
- any active **verified** phone Contact may identify the User after E.164 canonicalization;
- primary status is not required for login; verified secondary Contacts remain valid login identifiers until removed;
- unverified/removed Contacts do not authenticate;
- authentication responses remain non-enumerating across unknown Contact, missing local Credential, wrong password, suspended/deleting User, and equivalent caller-visible failure cases.

A local `PENDING` User with a valid local Credential may obtain only the restricted ADR-0012 onboarding continuation after proving a verified Contact/password and any required MFA; no normal tenant-scoped access JWT is issued until all activation/tenant-selection rules pass. A local `PENDING` User with no verified Contact cannot use password login as a shortcut around registration verification.

### Registration verification challenge

Registration verification code semantics are server-owned and fixed for v1:

```text
code format:          exactly 8 decimal digits
randomness:           SecureRandom / CSPRNG
stored verifier:      purpose-separated HMAC-SHA-256
plaintext persistence:none
TTL:                  10 minutes
maximum failed tries: 5
minimum resend gap:   60 seconds
single use:           yes
```

A resend that is permitted by policy creates a replacement challenge and immediately invalidates the previous challenge. A caller cannot extend TTL, increase attempts, reduce resend spacing, choose the HMAC/key version, or otherwise supply challenge policy.

The challenge verifier comparison is constant-time. Plaintext code exists only for the bounded creation/delivery path and is never persisted to PostgreSQL/Redis/Kafka/outbox/audit/logs/traces/metrics after the handoff representation has been safely created.

Registration challenge locale follows ADR-0008: initial registration supplies explicit canonical `fa`/`en`; resend reuses the persisted immutable locale and cannot change it.

### Semantic abuse controls

The registration entry points `REGISTER`, `RESEND_REGISTRATION_VERIFICATION`, and `CONFIRM_REGISTRATION` are protected by Identity-owned semantic quota policy in its ACL-isolated security Redis namespace under ADR-0024 semantics: atomic evaluation, pseudonymous keys, finite 75ms budget, one attempt, no retry/fallback, and non-enumerating caller behavior.

ADR-0024 is authoritative for the now-fixed v1 numeric capacities/refill/cleanup horizons for these three registration operations. They are server-owned and cannot be supplied by the request. The challenge-local five-failed-try limit remains authoritative for confirmation proof attempts in addition to the network quota. A quota dependency/time-source failure is an availability/security-dependency failure and is not misreported as an invalid code or account-existence result.

Authenticated add-contact verification uses the same v1 challenge format/TTL/attempt/resend safety baseline under a distinct purpose/key namespace; it never reuses a registration challenge verifier or plaintext secret.

### Registration idempotency and handoff

Stable registration/handoff request identities use the current purpose-separated versioned HMAC fingerprint policy. Equal replay returns the original committed result. Conflicting reuse of a stable request ID returns `ALREADY_EXISTS` with a typed machine code.

Registration state change + Notification handoff intent is committed locally before remote delivery. Notification invocation occurs only after the local transaction commits; no network I/O occurs in the registration transaction.

## Security and verification requirements

Tests prove:

- both gRPC servers bind distinct ports and enforce the configured message/metadata bounds;
- malformed calls fail closed and usable key-ring state participates in readiness;
- EMAIL and PHONE registration composition, local password/compromised-password requirement, with staging/production phone registration impossible until the SMS gate is explicitly satisfied;
- `PENDING -> ACTIVE` occurs only after required profile completion + at least one verified Contact + applicable local Credential and first verification establishes the primary Contact;
- profile name trim/NFC/control-character/length behavior and case/internal-space preservation;
- canonical email/provider-neutral behavior, E.164 phone behavior, verified-contact uniqueness, logical-delete reservation, and at-most-one active primary Contact per User under concurrent updates;
- one-live-registration reservation per canonical Contact, 10-minute expiry, concurrent acquisition, repeated same-pending continuation without protected-state overwrite, stale-pending cleanup eligibility, and no second User/challenge for an already verified/reserved Contact;
- local password login by any verified email/phone Contact, secondary Contact login, removed/unverified Contact denial, and non-enumerating failure behavior;
- exactly eight-digit CSPRNG challenge generation, HMAC-only persistence, constant-time verification, 10-minute TTL boundaries, five-attempt exhaustion, 60-second resend boundary, replacement invalidation, and single use;
- caller attempts to supply/extend security policy are rejected/ignored according to the versioned contract;
- locale persistence/resend behavior from ADR-0008;
- registration/resend/confirm exact ADR-0024 quota behavior, non-enumeration, and Redis outage/time-safety behavior;
- accepted typed Notification responses map correctly, one logical submission creates one transport invocation, and corrupt caller escrow becomes a permanent handoff failure without provider invocation;
- equal replay/conflicting request reuse and no remote I/O inside database transactions;
- logs/metrics/traces/errors expose no recipient, code, ciphertext, `request_id`, or `notification_id`.

Dependency locking/verification metadata covers the transport.

## Rollback considerations

Runtime exposure may be disabled with `IDENTITY_REGISTRATION_RUNTIME_ENABLED=false` without schema rollback. Phone registration may remain independently gated without removing its schema/contracts. Already committed handoffs remain durable, retain their stable `request_id`, and resume from the current lease/cutoff rules when dispatch is re-enabled.

Rollback MUST preserve contact canonicalization/verified uniqueness, pending reservation uniqueness/non-overwrite, logical-delete reservation, verified-Contact local login semantics, the single active primary-Contact invariant, `PENDING -> ACTIVE` verification/profile/Credential gate, challenge HMAC-only storage, eight-digit/10-minute/five-attempt/60-second semantics, single-use/replacement invalidation, persisted locale, and stable idempotency behavior. Executed Flyway migrations are never edited or reversed.
