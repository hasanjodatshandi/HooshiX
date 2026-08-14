# Testing and Quality Gates — Current State

Testing proves current contracts and failure semantics at the cheapest trustworthy layer. Documentation/configuration is not evidence until corresponding executable check exists and passes.

## 1. Test portfolio

Each service uses applicable layers:

- Domain unit tests;
- Application use-case tests with fake ports, no Spring;
- ArchUnit architecture tests;
- PostgreSQL/Flyway integration tests with Testcontainers when mutable PostgreSQL persistence is used;
- SQLite adapter/dataset compiler integration tests when ADR-0040 is used;
- Kafka/Redis integration tests where used;
- gRPC/REST/OpenAPI/Protobuf contract compatibility tests;
- Outbox/Inbox/idempotency/duplicate/restart/replay/DLQ tests where those semantics exist;
- authorization/tenant/RLS/workload-identity/security tests where applicable;
- timeout/cancellation/retry/bulkhead/concurrency tests;
- logging/PII/log-injection/Semgrep/canary-sink tests;
- load/failover/chaos/recovery tests for critical paths;
- BDD acceptance tests for critical business behavior;
- frontend unit/component/accessibility tests;
- Playwright critical browser journeys.

Do not create meaningless test categories for unused technology. ADR-0040's immutable SQLite reference artifact does not make PostgreSQL/Flyway/Kafka/Redis tests applicable to Compromised Password unless future scope actually introduces those technologies.

## 2. Unit and integration rules

Domain/Application unit tests instantiate code directly and do not start Spring or depend on network/database state.

Integration tests use real infrastructure where behavior under test depends on it. Testcontainers is preferred for PostgreSQL/Kafka/Redis adapters. Embedded SQLite tests use the exact approved Xerial/SQLite dependency and deterministic generated fixture databases. Verify transaction/read-only boundaries, query bounds, path/configuration safety, concurrency, error mapping, and security constraints rather than only happy-path lookup.

Tests are deterministic and parallel-safe where intended. Fixed sleeps and shared mutable global fixtures are prohibited as synchronization mechanisms.

## 3. Contract compatibility

Protobuf contract governance:

```text
buf lint: STANDARD
buf breaking: FILE against current main/approved compatibility base
```

Field-number reuse is prohibited. Generated code remains derived from canonical contracts.

OpenAPI and other externally consumed contracts use compatibility diff/contract checks. No runtime Schema Registry exists in v1.

Compromised Password contract tests prove exact five-uppercase-hex request, exact 59-uppercase-hex suffix/non-negative count response, deterministic ordering/bounds and stable sanitized errors without exposing SQLite/JDBC/native/file details.

## 4. BDD and Playwright

BDD/Gherkin describes critical business behavior understandable by Product/QA/Engineering; it does not encode selectors, SQL, method names, or every CRUD edge case. Cucumber-JVM runs on JUnit Platform. Step definitions stay thin.

Playwright Test + TypeScript is primary browser E2E tool. Use semantic locators, web-first assertions, auto-waiting, isolated data, and parallel-safe tests. Fragile CSS/XPath and fixed `waitForTimeout` synchronization are prohibited when semantic alternatives exist. Retries never redefine flakiness as passing; flaky tests have owner and remediation deadline.

Compromised Password's internal SQLite implementation is not a reason to create browser-level storage tests. Critical user-facing password create/change/reset acceptance may prove compromised-password rejection, while storage/security details stay at service/integration layers.

## 5. Java executable quality gates

Every Java service is covered by `../engineering/build-and-ci-quality-enforcement.md` and ADR-0039:

- independent Gradle Wrapper/build + dependency verification/locks;
- Spotless;
- SpotBugs;
- ArchUnit `architectureTest`;
- repository Semgrep architecture/security/logging rules with fixtures;
- applicable unit/integration/contract/schema tasks;
- GitHub Actions required checks.

`ignoreFailures`, broad analyzer exclusions, disabled mandatory tests, or required-check removal to obtain green CI are prohibited.

