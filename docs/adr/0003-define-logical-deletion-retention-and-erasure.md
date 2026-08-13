# ADR 0003: Define Logical Deletion, Retention, Erasure, and Legal Holds

## Status

Accepted

## Date

2026-08-05

## Context

The platform requires logical deletion as the default behavior across bounded
contexts. Physical deletion is allowed only when a documented legal, security,
regulatory, or business requirement justifies it.

ADR-0002 defines logical deletion as the default for tenants but intentionally
defers the platform-wide rules for retention, restoration, erasure,
anonymization, legal holds, uniqueness, cascading behavior, and physical purge.

Without a common policy, services may implement incompatible deletion
semantics, accidentally expose deleted data, reuse protected identifiers too
early, or make restoration impossible.

This ADR defines the platform default. A bounded context may deviate only
through an accepted ADR that documents the requirement and safeguards.

## Decision

### Logical deletion metadata

A logically deletable record must contain deletion metadata equivalent to:

```text
deleted_at
deleted_by
deletion_reason
```

`deleted_at` is the authoritative indicator that the record is logically
deleted. A standalone Boolean such as `is_deleted` must not be the only source
of deletion state.

`deleted_by` identifies the authenticated actor, system workflow, or service
responsible for the deletion.

`deletion_reason` must contain a stable reason code. Optional human-readable
details may be stored separately when required.

Deletion and restoration actions must also emit immutable audit records. The
mutable row metadata does not replace the audit trail.

### Default query behavior

Normal application queries must exclude logically deleted records.

Access to deleted records requires an explicit use case and an explicit
persistence operation such as:

```text
findIncludingDeleted
searchDeleted
restoreDeleted
```

The exclusion rule must be enforced by repository and persistence-adapter
boundaries. It must not depend only on every caller remembering to add
`deleted_at IS NULL`.

Administrative access to deleted records must be authorized and audited.

### Stable identifiers and uniqueness

Generated technical identifiers, security identifiers, event identifiers, and
audit identifiers must never be reused.

Uniqueness rules for human-facing identifiers must distinguish recoverable
logical deletion from irreversible release.

A global user email remains reserved while the user account is recoverable.
It may become reusable only after an explicit irreversible erasure or release
workflow permits reuse. A later account using that email receives a different
immutable User identifier.

A tenant slug remains reserved while the tenant is recoverable unless an
explicit release workflow makes the slug reusable.

When a released identifier has been reused, restoration of the old record must
not take the identifier from the current owner. Restoration must either:

- require assignment of a new available identifier
- remain blocked until the conflict is resolved

Partial unique indexes may enforce uniqueness among active and reserved
records. Domain-specific reuse rules must be explicit.

### Restoration

Logical deletion has no automatic restoration expiry.

A logically deleted record remains restorable while its required data still
exists and has not been irreversibly purged or anonymized.

Restoration after physical purge or irreversible anonymization is impossible
and must not be represented as a supported operation.

Every restore operation must:

- be explicitly authorized
- be audited
- validate uniqueness constraints again
- validate required parent and dependency state
- preserve the original immutable identifier
- resolve released or reused human-facing identifiers safely

Restoration must not automatically cascade across an aggregate unless the
bounded context defines and tests that behavior explicitly.

### Retention policy

Retention periods are defined per data class.

The platform default retention period is:

```text
360 days
```

A data class may require a longer, shorter, or indefinite retention period when
an accepted legal, regulatory, security, privacy, or business policy documents
the reason.

Reaching the end of a retention period must not automatically delete data.

After the applicable retention period, data may become eligible for an
explicit purge or anonymization decision. Eligibility does not require that the
operation occur.

Retention configuration and changes must be versioned, authorized, and
audited.

### Physical purge

Physical deletion is prohibited in normal business APIs and ordinary
repository operations.

Physical deletion may occur only through an explicit purge workflow that is:

- authorized
- idempotent
- auditable
- observable
- protected against cross-tenant access
- bound to an approved data-class policy
- blocked by an active legal hold
- safe to retry after partial failure

A purge workflow must verify the applicable retention, legal, regulatory,
security, privacy, and business requirements before deleting data.

The end of the default 360-day period alone is not sufficient authorization to
purge data.

A subsystem requiring different physical deletion behavior must record the
exception in an accepted ADR.

### Legal holds

A legal hold blocks:

- physical purge
- irreversible anonymization
- release of reserved identifiers
- destruction of required evidence

