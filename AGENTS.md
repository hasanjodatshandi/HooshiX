# AGENTS.md

## Purpose

This file defines mandatory operating rules for AI coding agents working in this repository.

It is intentionally concise. Detailed architecture, security, reliability, technology, testing, deployment, and bounded-context rules live in the canonical documents referenced under **Sources**.

Agents MUST read the applicable current repository documents before planning, modifying, reviewing, generating, deleting, or reporting non-trivial work. Agent memory, prior conversation context, cached summaries, and previous reads are not substitutes for the current files.

## 1. Source of Truth

The repository is the source of truth.

Before implementation, use the current versions of:

1. this `AGENTS.md`;
2. `docs/architecture/README.md`;
3. `docs/architecture/SOURCES.md`;
4. `docs/architecture/TASK-REVIEW-MATRIX.md` when choosing targeted scope;
5. `docs/adr/decision-register.md`;
6. applicable current-state architecture documents;
7. applicable accepted ADRs;
8. `docs/technology/technology-baseline.md`, `docs/technology/local-development-baseline.md`, and `docs/technology/production-compatibility-matrix.md` when exact production/local versions or compatibility matter;
9. `docs/architecture/performance-and-bottlenecks.md` for critical-path/capacity changes;
10. `docs/architecture/dependency-criticality.yaml` and `docs/architecture/dependency-criticality-matrix.md` for synchronous dependency/fallback changes;
11. `docs/architecture/PRODUCTION-READINESS-CHECKLIST.md` for release evidence;
12. `docs/operations/incident-response-runbook.md` and `docs/operations/chaos-engineering-program.md` for incident/recovery/chaos work;
13. `docs/engineering/coding-standards.md` and `docs/engineering/build-and-ci-quality-enforcement.md` for Java/source/build/CI changes;
14. applicable engineering, operations, testing, security, and deployment documentation.

Do not rely on remembered architecture when the current file can be inspected.

## 2. Architecture Decision Precedence

Accepted ADRs are immutable historical decisions. A changed decision is represented by a later ADR that explicitly supersedes all or part of an earlier ADR.

Always inspect `docs/adr/decision-register.md` before relying on an older ADR.

A superseding ADR overrides only the scope it explicitly supersedes. Unrelated decisions in the older ADR remain valid.

Do not silently resolve contradictions between current-state architecture documents and ADRs. When a contradiction is found:

1. inspect the Decision Register;
2. inspect the relevant ADRs;
3. determine whether explicit supersession resolves it;
4. use the current accepted decision when unambiguous;
5. update stale current-state documentation as part of the task when appropriate;
6. do not make a material architectural assumption when the conflict remains unresolved.

## 3. Mandatory Architecture Review

Every non-trivial implementation task requires an architecture review mode of either `full-read` or `targeted`.

A targeted review is allowed only when the task is narrow and the relevant documents and ADRs can be identified with confidence.

A full read of the applicable architecture set is required when the task:

- creates a service;
- changes a bounded-context boundary;
- changes service ownership;
- changes architecture;
- changes authentication, authorization, or security architecture;
- changes infrastructure architecture;
- introduces or changes service-to-service communication;
- materially changes persistence or consistency;
- introduces a new platform technology;
- starts after material context loss; or
- cannot prove that targeted review is sufficient.

## 4. Minimum Review for Every Non-Trivial Task

Locate and review the current applicable versions of:

- global architecture rules;
- prohibited practices;
- AI/code-generation rules;
- logging and PII-redaction rules;
- testing and quality gates;
- Definition of Done;
- Architecture Decision Register;
- applicable ADRs.

Also inspect:

- existing implementation;
- existing contracts;
- existing tests;
- the current Git diff.

## 5. Task-Specific Minimum Review

### Kafka or Event Changes

Review transport selection, event ownership, integration events, Protobuf compatibility, transactional outbox, inbox/idempotency, ordering, retries, DLQ, replay, observability, and tests.

### Database or Persistence Changes

Review aggregate boundaries, transaction boundaries, PostgreSQL, JPA/Hibernate, jOOQ, Flyway, connection pools, locking, migrations, backup/PITR, rollback compatibility, batch/fetch/flush behavior for sensitive paths, persistence-model shape, and tests.

### Java Code, Build, or CI Changes

