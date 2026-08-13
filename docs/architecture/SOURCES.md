# Architecture Sources — Current State

- **Mode:** current-only
- **Documentation policy:** `../engineering/current-only-documentation-policy.md`
- **Decision index:** `../adr/decision-register.md`

This file maps implementation concerns to the current authoritative documentation. Superseded decision history and raw source notes are intentionally excluded.

## Global entry points

1. `../../AGENTS.md`
2. `../engineering/current-only-documentation-policy.md`
3. `../engineering/repository-change-workflow.md`
4. `README.md`
5. `TASK-REVIEW-MATRIX.md`
6. `../adr/decision-register.md`
7. applicable current-state architecture/service docs
8. applicable retained current ADRs
9. technology baselines/compatibility documents when exact versions matter
10. engineering/operations/runbooks required by the task

## Current architecture map

### Platform topology and boundaries

- `platform-architecture.md`
- `backend-engineering.md`
- ADR-0001, ADR-0002, ADR-0004, ADR-0007
- `../engineering/coding-standards.md`
- `../engineering/build-and-ci-quality-enforcement.md`

### Identity, authentication, tenancy, MFA, sessions

- `security-architecture.md`
- `services/identity-service.md`
- `services/web-bff.md`
- ADR-0002, ADR-0003, ADR-0006, ADR-0008, ADR-0009
- ADR-0034, ADR-0035, ADR-0038, ADR-0045, ADR-0052, ADR-0058

### Authorization

- `security-architecture.md`
- `services/authorization-service.md`
- `dependency-criticality.yaml`
- `dependency-criticality-matrix.md`
- `performance-and-bottlenecks.md`
- ADR-0004
- ADR-0039
- ADR-0055, ADR-0056, ADR-0062, ADR-0063, ADR-0066

Current online rule: one authoritative `CheckPermission`; safe local reject-only prechecks; no permission-result cache, Kafka invalidation, retry, stale allow fallback, or layered retry. Production SLO/capacity/deployment baseline is ADR-0056; current breaker alert/recovery governance is ADR-0062/ADR-0066.

### Semantic security quotas

- `security-architecture.md`
- ADR-0041
- ADR-0054

Service-owned Redis quota enforcement remains atomic and fail-safe. Time/skew/TTL semantics use the hardened current model in ADR-0054.

### Notification

- `services/notification-service.md`
- `security-architecture.md`
- `data-and-messaging.md`
- `reliability-and-observability.md`
- ADR-0010, ADR-0012 through ADR-0017
- ADR-0029, ADR-0030, ADR-0036, ADR-0043, ADR-0047, ADR-0049

Current high-level runtime:

- Identity submits idempotently over internal gRPC;
- Notification owns templates, rendering, Email/SMS providers, retries, reconciliation, and delivery evidence;
- sensitive handoff/delivery state uses bounded local AES-256-GCM key rings sourced through OpenBao/External Secrets, without routine OpenBao hot-path RPC;
- PostgreSQL authoritative time + durable `DISPATCHING` commit replaces bespoke clock/fence control-plane components;
- production Email = Liara Transactional Email;
- production Iran SMS = IPPanel Webservice mode;
- local logging SMS adapter is local-only and never a production fallback.

### PostgreSQL, persistence, migrations, data ownership

- `data-and-messaging.md`
- `runtime-and-deployment.md`
- `services/*` applicable service doc
- `performance-and-bottlenecks.md`
- ADR-0048, ADR-0053, ADR-0057, ADR-0064, ADR-0067

Every persistent production microservice owns its database, credentials, Flyway history, and dedicated CloudNativePG cluster. Tenant-owned production tables use forced RLS; runtime roles do not own tables and cannot bypass RLS.

### Kafka/events/contracts

- `data-and-messaging.md`
- `dependency-criticality.yaml` for synchronous edges; Kafka is not ordinary request/reply
- ADR-0026
- ADR-0044

Transactional Outbox, consumer idempotency/Inbox, bounded retry/DLQ, replay procedures, RF/minISR/acks/idempotent-producer durability, and rebuildable Kafka DR are current requirements.

### Reliability, performance, SLOs, DR

- `reliability-and-observability.md`
- `performance-and-bottlenecks.md`
- `PRODUCTION-READINESS-CHECKLIST.md`
- `../operations/chaos-engineering-program.md`
- `../operations/incident-response-runbook.md`
- ADR-0027, ADR-0028, ADR-0044, ADR-0048
- ADR-0055, ADR-0056, ADR-0062, ADR-0063, ADR-0066, ADR-0067

### Kubernetes, GitOps, mesh, edge, secrets

- `runtime-and-deployment.md`
- `security-architecture.md`
- `../runbooks/local-istio-ambient.md`
- `../runbooks/local-traefik-edge.md`
- `../technology/technology-baseline.md`
- `../technology/local-development-baseline.md`
- `../technology/production-compatibility-matrix.md`
- ADR-0024, ADR-0025, ADR-0037, ADR-0050, ADR-0051, ADR-0059

Public path remains upstream L3/L4 DDoS control -> Traefik -> Caddy/Coraza -> Web BFF. Internal workloads use dedicated ServiceAccounts, deny-by-default NetworkPolicy, Istio Ambient strict mTLS, and least-privilege authorization.

### Supply chain, vulnerabilities, human access, logging

- `security-architecture.md`
- `testing-and-quality-gates.md`
- `../engineering/build-and-ci-quality-enforcement.md`
- `../operations/incident-response-runbook.md`
- ADR-0046, ADR-0060, ADR-0061, ADR-0065, ADR-0068

### Java coding and repository enforcement

- `backend-engineering.md`
- `../engineering/coding-standards.md`
- `../engineering/build-and-ci-quality-enforcement.md`
- `../engineering/developer-workflow.md`
- `../engineering/repository-change-workflow.md`
- ADR-0007, ADR-0061, ADR-0069

Key enforceable rules include feature-first/nature-separated packages, strict package segment naming, Domain/JPA separation, constructor injection, no dumping-ground packages, no remote I/O inside DB transactions, bounded deadlines/retries/concurrency, immutable same-digest promotion, hardened Kubernetes security contexts, and machine-enforced architecture/logging/security gates.

## Technology/version authority

Use:

- `../technology/technology-baseline.md` — approved production/application pins;
- `../technology/local-development-baseline.md` — local workstation/container/kind pins;
- `../technology/production-compatibility-matrix.md` — supported production combinations;
- repository wrappers, dependency locks, image digests, chart locks, and GitOps metadata — exact deployed artifact identity.

Current-state architecture should refer to product families/major-minor lines unless an exact patch is architecturally significant.

## Maintenance rule

When architecture changes:

1. update the applicable current-state document;
2. update or create the retained current ADR when a durable architectural decision record is useful;
3. remove/normalize any record that would otherwise preserve obsolete alternatives;
4. update this source map and `../adr/decision-register.md`;
5. update technology baselines when version/compatibility changes;
6. update executable enforcement/tests and production-readiness evidence where applicable;
7. deliver the complete change through the PR-first workflow.
