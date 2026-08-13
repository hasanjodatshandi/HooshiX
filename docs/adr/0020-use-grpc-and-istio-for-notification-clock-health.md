# ADR-0020: Use gRPC and Istio for Notification Clock Health

## Status

Accepted

## Date

2026-08-09

## Relationship to Earlier Decisions

This ADR extends ADR-0019 by defining the clock-health pull transport, polling
deadline and failure behavior, local freshness rule, response identity fields,
and Istio workload authorization.

It does not change the chronyd-derived error formula, classification,
critical-entry behavior, recovery hysteresis, degraded mode, or immutable
deadline rules established by ADR-0017 through ADR-0019. Exact
PostgreSQL-primary binding and cross-replica worker fencing remain separate
architecture decisions.

## Context

Every Notification replica needs a current health signal for the system clock
used by the authoritative PostgreSQL primary. A push or Prometheus-mediated
path could introduce delayed or stale authorization. A pull contract allows
each replica to bound the age and duration of its own decision.

The transport carries security-sensitive infrastructure health but no user or
message data. Istio Ambient already supplies workload identity and mTLS, so a
second application authentication scheme or TLS layer would add credentials and
failure modes without improving the identity decision required here.

## Decision

### Pull-based gRPC contract

Clock-health transport uses an internal pull-based unary gRPC API:

```text
ClockHealthService.GetClockHealth
```

Each Notification replica polls the `clock-health-agent` bound to the current
authoritative PostgreSQL primary every 2 seconds. Each logical poll has:

- an overall gRPC deadline of 500 milliseconds;
- exactly one RPC attempt;
- no application, gRPC service-config, mesh, or other per-poll retry.

The next invocation at the normal 2-second polling cadence is a new sample
request and is not a retry of the preceding RPC.

Any RPC failure, deadline expiry, malformed response, or source-identity
mismatch makes that Notification replica's local signal immediately
`CRITICAL`. There is no grace interval before critical entry for an explicit
failed poll.

### Freshness and stale signal

Each Notification replica measures freshness from receipt of its last
successful, well-formed, source-matching sample using a local monotonic timer.
The agent's wall-clock timestamp, the Notification pod wall clock, and
Prometheus timestamps are not used for freshness.

If no such successful sample has been received for 5 seconds, the local signal
is `CRITICAL` with reason `TIME_SIGNAL_STALE`. A valid sample that reports
`CRITICAL` clock health can refresh transport freshness, but it does not make
the clock healthy.

Recovery from any locally critical state remains subject to ADR-0019's three
consecutive qualifying 2-second samples. A later successful poll cannot bypass
that recovery hysteresis.

### Response contract

Every `GetClockHealth` response contains:

- `db_instance_id`;
- `node_id`;
- `agent_boot_id`;
- monotonic `sample_sequence`;
- canonical health classification;
- bounded chrony measurements required by the approved health contract.

The concrete Protobuf field types, field numbers, numeric bounds, measurement
units, boot identifier lifecycle, and sequence validation across agent restarts
must be fixed in the versioned contract before implementation. Arbitrary maps,
unbounded text, raw chronyd output, and agent logs are not contract fields.

Notification rejects a response whose `db_instance_id` does not match the
authoritative PostgreSQL instance. Rejection is a source mismatch and makes the
local signal immediately `CRITICAL`.

ADR-0019's current-primary rule remains mandatory: health from the former
primary cannot authorize deadline-sensitive work after failover. The exact
primary-discovery, agent-selection, instance/node binding, and cross-replica
fencing mechanism remains a separate explicit decision.

### Istio workload identity and authorization

`clock-health-agent` and Notification Service are enrolled in Istio Ambient
Mesh under separate Kubernetes ServiceAccounts. Neither workload may use the
Kubernetes `default` ServiceAccount or share a ServiceAccount with the other.

Agent ingress requires `PeerAuthentication` in `STRICT` mode. An L4 Istio
`AuthorizationPolicy` on the dedicated health gRPC port permits ingress only
from the Notification Service ServiceAccount principal. Other workload
identities and plaintext connections are denied.

Authorization is based only on workload identity and destination port. A
waypoint is not required because the policy does not inspect gRPC method,
headers, claims, or other L7 attributes.

The health RPC uses Istio Ambient mTLS and workload identity. It does not use:

- a bearer token;
- a shared API key;
- an application-level JWT;
- a second application-managed native-TLS layer.

The final trust domain, namespace names, ServiceAccount names, health port, and
concrete Istio manifests remain deployment inputs governed by the production
Istio trust/CA decision and GitOps review.

### Observability and tests

Telemetry is bounded and distinguishes at least successful polls, RPC failure,
deadline expiry, malformed response, source mismatch, stale signal, received
classification, recovery progress, and Istio authorization denial. It must not
record full response payloads or place instance, node, boot, request, or
notification identifiers into unbounded metric dimensions.

Runtime verification must include:

- successful unary poll under the Notification Service identity;
- denial for every non-Notification workload identity;
- plaintext rejection under `PeerAuthentication STRICT`;
- 500-millisecond deadline enforcement and absence of retries;
- immediate local critical state for RPC failure, timeout, malformed response,
  and source mismatch;
- `TIME_SIGNAL_STALE` after 5 seconds without a successful sample using a
  monotonic test clock;
- agent restart, sequence, and recovery-hysteresis tests after the exact
  sequence contract is approved;
- PostgreSQL failover tests after primary binding and fencing are approved.

### Implementation gate

Runtime implementation remains gated on:

- exact Protobuf types, bounds, field numbers, and compatibility policy;
- sequence, boot transition, anti-replay, and ordering validation;
- current-primary discovery, agent selection, and primary/node binding;
- degraded-mode coordination and cross-replica worker fencing;
- final Istio trust domain, namespaces, ServiceAccounts, port, and manifests;
- clock-health-agent deployment topology and chronyd socket permissions;
- rollout, rollback, alerting, and chaos-test design.

## Consequences

- Every Notification replica owns a bounded, independently fresh local signal.
- Explicit poll failure closes the local safety gate immediately; a missing
  success also has a five-second stale bound.
- No retry amplification occurs during agent or mesh failure.
- Istio supplies transport confidentiality, peer authentication, and
  least-privilege workload authorization without application credentials.
- The L4-only policy avoids waypoint cost and complexity.
- Primary discovery and cross-replica fencing remain required before the signal
  can safely control distributed workers.

## Alternatives Considered

### Push clock-health updates to Notification

Rejected because each replica would need a separate authoritative freshness and
delivery mechanism, while bounded polling provides a direct local observation.

### Query Prometheus from Notification

Rejected by ADR-0019 because Prometheus is observational and can return delayed
or stale data.

### Retry a failed poll

Rejected because retry could mask a failed sample, increase load during outage,
and make critical-entry latency unpredictable.

### Use bearer tokens or shared API keys

Rejected because Istio workload identity already authenticates the caller and
avoids distributing another secret.

### Add application-native TLS

Rejected because Ambient mTLS already protects this internal path. A second TLS
layer would duplicate certificate and rotation responsibility.

### Add a waypoint for method-level authorization

Rejected because only workload identity and port authorization are required.

## Rollback or Migration Considerations

This ADR creates no database migration by itself.

Rollout must apply strict mTLS and the positive/negative L4 authorization policy
before the signal can authorize deadline-sensitive work. Mixed-version
Notification replicas must fail closed if they lack the approved freshness,
deadline, no-retry, or source-validation behavior. Rollback must not introduce
tokens, shared keys, plaintext, Prometheus authority, or retries.
