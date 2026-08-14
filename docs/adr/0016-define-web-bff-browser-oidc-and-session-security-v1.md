# ADR-0016: Web BFF Browser, OIDC, Session, and Public API Security v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13; Identity onboarding/OIDC evidence semantics finalized on 2026-08-14; Web BFF implementation contract finalized on 2026-08-14; Reference Data public facade aligned on 2026-08-14; deployment made profile-aware on 2026-08-14

## Decision

### Public browser/API boundary

Web BFF is the only browser-facing application API boundary. The v1 public REST namespace is:

```text
/api/v1
/api/v1/auth
/api/v1/identity
/api/v1/authorization
/api/v1/reference
```

`/api/v1/reference` is the ADR-0041 public read-only Reference Data facade. Its v1 GET/HEAD responses may be anonymous because the data is global/non-user-specific, but they remain same-origin application API routes behind the mandatory edge/WAF path and create no authentication/session/tenant authority.

Internal gRPC/RPC method names are not exposed mechanically as public paths. OpenAPI remains the authoritative browser REST contract and the public route shape is intentionally decoupled from provider-owned internal RPC naming.

Public JSON request bodies are bounded to 256 KiB. Authentication/OIDC/session request bodies are bounded to 64 KiB. Request headers/metadata are bounded to 16 KiB. Multipart/file upload is not part of the v1 Web BFF contract. Reference Data v1 uses GET/HEAD only and no request body. The existing 2600 ms Web BFF total request budget remains the outer synchronous budget; downstream edges MUST use their stricter registered deadlines where present.

Public errors use an RFC 9457 profile containing `type`, `title`, `status`, stable machine-readable `code`, and only a safe correlation identifier when needed. Public errors MUST NOT expose tenant IDs, membership IDs, Contact identifiers, internal request IDs, provider/source payload/text, exception text/stack traces, tokens, Role/permission internals, source-artifact paths, or security-policy implementation detail.

### Same-origin browser model

Credentialed BFF API traffic is same-origin only in v1. Cross-origin CORS is not enabled. Anonymous Reference Data reads do not create a cross-origin CORS exception. A future cross-origin browser client requires an explicit reviewed architecture change; credentialed wildcard/reflected origins remain prohibited.

Unsafe cookie-authenticated production browser requests require all of:

- trusted exact `Origin` equal to the configured public origin;
- valid session-bound synchronizer CSRF token in `X-CSRF-Token`;
- `Sec-Fetch-Site: same-origin`.

Missing Fetch Metadata on the normal production browser surface fails closed. A future non-browser integration that cannot satisfy this browser contract MUST use a separately reviewed surface rather than weakening the browser route. GET/HEAD/OPTIONS remain side-effect free. Reference Data v1 GET/HEAD does not require CSRF proof because it is side-effect free and anonymous/global; this does not weaken unsafe authenticated routes.

### Google OIDC and browser pre-auth transaction

Authorization Code uses PKCE S256 only.

Exact randomness/encoding contract:

```text
state:          exactly 256 CSPRNG bits
nonce:          exactly 256 CSPRNG bits
PKCE verifier:  exactly 32 CSPRNG bytes, Base64URL without padding
PKCE method:    S256 only
```

BFF binds `state`, `nonce`, PKCE verifier, provider/redirect context, and post-login target to one server-side pre-auth transaction. The browser receives only the temporary opaque cookie:

```text
__Host-sajtech-preauth
Secure
HttpOnly
SameSite=Lax
Path=/
Domain absent
identifier entropy >=256 CSPRNG bits
```

The raw pre-auth identifier is not used as a Redis key or log field. BFF derives the Redis locator with a purpose-separated versioned HMAC. Pre-auth state expires in <=10m, is single-use, and at most five live pre-auth transactions may exist for one browser at a time. Replacement/cleanup cannot resurrect consumed or expired OIDC state.

The post-login return target is a relative same-origin path only. It MUST start with one `/`, be <=1024 characters after canonical validation, and reject absolute/scheme/userinfo forms, `//`, backslash, control characters, and raw or percent-encoded normalization/bypass variants that could become an external or authority-relative destination. Redirect URI matching to the provider is exact; wildcard/open redirect is prohibited.

BFF validates state, nonce, PKCE, provider signature, exact configured issuer/audience, timestamps, redirect binding, and bounded provider claims before invoking Identity. Provider credentials remain inside the approved secret-delivery boundary and do not enter browser storage, Identity requests, Git values, or telemetry.

### Trusted Identity OIDC evidence

