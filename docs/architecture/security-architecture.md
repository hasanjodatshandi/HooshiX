# Security Architecture — Current State

Security is layered and fails closed when identity assurance, authorization, or security-significant dependency state cannot be proven. Reduced infrastructure availability never justifies reduced security assurance.

ADR-0042 selects `production-single-server`. OpenBao, end-user MFA, tenant isolation, workload identity, signed-artifact admission, semantic-quota correctness, WAF/DDoS controls, Authorization semantics, required audit, and ADR-0045 DevSecOps source/secret/dependency-advisory/final-artifact gates remain invariants.

`threat-model.md`, `security-verification-matrix.md`, and Production Readiness define threat/evidence mapping. ADR-0043 owns network/client-address trust. ADR-0044 owns ordinary observability security. ADR-0045 and `devsecops-security-toolchain.md` own the source/secret/dependency-advisory/SBOM/final-artifact-vulnerability/signing/admission tool responsibility map.

## 1. Identity/tenant isolation

- User identity is global; tenant authority is membership/context scoped.
- Trusted active tenant context comes only from validated authenticated state.
- Tenant-owned PostgreSQL tables use forced RLS plus application enforcement.
- Runtime roles are non-owner `NOSUPERUSER NOBYPASSRLS` and cannot access another service DB.
- Tenant context is parameterized transaction-local; pooled session-scoped tenant state prohibited.
- Missing/malformed context fails closed.
- External identity binds issuer+subject; email alone never auto-links.
- Erasure/legal-hold/authority-removal semantics remain current ADR authority.

Single-server broadens host/root blast radius but does not weaken logical isolation.

## 2. Browser/BFF boundary

Current ADR-0016/BFF rules remain:

- browser uses BFF public API only;
- OIDC Authorization Code + PKCE S256 + state/nonce/pre-auth/replay controls;
- provider/internal tokens not exposed to browser;
- server-side session/pre-auth state;
- CSRF + Origin + Fetch Metadata + same-origin CORS;
- strict CSP/cache/security headers;
- server-owned route->audience mapping;
- final protected-resource authorization remains resource owner + Authorization;
- public forwarding/trace/correlation headers are not security authority.

## 3. Authentication/MFA

Identity authentication/MFA semantics remain unchanged:

- password storage uses Argon2id;
- TOTP remains required where active policy requires it;
- Email/SMS is not a freely selectable weaker bypass;
- Google/external identity does not bypass active MFA;
- responses remain non-enumerating where required;
- current session/recovery/assurance rules remain.

## 4. Compromised Password / Reference Data

Compromised Password:

- official HIBP Pwned Passwords SHA-1 corpus acquired offline;
- SHA-1 is screening-only, never credential storage;
- Identity computes full SHA-1 locally and sends five-hex prefix only;
- service stores immutable 20-byte SHA-1 SQLite reference rows and returns positive-count suffix candidates;
- Identity performs exact full-hash comparison;
- no runtime HIBP/provider egress;
- <=35-day dataset readiness age and <=30-day acquisition/build verification cadence;
- complete-corpus cardinality/response bounds measured before release;
- stale/corrupt/missing/incompatible data fails closed.

Reference Data:

- global immutable non-tenant bundle;
- no runtime source-provider sync or mutable datastore;
- independent service remains gated by ADR-0041 consumers/lifecycle/security/scale/ownership evidence;
- one journey alone does not create a network service.

## 5. Authorization

Authorization remains online/authoritative/fail-closed:

- successful authoritative completion is ALLOW;
- current business DENY gRPC semantics remain unchanged;
- error/timeout/overload/breaker never fabricates ALLOW;
- one attempt, no permission-result cache/Kafka invalidation/stale allow/retry;
- safe local checks reject only;
- resource owner remains final resource/domain authority;
- platform permission cannot bypass tenant/resource authority;
- admin privilege-escalation/owner-safety/idempotency/audit remain current.

## 6. Semantic security quotas

ADR-0024 is authoritative.