Compromised Password build verification includes the exact Xerial artifact/native engine in dependency locks, SBOM/advisory correlation and architecture tests keeping SQLite/JDBC types/SQL out of Domain/Application.

## 6. CI dependency graph

Independent checks SHOULD run in parallel, but no downstream mandatory stage proceeds after a required predecessor fails.

```text
compile/unit + formatting/architecture/static checks
+ contract/schema/dependency/secret/security checks
-> focused integration evidence
-> Helm/Kubernetes/runtime policy validation where affected
-> container build
-> final-image SBOM/vulnerability scan + signature/provenance
-> staging deploy
-> smoke + critical acceptance/browser/security checks
-> production-readiness gates
-> promote the same immutable digest
-> production-safe smoke/synthetic checks
```

For ADR-0040, offline dataset compiler validation produces immutable dataset artifact identity/integrity evidence before service release. Production serving does not rebuild/download/mutate the dataset. Application image + dataset format/version compatibility is verified before promotion; exact deployment artifact identity remains reviewed GitOps/release state.

Production rebuild after staging validation is prohibited.

## 7. Security and supply-chain gates

Pre-build controls include applicable SAST, dependency vulnerability/license policy, secret scan, and Gradle verification.

Final-image controls include signed CycloneDX SBOM, provenance, Cosign signature/attestations, container vulnerability correlation, and exact owned/expiring exceptions.

Current continuous policy from ADR-0035/ADR-0038:

- newly introduced Critical blocks merge/promotion absent exact unexpired exception;
- High with available fix blocks production promotion absent exact unexpired exception;
- deployed digest inventory rescans at least every six hours;
- advisory/KEV inputs ingest at least every two hours and may trigger targeted rescans;
- Critical/known-exploited production exposure targets <=24h mitigation and incident handling;
- High production exposure targets <=48h;
- Critical exception <=7d, High <=30d; expiry stops new promotion and escalates production exposure;
- transitive dependency accountability follows deployed artifact owner;
- scanners do not unauditedly kill running pods and never authorize unsigned/unprovenanced images.

For Compromised Password, SBOM/advisory evaluation covers both `org.xerial:sqlite-jdbc` and the bundled native SQLite engine. Dataset-source provenance/license/use-right review is separate from software-vulnerability scanning and is required before external source material is admitted by the offline compiler.

## 8. Kubernetes, mesh, and deployment gates

Affected release candidates run applicable:

- `helm lint` and all-environment render;
- Kubernetes schema/policy/security-context/resources/probes checks;
- rendered-secret and manifest-diff checks;
- Gateway API/Traefik/WAF route and negative-bypass tests;
- `istioctl analyze`;
- Ambient enrollment, STRICT mTLS, ServiceAccount, NetworkPolicy, and authorization positive/negative tests;
- immutable digest/signature/provenance/SBOM admission checks;
- admission-policy authoring RBAC negatives proving ordinary application/service identities cannot create or modify cluster-scoped policy;
- Kyverno CEL HTTP-context disabled-by-default checks and, when approved external lookup exists, bounded destination/timeout/response/failure tests plus loopback/link-local/cloud-metadata/unreviewed-private/arbitrary-caller-target SSRF negatives and NetworkPolicy-constrained egress.

For Web BFF specifically, rendered policy tests prove only the public edge can reach BFF and BFF egress is restricted to Identity, Authorization management, registered resource services, BFF/security Redis, configured Google OIDC endpoints and approved telemetry. Arbitrary Internet/URL egress and wrong workloads are denied.

For Compromised Password, rendered policy tests prove only Identity can reach application gRPC, application Internet/provider egress is absent, the SQLite dataset path is read-only, any Xerial native-extraction writable temp mount is separate/bounded/non-dataset, and wrong workloads cannot access the service.

## 9. Production resilience/security evidence

### Semantic quotas — ADR-0024

