# ADR-0016: Web BFF Browser, OIDC, Session, and Public API Security v1

## Status

Accepted — current effective decision

## Date

2026-08-10; current browser/OIDC/session contract finalized through 2026-08-14; Reference Data deployment and ADR-0024/ADR-0044 alignment updated 2026-08-15

## Decision

### 1. Public browser/API boundary

Web BFF is the only browser-facing application API boundary. V1 public REST namespace is:

```text
/api/v1
/api/v1/auth
/api/v1/identity
/api/v1/authorization
/api/v1/reference
```

`/api/v1/reference` is the ADR-0041 public read-only Reference Data facade. It may be served from the approved immutable bundle inside BFF before the independent-service trigger, or from the typed Reference Data gRPC service after that trigger. Its GET/HEAD responses may be anonymous/global, but remain same-origin application API routes behind the mandatory edge/WAF path and create no authentication/session/tenant authority.

Internal RPC names are not mechanically exposed as public paths. OpenAPI is browser REST authority.

Bounds:

```text
public JSON request body:          <=256 KiB
auth/OIDC/session request body:    <=64 KiB
request headers/metadata:          <=16 KiB
multipart/file upload:             not in v1
BFF outer synchronous budget:      2600 ms
```

Reference GET/HEAD has no request body. Child dependencies use stricter registered deadlines where applicable.

Public errors use RFC 9457 profile with `type`, `title`, `status`, stable `code`, and only safe correlation data when needed. They do not expose tenant/membership/Contact/internal request IDs, provider/source payloads, stack/exception/SQL text, tokens, Role/permission internals, artifact paths, or security-policy detail.

### 2. Same-origin browser model

Credentialed BFF traffic is same-origin only. Cross-origin CORS is disabled. Anonymous Reference Data does not create a CORS exception.

Unsafe cookie-authenticated browser requests require all:

- exact trusted `Origin` equal to configured public origin;
- valid session-bound synchronizer token in `X-CSRF-Token`;
- `Sec-Fetch-Site: same-origin`.

Missing Fetch Metadata on normal production browser surface fails closed. A future non-browser integration uses a separately reviewed surface rather than weakening browser requirements. GET/HEAD/OPTIONS remain side-effect free. Reference Data GET/HEAD requires no CSRF proof because it is side-effect free/global.

### 3. Google OIDC / pre-auth transaction

Authorization Code uses PKCE S256 only.

```text
state:          exactly 256 CSPRNG bits
nonce:          exactly 256 CSPRNG bits
PKCE verifier:  exactly 32 CSPRNG bytes, Base64URL without padding
PKCE method:    S256 only
```

BFF binds state, nonce, verifier, provider/redirect context, and return target to one server-side pre-auth transaction.

Browser receives only:

```text
__Host-sajtech-preauth
Secure
HttpOnly
SameSite=Lax
Path=/
Domain absent
identifier entropy >=256 CSPRNG bits
```

Raw pre-auth ID is not Redis key/log field. BFF uses purpose-separated versioned HMAC locator. Pre-auth TTL <=10m, single-use, max five live transactions/browser. Cleanup/replacement cannot resurrect consumed/expired state.

Return target is relative same-origin path only: exactly one leading `/`, <=1024 characters after canonical validation, no absolute/scheme/userinfo/`//`/backslash/control/raw-or-encoded normalization bypass. Provider redirect URI match is exact; wildcard/open redirect prohibited.

BFF validates state, nonce, PKCE, provider signature, exact issuer/audience, timestamps, redirect binding, and bounded claims before Identity invocation. Provider credentials remain in approved secret boundary and never enter browser storage, Identity request, Git values, or telemetry.

### 4. Trusted Identity OIDC evidence

After provider validation BFF creates:

```text
evidence_id        exactly 256 CSPRNG bits
request_id         canonical UUIDv4
evidence_issued_at BFF server time after provider validation
issuer             canonical validated issuer
subject            validated provider subject
metadata_version   bounded explicit version
optional metadata  validated email/email_verified + bounded name suggestions only
```

Google authorization/access/refresh/ID tokens are never forwarded to Identity. Evidence lifetime is two minutes, bound to BFF workload/request/issuer/subject/time/versioned metadata. Identity retains spent/replay evidence >=10m and applies current replay/conflict rules.

Provider-verified email may create verified Contact only when canonical email is free; collision never auto-links and produces account-link-required behavior. Missing/unverified provider email creates no Contact automatically. Names are suggestions only.

If active TOTP exists, Google evidence is primary proof only. BFF creates no completed session until current TOTP/recovery continuation succeeds. Provider proof never downgrades MFA.

