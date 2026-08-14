# Reliability and Chaos Engineering Program

## Purpose

The program validates that documented failure semantics actually hold. It is staging-first and evidence-driven; it is not permission for uncontrolled random production failure injection.

ADR-0042 selects `production-single-server`. For that profile, many infrastructure faults are expected to cause complete/partial outage because redundancy is intentionally absent. The test objective is therefore **safe failure + bounded recovery + no security/correctness bypass**, not fabricated failover.

## 1. General rules

Every exercise defines before execution:

- production profile/environment;
- hypothesis and expected failure semantics;
- affected services/dependencies;
- blast radius;
- preconditions/backup evidence;
- abort criteria;
- responsible operator/incident owner;
- observability/audit signals;
- recovery procedure;
- pass/fail conditions;
- evidence retention location.

Production game days require prior staging evidence, approved change window and explicit owner. Never inject a failure that could create unbounded data loss or bypass security controls merely to test resilience.

## 2. Pass criteria by profile

### `production-single-server`

A one-host/node failure can make the platform unavailable. A successful exercise proves:

- outage is detected/alerted accurately;
- security dependencies fail according to their documented contract rather than fail open;
- no unsafe data mutation continues during uncertain state;
- off-host recovery/audit artifacts remain available;
- rebuild/restore follows the approved dependency-safe sequence;
- measured recovery stays within the accepted RTO for the profile;
- no hidden dependency on local-only unrecoverable state exists;
- restored traffic passes integrity/security/smoke verification;
- the event does not falsely claim node/database/Redis/Kafka failover.

### `production-ha`

Exercises retain their current quorum/failover/rescheduling objective and prove the applicable service SLO while one allowed failure occurs.

## 3. Single-server host/node loss

At least quarterly before the profile is considered mature, exercise a representative complete-host loss/rebuild or isolated equivalent.

Validate:

1. alerts identify platform-wide impact;
2. off-host K3s datastore/token recovery artifact exists and is usable when chosen;
3. clean GitOps rebuild is available as an alternative to control-plane-state restore;
4. OpenBao recovery follows unchanged ADR-0011 procedures;
5. PostgreSQL physical recovery uses WAL/PITR/off-site backup when required;
6. Redis/Kafka rebuild/restart semantics are correct;
7. Istio/Kyverno/edge security returns before unsafe application traffic/deployments;
8. applications become ready only when safe;
9. privileged recovery activity has off-host audit evidence;
10. final state passes security/data/smoke checks.

The expected service availability during the host-loss interval is outage. The test passes on safe bounded recovery, not zero downtime.

## 4. PostgreSQL exercises

### Single-server

Exercise at least:

- PostgreSQL process crash/restart;
- storage unavailability simulation in staging;
- WAL archive interruption alerting;
- backup verification failure;
- isolated whole-shared-cluster PITR restore;
- service-specific recovery extraction/import without destructive restoration of another current DB;
- connection-pool/noisy-neighbor saturation;
- rollback/fail-forward behavior for a shared-cluster upgrade failure.

Expected behavior:

- all affected PostgreSQL-backed services may become unavailable;
- RLS/role/cross-service DB boundaries never relax;
- `pg_dump + cron` is never substituted for required WAL/PITR evidence;
- failed restore creates the correct promotion freeze;
- recovered traffic does not open before Flyway/integrity/RLS/erasure/legal-hold checks.

### HA

Retain primary crash, replica loss, synchronous-durability/failover refusal, service-isolated restore and one-cluster-upgrade-wave exercises.

## 5. Redis exercises

### Single-server

Exercise:

- Redis process kill/restart;
- AOF replay/recovery;
- AOF rewrite/fsync latency under concurrent PostgreSQL/Kafka/telemetry IO;
- memory pressure approaching `noeviction` boundary;
- dependency timeout/unavailability;
- app/Redis clock skew beyond the approved bound;
- complete loss of session state.

Expected behavior:

- no local fail-open quota/session bypass;
- no eviction-based silent authority loss;
- session loss results in re-authentication;
- browser cookie does not reconstruct authenticated server state;
- quota/time-source failure remains distinct from quota denial;
- no Sentinel/failover claim exists.

### HA

Retain primary/replica/Sentinel loss and failover exercises.

## 6. Kafka exercises

### Single-server

Exercise:

- combined broker/controller process kill/restart;
- broker data-volume loss in isolated staging;
- producer failure while service state commits to Outbox;
- consumer duplicate/restart;
- retry/DLQ poison handling;
- clean Kafka rebuild from GitOps;
- replay/reconstruction from service-owned critical publication evidence;
- storage contention with WAL/AOF/telemetry.

Expected behavior:

- async transport may stop;
- broker-local data may be lost in the destructive test;
- committed business state/outbox evidence remains authoritative;
- consumer idempotency prevents duplicate business effect;
- critical event state can be replayed/reconstructed;
- RF=1 is never described as broker-failure tolerant.

### HA

