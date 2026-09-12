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
| HR-014 | HIGH | External/runtime evidence | Full HIBP corpus bounds, Google Gmail execution, production SMS.ir delivery, erasure redeploy/restore scenarios, and provider ambiguity remain incomplete or `NOT VERIFIED`. The simulated SMS.ir Sandbox contract/failure probe is `Passed`; Google OIDC execution is owner-deferred and not applicable to Notification-provider evidence. | 7 |
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
| 1 | Current-truth documentation reconciliation | `COMPLETED` | Publish this register; route it from architecture sources; correct reporting-standard references, frontend milestone overstatement, Identity completion wording, and current protected-CI status; review the complete documentation diff; pass context/documentation/baseline gates. | Final corrected implementation head `a4dca87963854083be505091283ed645bda59fe1`; protected repository baseline run `33181670174` attempt 2 and frontend E2E run `33181670044` passed |
| 2 | Database deadlines and durable-worker lease safety | `COMPLETED` | Define operation-specific transaction/statement/lock budgets; implement cancellation/error mapping; add lock contention/pool-exhaustion tests; measure and enforce worker batch/deadline/lease invariants without layered retries or remote I/O in transactions. | Final reviewed implementation head `a01a7bd03f0768398d83abb1198a6c49c13941a0`; protected repository baseline run `33206841203` and frontend E2E run `33206840964` passed |
| 3 | Frontend resilience and privacy | `COMPLETED` | Add one bounded abortable BFF request boundary, consistent safe problem mapping, busy/double-submit/cancellation/error states, error boundary, safe storage failure behavior, minimal persisted state, and prompt PII clearing. | Final reviewed implementation head `856236885a5f7c18380641a75dee4d4d5576d5bf`; protected repository baseline run `33234759108` and frontend E2E run `33234759032` passed |
| 4 | Frontend testing, localization, and accessibility | `COMPLETED` | Add Vitest/RTL component coverage, automated accessibility gate, real `fa`/`en` consumption and RTL/LTR switching, keyboard/focus/error semantics, and broader Playwright journeys. | Final reviewed implementation head `4f29bdccc60581b2a2144ef296d6e31647b86e42`; protected repository baseline run `33247612662` and frontend E2E run `33247612549` passed |
| 5 | Dependency, DevSecOps, and frontend release alignment | `COMPLETED` | Replace dynamic manifest versions with reviewed pins, align React types/runtime and Protobuf compiler, add distinct JS advisory/SAST gates, and include the frontend in immutable image/SBOM/Grype/Cosign/Kyverno release evidence. | Final reviewed implementation head `209684a5a465477e87ff9c257c0511ace5af3a0f`; protected repository baseline run `33301549810` and frontend E2E run `33301549573` passed |
| 6 | Characterization-first maintainability refactor | `COMPLETED` | Add characterization tests, then split identified stores/config/client/workflows by existing capabilities without changing public contracts, transaction boundaries, failure semantics, or security gates. | Final reviewed implementation head `56bb71c29b96ddb4cce7f0b276f25c53417a32fc`; protected repository baseline run `33322638261` and frontend E2E run `33322638140` passed |
| 7 | Performance, reliability, and test evidence | `IN PROGRESS` | Add risk-based coverage thresholds, selective security mutation tests, representative plans, load/soak/fault/lease/pool tests, complete HIBP/provider staging evidence, erasure restore/redeploy checks, and measured headroom evidence. | Both measured load findings are corrected. Exact-commit image/deploy, production-fidelity, zero-unexpected-result load/soak, protected CI, complete-corpus HIBP staging, SMS.ir Sandbox, and real staging erasure/redeploy/restore evidence passed. Real Google Gmail and Production SMS.ir delivery evidence remain open. |
| 8 | MLOps evaluation and safety architecture gate | `PLANNED` | Approve versioned non-PII eval data, model/prompt/price catalog, promotion/rollback/canary thresholds, safety/acceptable-use/feedback/drift policy, and provider data-control requirements. Do not add an MLOps platform without an evidenced need. | Pending |
| 9 | ADR-0054 private Conversation + ModelRun vertical slice | `PLANNED` | Implement the accepted service/contracts/DB/RLS/encryption/worker/provider/cost/lifecycle/telemetry/BFF/UI slice and its security/privacy/failure/load/browser/Helm evidence, preserving every ADR-0054 exclusion. | Pending |
| 10 | Production Commissioning & Readiness | `DEFERRED` | Execute every current Production readiness/environment/release/recovery/capacity gate only after explicit owner reactivation; repository documentation or local kind evidence alone cannot complete this stage. | Not applicable while deferred |

Stage 2 is the first code-changing stage. It precedes Conversation implementation so
new model-execution load is not added before database, pool, worker lease, and
cancellation behavior is bounded and testable.

