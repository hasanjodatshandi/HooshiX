# ADR-0062: Finalize Authorization SLO Alerting and Circuit-Breaker Recovery v1

## Status

Accepted

## Date

2026-08-11

## Relationship to Earlier Decisions

This ADR extends ADR-0028, ADR-0055, and ADR-0056. It does not weaken
ADR-0039's online authoritative `CheckPermission`, 300-millisecond maximum
caller deadline, one attempt, no permission cache, no stale fallback, and
fail-closed semantics.

ADR-0056's production objectives remain unchanged:

```text
availability >= 99.95% rolling 30d
p95 <= 100 ms
p99 <= 200 ms
caller deadline = 300 ms
```

This ADR defines exact SLI interpretation, multi-window burn alerting, and
half-open recovery behavior so short-lived latency noise does not create noisy
paging and breaker recovery does not trust a separate health endpoint.

## Decision

### No grace period that hides SLO consumption

Authorization does not use an ad-hoc grace period that removes real errors or
latency from the SLO. Short-lived degradation is absorbed by the rolling error
budget and multi-window burn-rate policy.

An isolated p95/p99 sample is never itself a paging condition.

### Availability SLI

An eligible request is a workload-authenticated, syntactically valid
`CheckPermission` call. An authoritative allow or deny is an available result.
Caller contract errors such as malformed `INVALID_ARGUMENT` are excluded from
the availability denominator. Server, database, timeout, overload, and
transport failures remain unavailable outcomes.

The 99.95-percent rolling-30-day objective has an approximate error budget of
21 minutes and 36 seconds.

Availability burn alerts are:

| Burn rate | Windows that must both fire | Action |
| ---: | --- | --- |
| 14.4x | 5m and 1h | Page Authorization + Platform on-call |
| 6x | 30m and 6h | Page Authorization + Platform on-call |
| 3x | 2h and 24h | Reliability ticket and release-risk review |

A single short window without the paired long window does not page.

### Latency SLI

The percentile objectives are represented as request-event objectives so they
can use the same error-budget discipline:

```text
>=95% of eligible calls complete <=100 ms
>=99% of eligible calls complete <=200 ms
```

The `<=200 ms` objective is the paging latency SLI. It uses the same 14.4x and
6x paired-window page conditions and the 3x ticket condition against its
1-percent latency-error budget.

The `<=100 ms` objective is primarily an engineering/capacity SLI. Sustained
burn creates a reliability action; it is not allowed to page repeatedly while
the `<=200 ms` user-impact boundary remains healthy.

Queue wait, Hikari acquisition, SQL execution, serialization, and network time
are separately measured so SLO burn can be attributed rather than masked by
raising timeouts.

### Circuit-breaker opening

Each resource-service Authorization client owns a local breaker. Infrastructure
failures counted by ADR-0056 include `UNAVAILABLE`, `DEADLINE_EXCEEDED`,
transport failure, and `RESOURCE_EXHAUSTED / AUTHORIZATION_OVERLOADED`.
Authoritative allow/deny responses are successful infrastructure outcomes.
Expected caller errors do not feed the breaker.

The baseline breaker opens when either:

- at least 50 percent of infrastructure outcomes fail in a rolling window of
  20 completed eligible calls with at least 10 calls observed; or
- five consecutive infrastructure failures occur.

Services with lower call volume may use the consecutive-failure rule before
the percentage window reaches its minimum call count.

### Open and half-open behavior

The open state lasts 2 seconds plus per-instance random jitter of up to 1
second. Calls rejected by an open breaker fail immediately as
`UNAVAILABLE / AUTHORIZATION_UNAVAILABLE` and never become an authorization
deny.

After the open interval, the breaker enters half-open and permits at most three
concurrent probe calls. These probes are **real `CheckPermission` operations**
for real incoming protected requests and retain the normal:

```text
300 ms maximum deadline
1 attempt
no retry
no cache/stale fallback
```

Three consecutive infrastructure-successful probe outcomes close the breaker.
An authoritative `PERMISSION_DENIED` counts as an infrastructure-successful
probe because Authorization completed the authoritative decision. Any
infrastructure failure or overload response during half-open immediately
reopens the breaker.

Non-probe calls while the half-open permit set is exhausted fail fast through
the same availability path.

### Health endpoints are not breaker recovery authority

Authorization exposes ordinary Kubernetes startup/readiness/health signals for
orchestration and diagnostics. A caller circuit breaker MUST NOT close merely
because a separate health endpoint reports healthy.

A health endpoint can succeed while the real `CheckPermission` query,
connection pool, tenant policy data, or overload path is failing. Breaker
recovery therefore uses real contract probes only.

## Verification Requirements

Tests and production-readiness evidence cover:

- SLI denominator inclusion/exclusion;
- paired-window burn alerts and absence of isolated-percentile paging;
- 20-call/five-consecutive-failure opening behavior;
- open-state jitter and fail-fast mapping;
- maximum three half-open probes;
- three consecutive infrastructure-successful probes to close;
- authoritative deny counted as infrastructure success, not breaker failure;
- immediate reopen on timeout/unavailable/overload in half-open;
- proof that readiness/health cannot close the client breaker;
- load and chaos tests attributing latency to network, queue, pool, SQL, and
  serialization components.

## Consequences

The 100/200-millisecond SLO remains meaningful without turning transient disk
or network noise into useless pages. Recovery is proven on the exact protected
contract path rather than inferred from a weak side-channel health check.

## Rollback Considerations

Rollback must not restore paging on isolated percentile samples, remove
fail-closed breaker behavior, use a health endpoint as authoritative recovery,
or increase retries/deadlines to hide SLO burn.
