# Production Readiness Checklist — Current State

This checklist tracks **implementation and executable evidence**, not architecture discovery. Current retained ADRs/current-state documents define the target. A missing/failed gate is never permission to redesign or bypass the target through configuration.

Selected initial profile: `production-single-server` under ADR-0042.

For each applicable item:

```text
Architecture: DECIDED / NOT DECIDED
Implementation: IMPLEMENTED / PLANNED / NOT VERIFIED / NOT APPLICABLE
Evidence: PASS / FAIL / NOT RUN / NOT APPLICABLE
Owner: <role/team>
Artifact: <CI run/report/manifest/test/restore record>
```

Production traffic MUST NOT open while a mandatory applicable gate is `FAIL`, `NOT RUN` or materially `NOT VERIFIED`.

`implementation-status.md` records repository implementation/evidence presence. A target path in an architecture document is not proof of implementation.

## 1. Profile selection and risk acceptance

### `production-single-server`

- [ ] ADR-0042 is present in Decision Register/source/task maps.
- [ ] ADR-0043 is present in Decision Register/source/task maps.
- [ ] `threat-model.md` covers the selected profile, public edge/client-address trust, management path, privileged access, restore, and single-host residual risk.
- [ ] Operator/business owner explicitly accepts that host/node/kernel/storage/maintenance failure can stop the complete platform.
- [ ] Operator/security owner explicitly accepts the broader host/root compromise blast radius of one physical server.
- [ ] No document/manifest/dashboard claims node/control-plane/PostgreSQL/Redis/Kafka/Kyverno HA for this profile.
- [ ] Application replica count renders as one unless a separately reviewed local-concurrency exception exists.
- [ ] HPA is disabled by default.
- [ ] Availability PDBs do not block required one-node maintenance while creating no real availability.
- [ ] `production-cold-dr.md` and applicable component recovery procedures identify whole-platform recovery implications.

### `production-ha`

When selected, the existing redundant control-plane/worker, dedicated PostgreSQL, Kafka, Redis, Kyverno and service-replica gates apply and must be proven independently.

## 2. K3s/Kubernetes and host baseline

Single-server mandatory evidence:

- [ ] exact Kubernetes/K3s artifacts match Technology Baseline and integrity/signature/checksum metadata is recorded;
- [ ] K3s server uses the approved matching Kubernetes line;
- [ ] K3s embedded SQLite control-plane datastore is used unless a later current decision changes it;
- [ ] K3s secrets encryption is enabled and tested;
- [ ] K3s Flannel is disabled;
- [ ] K3s network-policy controller is disabled so Calico remains authoritative;
- [ ] K3s bundled Traefik and ServiceLB are disabled;
- [ ] Calico installation/render/connectivity/deny-by-default tests pass;
- [ ] K3s datastore directory plus server token are encrypted and copied off-host;
- [ ] clean K3s/GitOps rebuild from documented recovery artifacts passes;
- [ ] host OS/kernel/filesystem/time/network hardening is versioned and reviewable;
- [ ] exact host WireGuard package/kernel support and configuration are pinned in provisioning metadata;
- [ ] host/provider firewall state separates public, management, and workload paths according to `network-architecture.md`;
- [ ] reboot/restart ordering does not create a fail-open security window.

## 3. Workload hardening

For every production application workload:

- [ ] immutable image digest; no `latest`;
- [ ] reviewed source/provenance identity;
- [ ] non-root execution;
- [ ] `allowPrivilegeEscalation=false`;
- [ ] default Linux capability drop;
- [ ] `RuntimeDefault` seccomp;
- [ ] read-only root filesystem where compatible;
- [ ] finite CPU/memory requests and limits;
- [ ] startup/readiness/liveness probes have correct dependency semantics;
- [ ] graceful shutdown is verified;
- [ ] dedicated ServiceAccount; no Kubernetes `default` SA;
- [ ] deny-by-default NetworkPolicy with only required ingress/egress;
- [ ] least-privilege Istio authorization;
- [ ] secrets are not present in images/manifests/rendered logs.

## 4. Istio Ambient and workload identity

Common security gates:

