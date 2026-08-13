# Current-Only Documentation Policy

- **Effective:** 2026-08-13
- **Status:** Active owner directive
- **Scope:** Architecture documentation and ADR history in this repository

## Rule

Until the repository owner explicitly withdraws this directive, the documentation set is **current-only**.

The repository keeps the currently effective architecture, engineering rules, technology baseline, and decision records required to implement and review the system. Superseded decision history, obsolete alternatives, and raw historical source material are not retained merely for chronology.

This active owner directive overrides older repository wording that required immutable preservation of accepted ADR history. The PR-first repository-change workflow, review requirements, security checks, compatibility checks, and evidence requirements remain unchanged.

## ADR behavior

- `docs/adr/decision-register.md` indexes only decisions that still contain current effective scope.
- An ADR that is completely superseded is deleted after all current semantics are represented by a retained ADR or current-state document.
- If only part of an ADR is obsolete, the ADR is normalized in place so it contains only current effective scope; obsolete alternatives and supersession narrative are removed.
- New architecture decisions may still be recorded as ADRs when useful, but they describe the resulting current decision rather than preserving a chain of obsolete alternatives.
- Current-state architecture documents remain the implementation-facing source of truth and MUST be updated with any decision change.

## Safety rule for deletion

Historical text may be deleted only after verifying that no still-current invariant, contract, security requirement, SLO, failure semantic, migration rule, or operational requirement would be lost. When current semantics exist only in an old record, migrate them to the appropriate current document before deleting that record.

## Precedence

For documentation-history handling, this file is the active owner directive. For architecture semantics, use the Decision Register plus the applicable current-state documents and retained ADRs. If a contradiction remains unresolved, do not guess; correct the documentation in the same PR before implementation depends on it.
