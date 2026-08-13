# ADR-0019: Derive Notification Clock Health from Chrony

## Status

Accepted

## Date

2026-08-09

## Relationship to Earlier Decisions

This ADR extends ADR-0017 and ADR-0018 by defining the authoritative
clock-synchronization signal, error calculation, health classification,
critical-entry behavior, recovery hysteresis, and PostgreSQL-primary binding.

It does not change the PostgreSQL lifecycle-time authority, canonical timestamp
precision, immutable deadlines, degraded-mode behavior, or recovery evaluation
defined by ADR-0017 and ADR-0018.

## Context

Notification must decide whether the system clock used by its authoritative
PostgreSQL instance is synchronized well enough for deadline-sensitive work.
Application-pod clocks and remotely scraped observability data do not prove the
health of the database host clock that produces `clock_timestamp()`.

The signal must fail closed when chronyd is unsynchronized, unavailable,
unparseable, or using an untrusted local reference. It must also avoid rapid
critical/recovered oscillation and must follow the current PostgreSQL primary
across failover.

## Decision

### Authoritative synchronization signal

Authoritative Notification clock-synchronization health is derived from
`chronyd` running against the same host or node system clock used by the
authoritative PostgreSQL instance.

A lightweight node-local `clock-health-agent` queries that local `chronyd`
instance through its Unix-domain socket every 2 seconds. It obtains tracking
data equivalent to the machine-readable output of:

```text
chronyc -c tracking
```

The runtime decision uses the agent's authoritative tracking signal directly.
Prometheus is observational only and is not an authority for runtime
clock-health decisions. Prometheus may scrape equivalent bounded metrics for
dashboards and alerts, but a Prometheus value cannot authorize
deadline-sensitive Notification work.

The exact authenticated transport, anti-replay behavior, freshness contract,
and workload authorization between `clock-health-agent` and Notification remain
separate implementation decisions.

### Canonical estimated maximum error

For every valid tracking sample, the canonical estimated maximum system-clock
error is calculated as:

```text
estimated_max_error = abs(system_time_offset)
                    + root_dispersion
                    + 0.5 * root_delay
```

All three inputs come from the same chronyd tracking sample and are converted
to the same duration unit before evaluation. A missing, non-finite, negative
where prohibited by the tracking contract, overflowing, or otherwise
unparseable input makes the sample critical rather than producing a partial
estimate.

### Health classification

The accuracy target remains `estimated_max_error <= 250ms`.

For a synchronized clock using an approved external time reference, canonical
health classification is:

| Estimated maximum error | Health |
| --- | --- |
| `<= 500ms` | `HEALTHY` |
| `> 500ms` and `<= 2s` | `WARNING` |
| `> 2s` | `CRITICAL` |

The range above the 250-millisecond target through 500 milliseconds is still
`HEALTHY`, but it does not meet the target accuracy objective.

Regardless of the numeric estimate, any of the following conditions is
immediately `CRITICAL` and produces `TIME_SOURCE_UNHEALTHY`:

- leap status is `Not synchronised`;
- chronyd uses a non-external or local time reference;
- the agent cannot obtain the chronyd tracking signal;
- the agent cannot parse or validate the tracking signal;
- an equivalent loss of synchronization is reported.

Entry into `CRITICAL` is immediate from one qualifying sample or signal
failure. There is no entry debounce or grace period. ADR-0018 degraded-mode
gates apply immediately after critical health is established.

### Recovery hysteresis

Recovery from `CRITICAL` requires all of the following for three consecutive
samples taken on the 2-second sampling interval:

- synchronization is restored;
- the time source is an approved external reference;
- the tracking signal is obtainable, parseable, and valid;
- `estimated_max_error <= 1s`.

Any failed or invalid sample resets the consecutive-recovery sequence. Until
all three qualifying samples have been observed, health remains `CRITICAL` and
ADR-0018 degraded mode remains active.

After recovery, the current numeric estimate determines whether the resulting
steady classification is `HEALTHY` or `WARNING`. Therefore recovery with an
estimate above 500 milliseconds and at most 1 second exits `CRITICAL` into
`WARNING`; it does not misreport the clock as `HEALTHY`.

### PostgreSQL-primary identity binding

Every authoritative clock-health signal identifies the PostgreSQL instance and
host or node whose system clock it describes.

Notification may authorize deadline-sensitive work only from a valid signal
bound to the currently authoritative PostgreSQL primary and its current host or
node. After PostgreSQL failover, a signal for the former primary or its former
host cannot authorize acceptance, dispatch, retry, expiration, receipt polling,
or recovery evaluation against the new primary.

The failover integration that establishes the current primary identity, the
identifier format, and the authenticated binding between database identity and
agent signal require explicit approval before runtime implementation.

### Observability and implementation gate

The agent and Notification expose bounded metrics for:

- current health classification;
- estimated maximum error;
- age and validity of the latest sample;
- chronyd synchronization and reference validity;
- consecutive recovery-sample count;
- PostgreSQL-primary and node binding match or mismatch;
- transitions into and out of `CRITICAL`.

Instance and node identity labels must use bounded infrastructure identifiers.
Metrics and logs must not contain recipient data, notification content, codes,
request payloads, or business identifiers. Prometheus remains observational
even when it stores all of these metrics.

Runtime implementation remains gated on:

- authenticated agent-to-Notification signal transport and authorization;
- maximum signal age, anti-replay, ordering, and restart behavior;
- current-primary discovery and failover binding;
- degraded-mode coordination and worker fencing across Notification replicas;
- exact chronyd reference allow-list and deployment policy;
- schema, configuration, rollout, rollback, and chaos-test design.

## Consequences

- Runtime health is measured from the clock that actually governs PostgreSQL
  lifecycle timestamps.
- Prometheus delay or outage cannot accidentally authorize deadline-sensitive
  work.
- Missing, malformed, locally sourced, or unsynchronized time fails closed.
- Immediate critical entry limits unsafe work, while three-sample recovery
  prevents rapid oscillation.
- A PostgreSQL failover cannot reuse health from a clock that no longer belongs
  to the authoritative primary.
- The node-local agent and its authenticated signal become security-sensitive
  production infrastructure.

## Alternatives Considered

### Use Prometheus as the runtime authority

Rejected because scrape, storage, and query delays can return stale health and
make observability availability part of a security decision.

### Measure the Notification application-pod clock

Rejected because `clock_timestamp()` is generated by the PostgreSQL host clock,
not an arbitrary Notification pod clock.

### Use only system-time offset

Rejected because the maximum-error bound also includes root dispersion and half
of root delay.

### Recover immediately after one valid sample

Rejected because transient synchronization changes could rapidly reopen and
close deadline-sensitive work.

### Continue using the former primary's health after failover

Rejected because that signal describes a clock that no longer generates the
authoritative PostgreSQL lifecycle time.

## Rollback or Migration Considerations

This ADR creates no application database migration by itself.

Production rollout must remain fail closed until Notification can authenticate
a fresh signal bound to the current primary. Mixed-version deployment must not
allow a replica without current-primary binding or recovery hysteresis to claim
deadline-sensitive work. Rollback must not fall back to Prometheus, pod-clock
measurement, or a former primary's health signal.
