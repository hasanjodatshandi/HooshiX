# Web BFF Architecture

## 1. Responsibility and executable boundary

Web BFF is the only public browser application backend. It owns browser OIDC orchestration, server-side session/pre-auth state, CSRF/origin/browser security, exact route-to-audience brokerage, public REST contracts, and trusted edge-derived client-address context.

It is not Identity, Authorization, or resource-domain authority. It does not copy downstream business/domain/persistence models.

Implementation target:

```text
services/web-bff
base package: com.sajtech.webbff
```

## 2. Public path and browser security

All public application traffic follows:

```text
Internet
-> upstream volumetric mitigation
-> external L4
-> Traefik
-> Caddy/Coraza WAF
-> Web BFF
```

Direct Internet->BFF and Traefik->BFF application bypass are prohibited.

Current ADR-0016 browser rules remain authoritative, including:

- Authorization Code + PKCE S256;
- exact state/nonce/pre-auth/replay controls;
- server-side session and refresh/provider credential custody;
- browser receives no downstream/provider access/refresh tokens;
- HttpOnly/Secure/SameSite cookie policy;
- CSRF + Origin + Fetch Metadata + same-origin CORS;
- strict CSP/cache/security headers;
- server-owned exact route->audience map;
- arbitrary browser-selected downstream audience prohibited.

Frontend/library choices MUST NOT require `unsafe-inline`/`unsafe-eval` as a convenience workaround around the approved CSP.

## 3. Trusted exact client address

ADR-0043 is authoritative.

BFF accepts one internal `X-HooshiX-Client-IP` only on the WAF-only trusted ingress path. It never trusts public `Forwarded`, `X-Forwarded-*`, `X-Real-IP`, or caller-provided private client-IP headers.

Input is exactly one canonical IP literal; malformed/list/hostname/port/CIDR/zone/proxy-address cases fail closed when the operation requires network quota. IPv4-mapped IPv6 normalizes to IPv4.

When a backend owns the quota, BFF forwards a typed **exact binary address + family** only to the approved backend workload/operation. It does not pre-collapse to `/24` or `/64`.

The quota-owning service derives ADR-0024 dimensions:

```text
client_ip_exact:          IPv4 /32 or IPv6 /128 hard gate
client_network_aggregate: IPv4 /24 or IPv6 /64 pressure only in v1
```

BFF-owned OIDC start/callback quotas use the same trusted exact address policy and ADR-0024 common-clock/cardinality rules.

Raw client IP is transient and MUST NOT appear in ordinary logs, traces, metrics, Redis key material before HMAC, Kafka, or durable business state.

## 4. Sessions and token brokerage

Session/pre-auth state remains server-side Redis authority under current ADRs. Browser cookie alone never reconstructs authenticated state.

Session creation/rotation/revocation/idle/absolute lifetime, user-session index, retained refresh encryption/key-ring behavior, and OIDC pre-auth limits remain under current ADR-0016/Identity contracts.

For protected backend dispatch:

1. validate browser session and route policy;
2. derive server-owned target audience;
3. obtain/refresh bounded Identity-issued audience token through approved internal contract;
4. call only the registered backend/operation;
5. never expose token to browser;
6. never treat token issuance as final resource authorization.

Protected resource services still perform final online Authorization and local resource/domain invariants.

## 5. Internal dependency semantics

Every BFF internal edge is registered with exact workload identity, criticality/failure action, finite child deadline inside the outer request budget, one retry owner, bounded concurrency, cancellation, and no unreviewed fallback.

Authoritative security dependencies do not use automatic retry/stale allow.

Reference Data:

- before ADR-0041 independent-service trigger, BFF may serve the approved immutable reference bundle through an in-process adapter;
- this creates no gRPC dependency or new service deployment;
- after trigger, BFF may switch to the reviewed typed Reference Data gRPC edge;
- one journey/route group is not itself the service trigger;
- there must not be two competing reference-data authorities after migration.

## 6. Authentication/Authorization boundary

BFF authenticates browser session and validates request/browser protocol controls. It is not final tenant/resource permission authority.

For Authorization-management REST facade, BFF sends only current server-controlled authenticated context/token to Authorization. Roles/permissions submitted by browser are never trusted authority.

