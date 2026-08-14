# Web BFF Architecture

## 1. Responsibility and executable boundary

Web BFF is the browser-facing backend boundary. It translates REST/OpenAPI browser interactions into internal gRPC calls and owns browser session, OIDC, CSRF, public error/bounds, public Reference Data facade, and downstream credential brokerage mechanics.

It does not become a second Domain layer. Business invariants and final protected-resource authorization remain in backend bounded contexts. Reference Data canonical metadata remains owned by Reference Data Service; BFF only exposes the approved public read facade.

Base package: `com.sajtech.webbff`.

The first executable implementation lives at `services/web-bff` and follows the independent-service build/release boundary from the engineering standards.

OpenAPI is the authoritative browser/public REST contract. Frontend TypeScript clients are generated from the approved OpenAPI definition; handwritten duplicate transport DTO/client layers are prohibited except thin UI/domain wrappers around generated clients.

## 2. Public path, namespace, bounds, and errors

```text
Internet
-> upstream L3/L4 volumetric mitigation/scrubbing
-> redundant external L4 load balancing
-> Traefik
-> dedicated Caddy + Coraza WAF
-> Web BFF
```

Direct Internet->BFF and Traefik->BFF application paths are prohibited by routing plus NetworkPolicy/Istio authorization. A CDN is deployment-specific and does not replace mandatory upstream volumetric mitigation or load-balancer controls.

The v1 public REST namespace is:

```text
/api/v1
/api/v1/auth
/api/v1/identity
/api/v1/authorization
/api/v1/reference
```

The `/api/v1/reference` subspace is the explicit public read-only facade for ADR-0041 Reference Data. Initial routes include countries, currencies, time zones and supported locales. Provider-owned gRPC method names are not mechanically exposed as public REST paths. Public route design remains explicit OpenAPI API design.

Request limits:

```text
public JSON body:       <=256 KiB
auth/OIDC/session body: <=64 KiB
headers/metadata:       <=16 KiB
multipart/file upload:  unsupported in v1
BFF total request:      <=2600 ms outer budget
```

Reference Data v1 uses GET/HEAD only and does not accept request bodies. Oversized requests are rejected before expensive parsing/downstream work. The BFF does not buffer unbounded bodies or provider responses.

Public REST errors use the v1 RFC 9457 profile with:

```text
type
title
status
code
safe correlation identifier only when needed
```

Public errors never expose internal exception/provider text, stack traces, access/refresh/provider tokens, tenant/membership/Contact identifiers, internal request IDs, Redis keys, Role/permission internals, source-artifact paths, or security-policy implementation detail.

## 3. OIDC and pre-auth browser transaction

ADR-0016 is authoritative; ADR-0012 defines trusted BFF->Identity evidence/signup/link semantics.

Google/future browser login uses Authorization Code + PKCE S256.

Exact entropy/encoding:

```text
state:          exactly 256 CSPRNG bits
nonce:          exactly 256 CSPRNG bits
PKCE verifier:  exactly 32 CSPRNG bytes, Base64URL without padding
PKCE method:    S256 only
```

One server-side pre-auth transaction binds state, nonce, PKCE verifier, provider/redirect context and post-login target. Browser receives only:

```text
__Host-sajtech-preauth
Secure; HttpOnly; SameSite=Lax; Path=/; no Domain
opaque identifier entropy >=256 CSPRNG bits
```

The raw pre-auth identifier is not a Redis key and is never logged. BFF derives the Redis locator with a purpose-separated versioned HMAC. Server-side pre-auth state expires <=10m, is single-use, and at most five live transactions may exist for one browser. Expired/consumed state cannot be revived by retry or replacement.

Provider callback validates state, nonce, PKCE, signature, configured issuer/audience, timestamps, redirect binding and bounded claims before invoking Identity. Redirect URI matching is exact.

Post-login return target must be a same-origin relative path beginning with one `/`, <=1024 characters after canonical validation. Reject `//`, backslash, scheme/userinfo/authority forms, control characters, and raw/encoded normalization bypasses that could become an external redirect.

BFF is application owner of provider-protocol validation. Identity does not call Google during login/link/signup and does not receive provider authorization codes or provider tokens.

Immediately after successful provider validation, BFF creates Identity evidence:

```text
evidence_id        exactly 256 bits CSPRNG
evidence_issued_at BFF server timestamp generated after validation
issuer             canonical validated issuer
subject            validated provider subject
request_id         canonical UUIDv4 BFF logical request identity
metadata_version   explicit bounded version
metadata           optional validated email + email_verified + bounded given/family-name suggestions
```