Review `docs/engineering/coding-standards.md`, `docs/engineering/build-and-ci-quality-enforcement.md`, ADR-0001, ADR-0007, ADR-0061, and ADR-0069. Inspect the service `build.gradle.kts`, Wrapper/dependency verification, ArchUnit tests, Spotless/SpotBugs configuration, repository Semgrep rules, GitHub Actions required checks, and the actual source/tests. Documentation alone never proves code compliance.

### REST, gRPC, or Service Communication Changes

Review contract ownership, versioning, compatibility, deadlines, cancellation, retries, idempotency, workload identity, Istio authorization, observability, tests, and the operation-level dependency criticality/fallback matrix.

### Security Changes

Review authentication, authorization, tenancy, workload identity, mTLS, ServiceAccounts, WAF, secrets, semantic quotas, logging/PII redaction, and security tests.

### Kubernetes or Deployment Changes

Review Kubernetes, Helm, GitOps, Argo CD, Traefik, Gateway API, WAF, Istio, NetworkPolicy, ServiceAccounts, probes, graceful shutdown, rollback, CI/CD, and smoke tests. For local kind/Ambient/edge work, also review `docs/technology/local-development-baseline.md`, `docs/runbooks/local-istio-ambient.md`, and `docs/runbooks/local-traefik-edge.md`.

### Frontend or BFF Changes

Review BFF responsibilities, REST/OpenAPI, OIDC PKCE, sessions, CSRF/CORS, authentication, authorization ownership, logging/PII, frontend tests, BDD, and Playwright.

### Performance, Scaling, or Reliability Changes

Review `docs/architecture/performance-and-bottlenecks.md`, SLOs, dependency budgets, pool/connection budgets, existing load evidence, and documented scale/split triggers before introducing caches, brokers, proxies, new services, retries, or distributed coordination.

## 6. Core Engineering Rules

Every microservice represents a real business capability or bounded context.

The backend architecture is **DDD + Hexagonal Architecture**. Clean Architecture is used only to enforce inward dependency direction.

Business logic belongs in Domain/Application. Business logic MUST NOT depend on infrastructure technologies.

Domain code MUST NOT depend on Spring, JPA/Hibernate, jOOQ, Kafka, Redis, gRPC, Protobuf, PostgreSQL, Kubernetes, Istio, or concrete adapters.

Every independently deployable service with relational persistence owns a **distinct PostgreSQL database**, independent runtime/migration credentials, Flyway history, contracts, deployment, and release lifecycle. In production, every persistent microservice also owns a dedicated CloudNativePG cluster under ADR-0057/ADR-0064; physical sharing is permitted only in non-production when database/credential isolation is preserved.

Service database roles MUST NOT have access to another service's database. Direct cross-service database access, cross-database joins/foreign keys, and shared business/domain or persistence models across bounded contexts are prohibited. ADR-0053 defines database ownership; ADR-0057 defines production physical isolation/RLS; ADR-0064/ADR-0067 define fleet and recovery operations.

## 7. Dependency Injection

Spring IoC is the only dependency injection container.

Required dependencies use constructor injection. Field injection is prohibited. Circular dependencies are prohibited. `@Lazy` must not hide a dependency cycle.

Domain models must not be Spring beans. Application use cases remain plain Java. Service locator, `ApplicationContext`, `BeanFactory`, and runtime bean lookup inside Domain/Application are prohibited.

## 8. Change Discipline

Before changing code:

1. identify the bounded context and use-case owner;
2. identify affected contracts;
3. identify transaction boundaries;
4. identify synchronous and asynchronous interactions;
5. define deadlines/timeouts;
6. define retry behavior;
7. define idempotency behavior;
8. evaluate authentication and authorization;
9. evaluate workload identity and Istio policy impact;
10. evaluate secrets and PII/logging impact;
11. identify migration and rollback implications;
12. review the applicable Definition of Done.

Prefer the smallest correct change. Do not perform unrelated refactoring. Do not change architecture silently. An intentional architectural deviation requires an ADR before implementation depends on the deviation.

### 8.1 Mandatory Code-Generation Checklist

Before changing or generating implementation code, the AI/engineer MUST complete the following checklist. `Not applicable` is allowed only when the item genuinely does not apply to the scoped change; it is not a shortcut for skipping review.

