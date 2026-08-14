# AGENTS.md

## Purpose

This file defines mandatory operating rules for AI coding agents working in this repository.

The repository is the source of truth. Agent memory, summaries, previous reads, and prior conversation context are not substitutes for current repository files.

## 1. Mandatory source order

Before non-trivial planning, implementation, deletion, review, or reporting, inspect the current applicable versions of:

1. `AGENTS.md`;
2. `docs/engineering/current-only-documentation-policy.md`;
3. `docs/engineering/repository-change-workflow.md`;
4. `docs/architecture/README.md`;
5. `docs/architecture/SOURCES.md`;
6. `docs/architecture/TASK-REVIEW-MATRIX.md` when targeted routing is appropriate;
7. `docs/adr/decision-register.md`;
8. applicable current-state architecture/service documents;
9. applicable retained current ADRs;
10. `docs/technology/technology-baseline.md`, `local-development-baseline.md`, and `production-compatibility-matrix.md` when versions/compatibility matter;
11. `docs/architecture/performance-and-bottlenecks.md` for performance/capacity/scaling changes;
12. `docs/architecture/dependency-criticality.yaml` and its rendered matrix for synchronous dependency/fallback changes;
13. `docs/architecture/PRODUCTION-READINESS-CHECKLIST.md` for release evidence;
14. applicable engineering, testing, security, operations, and runbook documents.

Do not rely on remembered architecture when the current file can be inspected.

## 2. Current-only decision policy

The active repository-owner directive is `docs/engineering/current-only-documentation-policy.md`.

Until the owner explicitly withdraws it:

- the documentation set keeps current effective architecture rather than preserving obsolete decision history;
- `docs/adr/decision-register.md` indexes only retained ADRs that still contain effective scope;
- fully superseded ADRs/raw historical source material are removed after confirming no current invariant, contract, security requirement, SLO, failure semantic, migration rule, or operational requirement would be lost;
- partially stale ADRs are normalized so obsolete alternatives/supersession narrative do not masquerade as current architecture;
- current-state architecture documents are updated whenever the effective design changes;
- deleted historical records MUST NOT be cited by new code, docs, tests, or runbooks.

When two current sources conflict, do not reconstruct a deleted historical chain or silently guess. Inspect the Decision Register/current sources and correct the stale document in the same PR before implementation depends on it.

## 3. Architecture review mode

Every non-trivial task uses `full-read` or `targeted` review.

Use `full-read` when the task creates/changes a service or bounded-context boundary, security architecture, infrastructure architecture, service-to-service communication, persistence/consistency, platform technology, or when context loss/uncertainty makes targeted scope unsafe.

A targeted review is allowed only when scope is narrow and `TASK-REVIEW-MATRIX.md` plus the current Decision Register identify the complete applicable set with confidence.

Every non-trivial review also inspects existing implementation/contracts/tests and the current Git/PR diff where available.

## 4. Core architecture rules

Every microservice represents a real business capability/bounded context.

Backend architecture is **DDD + Hexagonal Architecture**. Clean Architecture is used only to enforce inward dependency direction.

```text
Infrastructure -> Application -> Domain
Interfaces     -> Application -> Domain
```

Business logic belongs in Domain/Application. Domain MUST NOT depend on Spring, JPA/Hibernate, jOOQ, Kafka, Redis, SQLite, gRPC, Protobuf, PostgreSQL, Kubernetes, Istio, or concrete adapters.

Each independently deployable service with **mutable relational business persistence** owns its database, credentials, Flyway history, contracts, build, deployment, and release lifecycle. Physical PostgreSQL placement is production-profile specific: `production-single-server` may use one shared physical CloudNativePG/PostgreSQL cluster only while preserving distinct service databases/roles/Flyway histories and strict cross-service privilege denial; `production-ha` uses dedicated CloudNativePG clusters under the current database decisions. Direct cross-service database access, cross-database joins/foreign keys, and shared business/domain/persistence models are prohibited in both profiles.

A current ADR may define a narrower immutable reference-data artifact that is not mutable service business persistence. ADR-0040 is the current example: Compromised Password Service may use only its service-local immutable, read-only, rebuildable SQLite reference dataset. That exception has no runtime SQLite writes/Flyway/CloudNativePG requirement, cannot store subject/business state, and MUST NOT be generalized to mutable SQLite persistence or another service without a new current architecture decision.