- [ ] trust domain/CA hierarchy and workload certificate rotation match current architecture;
- [ ] STRICT mTLS positive and plaintext/unauthorized negatives pass;
- [ ] expected ServiceAccount principals are exact;
- [ ] NetworkPolicy permits required HBONE/health paths and denies unintended traffic;
- [ ] `istioctl analyze` has no material unresolved error;
- [ ] no duplicated mesh/application retry for the same failure class.

Single-server benchmark gates:

- [ ] `istiod`, Istio CNI and `ztunnel` idle/peak RSS+CPU recorded;
- [ ] critical-path p95/p99 latency impact recorded;
- [ ] throughput/connection saturation recorded;
- [ ] OOM/restart behavior under pressure is safe;
- [ ] Calico interaction is tested;
- [ ] waypoints are absent unless an explicit reviewed L7 requirement exists;
- [ ] complete-stack test still preserves >=30% validated CPU+memory headroom.

A failed Ambient capacity gate blocks production approval. It does not authorize disabling workload identity/mTLS.

## 5. Kyverno and supply-chain admission

- [ ] Kyverno uses the approved supported API/version family;
- [ ] production protected creates/updates fail closed when admission cannot validate policy;
- [ ] digest-only image enforcement passes positive/negative tests;
- [ ] approved signer identity positive and wrong/unsigned signer negatives pass;
- [ ] build provenance/attestation positive/missing/invalid tests pass;
- [ ] signed CycloneDX SBOM positive/missing/invalid tests pass;
- [ ] critical privileged/host-network/unsafe `hostPath`/unsafe security-context negatives pass;
- [ ] critical ServiceAccount/deployment identity policies pass;
- [ ] policy-authoring RBAC denies ordinary application identities;
- [ ] unneeded external HTTP context is disabled;
- [ ] any approved external context passes destination/timeout/size/credential/SSRF-negative tests.

Single-server:

- [ ] one-replica Kyverno deployment is explicitly non-HA;
- [ ] reduced policy inventory has an evidence-backed reason for every removed policy;
- [ ] removed policies do not create unsigned/privileged/identity bypass;
- [ ] audit-only production admission is not used.

## 6. PostgreSQL database ownership and RLS

For every service with mutable PostgreSQL relational business persistence:

- [ ] distinct database;
- [ ] distinct runtime role;
- [ ] distinct migration/owner role;
- [ ] independent Flyway history/release lifecycle;
- [ ] runtime role `NOSUPERUSER NOBYPASSRLS`, non-owner, no role creation;
- [ ] runtime/migration role cannot connect/access another service database;
- [ ] default/public privileges do not create cross-service access;
- [ ] no cross-service SQL/FK/view/FDW/`dblink`/shared persistence model;
- [ ] tenant-owned tables have forced RLS + `USING`/`WITH CHECK`;
- [ ] missing/malformed tenant context fails closed;
- [ ] pooled connection reuse after commit/rollback cannot leak prior tenant context;
- [ ] migration credentials are absent from application runtime pods.

Single-server:

- [ ] one physical CloudNativePG/PostgreSQL instance is rendered intentionally;
- [ ] aggregate application pools across all services are <=70% of `max_connections`;
- [ ] >=30% connection headroom is reserved for migration/backup/recovery/admin/emergency work;
- [ ] per-service pool ceilings prevent one service from consuming global operational headroom;
- [ ] noisy-neighbor CPU/IO/WAL/checkpoint/storage tests cover simultaneous representative service load;
- [ ] no PostgreSQL primary-failover claim appears in runtime evidence.

## 7. PostgreSQL backup/PITR/restore

Mandatory in both profiles:

- [ ] encrypted off-site physical backup in a separate failure domain;
- [ ] continuous WAL archive freshness monitored;
- [ ] evidence supports PostgreSQL DR RPO <=5m;
- [ ] online physical base backup at least daily;
- [ ] 35-day PITR window;
- [ ] monthly retained recovery artifact for 12 months;
- [ ] backup verification every cycle;
- [ ] monthly isolated restore exercise;
- [ ] quarterly full cold-DR exercise uses `../runbooks/production-cold-dr.md` or a stricter reviewed procedure;
- [ ] restore evidence includes versions, Flyway, integrity, RPO/RTO, RLS and erasure/legal-hold checks where applicable;
- [ ] quarterly platform evidence measures the ADR-0004 platform RTO <=4h rather than inferring it from backup success.

