# Runtime and Deployment Architecture — Current State

Exact supported patches belong in the Technology Baseline/deployment metadata. This document defines runtime topology, security, deployment, and operational invariants.

## 1. Production workload baseline

Production application workloads run as immutable OCI containers on Kubernetes and require:

- immutable image digest; no `latest`;
- provenance/source identity tied to the reviewed Git commit;
- non-root execution / `runAsNonRoot`;
- `allowPrivilegeEscalation: false`;
- Linux capabilities dropped by default; added capability is a narrow reviewed exception;
- `seccompProfile: RuntimeDefault`;
- read-only root filesystem where the approved image permits it;
- CPU/memory requests and limits;
- distinct startup/readiness/liveness probes;
- liveness does not fail merely because a dependency is temporarily unavailable;
- readiness only when the workload can safely serve intended traffic;
- graceful shutdown aligned with `terminationGracePeriodSeconds`;
- dedicated least-privilege ServiceAccount; Kubernetes `default` is prohibited for application workloads;
- deny-by-default NetworkPolicy with explicit ingress/egress;
- PDB/topology spread/anti-affinity according to availability target;
- autoscaling from measured valid signals, never as a substitute for capacity tests.

Privileged containers, host networking, `hostPath`, extra capabilities, or materially relaxed security context require an explicit current security decision and automated policy evidence.

## 2. Kubernetes active-cluster HA

ADR-0051 defines:

```text
3 dedicated stacked control-plane/etcd nodes
>=3 schedulable workers
redundant stable L4 controlPlaneEndpoint
N+1 critical worker capacity
6-hour encrypted off-node etcd snapshots + pre-upgrade snapshot
```

Critical replicas spread across workers/failure domains. External etcd is not a v1 requirement.

## 3. Calico + Istio Ambient

Calico OSS 3.32.x is the primary CNI/NetworkPolicy family for Kubernetes 1.35.x; exact patches come from the compatibility baseline. v1 uses standard Calico dataplane.

Istio Ambient is the service mesh. NetworkPolicy remains independent defense in depth and permits only required HBONE/health/application paths. Production application workloads use STRICT mTLS, dedicated ServiceAccount identities, default-deny/least-privilege authorization, and positive/negative policy tests.

```text
trustDomain = prod.sajtech.internal
meshID      = platform-prod
principal   = prod.sajtech.internal/ns/<namespace>/sa/<service-account>
```

Waypoints are added only for explicit L7 policy/routing/telemetry needs. Mesh retry MUST NOT duplicate application/client retry ownership.

Initial enrollment:

| Namespace | State |
| --- | --- |
| `platform-edge` | enrolled |
| `platform-apps` | enrolled |
| `platform-data` | selective |
| `istio-system` | control plane |
| `argocd` | not initially enrolled |
| `observability` | not initially enrolled |
| `kube-system` | not enrolled |

## 4. Istio trust hierarchy

```text
Offline Root CA
-> Production Cluster Intermediate CA
-> workload certificates
```

- root: 10 years, offline, two encrypted physical copies, never Kubernetes/OpenBao;
- intermediate: one year, rotate starting 90d before expiry with >=30d overlap, tightly controlled in `istio-system`;
- workload certificate: 24h automatic rotation.

## 5. Helm, Kustomize, GitOps, promotion

Helm 4 is the packaging baseline. Shared organization deployment rules belong in one reviewed application/library chart rather than copied charts.

Environment overlays/values are explicit; secret values never enter Git, Helm/Kustomize values, images, or rendered CI logs. CI runs lint/render/schema/policy/secret checks and target/deployed diffs where applicable.

Complex migration Helm hooks are prohibited unless ownership, idempotency, timeout/retry, failure, rollback/fail-forward, and test semantics are explicit. Non-trivial database migrations SHOULD use controlled migration jobs/workflows.

Current GitOps roots:

```text
deploy/clusters/staging
deploy/clusters/production
```

Argo CD reconciles reviewed desired state with automated sync/self-heal/prune, `allowEmpty=false`, `PruneLast=true`, and explicit confirmation for destructive critical resources.

The exact signed immutable image digest validated in staging is promoted to production. Rebuild between staging and production is prohibited. Rollback is reviewed Git state only when application/schema/data compatibility is safe; otherwise use the approved fail-forward/incident path.

## 6. Production PostgreSQL fleet

ADR-0048, ADR-0057, ADR-0064, and ADR-0067 define the current model.

Every persistent production microservice owns a dedicated CloudNativePG 1.30.x cluster, PostgreSQL database, runtime/migration roles, Flyway history, storage/capacity budget, WAL/PITR backup identity/namespace, and restore evidence. Critical services use three PostgreSQL instances.

Clusters use quorum synchronous required durability. Automatic failover is permitted only when acknowledged commits can be preserved; unsafe promotion fails availability rather than acknowledged data.

Aggregate application Hikari maxima across production pods stay <=70% of cluster `max_connections`, preserving >=30% for replication/failover, migration, monitoring, administration, and emergency work. PgBouncer is evidence-driven, not default.

