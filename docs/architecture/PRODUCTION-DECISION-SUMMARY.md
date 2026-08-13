# Production Decision Summary — 2026-08-11

This document summarizes the production-review decisions added after ADR-0040. The ADR files and Decision Register are authoritative.

## ADR-0041 — Semantic quotas

**Decision:** no quota microservice. Identity/Authorization enforce their own semantic quotas with one atomic token-bucket/GCRA-equivalent operation on ACL-isolated Redis Sentinel using pseudonymous HMAC keys, a 75ms/one-attempt dependency budget, and explicit anti-lockout sequencing for login/recovery subject counters.

**Why:** closes ADR-0040 without adding another synchronous service or PostgreSQL write path. Source abuse is blocked hard while account-subject pressure cannot be weaponized into permanent remote lockout.

## ADR-0039 / ADR-0056 / ADR-0062 / ADR-0066 — Online Authorization runtime, SLO, overload, and breaker recovery

**Decision:** retain ADR-0039 online authorization but engineer it as a critical HA dependency: one final check per protected resource request, >=3 replicas, PDB/topology spread, availability>=99.95%, p95<=100ms, p99<=200ms production SLO (75/150ms engineering target), 300ms one-attempt caller ceiling, PostgreSQL as the only synchronous downstream, explicit overload isolation, paired burn-rate alerting, and de-correlated fail-closed breaker recovery. ADR-0042 is the historical initial SLO/capacity decision; its superseded production latency/Hikari gate values are not current.

**Why:** preserves immediate fail-closed permission semantics without adding stale cache behavior or duplicate BFF checks.

## ADR-0043 — Notification local key ring

**Decision:** remove OpenBao Transit from Notification per-message hot paths. Use a purpose-specific mounted local AES-256-GCM key ring sourced from OpenBao through External Secrets.

**Why:** Transit added latency/availability coupling while an already-authorized compromised Notification workload could still obtain plaintext through Transit. Local keys materially simplify v1 and let OpenBao remain control-plane rather than request-path infrastructure.

## ADR-0044 — Kafka durability/DR

**Decision:** Kafka 4.2.x KRaft with 3 brokers + 3 dedicated controllers; critical RF3/minISR2/acks=all/idempotent producers. Kafka is rebuildable transport; cold DR recreates it from Git and replays 35-day retained transactional outboxes or reconstructs events from authoritative service state.

**Why:** strong primary-cluster durability without creating a second business source of truth or a hot DR Kafka cluster.

## ADR-0045 — Browser/BFF security

**Decision:** OIDC Authorization Code + PKCE S256, exact redirects, server-side state/nonce/PKCE verifier, `__Host-` server-side session, rotation/fixation defense, Origin + synchronizer-token CSRF, strict CORS, security headers, and no browser internal credentials.

**Why:** closes OAuth/browser/session attack surfaces explicitly instead of relying on SameSite or the WAF alone.

## ADR-0046 — Supply-chain admission

**Decision:** immutable Cosign-signed images + signed provenance + SBOM are enforced at production admission through Kyverno stable CEL image-validation policy after an audit rollout and HA admission deployment.

**Why:** CI signing is not enforcement if an unexpected or unsigned image can still be scheduled.

## ADR-0047 — Notification simplification

**Decision:** remove the custom clock-health-agent/Chrony socket RPC/primary-Pod binding/dispatch-fence/coordinator from current v1. Keep PostgreSQL-authoritative immutable deadlines, short durable `DISPATCHING` transaction before provider I/O, reconciliation, and no blind redispatch after unknown outcomes.

**Why:** this was the largest bespoke engineering/on-call hotspot. Credential validity still belongs to Identity; CloudNativePG synchronous durability closes the important failover state-loss window more simply.

## ADR-0048 — PostgreSQL HA/recovery

**Decision:** CloudNativePG 3-instance PostgreSQL 18.x, automatic failover, one synchronous failover-eligible acknowledgement for required durable writes, <=70% aggregate application connection budget, continuous WAL archive, daily physical backup, 35-day PITR, monthly isolated restore, quarterly DR.

**Why:** established the CloudNativePG HA/durability mechanics. ADR-0057 later strengthened production isolation further by assigning each persistent service its own physical CloudNativePG cluster; ADR-0064 standardizes that fleet so the additional isolation does not become three hand-built operational systems.

## ADR-0053 / ADR-0057 — Database and physical isolation

