# ADR-0034: Dedicated CloudNativePG Fleet Operations v1

## Status

Accepted — current effective decision

## Date

2026-08-11; normalized to current-only documentation on 2026-08-13

## Decision

Production keeps one dedicated CloudNativePG cluster per persistent microservice. Operational complexity is reduced through fleet standardization and automation, not by reconsolidating service security/failure domains.

ADR-0027 defines service database/cluster isolation. ADR-0019 defines shared HA/backup mechanics. This ADR defines the current fleet-management model; ADR-0037 defines restore evidence and upgrade safety.

### GitOps fleet model

A versioned reusable PostgreSQL component under `deploy/` defines the common CloudNativePG baseline. Per-service overlays vary only reviewed inputs such as:

- service/database identity;
- resource and storage sizing;
- workload criticality/SLO class;
- backup destination namespace/prefix and credentials;
- maintenance window;
- service-specific stricter retention/recovery requirements.

Common HA, synchronous durability, security, monitoring, backup verification, RLS-role expectations, and policy controls are inherited rather than copied by hand.

### Isolation remains real

Every production service cluster retains independent:

- PostgreSQL process/failure domain;
- database roles and credentials;
- persistent volumes;
- backup credentials and object-storage namespace/prefix;
- encryption context where supported;
- restore target/recovery evidence;
- upgrade/rollback/fail-forward execution.

A service application/migration credential MUST NOT authenticate to another service cluster.

### Fleet observability

Common bounded recording/alert rules expose at least:

- service owner;
- PostgreSQL/CloudNativePG version;
- primary/replica and synchronous-replication health;
- storage/IO pressure;
- connection/pool headroom;
- WAL/archive freshness;
- last successful backup verification;
- last successful isolated restore exercise;
- next maintenance/upgrade wave.

Labels remain bounded and do not expose tenant/user/business identifiers.

### Upgrade waves

Platform database/operator upgrades use one controlled compatibility set and roll in waves:

```text
staging restore/failover/compatibility evidence
-> one lowest-risk production service cluster
-> observation window
-> remaining clusters one at a time
```

A failed staging or production wave stops further rollout. Production-wide simultaneous PostgreSQL/CloudNativePG upgrades are prohibited.

### Backup and DR automation

Backup policy comes from the common baseline while every service preserves independent credentials/artifact namespace/encryption context. Restore exercises target one service cluster at a time and produce service-specific evidence. Shared operational tooling MUST NOT merge service backup trust boundaries.

### Physical consolidation

Shared production physical PostgreSQL is not a planned default optimization. Any future shared/hybrid topology intentionally reduces current isolation and therefore requires a new or revised current ADR with security, blast-radius, SLO, backup, noisy-neighbor, migration, and rollback evidence. Cost/operator convenience alone does not override the boundary.

## Verification requirements

- every service overlay renders from the common fleet baseline;
- policy tests prove independent database/runtime/migration/backup identities;
- one-cluster-at-a-time upgrade/rollback/fail-forward drills pass;
- common dashboards/alerts cover every service without high-cardinality business labels;
- monthly restore evidence is traceable to each service cluster;
- one service-cluster failure does not affect another service's PostgreSQL availability;
- GitOps/policy tests prevent accidental shared production endpoints/credentials.

## Rollback considerations

Rollback may revert a compatible reusable fleet template/version per controlled wave. It MUST NOT collapse multiple services into one production cluster, share application/migration/backup identities, or perform an unsafe database downgrade.