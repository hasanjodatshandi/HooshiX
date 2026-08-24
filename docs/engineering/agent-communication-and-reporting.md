# Agent Communication and Reporting Contract — Current Standard

This document is mandatory companion reading for `AGENTS.md`, applicable architecture/current ADRs, and engineering standards. Communication quality is part of task correctness; a technically correct change with misleading or unverified reporting is incomplete.

## 1. Core communication rules

1. Lead with result, blocker, material risk, or important finding.
2. Do not begin with filler/praise/task restatement.
3. Do not narrate routine tool calls, searches, file reads, or private reasoning.
4. Report verified outcomes, not intentions.
5. Preserve exact paths, symbols, commands, errors, versions, scopes, and numeric constraints.
6. Separate verified facts from assumptions, inference, unresolved questions, and recommendations.
7. Never claim something works/passes/is secure/complete/production-ready without relevant evidence.
8. Never hide failures, skipped checks, reduced scope, uncertainty, or unresolved risk.
9. Do not silently expand scope or perform unrelated refactoring.
10. Preserve the user's language unless another language is requested; code/commands/identifiers/errors stay exact.
11. Do not expose private chain-of-thought/internal reasoning; report evidence and concise rationale.
12. Implementation work follows the 20-item preflight in `AGENTS.md` §15 and `coding-standards.md` §16.

## 2. Progress updates

For non-trivial work, update the user only when the information can affect understanding or steering, such as:

- significant finding changing the plan;
- unexpected bounded-context/security/dependency impact;
- blocker requiring user input;
- security/privacy/data-loss/compatibility/operational risk;
- necessary scope change;
- failed verification affecting outcome;
- documentation/implementation drift that must be corrected.

Do not report every routine command/search/edit/test. A concise structured update may use:

```text
Status: <planning | investigating | implementing | verifying | blocked>
Finding: <material fact>
Impact: <why it matters>
Next action: <next material step>
```

## 3. Questions and assumptions

Do not ask questions that current repository/docs/tests/configuration/current ADRs can answer.

Ask only when missing information materially affects behavior/acceptance criteria, public/event contract, security/access semantics, data/migration strategy, destructive action, bounded-context boundary, backward compatibility, or external ownership/credential approval.

A low-risk assumption may be reported as:

```text
Assumption: <assumption>
Reason: <why reasonable>
Impact if incorrect: <required change>
```

Do not proceed on assumptions that can cause data loss, security regression, incompatible contract change, or irreversible action.

## 4. Scope discipline

Before implementation establish the task boundary through the required architecture review mode. During work:

- make the smallest complete coherent change;
- avoid unrelated refactoring/cleanup;
- report unrelated findings separately unless they block the scoped task;
- never weaken quality/security gates to avoid fixing the real issue.

If scope materially changes, report scope/reason/affected components/risk and whether user decision is required.

## 5. Evidence requirements

Important claims should be backed by available exact evidence, including where applicable:

- stable path/section/symbol references;
- exact executed check/command and scope;
- contract/schema comparison;
- Flyway/migration verification;
- ArchUnit/SpotBugs/Semgrep/dependency-verification output;
- logs/metrics when safe/relevant;
- rendered Helm/Kubernetes/Istio policy validation;
- Git/PR diff inspection;
- architecture/Technology Baseline/compatibility/current ADR evidence.

Never say “tests passed” when only a subset ran or “all checks passed” when required checks were skipped/unavailable.

## 6. Change reporting

Report code/config changes as compact receipts:

```text
- `<path>` — <symbol/config/area>: <change and reason>
```

Do not paste full diffs unless requested or required to explain a blocker. For generated code, identify generator/source-of-truth and whether output was regenerated/verified.

## 7. Review finding format

```text
<severity> `<path>:<location>` — <problem>. <impact>. <recommended fix>.
```

Severity:

- `BLOCKER` — unsafe to merge/deploy or likely destructive/security-critical failure;
- `HIGH` — likely correctness/security/data-loss/availability/contract failure;
- `MEDIUM` — meaningful reliability/performance/maintainability/operational risk;
- `LOW` — limited/non-blocking improvement;
- `QUESTION` — author intent/requirement must be resolved before safe conclusion.

Order by severity then path. If no findings: `No findings within the reviewed scope.` Do not generalize a scoped review to whole-system correctness.

## 8. Security and irreversible actions

Use fuller explicit reporting for authentication/authorization, secrets/PII, destructive migrations/deletion, public contract compatibility, production infrastructure, privileged access, and financial/legal impact.

Before a confirmation-required irreversible action state:

```text
Warning: <risk>
Action: <operation>
Affected data/system: <scope>
Why irreversible/risky: <reason>
Required backup/recovery: <evidence/path>
Confirmation required: yes
```

Do not execute without explicit confirmation when confirmation is required.

## 9. Failure/blocker reporting

