# Engineering Hardening Roadmap and Audit Register

- **Status:** Active remediation sequencing and continuation ledger
- **Audit baseline:** `main@68cf66cf24c07dd6fca010ddae2789f42608aa31`
- **Audit date:** 2026-08-29
- **Scope:** Architecture alignment, security, reliability, performance, testing,
  infrastructure, DevSecOps, frontend, and MLOps remediation
- **Production Commissioning & Readiness:** DEFERRED until explicitly reactivated by
  the owner

## 1. Purpose and authority

This document records the actionable findings from the repository-wide engineering
audit and the ordered remediation sequence requested by the owner. It is the
continuation ledger for this hardening track, including after a chat, transport, or
token interruption.

This document does not override current effective ADRs, service architecture,
`implementation-status.md`, `APPLICATION-IMPLEMENTATION-ROADMAP.md`, current Git,
or executable evidence. When these sources disagree, current Git and the authority
order in `documentation-standards.md` win, and the stale current-state source must be
corrected before dependent implementation proceeds.

The audit covered all tracked repository files through inventory and repository-wide
static searches, with semantic review of current authorities and high-risk application,
security, persistence, messaging, provider, browser, deployment, and test paths. The
absence of a static finding is not proof that no defect exists. Environment-dependent
claims remain `NOT VERIFIED` until the required executable evidence exists.

## 2. Resume and status protocol

Before resuming this track:

1. run the mandatory repository bootstrap from `AGENTS.md`;
2. reconcile branch, `HEAD`, worktree, open PRs, `origin/main`, and current diff;
3. inspect the status/evidence row below and current Git rather than relying on chat
   memory;
4. resume the first `IN PROGRESS` stage, otherwise the first `NEXT` stage;
5. do not repeat a merge, deployment, provider call, or other side effect already
   proved by current Git/external state;
6. do not mark a stage `COMPLETED` until its completion boundary has been reviewed
   against the complete stage diff and all applicable gates have passed;
7. record the exact completion commit and evidence in this file when the stage is
   completed.

Allowed stage states are:

```text
IN PROGRESS  implementation has started but the completion boundary is not verified
NEXT         first executable stage after the current stage
PLANNED      ordered later stage whose prerequisites are not complete
COMPLETED    implementation and every required repository-level stage gate passed
GATED        execution is prohibited until the stated trigger exists
DEFERRED     deliberately outside the active implementation track
```

Only one stage may be `IN PROGRESS`. When no stage is in progress, exactly one
executable stage should be `NEXT` unless all remaining work is `GATED` or `DEFERRED`.
`COMPLETED` is repository-level evidence, not a Production-readiness claim.

## 3. Verified audit baseline

| Evidence | Result at audit baseline |
| --- | --- |
| Git provenance | `HEAD == origin/main == 68cf66cf24c07dd6fca010ddae2789f42608aa31`; clean worktree |
| Context authority | `make context-verify` and `make context-bootstrap` passed |
| Repository baseline | `make baseline-verify` passed |
| Protected repository CI | Repository baseline run `33105936814` passed for the audit baseline, including contract validation and all five Java service security suites |
| Browser CI | Web frontend E2E run `33105936555` passed for the audit baseline |
| Contract boundary | Neutral Protobuf package `1.8.0`; version, Protovalidate, examples, server wiring, and compatibility gates present |
| Public BFF contract | OpenAPI `1.6.0`; controller parity, schema/examples, and generated frontend type drift gates present |
| Confirmed Critical vulnerability | None identified by the audit; this is not an absence guarantee or penetration-test evidence |
| Production readiness | `NOT VERIFIED`; local/repository evidence does not prove Production commissioning |

## 4. Finding register

Severity is the remediation priority within this audit. A finding marked as an
evidence gap is not reported as a confirmed runtime defect.

