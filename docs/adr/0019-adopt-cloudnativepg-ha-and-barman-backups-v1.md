# ADR-0019: CloudNativePG Topology and Barman Backups v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-14

## Decision

Production PostgreSQL uses CloudNativePG 1.30.x managing PostgreSQL 18.x; exact approved patches are pinned in the Technology Baseline.

Every production microservice that owns mutable PostgreSQL relational business persistence retains a distinct database, independent runtime/migration roles, independent Flyway history, and independent release ownership under ADR-0027. Physical cluster placement is profile-specific.

ADR-0040 Compromised Password Service's immutable, read-only, rebuildable SQLite reference dataset is not mutable PostgreSQL business persistence and is outside this CloudNativePG scope.

### `production-single-server` topology

ADR-0042 selects one shared physical CloudNativePG cluster with one PostgreSQL instance for mutable service databases.

The physical cluster is shared, but database/role/Flyway/security ownership is not. A PostgreSQL/host/storage failure can affect all PostgreSQL-backed services and no automatic primary failover exists. This is explicitly accepted as non-HA.

The shared instance has one global connection budget. Sum of every application Hikari `maximumPoolSize` across all service pods is <=70% of PostgreSQL `max_connections`; >=30% remains for migrations, backup/recovery, administration, and emergency headroom.

### `production-ha` topology

When the HA profile is selected, each mutable PostgreSQL service uses a dedicated CloudNativePG cluster. Critical service clusters use:

- three PostgreSQL instances across independent schedulable workers/failure domains where possible;
- automatic primary failover only when required durability can be preserved;
- synchronous replication requiring acknowledgement from at least one failover-eligible replica for required durable writes;
- no cross-region synchronous replica in v1;
- ordinary primary-failure target <=60s where the service SLO/runbook requires it, proven through failover testing.

Applications use the operator writer endpoint and never discover/select primaries through Kubernetes metadata.

For each dedicated HA service cluster, aggregate application Hikari `maximumPoolSize` across production pods is <=70% of that PostgreSQL cluster's `max_connections`; >=30% remains for failover, replication, administration, migrations, and emergency headroom.

### Backups and PITR

Both profiles use the approved Barman Cloud integration/plugin for encrypted off-site physical backups and continuous WAL archive in a separate failure domain/site.

Current shape:

- continuous WAL archive with monitoring/evidence sufficient for PostgreSQL DR RPO <=5m;
- scheduled online physical base backup at least daily;
- 35-day PITR window;
- monthly retained recovery artifact for 12 months;
- backup before material database/operator upgrade when appropriate;
- automated backup verification each cycle;
- monthly isolated PostgreSQL restore;
- quarterly full DR exercise.

Backup success without restore evidence is not recovery proof.

In `production-ha`, backup credentials/artifact namespaces/encryption contexts remain independent per service cluster.

In `production-single-server`, physical WAL/base backup necessarily covers the complete shared PostgreSQL cluster and therefore uses cluster-level backup identity. Service-specific recovery is performed by restoring the complete physical cluster to an isolated recovery environment, validating it, then transferring only the required service database through an approved compatibility-aware logical procedure. This MUST NOT destructively restore unrelated current service databases.

`pg_dump + cron` is not the production backup/PITR strategy in either profile. Logical export may be a controlled step after an isolated physical PITR restore; it does not replace WAL archive, off-site physical backup, or restore evidence.

### Notification durability

Notification's `DISPATCHING` transaction is durable before external provider I/O according to the selected profile's PostgreSQL durability capability. In the HA profile, permitted automatic failover MUST NOT promote a replica lacking an acknowledged required-durability transition. In the single-server profile there is no automatic PostgreSQL failover; unknown provider outcomes remain reconciliation cases and never authorize blind redispatch.

### Security

Mandatory controls in both profiles include PostgreSQL TLS, service-scoped least-privilege runtime/migration roles, OpenBao/External Secrets credential delivery, no public database exposure, deny-by-default NetworkPolicy, encrypted backup artifacts, forced tenant RLS where applicable, and JIT audited human privilege.

## Verification requirements

Both profiles verify database/runtime+migration isolation, connection budgets, WAL/PITR RPO, monthly isolated restore integrity/RTO, quarterly DR, TLS/NetworkPolicy, runtime-role/RLS restrictions, and Notification ambiguity safety where applicable.

`production-ha` also verifies planned switchover, unplanned primary crash, synchronous acknowledged-write durability, failover refusal when quorum/durability cannot be proven, and independent backup namespaces.

`production-single-server` verifies no false HA/failover claim, isolated whole-cluster PITR restore, service-specific recovery without destructive restoration of another current database, and explicit acceptance of shared physical blast radius.

ADR-0040 immutable SQLite dataset recovery remains its own artifact rebuild/redeploy and fail-closed readiness evidence.

## Rollback considerations

Rollback MUST NOT weaken service database/role/Flyway isolation, forced RLS, off-site WAL/PITR backup, restore evidence, or safe connection budgets. Moving to `production-single-server` requires explicit acceptance of shared physical failure/recovery blast radius and MUST NOT replace the physical recovery model with `pg_dump + cron`.
