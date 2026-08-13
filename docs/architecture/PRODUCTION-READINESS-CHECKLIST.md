# Production Readiness Checklist — Current State

This checklist tracks **implementation and executable evidence**, not architecture discovery. Current retained ADRs/current-state documents define the target. A missing/failed gate is never permission to redesign or bypass the target through configuration.

For each applicable item:

```text
Architecture: DECIDED
Implementation: REQUIRED
Evidence: NOT VERIFIED until executed/measured
```

Production configuration MUST NOT bypass a failed gate.

## 1. Kubernetes active-cluster HA — ADR-0022

Required evidence:

- 3 healthy stacked control-plane/etcd nodes;
- stable redundant L4 API endpoint;
- >=3 schedulable workers;
- critical replica spread across failure domains;
- one control-plane and one-worker loss/drain tests;
- N+1 critical-path worker capacity;
- encrypted off-node etcd snapshots + tested restore/rebuild.

Status until verified: **platform HA production blocker**.

## 2. Semantic quotas — ADR-0024

Required evidence:

- Redis 1-primary/2-replica/3-Sentinel topology across failure domains;
- TLS/ACL/key-namespace isolation + `noeviction`;
- one atomic multi-dimension quota operation with no partial consumption;
- trusted app time + Redis `TIME`, <=2s skew, monotonic effective time, no TTL security reset;
- HMAC pseudonymous keying/rotation without budget reset;
- authentication/MFA/recovery anti-lockout + non-enumeration tests;
- Redis outage/failover fails protected operations closed without converting dependency failure into false quota denial;
- production profiles cannot bypass the limiter;
- >=2x projected peak with p95<=10ms, p99<=25ms, 75ms ceiling, >=30% memory headroom, zero eviction.

Status until verified: **blocker for semantic-quota-protected production entry points**.

## 3. Online Authorization — ADR-0013/0026/0032/0036

Required evidence:

- >=3 replicas, PDB `minAvailable=2`, topology spread;
- availability >=99.95%; p95<=100ms/p99<=200ms at >=2x projected peak;
- exact 300ms/one-attempt/wait-for-ready-off/no-cache/no-retry/no-fallback behavior;
- bounded global/per-caller concurrency and no unbounded queue;
- current 50%-window/five-consecutive breaker opening behavior;
- repeated OPEN durations de-correlate with bounded reopen backoff;
- HALF_OPEN permits one real `CheckPermission` probe in flight; three consecutive infrastructure-successful probes close; infrastructure failure/overload reopens;
- health endpoint cannot close the breaker; tenant/commercial tier does not alter breaker semantics;
- `dependency-criticality.yaml` schema/coverage/render checks pass;
- Hikari acquisition p99<25ms, acquisition ceiling<=50ms, permission SQL ceiling<=100ms;
- no synchronous downstream other than Authorization-owned PostgreSQL;
- no routine duplicate BFF permission check;
- one replica/node loss and PostgreSQL primary failover preserve fail-closed semantics/objectives.

Status until verified: **protected-operation production blocker**.

## 4. PostgreSQL isolation/HA/recovery — ADR-0019/0027/0034/0037

Required evidence per persistent service:

- dedicated production CloudNativePG cluster/database/runtime+migration roles/Flyway history/backup identity;
- 3 instances for critical clusters across independent schedulable failure domains where possible;
- synchronous acknowledgement from one failover-eligible replica for required durable writes;
- safe automatic failover; planned/unplanned failover evidence, ordinary target <=60s when durability is preserved;
- negative cross-service `CONNECT`/object privilege tests;
- forced tenant RLS; runtime roles `NOSUPERUSER NOBYPASSRLS` and non-owner;
- tenant context comes only from validated authenticated context, is parameterized and transaction-local, and pooled-connection reuse across commit/rollback cannot leak a prior tenant into a later borrower;
- missing/malformed tenant context and deliberately missing application tenant predicates fail closed in cross-tenant negative tests;
- aggregate application Hikari maxima <=70% `max_connections`;
- continuous WAL archive measured against RPO<=5m;
- encrypted off-site daily physical base backup + 35-day PITR;
- monthly retained artifacts for 12 months where policy requires;
- monthly isolated restore + quarterly full DR;
- restore record includes backup/WAL identity, requested/actual timestamp, RPO/RTO, versions/Flyway, integrity/RLS/erasure checks, runbook revision, owner, PASS/FAIL;
- dashboard exposes last restore/RPO/RTO/next due/overdue/failed state;
- failed monthly restore freezes ordinary affected-service promotion until replacement drill passes;
- upgrade waves stop on staging/production failure; reversible state only rolls back when supported; irreversible/major changes never use unsafe downgrade;
- Notification acknowledged `DISPATCHING` survives every permitted automatic failover with no blind redispatch.

