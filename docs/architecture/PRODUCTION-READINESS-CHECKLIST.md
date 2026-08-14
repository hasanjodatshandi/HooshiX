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
- exact registration policy tests: REGISTER contact `5, 1/15m, 24h`, REGISTER network `60, 1/5s, 1h`, RESEND contact `5, 1/10m, 2h` plus fixed 60s challenge gap, RESEND network `60, 1/5s, 1h`, CONFIRM network `120, 2/1s, 30m` plus challenge-local five-proof cap;
- authenticated Contact verification/recovery/MFA operation namespaces cannot share quota keys with registration despite reusing approved numeric envelopes;
- Web BFF exact OIDC browser-protocol buckets: `OIDC_START/network` `60, 1/5s, 1h` and `OIDC_CALLBACK/network` `120, 2/1s, 30m`, domain-separated from Identity `GOOGLE_LOGIN`, plus independent max five live pre-auth transactions/browser;
- Authorization `AUTH_ADMIN_WRITE` uses actual semantic-mutation cost `max(1, count)` with count<=100, `ReplaceRolePermissions` cost is additions+removals, both dimensions consume same cost atomically before DB work, and later DB failure does not refund quota;
- authentication/MFA/recovery anti-lockout + non-enumeration tests;
- Redis outage/failover fails protected operations closed without converting dependency failure into false quota denial;
- production profiles cannot bypass limiter;
- >=2x projected peak with p95<=10ms, p99<=25ms, 75ms ceiling, >=30% memory headroom, zero eviction.

Status until verified: **blocker for semantic-quota-protected production entry points**.

## 3. Online Authorization — ADR-0013/0026/0032/0036

Required evidence:

