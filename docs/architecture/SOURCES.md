# Architecture Sources — Current State

- **Mode:** current-only
- **Policy:** `../engineering/current-only-documentation-policy.md`
- **Decision index:** `../adr/decision-register.md`

This map points only to current authoritative documentation. Deleted predecessor ADRs and raw historical notes are intentionally excluded.

## Global entry points

1. `../../AGENTS.md`
2. `../engineering/current-only-documentation-policy.md`
3. `../engineering/repository-change-workflow.md`
4. `README.md`
5. `TASK-REVIEW-MATRIX.md`
6. `../adr/decision-register.md`
7. applicable current-state architecture/service documents
8. applicable retained current ADRs
9. technology baselines/compatibility docs when exact versions matter
10. applicable engineering/operations/runbooks

## Current architecture map

### Platform topology, Java architecture, and code quality

- `platform-architecture.md`
- `backend-engineering.md`
- `../engineering/coding-standards.md`
- `../engineering/build-and-ci-quality-enforcement.md`
- ADR-0069

Current implementation rules include DDD + Hexagonal inward dependency direction, independent service builds, feature-first/nature-separated packages, strict package naming, Domain/JPA/transport separation, constructor injection, no dumping-ground packages, bounded transactions/dependencies/concurrency, hardened runtime manifests, and executable architecture/security/quality gates.

### Identity, tenancy, sessions, external identity, MFA, erasure

- `security-architecture.md`
- `services/identity-service.md`
- `services/web-bff.md`
- ADR-0034, ADR-0035, ADR-0038, ADR-0052, ADR-0058

Current model includes global users with tenant memberships, trusted active-tenant context, current tenant lifecycle, forced production tenant RLS, provider-neutral password/TOTP controls, issuer+subject external identity binding, rotating BFF/refresh session semantics, JWT signing/verifier lifecycle, and coordinated erasure evidence.

### Authorization

- `security-architecture.md`
- `services/authorization-service.md`
- `dependency-criticality.yaml`
- `dependency-criticality-matrix.md`
- `performance-and-bottlenecks.md`
- ADR-0039, ADR-0055, ADR-0056, ADR-0062, ADR-0063, ADR-0066

Current rule: one authoritative online `CheckPermission`; safe local reject-only prechecks; no permission-result cache, Kafka invalidation, retry, stale allow fallback, or duplicate routine BFF enforcement. Production SLO/capacity/deployment is ADR-0056; burn/recovery/dependency governance is ADR-0062/0063/0066.

### Semantic security quotas

- `security-architecture.md`
- ADR-0041, ADR-0054

Quotas are service-owned, atomic, fail-safe, pseudonymous, and use the current trusted-application-time + Redis-time skew/TTL model.

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
- purpose-specific local AES-256-GCM key rings sourced via OpenBao/External Secrets, without routine OpenBao hot-path RPC;
- PostgreSQL-authoritative time + durable `DISPATCHING` commit; no bespoke clock/fence control plane;
- Liara Transactional Email;
- IPPanel Webservice-mode Iran SMS;
- local logging SMS only for local development, never production fallback;
- authenticated/correlated delivery evidence, bounded retries/reconciliation, non-PII result callback.

### PostgreSQL, persistence, migrations, recovery

- `data-and-messaging.md`
- `runtime-and-deployment.md`
- applicable `services/*`
- `performance-and-bottlenecks.md`
- ADR-0048, ADR-0053, ADR-0057, ADR-0064, ADR-0067

Every persistent production microservice owns its database, credentials, Flyway history, dedicated CloudNativePG cluster, backup identity, and capacity budget. Tenant-owned tables use forced RLS. Restore evidence and compatibility-aware upgrade/rollback rules are production gates.

### Kafka, events, and contracts

- `data-and-messaging.md`
- ADR-0026, ADR-0044

Transactional Outbox, consumer idempotency/Inbox, bounded retry/DLQ/replay, Protobuf + Buf compatibility, KRaft durability, and rebuildable Kafka DR are current. Kafka is not ordinary request/reply or business source of truth.

### SLOs, reliability, performance, DR

- `reliability-and-observability.md`
- `performance-and-bottlenecks.md`
- `PRODUCTION-READINESS-CHECKLIST.md`
- `../operations/chaos-engineering-program.md`
- `../operations/incident-response-runbook.md`
- ADR-0027, ADR-0028, ADR-0044, ADR-0048, ADR-0055, ADR-0056, ADR-0062, ADR-0063, ADR-0066, ADR-0067

### Kubernetes, GitOps, edge, mesh, secrets

- `runtime-and-deployment.md`
- `security-architecture.md`
- `../runbooks/local-istio-ambient.md`
- `../runbooks/local-traefik-edge.md`
- `../technology/technology-baseline.md`
- `../technology/local-development-baseline.md`
- `../technology/production-compatibility-matrix.md`
- ADR-0024, ADR-0025, ADR-0037, ADR-0050, ADR-0051, ADR-0059

Public path: upstream L3/L4 DDoS mitigation -> Traefik -> Caddy/Coraza WAF -> Web BFF. Internal workloads use dedicated ServiceAccounts, hardened pod security contexts, deny-by-default NetworkPolicy, Istio Ambient strict mTLS, and least-privilege authorization.

### Supply chain, vulnerabilities, human access, logging

- `security-architecture.md`
- `testing-and-quality-gates.md`
- `../engineering/build-and-ci-quality-enforcement.md`
- `../operations/incident-response-runbook.md`
- ADR-0046, ADR-0060, ADR-0061, ADR-0065, ADR-0068

## Technology/version authority

- `../technology/technology-baseline.md` — approved exact production/application pins;
- `../technology/local-development-baseline.md` — local tool/cluster pins;
- `../technology/production-compatibility-matrix.md` — supported production combinations;
- repository wrappers, dependency locks, image digests, chart locks, GitOps metadata — exact deployed artifact identity.

Architecture prose uses product families/major-minor lines unless an exact patch is itself an architecture constraint.

## Maintenance rule

When effective architecture changes:

1. update applicable current-state docs;
2. create/update a retained current ADR only when it still adds durable decision value;
3. remove/normalize obsolete predecessor text after preserving every still-current invariant/contract/security/SLO/migration/operation rule;
4. update this map, Decision Register, and task matrix;
5. update technology/compatibility docs when versions change;
6. update executable enforcement/tests/evidence;
7. deliver/review the full change through PR-first workflow.