1. Identify the bounded context and owner of the use case.
2. Define the required inbound and outbound ports before choosing concrete adapters.
3. Place business rules in Domain/Application and keep them independent of infrastructure frameworks.
4. Implement transport, persistence, messaging, cache, and external-provider concerns only in the applicable `interfaces`/`infrastructure` adapters and configuration composition.
5. Decide explicitly whether each interaction is synchronous or event-driven, using the approved transport rules and dependency-criticality policy.
6. Use the transactional outbox for a state change that must publish an integration event as part of the same business effect; never rely on a direct post-commit Kafka send for atomicity.
7. Define timeout/deadline, retry, idempotency, cancellation, concurrency/bulkhead, and transaction boundaries for every affected interaction.
8. Add or update required database migrations, tests, metrics/logs/traces, dashboards/alerts, and operational evidence in the same change when applicable.
9. Add or update ArchUnit/architecture tests whenever package/dependency/module boundaries or architectural rules are affected.
10. Add no dependency, plugin, library, runtime, or build tool without justification, ownership, license/security review where applicable, and compatibility verification against the Technology Baseline.
11. Keep the service `Dockerfile`, Helm/GitOps manifests, probes, resources, autoscaling/PDB settings when applicable, ServiceAccount, and NetworkPolicy aligned with runtime-impacting service changes.
12. Review and update related Traefik/Gateway API routes, upstream DDoS/WAF policy, request limits, security headers, and public-surface tests whenever the external/public surface changes.
13. Add or update BDD acceptance scenarios for critical business-behavior changes; do not use BDD for trivial CRUD or implementation detail.
14. Add or update Playwright tests for critical user-flow changes; keep browser tests focused on critical journeys rather than duplicating lower-level coverage.
15. Review and test every new or materially changed log statement for PII, secrets, credentials, tokens, cookies, payment data, unsafe exception text, CR/LF injection, and high-cardinality identifiers.
16. Connect dependencies through constructor injection and application/domain ports; never instantiate real infrastructure adapters from a use case or use runtime service lookup.
17. Add or update the workload ServiceAccount, Ambient Mesh enrollment, NetworkPolicy, and required Istio `AuthorizationPolicy` when workload identity or allowed communication changes.
18. For every new or changed service-to-service interaction, register the source/destination identities and operation-level dependency policy, define the positive and negative authorization cases, and add the corresponding policy/contract/failure-path tests.

The checklist complements, rather than replaces, the task-specific review requirements above. If a checklist item conflicts with an accepted current ADR, the ADR/Decision Register wins and the stale checklist/documentation MUST be corrected in the same change.

### 8.2 File Granularity and Task-Output Contract

Implementation files remain small and single-purpose under `docs/engineering/coding-standards.md` §15.1. At minimum, domain entities, value objects, domain events, DTOs, commands/queries, ports/interfaces, repository implementations, and distinct technical adapters are separated when they represent distinct responsibilities. Persistence models follow aggregate/query needs; one-table/one-model mapping is never mandatory.

Every non-trivial implementation report MUST explicitly cover the changed module/context, contracts, database migration, transaction boundary, timeout/deadline behavior, Kafka/event and idempotency behavior when applicable, security impact, observability, tests, and rollback considerations. See §14 below for the canonical report fields.

## 9. Repository State

Inspect the current Git diff before modifying code. Distinguish user changes, unrelated existing changes, and changes created for the current task.

Do not overwrite, revert, reformat, or absorb unrelated changes without justification.

Search the repository for established patterns before inventing a new one. Follow sound current conventions, but do not copy a pattern that violates current architecture.

## 10. Verification

Never claim success without verification.

Run applicable checks when available, including compilation, unit tests, integration tests, architecture tests, contract tests, migration tests, security tests, authorization tests, logging/PII tests, schema compatibility, dependency verification, static analysis, Helm/Kubernetes validation, `istioctl analyze`, and smoke tests.

Report every required check that passed, failed, was skipped, or was unavailable.

A task is not `completed` when missing verification materially prevents confidence in correctness.

## 11. Architecture Enforcement

Architecture compliance must not rely only on documentation or agent memory.

Use automated enforcement where applicable, including ADR-0069 Spotless formatting, SpotBugs, ArchUnit, repository Semgrep/static rules, contract compatibility checks, Buf compatibility checks, Gradle dependency verification, signed SBOM/advisory correlation plus continuous rescanning, migration/RLS validation, Authorization overload/burn/breaker-recovery tests, machine-readable dependency-policy schema/coverage/render checks, restore/DR evidence gates, logging/PII canary tests, Kubernetes policy validation, Helm validation, `istioctl analyze`, and GitHub Actions/CI quality gates.

