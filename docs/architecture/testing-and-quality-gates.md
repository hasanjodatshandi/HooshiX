# Testing and Quality Gates

## 1. Testing strategy

Each service implements applicable tests from these categories:

- Domain unit tests;
- Application use-case tests with fake ports;
- ArchUnit architecture tests;
- PostgreSQL integration tests with Testcontainers;
- Kafka integration tests;
- Redis integration tests where Redis is used;
- gRPC contract tests;
- REST/OpenAPI contract tests where applicable;
- Protobuf compatibility tests;
- Outbox and Inbox/idempotency tests;
- duplicate/retry/restart/DLQ tests;
- Flyway migration tests;
- authorization tests;
- logging/PII-redaction, custom Semgrep logging-policy, canary-sink, and log-injection tests;
- Istio mTLS/workload-identity/AuthorizationPolicy tests;
- timeout and cancellation tests;
- load tests for critical paths;
- BDD acceptance tests for critical business behavior;
- frontend unit/component tests with Vitest + React Testing Library;
- Playwright E2E tests for critical user journeys.

Only applicable integration categories are required; do not create meaningless tests for unused technology.

## 2. Unit tests

Domain and Application unit tests do not start Spring. They are deterministic, fast, and independent of networks/databases. Application use cases are instantiated directly with fake/mock ports.

## 3. Integration tests

Use Testcontainers for real infrastructure behavior where practical. Verify Flyway, persistence adapters, transaction boundaries, outbox/inbox, Kafka, Redis, adapter-level retry/timeout/error handling, and concurrency behavior.

## 4. Contract compatibility

Protobuf uses `buf lint` with STANDARD and `buf breaking` with FILE against main. Field-number reuse is prohibited.

OpenAPI and other externally consumed contracts require compatibility diff/contract checks. No runtime Schema Registry exists in v1.

## 5. BDD policy

BDD is an analysis/acceptance process, not a replacement for unit/integration/contract tests.

Use Gherkin for critical business behavior understandable by Product/QA/Development. Do not encode selectors, SQL, implementation method names, or every CRUD edge case.

Cucumber-JVM runs on the JUnit Platform. Keep a small tag set such as `@critical`, `@smoke`, and `@regression`. Step definitions remain thin. BDD acceptance scenarios run at API/system level whenever practical; driving every scenario through the browser is prohibited because it produces a slow/flaky duplicate UI suite.

## 6. Playwright policy

Playwright Test + TypeScript is the primary browser E2E tool. Use Playwright Test natively; wiring every browser test through Cucumber is not the default. Selected BDD scenarios may share a Playwright driver only when the acceptance contract genuinely requires browser behavior.

Use semantic locators (roles/labels/accessibility text), web-first assertions, auto-waiting, isolated data, and parallel-safe independent tests. Fragile CSS/XPath selectors are prohibited when a stable semantic locator is available.

Fixed sleeps/`waitForTimeout` as synchronization are prohibited. Create test data through APIs/fixtures where practical instead of replaying UI setup for every test. Page objects are used only for repeated interactions and must not become large business-logic containers. Retries must not hide flakiness; every flaky test has an owner and remediation deadline. Screenshots/traces/video are normally captured on failure/retry.

## 7. Executable coding-quality gates — ADR-0069

Every Java service is covered by the executable enforcement defined in `/docs/engineering/build-and-ci-quality-enforcement.md`:

- independent `build.gradle.kts` + Gradle Wrapper + dependency verification metadata;
- Spotless formatting gate;
- SpotBugs production-code analysis;
- ArchUnit `architectureTest`;
- repository-owned Semgrep architecture/security/logging rules with rule fixtures;
- applicable unit/integration/contract/schema tasks;
- GitHub Actions required checks.

Architecture/policy is **DECIDED**, implementation is **REQUIRED**, and evidence remains **NOT VERIFIED** until the real files/tasks/workflows exist and pass against the service source. `ignoreFailures`, broad analyzer exclusions, disabled required tests, or removal of required checks are prohibited as a means to make CI green.

## 8. Per-service CI path

```text
Compile + Unit Tests
+ Spotless / ArchUnit / SpotBugs / Semgrep (parallel where safe)
-> Integration Tests
-> Contract Tests
-> Schema Compatibility
-> Dependency / Secret / Vulnerability Scan
-> Helm / Kubernetes Validation
-> Container Build
-> SBOM + Signing / Provenance
-> Container Image Scan
```

No mandatory stage proceeds after a required predecessor fails.

### Pipeline execution model

The arrows above express dependency/gating order, not mandatory serial wall-clock execution. Independent checks SHOULD run in parallel when their inputs are available and correctness is preserved. Use safe Gradle/CI caching and test sharding where appropriate. Mandatory coverage must not be reduced merely to shorten CI duration.

## 9. System release path

```text
Deploy to Staging
-> Backend Smoke Tests
-> BDD Acceptance Tests
-> Critical Playwright Tests
-> Deploy the same artifact to Production
-> Production Smoke Tests
```

Production uses the exact validated artifact; rebuilding between staging validation and production is prohibited.

