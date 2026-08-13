# ADR-0058: Data-Subject Erasure Execution and Evidence v1

## Status

Accepted — current effective decision

## Date

2026-08-11; normalized to current-only documentation on 2026-08-13

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

### Service erasure contract

Each service inventories/reconciles all applicable owned copies, including primary/logically deleted rows, derived/search/cache state, service-owned outbox/inbox payloads, attachments/blobs, and provider-side data contractually deletable by the service.

For each data category it performs one approved action:

- irreversible physical deletion;
- irreversible anonymization where non-identifying facts must be retained;
- cryptographic erasure only for independently envelope-encrypted material with a dedicated destroyable data-encryption key;
- legal-hold retention with explicit blocking evidence.

Crypto-shredding is not a blanket substitute for erasing ordinary relational PII.

### Evidence without retaining erased PII

Each service persists append-only erasure evidence containing only approved non-PII metadata: request ID, service, policy/version, completion time, action categories, legal-hold state, and integrity/audit fields. It never retains the removed value merely to prove removal.

### Backup/restore behavior

Immutable backups are not rewritten in place; approved retention still applies. Before a restored environment can serve traffic, the platform replays durable erasure/legal-hold evidence so older restored personal data is re-erased/anonymized before exposure. Expired backup artifacts are destroyed according to retention policy.

### Identifier behavior

Generated technical/security/event/audit identifiers are never reused. Human-facing identifiers remain reserved while restoration is supported and are released only through an explicit irreversible policy. Restoration can never steal an identifier already assigned to a newer owner.

### Logical deletion/retention baseline

Current platform default retention is 360 days unless a reviewed data-class policy defines another period. Expiry creates eligibility for purge/anonymization; it does not automatically authorize destruction. Physical purge is unavailable through ordinary business APIs/repositories, is idempotent/observable/audited/tenant-safe, and is blocked by active legal hold.

## Verification requirements

- end-to-end erasure across every registered owning service;
- idempotent replay after partial failure;
- legal-hold blocking and later release;
- no retained PII in receipts;
- cache/search/outbox/inbox/blob/provider cleanup where applicable;
- restore from pre-erasure backup followed by mandatory re-erasure before traffic;
- cryptographic-erasure tests only for data classes with independent destroyable keys;
- identifier non-reuse/release conflict tests;
- audit/alerting for stuck/failed requests;
- proof that ordinary APIs cannot perform irreversible purge.

## Rollback considerations

Rollback MUST NOT resurrect erased data into serving state, reuse protected identifiers, drop legal-hold checks, convert irreversible erasure into reversible logical deletion, or retain raw PII as evidence. Restored systems must replay erasure evidence before traffic regardless of application rollback version.