BFF workload identity, evidence ID, issuance time, issuer, subject, request identity and metadata are part of Identity evidence/idempotency binding. Evidence lifetime is exactly two minutes from trusted issuance time and spent/replay evidence remains >=10m in Identity.

Provider token is never forwarded as evidence. Email equality never authorizes auto-link. `email_verified=true` may only be forwarded as validated evidence Identity can use when canonical Contact is free; collision becomes `ACCOUNT_LINK_REQUIRED`. Missing/false verified email creates no Contact automatically. Provider names remain suggestions.

For an existing User with active TOTP, Google evidence is only primary-authentication proof. Identity returns MFA pre-auth continuation and no completed Identity/BFF session exists until TOTP or valid recovery code succeeds.

Provider credentials remain inside approved secret-delivery boundary and do not enter browser storage, Identity requests, Git values or telemetry.

## 4. Identity audience-specific token brokerage

The browser never receives an Identity access/refresh credential and never chooses a downstream JWT audience.

BFF owns a server-configured, reviewed route->downstream/audience mapping. After validating a BFF session, it requests a short-lived exact-audience access JWT through the Identity-owned internal token-broker operation `IssueAudienceAccessToken` (name may be reflected exactly in canonical Protobuf when implemented).

Contract:

- caller must be the authorized BFF workload;
- source Identity Session/RefreshFamily must be active and bound to the BFF session;
- target audience must be in Identity's server-owned allow-list for the BFF workload and session mode;
- tenant/membership context is derived from authoritative Identity session state, not browser claims;
- issued JWT uses the existing exact five-minute Identity access-token lifetime and claim baseline;
- browser-supplied arbitrary audience is rejected and is never forwarded as authority;
- this is not a public generic OAuth token-exchange endpoint.

`authenticated_onboarding` cannot obtain ordinary resource-service or `authorization-service` audiences. Its route allow-list remains only the reviewed Identity onboarding/profile/tenant-create/invitation-accept/tenant-selection surface.

BFF may retain an issued access JWT only as bounded server-side transport state until that JWT's own `exp` and only while corresponding session/tenant/assurance state is unchanged and valid. This is not an Authorization decision cache. Session/tenant/assurance rotation invalidates reuse, and resource-owning service still performs final online `CheckPermission`.

Dependency contract:

```text
dependency:      Identity IssueAudienceAccessToken
class:           AUTHORITATIVE_SECURITY
deadline:        1500 ms maximum
attempts:        1
wait-for-ready:  off
automatic retry: none
fallback/cache:  none for security decision
failure mode:    fail closed / authentication dependency unavailable
```

## 5. Completed browser session

Browser receives only:

```text
__Host-sajtech-session
Secure; HttpOnly; SameSite=Lax; Path=/; no Domain
```

BFF session ID has >=256 CSPRNG bits. Raw session ID is never a Redis key, metric label or log field. BFF derives its server-side session locator with a purpose-separated versioned HMAC.

Bounded server-side session state includes:

- User/Identity Session/RefreshFamily references required for authentication continuity;
- active tenant/membership context when present;
- session mode (`authenticated_onboarding` or tenant-authenticated);
- CSRF digest;
- created-at, coalesced last-seen, idle expiry and immutable absolute expiry;
- encrypted retained Identity refresh credential when present;
- bounded assurance/security state required for routing and safe rotation.

Raw email/phone/provider subject is not session-key material. Session telemetry remains identifier-safe per logging policy.

Session lifetime:

```text
idle:      <=7 days
absolute:  <=30 days
absolute expiry: never extended
underlying Identity RefreshFamily expiry: always an upper bound
last_seen persistence: at most once per five-minute activity window
```

The five-minute write-coalescing rule limits Redis amplification without turning last-seen state into a client authority.

Every completed BFF session maps to exactly one current Identity RefreshFamily. BFF maintains a purpose-HMAC/pseudonymous User->sessions index so logout-all, suspension, DELETING/erasure and family-wide revocation remove all corresponding BFF sessions without unbounded Redis scanning.

Session ID rotates after login, MFA completion, tenant switch, recovery, password reset/change where current session remains valid, security/assurance elevation and observed Identity MFA-state change that preserves a session.

Rotation is atomic. Once replacement session state is authoritative, predecessor session ID is invalid immediately; no dual-valid grace period exists.

Identity current-family logout, logout-all, password reset/change revocation, ExternalIdentity unlink revocation, MFA-state-change revocation, User suspension/DELETING, RefreshFamily reuse/expiry/revocation invalidates corresponding BFF session/continuation when observed. BFF never manufactures continuity from revoked/failed Identity refresh state.

