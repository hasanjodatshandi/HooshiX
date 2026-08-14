# ADR-0037: PostgreSQL Restore Evidence and Upgrade Safety v1

## Status

Accepted — current effective decision

## Date

2026-08-11; normalized to current-only documentation on 2026-08-14

## Decision

Every production service that owns mutable PostgreSQL relational business persistence retains the current monthly isolated PostgreSQL restore cadence and participates in the quarterly full cold-DR cadence. This ADR standardizes recovery evidence, overdue/failure policy, and compatibility-aware upgrade rollback/fail-forward behavior for the dedicated CloudNativePG fleet.

ADR-0040 Compromised Password Service's immutable, read-only, rebuildable SQLite reference dataset is outside the PostgreSQL restore fleet. Its recovery evidence is immutable dataset rebuild/redeploy, integrity/readiness validation, and fail-closed behavior; it does not inherit PostgreSQL WAL/PITR/monthly database-restore requirements.

### Restore drill evidence

Every monthly isolated PostgreSQL service restore produces an immutable or append-only evidence record containing at least:

- service and production PostgreSQL cluster identity;
- backup artifact/WAL recovery source identity;
- requested recovery timestamp when PITR is exercised;
- actual recovered database timestamp;
- drill start/end timestamps and measured recovery duration;
- measured RPO/RTO result against the applicable target;
- restored PostgreSQL/CloudNativePG version;
- Flyway schema version;
- integrity checks and result;
- tenant/RLS negative-access checks where applicable;
- current ADR-0028 erasure/legal-hold replay checks where applicable;
- runbook revision;
- executor/approver identity;
- final `PASS`/`FAIL` and incident/ticket reference when failed.

The PostgreSQL fleet dashboard exposes for each service cluster:

```text
last successful backup verification
last successful isolated restore
last restore RPO/RTO result
next restore due date
overdue/failed status
```

Backup creation success alone is not recovery proof.

### Failure and overdue policy

- failed monthly PostgreSQL restore -> immediate reliability incident/ticket;
- ordinary production promotion for the affected PostgreSQL-backed service freezes until a replacement restore passes, except an explicitly approved emergency security fix;
- overdue PostgreSQL restore -> visible release-risk gate and escalation to service owner + Platform Reliability;
- quarterly full cold-DR remains mandatory and is not replaced by per-service PostgreSQL drills.

ADR-0040 recovery failure has its own promotion/readiness consequence: Compromised Password cannot claim production readiness when the approved immutable dataset cannot be rebuilt/redeployed and validated fail closed.

### Upgrade waves and rollback safety

A failed staging PostgreSQL/CloudNativePG upgrade stops production rollout. There is no universal automatic database rollback timer because rollback safety depends on state compatibility.

1. **GitOps/config/operator change with backward-compatible PostgreSQL state** — revert to the prior approved manifest/operator version when upstream support and compatibility permit it, then verify quorum, replication, backups, and application health.
2. **PostgreSQL minor/patch upgrade** — stop the wave on regression; downgrade only when the supported procedure proves binary/data compatibility. Otherwise fail forward or restore through the tested runbook.
3. **PostgreSQL major or irreversible storage/schema transition** — no automatic in-place downgrade. Use an explicitly tested migration strategy with a service-specific rollback/recovery point.
4. **Application Flyway migration** — executed migrations remain immutable; rollback is application compatibility over expanded schema or a deliberate forward migration, not editing/reversing executed migration files.

Automation may stop rollout and revert reversible GitOps state automatically. It MUST NOT perform an unsupported/unsafe database downgrade to meet an arbitrary time objective.

ADR-0040 SQLite application/engine/dataset-format upgrades follow their own compatibility matrix and immutable artifact rollback/fail-forward rules; production does not mutate/migrate the active dataset in place.

### Recovery exercise program

Current operational cadence includes:

- monthly isolated restore per production service with mutable PostgreSQL relational business persistence;
- quarterly full cold-DR exercise across the platform;
- scheduled staging PostgreSQL primary/replica failure exercises;
- at least quarterly Authorization/database failover exercise under load;
- Compromised Password immutable-dataset rebuild/redeploy plus missing/corrupt fail-closed exercise at least quarterly and before material dataset-format/storage changes;
- Compromised Password replica/node-loss exercise with identical approved dataset version at least quarterly or before material topology/storage changes;
- controlled production game days only after staging evidence, with explicit owner, blast radius, abort criteria, and change window.

Unbounded/random production fault injection is not required.

## Verification requirements

- PostgreSQL restore evidence schema is complete/queryable for every service cluster in the mutable PostgreSQL fleet;
- dashboard identifies overdue/failed PostgreSQL drills;
- simulated failed PostgreSQL restore freezes ordinary promotion only for the affected PostgreSQL-backed service;
- staging/production PostgreSQL/CloudNativePG upgrade failure stops later waves;
- reversible manifest/operator rollback is tested when supported;
- irreversible/major PostgreSQL transition test proves no unsafe automatic downgrade;
- erasure/legal-hold replay is verified before restored PostgreSQL traffic where applicable;
- monthly PostgreSQL/quarterly platform cadence produces traceable evidence;
- ADR-0040 Compromised Password rebuild/redeploy/corrupt-dataset/replica-loss cadence produces traceable non-PII artifact/runtime evidence without fabricating PostgreSQL restore records.

## Rollback considerations

Rollback MUST NOT remove required PostgreSQL recovery evidence/cadence, bypass a failed-restore promotion freeze, expose restored erased/held data before reconciliation, perform an unsupported PostgreSQL/CloudNativePG downgrade, or treat ADR-0040 immutable SQLite artifact recovery as mutable PostgreSQL restore.