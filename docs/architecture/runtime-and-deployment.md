# Runtime and Deployment Architecture — Current State

Exact supported patches belong in Technology Baseline/deployment metadata. This document defines runtime topology, deployment, security, and operational invariants. ADR-0042 selects `production-single-server` as the initial production profile. `production-ha` remains the expansion profile.

## 1. Production workload baseline

Production application workloads run as immutable OCI containers on Kubernetes and require:

- immutable image digest; no `latest`;
- provenance/source identity tied to reviewed Git commit;
- non-root execution / `runAsNonRoot`;
- `allowPrivilegeEscalation: false`;
- Linux capabilities dropped by default;
- `seccompProfile: RuntimeDefault`;
- read-only root filesystem where compatible;
- finite CPU/memory requests and limits;
- distinct startup/readiness/liveness probes;
- liveness independent from ordinary downstream availability;
- readiness only when the workload can safely serve intended traffic;
- graceful shutdown aligned with `terminationGracePeriodSeconds`;
- dedicated least-privilege ServiceAccount; Kubernetes `default` is prohibited for application workloads;
- deny-by-default NetworkPolicy with explicit ingress/egress;
- least-privilege Istio authorization;
- profile-specific replica/HPA/PDB/topology settings backed by measured capacity.

Privileged containers, host networking, `hostPath`, extra capabilities, or materially relaxed security context require an explicit current security decision and automated policy evidence.

## 2. Production Kubernetes profiles

### 2.1 Selected `production-single-server`

The initial production platform uses one K3s server that is also the only schedulable workload node.

```text
Kubernetes: 1.35.6
K3s:        v1.35.6+k3s1
nodes:      1 server + workload node
HA:         none
app replicas: 1
HPA:        disabled
availability PDB: disabled
```

K3s profile rules:

- embedded SQLite is the Kubernetes control-plane datastore;
- K3s secrets encryption is enabled;
- Flannel is disabled;
- the K3s network-policy controller is disabled so Calico remains the NetworkPolicy authority;
- bundled K3s Traefik and ServiceLB are disabled so the repository edge stack remains authoritative;
- K3s datastore directory and server token are protected and copied encrypted off-host as operational recovery artifacts;
- Git remains desired-state authority;
- multiple pods on the same server MUST NOT be described as node HA;
- host/node/kernel/storage/maintenance failure may stop the complete platform.

A one-replica PDB MUST NOT block necessary maintenance while creating no real availability. Replica/HPA/PDB targets in service documents are the `production-ha` targets unless the service document explicitly states another profile rule.

### 2.2 Expansion `production-ha`

When availability/capacity requirements justify expansion:

```text
3 dedicated stacked control-plane/etcd nodes
>=3 schedulable workers
redundant stable L4 controlPlaneEndpoint
N+1 critical worker capacity
6-hour encrypted off-node etcd snapshots + pre-upgrade snapshot
```

Critical replicas spread across workers/failure domains. External etcd is not a default.

## 3. Calico + Istio Ambient

Calico OSS 3.32.x is the primary CNI/NetworkPolicy family for Kubernetes 1.35.x; exact patches come from compatibility baseline. The single-server K3s profile uses Calico as a custom CNI and does not run the conflicting bundled Flannel/network-policy controller.

Istio Ambient is the production service mesh. NetworkPolicy remains independent defense in depth. Production application workloads use STRICT mTLS, dedicated ServiceAccount identities, default-deny/least-privilege authorization, and positive/negative policy tests.

```text
trustDomain = prod.sajtech.internal
meshID      = platform-prod
principal   = prod.sajtech.internal/ns/<namespace>/sa/<service-account>
```

Waypoints are absent by default and are added only for explicit L7 policy/routing/telemetry needs.

For `production-single-server`, Ambient is a **production-readiness benchmark gate**. Measure `istiod`, Istio CNI and `ztunnel` idle/peak CPU/RAM, p95/p99 request impact, throughput, connection count, OOM/restart behavior, Calico interaction and complete-stack headroom. If the benchmark fails, increase host capacity or approve a reviewed replacement security architecture. Do not silently disable workload identity or strict mTLS to fit the server.

Mesh retry MUST NOT duplicate application/client retry ownership.

## 4. Istio trust hierarchy

```text
Offline Root CA
-> Production Cluster Intermediate CA
-> workload certificates
```

- root: 10 years, offline, two encrypted physical copies, never Kubernetes/OpenBao;
- intermediate: one year, rotate starting 90d before expiry with >=30d overlap, tightly controlled in `istio-system`;
- workload certificate: 24h automatic rotation.

ADR-0042 changes none of these trust rules.

## 5. Helm, GitOps, Argo CD and promotion

Helm 4 is the packaging baseline. Shared organization deployment rules belong in one reviewed application/library chart rather than copied charts.

Environment overlays are explicit. Secret values never enter Git, Helm/Kustomize values, images, or rendered CI logs. CI runs lint/render/schema/policy/secret checks and target/deployed diffs where applicable.