## 6. Refresh encryption and BFF key ring

Any retained Identity refresh credential is encrypted before Redis persistence:

```text
AES-256-GCM
random 96-bit nonce per encryption
128-bit authentication tag
AAD = session binding + purpose + key-id/version
key rotation = every 90 days
```

Old decrypt keys remain available through the lifetime/rekeying of dependent sessions plus seven days. Keys are mounted only from the approved BFF-specific secret/key-ring boundary and never stored in Git, Redis or browser data.

Key-ring reload is atomic: a partially validated replacement cannot replace the active snapshot. During key-source outage BFF may use the last fully validated snapshot for <=1h. Once snapshot staleness exceeds one hour, operations requiring refresh encrypt/decrypt fail closed rather than extending stale-key use.

Key identifiers may be logged only when they do not reveal secret material and are useful for rotation diagnosis; plaintext credential, nonce+ciphertext payload and AAD identifiers containing session/user IDs are not logged.

## 7. MFA pre-auth and authenticated onboarding

When Identity returns a pre-auth MFA challenge after password or trusted Google evidence, BFF creates no completed normal or onboarding session. It retains only bounded pre-auth continuation state. Final authenticated state exists only after Identity confirms required MFA and creates Identity Session/RefreshFamily result.

When Identity authentication succeeds but no active Tenant/Membership is selected, BFF may create only `authenticated_onboarding`:

- no normal tenant-scoped Identity access JWT;
- only reviewed same-origin Identity onboarding/profile/tenant-create/invitation-accept/tenant-selection routes;
- ordinary resource-service and Authorization-management requests rejected before dispatch;
- zero Membership remains onboarding; one valid Membership selected automatically by Identity; multiple use Identity revalidated last-selection/explicit-selection rules;
- completing tenant selection rotates BFF session ID and transitions to tenant-authenticated state.

Public `/api/v1/reference` reads are not a tenant/resource operation and may remain available anonymously during onboarding; they create no tenant/session authority and do not permit resource/Authorization dispatch.

## 8. CSRF, Fetch Metadata, CORS, CSP and cache controls

CSRF synchronizer token is exactly 256 CSPRNG bits and bound to current BFF session. BFF stores only a purpose-separated versioned HMAC digest and compares proofs in constant time. Token rotates with every session/assurance rotation.

There is no CSRF cookie. Frontend receives clear token only from reviewed same-origin session/bootstrap response for use in explicit `X-CSRF-Token` header. It is not persisted in local/session storage or URLs.

Unsafe cookie-authenticated production browser requests require:

```text
Origin: exact configured same origin
X-CSRF-Token: valid session-bound proof
Sec-Fetch-Site: same-origin
```

Missing/invalid Fetch Metadata on normal production browser routes fails closed. GET/HEAD/OPTIONS do not mutate business state. Public Reference Data v1 uses GET/HEAD only and therefore does not require CSRF proof. A future non-browser integration that cannot meet the relevant contract uses a separately reviewed surface.

CORS v1: disabled for cross-origin credentialed API use. Same-origin only. Anonymous Reference Data does not create a cross-origin CORS exception. Future cross-origin use requires architecture review; wildcard/reflected credentialed origin remains prohibited.

Exact CSP:

```text
default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; manifest-src 'self'; worker-src 'self'
```

`unsafe-eval` and `unsafe-inline` are prohibited. Additional external sources/directives require explicit review.

Also enforce HSTS `max-age=31536000` after HTTPS-domain coverage verification, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, restrictive Permissions Policy and no framing via CSP.

Authentication/OIDC/session/Authorization-administration responses use `Cache-Control: no-store`. ADR-0041 public Reference Data responses are the explicit v1 cacheable exception and use deterministic `ETag` plus `Cache-Control: public, max-age=3600`; their representation locale is explicit (`fa` or `en`) and never derived from hidden session/cookie state.

## 9. Semantic OIDC abuse quotas

BFF owns semantic OIDC quotas in its isolated `security-redis` ACL/key namespace and follows ADR-0024 atomic/pseudonymous/dual-clock/fail-closed rules.

Exact network buckets:

| Operation | Capacity | Refill | Cleanup horizon |
| --- | ---: | --- | --- |
| `OIDC_START/network` | 60 | 1 token / 5s | 1h |
| `OIDC_CALLBACK/network` | 120 | 2 tokens / 1s | 30m |

