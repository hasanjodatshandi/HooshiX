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

Meeting these targets depends on current off-site artifact availability, GitOps reproducibility, PostgreSQL backup/WAL integrity, immutable reference-artifact availability/rebuildability, OpenBao recovery material, capacity and exercised runbooks.

ADR-0042 selects `production-single-server` as the initial topology. This changes physical recovery blast radius but not the RPO/RTO/recovery-evidence requirements.

### PostgreSQL recovery evidence

For production services that own mutable PostgreSQL relational business persistence, current backup/PITR uses the CloudNativePG/Barman model:

- continuous WAL archive to encrypted off-site object storage;
- daily online physical base backup;
- 35-day PITR window;
- monthly retained recovery artifact for 12 months;
- object versioning/immutability where supported;
- automated verification each backup cycle;
- isolated PostgreSQL restore every month;
- full DR exercise every quarter.

`production-single-server` uses one shared physical PostgreSQL cluster, so physical backup/WAL identity is cluster-wide. Service database/runtime role/migration role/Flyway/RLS ownership remains separate. Physical PITR restores the complete shared cluster into an isolated recovery environment; service-specific recovery then transfers only the required database through the approved controlled procedure without destructively restoring unrelated current databases.

`production-ha` retains independent physical backup namespace/credential/encryption context per service cluster.

Restore evidence records measured RPO/RTO/integrity rather than treating backup-job success as proof of recovery.

ADR-0040 Compromised Password Service's immutable, read-only, rebuildable SQLite reference dataset is not PostgreSQL business persistence and does not receive fabricated WAL/PITR/database-restore requirements. Its recovery evidence is the approved immutable dataset artifact or deterministic rebuild path, integrity/schema/version validation and fail-closed readiness/serving behavior.

ADR-0041 Reference Data similarly recovers from the approved immutable signed image/bundle rather than PostgreSQL.

### OpenBao recovery — unchanged

OpenBao creates an encrypted Raft snapshot every hour and before significant upgrades/changes. Snapshot artifacts remain off the primary PVC with the current approved retention/recovery controls. Manual Shamir recovery remains available under ADR-0011.

ADR-0042 does not change OpenBao topology, secret authority, snapshot, unseal or restore semantics.

### Rebuildable/ephemeral state

Security Redis quota/session state is ephemeral and is not durable business truth or cold-DR RPO state.

- single-server Redis uses one instance with AOF; loss may require session re-authentication and security operations remain fail closed until a valid decision is possible;
- HA Redis uses the current Sentinel topology.

Kafka is rebuilt from GitOps/configuration and recovered through retained service-owned publication/state evidence under ADR-0015 rather than restored as business source of truth. Single-server RF=1 may lose broker-local data; Outbox/Inbox/replay evidence remains the recovery authority.

Compromised Password and Reference Data are reconstructed/redeployed from approved immutable artifacts and remain fail-safe while unavailable.

### Cold recovery sequence

Canonical ordering:

```text
Disaster
-> provision clean selected-profile Kubernetes/control plane
-> restore/validate required platform secret authority and privileged access path
-> GitOps reconcile desired infrastructure/security controls
-> restore applicable PostgreSQL mutable state to approved recovery point
-> restore/redeploy immutable reference artifacts
-> replay erasure/legal-hold and required post-restore correctness evidence
-> reconstruct Kafka/other rebuildable or ephemeral state
-> verify secrets, database/reference integrity, workload identity/policy, edge/admission and critical application flows
-> enable traffic only after gates pass
```

For single-server, clean K3s/GitOps rebuild may be safer/faster than restoring Kubernetes operational state. K3s datastore+token are operational recovery artifacts; business persistence and OpenBao recovery follow their owning procedures.

Actual sequencing may interleave operators, PostgreSQL restores and immutable-reference recovery where tooling requires it, but traffic remains disabled until integrity/security/recovery gates succeed.

### Exercises

- PostgreSQL backup verification: every backup cycle;
- isolated PostgreSQL restore: monthly according to selected profile;
- single-server isolated shared-cluster PITR + service-specific non-destructive recovery: monthly/current restore cadence;
- Compromised Password immutable dataset rebuild/redeploy + missing/corrupt fail-closed exercise: quarterly and before material dataset changes;
- full cold-DR exercise: quarterly;
- single-server host/K3s rebuild/recovery: quarterly or before material platform changes;
- failed required recovery evidence freezes ordinary affected-capability promotion until remediation/revalidation.

## Verification requirements

Prove PostgreSQL WAL/PITR RPO, profile-aware isolated restore integrity/RTO, single-server non-destructive service-specific recovery or HA independent service backup permissions as applicable; immutable reference-artifact recovery; unchanged OpenBao snapshot recovery; clean selected-profile Kubernetes/GitOps reconstruction; Kafka reconstruction/replay; erasure/legal-hold replay before traffic; workload identity/admission/edge policy restoration; critical smoke/transaction verification; and quarterly platform RTO <=4h under the documented exercise scope.

## Rollback considerations

Rollback MUST NOT shorten recovery evidence/retention, disable PostgreSQL WAL archive, replace tested physical backup/PITR with `pg_dump + cron`, restore unrelated current service databases destructively, merge service application credentials, treat backup creation as restore proof, treat an unavailable/corrupt immutable reference artifact as healthy, remove/change OpenBao through this topology decision, or enable traffic before integrity/security/post-restore gates pass.
