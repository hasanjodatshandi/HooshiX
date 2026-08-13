# ADR-0024: Dedicated Caddy + Coraza Edge WAF v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13; topology refreshed on 2026-08-13

## Decision

Production public application traffic follows one mandatory path:

```text
Internet
-> upstream L3/L4 volumetric mitigation/scrubbing
-> redundant external L4 load balancing
-> Traefik
-> dedicated edge-waf deployment
   Caddy + coraza-caddy + Coraza v3 + OWASP CRS 4.x LTS
-> Web BFF
```

The upstream volumetric-mitigation and redundant-L4 requirements are governed in detail by ADR-0059. A CDN is deployment-specific and does not replace either control.

Traefik is the Kubernetes/Gateway edge router and forwards public application traffic to the dedicated WAF service. Caddy/Coraza is the L7 inspection layer. Direct Internet->BFF or Traefik->BFF application routes that bypass the WAF are prohibited by route design plus NetworkPolicy/Istio authorization.

### WAF policy

- CRS is pinned; no automatic rule update in production;
- Paranoia Level 1 initial baseline;
- >=7 representative days in DetectionOnly before reviewed blocking activation;
- false-positive exceptions are narrow, versioned, owned, reviewed, and expiring where temporary;
- request-body inspection limits are explicit/bounded per endpoint/content class;
- unsupported/oversize content follows an explicit safe route/application policy rather than silent bypass;
- WAF telemetry does not record secrets, credentials, raw sensitive bodies, or unreviewed PII.

WAF is defense in depth and does not replace authentication, resource authorization, CSRF/CORS, semantic quotas, validation, output encoding, provider/network security, or upstream volumetric protection.

### Availability

The WAF deployment is replicated and spread according to the current production HA target. One WAF replica/node loss must not intentionally remove the public application path. Saturation/latency/error telemetry is a release/operations signal because every public request traverses this layer.

## Verification requirements

Verify the exact upstream mitigation/scrubbing -> redundant external L4 load balancing -> Traefik -> WAF -> BFF route, direct-bypass negatives, pinned CRS/image identities, DetectionOnly evidence, reviewed blocking exceptions, body-limit behavior, PII-safe edge logging, one-replica/node loss, load/latency impact, and coexistence with current authentication/authorization/quota controls.

## Rollback considerations

Rollback MUST NOT introduce a direct Traefik/BFF bypass, remove required upstream mitigation or redundant L4 load balancing, silently disable blocking on routes previously approved for blocking, weaken sensitive logging restrictions, or substitute the WAF for current application security controls.
