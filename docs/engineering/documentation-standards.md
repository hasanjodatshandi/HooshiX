# Documentation Standards and Authority

This document defines how repository documentation remains current, non-duplicative, and agent-usable. It complements `current-only-documentation-policy.md`.

## 1. Normative language

- **MUST / MUST NOT**: mandatory rule; automate enforcement where reliable.
- **SHOULD / SHOULD NOT**: default; deviation requires a documented reason.
- **MAY**: optional.

A normative rule has one authoritative home. Other documents link to it and include only the context required to understand a decision. Repeated normative text that can drift is a documentation defect.

## 2. Authority order

When documents overlap, use this order unless an explicit current decision says otherwise:

1. legal/security/privacy obligations and approved security controls;
2. current retained ADRs and Decision Register;
3. current-state architecture/service documents;
4. engineering/security/operations standards;
5. technology baselines and compatibility matrices for exact approved versions;
6. governance/checklists/matrices;
7. runbooks/playbooks;
8. examples/templates/reference configuration.

A lower level may make a higher-level rule more specific but MUST NOT contradict or weaken it.

## 3. Document classes

| Class | Purpose |
| --- | --- |
| ADR | current durable material decision and its enforceable consequences |
| Architecture | current capabilities, boundaries, ownership and topology |
| Standard | normative implementation/verification rules |
| Governance | ownership, evidence, quality/security mapping and traceability |
| Operations/Runbook | repeatable deployment, incident, recovery and production obligations |
| Baseline/Matrix | approved version or compatibility authority |
| Template/Example | non-authoritative scaffolding; cannot weaken a standard |

## 4. Required document quality

Authoritative documents SHOULD state purpose/scope, owner or responsible role, decisions/rules, verification/evidence expectations, and review/change triggers when useful.

For humans and agents:

- headings are stable and descriptive;
- tables use explicit column names;
- identifiers are unique and durable when cross-document traceability needs them;
- diagrams never replace textual rules;
- unknown production values are marked `Not verified`/`TBD` with an owner/gate rather than guessed;
- screenshots are never the sole location of an instruction;
- relative repository links are preferred for internal documents.

## 5. Current-only maintenance

When a decision changes:

1. identify every authoritative location and inbound reference;
2. preserve every still-current invariant/security/SLO/contract/operational rule;
3. update the single authoritative rule;
4. remove or normalize obsolete duplicate/predecessor text under current-only policy;
5. update Decision Register, source map, task matrix, machine-readable registries, baselines, and verification references as applicable;
6. validate that no deleted document is still referenced;
7. review the complete PR diff against current `main`.

## 6. Documentation fitness checks

Repository automation SHOULD check, where practical:

- broken relative links and missing referenced files;
- references to deleted/non-current ADRs;
- duplicate/conflicting normative rules in known canonical areas;
- terminology drift for canonical concepts;
- stale version pins outside baseline/lock authority;
- Decision Register/source-map/task-matrix coverage;
- machine-readable schema/render consistency;
- files added/removed without source/index maintenance when required.

A documentation check is evidence only when it actually executes and passes in CI; this file alone is not proof of compliance.