- versioned Authorization Protobuf/Buf compatibility for `CheckPermission`, `CheckPlatformPermission`, bounded reads, tenant-management writes, and Identity provisioning/lifecycle/owner-safety commands;
- Git-owned permission-catalog schema/path is present and validated; permission syntax `^[a-z][a-z0-9]*(?:\.[a-z][a-z0-9]*)+$`, max 128, scope/owner/lifecycle are enforced; unknown/RETIRED fail closed, DEPRECATED cannot receive new grants, identifiers are never reused, and SYSTEM-role mappings remain compatible with retirement;
- SYSTEM Roles are immutable with exact current semantics: `tenant_owner` all ACTIVE tenant permissions, `tenant_admin` all except `tenant.delete`/`membership.owner.assign`, `tenant_member` exactly `tenant.read`/`membership.read`/`role.read`;
- custom Role UUIDv4, ACTIVE->ARCHIVED, no ordinary hard delete, trim+NFC name 1..80/control-free/case-insensitive per-tenant uniqueness, description<=500, SYSTEM-name reservation, optimistic version and no archived identity/name reuse;
- exact hard limits: 100 custom Roles/tenant, 200 permissions/custom Role, 20 Roles/Membership, 100 direct overrides/Membership, max 100 semantic mutations/bulk, pagination default 50/max 200;
- direct override scope is only Membership+exact permission with one GRANT/DENY, no resource conditions/expressions/TTL/expiry, explicit removal only;
- exact `CheckPermission(tenant_id,membership_id,permission_key)` request shape, approved resource-workload identity, success means ALLOW, authoritative deny is `PERMISSION_DENIED / AUTHORIZATION_DENIED`, no `allowed=false`, no Role/permission snapshot;
- exact 300ms/one-attempt/wait-for-ready-off/no-cache/no-retry/no-fallback behavior for `CheckPermission`; deny/outage/healthy-overload mapping remains stable;
- browser tenant administration arrives through Web BFF with Identity JWT whose exact audience is `authorization-service`; Authorization locally verifies `sub`/`tenant_id`/`membership_id`/`sid`, trusts no actor/role/permission payload authority, and performs no self-gRPC authorization call;
- exact tenant-management permission mapping for Role/Membership operations and privilege-escalation negatives: actor cannot add/grant/assign authority it lacks, direct-DENY removal requires possession of permission, and owner assignment additionally requires `membership.owner.assign`;
- `GetMembershipAuthorization` is bounded/paginated and explicitly non-authoritative for access decisions; no public/admin `ExplainPermission` surface exists;
- `AUTH_ADMIN_WRITE` executes before PostgreSQL, uses actual semantic mutation count with max100, has no refund on later validation/optimistic-concurrency/DB failure, while DB mutation itself commits all-or-none with no partial-success response;
- all management/lifecycle/platform writes use lowercase UUIDv4 `request_id` + purpose/version HMAC-SHA-256 fingerprint; equal replay returns original, conflicting reuse -> `ALREADY_EXISTS / REQUEST_ID_CONFLICT`, idempotency/security evidence >=35d;
- stable Authorization machine-code taxonomy is contract-tested and internal exception/SQL/provider details never leak;
- durable audit exists for every management write, platform authority assignment/revocation and privilege-sensitive management/platform rejection; owner/direct-override/Role-permission/platform changes require trim+NFC control-free reason 1..500; audit fields are bounded/PII-safe and retained >=365d;
- routine hot-path `CheckPermission` does not accidentally introduce synchronous durable audit write;
- Identity `PrepareMembershipRemoval` uses 300ms/one-attempt/no-cache/no-retry/no-fallback fail-closed semantics and Authorization-side durable owner-safety reservation;
- Identity removal reservations and local `tenant_owner` assignment/removal/demotion share one tenant-scoped serialization domain; concurrent final-owner operations cannot both consume final capacity, reservations do not auto-expire into unsafe allow, and no force/caller-owner-count path exists;
- Identity crash between prepare/local Membership commit recovers through stable request replay and idempotent finalize/cancel durable resolution;
- default `tenant_member` provisioning and tenant lifecycle cleanup/reconciliation are idempotent durable commands and never fabricate permission while pending;
- `platform_admin` is a global explicit SYSTEM capability profile with exactly current platform permissions; `CheckPlatformPermission(user_id, permission_key)` is Identity-only, 300ms/one-attempt/no-cache/no-retry/no-fallback/fail-closed, and never bypasses tenant/resource/domain checks;
- platform profile assignment/revocation is absent from ordinary tenant/BFF APIs and requires separately privileged JIT-controlled audited workflow;
- Authorization erasure removes subject-linked Membership Roles/direct overrides/projections and platform profile assignment while preserving tenant-owned Role definitions; retained audit removes/irreversibly pseudonymizes direct User linkage and receipts are non-PII;
- Authorization uses jOOQ/JDBC only; no JPA/Hibernate persistence model or generated jOOQ type leaks into Domain/Application;
- forced tenant RLS, non-owner `NOSUPERUSER NOBYPASSRLS` runtime role, transaction-local tenant context and pooled-context reuse negatives pass;
- no remote Redis/gRPC/HTTP/Kafka/provider I/O occurs inside Authorization DB transactions; quota occurs before DB locks;
- Hikari acquisition p99<25ms, acquisition ceiling<=50ms, permission SQL ceiling<=100ms and representative `EXPLAIN (ANALYZE, BUFFERS)`/index evidence pass;
- >=3 replicas, PDB `minAvailable=2`, topology spread, HPA initial 3..12, app gRPC convention 9090, separate management port, 64KiB message/16KiB metadata caps, hardened pod/ServiceAccount/NetworkPolicy/Istio policy and safe readiness/liveness render/test correctly;
- bounded global/per-caller concurrency, <=25ms server queue wait and no unbounded queue;
- current breaker opening/recovery behavior from ADR-0032/0036;
- `dependency-criticality.yaml` schema/coverage/render checks pass including platform permission and every Identity->Authorization lifecycle edge with current section references;
- no synchronous downstream other than Authorization-owned PostgreSQL on online permission path;
- no routine duplicate BFF permission check;
- one replica/node loss and PostgreSQL primary failover preserve fail-closed semantics/objectives;
- availability >=99.95%; p95<=100ms/p99<=200ms at >=2x projected peak with >=30% validated resource/database headroom.

