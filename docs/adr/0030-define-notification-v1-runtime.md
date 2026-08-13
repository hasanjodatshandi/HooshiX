# ADR-0030: Define Notification v1 Provider, Persistence, and Operations

## Status

Accepted

## Date

2026-08-10

## Relationship to Earlier Decisions

This ADR closes the initial provider, receipt-ingress, persistence, worker,
retention-cleanup, observability, and legacy-cutover inputs left open by
ADR-0012 through ADR-0015 and ADR-0022. ADR-0029 defines the corresponding
handoff, semantic contract, authorization, escrow, and retention policy.

## Decision

### Email provider

Email v1 uses the Amazon SES v2 HTTPS API in `eu-central-1` (Frankfurt), not
SMTP. Production uses a dedicated AWS account or identity with only the
required send capability. The sender is `no-reply@<production-domain>` with
dedicated MAIL FROM and DKIM configuration.

The initial credential is a dedicated least-privilege IAM access key stored in
OpenBao and rotated every 90 days. Provider credentials are never placed in
Git, environment variables, logs, traces, metrics, or application payloads.

### SMS provider and geography gate

SMS v1 uses Twilio Programmable Messaging in IE1/Dublin with a dedicated
production subaccount, region-specific API key and secret stored in OpenBao,
90-day rotation, a dedicated Messaging Service, and destination Geo Permissions
limited to countries actually enabled for launch.

Twilio's current IE1 documentation does not support `+1` phone-number senders,
and Twilio stopped messaging traffic to Iran on 2025-03-15. If the United
States, Canada, or Iran is a launch geography, this provider decision must be
superseded before production. Geo Permissions cannot override an unsupported
or prohibited destination.

### Public provider webhook adapter

Provider receipt ingress terminates in a separately deployed
`notification-provider-webhook-adapter`:

```text
Internet/provider -> WAF -> webhook adapter
                  -> authenticated internal gRPC -> notification-service
```

Notification Service remains internal-only. The adapter verifies the provider
signature before parsing the business payload, bounds request size and content
type, never logs raw payload, and extracts the provider event ID. Notification
deduplicates receipt input with `UNIQUE(provider, provider_event_id)`.

When a provider's real signature scheme includes a signed timestamp, the
tolerance is five minutes. A scheme without a signed timestamp does not gain a
fabricated timestamp requirement; authenticated signature plus unique provider
event ID is the anti-replay authority. Signature secrets rotate with overlap
when the provider supports it.

### PostgreSQL ownership and schema

Notification owns database `notification` and schema `notification` on the
current shared physical PostgreSQL cluster. Credentials and schema are private
to Notification. The initial topology is single-primary; PostgreSQL HA remains
deferred until availability evidence justifies it, while the dispatch-fence
design stays HA-compatible.

Persistence uses jOOQ/JDBC and normalized relational tables, without JPA:

```text
notification.notification
notification.notification_attempt
notification.provider_receipt_evidence
notification.notification_result_outbox
notification.notification_dispatch_fence
```

Core indexes and constraints include:

- `UNIQUE(caller_service, request_id)`;
- work index `(state, next_action_at, created_at)`;
- partial provider lookup `(provider, provider_message_id) WHERE
  provider_message_id IS NOT NULL`;
- partial result-outbox index `(next_attempt_at) WHERE acked_at IS NULL`.

v1 uses no table partitioning. Retention is implemented with bounded batch
deletion. Flyway is the only schema-change mechanism, executed migrations are
immutable, and cleanup never holds an unbounded transaction.

### Provider worker and dispatch fence

Provider work uses:

| Setting | Value |
| --- | ---: |
| Claim lease | 30 seconds |
| Claim batch | 25 |
| Busy poll | 250 milliseconds |
| Idle poll | 1 second |
| Fence heartbeat cadence | 2 seconds |
| Transaction isolation | `READ COMMITTED` |
| `lock_timeout` | 100 milliseconds |
| General worker `statement_timeout` | 500 milliseconds |

The distinct 150-millisecond primary-verification statement timeout from
ADR-0023 remains unchanged. Claims use `FOR UPDATE SKIP LOCKED`. A lease is
reclaimable only before `DISPATCHING`.

After `DISPATCHING` commits, lease expiry never authorizes blind redispatch. A
stale `DISPATCHING` attempt enters reconciliation after 30 seconds. A late
result for the same immutable attempt/execution token may be applied only while
the notification is non-terminal and the transition remains legal. Evidence
arriving after a terminal state is retained only as bounded audit evidence and
does not mutate that state.

The FenceCoordinator session advisory lock is:

