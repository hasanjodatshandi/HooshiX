# ADR-0034: CloudNativePG Production Operations v1

## Status

Accepted — current effective decision

## Date

2026-08-11; normalized to current-only documentation on 2026-08-14

## Decision

Production PostgreSQL operations use one versioned reusable CloudNativePG baseline under `deploy/`. The baseline supports two explicit physical profiles while preserving service database/role/Flyway ownership from ADR-0027 and backup/restore requirements from ADR-0019/ADR-0037.

- `production-single-server`: one shared physical CloudNativePG cluster with one PostgreSQL instance and separate service databases/roles.
- `production-ha`: one dedicated CloudNativePG cluster per mutable PostgreSQL service, using the current HA topology for critical services.

ADR-0040 Compromised Password Service's immutable, read-only, rebuildable SQLite reference dataset remains outside CloudNativePG.

### GitOps model

A versioned reusable PostgreSQL component defines common:

- approved PostgreSQL/CloudNativePG/Barman versions;
- TLS and NetworkPolicy controls;
- backup/WAL/PITR behavior;
- monitoring and storage checks;
- role/RLS expectations;
- resource/security context;
- recovery evidence interfaces.

Profile overlays vary only reviewed inputs such as physical topology, service/database identity, resource/storage sizing, backup destination, maintenance window, and stricter recovery requirements.

### `production-single-server` operations

The shared cluster retains independent service databases, runtime roles, migration roles, Flyway histories, and service ownership. It has one physical volume/process failure domain and one cluster-level physical backup identity.

Operational automation MUST:

- create/reconcile each service database and roles without granting cross-service access;
- revoke unsafe public/default privileges;
- keep runtime roles non-owner `NOSUPERUSER NOBYPASSRLS`;
- expose per-service connection/pool usage while enforcing a global shared-instance connection budget;
- monitor noisy-neighbor CPU/memory/IO/WAL/checkpoint pressure;
- restore the whole physical cluster only into an isolated recovery target before any service-specific recovery transfer;
- keep application/migration credentials away from physical backup credentials.

A service-specific logical recovery transfer after isolated PITR is a controlled recovery action. It is not the backup strategy.

### `production-ha` fleet operations

Every mutable PostgreSQL service retains independent:

- PostgreSQL process/failure domain;
- database roles and credentials;
- persistent volumes;
- backup credentials/object-storage namespace;
- encryption context where supported;
- restore target/recovery evidence;
- upgrade/rollback/fail-forward execution.

Per-service overlays vary resource/storage sizing, SLO criticality, backup destination, maintenance window, and stricter recovery requirements while common HA/security/backup controls are inherited.

### Observability

Bounded recording/alert rules expose at least:

- profile and service/database owner;
- PostgreSQL/CloudNativePG version;
- primary/replica health when replicas exist;
- storage/IO pressure;
- global and per-service connection/pool headroom;
- WAL/archive freshness;
- last successful backup verification;
- last successful isolated restore exercise;
- next maintenance/upgrade activity.

Labels remain bounded and exclude tenant/user/business identifiers.

### Upgrade waves

`production-ha` upgrades roll in controlled service-cluster waves:

```text
staging restore/failover/compatibility evidence
-> one lowest-risk production PostgreSQL service cluster
-> observation window
-> remaining clusters one at a time
```

`production-single-server` has no independent production cluster wave because all mutable service databases share one physical PostgreSQL process. Therefore a PostgreSQL/CloudNativePG upgrade is a platform-wide maintenance event. It requires:

- current verified backup/PITR evidence;
- isolated restore/compatibility test for the shared cluster;
- application/Flyway compatibility across every database before production mutation;
- explicit maintenance/downtime window;
- safe rollback or fail-forward decision before change;
- post-upgrade checks for every service database and RLS/role boundary.

A failed staging or production upgrade stops rollout. Unsupported database downgrades are prohibited.

## Verification requirements

Both profiles verify GitOps render from the common baseline, exact version/security policy, role/RLS isolation, backup freshness, restore evidence, observability without high-cardinality business labels, and upgrade/rollback/fail-forward safety.

`production-single-server` additionally verifies cross-database privilege negatives, global/per-service connection limits, noisy-neighbor thresholds, isolated whole-cluster restore, service-specific recovery without destructive restoration of another current database, and platform-wide upgrade maintenance semantics.

`production-ha` additionally verifies independent service endpoints/credentials/backups, one-cluster-at-a-time upgrade behavior, and that one service-cluster failure does not affect another service's PostgreSQL availability.

ADR-0040 Compromised Password instead proves immutable dataset identity, read-only behavior, fail-closed missing/corrupt dataset behavior, and rebuild/redeploy recovery under its own gates.

## Rollback considerations

Rollback may revert compatible GitOps configuration only when PostgreSQL state remains compatible. It MUST NOT share application/migration roles, remove forced RLS, weaken WAL/PITR evidence, perform an unsafe database downgrade, or broaden ADR-0040.

Moving from `production-ha` to the shared single-server physical cluster requires the explicit ADR-0042 non-HA acceptance and data migration validation. Moving back to HA re-establishes dedicated physical clusters without changing service database/Flyway ownership.