Status until verified: **protected-operation and Authorization-management/platform-authority production blocker**.

## 4. PostgreSQL isolation/HA/recovery — ADR-0019/0027/0034/0037

Required evidence per service with mutable relational business persistence:

- dedicated production CloudNativePG cluster/database/runtime+migration roles/Flyway history/backup identity;
- 3 instances for critical clusters across independent schedulable failure domains where possible;
- synchronous acknowledgement from one failover-eligible replica for required durable writes;
- safe automatic failover; planned/unplanned failover evidence, ordinary target <=60s when durability is preserved;
- negative cross-service `CONNECT`/object privilege tests;
- forced tenant RLS; runtime roles `NOSUPERUSER NOBYPASSRLS` and non-owner;
- tenant context comes only from validated authenticated context, is parameterized and transaction-local, and pooled-connection reuse across commit/rollback cannot leak prior tenant into later borrower;
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

ADR-0040's immutable read-only rebuildable SQLite reference dataset is not mutable business persistence and is covered by §5 instead; it does not weaken this gate for any mutable PostgreSQL service state.

Status until verified: **platform/data production blocker**.

## 5. Compromised Password Service — ADR-0040

Required evidence:

- independent Java 25/Spring Boot service build, Gradle Wrapper, dependency verification, Spotless/SpotBugs/ArchUnit/Semgrep and Protobuf/Buf contract tasks exist and pass;
- exact internal operation is `LookupCompromisedPasswordRange`; request is exactly five uppercase SHA-256 prefix hex characters, response suffix exactly 59 uppercase hex characters plus non-negative occurrence count, deterministic order and stable redacted errors;
- Identity NFC-normalizes/UTF-8 encodes/computes SHA-256 locally, sends only first 20 bits, performs exact full-digest match locally, and treats exact count>0 match as compromised;
- raw password/full SHA-256 digest/User/Tenant/Membership/Contact/Session/provider identity never enters Compromised Password request, storage, log, trace or metric;
- the SQLite artifact is immutable/read-only/rebuildable reference data, not mutable business state; no PostgreSQL/CloudNativePG/Flyway/Redis/Kafka runtime dataset path exists;
- exact Technology Baseline Xerial SQLite JDBC 3.53.2.1 / embedded SQLite 3.53.2 is locked/verified until a reviewed compatible upgrade; final image/SBOM/advisory correlation includes Java artifact and bundled native engine;
- production dataset path/JDBC URI/native-library path/query text/PRAGMA/ATTACH target are server-owned and not caller-controlled;
- runtime opens the approved SQLite file read-only/query-only; INSERT/UPDATE/DELETE/DDL, `ATTACH`/`DETACH`, arbitrary PRAGMA and extension loading are impossible by production configuration/source tests;
- runtime SQL is fixed/parameterized and normal lookup uses indexed `(prefix,hash)` access without full-table scan or dynamic SQL;
- offline compiler validates exact schema/metadata/version, SHA-256 length, prefix equals first 20 hash bits, deterministic deduplication/count aggregation with checked overflow, positive counts and database integrity;
- every dataset prefix has <=2048 records and serializes within <=128KiB response contract; compiler fails publication on violation and runtime never truncates a prefix response;
- source provenance, integrity, license/use rights are reviewed; plaintext source material, when an approved import uses it, never enters runtime artifact/source control/logs/telemetry;
- no full-dataset JVM heap index/cache/Bloom authority exists; OS/SQLite bounded I/O page cache is not application authority;
- no HIBP/Pwned Passwords/external compromised-password provider or arbitrary application Internet egress exists at runtime;
- only approved Identity ServiceAccount can call application gRPC; wrong workload/public ingress is denied by NetworkPolicy/Istio/Ambient strict-mTLS policy;
- Identity dependency contract is <=900ms overall, one attempt, wait-for-ready off, no automatic retry/cache/fallback; cancellation/concurrency/read connections/queue are bounded;
- missing/incompatible/corrupt dataset, SQLite open/read error, malformed/oversized result, storage I/O saturation, deadline or healthy overload maps to stable unavailable/overloaded outcome and Identity rejects unchecked password; no failure becomes `not compromised`;
- runtime does not truncate on deadline/size failure and does not fabricate empty success;
- >=3 replicas, PDB minAvailable=2 and topology spread use identical approved dataset version; one replica/worker loss preserves fail-closed service behavior;
- root filesystem and dataset path are read-only; if Xerial native extraction needs writable temp space it is a separate bounded ephemeral mount containing no dataset/password/source/subject state;
- readiness requires approved local dataset path, read-only open/query, compatible schema/metadata/version/artifact identity/integrity and security config; liveness is local process/runtime only;
- HPA remains disabled until representative disk/query load proves a safe signal/capacity envelope;
- representative **multi-million-row** warm/cold storage tests at >=2x projected credential-write peak meet availability>=99.95%, p95<=250ms, p99<=750ms and show bounded storage/I/O/concurrency/response behavior;
- logging/metrics exclude prefix/suffix/full hash/returned rows/subject IDs/caller metadata/unreviewed SQLite/JDBC/native exception text; only low-cardinality outcome/latency/saturation/dataset health is emitted;
- Compromised Password is not a data-subject erasure participant because no subject-linked state exists; negative tests prove this remains true;
- cold-DR/recovery rebuilds/redeploys the approved immutable dataset artifact and keeps pods unready until compatibility/integrity passes; no stale/corrupt/provider fallback is used;
- `dependency-criticality.yaml` and generated Markdown contain the exact Identity->Compromised Password authoritative-security edge and ADR/service policy references.