The independent max-five-live-pre-auth/browser rule still applies. Redis/time-source failure does not bypass OIDC abuse control.

## 10. Internal calls and tenant Authorization administration

Internal synchronous calls use gRPC + Protobuf over Istio Ambient strict mTLS/workload identity. Every call has explicit deadline/cancellation/error map. BFF does not create long-running workflows or deep synchronous call chains.

Dependency ownership is explicit in `dependency-criticality.yaml`: browser-flow Google OIDC, trusted Identity evidence/session establishment, Identity audience-token brokerage, session/quota Redis, Authorization-management, Reference Data reads, and registered resource dispatch edges are BFF-owned. Identity->Google is prohibited.

BFF->Identity evidence submission has no retry/fallback. Ambiguity is resolved only through stable request/evidence idempotency; BFF never creates second provider identity or changes evidence to force success.

Tenant Authorization administration is a real BFF synchronous edge. Browser invokes `/api/v1/authorization`; BFF maps it to matching Authorization gRPC management operation using approved workload identity and current Identity JWT with exact `aud=authorization-service`.

BFF does not pre-authorize or fabricate management state. Authorization validates end-user JWT locally and remains management/domain authority.

Authorization-management edge:

```text
deadline:        1500 ms maximum
attempts:        1
wait-for-ready:  off
automatic retry: none
fallback:        none
failure mode:    fail closed / management unavailable
```

Write requests preserve canonical UUIDv4 `request_id`. Timeout/ambiguity is not retried automatically; later explicit replay uses same request identity so Authorization idempotency can resolve committed original or stable conflict.

Reference Data facade maps only explicit `/api/v1/reference` read routes to typed Reference Data gRPC operations. It does not forward caller-selected dataset names or generic query/schema strings.

Reference Data edge:

```text
class:           AUTHORITATIVE_STATE
deadline:        <=1000 ms and <= remaining parent budget
attempts:        1
wait-for-ready:  off
automatic retry: none
fallback/cache:  none on the server side
failure mode:    reference data unavailable; no fabricated/stale server result
```

Inbound cancellation propagates to the gRPC call where supported. The BFF may rely on normal browser/HTTP validators for successful immutable public representations but does not reconstruct current reference data locally on service failure.

## 11. Final Authorization boundary

Routine protected-resource flow does not pay two online Authorization calls. Resource-owning service performs final online `CheckPermission` under ADR-0013/ADR-0026/ADR-0032/ADR-0036. BFF only checks authorization for BFF-owned resources or separately justified UX/read-model use; such checks never replace final enforcement.

Tenant-management facade transports browser administration to Authorization-owned use case. Browser/session/JWT Role/permission lists are not management authority. `GetMembershipAuthorization` responses are administration/UX snapshots only and never authoritative access-control cache.

Reference Data existence/lifecycle is also not authorization or domain acceptance authority. A resource/domain service still validates its own business policy for country/currency/time-zone/locale usage.

`authenticated_onboarding` is not an authorization bypass and never gains Authorization-management implicitly.

## 12. Erasure

BFF participates in Identity-owned global erasure workflow. An authoritative erasure command removes or irreversibly unlinks all subject-associated:

- completed BFF sessions;
- pre-auth/OIDC transaction state;
- encrypted refresh credentials;
- User->sessions index entries;
- other user-linked authentication continuation/token-broker state.

Participant processing is idempotent. Completion receipt contains no PII/stable user/session identifier beyond approved pseudonymous workflow evidence. Successful erasure leaves no usable user-linked BFF authentication state. Generic aggregate telemetry without stable subject/session/tenant identity does not require deletion.

Anonymous Reference Data responses contain no subject-linked state and create no additional erasure participation.

## 13. Runtime/deployment and egress

Production defaults:

```text
namespace:         platform-apps
Deployment:        web-bff
Service:           web-bff
ServiceAccount:    web-bff
application HTTP:  8080
management:        separate configured port
replicas:          >=3
PDB minAvailable:  2
HPA:               3..12 only after load/connection evidence
```

Hardened pod security context: non-root, no privilege escalation, drop unnecessary capabilities, read-only root filesystem except explicit writable mounts, approved seccomp profile, bounded CPU/memory/ephemeral resources and graceful termination.

Deny-by-default NetworkPolicy/Istio policy permits production egress only to:

- Identity Service;
- Authorization management surface;
- Reference Data Service typed read surface when ADR-0041 implementation is active;
- resource services explicitly registered for BFF routes;
- BFF/security Redis;
- configured Google OIDC endpoints;
- approved telemetry backend/collector.

