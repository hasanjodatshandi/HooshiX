# ADR-0020: IPPanel Webservice SMS for Iran Production

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

## Decision

Iran production SMS uses IPPanel Edge API **Webservice** sending mode. Notification remains the only message-template/rendering authority; provider-managed Pattern rendering is prohibited for the production semantic contract.

### Request model

- target geography: Iran (`+98`);
- one recipient per Notification provider request by platform policy;
- recipient is already canonical E.164;
- exact SMS text is rendered from the active versioned Notification PostgreSQL template before durable acceptance;
- provider receives that exact rendered text;
- retries/reconciliation preserve the exact accepted content;
- the provider adapter remains behind the provider-neutral Application outbound port.

### Authentication and configuration

- dedicated production API token is secret-managed through OpenBao/External Secrets;
- normal credential rotation is every 90 days, with immediate emergency rotation after suspected compromise;
- sender configuration is typed deployment configuration and cannot be supplied by callers;
- provider endpoint/token never appears in Git, logs, traces, metrics, event payloads, or public/internal error bodies;
- egress NetworkPolicy permits only the reviewed provider destination/path.

### Timeouts and submission outcome

```text
connect timeout:          500 ms
total provider timeout:   1500 ms
automatic HTTP retry:     none
```

Provider-attempt budgets and Notification delivery deadlines remain authoritative.

Timeout, connection loss, malformed response, or any response for which provider acceptance cannot be proven is `AMBIGUOUS`. Ambiguous submission is never blindly resubmitted.

`DEFINITIVE_ACCEPTED` requires the pinned sandbox-tested provider contract to return a successful response with one non-empty provider correlation identifier for the immutable attempt. Exact provider JSON is isolated in the adapter and pinned by fixtures rather than leaked into Domain/Application semantics.

### Delivery evidence

Authenticated recipient-level report polling maps the pinned provider status contract:

```text
2 -> DELIVERED evidence
3 -> definitive non-delivery / FAILED_PERMANENT mapping
4 -> blacklisted/non-deliverable / FAILED_PERMANENT mapping
0/1 -> non-terminal observation
```

Bulk/outbox-level `sent` state is not delivery evidence.

Missing, unknown, conflicting, or inconclusive evidence remains under the 12-hour SMS observation window and resolves to `DELIVERY_STATUS_UNKNOWN` when no conclusive terminal evidence exists.

Polling is bounded, starts only after provider acceptance, never submits a message, and never resets provider-attempt budgets. Representative cadence:

```text
15s -> 30s -> 60s -> 2m -> 5m -> <=15m interval
```

Provider-wide polling concurrency/QPS is bounded with backpressure; an unbounded poll storm is prohibited.

### MFA readiness

SMS MFA is production-eligible only when current semantic-quota enforcement, provider contract/credentials, Notification encrypted exact-content lifecycle, provider ambiguity/delivery tests, and Identity MFA controls are verified. Provider unavailability does not activate a local logging adapter or another unreviewed production fallback.

`LoggingSmsProviderAdapter` is local-development-only under `local & !staging & !production`.

## Verification requirements

Test the pinned accepted-response and report-status fixtures, no blind retry after ambiguity, exact-content reuse, one canonical +98 recipient, token redaction/revocation, bounded response parsing, status-2-only delivery mapping, bulk-sent rejection as delivery proof, polling backpressure, PII-safe telemetry, and proof that the local logging adapter cannot activate in staging/production.

## Rollback considerations

Rollback must preserve accepted attempt identity, exact content, ambiguity/reconciliation, observation, and terminal-state invariants. It cannot silently switch to provider-managed Pattern rendering or activate the logging adapter in production. A provider replacement requires a new current reviewed decision.
