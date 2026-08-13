# ADR-0045: Web BFF Browser, OIDC, and Session Security v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

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

Session ID has >=256 bits CSPRNG entropy and server-side state. It rotates after login, MFA completion, tenant switch, privilege/assurance elevation, recovery, and password reset/change where the session remains valid.

Session idle <=7d, absolute <=30d, and never exceeds underlying Identity refresh-family validity. Logout invalidates server-side state before success.

The browser never receives/stores provider tokens or Identity access/refresh credentials. If BFF stores an Identity refresh credential server-side, it follows the current local encryption/key-ring policy and never remains raw in Redis/browser storage.

### CSRF

Unsafe cookie-authenticated requests require a session-bound synchronizer CSRF token sent in an explicit header. Safe methods remain side-effect free. Trusted Origin is validated for unsafe browser requests; Referer may be a secondary fallback when appropriate. SameSite is defense in depth, not the only CSRF control.

### Browser security headers

Baseline includes HSTS `max-age=31536000` (`includeSubDomains` only when safe), `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, restrictive Permissions Policy, and CSP beginning with:

```text
default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'
```

`unsafe-eval` is prohibited. Additional sources/directives are explicit and reviewed.

### Authorization boundary

BFF authenticates the browser session and propagates trusted context, but the resource-owning service performs final ADR-0039 authorization. Routine duplicate `CheckPermission` in BFF is prohibited when the resource service performs the authoritative check.

### Runtime isolation

BFF uses its own ServiceAccount, deny-by-default NetworkPolicy, Istio Ambient strict mTLS/least-privilege authorization, hardened pod security context, and ACL-isolated session Redis namespace. Session dependency failure never fabricates authenticated state.

## Verification requirements

Test PKCE downgrade, state/nonce replay, exact redirect/open redirect, external-identity no-auto-link, session fixation/rotation/reuse/logout, cookie flags, refresh/session encryption behavior, CSRF, CORS, CSP/security headers, session-Redis outage/fail-closed behavior, browser token storage, workload policy, and critical Playwright authentication journeys.

## Rollback considerations

Rollback MUST NOT weaken PKCE, state/nonce replay protection, exact redirects, cookie constraints, CSRF/CORS, server-side session invalidation/rotation, browser token isolation, or resource-owner final authorization.
