# Repository Change Workflow

This document defines the mandatory change-delivery workflow for this repository.

## Pull-request-first rule

All normal repository changes use a dedicated branch and a pull request targeting `main`.

Required sequence:

1. create a task-specific branch from the current `main`;
2. open a Draft pull request as early as GitHub permits;
3. place all substantive task changes on the pull-request branch, never directly on `main`;
4. compare and review the complete pull-request diff against the latest `main`;
5. run and record every applicable documentation, security, compatibility, build, test, and policy check;
6. resolve material findings before merge;
7. if final review finds another issue in the same coherent change, fix it in the same PR and re-review the affected authority/dependent documents;
8. mark the pull request ready only when scope/review is complete;
9. merge into `main` only after required review and verification;
10. verify the resulting `main` merge/head SHA and final repository state;
11. for a non-trivial task PR with a pre-merge work checkpoint, create the required post-merge checkpoint follow-up defined below.

GitHub does not permit opening a PR whose head has no commit different from base. The smallest legitimate task/governance scaffolding commit may be created first only to establish the Draft PR; substantive work remains inside that PR.

Direct commits to `main` are prohibited for normal agent-driven work. Emergency exceptions require an explicit user instruction describing the emergency and must be documented after the fact.

## Post-merge checkpoint lifecycle

A pre-merge work checkpoint is historical evidence for the subject implementation/review commit. It MUST NOT be rewritten after later CI, reconciliation, or merge evidence exists.

For every non-trivial task PR that created a work checkpoint, after the task PR is merged and the resulting `main` commit is verified:

1. use the verified pre-merge checkpoint as `source_checkpoint`;
2. use the exact pre-merge `main` commit as `base_commit`;
3. use the exact merged `main` commit as post-merge `subject_commit`;
4. record final merge, CI, diff-review, risk, unfinished-work, and next-action evidence in a bounded post-merge receipt;
5. create the post-merge checkpoint only through `scripts/context/post_merge_checkpoint.py`;
6. publish that one checkpoint in a focused checkpoint-only follow-up PR against `main`;
7. run the protected repository baseline on the checkpoint follow-up before merge.

The post-merge tool verifies that the merge subject is reachable from current `main`, the base is its ancestor, the source checkpoint is valid and belongs to the same PR, and `changed_paths` exactly matches the Git-derived `base..merge` diff excluding `context/checkpoints/` transport files.

A checkpoint-only follow-up PR exists only to transport already-derived historical evidence into current Git. It does **not** create another post-merge checkpoint for itself. This explicit non-recursion rule prevents an infinite checkpoint-PR chain. Any substantive code, architecture, security, or governance change added to that follow-up makes the exemption invalid and requires the normal lifecycle.

## Coherent-change PR rule

The engineering unit is a **coherent reviewed change**, not a conversation prompt.

- One PR MUST represent one coherent atomic engineering/change objective.
- A user prompt may require one PR or multiple sequential PRs only when it contains materially independent engineering changes that should not share review/rollback risk.
- Multiple user messages may continue the same active PR when they refine the same coherent objective.
- Do not split one coherent fix merely because a conversation turn ended.
- Do not combine unrelated architecture, refactoring, feature, or cleanup work merely because it arrived in one prompt.
- An agent/task stream normally has at most one active task PR at a time. Finish or explicitly abandon the current task PR before starting an unrelated one.
- If post-merge verification finds a material defect introduced by the merged change, create a focused follow-up PR when needed. Conversation-turn boundaries MUST NOT delay a required security/correctness repair.

The user may explicitly require a single PR for a bounded task. When that instruction is compatible with coherent-change review, all verification-driven fixes for that task stay in that PR before merge.

## Review requirements

Before merge, reviewers/agents MUST:

- inspect the PR diff against the latest `main` and account for any base movement;
- review `current-only-documentation-policy.md` and current Decision Register when ADR/documentation scope changes;
- verify stable ADR identifiers are not renumbered/reused and superseded provenance remains resolvable;
- verify that normalized decision records do not remove a still-current invariant, contract, security requirement, SLO, failure semantic, migration rule, or operational requirement;
- verify exact version/security changes are reflected in Technology Baseline and compatibility documentation where applicable;
- report checks as passed, failed, not run, unavailable, or not applicable;
- distinguish an empty/unconfigured CI/status result from a passing check set; absence of reported checks MUST NOT be described as green CI;
- avoid merging when a known Critical/High security issue, unresolved current-state contradiction, merge conflict, or required verification blocker remains.

## Scope discipline

One pull request should represent one coherent task. Unrelated refactoring or documentation cleanup must not be silently absorbed.

A follow-up PR is not a failure of process when it is the smallest safe unit for a material defect discovered after merge. The defect and relationship to the original change must be explicit.
