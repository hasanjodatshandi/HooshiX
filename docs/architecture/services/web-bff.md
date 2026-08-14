# Web BFF Architecture

## 1. Responsibility

Web BFF is the browser-facing backend boundary. It translates REST/OpenAPI browser interactions into internal gRPC calls and owns browser-session/OIDC protocol mechanics.

It does not become a second Domain layer. Business invariants and final resource authorization remain in backend bounded contexts.

OpenAPI is the authoritative browser/public REST contract. Frontend TypeScript clients are generated from the approved OpenAPI definition; handwritten duplicate transport DTO/client layers are prohibited except for thin UI/domain wrappers around generated clients. Public REST errors use RFC 9457 Problem Details (or a versioned extension profile) and never expose internal exception details.

## 2. Public path

```text
Internet
-> upstream L3/L4 volumetric mitigation/scrubbing
-> redundant external L4 load balancing
-> Traefik
-> dedicated Caddy + Coraza WAF
-> Web BFF
```

Direct Internet->BFF and Traefik->BFF application paths are prohibited by routing plus NetworkPolicy/Istio authorization. A CDN is deployment-specific and does not replace the mandatory upstream volumetric-mitigation or load-balancer controls.

## 3. OIDC

ADR-0016 is current; ADR-0012 defines the trusted BFF->Identity evidence/signup/link contract.

Google/future browser login uses Authorization Code + PKCE S256. BFF creates single-use `state`, `nonce`, verifier/challenge; transaction state is server-side and expires <=10m.

Callback validates state, nonce, PKCE, signature, configured issuer/audience, and timestamps before invoking Identity. Redirect URI matching is exact; post-login return destinations are validated same-origin relative paths, not caller-controlled absolute URLs.

BFF is the application owner of provider-protocol validation. Identity does not call Google during login/link/signup and does not receive provider authorization codes or provider tokens.

Immediately after successful provider validation, BFF creates and submits the typed Identity evidence:

```text
evidence_id        exactly 256 bits CSPRNG
evidence_issued_at BFF server timestamp generated after validation
issuer             canonical validated issuer
subject            validated provider subject
request_id         canonical UUIDv4 BFF logical request identity
metadata_version   explicit bounded version
metadata           optional validated email + email_verified + bounded given/family-name suggestions
```

BFF workload identity, evidence ID, issuance time, issuer, subject, request identity and metadata are part of the Identity evidence/idempotency binding. The two-minute evidence lifetime and >=10-minute spent/replay retention are Identity security policy; browser/provider input cannot extend them.

A provider token is never forwarded as a substitute for evidence. Email equality never authorizes auto-link. `email_verified=true` may only be forwarded as validated evidence that Identity can use when the canonical Contact is free; an existing-email collision becomes `ACCOUNT_LINK_REQUIRED`. Missing/`email_verified=false` never creates an Identity Contact automatically. Provider names remain suggestions and never silently complete the Identity profile.

For an existing User with active TOTP, successful Google evidence is only primary-authentication proof. Identity returns the same MFA pre-auth continuation used after password proof; BFF cannot establish a completed session until TOTP or a valid recovery code succeeds.

Provider credentials remain inside the approved secret-delivery boundary and do not enter browser storage, Identity requests, Git values, or telemetry.

## 4. Browser session and authenticated onboarding

Browser receives only the opaque BFF session cookie:

```text
__Host-sajtech-session
Secure; HttpOnly; SameSite=Lax; Path=/; no Domain
```

BFF session ID has >=256 bits CSPRNG entropy and rotates after login, MFA completion, tenant switch, recovery, password reset/change where the session remains valid, security/assurance elevation, and observed Identity MFA-state changes that preserve a session.

Server-side session state lives in BFF-owned ACL/key namespace on `security-redis`; idle <=7d, absolute <=30d. Any retained Identity refresh credential is AES-256-GCM encrypted with a BFF-specific mounted local key ring and is never stored raw in Redis/browser/telemetry.

When Identity returns a pre-auth MFA challenge after any primary proof—password or trusted Google evidence—BFF creates no completed authenticated browser session. It retains only bounded pre-auth continuation state. Final authenticated state exists only after Identity confirms required MFA and creates the Identity Session/RefreshFamily result.

When Identity authentication succeeds but no active Tenant/Membership is selected, BFF may create only `authenticated_onboarding` state:

- no normal tenant-scoped Identity access JWT exists;
- the browser can access only the reviewed same-origin Identity onboarding/profile/tenant-create/invitation-accept/tenant-selection routes;
- ordinary resource-service requests are rejected rather than sent without a tenant credential;
- zero Membership remains onboarding; one valid Membership is selected by Identity automatically; multiple use Identity's valid last-selection/explicit-selection rules;
- completing tenant selection rotates the BFF session ID and transitions to normal tenant-authenticated state.

Identity current-family logout, logout-all, password reset/change revocation, ExternalIdentity unlink revocation, MFA-state-change revocation, User suspension/DELETING, refresh-family reuse or expiry invalidates the corresponding BFF session/continuation when observed. BFF never manufactures continuity from a revoked/failed Identity refresh.

## 5. CSRF/CORS/browser hardening

