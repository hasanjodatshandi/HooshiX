# ADR-0063: Operation-Level Dependency Criticality and Degradation v1

## Status

Accepted — current effective decision

## Date

2026-08-11; normalized to current-only documentation on 2026-08-13

## Decision

Dependency criticality belongs to an **operation -> dependency edge**, not to a service name globally. A service can be authoritative for one operation, absent from another path, and explicitly degradable for a third.

The canonical machine-readable registry is:

```text
docs/architecture/dependency-criticality.yaml
```

`docs/architecture/dependency-criticality-matrix.md` is the generated human-readable view and MUST NOT become an independent source of truth.

Every production synchronous remote dependency adds or updates a registry edge before implementation becomes production-eligible.

### Dependency classes

1. **AUTHORITATIVE_SECURITY** — failure MUST NOT grant access or bypass a security rule; fail closed with the registered availability/security-dependency action.
2. **AUTHORITATIVE_STATE** — required source of truth; failure aborts/blocks the operation and never fabricates state.
3. **DURABLE_COMMAND** — remote work is represented by durable local intent; outage leaves that intent pending for its owning idempotent dispatcher/reconciler.
4. **EXTERNAL_SIDE_EFFECT** — external result can be ambiguous; unknown remains unknown, no blind retry/fabricated success.
5. **OPTIONAL_READ** — explicitly non-authoritative enrichment; degradation is allowed only through the exact bounded fallback registered by the owning context.
6. **OBSERVABILITY** — ordinary non-audit telemetry may use bounded buffering/drop and MUST NOT make the business request fail; required security/audit evidence follows its durable contract instead.

### Composition rules

Every edge keeps its own class and failure action when an operation has multiple dependencies.

- `AUTHORITATIVE_SECURITY` or `AUTHORITATIVE_STATE` failure blocks according to that edge.
- `OPTIONAL_READ` degrades only through its registered bounded fallback.
- `DURABLE_COMMAND` defers remote execution only after required local intent is durably committed.
- `EXTERNAL_SIDE_EFFECT` preserves ambiguous outcomes and reconciles them.
- Authoritative security checks SHOULD precede optional remote enrichment when practical.
- An optional dependency never makes a mandatory dependency optional.
- Missing fallback means **no fallback**.

### Ownership

The caller/bounded-context owner owns each operation edge. The provider owns its service SLO and stable error taxonomy. Platform Architecture owns the registry schema/classes. Security co-reviews `AUTHORITATIVE_SECURITY` changes.

Each edge records the applicable source/destination semantics, failure action, retry owner, fallback, owner, and current decision references. Deadline/retry/breaker/idempotency details remain in the owning contract/ADR/current architecture and are reflected in implementation tests.

### CI governance

CI MUST:

1. validate YAML against `dependency-criticality.schema.json` and allowed classes;
2. reject duplicate `(operation_id, dependency_id)` edges;
3. reject missing owner/failure/retry/fallback/current-decision fields;
4. regenerate/check `dependency-criticality-matrix.md` from YAML;
5. require every production synchronous remote edge represented by current service architecture/contracts to have a registry entry;
6. reject orphan edges after operation/dependency removal or rename;
7. require architecture/security review when an authoritative edge becomes degradable or gains a fallback.

The exact CI task name is repository-defined; compliance MUST NOT rely on a PR checkbox alone.

## Verification requirements

Architecture/contract/policy tests prove registry coverage, schema/duplicate/orphan/render failures, authoritative fail-closed behavior, durable-command pending/replay behavior, preservation of external-side-effect ambiguity, explicit optional degradation, composite-edge semantics, one retry owner, and absence of implicit fallback.

## Rollback considerations

Rollback MUST NOT make the Markdown matrix an independent authority, drop a required production edge, convert an authoritative dependency to degradable behavior, add fallback implicitly, or introduce duplicate retry ownership without a reviewed current decision.