Fleet management uses one reusable GitOps baseline with bounded per-service overlays, common bounded alerts, independent backup trust boundaries, monthly service-specific restore evidence, and one-cluster-at-a-time upgrade waves. A failed wave stops remaining rollout. Production physical consolidation requires a reviewed current architecture change.

### Backup/PITR

- continuous encrypted off-site WAL archive;
- daily online physical base backup;
- PostgreSQL RPO <=5m;
- 35-day PITR;
- monthly retained recovery set for 12 months;
- versioning/object-lock where supported;
- verify every backup cycle;
- isolated restore monthly per service;
- full cold DR quarterly;
- queryable RPO/RTO/integrity/RLS/erasure evidence.

A failed restore freezes ordinary affected-service promotion until replacement evidence passes.

## 7. Kafka

Production critical Kafka uses ADR-0044:

```text
KRaft
3 brokers + 3 dedicated controllers
critical RF=3 / minISR=2 / acks=all
idempotent producers
unclean leader election disabled
```

Native TLS/authentication/ACL/quotas remain mandatory. Kafka is rebuildable transport, not business truth. Cold DR rebuilds configuration from GitOps and replays/reconstructs service-owned evidence. Critical publication/dedup evidence covers the 35-day recovery horizon.

## 8. Security Redis

Approved shared physical `security-redis` is restricted to security-ephemeral capabilities such as semantic quotas and BFF session state:

```text
1 primary + 2 replicas + 3 Sentinel voters
TLS + per-owner ACL/key namespaces
noeviction
```

Raw PII/business IDs are not used as security keys when pseudonymous HMAC keys are required. If quota/session workloads interfere materially, split Sentinel deployments before introducing Redis Cluster complexity.

## 9. Public edge

Production path:

```text
Internet
-> upstream L3/L4 DDoS mitigation
-> external load balancing
-> Traefik
-> dedicated Caddy + Coraza WAF
-> Web BFF
```

Direct Internet -> BFF and Traefik -> BFF application paths are prohibited by route + NetworkPolicy + Istio authorization.

Traefik uses Gateway API by default; proprietary CRDs require explicit capability need. Dashboard/insecure API is not public. Wildcard/catch-all public routes are prohibited.

WAF uses approved Caddy/Coraza/CRS family, PL1, >=7 representative DetectionOnly days before reviewed blocking, narrow versioned exceptions, bounded body policy, no automatic rule updates, and PII-safe telemetry.

Upstream volumetric mitigation is mandatory; the WAF is L7 inspection and is not bandwidth-saturation protection.

## 10. OpenBao and External Secrets

OpenBao 2.6.1 is the exact current secret-authority pin under ADR-0037. v1 uses one Raft instance/PVC, manual Shamir 3 shares/threshold 2, hourly encrypted off-PVC snapshots, and tested restore/unseal.

Normal application hot paths use validated mounted/local key material, not per-request OpenBao RPCs. OpenBao remains a security-sensitive control-plane dependency for secret refresh/rotation/recovery.

External Secrets uses Kubernetes Auth and namespace-scoped stores where practical. Rotating key material is mounted read-only. Secret values never enter Git, ConfigMaps, ordinary env vars for rotating key rings, logs, traces, or metrics.

## 11. Browser security

Browser production follows ADR-0045: OIDC Authorization Code + PKCE S256, exact redirects, server-side transaction/session state, secure `__Host-` cookie, Origin + synchronizer-token CSRF, exact CORS, and centrally tested security headers.

## 12. Supply-chain admission and continuous vulnerability response

Release images are immutable, signed, carry signed provenance and CycloneDX SBOM evidence, and are indexed by deployed digest. Production admission verifies approved registry/digest/signature/provenance/required attestations through HA Kyverno before fail-closed enforcement.

Vulnerability inventory is continuously rescanned/correlated with approved threat/advisory inputs. Exceptions are exact, owned, reviewed, expiring; expiry stops new promotion and escalates production exposure. No scanner/feed proves absence of unknown vulnerabilities and no scan result authorizes an unsigned artifact.

## 13. Human production access

Teleport Enterprise Self-Hosted is the privileged human access plane. No standing production admin/root/database-superuser credentials are permitted. Kubernetes/database/host access uses SSO, phishing-resistant MFA, JIT approval, short-lived roles, audit/session evidence, and automatic expiry. Privileged write elevation requires two reviewers and <=30-minute access.

## 14. Deployment validation

Applicable pre-promotion evidence includes:

- code/unit/integration/architecture/contract/schema/security checks;
- dependency/secret/vulnerability verification;
- Buf/OpenAPI compatibility;
- Helm/Kustomize rendering and Kubernetes security/schema/policy checks;
- rendered-secret and manifest-diff checks;
- immutable digest/signature/provenance/SBOM verification;
- PostgreSQL/CloudNativePG backup/restore/upgrade-policy checks;
- Kafka durability/replay checks;
- Gateway/Traefik/WAF route and blocking tests;
- `istioctl analyze`, Ambient/STRICT mTLS/ServiceAccount/NetworkPolicy/authorization positive and negative tests;
- staging smoke/acceptance/critical browser tests;
- production-safe smoke/synthetic checks.

A required predecessor failure stops downstream promotion. Documentation is not runtime evidence; detailed gates live in `PRODUCTION-READINESS-CHECKLIST.md`.