### Stage completion receipts

| Stage | Base commit | Final reviewed implementation commit | Review and verification result |
| ---: | --- | --- | --- |
| 1 | `68cf66cf24c07dd6fca010ddae2789f42608aa31` | `a4dca87963854083be505091283ed645bda59fe1` | `COMPLETED`: complete corrected diff reviewed with no remaining Stage 1 finding; `make context-verify`, generated matrix parity, `make context-bootstrap`, and `make baseline-verify` passed locally; protected repository baseline run `33181670174` attempt 2 passed structure, contracts, all five service security suites, and the final aggregator; frontend E2E run `33181670044` passed. Production-only evidence remains outside this stage. |
| 2 | `04a986a626f8447f56c3f4e328fff7b2bf6f63b6` | `a01a7bd03f0768398d83abb1198a6c49c13941a0` | `COMPLETED`: complete Stage 2 diff reviewed with no remaining HR-001/HR-002 finding; Context7-confirmed Spring/jOOQ/PostgreSQL/gRPC behavior was verified against installed versions; Identity and Notification full Gradle checks, transaction-local reset/deadline/lock/pool tests, real unary gRPC failure mapping, multi-item lease-order tests, `make context-verify`, `make context-bootstrap`, and `make baseline-verify` passed locally; protected repository baseline run `33206841203` passed structure, contracts, all five service security suites, and final baseline verification; frontend E2E run `33206840964` passed. Production load/capacity evidence remains owned by Stage 7/10. |
| 3 | `42b2f191b46e959ba0c9086fd56e74f5c0dcaee4` | `856236885a5f7c18380641a75dee4d4d5576d5bf` | `COMPLETED`: complete Stage 3 diff reviewed with no remaining HR-003/HR-004 finding; React effect cleanup, error-boundary, and browser external-store behavior was verified against current official React documentation after Context7 transport remained unavailable. Frontend build, generated OpenAPI drift, all 22 Playwright journeys, `make context-verify`, `make context-bootstrap`, and `make baseline-verify` passed locally; protected repository baseline run `33234759108` passed structure, contracts, all five service security suites, and final baseline verification; frontend E2E run `33234759032` passed. Component/accessibility/localization and deployed-browser evidence remain owned by Stage 4/10. |
| 4 | `ae68a2f9f7351826fbee952bb5e97ffe8285a070` | `4f29bdccc60581b2a2144ef296d6e31647b86e42` | `COMPLETED`: complete Stage 4 diff reviewed with no remaining HR-005/HR-006 finding. Context7 was invoked for Vitest, React Testing Library, and axe documentation but its transport was unavailable; current official project documentation and exact installed-version behavior were used as the fallback. Typed `fa`/`en` catalogs now drive every current journey, request locale, and document RTL/LTR without persistent browser state; route headings receive client-navigation focus and every direct route has a main landmark. Three Vitest/RTL component tests, three Chromium/axe test journeys covering two directions and eleven stable route shells, twenty-four other Playwright journeys, generated OpenAPI drift, TypeScript/build, npm advisory, `make context-verify`, `make context-bootstrap`, and `make baseline-verify` passed locally. Protected frontend run `33247612549` passed and repository baseline run `33247612662` passed structure, contracts, all five service security suites, and its final aggregator. Coverage thresholds remain Stage 7; frontend SAST/advisory/release alignment remains Stage 5; deployed-browser/Production evidence remains Stage 10 and `NOT VERIFIED`. |
| 5 | `c6a972d2d770ddb8a50b48dc57630856d2920597` | `209684a5a465477e87ff9c257c0511ace5af3a0f` | `COMPLETED`: the complete Stage 5 diff was reviewed against unchanged `origin/main` with no remaining HR-007/HR-008/HR-009 finding. Context7-confirmed React 19.2.7 alignment, OSV npm-lock scanning, and explicit protoc 4.34.2 configuration were applied. Frontend manifest and lockfile pins, OSV scan, eight-rule Semgrep source policy with positive/negative fixtures, non-root/read-only/no-capability Caddy image health/SPA smoke, Production six-component release validation/rescan/Syft/Grype/Cosign/Kyverno wiring, all five strict Gradle checks, production tests, component/API/build/accessibility/twenty-four other browser journeys, `make context-verify`, `make context-bootstrap`, and `make baseline-verify` passed locally. Protected frontend run `33301549573` passed every new frontend gate and protected repository baseline run `33301549810` passed contracts, structure, all five service security suites, and its final aggregator. Actual frontend staging/Production workload/routing, real release signing/scanning/admission execution, deployed browser journeys, and Production readiness remain Stage 10 and `NOT VERIFIED`. |
| 6 | `703333fabcdd97808863a33604a19d161c9a8d2b` | `56bb71c29b96ddb4cce7f0b276f25c53417a32fc` | `COMPLETED`: the complete Stage 6 diff was reviewed against unchanged `origin/main` with no remaining HR-010/HR-011 finding. Characterization tests fixed the public facade constructors/interfaces and critical Spring bean names, conditions, profiles, and destroy methods before refactoring. Authorization persistence, Identity tenant persistence, Identity runtime configuration, and the BFF Identity client are now capability-focused facades/components; transaction-call counts, BFF deadline counts, and Identity bean counts match the pre-refactor surfaces, and no contract, migration, deployment, or dependency file changed. The five service workflows now share one executable Gitleaks/OSV implementation while retaining four explicit blocking security steps each. Checksum-pinned ShellCheck 0.11.0, actionlint 1.7.12, and Ruff 0.16.5 enforce selected high-signal repository source checks. Context7-confirmed Spring configuration composition/profile behavior, jOOQ transaction-scoped context use, and actionlint/ShellCheck integration informed the review. Authorization, Identity, and Web BFF formatting, unit, integration, architecture, SpotBugs, and runtime-JAR gates, `make context-verify`, `make context-bootstrap`, and `make baseline-verify` passed locally. Protected repository baseline run `33322638261` passed source lint, contracts, all five complete service security suites, and its final aggregator; frontend E2E run `33322638140` passed. Stage 7 retains all coverage, mutation, performance, load, soak, fault, provider, and capacity evidence work. |

