# Production Readiness Checklist — Current State

This checklist tracks **implementation and executable evidence**, not architecture discovery. Current ADRs/current-state documents define the target. A missing/failed gate is never permission to bypass or weaken the architecture.

Selected initial profile: `production-single-server` under ADR-0042. ADR-0045 defines source/secret/final-artifact DevSecOps responsibilities.

For each applicable item record:

```text
Architecture: DECIDED / NOT DECIDED
Implementation: IMPLEMENTED / PLANNED / NOT VERIFIED / NOT APPLICABLE
Evidence: PASS / FAIL / NOT RUN / NOT APPLICABLE
Owner: <role/team>
Artifact: <CI run/report/manifest/test/restore record>
```

Production traffic MUST NOT open while a mandatory applicable gate is `FAIL`, `NOT RUN`, or materially `NOT VERIFIED`.

## 1. Profile and repository status

- [ ] ADR-0042 selected profile is present in current Decision Register/source/task maps.
- [ ] ADR-0043 network trust, ADR-0044 Day-One Observability, and ADR-0045 DevSecOps tool responsibilities are present.
- [ ] `implementation-status.md` matches actual repository tree/evidence.
- [ ] operator/business owner accepts single-host outage/maintenance risk.
- [ ] operator/security owner accepts single-host root/blast-radius risk.
- [ ] no manifest/dashboard/document claims false HA for node/PostgreSQL/Redis/Kafka/Kyverno/apps/observability.
- [ ] application replicas render one; HPA off; availability PDB off unless reviewed exception.
- [ ] complete cold-DR procedure exists and has required exercise evidence.

## 2. K3s/host/network baseline

- [ ] exact K3s/Kubernetes artifacts match Technology Baseline with integrity evidence.
- [ ] embedded SQLite control-plane datastore and secrets encryption configured.
- [ ] K3s Flannel/network-policy controller/bundled Traefik/ServiceLB disabled.
- [ ] Calico is CNI/NetworkPolicy authority and deny-by-default tests pass.
- [ ] K3s datastore/token recovery artifact encrypted off-host.
- [ ] clean K3s/GitOps rebuild passes.
- [ ] host OS/kernel/filesystem/time/network hardening is versioned/reviewed.
- [ ] host time synchronization is healthy before quota-protected traffic.
- [ ] WireGuard package/kernel/config pinned; public SSH denied.
- [ ] host/provider firewall separates public/management/workload paths.
- [ ] reboot/startup does not create fail-open window.

## 3. Workload hardening

Every production workload:

- [ ] immutable signed digest; no `latest`.
- [ ] non-root; `allowPrivilegeEscalation=false`; capabilities dropped; `RuntimeDefault` seccomp.
- [ ] read-only root filesystem where compatible.
- [ ] finite CPU/memory and bounded ephemeral storage.
- [ ] startup/readiness/liveness have correct dependency semantics.
- [ ] graceful shutdown proven.
- [ ] dedicated ServiceAccount; no `default` SA.
- [ ] deny-by-default NetworkPolicy and least-privilege Istio authorization.
- [ ] secrets absent from image/manifests/rendered logs/traces/metrics.

## 4. DevSecOps, Istio, Kyverno, and supply chain

Source/repository security:

- [ ] Gitleaks 8.30.1 exact native tool artifact/checksum or immutable tool digest is pinned and verified.
- [ ] Gitleaks current-tree scan passes.
- [ ] Gitleaks protected Git-history scan passes, including a commit-then-delete positive fixture.
- [ ] Gitleaks output is fully redacted and does not publish discovered secret material to logs/annotations/artifacts.
- [ ] no unresolved real committed credential remains; plausible exposed credentials have revoke/rotate evidence.
- [ ] Semgrep blocking source-policy/SAST rules pass with applicable positive/negative fixtures.
- [ ] Gradle dependency verification/locks and vulnerability scanning are proven as separate failure classes.

Final-artifact security:

- [ ] Syft 1.51.0 exact tool integrity is verified and generates CycloneDX JSON from the exact final releasable image digest.
- [ ] Grype 0.117.0 exact tool integrity is verified and scans that exact final image/SBOM.
- [ ] Grype advisory database/feed freshness, Critical/High policy, VEX/exception expiry, ownership, and continuous rescanning meet ADR-0035/0038.
- [ ] Cosign 3.0.6 exact tool integrity is verified.
- [ ] image signature, build provenance, and signed CycloneDX SBOM are bound to the exact final image digest.
- [ ] correct signer/wrong signer/unsigned/missing or invalid provenance/SBOM negatives pass.
- [ ] Trivy and OWASP Dependency-Check have not been silently introduced as competing authorities without ADR-0045 distinct-coverage review.
- [ ] separate Semgrep Secrets/Supply Chain/hosted product capabilities are not claimed from Semgrep CLI presence alone.

