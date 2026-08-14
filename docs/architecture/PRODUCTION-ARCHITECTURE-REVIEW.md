# Production Architecture Review — Current State

- **Reviewed:** 2026-08-15
- **Status:** architecture target accepted; implementation/runtime evidence is not implied
- **Documentation mode:** current-only
- **Selected initial profile:** `production-single-server`
- **Availability posture:** explicit non-HA

This document records review conclusions. It does not duplicate the full normative rules from ADRs/current-state documents.

## Outcome

The single-server architecture remains acceptable only as a named non-HA production profile with security/correctness/recovery invariants preserved.

The follow-up review confirmed that the architecture already models the major single-server capacity, SLO, shared-PostgreSQL, Authorization, Redis/Kafka, OpenBao, MFA and implementation-evidence risks. It also identified cross-cutting gaps that are now represented by current authority:

- **trusted public client-address authority** -> ADR-0043 + `network-architecture.md`;
- **concrete single-server management network** -> ADR-0043 + ADR-0030;
- **formal platform threat model** -> `threat-model.md`;
- **full cold-DR procedure** -> `../runbooks/production-cold-dr.md`;
- **repository implementation/evidence status** -> `implementation-status.md`;
- **email product identity vs SMTP delivery representation** -> ADR-0009;
- **dependency-registry authority ambiguity** -> corrected in `reliability-and-observability.md` to match ADR-0033;
- **duplicated summary/source rules** -> source maps remain indexes and reference the authoritative owner instead of becoming a second specification.

## Accepted production-profile decisions

The existing ADR-0042 decisions remain accepted:

- one K3s server/workload node;
- one physical PostgreSQL instance with distinct service databases/roles/Flyway histories/RLS;
- one Redis instance with TLS/ACL/`noeviction`/AOF/fail-closed behavior;
- one combined KRaft Kafka broker/controller with RF=1/minISR=1 and formal non-HA acceptance;
- one application replica per service with HPA/availability PDB disabled by default;
- Istio Ambient retained behind a complete-stack benchmark gate;
- Kyverno retained with a smaller high-value policy set but blocking enforcement;
- evidence-based host sizing rather than an assumed 2 vCPU / 3-4 GiB production claim;
- OpenBao and end-user MFA unchanged.

ADR-0043 additionally accepts:

- external L4 as source-address authority with trusted PROXY protocol v2 to Traefik;
- strict proxy/header trust through Traefik/Caddy/BFF; caller forwarding headers are not authority;
- typed BFF-derived client-network context for downstream public security quotas;
- dedicated WireGuard management overlay for normal single-server SSH reachability;
- network admission separate from FIDO2 human authentication and JIT privilege;
- public-interface TCP/22 denial.

## Rejected shortcuts

The review does not accept:

- replacing physical WAL/PITR/off-site recovery with `pg_dump + cron`;
- removing or weakening Kyverno, Ambient, WAF, OpenBao, MFA, RLS, Authorization or fail-closed quota behavior to fit one host;
- using caller `Forwarded`/`X-Forwarded-*`/`X-Real-IP` as security authority;
- enabling Traefik insecure forwarding/PROXY trust in production;
- falling back to a proxy address when trusted client identity is unavailable for a required network quota;
- exposing normal SSH on the public interface;
- using WireGuard as a substitute for human FIDO2 identity/JIT authorization;
- using `.bashrc`/shell history as privileged-session audit;
- claiming one-node/one-broker/one-Redis/one-PostgreSQL topology is HA;
- claiming planned source/deployment paths are implemented without repository/runtime evidence.

## Review of second-order complexity

The platform intentionally keeps distributed bounded-context/service boundaries even though the selected first production profile is one physical server. This creates operational cost without node-level HA benefits.

The follow-up review considered consolidation/modular-monolith alternatives but did **not** identify a correctness or security defect that requires changing the current bounded-context decisions. No service boundary is changed in this review. A future product/cost decision may revisit deployment consolidation through a separate architecture decision with contract/data/security migration analysis.

Compromised Password remains an independent boundary under ADR-0040. Reference Data remains implementation-gated under ADR-0041.

## Network/security review

The prior architecture had the correct high-level public path but did not define one canonical source-address authority. This was material because ADR-0024 uses network identity for security quotas.

ADR-0043 closes that ambiguity:

```text
validated external-L4 client source
-> trusted PROXY v2
-> Traefik sanitized forwarding state
-> Caddy strict trusted-proxy resolution
-> server-derived BFF client IP
-> typed internal network context
-> /24 or /64 HMAC quota identity
```

Missing/malformed/untrusted identity fails closed where a network quota is required. Raw client IP is not normal durable state or telemetry.

The single-server management path is now concrete:

```text
approved operator device
-> WireGuard
-> host management address
-> OpenSSH/FIDO2
-> JIT privilege
```

Provider console is break-glass only. Public SSH is not a recovery fallback.

## Recovery review

ADR-0004 remains RPO/RTO authority. The new cold-DR runbook turns the prior high-level recovery model into one ordered operator procedure without claiming it has executed.

The sequence covers clean host/management access, K3s/Calico, unchanged OpenBao, GitOps security controls, shared PostgreSQL PITR, immutable reference artifacts, Redis, Kafka, erasure/legal-hold replay, services, trusted edge/client address, security checks and traffic enablement.

Backup success remains insufficient. Quarterly full cold-DR must measure the actual platform RTO and applicable component RPOs.

## Threat-model review

`threat-model.md` now captures assets, actors, trust boundaries, STRIDE threats, abuse cases, mitigations, residual risk and verification mapping.

The most important residual single-server security risk is host/root compromise: one host has a broad local blast radius. Workload/network policy is not presented as a security boundary against host root. Recovery therefore depends on off-host audit/recovery material, credential rotation/revocation, trusted rebuild artifacts and explicit risk acceptance.

## Email identity review

HooshiX keeps the existing product behavior that email identity equality is case-insensitive. ADR-0009 now states this explicitly as a product identity decision rather than an SMTP protocol claim.

A case-preserving delivery representation is retained so outbound transport does not have to rewrite mailbox local-part spelling. Identity equality/uniqueness and delivery spelling cannot be mutated independently to bypass verification or uniqueness.

## Dependency-policy review

ADR-0033 remains unchanged: the machine-readable dependency registry owns operation-edge criticality/failure/retry-owner/fallback/policy references. Exact deadlines, breaker details, idempotency and concurrency remain in owning contracts/current policy.

The review rejected copying all those values into the registry because that would create duplicate authority unless the architecture deliberately migrated ownership to the registry.

## Capacity and availability review

Existing requirements remain correct:

- actual service SLO/SLI and downtime remain measured in single-server;
- missing physical redundancy does not remove real downtime from error budgets;
- complete-stack benchmark is simultaneous, not component-by-component only;
- >=30% validated CPU/memory headroom and applicable >=2x peak evidence are required;
- shared disk pressure includes PostgreSQL WAL/checkpoint/backup, Redis AOF/rewrite, Kafka and telemetry;
- network capacity evidence now also covers MTU/PMTU, conntrack, file descriptors/listen queues, ephemeral ports/TIME_WAIT and interface errors/drops.

Failure to fit requires larger capacity or `production-ha`, not security downgrades.

## Production-readiness conclusion

Architecture is more implementation-ready after these clarifications, but **runtime production readiness remains unproven**.

At this documentation revision, planned application/platform/CI targets are not present; see `implementation-status.md`. Production traffic remains blocked until all applicable `PRODUCTION-READINESS-CHECKLIST.md` gates have executable evidence.
