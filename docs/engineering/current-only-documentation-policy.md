# Current-Only Documentation Policy

- **Effective:** 2026-08-13; ADR identifier-stability rule updated 2026-08-15
- **Status:** Active owner directive
- **Scope:** Architecture documentation and ADR maintenance in this repository

## Rule

The implementation-facing documentation set remains **current-only**. It keeps the currently effective architecture, engineering rules, technology baseline, runbooks, and decision content required to implement and review the system. Obsolete alternatives and superseded implementation guidance are not kept in current-state documents merely for chronology.

ADR identifiers are a separate provenance concern. Once an ADR ID has merged to `main`, that identifier is permanent and MUST NOT be renumbered, reassigned, or reused for a different decision.

## Current-state documents

Current-state architecture, service, engineering, technology, operations, and runbook documents describe the current effective system only.

When a decision changes:

- replace or remove obsolete implementation guidance from current-state documents;
- preserve every still-current invariant, contract, security requirement, SLO, failure semantic, migration rule, and operational requirement;
- update the applicable current ADR/Decision Register/source/task maps and executable evidence requirements in the same coherent change;
- do not keep a second implementation authority only to preserve narrative history.

## ADR behavior

- New ADR IDs are allocated monotonically. Gaps are permitted.
- A merged ADR filename and `ADR-00XX` identifier are immutable.
- A merged ADR ID is never reused, even if the decision is later fully superseded.
- A current ADR may be edited in place while it still owns current effective scope, but the identifier remains stable.
- When an ADR becomes fully superseded, retain the original file as a compact provenance record with `Status: Superseded by ADR-00XX` (or the exact current replacement location). Remove obsolete implementation-facing detail when useful, but keep enough identity/decision context to make old Git/PR/incident/audit references resolvable.
- A partially superseded ADR remains under its original ID and is normalized to the current retained scope, with an explicit pointer for any scope now owned elsewhere.
- `docs/adr/decision-register.md` separates current effective ADRs from superseded retained identifiers. Superseded ADRs are not current implementation authority.
- Do not create an old-to-new renumbering system. Stable original IDs are the compatibility mechanism.

## Safety rule

Historical/provenance reduction is allowed only after verifying that no current invariant, contract, security requirement, SLO, failure semantic, migration rule, operational requirement, or active evidence reference would be lost.

Deleting a merged ADR file or reusing its identifier is prohibited. Git history remains the detailed narrative history; the retained superseded ADR record exists so durable external references keep their meaning.

## Precedence

For documentation-history handling, this file is the active owner directive. For architecture semantics, use the current Decision Register plus applicable current-state documents and current effective ADRs.

If current sources conflict, do not infer intent from an obsolete version or silently guess. Correct the current authority in the same PR before implementation depends on it.