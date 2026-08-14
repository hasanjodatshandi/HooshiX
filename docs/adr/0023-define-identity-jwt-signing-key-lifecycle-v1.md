# ADR-0023: Identity JWT Signing-Key Lifecycle v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13; verifier clock-leeway contract finalized on 2026-08-14; Web BFF exact-audience brokerage contract finalized on 2026-08-14

## Decision

### Signing algorithm and key identity

Identity access tokens use RS256 with RSA-3072 signing keys. Every key has an immutable random `kid`; a `kid` is never rebound to different key material.

Verifiers accept only configured RS256 algorithm and approved issuer/audience. Token input MUST NOT select arbitrary algorithm, verification-key source, URL, or remote JWK endpoint.

Access-token lifetime remains five minutes under current Identity/session architecture.

### Private-key custody and local signing

Identity exclusively owns private signing material. OpenBao is authoritative secret source and External Secrets Operator materializes approved signing-key ring to a read-only mounted volume.

- private keys never enter Git, ConfigMaps, images, ordinary environment variables, database rows, events, logs, traces, or metrics;
- only Identity workload identity can access signing-key secret path;
- candidate key-ring snapshot is fully parsed/validated before atomic replacement of active in-process snapshot;
- startup/readiness fails closed when configured active signing key is unusable;
- signing is local; token-issuance hot path performs no OpenBao/remote signing RPC.

### BFF exact-audience token brokerage

Identity owns the internal BFF audience-token operation, represented in current contracts as `IssueAudienceAccessToken`.

This operation is not a public generic OAuth token-exchange endpoint. It accepts requests only from the approved Web BFF workload identity and only when the referenced Identity Session/RefreshFamily is active and consistent with current User/tenant/Membership/session state.

Audience authority is server-owned:

- browser input never selects or forwards arbitrary target audience as authority;
- BFF uses a reviewed server-side route->downstream/audience mapping;
- Identity independently checks target audience against its own server-owned allow-list for the BFF workload and current session mode;
- `authenticated_onboarding` cannot obtain ordinary resource-service or `authorization-service` audiences;
- tenant/membership/session claims are derived from authoritative Identity state rather than browser-provided Role/permission/tenant snapshots;
- issued token keeps exact five-minute access-token lifetime and current claim allow-list;
- wildcard audience remains prohibited;
- provider/refresh credentials are never returned by this operation.

The BFF->Identity token-broker dependency is `AUTHORITATIVE_SECURITY`:

```text
deadline:        1500 ms maximum
attempts:        1
wait-for-ready:  off
automatic retry: none
fallback:        none
failure mode:    fail closed / authentication dependency unavailable
```

Any bounded BFF server-side retention of the issued access JWT ends no later than its own `exp` and is invalidated by relevant session/tenant/assurance transition. Such retention is transport reuse only and MUST NOT become permission-result caching or replace the resource-owning service's final online Authorization check.

### Public verification bundle

Public JWK material is non-secret. Canonical verification bundle is reviewed GitOps configuration containing only bounded `current`, `next`, and `previous` public keys needed for rotation/rollback.

BFF/resource services verify tokens locally. Normal JWT verification performs no network call to Identity, OpenBao, or remote JWKS.

Verifier reload is atomic: invalid candidate bundle never replaces last valid bundle. A verifier with no valid approved bundle is not Ready for token-protected traffic.

### Time validation and clock leeway

Identity writes `iat`/`exp` from trusted server time and access-token `exp` represents exactly current five-minute issuance lifetime.

Verifier clock leeway is typed configuration and MUST NOT exceed 30 seconds in either direction. A deployment may choose a smaller value. Caller/token input cannot select or extend leeway. Tokens outside configured issuer/audience/time window fail closed; leeway is clock-tolerance only and is never used to intentionally lengthen issued token lifetime or mask clock-health defects.

### Normal rotation

Normal rotation occurs every 90 days:

1. generate a new RSA-3072 pair with new immutable `kid`;
2. place private material in OpenBao without activating it;
3. add public key to reviewed GitOps bundle as `next`;
4. distribute and verify expanded bundle on all token-verifying workloads;
5. activate new `kid` in Identity;
6. retain previous public key for at least 24 hours;
7. retain prior private material only for bounded rollback window, then destroy it when no valid rollback may sign with it.

Identity MUST NOT sign with a `kid` before corresponding public key is distributed and verified.

### Compromise rotation

Suspected private-key compromise is a security incident. After replacement trust is distributed, compromised public key may be removed immediately, intentionally invalidating any remaining five-minute tokens signed by it.

Compromise response never extends token TTL, resurrects compromised key material, or accepts unknown/old key as fallback.

### Verification contract

Every protected service validates at least:

- signature using approved local public key selected by bounded `kid`;
- algorithm exactly RS256;
- issuer and exact audience;
- `sub`;
- active tenant/membership/session claim shape where required;
- `jti`, `iat`, and `exp`;
- configured verifier clock leeway <=30 seconds;
- canonical token/claim size bounds.

Permission lists are not trusted from token. Protected resource operations use current online Authorization contract.

Normal access-token verification remains local and has no blacklist/introspection callback. Session/family revocation therefore does not retroactively alter a correctly signed already-issued JWT; remaining cryptographic lifetime is bounded by five-minute token lifetime plus only configured <=30-second clock tolerance. Authorization decisions remain online under ADR-0013.

## Verification requirements

Tests cover RSA key size/generation, `kid` uniqueness, algorithm confusion, unknown `kid`, invalid issuer/audience/signature, exact five-minute issuance lifetime, zero/normal/30-second leeway boundaries and rejection of >30-second configuration, future-`iat`/expired-token edges, prepublish-before-sign ordering, current/next/previous bundles, atomic reload failure, scheduled rotation, previous-key overlap, emergency key removal, readiness without valid material, file permissions, and absence of private keys from Git/telemetry.

BFF brokerage tests additionally cover approved BFF workload identity, active/revoked/expired Session/RefreshFamily, server-owned audience allow-list, browser/arbitrary/wildcard audience rejection, `authenticated_onboarding` resource/Authorization-audience denial, exact tenant/membership/session binding, exact five-minute issuance, no provider/refresh credential return, 1500ms/one-attempt/no-retry/no-fallback behavior, cancellation, and proof that BFF transport retention never replaces resource-owner final Authorization.

A staging rotation exercise is required before production and at least annually in addition to normal scheduled rotations.

## Rollback considerations

Rollback may use prior signing key only while bounded private rollback material and matching public verification key are intentionally retained and uncompromised. Rollback MUST NOT resurrect destroyed/compromised material, reuse a `kid`, increase verifier leeway beyond 30 seconds, extend access-token TTL, introduce network JWKS/introspection lookup as normal verification fallback, expose audience selection to browser, enable wildcard/generic token exchange, or allow onboarding state to obtain normal resource/Authorization audience.