Safe local checks may reject malformed/obviously unauthorized protocol state but cannot fabricate ALLOW reserved for resource owner/Authorization.

## 7. Redis and semantic quotas

BFF session/pre-auth and BFF-owned security quota use approved security Redis.

ADR-0024 requirements apply:

- TLS/ACL/`noeviction`/AOF single-server baseline;
- exact `/32`/`/128` hard client identity;
- separate `/24`/`/64` aggregate pressure;
- app/Redis skew + local wall-vs-monotonic Clock Safety Guard;
- boot host-sync and 60s stable re-arm;
- no security TTL reset;
- bounded cleanup;
- low-cardinality new-state allocation guard and `QUOTA_CAPACITY_UNHEALTHY` before eviction/OOM;
- no local fail-open fallback.

A quota time/capacity/Redis failure returns stable availability behavior distinct from normal 429 denial.

## 8. Public Reference Data facade

When implemented, read-only reference routes may exist under `/api/v1/reference`.

Before independent service trigger they may use BFF-local approved immutable bundle. After trigger they may use the independent service.

Reference responses may be anonymous/public-cacheable only under ADR-0041 semantics. They still traverse edge/WAF, do not enable credentialed cross-origin CORS, and do not inherit private auth/session `no-store` semantics where ADR-0041 permits public cache validators.

## 9. Runtime identity and workload security

```text
namespace:      platform-apps
Deployment:     web-bff
Service:        web-bff
ServiceAccount: web-bff
application:    HTTP/REST
management:     separate private port
```

Only Caddy/Coraza may reach application ingress. Internal egress is deny-by-default and limited to registered services, Redis, provider endpoints required by BFF-owned OIDC/token flow, DNS, and ADR-0044 telemetry.

Strict Ambient mTLS/dedicated ServiceAccount/NetworkPolicy/Istio authorization apply.

Single-server uses one replica/HPA off/availability PDB off. HA uses current replicated target.

## 10. Day-One observability

ADR-0044 applies from first implementation commit.

BFF implements structured safe JSON logs, Micrometer route/dependency/session/Redis/provider/saturation metrics, OpenTelemetry traces, and alerts/dashboard ownership.

Trace context from browser is untrusted correlation input. BFF may accept/continue only reviewed bounded trace context; caller trace/baggage never becomes session, CSRF, OIDC state, tenant, Authorization, quota, idempotency, or audit identity.

Do not log/trace/label:

- cookie/session/pre-auth/provider/internal token material;
- state/nonce/code verifier;
- CSRF secret;
- raw client IP;
- User/Tenant/Membership/contact/resource/request IDs as metric labels;
- full request/response bodies or unreviewed downstream/provider errors.

Required bounded signals include route class, outcome/latency, in-flight saturation, downstream deadline/cancellation/outcome, Redis latency/time/capacity health, OIDC/provider outcome categories, client-address trust failures, and telemetry-export health without subject labels.

Ordinary telemetry exporter/backend outage does not fail a safe business request. Security/audit evidence that is authoritative follows its durable path.

## 11. Verification

BFF evidence covers:

- real edge/WAF-only ingress and direct bypass negatives;
- forged forwarding/client-IP headers and exact address parsing;
- exact hard vs aggregate pressure quota behavior including NAT/IPv6 cases;
- common-mode quota clock and cardinality failure behavior;
- OIDC PKCE/state/nonce/pre-auth/replay/redirect;
- server-side session/token custody/rotation/revocation;
- CSRF/Origin/Fetch Metadata/CORS/CSP/cache/security headers;
- route->audience mapping and browser arbitrary-audience denial;
- downstream workload/deadline/cancellation/no-stale-authority behavior;
- Reference Data local-vs-remote trigger/migration consistency;
- Day-One logs/metrics/traces, correlation non-authority, canary privacy, and telemetry-backend outage;
- strict mTLS/NetworkPolicy/Istio positive/negative paths;
- profile-correct container/GitOps/load/recovery behavior.

The BFF is not repository-complete until applicable source/contracts/tests/build/deploy/security/observability/CI artifacts exist. Current runtime evidence remains `NOT VERIFIED` until they execute.