Prove atomic multi-dimension enforcement, HMAC pseudonymous keys, anti-lockout sequencing, dual trusted time/<=2s skew, no TTL security reset, 75ms one-attempt fail-closed semantics, Redis Sentinel failover/outage, exact Identity registration values, exact Web BFF `OIDC_START/network=60,1/5s,1h` and `OIDC_CALLBACK/network=120,2/1s,30m` plus max-five-live-pre-auth composition/domain separation from Identity Google-login keys, exact Authorization semantic-mutation cost/no-refund behavior, and >=2x projected peak load.

### Authorization — ADR-0013/0026/0032/0036

Prove complete Authorization contract, not only hot-path happy case:

- permission-catalog schema/scope/owner/lifecycle/non-reuse plus unknown/retired/deprecated behavior;
- exact SYSTEM Role mappings/immutability and custom Role normalization/version/archive/limits;
- direct override precedence/one-value/no-condition/no-TTL behavior;
- exact `CheckPermission` request, approved caller workload, success-is-ALLOW/deny-status/no-`allowed=false` semantics;
- one-call/300ms/no-cache/no-retry/no-fallback and stable deny/unavailable/overload mapping;
- Web BFF tenant-management workload + local JWT `aud=authorization-service` verification, no role/permission JWT trust and no self-gRPC management check;
- exact management permission mapping and privilege-escalation negatives for Role permissions/assignment/direct grants/deny removal/owner assignment;
- bounded management reads, deterministic pagination and `GetMembershipAuthorization` non-authority/no `ExplainPermission` surface;
- hard limits and atomic management mutation, AUTH_ADMIN_WRITE set-delta cost/max100/quota-before-DB/no-refund behavior;
- UUIDv4/HMAC idempotency equal-replay/conflict and >=35d evidence;
- stable error taxonomy and >=365d PII-safe durable audit/reason requirements while proving hot-path checks do not add synchronous audit writes;
- owner-role mutation and Identity Membership-removal reservation atomic serialization, reservation replay/finalize/cancel/no unsafe expiry;
- exact `CheckPlatformPermission` Identity-only 300ms/fail-closed profile permissions, platform no-bypass and JIT-only profile assignment/revocation;
- jOOQ/JDBC-only persistence, forced RLS/pool context negatives, representative query-plan evidence and no remote I/O inside DB transactions;
- erased User tenant/platform authority removal while tenant-owned Role definitions remain;
- fair overload shedding, current breaker opening/recovery, paired burn alerts, p95<=100ms/p99<=200ms, >=3 replicas/PDB/spread, Hikari p99<25ms, >=2x peak capacity, one replica/node loss, PostgreSQL failover, and absence of duplicate routine BFF resource permission checks.

### Dependency registry — ADR-0033/0036/0040

Validate `dependency-criticality.yaml` schema, duplicates/orphans/coverage, generated Markdown view, current policy-reference anchors including Compromised Password, Authorization platform/lifecycle and Web BFF session/quota/Google/evidence/audience-token/Authorization-management/resource-dispatch edges, one retry owner, no implicit fallback, and composite-edge semantics.

### Compromised Password — ADR-0040

Prove the complete self-contained contract:

- Identity NFC/UTF-8/SHA-256 behavior and only first 20 bits/five uppercase hex leave Identity;
- exact `LookupCompromisedPasswordRange` request validation, 59-uppercase-hex suffix/count response, deterministic ordering and local full-digest exact comparison in Identity;
- raw password/full hash/User/Tenant/Contact/session data absent from service requests, storage and telemetry;
- SQLite schema/metadata/version rules; `WITHOUT ROWID` `(prefix,hash)` index path; prefix recomputation/dedup/count-overflow validation;
- offline dataset compiler deterministic build, source provenance/license/integrity, no plaintext source material in runtime artifact/Git/logs;
- <=2048 rows per prefix and <=128KiB response compatibility across every prefix; build fails on violation and runtime never truncates;
- exact Xerial SQLite JDBC 3.53.2.1 / embedded SQLite 3.53.2 baseline until reviewed upgrade; dependency verification, Java25/Linux native compatibility, final-image SBOM/advisory correlation;
- runtime only server-owned JDBC/database/native configuration; SQLite read-only/query-only; fixed parameterized query; no INSERT/UPDATE/DELETE/DDL/ATTACH/DETACH/arbitrary PRAGMA/extension loading;
- no full-corpus JVM cache/Bloom authority, Redis/PostgreSQL copy, Kafka path, HIBP/external provider call or arbitrary Internet egress;
- Identity-only workload authorization, ClusterIP/Ambient strict mTLS/NetworkPolicy negatives;
- missing/incompatible/corrupt/open/read/storage-saturation/deadline/overload failure maps to fail-closed unchecked-password rejection and never clean result;
- 900ms caller ceiling, one attempt, no retry/fallback; bounded read connections/in-flight/queue;
- multi-million-row warm/cold disk-backed load at >=2x projected credential-write peak meets availability>=99.95%, p95<=250ms, p99<=750ms before production;
- >=3 replicas/PDB2/spread use identical approved dataset version; replica/node loss does not create stale/corrupt fallback;
- read-only root/dataset path and separate bounded native-extraction temp mount behavior under hardened container;
- immutable dataset rebuild/redeploy DR and readiness block until compatible dataset identity/schema/integrity passes;
- no data-subject erasure participant because no subject-linked state; test proves no such state is introduced.

