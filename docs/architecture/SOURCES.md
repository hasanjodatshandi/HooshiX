# Architecture Sources — Current State

- **Mode:** current-only
- **Policy:** `../engineering/current-only-documentation-policy.md`
- **Decision index:** `../adr/decision-register.md`

This map points only to current authoritative documentation. Deleted predecessor ADRs/raw historical notes are intentionally excluded.

## Global entry points

1. `../../AGENTS.md`
2. `../engineering/current-only-documentation-policy.md`
3. `../engineering/repository-change-workflow.md`
4. `../engineering/documentation-standards.md`
5. `README.md`
6. `TASK-REVIEW-MATRIX.md`
7. `../adr/decision-register.md`
8. applicable current-state architecture/service documents
9. applicable retained current ADRs
10. technology baselines/compatibility docs when exact versions matter
11. applicable engineering/operations/runbooks

## Current architecture map

### Platform topology, Java architecture, code quality

- `platform-architecture.md`
- `backend-engineering.md`
- `architecture-fitness-functions.md`
- `../engineering/coding-standards.md`
- `../engineering/sql-and-flyway-coding-standards.md`
- `../engineering/frontend-coding-standards.md`
- `../engineering/build-and-ci-quality-enforcement.md`
- ADR-0039

Current implementation rules include DDD + Hexagonal inward dependency direction, independent service builds, feature-first/nature-separated packages, strict package naming, Domain/JPA/transport separation, constructor injection, no dumping-ground packages, bounded transactions/dependencies/concurrency, hardened runtime manifests, SQL/Flyway evolution/query discipline, strict TypeScript/React boundaries, and executable architecture/security/quality gates.

### Identity, tenancy, sessions, external identity, MFA, erasure

- `security-architecture.md`
- `security-verification-matrix.md`
- `services/identity-service.md`
- `services/web-bff.md`
- ADR-0008, ADR-0009, ADR-0012, ADR-0023, ADR-0028

Current model includes global users with tenant memberships, trusted active-tenant context, current tenant lifecycle, forced production tenant RLS with pool-safe transaction-local tenant database context, provider-neutral password/TOTP controls, issuer+subject external identity binding, rotating session/refresh semantics, exact-audience JWT signing/verifier lifecycle, BFF-only server-owned audience brokerage, and coordinated erasure evidence.

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
- ADR-0004, ADR-0005, ADR-0012, ADR-0025, ADR-0033, ADR-0035, ADR-0038, ADR-0040

Current v1 model is self-contained: Identity computes SHA-256 and sends only the 20-bit/five-uppercase-hex prefix; Compromised Password performs a bounded exact range lookup from a service-local immutable read-only embedded SQLite dataset and returns suffix/count candidates; Identity keeps the full digest and final compromised/not-compromised decision. The SQLite artifact is rebuildable reference data, not mutable business persistence. There is no runtime HIBP/external compromised-password provider, PostgreSQL, Redis, Kafka, full-dataset JVM cache, or subject-linked application state. Only Identity workload may call the service; dataset corruption/incompatibility/unavailability fails closed.

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
- ADR-0012, ADR-0016, ADR-0023, ADR-0024, ADR-0025, ADR-0033

Current Web BFF model uses the `/api/v1` browser namespace with bounded RFC-9457 errors/requests, exact OIDC state/nonce/PKCE/pre-auth and safe-return rules, trusted Identity evidence, server-owned exact-audience Identity token brokerage, HMAC-located Redis sessions/pre-auth state, 7d idle/30d absolute session limits with five-minute last-seen write coalescing, atomic no-grace session rotation, User->sessions revocation index, AES-256-GCM retained-refresh protection with 90d key rotation/one-hour stale-snapshot fail close, exact synchronizer CSRF + mandatory Fetch Metadata, same-origin-only v1 CORS, exact CSP/no-store behavior, BFF-owned OIDC semantic quotas, erasure participation, and deny-by-default workload/egress policy. Browser never receives provider/Identity/downstream credentials and final protected-resource authorization remains in the resource-owning service.

### Authorization

- `security-architecture.md`
- `security-verification-matrix.md`
- `services/authorization-service.md`
- `dependency-criticality.yaml`
- `dependency-criticality-matrix.md`
- `performance-and-bottlenecks.md`
- ADR-0013, ADR-0024, ADR-0025, ADR-0026, ADR-0032, ADR-0033, ADR-0036

Current model uses an exact Git-owned permission catalog with TENANT/PLATFORM scope and lifecycle/non-reuse rules; immutable SYSTEM Roles plus bounded/versioned custom Roles/direct Membership overrides; one authoritative online success-is-ALLOW `CheckPermission` with no permission-result cache/Kafka invalidation/retry/stale fallback; BFF-facaded but locally authorized tenant management with privilege-escalation prevention, bounded bulk limits and semantic-mutation quotas; atomic owner safety shared by local owner-role mutation and Identity Membership-removal reservations; separate Identity-only fail-closed `CheckPlatformPermission` with no tenant/resource bypass; UUIDv4/HMAC idempotency, durable PII-safe audit, jOOQ/JDBC + forced RLS persistence, and erased-subject tenant/platform authority removal. Production SLO/capacity/deployment is ADR-0026; burn/recovery/dependency governance is ADR-0032/0033/0036.

### Semantic security quotas

- `security-architecture.md`
- `security-verification-matrix.md`
- `services/identity-service.md`, `services/web-bff.md`, `services/authorization-service.md` as applicable
- ADR-0024

ADR-0024 is consolidated current decision for quota ownership, Redis topology, atomic multi-dimension policy, pseudonymization, anti-lockout behavior, exact Identity registration values, exact Web BFF OIDC start/callback values, Authorization semantic-mutation cost, dual trusted time, no security-significant TTL reset, failure semantics, SLO/capacity, and verification.

