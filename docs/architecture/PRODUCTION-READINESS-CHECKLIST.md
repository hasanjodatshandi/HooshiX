# Production Readiness Checklist

Accepted ADRs through ADR-0069 resolve the current architecture decisions identified during the production reviews. This file tracks the remaining **implementation and evidence checks**. It is not permission to invent different architecture or bypass an accepted ADR through configuration.

Production configuration MUST NOT bypass a failed gate.

Architecture decisions in this checklist are already **DECIDED** by accepted ADRs.
For each applicable item:

```text
Architecture: DECIDED
Implementation: REQUIRED
Evidence: NOT VERIFIED until measured/tested
```

A missing evidence result is not permission to redesign or bypass the accepted architecture.

## 1. Kubernetes active-cluster HA

Before claiming production HA under ADR-0051:

- 3 healthy stacked control-plane/etcd nodes;
- stable redundant L4 Kubernetes API endpoint;
- at least 3 schedulable worker nodes;
- critical replicas demonstrably spread across nodes/failure domains;
- one control-plane failure and one-worker drain/failure tests pass;
- N+1 worker capacity for Class A/Authorization projected peak;
- encrypted off-node etcd snapshots and restore procedure are verified.

Status until verified: **platform HA production blocker**.

## 2. Semantic quota enforcement — ADR-0041

Required evidence:

- Redis 1-primary/2-replica/3-Sentinel topology healthy across failure domains;
- TLS, ACL, key-namespace isolation, and `noeviction` verified;
- atomic token-bucket/GCRA multi-dimension tests with no partial consumption;
- ADR-0054 dual-clock refill/skew failure and proof that Redis TTL expiry cannot reset a security budget;
- HMAC pseudonymous-key rotation without budget reset;
- login/MFA-recovery anti-lockout and non-enumeration tests;
- Redis outage/failover fails protected operations closed without converting dependency failures into false quota denials;
- production profiles cannot bypass the limiter;
- >=2x projected peak load with p95<=10ms, p99<=25ms, 75ms ceiling, >=30% memory headroom, zero eviction.

Status until verified: **blocking for ADR-0040-gated entry points**.

## 3. Authorization online dependency — ADR-0039/0056/0062/0066

Required evidence:

- >=3 replicas, PDB `minAvailable=2`, topology spread;
- `CheckPermission` availability >=99.95%;
- p95<=100ms and p99<=200ms at >=2x projected peak (75/150ms steady-state engineering target);
- exact 300ms/one-attempt/wait-for-ready-off/no-cache/no-retry/no-fallback behavior;
- bounded in-flight concurrency and no unbounded internal queue;
- repeated OPEN intervals de-correlate across caller replicas with bounded reopen backoff; tenant tier does not alter breaker semantics;
- HALF_OPEN permits only one real `CheckPermission` probe in flight per caller breaker; three consecutive infrastructure-successful probes close and any infrastructure failure reopens;
- machine-readable dependency registry schema/coverage/render checks pass for the Authorization edge and every other production synchronous edge;
- Hikari acquisition p99<25ms at target load, acquisition ceiling<=50ms, permission SQL ceiling<=100ms;
- no synchronous downstream dependency other than Authorization-owned PostgreSQL;
- routine duplicate BFF permission check absent;
- one replica/node loss and CloudNativePG primary failover under sustained Authorization traffic preserve fail-closed semantics and objectives.

Status until verified: **blocking for protected-operation production traffic**.

## 4. PostgreSQL HA and recovery — ADR-0048/0057/0064/0067

Required evidence:

- CloudNativePG 3-instance topology across independent schedulable failure domains where infrastructure permits;
- automatic safe primary failover;
- synchronous acknowledgement from one failover-eligible replica for required durable writes;
- planned switchover and unplanned primary-failure tests, ordinary failover target <=60s;
- ADR-0053 database isolation: a distinct database and runtime/migration credentials per persistent microservice, default/public privilege review, and negative `CONNECT`/object-access tests against every other service database;
- aggregate application Hikari maxima <=70% of PostgreSQL `max_connections`, with >=30% reserve;
- continuous WAL archive healthy and measured against PostgreSQL RPO<=5m;
- encrypted off-site daily physical base backup;
- 35-day PITR restore test;
- monthly retained recovery artifacts for 12 months;
- monthly isolated restore and quarterly full DR evidence;
- each monthly restore records backup/WAL source, requested/actual recovery timestamp, measured RPO/RTO, PostgreSQL/CNPG/Flyway versions, integrity/RLS/erasure checks, runbook revision, owner, and PASS/FAIL;
- fleet dashboard shows last successful restore, measured RPO/RTO, next due date, and overdue/failed status per service;
- a failed monthly restore freezes ordinary promotion for the affected service until a replacement drill passes;
- upgrade tests prove staging failure stops later waves, reversible GitOps/operator rollback works when supported, and irreversible/major database changes are never automatically downgraded;
- a tested single-service database recovery procedure that does not overwrite unrelated live service databases;
- Notification acknowledged `DISPATCHING` commit survives every permitted automatic failover and never causes blind redispatch.

Status until verified: **platform production blocker**.