### Stage 7 continuation receipt

This is interruption-safe progress evidence, not a completion receipt. The latest
completed local production-fidelity and capacity run used the clean implementation
revision `25b747e4a7070d6cfc0602eb0506d6b5bebb5866` on 2026-09-08, as recorded in the
rebuild receipt below. The following 2026-09-07 measurements belong to
`a29486549bec3b258376c6c66b51714f94e94947`. Earlier coverage,
mutation, plan, and developer-local erasure receipts were recorded at
`a8d100edcbd61d37ec314d9ac138a95266a3e24c`; they are commit-bound historical evidence,
not proof for later changes.

- **Passed:** all five Java services have enforced global and risk-focused JaCoCo
  thresholds over combined unit/integration execution; the Identity security slice
  killed 33 of 34 PIT mutants (97%), and the Web BFF boundary killed 108 of 245 PIT
  mutants (44%) under their reviewed risk-specific thresholds.
- **Passed:** frontend Vitest/V8 coverage has global and risk-module thresholds, with
  47 current tests; representative Authorization and Notification PostgreSQL plans use
  the intended indexes on realistic local cardinalities without sequential scans.
- **Passed:** the owner-authorized SMS.ir Sandbox probe verified TLS hostname checking,
  credential acceptance, invalid-credential rejection, invalid-input rejection, and the
  documented simulated-success shape. Its identifier-free mode-0600 receipt explicitly
  records no real delivery claim; it is not production SMS acceptance or delivery evidence.
- **Passed:** the local production-fidelity staging lane now has an exact-digest
  Kafka 4.2.1 combined KRaft workload, explicit erasure topics, strict mesh/workload policy,
  per-service connection Secrets, and all four participant gates. At
  `f19746fb54add27f4edc52a7344b6bcc6e734301`, the clean-commit rehearsal deleted the
  synthetic Identity, verified all four participant receipts, restarted all five applications,
  restored all four pre-completion database snapshots, and verified normal replay without
  reappearance. The identifier-free receipt is mode `0600`.
- **Passed:** `make local-runtime-smoke-erasure-recovery` completed a developer-local
  four-participant erasure, full service redeploy, pre-completion PostgreSQL 18 snapshot
  restore, and normal Outbox/Kafka/Inbox reconciliation without reappearance. Its
  identifier-free mode-0600 receipt is bound to `a8d100edcbd61d37ec314d9ac138a95266a3e24c`. This is not
  staging or Production restore evidence.
- **Passed:** the complete local production-fidelity staging lane, including five
  services, persistence, strict Ambient mTLS/workload-identity negatives, edge/WAF,
  metrics, traces, privacy-filtered logs, Grafana, and telemetry-backend outage
  non-authority behavior, passed at `a29486549bec3b258376c6c66b51714f94e94947`.
- **Passed:** the 60-second invalid-login load at concurrency 16 completed 5,940
  operations with 100% exact safe outcomes, no unexpected failures, p99 510.596 ms,
  minimum CPU headroom 42.056%, and minimum memory headroom 67.778%.
