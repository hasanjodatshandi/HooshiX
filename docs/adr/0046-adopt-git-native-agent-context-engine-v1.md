# ADR-0046: Git-Native Agent Context Engine v1

## Status

Accepted — current effective decision

## Date

2026-08-17

## Context

AI-assisted engineering loses useful project context when work moves between chats, clients, IDEs, or model providers. Re-reading the complete repository for every narrow task wastes time and tokens, while trusting chat memory or a stale summary can be more dangerous than having no memory because it can silently contradict current Git state.

HooshiX already treats the repository as the source of truth, uses current-only documentation, records stable ADRs, routes architecture reading through `SOURCES.md` and `TASK-REVIEW-MATRIX.md`, and records repository implementation state separately from architecture targets. The missing capability is a reproducible, machine-checkable way to compile those sources into current task context and historical work checkpoints without creating another architecture authority.

## Decision

HooshiX adopts a local **Git-native Agent Context Engine** as repository/developer tooling. It is not an application bounded context, production microservice, datastore, control plane, or request-path dependency.

The v1 control order is:

```text
current Git repository authority
-> verified bootstrap
-> machine-readable task routing
-> task context compilation / Git-aware retrieval
-> non-authoritative work/post-merge checkpoints
-> read-only MCP adapter
```

A central cross-project or cross-user memory service is deliberately deferred. It requires separate evidence and a new reviewed decision.

## 1. Authority and precedence

The Context Engine never replaces repository authority. Its precedence is:

```text
current effective Git authority
> derived context compiled from that Git state
> commit-bound historical checkpoint
> external/chat/model memory
```

When derived or remembered information conflicts with current effective repository authority, the repository wins.

`AGENTS.md`, current ADRs/Decision Register, current architecture, engineering/security/operations standards, Technology Baseline, and other documents retain their existing authority order. The Context Engine stores pointers and provenance rather than duplicating normative rules.

A checkpoint is historical evidence only. It cannot make a superseded decision current, prove implementation that is absent, or authorize behavior that current Git prohibits.

## 2. Verified project bootstrap

The tracked bootstrap contract is `context/bootstrap.json`. It contains stable project identity, authority paths, context-routing paths, verification policy, and bounded retrieval policy. It does **not** contain a committed `HEAD` value because committing that value would make the file self-stale.

At execution time the engine derives the live repository revision and reports at least:

- repository root, branch, and exact `HEAD` commit;
- current dirty paths;
- configured authority path status;
- committed blob SHA and current worktree SHA where applicable;
- configuration/schema/route/checkpoint verification result;
- whether the bootstrap is safe to use as evidence for `targeted` review.

A verified bootstrap may resolve ordinary **context uncertainty**. It does not override a real `full-read` trigger from `AGENTS.md` or the task router.

If a required authority/configuration path is missing, invalid, untracked, or modified in the worktree, the engine MUST NOT claim that current targeted-review context is verified. It reports the reason and requires broader/manual review as applicable.

Dirty non-authority implementation files are reported but do not alone invalidate the authority bootstrap.

## 3. Machine-readable task routing

`context/routes.json` is the canonical machine-readable task-routing registry. `docs/architecture/TASK-REVIEW-MATRIX.md` is its generated/check human-readable view and MUST NOT become an independent source of truth.

The registry contains:

- globally required current sources;
- named task/change-area routes;
- bounded keyword/phrase hints for deterministic routing;
- exact minimum repository source paths;
- route-specific notes;
- explicit `full-read` routes and global escalation triggers.

Routing is conservative:

- an explicit route ID may select a known route but cannot bypass a matching global full-read trigger;
- ambiguous, tied, or unmatched automatic routing does not invent a scope and escalates to `full-read`;
- any route marked `full-read` remains full-read;
- current-source disagreement remains a full-read/escalation condition;
- a targeted route never permits skipping implementation/diff/evidence inspection required by current policy.

The router is an aid to select current sources. It is not architectural decision authority.

## 4. Work and post-merge checkpoints

`context/checkpoint.schema.json` defines append-only, commit-bound checkpoint records under `context/checkpoints/`.

