# ADR-0052: Identity JWT Signing-Key Lifecycle v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

## Decision

### Signing algorithm and key identity

Identity access tokens use RS256 with RSA-3072 signing keys. Every key has an immutable random `kid`; a `kid` is never rebound to different key material.

Verifiers accept only the configured RS256 algorithm and approved issuer/audience. Token input MUST NOT select an arbitrary algorithm, verification-key source, URL, or remote JWK endpoint.

Access-token lifetime remains five minutes under the current Identity/session architecture.

### Private-key custody and local signing

Identity exclusively owns private signing material. OpenBao is the authoritative secret source and External Secrets Operator materializes the approved signing-key ring to a read-only mounted volume.

- private keys never enter Git, ConfigMaps, images, ordinary environment variables, database rows, events, logs, traces, or metrics;
- only the Identity workload identity can access the signing-key secret path;
- a candidate key-ring snapshot is fully parsed/validated before atomic replacement of the active in-process snapshot;
- startup/readiness fails closed when the configured active signing key is unusable;
- signing is local; the token-issuance hot path performs no OpenBao/remote signing RPC.

### Public verification bundle

Public JWK material is non-secret. The canonical verification bundle is reviewed GitOps configuration containing only the bounded `current`, `next`, and `previous` public keys needed for rotation/rollback.

BFF/resource services verify tokens locally. Normal JWT verification performs no network call to Identity, OpenBao, or remote JWKS.

Verifier reload is atomic: an invalid candidate bundle never replaces the last valid bundle. A verifier with no valid approved bundle is not Ready for token-protected traffic.

### Normal rotation

Normal rotation occurs every 90 days:

1. generate a new RSA-3072 pair with a new immutable `kid`;
2. place private material in OpenBao without activating it;
3. add the public key to the reviewed GitOps bundle as `next`;
4. distribute and verify the expanded bundle on all token-verifying workloads;
5. activate the new `kid` in Identity;
6. retain the previous public key for at least 24 hours;
7. retain prior private material only for the bounded rollback window, then destroy it when no valid rollback may sign with it.

Identity MUST NOT sign with a `kid` before the corresponding public key is distributed and verified.

### Compromise rotation

Suspected private-key compromise is a security incident. After replacement trust is distributed, the compromised public key may be removed immediately, intentionally invalidating any remaining five-minute tokens signed by it.

Compromise response never extends token TTL, resurrects compromised key material, or accepts an unknown/old key as fallback.

### Verification contract

Every protected service validates at least:

- signature using an approved local public key selected by bounded `kid`;
- algorithm exactly RS256;
- issuer and audience;
- `sub`;
- active tenant/membership/session claims where required;
- `jti`, `iat`, and `exp`;
- canonical token/claim size bounds.

Permission lists are not trusted from the token. Protected resource operations use the current online Authorization contract.

## Verification requirements

Tests cover RSA key size/generation, `kid` uniqueness, algorithm confusion, unknown `kid`, invalid issuer/audience/signature, prepublish-before-sign ordering, current/next/previous bundles, atomic reload failure, scheduled rotation, previous-key overlap, emergency key removal, five-minute expiry, readiness without valid material, file permissions, and absence of private keys from Git/telemetry.

A staging rotation exercise is required before production and at least annually in addition to normal scheduled rotations.

## Rollback considerations

Rollback may use a prior signing key only while its bounded private rollback material and matching public verification key are intentionally retained and uncompromised. Rollback MUST NOT resurrect destroyed/compromised material, reuse a `kid`, or introduce network JWKS lookup as a fallback.