- **Passed:** the 1,800-second session-bootstrap soak at concurrency 8 completed
  273,524 operations with 273,520 successes (99.999%), p99 62.509 ms, minimum CPU
  headroom 67.4%, and minimum memory headroom 66.891%. Both profiles recorded zero
  swap movement, restarts, OOM kills, or application pod-UID changes. Results are in
  `.platform-runtime/stage7/capacity/` with the exact revision and UTC timestamps.
- **Inconclusive:** the soak recorded four `BOOTSTRAP_HTTP_500_INTERNAL_ERROR`
  outcomes. Prometheus confirmed all four on the bootstrap route, but logs and
  sampled traces did not retain a classified cause; these are not claimed as diagnosed
  Redis timeouts or as zero-error evidence. This prompted the session-failure follow-up
  below despite the capacity suite passing its unchanged thresholds.
- **Passed:** the official complete HIBP corpus acquired on 2026-09-09 was built into a
  72,477,519,872-byte immutable SQLite artifact with 2,068,408,781 records. The observed
  maximum prefix cardinality is 2,509 and maximum Protobuf response is 102,932 bytes,
  within the reviewed 4,096-record/131,072-byte compatibility bounds. On the exact
  `f19746f` image, 256 cold and warm random lookups measured p99 32.640 ms and 8.353 ms;
  a one-connection 64-stream run, including bounded read-only storage pressure, completed
  64/64 with no transport or unexpected status. It did not observe a fail-fast rejection,
  so no rejection claim is made. Rebuild/redeploy/recovery and full post-restore staging and
  production-fidelity verification passed. The identifier-free receipt is mode `0600` at
  `.platform-runtime/stage7/hibp-staging-evidence.json`.
- **Passed:** protected Repository baseline run `34630982537` and Web frontend E2E
  run `34630981897` completed successfully for exact implementation
  `f19746fb54add27f4edc52a7344b6bcc6e734301`, including every five-service security
  suite, contract/examples, WAF privacy boundary, frontend journeys, and both final
  aggregators. PR #126 remains Draft while provider evidence is open.
- **Passed:** a bounded owner-authorized Google Gmail staging execution reached the real provider
  and the final SMTP response moved the attempt to `PROVIDER_ACCEPTED`; no mailbox-delivery or
  human-read claim is made. Credentials and recipients remained in mode-`0600` Git-ignored state
  and do not appear in the identifier-free receipt.
- **Partially verified:** a Production SMS.ir API key authenticated, `GET /v1/line` returned exactly
  one numeric sender line, and the real exact-text path was exercised without credential/recipient
  disclosure. SMS.ir rejected bulk submission with HTTP `400`, provider status `123` (sender-line
  activation required). Notification classified the explicit rejection as permanent and performed
  no blind retry. Production acceptance, delivery, and reconciliation remain `Not verified` until
  the account owner activates the sender line and the bounded exercise is repeated.
- Google OIDC remains owner-deferred and is not part of Notification-provider evidence.

Corrective work already included in the measured revision:

- The Identity soak failure was traced to one `OOMKilled` restart: a 75% JVM heap
  envelope inside the 1 GiB container left insufficient native/runtime reserve. The
  image uses 50% without changing Argon2 security or workload limits.
- Authorization and Web BFF explicitly disable duplicate OTLP metrics export while
  retaining Prometheus metrics and OTLP tracing; the full production-fidelity verifier
  passed this configuration, including absence of OTLP metrics-push errors.
- The intermittent login `INVALID_REQUEST` was reproduced with one canonical UUIDv4.
  Direct BFF access accepted it, while WAF-to-waypoint access rejected it. Effective
  Envoy configuration uses `UuidRequestIdConfig`, and official Envoy documentation
  confirms that `x-request-id` is generated/mutated telemetry. OpenAPI 2.0.0 therefore
  moves business replay identity to UUIDv4 `Idempotency-Key`; frontend/controller and
  edge regression coverage move atomically with it.
- Capacity evidence schema v2 snapshots the five application workloads before and
  after each run; any restart, OOM observation, or pod-set replacement now fails the
  result rather than being hidden by aggregate host headroom. The runner also refuses
  dirty worktrees so formal evidence cannot be misattributed to the preceding commit.
- Quota capacity/noeviction checks execute inside the same atomic EVAL only for new
  bucket allocation. Four adapters use the existing 75ms Lettuce command timeout;
  the duplicate elapsed-time rejection after a successful Redis decision is removed.
  Context7 Redis/Lettuce documentation, Redis 8.2.8 integration tests, and the Identity,
  Authorization, and BFF full Gradle checks support the change. No security budget,
  retry, fail-closed result, or memory reserve was relaxed.

Session-failure implementation and historical deployment interruption:

