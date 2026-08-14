# Runtime and Deployment Architecture — Current State

Exact supported patches belong in Technology Baseline/deployment metadata. This document defines runtime topology, security, deployment, and operational invariants.

## 1. Production workload baseline

Production application workloads run as immutable OCI containers on Kubernetes and require:

- immutable image digest; no `latest`;
- provenance/source identity tied to reviewed Git commit;
- non-root execution / `runAsNonRoot`;
- `allowPrivilegeEscalation: false`;
- Linux capabilities dropped by default; added capability is narrow reviewed exception;
- `seccompProfile: RuntimeDefault`;
- read-only root filesystem where approved image permits it;
- CPU/memory requests and limits;
- distinct startup/readiness/liveness probes;
- liveness does not fail merely because dependency is temporarily unavailable;
- readiness only when workload can safely serve intended traffic;
- graceful shutdown aligned with `terminationGracePeriodSeconds`;
- dedicated least-privilege ServiceAccount; Kubernetes `default` is prohibited for application workloads;
- deny-by-default NetworkPolicy with explicit ingress/egress;
- PDB/topology spread/anti-affinity according to availability target;
- autoscaling from measured valid signals, never as substitute for capacity tests.

Privileged containers, host networking, `hostPath`, extra capabilities, or materially relaxed security context require explicit current security decision and automated policy evidence.

## 2. Kubernetes active-cluster HA

ADR-0022 defines:

```text
3 dedicated stacked control-plane/etcd nodes
>=3 schedulable workers
redundant stable L4 controlPlaneEndpoint
N+1 critical worker capacity
6-hour encrypted off-node etcd snapshots + pre-upgrade snapshot
```

Critical replicas spread across workers/failure domains. External etcd is not a v1 requirement.

## 3. Calico + Istio Ambient

Calico OSS 3.32.x is primary CNI/NetworkPolicy family for Kubernetes 1.35.x; exact patches come from compatibility baseline. v1 uses standard Calico dataplane.

Istio Ambient is service mesh. NetworkPolicy remains independent defense in depth and permits only required HBONE/health/application paths. Production application workloads use STRICT mTLS, dedicated ServiceAccount identities, default-deny/least-privilege authorization, and positive/negative policy tests.

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

Helm 4 is packaging baseline. Shared organization deployment rules belong in one reviewed application/library chart rather than copied charts.

Environment overlays/values are explicit; secret values never enter Git, Helm/Kustomize values, images, or rendered CI logs. CI runs lint/render/schema/policy/secret checks and target/deployed diffs where applicable.

Complex migration Helm hooks are prohibited unless ownership, idempotency, timeout/retry, failure, rollback/fail-forward, and test semantics are explicit. Non-trivial database migrations SHOULD use controlled migration jobs/workflows.

Current GitOps roots:

```text
deploy/clusters/staging
deploy/clusters/production
```

Argo CD reconciles reviewed desired state with automated sync/self-heal/prune, `allowEmpty=false`, `PruneLast=true`, and explicit confirmation for destructive critical resources.

Exact signed immutable image digest validated in staging is promoted to production. Rebuild between staging and production is prohibited. Rollback is reviewed Git state only when application/schema/data compatibility is safe; otherwise use approved fail-forward/incident path.

Immutable application reference bundles such as ADR-0041 are part of the signed service image identity; changing the bundle creates a new reviewed image/release and never mutates production content in place.

## 6. Production PostgreSQL fleet

ADR-0019, ADR-0027, ADR-0034, and ADR-0037 define current model for mutable relational service business state.

Every production microservice with mutable relational business persistence owns dedicated CloudNativePG 1.30.x cluster, PostgreSQL database, runtime/migration roles, Flyway history, storage/capacity budget, WAL/PITR backup identity/namespace, and restore evidence. Critical services use three PostgreSQL instances.

Clusters use quorum synchronous required durability. Automatic failover is permitted only when acknowledged commits can be preserved; unsafe promotion fails availability rather than acknowledged data.

