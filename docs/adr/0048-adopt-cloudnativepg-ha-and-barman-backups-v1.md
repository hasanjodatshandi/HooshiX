# ADR-0048: Adopt CloudNativePG HA and Barman Backups v1

## Status

Accepted

## Date

2026-08-10

## Supersedes / Extends

This ADR supersedes ADR-0030's Notification single-primary PostgreSQL assumption and the temporary no-auto-failover restriction implied by older Notification fencing designs. It also supersedes ADR-0027's weekly-full/daily-differential backup shape only.

ADR-0027 RPO/RTO/PITR/retention/off-site/restore-test objectives remain accepted. Per-service database ownership and Flyway remain unchanged.

## Decision

Production PostgreSQL uses CloudNativePG 1.30.x managing PostgreSQL 18.x, exact patches from Technology Baseline.

### HA topology

- 3 PostgreSQL instances across independent worker/failure domains;
- automatic primary failover;
- synchronous replication requiring 1 failover-eligible replica acknowledgement for acknowledged writes on durability-sensitive workloads;
- no cross-region synchronous replica in v1;
- ordinary primary-failure target <=60s, verified by chaos test.

Applications use the operator's writer endpoint; they never discover primaries through Kubernetes metadata.

Each service retains its private database/schema/role/credentials/Flyway history. No cross-service DB access is introduced.

### Connection budget

Aggregate application Hikari `maximumPoolSize` across all production pods is <=70% of PostgreSQL `max_connections`; >=30% remains reserved for failover, replication, administration, migrations, and emergency headroom.

Each service has an explicit pool budget. PgBouncer is not deployed initially and requires measured connection pressure to justify the extra component.

### Backups and PITR

CloudNativePG uses its supported Barman Cloud integration/plugin to store encrypted off-site physical backups and continuous WAL archive in a separate failure domain/site.

Operational backup shape:

- continuous WAL archive with monitoring sufficient to prove PostgreSQL DR RPO <=5m;
- one scheduled physical base backup at least daily;
- 35-day PITR recovery window;
- monthly retained recovery artifact for 12 months;
- backup before material database/operator upgrade where appropriate;
- monthly isolated restore test;
- quarterly full DR exercise.

Backup success without restore evidence is not accepted as recovery proof.

### Notification durability

Notification's `DISPATCHING` commit must be synchronously durable before provider I/O. Permitted automatic failover cannot acknowledge a state transition and then promote a replica that lacks it. The old Notification application dispatch fence is not required under ADR-0047.

### Security

TLS, least-privilege roles, OpenBao-delivered credentials, NetworkPolicy, encryption of backups, and no public database exposure remain mandatory.

## Verification Requirements

Planned switchover, primary crash, sync durability, connection budget, PITR, monthly restore, DR exercise, service DB isolation, TLS/NetworkPolicy, and Notification dispatch/failover tests.

## Consequences

PostgreSQL ceases to be the major single-instance infrastructure SPOF. Same-site synchronous durability adds bounded commit latency but allows the complex Notification application fence to be removed safely.