`pg_dump + cron` MUST NOT be recorded as the primary production backup/PITR strategy.

Single-server service-specific recovery:

- [ ] complete shared physical cluster restores to an isolated environment at a requested PITR point;
- [ ] every affected service DB/Flyway/role/RLS boundary is validated;
- [ ] required single service DB can be extracted through the approved logical recovery procedure;
- [ ] controlled import/restore proves application/schema compatibility;
- [ ] unrelated current service databases are not destructively restored;
- [ ] a failed shared-cluster restore triggers the ADR-0037 promotion freeze.

## 8. Kafka

Single-server:

- [ ] exactly one combined KRaft broker/controller is intentional;
- [ ] critical topics use RF=1/minISR=1;
- [ ] producers use `acks=all` and idempotence;
- [ ] unclean leader election is disabled;
- [ ] enabled internal topics/features have one-broker-compatible replication configuration;
- [ ] TLS/authentication/per-service principals/ACLs/quotas pass;
- [ ] broker/node/disk outage is explicitly accepted as non-HA;
- [ ] clean Kafka rebuild from GitOps passes;
- [ ] critical replay/reconstruction from service-owned evidence passes.

Both profiles:

- [ ] Transactional Outbox is used when state+event are one business effect;
- [ ] consumer idempotency/Inbox behavior survives duplicates/restarts;
- [ ] offsets commit only after durable effect;
- [ ] finite retry/DLQ ownership and retention are tested;
- [ ] critical publication and consumer dedup evidence covers >=35 days;
- [ ] Protobuf/Buf compatibility passes.

## 9. Security Redis

Common:

- [ ] TLS enabled;
- [ ] per-owner ACL identities/key namespaces enforced;
- [ ] `noeviction` configured and observed;
- [ ] raw identifiers are absent where HMAC pseudonymization is required;
- [ ] network quota identity comes only from the ADR-0043 trusted edge/BFF context;
- [ ] forged forwarding/client-IP headers cannot create a different network budget;
- [ ] IPv4, IPv6, and IPv4-mapped IPv6 canonicalization cannot create alias budgets;
- [ ] missing/malformed/proxy-address network identity fails closed when the operation requires a network quota;
- [ ] quota atomicity/anti-lockout/time-source/TTL rules pass;
- [ ] covered operations fail closed on Redis/time-source failure;
- [ ] memory headroom >=30% at approved peak.

Single-server:

- [ ] exactly one Redis instance is intentional;
- [ ] AOF is enabled with `appendfsync everysec`;
- [ ] restart/AOF recovery behavior is tested;
- [ ] loss of session state results in re-authentication, not cookie-based authority reconstruction;
- [ ] no Sentinel/failover claim appears;
- [ ] AOF rewrite/fsync latency under concurrent platform IO is inside tested budgets.

## 10. Authentication, MFA and browser security

ADR-0042 changes none of these gates.

- [ ] Identity password/Google/external-identity binding rules pass;
- [ ] compromised-password screening fails closed when unsafe to decide;
- [ ] active TOTP cannot be bypassed by freely selecting Email/SMS as a weaker factor;
- [ ] MFA enroll/disable/recovery proof + quota + audit tests pass;
- [ ] OIDC Authorization Code + PKCE state/nonce/pre-auth/replay/redirect tests pass;
- [ ] browser never receives provider/internal service access/refresh credentials;
- [ ] BFF session entropy/rotation/revocation/index/idle/absolute limits pass;
- [ ] CSRF + Fetch Metadata + Origin + same-origin CORS tests pass;
- [ ] CSP/cache/security-header tests pass;
- [ ] exact-audience BFF token brokerage cannot mint arbitrary audience authority;
- [ ] tenantless/onboarding session cannot access ordinary protected resources;
- [ ] HooshiX email identity comparison is intentionally case-insensitive while the mail-delivery representation preserves local-part case under ADR-0009.

## 11. Authorization

- [ ] one authoritative online `CheckPermission` path is used for protected resource permission decisions;
- [ ] no permission-result cache/Kafka invalidation/stale allow exists;
- [ ] one attempt/no automatic retry contract passes;
- [ ] deny/error/timeout/breaker states do not fabricate ALLOW;
- [ ] safe local prechecks reject only;
- [ ] resource-owning service remains final resource authorization authority;
- [ ] admin privilege-escalation/owner-safety/idempotency/audit rules pass;
- [ ] platform capability path cannot bypass tenant/resource authority;
- [ ] dependency criticality registry and positive/negative Istio policy tests cover every active edge.