Tenant isolation uses trusted authenticated context plus persistence defense in depth. Production tenant-owned PostgreSQL tables use forced RLS and non-owner `NOSUPERUSER NOBYPASSRLS` runtime roles. Tenant database context comes only from validated authenticated context and uses the canonical parameterized transaction-local mechanism; session-scoped tenant state on pooled connections is prohibited and missing/malformed context fails closed.

## 5. Java/package/DI rules

`docs/engineering/coding-standards.md` is the canonical implementation coding standard.

Key mandatory rules include:

- feature-first + nature-separated packages;
- package segments match `[a-z][a-z0-9]*`;
- business dumping grounds such as `common`, `util`, `helper`, `manager`, `misc`, `generic` are prohibited;
- Domain and JPA/generated/query/provider/transport models are separate;
- one meaningful public top-level type per file by default;
- Spring IoC is the only DI container;
- required dependencies use constructor injection;
- field injection, circular dependencies, `@Lazy` cycle hiding, service locator, `ApplicationContext`/`BeanFactory` lookup inside Domain/Application, and direct adapter construction from use cases are prohibited;
- singleton beans are stateless or explicitly thread-safe.

When package/module/layering rules change, update ArchUnit/architecture tests in the same task.

## 6. Persistence and transaction rules

Review aggregate/transaction boundaries, JPA/jOOQ/Flyway/Hikari behavior, locking, query bounds/plans, backups/PITR, and rollback compatibility.

Mandatory rules for mutable service relational persistence:

- Flyway is the only schema-change mechanism; executed/released migrations are immutable;
- evolution follows expand -> migrate -> contract;
- OSIV is prohibited;
- N+1, broad EAGER loading, `SELECT *`, and unbounded production queries are prohibited;
- transaction boundaries are short/explicit;
- remote HTTP/gRPC/Kafka/Redis/provider I/O is prohibited inside DB transactions;
- DB locks are never held across remote I/O;
- retries execute outside failed transactions;
- persistence models follow aggregate/query needs; one-table/one-model mapping is not mandatory;
- sensitive/expensive queries require index and representative-plan evidence.

ADR-0040's SQLite reference artifact is built offline as a complete immutable version and has no runtime schema migration or write transaction. Its fixed read query, path/configuration, integrity, bounds, native dependency, recovery, and no-write/DDL/ATTACH/extension rules remain mandatory and do not weaken the mutable-persistence rules above.

## 7. Synchronous dependencies

For every new/changed remote synchronous edge define:

- source/destination workload identities;
- operation-level criticality/failure action in `dependency-criticality.yaml`;
- finite parent/child deadlines;
- cancellation behavior;
- retry owner and safe/idempotent retry conditions;
- concurrency/bulkhead/queue bounds;
- breaker/fallback behavior;
- positive/negative authorization tests;
- observability and contract tests.

Retries are finite and owned by one layer only. Layered application + mesh/client retry for the same failure is prohibited.

Authorization is especially strict: one authoritative online `CheckPermission`, one attempt, 300ms maximum caller deadline, no permission-result cache/Kafka invalidation/stale fallback/retry, and fail-closed behavior.

## 8. Kafka/event rules

Kafka is asynchronous integration transport, not ordinary request/reply and not business source of truth.

A state change that must publish an integration event as one business effect uses Transactional Outbox. Consumers assume at-least-once delivery and are idempotent; Inbox/dedup state is committed atomically with business effect where required.

Review ordering, Protobuf compatibility, retry/DLQ/replay, retention/recovery evidence, ownership, observability, and tests. Kafka/event payloads MUST NOT carry secrets or unapproved PII.

The selected production profile controls Kafka topology. `production-single-server` is an explicit RF=1/non-HA exception under ADR-0042; it does not weaken Outbox/Inbox/idempotency/replay/ACL/TLS requirements or make Kafka business authority.

## 9. Security rules

Security work reviews authentication, tenancy, Authorization, MFA/sessions, workload identity, mTLS, NetworkPolicy, WAF/upstream DDoS, secrets, semantic quotas, supply chain, privileged access, and logging/PII.

Mandatory principles:

- safe local checks may reject invalid requests but never grant authority reserved for an authoritative service/domain decision;
- external identities bind by stable issuer + subject, not email-only auto-linking;
- raw passwords/OTP/recovery codes/tokens/cookies/API keys/private keys/secrets/provider credentials are never logged or durably exposed;
- production secrets never enter Git, images, Helm/Kustomize values, logs, traces, metrics, or CI output;
- production workloads use dedicated ServiceAccounts, deny-by-default NetworkPolicy, Istio Ambient strict mTLS, and least-privilege authorization;
- Kubernetes `default` ServiceAccount is prohibited for production application workloads;
- browser/BFF security follows current OIDC PKCE/session/CSRF/CORS requirements;
- supply-chain admission verifies immutable signed/provenanced artifacts/SBOM requirements;
- admission-policy authoring is restricted to controlled GitOps/CI identities; Kyverno external HTTP context is disabled unless explicitly reviewed, and any approved external context uses bounded destination/egress/failure semantics with SSRF-negative verification;
- vulnerability scanning/advisory correlation is continuous; no scanner/feed is proof of zero unknown vulnerabilities;
- privileged human production access is JIT/short-lived/phishing-resistant/audited under ADR-0030 and the selected production profile;
- `production-single-server` MUST NOT replace real system/privilege audit with `.bashrc`/shell-history logging;
- OpenBao remains the production secret authority under ADR-0011/Technology Baseline unless a separate current security decision explicitly changes it;
- end-user MFA semantics are not weakened by infrastructure profile selection.

## 10. Logging and PII

Logging is allow-list based and structured. Do not log raw sensitive credentials, full request/response bodies, SQL binds, complete gRPC metadata, Kafka headers, unreviewed provider payloads, or unreviewed exception/cause text.

Ordinary PII appears only for an approved purpose with masking/tokenization or managed-key HMAC pseudonymization when correlation is required. Protect input-derived log fields against CR/LF/log injection. Metric labels remain low-cardinality and contain no user/tenant/session/request/resource IDs, trace IDs, raw URLs, or free-form errors.

Ordinary non-audit telemetry may use bounded buffering/drop according to its registered `OBSERVABILITY` semantics. Required security/audit evidence classified as authoritative state must be durably persisted/outboxed according to its operation contract and MUST NOT be silently dropped or reclassified as ordinary telemetry.

New/materially changed logging requires source tests/review plus pipeline/runtime leak controls where applicable.

## 11. Kubernetes, container, Helm, and GitOps rules

Production application workloads require:

- immutable image digest; no `latest`;
- source/provenance identity tied to the reviewed Git commit;
- non-root execution;
- `allowPrivilegeEscalation=false`;
- Linux capabilities dropped by default;
- `seccompProfile: RuntimeDefault`;
- read-only root filesystem where compatible;
- finite CPU/memory resources;
- distinct startup/readiness/liveness probes;
- liveness MUST NOT fail merely because a dependency is temporarily unavailable;
- graceful shutdown;
- dedicated ServiceAccount;
- deny-by-default NetworkPolicy;
- least-privilege mesh authorization.

Privileged containers, host networking, `hostPath`, added capabilities, or relaxed security context require an explicit current security decision.

Shared deployment standards belong in reviewed organization Helm application/library charts rather than copied full charts. Secret values never enter values files. Complex migration hooks require explicit ownership, idempotency, timeout/retry, failure, rollback/fail-forward, and test evidence.

Staging and production promote the exact same signed immutable artifact digest. Production rebuild after staging validation is prohibited.

The selected production profile controls replica/HPA/PDB and platform topology. `production-single-server` uses the explicit one-replica/non-HA rules in ADR-0042 and MUST NOT claim node failover; it still preserves all security-context, ServiceAccount, NetworkPolicy, admission, backup, and workload-identity controls.

## 12. Testing and executable enforcement

Architecture compliance does not rely on documentation/agent memory.

Use applicable automated enforcement including:

- Spotless;
- SpotBugs;
- ArchUnit;
- repository Semgrep/static rules;
- Gradle dependency verification/locks;
- unit/integration/security/authorization/migration tests;
- Buf lint/breaking and OpenAPI compatibility;
- schema/contract compatibility;
- container/Kubernetes/Helm policy validation;
- secret/render scans;
- `istioctl analyze` and mesh authorization tests;
- signed SBOM/advisory correlation and admission tests;
- restore/DR/failover evidence gates;
- logging/PII canary tests;
- load/chaos/smoke/critical browser tests where applicable.

