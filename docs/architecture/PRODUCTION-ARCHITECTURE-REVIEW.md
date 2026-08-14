# Production Architecture Review — Current State

- **Reviewed:** 2026-08-14
- **Status:** architecture target accepted; implementation evidence is not implied
- **Documentation mode:** current-only

## Outcome

The current v1 architecture is coherent as the repository production target, subject to the executable implementation/evidence gates in `PRODUCTION-READINESS-CHECKLIST.md`.

The design favors strong correctness/security with bounded operational complexity: add redundancy where loss would violate user/security/data guarantees, and avoid extra synchronous services, request-path secret-manager RPCs, duplicate retry layers, or bespoke distributed coordination without measured need.

## Current architecture conclusions

- Identity owns registration, credential/MFA/session/token-signing concerns; external identities bind by issuer+subject and browser credentials remain BFF-managed. Identity also owns the internal exact-audience token-broker operation used only by authorized BFF workload against active Session/RefreshFamily state and server allow-listed audience.
- Web BFF is the only browser-facing application API boundary. Its v1 contract explicitly fixes `/api/v1` public namespace, bounded request/error handling, exact OIDC state/nonce/PKCE/pre-auth/redirect rules, trusted provider evidence, server-owned audience brokerage, HMAC-located Redis session/pre-auth state, 7d-idle/30d-absolute sessions with five-minute last-seen write coalescing, atomic no-grace rotation, user-session revocation index, AES-256-GCM retained-refresh key lifecycle, tenantless onboarding isolation, exact CSRF+Origin+Fetch-Metadata enforcement, same-origin-only CORS, exact CSP/no-store policy, OIDC quotas, erasure, runtime and deny-by-default egress.
- Browser receives no provider/Identity/downstream access or refresh credentials and cannot choose arbitrary JWT audiences. `authenticated_onboarding` cannot obtain ordinary resource or Authorization-management audience.
- Final protected-resource authorization remains in the resource-owning service; Web BFF tenant administration is transport/facade, not an authorization authority.
- Authorization remains the online authoritative tenant-permission boundary with no permission cache/Kafka invalidation/stale fallback/retry. Its permission catalog is exact/versioned/non-reused; SYSTEM/custom Role and direct-override semantics are bounded; management is BFF-facaded but locally authorized in Authorization; privilege escalation is denied; owner-role mutation shares atomic safety with Identity Membership-removal reservations; platform capability checks are separate Identity-only fail-closed authority and never tenant/resource bypass.
- Authorization uses jOOQ/JDBC with forced tenant RLS and bounded query plans; management/platform idempotency/audit and erased-subject authority removal are explicit current contracts.
- Authorization has explicit SLOs, fair overload isolation, HA/capacity gates, burn alerts, and de-correlated real-contract breaker recovery.
- Semantic security quotas remain service-owned and atomically enforced in isolated Redis; no quota microservice is introduced. Exact current policies include Identity registration, Web BFF OIDC start/callback, and Authorization bounded semantic-mutation administration cost.
- Compromised Password remains an independent internal security reference-data bounded context. Identity computes SHA-256 locally and sends only the 20-bit/five-uppercase-hex prefix; Compromised Password performs one bounded exact indexed lookup against an immutable read-only embedded SQLite artifact and returns suffix/count candidates; Identity retains the full hash and final credential decision.
- Compromised Password v1 has no HIBP/Pwned Passwords or other runtime external provider/API, no Redis/PostgreSQL/Kafka dataset path, no full-dataset JVM cache and no User/Tenant/Contact/session state. Dataset/source updates occur only through an offline reviewed compiler/release process.
- The ADR-0040 SQLite artifact is deliberately classified as immutable, rebuildable reference data rather than mutable service business persistence. It is a narrow exception to PostgreSQL/Flyway/CloudNativePG rules only for this dataset and cannot be generalized to mutable SQLite business state without a new current decision.
- Notification owns durable human-channel delivery. Sensitive retry state uses bounded local AES-GCM key rings rather than request-path OpenBao Transit. PostgreSQL-authoritative time and durable `DISPATCHING` commit replace bespoke clock/fence coordination.
- Production Notification providers are Liara Transactional Email and IPPanel Webservice-mode Iran SMS. Provider ambiguity is explicit and never converted to fabricated success or blind resend.
- Every production microservice with mutable relational business persistence owns distinct PostgreSQL database/credentials/Flyway history and dedicated CloudNativePG cluster; tenant-owned tables use forced RLS.
- Kafka is replicated rebuildable transport, with transactional outbox/idempotent consumer semantics and 35-day critical recovery evidence.
- Browser traffic follows upstream L3/L4 volumetric mitigation/scrubbing -> redundant external L4 load balancing -> Traefik -> Caddy/Coraza WAF -> Web BFF; internal traffic uses Istio Ambient strict mTLS + workload identity + least-privilege authorization. BFF egress is additionally restricted to its registered Identity/Authorization/resource/Redis/Google/telemetry dependencies. Compromised Password accepts only Identity ingress and has no application provider/Internet lookup egress.
- GitOps, signed/provenanced immutable artifacts, admission verification with least-privilege policy authoring and bounded policy-engine egress/SSRF controls, continuous SBOM/advisory correlation including bundled native components, PII-safe telemetry, and JIT privileged access form production security/operations baseline.
- Java/source quality uses canonical coding standard plus executable Spotless, SpotBugs, ArchUnit, Semgrep, dependency verification, contract, test, and GitHub Actions gates where implementation exists.

