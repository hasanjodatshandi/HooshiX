# Testing and Quality Gates — Current State

Testing proves current contracts and failure semantics at the cheapest trustworthy layer. Documentation/configuration is not evidence until the corresponding executable check exists and passes.

ADR-0042 selects `production-single-server`; profile-specific tests supplement, not replace, service/security/data tests. ADR-0044 makes observability part of the first executable service Definition of Done. ADR-0045 defines the current DevSecOps source/secret/dependency-advisory/final-artifact toolchain.

## 1. Test portfolio

Use the smallest trustworthy layer:

- Domain/Application unit tests;
- ArchUnit package/layer/dependency tests;
- focused adapter/Testcontainers tests for PostgreSQL/Redis/Kafka/SQLite/protocol behavior;
- gRPC/Protobuf/OpenAPI/provider contract tests;
- Flyway/RLS/security tests;
- Gitleaks current-tree/Git-history secret-scanning tests;
- OSV-Scanner declared/locked dependency advisory tests;
- Kubernetes/Helm/Istio/Kyverno/NetworkPolicy render/policy tests;
- static + runtime logging/PII/cardinality tests;
- observability propagation/export/failure tests;
- BDD only for critical shared behavior;
- Playwright only for critical browser journeys;
- load/soak/chaos/recovery at staging/release/scheduled cadence;
- final-image Syft/Grype/Cosign/SBOM/signature/provenance/advisory tests.

Do not duplicate the same assertion at every layer without a distinct failure class.

## 2. Java/build gates

Applicable Java services require compile, unit/integration tests, ArchUnit, Spotless, SpotBugs, Semgrep/SAST, Gitleaks current-tree/history scanning, Gradle dependency verification/locks, OSV-Scanner declared/locked dependency advisory scanning, contract/migration/security tests, and Day-One observability tests.

Semgrep, Gitleaks, Gradle verification, and OSV-Scanner protect different failure classes. OSV early dependency advisory scanning is not final-image vulnerability evidence; Syft+Grype own that release boundary.

Do not disable a gate, broaden suppression, or set `ignoreFailures` merely to pass CI.

## 3. Contract compatibility

- Protobuf: Buf lint + breaking; field numbers never reused.
- OpenAPI: compatibility, input/output bounds, security headers/errors.
- Provider adapters: bounded request/response/ambiguity behavior.
- Schema/contract changes: producer/consumer rollout compatibility.
- Kafka remains async transport; internal request/reply is not silently switched to broker messaging.

## 4. Persistence and recovery

Mutable PostgreSQL services prove Flyway evolution, role isolation, forced RLS, transaction-local tenant context across pooled reuse, no remote I/O inside DB transactions, query/index bounds, and connection budgets.

Single-server additionally proves shared-instance pool/noisy-neighbor behavior, isolated whole-cluster PITR, service-specific controlled recovery, and no destructive restoration of unrelated current DBs.

`pg_dump + cron` is not recovery evidence for the production PITR requirement.

## 5. Compromised Password tests

ADR-0040 evidence includes:

- official HIBP Pwned Passwords SHA-1 acquisition/provenance;
- all official prefix ranges or equivalent complete-download evidence;
- SHA-1 screening-only and Argon2id password-storage separation;
- exact five-hex prefix, 20-byte SQLite hash, 35-hex suffix reconstruction, positive count;
- zero-count HIBP padding rejection;
- dataset age <=35 days for production readiness;
- full-corpus prefix-cardinality/serialized-size measurement and build/runtime compatibility limit;
- immutable read-only/query-only SQLite; no write/DDL/ATTACH/extension loading;
- no runtime HIBP/provider call or application Internet egress;
- raw password/full SHA-1 non-egress and telemetry negatives;
- stale/corrupt/missing/oversized/incompatible data fails closed;
- complete-corpus warm/cold disk-backed latency/saturation and recovery.

## 6. Reference Data tests

