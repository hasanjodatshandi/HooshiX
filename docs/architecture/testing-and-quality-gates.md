# Testing and Quality Gates — Current State

Testing proves current contracts and failure semantics at the cheapest trustworthy layer. Documentation/configuration is not evidence until the corresponding executable check exists and passes.

ADR-0042 selects `production-single-server`; profile-specific tests supplement, not replace, service/security/data tests.

## 1. Test portfolio

Use the smallest trustworthy layer:

- Domain/application unit tests for business invariants;
- ArchUnit for package/layer/dependency direction;
- adapter/integration tests with real PostgreSQL/Redis/Kafka/SQLite/protocol behavior where required;
- contract tests for gRPC/Protobuf/OpenAPI/provider boundaries;
- migration/RLS/security tests for persistence;
- policy/render tests for Kubernetes/Helm/Istio/Kyverno/NetworkPolicy;
- BDD only for critical business behavior;
- Playwright only for critical browser journeys;
- load/soak/chaos/recovery tests at staging/release/scheduled cadence where they are too heavy for PR;
- static + runtime logging/PII/security leak tests;
- final-image SBOM/signature/provenance/vulnerability tests.

Do not duplicate the same assertion at every layer without a distinct failure class.

## 2. Java/build quality gates

Applicable Java services require, at minimum:

- compile;
- unit tests;
- JUnit integration tests;
- ArchUnit architecture tests;
- Spotless;
- SpotBugs;
- repository Semgrep/static rules;
- Gradle dependency verification/locks;
- dependency/license/security checks required by current CI;
- service-specific contract/migration/security tests.

Do not disable a gate, broaden suppression or set `ignoreFailures` merely to pass CI.

## 3. Contract compatibility

- Protobuf uses Buf lint + breaking checks under current Git-owned contract governance;
- field numbers are never reused;
- OpenAPI compatibility/bounds/security headers/errors are tested for public BFF APIs;
- provider adapters have bounded request/response/ambiguity tests;
- schema/contract changes include producer/consumer rollout compatibility.

Kafka remains async transport; REST is not silently substituted for internal gRPC.

## 4. Persistence tests

For mutable PostgreSQL services:

- Flyway migration from supported prior state to current state;
- released migration immutability;
- expand/migrate/contract compatibility;
- runtime role non-owner `NOSUPERUSER NOBYPASSRLS`;
- cross-service database/role privilege negatives;
- forced RLS + `USING`/`WITH CHECK` negatives;
- pooled transaction-local tenant context across commit/rollback/reuse;
- no remote I/O inside DB transactions;
- index/query-plan/bounds for sensitive/expensive queries;
- connection-pool budget under representative peak.

Single-server additionally tests shared-instance global pool budget/noisy-neighbor behavior and service-isolation negatives inside the same physical PostgreSQL process.

ADR-0040 SQLite tests prove immutable/read-only/query-only fixed lookup, no write/DDL/ATTACH/extension loading, server-owned path, bounded cardinality/response, native dependency integrity and rebuild/redeploy recovery.

ADR-0041 Reference Data tests apply only when implementation trigger/release scope is active and prove deterministic importer/bundle, bounded typed reads and no runtime DB/Redis/Kafka/provider authority.

## 5. PostgreSQL recovery tests

Both profiles require continuous WAL/off-site backup/PITR evidence, monthly isolated restore and quarterly cold DR.

Single-server:

- whole shared physical cluster restores to an isolated environment at requested PITR point;
- every service DB/Flyway/role/RLS boundary validates;
- required service DB can be extracted/imported through approved recovery flow;
- unrelated current DBs are not destructively restored;
- failed shared restore triggers correct promotion freeze;
- `pg_dump + cron` is never treated as substitute for WAL/PITR evidence.

HA additionally proves service-cluster failover/independent restore identities.

## 6. Kafka/event tests

Both profiles:

- Transactional Outbox atomicity;
- relay duplicate/restart behavior;
- consumer idempotency/Inbox atomicity where required;
- offset-after-durable-effect behavior;
- bounded retry/DLQ;
- stable event identity;
- >=35-day critical replay/dedup evidence;
- clean broker rebuild/replay/reconstruction;
- TLS/authentication/ACL/quota negatives.

Single-server additionally proves combined KRaft broker/controller configuration, RF=1/minISR=1/acks=all/idempotence, internal-topic compatibility and explicit non-HA outage behavior.