Privileged GitHub Actions event contexts such as `pull_request_target` or `workflow_run` MUST NOT execute unreviewed PR-controlled code/config while secrets, write tokens, protected environments, or equivalent privilege are available. Trusted follow-up workflows that consume untrusted build artifacts/metadata must verify repository/event/source SHA, producer workflow, and artifact identity/integrity before granting privilege.

Do not disable tests, weaken a gate, broaden suppressions, or use `ignoreFailures` merely to make CI green.

Never claim compilation/test/security/production-readiness success without actual evidence. Documentation alone never proves source/runtime compliance.

## 13. Performance and reliability

Before changing scaling/retries/caches/brokers/proxies/pools/concurrency, review `performance-and-bottlenecks.md`, SLOs, dependency budgets, connection/resource budgets, and existing load evidence.

Virtual Threads do not create database connections/provider quota/CPU/memory. Constrained dependencies use bounded concurrency/queues. CPU-heavy work uses bounded workers. `Thread.sleep` is prohibited for coordination/polling/test synchronization. A blanket `synchronized` ban is also prohibited on Java 25; use evidence for contention/pinning issues.

Error budgets and burn-rate policy are release/operations controls. Do not hide SLO burn by simply increasing timeouts/retries.

For `production-single-server`, a `2 vCPU / 3-4 GiB RAM` full-stack host is not an approved capacity claim. Production approval requires the ADR-0042 complete-stack benchmark, >=30% validated resource headroom, and the current critical-path load/recovery/security evidence. Insufficient capacity is solved by increasing host resources or moving to `production-ha`, not by weakening OpenBao, Kyverno, Ambient security, backup/PITR, MFA, or fail-closed dependencies.

## 14. Change discipline and PR-first workflow

All normal repository changes follow `docs/engineering/repository-change-workflow.md`:

1. branch from current `main`;
2. open a Draft PR as early as GitHub permits;
3. make all substantive task changes on the PR branch;
4. review complete diff against latest `main` and account for base movement;
5. run/record applicable checks;
6. resolve material findings;
7. mark ready only when scope/review is complete;
8. merge only after required review/verification;
9. verify resulting `main` merge/head SHA and final state.

Direct normal writes to `main` are prohibited.

Before changing code, identify bounded context/use-case owner, contracts, transaction boundaries, sync/async interactions, deadlines, retry/idempotency/cancellation/concurrency, authentication/authorization/workload identity, secrets/PII/logging, migrations, observability, deployment impact, rollback, and Definition of Done.

Prefer the smallest correct coherent change. Do not absorb unrelated refactoring.

## 15. Mandatory code-generation preflight

Before generating/modifying implementation code, explicitly review:

1. bounded context/capability/use-case owner;
2. inbound/outbound ports before adapters;
3. Domain/Application framework independence;
4. transport/persistence/messaging/cache/provider placement only in Interfaces/Infrastructure;
5. sync vs event-driven interaction semantics;
6. transactional outbox need;
7. timeout/deadline/retry/idempotency/cancellation/concurrency/breaker/transaction boundaries;
8. migration/tests/metrics/logs/traces/dashboards/alerts/runbook evidence;
9. ArchUnit/architecture tests for boundary changes;
10. dependency/plugin/tool need, owner, compatibility, integrity, security/license review;
11. Dockerfile/Helm/GitOps/probes/resources/HPA/PDB/ServiceAccount/NetworkPolicy/securityContext/shutdown alignment;
12. public route/Gateway/Traefik/WAF/upstream-DDoS/security-header/CORS/CSRF impact;
13. critical BDD scenario impact;
14. critical Playwright journey impact;
15. logging/error PII/secret/CRLF/cardinality safety;
16. constructor injection/ports with no runtime service lookup;
17. workload identity/Ambient/NetworkPolicy/Istio policy impact;
18. operation-level dependency registry and positive/negative policy tests;
19. immutable same-digest staging-to-production promotion tied to Git commit;
20. Kubernetes security-context and migration-workflow compliance.

`Not applicable` is valid only when genuinely inapplicable.

## 16. Repository state and sub-agent rules

Inspect the current Git/PR diff before modification. Distinguish user/task changes from unrelated existing work. Do not overwrite/revert/reformat unrelated changes without justification.

Search the repository for established current patterns before inventing a new one, but do not copy a pattern that violates current architecture.