## 10. Security and supply-chain gates

Applicable pre-build controls:

- SAST;
- dependency vulnerability scan;
- secret scan;
- Gradle dependency verification;
- license policy where required.

Post-build controls:

- signed CycloneDX SBOM generated for the final image and indexed by image digest;
- Syft/Grype (or approved equivalent) component/CVE correlation;
- provenance;
- image signing;
- container vulnerability scan;
- vulnerability/VEX exceptions require owner, reason, expiry, and review.

Deployment is blocked when vulnerabilities exceed approved policy.

ADR-0065/ADR-0068 make vulnerability response continuous rather than build-time only:

- final-image SBOM/image scanning runs before promotion;
- newly introduced Critical findings block merge/promotion without an approved expiring exception;
- High findings with an available fix block production promotion without an approved expiring exception;
- digest-indexed staging/production inventory is rescanned at least every 6 hours with refreshed vulnerability data;
- newly disclosed Critical/known-exploited findings page Security + service owner and target mitigation/patch <=24h;
- High production findings target <=48h;
- Critical exceptions expire <=7d and High exceptions <=30d; expiry immediately stops authorizing promotion and escalates production exposure by severity;
- CISA KEV plus approved CVE/ecosystem/vendor advisory inputs trigger targeted correlation/rescan; no feed is treated as guaranteed zero-day detection;
- transitive-component remediation accountability belongs to the owner of the deployed artifact; Platform owns shared base/runtime artifacts and Security owns scanning/escalation policy;
- scanners never unauditedly kill running pods; containment rolls a newly signed patched artifact.

## 11. Kubernetes/mesh gates

Applicable checks:

- `helm lint`;
- render all environments;
- Kubernetes schema validation;
- policy checks for security context/resources/probes/NetworkPolicy;
- rendered-secret scans;
- Gateway API/Traefik checks;
- WAF rule/exception tests;
- `istioctl analyze`;
- PeerAuthentication/AuthorizationPolicy/waypoint validation;
- Ambient enrollment and ServiceAccount checks;
- manifest diff.

## 12. Baseline Gradle flow

Typical order:

```bash
./gradlew spotlessCheck compileJava test
./gradlew architectureTest integrationTest contractTest
./gradlew schemaCompatibilityCheck
./gradlew spotbugsMain dependencyCheckAnalyze
# repository Semgrep blocking rules
```

Exact task names are repository-defined. Never claim a task exists without checking the current build. CI formatting uses `spotlessCheck`, never mutating `spotlessApply`. The container/distributable build is reproducible enough to promote the exact same signed immutable image digest from staging to production; rebuilding for production is prohibited.

## 13. Fast feedback and heavy verification

The developer inner loop should run the smallest trustworthy test scope. Domain/Application tests remain infrastructure-free; adapter integration uses Testcontainers where practical. Full load, chaos, DR, failover, certificate-rotation, and complete platform exercises run at the release/scheduled frequency required by their ADRs and risk class rather than on every local edit.

Production-only infrastructure validation remains mandatory before the release gate that depends on it. See `/docs/engineering/developer-workflow.md`.

## 14. Production resilience/security gates

The production candidate additionally proves applicable current decisions:

- ADR-0041/ADR-0054 semantic-quota atomicity, pseudonymous keys, anti-lockout,
  dual-clock/skew failure, no TTL security reset, Redis Sentinel failover, and
  >=2x projected peak quota load;
- ADR-0039/ADR-0056/ADR-0062/ADR-0066 Authorization no-cache/no-retry semantics, safe
  prechecks, fair-share overload shedding, fail-closed breaker opening and real
  half-open `CheckPermission` probes, paired-window burn alerts, p95<=100ms/p99<=200ms
  SLO capacity, three-replica/PDB behavior, database failover, and absence of
  routine duplicate BFF permission checks;
- ADR-0063/ADR-0066 machine-readable operation-level dependency registry schema/coverage/render checks, no implicit fallback, composite-edge semantics, and resilience-class contract tests for every synchronous production edge;
- ADR-0043 Notification local key-ring rotation, refresh, corruption, readiness,
  erasure, and proof that OpenBao is not called on the message hot path;
- ADR-0044 Kafka KRaft broker/controller failure, RF/minISR/acks durability,
  outbox replay into a clean Kafka cluster, and consumer idempotency;
- ADR-0045 PKCE/state/nonce/exact redirect/session fixation/CSRF/CORS/security
  header/browser-token tests;
- ADR-0046 Kyverno immutable-digest/signature/provenance/SBOM admission tests;
- ADR-0065/ADR-0068 final-image vulnerability gates, exact/expiring exception escalation, <=2h advisory/KEV ingestion, <=6h deployed inventory rescan, targeted advisory-triggered rescans, transitive owner routing, and patched-image rebuild/sign/attestation tests;
- ADR-0047 Notification crash/failover tests around the synchronously committed
  `DISPATCHING` transition and proof that stale attempts reconcile rather than
  blind redispatch;