HA proves RF=3/minISR=2 broker/controller failure behavior.

## 7. Security Redis tests

Common:

- TLS;
- ACL/key-namespace isolation;
- `noeviction`;
- atomic quota dimensions;
- HMAC pseudonymous keying;
- dual trusted time and exact skew failure;
- no TTL-based security reset;
- anti-lockout;
- outage/time-source fail-closed behavior;
- memory/cardinality bounds.

Single-server additionally proves AOF enabled with `appendfsync everysec`, restart recovery, session-loss re-authentication and no false Sentinel/failover claim.

HA proves Sentinel/replica failover.

## 8. Authentication, MFA and BFF tests

Infrastructure profile does not reduce these tests.

Identity/BFF suites cover current:

- password/Google/external identity binding and non-enumeration;
- compromised-password fail-closed screening;
- TOTP continuation and downgrade prevention;
- MFA enroll/disable/recovery proof/quota/audit;
- JWT key/audience/time rules;
- OIDC state/nonce/PKCE/pre-auth/replay/redirect rules;
- browser token isolation;
- server-side session entropy/rotation/revocation/index/idle/absolute lifetime;
- retained-refresh encryption/key rotation/stale-source fail close;
- CSRF/Origin/Fetch Metadata/same-origin CORS;
- CSP/cache/security headers;
- exact-audience BFF token brokerage;
- tenantless/onboarding authority restrictions;
- erasure/session shutdown behavior.

Email/SMS MUST NOT pass as an arbitrary weaker substitute for active TOTP where current policy requires TOTP.

## 9. Authorization tests

- final protected-resource permission path uses one authoritative `CheckPermission`;
- ALLOW only on successful authoritative completion;
- denial/error/timeout/breaker/open/overload do not fabricate ALLOW;
- no permission-result cache/Kafka/stale fallback/retry;
- safe local checks reject only;
- caller/operation bulkhead and breaker recovery tests;
- admin privilege-escalation prevention;
- owner-safety concurrency;
- platform capability cannot bypass tenant/resource authority;
- exact dependency-registry coverage;
- positive/negative Istio policy cases.

Single-server capacity tests include shared-host contention; security semantics do not change.

## 10. Kubernetes/K3s profile tests

Single-server:

- exact K3s/Kubernetes artifact/version/integrity;
- one server/workload node render;
- embedded SQLite control-plane datastore;
- secrets encryption;
- Flannel disabled;
- K3s network-policy controller disabled;
- Calico active and policy negatives pass;
- bundled K3s Traefik/ServiceLB disabled;
- repository edge resources remain authoritative;
- one application replica/HPA disabled/availability PDB disabled render;
- encrypted off-host K3s datastore+token artifact;
- clean GitOps rebuild;
- whole-host reboot/recovery.

HA keeps current control-plane/worker/quorum/node-loss/topology-spread tests.

## 11. Istio tests

Both profiles:

- STRICT mTLS positive/plaintext negative;
- exact ServiceAccount identity;
- least-privilege AuthorizationPolicy positive/negative;
- NetworkPolicy/HBONE compatibility;
- `istioctl analyze`;
- no duplicate retry ownership.

Single-server additionally runs representative complete-stack benchmark for `istiod`, CNI, `ztunnel`, latency/throughput/connection pressure/OOM and Calico interaction, with >=30% validated resource headroom. Failed capacity blocks production; it does not permit silent mesh disablement.

Waypoints require separate L7 need/capacity/security evidence.

## 12. Kyverno/supply-chain tests

Both profiles:

- digest-only image;
- approved signer positive + wrong/unsigned negatives;
- provenance positive/missing/invalid;
- signed CycloneDX SBOM positive/missing/invalid;
- critical privileged/host-network/`hostPath`/securityContext negatives;
- policy-authoring RBAC;
- disabled/unneeded HTTP context;
- approved external-context destination/timeout/response/credential/SSRF negatives;
- production fail-closed admission.

Single-server additionally verifies that the reduced policy inventory still protects every mandatory control and one-replica admission outage does not create bypass. Audit-only promotion is prohibited.

## 13. Human privileged access tests

Common:

- no standing administrator/database-superuser/root authority;
- attributable identity;
- two-reviewer write elevation;
- automatic <=30m write expiry;
- bounded read-only elevation;
- break-glass exercise;
- durable protected audit.

