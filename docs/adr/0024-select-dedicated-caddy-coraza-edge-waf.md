# ADR-0024: Dedicated Caddy + Coraza Edge WAF v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

## Decision

Production public application traffic follows:

```text
Internet / upstream L3-L4 mitigation
-> external load balancing
-> Traefik
-> dedicated edge-waf (Caddy + Coraza + OWASP CRS)
-> Web BFF
```

The WAF is a separately deployable edge workload. It is not implemented as a Java/Spring filter and cannot be bypassed by an alternate public Traefik -> BFF route.

### Enforcement

- Caddy + Coraza v3 + the approved CRS 4.x LTS line;
- exact artifact/rule pins live in the Technology Baseline and deployment metadata;
- PL1 initial policy;
- at least seven representative days in DetectionOnly before reviewed blocking enablement;
- rule exclusions are narrow, reasoned, owned, reviewed, and versioned;
- automatic CRS/rule upgrades are prohibited;
- request-body inspection is bounded; large/upload routes define explicit body policy rather than globally increasing limits;
- WAF/edge telemetry MUST NOT log full bodies, credentials, tokens, cookies, or unreviewed PII.

### Availability and identity

Production WAF replication, PDB/topology placement, ServiceAccount, NetworkPolicy, and Istio authorization follow current runtime/SLO requirements. Route and policy controls MUST deny direct Internet -> BFF and Traefik -> BFF application access.

The WAF provides L7 HTTP inspection only. Upstream volumetric DDoS protection is separately mandatory and the in-cluster WAF MUST NOT be described as bandwidth-saturation protection.

## Verification requirements

Verify public-route traversal through WAF, direct-bypass negative tests, representative DetectionOnly tuning evidence, controlled blocking tests, request-size/body limits, one-replica/node-loss behavior where HA applies, incremental latency/load impact, PII-safe logging, pinned rule/artifact integrity, and NetworkPolicy/Istio positive/negative paths.

## Rollback considerations

Rollback may restore a previously approved pinned WAF/rule set through GitOps when compatible. It MUST NOT create a direct BFF bypass, disable authentication/authorization/semantic quotas, enable unsafe body logging, or remove upstream volumetric protection.