# ADR-0028: Data-Subject Erasure Execution and Evidence v1

## Status

Accepted — current effective decision

## Date

2026-08-11; normalized to current-only documentation on 2026-08-13; erasure coordination contract finalized on 2026-08-13

## Decision

Logical deletion is the normal reversible lifecycle step. Approved irreversible erasure is a separate explicit workflow. Legal hold and required retention block incompatible purge/anonymization actions.

This architecture supports privacy-erasure obligations but is not legal advice or a claim of regulatory compliance.

### Ownership and workflow

Identity owns the platform-global data-subject erasure request because it owns global User identity. Each bounded context remains sole owner of how its service-owned data is erased, anonymized, legally retained, or proved absent.

A stable non-PII `erasure_request_id` coordinates an idempotent workflow:

```text
REQUESTED
-> IN_PROGRESS
-> COMPLETED
```

with explicit blocking/failure states such as:

```text
BLOCKED_BY_LEGAL_HOLD
FAILED_RETRYABLE
```

Global completion requires a durable current receipt from every required service.

### Required-service registry

The required participant set is server-owned configuration/versioned platform policy. It is never supplied by the API caller, and a caller cannot omit a required service to force premature completion.

Initial v1 required participants are:

```text
identity-service
authorization-service
notification-service
web-bff
```

A participant may be added/removed only through reviewed current architecture/data-ownership policy with migration/reconciliation rules for already-open requests.

Identity snapshots the applicable participant-policy version for an erasure request and tracks one durable receipt state per required participant. Global `COMPLETED` is impossible until all required receipts for that policy version are current and successful or an explicitly approved legal-retention outcome is represented according to policy.

### Coordination transport

Erasure coordination is asynchronous and recoverable. Identity commits request/progress state plus Transactional Outbox records locally, then publishes versioned Kafka/Protobuf coordination events after commit. Participating services process at-least-once delivery idempotently and return durable non-PII receipt/progress events through their own outbox/inbox semantics.

Synchronous fan-out gRPC is not the v1 completion mechanism because participant outage must not make the Identity database transaction or initial erasure request depend on simultaneous service availability.

Critical publication and consumer dedup evidence follows the current 35-day recovery horizon under ADR-0015. Retry/DLQ behavior is finite, observable, and retained at least 14 days where a retry/DLQ record exists. No erased PII, raw contact value, access credential, or provider token is placed in erasure coordination events or DLQs.

### Service erasure contract

Each service inventories/reconciles all applicable owned copies, including primary/logically deleted rows, derived/search/cache state, service-owned outbox/inbox payloads, attachments/blobs, and provider-side data contractually deletable by the service.

For each data category it performs one approved action:

- irreversible physical deletion;
- irreversible anonymization where non-identifying facts must be retained;
- cryptographic erasure only for independently envelope-encrypted material with a dedicated destroyable data-encryption key;
- legal-hold retention with explicit blocking evidence.

Crypto-shredding is not a blanket substitute for erasing ordinary relational PII.

### Legal hold ledger

Legal hold is explicit durable state, not a boolean request parameter. The Identity coordination ledger records at least:

```text
hold_id
erasure_request_id or data-subject scope
status: ACTIVE | RELEASED
authority/reference
actor
created_at
released_at when applicable
policy_version
append-only audit/integrity evidence
```

Only an authorized platform/legal workflow may create or release a hold. Ordinary erasure callers cannot create, remove, or bypass legal hold. `ACTIVE` hold blocks incompatible purge/anonymization while preserving the minimum legally required data/evidence. Release is audited and resumes the idempotent erasure workflow from durable state; it does not create a new unrelated erasure request merely to continue work.

### Evidence without retaining erased PII

Each service persists append-only erasure evidence containing only approved non-PII metadata: request ID, service, policy/version, completion time, action categories, legal-hold state, and integrity/audit fields. It never retains the removed value merely to prove removal.

Non-PII erasure receipts are retained for the platform lifetime or until an explicit approved legal/data-retention policy defines a shorter safe period. Receipt cleanup must never make a restored backup capable of serving previously erased data without the required replay evidence.

### Backup/restore behavior

Immutable backups are not rewritten in place; approved retention still applies. Before a restored environment can serve traffic, the platform replays durable erasure/legal-hold evidence so older restored personal data is re-erased/anonymized before exposure. Expired backup artifacts are destroyed according to retention policy.

Restore reconciliation uses the same server-owned participant policy/receipt evidence. Traffic remains disabled until required erasure/legal-hold replay and participant reconciliation complete successfully.

### Identifier behavior

Generated technical/security/event/audit identifiers are never reused. Human-facing identifiers remain reserved while restoration is supported and are released only through an explicit irreversible policy. Restoration can never steal an identifier already assigned to a newer owner.

### Logical deletion/retention baseline

Current platform default retention is 360 days unless a reviewed data-class policy defines another period. Expiry creates eligibility for purge/anonymization; it does not automatically authorize destruction. Physical purge is unavailable through ordinary business APIs/repositories, is idempotent/observable/audited/tenant-safe, and is blocked by active legal hold.

## Verification requirements

Tests/evidence cover:

- end-to-end erasure across every server-registered owning service;
- caller inability to omit/forge required participants or participant-policy version;
- Transactional Outbox/Kafka at-least-once replay, duplicate receipt handling, partial failure, retry/DLQ behavior, and 35-day critical dedup evidence;
- idempotent replay after partial failure;
- legal-hold authorization, ACTIVE blocking, RELEASED continuation, actor/authority/audit evidence;
- no retained PII in receipts/events/DLQs;
- cache/search/outbox/inbox/blob/provider cleanup where applicable;
- restore from pre-erasure backup followed by mandatory re-erasure/reconciliation before traffic;
- cryptographic-erasure tests only for data classes with independent destroyable keys;
- identifier non-reuse/release conflict tests;
- audit/alerting for stuck/failed requests;
- proof that ordinary APIs cannot perform irreversible purge.

## Rollback considerations

Rollback MUST NOT resurrect erased data into serving state, reuse protected identifiers, drop required-service receipts, accept caller-selected participant sets, replace durable async coordination with availability-coupled synchronous completion, drop legal-hold checks/ledger evidence, convert irreversible erasure into reversible logical deletion, or retain raw PII as evidence.

Restored systems must replay erasure/legal-hold evidence before traffic regardless of application rollback version.