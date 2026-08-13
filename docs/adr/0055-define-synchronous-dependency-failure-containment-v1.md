# ADR-0055: Define Synchronous Dependency Failure Containment v1

## Status

Accepted

## Date

2026-08-11

## Decision

Every synchronous remote dependency uses explicit failure containment. This
ADR does **not** require a circuit breaker around every gRPC call; resilience
mechanisms are selected by dependency semantics so they do not create hidden
retries, duplicate side effects, or unsafe fallbacks.

### Mandatory for every synchronous remote call

- finite parent/child deadline;
- bounded in-flight concurrency;
- bounded or zero waiting queue;
- cancellation propagation where supported;
- explicit retry owner (often none);
- stable failure mapping;
- dependency saturation/latency telemetry.

### Circuit-breaker classes

A circuit breaker is mandatory when repeated network/dependency failures would
otherwise consume scarce caller resources and the open-state behavior is
semantically safe.

1. **Authoritative security dependencies** (for example Authorization): the
   breaker may open only into the same fail-closed availability result. It never
   returns cached/stale allow data.
2. **External provider calls**: breaker/open-state suppresses new immediate
   attempts and hands control to the existing durable retry/reconciliation
   policy where one exists.
3. **Optional/read-only enrichments**: a breaker may return an explicitly
   approved degraded result only when the bounded context owns such fallback
   semantics.
4. **Durable side-effect commands**: the caller/outbox scheduler owns retry and
   idempotency. A generic client retry is prohibited; a breaker may pause new
   dispatch but cannot reinterpret an unknown result.
5. **PostgreSQL/Redis local infrastructure**: use finite acquisition/statement
   budgets, driver/HA behavior, pool limits, and load shedding. Do not wrap the
   database blindly in another breaker that obscures transaction outcomes.

### Resilience4j baseline

Java services use Resilience4j for application-level circuit breakers and
bulkheads when this ADR requires them. One resilience layer owns each behavior;
Istio/gRPC/application retry duplication is prohibited.

Circuit-breaker configuration is versioned per dependency. It records only
transport/dependency failures appropriate to that contract; business denials,
`INVALID_ARGUMENT`, authorization deny, and other expected domain outcomes do
not count as infrastructure failures.

### Overload behavior

When a bulkhead or bounded queue is exhausted, reject promptly with the
contract's availability/overload status rather than waiting past the caller's
useful deadline. Overload must not be misreported as a business denial.

## Verification Requirements

- breaker opens/closes only for configured infrastructure failures;
- no fallback converts dependency failure into authorization success;
- no duplicate retry layer exists;
- bulkhead saturation fails promptly;
- cancellation/deadline propagation works;
- unknown side-effect outcomes remain unknown and reconcile through their
  durable owner;
- chaos tests show dependency outage does not create unbounded caller queues or
  thread/connection growth.

## Consequences

Failure isolation becomes an explicit contract rather than a blanket library
annotation. This reduces cascade risk without introducing unsafe retries or
false success.
