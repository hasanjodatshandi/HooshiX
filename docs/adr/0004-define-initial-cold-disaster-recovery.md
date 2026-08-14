# ADR-0004: Cold Disaster Recovery v1

## Status

Accepted — current effective decision

## Date

2026-08-09; normalized to current-only documentation on 2026-08-14

## Decision

### Recovery profile and targets

Initial production uses cold disaster recovery. A second continuously running production Kubernetes cluster is not maintained.

| Component/scope | Target |
| --- | ---: |
| PostgreSQL RPO | <=5 minutes |
| OpenBao RPO | <=1 hour |
| Platform RTO | <=4 hours |

Meeting these targets depends on current off-site artifact availability, GitOps reproducibility, backup/WAL integrity for PostgreSQL-backed mutable state, immutable reference-artifact availability/rebuildability where applicable, access to required OpenBao recovery material, capacity, and exercised runbooks.

### PostgreSQL recovery evidence

For production services that own mutable PostgreSQL relational business persistence, current backup/PITR uses the CloudNativePG/Barman model:

- continuous WAL archive to encrypted off-site object storage;
- daily online physical base backup;
- 35-day PITR window;
- monthly retained backup set for 12 months;
- object versioning/immutability where supported;
- automated verification each backup cycle;
- isolated PostgreSQL restore every month per applicable service;
- full DR exercise every quarter.

Each PostgreSQL-backed mutable-state production service has an independent backup namespace/credential/encryption context under the current service-isolation/fleet decisions. Restore evidence records measured RPO/RTO/integrity rather than treating backup-job success as proof of recovery.

ADR-0040 Compromised Password Service's immutable, read-only, rebuildable SQLite reference dataset is not PostgreSQL business persistence and does not receive fabricated WAL/PITR/monthly database-restore requirements. Its recovery evidence is the approved immutable dataset artifact or deterministic rebuild path, integrity/schema/version validation, and fail-closed readiness/serving behavior.

### OpenBao recovery

OpenBao creates an encrypted Raft snapshot every hour and before significant upgrades/changes. Snapshot artifacts remain off the primary PVC with the current approved retention/recovery controls. Manual Shamir recovery remains available under the current OpenBao decision.

### Rebuildable/ephemeral state

Security Redis quota/session state is ephemeral and is not treated as durable business truth or cold-DR RPO state. It is rebuilt/reset safely according to current security/session semantics.

Compromised-password datasets and other explicitly rebuildable reference data are reconstructed/redeployed from their approved immutable artifact or deterministic authoritative import/rebuild evidence and remain fail-safe while unavailable. For ADR-0040, traffic requiring password create/change/reset remains fail closed until the compatible SQLite dataset passes readiness/integrity gates.

Kafka is rebuilt from GitOps/configuration and recovered through retained service-owned publication/state evidence according to ADR-0015 rather than restored as business source of truth.

### Cold recovery sequence

Canonical ordering:

```text
Disaster
-> provision clean Kubernetes/control plane
-> restore/validate required platform secret authority and access path
-> GitOps reconcile desired infrastructure/workloads
-> restore applicable PostgreSQL-backed mutable-state service clusters to approved recovery points
-> restore/redeploy approved rebuildable reference artifacts such as ADR-0040 SQLite dataset
-> replay erasure/legal-hold and other required post-restore correctness evidence
-> reconstruct Kafka/other rebuildable or ephemeral state as defined by its current contract
-> verify secrets, database/reference-artifact integrity, workload identity/policy, and critical application flows
-> enable traffic only after gates pass
```

Actual sequencing may interleave GitOps/platform operators, PostgreSQL restores, and immutable reference-artifact recovery where tooling requires it, but traffic remains disabled until integrity/security/recovery gates succeed.

### Exercises

- PostgreSQL backup verification: every backup cycle for applicable PostgreSQL-backed mutable-state services;
- isolated PostgreSQL service restore: monthly for each applicable PostgreSQL-backed mutable-state service;
- Compromised Password immutable dataset rebuild/redeploy + missing/corrupt fail-closed exercise: quarterly and before material dataset-format/storage changes;
- Compromised Password replica/node loss with identical approved dataset version: quarterly or before material topology/storage changes;
- full cold-DR exercise: quarterly;
- failed required recovery evidence freezes ordinary affected-capability promotion until remediation/revalidation.

## Verification requirements

Prove, as applicable, PostgreSQL WAL/PITR RPO, monthly isolated PostgreSQL restore integrity/RTO and independent service backup permissions for mutable PostgreSQL state; ADR-0040 immutable SQLite artifact rebuild/redeploy/schema/version/integrity and fail-closed readiness; OpenBao snapshot recovery; clean-cluster GitOps reconstruction; Kafka reconstruction/replay; erasure/legal-hold replay before traffic; workload identity/policy restoration; critical smoke/transaction verification; and quarterly platform RTO <=4h under the documented exercise scope.

## Rollback considerations

Rollback MUST NOT shorten required recovery evidence/retention, disable PostgreSQL WAL archive for applicable mutable state, replace tested daily physical backups with an unverified scheme, merge service backup credentials, treat backup creation as restore proof, treat an unavailable/corrupt/incompatible immutable reference artifact as healthy, add runtime external fallback for ADR-0040, or enable traffic before integrity/security/post-restore erasure gates pass.
