# ADR-0023: Bound Notification Clock-Health Cycle Timeouts

## Status

Accepted

## Date

2026-08-09

## Relationship to Earlier Decisions

This ADR extends ADR-0020 and ADR-0021 by fixing the database-acquisition,
primary-verification statement, and complete-cycle timeout budgets for the
2-second Notification clock-health cycle.

It does not change the agent's 500-millisecond gRPC deadline, single-attempt
policy, no-retry rule, 5-second stale threshold, primary-Pod binding, recovery
hysteresis, or database-backed dispatch fence established by ADR-0019 through
ADR-0022.

## Context

ADR-0021 requires every Notification replica to verify the PostgreSQL writer
and then query the matching primary-bound clock-health agent during each
2-second cycle. Without an overall deadline and subordinate step budgets, pool
contention or a slow database step could leave too little time for the agent
call or let one cycle overlap the next scheduling boundary.

A timed-out or incomplete cycle cannot refresh signal freshness. Retrying it
immediately would weaken the fixed sampling cadence and create additional load
during a database, agent, or mesh failure.

## Decision

### Canonical cycle budgets

Each Notification clock-health cycle uses these maximum budgets:

| Scope | Maximum |
| --- | ---: |
| PostgreSQL connection acquisition | 300 milliseconds |
| Primary-verification query `statement_timeout` | 150 milliseconds |
| Clock-health-agent gRPC call | 500 milliseconds |
| Complete database-verification and agent cycle | 1200 milliseconds |

The complete-cycle deadline is measured with a local monotonic timer and covers
connection acquisition, the primary-verification query, response validation,
the matching-agent gRPC call, and local validation required to complete the
cycle.

The primary-verification query uses a session-scoped PostgreSQL
`statement_timeout` of at most 150 milliseconds. Its scope must not leak that
setting into unrelated work when a pooled connection is reused.

### Remaining-budget cap

Every step is additionally bounded by the remaining complete-cycle budget at
the time that step begins. The effective maximum is therefore:

```text
connection acquisition = min(300ms, remaining cycle budget)
primary query           = min(150ms, remaining cycle budget)
agent gRPC call          = min(500ms, remaining cycle budget)
```

The agent call retains exactly one attempt and no application, gRPC, mesh, or
other retry. Its gRPC deadline can be shorter than 500 milliseconds when less
than 500 milliseconds remains in the 1200-millisecond cycle budget.

### Failure and scheduling behavior

Any connection-acquisition timeout, statement timeout, agent timeout, or
complete-cycle deadline expiry invalidates that cycle. An invalid or incomplete
cycle does not refresh the local successful-signal freshness timestamp and
cannot contribute a recovery-hysteresis sample.

Existing immediate fail-closed behavior remains in force for an explicit
database-verification or agent failure. The 5-second `TIME_SIGNAL_STALE`
threshold remains independently authoritative when no complete valid cycle has
refreshed signal freshness.

The next invocation at the normal 2-second schedule is a new independent
cycle. No immediate retry, catch-up retry, or continuation of the expired cycle
is permitted. A valid cycle must complete all required steps before its
1200-millisecond monotonic overall deadline.

### Observability and verification

Bounded telemetry distinguishes connection acquisition, primary statement,
agent RPC, and overall-cycle timeout. It records cycle completion duration,
remaining-budget truncation, invalid-cycle count, and freshness age without
including database credentials, SQL parameters, raw agent responses, recipient
data, content, codes, `request_id`, or `notification_id`.

Runtime verification must include:

- each individual 300-, 150-, and 500-millisecond cap;
- the 1200-millisecond monotonic overall deadline;
- shortening every step to its remaining overall budget;
- no freshness refresh or recovery progress after any timeout;
- no immediate retry and one new independent cycle at the next 2-second tick;
- `TIME_SIGNAL_STALE` after 5 seconds without a complete valid cycle;
- pooled-connection tests proving the session-scoped statement timeout does
  not leak into unrelated database work.

### Implementation gate

This ADR does not select the concrete PostgreSQL pool API, timeout-setting and
reset mechanism, scheduler implementation, metric names, alert thresholds, or
threading mechanism. Those choices remain subject to explicit approval where
they materially affect production behavior or structure.

The remaining Protobuf, identity-transition, database-credential,
sidecar-permission, network-policy, rollout, and fencing-detail decisions from
ADR-0020 through ADR-0022 remain gated.

## Consequences

- A slow database or agent step cannot consume an unbounded sampling cycle.
- The agent retains its 500-millisecond maximum without exceeding the remaining
  overall budget.
- Failed cycles reduce availability and freshness rather than authorizing work
  from a partial observation.
- The fixed 2-second cadence does not become an immediate-retry loop during an
  outage.
- Pool reuse must safely contain the session-scoped statement timeout.

## Alternatives Considered

### Use only independent per-step timeouts

Rejected because sequential steps could collectively exceed the intended cycle
budget.

### Give every agent call a fixed 500-millisecond deadline

Rejected when less than 500 milliseconds remains because a child step must not
outlive the complete-cycle budget.

### Retry immediately after a timeout

Rejected because the next scheduled 2-second cycle is the independent next
attempt and immediate retry would amplify dependency failure.

### Refresh freshness after a partial cycle

Rejected because primary verification and the matching agent response are one
indivisible authorization observation.

## Rollback or Migration Considerations

This ADR creates no database migration. Rollout must keep the existing
fail-closed behavior until every Notification replica enforces the complete
cycle budget and subordinate caps.

Rollback must not restore unbounded acquisition or statement waits, permit an
agent call to outlive the remaining cycle budget, refresh freshness from a
partial cycle, or add an immediate retry. Any session-scoped timeout must be
contained before its pooled connection is returned.