Arbitrary URL/Internet egress is prohibited. New synchronous downstream must be added to canonical dependency registry with class/deadline/retry/failure action before production. Google remains explicit provider-egress exception with configured endpoint allow-list. Reference Data itself has no standards-source Internet synchronization path.

Liveness proves local runtime progress only. Readiness requires usable session/key configuration and required entry-point prerequisites, but does not synchronously probe every downstream on every health request. HPA production enablement requires load evidence that includes HTTP/gRPC connection pools, Redis throughput, crypto cost and downstream bulkheads.

## 14. Failure behavior

BFF never fabricates successful business/authentication/reference state when a dependency is unavailable.

- session Redis unavailable -> authentication/session continuity fails closed;
- semantic quota Redis/time unhealthy -> covered OIDC operation fails closed;
- Identity evidence/token-broker unavailable -> auth/token operation unavailable, no fabricated session/JWT;
- Authorization management deny/unavailable/overload/idempotency conflict remains distinct in public stable mapping;
- Reference Data unavailable/incompatible/overloaded -> affected `/api/v1/reference` route returns stable unavailability; no local stale/fabricated reference list;
- Google/OIDC failure never falls back to email auto-link, browser-stored provider token, SMS downgrade of active TOTP or fabricated authenticated state;
- stale key snapshot beyond one hour -> refresh-key-dependent operation fails closed;
- onboarding state without valid server-side state is not reconstructed from browser input.

Cancellation propagates from inbound HTTP through owned gRPC/Redis/provider calls. No automatic retry is added to non-idempotent, authoritative-security, or Reference Data authoritative-state operations outside their explicit contracts.

## 15. Verification

Required evidence includes:

- OpenAPI `/api/v1` namespace including explicit `/reference` routes and generated-client contract tests;
- Reference Data GET/HEAD anonymous semantics, explicit `fa|en` representation locale, no implicit cookie/session variance, deterministic ETag/304 and `Cache-Control: public, max-age=3600` tests;
- proof `/reference` does not add cross-origin credentialed CORS, bypass WAF/edge controls, create session/JWT authority, or permit unsafe method side effects;
- RFC 9457 profile/redaction and body/header/multipart bound tests;
- state/nonce/verifier exact entropy, PKCE downgrade, pre-auth HMAC/TTL/single-use/max-five, replay and open-redirect/encoded-bypass tests;
- provider validation before Identity call; provider-code/token absence from Identity/browser/telemetry;
- evidence randomness/issued-at/two-minute expiry/ten-minute replay/equal-replay/changed-payload/wrong-workload tests;
- Google verified-email collision/no-auto-link/unverified-email no-Contact/name-suggestion and active-TOTP continuation tests;
- server-owned route->audience mapping, arbitrary audience rejection, exact downstream audience, onboarding audience denial, no browser JWT/refresh exposure;
- session HMAC locator, atomic rotation/no grace, five-minute last-seen coalescing, idle/absolute limits, logout/revocation/user-session-index behavior;
- AES-GCM nonce/tag/AAD, 90-day rotation, dependent-session+7d decrypt retention, atomic reload and one-hour stale snapshot fail-closed tests;
- CSRF exact entropy/HMAC/constant-time/rotation, Origin and Fetch Metadata failure tests;
- no cross-origin credentialed CORS, exact CSP/no unsafe-inline/eval, security headers, private `no-store`, and public-reference cache tests;
- OIDC quota numeric/atomic/outage/skew tests and max-five pre-auth composition;
- Redis failover/session fail-closed behavior;
- BFF->Identity/Authorization exact dependency deadlines/one-attempt/no-retry/no-fallback and cancellation propagation;
- BFF->Reference Data <=1000ms/one-attempt/wait-for-ready-off/no-retry/no-fallback, typed-route mapping, cancellation and unavailable/no-fabrication tests;
- Authorization management exact audience/request-id ambiguity replay/wrong-workload negatives;
- proof BFF does not locally grant management/final resource authority or domain validity from Reference Data existence;
- erasure cleanup/idempotency/non-PII receipt;
- deny-by-default egress including Reference Data allow and wrong/unregistered downstream denial, wrong-workload and direct-edge-bypass tests;
- PII/secret-safe logs/metrics/traces;
- BDD critical flows and Playwright authentication/onboarding/administration/reference journeys where implemented.

Implementation/runtime/build/staging evidence remains `NOT VERIFIED` until `services/web-bff`, Reference Data implementation when triggered, and required environment artifacts exist and these checks execute.
