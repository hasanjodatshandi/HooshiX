# Web BFF Architecture

## 1. Responsibility

Web BFF is the browser-facing backend boundary. It translates REST/OpenAPI
browser interactions into internal gRPC calls and owns browser-session/OIDC
protocol mechanics.

It does not become a second Domain layer. Business invariants and final resource
authorization remain in backend bounded contexts.

OpenAPI is the authoritative browser/public REST contract. Frontend TypeScript
clients are generated from the approved OpenAPI definition; handwritten duplicate
transport DTO/client layers are prohibited except for thin UI/domain wrappers
around generated clients. Public REST errors use RFC 9457 Problem Details (or a
versioned extension profile) and never expose internal exception details.

## 2. Public path

```text
Internet
-> upstream L3/L4 volumetric mitigation/scrubbing
-> redundant external L4 load balancing
-> Traefik
-> dedicated Caddy + Coraza WAF
-> Web BFF
```

Direct Internet->BFF and Traefik->BFF application paths are prohibited by
routing plus NetworkPolicy/Istio authorization. A CDN is deployment-specific
and does not replace the mandatory upstream volumetric-mitigation or load-balancer
controls.

## 3. OIDC

ADR-0045 is current.

Google/future browser login uses Authorization Code + PKCE S256. BFF creates
single-use `state`, `nonce`, verifier/challenge; transaction state is server-side
and expires <=10m.

Callback validates state, nonce, PKCE, signature, configured issuer/audience,
and timestamps before invoking Identity. Redirect URI matching is exact; post-
login return destinations are validated same-origin relative paths, not
caller-controlled absolute URLs.

## 4. Browser session

Browser receives only the opaque BFF session cookie:

```text
__Host-sajtech-session
Secure; HttpOnly; SameSite=Lax; Path=/; no Domain
```

BFF rotates session ID after login, MFA completion, tenant switch, recovery,
and security elevation.

Server-side session state lives in BFF-owned ACL/key namespace on
`security-redis`; idle <=7d, absolute <=30d. Any retained Identity refresh
credential is AES-256-GCM encrypted with a BFF-specific mounted local key ring
and is never stored raw in Redis/browser/telemetry.

## 5. CSRF/CORS/browser hardening

Unsafe cookie-authenticated browser requests require trusted Origin + session-
bound synchronizer token in `X-CSRF-Token`; Fetch Metadata is additional
defense. GET/HEAD/OPTIONS do not mutate business state.

Same-origin is preferred. If CORS is needed, use exact origin allow-list;
credentialed wildcard/reflected origins are prohibited.

CSP, `nosniff`, restrictive referrer/permissions policy, frame protection, and
HSTS after HTTPS-domain coverage verification are centrally tested.

## 6. Internal calls

Internal synchronous calls use gRPC + Protobuf over Istio Ambient strict mTLS/
workload identity. Every call has an explicit deadline/cancellation/error map.
The BFF does not create long-running workflows or deep synchronous call chains.

## 7. Authorization

Routine protected request flow does **not** pay two online Authorization calls.
The resource-owning service performs the final online `CheckPermission` under
the current ADR-0039/ADR-0056/ADR-0062/ADR-0066 authorization runtime. BFF only
checks authorization for BFF-owned resources or a separately justified
UX/read-model need; such checks never replace final resource enforcement.

## 8. Failure behavior

The BFF does not fabricate successful business data when downstream services are
unavailable. It maps stable downstream error categories to bounded public error
contracts without leaking internal exception details, tokens, tenant IDs, or
provider payloads.

## 9. Verification

Applicable tests include REST/OpenAPI contracts, PKCE/state/nonce replay,
redirect/open-redirect negatives, cookie/session rotation/fixation, Redis
failover/session behavior, CSRF Origin/token, CORS, security headers, browser
storage token absence, public-edge traversal and direct-bypass negatives,
internal gRPC deadlines/error maps, final-authorization ownership, PII-safe
logging, BDD critical flows, and Playwright critical browser journeys.