Status until verified: **password create/change/reset production blocker; unrelated authentication flows may proceed independently when their own gates pass**.

## 6. Kafka durability/rebuildable DR — ADR-0015

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

## 7. Browser/BFF security — ADR-0016

Required evidence:

- OpenAPI exposes only reviewed `/api/v1` public namespace/subspaces and does not mechanically publish internal gRPC method names;
- public JSON <=256KiB, auth/OIDC/session body <=64KiB, headers/metadata <=16KiB, multipart/file upload rejected in v1, and oversized input is rejected before expensive downstream work;
- RFC 9457 public profile is contract-tested for `type`/`title`/`status`/stable `code`/optional safe correlation and redacts tenant/membership/Contact/internal request IDs, provider/exception text, tokens and security internals;
- OIDC Authorization Code + PKCE S256; `state` exactly 256 CSPRNG bits, `nonce` exactly 256 CSPRNG bits, verifier exactly 32 random bytes Base64URL-no-padding;
- `__Host-sajtech-preauth` has Secure/HttpOnly/SameSite=Lax/Path=/, no Domain and >=256-bit opaque ID; raw ID is absent from Redis keys/logs and purpose-HMAC locator is used;
- pre-auth state <=10m/single-use/max five live transactions per browser; consumed/expired state cannot revive;
- exact provider redirect plus post-login target validation rejects absolute/scheme/userinfo/`//`/backslash/control/encoded-canonicalization bypasses and enforces same-origin relative path <=1024;
- provider validation occurs in BFF before Identity invocation; no direct Identity->Google login/link/signup dependency and provider authorization code/tokens do not enter Identity;
- BFF->Identity evidence is exactly 256-bit CSPRNG, bound to trusted BFF workload + canonical UUIDv4 request + issuer/subject/issued-at/versioned metadata, expires after two minutes, and retains spent/replay evidence >=10m;
- exact replay returns original result; changed payload/request under same evidence ID returns stable replay conflict;
- Google signup verified-email collision becomes `ACCOUNT_LINK_REQUIRED`; email equality never auto-links; `email_verified=false` creates no Contact; provider names are suggestion-only;
- active TOTP after Google proof enters same MFA pre-auth continuation as password and cannot establish completed BFF/Identity session before MFA;
- browser never receives provider/Identity access/refresh credentials or arbitrary downstream-audience authority;
- server-owned route->downstream mapping drives Identity `IssueAudienceAccessToken`; source Session/RefreshFamily must be active; exact audience is allow-listed; arbitrary browser audience is rejected; issuance remains five-minute exact-audience JWT and is not generic public token exchange;
- `authenticated_onboarding` cannot obtain ordinary resource-service or `authorization-service` audience and cannot dispatch ordinary resource/Authorization-management calls;
- BFF->Identity audience-token broker edge is 1500ms max, one attempt, wait-for-ready off, no automatic retry/fallback/security cache and fails closed;
- secure `__Host-sajtech-session`; raw session ID absent from Redis keys/logs; purpose-HMAC locator is used;
- session state contains bounded Identity Session/RefreshFamily, tenant/membership/mode, CSRF digest, created/last-seen/idle/absolute state and encrypted refresh only where needed;
- idle<=7d, absolute<=30d/immutable, Identity family expiry upper-bounds session and `last_seen` persists at most once per five-minute activity window;
- every completed BFF session maps to one current RefreshFamily and purpose-HMAC/pseudonymous User->sessions index supports logout-all/suspension/DELETING/erasure/family revocation without unbounded scans;
- session rotation after login/MFA/tenant switch/recovery/security transitions is atomic and predecessor ID becomes invalid immediately with no dual-valid grace;
- retained refresh credential uses AES-256-GCM, random 96-bit nonce, 128-bit tag, session+purpose+key-id/version AAD, 90d rotation, prior decrypt key through dependent-session lifetime+7d, atomic reload, <=1h last-valid snapshot and fail-closed after staleness;
- tenantless `authenticated_onboarding` has no normal resource JWT, permits only reviewed Identity onboarding routes, and transitions only after valid Membership selection;
- zero/one/many Membership journeys and tenant-switch session/refresh rotation pass; Identity MFA-state-change revocation/rotation is reflected in BFF continuity;
- CSRF token exactly 256 CSPRNG bits/session-bound, only purpose-HMAC stored, constant-time compare, rotates with session/assurance, no separate CSRF cookie or persistent browser storage;
- unsafe production browser request requires exact Origin + CSRF + `Sec-Fetch-Site:same-origin`; missing Fetch Metadata fails closed; safe methods are side-effect free;
- cross-origin credentialed CORS is absent in v1/same-origin only;
- exact CSP has no `unsafe-inline`/`unsafe-eval`; HSTS/nosniff/referrer/Permissions-Policy/frame checks pass; auth/OIDC/session/Authorization-admin responses are `Cache-Control: no-store`;
- exact OIDC_START/OIDC_CALLBACK quotas and max-five-pre-auth rule pass outage/skew/atomicity tests;
- browser/storage/service-worker inspection proves no provider/internal JWT/refresh leakage or private authenticated cache;
- `dependency-criticality.yaml` contains current BFF session/quota/Google/evidence/audience-token/Authorization-management/resource-dispatch edges and generated Markdown matches;
- BFF resource dispatch does not fabricate business data on downstream failure and final protected-resource `CheckPermission` remains in resource-owning service;
- BFF erasure removes sessions/pre-auth/OIDC/encrypted refresh/User-session index/user-linked continuation and returns only non-PII idempotent evidence;
- runtime renders `platform-apps/web-bff`, HTTP 8080, separate management port, >=3 replicas, PDB minAvailable=2, HPA 3..12 only after load evidence, hardened security context;
- deny-by-default NetworkPolicy/Istio egress permits only Identity, Authorization management, registered resource services, BFF/security Redis, configured Google OIDC endpoints and approved telemetry; arbitrary URL/Internet egress and wrong workload are denied.