Aggregate application Hikari maxima across production pods stay <=70% of cluster `max_connections`, preserving >=30% for replication/failover, migration, monitoring, administration, and emergency work. PgBouncer is evidence-driven, not default.

Fleet management uses one reusable GitOps baseline with bounded per-service overlays, common bounded alerts, independent backup trust boundaries, monthly service-specific restore evidence, and one-cluster-at-a-time upgrade waves. A failed wave stops remaining rollout. Production physical consolidation requires reviewed current architecture change.

ADR-0040 Compromised Password's immutable rebuildable SQLite reference dataset is not mutable PostgreSQL business state and is outside this fleet. It does not weaken the fleet rule for any mutable service state. ADR-0041 Reference Data uses no database and therefore also has no CloudNativePG/Flyway runtime.

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

## 7. Compromised Password runtime and immutable SQLite dataset

ADR-0040 and `services/compromised-password-service.md` define the self-contained internal runtime:

```text
base package:      com.sajtech.compromisedpassword
namespace:         platform-apps
Deployment:        compromised-password-service
Service:           compromised-password-service
ServiceAccount:    compromised-password-service
application gRPC:  9090
management:        separate configured port
replicas:          >=3
PDB minAvailable:  2
HPA:               evidence-gated only
```

Only the approved `identity-service` workload may reach application gRPC. The service is ClusterIP-only and Ambient-enrolled under strict mTLS. NetworkPolicy + Istio authorization deny every other application caller.

The production compromised-password dataset is a service-local immutable, read-only, rebuildable SQLite artifact. Runtime properties:

- Xerial/SQLite version is exact Technology Baseline pin and final-image SBOM/advisory input;
- dataset path/JDBC URI is server-owned and cannot be selected by request data;
- dataset is opened read-only/query-only;
- runtime INSERT/UPDATE/DELETE/DDL, `ATTACH`/`DETACH`, arbitrary PRAGMA and extension loading are prohibited;
- full dataset is not loaded into JVM heap, application hash map, Bloom authority or Redis/PostgreSQL cache;
- normal lookup is the fixed indexed 20-bit prefix query from ADR-0040;
- no HIBP/external compromised-password provider/Internet lookup egress exists;
- replicas use the same approved dataset version and immutable artifact identity;
- missing/incompatible/corrupt dataset keeps unsafe service unready/fail closed.

The normal root filesystem remains read-only. The dataset mount/path is read-only. If the Xerial native library requires runtime extraction, use only a separate bounded writable ephemeral mount (for example `emptyDir`) dedicated to native extraction/temp use. It contains no password/dataset/source/subject state and is constrained by security context/resource limits. A writable dataset path, `hostPath`, privileged container or arbitrary native-library path is prohibited.

Liveness proves local process/runtime progress. Readiness validates bounded local prerequisites: expected dataset path, read-only open/query capability, supported metadata/schema/version, deployment artifact identity/integrity evidence and security configuration. It does not perform unbounded full-corpus verification on every probe; full integrity/compiler verification belongs to artifact build/release.

Autoscaling is enabled only after representative multi-million-row warm/cold disk-backed load proves a safe signal, bounded SQLite read concurrency/queue and storage capacity. Replica scaling must not hide slow/corrupt storage or change exact-match security semantics.

DR/recovery redeploys/reconstructs the approved immutable dataset artifact and validates it before readiness. SQLite WAL/PITR/runtime migration is not used for this read-only artifact.

### 7.1 Reference Data runtime target and immutable application bundle

ADR-0041 and `services/reference-data-service.md` define a target runtime only after the implementation trigger is met:

```text
service path:      services/reference-data-service
base package:      com.sajtech.referencedata
namespace:         platform-apps
Deployment:        reference-data-service
Service:           reference-data-service
ServiceAccount:    reference-data-service
application gRPC:  9090
management:        separate configured port
replicas:          >=3
PDB minAvailable:  2
HPA:               evidence-gated only
```