Unsafe cookie-authenticated browser requests require trusted Origin + session-bound synchronizer token in `X-CSRF-Token`; Fetch Metadata is additional defense. GET/HEAD/OPTIONS do not mutate business state.

Same-origin is preferred. If CORS is needed, use exact origin allow-list; credentialed wildcard/reflected origins are prohibited.

CSP, `nosniff`, restrictive referrer/permissions policy, frame protection, and HSTS after HTTPS-domain coverage verification are centrally tested.

## 6. Internal calls

Internal synchronous calls use gRPC + Protobuf over Istio Ambient strict mTLS/workload identity. Every call has an explicit deadline/cancellation/error map. The BFF does not create long-running workflows or deep synchronous call chains.

Authentication dependency ownership is explicit in `dependency-criticality.yaml`: Web BFF owns the browser-flow edge to Google OIDC endpoints and the trusted evidence/session-establishment edge to Identity. Identity->Google is not an allowed login/link/signup dependency.

BFF->Identity evidence submission has no retry/fallback. A failed/ambiguous call is resolved only through the stable request/evidence idempotency contract; BFF never creates a second provider identity or alters the evidence payload to force success.

Tenant Authorization administration is also a real BFF-owned synchronous edge. Browser calls the BFF REST/OpenAPI facade; BFF invokes the matching Authorization gRPC management operation with its approved workload identity and a current Identity access JWT whose audience is exactly `authorization-service`.

The BFF does not pre-authorize or fabricate Authorization management state. Authorization validates the end-user JWT locally and is the management permission/domain authority. The BFF call contract uses the current generic gRPC ceiling as the exact v1 edge limit:

```text
deadline:        1500 ms maximum
attempts:        1
wait-for-ready:  off
automatic retry: none
fallback:        none
failure mode:    fail closed / management unavailable
```

Write requests preserve the caller/BFF-generated canonical UUIDv4 `request_id`. A timeout/ambiguous result is not retried automatically; a later explicit replay uses the same request identity so Authorization's idempotency contract can return the committed original or stable conflict.

## 7. Authorization

Routine protected resource request flow does **not** pay two online Authorization calls. The resource-owning service performs the final online `CheckPermission` under the current ADR-0013/ADR-0026/ADR-0032/ADR-0036 authorization runtime. BFF only checks authorization for BFF-owned resources or a separately justified UX/read-model need; such checks never replace final resource enforcement.

The tenant-management facade is different from duplicate resource authorization: BFF transports browser administration to the Authorization-owned management use case, but does not decide that use case itself. It never trusts Role/permission lists from browser/session/JWT as management authority and never converts an Authorization outage/deny into a local allow.

`GetMembershipAuthorization` responses are administration/UX snapshots only. The BFF/React client must not reuse them as authoritative access-control decisions for protected resource calls.

`authenticated_onboarding` is not an authorization bypass. It never carries a normal resource token and is restricted to the explicitly reviewed Identity onboarding surface; Authorization management is not implicitly added to the onboarding allow-list.

## 8. Failure behavior

The BFF does not fabricate successful business data when downstream services are unavailable. It maps stable downstream error categories to bounded public error contracts without leaking internal exception details, tokens, tenant IDs, Contact ownership, provider payloads, Role/permission internals, or Authorization audit data.

Authorization management deny/unavailable/overload/idempotency-conflict remains distinct in the public RFC 9457 mapping. BFF does not retry a denied/unavailable management mutation with changed payload/request identity to force success.

OIDC evidence expiry/replay/conflict, Identity dependency failure, Google verified-email collision, or MFA pre-auth failure remains authentication unavailable/denied/explicit-link-required according to the stable contract and never falls back to email auto-link, a browser-stored provider token, SMS downgrade of active TOTP, or a fabricated authenticated session.

Session Redis failure fails authentication/session continuity closed. An onboarding session without valid server-side state is not reconstructed from browser data.

## 9. Verification

Applicable tests include REST/OpenAPI contracts, PKCE/state/nonce replay, redirect/open-redirect negatives, provider validation before Identity invocation, provider-code/token absence from Identity requests/telemetry, exact 256-bit evidence randomness, issued-at binding, two-minute expiry, >=10-minute replay retention, equal replay/changed-payload conflict/wrong-workload negatives, Google signup verified-email collision/no-auto-link/unverified-email no-Contact/name-suggestion behavior, active-TOTP Google proof entering MFA continuation, password/Google MFA pre-auth with no completed session before MFA, tenantless `authenticated_onboarding` route allow-list + ordinary-resource/Authorization-management denial, zero/one/many Membership journeys, tenant switch/session rotation, cookie/session rotation/fixation/logout/revocation/MFA-state-change behavior, Redis failover/session behavior, CSRF Origin/token, CORS, security headers, browser storage token absence, public-edge traversal/direct-bypass negatives, internal gRPC deadlines/error maps, Authorization management 1500ms/one-attempt/no-retry/no-fallback behavior, exact `aud=authorization-service` token propagation, stable write request-id replay after ambiguity, wrong-workload/expired/wrong-audience management negatives, proof BFF does not locally grant management authority, final resource-authorization ownership, PII-safe logging, BDD critical flows, and Playwright critical authentication/onboarding/administration journeys where implemented.
