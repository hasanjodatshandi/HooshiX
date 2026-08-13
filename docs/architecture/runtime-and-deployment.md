# Runtime and Deployment Architecture

## 1. Kubernetes workload baseline

All production services run as immutable OCI-compatible containers on Kubernetes. Exact supported pins live in the Technology Baseline and deployment metadata.

Typical resources:

```text
Deployment / Stateful operator resource as applicable
Service
independent ServiceAccount
ConfigMap
Secret reference
NetworkPolicy
```

HPA/PDB/topology spread are added according to replication/SLO/capacity needs. HTTPRoute/GRPCRoute exists only for approved public/BFF boundaries or explicitly exposed platform adapters.

Mandatory production application-workload rules:

- image is referenced by immutable digest; `latest` is prohibited;
- release metadata identifies the source Git commit and approved build/provenance;
- `runAsNonRoot`/non-root execution is required;
- `allowPrivilegeEscalation: false`;
- Linux capabilities are dropped by default (`drop: ["ALL"]`); any added capability is the smallest reviewed exception;
- `seccompProfile: RuntimeDefault`;
- root filesystem is read-only where the approved image/runtime permits it;
- privileged containers, host networking, or `hostPath` require an explicit current security decision and narrow justification;
- CPU/memory requests and limits are mandatory;
- startup, readiness, and liveness probes are distinct;
- liveness MUST NOT fail merely because PostgreSQL, Kafka, Redis, an external provider, or another dependency is temporarily unavailable;
- readiness succeeds only when the workload can safely serve intended traffic;
- graceful shutdown aligns application shutdown with `terminationGracePeriodSeconds`;
- each workload uses an independent least-privilege ServiceAccount; Kubernetes `default` is prohibited for production application workloads;
- NetworkPolicy is deny-by-default with explicit required ingress/egress;
- autoscaling uses measured valid metrics and never replaces load/capacity testing;
- sensitive replicated workloads use PDB/topology-spread/anti-affinity according to their availability target.

## 2. Kubernetes control-plane HA

ADR-0051 requires three dedicated stacked control-plane/etcd nodes and at least three schedulable workers. API servers are exposed through one stable redundant L4 `controlPlaneEndpoint`; the endpoint itself MUST NOT be a single-host failure point. External etcd is not part of v1.

Critical replicated workloads spread across workers/failure domains, and worker capacity is planned N+1 for Class A/Authorization request paths. Kubernetes etcd is snapshotted encrypted off-node every six hours and before control-plane upgrades. Clean-cluster GitOps rebuild is the preferred site-DR path when appropriate.

## 3. Calico CNI and NetworkPolicy

ADR-0050 selects Calico OSS 3.32.x as the primary CNI/NetworkPolicy family for Kubernetes 1.35.x. Exact approved patches live in the Technology Baseline/deployment metadata. v1 uses the standard dataplane, not experimental/eBPF acceleration.

Istio Ambient remains the service mesh. NetworkPolicy is independent defense in depth and explicitly permits only required Ambient HBONE/health flows while preserving deny-by-default application reachability. Positive/negative tests cover ordinary pod traffic, ztunnel/HBONE paths, and selectively enrolled `platform-data` workloads.

## 4. Helm 4 and Kustomize

Helm 4 is the application packaging baseline. Shared organization-wide application standards belong in one reviewed Company Application Chart or Helm Library Chart. Copying complete charts between services is prohibited.

Environment composition under `deploy/clusters/*` may use Helm and Kustomize according to the current GitOps model.

Rules:

- chart version and application version are managed independently;
- environment-specific values/overlays are explicit;
- secret values never enter Helm values, Kustomize, Git, images, or rendered CI logs; only secret references are allowed;
- CI runs `helm lint`, renders all required environments, validates Kubernetes schemas/policy, and scans rendered output for secrets;
- rendered manifests are diffed against the deployed/target state where applicable;
- complex migration Helm hooks are prohibited unless the migration has explicit ownership, idempotency, bounded timeout/retry, failure semantics, rollback/fail-forward strategy, and test evidence;
- non-trivial database migrations SHOULD use an explicit controlled migration job/workflow rather than hiding lifecycle semantics inside a chart hook.

## 5. GitOps, artifact promotion, and Argo CD

Current desired-state roots are:

```text
deploy/clusters/staging
deploy/clusters/production
```

Argo CD reconciles reviewed Git desired state. Promotion follows the repository PR-first workflow and immutable image digests, with automated sync, self-heal, prune, `allowEmpty=false`, `PruneLast=true`, and `Prune=confirm` for explicitly destructive critical resources.

The release pipeline MUST promote the exact same signed immutable image digest validated in staging to production. Rebuilding between staging validation and production is prohibited. Artifact/provenance metadata MUST identify the reviewed Git commit.

Production rollback is a reviewed Git revert only when the resulting application/schema/data state is backward compatible. Direct unreviewed production cluster mutation is prohibited.

## 6. Production PostgreSQL — CloudNativePG

