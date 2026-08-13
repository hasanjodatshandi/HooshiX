# ADR-0026: Online Authorization Runtime, Capacity, and Overload Protection v1

## Status

Accepted — current effective decision

## Date

2026-08-11; consolidated to current-only documentation on 2026-08-13

## Decision

ADR-0013 defines the one authoritative online `CheckPermission` model. This ADR defines its current production SLO, capacity, deployment, pre-check, overload, and caller-breaker requirements. ADR-0032 owns current SLI/burn alerting and breaker-opening criteria; ADR-0036 owns current de-correlated OPEN/HALF_OPEN recovery behavior.

### Request contract

```text
availability:              >=99.95% rolling 30d
p95 server latency:        <=100 ms
p99 server latency:        <=200 ms
steady engineering target: p95<=75 ms / p99<=150 ms
caller hard deadline:      300 ms
attempts:                  1
wait-for-ready:            off
retry:                     none
permission-result cache:   none
stale fallback:            none
failure mode:              fail closed
```

Authoritative deny returns `PERMISSION_DENIED`. Infrastructure/dependency failure returns `UNAVAILABLE / AUTHORIZATION_UNAVAILABLE`. Healthy saturation returns `RESOURCE_EXHAUSTED / AUTHORIZATION_OVERLOADED` and is still treated by callers as fail-closed dependency unavailability rather than permission denial.

### Safe caller pre-checks

Before invoking Authorization, a resource-owning service may reject locally when access-token signature/algorithm/key, issuer/audience/time claims, required subject/tenant/membership/session shape, obvious tenant-context shape, permission-key syntax, or resource-identifier syntax is invalid.

These checks may reject invalid traffic but MUST NOT grant access. The authoritative protected-operation decision remains one online `CheckPermission`.

No Bloom filter, local permission cache, signed permission list, negative permission-result cache, Kafka invalidation path, or stale allow state is authoritative in v1.

### Server overload isolation

Authorization enforces:

- global bounded in-flight `CheckPermission` concurrency;
- per-caller-workload-principal fair-share concurrency;
- no unbounded request/application queue;
- <=25 ms server-side queue wait before shedding;
- dedicated bounded PostgreSQL pool and bounded SQL;
- fair load shedding with stable overload status;
- resource limits and autoscaling that respect the global PostgreSQL connection budget.

### Caller circuit breaker

Resource services apply the current ADR-0025/ADR-0036 fail-closed Authorization breaker.

Infrastructure/dependency failures—including timeout/unavailable/transport failure and explicit Authorization overload—count toward breaker failure. Authoritative deny and expected contract errors do not.

OPEN returns `AUTHORIZATION_UNAVAILABLE` immediately and never serves stale/cached allow state. HALF_OPEN uses real `CheckPermission` probes under the same one-attempt 300 ms contract. Breaker reopen timing, de-correlation, and closing criteria follow ADR-0036.

### Production deployment baseline

```text
minimum replicas: 3
PDB:              minAvailable=2
topology spread:  required across available failure domains
HPA initial min:  3
HPA initial max:  12
DB acquire budget <=50 ms per request path
permission query budget <=100 ms
```

HPA/pool maxima are included in the global PostgreSQL connection budget. Autoscaling does not permit unbounded concurrency or pool growth. Readiness reflects ability to safely serve permission checks; liveness does not flap merely because PostgreSQL is temporarily degraded.

### Capacity and launch gate

Before production, prove >=2x projected peak while meeting p95<=100ms and p99<=200ms with:

- >=30% CPU/memory/database headroom at the validated target;
- Hikari acquisition p99 <25ms under steady target load;
- no sustained application/request/pool queue growth;
- fair-share isolation between caller principals;
- one Authorization replica/node loss;
- PostgreSQL planned/unplanned failover under sustained permission traffic;
- abusive invalid-token traffic rejected before Authorization;
- abusive valid-token traffic bounded by caller/server bulkheads without stale allow or retry amplification;
- pool/HPA limits proven safe against the database connection budget.

## Observability and alerting

Track request outcome/latency, saturation/load-shed counts, queue wait, per-principal fair-share pressure without high-cardinality identity labels, breaker state/transitions, database pool acquisition, SQL latency, replica availability, and SLO burn.

Paging uses the paired-window burn policy defined by ADR-0032 rather than isolated percentile samples. ADR-0036 governs breaker recovery de-correlation and serialized real HALF_OPEN probes, not SLO burn thresholds.

## Verification requirements

Tests cover safe pre-check rejection, exactly one authoritative permission call, no cache/Bloom/stale fallback, fair-share isolation, bounded queue/bulkhead shedding, overload/error mapping, breaker OPEN/HALF_OPEN recovery, SQL/pool budgets, HPA/pool connection-budget constraints, replica loss, PostgreSQL failover, invalid/valid abuse traffic, and >=2x peak latency/error-budget behavior.

## Rollback considerations

A rollback MUST NOT reintroduce permission caching, Kafka invalidation, retries, stale allow fallback, lower replica safety, unbounded queues, or pool/HPA limits that violate the global database budget. SLO/capacity changes require new load evidence before becoming release authority.