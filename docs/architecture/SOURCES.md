# Architecture Sources — Current State

- **Mode:** current-only
- **Policy:** `../engineering/current-only-documentation-policy.md`
- **Decision index:** `../adr/decision-register.md`
- **Selected production profile:** `production-single-server` under ADR-0042

This map points only to current authoritative documentation. Deleted predecessor ADRs/raw historical notes are intentionally excluded.

## Global entry points

1. `../../AGENTS.md`
2. `../engineering/current-only-documentation-policy.md`
3. `../engineering/repository-change-workflow.md`
4. `../engineering/documentation-standards.md`
5. `README.md`
6. `TASK-REVIEW-MATRIX.md`
7. `../adr/decision-register.md`
8. ADR-0042 when production topology, capacity, availability, PostgreSQL physical placement, Redis/Kafka topology, Kyverno availability, or human infrastructure access is relevant
9. applicable current-state architecture/service documents
10. applicable retained current ADRs
11. technology baselines/compatibility docs when exact versions matter
12. applicable engineering/operations/runbooks

## Production-profile authority

ADR-0042 defines the selected initial `production-single-server` topology. ADR-0022 defines both the selected single-server Kubernetes topology and the `production-ha` expansion topology. The Technology Baseline and Production Compatibility Matrix define exact profile-specific version relationships.

A service document may still state a replicated deployment target such as `replicas >=3`, an availability PDB, or a dedicated PostgreSQL physical cluster because that remains the `production-ha` target. For `production-single-server`, ADR-0042 and the profile-aware platform/data/runtime ADRs override **only** infrastructure topology, replica/HPA/PDB availability settings, and physical datastore placement. They do not override service business contracts, security semantics, data ownership, Flyway ownership, RLS, workload identity, deadlines, fail-closed behavior, event idempotency, or API contracts.

The selected single-server profile has these mandatory invariants:

- one K3s server/workload node; formal non-HA acceptance;
- one application replica and no availability HPA/PDB by default;
- one physical CloudNativePG/PostgreSQL instance with distinct service databases, roles, Flyway histories, RLS, and cross-service privilege denial;
- continuous WAL archive, off-site physical backup, PITR, and isolated restore evidence; `pg_dump + cron` is not the primary backup strategy;
- one Redis with TLS, ACL isolation, `noeviction`, AOF, and fail-closed security/session semantics;
- one combined KRaft Kafka broker/controller with RF=1/minISR=1 and formal non-HA acceptance while Outbox/Inbox/idempotency/replay remain mandatory;
- Istio Ambient retained and benchmark-gated; no silent security disablement for RAM savings;
- Kyverno retained with blocking enforcement; the policy set may be reduced, not bypassed;
- hardened OpenSSH + hardware-backed FIDO2 + time-bounded approved privilege + durable system/`sudo`/boundary audit instead of Teleport in this profile; shell history is not authoritative audit;
- OpenBao 2.6.1 remains the production secret authority unchanged;
- end-user MFA semantics remain unchanged;
- `2 vCPU / 3-4 GiB RAM` is not an approved full-stack production capacity claim without complete-stack evidence.

## Current architecture map

### Platform topology, Java architecture, code quality

- `platform-architecture.md`
- `runtime-and-deployment.md`
- `backend-engineering.md`
- `architecture-fitness-functions.md`
- `performance-and-bottlenecks.md`
- `../engineering/coding-standards.md`
- `../engineering/sql-and-flyway-coding-standards.md`
- `../engineering/frontend-coding-standards.md`
- `../engineering/build-and-ci-quality-enforcement.md`
- ADR-0022, ADR-0039, ADR-0042

Current implementation rules include DDD + Hexagonal inward dependency direction, independent service builds, feature-first/nature-separated packages, strict package naming, Domain/JPA/transport separation, constructor injection, no dumping-ground packages, bounded transactions/dependencies/concurrency, hardened runtime manifests, SQL/Flyway evolution/query discipline, strict TypeScript/React boundaries, and executable architecture/security/quality gates. Production topology is profile-aware and MUST NOT infer HA from replica count on one host.

### Identity, tenancy, sessions, external identity, MFA, erasure

- `security-architecture.md`
- `security-verification-matrix.md`
- `services/identity-service.md`
- `services/web-bff.md`
- ADR-0008, ADR-0009, ADR-0012, ADR-0023, ADR-0028, ADR-0042