Runtime/admission security:

- [ ] Ambient strict mTLS positive/plaintext/wrong-SA negatives pass.
- [ ] `istioctl analyze` has no blocking error and no duplicate retry layer exists.
- [ ] Ambient CPU/RAM/latency/connection pressure fits complete-stack benchmark.
- [ ] Kyverno exact version matches baseline.
- [ ] new production policy manifests use stable `policies.kyverno.io/v1` CEL types.
- [ ] CI/render gate rejects legacy `kyverno.io/v1` ClusterPolicy/Policy and `kyverno.io/v2` CleanupPolicy/ClusterCleanupPolicy for new controls.
- [ ] Kyverno verifies required digest/signature/provenance/signed-SBOM evidence and missing/wrong negatives fail closed.
- [ ] privileged/host-network/unsafe-hostPath/security-context negatives pass.
- [ ] policy-authoring RBAC and external-context SSRF controls pass.
- [ ] one-replica single-server Kyverno outage does not create admission bypass.
- [ ] required scanner/feed/signing/admission outage or stale evidence cannot silently permit affected promotion.

## 5. PostgreSQL ownership/RLS/recovery

For each mutable PostgreSQL service:

- [ ] distinct DB/runtime role/migration role/Flyway history.
- [ ] runtime role non-owner `NOSUPERUSER NOBYPASSRLS`.
- [ ] no cross-service CONNECT/object/credential access.
- [ ] no cross-service SQL/FK/view/FDW/`dblink`/shared model.
- [ ] tenant-owned tables use forced RLS with `USING`/`WITH CHECK`.
- [ ] missing/malformed tenant context fails closed.
- [ ] pooled commit/rollback/reuse cannot leak tenant context.
- [ ] migration credentials absent from runtime pods.

Single-server:

- [ ] one physical PostgreSQL instance is intentional.
- [ ] aggregate app pools <=70% `max_connections`; >=30% operational reserve.
- [ ] per-service pool ceilings prevent noisy-neighbor exhaustion.
- [ ] concurrent query/WAL/checkpoint/backup/noisy-neighbor tests pass.

Recovery:

- [ ] encrypted off-site physical backup + continuous WAL.
- [ ] PostgreSQL RPO <=5m evidence.
- [ ] daily online base backup; 35-day PITR; monthly retained artifact for 12 months.
- [ ] backup verification every cycle; isolated restore monthly.
- [ ] quarterly full cold DR measures real platform RTO <=4h or records miss.
- [ ] shared-cluster PITR restores in isolation and service-specific recovery does not destructively restore other current DBs.

`pg_dump + cron` is not primary recovery.

## 6. Kafka

- [ ] single combined KRaft broker/controller is intentional in single-server.
- [ ] RF=1/minISR=1/acks=all/idempotence/unclean-election-disabled render matches decision.
- [ ] TLS/auth/per-service principals/ACLs/quotas pass.
- [ ] clean broker rebuild and critical replay/reconstruction from service-owned evidence pass.
- [ ] Outbox/Inbox/idempotency/offset-after-durable-effect/retry-DLQ semantics pass.
- [ ] critical publication/dedup evidence covers required recovery horizon.

## 7. Redis and semantic security quotas

Baseline:

- [ ] TLS + per-service ACL/key isolation.
- [ ] `noeviction`; AOF `appendfsync everysec` in single-server.
- [ ] >=30% validated memory reserve.
- [ ] no raw identifiers in Redis keys/telemetry where pseudonymization required.
- [ ] outage/session-loss behavior fails closed or reauthenticates as designed.

Network identity:

- [ ] public exact client address comes only from ADR-0043 trusted BFF context.
- [ ] forged forwarding/private headers cannot alter identity.
- [ ] exact hard identity is IPv4 `/32` or IPv6 `/128`.
- [ ] aggregate IPv4 `/24` / IPv6 `/64` is separate pressure signal and not sole v1 hard 429 gate.
- [ ] NAT/campus/VPN/IPv6 cases prove aggregate collateral does not create one shared hard bucket.