Status until verified: **platform/data production blocker**.

## 5. Kafka durability/rebuildable DR — ADR-0015

Required evidence:

- 3 dedicated controllers + 3 brokers;
- critical RF3/minISR2/unclean leader election disabled;
- critical producers `acks=all` + idempotence;
- TLS/authenticated per-service principals/ACLs/quotas;
- event classes explicitly `OUTBOX_REPLAYABLE`, `RECONSTRUCTABLE`, or `NON_CRITICAL`;
- replayable critical publication evidence + participating consumer dedup evidence cover 35 days;
- clean Kafka can be rebuilt from Git and critical flows replayed/reconstructed;
- quarterly representative reconstruction/replay exercise.

Status until verified: **critical async-flow blocker**.

## 6. Browser/BFF security — ADR-0016

Required evidence:

- OIDC Authorization Code + PKCE S256, state/nonce replay/mismatch negatives;
- exact redirect and open-redirect negatives;
- provider validation occurs in BFF before Identity invocation; no direct Identity->Google login/link dependency;
- provider authorization code/tokens do not enter Identity; BFF->Identity uses the bounded short-lived single-use evidence contract from ADR-0012;
- evidence expiry/replay/wrong-workload-identity negatives;
- secure `__Host-sajtech-session` + fixation/rotation tests;
- password+MFA pre-auth cannot become a completed browser session before successful MFA;
- server-side session + encrypted refresh-credential handling where used;
- Origin + synchronizer-token CSRF positives/negatives;
- same-origin/default-deny CORS;
- CSP/HSTS/nosniff/referrer/Permissions-Policy/frame checks;
- browser/storage/service-worker inspection proves no provider/internal token leakage or private authenticated cache.

Status until verified: **public-internet blocker**.

## 7. Supply chain + vulnerability response — ADR-0017/0035/0038

Required evidence:

- final-image CycloneDX SBOM, signed provenance, vulnerability result, Cosign signature/attestations for immutable digest;
- exact same signed digest staging -> production;
- Kyverno stable image-validation policy with >=3 replicas/PDB/spread before fail-closed mode;
- audit rollout before production deny enforcement;
- unsigned/wrong-signer/wrong-provenance/mutable-tag-only/unapproved-registry negatives;
- only tightly controlled GitOps/CI identities can create or modify cluster-scoped admission policy; application/service identities are denied;
- Kyverno CEL HTTP context is disabled where unnecessary; any approved lookup has exact destination/purpose allow-list, bounded timeout/response/failure behavior, no arbitrary credential forwarding, and NetworkPolicy-constrained egress;
- loopback, link-local/cloud-metadata, unreviewed private-network, and arbitrary caller-influenced SSRF destination negatives pass; external-context failure cannot silently become allow;
- no unsigned emergency bypass;
- advisory/KEV ingestion <=2h + targeted affected-digest rescan;
- full deployed inventory rescan <=6h;
- known-exploited/Critical production findings page Security + owner with <=24h mitigation target; High <=48h;
- expired exceptions stop promotion immediately and escalate production exposure;
- transitive findings route to deployed-artifact owner; shared base/runtime findings route to Platform + consumers;
- stale required feed/scanner state fails promotion closed.

Status until verified: **production deployment-security blocker**.

## 8. Notification runtime — ADR-0006/0007/0014/0018/0020

Required evidence:

- local AES-256-GCM key-ring rotation/historical decrypt/refresh/corruption/staleness/readiness;
- no OpenBao RPC on acceptance/dispatch/retry/reconciliation hot paths;
- no application clock-health sidecar/Chrony `hostPath`/dispatch-fence control plane in desired state;
- PostgreSQL-authoritative deadline boundaries;
- request replay/fingerprint conflict behavior;
- crash before/after durable `DISPATCHING` commit;
- database failover around dispatch commit;
- unknown/stale `DISPATCHING` reconciles, never blind resend;
- exact-content retry/terminal immutability/escrow erasure;
- Liara SMTP STARTTLS/auth/outcome classification;
- IPPanel accepted/report fixtures, ambiguity/no blind retry, bounded polling/backpressure;
- local logging SMS impossible in staging/production.

Status until verified: **Notification production blocker**.

## 9. OpenBao recovery — ADR-0011

Required evidence:

- exact 2.6.1 image digest;
- Shamir 3-share/2-threshold custody/access runbook;
- encrypted hourly snapshot outside primary PVC + pre-upgrade snapshots;
- restore + manual unseal exercise;
- External Secrets/key refresh staleness alerting and fail-closed behavior after allowed local-key staleness;
- recovery fits platform RTO sequence;
- Istio Root CA private key absent from Kubernetes/OpenBao.

Status until verified: **secret-platform blocker**.

## 10. WAF + upstream DDoS — ADR-0001/0029

Required evidence:

- only public application path is upstream L3/L4 mitigation/scrubbing -> redundant external L4 load balancing -> Traefik -> edge-waf -> Web BFF;
- direct bypass negative tests;
- replicated WAF/placement according to current HA target;
- >=7 representative DetectionOnly days + reviewed narrow exceptions;
- blocking-mode load/latency + endpoint body-limit tests;
- no sensitive request/response logging;
- upstream L3/L4 mitigation provider capability/escalation/runbook;
- origin-bypass/connection-pressure controls and authorized saturation exercise.

Status until verified: **public-internet blocker**.

## 11. Iran SMS / SMS MFA — ADR-0020

Required evidence:

- IPPanel Webservice sandbox fixture pins definitive accepted correlation field/type;
- recipient-level report fixture pins current status mappings;
- exact Notification-rendered text; no provider-managed Pattern authority;
- dedicated OpenBao credential, 90-day rotation/emergency revocation, bounded egress;
- 500ms connect / 1500ms total / no automatic HTTP retry;
- timeout/connection loss/malformed/unproven acceptance -> `AMBIGUOUS`, never blind resend;
- bounded report polling/backpressure;
- local logging adapter cannot activate in production;
- SMS MFA additionally passes ADR-0024 quotas and current Identity MFA/session gates.

Status until verified: **SMS-dependent feature blocker; unrelated Email-only capabilities may proceed independently**.

## 12. Platform compatibility / CNI / immutable artifacts — ADR-0021

Required evidence:

- every deployed image pinned by immutable digest;
- Technology Baseline + compatibility matrix revalidated against upstream support/security at release time;
- Kubernetes/Istio Ambient/Calico positive/negative flows including HBONE/health;
- CloudNativePG/cert-manager/Kyverno/Traefik/Gateway API/WAF render/compatibility checks;
- `istioctl analyze`, Helm/Kustomize/Kubernetes policy checks;
- staging/production desired state renders without secret values;
- rollback artifacts/digests remain available;
- unsupported/EOL components are replaced by supported compatible baseline before rollout.

Status until verified: **platform release blocker**.

## 13. JWT signing-key lifecycle — ADR-0023

Required evidence:

- RSA-3072/RS256 private signing keys only in Identity local/OpenBao delivery boundary;
- next public key deployed/verified before activation;
- local GitOps verifier bundle reloads atomically;
- exact v1 claim allow-list (`iss`,`aud`,`sub`,`jti`,`iat`,`exp`,`tenant_id`,`membership_id`,`sid`), no role/permission snapshot authority, wildcard-audience rejection;
- algorithm-confusion/unknown-kid/issuer/audience negatives;
- 90-day normal rotation + emergency compromise exercise;
- no normal verification call to Identity/OpenBao/remote JWKS;
- private-key Git/telemetry leak tests.

