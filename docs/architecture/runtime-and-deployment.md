# Runtime and Deployment Architecture

## 1. Kubernetes baseline

All production services run as immutable OCI-compatible containers on
Kubernetes. Exact supported pins live in the Technology Baseline and deployment
metadata.

Typical workload resources:

```text
Deployment / Stateful operator resource as applicable
Service
independent ServiceAccount
ConfigMap
Secret reference
NetworkPolicy
```

HPA/PDB are added when replication/SLA/capacity requires them. HTTPRoute/
GRPCRoute are created only for BFF/public boundaries or explicitly exposed
platform adapters.

Mandatory workload rules:

- immutable image digest; `latest` prohibited;
- CPU/memory requests and limits;
- separate startup/readiness/liveness probes;
- liveness does not fail merely because a dependency is temporarily unavailable;
- readiness reflects whether the workload can safely serve its intended traffic;
- graceful shutdown aligned with `terminationGracePeriodSeconds`;
- non-root and read-only root filesystem where practical;
- privileged/hostPath require explicit security ADR;
- independent ServiceAccount per workload;
- deny-by-default NetworkPolicy with explicit egress/ingress;
- autoscaling uses measured metrics and never replaces load/capacity testing.

## 2. Kubernetes control-plane HA

ADR-0051 requires 3 dedicated stacked control-plane/etcd nodes and at least 3
schedulable workers. The API servers are exposed through one stable redundant L4
`controlPlaneEndpoint`; the endpoint itself must not be a single-host failure
point. External etcd is intentionally not used in v1.

Critical replicated workloads spread across workers/failure domains, and worker
capacity is planned N+1 for the Class A/Authorization request paths. Kubernetes
etcd is snapshotted encrypted off-node every 6 hours and before control-plane
upgrades; clean-cluster GitOps rebuild remains the preferred site-DR path when
appropriate.

## 3. Calico CNI and NetworkPolicy

ADR-0050 selects Calico OSS 3.32.1 as the primary Kubernetes CNI and
NetworkPolicy implementation on Kubernetes 1.35.6. The platform uses Calico's
standard dataplane in v1; experimental/eBPF acceleration is not enabled.

Upstream Istio Ambient remains the service mesh. NetworkPolicy is defense in
depth and must explicitly permit the Ambient HBONE/health flows required by the
selected enrollment while still enforcing deny-by-default application traffic.
Positive and negative tests are mandatory across ordinary pod traffic, ztunnel/
HBONE paths, and selected `platform-data` workloads.

## 4. Helm 4 and Kustomize

Helm 4 is the application packaging baseline. Shared standards belong in a
company application/library chart rather than copied service charts.

Environment composition under `deploy/clusters/*` may use Kustomize overlays as
accepted by ADR-0037. Secret values never enter Helm values/Kustomize/Git.

CI runs `helm lint`, renders both environments, validates Kubernetes schemas and
policy, and scans rendered output for secrets.

## 5. GitOps and Argo CD

Current v1 desired state is in this repository:

```text
deploy/clusters/staging
deploy/clusters/production
```

Argo CD is the GitOps controller. Promotion is PR review + merge to `main` with
immutable image digests, automated sync, self-heal, prune, `allowEmpty=false`,
`PruneLast=true`, and `Prune=confirm` for explicitly destructive critical
resources.

Production rollback is Git revert. Direct unreviewed cluster mutation is
prohibited.

## 6. Production PostgreSQL — CloudNativePG

ADR-0048 + ADR-0057 + ADR-0064 + ADR-0067 are current.

ADR-0057 supersedes the production shared-cluster allowance. Production runs one
dedicated CloudNativePG cluster per persistent microservice. Each service owns
its PostgreSQL database, runtime/migration roles, Flyway history, WAL/PITR backup
namespace/credentials/encryption context, and capacity budget. Critical production
service clusters use 3 PostgreSQL instances. Non-production may consolidate
physical clusters only while preserving database/role isolation.

The cluster uses quorum synchronous replication (`ANY 1` equivalent), required
data durability, and failover quorum. Automatic failover is enabled only when
safe promotion can preserve acknowledged synchronous commits; unsafe promotion
fails availability instead of risking acknowledged data loss.

Three instances are spread across independent schedulable nodes/failure domains
when infrastructure permits.