| ID | Severity | Area | Finding and impact | Remediation stage |
| --- | --- | --- | --- | ---: |
| HR-001 | HIGH | Persistence reliability | Identity transaction execution has no explicit per-operation transaction/statement/lock deadline policy while lock-taking paths exist. A blocked query can retain a pool connection beyond the caller deadline. | 2 |
| HR-002 | HIGH | Durable workers | Notification delivery and service Outbox batches have narrow theoretical lease margins when provider/dependency calls consume their maximum deadline sequentially. Current single-replica behavior is not proof of safe restart or future scale behavior. | 2 |
| HR-003 | HIGH | Frontend reliability | Browser requests lack a common finite abort deadline and consistent RFC Problem mapping; several async journeys lack complete rejection, busy, cancellation, or duplicate-submit behavior. | 3 |
| HR-004 | MEDIUM | Frontend privacy/state | Browser persistence can throw on write, retains contact data longer than required, and may rehydrate stale UX authentication/tenant state. Browser state is not authorization authority, but privacy and recovery behavior are incomplete. | 3 |
| HR-005 | HIGH | Frontend verification | The selected Vitest/React Testing Library baseline is not implemented; unit/component test count is zero, automated accessibility testing is absent, and Playwright covers only the current critical journeys. | 4 |
| HR-006 | MEDIUM | Localization/accessibility | `fa`/`en` resources and direction support are incomplete/not integrated, most UI text is hard-coded English, and broader accessibility evidence is absent. Prior roadmap wording overstated this completion boundary. | 4 |
| HR-007 | HIGH | Frontend DevSecOps/release | Frontend CI lacks dedicated JS/TS advisory and SAST gates, component/accessibility gates, and a signed immutable Production image/SBOM/vulnerability/admission path. Repository-wide Java workflow Gitleaks still covers the Git tree. | 5 |
| HR-008 | MEDIUM | Dependency governance | Frontend manifest uses `latest`/caret ranges and React runtime/type versions are not aligned. The lockfile makes current `npm ci` reproducible, but manifest updates can admit unreviewed versions. | 5 |
| HR-009 | MEDIUM | Contract toolchain | Central contracts use Protobuf/protoc `4.34.2`, while service-local Protobuf compilers use `3.25.8`, contradicting the aligned compiler/runtime baseline and creating future code-generation drift risk. | 5 |
| HR-010 | MEDIUM | Maintainability | `JooqAuthorizationStore`, Identity `RuntimeConfiguration`, `IdentityBffClient`, and `JooqTenantStore` have accumulated multiple responsibilities and large change surfaces. | 6 |
| HR-011 | MEDIUM | CI/tooling maintainability | Five service workflows repeat substantial security/build setup. Repository shell/Python/workflow scripts have custom tests but no selected high-signal ShellCheck/actionlint/Python static gate. | 6 |
| HR-012 | HIGH | Capacity/performance evidence | No executable load/soak/chaos suite or complete-stack headroom proof exists; representative sensitive-query `EXPLAIN` evidence was not found. Required Production headroom remains `NOT VERIFIED`. | 7 |
| HR-013 | MEDIUM | Test depth | Java and frontend coverage thresholds are absent; selective mutation testing for security state machines is absent; BDD/scenario coverage is narrow. | 7 |
| HR-014 | HIGH | External/runtime evidence | Full HIBP corpus bounds, real Google/Liara/IPPanel execution, erasure redeploy/restore scenarios, provider ambiguity, and real staging failure evidence remain incomplete or `NOT VERIFIED`. | 7 |
| HR-015 | HIGH | MLOps governance | Conversation architecture has no versioned offline synthetic/adversarial evaluation suite, quality/safety/cost/latency promotion thresholds, canary policy, or model/prompt rollback evidence. | 8 |
| HR-016 | HIGH | AI safety/data control | Content-safety/acceptable-use ownership, provider data-control approval, feedback handling, and drift policy require an explicit reviewed decision before model execution is enabled. | 8 |
| HR-017 | HIGH | Core product | ADR-0054 Conversation/ModelRun is designed but no executable service, contracts, database, worker/provider adapter, BFF/UI, or lifecycle implementation exists. | 9 |
| HR-018 | BLOCKER when Production track is active | Production platform | Production K3s/Calico/Istio/Kyverno/OpenBao, CNPG/Barman, Kafka, Redis security/recovery, Argo CD, WireGuard/JIT access, external host-down monitoring, off-host audit, signing/admission execution, backup/PITR/DR, and complete-stack capacity evidence are not commissioned. | 10 |
| HR-019 | MEDIUM | Documentation truth | Roadmap frontend wording, Identity completion wording, reporting-standard section references, and current protected-CI evidence had drifted from current Git. | 1 |

### Key source anchors

These anchors make the finding register reproducible; the owning stage must inspect
the full affected flow rather than patching only the named line.