## Main bottlenecks and failure domains

Highest-risk current capacity/availability boundaries are:

1. Authorization service + PostgreSQL query/pool path;
2. Web BFF fan-out/session Redis/token-broker/crypto/downstream pool path;
3. per-service PostgreSQL HA fleet capacity, synchronous-write latency, backup/restore load, and upgrade/operations overhead;
4. security Redis latency/failover for semantic quotas and BFF session/pre-auth state;
5. password-hash CPU/memory under login/credential attack load;
6. Compromised Password SQLite disk-backed prefix lookup/storage I/O with multi-million-row datasets;
7. WAF inspection cost on every public request;
8. Kafka disk, broker, partition, consumer-lag, and replay capacity;
9. Liara/IPPanel latency/throttling and IPPanel report polling/reconciliation;
10. Kubernetes worker capacity/replica placement during node loss;
11. external upstream DDoS/provider/identity dependencies outside cluster.

Metric, mitigation, and scale/split triggers are maintained in `performance-and-bottlenecks.md`.

## Deliberately absent complexity

The current production target intentionally does **not** add:

- a duplicate routine Authorization check in BFF for ordinary protected resource calls;
- a permission-result cache or Kafka invalidation path;
- browser-selected arbitrary downstream audience or public generic token-exchange endpoint;
- downstream access/refresh/provider token exposure to browser storage/cookies/URLs;
- cross-origin credentialed CORS in v1;
- a dual-valid grace window for BFF session rotation;
- reconstruction of BFF authenticated state from browser cookies after Redis/session loss;
- a separate CSRF cookie;
- caller-supplied Role/permission/owner snapshots as authorization authority;
- wildcard/custom Role inheritance or resource-expression policy in v1;
- a successful `allowed=false` permission-result shape;
- a public/admin permission-explanation endpoint;
- a quota microservice;
- a runtime Schema Registry in v1;
- Notification per-message OpenBao RPCs;
- a bespoke Notification clock-health agent, Chrony `hostPath` sidecar, or dispatch-fence coordinator;
- per-request remote JWKS lookup for normal internal token verification;
- BFF per-request OpenBao RPC for refresh-key use;
- HIBP/Pwned Passwords or another external compromised-password lookup on production request path;
- Redis/PostgreSQL/Kafka as a second Compromised Password dataset store/cache;
- loading the complete Compromised Password corpus into application JVM heap or using a Bloom filter as final authority;
- runtime mutation/migration/DDL of the Compromised Password SQLite artifact;
- PgBouncer/Redis Cluster/external etcd without measured need;
- an Istio waypoint for every service/namespace;
- retries in both application and mesh/client layers for one failure;
- Argo CD/OpenBao request-path HA merely for symmetry when hot paths do not depend on them.