Immediately after successful provider validation, BFF creates and submits the typed Identity evidence:

```text
evidence_id        exactly 256 bits CSPRNG
request_id         canonical UUIDv4
evidence_issued_at BFF server time generated after provider validation
issuer             canonical validated issuer
subject            validated provider subject
metadata_version   explicit bounded version
optional metadata  provider-validated email/email_verified + bounded name suggestions only
```

BFF never forwards Google authorization/access/refresh/ID tokens to Identity. Evidence lifetime is fixed by Identity at two minutes; browser/provider input cannot extend it. Evidence is bound to BFF workload identity, request identity, issuer, subject, issuance time and canonical versioned metadata. Identity retains spent/replay evidence for >=10m and applies ADR-0012 replay/conflict behavior.

Google may start a new Identity User through this evidence flow. Provider-verified email is only evidence Identity may use to create a verified Contact when the canonical email is free; collision never auto-links and becomes `ACCOUNT_LINK_REQUIRED`. Missing or `email_verified=false` provider email creates no Identity Contact automatically. Provider names remain suggestions and never silently complete the Identity profile.

For a User with active TOTP, successful Google evidence is only primary-authentication proof. Identity returns the same MFA pre-auth continuation used after password proof; BFF cannot establish a completed session until TOTP or a valid recovery code succeeds. Provider proof never downgrades active MFA.

### Audience-specific Identity token brokerage

Browser routes never receive or select a downstream JWT audience. The BFF maintains a server-owned, reviewed route-to-downstream/audience mapping. For a valid Identity Session/RefreshFamily, BFF obtains a five-minute exact-audience access JWT from an Identity-owned internal operation such as `IssueAudienceAccessToken`.

This operation is an internal application token-broker contract, not a public generic OAuth token-exchange endpoint. Browser input cannot supply an arbitrary audience. Identity accepts only server-allow-listed audiences appropriate to the calling BFF workload and current session/tenant state.

`authenticated_onboarding` state cannot obtain ordinary resource-service or Authorization-management audiences. Only the explicitly reviewed onboarding/Identity surface is available until Identity has validated an active Membership/Tenant. Public ADR-0041 Reference Data reads are not tenant/resource dispatch and create no downstream token authority.

BFF never exposes downstream Identity access JWTs or refresh credentials to browser JavaScript, cookies, local/session storage, URLs, HTML, logs, metrics or error bodies.

The BFF->Identity audience-token operation is an `AUTHORITATIVE_SECURITY` synchronous dependency. Until a stricter operation-specific value is approved, it uses the current generic internal gRPC ceiling:

```text
deadline:        1500 ms maximum
attempts:        1
wait-for-ready:  off
automatic retry: none
fallback/cache:  none
failure mode:    fail closed / authentication dependency unavailable
```

A valid access JWT may be retained only as bounded server-side transport state until its own `exp` and only while the corresponding BFF/Identity session remains valid. This is not an authorization decision cache: final resource authorization remains online in the resource-owning service, and session/tenant/assurance rotation invalidates BFF token reuse.

### Completed BFF session

Production cookie:

```text
__Host-sajtech-session
Secure
HttpOnly
SameSite=Lax
Path=/
Domain absent
```

Session ID has >=256 CSPRNG bits. The raw session ID is not a Redis key and is never logged. BFF derives the server-side locator with a purpose-separated versioned HMAC.

Server-side session state includes only the bounded authentication/session fields needed by the BFF, including:

- User/Identity Session/RefreshFamily identifiers or approved pseudonymous references;
- selected tenant/membership state when present;
- explicit session mode including `authenticated_onboarding` versus tenant-authenticated;
- CSRF-token digest;
- created-at, coalesced last-seen, idle expiry, and immutable absolute expiry;
- encrypted retained Identity refresh credential when present;
- current assurance/security state needed for route policy and safe session rotation.

Session idle lifetime is <=7d, absolute lifetime <=30d, and never exceeds underlying Identity refresh-family validity. Absolute expiry is immutable and is never extended. To limit Redis write amplification, BFF persists `last_seen` at most once per five-minute activity window rather than on every request; the security decision still uses the authoritative idle/absolute bounds.

Every BFF session is linked to exactly one current Identity RefreshFamily. BFF maintains a purpose-HMAC/pseudonymous User->sessions index so logout-all, User suspension/DELETING, erasure, refresh-family reuse/revocation and other Identity-wide revocations can remove all affected browser sessions without scanning arbitrary Redis keys.