Status until verified: **public-internet/Web-BFF production blocker**.

## 8. Supply chain + vulnerability response — ADR-0017/0035/0038

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
- stale required feed/scanner state fails promotion closed;
- final-image native components such as the Xerial-bundled SQLite engine are included in SBOM/advisory correlation and not treated as invisible Java-only dependency content.

Status until verified: **production deployment-security blocker**.

## 9. Notification runtime — ADR-0006/0007/0014/0018/0020

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

## 10. OpenBao recovery — ADR-0011

Required evidence:

- exact 2.6.1 image digest;
- Shamir 3-share/2-threshold custody/access runbook;
- encrypted hourly snapshot outside primary PVC + pre-upgrade snapshots;
- restore + manual unseal exercise;
- External Secrets/key refresh staleness alerting and fail-closed behavior after allowed local-key staleness;
- recovery fits platform RTO sequence;
- Istio Root CA private key absent from Kubernetes/OpenBao.

Status until verified: **secret-platform blocker**.

## 11. WAF + upstream DDoS — ADR-0001/0029

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

## 12. Iran SMS / SMS MFA — ADR-0020/0012

Required evidence:

- IPPanel Webservice sandbox fixture pins definitive accepted correlation field/type;
- recipient-level report fixture pins current status mappings;
- exact Notification-rendered text; no provider-managed Pattern authority;
- dedicated OpenBao credential, 90-day rotation/emergency revocation, bounded egress;
- 500ms connect / 1500ms total / no automatic HTTP retry;
- timeout/connection loss/malformed/unproven acceptance -> `AMBIGUOUS`, never blind resend;
- bounded report polling/backpressure;
- local logging adapter cannot activate in production;
- SMS MFA additionally passes ADR-0024 quotas and current Identity MFA/session gates;
- active TOTP cannot be downgraded/bypassed by SMS; SMS MFA available only for accounts without active TOTP under approved production gate;
- SMS MFA proof exactly eight CSPRNG decimal digits, purpose-HMAC-only, no plaintext durable storage after safe handoff, expires no later than enclosing 5m pre-auth challenge, shares max-five failed proofs, enforces 60s resend, replacement invalidation and single use.