Single-server availability loss does not make these fail-open rules optional.

## 12. Human privileged production access

Common:

- [ ] no standing root/unrestricted Kubernetes/PostgreSQL-superuser privilege;
- [ ] attributable per-human identity;
- [ ] phishing-resistant privileged authentication;
- [ ] explicit reason/ticket;
- [ ] two-reviewer write/admin/database-write elevation;
- [ ] <=30m write privilege with automatic expiry;
- [ ] separately scoped read-only <=1h;
- [ ] protected break-glass exercise;
- [ ] ordinary requester cannot modify required audit evidence.

Single-server:

- [ ] normal management path is the ADR-0043 WireGuard overlay;
- [ ] public-interface/Internet TCP/22 is unreachable;
- [ ] SSH is reachable only on the host management address/interface;
- [ ] every approved operator device has an independent attributable WireGuard peer key;
- [ ] shared WireGuard peer keys are denied;
- [ ] revoked/unapproved peers cannot reach SSH;
- [ ] peer `AllowedIPs` are minimal and do not grant broad workload/application network access by default;
- [ ] WireGuard network admission alone grants no SSH or privileged authority;
- [ ] root login denied;
- [ ] password authentication denied;
- [ ] shared SSH accounts/keys denied;
- [ ] hardware-backed OpenSSH FIDO2 requires user presence + user verification;
- [ ] FIDO2 login alone does not grant administrator authority;
- [ ] JIT privilege automation expires without manual cleanup dependency;
- [ ] `sudo` I/O/session audit is enabled where applicable;
- [ ] OS audit covers authentication/process/privilege/security-config changes;
- [ ] Kubernetes/database privileged operations are audited at their boundary;
- [ ] required audit is exported off-host to append-only/tamper-resistant storage;
- [ ] `.bashrc`, shell history and `PROMPT_COMMAND` are not accepted as authoritative session audit;
- [ ] audit export failure has incident/continuity handling;
- [ ] provider console, if available, is tested only as protected incident-linked break glass.

HA uses Teleport-specific SSO/WebAuthn/JIT/session-recording gates.

## 13. OpenBao and secrets — unchanged

ADR-0042 MUST NOT change OpenBao.

- [ ] OpenBao exact version/topology matches ADR-0011/Technology Baseline;
- [ ] current Shamir/Raft/PVC/encrypted snapshot/restore/unseal evidence passes;
- [ ] External Secrets/Kubernetes Auth flows pass;
- [ ] application hot paths use current mounted/local validated key material rather than new per-request OpenBao dependency;
- [ ] purpose-separated key-ring rotation/reload/stale-snapshot rules pass;
- [ ] no secret appears in Git/image/values/log/trace/metric/CI output;
- [ ] no profile change removed/replaced/bypassed OpenBao;
- [ ] OpenBao capacity issue is not solved by weakening secret authority.

## 14. Public edge, WAF, and client address

- [ ] upstream volumetric protection is active and evidenced;
- [ ] external L4 -> repository Traefik -> Caddy/Coraza WAF -> BFF path is enforced;
- [ ] the Traefik application origin accepts public application traffic only from the exact approved external-L4 source ranges; direct Internet/non-approved-source access is denied before application routing;
- [ ] external L4 preserves the validated original client address with the approved PROXY-v2 contract;
- [ ] Traefik `proxyProtocol.trustedIPs` contains only approved external-L4 source CIDRs;
- [ ] Traefik `proxyProtocol.insecure` is disabled;
- [ ] Traefik `forwardedHeaders.insecure` is disabled;
- [ ] Caddy trusted-proxy ranges are limited to the required Traefik source path and strict trusted-proxy parsing is enabled;
- [ ] Caddy overwrites the internal `X-HooshiX-Client-IP`; caller-supplied values cannot pass through as authority;
- [ ] Web BFF accepts one server-derived internal client IP only on the WAF-only ingress path;
- [ ] forged `Forwarded`, `X-Forwarded-For`, `X-Real-IP`, and `X-HooshiX-Client-IP` values do not affect client identity;
- [ ] missing/misconfigured source-address preservation is detected before quota-protected traffic is accepted;
- [ ] direct Internet/BFF and Traefik/BFF WAF-bypass paths fail negative tests;
- [ ] K3s bundled Traefik/ServiceLB is absent in single-server;
- [ ] WAF current CRS/rule version and DetectionOnly-to-blocking evidence pass;
- [ ] application request/body/header limits remain independent defense in depth;
- [ ] public dashboard/insecure gateway API is not exposed.