BFF session ID rotates after login, MFA completion, tenant switch, recovery, password reset/change where the session remains valid, security/assurance elevation, and observed Identity MFA-state changes that preserve a current session. Rotation is atomic: after the replacement session becomes authoritative, the predecessor session ID is immediately invalid. There is no dual-valid grace window.

Logout invalidates server-side state before reporting success. Identity logout/current-family, logout-all, password/session revocation, ExternalIdentity unlink, MFA-state-change revocation, User suspension/DELETING, refresh-family reuse or expiry invalidates corresponding BFF state when observed. BFF never reconstructs authentication from browser data or from an expired/revoked Identity credential.

### Refresh credential encryption and local key ring

If BFF retains an Identity refresh credential, it is encrypted before persistence using:

```text
algorithm:        AES-256-GCM
nonce:            random 96 bits per encryption
authentication:   128-bit GCM tag
AAD:              session binding + purpose + key identifier/version
key rotation:     every 90 days
```

Old decrypt keys remain available until all dependent sessions have expired/rekeyed plus seven days. Keys are delivered only through the approved BFF-specific mounted secret/key-ring path, are never stored in Git/Redis/browser state, and reload atomically. During a key-source outage, the last fully validated in-memory/on-disk snapshot may be used for at most one hour. After that staleness bound, operations requiring refresh encrypt/decrypt fail closed rather than extending stale-key operation.

### MFA pre-auth and authenticated onboarding

When Identity returns an MFA pre-auth challenge after any primary proof—password or trusted Google evidence—BFF creates no completed authenticated browser session. It retains only bounded pre-auth continuation state. Final authenticated state exists only after Identity confirms required MFA and creates the Identity Session/RefreshFamily result.

When Identity authentication succeeds but no active Tenant/Membership is selected, BFF may create only `authenticated_onboarding` state:

- no normal tenant-scoped Identity access JWT exists;
- browser access is limited to reviewed same-origin Identity onboarding/profile/tenant-create/invitation-accept/tenant-selection routes;
- ordinary resource-service and Authorization-management requests are rejected before downstream dispatch;
- zero Membership remains onboarding; one valid Membership is selected by Identity automatically; multiple use Identity's valid last-selection/explicit-selection rules;
- completing tenant selection rotates BFF session ID and transitions to normal tenant-authenticated state.

Public Reference Data GET/HEAD remains globally readable during onboarding because it creates no tenant/resource authority.

### CSRF token contract

CSRF token is exactly 256 CSPRNG bits and is bound to the current BFF session. BFF persists only a purpose-separated versioned HMAC digest and compares proofs in constant time. The token rotates whenever session/assurance rotation occurs.

There is no separate CSRF cookie. The frontend receives the clear synchronizer value only through the reviewed same-origin authenticated bootstrap/session response needed to set the explicit `X-CSRF-Token` header. The token is not written to persistent browser storage, URLs, logs or telemetry.

### Browser security headers and caching

The v1 Content-Security-Policy is:

```text
default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; manifest-src 'self'; worker-src 'self'
```

`unsafe-eval` and `unsafe-inline` are prohibited. Additional remote origins/directives require an explicit reviewed architecture change rather than runtime reflection.

Baseline also includes HSTS `max-age=31536000` after HTTPS/domain coverage verification, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, restrictive Permissions Policy, and equivalent modern anti-framing protection through CSP `frame-ancestors 'none'`.

Authentication, OIDC/session and authorization-administration responses use `Cache-Control: no-store`. Sensitive response payloads are not cached by BFF/proxies/browser shared caches.

ADR-0041 Reference Data GET/HEAD is the explicit public cacheable exception. A successful representation uses deterministic `ETag` plus `Cache-Control: public, max-age=3600`; representation locale is an explicit canonical `fa`/`en` route/query input rather than hidden cookie/session state. Conditional requests may return `304 Not Modified`. BFF has no server-side stale Reference Data fallback.

### Internal calls and tenant Authorization administration

Internal synchronous calls use gRPC + Protobuf over Istio Ambient strict mTLS/workload identity. Every call has an explicit deadline, cancellation propagation and stable error map. BFF does not create long-running workflows or deep synchronous call chains.

Dependency ownership is explicit in `dependency-criticality.yaml`: Web BFF owns Google OIDC, trusted Identity evidence/session establishment, audience-token brokerage, BFF security Redis, tenant Authorization-management, Reference Data reads, and registered resource-dispatch edges. Identity->Google is not an allowed login/link/signup dependency.