Current model includes global users with tenant memberships, trusted active-tenant context, current tenant lifecycle, forced production tenant RLS with pool-safe transaction-local tenant database context, provider-neutral password/TOTP controls, issuer+subject external identity binding, rotating session/refresh semantics, exact-audience JWT signing/verifier lifecycle, BFF-only server-owned audience brokerage, and coordinated erasure evidence. ADR-0042 changes no end-user MFA rule.

### Compromised Password

- `services/compromised-password-service.md`
- `services/identity-service.md` §7
- `security-architecture.md` §4
- `data-and-messaging.md`
- `dependency-criticality.yaml`
- `dependency-criticality-matrix.md`
- `performance-and-bottlenecks.md`
- `reliability-and-observability.md`
- `runtime-and-deployment.md`
- `testing-and-quality-gates.md`
- `PRODUCTION-READINESS-CHECKLIST.md`
- `../technology/technology-baseline.md`
- `../technology/production-compatibility-matrix.md`
- ADR-0004, ADR-0005, ADR-0012, ADR-0025, ADR-0033, ADR-0035, ADR-0038, ADR-0040, ADR-0042

Current v1 model is self-contained: Identity computes SHA-256 and sends only the 20-bit/five-uppercase-hex prefix; Compromised Password performs a bounded exact range lookup from a service-local immutable read-only embedded SQLite dataset and returns suffix/count candidates; Identity keeps the full digest and final compromised/not-compromised decision. The SQLite artifact is rebuildable reference data, not mutable business persistence. There is no runtime HIBP/external compromised-password provider, PostgreSQL, Redis, Kafka, full-dataset JVM cache, or subject-linked application state. Only Identity workload may call the service; dataset corruption/incompatibility/unavailability fails closed. Its replicated HA deployment target does not change the selected single-server one-replica profile override.

### Reference Data

- `services/reference-data-service.md`
- `services/web-bff.md`
- `platform-architecture.md`
- `data-and-messaging.md`
- `dependency-criticality.yaml`
- `dependency-criticality-matrix.md`
- `reliability-and-observability.md`
- `runtime-and-deployment.md`
- `security-architecture.md`
- `security-verification-matrix.md`
- `performance-and-bottlenecks.md`
- `testing-and-quality-gates.md`
- `PRODUCTION-READINESS-CHECKLIST.md`
- ADR-0005, ADR-0016, ADR-0025, ADR-0033, ADR-0041, ADR-0042

Reference Data architecture is decided but executable implementation is evidence-gated. v1 owns only global non-tenant Country, Currency, TimeZone and SupportedLocale reference families. ISO 3166/4217, IANA BCP 47/tzdb and stable CLDR source material enters only through a reviewed offline deterministic importer; exact source revisions live in the immutable bundle manifest. Runtime uses an image-bundled read-only resource with no PostgreSQL/SQLite/Redis/Kafka/source-provider dependency. The initial runtime caller is Web BFF through typed bounded gRPC; public `/api/v1/reference` GET/HEAD routes are anonymous/global, same-origin, edge/WAF-protected and deterministically cacheable. Reference Data is not a generic dictionary or business-validation authority.

### Web BFF/browser boundary

- `services/web-bff.md`
- `security-architecture.md`
- `security-verification-matrix.md`
- `dependency-criticality.yaml`
- `dependency-criticality-matrix.md`
- `runtime-and-deployment.md`
- `reliability-and-observability.md`
- `performance-and-bottlenecks.md`
- `testing-and-quality-gates.md`
- `PRODUCTION-READINESS-CHECKLIST.md`
- ADR-0012, ADR-0016, ADR-0023, ADR-0024, ADR-0025, ADR-0033, ADR-0041, ADR-0042

Current Web BFF model uses the `/api/v1` browser namespace with bounded RFC-9457 errors/requests, exact OIDC state/nonce/PKCE/pre-auth and safe-return rules, trusted Identity evidence, server-owned exact-audience Identity token brokerage, HMAC-located Redis sessions/pre-auth state, 7d idle/30d absolute session limits with five-minute last-seen write coalescing, atomic no-grace session rotation, User->sessions revocation index, AES-256-GCM retained-refresh protection with 90d key rotation/one-hour stale-snapshot fail close, exact synchronizer CSRF + mandatory Fetch Metadata for unsafe authenticated methods, same-origin-only v1 CORS, exact CSP/private no-store behavior, BFF-owned OIDC semantic quotas, erasure participation, public cacheable Reference Data GET/HEAD facade, and deny-by-default workload/egress policy. Browser never receives provider/Identity/downstream credentials and final protected-resource authorization remains in the resource-owning service.