- Session Redis commands now share the existing timing/error boundary, classify only
  fixed `timeout`/`unavailable` outcomes, and translate dependency failure into stable
  `503/DEPENDENCY_UNAVAILABLE`. Browser-filter lookup/touch stops dispatch without
  clearing a valid cookie. No application retry or session grant follows ambiguity.
- **Passed:** focused real-Redis delayed-write/closed-connection tests and HTTP/filter
  rejection, no-grant, no-retry, and safe-label/error tests; full BFF Gradle check
  (unit/integration/architecture/SpotBugs/format/coverage); BFF PIT 110/248 mutants
  killed (44%) under the existing threshold; official checksum-pinned
  repository actionlint/ShellCheck/Ruff and baseline gates.
- **Passed:** all five staging images were built from clean session-failure revision
  `bbbfb448297c777ca810c078aca55b588a38194f`.
- **Failed:** deployment of that revision stopped at the first Kubernetes apply with
  `Forbidden`. The configured `kubernetes-admin` cannot list namespaces in
  `kind-platform-local`; the failure was reconfirmed before the CI remediation below.
  Read-only container diagnostics show the control plane restarted at
  `2026-09-07T10:40:06Z`, after the earlier capacity run, with only its core static
  pods running. Earlier API startup diagnostics reported missing system namespaces.
  Its exact historical cause remains **Inconclusive**. The configured etcd storage
  is the explicitly ephemeral `/dev/shm/hooshix-kind/etcd`; loss on WSL restart is
  consistent with the documented topology, not evidence of a persistent-volume
  recovery guarantee. The subsequent owner-approved rebuild is recorded below.

Owner-approved local rebuild and refreshed capacity receipt, 2026-09-08:

- The owner explicitly approved deleting/rebuilding only `platform-local`. Its three
  old kind nodes and cluster-local state were removed; repository/Git data and the
  existing local registry were preserved. Recovery of removed cluster data is not
  established. Do not repeat `production-fidelity-up` merely to resume: it deletes
  and recreates this ephemeral cluster. Use the component deploy/verify commands.
- **Passed:** `production-fidelity-up` at clean
  `25b747e4a7070d6cfc0602eb0506d6b5bebb5866`: restored configured operator access,
  all three Ready nodes, five exact-source service images, persistence, strict Ambient
  identity/bypass negatives, Kyverno, edge/WAF, and full observability including
  backend-outage non-authority tests. Log: `.platform-runtime/stage7/approved-rebuild.log`.
- **Passed:** unchanged 60-second/concurrency-16 invalid-login capacity thresholds,
  UTC `03:26:32`–`03:27:32`: 6,514 operations, 6,510 expected outcomes (99.939%),
  p99 441.052 ms, minimum CPU/memory headroom 52.116%/72.210%.
  Four unexpected responses remain material follow-up, not zero-error evidence:
  three `LOGIN_HTTP_403_INVALID_PROBLEM` and one
  `LOGIN_HTTP_503_DEPENDENCY_UNAVAILABLE`.
- **Passed:** unchanged 1,800-second/concurrency-8 bootstrap soak, UTC
  `03:27:32`–`03:57:33`: 283,189/283,189 successes (100%), no unexpected failure,
  p99 59.126 ms, minimum CPU/memory headroom 70.737%/71.138%.
  Both runs recorded zero application restarts, OOM kills or pod-UID changes.
  Swap movement was nonzero but isolated (maximum consecutive active samples: one
  in each run), below the unchanged five-consecutive-sample failure threshold.
  Exact mode-0600 JSON receipts: `.platform-runtime/stage7/capacity-25b747e-20260908/`.
- **Passed:** BFF session Redis metric aggregates contained only fixed `ok` outcomes
  through these runs. The previous four bootstrap 500s did not recur; this does not
  retroactively establish their original cause.
- **Passed locally:** all three load 403s were traced to CRS rule 930120 inspecting
  a valid opaque session locator. The exact one-cookie locator shape is now excluded
  only from that rule; malformed/duplicate/other cookies, arguments, bodies and other
  rules remain inspected. The pinned coraza-caddy source is checksum-verified, patched
  before compilation to emit only fixed events plus numeric rule metadata, and covered
  by embedded CRS/privacy tests. Audit/debug expansion is off, Caddy request metadata
  is dropped, and the immutable local image is
  `sha256:22ba62be1b1bdc55c4f5a5ce7236e006c93f58ff02ca5caca133494b5171efab`.
  Its no-network packaged-image smoke and configuration validation passed.
- **Passed locally:** Traefik chart-41 access logging now uses a response/timing-only
  allowlist. Its single-node host-port update does not surge. An exact current
  Kubernetes API EndpointSlice `/32` and TCP port is installed before the chart so a
  replacement pod can restore Gateway watches without broad egress. The complete edge
  verifier passed foundation, strict Ambient identity positives/negatives, synthetic
  privacy canaries, a real Traefik pod replacement and post-restart WAF routing.