Complex migration hooks are prohibited unless ownership, idempotency, timeout/retry, failure, rollback/fail-forward and test semantics are explicit. Non-trivial database migrations SHOULD use controlled migration jobs/workflows.

Current GitOps roots:

```text
deploy/clusters/staging
deploy/clusters/production
```

Argo CD reconciles reviewed desired state with automated sync/self-heal/prune, `allowEmpty=false`, `PruneLast=true`, and explicit confirmation for destructive critical resources.

The exact signed immutable image digest validated in staging is promoted to production. Rebuild between staging and production is prohibited. Rollback is reviewed Git state only when application/schema/data compatibility is safe; otherwise use the approved fail-forward/incident path.

## 6. Production PostgreSQL runtime

ADR-0019/0027/0034/0037 define database ownership, backup and recovery. `data-and-messaging.md` is the implementation-facing data authority.

### Single-server

- one physical CloudNativePG cluster;
- one PostgreSQL instance;
- distinct database/runtime role/migration role/Flyway history per mutable service;
- forced RLS where applicable;
- no cross-service SQL/credentials;
- global application-pool budget <=70% of `max_connections`, with >=30% operational headroom;
- no PostgreSQL primary failover claim;
- one shared physical failure/recovery blast radius.

### HA

Each mutable PostgreSQL service uses a dedicated CloudNativePG cluster; critical clusters use the current three-instance synchronous durability/failover model and independent backup identities.

### Backup/PITR

Both profiles retain:

- continuous encrypted off-site WAL archive;
- daily online physical base backup;
- PostgreSQL RPO <=5m;
- 35-day PITR;
- monthly retained recovery artifact for 12 months;
- verification every backup cycle;
- monthly isolated restore evidence;
- quarterly full cold DR.

`pg_dump + cron` is not the production backup strategy.

In single-server, physical PITR restores the complete shared cluster into an isolated recovery environment. A service-specific recovery then transfers only the required service database through the approved controlled recovery procedure. It MUST NOT destructively restore unrelated current databases.

ADR-0040 Compromised Password's immutable SQLite dataset and ADR-0041 Reference Data bundle remain outside PostgreSQL PITR.

## 7. Kafka runtime

`production-single-server`:

```text
1 Kafka 4.2.x combined KRaft broker/controller
critical RF=1
minISR=1
acks=all
idempotent producers
unclean leader election disabled
formal non-HA acceptance
```

`production-ha`:

```text
3 brokers + 3 dedicated controllers
critical RF=3
minISR=2
acks=all
idempotent producers
unclean leader election disabled
```

Native TLS/authentication, per-service principals, ACLs, quotas and bounded partitioning are mandatory in both profiles. Kafka remains rebuildable async transport, not business authority. Outbox/Inbox/idempotency/replay requirements do not weaken under RF=1.

## 8. Security Redis runtime

`production-single-server`:

```text
1 Redis instance
TLS + per-owner ACL/key namespaces
noeviction
AOF enabled
appendfsync everysec
no failover claim
```

`production-ha`:

```text
1 primary + 2 replicas + 3 Sentinel voters
TLS + per-owner ACL/key namespaces
noeviction
```

Redis is restricted to security-ephemeral capabilities such as semantic quotas and BFF session/pre-auth state. It is not business source of truth. Covered security decisions fail closed on dependency/time-source failure. If session state is lost, the user reauthenticates; browser cookies never reconstruct server authority.

## 9. Kyverno admission

Kyverno remains production admission authority for the signed-artifact/security policy set.

`production-single-server` may run one Kyverno replica because same-host replicas do not create node HA. The policy inventory may be reduced only after proving removed rules are redundant/non-critical or enforced by another blocking control.

The retained single-server policy set continues to block at least:

- non-digest production images;
- invalid/unapproved signatures;
- invalid/missing provenance;
- invalid/missing signed CycloneDX SBOM attestation;
- unapproved privileged/host-network/unsafe `hostPath`/unsafe security-context patterns;
- critical ServiceAccount/deployment identity violations that are reliable admission properties.

Admission unavailability MUST NOT become an allow path. Audit-only production admission is not permitted.

`production-ha` keeps >=3 Kyverno replicas/topology/disruption protection.

Policy-authoring RBAC and policy-engine HTTP/SSRF restrictions remain unchanged.

## 10. Public edge

Production request path remains:

```text
Internet
-> upstream L3/L4 volumetric mitigation/scrubbing
-> external L4
-> Traefik
-> dedicated Caddy + Coraza WAF
-> Web BFF
```

The K3s bundled Traefik/ServiceLB is disabled in the single-server profile. The repository-pinned Traefik/Gateway API deployment remains authoritative.

Direct Internet -> BFF and Traefik -> BFF application paths that bypass WAF are prohibited by route + NetworkPolicy + Istio authorization. Traefik dashboard/insecure API is not public. Wildcard/catch-all public routes are prohibited.

WAF uses the approved Caddy/Coraza/CRS family, DetectionOnly tuning before reviewed blocking, narrow versioned exceptions, bounded body policy, no automatic rule updates, and PII-safe telemetry. Upstream volumetric protection remains mandatory.