These omissions are intentional current decisions, not unresolved historical alternatives.

## Security review

Current security boundaries are coherent only when enforced together:

- trusted tenant/user/workload identity is derived/validated at correct boundary;
- internal services are not directly Internet-exposed;
- upstream volumetric protection and redundant external load balancing precede Traefik/WAF; no public route bypasses WAF;
- WAF does not replace authentication/authorization/validation/semantic quotas or BFF body/header bounds;
- BFF OIDC state/nonce/PKCE/pre-auth are server-bound/single-use and return redirects cannot become external/authority-relative through raw or encoded bypasses;
- browser never obtains provider/Identity/downstream credentials or arbitrary audience authority;
- BFF session/pre-auth Redis keys are purpose-HMAC locators, raw IDs are not logged, and refresh credentials are AES-GCM encrypted under bounded rotating local key ring;
- unsafe cookie-authenticated production browser requests require exact Origin + CSRF + `Sec-Fetch-Site:same-origin`; v1 does not enable cross-origin credentialed CORS;
- exact CSP forbids `unsafe-inline`/`unsafe-eval`; sensitive auth/session/admin responses are no-store;
- BFF erasure/revocation removes usable user-linked authentication continuation and session index state;
- Compromised Password receives only five uppercase SHA-256 prefix hex characters from Identity; raw password/full digest/subject identity never enters the service and exact full-hash matching remains in Identity;
- Compromised Password SQLite path/JDBC/query/PRAGMA/extension/ATTACH authority is server-owned, runtime is read-only/query-only, result bounds are build-enforced, and any corrupt/unavailable/oversized lookup fails closed rather than becoming a false clean result;
- Compromised Password workload accepts only Identity and has no arbitrary application Internet/provider egress; Xerial Java + bundled SQLite native engine remain SBOM/advisory inputs;
- Istio identity does not replace NetworkPolicy or native datastore authentication;
- BFF egress is deny-by-default and cannot become arbitrary URL/Internet SSRF path;
- local reject-only Authorization prechecks cannot grant permission;
- Authorization permission identifiers are lifecycle-governed/non-reused; actors cannot grant authority they do not possess; platform capability is separate from tenant permission and cannot bypass resource/domain invariants;
- last-owner protection is atomic across Identity removal reservations and local owner Role changes;
- erased subjects cannot retain Membership/direct-override/platform-profile authority;
- sensitive material never enters Kafka/logs/traces/metrics/raw provider telemetry;
- production secrets are not committed to Git/Helm values/images;
- production workloads use hardened security contexts and independent ServiceAccounts;
- admission-policy authoring is least privilege and policy-engine external context cannot become unrestricted SSRF-capable egress;
- signed artifact admission does not replace continuous vulnerability response;
- no vulnerability feed/scanner is considered proof that unknown vulnerabilities do not exist.

## Reliability review

