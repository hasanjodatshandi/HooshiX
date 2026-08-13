# ADR-0033: Defer SMS Provider Selection and Use a Local Logging Adapter

## Status

Accepted

## Date

2026-08-10

## Supersedes

This ADR supersedes every SMS-provider product selection in ADR-0030 and
ADR-0032. In particular, Twilio and IPPanel are not selected for the current
baseline. ADR-0032's `com.sajtech.notification` package decision remains
accepted, as do all provider-neutral lifecycle, retry, ambiguity, escrow,
delivery-evidence, and terminal-state rules in ADR-0012 through ADR-0015.

ADR-0030's Email provider and its persistence, worker, fence, observability,
and legacy-cutover decisions remain unchanged.

## Context

The production SMS provider must be selected after evaluating Iranian
providers. Committing the architecture to IPPanel, Kavenegar, Twilio, a Pattern
API, a webhook, or a polling contract before that evaluation would create a
false production baseline.

Development still needs a concrete outbound adapter so the Notification
package boundary, dependency direction, composition, and safe telemetry can be
implemented and tested without external provider I/O or credentials.

## Decision

### Production provider remains deferred

No production SMS provider, provider API, authentication mechanism, sender,
template mechanism, provider identifier, idempotency behavior, receipt ingress,
webhook, polling API, delivery-status mapping, timeout, or credential-rotation
policy is selected in the current baseline.

IPPanel, Kavenegar, and every other Iranian provider remain evaluation
candidates only. No provider SDK, endpoint, credential, egress rule, webhook,
Report API, reconciliation job, or provider-specific persistence is introduced
until a later accepted ADR selects the provider and its verified contract.

### Local logging adapter

`notification-service` contains a `LoggingSmsProviderAdapter` under
`com.sajtech.notification.infrastructure.delivery.provider.logging`. It
implements the provider-neutral Application outbound port and is enabled only
under the Spring profile expression `local & !staging & !production`. Tests may
instantiate it directly; staging and production must never activate it, even if
`local` is accidentally enabled alongside either profile.

The adapter performs no network I/O, reads no credential, creates no provider
message identifier, and performs no webhook or reconciliation work. Its result
is the explicit non-production value `SIMULATED`. `SIMULATED` is not a
canonical provider-attempt outcome or Notification lifecycle state and must
never be mapped to `DEFINITIVE_ACCEPTED`, `PROVIDER_ACCEPTED`, `DELIVERED`, or a
terminal result callback.

The adapter emits one structured informational event with only these bounded
fields:

```text
eventCode = notification.sms.simulated_submission
channel = SMS
adapter = logging
semanticType = approved enum
locale = approved enum
simulation = true
```

It must not log or expose the recipient, OTP/code, rendered message, template
parameters, ciphertext, `request_id`, `notification_id`, provider identifiers,
arbitrary object `toString()` output, or raw exception/input values. The
request type has a redacted `toString()` as defense in depth.

### Runtime and readiness gate

The logging adapter is a developer aid, not a fallback. It must not be used
when a real provider is unavailable and cannot make an SMS-capable production
deployment Ready. Production SMS dispatch remains disabled until a later ADR
selects a real provider and the required adapter, secrets, delivery evidence,
timeouts, reconciliation, network policy, and contract tests are implemented.

Provider-neutral rendering and encrypted exact-content escrow remain valid.
The Pattern-rendering blocker recorded by ADR-0032 is removed because Pattern
SMS is no longer selected; a future Pattern-provider decision must resolve that
invariant if it chooses provider-managed rendering.

## Security and Verification Requirements

Implementation requires:

- unit tests proving that recipient, OTP, rendered content, control characters,
  and canary secrets never appear in the emitted log;
- a redacted request `toString()` test;
- a test proving the result is `SIMULATED` and cannot claim provider acceptance
  or delivery;
- a Spring composition test proving the adapter exists under `local` and is
  absent under `staging` and `production`;
- ArchUnit tests for Domain/Application dependency direction and provider code
  placement; and
- repository enforcement that no production SMS provider or provider-specific
  dependency is present in the active baseline.

## Consequences

- Iranian SMS-provider evaluation can proceed without product lock-in.
- Local development has a concrete, safe adapter without provider credentials
  or network access.
- Local runs can prove that an SMS dispatch request reached the outbound port,
  but cannot claim that a human received a message.
- Production SMS remains intentionally unavailable until an explicit provider
  decision and implementation are complete.

## Rollback or Migration Considerations

No provider data or credential migration exists because no real provider is
active. The logging adapter can be removed after a real adapter is implemented
and verified, but accepted ADR history remains unchanged.

A future provider ADR must supersede this deferral, keep the provider-neutral
port compatible or provide a migration, and must not reinterpret historical
`SIMULATED` results as provider acceptance or delivery.