An intermediate failure does not justify an incomplete final status while a safe, authorized recovery action remains available in the current response. For a terminal-condition task, follow `AGENTS.md` §14.1: diagnose recoverable failures, use materially distinct bounded recovery attempts, re-verify, and continue. A progress checkpoint or conversation-turn boundary is not a final task boundary.

Before reporting `partial` or `blocked`, record the exact unsatisfied terminal condition, blocker evidence, recovery actions attempted, why available tools cannot safely advance the task, and any exact external or user action required. If no new external input is required and the next safe action is available, execute it instead of ending.

When incomplete:

```text
Status: blocked | partial | failed
Completed: <verified complete scope>
Blocked at: <step/component>
Root cause: <cause>
Evidence: <exact evidence>
User input required: <input or None>
Recommended next action: <next step>
Safe rollback/recovery: <path or Not applicable>
```

Classify failed checks as pre-existing, introduced by the change, unrelated-but-blocking, or inconclusive. Never silently ignore a required failure.

## 10. Verification vocabulary

Use precisely:

- `Passed:` executed successfully for stated scope;
- `Failed:` executed and failed;
- `Not run:` not executed, with reason;
- `Not applicable:` genuinely irrelevant, with reason;
- `Partially verified:` only part tested;
- `Inconclusive:` evidence insufficient;
- `Not verified:` required implementation/evidence artifact absent or uninspected.

Avoid unsupported language such as “should work”, “fully secure”, “production-ready”, “all good”, or “nothing else is affected”.

## 10.1 Automation-safe terminal report protocol

Every final report uses these exact machine-readable keys in addition to the human-readable evidence required by this standard:

```text
Outcome:
completed | partial | blocked | failed

Remaining work:
None | <remaining items>

Continuation action:
continue | stop | human

Retryable:
yes | no

Human action required:
None | <exact action>
```

These fields describe whether local task-supervision automation may safely continue. They do not grant authority, broaden tool permissions, or authorize a side effect. Current Git/repository/runtime state remains the source of truth. After a UI, delivery, transport, or tool interruption, reconcile the real source of truth before repeating any mutation, deployment, commit, message, or other side effect.

Required invariants:

- `Outcome: completed` is valid only with `Remaining work: None`, `Continuation action: stop`, `Retryable: no`, and `Human action required: None`. Automation treats only that complete tuple as successful terminal completion.
- `Continuation action: continue` means a safe authorized next action remains, no new external/user input is required, and automatic continuation may proceed. A transient `blocked`/`failed` report may use `continue` only with `Retryable: yes` and only after source-of-truth reconciliation.
- `Continuation action: human` means progress requires an external/user action that the agent cannot perform with current authority. It requires `Retryable: no` and a concrete non-`None` `Human action required` value.
- `Continuation action: stop` means automation must not send another continuation. For a non-`completed` outcome, the report must state the unresolved condition and why no safe automatic continuation remains.
- `Retryable: yes` means a bounded automated retry/continuation is safe after current-state reconciliation. `Retryable: no` means automation must not retry the failed/blocked action automatically.
- `Remaining work: None` is prohibited for `partial`, `blocked`, or `failed` when actual required work remains.
- Human prose, an apparently idle UI, a missing spinner, or a transport timeout is never equivalent to `Outcome: completed`.

The key names and token values above are compatibility-sensitive. Change them only in one coherent change that updates the agent contract, repository enforcement, and any consuming local automation.

## 11. Required implementation report

Every completed/partially completed non-trivial implementation task fills:

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

Every field has evidence or `None`, `Not applicable`, or `Not verified`.

## 12. Final response order

```text
Outcome:
completed | partial | blocked | failed
<one/two-sentence result>

Changes:
- <path/component> — <change>

Verification:
- Passed: <executed check/scope>
- Failed: <executed check/result>
- Not run: <check/reason>

Risks and limitations:
- <remaining risk or None identified within reviewed scope>

Remaining work:
- <required next action or None>

Continuation action:
continue | stop | human

Retryable:
yes | no

Human action required:
None | <exact action>

Architecture report:
<required report fields>
```

## 13. Completion criteria

Report `completed` only when requested behavior/artifact is implemented, affected files/contracts/configuration inspected, current Git/PR diff reviewed, applicable checks passed, current architecture/Definition of Done checked, limitations disclosed, and no known blocker remains within scope.

If a material required check could not run, first apply the recovery discipline in `AGENTS.md` §14.1. Report `partial` only when the check remains unavailable after applicable safe recovery and the missing evidence is material. Use `Not applicable` only when it is genuinely irrelevant.

## 14. Prohibited reporting

Never invent commands/results/logs/version evidence; conceal skipped/failed checks; describe planned work as completed; dump unnecessary logs; expose private reasoning; hide security/data-loss implications; or claim repository/source/runtime compliance from documentation alone.