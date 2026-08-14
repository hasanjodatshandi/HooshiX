# ADR-0026: Online Authorization Runtime, Capacity, and Overload Protection v1

## Status

Accepted — current effective decision

## Date

2026-08-11; consolidated to current-only documentation and made profile-aware on 2026-08-14

## Decision

ADR-0013 defines the one authoritative online `CheckPermission` model. This ADR defines its current production SLO, capacity, deployment, pre-check, overload, and caller-breaker requirements. ADR-0032 owns current SLI/burn alerting and breaker-opening criteria; ADR-0036 owns current de-correlated OPEN/HALF_OPEN recovery behavior.

### Request contract

```text
availability objective:     >=99.95% rolling 30d
p95 server latency:         <=100 ms
p99 server latency:         <=200 ms
steady engineering target:  p95<=75 ms / p99<=150 ms
caller hard deadline:       300 ms
attempts:                   1
wait-for-ready:             off
retry:                      none
permission-result cache:    none
stale fallback:             none
failure mode:               fail closed
```

The availability objective is measured as real service availability. `production-single-server` does not claim node/PostgreSQL failover merely because this objective exists; real host maintenance/outage remains in SLI accounting under ADR-0005.

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
- dedicated bounded PostgreSQL connection allocation/pool for Authorization within the selected profile's database budget;
- bounded SQL;
- fair load shedding with stable overload status;
- finite resource limits and scaling configuration appropriate to the selected profile.

### Caller circuit breaker

Resource services apply the current ADR-0025/ADR-0036 fail-closed Authorization breaker.

Infrastructure/dependency failures—including timeout/unavailable/transport failure and explicit Authorization overload—count toward breaker failure. Authoritative deny and expected contract errors do not.

OPEN returns `AUTHORIZATION_UNAVAILABLE` immediately and never serves stale/cached allow state. HALF_OPEN uses real `CheckPermission` probes under the same one-attempt 300 ms contract. Breaker reopen timing, de-correlation, and closing criteria follow ADR-0036.

### Production deployment by profile

`production-single-server` under ADR-0042:

```text
replicas:          1
HPA:               disabled
availability PDB:  disabled
node failover:     none
DB acquire budget: <=50 ms per request path
permission query:  <=100 ms
```

Authorization uses its distinct database/runtime/migration roles inside the shared physical PostgreSQL instance. Its pool ceiling participates in the global shared-instance <=70% application-connection budget. One replica and one PostgreSQL instance are an explicit availability reduction only; they do not change the one-attempt/fail-closed permission contract, bounded queues, fair-share isolation, SQL limits, or security semantics.

`production-ha`:

```text
minimum replicas: 3
PDB:               minAvailable=2
topology spread:   required across available failure domains
HPA initial min:   3
HPA initial max:   12
DB acquire budget: <=50 ms per request path
permission query:  <=100 ms
```

HA HPA/pool maxima remain included in the dedicated Authorization PostgreSQL connection budget. Autoscaling does not permit unbounded concurrency or pool growth.

In both profiles, readiness reflects ability to safely serve permission checks; liveness does not flap merely because PostgreSQL is temporarily degraded.

### Capacity and launch gate

Before production, prove >=2x projected peak while meeting p95<=100ms and p99<=200ms with:

- >=30% CPU/memory/database headroom at the validated target;
- Hikari acquisition p99 <25ms under steady target load;
- no sustained application/request/pool queue growth;
- fair-share isolation between caller principals;
- abusive invalid-token traffic rejected before Authorization;
- abusive valid-token traffic bounded by caller/server bulkheads without stale allow or retry amplification;
- selected-profile pool limits proven safe against the applicable database connection budget.

`production-single-server` additionally proves complete-stack contention with the other same-host platform workloads, Authorization/PostgreSQL process or whole-host loss produces fail-closed unavailability rather than stale ALLOW, and restart/recovery restores correct policy/data state. No replica/failover success is claimed.

`production-ha` additionally proves one Authorization replica/node loss and PostgreSQL planned/unplanned failover under sustained permission traffic.

## Observability and alerting

Track request outcome/latency, saturation/load-shed counts, queue wait, per-principal fair-share pressure without high-cardinality identity labels, breaker state/transitions, database pool acquisition, SQL latency, selected-profile replica/database availability, and SLO burn.

Paging uses the paired-window burn policy defined by ADR-0032 rather than isolated percentile samples. ADR-0036 governs breaker recovery de-correlation and serialized real HALF_OPEN probes, not SLO burn thresholds.

## Verification requirements

Both profiles test safe pre-check rejection, exactly one authoritative permission call, no cache/Bloom/stale fallback, fair-share isolation, bounded queue/bulkhead shedding, overload/error mapping, breaker OPEN/HALF_OPEN recovery, SQL/pool budgets, invalid/valid abuse traffic, and >=2x peak latency/error-budget behavior.

`production-single-server` additionally tests one-replica render, HPA/PDB absence, shared-PostgreSQL role/RLS/pool isolation, whole-host/database outage fail-closed behavior, complete-stack headroom, and no false failover claim.

`production-ha` additionally tests HPA/pool connection-budget constraints, replica/node loss and PostgreSQL failover.

## Rollback considerations

A rollback MUST NOT reintroduce permission caching, Kafka invalidation, retries, stale allow fallback, unbounded queues, or pool/scaling limits that violate the selected-profile database budget. Moving to the single-server profile MUST NOT be represented as retaining HA replica/database failover. SLO/capacity changes require new load evidence before becoming release authority.
