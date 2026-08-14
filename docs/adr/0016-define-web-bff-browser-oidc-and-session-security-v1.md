# ADR-0016: Web BFF Browser, OIDC, and Session Security v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13; Identity onboarding/OIDC evidence semantics finalized on 2026-08-14

## Decision

### Same-origin browser model

Credentialed BFF API traffic is same-origin by default. CORS is deny-by-default; any exception is an exact reviewed origin. Wildcard origin with credentials is prohibited.

### Google OIDC

Authorization Code uses:

- PKCE S256 only;
- cryptographically random `state` + `nonce`;
- state, nonce, PKCE verifier, and post-login target bound to one server-side pre-auth transaction;
- transaction TTL <=10m and single use;
- exact redirect URI allow-list; no wildcard/open redirect;
- issuer/audience/signature/expiry/nonce validation before trusted Identity invocation.

Stable external identity remains `issuer + subject`; email equality never authorizes automatic account linking.

After successful provider validation, BFF creates the ADR-0012 Identity evidence payload immediately before the trusted Identity call:

```text
evidence_id        exactly 256 bits CSPRNG
request_id         canonical UUIDv4
evidence_issued_at BFF server time generated after provider validation
issuer             canonical validated issuer
subject            validated provider subject
metadata_version   explicit bounded version
optional metadata  provider-validated email/email_verified + bounded name suggestions only
```

BFF never forwards Google authorization/access/refresh/ID tokens to Identity. The evidence policy lifetime is fixed by Identity at two minutes; BFF supplies evidence issuance time as a trusted workload-generated fact, not a caller-selected TTL. Evidence is bound to the BFF workload identity, request identity, issuer, subject, issuance time and canonical versioned metadata. Identity retains spent/replay evidence for at least ten minutes and applies the replay/conflict behavior in ADR-0012.

Google may start a new Identity User through this evidence flow. A provider-verified email is only evidence that Identity may use to create a verified Contact when that canonical email is free; an email collision never auto-links and becomes the stable `ACCOUNT_LINK_REQUIRED` flow. An absent or `email_verified=false` provider email does not create an Identity Contact automatically. Provider name data is suggestion-only and never silently completes the Identity profile.

For an existing User with active TOTP, successful Google evidence is only the primary-authentication proof. It must enter the same Identity MFA pre-auth continuation as password login and cannot create a completed Session/RefreshFamily/browser session until TOTP or a valid recovery code succeeds. Provider proof never downgrades active MFA.

### BFF session

Production cookie:

```text
__Host-sajtech-session
Secure
HttpOnly
SameSite=Lax
Path=/
Domain absent
```

Session ID has >=256 bits CSPRNG entropy and server-side state. It rotates after login, MFA completion, tenant switch, privilege/assurance elevation, recovery, password reset/change where the session remains valid, and observed Identity MFA-state changes that preserve a current session.

Session idle <=7d, absolute <=30d, and never exceeds underlying Identity refresh-family validity. Logout invalidates server-side state before success.

The browser never receives/stores provider tokens or Identity access/refresh credentials. If BFF stores an Identity refresh credential server-side, it follows the current local encryption/key-ring policy and never remains raw in Redis/browser storage.

When Identity authenticates a User but no active Tenant/Membership has yet been selected, BFF may establish only the explicit `authenticated_onboarding` session mode. This mode:

- is authenticated server-side but carries no normal tenant-scoped Identity access JWT;
- permits only the reviewed allow-list of Identity onboarding/profile/tenant-create/invitation-accept/tenant-selection operations;
- cannot be used as an authenticated token source for ordinary resource-service APIs;
- upgrades to a normal authenticated tenant session only after Identity selects/validates an active Membership and issues the corresponding tenant-scoped credentials;
- rotates the BFF session ID when tenant selection completes.

When Identity returns a pre-auth MFA challenge after any primary authentication proof (password or trusted Google evidence), BFF does not create either a completed normal session or `authenticated_onboarding`. It retains only bounded pre-auth continuation state. Final authenticated session establishment occurs only after Identity confirms required MFA and creates the Session/RefreshFamily result.

Identity logout/current-family, logout-all, password/session revocation, ExternalIdentity unlink, MFA-state-change revocation, User suspension/deleting state, or refresh reuse invalidates corresponding BFF server-side session state when observed. BFF never reconstructs authentication from an expired/revoked Identity credential.

### CSRF

Unsafe cookie-authenticated requests require a session-bound synchronizer CSRF token sent in an explicit header. Safe methods remain side-effect free. Trusted Origin is validated for unsafe browser requests; Referer may be a secondary fallback when appropriate. SameSite is defense in depth, not the only CSRF control.

### Browser security headers

Baseline includes HSTS `max-age=31536000` (`includeSubDomains` only when safe), `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, restrictive Permissions Policy, and CSP beginning with:

```text
default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'
```

`unsafe-eval` is prohibited. Additional sources/directives are explicit and reviewed.

### Authorization boundary

BFF authenticates the browser session and propagates trusted context, but the resource-owning service performs final ADR-0013 authorization. Routine duplicate `CheckPermission` in BFF is prohibited when the resource service performs the authoritative check.

`authenticated_onboarding` is not an authorization bypass: it is restricted to the explicit Identity onboarding surface and provides no ordinary resource-service access token.

### Runtime isolation

BFF uses its own ServiceAccount, deny-by-default NetworkPolicy, Istio Ambient strict mTLS/least-privilege authorization, hardened pod security context, and ACL-isolated session Redis namespace. Session dependency failure never fabricates authenticated state.

## Verification requirements

Test PKCE downgrade, state/nonce replay, exact redirect/open redirect, 256-bit evidence randomness, evidence issuance/binding/two-minute expiry/ten-minute replay retention, changed-payload replay, provider-code/token absence from Identity payloads/telemetry, Google signup verified-email collision/no-auto-link, unverified-email no-Contact, suggestion-only profile data, active-TOTP Google login entering MFA pre-auth, session fixation/rotation/reuse/logout, cookie flags, refresh/session encryption behavior, `authenticated_onboarding` allow-list and ordinary-resource denial, zero/one/many Membership login journeys, password/Google MFA pre-auth not becoming onboarding/authenticated session, Identity MFA-state-change revocation/rotation, CSRF, CORS, CSP/security headers, session-Redis outage/fail-closed behavior, browser token storage, workload policy, and critical Playwright authentication/onboarding journeys.

## Rollback considerations

Rollback MUST NOT weaken PKCE, state/nonce replay protection, exact redirects, evidence entropy/expiry/replay binding, email no-auto-link/unverified-email handling, active-MFA enforcement after Google proof, cookie constraints, CSRF/CORS, server-side session invalidation/rotation, tenantless onboarding isolation, browser token isolation, or resource-owner final authorization.