A normal work checkpoint is created from an explicit bounded receipt plus Git state. The tool derives branch, subject commit, base commit, and changed paths rather than asking an agent to restate them as unverifiable prose. A work checkpoint records a coherent implementation/review state and remains immutable historical evidence even if later CI, reconciliation, or merge work completes its recorded `next_actions`.

A post-merge checkpoint closes that continuity gap without rewriting the work checkpoint. It has `checkpoint_kind=post-merge`, references the earlier work record through `source_checkpoint`, carries the same pull-request identity, and binds final evidence to the exact merged `main` commit.

Post-merge creation derives and verifies:

- `base_commit` as the exact pre-merge `main` commit supplied to the tool;
- `subject_commit` as the exact merged `main` commit;
- the base is an ancestor of the merge subject;
- the merge subject is reachable from current `main`;
- `source_checkpoint` is a valid non-post-merge checkpoint for the same pull request;
- `changed_paths` exactly matches the Git-derived `base..subject` diff, excluding `context/checkpoints/` transport files.

Post-merge evidence records final merge/CI/diff-review outcomes, remaining risks, unfinished work, and real next actions. Completed pre-merge actions are not carried forward merely because they existed in the source checkpoint.

For a non-trivial task PR with a work checkpoint, the post-merge record is transported through a focused checkpoint-only follow-up PR after the task merge is verified. That checkpoint-only follow-up does not recursively require another post-merge checkpoint. If substantive implementation, architecture, security, or governance work is added to the follow-up, the exemption no longer applies.

Checkpoint records use bounded fields such as objective, scope, completed work, current decisions with authority references, changed paths, verification outcomes, risks, unfinished work, and next actions.

Mandatory rules:

- checkpoint `subject_commit` must be an explicit full Git commit revision;
- authority references must be repository paths that exist for the recorded work;
- secret/private-key/token/credential material is prohibited;
- checkpoints do not copy large source/document bodies;
- a later checkpoint does not mutate an older checkpoint into different historical evidence;
- `latest checkpoint` means newest valid checkpoint record, not current architecture authority;
- context consumers compare checkpoint revision with current `HEAD` and inspect intervening Git changes before relying on it for continuity;
- legacy work checkpoints without `checkpoint_kind` remain valid as v1 historical records;
- post-merge records require explicit source-checkpoint and pull-request linkage.

The Context Engine does not claim that schema validation detects every possible secret. Current Gitleaks/secret-handling policy remains authoritative.

## 5. Git-aware retrieval v1

V1 retrieval is local, deterministic, and rebuildable from Git. It uses tracked-file metadata plus bounded lexical/path search. It does not require a vector database, embedding service, hosted index, or remote model call.

Search results include source provenance:

- exact repository `HEAD`;
- repository-relative path;
- line range/excerpt;
- committed blob SHA when present;
- current worktree content SHA;
- source state/classification.

Retrieval reads only repository-tracked files and applies bounded query/result/excerpt/file-size limits. Binary and configured sensitive-file patterns are excluded. User input never becomes a shell command or arbitrary filesystem path.

Semantic/vector retrieval may be added later only when measured retrieval failures justify the extra dependency, indexing lifecycle, security/privacy surface, and operational cost. Any derived index remains rebuildable and non-authoritative.

## 6. MCP adapter

The engine exposes a read-only MCP stdio adapter for interoperability with AI clients. The adapter is developer tooling only.

V1 properties:

- stdio transport only; no HTTP/network listener;
- current MCP `2026-07-28` support plus bounded legacy `2025-11-25` stdio compatibility for clients that still use the initialize handshake;
- read-only tools for bootstrap, task context, search, latest checkpoint, and changed-context inspection;
- no file write, Git mutation, command execution, credential access, deployment, network fetch, or checkpoint-create MCP tool;
- fixed tool list and deterministic schemas/order;
- bounded request/query/result sizes;
- protocol diagnostics go to stderr only; stdout remains protocol data only.

MCP client/server self-reported identity is informational and never security authority.

Checkpoint creation remains an explicit local CLI/repository workflow action. An AI connected through MCP cannot mutate repository memory through the read-only adapter.