Clock safety:

- [ ] app/Redis <=2s skew rule passes exact/beyond-bound tests.
- [ ] one-clock jump cannot create refill.
- [ ] wall-vs-monotonic Clock Safety Guard detects common-mode host forward/backward step.
- [ ] boot/recovery blocks quota-protected traffic until host sync healthy.
- [ ] guard trip requires 60s stable no-step + healthy host sync + <=2s app/Redis skew before re-arm.

Cardinality/capacity:

- [ ] active bucket cardinality/new-allocation/cleanup rate metrics are low-cardinality.
- [ ] adversarial unique-contact/address flood test passes without eviction/OOM.
- [ ] bounded capacity guard rejects unsafe new allocations as `QUOTA_CAPACITY_UNHEALTHY`.
- [ ] capacity guard itself does not create attacker-cardinality keys.
- [ ] time/capacity unavailability remains distinct from `SEMANTIC_QUOTA_EXCEEDED`/429.
- [ ] no TTL security reset/local bypass/retry fallback.

## 8. Identity, compromised password, MFA, BFF

- [ ] Identity password/Google/external binding/non-enumeration rules pass.
- [ ] active TOTP cannot be bypassed by Email/SMS downgrade.
- [ ] MFA enroll/disable/recovery proof/quota/audit tests pass.
- [ ] OIDC PKCE/state/nonce/pre-auth/replay/redirect tests pass.
- [ ] browser receives no provider/internal access/refresh credentials.
- [ ] BFF session entropy/rotation/revocation/lifetimes pass.
- [ ] CSRF/Origin/Fetch Metadata/same-origin CORS/CSP/cache/header tests pass.
- [ ] exact-audience token brokerage and tenantless restrictions pass.

Compromised Password:

- [ ] source is official HIBP Pwned Passwords SHA-1 corpus acquired offline.
- [ ] SHA-1 is screening-only; Argon2id remains password-storage authority.
- [ ] complete corpus acquisition/provenance/tool identity recorded.
- [ ] zero-count padding rows rejected.
- [ ] dataset age <=35 days; acquisition/build verification <=30-day cadence.
- [ ] full-corpus max prefix cardinality/serialized response measured and compatibility limit reviewed.
- [ ] SQLite stores 20-byte SHA-1 and is immutable/read-only/query-only.
- [ ] no runtime HIBP/provider call or application Internet egress.
- [ ] stale/corrupt/missing/incompatible data never becomes false clean-password result.

## 9. Authorization

- [ ] one authoritative `CheckPermission` path for protected resource permission decisions.
- [ ] no permission-result cache/Kafka invalidation/stale allow/retry.
- [ ] business DENY/current gRPC semantics remain explicit and breaker/error classification tests pass.
- [ ] dependency error/timeout/overload/breaker never fabricates ALLOW.
- [ ] final resource/domain owner remains final resource authority.
- [ ] admin privilege-escalation/owner-safety/idempotency/audit tests pass.
- [ ] platform capability cannot bypass tenant/resource authority.

## 10. Human privileged access/OpenBao

- [ ] no standing root/unrestricted K8s/DB superuser privilege.
- [ ] per-human attributable identity; hardware FIDO2; JIT reason/ticket; two-reviewer write; <=30m expiry.
- [ ] WireGuard-only normal management path; public TCP/22 unreachable.
- [ ] independent per-device peers; revoked/unapproved peers denied.
- [ ] password/root/shared SSH denied; FIDO2 alone grants no admin.
- [ ] `sudo`/OS/Kubernetes/database privileged audit exported off-host.
- [ ] shell history is not authoritative audit.

OpenBao unchanged:

- [ ] exact 2.6.1 topology/version and Shamir/Raft/PVC/snapshot/restore/unseal evidence.
- [ ] External Secrets/Kubernetes Auth/local mounted key workflows pass.
- [ ] no per-request OpenBao hot-path regression.
- [ ] no secret in Git/image/values/log/trace/metric/CI.

## 11. Public edge and client address

