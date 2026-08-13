# ADR-0064: Standardize Dedicated CloudNativePG Fleet Operations v1

## Status

Accepted

## Date

2026-08-11

## Relationship to Earlier Decisions

This ADR extends ADR-0048, ADR-0053, and ADR-0057. It does **not** supersede
ADR-0057's production physical isolation: each persistent production
microservice continues to own a dedicated CloudNativePG cluster.

## Decision

Production keeps dedicated per-service PostgreSQL clusters. Operational
complexity is reduced through fleet standardization and automation rather than
by silently reconsolidating security/failure domains.

### GitOps fleet model

A versioned reusable PostgreSQL component under `deploy/` defines the common
CloudNativePG baseline. Per-service overlays may vary only approved inputs such
as:

- service/database identity;
- resource and storage sizing;
- workload criticality/SLO class;
- backup destination namespace/prefix and credentials;
- maintenance window;
- service-specific stricter retention or recovery requirements.

Common HA, synchronous-durability, security, monitoring, backup verification,
RLS-role expectations, and policy controls are inherited rather than copied by
hand.

### Isolation remains real

Every production service cluster retains independent:

- PostgreSQL process/failure domain;
- database roles and credentials;
- persistent volumes;
- backup credentials and object-storage namespace/prefix;
- encryption context where supported;
- restore target and recovery evidence;
- upgrade/rollback execution.

One service's application or migration credential must not authenticate to
another service's cluster.

### Fleet observability

Prometheus/Grafana/Alertmanager use common recording/alert rules parameterized
by bounded cluster/service labels. The fleet inventory must expose at least:

- service owner;
- PostgreSQL/CNPG version;
- primary/replica health;
- synchronous-replication health;
- storage/IO pressure;
- connection/pool headroom;
- WAL/archive freshness;
- last successful backup verification;
- last isolated restore exercise;
- next maintenance/upgrade wave.

### Upgrade waves

Platform upgrades use one controlled compatibility set and are rolled in waves:

```text
staging restore/failover evidence
-> one lowest-risk production service cluster
-> observation window
-> remaining clusters one at a time
```

A production-wide simultaneous PostgreSQL/CNPG upgrade is prohibited. A failed
wave stops further rollout.

### Backup and DR automation

Backup policy is generated from the common baseline but every service has
independent credentials/artifact namespace. Restore exercises target one
service cluster at a time and produce service-specific evidence. Shared
operational tooling must not merge service backup trust boundaries.

### No planned consolidation roadmap

The architecture does not define `v2 = shared physical PostgreSQL cluster` as
the expected destination. That would intentionally reduce the isolation added
by ADR-0057.

A future shared/hybrid physical topology requires a new accepted ADR showing
why its security, blast radius, SLO, backup, and noisy-neighbor tradeoffs are
acceptable. Cost or operator convenience alone does not silently override the
current production boundary.

## Verification Requirements

- rendering every service overlay from one common fleet baseline;
- policy tests proving independent credentials/backup namespaces;
- one-cluster-at-a-time upgrade/rollback drill;
- common dashboard/alert coverage without high-cardinality identifiers;
- monthly restore evidence traceable to each service cluster;
- failure of one service cluster does not affect another service's PostgreSQL
  availability;
- GitOps tests prevent accidental shared production database endpoints.

## Consequences

The platform accepts the additional cluster count in exchange for smaller
security and failure blast radius, while avoiding three independently hand-made
backup, monitoring, and upgrade systems.

## Rollback Considerations

Rollback may revert a reusable fleet template/version per controlled wave. It
must not collapse multiple service databases into one production cluster or
share runtime/migration credentials without a superseding ADR.