The parent agent remains responsible for delegated work. Sub-agents receive canonical paths, retained current ADRs, search terms/sections, exact task boundary, and applicable Definition of Done; they independently verify current repository files.

## 17. Communication and verification

`docs/engineering/agent-communication-and-reporting.md` is the detailed evidence/reporting contract.

Lead with result, blocker, material risk, or important finding. Do not narrate routine tool operations or private reasoning. Preserve exact paths, symbols, commands/errors, versions/scopes/numeric constraints.

Separate verified facts from assumptions/inference/recommendations. Never claim completion, correctness, security, production readiness, build/test success, or architecture compliance without evidence.

Report every required check as passed, failed, not run, or unavailable. A task is not `completed` when missing verification materially prevents confidence in the requested result.

## 18. Final implementation report

Every completed non-trivial implementation task reports:

```text
Outcome:
completed | partial | blocked | failed
<one- or two-sentence result>

Changes:
- <path or component> — <change>

Verification:
- Passed: <checks actually executed>
- Failed: <executed check and result>
- Not run: <check and reason>

Risks and limitations:
- <items or "None identified within reviewed scope">

Remaining work:
- <required next action or "None">

Architecture report:
Architecture review mode: full-read/targeted
Architecture document version/commit:
Architecture sections reviewed:
Search terms used:
ADRs reviewed or changed:
Changed bounded context/module:
Contracts changed:
Database migration:
Transaction boundary:
Timeout/deadline behavior:
Retry/cancellation/concurrency behavior:
Kafka/event and idempotency behavior:
Security impact:
Istio identity and authorization impact:
Logging and PII impact:
Observability added or changed:
Build/CI/architecture enforcement changed:
Tests executed:
Architecture deviations:
Rollback considerations:
```

Do not leave fields blank; use `None`, `Not applicable`, or `Not verified` when accurate.

## 19. Canonical source groups

### Architecture/index

- `docs/architecture/README.md`
- `docs/architecture/SOURCES.md`
- `docs/architecture/TASK-REVIEW-MATRIX.md`
- `docs/architecture/PRODUCTION-READINESS-CHECKLIST.md`
- `docs/architecture/PRODUCTION-DECISION-SUMMARY.md`
- `docs/architecture/PRODUCTION-ARCHITECTURE-REVIEW.md`
- `docs/architecture/performance-and-bottlenecks.md`
- `docs/architecture/dependency-criticality.yaml`
- `docs/architecture/dependency-criticality.schema.json`
- `docs/architecture/dependency-criticality-matrix.md`

### Current-state architecture

- `docs/architecture/platform-architecture.md`
- `docs/architecture/backend-engineering.md`
- `docs/architecture/security-architecture.md`
- `docs/architecture/data-and-messaging.md`
- `docs/architecture/reliability-and-observability.md`
- `docs/architecture/runtime-and-deployment.md`
- `docs/architecture/testing-and-quality-gates.md`
- `docs/architecture/services/*`

### Decisions

- `docs/adr/decision-register.md`
- retained ADRs listed by that register

### Technology

- `docs/technology/technology-baseline.md`
- `docs/technology/local-development-baseline.md`
- `docs/technology/production-compatibility-matrix.md`

### Engineering/operations

- `docs/engineering/current-only-documentation-policy.md`
- `docs/engineering/repository-change-workflow.md`
- `docs/engineering/developer-workflow.md`
- `docs/engineering/coding-standards.md`
- `docs/engineering/build-and-ci-quality-enforcement.md`
- `docs/engineering/agent-communication-and-reporting.md`
- `docs/operations/incident-response-runbook.md`
- `docs/operations/chaos-engineering-program.md`
- `docs/runbooks/local-istio-ambient.md`
- `docs/runbooks/local-traefik-edge.md`

## 20. Source maintenance

When effective architecture changes:

1. update current-state architecture;
2. create/update the retained current ADR when useful;
3. remove/normalize obsolete decision/source text only after preserving all still-current semantics;
4. update Decision Register/SOURCES/TASK-REVIEW-MATRIX as applicable;
5. update Technology Baseline/compatibility documents when a version decision changes;
6. update executable architecture/security/quality enforcement and tests;
7. update production-readiness/operations evidence where applicable;
8. deliver through the PR-first workflow and verify the final merged state.
