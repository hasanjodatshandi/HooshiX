# ADR-0059: Require Upstream Volumetric DDoS Protection v1

## Status

Accepted

## Date

2026-08-11

## Relationship to Earlier Decisions

ADR-0024's Caddy/Coraza tier remains the application-layer WAF. This ADR adds the
network-layer control that a WAF inside the origin cluster cannot provide when
an attack saturates the site's external bandwidth or state tables.

## Decision

### Upstream mitigation is mandatory

A production hosting/network provider is eligible only if the public service
has upstream L3/L4 volumetric DDoS detection and mitigation/scrubbing before
traffic can saturate the origin link.

The exact provider is a deployment/vendor choice, but the capability is not
optional. Production readiness requires documented provider limits,
escalation contacts, mitigation activation behavior, and a runbook.

### Origin and edge controls

The edge additionally enforces:

- redundant L4 load-balancer capacity;
- bounded connection/handshake limits;
- OS/network SYN-flood protections and reviewed conntrack sizing;
- coarse per-source/emergency rate limits before expensive WAF/application work;
- origin reachability restricted to the approved upstream/LB paths where the
  chosen topology permits it;
- no direct route that bypasses Traefik -> Caddy/Coraza -> BFF.

NetworkPolicy and Istio remain east-west/cluster controls and are not described
as volumetric internet DDoS protection.

### WAF responsibility remains L7

Coraza/CRS handles bounded HTTP inspection and application-layer attack
patterns. It does not replace upstream bandwidth/scrubbing capacity.

### Operations

DDoS telemetry separates packets/bytes/connections, LB saturation, Traefik/WAF
load, and application request rate. Emergency actions are documented and
reversible; operators do not disable authentication/WAF/authorization merely to
recover capacity.

## Verification Requirements

- provider capability/SLA and emergency-contact review;
- origin-bypass negative tests where source restriction is available;
- controlled connection/HTTP flood tests in an authorized environment;
- one edge replica/node loss under elevated load;
- alerting on link/LB/conntrack/WAF saturation;
- incident exercise covering provider escalation, temporary coarse limits, and
  rollback.

## Consequences

The architecture acknowledges that in-cluster WAF capacity cannot absorb an
attack that exhausts upstream bandwidth. Volumetric mitigation becomes a
network-provider requirement rather than an application feature.
