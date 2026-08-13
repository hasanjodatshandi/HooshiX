# Agent Communication and Reporting Contract

> This document is mandatory companion reading for `AGENTS.md`, the applicable
> architecture documents, and `docs/engineering/coding-standards.md`. An agent
> working on architecture, implementation, infrastructure, review, migration,
> security, or release evidence must follow it before planning, changing,
> reviewing, or reporting non-trivial work.

Communication quality is part of task correctness. A technically correct change
with a vague, misleading, incomplete, or unverified report is not complete.

## 1. Core communication rules

1. Lead with the result, decision, blocker, material risk, or most important finding.
2. Do not begin with filler, generic praise, or a restatement of the task.
3. Do not provide a chronological diary of routine searches, tool calls, file reads, or internal reasoning.
4. Report verified outcomes rather than intentions.
5. Preserve exact paths, symbols, commands, configuration keys, error messages, versions, test scopes, and numeric constraints.
6. Clearly distinguish verified facts, assumptions, inferences, unresolved questions, and recommendations.
7. Never claim that something works, passes, is secure, is complete, or is production-ready unless the relevant evidence was actually obtained.
8. Never hide failures, skipped checks, reduced scope, uncertainty, or unresolved risks.
9. Do not silently expand scope or perform unrelated refactoring.
10. Preserve the user's language unless another language is requested. Keep code, commands, identifiers, API names, exact errors, and repository paths unchanged.
11. Do not expose private chain-of-thought or internal reasoning. Report evidence, decisions, and concise rationale instead.
12. Follow the complete Code-Generation Checklist in `AGENTS.md` §8.1 / `coding-standards.md` §15 for implementation work.

## 2. Communication during work

For non-trivial work, provide brief progress updates only when they add useful
information or let the user steer before a material decision becomes expensive.

Useful updates include:

- a significant finding that changes the implementation plan;
- an unexpected dependency or affected bounded-context/security boundary;
- a blocker requiring user input;
- a security, privacy, data-loss, compatibility, or operational risk;
- a necessary scope expansion/reduction;
- a failed verification step that changes the outcome;
- a discovered documentation/implementation drift that must be resolved.

Do not report every command, search query, routine file edit, or normal test run.

When a structured update is useful, use:

```text
Status: <planning | investigating | implementing | verifying | blocked>
Finding: <material fact>
Impact: <why it matters>
Next action: <next material step>
```

Omit fields that do not add useful information.

## 3. Questions, ambiguity, and assumptions

Do not ask questions that can be answered by inspecting the current repository,
documentation, tests, configuration, Git history, baseline files, or applicable
ADRs.

Ask the user only when missing information materially changes:

- expected behavior or acceptance criteria;
- public/event contract;
- security posture or access-control semantics;
- data model or migration strategy;
- destructive/irreversible action;
- bounded-context/architecture boundary;
- backward compatibility;
- ownership or externally controlled credentials/approvals.

Ask one focused question at a time when a question is necessary.

When a low-risk assumption permits progress, report it explicitly:

```text
Assumption: <assumption>
Reason: <why it is reasonable>
Impact if incorrect: <what would need to change>
```

Do not proceed on an assumption that could cause data loss, a security
regression, incompatible contract change, or irreversible operation.

## 4. Scope control

Before implementation, establish the task boundary using the repository and the
mandatory architecture review mode.

During implementation:

- make the smallest complete change that satisfies the requirement;
- avoid drive-by cleanup and unrelated refactoring;
- report newly discovered unrelated issues separately;
- do not fix unrelated issues unless they block the agreed task or the user expands scope;
- do not weaken quality gates to avoid fixing the actual problem.

If scope materially changes, report:

```text
Scope change: <change>
Reason: <why>
Files or components added: <scope>
Risk: <material risk>
User decision required: yes/no
```

## 5. Evidence requirements

Every important implementation claim must be supported by available evidence.
Preferred evidence includes:

- `path:line` references when stable;
- changed symbols/configuration keys when line numbers are unstable;
- exact test/check commands and result scope;
- contract/schema comparisons;
- Flyway/migration validation;
- ArchUnit/SpotBugs/Semgrep/dependency-verification output;
- relevant logs or metrics;
- rendered Helm/Kubernetes policy validation;
- Git diff inspection;
- applicable architecture sections, Technology Baseline, compatibility matrix, and ADRs.

Use exact evidence. Do not say `tests passed` when only a focused subset ran, and
do not say `all checks passed` when a required check was skipped or unavailable.

## 6. Reporting code/configuration changes

Report changes as a compact receipt:

```text
- `<path>:<line-range>` — <what changed and why>
- `<path>` — `<Class.method or configuration key>`: <change and reason>
```

Do not paste a complete diff unless the user requests it or the exact patch is
required to explain a problem.

For generated code, report the generator/source-of-truth and whether generated
output was regenerated and verified.

## 7. Reporting review findings

For defects, risks, or review findings, use:

```text
<severity> `<path>:<line>` — <problem>. <impact>. <recommended fix>.
```

Severity levels:

- `BLOCKER` — unsafe to merge/deploy or likely destructive/security-critical failure;
- `HIGH` — likely correctness, security, data-loss, availability, or contract failure;
- `MEDIUM` — meaningful reliability, performance, maintainability, or operational risk;
- `LOW` — limited impact or non-blocking improvement;
- `QUESTION` — author intent or requirement must be clarified before a safe conclusion.

Order findings by severity, then by file/path. Do not pad a review with praise or
style observations that do not materially affect correctness or maintainability.

If none exist, say:

```text
No findings within the reviewed scope.
```

Do not generalize a scoped review into a claim that the entire system is correct.

## 8. Security and irreversible actions

Use fuller, explicit communication for authentication/authorization, secrets,
PII, destructive migrations, permanent deletion, public API compatibility,
production infrastructure, privileged access, financial/legal impact, and
security policy changes.

Before an irreversible/destructive action that requires confirmation, state:

```text
Warning: <risk>
Action: <exact operation>
Affected data or system: <scope>
Why it is irreversible or risky: <reason>
Required backup or recovery path: <evidence/path>
Confirmation required: yes
```

Do not execute a confirmation-required destructive action without explicit user
confirmation.

## 9. Failure and blocker reporting

When work cannot be completed, do not return a generic failure.

Use:

```text
Status: blocked | partial | failed
Completed: <what is verified complete>
Blocked at: <step/component>
Root cause: <cause>
Evidence: <exact evidence>
User input required: <input or None>
Recommended next action: <next step>
Safe rollback or recovery: <path or Not applicable>
```

When a check fails, classify whether it:

- existed before the change;
- was introduced by the change;
- is unrelated but blocks verification;
- could not be classified with available evidence.

Never silently ignore a failing required check.

## 10. Verification language

Use these terms precisely:

- `Passed:` executed successfully for the stated scope.
- `Failed:` executed and failed.
- `Not run:` not executed, with a reason.
- `Not applicable:` does not apply, with a reason.
- `Partially verified:` only part of the required behavior was tested.
- `Inconclusive:` available evidence cannot prove the result.
- `Not verified:` a required implementation/evidence artifact does not yet exist or was not inspected.

Avoid unsupported statements such as `should work`, `probably fixed`, `fully
secure`, `production-ready`, `all good`, or `nothing else is affected`.

## 11. Required implementation report

Every completed or partially completed non-trivial implementation task includes:

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

Every field is filled with evidence or `None`, `Not applicable`, or `Not verified`.

## 12. Final response order

Unless a specialized task requires a stricter format, final responses use:

```text
Outcome:
<completed | partial | blocked | failed>
<one- or two-sentence result>

Changes:
- `<path or component>` — <change and reason>

Verification:
- Passed: `<command/check>` — <result/scope>
- Failed: `<command/check>` — <result>
- Not run: `<check>` — <reason>

Risks and limitations:
- <remaining risk/limitation or "None identified within reviewed scope">

Remaining work:
- <required next action or "None">

Architecture report:
<required implementation report>
```

Keep the response concise. Do not paste full diffs or long logs unless requested
or required to establish a blocker.

## 13. Completion criteria

Report `completed` only when:

- the requested behavior/artifact is implemented;
- affected files/contracts/configuration have been inspected;
- the current Git diff has been reviewed;
- applicable tests/checks have passed;
- architecture requirements and Definition of Done were checked;
- remaining limitations/risks are disclosed;
- no known blocker remains within the agreed scope.

If a material required check could not be completed, report `partial` unless the
check is explicitly and correctly `Not applicable`.

## 14. Prohibited reporting practices

An agent must not:

- claim completion before verification;
- invent commands, test results, logs, metrics, or version evidence;
- conceal skipped/failed checks;
- describe planned work as completed work;
- say `all tests passed` without identifying the executed scope;
- provide vague summaries such as `updated the code`;
- dump raw logs when a short decisive excerpt is sufficient;
- expose private chain-of-thought/internal reasoning;
- blame tools, agents, or the user without the technical cause;
- hide security/data-loss implications behind brevity;
- use excessive repetition, praise, filler, or unrelated improvement suggestions;
- claim repository compliance from documentation alone when executable evidence is absent.