Single-server:

- management-path-only SSH;
- root/password/shared-account/shared-key negatives;
- hardware-backed OpenSSH FIDO2 user-presence/user-verification positives and negatives;
- authentication does not itself grant admin;
- JIT privilege grant/expiry;
- `sudo` I/O/session audit;
- OS audit for authentication/process/privilege/security-config changes;
- Kubernetes/database audit;
- off-host append-only/tamper-resistant audit integrity and requester access denial;
- audit export failure handling;
- `.bashrc`/shell history cannot satisfy session-audit evidence.

HA runs current Teleport SSO/WebAuthn/JIT/session-recording tests.

## 14. OpenBao tests — unchanged

ADR-0042 does not change OpenBao.

Continue current:

- exact OpenBao version/topology checks;
- Shamir/unseal/recovery exercise;
- encrypted snapshot/restore;
- Kubernetes Auth/External Secrets flow;
- mounted/local key rotation/reload;
- stale-source/fail-closed behavior;
- no per-request OpenBao hot-path regression;
- secret scans proving no Git/image/values/log/trace/metric/CI leakage.

A profile change fails review if it removes/replaces/bypasses OpenBao without a separate current security decision.

## 15. Edge/WAF tests

- upstream volumetric protection evidence;
- external L4 -> repository Traefik -> Caddy/Coraza -> BFF route;
- direct Internet->BFF and Traefik->BFF WAF-bypass negatives;
- K3s bundled Traefik/ServiceLB absence in single-server;
- WAF DetectionOnly/tuning/blocking evidence;
- request/body/header bounds independent of WAF;
- public dashboard/insecure API negatives;
- anonymous Reference Data routes still traverse the complete edge when active.

## 16. Logging/PII tests

- source/static logging policy;
- secret/token/cookie/credential leak tests;
- PII masking/HMAC policy;
- CR/LF injection negatives;
- low-cardinality metric labels;
- pipeline redaction;
- synthetic canary/runtime leak detection where required;
- authoritative audit non-drop behavior;
- single-server off-host privileged-audit durability.

## 17. Complete-stack single-server load/recovery test

Run the selected profile with all intended platform/application components at the same time.

Evidence records:

- host CPU/memory/swap/pressure;
- K3s system overhead;
- all JVM CPU/RSS;
- PostgreSQL connections/query/WAL/checkpoint/backup IO;
- Redis memory/AOF fsync/rewrite;
- Kafka memory/log IO/produce/fetch/lag;
- Istio resources/latency;
- Kyverno admission;
- edge/WAF;
- observability;
- filesystem/free-space/IO latency;
- reboot/recovery.

Required outcome:

- no OOM kill;
- no sustained swap pressure;
- no memory-pressure eviction;
- >=30% validated CPU+memory headroom at approved peak;
- applicable >=2x projected peak validation for critical/security paths;
- safe IO/free-space under concurrent WAL+AOF+Kafka+telemetry;
- no security/admission/backup bypass required to pass;
- no fail-open behavior during reboot/recovery.

A `2 vCPU / 3-4 GiB RAM` host is not accepted without this evidence.

## 18. CI/CD ordering

Recommended authority order:

```text
format/static/architecture
-> unit
-> contract/schema
-> focused integration/security/migration
-> image build + SBOM/sign/provenance/vulnerability checks
-> Helm/Kubernetes/Istio/Kyverno render/policy/secret checks
-> staging smoke/critical browser
-> profile-specific load/recovery/chaos evidence
-> production approval
```

Trusted privileged workflows MUST NOT execute unreviewed PR-controlled code/config with secrets/write tokens/protected environments. Artifacts from untrusted contexts are verified for repository/event/source SHA/producer/integrity before privilege is granted.

## 19. Definition of Done

A non-trivial change is not complete until applicable evidence proves:

- architecture/service boundary compliance;
- contract compatibility;
- migration/transaction correctness;
- timeout/retry/idempotency/failure behavior;
- security/authorization/tenant isolation;
- workload identity/network policy;
- logging/PII safety;
- observability;
- deployment/render/security policy;
- rollback/fail-forward/recovery behavior;
- selected production profile consistency.

Documentation-only architecture work may establish target decisions, but it MUST NOT be reported as runtime production readiness when executable implementation/load/recovery evidence does not exist.