ADR-0048, ADR-0053, ADR-0057, ADR-0064, and ADR-0067 define the current database operating model.

Every persistent production microservice runs a dedicated CloudNativePG 1.30.x cluster with the exact approved patch in the Technology Baseline. Each service owns its PostgreSQL database, runtime/migration roles, Flyway history, WAL/PITR backup namespace/credentials/encryption context, and capacity budget. Critical production service clusters use three PostgreSQL instances. Non-production may consolidate physical clusters only while preserving database/credential/role/Flyway ownership isolation.

Clusters use quorum synchronous replication (`ANY 1` equivalent), required durability, and failover quorum. Automatic failover is allowed only when safe promotion can preserve acknowledged synchronous commits; unsafe promotion fails availability rather than risking acknowledged data loss.

Three instances are spread across independent schedulable nodes/failure domains where infrastructure permits.

For each service cluster, aggregate application Hikari `maximumPoolSize` across production pods is capped at <=70% of PostgreSQL `max_connections`; >=30% remains for failover, replication, administration, migrations, and emergency headroom. HPA changes MUST preserve this budget. PgBouncer is introduced only after measured connection pressure and explicit compatibility/load evidence.

The fleet uses one reusable GitOps CloudNativePG baseline, bounded per-service overlays, common recording/alert rules, independent backup identities/artifact namespaces, service-specific restore evidence, one-cluster-at-a-time production upgrade waves, and compatibility-aware rollback/fail-forward rules. A failed upgrade wave stops remaining rollout. Production physical consolidation is not a planned default optimization; any reduction in isolation requires a new reviewed current decision.

## 7. PostgreSQL backup/PITR

Barman Cloud CNPG-I is the PostgreSQL backup/WAL integration. cert-manager supplies the plugin/operator TLS path.

Baseline:

- continuous WAL archive to encrypted off-site object storage;
- daily online physical base backup;
- PostgreSQL RPO <=5 minutes;
- 35-day PITR window;
- monthly retained backup set for 12 months;
- object versioning and object lock/immutability where supported;
- automated backup verification each cycle;
- isolated restore monthly;
- full DR exercise quarterly;
- restore evidence records measured RPO/RTO, integrity checks, source backup identity, and result ownership.

Backup existence is not recovery evidence. Failed restore evidence freezes ordinary affected-service promotion until remediation/revalidation.

## 8. Kafka runtime

Kafka v1 uses KRaft with three brokers and three dedicated controllers for production critical use. Critical topics use RF=3, minISR=2, producer `acks=all`, idempotent producers, and unclean leader election disabled.

Kafka retains native TLS/authentication/ACL/quota controls. It is transport, not business source of truth. Cold DR reconstructs Kafka from GitOps and replays retained service-owned outbox/publication records after PostgreSQL recovery. Critical publication/dedup evidence covers the current 35-day recovery horizon.

## 9. Security Redis

The shared physical `security-redis` deployment is limited to approved security-ephemeral capabilities such as semantic quotas and BFF sessions, with separate ACL identities, key namespaces, quotas, and ownership.

Production baseline is one primary + two replicas + three Sentinel voters, TLS, and `noeviction`. Raw PII/business identifiers are not stored in keys. Quota/session state is not durable business truth and is not part of cold-DR RPO.

If BFF session and semantic-quota workloads materially interfere under load, split them into separate physical Redis deployments before adding Redis Cluster complexity.

## 10. Traefik and Gateway API

Traefik is the external gateway. Kubernetes Gateway API is preferred; Traefik proprietary CRDs are used only for approved capability not representable through Gateway API.

Traefik owns TLS termination/redirect, public routing, edge request-size limits, coarse rate limits, security headers, bounded access telemetry, and weighted routing where approved. Dashboard/insecure API are private/disabled from public access. Public wildcard/catch-all routes are prohibited.

## 11. Dedicated production WAF and upstream DDoS

Public application traffic follows:

```text
Internet / upstream L3/L4 mitigation
-> external load balancing
-> Traefik
-> edge-waf (Caddy + Coraza v3 + OWASP CRS 4.x LTS)
-> web-bff
```

Direct Internet -> BFF and Traefik -> BFF application paths are prohibited by route, NetworkPolicy, and Istio authorization.

WAF rollout starts at PL1 and DetectionOnly for at least seven representative days, then reviewed narrow tuning, then blocking. Automatic CRS updates are prohibited. Large/upload endpoints use explicit bounded request-body policy rather than globally expanding inspection. WAF logs contain bounded rule/disposition evidence, not sensitive bodies/headers.

ADR-0059 requires upstream volumetric DDoS mitigation/scrubbing before traffic can saturate origin capacity. Redundant L4 capacity, bounded connection/handshake limits, reviewed SYN/conntrack controls, emergency coarse limits, provider escalation contacts, and origin-bypass restrictions are production requirements. Coraza is L7 protection, not volumetric mitigation.

## 12. Istio Ambient Mode

Istio Ambient is mandatory for enrolled production application workloads.

