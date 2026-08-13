# ADR-0067: Standardize PostgreSQL Restore Evidence and Upgrade Safety v1

## Status

Accepted

## Date

2026-08-11

## Relationship to Earlier Decisions

This ADR extends ADR-0027, ADR-0048, ADR-0057, and ADR-0064. It does not change
the dedicated per-service CloudNativePG production topology or the existing
monthly isolated restore and quarterly full-DR cadence.

## Context

ADR-0027 already requires monthly isolated restores and quarterly DR exercises.
ADR-0064 requires service-specific restore evidence and one-cluster-at-a-time
upgrade waves. This ADR standardizes the evidence, overdue/failure policy, and
safe rollback/fail-forward rules.

## Decision

### Restore drill evidence

Every monthly isolated per-service restore drill produces an immutable or
append-only evidence record containing at least:

- service and production cluster identity;
- backup artifact / WAL recovery source identity;
- requested recovery timestamp when PITR is exercised;
- actual recovered database timestamp;
- drill start/end timestamps and measured recovery duration;
- measured RPO and RTO result against the applicable target;
- restored PostgreSQL/CNPG version;
- Flyway schema version;
- integrity checks executed and result;
- tenant/RLS negative-access checks where applicable;
- erasure/legal-hold replay checks required by ADR-0003/ADR-0058;
- runbook revision;
- executor/approver identity;
- final `PASS` / `FAIL` status and incident/ticket reference when failed.

The fleet dashboard exposes for every service:

```text
last successful backup verification
last successful isolated restore
last restore RPO/RTO result
next restore due date
overdue/failed status
```

A backup artifact is not considered dependable merely because backup creation
succeeded.

### Failure and overdue policy

- A failed monthly restore opens a reliability incident/ticket immediately.
- Production promotion for the affected service is frozen until a successful
  replacement restore drill or an explicitly approved emergency security fix.
- An overdue restore drill is visible as a release-risk gate and escalates to
  the service owner + Platform Reliability.
- Quarterly full-DR exercises remain mandatory and are not replaced by per-
  service restore drills.

### Upgrade waves and rollback safety

A failed staging upgrade stops the production wave. There is **no universal
"automatic rollback within five minutes" requirement** for PostgreSQL because
rollback safety depends on whether the change is reversible.

Upgrade classes are handled as follows:

1. **GitOps/config/operator change with backward-compatible database state** —
   revert to the previously approved manifest/operator version when supported,
   then verify quorum, replication, backup, and application health.
2. **PostgreSQL minor/patch upgrade** — stop the wave on regression; rollback is
   allowed only when the supported procedure proves binary/data compatibility.
   Otherwise fail forward or restore according to the tested runbook.
3. **PostgreSQL major upgrade or irreversible storage/schema transition** — no
   automatic in-place rollback. Use an explicitly tested migration strategy
   (for example blue/green/logical migration or restore/fail-forward as
   appropriate) with a service-specific rollback point.
4. **Application Flyway migration** — follows forward-only migration policy;
   executed migrations are never edited/reversed as a generic infrastructure
   rollback.

Automation may stop rollout and revert reversible GitOps state automatically;
it must not perform an unsafe database downgrade to satisfy an arbitrary time
objective.

### Chaos / recovery program

The operational program includes:

- monthly isolated restore per persistent service;
- quarterly full cold-DR exercise;
- scheduled staging primary-failure and replica-loss exercises;
- at least quarterly Authorization/database failover exercise under load;
- controlled production game days only after staging evidence exists, with
  explicit blast radius, abort criteria, owner, and change window.

Unbounded/random production fault injection is not required for v1.

## Verification Requirements

- evidence schema is complete and queryable for every service;
- dashboard identifies overdue/failed drills;
- a simulated failed restore freezes ordinary promotion for only the affected
  service;
- staging upgrade failure stops later waves;
- reversible manifest rollback is tested;
- irreversible/major upgrade test proves no unsafe automatic downgrade occurs;
- monthly/quarterly cadence produces traceable evidence.

## Consequences

Restore testing becomes auditable operational evidence instead of a calendar
checkbox. Upgrade safety favors data integrity over a cosmetically fast rollback
metric.

## Rollback Considerations

Rollback must not remove monthly/quarterly recovery evidence or permit automatic
PostgreSQL downgrade where compatibility has not been proven.