Until the trigger/source/deployment/build evidence exists, implementation status remains `PLANNED / NOT VERIFIED` and no deployment is implied by this document.

When implemented, only the approved `web-bff` workload may initially reach application gRPC. Reference Data is ClusterIP-only and Ambient-enrolled under strict mTLS; NetworkPolicy + Istio authorization deny every other application caller unless a later caller-specific architecture/dependency decision registers it.

The Country/Currency/TimeZone/SupportedLocale bundle is a small immutable read-only application resource inside the signed image. It has no PostgreSQL/CloudNativePG/Flyway/SQLite/Redis/Kafka or separately mutable production volume. Startup validates its format/version/source manifest/content digest and may build bounded immutable in-process indexes. A bundle failure keeps readiness false rather than downloading or fabricating data.

Serving has no ISO/IANA/Unicode/CLDR Internet synchronization. Application egress is deny-by-default; only narrowly necessary DNS and approved telemetry may be permitted. Source acquisition occurs only in the reviewed offline release/import process.

Liveness proves local runtime progress only. Readiness validates the locally packaged compatible bundle and security/configuration. It never probes standards-source Internet endpoints.

Recovery/redeploy uses the same approved signed image/bundle or a deterministic approved rebuild; there is no database restore/runtime data repair. HPA remains disabled until representative reference-route load proves a safe signal and capacity envelope.

## 8. Kafka

Production critical Kafka uses ADR-0015:

```text
KRaft
3 brokers + 3 dedicated controllers
critical RF=3 / minISR=2 / acks=all
idempotent producers
unclean leader election disabled
```

Native TLS/authentication/ACL/quotas remain mandatory. Kafka is rebuildable transport, not business truth. Cold DR rebuilds configuration from GitOps and replays/reconstructs service-owned evidence. Critical publication/dedup evidence covers 35-day recovery horizon.

Compromised Password v1 and Reference Data v1 have no Kafka runtime path.

## 9. Security Redis

Approved shared physical `security-redis` is restricted to security-ephemeral capabilities such as semantic quotas and BFF session/pre-auth state:

```text
1 primary + 2 replicas + 3 Sentinel voters
TLS + per-owner ACL/key namespaces
noeviction
```

Raw PII/business/session/pre-auth identifiers are not used as Redis keys where pseudonymous HMAC keys are required. BFF session/pre-auth locators use purpose/version HMAC. BFF session `last_seen` persistence is coalesced to at most once per five-minute activity window. If quota/session workloads interfere materially, split Sentinel deployments before introducing Redis Cluster complexity.

Redis session/quota state is not cold-DR business truth. After state loss users reauthenticate; browser cookies never reconstruct authenticated server state.

Compromised Password and Reference Data v1 do not use Redis as a dataset store/cache/index. Reference Data's HTTP `ETag`/`Cache-Control` policy is representation caching, not Redis server-state fallback.

## 10. Public edge

Production path:

```text
Internet
-> upstream L3/L4 volumetric mitigation/scrubbing
-> redundant external L4 load balancing
-> Traefik
-> dedicated Caddy + Coraza WAF
-> Web BFF
```

Direct Internet -> BFF and Traefik -> BFF application paths are prohibited by route + NetworkPolicy + Istio authorization.

Traefik uses Gateway API by default; proprietary CRDs require explicit capability need. Dashboard/insecure API is not public. Wildcard/catch-all public routes are prohibited.

WAF uses approved Caddy/Coraza/CRS family, PL1, >=7 representative DetectionOnly days before reviewed blocking, narrow versioned exceptions, bounded body policy, no automatic rule updates, and PII-safe telemetry.

Upstream volumetric mitigation is mandatory; WAF is L7 inspection and is not bandwidth-saturation protection.

## 11. Web BFF runtime and egress

ADR-0016 and `services/web-bff.md` define exact browser runtime defaults:

