# ADR-0029: Upstream Volumetric DDoS Protection v1

## Status

Accepted — current effective decision

## Date

2026-08-11; normalized to current-only documentation and made profile-aware on 2026-08-14; aligned with mandatory ADR-0043 origin restriction on 2026-08-15

## Decision

Production hosting/networking is eligible only when public traffic has upstream L3/L4 volumetric DDoS detection and mitigation/scrubbing **before** attack traffic can saturate the origin link or state tables.

The exact provider is deployment-specific, but production readiness requires documented provider limits, activation behavior, escalation contacts, and an exercised incident path.

### Origin/edge controls

The origin additionally uses:

- upstream/external L4 capacity with redundancy appropriate to the provider/deployment, even though the selected single-server origin itself remains non-HA;
- bounded connection/handshake limits;
- reviewed SYN-flood and conntrack controls;
- emergency/coarse pre-WAF limits for volumetric pressure;
- production Traefik application-origin reachability restricted to the exact approved external-L4 source path under ADR-0043; direct Internet/non-approved-source access is denied before application routing;
- no route that bypasses Traefik -> Caddy/Coraza WAF -> Web BFF.

A production deployment that cannot enforce the external-L4-only Traefik origin restriction is not eligible without a revised current security decision. Application header validation is not a substitute for the network restriction.

NetworkPolicy and Istio protect cluster/east-west paths but are not Internet volumetric mitigation. Coraza/CRS is bounded L7 inspection and does not replace upstream bandwidth/scrubbing capacity.

### Profile interpretation

`production-single-server` explicitly accepts that loss or maintenance of the only origin host can remove application availability. Redundant upstream mitigation/L4 capacity protects against volumetric/network-provider failures within its scope; it does not turn one origin into HA. During origin overload/outage, traffic MUST NOT be rerouted around Traefik/WAF/BFF or the external-L4-only origin control merely to recover availability.

`production-ha` retains edge/workload redundancy and one-node/replica-loss availability tests under the current HA topology while preserving the same approved public origin path.

### Operations

Telemetry separates packets/bytes/connections, external-L4 saturation, Traefik/WAF load, and application request rate. Emergency controls are documented/reversible and MUST NOT disable authentication, WAF, origin restriction, Authorization, tenant isolation, semantic quotas, workload identity, signed-artifact controls, or required audit to recover capacity.

## Verification requirements

Both profiles verify:

- provider capability/SLA and escalation contacts;
- direct Internet/non-approved-source Traefik-origin denial before application routing;
- authorized connection/HTTP flood tests;
- link/L4/conntrack/WAF saturation alerting;
- incident exercise covering provider escalation, temporary coarse limits, containment, and rollback;
- no emergency direct-origin or WAF bypass.

`production-single-server` additionally verifies the complete approved edge path under representative load, records expected application outage when the only origin host is unavailable, and proves neither automation nor operator recovery creates an origin/WAF/BFF bypass.

`production-ha` additionally verifies one edge workload/node loss under elevated load according to its HA target.

## Rollback considerations

Rollback MUST NOT expose a direct origin, remove required upstream mitigation, substitute in-cluster WAF/mesh controls for volumetric protection, or describe redundant upstream L4 as origin HA when the selected profile has one server.