Retain broker/controller loss, ISR/quorum and RF3/minISR2 durability exercises.

## 7. Authorization exercises

In both profiles exercise:

- Authorization latency/timeout;
- overload/concurrency saturation;
- DB unavailability;
- breaker OPEN/HALF_OPEN recovery;
- caller cancellation;
- wrong workload identity;
- explicit denial.

Expected result is never fabricated ALLOW. Safe local checks may reject only. Single-server may show longer/unavailable recovery because redundancy is absent; security semantics remain identical.

## 8. Istio Ambient exercises

Exercise:

- `ztunnel` restart;
- `istiod` restart/unavailability;
- NetworkPolicy deny/allow edges;
- wrong ServiceAccount principal;
- plaintext attempt;
- node resource pressure during representative load;
- Calico/Ambient connectivity after K3s reboot.

Single-server additionally validates complete-stack resource headroom and that resource pressure does not lead operators/automation to disable strict mTLS/workload identity. Waypoints are tested only when explicitly present.

## 9. Kyverno exercises

Single-server:

- one Kyverno replica unavailable;
- unsigned/wrong-signer image;
- missing/invalid provenance;
- missing/invalid signed SBOM;
- prohibited privileged/host-network/unsafe `hostPath`/security-context workload;
- ordinary workload attempting policy authoring;
- approved external-context SSRF negatives if such context exists.

Expected behavior: protected new/updated workload is not admitted through a bypass. Existing workloads are not killed merely because admission is unavailable.

HA additionally validates admission availability through one replica/node loss.

## 10. OpenBao exercises — unchanged

ADR-0042 does not change OpenBao.

Continue current snapshot/restore/unseal/External Secrets/key-rotation/stale-source exercises. Verify normal hot paths do not acquire a new per-request OpenBao dependency.

Exercise capacity or host loss MUST NOT conclude that OpenBao can be removed/replaced/bypassed without a separate current security decision.

## 11. Human privileged-access exercises

### Single-server

Exercise:

- password SSH attempt -> denied;
- direct root SSH -> denied;
- shared/non-approved key -> denied;
- FIDO2 without required user presence/verification -> denied;
- approved FIDO2 -> attributable login only, not automatic admin;
- JIT write elevation -> two reviewers + <=30m expiry;
- expiry -> privilege removed automatically;
- `sudo` I/O/session logging;
- OS audit for auth/process/privilege/security-config changes;
- off-host audit ingestion and requester inability to alter evidence;
- audit-export interruption handling;
- protected break-glass exercise.

Shell history/`.bashrc` does not satisfy audit evidence.

### HA

Retain Teleport SSO/WebAuthn/JIT/session-recording/break-glass exercises.

## 12. Edge/WAF exercises

Exercise:

- direct Internet/BFF path attempt;
- Traefik/BFF WAF bypass attempt;
- WAF deny/false-positive tuning cases;
- oversized body/header;
- upstream volumetric-protection operational scenario;
- K3s bundled Traefik/ServiceLB absence in single-server.

Expected behavior: public application traffic always traverses the approved edge/WAF path.

## 13. Backup and DR cadence

Minimum cadence:

- every backup cycle: automated backup verification;
- monthly: isolated PostgreSQL restore evidence;
- quarterly: full platform cold-DR exercise;
- quarterly or before material access changes: privileged-access/break-glass exercise;
- before material platform/security version changes: profile-specific recovery/failure evidence;
- scheduled load/soak: complete-stack single-server capacity evidence.

ADR-0040 immutable SQLite and ADR-0041 immutable Reference Data recover by signed artifact rebuild/redeploy under their own gates.

## 14. Complete-stack capacity exercise

Run all intended single-server platform/application components together. Include representative traffic plus background WAL/base backup, Redis AOF, Kafka log traffic and observability.

Record:

- CPU/memory/swap/node pressure;
- storage latency/IOPS/free space;
- all JVM RSS/CPU;
- PostgreSQL connection/query/WAL/checkpoint/backup state;
- Redis memory/AOF/rewrite;
- Kafka memory/IO/lag;
- Istio resources/latency;
- Kyverno admission;
- edge/WAF;
- observability;
- restart/reboot behavior.

Pass requires no OOM/sustained swap/MemoryPressure, >=30% validated CPU+memory headroom, applicable >=2x projected peak evidence on critical/security paths, safe storage behavior and no security/admission/backup bypass.

A `2 vCPU / 3-4 GiB RAM` host is not approved without this evidence.

## 15. Evidence and remediation

Every exercise stores:

- exact artifact/profile versions;
- start/end/fault/recovery timestamps;
- expected vs actual behavior;
- measured SLI/RPO/RTO/resource data;
- security/data-integrity result;
- audit/runbook links;
- `PASS`/`FAIL`;
- remediation owner/deadline.

A failed mandatory exercise blocks production promotion according to the current readiness/recovery policy. Do not relabel a failure `Not applicable` merely because the selected profile is intentionally non-HA.