- **Passed locally:** the Identity SQL connection-acquisition exception was confirmed
  to escape a direct pre-transaction credential read. Direct and transactional jOOQ
  paths now share type/SQLSTATE-based finite failure translation; the unary boundary
  returns `RESOURCE_EXHAUSTED / IDENTITY_DATABASE_POOL_UNAVAILABLE` without a cause,
  grant, retry or second quota charge and recovers after the held connection releases.
  BFF maps only reviewed quota/business-limit descriptions to 429 and maps capacity or
  unknown exhaustion to stable 503. Fresh full Identity and BFF `check bootJar` runs,
  including integration, architecture, SpotBugs, formatting and coverage gates, passed.
  One Identity unit test first exceeded its existing 100 ms client deadline while both
  service suites were deliberately rerun concurrently; the isolated unchanged suite
  then passed all 23 executed tasks. No production deadline was relaxed.
- **Passed:** all five service images were built and deployed from clean commit
  `dd9574075b63e4c2251d1ae469ae6f51fc9f4a87` with exact Git/worktree provenance.
  The complete local production-fidelity verifier then passed five-service persistence,
  strict Ambient identity positives/negatives, edge privacy and Traefik replacement
  recovery, Prometheus/Tempo/Loki/Grafana, and telemetry-backend outage non-authority.
  Docker Hub returned 403 for the already pinned disposable curl canary on one node;
  the failed attempt remains recorded. The exact committed verifier passed after that
  node was temporarily cordoned without eviction so Kubernetes selected the other
  worker's identical cached digest, and all three nodes were uncordoned afterward.
  This is local functional evidence, not offline-registry or Production evidence.
- **Passed:** formal post-fix 60-second/concurrency-16 invalid-login load, UTC
  `16:44:49`–`16:45:50`, completed 6,260/6,260 expected outcomes (100%), zero
  unexpected responses, p99 478.965 ms, minimum CPU/memory headroom
  49.177%/64.468%, and zero swap movement, restart, OOM or pod-UID change.
- **Passed:** formal post-fix 1,800-second/concurrency-8 session-bootstrap soak, UTC
  `16:45:50`–`17:15:50`, completed 280,071/280,071 successes (100%), zero
  unexpected responses, p99 60.617 ms, minimum CPU/memory headroom
  67.861%/63.881%, and zero swap movement, restart, OOM or pod-UID change. Both
  schema-v2 receipts independently verify and are mode 0600 under
  `.platform-runtime/stage7/capacity-dd95740-20260908/`. Neither earlier WAF 403 nor
  Identity-pool 503 recurred.
- **Passed:** protected repository baseline run `34251259796`, attempt 2, passed
  contracts/examples, structure, the new WAF privacy/cookie image gate, and all five
  service suites. Authorization and Compromised Password integration steps failed on
  attempt 1 but passed unchanged on the fresh runner; no test or gate was weakened.
  Frontend run `34251259315`, attempt 1, passed. Both runs are bound to `dd95740`.
- **Passed later at `f19746f`:** official complete-corpus HIBP staging latency/load/
  recovery and real staging four-participant erasure restart/restore/reconciliation.
  **Not verified:** authorized real Google Gmail and Production SMS.ir delivery exercises.
  Google OIDC is an independently implemented optional login path and is owner-deferred
  for this stage. Generated local fixtures cannot satisfy provider gates; secrets must
  not be sent in chat.

#### Load-finding remediation review report

This report covers only the bounded WAF/Traefik and Identity/BFF corrections caused by
the measured load findings. It is not a Stage 7 completion receipt.