| Finding | Initial evidence anchors |
| --- | --- |
| HR-001 | `services/identity-service/src/main/java/com/sajtech/identity/infrastructure/persistence/SpringTransactionRunner.java`; Identity lock-taking repository queries; Identity datasource/transaction configuration |
| HR-002 | `services/notification-service/src/main/java/com/sajtech/notification/infrastructure/runtime/delivery/NotificationDeliveryWorker.java`; `services/notification-service/src/main/java/com/sajtech/notification/application/delivery/usecase/RunDeliveryBatchService.java`; Identity/Notification Outbox dispatchers and their lease/deadline configuration |
| HR-003/004 | `apps/web-frontend/src/api/bffClient.ts`; `apps/web-frontend/src/pages/TenantSelectionPage.tsx`; `apps/web-frontend/src/state/storage.ts`; `apps/web-frontend/src/state/appReducer.ts`; all submit/selection/destructive-action pages |
| HR-005/006 | `apps/web-frontend/package.json`; `apps/web-frontend/e2e/`; `apps/web-frontend/src/i18n/resources.ts`; frontend accessibility audit and roadmap/status claims |
| HR-007/008 | `.github/workflows/web-frontend-e2e.yml`; `apps/web-frontend/package.json`; `apps/web-frontend/package-lock.json`; Production release/deployed-digest workflows |
| HR-009 | `contracts/protobuf-contracts/build.gradle.kts`; all five `services/*/build.gradle.kts` files that configure service-local Protobuf generation |
| HR-010 | `services/authorization-service/src/main/java/com/sajtech/authorization/infrastructure/persistence/JooqAuthorizationStore.java`; `services/identity-service/src/main/java/com/sajtech/identity/configuration/RuntimeConfiguration.java`; `services/web-bff/src/main/java/com/sajtech/webbff/infrastructure/client/IdentityBffClient.java`; `services/identity-service/src/main/java/com/sajtech/identity/infrastructure/persistence/JooqTenantStore.java` |
| HR-011 | `.github/workflows/`; `scripts/`; `docs/engineering/build-and-ci-quality-enforcement.md` |
| HR-012/013/014 | `docs/architecture/performance-and-bottlenecks.md`; `docs/architecture/testing-and-quality-gates.md`; `docs/operations/chaos-engineering-program.md`; `docs/runbooks/production-cold-dr.md`; current test/source inventory |
| HR-015/016/017 | `docs/adr/0054-define-core-conversation-and-model-execution-v1.md`; `docs/architecture/services/conversation-service.md`; absence of `services/conversation-service/` |
| HR-018 | `docs/architecture/PRODUCTION-READINESS-CHECKLIST.md`; `docs/architecture/implementation-status.md`; Production release, platform, and recovery authorities |

### Test and contract snapshot

At the audit baseline, backend services had 111 tracked files under their service test
source trees, the neutral contract package published twelve Protobuf files with
validation-backed request messages and example-backed consumer checks, and the BFF
OpenAPI covered all 58 public controller method/path mappings. The frontend had seven
project-owned Playwright spec files containing eleven current journeys, but no
project-owned unit/component test file and no coverage threshold. Counts are baseline
inventory only; stage completion depends on risk coverage and executed behavior, not a
target file count or 100% line-coverage goal.

## 5. Confirmed strengths to preserve

Remediation must not weaken these verified current properties:

- DDD/Hexagonal dependency direction and service-owned persistence/build/release
  boundaries;
- one online fail-closed Authorization attempt with the current 300 ms caller
  deadline and no permission cache/retry/stale allow;
- forced tenant RLS and non-owner runtime roles;
- strict JSON/body/metadata bounds, CSRF/Origin/Fetch Metadata controls, secure
  cookies, browser security headers, and validated identity/contact inputs;
- issuer+subject external-identity binding and provider-token isolation;
- AES-GCM/HMAC/Argon2id/SecureRandom/constant-time comparison choices;
- transactional Outbox, at-least-once Inbox/idempotency, and ambiguity-safe
  Notification semantics;
- versioned/validated/example-backed Protobuf contracts and BFF OpenAPI parity;
- PII-safe allow-list telemetry and low-cardinality metrics;
- immutable-digest hardened Kubernetes workload policy and the selected
  Syft/Grype/Cosign/Kyverno release responsibility chain;
- browser-to-BFF-only public authority boundary;
- ADR-0054 exclusions: no Workflow/Agent/tool/RAG/streaming/BYOK/shared
  conversation/provider-side state in the first slice.

## 6. Ordered remediation stages

