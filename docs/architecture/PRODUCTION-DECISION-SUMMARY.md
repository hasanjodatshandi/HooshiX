# Production Decision Summary — Current State

- **Reviewed:** 2026-08-15
- **Mode:** current-only
- **Selected production profile:** `production-single-server`
- **Availability posture:** explicit non-HA
- **Status:** architecture target; repository implementation/evidence state is tracked in `implementation-status.md` and production traffic remains gated by `PRODUCTION-READINESS-CHECKLIST.md`

This document is a summary only. The Decision Register, retained ADRs and current-state architecture/service documents are authoritative for detailed contracts.

## 1. Application and service model

- Backend services use DDD + Hexagonal Architecture with inward dependencies.
- Java services use the current Java/Spring MVC/Virtual Threads baseline, constructor injection, independent builds and executable quality gates when implemented.
- Browser traffic uses Web BFF; ordinary internal synchronous communication is gRPC; asynchronous integration uses Kafka only where a durable event boundary is appropriate.
- Each service owns its contracts, data, deployment and release lifecycle.
- Cross-service database access, joins/FKs, shared runtime credentials and shared business/persistence models are prohibited.
- ADR-0040 is the narrow immutable read-only SQLite reference-data exception for Compromised Password. ADR-0041 Reference Data uses an immutable image bundle and has no runtime DB/Redis/Kafka source.
- Planned implementation targets are not runtime evidence; current presence is recorded only in `implementation-status.md`.

## 2. Selected production topology

ADR-0042 selects one physical server as the initial production profile:

```text
1 K3s server/workload node
Kubernetes 1.35.6 / K3s v1.35.6+k3s1
1 application replica per service
HPA disabled
availability PDB disabled
no node/control-plane HA claim
```

K3s uses Calico as the custom CNI. K3s Flannel/network-policy controller and bundled Traefik/ServiceLB are disabled. The repository-pinned Traefik/Gateway/WAF stack remains authoritative.

`production-ha` remains the expansion profile with redundant control plane/workers, dedicated PostgreSQL clusters, HA Kafka/Redis/Kyverno and replicated service targets.

Service-doc replica/PDB/HPA targets such as `>=3`, `PDB2` or `3..12` are the HA targets unless explicitly stated otherwise. The single-server profile overrides only infrastructure placement/availability settings. Business/security contracts remain unchanged.

## 3. PostgreSQL

Single-server production uses one physical CloudNativePG cluster with one PostgreSQL instance for mutable service databases.

Every PostgreSQL-backed service still owns:

- a distinct database;
- distinct runtime role;
- distinct migration/owner role;
- distinct Flyway history and release lifecycle;
- no cross-service `CONNECT`/object privilege or SQL integration;
- forced tenant RLS where applicable.

Aggregate application pools across all service pods stay <=70% of shared `max_connections`; >=30% remains for migrations, backup/recovery, monitoring, administration and emergency work.

This profile accepts shared PostgreSQL process/host/storage blast radius and no automatic primary failover.

## 4. PostgreSQL backup and cold recovery

`pg_dump + cron` is not the production backup strategy.

Both profiles retain:

- encrypted off-site physical backup;
- continuous WAL archive;
- PostgreSQL DR RPO <=5 minutes;
- daily online base backup;
- 35-day PITR;
- monthly retained recovery artifact for 12 months;
- backup verification;
- monthly restore evidence;
- quarterly full cold-DR.

In single-server, physical PITR restores the complete shared cluster into an isolated recovery environment. A service-specific recovery then transfers only the required service database through an approved controlled procedure. Unrelated current service databases are not destructively restored.

`../runbooks/production-cold-dr.md` defines the full-host recovery sequence, including clean host/management access, K3s/Calico, unchanged OpenBao recovery, PostgreSQL, immutable artifacts, Redis, Kafka, erasure/legal-hold replay, edge/security checks, and the traffic-enable evidence gate. ADR-0004 remains the RPO/RTO authority.

## 5. Kafka

Single-server Kafka uses one Kafka 4.2.x process with combined KRaft broker/controller roles:

```text
RF=1
minISR=1
acks=all
idempotent producer enabled
unclean leader election disabled
```

This is a formal non-HA exception. Broker/node/disk outage can stop async transport and may lose broker-local data.

Kafka remains rebuildable transport, not business source of truth. Transactional Outbox, Inbox/idempotency, finite retry/DLQ, stable event identities and >=35-day critical replay/dedup evidence remain mandatory.

The HA profile retains 3 brokers + 3 dedicated controllers with RF=3/minISR=2.

## 6. Security Redis

Single-server uses one Redis instance with TLS, per-owner ACL/key isolation, `noeviction`, AOF `appendfsync everysec`, fail-closed quota/session behavior and no failover claim.

AOF reduces restart loss but is not HA. Lost session state results in re-authentication. Semantic quota correctness, dual-clock checks, pseudonymous keys and anti-lockout rules remain unchanged.

For public network quota dimensions, only ADR-0043 trusted client-network identity is valid. Caller forwarding headers or a shared proxy address cannot become fallback quota identity.

The HA profile retains primary/replicas/Sentinel.

## 7. Istio Ambient and workload identity

Istio Ambient remains the service-mesh security model. Production workloads retain dedicated ServiceAccounts, STRICT mTLS, least-privilege authorization and Calico deny-by-default NetworkPolicy.