For each service cluster, aggregate application Hikari `maximumPoolSize` across
that service's production pods is capped at <=70% of PostgreSQL `max_connections`;
>=30% remains reserved for failover, replication, administration, migrations, and
emergency headroom. HPA changes must preserve this budget. PgBouncer is not a
v1 default and is introduced only after measured connection pressure.

ADR-0064/ADR-0067 standardize this dedicated-cluster fleet through one reusable GitOps
CloudNativePG baseline, bounded per-service overlays, common recording/alert
rules, independent backup credentials/artifact namespaces, service-specific
restore evidence, one-cluster-at-a-time production upgrade waves, queryable monthly restore drill evidence, and compatibility-aware rollback/fail-forward rules. A failed
upgrade wave stops the remaining rollout. Shared physical PostgreSQL is not the
planned v2 destination; reducing production isolation requires a new ADR.

## 7. PostgreSQL backup/PITR

Barman Cloud CNPG-I plugin is the PostgreSQL backup/WAL integration. cert-manager
supplies the plugin/operator TLS path.

Baseline:

- continuous WAL archive to encrypted off-site object storage;
- daily online physical base backup;
- PostgreSQL RPO <=5 minutes;
- 35-day PITR window;
- monthly retained backup set for 12 months;
- object versioning and object lock/immutability when supported;
- automated backup verification each cycle;
- isolated restore monthly;
- full DR exercise quarterly.

The daily online base backup supersedes the old daily-differential shape; v1
does not run a separate incremental-backup server solely to reproduce that
mechanic.

## 8. Kafka runtime

Kafka v1 runs KRaft with 3 brokers and 3 dedicated controllers for production
critical use. Critical topics use RF=3, minISR=2, producer `acks=all`, idempotent
producers, and no unclean leader election.

Kafka remains native-TLS/auth/ACL/quota protected. It is transport, not the
business source of truth. Cold DR reconstructs Kafka from GitOps and replays
retained service-owned outbox/publication records after PostgreSQL recovery.

## 9. Security Redis

The shared physical `security-redis` deployment is used only for approved
security-ephemeral capabilities such as semantic quotas and BFF sessions, with
separate ACL identities and key namespaces.

Production baseline is 1 primary + 2 replicas + 3 Sentinel voters, TLS, and
`noeviction`. Raw PII/business identifiers are not stored in keys. Quota/session
state is not treated as durable business truth and is not part of the cold-DR
RPO.

If BFF session workload and semantic-quota workload interfere materially under
load, split them into separate physical Redis deployments before adding Redis
Cluster complexity.

## 10. Traefik and Gateway API

Traefik is the external gateway. Kubernetes Gateway API is preferred; Traefik
CRDs are used only for proprietary capability not representable through Gateway
API.

Traefik owns TLS termination/redirect, public routing, edge request-size limits,
coarse rate limits, security headers, observability, and weighted routing where
approved. Dashboard is private. Public catch-all routes are prohibited.

## 11. Dedicated production WAF

Public application traffic follows:

```text
Internet / External LB
-> Traefik
-> edge-waf (Caddy + Coraza v3 + OWASP CRS 4.x LTS)
-> web-bff
```

Direct Internet->BFF and Traefik->BFF application paths are prohibited by route,
NetworkPolicy, and Istio authorization.

WAF rollout remains PL1 and DetectionOnly for at least 7 representative days,
then reviewed narrow tuning, then blocking. Automatic CRS updates are
prohibited. Large/upload endpoints require explicit request-body policy rather
than globally expanding inspection.

ADR-0059 requires upstream L3/L4 volumetric DDoS mitigation/scrubbing from the
hosting/network provider before traffic can saturate the origin. Redundant L4
load-balancer capacity, bounded connection/handshake limits, reviewed SYN/
conntrack controls, emergency coarse limits, provider escalation contacts, and
origin-bypass restrictions are production requirements. Coraza remains the L7
WAF and is not described as volumetric protection.

## 12. Istio Ambient Mode

Istio Ambient Mode is mandatory for enrolled production application workloads.

```text
trustDomain = prod.sajtech.internal
meshID      = platform-prod
principal   = prod.sajtech.internal/ns/<namespace>/sa/<service-account>
```

Production uses STRICT mTLS, default-deny authorization, and least-privilege
ServiceAccount identities. Waypoints exist only for actual L7 method/path/
header/claim policy or routing/telemetry needs.

