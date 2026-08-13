# ADR-0067: PostgreSQL Restore Evidence and Upgrade Safety v1

## Status

Accepted — current effective decision

## Date

2026-08-11; normalized to current-only documentation on 2026-08-13

## Decision

Every persistent service retains the current monthly isolated restore cadence and quarterly full cold-DR cadence. This ADR standardizes recovery evidence, overdue/failure policy, and compatibility-aware upgrade rollback/fail-forward behavior for the dedicated CloudNativePG fleet.

### Restore drill evidence

Every monthly isolated per-service restore produces an immutable or append-only evidence record containing at least:

- service and production cluster identity;
- backup artifact/WAL recovery source identity;
- requested recovery timestamp when PITR is exercised;
- actual recovered database timestamp;
- drill start/end timestamps and measured recovery duration;
- measured RPO/RTO result against the applicable target;
- restored PostgreSQL/CloudNativePG version;
- Flyway schema version;
- integrity checks and result;
- tenant/RLS negative-access checks where applicable;
- current ADR-0058 erasure/legal-hold replay checks where applicable;
- runbook revision;
- executor/approver identity;
- final `PASS`/`FAIL` and incident/ticket reference when failed.

The fleet dashboard exposes for each service:

```text
last successful backup verification
last successful isolated restore
last restore RPO/RTO result
next restore due date
overdue/failed status
```

Backup creation success alone is not recovery proof.

### Failure and overdue policy

- failed monthly restore -> immediate reliability incident/ticket;
- ordinary production promotion for the affected service freezes until a replacement restore passes, except an explicitly approved emergency security fix;
- overdue restore -> visible release-risk gate and escalation to service owner + Platform Reliability;
- quarterly full cold-DR remains mandatory and is not replaced by per-service drills.

### Upgrade waves and rollback safety

A failed staging upgrade stops production rollout. There is no universal automatic database rollback timer because rollback safety depends on state compatibility.

1. **GitOps/config/operator change with backward-compatible database state** — revert to the prior approved manifest/operator version when upstream support and compatibility permit it, then verify quorum, replication, backups, and application health.
2. **PostgreSQL minor/patch upgrade** — stop the wave on regression; downgrade only when the supported procedure proves binary/data compatibility. Otherwise fail forward or restore through the tested runbook.
3. **PostgreSQL major or irreversible storage/schema transition** — no automatic in-place downgrade. Use an explicitly tested migration strategy with a service-specific rollback/recovery point.
4. **Application Flyway migration** — executed migrations remain immutable; rollback is application compatibility over expanded schema or a deliberate forward migration, not editing/reversing executed migration files.

Automation may stop rollout and revert reversible GitOps state automatically. It MUST NOT perform an unsupported/unsafe database downgrade to meet an arbitrary time objective.

### Recovery exercise program

Current operational cadence includes:

- monthly isolated restore per persistent service;
- quarterly full cold-DR exercise;
- scheduled staging primary/replica failure exercises;
- at least quarterly Authorization/database failover exercise under load;
- controlled production game days only after staging evidence, with explicit owner, blast radius, abort criteria, and change window.

Unbounded/random production fault injection is not required.

## Verification requirements

- evidence schema is complete/queryable for every service;
- dashboard identifies overdue/failed drills;
- simulated failed restore freezes ordinary promotion only for the affected service;
- staging/production upgrade failure stops later waves;
- reversible manifest/operator rollback is tested when supported;
- irreversible/major transition test proves no unsafe automatic downgrade;
- erasure/legal-hold replay is verified before restored traffic where applicable;
- monthly/quarterly cadence produces traceable evidence.

## Rollback considerations

Rollback MUST NOT remove recovery evidence/cadence, bypass a failed-restore promotion freeze, expose restored erased/held data before reconciliation, or perform an unsupported PostgreSQL/CloudNativePG downgrade.