**Decision:** every persistent microservice owns a distinct PostgreSQL database,
credentials and Flyway history; ADR-0057 additionally requires a dedicated
production CloudNativePG cluster, independent backups, and forced tenant RLS for
tenant-owned tables.

**Why:** application credential, DBA, backup, noisy-neighbor, and recovery blast
radius are now scoped to one service instead of the whole platform.

## ADR-0049 — Iran SMS

**Decision:** IPPanel Edge **Webservice** mode for Iran, not provider-managed Pattern rendering. Notification remains the sole exact-content/template authority; provider submission has bounded timeouts/no transport retry; ambiguous results reconcile rather than blind resend; recipient delivery evidence is polled with bounded backpressure.

**Why:** enables Iran SMS/SMS MFA without creating a second mutable presentation authority or a new public webhook surface.

## ADR-0050 — Platform compatibility/CNI

**Decision:** pin one tested production compatibility set in Technology Baseline, use Calico OSS standard dataplane for NetworkPolicy with upstream Istio Ambient, retain immutable image digests and explicit upgrade governance. Argo CD remains initially non-HA because it is not request-path infrastructure; OpenBao remains single-node because hot paths use validated local key material.

**Why:** closes version/CNI ambiguity while avoiding HA/control-plane components that do not materially improve request availability in v1.

## ADR-0051 — Self-hosted Kubernetes HA

**Decision:** run three dedicated stacked control-plane/etcd nodes and at least
three schedulable workers, with a redundant stable API endpoint, N+1 critical
worker capacity, replica spread, and tested encrypted off-node etcd snapshots.

**Why:** service/database HA is incomplete if Kubernetes itself has a hidden
single-node control-plane or worker-placement failure domain. Stacked etcd gives
quorum while avoiding the additional three hosts required by external etcd.

## Security Baseline — Password credentials

**Decision:** close ADR-0006's pending password-hash input with Argon2id
(`m=19 MiB`, `t=2`, `p=1`, random 16-byte salt, >=32-byte hash), 15..128
Unicode-code-point password policy, NFC normalization, compromised-password
blocklist, no composition/periodic-rotation rules, rehash-on-success, and a
bounded password-hash bulkhead.

**Why:** this gives a memory-hard modern baseline without turning login into an
unbounded CPU/memory DoS surface or weakening user password-manager/passphrase
usability.

## ADR-0052 — Identity signing-key lifecycle

**Decision:** retain RS256 but use RSA-3072 local Identity signing keys from
OpenBao, immutable `kid` values, 90-day rotation, next-key prepublication, and a
local GitOps public verification bundle for every verifier.

**Why:** makes token verification fast and independent of Identity/OpenBao on the
request path while giving key rotation and compromise response explicit rules.

## Net architecture effect

The production review **adds** HA where it directly protects user/business correctness (Authorization replicas, Redis Sentinel, PostgreSQL synchronous HA, Kafka replication, WAF/admission replicas) and **removes** bespoke hot-path mechanisms whose cost exceeded their v1 value (Notification Transit RPC and clock-agent/fence subsystem).

The principal remaining bottlenecks are therefore measurable capacity boundaries rather than unresolved architecture: online Authorization, the operational/capacity cost of the per-service PostgreSQL HA fleet, security Redis, WAF inspection, Kafka disk/partition capacity, IPPanel polling, and external provider availability. See `performance-and-bottlenecks.md`.

## ADR-0054 — Quota time safety

**Decision:** semantic quotas use trusted application time plus Redis `TIME`, fail
closed above two seconds of skew, use the minimum validated time for refill, and
do not treat Redis TTL expiry as permission to reset a security budget.

## ADR-0055 / ADR-0056 — Failure containment and Authorization overload

**Decision:** synchronous dependencies use semantic circuit breakers/bulkheads;
Authorization retains one online no-cache/no-retry decision but adds safe local
invalid-token prechecks, fair-share load shedding, a fail-closed breaker, and a
p95<=100ms/p99<=200ms production SLO. Bloom filters do not participate in
authoritative permission decisions.

## ADR-0058 — Data-subject erasure

**Decision:** logical deletion remains reversible lifecycle state; approved
irreversible erasure is coordinated by Identity and executed idempotently by each
owning service with non-PII receipts, legal-hold semantics, and mandatory
re-erasure after backup restore.

## ADR-0059 — Volumetric DDoS

**Decision:** production hosting requires upstream L3/L4 mitigation/scrubbing and
edge connection controls before the in-cluster L7 WAF.

## ADR-0060 — Human production access

