# Production Architecture Review — Current State

- **Reviewed:** 2026-08-16
- **Status:** architecture target accepted; implementation/runtime evidence not implied
- **Selected profile:** `production-single-server`
- **Availability posture:** explicit non-HA

This document records review conclusions and points to current authority. It does not duplicate full normative rules.

## Outcome

The architecture remains acceptable as a named single-server production profile only with security/correctness/recovery invariants preserved. Current material architecture decisions include:

- Day-One observability runtime/evidence -> ADR-0044;
- Compromised Password source/hash/freshness/provenance -> ADR-0040;
- quota common-mode clock/cardinality/collateral network behavior -> ADR-0024;
- DevSecOps source/secret/dependency-advisory/final-artifact tool responsibilities -> ADR-0045;
- stable post-merge ADR identifiers -> current-only/documentation standards;
- coherent-change PR governance -> repository workflow;
- stricter Reference Data independent-service trigger -> ADR-0041;
- greenfield Kyverno stable CEL policy enforcement -> ADR-0017 + build/CI standard.

Earlier network/management/threat-model/DR/status findings remain represented by ADR-0043, network architecture, threat model, cold-DR runbook, and implementation status.

## Accepted topology

ADR-0042 remains selected:

- one K3s server/workload node;
- one physical PostgreSQL instance with distinct service DB/roles/Flyway/RLS;
- one TLS/ACL/`noeviction`/AOF Redis;
- one combined KRaft broker/controller, RF1/minISR1, explicitly non-HA;
- one application replica per implemented independent service; HPA/PDB off by default;
- Istio Ambient retained behind benchmark gate;
- Kyverno retained blocking/fail-closed;
- evidence-based host sizing;
- OpenBao and end-user MFA unchanged.

No service boundary is changed by this review. Compromised Password remains independent. Reference Data independent deployment is deferred/gated more strictly. ADR-0045 scanner/SBOM/signing tools are pre-runtime CI/release controls and create no application service or request-path dependency.

## Quota/client-address review

ADR-0043 still owns the trusted source chain:

```text
external-L4 validated source
-> trusted PROXY v2
-> Traefik
-> Caddy strict proxy parsing
-> BFF exact canonical client IP
-> typed exact internal context
```

ADR-0024 separates:

```text
hard v1 network quota identity:
  IPv4 /32
  IPv6 /128

aggregate abuse/allocation pressure:
  IPv4 /24
  IPv6 /64
```

Aggregate prefix is not the sole hard user-denial identity. NAT/campus/VPN/IPv6 collateral behavior is an explicit test class.

Single-server app wall time and Redis TIME share a host failure domain, so skew-only detection is insufficient for common-mode clock steps. A local wall-vs-monotonic Clock Safety Guard detects abrupt host-clock discontinuity, with host-sync readiness and 60-second stable re-arm.

`noeviction` plus non-expiring security state can be attacked through high-cardinality new-key creation. New security-state allocation is therefore bounded with low-cardinality capacity controls and fails as `QUOTA_CAPACITY_UNHEALTHY` before eviction/OOM. This remains fail closed and distinct from normal user quota denial.

## Compromised Password review

V1 corpus authority is official offline HIBP Pwned Passwords SHA-1 data.

- SHA-1 is screening-only; Argon2id remains password-storage authority.
- Identity computes full SHA-1 locally and sends only the five-hex prefix.
- Compromised Password stores immutable 20-byte SHA-1 reference rows in SQLite and returns positive-count suffix candidates.
- Runtime has no HIBP provider dependency.
- Dataset readiness age <=35 days; acquisition/build verification at least every 30 days.
- Complete corpus is measured for prefix cardinality/serialized response bounds; implementation does not rely on an unevidenced permanent historical cap.
- Stale/corrupt/missing/incompatible corpus state fails closed.

## Reference Data review

Reference Data remains a valid capability but no longer gains an independent microservice merely because one journey needs it.

Before the independent-service trigger, the approved immutable bundle may live in the owning deployable, initially BFF.

Create `reference-data-service` only after evidence for at least one of:

- >=2 independently deployable consumers;
- independent update/release lifecycle;
- independent security boundary;
- independent scale/availability profile;
- independent operational/team ownership.

## Day-One observability review

Observability is implementation work from the first executable service commit, not a later cleanup phase.

Current single-server target:

```text
structured JSON -> otelcol-contrib -> Loki
Micrometer metrics -> Prometheus -> Alertmanager
OpenTelemetry traces -> otelcol-contrib -> Tempo
Prometheus/Loki/Tempo -> Grafana
external black-box monitor -> approved public edge from outside host failure domain
```

Current pins are in Technology Baseline: Collector 0.157.0, Loki 3.7.4, Tempo 3.0.2, with existing Prometheus/Alertmanager/Grafana retained.

Trace/baggage/correlation is telemetry only, never authN/authZ/tenant/quota/idempotency/audit authority. Collector ingress is private, queues/memory bounded, and its node-log filesystem exception is exact/read-only only.

Local telemetry shares the single-host failure domain, so production requires independent external total-host detection. Required privileged/security audit remains separate durable/off-host authority.

## DevSecOps security review

ADR-0045 standardizes one authority per failure class:

```text
Gitleaks -> Semgrep/static + Gradle integrity -> OSV-Scanner dependency advisory -> tests -> final image -> Syft -> Grype -> Cosign -> Kyverno -> staging -> same signed digest in production
```

