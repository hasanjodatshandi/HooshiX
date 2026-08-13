# ADR-0029: Finalize the Notification v1 Handoff and Semantic Contract

## Status

Accepted

## Date

2026-08-10

## Relationship to Earlier Decisions

This ADR closes the remaining request-conflict, RPC, caller-dispatch, callback,
authorization, semantic-template, recipient, escrow, and retention decisions
left open by ADR-0010 and ADR-0012 through ADR-0017. It does not change the
canonical lifecycle, provider-outcome taxonomy, retry schedule, immutable
deadlines, or terminal-state rules in ADR-0013 through ADR-0018.

For Identity handoff escrow, this ADR selects the existing local AES-256-GCM
key-ring boundary instead of adding an OpenBao Transit round trip. Notification
continues to use the independent Transit key approved by ADR-0012.

## Decision

### Request identity and conflict detection

`request_id` is stable for one caller-owned delivery intent. Notification
stores a full 32-byte HMAC-SHA-256 intent fingerprint with
`fingerprint_version`, `fingerprint_key_id`, and `fingerprint`.

The fingerprint key is a dedicated random 256-bit key materialized from
OpenBao and used locally. OpenBao Transit HMAC and decrypt-and-compare are
prohibited on this path. Keys rotate every 90 days; an old key remains
available for at least the 35-day dedup retention plus seven days.

The `fingerprint-v1` input excludes `request_id` and includes:

- authenticated caller identity;
- channel;
- semantic template type;
- canonical recipient;
- canonical locale;
- `message_not_after` at canonical UTC microsecond precision when present;
- every typed semantic parameter.

Encoding is versioned, domain-separated, and length-prefixed binary. Protobuf
deterministic serialization is not the canonical fingerprint format.
Fingerprints are compared in constant time.

After lookup by `UNIQUE(caller_service, request_id)`:

- an equal fingerprint returns the original acceptance or stored result;
- a different fingerprint returns gRPC `ALREADY_EXISTS` with stable code
  `REQUEST_ID_CONFLICT`;
- conflict never returns `FAILED_PRECONDITION` and never decrypts escrow.

The duplicate lookup and fingerprint comparison occur before current-time,
clock-health, or expiry validation.

### `SubmitNotification` RPC

Each invocation has:

| Setting | Value |
| --- | ---: |
| Overall deadline | 900 milliseconds |
| Attempts | 1 |
| Wait-for-ready | disabled |
| gRPC automatic retry | disabled |

The caller outbox may later submit the same `request_id` after
`UNAVAILABLE`, `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`, or `ABORTED`.
`INVALID_ARGUMENT`, `REQUEST_ID_CONFLICT`, `PERMISSION_DENIED`, and
`UNAUTHENTICATED` are non-retryable. `INTERNAL` is not automatically retried.

Cancellation before the durable acceptance commit aborts work when possible.
Cancellation or deadline expiry after commit cannot undo `ACCEPTED`; the caller
replays the same `request_id` to recover the committed outcome.

Identity's dispatcher claims with `FOR UPDATE SKIP LOCKED` and uses:

| Setting | Value |
| --- | ---: |
| Claim lease | 30 seconds |
| Batch size | 32 |
| Poll while backlog exists | 250 milliseconds |
| Idle poll | 1 second |
| Retry delays | 1s, 2s, 5s, 10s, then 30s maximum |
| Jitter | plus or minus 20 percent |

A time-bound handoff starts no new RPC when PostgreSQL-authoritative time is at
or after `message_not_after - 5s`, and stops immediately when the next
scheduled attempt would fall after that cutoff. A non-time-bound handoff retries
for at most 30 minutes, then becomes `HANDOFF_FAILED` and alerts; automatic
retry is never unbounded.

### Result callback and routing

`ReportNotificationResult` has a 750-millisecond deadline, one attempt,
wait-for-ready disabled, and no automatic gRPC retry. The durable result-outbox
dispatcher uses plus or minus 20 percent jitter and this schedule:

```text
1s -> 5s -> 30s -> 2m -> 10m -> 30m maximum
maximum automatic retry age = 7 days
```