### 5. Audience-specific token brokerage

Browser never selects or receives downstream JWT audience/token.

BFF owns reviewed route->downstream/audience mapping. For a valid Identity session/refresh family it obtains a five-minute exact-audience access JWT through the current internal Identity operation.

Browser input cannot supply arbitrary audience. Identity permits only server-allow-listed audiences for BFF and current session/tenant state.

`authenticated_onboarding` cannot obtain ordinary resource/Authorization-management audiences. Public Reference Data is not tenant/resource dispatch and creates no token authority.

BFF never exposes downstream access JWT/refresh credential to browser JS/cookie/storage/URL/HTML/log/metric/error.

Audience-token dependency remains:

```text
class:           AUTHORITATIVE_SECURITY
deadline:        <=1500 ms
attempts:        1
wait-for-ready:  off
automatic retry: none
fallback/cache:  none
failure:         fail closed / authentication dependency unavailable
```

A valid access JWT may remain only as bounded server-side transport state until its `exp` and while corresponding session remains valid. It is not permission-result cache; resource owner performs final Authorization.

### 6. Completed BFF session

Production cookie:

```text
__Host-sajtech-session
Secure
HttpOnly
SameSite=Lax
Path=/
Domain absent
```

Session ID entropy >=256 CSPRNG bits. Raw ID is not Redis key/log field; locator uses purpose-separated versioned HMAC.

Server-side state is bounded and may include approved references to User/Identity Session/RefreshFamily, selected tenant/membership, session mode, CSRF digest, timestamps/expiry, encrypted retained refresh credential, and required assurance/security state.

```text
idle lifetime:      <=7d
absolute lifetime:  <=30d and never beyond Identity refresh validity
last_seen write:    at most once per 5m activity window
```

Absolute expiry is immutable.

Each BFF session links one current Identity RefreshFamily. BFF maintains purpose-HMAC/pseudonymous User->sessions index for logout-all/suspension/deleting/erasure/reuse/revocation without arbitrary Redis scan.

Session ID rotates after login, MFA completion, tenant switch, recovery, password reset/change when session remains valid, assurance elevation, and observed MFA-state changes that preserve session. Rotation is atomic; predecessor becomes immediately invalid with no dual-valid grace.

Logout invalidates server state before success. Identity revocation/logout/password/session/external-identity/MFA-state/suspension/deleting/reuse/expiry invalidates corresponding BFF state when observed. Browser data never reconstructs authentication.

### 7. Retained refresh encryption

If BFF retains Identity refresh credential:

```text
AES-256-GCM
random 96-bit nonce
128-bit tag
AAD = session binding + purpose + key id/version
normal key rotation = 90d
```

Old decrypt keys remain until all dependent sessions expire/rekey plus seven days. Keys arrive only through approved mounted BFF secret/key-ring path, never Git/Redis/browser/telemetry, and reload atomically.

During key-source outage, last fully validated local snapshot may be used <=1h. After that, operations requiring encrypt/decrypt fail closed.

### 8. MFA pre-auth / authenticated onboarding

MFA pre-auth creates no completed browser session. Final authenticated state exists only after Identity confirms required MFA and creates Session/RefreshFamily.

If authentication succeeds without active Tenant/Membership selection, BFF may create only `authenticated_onboarding`:

- no normal tenant-scoped access JWT;
- only reviewed Identity onboarding/profile/tenant-create/invitation/selection routes;
- ordinary resource and Authorization-management dispatch rejected;
- zero Membership remains onboarding; one valid Membership may be Identity-selected; multiple follow current selection rules;
- completing tenant selection rotates BFF session and transitions to tenant-authenticated state.

Reference Data GET/HEAD remains globally readable because it creates no tenant authority.

### 9. CSRF

CSRF token is exactly 256 CSPRNG bits, bound to current BFF session. Server stores only purpose-separated versioned HMAC digest and compares in constant time. Token rotates with session/assurance rotation.

No separate CSRF cookie. Frontend receives clear synchronizer token only through reviewed same-origin authenticated bootstrap/session response, uses explicit `X-CSRF-Token`, and does not persist it in browser storage/URL/log/telemetry.

### 10. Browser security headers/caching

V1 CSP:

```text
default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; manifest-src 'self'; worker-src 'self'
```

`unsafe-inline` and `unsafe-eval` prohibited. Additional remote origins/directives require reviewed architecture change.

Also apply HSTS `max-age=31536000` after HTTPS/domain coverage verification, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, restrictive Permissions Policy, and CSP anti-framing.

Auth/OIDC/session/authorization-admin responses use `Cache-Control: no-store`.