Single-server production requires a complete-stack benchmark of `istiod`, Istio CNI and `ztunnel` CPU/RAM/latency/throughput plus Calico interaction. Waypoints are absent by default.

If Ambient cannot fit the validated host capacity envelope, production approval fails. Increase host capacity or approve a reviewed replacement security architecture. Do not silently disable workload identity or strict mTLS.

## 8. Kyverno and supply chain

Kyverno is retained. It is not removed or changed to audit-only.

Single-server may run one Kyverno replica and a reduced high-value policy inventory, but production admission remains blocking for digest-only images, approved signatures, signed provenance, signed CycloneDX SBOM, critical unsafe security-context/host-access patterns and critical workload identity/deployment invariants.

Admission unavailability does not become an allow path. The HA profile retains >=3 Kyverno replicas.

## 9. Production network and client-address trust

ADR-0043 and `network-architecture.md` define the production network trust boundary.

Public path:

```text
Internet
-> upstream volumetric mitigation
-> external L4
-> Traefik
-> Caddy/Coraza WAF
-> Web BFF
```

The external L4 preserves validated original client source using PROXY protocol v2. Traefik trusts PROXY only from exact reviewed external-L4 source CIDRs. Insecure PROXY/forwarded-header trust is prohibited. Caddy uses strict trusted-proxy parsing and replaces the internal client-IP header. BFF accepts only that server-derived value on the WAF-only ingress path.

Public `Forwarded`, `X-Forwarded-*`, `X-Real-IP`, and private client-IP headers are not authority. Missing/invalid trusted network identity fails closed for quota-required operations. Direct Internet -> BFF and Traefik -> BFF WAF bypass remain prohibited.

## 10. Human privileged access

Single-server does not deploy Teleport. Normal management uses:

```text
approved operator device
-> WireGuard management overlay
-> host management address
-> hardened OpenSSH + hardware-backed FIDO2
-> separate JIT privilege
```

WireGuard grants network reachability only. It does not replace human authentication or JIT privilege.

Mandatory controls include public-interface TCP/22 denial, independent per-device WireGuard peers, minimal routes, no shared peer/SSH credentials, FIDO2 user presence + user verification, at least two reviewers for write/admin/database-write elevation, maximum 30-minute write elevation with automatic expiry, separately scoped read-only elevation <=1 hour, `sudo`/OS/Kubernetes/database audit, off-host protected retention and protected break glass.

`.bashrc`, shell history or `PROMPT_COMMAND` logging is not authoritative audit. The HA profile retains Teleport Enterprise Self-Hosted JIT access.

## 11. OpenBao — unchanged

**OpenBao is not part of the topology/network simplification.**

OpenBao 2.6.1 remains the production secret authority under ADR-0011 with the existing Shamir/Raft/PVC/snapshot/restore/unseal and External Secrets/Kubernetes Auth model. Normal application hot paths continue to use validated mounted/local key material rather than per-request OpenBao RPC.

ADR-0042/ADR-0043 MUST NOT remove, replace, bypass or weaken OpenBao.

## 12. Identity, email, MFA and browser security

Identity/BFF security remains under ADR-0012/0016 and service documents. Active TOTP remains required where the current Identity state requires it; Email/SMS verification or recovery is not a freely selectable weaker substitute.

ADR-0009 clarifies email handling: HooshiX deliberately uses a case-insensitive canonical email identity for equality/uniqueness/login/reservation, while preserving a case-preserving delivery representation so the SMTP path does not have to rewrite mailbox local-part spelling.

## 13. Authorization

Protected resource operations retain one authoritative online `CheckPermission`, one attempt, no permission-result cache, no Kafka invalidation authority, no retry and no stale-allow fallback.

The single-server profile may reduce availability; it does not convert dependency failure to ALLOW.

## 14. Threat model

`threat-model.md` is the current formal design-time threat model. It covers assets, actors, trust boundaries, STRIDE/abuse cases, single-host residual risk and mitigation-to-verification mapping.

A documented threat/mitigation is not runtime proof. Security verification and readiness evidence remain mandatory.

## 15. Capacity decision

`2 vCPU / 3-4 GiB RAM` is **not** an approved full-stack production sizing.

Production approval requires complete-stack evidence covering applications, K3s, PostgreSQL/WAL/backup, Redis AOF, Kafka, Istio Ambient, Kyverno, WAF, observability and host network pressure together.

Pass criteria include at least no OOM/sustained swap/node memory-pressure eviction, >=30% validated CPU/memory headroom, applicable >=2x projected peak evidence, safe disk IO, and safe MTU/PMTU/conntrack/file-descriptor/ephemeral-port behavior.

If the host does not pass, increase resources or move to `production-ha`. Do not remove OpenBao, disable Kyverno, weaken Ambient, replace PITR, weaken MFA, disable WAF, disable trusted client-address enforcement, expose public SSH, or convert fail-closed dependencies to fail-open.

## 16. Production-readiness status

The architecture decision is current. The executable application/platform tree is not present at this documentation revision; exact status is in `implementation-status.md`.

Production approval still requires all applicable gates in `PRODUCTION-READINESS-CHECKLIST.md`, including client-address anti-spoofing, management-only WireGuard/OpenSSH access, current threat-model review, cold-DR/RPO/RTO evidence, full-stack capacity, unchanged OpenBao/MFA controls and explicit single-server downtime/host-blast-radius acceptance.