Istio does not replace NetworkPolicy or native Kafka/PostgreSQL/Redis security.

## 13. Istio CA hierarchy

```text
Offline Root CA
-> Production Cluster Intermediate CA
-> Istio workload certificates
```

- Root lifetime 10 years, key offline, never Kubernetes/OpenBao;
- two encrypted physical copies in separate locations;
- intermediate lifetime 1 year;
- rotation begins 90 days before expiry with >=30-day overlap;
- intermediate key only in `istio-system`, encrypted at rest and tightly RBACed;
- workload cert TTL 24h with automatic rotation.

## 14. Ambient enrollment

| Namespace | Initial enrollment |
| --- | --- |
| `platform-edge` | enrolled |
| `platform-apps` | enrolled |
| `platform-data` | selective per workload |
| `istio-system` | control plane |
| `argocd` | not initially enrolled |
| `observability` | not initially enrolled |
| `kube-system` | not enrolled |

Enrollment is explicit and opt-in.

## 15. OpenBao and External Secrets

OpenBao 2.6.1 remains the authoritative external Secret Manager. Initial v1
operating topology remains one OpenBao Raft instance/PVC, manual Shamir seal
(3 shares, threshold 2), hourly encrypted off-PVC snapshots, no initial HSM or
multi-node HA.

This single-node topology is acceptable in v1 because normal application hot
paths consume mounted/local key material rather than making per-request OpenBao
calls. OpenBao remains an operational/control-plane SPOF for secret refresh,
rotation, and recovery and has explicit backup/recovery monitoring.

Move to 3-node OpenBao HA through a later ADR when measured secret-refresh
availability, recovery/unseal time, compliance, or operational evidence makes
the current topology an SLO risk.

External Secrets Operator uses Kubernetes Auth and namespace-scoped
`SecretStore` by default. Rotating secrets/key rings are mounted read-only,
never placed in Git/ConfigMaps/environment variables for the rotating key-ring
path.

## 16. Browser/BFF security deployment

ADR-0045 is mandatory for browser production: OIDC Authorization Code + PKCE
S256, exact redirect matching, server-side transaction/session state, secure
`__Host-` cookie, Origin+synchronizer-token CSRF protection, exact CORS policy,
and centrally tested browser security headers.

## 17. Supply-chain admission

ADR-0046 is mandatory before production enforcement.

Cosign signs immutable image digests and required provenance/CycloneDX SBOM
attestations. CI generates an SBOM for the final image, indexes it by service +
image digest, and scans it with pinned Syft/Grype tooling (or an explicitly
approved equivalent) so transitive CVEs can be correlated to deployed artifacts.
Kyverno stable `ImageValidatingPolicy` verifies production images at admission.
Production workloads must use approved registries, immutable digests, valid
organization signatures, and approved provenance. Exceptions are narrow,
Git-reviewed, owned, and time limited.

Kyverno runs HA with at least 3 replicas/PDB/topology spread before fail-closed
production admission is enabled.

## 18. Production human access

ADR-0060 selects Teleport Enterprise Self-Hosted as the privileged human access
plane. Normal engineers have no standing production admin/root/database-superuser
credentials. Kubernetes/database/host access uses SSO, phishing-resistant MFA,
JIT approval, short-lived roles, session/audit evidence, and automatic expiry.
Privileged write access requires two reviewers and is limited to <=30 minutes.
Static kubeconfigs, shared SSH keys, shared database passwords, and permanent
`cluster-admin` are prohibited. The access plane resides in a management failure
domain so a workload-cluster incident does not remove the only supported recovery
path.

## 19. Deployment validation

Applicable CI/release checks include:

- Helm/Kustomize render of staging and production;
- Kubernetes schema/policy validation;
- secret scan of rendered output;
- immutable digest/signature/provenance/SBOM verification;
- CloudNativePG/PostgreSQL policy validation;
- Barman backup/WAL configuration validation;
- Kafka topic/controller/broker durability validation;
- Gateway/Traefik/WAF tests;
- `istioctl analyze`, Ambient enrollment, STRICT mTLS and authorization tests;
- ServiceAccount/NetworkPolicy checks;
- manifest diff;
- post-deployment smoke and production-safe synthetic checks.

See `PRODUCTION-READINESS-CHECKLIST.md` for gates that require implementation/evidence
rather than another architecture product decision.