```text
base package:      com.sajtech.webbff
namespace:         platform-apps
Deployment:        web-bff
Service:           web-bff
ServiceAccount:    web-bff
application HTTP:  8080
management:        separate configured port
replicas:          >=3
PDB minAvailable:  2
HPA range:         3..12 only after load/connection evidence
```

`web-bff` uses same immutable/hardened production workload baseline above: non-root, no privilege escalation, default capability drop, `RuntimeDefault` seccomp, read-only root filesystem except explicit writable mounts, bounded resources and graceful termination.

Liveness proves local process/runtime progress only and does not fail on ordinary downstream unavailability. Readiness requires usable local BFF security/session/key configuration and entry-point prerequisites; it does not synchronously probe every Identity/Authorization/Reference Data/resource/provider dependency per health request.

HPA is disabled until representative load evidence covers HTTP/gRPC connection pools, Redis session/quota throughput/failover, AES-GCM/token-broker CPU, Reference Data/resource downstream bulkheads and latency. Autoscaling that would only multiply saturated downstream load is not production-ready evidence.

NetworkPolicy + Istio authorization are deny-by-default. Production BFF egress is restricted to exact required destinations:

- Identity Service;
- Authorization tenant-management surface;
- Reference Data typed read surface when ADR-0041 implementation is active;
- registered resource services referenced by reviewed BFF routes;
- BFF/security Redis;
- configured Google OIDC endpoints;
- approved telemetry backend/collector.

Arbitrary Internet/URL egress is prohibited. Google endpoints are explicit provider-egress exception and must be configured/allow-listed rather than caller-controlled. Every additional synchronous downstream requires canonical dependency-registry entry and corresponding mesh/network policy review before production use.

The browser public namespace is `/api/v1`; current subspaces are `/api/v1/auth`, `/api/v1/identity`, `/api/v1/authorization`, and `/api/v1/reference`. Reference Data v1 GET/HEAD may be anonymous but remains same-origin/edge/WAF protected and uses explicit locale plus deterministic ETag/public one-hour cache semantics. Public body/header bounds and the remaining same-origin/CSP/private-cache behavior are application contracts from ADR-0016/ADR-0041 and are tested independently of edge/WAF limits.

## 12. OpenBao and External Secrets

OpenBao 2.6.1 is exact current secret-authority pin under ADR-0011. v1 uses one Raft instance/PVC, manual Shamir 3 shares/threshold 2, hourly encrypted off-PVC snapshots, and tested restore/unseal.

Normal application hot paths use validated mounted/local key material, not per-request OpenBao RPCs. OpenBao remains security-sensitive control-plane dependency for secret refresh/rotation/recovery.

External Secrets uses Kubernetes Auth and namespace-scoped stores where practical. Rotating key material is mounted read-only. Secret values never enter Git, ConfigMaps, ordinary env vars for rotating key rings, logs, traces, or metrics.

BFF retained-refresh encryption key ring is a dedicated purpose-separated mounted secret. Normal rotation is 90d; old decrypt keys remain through dependent-session lifetime/rekey plus 7d. Reload is atomic. Last fully validated snapshot may bridge secret-source outage <=1h; after that refresh-crypto-dependent operations fail closed. This does not authorize per-request OpenBao RPC or plaintext refresh persistence.

Compromised Password v1 and Reference Data v1 need no runtime provider credential for their reference lookups. Reference Data source provenance/integrity/license evidence is release evidence, not a production source credential.

## 13. Browser security

Browser production follows ADR-0016: same-origin-only v1; OIDC Authorization Code + PKCE S256; exact state/nonce/verifier/pre-auth rules; exact return redirects; server-side HMAC-located transaction/session state; secure `__Host-` cookies; server-owned downstream-audience brokerage; Origin + synchronizer-token CSRF + mandatory `Sec-Fetch-Site:same-origin` for unsafe production browser requests; exact CSP/security headers; auth/OIDC/session/admin `no-store`; bounded request/error profiles. ADR-0041 adds an explicit anonymous safe-method Reference Data facade with deterministic ETag and `Cache-Control: public, max-age=3600`; it does not relax same-origin CORS or unsafe-method controls.