- All synchronous dependencies have finite deadlines and bounded concurrency.
- Retry is safe/idempotent and single-owner only.
- Web BFF request budget remains 2600ms with stricter child ceilings; cancellation propagates and authoritative BFF dependencies do not gain hidden retry/fallback.
- BFF session Redis failure does not reconstruct authentication; semantic-quota Redis failure does not disable OIDC abuse controls; token-broker/Authorization/resource failures do not fabricate credentials/authority/business state.
- BFF access-JWT reuse, if any, is bounded transport reuse only until token expiry/current session state and never replaces final resource Authorization.
- BFF session `last_seen` write coalescing limits Redis amplification; user-session index bounds global revocation/erasure; HPA remains gated on representative downstream/Redis/crypto load evidence.
- BFF last-valid refresh key-ring snapshot bridges source outage for <=1h only; after that key-dependent operations fail closed.
- Authorization `CheckPermission` and `CheckPlatformPermission` are one-attempt fail-closed authority calls with no cache/retry/fallback; platform-check outage blocks only operations requiring platform authority rather than fabricating it.
- Compromised Password is a one-attempt <=900ms authoritative-security dependency for password credential writes. Missing/corrupt/incompatible dataset, SQLite read/storage failure, malformed/oversized result, queue/concurrency saturation or deadline expiry rejects the unchecked password; no runtime provider/cache fallback exists.
- Compromised Password availability uses >=3 replicas with identical approved immutable dataset version rather than mutable DB replication. Cold DR redeploys/rebuilds the artifact and withholds readiness until compatibility/integrity is valid.
- Remote I/O is not performed inside database transactions; Identity calls Compromised Password outside its DB transaction and Authorization administration quota is intentionally evaluated before PostgreSQL mutation transaction.
- Kafka publication uses transactional outbox and at-least-once consumers are idempotent.
- CloudNativePG failover must preserve required durable commits or refuse unsafe failover.
- Restore evidence, not backup existence, proves recovery capability; immutable reference artifact recovery is separately proven by rebuild/redeploy evidence.
- Release rollback is allowed only when schema/data/runtime state is backward compatible; unsafe database downgrade is not used to satisfy arbitrary rollback timer.
- Error-budget/burn policy, chaos tests, and failover/load evidence remain production gates.

## Delivery-speed guardrail

Local development remains smaller than production. Pure Domain/Application work should run without Kubernetes/Istio/WAF/Kafka HA. Integration work uses pinned local kind/Ambient/Traefik/WAF foundation only when integration behavior is actually under test. Heavy load, failover, backup/PITR, DR, provider, certificate, and production-policy evidence belongs to staging/release/scheduled pipelines.

Web BFF local/PR work can test OIDC/session/CSRF/audience/error/request-bound rules with deterministic adapters/Testcontainers and generated OpenAPI contracts; real mesh/WAF/HA/provider/load evidence remains staging/release/scheduled. Compromised Password local/PR work uses deterministic generated SQLite fixtures and offline compiler checks; production corpus/source material is not required for the inner loop. Production complexity is not required merely to run the inner loop.

## Coding-quality review

Coding baseline incorporates feature-first/nature-separated packages, strict package naming, Domain/persistence separation, constructor injection, bounded files/responsibilities, no dumping-ground packages, explicit transaction/deadline/retry/idempotency rules, PII-safe telemetry, hardened container/Kubernetes settings, Helm migration discipline, and immutable same-digest staging-to-production promotion.

Authorization's jOOQ/JDBC-only implementation decision does not relax Hexagonal boundaries: generated SQL types remain Infrastructure-only and critical permission queries require plan/index evidence.

Web BFF's audience-token/session/crypto/provider adapters remain Infrastructure boundaries; browser transport DTOs/OpenAPI models do not become Identity/Authorization Domain models, and the BFF must not duplicate backend business invariants.

Compromised Password's Xerial/JDBC/SQLite schema/query/native extraction details remain Infrastructure/offline-compiler concerns. Domain/Application code owns reference lookup meaning and bounds but does not depend on SQLite/JDBC. The immutable SQLite exception cannot become a general repository shortcut around PostgreSQL/Flyway rules.

Machine-checkable rules should be executable. Documentation-only presence is not source compliance.

## Evidence gap

The architecture review does **not** claim that implementation already satisfies these rules. Until service source/builds, Gradle wrappers/locks, workflows, manifests, policy tests, scans, dataset compiler/artifact evidence, multi-million-row SQLite load tests, failover/rebuild/restore exercises, and deployment evidence exist and pass, those implementation dimensions remain `NOT VERIFIED`.