- Redis TLS + per-owner ACL + `noeviction`;
- atomic hard-dimension decision;
- HMAC pseudonymous keys where required;
- exact trusted BFF client address only from ADR-0043;
- hard client identity: IPv4 `/32`, IPv6 `/128`;
- separate aggregate pressure: IPv4 `/24`, IPv6 `/64`, not sole v1 hard 429 gate;
- app/Redis <=2s skew plus local wall-vs-monotonic Clock Safety Guard for common-mode host clock steps;
- host sync before quota-protected traffic and 60s stable re-arm after a clock trip;
- no security-significant TTL reset;
- bounded cleanup;
- low-cardinality new-bucket allocation guard + >=30% Redis memory reserve;
- adversarial unique-subject/address allocation test;
- time/capacity/transport failures are availability failures distinct from normal quota denial and fail closed.

A sufficiently large cardinality attack may intentionally make protected operations unavailable. It may not cause fail-open behavior or eviction of authoritative security state.

## 7. Workload identity/network policy

Production workloads use dedicated ServiceAccounts, Istio Ambient STRICT mTLS, Calico deny-by-default NetworkPolicy, least-privilege Istio authorization, and positive/negative identity/connectivity tests.

Default ServiceAccount is prohibited for application workloads. Single-server cannot disable Ambient/NetworkPolicy to save capacity.

## 8. Public edge/client-address trust

```text
Internet -> upstream mitigation -> external L4 -> Traefik -> Caddy/Coraza -> BFF
```

- external L4 preserves validated client source through trusted PROXY v2;
- Traefik trusts exact L4 CIDRs only; insecure PROXY/forwarded modes prohibited;
- direct non-approved access to Traefik origin denied before routing;
- caller forwarding/private client-IP headers are not authority;
- Caddy strict proxy parsing + internal client-IP overwrite;
- BFF accepts one exact canonical IP only on WAF path;
- backends receive only typed exact IP context from approved BFF workload;
- direct Internet->BFF and Traefik->BFF bypass prohibited;
- raw client IP not ordinary telemetry or durable state.

WAF/application quotas complement upstream volumetric protection; none replaces another.

## 9. Secrets/OpenBao

OpenBao 2.6.1 remains unchanged production secret authority with current Raft/PVC/Shamir/snapshot/restore/Kubernetes Auth/External Secrets workflows.

- secrets never enter Git/images/values/logs/traces/metrics/unapproved CI;
- ADR-0045 requires blocking Gitleaks current-tree and Git-history detection for committed secret material when implemented;
- a real committed credential is revoked/rotated when exposure is plausible; deleting it from the latest tree is not sufficient remediation;
- scanner output never republishes the secret into logs, annotations, or artifacts;
- normal hot paths use validated local mounted material, not per-request OpenBao RPC;
- key-ring reload/rotation/stale-source behavior stays fail-closed per owning service;
- no plaintext/Git fallback under outage/capacity pressure.

## 10. DevSecOps supply chain/Kyverno

Production artifacts are immutable digest-only, signed, provenance-bound, SBOM-attested, advisory-correlated, and fail-closed at admission.

The current control chain is:

```text
Gitleaks -> Semgrep/static/integrity -> OSV-Scanner dependency advisory -> tests -> final image -> Syft -> Grype -> Cosign -> Kyverno -> staging -> same-digest production promotion
```

Responsibilities are distinct:

- Semgrep: first-party source SAST/repository policy;
- Gitleaks: current-tree and Git-history secret detection;
- Gradle verification/locks: dependency integrity/reproducibility, not CVE authority;
- OSV-Scanner: early declared/locked dependency advisory scanning on PR/push/scheduled security verification;
- Syft: CycloneDX SBOM from the final releasable image;
- Grype: final-image/SBOM release/deployed-artifact vulnerability correlation under ADR-0035/0038;
- Cosign: exact-digest signature, provenance, and signed SBOM attestation;
- Kyverno: production admission verification.

A passing OSV lockfile scan is not final-image vulnerability evidence. Final-image OS/runtime/JDK/native/transitive visibility belongs to Syft+Grype.

Trivy and OWASP Dependency-Check are not selected current default tools. A later addition requires ADR-0045 distinct-coverage evidence rather than tool-count duplication. Separate Semgrep Secrets/Supply Chain/hosted product capabilities are also not implied by the repository Semgrep CLI decision.