## 7. Security and prompt-injection boundary

Repository content returned by search is **data**, not automatically agent instruction. Provenance/classification distinguishes agent policy/current repository policy documents, operational guidance, historical checkpoints, and source content.

The engine MUST NOT treat arbitrary comments, fixture text, issue-like prose, generated content, or retrieved source snippets as higher-priority instructions than configured repository authority.

Security controls include:

- repository-root confinement;
- tracked-file-only retrieval;
- sensitive filename/path exclusion;
- no `shell=True` or caller-constructed command strings;
- fixed Git subcommands/argument arrays;
- read-only MCP surface;
- no network listener in v1;
- bounded input/output and file sizes;
- commit/blob/worktree provenance on derived context;
- fail-safe targeted-review trust when configured authority inputs are dirty or invalid;
- post-merge source-path confinement, same-PR linkage, main-reachability validation, and Git-derived diff verification.

A Context Engine defect cannot authorize weaker authentication, Authorization, tenant isolation, secrets handling, supply-chain policy, production access, or any other product/runtime security control.

## 8. CI and governance

Repository baseline verification covers at least:

- bootstrap/routes/checkpoint schema files are valid JSON and current supported versions match tooling;
- configured required authority/source paths exist;
- route identifiers are unique and route references resolve;
- global full-read triggers are non-empty;
- generated `TASK-REVIEW-MATRIX.md` exactly matches canonical `context/routes.json`;
- checkpoint fixtures/current records validate and contain required commit/provenance shape;
- post-merge records reference valid same-PR work checkpoints and recompute to the recorded `base..merge` changed paths;
- post-merge subjects are reachable from current `main` and bases are ancestors of their subjects;
- targeted routing escalates on unknown/ambiguous/full-read-trigger input;
- dirty authority state cannot be reported as verified targeted-review context;
- search bounds/sensitive-file exclusions/provenance behavior;
- MCP modern discovery/tools and legacy initialize compatibility;
- MCP exposes no write/mutation tool.

Repository structure CI fetches the Git history needed to re-verify post-merge main provenance. Context-engine checks are part of `make baseline-verify`. Documentation alone is not implementation evidence.

## 9. Cross-agent memory service trigger

V1 does **not** create a central database/service for long-lived semantic memory.

A separate memory service may be proposed only when evidence shows a need that Git-native bootstrap/routing/checkpoints/retrieval cannot reasonably solve, such as material use across multiple repositories, multiple developers with shared non-Git intent state, or multiple independent agents that require durable cross-project memory.

Before such a service exists, a new ADR must define at least authority/ownership, authentication/authorization, tenant/project isolation, privacy/PII classification, retention/erasure, encryption/key custody, provenance/staleness, conflict resolution, backup/recovery, availability, cost, and failure behavior. The service can never silently outrank current project Git authority.

## Verification requirements

Executable evidence must prove the CI/governance requirements above, plus one end-to-end example in which a clean current repository bootstrap selects a targeted route, returns commit/blob provenance, and serves the same information through MCP without repository mutation.

Checkpoint evidence must also prove a work checkpoint can remain immutable while a later post-merge checkpoint references it, binds to an exact reachable `main` merge commit, derives the exact non-checkpoint changed paths from Git, and rejects source-PR mismatch, non-main subjects, tampered changed paths, path traversal, and shell-like revision input.

Negative evidence must prove unknown/ambiguous tasks, dirty authority files, malformed config/checkpoints, sensitive-file search attempts, oversized MCP input, unsupported protocol version, and unknown/write-like MCP tool requests fail safely.

## Rollback considerations

Rollback may remove the Context Engine only if repository/agent workflow returns to the previous mandatory reading rules without losing authoritative architecture or historical Git evidence.

Rollback MUST NOT leave `TASK-REVIEW-MATRIX.md` claiming machine authority after its canonical registry is removed, preserve stale generated context as authority, make checkpoints normative, rewrite an older work checkpoint to imitate post-merge evidence, expose a write-capable/unbounded/network MCP endpoint, or introduce a central memory service without the trigger/review above.