## 11. Human privileged production access

ADR-0030 defines the invariant: zero standing privileged access, phishing-resistant authentication, explicit reason, two-reviewer write/admin approval, bounded elevation, durable audit and protected break glass.

### Single-server

Teleport is not deployed. Human host access uses hardened OpenSSH plus hardware-backed FIDO2.

Mandatory controls:

- SSH only from the approved management path/network;
- no direct root login;
- password authentication disabled;
- no shared accounts/keys;
- privileged human authentication requires FIDO2 user presence and user verification;
- FIDO2 authentication alone does not grant root/Kubernetes/database write authority;
- write/admin elevation maximum 30 minutes, with automatic expiry and at least two authorized reviewers;
- read-only elevation maximum one hour;
- no permanent `cluster-admin`, shared kubeconfig, shared DB password, or manual standing `sudoers` substitute;
- SSH/auth/process/privilege/security-config events captured by OS audit;
- `sudo` I/O/session logging for privileged interactive use where applicable;
- Kubernetes/database privileged operations audited at those boundaries;
- required audit exported off-host to append-only/tamper-resistant storage outside ordinary requester control;
- `.bashrc`, shell history or `PROMPT_COMMAND` logging is not authoritative audit;
- separately protected hardware-backed break-glass identity, incident-linked and reviewed/rotated after use.

### HA

Teleport Enterprise Self-Hosted remains the privileged human access plane with current SSO/WebAuthn/JIT/session-recording controls.

## 12. OpenBao and External Secrets — unchanged

OpenBao is **not** simplified by ADR-0042.

OpenBao 2.6.1 remains the exact current secret-authority pin under ADR-0011. The existing v1 topology and recovery model remain unchanged, including its current Raft/PVC, Shamir, encrypted snapshot, restore/unseal and External Secrets/Kubernetes Auth workflows.

Normal application hot paths use validated mounted/local key material, not per-request OpenBao RPCs. OpenBao remains a security-sensitive control-plane dependency for secret refresh/rotation/recovery.

Secret values never enter Git, ConfigMaps, normal logs/traces/metrics, or other unapproved durable surfaces. Rotating key material remains read-only mounted/local according to its owning service contract.

ADR-0042 MUST NOT be used to remove, replace, bypass, or weaken OpenBao.

## 13. MFA and browser security — unchanged

The infrastructure profile does not change end-user MFA/security semantics. Current Identity/BFF/OIDC/TOTP/SMS/recovery/session/CSRF/CORS rules remain owned by ADR-0012/0016 and service documents.

Email/SMS verification or recovery is not a freely selectable weaker substitute for active TOTP where the current Identity rule requires TOTP.

## 14. Observability and audit placement

Application logs remain structured/PII-safe. Metrics/labels remain bounded. Required security/audit evidence is not silently dropped as ordinary telemetry.

In single-server, observability running on the same host is a resource competitor and must be included in full-stack capacity evidence. Required privileged-access audit must additionally be exported off-host so loss/compromise of the server does not erase the only audit copy.

## 15. Capacity and availability interpretation

`production-single-server` is deliberately non-HA. It does not claim:

- control-plane quorum;
- node failover;
- PostgreSQL primary failover;
- Redis Sentinel failover;
- Kafka broker/controller failover;
- Kyverno node-level admission availability;
- maintenance without platform downtime.

A `2 vCPU / 3-4 GiB RAM` full-stack host is not approved without measured evidence. Production approval requires the complete-stack benchmark from ADR-0042/performance/readiness documents, including >=30% validated CPU+memory headroom and applicable >=2x projected peak evidence.

If the host cannot pass, increase resources or move to `production-ha`. Do not weaken OpenBao, Kyverno, Ambient security, backup/PITR, MFA, WAF, workload identity, audit, or fail-closed behavior to make the host fit.

## 16. Verification

Before production approval, the selected profile proves at least:

- exact pinned/verified platform artifacts and supported compatibility;
- Kubernetes/K3s custom-CNI and bundled-component disablement where applicable;
- workload security context, ServiceAccount, NetworkPolicy and Istio identity policies;
- profile-correct replica/HPA/PDB render;
- PostgreSQL database/role/Flyway/RLS isolation plus profile-specific backup/restore/failover semantics;
- Kafka profile topology + TLS/ACL/rebuild/replay/idempotency;
- Redis TLS/ACL/noeviction + single-server AOF/restart or HA Sentinel evidence;
- Ambient workload-identity/mTLS positive/negative tests and `istioctl analyze`, plus single-server capacity benchmark;
- Kyverno signature/provenance/SBOM/security admission negative tests;
- single-server OpenSSH FIDO2/JIT/audit/break-glass tests or HA Teleport tests;
- unchanged OpenBao secret/recovery flows;
- unchanged MFA downgrade-prevention tests;
- full-stack load/soak/reboot/recovery evidence and explicit single-server non-HA sign-off.

Production readiness is blocked when required runtime evidence is absent.
