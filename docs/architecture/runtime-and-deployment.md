# Runtime and Deployment Architecture — Current State

Exact patches live in Technology Baseline/deployment locks. This document defines runtime topology/deployment/security invariants. ADR-0042 selects `production-single-server`; ADR-0043 owns network trust; ADR-0044 owns Day-One ordinary observability.

## 1. Production workload baseline

Production application workloads require:

- immutable signed image digest tied to reviewed Git revision;
- non-root; `allowPrivilegeEscalation=false`; default capabilities dropped; `RuntimeDefault` seccomp;
- read-only root filesystem where compatible;
- finite CPU/memory/ephemeral-storage requests/limits;
- correct startup/readiness/liveness and graceful shutdown;
- dedicated least-privilege ServiceAccount;
- deny-by-default NetworkPolicy and least-privilege Istio authorization;
- profile-correct replica/HPA/PDB topology;
- ADR-0044 logs/metrics/traces/health from first executable service commit.

Privileged containers, host network, broad `hostPath`, extra capability, or relaxed context require an explicit current security decision. ADR-0044 permits only the narrow exact read-only pod/container-log mount for the Collector.

## 2. Kubernetes profiles

### Selected single-server

```text
Kubernetes 1.35.6
K3s v1.35.6+k3s1
1 server + workload node
embedded SQLite control-plane datastore
1 app replica per independent service
HPA off
availability PDB off
no HA
```

- K3s secrets encryption enabled;
- Flannel and K3s network-policy controller disabled;
- Calico is CNI/NetworkPolicy authority;
- bundled Traefik/ServiceLB disabled;
- K3s datastore/token protected and copied encrypted off-host;
- Git/GitOps remains desired-state authority;
- same-host replicas are not node HA.

### HA expansion

Retains current dedicated control-plane/worker topology and profile-specific redundancy/evidence.

## 3. Calico + Istio Ambient

Calico 3.32.x is the primary CNI/NetworkPolicy family. Istio Ambient provides strict mTLS/workload identity.

```text
trustDomain = prod.sajtech.internal
meshID      = platform-prod
principal   = prod.sajtech.internal/ns/<namespace>/sa/<service-account>
```

Waypoints are absent by default unless an explicit L7 need is measured/reviewed.

Single-server Ambient is benchmark-gated. If it cannot fit the validated capacity envelope, add host capacity or approve a reviewed security architecture; do not silently disable mTLS/workload identity.

## 4. Helm/GitOps/promotion

Helm 4 + Argo CD remain current packaging/GitOps path.

- environment overlays explicit;
- no secret values in Git/values/images/logs;
- lint/render/schema/policy/secret checks;
- complex migration hooks need explicit ownership/idempotency/deadline/failure/rollback evidence;
- exact signed staging-validated image digest is promoted to production;
- no production rebuild;
- rollback only when app/schema/data compatibility is safe.

## 5. PostgreSQL

Single-server:

- one physical CloudNativePG/PostgreSQL instance;
- distinct DB/runtime/migration/Flyway ownership per mutable service;
- forced RLS where applicable;
- application pool maxima <=70% global `max_connections`; >=30% operational reserve;
- no primary failover claim.

HA retains dedicated service clusters/current durability/failover model.

Both profiles retain continuous off-site WAL, daily base backup, <=5m PostgreSQL RPO target, 35-day PITR, retained monthly artifacts, monthly restore, quarterly cold DR. `pg_dump + cron` is not primary recovery.

ADR-0040/0041 immutable reference artifacts are outside PostgreSQL PITR.

## 6. Kafka

Single-server:

```text
1 combined KRaft broker/controller
RF=1 / minISR=1
acks=all + idempotence
unclean leader election disabled
non-HA
```

HA retains RF3/minISR2 current topology.

TLS/auth/principals/ACLs/quotas, Outbox/Inbox/idempotency/replay remain mandatory. Kafka is transport, not business authority.

## 7. Security Redis / quota runtime

Single-server:

```text
1 Redis
TLS + per-owner ACL
noeviction
AOF appendfsync everysec
no failover claim
```

ADR-0024 additionally requires:

- exact `/32` IPv4 / `/128` IPv6 hard client identity;
- separate `/24`/`/64` aggregate pressure;
- app/Redis <=2s skew + local wall-vs-monotonic common-clock guard;
- host-sync readiness + 60s safe re-arm after clock fault;
- no security TTL reset;
- low-cardinality new-bucket allocation guard;
- >=30% Redis memory reserve;
- `QUOTA_TIME_SOURCE_UNHEALTHY` / `QUOTA_CAPACITY_UNHEALTHY` fail-closed outcomes distinct from user quota denial.

Session loss causes reauthentication. Browser cookie does not reconstruct server authority.

## 8. Kyverno admission

Kyverno 1.18.2 remains blocking/fail-closed. Single-server may run one replica but does not weaken policy semantics.

