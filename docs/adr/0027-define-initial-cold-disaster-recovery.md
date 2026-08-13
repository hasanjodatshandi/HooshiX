# ADR-0027: Define Initial Cold Disaster Recovery

## Status

Accepted

## Date

2026-08-09

## Relationship to Earlier Decisions

This ADR resolves the pending production RPO, RTO, backup, PITR, retention,
restore-test, and disaster-recovery topology decisions.

It supersedes only ADR-0011's daily OpenBao Raft snapshot cadence. OpenBao
remains single-node with Integrated Storage, manual Shamir unseal, and no
initial HA or auto-unseal. All other OpenBao and Argo CD decisions remain in
force.

## Context

The initial self-hosted phase does not maintain a continuously running second
production cluster. Recovery therefore depends on off-site encrypted backups,
reproducible GitOps state, tested clean-cluster provisioning, and explicit
restore ordering.

Backup creation alone is insufficient evidence of recoverability. Verification
and isolated restore exercises are part of the recovery contract.

## Decision

### Recovery profile and targets

Initial production uses cold disaster recovery. A second Kubernetes cluster is
not kept continuously running.

The targets are:

| Component or scope | Target |
| --- | ---: |
| PostgreSQL RPO | at most 5 minutes |
| OpenBao RPO | at most 1 hour |
| Platform RTO | at most 4 hours |

### PostgreSQL backup and retention

PostgreSQL uses:

- continuous WAL archiving;
- a weekly full backup;
- a daily differential backup;
- off-site object storage in a different failure domain, provider, or site.

The PITR window is 35 days. Monthly full backups are retained for 12 months.
Backup transport and stored backup artifacts are encrypted.

### OpenBao backup and retention

The single-node OpenBao deployment creates an encrypted Raft snapshot every
hour and before upgrades or significant changes. This hourly cadence
supersedes ADR-0011's daily cadence.

OpenBao snapshot retention is:

| Snapshot class | Retention |
| --- | ---: |
| Hourly | 48 hours |
| Daily | 35 days |
| Monthly | 12 months |

Snapshots remain outside the primary OpenBao PVC.

### Rebuildable and ephemeral data

Identity rate-limit Redis state is ephemeral and is not backed up.

The compromised-password dataset is rebuilt from its authoritative upstream
and is not part of business-data RPO. Its existing active-dataset availability
and fail-closed service rules remain unchanged during normal operation.

### Cold recovery sequence

The canonical recovery sequence is:

```text
Disaster
  -> provision clean Kubernetes
  -> Argo CD reconstructs desired state
  -> restore OpenBao
  -> restore PostgreSQL and PITR
  -> verify secrets and database integrity
  -> enable application traffic
```

Traffic remains disabled until secret and database integrity checks succeed.

### Verification and exercises

The required schedule is:

- automated backup verification during every backup cycle;
- an isolated restore test every month;
- a full disaster-recovery exercise every quarter.

A backup without a successful restore test is not considered a dependable
recovery artifact.

### Implementation gate

Exact PostgreSQL backup tooling, object-store product, storage immutability,
encryption-key custody, bandwidth sizing, clean-cluster provisioning tooling,
and detailed recovery runbooks require explicit approval before deployment.

## Consequences

- Initial DR has lower steady-state infrastructure cost than a warm or hot
  secondary cluster.
- Meeting the 4-hour platform RTO depends on automation, off-site artifact
  availability, Shamir-share access, and exercised runbooks.
- PostgreSQL can lose at most the approved five-minute window only when WAL
  archival and recovery validation meet that objective.
- Redis rate-limit counters may reset after disaster recovery.
- OpenBao backup frequency increases from daily to hourly.

## Alternatives Considered

### Keep a second production cluster continuously running

Deferred for the initial cold-DR phase.

### Back up Identity rate-limit Redis

Rejected because this state is explicitly ephemeral rather than a durable
source of truth.

### Treat backup-job success as proof of recoverability

Rejected because only tested restore evidence makes a backup dependable.

## Rollback or Migration Considerations

This ADR creates no backup jobs or storage resources by itself.

Rollout must preserve existing snapshots until the new retention and restore
tests have proven usable artifacts. Rollback must not delete the last known-good
backup, shorten retention without approval, disable WAL archiving, or restore
traffic before integrity verification.
