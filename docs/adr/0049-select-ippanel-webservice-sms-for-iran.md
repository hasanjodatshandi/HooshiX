# ADR-0049: Select IPPanel Webservice SMS for Iran Production

## Status

Accepted

## Date

2026-08-10

## Supersedes

This ADR supersedes ADR-0033's production SMS-provider deferral and the provider/Pattern portions of ADR-0032. ADR-0032's `com.sajtech.notification` package decision remains accepted.

Provider-neutral lifecycle, retry, ambiguity, exact-content escrow, delivery-evidence, terminal-state, and caller contract rules remain unchanged.

## Context

Production SMS is required for the Iran launch and SMS MFA. The previous IPPanel Pattern option conflicted with Notification's invariant that an exact versioned provider-ready message is rendered before durable acceptance and replayed unchanged on retry. Provider-managed mutable patterns would create a second presentation authority.

IPPanel's Webservice sending mode accepts the exact rendered message text, allowing Notification's PostgreSQL template/version model to remain authoritative.

## Decision

### Provider and sending mode

Iran production SMS uses IPPanel Edge API with the provider's Webservice sending mode, not Pattern rendering.

- target geography: Iran (`+98`);
- one recipient per Notification provider request by platform policy;
- recipient already canonical E.164;
- exact SMS text is rendered by Notification from its versioned PostgreSQL template before durable acceptance;
- provider receives that exact rendered text;
- no provider-managed template/pattern is part of the production semantic contract.

The adapter remains behind the provider-neutral Application outbound port.

### Authentication and configuration

- dedicated production API token stored in OpenBao;
- credential rotation every 90 days or immediately after suspected compromise;
- sender number/configuration is a typed deployment setting and not supplied by callers;
- provider endpoint/token never appears in Git, logs, traces, metrics, event payloads, or error bodies;
- egress NetworkPolicy allows only required provider destinations through the approved egress path.

### Timeouts and retries

Provider HTTP behavior uses:

- connect timeout: 500ms;
- total provider request timeout: 1500ms;
- no automatic HTTP-client retry.

Notification's existing provider attempt budget/deadline policy remains authoritative.

A timeout, connection loss, malformed response, or response for which acceptance cannot be proven is `AMBIGUOUS`. An ambiguous submission is never blindly re-submitted.

`DEFINITIVE_ACCEPTED` requires the pinned provider contract to return a successful response with one non-empty provider outbox/message identifier correlated to this attempt. The exact JSON field/type is pinned by a sandbox contract fixture before production enablement and remains isolated in the IPPanel adapter.

### Delivery evidence

IPPanel receipt status is obtained by authenticated provider report polling. No public SMS webhook is required in v1.

The adapter maps the pinned recipient-level status contract as:

- provider status `2`: `DELIVERED` evidence;
- provider status `3`: definitive not-delivered -> `FAILED_PERMANENT` according to canonical mapping;
- provider status `4`: blacklisted/non-deliverable -> `FAILED_PERMANENT` according to canonical mapping;
- provider status `0` or `1`: non-terminal observation only.

A bulk/outbox-level "sent" state is not sufficient evidence for `DELIVERED`.

Missing, unknown, conflicting, or inconclusive evidence resolves under the existing SMS 12-hour observation window and final reconciliation to `DELIVERY_STATUS_UNKNOWN` when no conclusive outcome exists.

### Polling schedule

After `PROVIDER_ACCEPTED`, polling uses a bounded schedule approximately:

```text
15s -> 30s -> 60s -> 2m -> 5m -> 15m maximum interval
```

Polling never submits a message and never resets provider attempt budgets. Provider-wide concurrency/QPS limits are explicit configuration with backpressure; Notification must not create an unbounded poll storm.

### SMS MFA gate

SMS MFA becomes eligible for production only after all of these are verified:

- ADR-0041 semantic quota enforcement;
- this provider adapter contract fixture and production credentials;
- provider delivery/ambiguity tests;
- Notification readiness and encrypted exact-content lifecycle;
- Identity MFA security requirements from ADR-0038.

SMS remains unavailable when provider readiness fails; the local logging adapter is never a production fallback.

## Security and Verification Requirements

- provider sandbox/contract fixtures pin accepted-response field and report-status semantics;
- no blind retry after ambiguous submission;
- exact accepted content reused across attempts;
- one canonical +98 recipient per request;
- token redaction and emergency revocation;
- bounded response parsing and payload sizes;
- status `2` is required for delivered mapping;
- bulk sent state cannot map to delivered;
- polling concurrency/backoff tests;
- no recipient/code/message/provider payload in telemetry;
- production profile cannot activate `LoggingSmsProviderAdapter`.

## Consequences

- Production SMS is no longer blocked by provider selection.
- Notification remains the sole template/rendering authority; provider-side mutable patterns are avoided.
- Polling adds provider QPS that must be bounded, but avoids a new public webhook surface.
- Provider ambiguity remains explicit and fail-safe.

## Rollback or Migration Considerations

Rollback must preserve accepted IPPanel attempt identity, ambiguity, observation, and terminal-state invariants. It cannot reactivate the logging adapter in production or silently switch to Pattern rendering. Provider replacement requires another ADR.