New production controls use stable CEL-based `policies.kyverno.io/v1` policy APIs. CI/render gates reject greenfield legacy `kyverno.io/v1` ClusterPolicy/Policy and `kyverno.io/v2` CleanupPolicy/ClusterCleanupPolicy.

Required blocking controls include digest/signature/provenance/SBOM and critical workload security/identity policies. Audit-only production admission is not accepted.

## 9. Public edge/client address

```text
Internet
-> upstream mitigation
-> external L4
-> Traefik
-> Caddy/Coraza
-> BFF
```

- direct non-L4 Traefik origin denied;
- direct Internet->BFF and Traefik->BFF denied;
- external L4 preserves source through trusted PROXY v2;
- insecure proxy/forwarded trust prohibited;
- Caddy strict proxy parsing and internal header overwrite;
- BFF accepts one exact canonical IP from WAF-only path;
- backend receives exact typed IP context and derives ADR-0024 exact/aggregate dimensions;
- raw IP is not ordinary telemetry/persistence.

## 10. Human access

Single-server:

```text
approved device -> WireGuard -> management address -> OpenSSH/FIDO2 -> JIT privilege
```

Public SSH denied. Per-device peer keys, no root/password/shared keys, <=30m write elevation/two reviewers, bounded read-only elevation, OS/`sudo`/K8s/DB audit exported off-host. Network reachability never substitutes for human identity/privilege.

HA retains current Teleport path.

## 11. OpenBao/MFA

OpenBao 2.6.1 remains unchanged secret authority with current Raft/PVC/Shamir/snapshot/restore/ESO/Kubernetes Auth behavior. Application hot paths use validated local mounted material, not new per-request OpenBao RPC.

End-user MFA/session/browser security semantics remain unchanged. Infrastructure profile does not permit Email/SMS downgrade around required active TOTP.

## 12. Day-One observability runtime

Single-server target from ADR-0044:

```text
structured JSON stdout -> otelcol-contrib 0.157.0 -> Loki 3.7.4
Micrometer metrics      -> Prometheus 3.13.2 -> Alertmanager 0.33.1
OpenTelemetry traces    -> otelcol-contrib -> Tempo 3.0.2
Prometheus/Loki/Tempo   -> Grafana 13.1.3
external black-box monitor -> approved public edge, outside host failure domain
```

Collector requirements:

- dedicated ServiceAccount/RBAC/NetworkPolicy;
- internal-only OTLP receiver;
- restricted telemetry-backend egress;
- memory limiter/batch/finite queues/drop observability;
- pre-export redaction/filtering;
- no host network/privilege escalation;
- only exact read-only Kubernetes pod/container log paths mounted from host.

Loki single-binary and Tempo monolithic are explicitly non-HA in single-server. Exact retention/sampling/storage values are deployment evidence, not invented constants.

Trace/baggage/correlation is telemetry only. Ordinary telemetry backend outage does not fail ordinary business requests. Required security/privileged audit remains separate durable/off-host authority.

## 13. Compromised Password runtime

ADR-0040 corpus is official offline HIBP Pwned Passwords SHA-1. Runtime SQLite stores 20-byte SHA-1 reference hashes and is immutable/read-only/query-only. SHA-1 is screening-only; credentials remain Argon2id.

Dataset age <=35 days at readiness; acquisition/build validation at least every 30 days; complete-corpus cardinality/response compatibility measured before release. No runtime HIBP egress/fallback.

## 14. Reference Data deployment

Before ADR-0041 trigger, Reference Data is an immutable local bundle in the owning deployable. Do not create `reference-data-service` for one journey/route group.

Independent service requires evidence for consumers, lifecycle, security boundary, scale/availability, or ownership. When activated, deploy its own SA/Service/contract and remove competing local serving authority.

## 15. Capacity and availability

Single-server makes no failover claim for node/control-plane/PostgreSQL/Redis/Kafka/Kyverno/local observability.

Complete-stack benchmark includes applications, DB/WAL/backup, Redis AOF/cardinality, Kafka, Istio, Kyverno, WAF, OpenBao support components, Collector, Prometheus, Loki, Tempo, Grafana, Alertmanager, storage/network/kernel pressure, and external host-monitor behavior.

Pass requires >=30% validated CPU/RAM headroom, no OOM/sustained swap/MemoryPressure, safe concurrent IO/network tables, and no security/audit/backup/observability bypass.

## 16. Verification

Production evidence covers exact artifacts/compatibility, profile render, DB/Redis/Kafka recovery, quota clock/cardinality/network tests, Kyverno CEL/admission tests, edge/client-address negatives, WireGuard/FIDO/JIT/audit, OpenBao/MFA invariance, Day-One observability/privacy/fault tests, external total-host detection, and cold DR.

Documentation alone remains `NOT VERIFIED`.