**Decision:** Teleport Enterprise Self-Hosted provides JIT SSO/WebAuthn privileged
access, two-reviewer production write elevation, short TTLs, and audited/recorded
Kubernetes/database/host sessions. Standing admin/root/shared credentials are
prohibited.

## ADR-0061 — PII-safe logging enforcement

**Decision:** custom Semgrep rules, structured attribute allow-lists, telemetry
redaction, seeded canary sink tests, and runtime detectors enforce the logging
policy.

## Clarifications from the final security review

- SBOM was already mandatory in ADR-0046; the implementation is strengthened by
  digest-indexed CycloneDX inventory plus pinned Syft/Grype CVE correlation.
- Java 25 already contains JEP 491, so `synchronized` is not blanket-banned for
  virtual-thread pinning. JFR/load tests target the remaining native/FFM and
  resource-saturation cases instead.

## ADR-0062 — Authorization SLO alerting and breaker recovery

**Decision:** keep the 99.95% / p95<=100ms / p99<=200ms Authorization objectives and paired multi-window burn. ADR-0066 refines recovery with bounded per-instance reopen de-correlation and one real half-open `CheckPermission` probe in flight at a time; three consecutive infrastructure-successful probes close. A health endpoint never closes the breaker.

## ADR-0063 — Dependency criticality matrix

**Decision:** resilience semantics attach to operation->dependency edges. ADR-0066 makes `dependency-criticality.yaml` the machine-checkable source, with CI schema/coverage/render checks and explicit composition rules for operations that have both authoritative and optional dependencies. Missing fallback means no fallback.

## ADR-0064 — Dedicated PostgreSQL fleet operations

**Decision:** keep dedicated production CloudNativePG clusters from ADR-0057; standardize them through one GitOps baseline, common monitoring, independent backup identities, restore evidence, and one-cluster-at-a-time upgrade waves. ADR-0067 standardizes monthly restore evidence and compatibility-aware rollback/fail-forward behavior. There is no planned shared-cluster v2 destination.

## ADR-0065 — Continuous CVE response

**Decision:** signed CycloneDX SBOMs are digest-indexed and rescanned at least every six hours. ADR-0068 adds <=2h threat-advisory/KEV ingestion, targeted rescans, active exception-expiry escalation, and deterministic ownership of direct/transitive components by deployed artifact. Critical/known-exploited production findings target immediate incident handling and <=24h mitigation; High production findings target <=48h.

## ADR-0066 — Authorization breaker de-correlation and dependency-policy governance

**Decision:** keep the Authorization SLO/fail-closed model, replace fixed synchronized reopen timing with bounded exponential per-instance de-correlation, serialize real half-open probes, and make `dependency-criticality.yaml` the machine-checkable source of truth. Tenant tier does not affect security breaker recovery.

## ADR-0067 — PostgreSQL restore evidence and upgrade safety

**Decision:** keep monthly isolated restores and quarterly DR, but standardize queryable RPO/RTO/integrity evidence and freeze ordinary service promotion after a failed restore. Upgrade waves stop on staging/production failure; reversible state may roll back, while irreversible/major database transitions never use unsafe automatic downgrade to meet an arbitrary time target.

## ADR-0068 — Vulnerability exception expiry, threat intelligence, and ownership

**Decision:** expired exceptions actively stop authorizing promotion and escalate running-production exposure. CISA KEV plus approved CVE/ecosystem/vendor advisories are ingested frequently and trigger targeted correlation/rescan. No feed is treated as guaranteed zero-day detection. Direct/transitive component accountability follows the deployed service artifact; Platform owns shared base/runtime artifacts and Security owns feed/scanner policy.

## ADR-0069 — Java coding standards and executable quality gates

**Decision:** consolidate implementation-level Java coding rules in `docs/engineering/coding-standards.md` and require independent per-service Gradle builds to expose Spotless, SpotBugs, ArchUnit, repository Semgrep rules, dependency verification, applicable test/contract tasks, and GitHub Actions required-check evidence. REST errors use RFC 9457 Problem Details; logging receives explicit CR/LF injection, debug-elevation, exception-safety, export-failure, and log-store access rules; persistence receives explicit batch/fetch/flush measurement and no mandatory one-table/one-model rule; Playwright receives selector/test-data/flakiness discipline.

**Why:** documentation-only coding rules are not proof of implementation. Machine-checkable rules should fail CI, while non-machine-provable design quality remains explicit code-review responsibility. The repository still reports implementation evidence as not verified until real service source/build/workflows exist and pass.