### Authorization

- `security-architecture.md`
- `security-verification-matrix.md`
- `services/authorization-service.md`
- `dependency-criticality.yaml`
- `dependency-criticality-matrix.md`
- `performance-and-bottlenecks.md`
- ADR-0013, ADR-0024, ADR-0025, ADR-0026, ADR-0032, ADR-0033, ADR-0036, ADR-0042

Current model uses an exact Git-owned permission catalog with TENANT/PLATFORM scope and lifecycle/non-reuse rules; immutable SYSTEM Roles plus bounded/versioned custom Roles/direct Membership overrides; one authoritative online success-is-ALLOW `CheckPermission` with no permission-result cache/Kafka invalidation/retry/stale fallback; BFF-facaded but locally authorized tenant management with privilege-escalation prevention, bounded bulk limits and semantic-mutation quotas; atomic owner safety shared by local owner-role mutation and Identity Membership-removal reservations; separate Identity-only fail-closed `CheckPlatformPermission` with no tenant/resource bypass; UUIDv4/HMAC idempotency, durable PII-safe audit, jOOQ/JDBC + forced RLS persistence, and erased-subject tenant/platform authority removal. Profile topology MUST NOT weaken fail-closed Authorization semantics.

### Semantic security quotas

- `security-architecture.md`
- `security-verification-matrix.md`
- `services/identity-service.md`, `services/web-bff.md`, `services/authorization-service.md` as applicable
- ADR-0024, ADR-0042

ADR-0024 is the consolidated current decision for quota ownership, profile-aware Redis topology, atomicity, pseudonymization, anti-lockout behavior, exact Identity registration values, exact Web BFF OIDC start/callback values, Authorization semantic-mutation cost, dual trusted time, no security-significant TTL reset, failure semantics, capacity, and verification.

### Notification

- `services/notification-service.md`
- `services/identity-service.md`
- `security-architecture.md`
- `data-and-messaging.md`
- `reliability-and-observability.md`
- ADR-0006, ADR-0007, ADR-0010, ADR-0014, ADR-0018, ADR-0020, ADR-0042

Current runtime preserves idempotent durable internal gRPC handoff, exact versioned templates/content fixed at acceptance, purpose-specific local AES-256-GCM key rings via OpenBao/External Secrets without routine OpenBao hot-path RPC, PostgreSQL-authoritative time + durable `DISPATCHING` commit, Liara Transactional Email, IPPanel Webservice-mode Iran SMS, authenticated/correlated delivery evidence, bounded retries/reconciliation, and non-PII result callback. The single-server profile removes no OpenBao or Notification correctness control.

### PostgreSQL, persistence, SQL, migrations, recovery

- `data-and-messaging.md`
- `runtime-and-deployment.md`
- applicable `services/*`
- `performance-and-bottlenecks.md`
- `../engineering/sql-and-flyway-coding-standards.md`
- ADR-0019, ADR-0027, ADR-0034, ADR-0037, ADR-0042

ADR-0027 is the current service-isolation decision for mutable relational business persistence. Every such service owns a distinct database, credentials/roles, Flyway history, no-cross-service-SQL/model boundary, forced tenant RLS where applicable, and parameterized transaction-local tenant context. `production-single-server` consolidates only the physical PostgreSQL process/cluster and physical backup identity; it does not consolidate these service ownership boundaries. `production-ha` uses dedicated physical service clusters. ADR-0019/0034/0037 define profile-aware backup/restore/upgrade mechanics. ADR-0040 is the explicit immutable SQLite reference-dataset exception; ADR-0041 Reference Data has no database.

### Kafka, events, contracts

- `data-and-messaging.md`
- ADR-0003, ADR-0015, ADR-0042

Transactional Outbox, consumer idempotency/Inbox, bounded retry/DLQ/replay, Protobuf + Buf compatibility, and rebuildable Kafka DR are current. Kafka is not ordinary request/reply or business source of truth. The selected single-server profile uses one combined KRaft broker/controller with RF=1/minISR=1 and explicit non-HA acceptance; the HA expansion profile keeps RF=3/minISR=2 and separate controller quorum.

### SLOs, reliability, performance, DR

