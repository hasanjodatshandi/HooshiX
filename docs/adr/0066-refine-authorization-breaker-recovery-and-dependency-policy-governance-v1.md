# ADR-0066: Refine Authorization Breaker Recovery and Dependency-Policy Governance v1

## Status

Accepted

## Date

2026-08-11

## Relationship to Earlier Decisions

This ADR extends ADR-0055, ADR-0056, ADR-0062, and ADR-0063. It does not weaken
ADR-0039's authoritative online `CheckPermission`, one-attempt, no-cache,
no-stale-fallback, and fail-closed semantics.

ADR-0062 remains authoritative for SLOs and burn-rate alerting. This ADR refines
only breaker de-correlation/half-open behavior and makes the dependency
criticality matrix machine-checkable.

## Decision

### Authorization breaker scope

An Authorization client breaker is scoped to the caller workload/service and the
`CheckPermission` dependency. It is **not** scoped by tenant tier, subscription
plan, or commercial priority.

Commercial tier must not alter the security meaning of an unavailable
authorization decision. Tenant/workload fairness belongs in bounded concurrency
and quota policy, not breaker recovery semantics.

### De-correlated open duration

The fixed `2s + <=1s` open interval from ADR-0062 is replaced by a bounded
per-breaker reopen backoff:

```text
base open duration = min(30s, 2s * 2^reopen_streak)
actual open duration = random value in [0.5 * base, base]
```

- `reopen_streak` starts at 0;
- any failed half-open recovery increments it;
- a continuously healthy CLOSED interval of at least 60 seconds resets it to 0;
- randomization is independent per caller instance/breaker;
- the caller continues to fail closed while OPEN.

This avoids synchronized recovery storms across replicas while bounding the
maximum outage-amplifying wait.

### Half-open probe serialization

Half-open recovery uses **real incoming `CheckPermission` calls** exactly as
ADR-0062 requires, but probes are serialized per caller breaker instance:

- at most one half-open probe is in flight per breaker instance;
- three consecutive infrastructure-successful probes close the breaker;
- authoritative allow **or deny** counts as infrastructure success;
- any timeout, `UNAVAILABLE`, transport failure, or
  `RESOURCE_EXHAUSTED / AUTHORIZATION_OVERLOADED` reopens immediately;
- non-probe requests while a half-open probe is in flight fail fast through the
  same `AUTHORIZATION_UNAVAILABLE` path;
- there is no synthetic health probe and no automatic retry.

No fixed probe cooldown is required. Serialization plus the de-correlated OPEN
interval is the anti-herd mechanism. A future cooldown may be introduced only
from measured recovery instability.

### Machine-readable dependency policy registry

The canonical operation/dependency policy is now:

```text
docs/architecture/dependency-criticality.yaml
```

`dependency-criticality-matrix.md` is the human-readable rendered view.

Each registry edge includes at least:

- stable `operation_id`;
- caller owner;
- dependency identifier;
- dependency class;
- failure action;
- retry owner;
- fallback policy;
- deadline/reference ADR where applicable;
- service/team owner.

### Multiple dependencies in one operation

Each operation/dependency edge is evaluated independently.

- Failure of `AUTHORITATIVE_SECURITY` or `AUTHORITATIVE_STATE` blocks the
  operation according to that edge's failure action.
- `OPTIONAL_READ` may degrade only when its edge defines an explicit bounded
  fallback.
- `DURABLE_COMMAND` may defer remote execution only after the owning service has
  durably committed the required local intent.
- `EXTERNAL_SIDE_EFFECT` preserves ambiguous outcome semantics and never
  fabricates success.
- When practical, authoritative security checks execute before optional remote
  enrichment so unauthorized requests do not consume optional downstream work
  or expose unnecessary data.

An optional dependency does not make a mandatory dependency optional.

### Matrix ownership and CI governance

The caller/bounded-context owner owns each edge. Platform Architecture owns the
schema and allowed classes. Security co-owns changes to
`AUTHORITATIVE_SECURITY` edges.

CI MUST:

1. validate the YAML schema and enum values;
2. reject duplicate `(operation_id, dependency_id)` edges;
3. reject missing owner/failure/fallback fields;
4. regenerate/check `dependency-criticality-matrix.md` from YAML;
5. require every production synchronous remote edge represented by service
   architecture/contract tests to have a registry entry;
6. fail when a removed/renamed operation leaves an orphan policy edge;
7. require architecture/security review when a change makes an authoritative
   edge degradable or adds a fallback.

The exact CI task name is repository-defined; architecture compliance must not
rely on a PR checkbox alone.

## Verification Requirements

- multiple caller replicas do not enter HALF_OPEN in a synchronized burst under
  deterministic outage/recovery tests;
- repeated failed recovery increases bounded open duration and healthy CLOSED
  operation resets it;
- only one half-open probe is in flight per breaker instance;
- three consecutive real contract successes close and any infrastructure
  failure reopens;
- tenant tier has no effect on Authorization breaker semantics;
- YAML schema/duplicate/orphan/render checks fail CI correctly;
- a composite operation with authoritative + optional dependencies fails only
  according to each registered edge and never silently invents fallback.

## Consequences

Breaker recovery is less likely to create a thundering herd and dependency
semantics become reviewable by both humans and CI. The platform avoids adding a
commercial-tier distinction to security availability semantics.

## Rollback Considerations

Rollback must not return to synchronized fixed recovery across replicas, remove
fail-closed behavior, or make the Markdown matrix an unchecked independent
source of truth.