| Required field | Review evidence |
| --- | --- |
| Architecture review mode | `full-read`; security/PII/persistence/platform work reviewed under `minimal-safe-engineering` critical priorities |
| Architecture document version/commit | Correction implementation `dd9574075b63e4c2251d1ae469ae6f51fc9f4a87`; `origin/main` reviewed at `a52dfd82856a1da9419e7d9cd4c20b96acf783fe`; final documentation commit and base reconciliation remain pre-merge gates |
| Architecture sections reviewed | Mandatory source order; edge/network/client trust, BFF/Identity service boundaries, synchronous failure containment, PII-safe observability, performance/capacity, test/CI and local edge runbook |
| Search terms used | `930120`, `MATCHED_VAR`, `ErrorLog`, `accessLog.fields`, `EndpointSlice`, `NetworkPolicy`, `RESOURCE_EXHAUSTED`, `SQLTransientConnectionException`, `TransactionUnavailableException`, `QUOTA_EXCEEDED` |
| ADRs reviewed or changed | ADR-0001/0016/0024/0025/0031/0039/0042/0043/0044/0045; no ADR changed |
| Changed bounded context/module | Local Traefik/WAF edge; Identity persistence failure adapter/transport evidence; Web BFF Identity failure mapping; protected baseline wiring |
| Contracts changed | No schema or public route changed. Existing stable gRPC descriptions and HTTP problem codes are classified more narrowly; compatibility tests cover the mappings |
| Database migration | Not applicable; no schema, query or migration changed |
| Transaction boundary | Unchanged. The same classifier now also covers direct jOOQ execution before a transaction begins |
| Timeout/deadline behavior | Unchanged: query, pool-acquisition, gRPC and BFF deadlines were not increased |
| Retry/cancellation/concurrency behavior | No retry added and no pool/concurrency limit increased. Local one-replica Traefik uses `maxSurge: 0`/`maxUnavailable: 1` because its host ports are exclusive |
| Kafka/event and idempotency behavior | Unchanged; no event, Outbox/Inbox or idempotency contract changed |
| Security impact | Removes valid opaque-locator false positives without disabling a rule globally; preserves WAF enforcement; capacity ambiguity fails closed as 503 rather than a false user quota result; API egress is one resolved `/32` and port |
| Istio identity and authorization impact | Existing strict Ambient identity and WAF-only public route remain unchanged and passed positive/negative runtime verification |
| Logging and PII impact | Expanded Coraza match/request text and Traefik request metadata are removed; fixed low-cardinality numeric WAF events remain. Synthetic canaries and all severity levels are tested |
| Observability added or changed | Edge log fields narrowed; Identity retains the existing low-cardinality `failure` metric. Telemetry remains non-authoritative |
| Build/CI/architecture enforcement changed | Protected baseline now requires checksum-pinned WAF source build, embedded rule/privacy tests, packaged configuration validation and no-network image smoke; repository/platform static tests cover the wiring |
| Tests executed | WAF build/embedded tests and image smoke, Caddy validation, full local edge/identity/restart verification, 17 platform tests, all repository baseline suites, fresh full Identity/BFF Gradle suites, exact-commit five-image build/deploy, complete production-fidelity, formal load/soak, and protected CI passed; all initial/retry failures are recorded above |
| Architecture deviations | None identified within this correction. Local single-node restart briefly interrupts ingress and is not an HA claim |
| Rollback considerations | Do not restore raw WAF/request logging, remove exact API watch egress, globally disable CRS 930120, route around WAF, or map unknown capacity to 429. Replace the source patch only with an upstream version that passes the same privacy/boundary gates |

Stage 7 CI remediation on 2026-09-07:

- Protected baseline run `34125212492` at `bbbfb448297c777ca810c078aca55b588a38194f`
  failed all five service jobs at the OSV dependency gate. Three Critical-rated OSV
  advisories in embedded Tomcat require the official fixed patch; this is a
  pre-existing dependency finding exposed by refreshed advisory data, not a
  demonstrated exploit of the application's configured authentication paths.
- Frontend run `34125212165` failed five Semgrep findings in the new Stage 7
  `storage.test.ts` fixtures. This was introduced by this PR's test setup, not a
  production storage change. The fixtures now seed isolated in-memory Storage
  doubles; the real adapter remains under test, including disabled access and failed
  reads/writes/removals. No scanner rule, exclusion, or coverage threshold changed.
- All five service builds now constrain the three embedded Tomcat modules to the
  security-fixed baseline in `../technology/technology-baseline.md`. Selective Gradle
  lock regeneration changed only these three modules; all six new JAR/POM checksums
  in each service match the published Maven Central SHA-256 values. Spring Boot,
  Gradle, and Vitest documentation was retrieved through Context7.
- **Passed:** checksum-pinned OSV rescans of all five updated service lockfiles;
  all five strict Gradle `check bootJar` executions (unit, integration, architecture,
  SpotBugs, formatting, dependency integrity, and existing risk-coverage thresholds);
  frontend 51 Vitest tests with existing coverage thresholds, OpenAPI generated-client
  parity, TypeScript/build, 3 accessibility journeys, 24 other Playwright journeys,
  and unchanged eight-rule Semgrep with positive/negative controls; repository baseline,
  actionlint/ShellCheck/Ruff, context verification/bootstrap, and whitespace checks.
- Protected recheck outcomes are recorded below by exact implementation revision.
  Previous failed runs remain provenance, never passing evidence for a later commit.

CI recheck of implementation `330ced32e03ac57a10ec83762688d93de319a2ea`:

- **Passed:** frontend run `34150784062`, and all five clean staging image builds
  with Git-bound metadata. Each packaged JAR contains the three aligned fixed Tomcat
  modules. These images have not been deployed into the inaccessible cluster.
