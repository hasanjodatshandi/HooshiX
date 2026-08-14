# Reliability and Chaos Engineering Program

## Purpose

Chaos/reliability exercises validate documented failure semantics. The program is staging-first, hypothesis-driven, bounded, and evidence-based. It is not permission for uncontrolled production fault injection.

For `production-single-server`, many infrastructure faults are expected to cause complete/partial outage. Success means **safe failure + accurate detection + bounded recovery + no security/correctness bypass**, not fabricated failover.

## 1. Exercise contract

Before every exercise define:

- profile/environment;
- hypothesis and expected behavior;
- affected services/dependencies;
- blast radius;
- preconditions/backup evidence;
- abort criteria;
- owner/incident commander;
- observability and authoritative audit signals;
- recovery procedure;
- pass/fail conditions;
- evidence retention location.

Production game days require prior staging evidence and an approved window. Never inject a fault that can create unbounded data loss or require disabling security controls.

## 2. Single-server pass criteria

A successful exercise proves:

- outage/degradation is detected and alerted correctly;
- at least one external signal can detect complete-host loss even when local observability disappears;
- security dependencies follow documented fail-closed semantics;
- no unsafe mutation continues through uncertain state;
- off-host recovery/audit artifacts remain available;
- recovery follows approved sequence;
- measured RPO/RTO/downtime is recorded honestly;
- restored traffic passes security/data/critical-journey checks;
- no node/PostgreSQL/Redis/Kafka/Kyverno/observability HA is falsely claimed.

## 3. Host/node loss

At least quarterly before the profile is mature, exercise representative complete-host loss/rebuild or isolated equivalent.

Validate:

1. local Prometheus/Alertmanager/Grafana/Loki/Tempo/Collector loss does not hide the event because external black-box monitoring detects it;
2. off-host K3s recovery artifact or clean GitOps rebuild is usable;
3. OpenBao recovery follows unchanged ADR-0011;
4. PostgreSQL physical WAL/PITR recovery works;
5. Redis/Kafka recovery/rebuild semantics hold;
6. Istio/Kyverno/edge/client-address controls return before unsafe traffic/deployment;
7. applications become ready only when safe;
8. privileged recovery activity has off-host audit;
9. observability returns without being used as a security bypass;
10. final security/data/smoke/telemetry checks pass.

## 4. PostgreSQL exercises

Single-server exercises include process crash/restart, storage unavailability in staging, WAL interruption, backup-verification failure, isolated whole-cluster PITR, service-specific non-destructive recovery, connection/noisy-neighbor saturation, and upgrade rollback/fail-forward behavior.

Expected: affected services may be unavailable; role/RLS isolation never relaxes; failed restore creates promotion freeze; traffic waits for Flyway/integrity/RLS/erasure/legal-hold verification.

HA retains its profile-specific primary/replica/quorum/failover exercises.

## 5. Redis and semantic quota exercises

Single-server exercises include:

- Redis process kill/restart and AOF replay;
- AOF rewrite/fsync under PostgreSQL/Kafka/telemetry IO;
- memory pressure toward `noeviction` boundary;
- Redis timeout/unavailability;
- app/Redis skew >2s;
- one-clock forward/backward jump;
- **common-mode host wall-clock jump affecting app and Redis together**;
- boot/recovery before host time synchronization is healthy;
- Clock Safety Guard trip + 60-second safe re-arm;
- adversarial flood of unique contacts/client addresses causing high new-bucket allocation rate;
- allocation-capacity threshold and cleanup backlog;
- exact-IP vs aggregate-prefix NAT/campus/VPN/IPv6 cases;
- complete session-state loss.

Expected:

- no local fail-open quota/session bypass;
- common-mode clock jump produces `QUOTA_TIME_SOURCE_UNHEALTHY`, not premature refill;
- unsafe new allocation produces `QUOTA_CAPACITY_UNHEALTHY` before eviction/OOM;
- existing security state is not evicted to serve new attacker cardinality;
- aggregate `/24`/`/64` pressure alone does not act as the sole v1 hard 429 identity;
- session loss causes reauthentication;
- quota denial remains distinct from dependency/time/capacity unavailability;
- no false Sentinel/failover claim.

## 6. Kafka exercises

Single-server exercises combined broker/controller kill/restart, isolated data-volume loss, producer failure after local Outbox commit, consumer duplicate/restart, retry/DLQ poison handling, clean GitOps rebuild, replay from service-owned evidence, and storage contention with WAL/AOF/telemetry.

Expected: transport may stop/local broker data may be lost; business/Outbox evidence remains authority; consumers remain idempotent; RF1 is never described as failure tolerant.

## 7. Authorization exercises

Exercise latency/timeout, overload/concurrency, DB unavailability, breaker OPEN/HALF_OPEN recovery, caller cancellation, wrong workload, and explicit business DENY.

