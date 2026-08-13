# ADR-0009: Enable the Identity Registration Runtime

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13; registration invariants finalized on 2026-08-13

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

A verified email or verified phone is globally unique among non-erased Users. Logical deletion does not release the canonical contact value; the value remains reserved until an approved irreversible erasure/release policy permits reuse. Each User has at most one active primary contact for a contact channel/type under the current profile model, enforced transactionally and with database constraints where representable.

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

Exact numeric quota capacities/refill values are versioned security-baseline configuration. They are server-owned and cannot be supplied by the request. A quota dependency/time-source failure is an availability/security-dependency failure and is not misreported as an invalid code or account-existence result.

### Registration idempotency and handoff

Stable registration/handoff request identities use the current purpose-separated versioned HMAC fingerprint policy. Equal replay returns the original committed result. Conflicting reuse of a stable request ID returns `ALREADY_EXISTS` with a typed machine code.

Registration state change + Notification handoff intent is committed locally before remote delivery. Notification invocation occurs only after the local transaction commits; no network I/O occurs in the registration transaction.

## Security and verification requirements

Tests prove:

- both gRPC servers bind distinct ports and enforce the configured message/metadata bounds;
- malformed calls fail closed and usable key-ring state participates in readiness;
- profile name trim/NFC/control-character/length behavior and case/internal-space preservation;
- canonical email/provider-neutral behavior, E.164 phone behavior, verified-contact uniqueness, logical-delete reservation, and primary-contact uniqueness/concurrency;
- exactly eight-digit CSPRNG challenge generation, HMAC-only persistence, constant-time verification, 10-minute TTL boundaries, five-attempt exhaustion, 60-second resend boundary, replacement invalidation, and single use;
- caller attempts to supply/extend security policy are rejected/ignored according to the versioned contract;
- locale persistence/resend behavior from ADR-0008;
- registration/resend/confirm non-enumeration and Redis semantic-quota outage/time-safety behavior;
- accepted typed Notification responses map correctly, one logical submission creates one transport invocation, and corrupt caller escrow becomes a permanent handoff failure without provider invocation;
- equal replay/conflicting request reuse and no remote I/O inside database transactions;
- logs/metrics/traces/errors expose no recipient, code, ciphertext, `request_id`, or `notification_id`.

Dependency locking/verification metadata covers the transport.

## Rollback considerations

Runtime exposure may be disabled with `IDENTITY_REGISTRATION_RUNTIME_ENABLED=false` without schema rollback. Already committed handoffs remain durable, retain their stable `request_id`, and resume from the current lease/cutoff rules when dispatch is re-enabled.

Rollback MUST preserve contact canonicalization/verified uniqueness, logical-delete reservation, challenge HMAC-only storage, eight-digit/10-minute/five-attempt/60-second semantics, single-use/replacement invalidation, persisted locale, and stable idempotency behavior. Executed Flyway migrations are never edited or reversed.