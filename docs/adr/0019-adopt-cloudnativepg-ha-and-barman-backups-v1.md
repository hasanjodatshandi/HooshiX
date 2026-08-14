# ADR-0019: CloudNativePG HA and Barman Backups v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-14

## Decision

Production PostgreSQL uses CloudNativePG 1.30.x managing PostgreSQL 18.x; exact approved patches are pinned in the Technology Baseline.

Every production microservice that owns mutable PostgreSQL relational business persistence has its own dedicated database and dedicated CloudNativePG cluster under ADR-0027. This ADR defines the HA/backup mechanics used by that PostgreSQL fleet; ADR-0034/ADR-0037 define fleet standardization, restore evidence, and upgrade safety.

ADR-0040 Compromised Password Service's immutable, read-only, rebuildable SQLite reference dataset is not mutable PostgreSQL business persistence and is outside this CloudNativePG fleet. That narrow exception does not authorize mutable SQLite persistence or weaken the PostgreSQL HA/backup rules for services that own mutable relational business state.

### HA topology for critical service clusters

- three PostgreSQL instances across independent schedulable workers/failure domains where possible;
- automatic primary failover only when required durability can be preserved;
- synchronous replication requiring acknowledgement from at least one failover-eligible replica for required durable writes;
- no cross-region synchronous replica in v1;
- ordinary primary-failure target <=60s where the service SLO/runbook requires it, proven through failover testing.

Applications use the operator writer endpoint and never discover/select primaries through Kubernetes metadata.

### Connection budget

For each service cluster in this PostgreSQL fleet, aggregate application Hikari `maximumPoolSize` across production pods is <=70% of PostgreSQL `max_connections`; >=30% remains for failover, replication, administration, migrations, and emergency headroom.

HPA changes must preserve the connection budget. PgBouncer is not a default and requires measured connection pressure plus compatibility/load evidence.

### Backups and PITR

CloudNativePG uses the approved Barman Cloud integration/plugin for encrypted off-site physical backups and continuous WAL archive in a separate failure domain/site.

Current shape:

- continuous WAL archive with monitoring/evidence sufficient for PostgreSQL DR RPO <=5m;
- scheduled online physical base backup at least daily;
- 35-day PITR window;
- monthly retained recovery artifact for 12 months;
- backup before material database/operator upgrade when appropriate;
- automated backup verification each cycle;
- monthly isolated PostgreSQL service restore;
- quarterly full DR exercise.

Backup success without restore evidence is not recovery proof. Backup credentials/artifact namespaces/encryption contexts are independent per PostgreSQL service cluster.

### Notification durability

Notification's `DISPATCHING` transaction is synchronously durable before external provider I/O. Permitted automatic failover MUST NOT promote a replica lacking an acknowledged required-durability transition. Unknown provider outcomes remain reconciliation cases and never authorize blind redispatch.

### Security

Mandatory controls include PostgreSQL TLS, service-scoped least-privilege runtime/migration roles, OpenBao/External Secrets credential delivery, no public database exposure, deny-by-default NetworkPolicy, encrypted backup artifacts, independent backup credentials, forced tenant RLS where applicable, and JIT audited human privilege.

## Verification requirements

For each production service that owns mutable PostgreSQL relational business persistence, test planned switchover, unplanned primary crash, synchronous acknowledged-write durability, failover refusal when quorum/durability cannot be proven, connection/HPA budgets, WAL/PITR RPO, monthly isolated restore integrity/RTO, quarterly DR, service DB/backup isolation, TLS/NetworkPolicy, runtime-role/RLS restrictions, and Notification dispatch/failover ambiguity safety where applicable.

ADR-0040 immutable SQLite dataset recovery is verified through its own artifact rebuild/redeploy and fail-closed readiness evidence, not this PostgreSQL restore program.

## Rollback considerations

Rollback MUST NOT restore shared production physical clusters, weaken required synchronous durability, remove independent backup identities, shorten recovery evidence, exceed safe connection budgets, permit acknowledged Notification dispatch state to be lost during automatic failover, or silently bring ADR-0040's immutable reference artifact into this mutable PostgreSQL fleet.