Status until verified: **SMS-dependent feature blocker; unrelated Email-only capabilities may proceed independently**.

## 13. Platform compatibility / CNI / immutable artifacts — ADR-0021/0040

Required evidence:

- every deployed image pinned by immutable digest;
- Technology Baseline + compatibility matrix revalidated against upstream support/security at release time;
- Kubernetes/Istio Ambient/Calico positive/negative flows including HBONE/health;
- CloudNativePG/cert-manager/Kyverno/Traefik/Gateway API/WAF render/compatibility checks;
- Xerial SQLite JDBC/embedded SQLite Java25/Linux native compatibility and current security/advisory relationship when Compromised Password is affected;
- `istioctl analyze`, Helm/Kustomize/Kubernetes policy checks;
- staging/production desired state renders without secret values;
- rollback artifacts/digests remain available;
- unsupported/EOL components are replaced by supported compatible baseline before rollout.

Status until verified: **platform release blocker**.

## 14. JWT signing-key lifecycle — ADR-0023

Required evidence:

- RSA-3072/RS256 private signing keys only in Identity local/OpenBao delivery boundary;
- next public key deployed/verified before activation;
- local GitOps verifier bundle reloads atomically;
- exact v1 claim allow-list (`iss`,`aud`,`sub`,`jti`,`iat`,`exp`,`tenant_id`,`membership_id`,`sid`), no role/permission snapshot authority, wildcard-audience rejection;
- Identity-owned BFF `IssueAudienceAccessToken` contract enforces authorized BFF workload + active Session/RefreshFamily + server-owned allow-listed exact audience, rejects browser-selected arbitrary audience and normal-resource/Authorization audience during `authenticated_onboarding`, and keeps five-minute issuance baseline;
- algorithm-confusion/unknown-kid/issuer/audience negatives;
- exact five-minute issuance lifetime and configurable verifier clock leeway <=30s, including rejection of >30s config;
- 90-day normal rotation + emergency compromise exercise;
- no normal verification call to Identity/OpenBao/remote JWKS/introspection;
- private-key Git/telemetry leak tests.