### Notification

- `services/notification-service.md`
- `services/identity-service.md`
- `security-architecture.md`
- `data-and-messaging.md`
- `reliability-and-observability.md`
- ADR-0006, ADR-0007, ADR-0010, ADR-0014, ADR-0018, ADR-0020

Current runtime:

- idempotent durable internal gRPC handoff;
- exact versioned templates/content fixed at acceptance;
- purpose-specific local AES-256-GCM key rings via OpenBao/External Secrets, without routine OpenBao hot-path RPC;
- PostgreSQL-authoritative time + durable `DISPATCHING` commit; no bespoke clock/fence control plane;
- Liara Transactional Email;
- IPPanel Webservice-mode Iran SMS;
- local logging SMS local-only, never production fallback;
- authenticated/correlated delivery evidence, bounded retries/reconciliation, non-PII result callback.

### PostgreSQL, persistence, SQL, migrations, recovery

- `data-and-messaging.md`
- `runtime-and-deployment.md`
- applicable `services/*`
- `performance-and-bottlenecks.md`
- `../engineering/sql-and-flyway-coding-standards.md`
- ADR-0019, ADR-0027, ADR-0034, ADR-0037

ADR-0027 is consolidated current service-isolation decision for mutable relational business persistence: every such production service owns its database, credentials/roles, Flyway history, dedicated CloudNativePG cluster, backup identity, capacity budget, no-cross-service-SQL/model boundary, forced tenant RLS where applicable, and parameterized transaction-local tenant context that cannot leak through pooled connections. ADR-0019/0034/0037 provide HA/backup/fleet/restore/upgrade mechanics. SQL/Flyway standards define naming, bounded/indexed query behavior, plan evidence, migration/backfill discipline, and JPA-vs-jOOQ/JDBC selection guidance without changing ownership. ADR-0040 is the explicit immutable rebuildable SQLite reference-dataset exception and does not authorize mutable SQLite business persistence.

### Kafka, events, contracts

- `data-and-messaging.md`
- ADR-0003, ADR-0015

Transactional Outbox, consumer idempotency/Inbox, bounded retry/DLQ/replay, Protobuf + Buf compatibility, KRaft durability, and rebuildable Kafka DR are current. Kafka is not ordinary request/reply or business source of truth.

### SLOs, reliability, performance, DR

- `reliability-and-observability.md`
- `performance-and-bottlenecks.md`
- `architecture-fitness-functions.md`
- `PRODUCTION-READINESS-CHECKLIST.md`
- `../operations/chaos-engineering-program.md`
- `../operations/incident-response-runbook.md`
- ADR-0004, ADR-0005, ADR-0015, ADR-0019, ADR-0025, ADR-0026, ADR-0032, ADR-0033, ADR-0036, ADR-0037, ADR-0040

### Kubernetes, GitOps, edge, mesh, secrets

- `runtime-and-deployment.md`
- `security-architecture.md`
- `security-verification-matrix.md`
- `../runbooks/local-istio-ambient.md`
- `../runbooks/local-traefik-edge.md`
- `../technology/technology-baseline.md`
- `../technology/local-development-baseline.md`
- `../technology/production-compatibility-matrix.md`
- ADR-0001, ADR-0002, ADR-0011, ADR-0021, ADR-0022, ADR-0029, ADR-0040

Public path: upstream L3/L4 volumetric mitigation/scrubbing -> redundant external L4 load balancing -> Traefik -> Caddy/Coraza WAF -> Web BFF. Internal workloads use dedicated ServiceAccounts, hardened pod security contexts, deny-by-default NetworkPolicy, Istio Ambient strict mTLS, and least-privilege authorization. Web BFF egress is additionally restricted to its explicitly registered downstream/provider/telemetry set. Compromised Password has no public ingress and no runtime provider/Internet lookup egress; only Identity may call its gRPC lookup.

### Frontend and BFF implementation

- `services/web-bff.md`
- `security-architecture.md`
- `../engineering/frontend-coding-standards.md`
- `testing-and-quality-gates.md`
- ADR-0016

Frontend rules cover strict TypeScript, runtime validation of untrusted data, React purity/effect discipline, feature import boundaries, same-origin BFF-only API access, browser token isolation, accessibility/RTL contracts, service-worker/private-cache restrictions, resilient Playwright practices, and route bundle/performance budgets. BFF implementation must additionally satisfy the exact browser/session/token-broker/egress contracts above rather than inventing a second frontend-facing security model.

### Supply chain, vulnerabilities, human access, logging

- `security-architecture.md`
- `security-verification-matrix.md`
- `testing-and-quality-gates.md`
- `architecture-fitness-functions.md`
- `../engineering/build-and-ci-quality-enforcement.md`
- `../operations/incident-response-runbook.md`
- ADR-0017, ADR-0030, ADR-0031, ADR-0035, ADR-0038, ADR-0040

Current supply-chain controls include immutable signed artifacts, signed provenance/SBOM, least-privilege admission-policy authoring, bounded policy-engine external context/egress with SSRF negatives, and continuous deployed-digest vulnerability response. Compromised Password final-image evidence includes both the Xerial Java artifact and bundled native SQLite engine.

## Technology/version authority

- `../technology/technology-baseline.md` — approved exact production/application pins;
- `../technology/local-development-baseline.md` — local tool/cluster pins;
- `../technology/production-compatibility-matrix.md` — supported production combinations;
- repository wrappers, dependency locks, image digests, chart locks, GitOps metadata — exact deployed artifact identity.

Architecture prose uses product families/major-minor lines unless an exact patch is itself an architecture constraint.

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