Status until verified: **authentication-trust blocker**.

## 14. Java/source/build/CI — ADR-0039

For each Java service:

- independent `settings.gradle.kts`/`build.gradle.kts`/Wrapper/dependency verification from clean checkout on Java 25;
- applicable test/integration/contract/architecture tasks exist;
- Spotless, SpotBugs, ArchUnit, repository Semgrep pass;
- custom static rules have positive/negative fixtures and no broad suppression;
- GitHub Actions required checks use least privilege and pinned third-party actions;
- source actually satisfies Java/SQL/deployment standards;
- promotion uses same previously built/signed digest;
- mandatory checks are not disabled/`ignoreFailures`/blanket-excluded.

Status until verified: **Java implementation/release blocker**.

## 15. Frontend/source/browser quality

For affected frontend releases:

- Prettier/ESLint/type-aware strict TypeScript/typecheck pass;
- no unsafe token storage or service-worker private caching;
- generated OpenAPI client contract is current;
- unit/component/accessibility tests pass;
- critical Playwright flows pass without fixed-sleep/flaky-retry masking;
- route bundle/performance budget passes;
- browser security/headers/session behavior passes.

Status until verified: **frontend release blocker when applicable**.

## 16. Identity Service repository-complete evidence — ADR-0009/0012/0023/0028

Required repository/build evidence includes:

- versioned feature-scoped Protobuf + Buf compatibility for registration/auth/session/tenant/invitation/external-identity/MFA/erasure entry points;
- server-owned IDs/TTLs/security policy and typed bounded gRPC errors;
- profile/contact canonicalization, verified global uniqueness/reservation, primary-contact and invitation concurrency rules;
- exactly eight-digit registration challenge, HMAC-only persistence, 10m TTL, five failed attempts, 60s resend spacing, replacement invalidation, single use, registration/resend/confirm semantic quotas and non-enumeration;
- explicit aggregate/transaction boundaries, JPA aggregate CRUD plus justified JDBC/jOOQ SQL-control paths, no remote I/O in transactions;
- compromised-password prefix-only outbound contract: raw password remains in Identity, 900ms/one-attempt/no-retry/fail-closed behavior;
- exact JWT claims/audience plus refresh-family rotation/reuse behavior;
- BFF-only provider validation, one-time evidence handoff, no Identity->Google path;
- password+TOTP/recovery pre-auth gate with no access/refresh issuance before MFA completion;
- tenant invitation existing-user target/7d/single-pending/acceptance-ownership rules;
- HMAC-versioned idempotency replay/conflict behavior, 35d critical publication/Inbox-dedup evidence, >=14d retry/DLQ evidence when used, >=365d security audit evidence;
- erasure server-owned required participant registry, Kafka/outbox/inbox replay, non-PII receipts, legal-hold ACTIVE->RELEASED, restore-before-traffic reconciliation;
- Identity Docker/Helm/GitOps/ServiceAccount/NetworkPolicy/Istio/probe/replica/PDB/topology/security-context/render checks and CI gates.

Repository-complete does **not** equal production-ready. Registry/DNS/secret paths/provider credentials/Redis/CNPG/backup/alert destinations may remain typed environment placeholders, but actual staging/production provider, secret, cluster, load, failover, restore, and DR evidence remains `NOT VERIFIED` until executed.

Status until verified: **Identity repository implementation/evidence blocker; external production evidence remains independently blocking**.

## 17. Final release evidence

The exact candidate additionally passes applicable critical load/SLO, Authorization/Redis/PostgreSQL/Kafka/WAF/provider capacity, node/replica/database failover, security-negative/workload-identity, backup/PITR/restore, smoke/BDD/critical Playwright, rollback/fail-forward, and error-budget release-policy checks.

Until actual service source/build/workflows/manifests and these checks exist/pass, implementation evidence remains **NOT VERIFIED**.