- [ ] upstream volumetric protection active.
- [ ] external L4 -> Traefik -> Caddy/Coraza -> BFF enforced.
- [ ] Traefik origin accepts only exact approved L4 sources; direct Internet/non-approved origin access denied before routing.
- [ ] external L4 preserves client address through approved PROXY v2.
- [ ] Traefik trusted CIDRs exact; insecure PROXY/forwarded modes off.
- [ ] Caddy strict trusted-proxy parsing + internal client-IP overwrite active.
- [ ] BFF accepts one exact internal IP only on WAF-only path.
- [ ] forged headers/untrusted PROXY/proxy-address/IPv4/IPv6/mapped-address negatives pass.
- [ ] no raw client IP in ordinary logs/metrics/traces/Kafka/business state.

## 12. Day-One observability

Every executable service:

- [ ] structured allow-listed JSON logs exist from first feature commit.
- [ ] Micrometer request/operation/dependency/saturation metrics exist.
- [ ] OpenTelemetry tracing exports via internal OTLP Collector.
- [ ] one synthetic implemented journey produces expected correlated logs/metrics/traces.
- [ ] trace/baggage cannot alter authN/authZ/tenant/quota/idempotency/audit results.
- [ ] baggage/attributes exclude prohibited User/Tenant/session/contact/raw-IP/secret values.
- [ ] metric labels low-cardinality; no trace/subject/request/resource IDs.
- [ ] ADR-0031 canary values absent from Loki/Tempo/Prometheus/Grafana-visible data.
- [ ] telemetry backend/exporter outage does not fail ordinary business processing.

Collector/platform:

- [ ] `otelcol-contrib` 0.157.0 exact image digest pinned.
- [ ] internal-only OTLP; wrong workload/public ingress denied.
- [ ] dedicated ServiceAccount/RBAC/NetworkPolicy.
- [ ] exact read-only pod/container log mount only; no broad hostPath/host network/privilege escalation.
- [ ] memory limiter/batch/finite queues/drop/backpressure config tested.
- [ ] Loki 3.7.4 single-binary and Tempo 3.0.2 monolithic render as explicitly non-HA in single-server.
- [ ] Prometheus 3.13.2, Alertmanager 0.33.1, Grafana 13.1.3 match baseline.
- [ ] retention/cardinality/storage quotas are explicit and fit capacity.
- [ ] required security/privileged audit remains separate durable/off-host evidence.
- [ ] independent external black-box monitor outside host failure domain detects total-host loss.

## 13. Reference Data implementation trigger

- [ ] immutable source/provenance/bundle rules pass in whichever owning deployable uses the bundle.
- [ ] `reference-data-service` is not created merely for one journey/route group.
- [ ] before independent service creation, at least one ADR-0041 trigger is evidenced: >=2 independent deployable consumers, independent release lifecycle, security boundary, scale/availability need, or independent ownership.
- [ ] trigger record identifies consumer/ownership/change/scale/security evidence.
- [ ] when service exists, local in-process serving authority is cleanly replaced/removed and typed gRPC/workload-policy/profile tests pass.

## 14. Complete-stack capacity/recovery

Run all intended single-server components together including applications, K3s, PostgreSQL, Redis, Kafka, Istio, Kyverno, Traefik/WAF, OpenBao, Collector, Prometheus, Loki, Tempo, Grafana, Alertmanager, backup IO, and host networking.

Evidence records CPU/RAM/swap, storage latency/IOPS/free space, JVMs, DB/WAL/backup, Redis AOF/cardinality, Kafka IO/lag, mesh/admission/edge, Collector queues/drops, Prometheus series/TSDB, Loki/Tempo ingest/query/storage, Grafana/Alertmanager, conntrack/FD/listen/ephemeral-port pressure, and reboot/recovery.

Pass:

```text
no OOM
no sustained swap/MemoryPressure
>=30% validated CPU headroom
>=30% validated memory headroom
applicable >=2x critical/security peak
safe concurrent WAL+AOF+Kafka+telemetry IO
no security/admission/backup/audit/observability bypass
```

## 15. Final production gate

Before opening traffic:

- [ ] all mandatory above are PASS/current.
- [ ] full cold DR/restore evidence meets or honestly records RPO/RTO result.
- [ ] critical browser/service journey works through real edge and security controls.
- [ ] external host-down monitor is tested.
- [ ] no unresolved real committed-secret exposure remains.
- [ ] no known Critical/High security blocker remains.
- [ ] final production digest is the same staging-validated signed/provenanced/SBOM-attested digest.
- [ ] no required evidence is claimed from documentation alone.

`implementation-status.md` remains repository-presence status. This checklist is the traffic gate.