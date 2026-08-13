# ADR-0045: Define Web BFF Browser, OIDC, and Session Security v1

## Status

Accepted

## Date

2026-08-10

## Relationship to Earlier Decisions

This ADR extends ADR-0006 and ADR-0038. Web BFF remains the OIDC relying party and the browser never stores internal access/refresh tokens.

## Decision

### Same-origin browser model

Credentialed BFF API traffic is same-origin. CORS is deny-by-default; any exception is an exact reviewed origin. Wildcard origin with credentials is prohibited.

### Google OIDC

Authorization Code uses:

- PKCE S256 only;
- cryptographically random state + nonce;
- state, nonce, PKCE verifier and post-login target bound to one server-side pre-auth transaction;
- transaction TTL <=10m and single use;
- exact redirect URI allow-list, no wildcard and no open redirect;
- issuer/audience/signature/expiry/nonce validation before trusted Identity call.

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

Session idle <=7d, absolute <=30d, and never exceeds the underlying Identity refresh-family validity. Logout invalidates server-side state before success.

### CSRF

Unsafe cookie-authenticated requests require a synchronizer CSRF token bound to the session and sent in an explicit header. Safe methods remain side-effect free. Origin is validated for unsafe browser requests when present; Referer may be secondary fallback. SameSite is defense in depth, not the only CSRF control.

### Headers

Baseline includes HSTS `max-age=31536000` (includeSubDomains only when safe), `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, restrictive Permissions Policy, and CSP starting with:

```text
default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'
```

`unsafe-eval` is prohibited. Additional sources are explicit and reviewed.

### Authorization

BFF authenticates the session and propagates trusted context, but the resource owner performs final ADR-0039 authorization. BFF does not routinely duplicate the same `CheckPermission` call.

## Verification Requirements

PKCE downgrade, state/nonce replay, exact redirect/open redirect, session fixation/rotation, cookie flags, CSRF, CORS, header/CSP, logout, browser token-storage, and critical Playwright auth journey tests.
