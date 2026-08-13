# ADR-0024: Select a Dedicated Caddy and Coraza Edge WAF

## Status

Accepted

## Date

2026-08-09

## Relationship to Earlier Decisions

This ADR resolves the pending self-hosted production WAF decision in the
canonical architecture. It replaces the previously listed production options
of a managed cloud WAF or Traefik Hub Native Coraza with the dedicated tier
defined here.

It does not change Traefik's gateway, routing, coarse rate-limiting, TLS, or
observability responsibilities, and it does not weaken application validation
or Identity semantic rate limiting.

## Context

The production platform is self-hosted. The WAF must remain an independently
bounded edge control rather than Java application code or a preview integration
inside Traefik. Public application traffic needs one enforceable path through
the WAF before reaching `web-bff`.

WAF rules and request-body inspection can affect legitimate traffic and must
therefore be versioned, bounded, observed, tuned, and promoted through GitOps.

## Decision

### Product and topology

The production WAF uses:

- OWASP Coraza v3;
- OWASP Core Rule Set 4.x LTS;
- a dedicated stateless `edge-waf` tier using Caddy with Coraza.

The canonical public application path is:

```text
Internet / External Load Balancer
  -> Traefik
  -> dedicated Caddy + Coraza edge-waf
  -> web-bff
```

Traefik routes public application traffic to `edge-waf`. The WAF forwards only
to `web-bff`. A direct Internet-to-BFF or Traefik-to-BFF application path is
prohibited and must be denied through Kubernetes NetworkPolicy and Istio
authorization in addition to route configuration.

The direct Traefik Coraza integration is not selected for production. The WAF
is not implemented in Spring, a Java filter, or application business logic.

### Availability baseline

`edge-waf` uses two replicas when the cluster has at least two schedulable
worker nodes. A one-replica fallback is allowed only when the cluster physically
has one worker node.

The workload remains stateless. Placement, disruption, readiness, and routing
must avoid treating the one-replica fallback as equivalent to redundant
production availability.

### CRS policy and rollout

The initial CRS paranoia level is `PL1`.

Rollout follows:

```text
DetectionOnly -> tune bounded exceptions -> blocking
```

`DetectionOnly` runs for at least 7 days of representative traffic before
blocking is eligible. Moving to blocking requires review of observed rule hits
and narrowly scoped, documented exceptions.

Rule and CRS changes require an explicit GitOps pull request. Images and rule
artifacts are pinned by exact version and immutable digest.
Automatic CRS updates are prohibited.

### Inspection and control boundaries

Request-body inspection is bounded. Endpoints requiring large uploads receive
a separately approved endpoint-specific policy before those uploads are
enabled. This ADR does not select the numeric body-inspection limit.

The WAF does not replace:

- Traefik coarse edge rate limiting;
- Identity semantic and security-sensitive rate limiting;
- authentication or authorization;
- input validation, output encoding, or secure coding.

WAF logs record bounded rule identifiers and disposition without request or
response bodies, credentials, tokens, or PII.

### Implementation gate

Exact Caddy, Coraza, CRS, and container patch versions and digests; bounded
body-inspection limits; resource sizing; probe values; and concrete policy
manifests require explicit approval before production deployment.

## Consequences

- The self-hosted WAF has an explicit, independently deployable boundary.
- Every public BFF request must traverse Traefik and the dedicated WAF tier.
- Detection and tuning precede blocking, reducing uncontrolled false-positive
  risk.
- A physical one-worker cluster retains an explicitly accepted single-replica
  availability limitation.
- Another internal proxy hop and its latency, capacity, and failure modes must
  be included in load and chaos testing.

## Alternatives Considered

### Traefik Hub Native Coraza

Not selected for this production profile.

### Direct Traefik Coraza integration

Not selected because the approved production boundary uses the dedicated Caddy
connector rather than the preview direct integration.

### Implement WAF behavior in Java

Rejected because WAF is an edge-platform responsibility and must not enter the
application request-processing code.

### Automatically update CRS

Rejected because an unreviewed rule change can alter or block production
traffic outside the approved GitOps release path.

## Rollback or Migration Considerations

This ADR creates no runtime deployment by itself.

Rollout must establish the WAF route and positive/negative reachability tests
before removing any former route. Rollback may restore a previously approved
WAF artifact through Git revert, but must not restore a direct Traefik-to-BFF or
Internet-to-BFF path. Moving from detection to blocking and rolling back a rule
set are GitOps changes using pinned artifacts.
