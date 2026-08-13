# ADR-0036: Authorization Breaker Recovery and Dependency-Policy Governance v1

## Status

Accepted — current effective decision

## Date

2026-08-11; normalized to current-only documentation on 2026-08-13

## Decision

### Breaker scope

An Authorization client breaker is scoped to the caller workload/service and the `CheckPermission` dependency. It is not scoped by tenant tier, subscription plan, or commercial priority. Commercial tier MUST NOT alter the security meaning of an unavailable authorization decision.

Tenant/workload fairness belongs in bounded server/client concurrency and quota policy, not in breaker recovery semantics.

### De-correlated OPEN duration

Repeated recovery attempts use bounded per-breaker backoff:

```text
base open duration = min(30s, 2s * 2^reopen_streak)
actual open duration = random value in [0.5 * base, base]
```

- `reopen_streak` starts at 0;
- failed HALF_OPEN recovery increments it;
- >=60 seconds of continuously healthy CLOSED operation resets it to 0;
- randomization is independent per caller instance/breaker;
- OPEN always fails closed with `AUTHORIZATION_UNAVAILABLE`.

### Serialized HALF_OPEN probes

Recovery uses real incoming `CheckPermission` operations under the unchanged one-attempt/300ms/no-cache/no-retry contract:

- at most one half-open probe is in flight per breaker instance;
- three consecutive infrastructure-successful probes close;
- authoritative allow or deny counts as infrastructure success;
- timeout, `UNAVAILABLE`, transport failure, or `RESOURCE_EXHAUSTED / AUTHORIZATION_OVERLOADED` reopens immediately;
- non-probe requests while the probe slot is occupied fail fast through `AUTHORIZATION_UNAVAILABLE`;
- no synthetic health probe and no automatic retry.

A separate readiness/health endpoint never authorizes breaker closure.

### Canonical dependency registry

The canonical operation/dependency policy source is:

```text
docs/architecture/dependency-criticality.yaml
```

`dependency-criticality-matrix.md` is the generated human-readable view.

Each production synchronous edge records stable operation/dependency identity, caller owner, dependency class, failure action, retry owner, fallback, policy owner, and non-empty `policy_refs`.

`policy_refs` may reference retained current ADRs and/or canonical current documents. They MUST point to the real current authority and MUST NOT point to deleted history or an unrelated ADR merely to satisfy schema validation.

### Composition

Each operation/dependency edge is evaluated independently:

- `AUTHORITATIVE_SECURITY`/`AUTHORITATIVE_STATE` failure blocks according to the registered action;
- `OPTIONAL_READ` degrades only through an explicit bounded fallback;
- `DURABLE_COMMAND` defers remote execution only after required local intent is durably committed;
- `EXTERNAL_SIDE_EFFECT` preserves ambiguous outcomes and never fabricates success;
- authoritative security checks SHOULD run before optional remote enrichment when practical.

An optional dependency never makes a mandatory dependency optional. Missing fallback means no fallback.

### Registry governance

Caller/bounded-context owner owns each edge; Platform Architecture owns schema/classes; Security co-reviews `AUTHORITATIVE_SECURITY` changes.

CI MUST validate schema/enums, reject duplicate edges/missing required fields/orphans, validate current `policy_refs`, regenerate/check the Markdown view, require coverage for production synchronous edges represented by current service architecture/contracts, and require architecture/security review when an authoritative edge becomes degradable or gains fallback.

## Verification requirements

Verify de-correlated multi-replica recovery, bounded reopen escalation/reset, one in-flight HALF_OPEN probe, three real successes to close, immediate reopen on infrastructure failure/overload, tenant-tier independence, health-endpoint non-authority, registry schema/duplicate/orphan/policy-ref/render/coverage checks, and composite-edge behavior.

## Rollback considerations

Rollback MUST NOT restore synchronized fixed recovery, concurrent half-open probe bursts, tenant-tier-dependent authorization availability, synthetic health-probe recovery, unchecked Markdown authority, forced ADR-only policy references, implicit fallback, or removal of fail-closed semantics.