## 5. Kafka durability and cold-DR replay — ADR-0044

Required evidence:

- KRaft 3 dedicated controllers + 3 brokers across failure domains;
- critical topics: RF=3, minISR=2, unclean leader election disabled;
- critical producers: `acks=all`, idempotence enabled;
- TLS, authenticated per-service principals, ACLs, quotas;
- every event class explicitly `OUTBOX_REPLAYABLE`, `RECONSTRUCTABLE`, or `NON_CRITICAL`;
- replayable critical publication evidence retained 35 days, subject to privacy/erasure/legal-hold policy;
- consumer dedupe/inbox evidence covers the required replay horizon;
- clean Kafka can be recreated from Git and critical flows replayed/reconstructed from authoritative service state;
- quarterly representative Kafka reconstruction/replay exercise scheduled and passing.

Status until verified: **blocking for critical production async flows**.

## 6. Browser/BFF security — ADR-0045

Required evidence:

- OIDC Authorization Code + PKCE S256, state and nonce replay/mismatch tests;
- exact registered redirect URI and open-redirect negative tests;
- `__Host-sajtech-session` cookie flags and session-fixation/rotation tests;
- server-side BFF session behavior and encrypted refresh-credential storage where used;
- Origin + synchronizer-token CSRF positive/negative tests;
- same-origin/default-deny CORS and exact allow-list negatives where cross-origin is explicitly needed;
- CSP/HSTS/nosniff/referrer/Permissions-Policy checks;
- browser storage scan proves no provider, refresh, Identity, or internal service token is exposed.

Status until verified: **public-internet production blocker**.

## 7. Supply-chain admission and vulnerability response — ADR-0046/0065/0068

Required evidence:

- CI emits CycloneDX SBOM, signed provenance, vulnerability result, and Cosign signature/attestations for immutable image digests;
- staging and production promote the exact same digest;
- Kyverno stable `policies.kyverno.io/v1` image-validation policy deployed with >=3 admission replicas, PDB, topology spread before fail-closed mode;
- audit rollout completed before production `Deny` enforcement;
- unsigned, wrong-signer, wrong-provenance/source/builder, mutable-tag-only, and unapproved-registry images are denied;
- emergency path still requires an approved trusted signer and audited bounded exception; no unsigned production bypass;
- advisory/KEV ingestion runs <=2h and targeted correlation/rescan is triggered for affected deployed SBOMs;
- CISA KEV/known-exploited or Critical production findings page Security + service owner; High findings follow the <=48h remediation target;
- expired exceptions immediately stop authorizing promotion; running production exposure escalates by severity and is never silently renewed;
- transitive dependency findings route to the deployed service owner; shared base/runtime findings route to Platform plus all consuming services;
- stale mandatory feed/scanner state fails promotion closed.

Status until verified: **production deployment-security blocker**.

## 8. Notification simplified runtime — ADR-0043 + ADR-0047

Required evidence:

- local mounted AES-256-GCM key-ring rotation, historical decrypt, refresh, corruption, staleness, and readiness tests;
- no OpenBao RPC during Notification acceptance, dispatch, retry, reconciliation, or erasure;
- former clock-health sidecar/Chrony `hostPath`/9095 polling/fence/coordinator resources absent from current production desired state;
- PostgreSQL-authoritative acceptance/deadline boundary tests;
- crash before and after durable `DISPATCHING` commit;
- CloudNativePG failover around dispatch commit;
- stale/unknown `DISPATCHING` always enters reconciliation/ambiguity handling and never blind redispatch;
- exact-content retry, terminal-state immutability, and escrow-erasure invariants remain intact.

Status until verified: **Notification production blocker**.

## 9. OpenBao operational recovery

OpenBao intentionally remains single-node in v1 because ADR-0043 removes it from normal Notification request/dispatch hot paths and applications consume validated local mounted key material.

Required evidence:

- exact 2.6.1 image digest;
- Shamir 3-share/2-threshold custody runbook and access test;
- encrypted hourly snapshot outside primary PVC plus pre-upgrade snapshots;
- restore + manual unseal exercise;
- External Secrets/key refresh staleness alerting and fail-closed behavior after allowed local-key staleness;
- recovery fits the ADR-0027 platform RTO sequence;
- Istio Root CA private key absent from Kubernetes and OpenBao.

Status until verified: **secret-platform production blocker**.

A 3-node OpenBao HA decision is required only if measured refresh/recovery availability, compliance, or RTO evidence shows the current control-plane topology is insufficient. Do not add HA merely for symmetry.

## 10. WAF and edge enforcement — ADR-0024

Required evidence:

- only public application route is Traefik -> edge-waf -> Web BFF;
- NetworkPolicy/Istio negative tests block direct Traefik/Internet-to-BFF application paths;
- two WAF replicas when >=2 schedulable worker nodes;
- >=7 representative days DetectionOnly;
- reviewed narrow CRS exceptions;
- blocking-mode load/latency test, including Class-A paths;
- endpoint-specific bounded body-inspection policy for large uploads when needed;
- no request/response body, credential, token, or PII logging.

Status until verified: **public-internet production blocker**.