Expected result is never fabricated ALLOW. Current gRPC business-DENY semantics remain unchanged.

## 8. Istio/Kyverno exercises

Istio: `ztunnel`/`istiod` restart, plaintext/wrong-SA/NetworkPolicy negatives, node pressure, reboot recovery, and complete-stack impact. Resource pressure does not authorize disabling mTLS/workload identity.

Kyverno:

- one-replica outage in single-server;
- unsigned/wrong-signer image;
- missing/invalid provenance/SBOM;
- prohibited privileged/host-network/unsafe hostPath/security context;
- ordinary workload policy-authoring attempt;
- legacy `ClusterPolicy`/`CleanupPolicy` production manifest fixture -> repository/render gate rejection;
- stable CEL `policies.kyverno.io/v1` positive fixtures;
- external-context SSRF negatives if any approved context exists.

Expected: protected create/update does not pass through a bypass.

## 9. OpenBao and human access

OpenBao remains unchanged. Continue snapshot/restore/unseal/External Secrets/key-rotation/stale-source exercises. Host/capacity fault never justifies replacing/bypassing OpenBao.

Human access exercises public-SSH denial, WireGuard peer revocation, FIDO2 presence/verification, no root/password/shared keys, JIT grant/expiry, `sudo`/OS/boundary audit, off-host audit, and break glass.

## 10. Edge/client-address exercises

Exercise:

- direct Internet->origin/BFF;
- Traefik->BFF WAF bypass;
- forged forwarding/client-IP headers;
- untrusted/missing PROXY v2;
- exact BFF client-address context;
- backend exact `/32`/`/128` hard identity and `/24`/`/64` aggregate pressure derivation;
- oversized body/header/WAF tuning;
- upstream volumetric scenario;
- public SSH denial.

Expected: public traffic follows approved edge and untrusted headers never become quota authority.

## 11. Observability exercises

ADR-0044 is itself failure-tested.

Exercise at least:

- Collector restart/unavailability;
- blocked Loki exporter;
- blocked Tempo exporter;
- Prometheus scrape failure;
- Loki/Tempo/Prometheus disk/cardinality pressure;
- Collector queue saturation/drop behavior;
- malformed/high-cardinality telemetry attribute attempts;
- seeded PII/secret canary flow;
- total local observability loss during complete-host failure;
- independent external black-box detection and recovery indication.

Expected:

- ordinary business processing does not fail solely because best-effort telemetry export/backend is down;
- telemetry queues/memory remain bounded and drops/backpressure are visible;
- prohibited PII/secrets do not appear in exported data;
- trace/baggage cannot alter authN/authZ/tenant/quota/idempotency behavior;
- authoritative security/privileged audit remains durable/off-host;
- local monitoring outage is not mistaken for healthy host state.

## 12. Compromised Password exercises

Exercise stale (>35d), corrupt, missing, incompatible, oversized-cardinality, wrong-schema, and storage-failed HIBP-derived SQLite artifacts.

Expected: service remains unavailable/fail closed; no runtime HIBP fallback and no false-clean password result. Dataset rebuild/redeploy from approved SHA-1 source/provenance is exercised.

## 13. Backup/DR cadence

Minimum:

- every backup cycle: backup verification;
- monthly: isolated PostgreSQL restore;
- quarterly: full cold DR;
- quarterly/before material access change: privileged-access/break-glass;
- before material platform/security version changes: affected recovery/failure evidence;
- scheduled load/soak: complete-stack capacity including observability;
- at least every 30 days: Compromised Password corpus acquisition/build verification; production dataset age <=35d.

## 14. Complete-stack capacity

Run all intended single-server components together under representative traffic plus WAL/base backup, Redis AOF/cardinality attack envelope, Kafka IO, WAF, Istio/Kyverno, and observability.

Record CPU/memory/swap/pressure, storage/IOPS/free space, JVMs, PostgreSQL, Redis, Kafka, mesh/admission/edge, Collector queues/drops, Prometheus series/TSDB, Loki/Tempo ingest/query/storage, Grafana/Alertmanager overhead, networking/kernel pressure, reboot behavior, and external monitor behavior.

Pass requires no OOM/sustained swap/MemoryPressure, >=30% validated CPU+memory headroom, applicable >=2x critical/security peak, safe storage behavior, and no security/admission/backup/audit/observability bypass.

## 15. Evidence/remediation

Every exercise stores exact versions/profile, timestamps, fault/recovery timeline, expected vs actual behavior, measured SLI/RPO/RTO/resources, security/data result, audit/runbook links, `PASS`/`FAIL`, and remediation owner/deadline.

A failed mandatory exercise blocks the dependent production promotion. Do not relabel failure `Not applicable` because single-server intentionally lacks HA.