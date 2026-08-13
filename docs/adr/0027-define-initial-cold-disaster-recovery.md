# ADR-0027: Cold Disaster Recovery v1

## Status

Accepted — current effective decision

## Date

2026-08-09; normalized to current-only documentation on 2026-08-13

## Decision

### Recovery profile and targets

Initial production uses cold disaster recovery. A second continuously running production Kubernetes cluster is not maintained.

| Component/scope | Target |
| --- | ---: |
| PostgreSQL RPO | <=5 minutes |
| OpenBao RPO | <=1 hour |
| Platform RTO | <=4 hours |

Meeting these targets depends on current off-site artifact availability, GitOps reproducibility, backup/WAL integrity, access to required OpenBao recovery material, capacity, and exercised runbooks.

### PostgreSQL recovery evidence

Current PostgreSQL backup/PITR uses the CloudNativePG/Barman model:

- continuous WAL archive to encrypted off-site object storage;
- daily online physical base backup;
- 35-day PITR window;
- monthly retained backup set for 12 months;
- object versioning/immutability where supported;
- automated verification each backup cycle;
- isolated restore every month;
- full DR exercise every quarter.

Each production service has an independent backup namespace/credential/encryption context under the current service-isolation/fleet decisions. Restore evidence records measured RPO/RTO/integrity rather than treating backup-job success as proof of recovery.

### OpenBao recovery

OpenBao creates an encrypted Raft snapshot every hour and before significant upgrades/changes. Snapshot artifacts remain off the primary PVC with the current approved retention/recovery controls. Manual Shamir recovery remains available under the current OpenBao decision.

### Rebuildable/ephemeral state

Security Redis quota/session state is ephemeral and is not treated as durable business truth or cold-DR RPO state. It is rebuilt/reset safely according to current security/session semantics.

Compromised-password datasets and other explicitly rebuildable reference data are reconstructed from their authoritative source and remain fail-safe while unavailable.

Kafka is rebuilt from GitOps/configuration and recovered through retained service-owned publication/state evidence according to ADR-0044 rather than restored as business source of truth.

### Cold recovery sequence

Canonical ordering:

```text
Disaster
-> provision clean Kubernetes/control plane
-> restore/validate required platform secret authority and access path
-> GitOps reconcile desired infrastructure/workloads
-> restore each service PostgreSQL cluster to approved recovery point
-> replay erasure/legal-hold and other required post-restore correctness evidence
-> reconstruct Kafka/rebuildable state
-> verify secrets, database integrity, workload identity/policy, and critical application flows
-> enable traffic only after gates pass
```

Actual sequencing may interleave GitOps/platform operators and restores where tooling requires it, but traffic remains disabled until integrity/security/recovery gates succeed.

### Exercises

- backup verification: every backup cycle;
- isolated service restore: monthly;
- full cold-DR exercise: quarterly;
- failed restore evidence freezes ordinary affected-service promotion until remediation/revalidation.

## Verification requirements

Prove PostgreSQL WAL/PITR RPO, monthly isolated restore integrity/RTO, independent service backup permissions, OpenBao snapshot recovery, clean-cluster GitOps reconstruction, Kafka reconstruction/replay, erasure replay before traffic, workload identity/policy restoration, critical smoke/transaction verification, and quarterly platform RTO <=4h under the documented exercise scope.

## Rollback considerations

Rollback MUST NOT shorten required recovery evidence/retention, disable WAL archive, replace tested daily physical backups with an unverified scheme, merge service backup credentials, treat backup creation as restore proof, or enable traffic before integrity/security/post-restore erasure gates pass.