### Notification — ADR-0006/0007/0014/0018/0020

Prove local key-ring rotation/refresh/corruption/erasure with no hot-path OpenBao RPC, request replay/conflict, exact-content lifecycle, PostgreSQL-authoritative deadlines, durable `DISPATCHING`, crash/failover no-blind-redispatch, Liara SMTP outcome classification, IPPanel accepted/report fixtures, ambiguity/no-blind-retry, and bounded polling.

### PostgreSQL — ADR-0019/0027/0034/0037

Prove per-service physical/database/credential/backup isolation for mutable relational business state, forced RLS/runtime-role restrictions, transaction-local parameterized tenant context with fail-closed missing/malformed behavior, pooled-connection reuse across different tenants after commit/rollback with no context leakage, synchronous required durability, pool budgets, planned/unplanned failover, WAL/PITR, monthly restore evidence, quarterly DR, failed-drill promotion freeze, one-cluster upgrade waves, and no unsafe downgrade. ADR-0040's immutable SQLite reference artifact does not reduce these requirements where PostgreSQL mutable state exists.

### Kafka — ADR-0015

Prove KRaft broker/controller failure, RF/minISR/acks/idempotence, TLS/ACL/quotas, 35-day critical publication/dedup evidence, clean-cluster replay/reconstruction, and consumer duplicate/restart safety.

### Browser/Web BFF — ADR-0016

Prove the complete BFF implementation contract:

- OpenAPI `/api/v1` namespace, internal-RPC non-exposure, RFC 9457 stable/redacted errors, JSON/auth/header size bounds and multipart rejection;
- exact 256-bit state/nonce, exact 32-byte Base64URL-no-pad PKCE verifier/S256, `__Host-sajtech-preauth` flags/entropy/HMAC locator, <=10m/single-use/max-five state, replay/mismatch negatives;
- exact provider redirect and <=1024 same-origin relative return target with absolute/`//`/backslash/control/encoded-bypass negatives;
- provider validation before Identity and provider-token/code absence from Identity/browser/logs;
- exact OIDC evidence entropy/2m/10m/replay/conflict and Google email/no-auto-link/profile-suggestion behavior;
- active TOTP after password/Google proof with no completed session before MFA;
- Identity `IssueAudienceAccessToken` authorized-BFF/active-session/server-allow-list contract, browser arbitrary-audience rejection, onboarding resource/Authorization-audience rejection, five-minute exact audience, 1500ms one-attempt/no-retry/fallback, and no browser JWT exposure;
- secure `__Host-sajtech-session`, HMAC Redis locator, bounded state, idle<=7d/absolute<=30d immutable, five-minute last-seen write coalescing;
- one-session-to-RefreshFamily binding, pseudonymous User->sessions index, logout-all/suspension/deletion/erasure/family-reuse cleanup;
- atomic session rotation with predecessor immediate invalidation/no dual-valid grace;
- refresh AES-256-GCM 96-bit nonce/128-bit tag/AAD, 90d rotation, dependent-session+7d prior-key retention, atomic reload, <=1h valid snapshot then fail closed;
- exact 256-bit CSRF/purpose-HMAC/constant-time/no-CSRF-cookie/rotation behavior;
- unsafe requests require Origin+CSRF+`Sec-Fetch-Site:same-origin`, missing Fetch Metadata fails closed; safe methods side-effect free;
- same-origin-only/no cross-origin credentialed CORS; exact CSP/no unsafe-inline/eval, HSTS/nosniff/referrer/Permissions-Policy/no-store;
- exact OIDC quota values plus outage/skew behavior;
- Authorization-management exact audience/request-id/1500ms behavior and proof BFF never locally grants management/final resource authority;
- registered resource-dispatch failure never fabricates business data;
- BFF erasure removes all user-linked auth state and returns non-PII idempotent receipt;
- runtime >=3/PDB2/HPA-gated hardening and exact egress/wrong-workload negatives;
- browser/storage/service-worker token/private-cache restrictions and critical accessibility/RTL/auth/onboarding/admin journeys where affected.