The minimum legal-hold lifecycle is:

```text
ACTIVE
RELEASED
```

A legal hold must record:

- scope
- reason
- external or internal reference
- issuing authority
- creation time
- release time when released
- actors responsible for creation and release

Legal-hold operations must be strictly authorized and audited.

### Anonymization and erasure

Logical deletion and anonymization are separate operations.

An erasure requirement may be satisfied by irreversibly removing or
anonymizing personal data while preserving records required for accounting,
fraud prevention, referential integrity, security, or audit.

Anonymization must be designed to prevent practical re-identification. A
reversible transformation or an unsalted predictable hash is not sufficient
when the original value can realistically be recovered.

Irreversible anonymization ends restoration of the anonymized personal data.

An active legal hold blocks anonymization unless the hold itself explicitly
allows a documented transformation.

### Cascading behavior

Database-level `ON DELETE CASCADE` is prohibited by default for domain data.

Logical deletion of a parent does not automatically change child rows unless
the aggregate use case explicitly defines that transition.

Aggregate deletion and restoration behavior belongs in application and domain
use cases, not in hidden database cascades.

Physical purge must use an explicit dependency order. A bounded context may
use database cascades only through an accepted design decision that proves the
aggregate boundary, audit behavior, retry safety, and absence of unintended
cross-aggregate deletion.

### Backups and restored environments

Physical purge from an active database does not imply immediate removal from
all backups.

Backup retention and disaster-recovery policies must define how erased or
purged data ages out of backups.

Restoring an older backup into an active environment must replay deletion,
erasure, legal-hold, and identifier-release decisions before the environment
can serve normal traffic.

## Consequences

### Positive

- Deletion behavior is consistent across bounded contexts.
- Normal queries do not accidentally expose deleted records.
- Restoration remains possible until an explicit irreversible operation occurs.
- Retention expiration cannot silently destroy data.
- Legal holds override automated lifecycle processing.
- Identifier reuse cannot silently take ownership from a restored record.
- Physical purge becomes deliberate, observable, and auditable.

### Negative

- Persistence adapters require explicit deleted-record operations.
- Unique-index design becomes more complex.
- Restoration may require conflict-resolution workflows.
- Purge and anonymization require dedicated orchestration.
- Backup restoration requires replay of lifecycle decisions.
- Indefinite retention for some records increases storage and governance cost.

### Required safeguards

Implementation must include:

- tenant-aware deletion and restoration checks
- default exclusion of deleted records
- explicit include-deleted repository operations
- immutable deletion, restoration, purge, and hold audit events
- conflict checks before restoration
- legal-hold checks before purge, anonymization, or identifier release
- idempotency for purge and anonymization workflows
- tests proving that deleted data is not returned by normal queries
- tests proving that active legal holds block irreversible operations
- tests proving that physical purge is unavailable through normal APIs

## Alternatives considered

### Immediate physical deletion

Rejected because it prevents restoration, complicates audit and legal holds,
and increases the impact of accidental deletion.

### Automatic purge at retention expiry

Rejected because retention expiry establishes eligibility, not sufficient
authorization for irreversible deletion.

### Permanent retention of every record

Rejected as a universal rule because privacy, storage, legal, and contractual
requirements differ by data class.

Indefinite retention remains valid for a documented data class.

### Boolean-only deletion state

Rejected because it does not record when deletion occurred and provides
insufficient lifecycle information.

### Global ORM filter as the only safeguard

Rejected because hidden filters can be bypassed by native queries, maintenance
code, migrations, and specialized repositories.

Repository contracts and tests remain mandatory.

### Database cascade as the default

Rejected because it hides business behavior, weakens auditability, and can
delete data outside the intended aggregate boundary.

## Rollback or migration considerations

This accepted ADR is immutable. A later decision must supersede it.

Existing tables adopting this policy require:

- classification as global or tenant-owned data
- a migration adding deletion metadata
- backfilling or documenting unknown historical deletion state
- replacement or review of uniqueness constraints
- repository updates that exclude deleted records by default
- explicit restoration and purge use cases
- legal-hold integration where applicable
- tests for active, deleted, restored, anonymized, and purged states

Changing the 360-day default does not require rewriting this ADR when the
change is made through a versioned governance policy and remains consistent
with the per-data-class model. A material change to the semantics of retention,
restoration, legal holds, or purge requires a superseding ADR.