```text
trustDomain = prod.sajtech.internal
meshID      = platform-prod
principal   = prod.sajtech.internal/ns/<namespace>/sa/<service-account>
```

Production uses STRICT mTLS, default-deny authorization, and least-privilege ServiceAccount identity. Waypoints exist only for an explicit L7 method/path/header/claim policy or routing/telemetry requirement.

Istio does not replace NetworkPolicy or native Kafka/PostgreSQL/Redis authentication/authorization. Retry/failure policy has one owner; simultaneous mesh + application retry for the same failure is prohibited.

## 13. Istio CA hierarchy

```text
Offline Root CA
-> Production Cluster Intermediate CA
-> Istio workload certificates
```

- Root lifetime: 10 years; key offline and never stored in Kubernetes/OpenBao;
- two encrypted physical root-key copies in separate locations;
- intermediate lifetime: one year;
- rotation begins 90 days before expiry with >=30-day overlap;
- intermediate key exists only in `istio-system`, encrypted at rest and tightly RBACed;
- workload certificate TTL: 24h with automatic rotation.

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

Enrollment is explicit and opt-in. Workload identity/authorization changes require positive and negative policy tests.

## 15. OpenBao and External Secrets

OpenBao 2.6.1 is the authoritative external secret manager under ADR-0037. v1 uses one OpenBao Raft instance/PVC with manual Shamir seal (three shares, threshold two) and hourly encrypted off-PVC snapshots.

Normal application hot paths consume validated mounted/local key material rather than making per-request OpenBao calls. OpenBao remains an operational/control-plane availability dependency for secret refresh/rotation/recovery and therefore requires recovery monitoring and tested snapshot restore.

A move to multi-node OpenBao HA requires measured secret-refresh/recovery/compliance/SLO evidence and a new reviewed current decision.

External Secrets Operator uses Kubernetes Auth and namespace-scoped `SecretStore` by default. Rotating secret/key-ring files are mounted read-only. Secret material is never stored in Git, ConfigMaps, ordinary environment variables for rotating key-ring paths, logs, traces, or metrics.

## 16. Browser/BFF security deployment

ADR-0045 is mandatory for browser production: OIDC Authorization Code + PKCE S256, exact redirects, server-side transaction/session state, secure `__Host-` cookie, Origin + synchronizer-token CSRF protection, exact CORS policy, and centrally tested browser security headers.

## 17. Supply-chain admission and vulnerability response

Cosign signs immutable image digests and required provenance/CycloneDX SBOM attestations. CI generates the SBOM for the final image, indexes it by service + image digest, and correlates it with pinned/approved vulnerability tooling/feed inputs. Kyverno stable image-validation policy verifies production images at admission.

Production workloads use approved registries, immutable digests, valid organization signatures, and approved provenance. Exceptions are narrow, Git-reviewed, owned, and expiring. Expired exceptions stop authorizing new promotion and escalate existing production exposure according to the current vulnerability policy.

Kyverno runs HA with >=3 replicas/PDB/topology spread before fail-closed production admission is enabled.

Scanner/feed success is not proof of zero unknown vulnerabilities; continuous rescanning/threat-intelligence correlation remains required.

## 18. Production human access

Teleport Enterprise Self-Hosted is the privileged human access plane. Normal engineers have no standing production admin/root/database-superuser credentials. Kubernetes/database/host access uses SSO, phishing-resistant MFA, JIT approval, short-lived roles, session/audit evidence, and automatic expiry. Privileged production write elevation requires two reviewers and <=30-minute access. Shared/static kubeconfigs, SSH keys, database passwords, and permanent `cluster-admin` are prohibited.

The access plane resides in a management failure domain so a workload-cluster incident does not remove the supported recovery path.

## 19. Deployment validation and smoke gates

Applicable CI/release checks include:

- unit/integration/architecture/contract/schema compatibility checks before image construction;
- security/static/dependency/secret checks before image construction;
- `buf lint`/breaking compatibility and OpenAPI compatibility where applicable;
- Helm/Kustomize render of all required environments;
- Kubernetes schema/policy/security-context validation;
- rendered-secret scan and deployed/target manifest diff;
- immutable digest/signature/provenance/SBOM verification;
- CloudNativePG/PostgreSQL policy, backup/WAL, and restore-evidence validation;
- Kafka broker/topic durability validation;
- Gateway/Traefik/WAF route and blocking-policy tests;
- `istioctl analyze`, Ambient enrollment, STRICT mTLS, ServiceAccount/NetworkPolicy, and positive/negative authorization tests;
- staging smoke/acceptance/critical browser tests before production promotion;
- production-safe smoke/synthetic checks after promotion.

A required predecessor failure stops downstream promotion. Container/release build occurs only after blocking pre-build tests/scans pass. Smoke failure stops progression and triggers rollback only when rollback is safe for current schema/data state; otherwise the approved fail-forward/incident path applies.

See `PRODUCTION-READINESS-CHECKLIST.md` for implementation/evidence gates. Documentation does not prove runtime compliance.