## 11. Iran SMS / SMS MFA — ADR-0049

Required evidence:

- IPPanel Edge Webservice-mode sandbox fixture pins the definitive accepted-response correlation field/type;
- recipient-level report fixtures pin all current status mappings, including delivered evidence;
- exact Notification-rendered SMS text is submitted; provider-managed Pattern rendering is absent;
- dedicated token from OpenBao, 90-day rotation, emergency revocation, bounded egress;
- 500ms connect / 1500ms total request timeout / no HTTP automatic retry verified;
- timeout/connection-loss/malformed/unproven acceptance maps to `AMBIGUOUS` and never blind resubmits;
- bounded 15s -> 15m receipt polling/backpressure cannot create provider poll storms;
- production profile cannot activate `LoggingSmsProviderAdapter`;
- SMS MFA additionally passes ADR-0041 quotas and ADR-0038 MFA security tests.

Status until verified: **blocking only for SMS-dependent production features, including SMS MFA; Email-only capabilities may proceed independently**.

## 12. Platform compatibility / CNI / immutable artifacts — ADR-0050

Required evidence:

- every deployed platform/application image is pinned by immutable digest;
- Technology Baseline and compatibility matrix are revalidated against supported upstream combinations at release time;
- Kubernetes/Istio Ambient/Calico NetworkPolicy positive and negative flows pass, including required HBONE/health paths;
- CloudNativePG/cert-manager/Kyverno/Traefik/Gateway API/WAF combinations render and validate cleanly;
- `istioctl analyze`, Helm/Kustomize/Kubernetes policy checks pass;
- both staging and production desired state render without secret values;
- rollback manifests/digests remain available;
- an upstream-EOL component is upgraded to a supported compatible release before rollout rather than deployed solely because an older baseline names it.

Status until verified: **platform release blocker**.

## 13. RS256 signing-key lifecycle — ADR-0052

Before user-token-protected production traffic:

- RSA-3072 private signing keys exist only in Identity/OpenBao local delivery;
- next public key is deployed to every verifier before activation;
- local GitOps public verification bundle loads atomically;
- algorithm-confusion/unknown-kid/issuer/audience negative tests pass;
- 90-day normal rotation and emergency compromise rotation are exercised;
- no normal token verification network call to Identity/OpenBao/remote JWKS;
- private-key Git/telemetry leak tests pass.

Status until verified: **authentication trust production blocker**.

## 14. Java coding/build/CI enforcement — ADR-0069

Required evidence for each Java service before claiming implementation compliance:

- independent `settings.gradle.kts`, `build.gradle.kts`, Gradle Wrapper, and dependency verification metadata exist and work from a clean checkout on Java 25;
- required source sets/tasks are present for applicable `test`, `integrationTest`, `contractTest`, and `architectureTest`;
- `spotlessCheck`, `spotbugsMain`, ArchUnit, and repository Semgrep blocking rules pass;
- custom ArchUnit/Semgrep rules have representative positive/negative fixtures and no broad suppression;
- GitHub Actions required checks enforce the quality gates with least-privilege workflow permissions and pinned third-party actions;
- the service source actually satisfies the coding standard; documentation is not used as compliance evidence;
- production promotion uses the same previously built/signed immutable image digest validated in staging;
- mandatory tests/analyzers are not disabled and `ignoreFailures`/blanket exclusions are absent.

Status until verified: **Java service implementation/release blocker**.

## 15. Final load, chaos, recovery, and release evidence

The exact release candidate passes applicable:

- Class-A/Class-B load and latency tests;
- Authorization/Redis/PostgreSQL/Kafka/WAF/provider capacity tests;
- replica/node/database failover tests;
- security-negative and workload-identity tests;
- backup/PITR/restore evidence;
- smoke, BDD, critical Playwright, rollback verification;
- SLO/error-budget release policy checks.

Use `performance-and-bottlenecks.md` for the current bottleneck register and scale/split triggers.

Status until verified: **final release blocker**.

## Additional security verification gates from ADR-0057..ADR-0061

Architecture is DECIDED; production evidence is still required for:

- per-service physical CloudNativePG isolation, forced tenant RLS, runtime-role
  `NOSUPERUSER NOBYPASSRLS`, independent backup permissions, and per-service PITR;
- end-to-end data-subject erasure including legal hold, service receipts, and
  restore-then-re-erasure before traffic;
- upstream L3/L4 volumetric DDoS provider capability, escalation/runbook,
  connection-pressure controls, and authorized edge saturation test;
- Teleport SSO/WebAuthn JIT access, two-reviewer privileged write elevation,
  automatic expiry, direct-access denial, and session/audit evidence;
- custom Semgrep logging policy, telemetry redaction, seeded PII/secret canary
  absence in the downstream sink, and runtime leakage detector alert safety;
- digest-indexed signed CycloneDX SBOM plus final-image transitive-vulnerability/advisory correlation, expiring-exception escalation, and deterministic artifact ownership;
- Java 25 JFR evidence for remaining native/FFM virtual-thread pinning and scarce
  dependency saturation; `synchronized` itself is not blanket prohibited.