- **Failed:** baseline run `34150784378` reached BFF mutation testing after its
  OSV/unit/integration/coverage/architecture gates passed, then strict dependency
  verification rejected the unrecorded `groovy-bom-4.0.11.module` metadata artifact.
  The corresponding POM was already recorded. The exact module bytes and published
  SHA-256 were independently matched against Maven Central; only that missing artifact
  checksum is added, without disabling metadata verification or changing dependencies.
- **Passed:** local BFF PIT at the Tomcat-patched revision still kills 110/248 mutants
  (44%), with 67% line coverage of selected classes and 59% test strength. Local
  cache success did not prove a fresh CI dependency-resolution path; that path was
  subsequently verified by the corrected-metadata protected run below.

CI remediation completion receipt, not Stage 7 completion:

- Reviewed corrected implementation: `ca6d4803ebf9d398dba0590a601076743497d467`.
- **Passed:** protected repository baseline run `34151203292`, including structure,
  contract validation/examples, every complete service security suite, both selective
  mutation gates, and the final baseline aggregator. Frontend run `34151203110` also
  passed all advisory, SAST, coverage, image, accessibility, and browser gates.
- **Passed:** forced local BFF mutation rerun with strict verification: all ten tasks
  executed, 110/248 mutants killed (44%), 67% selected-class line coverage and 59%
  test strength. No CI bypass, checksum wildcard, suppression, or reduced gate was used.
- The two original CI failures and the fresh-CI metadata failure are resolved.
  The cluster blocker was subsequently removed by the approved rebuild above.
  The later commit-bound load-finding verification is recorded above. Some required
  provider runtime evidence remains open, not unfinished CI remediation. PR #126 remains
  Draft and Stage 7 remains
  `IN PROGRESS`; `main` was not changed.
- **Continuation action:** activate the sole sender line returned by the authenticated SMS.ir
  account, then repeat the bounded Production SMS.ir acceptance/delivery/reconciliation exercise.
  Gmail staging execution is complete. Never request secret values in chat or substitute synthetic
  claims for the remaining run.

Then obtain and validate the remaining provider runtime evidence. Keep Stage 7
`IN PROGRESS` until every completion-boundary item and the complete Stage 7 diff pass
review; do not advance to Stage 8 before then.

#### CI remediation review report

This report covers the bounded CI follow-up, not completion of the entire Stage 7 PR.

| Required field | Review evidence |
| --- | --- |
| Architecture review mode | `full-read`, continued from the Stage 7 review; `minimal-safe-engineering` in `critical` mode |
| Architecture document version/commit | Current authorities reconciled at `bbbfb448297c777ca810c078aca55b588a38194f`; `origin/main` remained `a52dfd82856a1da9419e7d9cd4c20b96acf783fe` |
| Architecture sections reviewed | Mandatory source order; BFF browser/session ownership; version governance/compatibility; build/CI, testing, performance and interruption-safe evidence |
| Search terms used | `Tomcat`, `constraints`, `verification`, `lock`, `storage`, `Stage 7`, `IN PROGRESS` |
| ADRs reviewed or changed | Existing Stage 7 ADR review retained, including ADR-0016/0039/0045 for this follow-up; no ADR changed |
| Changed bounded context/module | Build dependencies of all five Java services; frontend storage unit-test fixtures; baseline/compatibility and roadmap evidence |
| Contracts changed | None in this CI follow-up; earlier PR OpenAPI change remains under Stage 7 review |
| Database migration | Not applicable to the CI follow-up |
| Transaction boundary | Unchanged |
| Timeout/deadline behavior | Unchanged; no budget increase |
| Retry/cancellation/concurrency behavior | Unchanged; no runtime retry added |
| Kafka/event and idempotency behavior | Unchanged |
| Security impact | Patch known vulnerable servlet-container dependency; preserve all source, secret, integrity, advisory and test gates |
| Istio identity and authorization impact | Unchanged; no bypass of the failed cluster RBAC boundary |
| Logging and PII impact | No runtime change; only synthetic in-memory frontend test data |
| Observability added or changed | None in this CI follow-up; earlier session metrics remain subject to renewed staging evidence |
| Build/CI/architecture enforcement changed | Aligned Tomcat constraints/locks and verified checksum metadata; no scanner suppression, threshold reduction, new plugin or workflow relaxation |
| Tests executed | Exact local and protected-run outcomes recorded above; failed/unverified runtime gates remain explicit |
| Architecture deviations | None identified within this bounded follow-up |
| Rollback considerations | Do not promote/revert to known vulnerable Tomcat artifacts; fail forward with a reviewed fixed compatible patch. Cluster recovery/backup is not established, so no destructive rebuild without explicit owner approval |

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