- ADR-0048 CloudNativePG planned/unplanned failover, synchronous durability,
  failover-quorum refusal, pool-budget validation, WAL archive, PITR restore,
  monthly isolated restore, and quarterly DR evidence;
- ADR-0057 per-service physical PostgreSQL isolation, forced tenant RLS, runtime
  role restrictions, independent backup permissions, and per-service restore;
- ADR-0064/ADR-0067 reusable CloudNativePG fleet rendering, independent service backup namespaces/credentials, queryable monthly restore evidence, failed-drill promotion freeze, one-cluster-at-a-time upgrade waves, safe reversible rollback, and explicit no-unsafe-downgrade tests;
- ADR-0058 end-to-end data-subject erasure, legal-hold, non-PII receipt, and
  restore-then-re-erasure tests;
- ADR-0059 upstream DDoS provider/runbook and authorized edge saturation tests;
- ADR-0060 Teleport JIT SSO/MFA/two-approval/expiry/direct-access-denial/session
  audit tests;
- ADR-0061 Semgrep logging rules, OTel redaction, canary downstream-sink absence,
  and runtime detector alert-safety tests;
- Java 25 JFR load/soak evidence for remaining virtual-thread native/FFM pinning
  and carrier/resource starvation; no blanket `synchronized` ban;
- current Liara SMTP STARTTLS/authentication/outcome-classification tests;
- IPPanel ADR-0049 contract fixtures for accepted identifier/report statuses,
  ambiguous submission/no-blind-retry, bounded polling/backpressure, credential
  redaction, and proof that the local logging adapter cannot activate in production;
- Technology Baseline compatibility-set validation.

OpenBao v1 is single-node; tests therefore cover snapshot/restore, Shamir unseal,
secret refresh degradation, and recovery time rather than nonexistent OpenBao
leader failover.

## 15. Smoke tests

After deployment verify applicable startup/readiness/health, PostgreSQL/Kafka/Redis connectivity, one critical REST/gRPC flow, isolated event publish/consume, basic authentication/authorization, error/timeout regression, and critical Playwright flows.

Production event smoke tests use isolated test tenants/topics/markers and do not create uncontrolled customer data.

Smoke-test failure stops or rolls back the rollout according to deployment policy.

## 16. Definition of Done

A capability is complete only when all applicable requirements are satisfied:

- business behavior is in the correct Domain/Application layer;
- Hexagonal boundaries and DI rules are preserved;
- ADR-0069 coding standards are satisfied by the actual source, not assumed from documentation;
- Spotless, SpotBugs, ArchUnit, Semgrep, dependency verification, and required GitHub Actions checks pass for Java services;
- contracts are versioned and compatibility checked;
- required Flyway migration exists and rollout/rollback implications are reviewed;
- query/index/pool impact is reviewed where relevant;
- transaction boundaries are explicit and contain no remote I/O;
- deadlines, retries, cancellation, and idempotency are defined;
- required event publication uses Outbox and consumers are idempotent;
- DLQ/replay behavior is defined where applicable;
- metrics/logs/traces are present;
- PII/secret/log-injection behavior is tested, including CR/LF input, safe exception handling, and production debug-logging controls where applicable;
- workload ServiceAccount/Ambient/mTLS/authorization policy is correct;
- applicable unit/integration/contract/architecture/security/load tests pass;
- container and security scans pass;
- Helm/Kubernetes/Istio validation passes;
- probes/resources/NetworkPolicy are valid;
- public surface changes have Traefik/WAF staging validation;
- critical BDD/Playwright tests pass where applicable; flaky tests have an owner/remediation deadline and fragile selector/sleep-based synchronization rules are not violated;
- smoke tests pass when deployment occurred;
- no prohibited architecture rule is violated;
- intentional deviations have an accepted ADR;
- skipped/failed/unavailable required verification is reported.

A task must not be reported `completed` if missing required verification materially affects confidence.

## 17. Architecture report

Every non-trivial implementation task uses the report format defined in `/AGENTS.md` and `../engineering/agent-communication-and-reporting.md`.

### Password credential verification

- Argon2id parameter/encoded-format tests;
- NFC/15..128-code-point boundary tests and no composition-rule regressions;
- compromised-password blocklist integration;
- rehash-on-success upgrade test;
- login subject failed-attempt anti-lockout test;
- password-hash bulkhead saturation/load test proving bounded memory/CPU work;
- no raw password in logs/traces/errors/events.


### Kubernetes active-cluster HA

Before production, verify ADR-0051 with a three-control-plane/etcd health check,
one-control-plane failure, one-worker drain/failure, critical replica placement,
etcd snapshot/restore, and Calico/Istio rescheduling policy tests.

### Access-token signing-key lifecycle

- RS256/RSA-3072 and immutable `kid` tests;
- algorithm-confusion/unknown-key/issuer/audience negative tests;
- next-key prepublication before activation;
- mixed current/previous rotation and atomic verifier reload;
- emergency compromise removal and five-minute token invalidation behavior;
- readiness/file-permission/private-key telemetry/Git leak tests;
- staging rotation exercise.
