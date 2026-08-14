# ADR-0037: PostgreSQL Restore Evidence and Upgrade Safety v1

## Status

Accepted — current effective decision

## Date

2026-08-11; normalized to current-only documentation on 2026-08-14

## Decision

Every production service that owns mutable PostgreSQL relational business persistence participates in the current monthly isolated PostgreSQL restore cadence and quarterly full cold-DR cadence. Recovery mechanics are profile-aware.

ADR-0040 Compromised Password Service's immutable, read-only, rebuildable SQLite reference dataset is outside PostgreSQL restore scope. Its recovery evidence remains immutable dataset rebuild/redeploy, integrity/readiness validation, and fail-closed behavior.

### Restore evidence

Every monthly PostgreSQL restore exercise produces an immutable or append-only evidence record containing at least:

- production profile;
- affected service database(s) and physical PostgreSQL cluster identity;
- backup artifact/WAL recovery source identity;
- requested recovery timestamp when PITR is exercised;
- actual recovered database timestamp;
- drill start/end timestamps and measured recovery duration;
- measured RPO/RTO result against the applicable target;
- restored PostgreSQL/CloudNativePG version;
- Flyway schema version for every affected service database;
- integrity checks and result;
- tenant/RLS negative-access checks where applicable;
- current ADR-0028 erasure/legal-hold replay checks where applicable;
- runbook revision;
- executor/approver identity;
- final `PASS`/`FAIL` and incident/ticket reference when failed.

Backup creation success alone is not recovery proof.

### `production-single-server` restore model

Physical Barman base backup and WAL archive cover the complete shared PostgreSQL cluster. A physical PITR restore therefore always restores the shared cluster into an **isolated recovery environment**, never over unrelated current production databases as the first recovery step.

For service-specific recovery:

1. restore the complete physical cluster to the required PITR point in isolation;
2. verify PostgreSQL integrity, Flyway versions, roles, RLS, and applicable erasure/legal-hold state;
3. extract only the required service database through the approved logical transfer procedure;
4. restore/import that database during controlled maintenance with application compatibility checks;
5. prove unrelated current service databases were not destructively restored.

The monthly evidence may cover all service databases in one isolated whole-cluster drill when every database receives the required service-specific integrity/RLS/compatibility checks. A failed shared-cluster restore freezes ordinary promotion for all mutable PostgreSQL-backed services until replacement evidence passes, except explicitly approved emergency security changes.

A failure in only the subsequent service-specific extraction/import procedure freezes the affected service and any other service whose shared-cluster safety cannot be proven.

### `production-ha` restore model

Each dedicated service cluster retains monthly isolated service restore evidence with independent backup identity. A failed restore freezes ordinary promotion for that affected PostgreSQL-backed service until a replacement restore passes, except explicitly approved emergency security changes.

### Failure and overdue policy

- failed required restore -> immediate reliability incident/ticket;
- overdue required restore -> visible release-risk gate and escalation to service owner + Platform Reliability;
- quarterly full cold-DR remains mandatory and is not replaced by monthly PostgreSQL drills;
- ADR-0040 recovery failure blocks Compromised Password production readiness under its own artifact rules.

### Upgrade and rollback safety

A failed staging PostgreSQL/CloudNativePG upgrade stops production rollout. There is no universal automatic database rollback timer because rollback safety depends on state compatibility.

1. **GitOps/config/operator change with backward-compatible PostgreSQL state** — revert to the prior approved manifest/operator version when upstream support and compatibility permit it, then verify database/backup/application health.
2. **PostgreSQL minor/patch upgrade** — stop on regression; downgrade only when the supported procedure proves binary/data compatibility. Otherwise fail forward or restore through the tested runbook.
3. **PostgreSQL major or irreversible storage/schema transition** — no automatic in-place downgrade. Use an explicitly tested migration strategy with a profile-appropriate rollback/recovery point.
4. **Application Flyway migration** — executed migrations remain immutable; rollback is application compatibility over expanded schema or a deliberate forward migration, not editing/reversing executed migration files.

`production-single-server` upgrades are platform-wide maintenance for all mutable PostgreSQL service databases because one physical process is shared. The pre-production compatibility test MUST validate every service database before the shared production cluster is mutated.

`production-ha` may use one-service-cluster-at-a-time upgrade waves.

Automation may stop rollout and revert reversible GitOps state automatically. It MUST NOT perform an unsupported/unsafe database downgrade to meet an arbitrary time objective.

### Recovery exercise program

Current operational cadence includes:

- monthly isolated PostgreSQL restore evidence according to selected profile;
- quarterly full cold-DR exercise across the platform;
- `production-ha`: scheduled PostgreSQL primary/replica failure exercises and applicable Authorization/database failover under load;
- `production-single-server`: host/database-process loss and isolated shared-cluster recovery exercise; no false failover test is claimed;
- Compromised Password immutable-dataset rebuild/redeploy plus missing/corrupt fail-closed exercise at least quarterly and before material dataset-format/storage changes;
- controlled production game days only after staging evidence, with explicit owner, blast radius, abort criteria, and change window.

## Verification requirements

- restore evidence schema is complete/queryable for the selected profile;
- dashboard identifies overdue/failed drills;
- simulated restore failure produces the correct profile-specific promotion freeze;
- staging/production PostgreSQL/CloudNativePG upgrade failure stops later rollout;
- reversible manifest/operator rollback is tested when supported;
- irreversible/major PostgreSQL transition proves no unsafe automatic downgrade;
- erasure/legal-hold replay is verified before restored traffic where applicable;
- monthly PostgreSQL/quarterly platform cadence produces traceable evidence;
- `production-single-server` proves isolated whole-cluster PITR plus service-specific recovery without destructive restoration of unrelated current databases;
- `production-ha` proves independent service-cluster restore/failover evidence;
- ADR-0040 produces its own non-PostgreSQL recovery evidence.

## Rollback considerations

Rollback MUST NOT remove required PostgreSQL recovery evidence/cadence, bypass a failed-restore promotion freeze, expose restored erased/held data before reconciliation, perform an unsupported PostgreSQL/CloudNativePG downgrade, replace PITR with `pg_dump + cron`, or treat ADR-0040 immutable SQLite artifact recovery as mutable PostgreSQL restore.