```sql
SELECT pg_try_advisory_lock(1313821769, 1178947139);
-- 0x4E4F5449 = NOTI, 0x46454E43 = FENC
```

ADR-0022's `FOR SHARE` dispatch authorization and `FOR UPDATE` fence-transition
serialization boundary remains mandatory.

### Observability and SLO enforcement

The v1 backend is Prometheus, Alertmanager, and Grafana. Notification exposes
at least:

```text
notification_submit_requests_total
notification_submit_duration_seconds
notification_clock_cycles_total
notification_clock_error_bound_seconds
notification_clock_signal_stale
notification_fence_generation
notification_fence_heartbeat_stale
notification_dispatch_attempts_total
notification_provider_ambiguous_total
notification_provider_receipt_lag_seconds
notification_escrow_oldest_age_seconds
notification_result_outbox_pending
notification_result_outbox_oldest_age_seconds
notification_result_callbacks_total
```

Labels never contain recipient, `request_id`, `notification_id`, code,
provider message ID, or free-form error text.

The availability SLI denominator is authenticated, syntactically valid
production requests. `INVALID_ARGUMENT`, caller `PERMISSION_DENIED`, and
`REQUEST_ID_CONFLICT` are excluded. Server and dependency failures remain in
the denominator.

The 99.9-percent SLO uses multi-window burn alerts:

| Burn | Windows | Action |
| ---: | --- | --- |
| 14.4x | 5m and 1h | Page |
| 6x | 30m and 6h | Page |
| 3x | 2h and 24h | Ticket/reliability action |

Operational thresholds are:

| Condition | Warning | Page |
| --- | ---: | ---: |
| Clock or fence stale | none | over 5s |
| Re-arm unsuccessful | none | over 30s |
| Cycle timeout ratio over 5m | over 1% | over 10% |
| Provider ambiguity | over 1%/15m | over 5%/5m |
| Oldest escrow | over 30m | over 2h |
| Oldest callback | over 15m | over 1h |

Platform owns primary on-call; semantic and authentication-flow incidents are
jointly routed to Identity. OpenTelemetry tracing uses one-percent
probabilistic sampling without PII or high-cardinality business identifiers.
Synthetic monitoring uses only organization-owned test identities or provider
sandboxes, never customer PII.

### Legacy Identity cutover gate

No deployment may assert or invent an `UNKNOWN` legacy-data condition. Before
retiring Identity-owned provider delivery, a preflight gate executes an exact
count of non-terminal rows in
`registration_verification_provider_delivery_states` using the complete legacy
non-terminal state set.

- count `0` permits the simple retirement path;
- count greater than `0` blocks deployment and requires a drain/migration ADR.

The query and state list are versioned with the cutover artifact. Legacy tables
or code are not removed without the destructive-target approval and new
forward-only Flyway migration already required by the migration plan.

## Security and Verification Requirements

Provider adapters require sanitized error mapping, ambiguity tests, credential
least-privilege verification, and provider-specific receipt-signature contract
tests. Webhook tests cover signature-before-parse, replay, payload limits,
content type, secret overlap, and absence of raw logs.

Persistence tests cover every uniqueness rule, legal transition, terminal
immutability, `SKIP LOCKED` concurrency, lease boundaries, stale dispatch
reconciliation, row-lock races, bounded cleanup, query plans, and Flyway
rollback compatibility. Chaos and load tests validate timeouts, poll cadence,
provider ambiguity, callback backlog, and the observability thresholds.

Provider validation references are the official
[Amazon SES endpoint table](https://docs.aws.amazon.com/general/latest/gr/ses.html),
[Twilio IE1 Messaging guide](https://www.twilio.com/docs/global-infrastructure/messaging-api-with-twilio-regions),
[Twilio IE1 migration limitations](https://www.twilio.com/docs/global-infrastructure/migrate-sms-us1-to-ie1),
and [Twilio destination restriction](https://www.twilio.com/docs/api/errors/21408).

## Consequences

- Provider, delivery evidence, retries, and operational telemetry have one
  service owner.
- The initial database topology stays operationally light without weakening
  service-level data ownership.
- Public provider traffic is isolated from Notification by a bounded validating
  adapter.
- North American sender and Iran delivery requirements are explicit provider
  blockers rather than implicit runtime surprises.

## Rollback or Migration Considerations

Rollback cannot redispatch a committed `DISPATCHING` attempt, mutate a terminal
state, or move provider ownership back to Identity after accepted cutover.
Before cutover, new resources may be disabled while legacy runtime remains
gated. After cutover, Notification must remain available to finish or reconcile
accepted intents. Schema rollback is application rollback over compatible
expanded schema, not reversal of executed Flyway migrations.
