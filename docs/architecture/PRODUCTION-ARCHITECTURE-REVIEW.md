# Production Architecture Review — Current State

- **Reviewed:** 2026-08-15
- **Status:** architecture target accepted; implementation/runtime evidence not implied
- **Selected profile:** `production-single-server`
- **Availability posture:** explicit non-HA

This document records review conclusions and points to current authority. It does not duplicate full normative rules.

## Outcome

The architecture remains acceptable as a named single-server production profile only with security/correctness/recovery invariants preserved. The latest pre-implementation review resolved the remaining material gaps that should be decided before the first executable vertical slice:

- Day-One observability runtime/evidence -> ADR-0044;
- Compromised Password source/hash/freshness/provenance -> ADR-0040;
- quota common-mode clock/cardinality/collateral network behavior -> ADR-0024;
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

No service boundary is changed by this review. Compromised Password remains independent. Reference Data independent deployment is deferred/gated more strictly.

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

ADR-0024 now separates:

```text
hard v1 network quota identity:
  IPv4 /32
  IPv6 /128

aggregate abuse/allocation pressure:
  IPv4 /24
  IPv6 /64
```

Aggregate prefix is no longer the sole hard user-denial identity. NAT/campus/VPN/IPv6 collateral behavior is an explicit test class.

Single-server app wall time and Redis TIME share a host failure domain, so skew-only detection was insufficient for common-mode clock steps. A local wall-vs-monotonic Clock Safety Guard now detects abrupt host-clock discontinuity, with host-sync readiness and 60-second stable re-arm.

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

## Kyverno review

The current Kyverno 1.18.2 line already supports stable CEL-based `policies.kyverno.io/v1` policy types. Greenfield HooshiX production controls use those APIs. CI/render gates reject new legacy ClusterPolicy/CleanupPolicy families unless a narrow migration-only exception exists.

## Governance review

Current-state documentation remains current-only, but ADR IDs are now stable after merge:

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
- legacy Kyverno policy types for new greenfield production controls;
- false HA claims or production-readiness claims from documentation.

## Production-readiness conclusion

Architecture is ready to move from design toward implementation, but production readiness is **not** proven.

The repository now has a repository-governance workflow under `.github/workflows/`, but still lacks executable `services/`, `deploy/`, and `infrastructure/` implementation roots. The governance workflow is not service/runtime/release evidence. The next value comes from an executable vertical slice with Day-One telemetry and negative evidence, not additional speculative architecture.

Production traffic remains blocked until applicable readiness gates have executed evidence, including quota fault/cardinality tests, HIBP corpus build evidence, Kyverno CEL policy checks, real logs/metrics/traces, independent host-loss detection, complete-stack capacity, and cold DR.