`UNAVAILABLE`, `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`, `ABORTED`, and
`INTERNAL` are retryable by the durable dispatcher. `UNAUTHENTICATED`,
`PERMISSION_DENIED`, and malformed `INVALID_ARGUMENT` are non-retryable and
alertable. A duplicate callback returns `OK` after proving that its prior local
effect is already committed; it does not return `ALREADY_EXISTS`.

An allow-listed callback registry is structured GitOps configuration. A caller
cannot supply a callback URL, URI, host, IP, scheme, redirect, port, or method.
The v1 registry entry is:

```text
IDENTITY_SERVICE -> identity-service.platform-apps:9091
method           -> ReportNotificationResult
```

Notification constructs the internal Kubernetes DNS name. A caller marked
`PERMANENTLY_RETIRED` stops retry immediately and moves the result to
`CALLBACK_SUPPRESSED_CALLER_RETIRED`. Lack of ACK after seven days becomes
`CALLBACK_EXHAUSTED`, triggers an alert, and retains only approved metadata.

### Workload identity and authorization

Notification and Identity run in namespace `platform-apps` with distinct
ServiceAccounts named `notification-service` and `identity-service`.

- `notification-service:9090` accepts `SubmitNotification` only from the
  `identity-service` principal;
- `identity-service:9091` accepts `ReportNotificationResult` only from the
  `notification-service` principal;
- both paths use Istio Ambient strict mTLS and L4 authorization;
- v1 adds no JWT, bearer token, API key, application TLS, or waypoint.

Identity is the only v1 caller and may submit exactly these semantic/channel
combinations:

| Semantic type | Channels |
| --- | --- |
| `REGISTRATION_VERIFICATION_CODE` | Email, SMS |
| `PASSWORD_RECOVERY_CODE` | Email, SMS |
| `MFA_VERIFICATION_CODE` | Email, SMS |
| `PASSWORD_CHANGED_NOTICE` | Email |

Before a second caller is enabled, secure caller-to-semantic permission
binding must be reviewed. Multiple callers must not share this L4-only port
without an approved L7 or application-identity design.

### Typed semantic contract, locale, and templates

The public v1 channel enum is `CHANNEL_UNSPECIFIED = 0`, `EMAIL = 1`, and
`SMS = 2`. Semantic content is a Protobuf `oneof` with initial messages
`RegistrationVerification`, `PasswordRecovery`, `MfaVerification`, and
`PasswordChanged`. An arbitrary parameter map, subject, HTML/text body, brand
name, or arbitrary URL is prohibited.

The three verification messages expose only their explicit `code` parameter.
It is ASCII, at most 16 bytes, and validated against the exact flow-specific
format. `PasswordChanged` exposes only `occurred_at` and any other fields later
added explicitly to its versioned message.

Initial canonical locales are `en` and `fa`. `en-US` canonicalizes to `en` and
`fa-IR` to `fa`. An unknown primary language returns
`INVALID_ARGUMENT / UNSUPPORTED_LOCALE`; it never silently becomes English.
Template lookup is exact locale, then language locale, then `en`, while CI must
catch any required missing translation before release.

Time-bound classification is immutable template metadata, not a caller flag:

- `RegistrationVerification`, `PasswordRecovery`, and `MfaVerification` are
  time-bound;
- `PasswordChanged` is non-time-bound.

Templates are immutable Git-bundled application resources. There is no v1
PostgreSQL template store, admin workflow, runtime editing, or hot activation.
Pebble renders in strict mode. HTML auto-escaping is mandatory; text and SMS
use dedicated plaintext templates. Missing or extra variables fail rendering.
The version is `<semantic>/<locale>/vN@sha256-<digest>`. Promotion is Git PR,
tests, image build, and GitOps deployment; rollback deploys the previous image
and template version.

### Recipient canonicalization

Email v1 accepts one mailbox only, with no display name, comment, or quoted
input. SMTPUTF8 is unsupported. The local part is ASCII, at most 64 octets,
and case-preserving. The IDN domain is canonicalized using UTS-46/IDNA to a
lowercase A-label; invalid labels are rejected. The complete canonical mailbox
is at most 254 bytes.