Before an independent `reference-data-service` exists, test the immutable bundle/module source/provenance/integrity/lifecycle/bounds in its owning deployable.

A separate service deployment is blocked unless ADR-0041 trigger evidence exists. When triggered, additionally test typed gRPC contract, exact BFF dependency semantics, wrong-workload/egress negatives, profile-correct deployment, load/SLO, and clean replacement of any in-process serving adapter.

One journey/route group alone is not an implementation trigger.

## 7. Kafka/event tests

Both profiles prove Outbox atomicity, relay duplicate/restart behavior, consumer Inbox/idempotency, offset-after-durable-effect, finite retry/DLQ, stable event identity, required replay/dedup evidence, clean rebuild/replay, TLS/auth/ACL/quota negatives, and Protobuf compatibility.

Single-server additionally proves one combined KRaft RF1/minISR1/acks-all/idempotence and explicit non-HA outage behavior.

## 8. Semantic security quota tests

Common:

- Redis TLS/ACL/`noeviction`;
- atomic hard-dimension consumption/no partial commit;
- HMAC domain-separated pseudonymous keys;
- exact client-IP hard dimension: IPv4 `/32`, IPv6 `/128`;
- separate aggregate pressure: IPv4 `/24`, IPv6 `/64`;
- proof aggregate prefix is not the sole v1 hard 429 gate;
- NAT/campus/VPN multiple exact-IP tests and IPv6 rotation cases;
- trusted ADR-0043 exact context; forged forwarding/private-header/proxy negatives;
- app/Redis <=2s skew and one-clock jump;
- local wall-vs-monotonic Clock Safety Guard common-mode forward/backward step;
- boot host-sync gate and 60s stable re-arm;
- no TTL security reset;
- bounded cleanup;
- active-bucket/new-allocation/cleanup aggregate telemetry;
- adversarial unique-contact/address flood;
- capacity guard returns `QUOTA_CAPACITY_UNHEALTHY` before eviction/OOM and does not create attacker-cardinality state;
- outage/time/capacity failures fail closed and stay distinct from normal quota denial;
- >=30% validated Redis memory reserve at approved peak/attack envelope.

Single-server adds AOF/restart/session-loss re-authentication/no-failover evidence. HA adds Sentinel/replica behavior.

## 9. Authentication, MFA, BFF, Authorization

Infrastructure profile does not reduce these tests.

Identity/BFF cover password/Google/external identity binding, non-enumeration, TOTP continuation/downgrade prevention, MFA lifecycle/recovery, JWT rules, OIDC state/nonce/PKCE/replay/redirect, browser token isolation, session rotation/revocation/lifetimes, CSRF/Origin/Fetch Metadata/CORS/CSP/cache, exact-audience token brokerage, tenantless restrictions, erasure/session shutdown, and trusted network-context behavior.

Authorization covers one final authoritative check, ALLOW only on successful authoritative completion, deny/error/timeout/breaker/overload fail closed, no permission cache/Kafka/stale fallback/retry, admin privilege-escalation prevention, owner-safety concurrency, platform-vs-tenant separation, and workload-policy positives/negatives.

Authorization business DENY remains the current gRPC contract; this PR does not change its semantics.

## 10. Kubernetes/K3s/Istio

Single-server proves exact K3s artifact/version/integrity, embedded SQLite, secrets encryption, Flannel/policy controller/bundled Traefik/ServiceLB disabled, Calico active, one replica/HPA off/PDB off, off-host K3s recovery artifacts, clean GitOps rebuild, and whole-host reboot/recovery.

Istio proves STRICT mTLS, exact ServiceAccount identity, least-privilege authorization positives/negatives, NetworkPolicy/HBONE compatibility, `istioctl analyze`, no duplicate retry ownership, and single-server complete-stack resource impact.

## 11. DevSecOps/Kyverno supply-chain tests

ADR-0045 evidence includes:

- Gitleaks detects a synthetic secret in the current tree;
- Gitleaks detects a synthetic committed secret after it is removed from the latest tree;
- Gitleaks output is fully redacted and a real exposed credential follows revoke/rotate handling rather than suppression;
- Semgrep custom rules have positive/negative fixtures and remain blocking;
- OSV-Scanner exact version/checksum is verified and supported declared/locked dependencies are scanned on blocking CI;
- scheduled repository security verification reruns the implemented service OSV advisory scan;
- Gradle dependency-integrity failure, OSV dependency-advisory failure, and Grype final-artifact vulnerability failure are proven as separate failure classes;
- Syft generates CycloneDX JSON from the exact final releasable image digest;
- Grype scans that exact final image/SBOM and applies ADR-0035/0038 severity/freshness/exception behavior;
- Cosign correct-signer/wrong-signer/unsigned/provenance/signed-SBOM positives and negatives pass;
- scanner/tool downloads verify immutable version/checksum/digest/signature metadata as applicable;
- scanner/feed unavailability or stale evidence does not silently permit a boundary that depends on it;
- Trivy/OWASP Dependency-Check are not silently added as competing authorities without a distinct-coverage review.

New production Kyverno policy manifests use stable `policies.kyverno.io/v1` CEL types.

CI/render tests reject legacy production policy declarations such as:

```text
kyverno.io/v1 ClusterPolicy/Policy
kyverno.io/v2 CleanupPolicy/ClusterCleanupPolicy
```

unless a narrowly reviewed migration-only exception has owner and removal deadline.

Also prove digest-only images, signer positive/wrong/unsigned negatives, provenance/SBOM positive/missing/invalid, privileged/host-network/hostPath/security-context negatives, policy-authoring RBAC, external-context SSRF controls, and fail-closed admission.

Single-server one-replica Kyverno outage must not create bypass.

## 12. Human access/OpenBao

Single-server proves WireGuard management-only reachability, public SSH denial, FIDO2 presence/verification, no root/password/shared keys, JIT grant/expiry, OS/`sudo`/Kubernetes/DB audit, and off-host audit integrity.

OpenBao remains unchanged: exact version/topology, snapshot/restore/unseal, Kubernetes Auth/External Secrets, mounted/local key rotation/reload, stale-source fail-close, no request-path OpenBao regression, and no secret leakage.

## 13. Edge/client-address tests

- upstream mitigation evidence;
- external L4 -> Traefik -> WAF -> BFF route;
- Traefik origin restricted to approved L4 sources;
- forged forwarding/client-IP headers ignored;
- untrusted PROXY denied;
- direct Internet->BFF and Traefik->BFF bypass denied;
- BFF produces one exact canonical IP context only;
- backend derives exact hard + aggregate pressure dimensions;
- no raw client IP in ordinary telemetry.

## 14. Day-One observability tests

Every executable service/critical path proves applicable:

- structured allow-listed JSON logs;
- Micrometer request/operation/dependency/saturation metrics;
- OpenTelemetry trace creation/propagation/export through internal OTLP Collector;
- one synthetic journey produces correlated expected logs, metrics, and trace spans across implemented BFF/gRPC boundaries;
- trace/baggage is correlation only and cannot alter authN/authZ/tenant/quota/idempotency/audit results;
- baggage allow-list rejects User/Tenant/session/contact/raw-IP/secret values;
- metric labels remain low-cardinality and exclude trace/subject/request/resource IDs;
- ADR-0031 secret/PII canaries absent from Loki/Tempo/Prometheus/Grafana-visible data;
- management scrape and OTLP endpoints not public;
- Collector wrong-workload/public ingress denied;
- Collector exact read-only pod-log mount and no broad host access;
- memory limiter/batch/finite queue/drop behavior;
- Collector/Loki/Tempo/Prometheus outage/backpressure does not fail ordinary business requests;
- required authoritative audit remains durable/off-host;
- independent external black-box check detects total host loss when local monitoring is down.

## 15. Developer-host Ops MCP tests

ADR-0048 repository tests prove:

- Context MCP still exposes exactly its five read-only tools;
- Ops MCP exposes only the reviewed separate Ops tool list with correct read-only/destructive/open-world hints;
- missing/malformed/unknown-field/duplicate-key/relative/out-of-range policy state fails closed;
- out-of-root, denied-root, allowed-root deletion, and symlink/reparse escape negatives;
- bounded UTF-8 file read/write, atomic replace, and SHA-256 overwrite precondition;
- policy command alias only, absolute allowed cwd, bounded argv, finite timeout, process-tree termination attempt, and bounded stdout/stderr capture;
- secret-like parent environment values such as `CONTROL_PLANE_API_KEY` are absent from child environment;
- audit metadata excludes file content, raw argv, raw purpose, stdout/stderr, and credentials and has bounded rotation;
- stdio JSON-RPC output is explicit UTF-8 bytes independent of Windows text code page;
- entry point works outside repository CWD.

Host evidence separately proves real Windows policy ACLs, intended elevation state, separate Ops tunnel/profile/key, local readiness, exact tool discovery, `ops.status`, bounded mutation/execution, audit behavior, and rollback/revocation. Background resilience evidence distinguishes tunnel-client child exit, internal stdio MCP-child failure/unready state, complete parent Scheduled Task stop, and orphan/duplicate same-profile cleanup. Pass requires full known profile-tree cleanup before parent recovery, convergence to exactly one launcher/wrapper/tunnel chain where applicable, healthy loopback `/readyz`, bounded local diagnostics, and no runtime-key exposure. The synchronous execution bound must also be proven below the effective end-to-end response deadline: a deliberately over-bound command must time out locally without changing tunnel PID or readiness.

Ops MCP tests do not prove production administration safety. ADR-0030 remains the production privileged-access authority.

## 16. Developer-host Desktop MCP tests

ADR-0049 plus ADR-0050 repository tests prove:

- Context and Ops tool surfaces remain unchanged while Desktop exposes exactly the reviewed separate Desktop tool list/annotations, including only the ADR-0050 opaque credential-use primitive;
- missing/malformed/unknown/duplicate/ambiguous policy state and incompatible WinApp version fail closed;
- app allow/deny rules use the real process identity from a freshly resolved HWND before targeted actions;
- inspect depth/selectors/output are bounded and coordinate-only mouse targets are refused;
- screenshot permission/capture-screen opt-in, PNG validation, byte bound, MCP image representation, and temporary-file cleanup;
- UIA/mouse/keyboard/system-key capabilities require explicit policy; caller key grammar rejects literal/raw-virtual/Secure-Attention/workstation-lock forms and only validated alphanumeric modifier chords receive internal VK normalization;
- literal text uses a fixed isolated PowerShell/C# Unicode helper; tests prove text is stdin-only, helper argv is fixed, child environment excludes tunnel/API secrets, output is bounded UTF-8 JSON, timeout preflight occurs before injection, and structured partial/foreground failures are not automatically retried;
- credential use is disabled by default, requires explicit bounded app/executable-path/SHA-256/selector/Generic-Credential bindings plus UIA/keyboard capability, and rejects denied apps, coordinate selectors, duplicate references/targets, unknown references, and wrong-app calls before local credential resolution;
- `desktop.use_credential` accepts only HWND plus opaque `credential_id`; secret/selector/Credential-Manager-target caller fields are not part of the schema and additional caller fields are rejected before engine execution;
- the fixed credential helper receives only non-secret reference metadata, sanitizes its child environment, verifies HWND/PID plus foreground state and focused UIA `IsPassword=true` before `CredReadW`; `@unique-password` must fail on multiple UIA matches and may fall back only on zero UIA matches to exactly one same-PID enabled/visible single-line native `Edit`/`WindowsForms10.EDIT.*` child with `ES_PASSWORD` plus non-zero bounded `EM_GETPASSWORDCHAR`; native focus is verified with `GetGUIThreadInfo`, the same PID/focus/foreground/password predicates are checked before every code unit, the credential buffer is freed, and no credential value/length/username/target is returned;
- host tests distinguish native password-target recognition from UIPI delivery authority: lower-integrity Desktop/credential helper processes must not be treated as capable of injecting into higher-integrity targets, and no automatic elevation/bypass is permitted;
- WinApp child environment excludes tunnel/API secrets and enables telemetry opt-out;
- audit begins before sensitive observation/action, fails closed when unavailable, rotates within bound, and never stores raw typed text/selectors/window titles/screenshots/WinApp output;
- explicit UTF-8 stdio behavior and modern/legacy MCP error handling remain deterministic.

