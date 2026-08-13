# ADR-0056: Harden Online Authorization Overload Protection and SLO v1

## Status

Accepted

## Date

2026-08-11

## Supersedes

This ADR supersedes ADR-0042 only for its production latency SLO values and
extends its overload/failure-containment requirements. ADR-0039's one online
`CheckPermission`, no permission-result cache, no Kafka invalidation, no retry,
no stale fallback, and fail-closed semantics remain accepted.

## Decision

### Safe pre-checks before `CheckPermission`

A resource-owning service rejects requests locally before calling Authorization
when any of these fail:

- access-token signature/algorithm/key validation;
- issuer/audience/expiration validation;
- required subject, active-tenant, membership, or session claim shape;
- obvious tenant-context mismatch at the resource boundary;
- syntactically invalid permission key or resource identifier.

These checks may reject invalid traffic but never grant a protected operation.
The authoritative permission decision remains the one online
`CheckPermission`.

No Bloom filter, local permission cache, signed permission list, or negative
permission-result cache is introduced in v1. Probabilistic structures are not
allowed to make authoritative authorization decisions.

### Authorization overload isolation

Authorization enforces:

- global bounded in-flight `CheckPermission` concurrency;
- per-caller-workload-principal fair-share concurrency;
- no unbounded server request queue;
- a maximum server-side queue wait of 25 ms;
- a dedicated bounded database pool and bounded SQL;
- load shedding with `RESOURCE_EXHAUSTED / AUTHORIZATION_OVERLOADED` when the
  service is healthy but saturated.

Callers map overload/dependency failure to the existing fail-closed availability
path; they do not convert it to `PERMISSION_DENIED` and do not retry.

### Fail-closed caller circuit breaker

The resource service applies ADR-0055's circuit breaker to the Authorization
client. The breaker counts infrastructure/dependency failures such as
`UNAVAILABLE`, `DEADLINE_EXCEEDED`, equivalent transport failures, and explicit
`RESOURCE_EXHAUSTED / AUTHORIZATION_OVERLOADED` saturation responses. An
authoritative deny and expected contract errors do not trip it.

An open breaker immediately returns
`UNAVAILABLE / AUTHORIZATION_UNAVAILABLE`. It never serves stale/cached allow
state. The breaker is deliberately short-lived and half-open probes use the same
single-attempt 300 ms maximum contract.

### Revised production objectives

```text
availability: >=99.95% rolling 30d
p95 server latency: <=100 ms
p99 server latency: <=200 ms
caller deadline: 300 ms
attempts: 1
retry: none
cache/stale fallback: none
```

The former 75/150 ms values become steady-state engineering targets, not paging
SLOs. Launch/load gates use the 100/200 ms SLO. Alerts use multi-window burn and
saturation signals rather than paging on an isolated percentile sample.

### Capacity gate

Before production, prove >=2x projected peak while meeting the revised SLO with:

- >=30% CPU/memory/database headroom;
- Hikari acquisition p99 <25 ms under steady target load;
- no sustained request or pool queue growth;
- one Authorization replica/node loss;
- PostgreSQL failover;
- abusive invalid-token traffic rejected before Authorization;
- abusive valid-token traffic bounded by caller/server bulkheads without unsafe
  allow or retry amplification.

## Verification Requirements

Tests cover all local pre-checks, one-and-only-one authoritative permission
call, no Bloom/cache/fallback, fair-share isolation, queue/bulkhead shedding,
fail-closed circuit behavior, error mapping, SQL/pool budgets, replica loss,
failover, and >=2x peak latency/error-budget behavior.

## Consequences

Authorization remains freshness-first while gaining explicit DoS/cascade
protection. The relaxed paging SLO leaves useful headroom inside the 300 ms hard
deadline without normalizing slow queries.
