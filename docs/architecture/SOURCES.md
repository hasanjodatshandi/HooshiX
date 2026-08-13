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
- ADR-0069

Current implementation rules include DDD + Hexagonal inward dependency direction, independent service builds, feature-first/nature-separated packages, strict package naming, Domain/JPA/transport separation, constructor injection, no dumping-ground packages, bounded transactions/dependencies/concurrency, hardened runtime manifests, SQL/Flyway evolution/query discipline, strict TypeScript/React boundaries, and executable architecture/security/quality gates.

### Identity, tenancy, sessions, external identity, MFA, erasure

- `security-architecture.md`
- `security-verification-matrix.md`
- `services/identity-service.md`
- `services/web-bff.md`
- ADR-0034, ADR-0035, ADR-0038, ADR-0052, ADR-0058

Current model includes global users with tenant memberships, trusted active-tenant context, current tenant lifecycle, forced production tenant RLS, provider-neutral password/TOTP controls, issuer+subject external identity binding, rotating session/refresh semantics, JWT signing/verifier lifecycle, and coordinated erasure evidence.

### Authorization

- `security-architecture.md`
- `security-verification-matrix.md`
- `services/authorization-service.md`
- `dependency-criticality.yaml`
- `dependency-criticality-matrix.md`
- `performance-and-bottlenecks.md`
- ADR-0039, ADR-0055, ADR-0056, ADR-0062, ADR-0063, ADR-0066

Current rule: one authoritative online `CheckPermission`; safe local reject-only prechecks; no permission-result cache, Kafka invalidation, retry, stale allow fallback, or duplicate routine BFF enforcement. Production SLO/capacity/deployment is ADR-0056; burn/recovery/dependency governance is ADR-0062/0063/0066.

### Semantic security quotas

- `security-architecture.md`
- `security-verification-matrix.md`
- `services/identity-service.md` / `services/authorization-service.md` as applicable
- ADR-0054

ADR-0054 is the consolidated current decision for quota ownership, Redis topology, atomic multi-dimension policy, pseudonymization, anti-lockout behavior, dual trusted time, no security-significant TTL reset, failure semantics, SLO/capacity, and verification.

### Notification

- `services/notification-service.md`
- `services/identity-service.md`
- `security-architecture.md`
- `data-and-messaging.md`
- `reliability-and-observability.md`
- ADR-0029, ADR-0030, ADR-0036, ADR-0043, ADR-0047, ADR-0049

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
- ADR-0048, ADR-0057, ADR-0064, ADR-0067

ADR-0057 is the consolidated current service-isolation decision: every persistent production service owns its database, credentials/roles, Flyway history, dedicated CloudNativePG cluster, backup identity, capacity budget, no-cross-service-SQL/model boundary, and forced tenant RLS where applicable. ADR-0048/0064/0067 provide HA/backup/fleet/restore/upgrade mechanics. SQL/Flyway standards define naming, bounded/indexed query behavior, plan evidence, migration/backfill discipline, and JPA-vs-jOOQ/JDBC selection guidance without changing ownership.

### Kafka, events, contracts

- `data-and-messaging.md`
- ADR-0026, ADR-0044

Transactional Outbox, consumer idempotency/Inbox, bounded retry/DLQ/replay, Protobuf + Buf compatibility, KRaft durability, and rebuildable Kafka DR are current. Kafka is not ordinary request/reply or business source of truth.

### SLOs, reliability, performance, DR

- `reliability-and-observability.md`
- `performance-and-bottlenecks.md`
- `architecture-fitness-functions.md`
- `PRODUCTION-READINESS-CHECKLIST.md`
- `../operations/chaos-engineering-program.md`
- `../operations/incident-response-runbook.md`
- ADR-0027, ADR-0028, ADR-0044, ADR-0048, ADR-0055, ADR-0056, ADR-0062, ADR-0063, ADR-0066, ADR-0067

### Kubernetes, GitOps, edge, mesh, secrets

- `runtime-and-deployment.md`
- `security-architecture.md`
- `security-verification-matrix.md`
- `../runbooks/local-istio-ambient.md`
- `../runbooks/local-traefik-edge.md`
- `../technology/technology-baseline.md`
- `../technology/local-development-baseline.md`
- `../technology/production-compatibility-matrix.md`
- ADR-0024, ADR-0025, ADR-0037, ADR-0050, ADR-0051, ADR-0059

Public path: upstream L3/L4 DDoS mitigation -> Traefik -> Caddy/Coraza WAF -> Web BFF. Internal workloads use dedicated ServiceAccounts, hardened pod security contexts, deny-by-default NetworkPolicy, Istio Ambient strict mTLS, and least-privilege authorization.

### Frontend and BFF implementation

- `services/web-bff.md`
- `security-architecture.md`
- `../engineering/frontend-coding-standards.md`
- `testing-and-quality-gates.md`

Frontend rules cover strict TypeScript, runtime validation of untrusted data, React purity/effect discipline, feature import boundaries, same-origin BFF-only API access, browser token isolation, accessibility/RTL contracts, service-worker/private-cache restrictions, resilient Playwright practices, and route bundle/performance budgets.

### Supply chain, vulnerabilities, human access, logging

- `security-architecture.md`
- `security-verification-matrix.md`
- `testing-and-quality-gates.md`
- `architecture-fitness-functions.md`
- `../engineering/build-and-ci-quality-enforcement.md`
- `../operations/incident-response-runbook.md`
- ADR-0046, ADR-0060, ADR-0061, ADR-0065, ADR-0068

## Technology/version authority

- `../technology/technology-baseline.md` — approved exact production/application pins;
- `../technology/local-development-baseline.md` — local tool/cluster pins;
- `../technology/production-compatibility-matrix.md` — supported production combinations;
- repository wrappers, dependency locks, image digests, chart locks, GitOps metadata — exact deployed artifact identity.

Architecture prose uses product families/major-minor lines unless an exact patch is itself an architecture constraint.

## Documentation/governance authority

- `../engineering/current-only-documentation-policy.md` — retain only effective current decisions under the owner's active directive;
- `../engineering/documentation-standards.md` — normative language, document authority, single-source rule, and documentation fitness expectations;
- `architecture-fitness-functions.md` — architecture properties and required executable evidence;
- `security-verification-matrix.md` — security verification families aligned to the current stable OWASP ASVS baseline without claiming certification.

## Maintenance rule

When effective architecture changes:

1. update applicable current-state docs;
2. create/update a retained current ADR only when it still adds durable decision value;
3. remove/normalize predecessor text after preserving every still-current invariant/contract/security/SLO/migration/operation rule;
4. update this map, Decision Register, and task matrix;
5. update technology/compatibility docs when versions change;
6. update executable enforcement/tests/evidence and affected fitness/security rows;
7. deliver/review the full change through PR-first workflow.