SMS input must already be E.164 with at most 15 digits after `+`. National
number inference from locale is prohibited. libphonenumber performs possible
and valid-number validation.

The recipient is canonicalized once. Its exact canonical provider destination
is encrypted in escrow and reused unchanged on every retry.

### Identity handoff escrow

Identity retains its existing independent AES-256-GCM delivery-escrow key ring
for this hop. Each encryption uses a random 96-bit nonce. Ciphertext contains
the exact canonical recipient plus the code or other typed sensitive
parameters. AAD binds `request_id`, channel, semantic template,
`message_not_after`, and key ID.

Keys rotate every 90 days with active and previous keys. An old key remains
until every dependent ciphertext is deleted plus seven days. Handoff escrow is
deleted immediately after durable `ACCEPTED`, has an absolute 24-hour maximum,
and for time-bound intents is deleted earlier at expiry or cutoff.

### Notification Transit policy

OpenBao Transit uses key `notification-delivery-escrow` with a 200-millisecond
connect timeout, 400-millisecond request timeout, and no immediate retry.
Before acceptance, any Transit failure returns `UNAVAILABLE` and commits no
Notification.

After acceptance, a transient decrypt failure does not consume a provider
attempt and cannot transition the attempt to `DISPATCHING`. Internal processing
retries after 1, 5, 15, and then at most 30 seconds without extending the
persisted delivery deadline. Definitively corrupt or undecryptable ciphertext
causes a critical alert and quarantine; provider I/O is prohibited. With proof
of no provider acceptance, the notification becomes `EXPIRED` when its
deadline arrives.

Notification authenticates through Kubernetes Auth as `notification-service`.
Its token TTL is approximately 15 minutes and is renewed before expiry. Transit
keys rotate every 90 days, ciphertext retains its key version, and old decrypt
versions remain available until no dependent ciphertext remains plus seven
days. Sensitive ciphertext has a 24-hour hard maximum retention.

### Data retention

| Data | Retention |
| --- | ---: |
| `request_id`, dedup record, and fingerprint | 35 days |
| Non-PII notification metadata | 90 days |
| Provider attempts and provider message IDs | 30 days |
| Authenticated receipt evidence | 30 days |
| Result outbox after ACK | 7 days |
| Unacked or exhausted callback metadata | 90 days |
| Security and audit records | 365 days |
| Sensitive escrow ciphertext | 24 hours hard maximum |

Raw recipient, code, and rendered content have no long-term retention.

## Security and Verification Requirements

Implementation requires tests for fingerprint encoding/versioning, key
rotation, constant-time comparison use, identical replay, conflict rejection,
deadline and cancellation after commit, dispatcher cutoffs, callback
idempotency, registry SSRF rejection, positive and negative Istio policy,
semantic permissions, locale behavior, strict rendering, content limits,
recipient canonicalization, Transit outage/corruption, escrow erasure, and every
retention bound.

Logs, traces, metrics, errors, and outboxes must not contain recipient data,
codes, rendered content, ciphertext, arbitrary parameters, provider payloads,
`request_id`, or `notification_id` except where a separately approved bounded
audit representation explicitly requires it.

The canonical fingerprint rule follows Protocol Buffers' explicit warning that
[serialization is not canonical](https://protobuf.dev/programming-guides/serialization-not-canonical/).

## Consequences

- Durable retries recover unknown RPC outcomes without duplicate intent.
- Conflict detection does not expose plaintext or add Transit latency.
- The callback target is not caller-controlled and therefore does not create
  an SSRF destination surface.
- Identity remains the only permitted v1 caller under an L4-only policy.
- Exact content is stable across provider retries, while sensitive escrow has a
  bounded lifecycle.

## Rollback or Migration Considerations

Contracts and schema are introduced additively. Mixed versions must preserve
fingerprint fields, stable identifiers, original accepted outcomes, ciphertext
versions, and callback idempotency. Rollback cannot recreate caller escrow
after `ACCEPTED`, change a resolved template, extend a deadline, or relax the
allow-listed callback route.
