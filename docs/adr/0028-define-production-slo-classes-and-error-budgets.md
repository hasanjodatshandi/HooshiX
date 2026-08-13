# ADR-0028: Define Production SLO Classes and Error Budgets

## Status

Accepted

## Date

2026-08-09

## Relationship to Earlier Decisions

This ADR resolves the pending production SLO-class, critical-path latency,
server-timeout ceiling, asynchronous Notification processing objective, and
error-budget release-policy decisions.

Existing dependency-specific deadlines remain valid when they are below the
class ceiling. In particular, the compromised-password lookup retains its
900-millisecond overall deadline.

## Context

Defining an independent SLO for every RPC would obscure the user and platform
outcomes that matter. Critical security transactions, their internal security
dependencies, and asynchronous notification dispatch have different meanings
and must not share one success definition.

Provider delivery is external and channel-dependent. It must not be reported as
the internal availability of Notification durable acceptance or provider-work
scheduling.

## Decision

### Class A: critical security transactions

Class A includes:

- login and authentication;
- OTP and MFA verification;
- registration completion;
- password-reset completion;
- password-change completion.

Its rolling 30-day objectives are:

| SLI | Objective |
| --- | ---: |
| Availability | 99.90 percent |
| End-to-end server latency p95 | at most 500 milliseconds |
| End-to-end server latency p99 | at most 1500 milliseconds |
| End-to-end server timeout ceiling | 2 seconds |

Every dependency receives a smaller budget than the 2-second ceiling. Nested
retries must not cause the path to exceed its total budget.

### Class B: critical internal security dependencies

Class B includes at least:

- `compromised-password-service`;
- the Identity Redis semantic limiter;
- Notification durable acceptance.

Its rolling 30-day objectives are:

| SLI | Objective |
| --- | ---: |
| Availability | 99.95 percent |
| Latency p95 | at most 250 milliseconds |
| Latency p99 | at most 750 milliseconds |

An individual RPC may keep a stricter or otherwise explicitly approved
deadline. The compromised-password RPC retains its existing 900-millisecond
deadline.

### Class C: asynchronous Notification processing

`SubmitNotification` success means durable acceptance, not provider acceptance
or Email/SMS delivery.

The internal processing objective is:

```text
99.9 percent of accepted intents begin their first provider attempt
within 5 seconds of durable acceptance.
```

Actual SMS and Email delivery use separate provider-dependent SLIs. They must
not be combined with Notification's internal durable-acceptance availability or
first-attempt scheduling objective.

### Error-budget release policy

A 99.90-percent availability objective over 30 days has approximately 43
minutes and 12 seconds of error budget.

The release policy is:

| Budget condition | Required action |
| --- | --- |
| Less than 25 percent consumed | Normal software delivery |
| At least 25 percent consumed within 24 hours | Reliability review and stop risky releases |
| At least 50 percent consumed | Freeze feature releases |
| 100 percent exhausted | Permit only security, incident, and reliability changes |

A release freeze remains until burn rate is controlled again and a remediation
plan exists.

Planned maintenance is not excluded from availability. If the user cannot
obtain service, that interval counts against the applicable availability SLO.

### Implementation gate

Exact SLI event definitions, denominator and eligibility rules, telemetry
backend, burn-rate alert windows, dashboard queries, and ownership/on-call
routing require explicit approval before production enforcement.

## Consequences

- Critical security journeys are evaluated as user outcomes rather than a
  collection of unrelated RPC statistics.
- Internal dependencies have a higher availability target than Class A user
  journeys.
- Notification durable acceptance and dispatch scheduling remain separate from
  external provider delivery.
- Error-budget consumption directly constrains release risk.
- Planned maintenance consumes availability budget.

## Alternatives Considered

### Define an SLO for every RPC

Rejected because the approved model uses three outcome classes.

### Treat provider delivery as Notification availability

Rejected because provider behavior is an external, channel-dependent SLI and
does not redefine durable acceptance.

### Exclude planned maintenance

Rejected because user-visible unavailability counts regardless of whether the
maintenance was scheduled.

## Rollback or Migration Considerations

This ADR creates no runtime telemetry or alerting configuration by itself.

Rollout must validate SLI queries against synthetic and historical data before
release freezes are automated. Rollback of dashboards or alert rules must not
silently weaken the approved objectives or erase consumed error budget.