### Edge/DDoS — ADR-0001/0029

Prove mandatory upstream L3/L4 mitigation/scrubbing -> redundant external L4 load balancing -> Traefik -> WAF -> Web BFF path, direct-bypass denial, controlled blocking/load behavior, no sensitive edge logging, provider capability/escalation, connection-pressure telemetry, and authorized saturation exercise.

### Secrets/access/logging

Prove OpenBao snapshot/restore/Shamir unseal and secret-refresh behavior, BFF key-ring rotation/staleness/no-secret logging, Teleport JIT SSO/WebAuthn/two-reviewer write elevation/expiry/direct-access denial/session audit, Authorization platform-profile assignment/revocation JIT audit controls, and ADR-0031 Semgrep + pipeline redaction + canary sink + runtime detector safety.

### Java 25 Virtual Threads

Use JFR/load/soak evidence for native/FFM pinning, contention, and scarce dependency saturation. `synchronized` itself is not blanket prohibited. Compromised Password additionally measures bounded SQLite/native/JDBC I/O concurrency; Virtual Threads never justify unbounded SQLite connections/queue.

## 10. Smoke tests

After deployment, verify applicable startup/readiness/health, database/Kafka/Redis/SQLite-reference connectivity as applicable, one critical REST/gRPC flow, isolated event publish/consume when used, authentication/authorization, timeout/error behavior, and critical Playwright flows. Event smoke uses isolated test tenants/topics/markers and does not create uncontrolled customer data.

Compromised Password smoke uses a deterministic non-sensitive fixture/approved dataset marker contract; it verifies one known compromised match, one not-listed result, corrupt/missing-dataset fail-closed behavior in staging where safe, Identity-only caller policy and absence of external provider egress. It never requires plaintext production source data.

Smoke failure stops rollout; rollback is used only when safe for current schema/data state.

## 11. Definition of Done

A capability is complete only when applicable:

- behavior is in correct Domain/Application ownership and Hexagonal boundaries;
- current coding/SQL/frontend standards are satisfied by actual source;
- required formatter/static/architecture/dependency/CI checks pass;
- contracts are versioned/compatible;
- Flyway/rollout/rollback and query/index/pool effects are reviewed for mutable relational persistence; ADR-0040 instead reviews immutable dataset compiler/format/read-only/query/rebuild compatibility;
- transaction boundaries contain no remote I/O;
- deadlines/retry owner/cancellation/idempotency/concurrency are explicit;
- state+event uses Outbox and consumers are idempotent where applicable;
- DLQ/replay exists where needed;
- metrics/logs/traces/alerts are present and PII-safe;
- workload identity/mesh/network policies are correct;
- applicable unit/integration/contract/architecture/security/load/recovery/browser tests pass;
- container/supply-chain/Helm/Kubernetes/Istio/edge checks pass;
- no current architecture rule is violated;
- skipped/failed/unavailable required verification is reported.

A task is not reported `completed` when missing required evidence materially prevents confidence.