Current selected roles:

- Gitleaks 8.30.0: committed/current-tree and protected Git-history secret detection;
- Semgrep: first-party SAST/repository-owned source policy;
- Gradle verification/locks: dependency integrity/reproducibility only;
- OSV-Scanner 2.4.0: early declared/locked dependency advisory scanning;
- Syft 1.51.0: final-image CycloneDX JSON SBOM;
- Grype 0.117.0: final-image/SBOM release/deployed-artifact vulnerability correlation under ADR-0035/0038;
- Cosign 3.0.6: exact-digest signature, provenance, and signed SBOM attestation;
- Kyverno 1.18.2: production admission.

The implemented Compromised Password, Notification, and Identity service CI suites include OSV-Scanner 2.4.0 locked-dependency scanning, and the scheduled repository security workflow reuses those service security suites so declared/locked dependency advisory scanning also runs without a source change. Exact tool/checksum ownership remains in each implemented workflow. This is early dependency feedback only.

Final-image vulnerability authority remains Syft+Grype because the final image can contain OS packages, JDK/runtime files, native libraries, and packaged transitive components outside the lockfile.

A real committed credential requires revoke/rotate handling when exposure is plausible; removing the latest-tree line alone is not remediation. Scanner output remains redacted.

Trivy and OWASP Dependency-Check are intentionally not selected default controls because their expected default roles overlap the current OSV+Syft+Grype chain. They can be proposed later only for a distinct measured coverage gap. Repository Semgrep CLI similarly does not imply separate Semgrep Secrets/Supply Chain product enablement.

This is an architecture decision, not executable evidence. Current repository implementation status is: service-specific Semgrep and OSV locked-dependency scanning exist for the implemented service slices; the Identity workflow also implements Gitleaks current-tree/Git-history scanning. Protected merged-main Compromised Password, Notification, and Identity security suites plus the final baseline aggregator passed on `main@a3766bd`; Syft/Grype/Cosign release automation and production Kyverno admission remain `NOT PRESENT / NOT VERIFIED` until implemented and executed.

## Kyverno review

The current Kyverno 1.18.2 line uses stable CEL-based `policies.kyverno.io/v1` policy types for greenfield HooshiX production controls. CI/render gates reject new legacy ClusterPolicy/CleanupPolicy families unless a narrow migration-only exception exists.

Production admission remains separate from vulnerability database lookup. Grype promotion decisions and continuous rescanning own final-artifact vulnerability freshness; Kyverno verifies required immutable artifact identity/evidence.

## Governance review

Current-state documentation remains current-only, but ADR IDs are stable after merge:

- no renumber;
- no reuse;
- gaps permitted;
- fully superseded ADR keeps a compact stable-ID provenance pointer and is not current implementation authority.

PR workflow is based on coherent engineering change rather than conversation prompt. This preserves atomic review/rollback while allowing a focused post-merge correction when a real material defect is found.

## Rejected shortcuts

Still rejected:

- `pg_dump + cron` as primary production recovery;
- weakening OpenBao/Kyverno/Ambient/WAF/MFA/RLS/Authorization/quota/audit to fit one host;
- caller forwarding headers as network authority;
- insecure Traefik PROXY/forwarded trust;
- proxy address fallback for missing client identity;
- aggregate `/24`/`/64` as sole hard v1 user quota identity;
- public SSH or WireGuard as substitute for FIDO2/JIT;
- shell history as privileged audit;
- runtime HIBP fallback or SHA-1 password storage;
- observability headers/baggage as business/security authority;
- public OTLP/management endpoints or broad Collector host access;
- suppressing a real committed credential instead of revoke/rotate remediation;
- treating Gradle dependency verification/locks as vulnerability evidence;
- treating OSV-Scanner lockfile results as final-image vulnerability evidence;
- adding Trivy/OWASP Dependency-Check merely to increase scanner count without a distinct coverage objective;
- treating repository Semgrep CLI as proof of separate Semgrep Secrets/Supply Chain product coverage;
- bypassing stale/unavailable required scanner/feed/signature/provenance/SBOM/admission evidence;
- legacy Kyverno policy types for new greenfield production controls;
- false HA claims or production-readiness claims from documentation.

## Production-readiness conclusion

Architecture has moved from design into multiple executable service slices, but production readiness is **not** proven.

The repository contains repository-governance CI plus executable Compromised Password, Notification, and Identity registration plus local-password authentication/Session/RefreshFamily/JWT-signing slices under their service roots. Service-specific Semgrep and OSV locked-dependency gates are present, and Identity adds the implemented Gitleaks tree/history gate. The wider Identity ADR-0012 surface and other application/platform implementation remain incomplete; root `deploy/` and `infrastructure/` production platform implementation, production corpus/release evidence, the complete ADR-0045 final-artifact release chain, and deployed observability/platform runtime are still absent or not verified as recorded in `implementation-status.md`. Repository source and local/CI evidence are not staging/runtime/release evidence.

Production traffic remains blocked until applicable readiness gates have executed evidence, including Gitleaks tree/history scanning, current OSV dependency-advisory state, final-image Syft/Grype/Cosign evidence, Kyverno admission negatives, quota fault/cardinality tests, HIBP corpus build evidence, real logs/metrics/traces, independent host-loss detection, complete-stack capacity, and cold DR.
