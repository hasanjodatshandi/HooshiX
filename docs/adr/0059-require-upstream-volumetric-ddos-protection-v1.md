# ADR-0059: Upstream Volumetric DDoS Protection v1

## Status

Accepted — current effective decision

## Date

2026-08-11; normalized to current-only documentation on 2026-08-13

## Decision

Production hosting/networking is eligible only when public traffic has upstream L3/L4 volumetric DDoS detection and mitigation/scrubbing **before** attack traffic can saturate the origin link or state tables.

The exact provider is deployment-specific, but production readiness requires documented provider limits, activation behavior, escalation contacts, and an exercised incident path.

### Origin/edge controls

The origin additionally uses:

- redundant L4 load-balancing capacity;
- bounded connection/handshake limits;
- reviewed SYN-flood and conntrack controls;
- emergency/coarse pre-WAF limits for volumetric pressure;
- origin reachability restricted to approved upstream/LB paths where topology permits;
- no route that bypasses Traefik -> Caddy/Coraza WAF -> Web BFF.

NetworkPolicy and Istio protect cluster/east-west paths but are not described as Internet volumetric mitigation. Coraza/CRS remains bounded L7 application-layer inspection and does not replace upstream bandwidth/scrubbing capacity.

### Operations

Telemetry separates packets/bytes/connections, load-balancer saturation, Traefik/WAF load, and application request rate. Emergency controls are documented, reversible, and MUST NOT disable authentication, WAF, authorization, tenant isolation, or semantic quotas to recover capacity.

## Verification requirements

Verify provider capability/SLA and escalation contacts, origin-bypass negatives where restriction is available, authorized connection/HTTP flood tests, one edge replica/node loss under elevated load, link/LB/conntrack/WAF saturation alerting, and an incident exercise covering provider escalation, temporary coarse limits, containment, and rollback.

## Rollback considerations

Rollback MUST NOT expose an origin bypass, remove required upstream mitigation, or substitute in-cluster WAF/mesh controls for volumetric protection.