Host evidence separately proves exact WinApp package/version/integrity, protected policy/audit/capture ACLs, intended non-elevated interactive session, exact mixed-case/Unicode helper delivery plus bounded shortcut/mouse/screenshot behavior, separate Desktop tunnel/profile/key, readiness, ChatGPT tool discovery, audit behavior, and rollback/revocation. ADR-0050 host evidence additionally uses a disposable Generic Credential and a disposable/password-capable target to prove local enrollment, password-target recognition, correct injection, wrong-window/focus failure, and no credential content in audit/argv/environment before a real application credential is enabled. Background resilience also proves tunnel-client child recovery, internal MCP-child/unready recovery, parent-task recovery, full launcher/wrapper/tunnel process-tree cleanup, one-process-per-profile convergence, and continued `elevated=false` / `interactive_session=true` after recovery. Logoff/logon persistence remains a separate session-bound host test.

Repository Desktop tests prove the broker contract and fail-closed invariants only. They do not prove the target application's security, same-Windows-user compromise resistance, real application login behavior, or production administration safety. ADR-0030 and credential owners remain unchanged.

## 17. Complete-stack single-server test

Run all intended platform/application/observability components together.

Record:

- host CPU/memory/swap/pressure;
- all JVM CPU/RSS;
- PostgreSQL connections/query/WAL/checkpoint/backup IO;
- Redis memory/AOF/cardinality/allocation;
- Kafka memory/log IO/lag;
- Istio/Kyverno/edge;
- Collector queues/drops;
- Prometheus series/scrape/TSDB;
- Loki ingest/query/storage;
- Tempo ingest/query/storage;
- Grafana/Alertmanager overhead;
- filesystem/free-space/IO/network/conntrack/FD/listen/ephemeral-port pressure;
- reboot/recovery and external-monitor behavior.

Pass requires no OOM, no sustained swap/MemoryPressure, >=30% validated CPU+memory headroom, applicable >=2x critical/security load, safe concurrent WAL+AOF+Kafka+telemetry IO, and no security/admission/backup/observability bypass.

## 18. CI/CD ordering

Recommended authority order:

```text
Gitleaks secret scan
-> Semgrep/format/static/architecture/governance/dependency integrity
-> OSV-Scanner declared/locked dependency advisory
-> unit
-> contract/schema/dataset
-> focused integration/security/quota/observability
-> final image -> Syft CycloneDX -> Grype final-artifact vulnerability decision -> Cosign sign/provenance/SBOM attestation
-> Helm/Kubernetes/Istio/Kyverno CEL/render/secret
-> staging smoke/critical browser/telemetry correlation
-> profile-specific load/recovery/chaos
-> production approval of the same signed digest
```

Independent checks may execute in parallel only when required input/evidence ordering remains correct. Scheduled OSV advisory scanning complements, but does not replace, ADR-0035 deployed-digest Grype rescanning.

## 19. Definition of Done

A non-trivial implementation change is not complete until applicable evidence covers architecture, contracts, persistence/migration/reference data, failure semantics, security/Authorization/tenant isolation, source/secret/dependency-advisory/final-artifact security, workload identity/network policy, **logs/metrics/traces**, deployment/render/policy, rollback/recovery, and selected profile consistency.

Documentation-only work establishes target decisions only; it never proves runtime production readiness.