BFF->Identity evidence submission has no retry/fallback. A failed/ambiguous call is resolved only through stable request/evidence idempotency; BFF never creates a second provider identity or alters evidence to force success.

Browser tenant Authorization administration uses the BFF REST/OpenAPI facade and matching Authorization gRPC management operation. BFF uses its approved workload identity and a current Identity access JWT whose audience is exactly `authorization-service`. BFF does not pre-authorize or fabricate management state; Authorization validates the end-user JWT locally and remains management permission/domain authority.

Authorization-management edge remains:

```text
deadline:        1500 ms maximum
attempts:        1
wait-for-ready:  off
automatic retry: none
fallback:        none
failure mode:    fail closed / management unavailable
```

Write requests preserve the canonical UUIDv4 `request_id`. Timeout/ambiguity is not automatically retried; later explicit replay uses the same request identity for Authorization idempotency resolution.

The `/api/v1/reference` facade maps only to ADR-0041 typed Reference Data operations. It never forwards caller-selected dataset/schema/query names.

Reference Data read edge is `AUTHORITATIVE_STATE`:

```text
deadline:        <=1000 ms and <= remaining BFF parent budget
attempts:        1
wait-for-ready:  off
automatic retry: none
fallback/cache:  none on the server side
failure mode:    reference route unavailable; no fabricated/stale server data
```

Inbound cancellation propagates to Reference Data where supported. A later new Reference Data caller requires a separate dependency-registry/workload-authorization decision; the BFF edge does not authorize general service access.

### Authorization boundary

Routine protected-resource request flow does not pay two online Authorization calls. Resource-owning service performs final online `CheckPermission` under ADR-0013/ADR-0026/ADR-0032/ADR-0036. BFF only checks authorization for BFF-owned resources or separately justified UX/read-model use; such checks never replace final resource enforcement.

Tenant-management facade transports browser administration to Authorization-owned management use cases but does not decide them itself. Browser/session/JWT Role or permission lists are never accepted as management authority. `GetMembershipAuthorization` is UX/read-model data only and is never reused as an authoritative protected-resource decision.

Reference Data code existence/lifecycle is not an Authorization or business-validation decision. The consuming bounded context still owns operation eligibility.

`authenticated_onboarding` is not an authorization bypass and is not an implicit Authorization-management allow-list.

### OIDC abuse quotas

OIDC initiation/callback abuse control uses the ADR-0024 atomic semantic-quota mechanism with the same pseudonymization and dual-clock/skew rules. Exact v1 network buckets are:

| Operation | Capacity | Refill | Cleanup horizon |
| --- | ---: | --- | --- |
| `OIDC_START/network` | 60 | 1 token / 5s | 1h |
| `OIDC_CALLBACK/network` | 120 | 2 tokens / 1s | 30m |

The five-live-pre-auth/browser limit is independent and still enforced. Quota dependency failure does not disable OIDC abuse controls; initiation/callback fails closed according to ADR-0024.

### Erasure

BFF is an erasure participant for browser-authentication state. An authoritative Identity/global erasure command removes or irreversibly unlinks all subject-associated:

- completed BFF sessions;
- pre-auth/OIDC transaction state;
- encrypted refresh credentials;
- User->sessions index entries;
- any other user-linked BFF authentication continuation state.

Completion evidence is non-PII and idempotent. Erasure does not require deletion of generic aggregate telemetry that contains no stable user/session/tenant identifier. No user-linked authentication state may remain usable after successful BFF erasure completion. Anonymous Reference Data responses add no subject-linked state.

### Runtime and network isolation

First executable Web BFF implementation uses:

```text
base package:      com.sajtech.webbff
namespace:         platform-apps
Deployment:        web-bff
Service:           web-bff
ServiceAccount:    web-bff
application HTTP:  8080
management:        separate configured port
```

Production deployment target is profile-specific.

`production-single-server` under ADR-0042:

```text
replicas:          1
HPA:               disabled
availability PDB:  disabled
node failover:     none
```

`production-ha`:

```text
minimum replicas:  3
PDB:               minAvailable=2
HPA range:         3..12 only after load/connection evidence
topology spread:   required across available failure domains
```

Pod security context is hardened: non-root, no privilege escalation, drop unnecessary capabilities, read-only root filesystem except explicit writable mounts, seccomp/runtime-default or stronger approved profile, bounded resources and graceful termination.

NetworkPolicy/Istio authorization is deny-by-default. Web BFF production egress is restricted to the exact required destinations:

