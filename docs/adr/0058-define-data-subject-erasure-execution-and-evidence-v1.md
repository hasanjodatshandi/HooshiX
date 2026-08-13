# ADR-0058: Define Data-Subject Erasure Execution and Evidence v1

## Status

Accepted

## Date

2026-08-11

## Relationship to Earlier Decisions

This ADR operationalizes ADR-0003. Logical deletion remains the normal first
lifecycle step; it is not the final state of an approved irreversible erasure.
Legal holds and legally required retention continue to block incompatible
purge/anonymization actions.

This architecture supports privacy-erasure obligations but does not by itself
constitute legal advice or a claim of regulatory compliance.

## Decision

### Ownership and workflow

Identity owns the platform-global data-subject erasure request because it owns
the global User identity. Each bounded context remains the sole owner of
how its service-owned data is erased, anonymized, retained under legal hold, or
proved absent.

A stable non-PII `erasure_request_id` coordinates an idempotent workflow:

```text
REQUESTED
-> IN_PROGRESS
-> COMPLETED
```

or an explicit blocking/failure state such as:

```text
BLOCKED_BY_LEGAL_HOLD
FAILED_RETRYABLE
```

No service may mark the global request complete until every required service
returns a durable receipt.

### Service erasure contract

A service handler must inventory and reconcile all applicable copies it owns,
including primary rows, logically deleted rows, derived/search/cache state,
service-owned outbox/inbox payloads, attachments/blobs, and provider-side data
that the service is contractually able to delete.

For each category it performs one approved action:

- irreversible physical deletion;
- irreversible anonymization where retention of non-identifying facts is
  required;
- cryptographic erasure for independently envelope-encrypted material when a
  dedicated destroyable data-encryption key exists;
- legal-hold retention with explicit blocking evidence.

Crypto-shredding is not used as a blanket substitute for erasing ordinary
unencrypted relational data.

### Evidence without retaining PII

Each service stores an append-only erasure receipt containing only approved
non-PII metadata: request ID, service, policy/version, completion time, action
categories, legal-hold state, and integrity/audit fields. It does not retain the
removed value merely to prove it was removed.

### Backup/restore behavior

Existing immutable backups are not rewritten in place. Their approved
retention still applies. Before a restored environment can serve traffic, the
platform replays the durable erasure/legal-hold ledger so data restored from an
older backup is re-erased/anonymized before exposure. Expired backup artifacts
are destroyed by retention policy.

### Identifier behavior

ADR-0003 remains authoritative: immutable technical/security IDs are not
reused. Human-facing identifiers are not released while restoration is
possible and are released only through the explicit irreversible policy.

## Verification Requirements

- end-to-end erasure across every registered owning service;
- idempotent replay after partial failure;
- legal-hold blocking and later release;
- no retained PII in erasure receipts;
- cache/search/outbox/blob cleanup where applicable;
- restore from a pre-erasure backup followed by mandatory re-erasure before
  traffic;
- cryptographic-erasure tests only for data classes that actually use
  independent destroyable keys;
- audit and alerting for stuck/failed erasure requests.

## Consequences

Logical deletion remains reversible operational state while an approved erasure
has a concrete irreversible workflow and proof model. Backup recovery no longer
risks silently resurrecting previously erased data into a serving environment.