Do not disable or weaken a quality gate merely to make the build pass.

## 12. Sub-Agent Rules

The parent agent remains responsible for delegated work.

Every sub-agent must receive:

- canonical architecture paths;
- applicable ADR paths;
- relevant section names or search terms;
- exact task boundary;
- applicable Definition of Done.

The sub-agent MUST independently verify current repository files. A summary supplied by the parent is not a substitute for reading authoritative sources when available.

Architecture design, service-boundary changes, security changes, infrastructure changes, service-communication changes, and work after context loss require the applicable full read.

## 13. Communication

`docs/engineering/agent-communication-and-reporting.md` is the mandatory detailed communication/evidence contract.

Communicate clearly and briefly. Lead with the result, blocker, material risk, or important finding. Do not narrate routine tool calls or private internal reasoning. Preserve exact paths, symbols, commands, errors, versions, scopes, and numeric constraints.

Separate verified facts from assumptions, inferences, unresolved questions, and recommendations. Never claim completion, correctness, build success, test success, security, production readiness, or architecture compliance without evidence.

Explain security risks, destructive actions, breaking changes, data-loss risks, and architecture deviations fully. For non-trivial tasks, progress updates are limited to significant findings, blockers, risks, scope changes, or failed verification that affects the outcome.

## 14. Implementation Report

Every completed non-trivial implementation task reports:

```text
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

Do not leave fields blank. Use `None`, `Not applicable`, or `Not verified` explicitly where accurate.

## 15. Final Response Format

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
<required Implementation Report>
```

Keep the report concise. Do not paste full diffs or long logs unless requested. Follow the detailed semantics in `docs/engineering/agent-communication-and-reporting.md`.

## 16. Sources

### Canonical Architecture Index

- `docs/architecture/README.md`
- `docs/architecture/SOURCES.md`
- `docs/architecture/TASK-REVIEW-MATRIX.md`
- `docs/architecture/PRODUCTION-READINESS-CHECKLIST.md`
- `docs/architecture/performance-and-bottlenecks.md`
- `docs/architecture/dependency-criticality.yaml`
- `docs/architecture/dependency-criticality.schema.json`
- `docs/architecture/dependency-criticality-matrix.md`
- `docs/architecture/PRODUCTION-DECISION-SUMMARY.md`
- `docs/architecture/PRODUCTION-ARCHITECTURE-REVIEW.md`

### Current-State Architecture

- `docs/architecture/platform-architecture.md`
- `docs/architecture/backend-engineering.md`
- `docs/architecture/security-architecture.md`
- `docs/architecture/data-and-messaging.md`
- `docs/architecture/reliability-and-observability.md`
- `docs/architecture/runtime-and-deployment.md`
- `docs/architecture/testing-and-quality-gates.md`

### Service Architecture

- `docs/architecture/services/identity-service.md`
- `docs/architecture/services/authorization-service.md`
- `docs/architecture/services/notification-service.md`
- `docs/architecture/services/web-bff.md`

### Architecture Decisions

- `docs/adr/decision-register.md`
- `docs/adr/`

The Decision Register is the entry point for ADR review. Review supersession before selecting an ADR.

### Technology Baseline

- `docs/technology/technology-baseline.md`
- `docs/technology/local-development-baseline.md`
- `docs/technology/production-compatibility-matrix.md`

Use them for exact approved versions and supported production combinations. Do not guess patch versions or compatibility.

### Engineering Workflow and Reporting

- `docs/engineering/agent-communication-and-reporting.md`
- `docs/engineering/developer-workflow.md`
- `docs/engineering/coding-standards.md`
- `docs/engineering/build-and-ci-quality-enforcement.md`

### Operations and Local Runbooks

- `docs/operations/incident-response-runbook.md`
- `docs/operations/chaos-engineering-program.md`
- `docs/runbooks/local-istio-ambient.md`
- `docs/runbooks/local-traefik-edge.md`

## 17. Source Maintenance

When an accepted architectural decision changes current architecture:

1. create a new ADR when required;
2. update the Architecture Decision Register;
3. update the applicable current-state architecture document;
4. update the Technology Baseline if a version decision changed;
5. update architecture tests or automated enforcement where applicable;
6. update `docs/architecture/SOURCES.md` when a source is added, removed, renamed, or superseded.

Do not rewrite historical accepted ADRs to make them look current. Keep historical decisions historical and current-state architecture current.