Status until verified: **authentication-trust blocker**.

## 15. Java/source/build/CI — ADR-0039

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

## 16. Frontend/source/browser quality

For affected frontend releases:

- Prettier/ESLint/type-aware strict TypeScript/typecheck pass;
- no unsafe token storage or service-worker private caching;
- generated OpenAPI client contract is current;
- unit/component/accessibility tests pass;
- critical Playwright flows pass without fixed-sleep/flaky-retry masking;
- route bundle/performance budget passes;
- browser security/headers/session behavior passes.

Status until verified: **frontend release blocker when applicable**.

## 17. Identity Service repository-complete evidence — ADR-0009/0012/0023/0028/0040

Required repository/build evidence includes:

- versioned feature-scoped Protobuf + Buf compatibility for registration/profile/contact/auth/password/session/tenant/invitation/membership/external-identity/MFA/erasure entry points plus BFF audience-specific token brokerage;
- canonical UUIDv4 entity/request IDs, 32-byte refresh credential, >=256-bit session IDs, 256-bit OIDC evidence IDs, UTC-microsecond persistence, non-reuse and server-owned TTL/policy fields;
- EMAIL + PHONE local registration implementation with local Credential/compromised-password dependency and staging/production PHONE gate tied to SMS readiness;
- User `PENDING -> ACTIVE -> SUSPENDED -> DELETING -> DELETED`, profile+verified-Contact+applicable-Credential activation, first verified primary, authentication shutdown/revocation for suspended/deleting states;
- one live 10m pending registration reservation per canonical Contact; repeated same pending continuation cannot overwrite protected registration state; reservation expiry releases only unverified reservation authority; stale challenge cannot revive; no second User/challenge for verified/reserved Contact;
- local password login by any active verified primary/secondary email/phone Contact, unverified/removed denial and non-enumerating unknown/no-local-Credential/wrong-password/blocked-account behavior;
- profile/contact APIs and recent-auth primary/remove constraints; ACTIVE User cannot lose last verified Contact outside erasure;
- exact registration/contact/password-recovery challenge format/TTL/attempt/resend/single-use and exact ADR-0024 numeric registration quota behavior/non-enumeration;
- explicit aggregate/transaction boundaries, JPA aggregate CRUD plus justified JDBC/jOOQ SQL-control paths, no remote I/O in transactions;
- tenant/invitation/Membership exact lifecycles, existing-user target/7d/single-pending, default `tenant_member` provisioning, no arbitrary invitation role;
- concurrent last-owner `PrepareMembershipRemoval` durable reservation, 300ms/one-attempt/no-cache/no-retry/fallback, crash-safe local intent + idempotent finalize/cancel and no unsafe automatic reservation expiry;
- exact platform mapping and fail-closed `CheckPlatformPermission` for platform tenant create/suspend/resume/restore and platform legal-hold management; approved Identity workload only, no caller-supplied platform profile/wildcard authority, 300ms/one-attempt/no-cache/no-retry/no-fallback, and all calls outside Identity DB transactions;
- tenant delete/suspend/restore lifecycle, pending invitation revocation, Authorization cleanup/reconciliation, slug/ID non-reuse;
- tenantless authenticated onboarding with no ordinary resource JWT, zero/one/many Membership selection, stale last-selection rejection and tenant-switch credential/session rotation;
- exact JWT claim/audience rules, five-minute lifetime, <=30s verifier leeway and local-verification residual-token trade-off;
- `IssueAudienceAccessToken` is Identity-owned internal-only BFF operation, requires authorized BFF workload + active Session/RefreshFamily, maps only to server-allow-listed exact audience valid for current tenant/session mode, rejects browser/arbitrary audience and normal resource/Authorization audience during onboarding, and never exposes refresh/provider credentials;
- refresh 32-byte generation/HMAC persistence, 7d idle/30d absolute, rotation/reuse, max 20 active families and deterministic oldest revocation;
- logout-current/logout-all/password-change/reset/suspension/deleting/ExternalIdentity-unlink/MFA-state-change revocation rules;
- password change recent-auth/MFA assurance, primary-Contact-only non-enumerating password recovery, no reset-created first local Credential, active-MFA reset requirement, no automated password+MFA-loss bypass, no password history;
- compromised-password NFC/UTF-8/SHA-256 local digest, only first 20 bits/five uppercase hex outbound, bounded suffix/count response, raw password/full digest non-egress, 900ms/one-attempt/no-retry/fail-closed behavior, exact local full-hash match, and no external-provider fallback;
- BFF-only Google provider validation; exact 256-bit/two-minute/ten-minute evidence semantics; `email_verified=false` no-Contact; verified-email signup collision `ACCOUNT_LINK_REQUIRED`; suggestion-only names; no email auto-link/provider token in Identity;
- active TOTP after both password and Google primary proof; ExternalIdentity link/unlink recent-auth and last-authentication-method protection;
- TOTP pre-auth 5m/five-failed-proof/single-use, new-primary-proof invalidation, timestep replay rejection, recovery-code atomic use; MFA-state-change session revocation; no SMS downgrade of active TOTP;
- exact SMS proof eight-digit/HMAC/no-plaintext/<=5m/five-proof/60s/replacement/single-use semantics and production gate;
- self-erasure recent-auth + active MFA + no ACTIVE/SUSPENDED Membership for non-DELETED Tenant; last-owner-safe exit; pending-invitation + all-family revocation; server-owned participants; Kafka/outbox/inbox replay/non-PII receipts/legal hold; no self-service undo; restore-before-traffic;
- Authorization erasure receipt/evidence proves subject-linked tenant authorization and platform capability assignments are removed without deleting tenant-owned Role policy;
- purpose/version HMAC idempotency replay/conflict, 35d critical publication/Inbox-dedup evidence, >=14d retry/DLQ evidence when used, >=365d security audit evidence;
- dependency registry includes semantic quota, exact ADR-0040 compromised-password lookup, Notification, owner/member provisioning, Membership removal prepare/resolution, tenant lifecycle, platform permission check, and Web BFF OIDC/audience-token ownership with valid current policy refs;
- Identity Docker/Helm/GitOps/ServiceAccount/NetworkPolicy/Istio/probe/replica/PDB/topology/security-context/render checks and CI gates.

Repository-complete does **not** equal production-ready. Registry/DNS/secret paths/provider credentials/Redis/CNPG/backup/alert destinations may remain typed environment placeholders, but actual staging/production provider, secret, cluster, Compromised Password dataset/service, load, failover, restore, and DR evidence remains `NOT VERIFIED` until executed.

Status until verified: **Identity repository implementation/evidence blocker; external production evidence remains independently blocking**.

## 18. Final release evidence

The exact candidate additionally passes applicable critical load/SLO, Authorization/Redis/PostgreSQL/Kafka/SQLite/WAF/provider capacity, node/replica/database/reference-artifact failure, security-negative/workload-identity, backup/PITR/restore/rebuild, smoke/BDD/critical Playwright, rollback/fail-forward, and error-budget release-policy checks.

Until actual service source/build/workflows/manifests and these checks exist/pass, implementation evidence remains **NOT VERIFIED**.