| Stage | Work package | State | Completion boundary | Completion evidence |
| ---: | --- | --- | --- | --- |
| 1 | Current-truth documentation reconciliation | `IN PROGRESS` | Publish this register; route it from architecture sources; correct reporting-standard references, frontend milestone overstatement, Identity completion wording, and current protected-CI status; review the complete documentation diff; pass context/documentation/baseline gates. | Pending |
| 2 | Database deadlines and durable-worker lease safety | `PLANNED` | Define operation-specific transaction/statement/lock budgets; implement cancellation/error mapping; add lock contention/pool-exhaustion tests; measure and enforce worker batch/deadline/lease invariants without layered retries or remote I/O in transactions. | Pending |
| 3 | Frontend resilience and privacy | `PLANNED` | Add one bounded abortable BFF request boundary, consistent safe problem mapping, busy/double-submit/cancellation/error states, error boundary, safe storage failure behavior, minimal persisted state, and prompt PII clearing. | Pending |
| 4 | Frontend testing, localization, and accessibility | `PLANNED` | Add Vitest/RTL component coverage, automated accessibility gate, real `fa`/`en` consumption and RTL/LTR switching, keyboard/focus/error semantics, and broader Playwright journeys. | Pending |
| 5 | Dependency, DevSecOps, and frontend release alignment | `PLANNED` | Replace dynamic manifest versions with reviewed pins, align React types/runtime and Protobuf compiler, add distinct JS advisory/SAST gates, and include the frontend in immutable image/SBOM/Grype/Cosign/Kyverno release evidence. | Pending |
| 6 | Characterization-first maintainability refactor | `PLANNED` | Add characterization tests, then split identified stores/config/client/workflows by existing capabilities without changing public contracts, transaction boundaries, failure semantics, or security gates. | Pending |
| 7 | Performance, reliability, and test evidence | `PLANNED` | Add risk-based coverage thresholds, selective security mutation tests, representative plans, load/soak/fault/lease/pool tests, complete HIBP/provider staging evidence, erasure restore/redeploy checks, and measured headroom evidence. | Pending |
| 8 | MLOps evaluation and safety architecture gate | `PLANNED` | Approve versioned non-PII eval data, model/prompt/price catalog, promotion/rollback/canary thresholds, safety/acceptable-use/feedback/drift policy, and provider data-control requirements. Do not add an MLOps platform without an evidenced need. | Pending |
| 9 | ADR-0054 private Conversation + ModelRun vertical slice | `PLANNED` | Implement the accepted service/contracts/DB/RLS/encryption/worker/provider/cost/lifecycle/telemetry/BFF/UI slice and its security/privacy/failure/load/browser/Helm evidence, preserving every ADR-0054 exclusion. | Pending |
| 10 | Production Commissioning & Readiness | `DEFERRED` | Execute every current Production readiness/environment/release/recovery/capacity gate only after explicit owner reactivation; repository documentation or local kind evidence alone cannot complete this stage. | Not applicable while deferred |

Stage 2 is the first code-changing stage. It precedes Conversation implementation so
new model-execution load is not added before database, pool, worker lease, and
cancellation behavior is bounded and testable.

## 7. Stage review checklist

Before changing a stage from `IN PROGRESS` to `COMPLETED`, record and review:

- exact base, implementation, and final reviewed commits;
- complete diff against the latest `main` and any intervening base changes;
- affected architecture/ADRs/contracts/migrations/transactions/remote edges;
- deadline, cancellation, retry, concurrency, idempotency, and rollback behavior;
- authentication, authorization, tenant, secret, PII, and telemetry impact;
- applicable unit/integration/contract/security/migration/browser/load/render gates;
- failures, unavailable evidence, deferred environment evidence, and residual risk;
- merge/CI state required by the repository workflow.

If any required repository-level condition is unverified, the stage remains
`IN PROGRESS`. Production-only evidence may remain `NOT VERIFIED` only when the stage
completion boundary explicitly separates repository completion from Production
commissioning.

## 8. Conditional Reference Data track

Reference Data is not part of stages 1-10. The local immutable capability remains
`GATED` until a real consumer journey requires it. An independent
`reference-data-service` remains prohibited until ADR-0041's independent-deployable
trigger is evidenced and reviewed.

## 9. Audit limitations

- Static/source review cannot prove the absence of exploitable defects.
- `npm audit` was `INCONCLUSIVE` during the audit because registry/network access did
  not return a result within the bounded attempt; current lockfile advisory status
  must be established in stage 5.
- Production, real-provider, full-corpus, capacity, restore, signing, and admission
  claims remain `NOT VERIFIED` where the owning environment/evidence is absent.
- Version-sensitive implementation must re-check the installed version and current
  official upstream documentation at the time of its stage.