## 15. Logging, PII, telemetry and audit

- [ ] static logging/Semgrep rules pass;
- [ ] sensitive credential/token/cookie/secret data cannot be logged in reviewed paths;
- [ ] PII masking/HMAC pseudonymization rules pass;
- [ ] raw client IP used for trusted network quota context is absent from ordinary logs/metrics/traces/Redis keys/Kafka/business state;
- [ ] CR/LF/log injection tests pass for input-derived fields;
- [ ] metric labels are bounded and exclude high-cardinality subject identifiers;
- [ ] synthetic canary/runtime leak detection is active where required;
- [ ] required audit evidence is not silently dropped as best-effort telemetry;
- [ ] single-server observability resource use is included in full-stack capacity evidence;
- [ ] privileged-access audit has an off-host durable copy.

## 16. Complete-stack single-server capacity gate

The following are measured **at the same time** under representative load/background work:

- [ ] K3s/system resource use;
- [ ] all application JVM CPU/RSS;
- [ ] PostgreSQL query/connection/WAL/checkpoint/backup IO;
- [ ] Redis memory/AOF fsync/rewrite;
- [ ] Kafka broker/controller/log IO;
- [ ] Istio control plane/CNI/ztunnel;
- [ ] Kyverno admission;
- [ ] Traefik/WAF;
- [ ] observability stack;
- [ ] host filesystem/disk free-space/latency/IOPS;
- [ ] effective MTU/PMTU behavior across public/pod/Ambient paths;
- [ ] conntrack usage/high-water/drop counters;
- [ ] file-descriptor/listen-queue usage;
- [ ] ephemeral-port/TIME_WAIT pressure;
- [ ] public/management interface packet/error/drop counters;
- [ ] reboot/recovery behavior.

Required pass criteria:

- [ ] no OOM kill;
- [ ] no sustained swap pressure;
- [ ] no node `MemoryPressure` eviction;
- [ ] >=30% validated CPU headroom at approved peak;
- [ ] >=30% validated memory headroom at approved peak;
- [ ] applicable critical/security paths pass >=2x projected peak validation;
- [ ] disk latency/free space stays inside tested thresholds while WAL+AOF+Kafka+telemetry coexist;
- [ ] conntrack, file descriptors, sockets, and ephemeral ports retain measured safe headroom;
- [ ] no security/admission/backup control must be disabled to pass;
- [ ] restart/reboot does not create fail-open behavior.

A `2 vCPU / 3-4 GiB RAM` host is not approved without this evidence.

## 17. Service-specific acceptance

Applicable service documents/ADRs still own their business, contract, migration, dependency, audit, performance and security gates. Profile overlay changes only infrastructure topology.

Examples that remain mandatory when the service is in release scope:

- Identity registration/authentication/MFA/session/token/erasure behavior;
- Authorization permission/admin/platform/owner-safety behavior;
- Notification durable acceptance/template/provider/reconciliation behavior;
- Web BFF OIDC/session/CSRF/CORS/token-broker/public API/client-network behavior;
- Compromised Password immutable dataset/query/security/recovery behavior;
- Reference Data importer/bundle/contract/runtime evidence only when its implementation trigger/release scope is active.

## 18. Release decision

Production approval requires:

```text
all applicable mandatory gates PASS
+ formal threat-model review current for the deployed topology
+ no unresolved security/correctness blocker
+ selected-profile capacity/recovery evidence PASS
+ quarterly cold-DR evidence current and platform RTO measured
+ current vulnerability/advisory/security-support review PASS
+ explicit single-server downtime/host-blast-radius sign-off
```

If a mandatory gate is missing, report `NOT VERIFIED` and block production readiness. Do not convert missing evidence into an architectural assumption.
