# ADR-0001: Dedicated Caddy + Coraza Edge WAF v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-14

## Decision

Production public application traffic follows one mandatory path:

```text
Internet
-> upstream L3/L4 volumetric mitigation/scrubbing
-> external L4
-> repository-pinned Traefik
-> dedicated edge-waf deployment
   Caddy + coraza-caddy + Coraza v3 + OWASP CRS 4.x LTS
-> Web BFF
```

The upstream volumetric-mitigation/external-L4 requirements are governed in detail by ADR-0029. A CDN is deployment-specific and does not replace either control.

Traefik is the Kubernetes/Gateway edge router and forwards public application traffic to the dedicated WAF service. Caddy/Coraza is the L7 inspection layer. Direct Internet->BFF or Traefik->BFF application routes that bypass the WAF are prohibited by route design plus NetworkPolicy/Istio authorization.

K3s bundled Traefik/ServiceLB is disabled in `production-single-server`; the repository-pinned edge deployment remains authority.

### WAF policy

- CRS is pinned; no automatic rule update in production;
- Paranoia Level 1 initial baseline;
- >=7 representative days in DetectionOnly before reviewed blocking activation;
- false-positive exceptions are narrow, versioned, owned, reviewed and expiring where temporary;
- request-body inspection limits are explicit/bounded per endpoint/content class;
- unsupported/oversize content follows an explicit safe route/application policy rather than silent bypass;
- WAF telemetry does not record secrets, credentials, raw sensitive bodies or unreviewed PII.

WAF is defense in depth and does not replace authentication, resource authorization, CSRF/CORS, semantic quotas, validation, output encoding, provider/network security or upstream volumetric protection.

### Availability by profile

`production-single-server` runs the edge/WAF path on the one physical server with one effective workload replica per component unless an explicit measured local-concurrency exception exists. It has no node-level WAF availability claim. Host/node failure may remove the public application path; the WAF MUST NOT be bypassed to restore traffic.

`production-ha` uses replicated edge/WAF workloads spread according to the current HA target. One WAF replica/node loss must not intentionally remove the public application path.

In both profiles, saturation/latency/error telemetry is a release/operations signal because every public application request traverses this layer.

## Verification requirements

Both profiles verify the exact upstream mitigation/scrubbing -> external L4 -> repository Traefik -> WAF -> BFF route, direct-bypass negatives, pinned CRS/image identities, DetectionOnly evidence, reviewed blocking exceptions, body-limit behavior, PII-safe edge logging, load/latency impact and coexistence with current authentication/authorization/quota controls.

`production-single-server` additionally verifies K3s bundled edge components are disabled, the one-host edge/WAF resource cost is included in complete-stack capacity/reboot tests, and WAF unavailability never creates a bypass path.

`production-ha` additionally verifies replicated WAF placement and one-replica/node loss.

## Rollback considerations

Rollback MUST NOT introduce a direct Traefik/BFF bypass, remove required upstream mitigation/external L4, silently disable blocking on routes previously approved for blocking, weaken sensitive logging restrictions, or substitute the WAF for current application security controls. Moving to the single-server profile requires explicit non-HA availability acceptance and MUST NOT be described as WAF node-failure tolerant.
