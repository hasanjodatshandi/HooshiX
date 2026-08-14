# Repository Change Workflow

This document defines the mandatory change-delivery workflow for this repository.

## Pull-request-first rule

All repository changes use a dedicated branch and a pull request targeting `main`.

Required sequence:

1. create a task-specific branch from the current `main`;
2. open a Draft pull request as early as GitHub permits;
3. place all task changes on the pull-request branch, never directly on `main`;
4. compare and review the complete pull-request diff against the latest `main`;
5. run and record every applicable documentation, security, compatibility, build, test, and policy check;
6. resolve material findings before merge;
7. if a final review/check uncovers and fixes another issue, re-review the affected file plus all directly dependent canonical/index/policy documents before merge; do not pretend the earlier review covered the new edit;
8. mark the pull request ready only when the reviewed scope is complete;
9. merge into `main` only after the required review and verification pass;
10. verify the resulting `main` merge/head SHA and final repository state.

GitHub does not permit opening a pull request when the head branch has no commits different from the base branch. When a new task begins, the smallest legitimate governance/task-scaffolding commit may be created first solely to establish the Draft PR; all substantive task changes then remain inside that PR.

Direct commits to `main` are prohibited for normal agent-driven work. Emergency exceptions require an explicit user instruction describing the emergency and must be documented after the fact in a follow-up pull request.

## One-pull-request-per-prompt rule

Normal agent-driven work may create at most one pull request for one user prompt.

- Accumulate all coherent task changes and verification-driven fixes required to complete that prompt in the same task PR before it is marked ready.
- When a PR is created for the prompt, review and merge it into `main` before completing the prompt, then verify the resulting `main` head and confirm that no task PR from that prompt remains open.
- Do not create a second PR in the same prompt.
- Do not begin a separate scope while the current prompt's PR remains open.
- If new work is discovered only after the prompt's single PR has already merged and that work requires another PR, report it as remaining work for the next user prompt instead of opening another PR in the current prompt.

This rule does not authorize mixing unrelated work into one PR. Keep the prompt scope coherent and continue to report unrelated findings separately unless they block the scoped task before merge.

## Review requirements

Before merge, reviewers/agents MUST:

- inspect the PR diff against the latest `main` and account for any base-branch movement;
- review `current-only-documentation-policy.md` and the current Decision Register when ADR/documentation scope changes;
- verify that deleted/normalized decision records do not remove a still-current invariant, contract, security requirement, SLO, failure semantic, migration rule, or operational requirement;
- verify that exact version/security changes are reflected in the Technology Baseline and compatibility documentation where applicable;
- report checks that passed, failed, were not run, or were unavailable;
- distinguish an empty/unconfigured CI/status result from a passing check set; absence of reported checks MUST be stated explicitly and MUST NOT be described as green CI;
- avoid merging when a known Critical/High security issue, unresolved current-state contradiction, merge conflict, or required verification blocker remains.

## Scope discipline

One pull request should represent one coherent task. Unrelated refactoring or documentation cleanup must not be silently absorbed into the task branch.
