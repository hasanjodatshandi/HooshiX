# ADR-0032: Authorization SLO Alerting and Breaker Opening v1

## Status

Accepted — current effective decision

## Date

2026-08-11; normalized to current-only documentation on 2026-08-13

## Decision

ADR-0026 defines the production Authorization request/capacity baseline. This ADR defines the current SLI interpretation, multi-window burn alerting, breaker-opening criteria, and the rule that real `CheckPermission` traffic—not a health endpoint—proves recovery. ADR-0036 owns the current reopen backoff and serialized HALF_OPEN behavior.

Current objectives remain:

```text
availability >=99.95% rolling 30d
p95 <=100 ms
p99 <=200 ms
caller deadline = 300 ms
attempts = 1
retry/cache/stale fallback = none
```

### Availability SLI

An eligible request is a workload-authenticated, syntactically valid `CheckPermission` call. An authoritative allow or deny is an available result. Caller contract errors such as malformed `INVALID_ARGUMENT` are excluded from the availability denominator. Server, database, timeout, overload, and transport failures remain unavailable outcomes.

The 99.95% rolling-30-day objective has an approximate error budget of 21 minutes 36 seconds.

Availability burn policy:

| Burn rate | Paired windows | Action |
| ---: | --- | --- |
| 14.4x | 5m and 1h | Page Authorization + Platform on-call |
| 6x | 30m and 6h | Page Authorization + Platform on-call |
| 3x | 2h and 24h | Reliability ticket + release-risk review |

An isolated short-window signal does not page without its paired long window.

### Latency SLI

Percentile objectives are represented as request-event objectives:

```text
>=95% of eligible calls complete <=100 ms
>=99% of eligible calls complete <=200 ms
```

The <=200ms objective is the paging latency SLI and uses the same paired burn policy against its 1% latency-error budget. The <=100ms objective is primarily an engineering/capacity SLI; sustained burn creates reliability action but does not repeatedly page while the <=200ms user-impact boundary remains healthy.

Queue wait, Hikari acquisition, SQL execution, serialization, and network time are measured separately so SLO burn is attributed rather than hidden by timeout increases.

### Breaker opening

Each resource-service Authorization client owns a breaker scoped to the `CheckPermission` dependency. Infrastructure failures include `UNAVAILABLE`, `DEADLINE_EXCEEDED`, transport failure, and `RESOURCE_EXHAUSTED / AUTHORIZATION_OVERLOADED`. Authoritative allow/deny results are infrastructure-successful outcomes; expected caller errors do not feed the breaker.

The baseline breaker opens when either:

- at least 50% of infrastructure outcomes fail in a rolling window of 20 completed eligible calls with at least 10 calls observed; or
- five consecutive infrastructure failures occur.

Low-volume callers may trigger the consecutive-failure rule before the percentage window reaches its minimum count.

### Current OPEN/HALF_OPEN recovery

Reopen timing and half-open concurrency follow ADR-0036:

```text
base open duration = min(30s, 2s * 2^reopen_streak)
actual open duration = random value in [0.5 * base, base]
```

A failed half-open recovery increments `reopen_streak`; at least 60 seconds of continuously healthy CLOSED operation resets it to zero.

HALF_OPEN permits at most **one** real incoming `CheckPermission` probe in flight per caller breaker instance. Three consecutive infrastructure-successful probes close the breaker. Authoritative allow or deny counts as infrastructure success. Any timeout, `UNAVAILABLE`, transport failure, or explicit overload reopens immediately. Non-probe requests while the probe slot is occupied fail fast through `AUTHORIZATION_UNAVAILABLE`.

OPEN and HALF_OPEN never return cached/stale allow state and never introduce automatic retry.

### Health endpoints are not recovery authority

Kubernetes health/readiness signals exist for orchestration and diagnostics, but a caller breaker MUST NOT close merely because a separate health endpoint is healthy. Recovery is proven on real `CheckPermission` operations because a health endpoint may succeed while policy SQL, pool capacity, tenant data, or overload behavior is broken.

## Verification requirements

Tests/evidence cover SLI denominator inclusion/exclusion, paired-window burns, absence of isolated-percentile paging, 20-call/50% and five-consecutive-failure opening, de-correlated bounded reopen backoff, single in-flight half-open probe, three consecutive real-contract successes to close, authoritative deny as infrastructure success, immediate reopen on infrastructure failure/overload, health endpoint non-authority, and load/chaos attribution across network/queue/pool/SQL/serialization.

## Rollback considerations

Rollback MUST NOT restore isolated-percentile paging, fixed synchronized reopen timing, concurrent half-open probe bursts, health-endpoint-authorized closure, stale/cached allow fallback, retries, or larger timeouts used merely to hide SLO burn.