## 14. Supply-chain admission and continuous vulnerability response

Release images are immutable, signed, carry signed provenance and CycloneDX SBOM evidence, and are indexed by deployed digest. Production admission verifies approved registry/digest/signature/provenance/required attestations through HA Kyverno before fail-closed enforcement.

Admission-policy write access is restricted to tightly controlled GitOps/CI identities; application workloads and ordinary service identities cannot author cluster-scoped admission policy. Kyverno CEL HTTP context is disabled unless reviewed policy genuinely needs it. Approved external context lookups use explicit versioned destination/purpose allow-lists, deny loopback/link-local/cloud-metadata/unreviewed private/arbitrary caller-influenced targets, do not forward credentials to arbitrary destinations, and use bounded timeout/response/failure semantics. NetworkPolicy and positive/negative SSRF tests enforce egress contract; lookup failure never silently becomes allow.

Vulnerability inventory is continuously rescanned/correlated with approved threat/advisory inputs. Exceptions are exact, owned, reviewed, expiring; expiry stops new promotion and escalates production exposure. No scanner/feed proves absence of unknown vulnerabilities and no scan result authorizes unsigned artifact.

For Compromised Password, final-image SBOM/advisory correlation includes both the Xerial Java artifact and its bundled native SQLite engine. Driver/native/dataset-format upgrades require compatibility evidence before rollout.

For Reference Data, the signed image/provenance additionally binds the approved immutable reference-bundle identity/source-revision manifest/content digest. Source-data revision changes are reviewed release inputs even though they are not Technology Baseline runtime pins.

## 15. Human production access

Teleport Enterprise Self-Hosted is privileged human access plane. No standing production admin/root/database-superuser credentials are permitted. Kubernetes/database/host access uses SSO, phishing-resistant MFA, JIT approval, short-lived roles, audit/session evidence, and automatic expiry. Privileged write elevation requires two reviewers and <=30-minute access.

## 16. Deployment validation

Applicable pre-promotion evidence includes:

- code/unit/integration/architecture/contract/schema/security checks;
- dependency/secret/vulnerability verification;
- Buf/OpenAPI compatibility;
- Helm/Kustomize rendering and Kubernetes security/schema/policy checks;
- rendered-secret and manifest-diff checks;
- immutable digest/signature/provenance/SBOM verification;
- Kyverno policy-authoring RBAC and policy-engine egress/SSRF positive/negative tests when applicable;
- PostgreSQL/CloudNativePG backup/restore/upgrade-policy checks for mutable relational state;
- Compromised Password dataset compiler/integrity/schema/bounds/read-only/path/no-write/no-external-egress/Xerial-native/load/rebuild checks when affected;
- Reference Data implementation-trigger, offline source provenance/license/integrity/import determinism, bundle manifest/digest/canonicalization/locale/bounds, no-database/broker/runtime-source egress, typed gRPC/BFF cache, workload-policy/load/rebuild checks when affected and implementation exists;
- Kafka durability/replay checks;
- Gateway/Traefik/WAF route and blocking tests;
- `istioctl analyze`, Ambient/STRICT mTLS/ServiceAccount/NetworkPolicy/authorization positive and negative tests;
- Web BFF exact ingress/egress, request-bound, session/Redis/key-ring/token-broker/Reference Data/CSRF/CORS/Fetch-Metadata/CSP/cache/quota/erasure render+integration tests when affected;
- staging smoke/acceptance/critical browser tests;
- production-safe smoke/synthetic checks.

A required predecessor failure stops downstream promotion. Documentation is not runtime evidence; detailed gates live in `PRODUCTION-READINESS-CHECKLIST.md`.