- `reliability-and-observability.md`
- `performance-and-bottlenecks.md`
- `architecture-fitness-functions.md`
- `PRODUCTION-READINESS-CHECKLIST.md`
- `../operations/chaos-engineering-program.md`
- `../operations/incident-response-runbook.md`
- ADR-0004, ADR-0005, ADR-0015, ADR-0019, ADR-0025, ADR-0026, ADR-0032, ADR-0033, ADR-0036, ADR-0037, ADR-0040, ADR-0041, ADR-0042

The single-server profile does not claim node, PostgreSQL-primary, Redis-Sentinel, Kafka-broker, or admission-plane HA. Recovery and fail-closed correctness remain mandatory. Complete-stack capacity evidence, >=30% validated resource headroom, and explicit operator acceptance of whole-platform node failure are production gates.

### Kubernetes, GitOps, edge, mesh, secrets

- `runtime-and-deployment.md`
- `security-architecture.md`
- `security-verification-matrix.md`
- `../runbooks/local-istio-ambient.md`
- `../runbooks/local-traefik-edge.md`
- `../technology/technology-baseline.md`
- `../technology/local-development-baseline.md`
- `../technology/production-compatibility-matrix.md`
- ADR-0001, ADR-0002, ADR-0011, ADR-0017, ADR-0021, ADR-0022, ADR-0029, ADR-0030, ADR-0040, ADR-0041, ADR-0042

Public path remains upstream L3/L4 volumetric mitigation/scrubbing -> external L4 -> Traefik -> Caddy/Coraza WAF -> Web BFF. Internal workloads use dedicated ServiceAccounts, hardened pod security contexts, deny-by-default NetworkPolicy, Istio Ambient strict mTLS, and least-privilege authorization. In `production-single-server`, K3s uses Calico instead of bundled Flannel, the repository Traefik instead of bundled K3s Traefik/ServiceLB, benchmark-gated Ambient, retained fail-closed Kyverno, and hardened OpenSSH/FIDO2 audited JIT access. OpenBao remains unchanged.

### Frontend and BFF implementation

- `services/web-bff.md`
- `security-architecture.md`
- `../engineering/frontend-coding-standards.md`
- `testing-and-quality-gates.md`
- ADR-0016, ADR-0041

Frontend rules cover strict TypeScript, runtime validation of untrusted data, React purity/effect discipline, feature import boundaries, same-origin BFF-only API access, browser token isolation, accessibility/RTL contracts, service-worker/private-cache restrictions, resilient Playwright practices, and route bundle/performance budgets.

### Supply chain, vulnerabilities, human access, logging

- `security-architecture.md`
- `security-verification-matrix.md`
- `testing-and-quality-gates.md`
- `architecture-fitness-functions.md`
- `../engineering/build-and-ci-quality-enforcement.md`
- `../operations/incident-response-runbook.md`
- ADR-0017, ADR-0030, ADR-0031, ADR-0035, ADR-0038, ADR-0040, ADR-0041, ADR-0042

Current supply-chain controls include immutable signed artifacts, signed provenance/SBOM, least-privilege admission-policy authoring, bounded policy-engine external context/egress with SSRF negatives, and continuous deployed-digest vulnerability response. The single-server profile changes Kyverno replica availability and human access implementation only; it does not weaken admission enforcement, audit integrity, or supply-chain requirements.

## Technology/version authority

- `../technology/technology-baseline.md` — approved exact production/application pins and profile-specific topology;
- `../technology/local-development-baseline.md` — local tool/cluster pins;
- `../technology/production-compatibility-matrix.md` — supported production combinations;
- repository wrappers, dependency locks, image digests, chart locks, host package locks, GitOps metadata — exact deployed artifact identity.

Architecture prose uses product families/major-minor lines unless an exact patch is itself a current architecture constraint.

## Documentation/governance authority

- `../engineering/current-only-documentation-policy.md` — retain only effective current decisions under owner's active directive;
- `../engineering/documentation-standards.md` — normative language, document authority, single-source rule, and documentation fitness expectations;
- `architecture-fitness-functions.md` — architecture properties and required executable evidence;
- `security-verification-matrix.md` — security verification families aligned to current stable OWASP ASVS baseline without claiming certification.

## Maintenance rule

When effective architecture changes:

1. update applicable current-state docs;
2. create/update retained current ADR only when it still adds durable decision value;
3. remove/normalize predecessor text after preserving every still-current invariant/contract/security/SLO/migration/operation rule;
4. update this map, Decision Register, and task matrix;
5. update technology/compatibility docs when versions change;
6. update executable enforcement/tests/evidence and affected fitness/security rows;
7. deliver/review full change through PR-first workflow.