- Identity Service;
- Authorization management surface;
- Reference Data Service typed read surface when ADR-0041 implementation is active;
- explicitly registered resource services;
- BFF/security Redis;
- configured Google OIDC endpoints;
- approved telemetry backend/collector.

Arbitrary URL/Internet egress is prohibited. Every new synchronous downstream must be entered into the canonical dependency registry with class/deadline/retry/failure behavior before production use. Google is the explicit provider-egress exception and remains allow-listed by configured endpoint policy. Reference Data itself has no runtime standards-source Internet synchronization.

The single-server one-replica target is an availability reduction only. It does not weaken same-origin browser controls, session/Redis fail-closed behavior, exact-audience token brokerage, MFA continuation, CSRF, OIDC replay protection, NetworkPolicy/Istio authorization, or public edge/WAF requirements.

## Verification requirements

Required evidence includes at least:

- OpenAPI namespace/error/request-size contract tests, including explicit `/api/v1/reference` routes;
- Reference Data GET/HEAD anonymous behavior, explicit `fa|en` representation locale, ETag/304, `public, max-age=3600`, no hidden session variance, no stale server fallback, same-origin CORS and mandatory edge/WAF tests;
- PKCE downgrade, exact state/nonce/verifier entropy, state/nonce replay and pre-auth single-use/TTL/live-limit tests;
- exact redirect and return-target canonicalization/open-redirect negatives including encoded bypasses;
- provider validation before Identity invocation and provider-code/token absence from Identity payloads/browser storage/telemetry;
- exact 256-bit OIDC evidence randomness, issued-at binding, two-minute expiry, >=10-minute replay retention, equal replay/changed-payload conflict/wrong-workload negatives;
- verified-email collision/no-auto-link, unverified-email no-Contact, suggestion-only profile behavior;
- active-TOTP Google proof entering MFA continuation; password/Google MFA pre-auth creates no completed session;
- server-owned route->audience mapping, arbitrary browser audience rejection, `authenticated_onboarding` audience-denial, exact downstream audience and no browser JWT exposure;
- session HMAC locator, fixation/atomic rotation/predecessor immediate invalidation, idle/absolute expiry, five-minute last-seen write coalescing, logout/revocation/user-session-index behavior;
- AES-256-GCM nonce/tag/AAD, rotation, old-key retention, atomic reload, <=1h stale-snapshot and fail-closed stale-key tests;
- exact CSRF entropy/digest/constant-time/session-rotation behavior, Origin and `Sec-Fetch-Site` fail-closed negatives;
- no cross-origin credentialed CORS, exact CSP/no unsafe-inline/no unsafe-eval, security headers, private `no-store`, and public Reference Data cache tests;
- request/body/header bounds and multipart rejection/DoS tests;
- OIDC_START/OIDC_CALLBACK quota atomicity/outage/skew tests;
- Redis selected-profile behavior and no reconstruction from browser state;
- Authorization management 1500ms/one-attempt/no-retry/no-fallback/exact-audience behavior and stable request-id replay after ambiguity;
- Reference Data <=1000ms/one-attempt/wait-for-ready-off/no-retry/no-fallback/cancellation, typed route mapping, wrong-workload and unavailable/no-fabrication behavior;
- proof BFF does not locally grant Authorization-management, final protected-resource authority, or business validity merely from a Reference Data record;
- erasure deletion/idempotency/non-PII receipt tests;
- deny-by-default egress/workload-policy/direct-bypass tests including Reference Data allow and unregistered downstream denial;
- PII/secret-safe logging and bounded-metadata verification;
- profile-correct workload render: one replica/HPA off/PDB off and whole-host outage semantics in single-server; >=3/PDB2/topology spread and evidence-gated HPA in HA;
- BDD and Playwright critical authentication/onboarding/administration/reference journeys where implemented.

## Rollback considerations

Rollback MUST NOT weaken public request bounds/error redaction, same-origin/CORS rules, Origin/CSRF/Fetch-Metadata enforcement, PKCE/state/nonce/pre-auth replay protection, exact redirects, evidence entropy/expiry/replay binding, email no-auto-link/unverified-email behavior, active-MFA enforcement, browser/token isolation, server-owned audience brokerage, cookie/session HMAC/rotation/revocation rules, refresh encryption/key staleness controls, tenantless onboarding isolation, Authorization authority, Reference Data typed/cache/no-fabrication/workload controls, OIDC quotas, erasure, deny-by-default egress, or resource-owner final authorization. Moving to `production-single-server` MUST NOT be represented as retaining replicated node-failure availability.