Kyverno 1.18.2 remains production admission engine. New greenfield production controls use stable CEL-based `policies.kyverno.io/v1` policy types. CI/render gates reject new legacy `kyverno.io/v1` ClusterPolicy/Policy and `kyverno.io/v2` CleanupPolicy/ClusterCleanupPolicy except a narrow migration-only exception with owner/removal deadline.

Single-server may use one Kyverno replica but cannot switch critical admission to audit-only.

## 11. Human privileged access

Single-server normal path:

```text
approved device -> WireGuard -> management address -> OpenSSH/FIDO2 -> JIT
```

- public SSH denied;
- independent per-device peer keys/minimal routes;
- no root/password/shared SSH;
- FIDO2 presence+verification;
- network/login alone grants no admin;
- two-reviewer <=30m write elevation and bounded read-only grant;
- OS/`sudo`/K8s/DB audit exported off-host;
- shell history is not authoritative audit;
- provider console is break glass only.

HA retains current Teleport path.

## 12. Day-One observability security

ADR-0044 ordinary telemetry is mandatory from first executable service commit but is **not** security/business authority.

### Application telemetry

- structured allow-list JSON logs;
- Micrometer low-cardinality metrics/observations;
- OpenTelemetry traces through internal OTLP Collector;
- trace/baggage/correlation never becomes authN/authZ/tenant/quota/idempotency/audit authority;
- no secret/credential/raw contact/raw IP/full body/SQL bind/complete gRPC metadata/compromised-password hash material in logs/metrics/traces;
- baggage allow-list excludes subject/contact/tenant/session/raw-IP/secret values;
- trace IDs are not metric labels.

### Collector/backends

- OTLP receiver internal-only and wrong workloads/public sources denied;
- dedicated Collector ServiceAccount/RBAC/NetworkPolicy;
- restricted telemetry egress;
- memory limiter/batching/finite queues/backpressure/drop observability;
- redaction/filtering before export;
- no host network/privilege escalation;
- only exact read-only Kubernetes pod/container log paths mounted from host;
- no broad host filesystem access.

Single-server Loki/Tempo/Prometheus/Grafana/Alertmanager/Collector are non-HA/local. Independent external black-box monitoring outside the host failure domain is required before production.

Ordinary telemetry loss does not fail ordinary business requests. Required privileged/security audit remains separate durable/off-host evidence and cannot be silently converted to best-effort Loki/Collector telemetry.

## 13. Security availability

Single-server accepts outages but never weaker decisions:

- Redis/time/capacity failure -> fail-closed availability, not local quota bypass;
- missing trusted client identity -> fail closed, no caller-header fallback;
- Authorization unavailable -> no ALLOW;
- mandatory Gitleaks/Semgrep/OSV/Syft/Grype/Cosign/Kyverno evidence unavailable or stale beyond the policy that owns that gate -> no affected merge/promotion/bypass;
- Kyverno unavailable -> no protected admission bypass;
- OpenBao unavailable -> no plaintext/Git secret fallback;
- Ambient pressure -> production gate fails, mTLS not disabled;
- WireGuard unavailable -> public SSH remains denied;
- telemetry unavailable -> no security/audit/control bypass;
- host loss -> external monitor alerts while local observability is unavailable.

## 14. Verification

Security evidence includes authentication/MFA, RLS/tenant isolation, Authorization failures, quota exact/aggregate/common-clock/cardinality tests, client-address/WAF bypass negatives, workload mTLS/NetworkPolicy, OpenBao/secret scans, Gitleaks current-tree/history secret fixtures with redacted output, Semgrep source-security fixtures, OSV declared/locked dependency advisory scanning, final-image Syft/Grype/Cosign evidence, Kyverno CEL/supply-chain negatives, WireGuard/FIDO/JIT/audit, HIBP corpus/freshness/source evidence, telemetry PII/cardinality/context/Collector/back-end outage tests, independent host-loss detection, and complete-stack capacity/DR.

Documentation alone remains `NOT VERIFIED`.