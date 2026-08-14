# Testing and Quality Gates — Current State

Testing proves current contracts and failure semantics at the cheapest trustworthy layer. Documentation/configuration is not evidence until the corresponding executable check exists and passes.

## 1. Test portfolio

Each service uses applicable layers:

- Domain unit tests;
- Application use-case tests with fake ports, no Spring;
- ArchUnit architecture tests;
- PostgreSQL/Flyway integration tests with Testcontainers;
- Kafka/Redis integration tests where used;
- gRPC/REST/OpenAPI/Protobuf contract compatibility tests;
- Outbox/Inbox/idempotency/duplicate/restart/replay/DLQ tests;
- authorization/tenant/RLS/workload-identity/security tests;
- timeout/cancellation/retry/bulkhead/concurrency tests;
- logging/PII/log-injection/Semgrep/canary-sink tests;
- load/failover/chaos/recovery tests for critical paths;
- BDD acceptance tests for critical business behavior;
- frontend unit/component/accessibility tests;
- Playwright critical browser journeys.

Do not create meaningless test categories for unused technology.

## 2. Unit and integration rules

Domain/Application unit tests instantiate code directly and do not start Spring or depend on network/database state.

Integration tests use real infrastructure where the behavior under test depends on it. Testcontainers is preferred for PostgreSQL/Kafka/Redis adapters. Verify transaction boundaries, Flyway, concurrency/locking, outbox/inbox, retry/error mapping, and security constraints rather than only happy-path CRUD.

Tests are deterministic and parallel-safe where intended. Fixed sleeps and shared mutable global fixtures are prohibited as synchronization mechanisms.

## 3. Contract compatibility

Protobuf contract governance:

```text
buf lint: STANDARD
buf breaking: FILE against current main/approved compatibility base
```

Field-number reuse is prohibited. Generated code remains derived from canonical contracts.

OpenAPI and other externally consumed contracts use compatibility diff/contract checks. No runtime Schema Registry exists in v1.

## 4. BDD and Playwright

BDD/Gherkin describes critical business behavior understandable by Product/QA/Engineering; it does not encode selectors, SQL, method names, or every CRUD edge case. Cucumber-JVM runs on JUnit Platform. Step definitions stay thin.

Playwright Test + TypeScript is the primary browser E2E tool. Use semantic locators, web-first assertions, auto-waiting, isolated data, and parallel-safe tests. Fragile CSS/XPath and fixed `waitForTimeout` synchronization are prohibited when semantic alternatives exist. Retries never redefine flakiness as passing; flaky tests have owner and remediation deadline.

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
- transitive dependency accountability follows the deployed artifact owner;
- scanners do not unauditedly kill running pods and never authorize unsigned/unprovenanced images.

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
- Kyverno CEL HTTP-context disabled-by-default checks and, when an approved external lookup exists, bounded destination/timeout/response/failure tests plus loopback/link-local/cloud-metadata/unreviewed-private/arbitrary-caller-target SSRF negatives and NetworkPolicy-constrained egress.

## 9. Production resilience/security evidence

### Semantic quotas — ADR-0024

Prove atomic multi-dimension enforcement, HMAC pseudonymous keys, anti-lockout sequencing, dual trusted time/<=2s skew, no TTL security reset, 75ms one-attempt fail-closed semantics, Redis Sentinel failover/outage, exact Authorization semantic-mutation cost/no-refund behavior, and >=2x projected peak load.

### Authorization — ADR-0013/0026/0032/0036

Prove the complete Authorization contract, not only the hot-path happy case:

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

### Dependency registry — ADR-0033/0036

Validate `dependency-criticality.yaml` schema, duplicates/orphans/coverage, generated Markdown view, current policy-reference anchors including Authorization platform/lifecycle edges, one retry owner, no implicit fallback, and composite-edge semantics.

### Notification — ADR-0006/0007/0014/0018/0020

Prove local key-ring rotation/refresh/corruption/erasure with no hot-path OpenBao RPC, request replay/conflict, exact-content lifecycle, PostgreSQL-authoritative deadlines, durable `DISPATCHING`, crash/failover no-blind-redispatch, Liara SMTP outcome classification, IPPanel accepted/report fixtures, ambiguity/no-blind-retry, and bounded polling.

### PostgreSQL — ADR-0019/0027/0034/0037

Prove per-service physical/database/credential/backup isolation, forced RLS/runtime-role restrictions, transaction-local parameterized tenant context with fail-closed missing/malformed behavior, pooled-connection reuse across different tenants after commit/rollback with no context leakage, synchronous required durability, pool budgets, planned/unplanned failover, WAL/PITR, monthly restore evidence, quarterly DR, failed-drill promotion freeze, one-cluster upgrade waves, and no unsafe downgrade.

### Kafka — ADR-0015

Prove KRaft broker/controller failure, RF/minISR/acks/idempotence, TLS/ACL/quotas, 35-day critical publication/dedup evidence, clean-cluster replay/reconstruction, and consumer duplicate/restart safety.

### Browser — ADR-0016

Prove PKCE/state/nonce/exact redirects, session fixation/rotation, secure cookie, CSRF Origin/token, CORS, security headers, browser-token absence, service-worker/private-cache restrictions, and critical accessibility/RTL/browser flows where affected.

### Edge/DDoS — ADR-0001/0029

Prove the mandatory upstream L3/L4 mitigation/scrubbing -> redundant external L4 load balancing -> Traefik -> WAF -> Web BFF path, direct-bypass denial, controlled blocking/load behavior, no sensitive edge logging, provider capability/escalation, connection-pressure telemetry, and authorized saturation exercise.

### Secrets/access/logging

Prove OpenBao snapshot/restore/Shamir unseal and secret-refresh behavior, Teleport JIT SSO/WebAuthn/two-reviewer write elevation/expiry/direct-access denial/session audit, Authorization platform-profile assignment/revocation JIT audit controls, and ADR-0031 Semgrep + pipeline redaction + canary sink + runtime detector safety.

### Java 25 Virtual Threads

Use JFR/load/soak evidence for native/FFM pinning, contention, and scarce dependency saturation. `synchronized` itself is not blanket prohibited.

## 10. Smoke tests

After deployment, verify applicable startup/readiness/health, database/Kafka/Redis connectivity, one critical REST/gRPC flow, isolated event publish/consume, authentication/authorization, timeout/error behavior, and critical Playwright flows. Event smoke uses isolated test tenants/topics/markers and does not create uncontrolled customer data.

Smoke failure stops rollout; rollback is used only when safe for current schema/data state.

## 11. Definition of Done

A capability is complete only when applicable:

- behavior is in correct Domain/Application ownership and Hexagonal boundaries;
- current coding/SQL/frontend standards are satisfied by actual source;
- required formatter/static/architecture/dependency/CI checks pass;
- contracts are versioned/compatible;
- Flyway/rollout/rollback and query/index/pool effects are reviewed;
- transaction boundaries contain no remote I/O;
- deadlines/retry owner/cancellation/idempotency/concurrency are explicit;
- state+event uses Outbox and consumers are idempotent;
- DLQ/replay exists where needed;
- metrics/logs/traces/alerts are present and PII-safe;
- workload identity/mesh/network policies are correct;
- applicable unit/integration/contract/architecture/security/load/recovery/browser tests pass;
- container/supply-chain/Helm/Kubernetes/Istio/edge checks pass;
- no current architecture rule is violated;
- skipped/failed/unavailable required verification is reported.

A task is not reported `completed` when missing required evidence materially prevents confidence.