ADR-0041 Reference Data is the explicit public cacheable exception:

```text
ETag: deterministic representation validator
Cache-Control: public, max-age=3600
```

Representation locale is explicit canonical `fa|en`, not hidden cookie/session state. Conditional GET may return 304. No server-side unreviewed stale fallback.

### 11. Internal calls / Authorization administration

Internal synchronous calls use gRPC + Protobuf over strict workload identity with explicit deadline/cancellation/error map. BFF does not create deep request chains.

BFF->Identity evidence submit has no retry/fallback. Ambiguity is resolved by stable request/evidence idempotency, not altered evidence/duplicate provider identity.

Authorization-management facade uses BFF workload identity plus current Identity access JWT with exact audience `authorization-service`. BFF never pre-authorizes/fabricates management state; Authorization remains authority.

```text
Authorization management deadline: <=1500 ms
attempts: 1
wait-for-ready: off
automatic retry: none
fallback: none
```

Writes preserve canonical UUIDv4 request ID; later explicit replay after ambiguity uses same ID.

### 12. Reference Data local-vs-remote behavior

`/api/v1/reference` exposes only typed ADR-0041 families and never caller-selected dataset/schema/query names.

**Before ADR-0041 independent-service trigger:**

- BFF may serve the approved immutable bundle through an in-process adapter;
- no BFF->Reference Data gRPC dependency exists;
- no Reference Data Service/ServiceAccount/NetworkPolicy edge exists;
- bundle/source/provenance/lifecycle/bounds/caching rules remain identical;
- one route group/journey is not a service trigger.

**After trigger and reviewed migration:**

```text
BFF -> Reference Data
class:           AUTHORITATIVE_STATE
deadline:        <=1000 ms and remaining-parent bounded
attempts:        1
wait-for-ready:  off
automatic retry: none
fallback/cache:  none on server side
failure:         reference route unavailable; no fabricated/stale data
```

Inbound cancellation propagates where supported. Dependency registry/workload policy becomes active for this edge only when the remote service is implemented. Migration removes the competing local serving authority so there are not two sources of truth.

A successful reference lookup never grants authorization/business validity.

### 13. Authorization boundary

Routine protected-resource path does not pay two online Authorization checks. Resource-owning service performs final `CheckPermission`. BFF checks only BFF-owned resources or separately justified UX/read model and never replaces final enforcement.

Management facade transports requests to Authorization-owned use cases. Browser/session/JWT Role/permission lists are never management authority. Authorization UX snapshots are never permission authority.

`authenticated_onboarding` is not an Authorization bypass.

### 14. OIDC semantic quotas

OIDC start/callback use ADR-0024 atomic security quota and trusted ADR-0043 exact client address.

Hard exact-IP policy names/values:

| Operation | Dimension | Capacity | Refill | Cleanup horizon |
| --- | --- | ---: | --- | --- |
| `OIDC_START` | `client_ip_exact` | 60 | 1 / 5s | 1h |
| `OIDC_CALLBACK` | `client_ip_exact` | 120 | 2 / 1s | 30m |

BFF derives exact IPv4 `/32` or IPv6 `/128` hard identity. Separate `/24`/`/64` aggregate pressure is not the sole v1 hard 429 gate.

Five-live-pre-auth/browser limit remains independent.

BFF quota implementation also preserves ADR-0024:

- app/Redis <=2s skew;
- local wall-vs-monotonic common-mode Clock Safety Guard;
- host time-sync readiness + 60s stable re-arm after guard trip;
- `noeviction`, no security TTL reset, bounded cleanup;
- low-cardinality new-bucket allocation guard + >=30% Redis memory reserve;
- `QUOTA_TIME_SOURCE_UNHEALTHY` / `QUOTA_CAPACITY_UNHEALTHY` distinct from normal quota denial;
- no local fail-open fallback.

### 15. Erasure

BFF is erasure participant for browser-auth state. Authoritative Identity/global erasure removes or irreversibly unlinks all subject-associated completed sessions, pre-auth/OIDC state, encrypted refresh credentials, User->sessions index entries, and other user-linked auth continuation state.

Completion evidence is non-PII/idempotent. Generic aggregate telemetry with no stable user/session/tenant identifier is not subject-linked state. No user-linked auth state remains usable after successful BFF erasure. Anonymous Reference Data adds no subject state.

### 16. Runtime/network isolation

Implementation target:

```text
base package:      com.sajtech.webbff
namespace:         platform-apps
Deployment:        web-bff
Service:           web-bff
ServiceAccount:    web-bff
application HTTP:  8080
management:        separate private port
```

Single-server:

```text
replicas: 1
HPA: disabled
availability PDB: disabled
node failover: none
```

HA retains current >=3/PDB2/topology-spread/evidence-gated HPA target.

Pod security: non-root, no privilege escalation, capabilities dropped, read-only root FS except reviewed mounts, RuntimeDefault seccomp, bounded resources, graceful termination.

NetworkPolicy/Istio authorization deny-by-default. Production egress is restricted to exact required destinations:

- Identity;
- Authorization-management surface;
- Reference Data service only after ADR-0041 remote trigger/migration;
- registered resource services;
- security Redis;
- configured Google OIDC endpoints;
- approved ADR-0044 Collector/telemetry path.

Arbitrary Internet/URL egress prohibited. New sync downstream requires registry/contract/security evidence before production.

Single-server availability reduction does not weaken browser/session/MFA/CSRF/OIDC/token/NetworkPolicy/WAF controls.

### 17. Day-One observability

ADR-0044 applies from first executable BFF commit.

Implement structured allow-listed JSON logs, Micrometer request/dependency/session/Redis/OIDC/saturation metrics, OpenTelemetry traces, health/readiness, and owned alerts/dashboard queries.

Trace context from browser is untrusted correlation. Trace/baggage/correlation never becomes session, CSRF, OIDC state, tenant, Authorization, quota, request/idempotency, or audit identity.

Never log/trace/label raw:

- cookie/session/pre-auth/provider/internal token;
- state/nonce/verifier/CSRF secret;
- raw client IP;
- subject/contact identifiers outside explicitly approved audit path;
- full request/response or unreviewed provider/downstream error.

Metric labels are low-cardinality and exclude User/Tenant/Membership/session/request/resource/trace IDs.

Ordinary telemetry exporter/backend failure does not fail an otherwise safe BFF request. Authoritative security/audit follows its durable path.

## Verification requirements

At minimum:

- OpenAPI namespace/error/request/header/body/multipart bounds;
- Reference Data local-bundle mode before trigger and remote typed mode only after trigger; cache/ETag/locale/no-stale/same-origin/edge tests in both applicable modes;
- no remote Reference Data dependency/policy before trigger; clean local-authority removal on remote migration;
- PKCE/state/nonce/verifier entropy/replay/single-use/TTL/live-limit;
- redirect/return-target canonicalization/open-redirect encoded negatives;
- provider validation before Identity and no provider tokens in Identity/browser/telemetry;
- exact OIDC evidence entropy/issued-at/expiry/replay/conflict/wrong-workload;
- verified-email collision/no-auto-link/unverified-email behavior;
- Google active-TOTP continuation/no completed session before MFA;
- server route->audience map/arbitrary-audience denial/onboarding audience limits/no browser JWT exposure;
- session locator/fixation/atomic rotation/revocation/idle/absolute/last-seen/User-session index;
- AES-GCM nonce/tag/AAD/rotation/key retention/reload/stale <=1h/fail-close;
- CSRF entropy/digest/constant-time/rotation + Origin/Fetch Metadata negatives;
- no credentialed cross-origin CORS; exact CSP/no unsafe-inline/no unsafe-eval; security headers; private no-store/public Reference Data cache behavior;
- exact client address + `/32`/`/128` hard quota and separate `/24`/`/64` pressure, NAT/IPv6 cases;
- OIDC quota atomicity, Redis outage, one-clock/common-clock jumps, host-sync/re-arm, cardinality allocation pressure, capacity/time failure distinction;
- Redis selected-profile behavior and no browser authority reconstruction;
- Authorization-management exact audience/deadline/no-retry/stable request replay;
- BFF never locally grants Authorization/resource/business validity;
- erasure idempotency/non-PII receipt;
- deny-by-default egress/workload/direct-bypass tests;
- ADR-0044 PII-safe logs/metrics/traces, context non-authority, private telemetry paths, and backend outage;
- profile-correct workload render and recovery;
- BDD/Playwright critical authentication/onboarding/administration/reference journeys where implemented.

## Rollback considerations

Rollback MUST NOT weaken request bounds/error redaction, same-origin/CORS/Origin/CSRF/Fetch Metadata, PKCE/state/nonce/replay/redirects, evidence binding, no-auto-link, MFA, browser token isolation, route-owned audience brokerage, session HMAC/rotation/revocation, refresh encryption/key staleness, onboarding isolation, Authorization authority, Reference Data trigger/local-vs-remote single-authority rules, exact-IP/common-clock/cardinality quota safety, erasure, deny-by-default egress, Day-One telemetry privacy/non-authority, or resource-owner final authorization. Single-server MUST NOT be presented as replicated node-failure availability.