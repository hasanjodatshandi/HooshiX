# ADR-0052: Define Identity JWT Signing-Key Lifecycle v1

## Status

Accepted

## Date

2026-08-10

## Relationship to Earlier Decisions

This ADR completes ADR-0038's RS256 access-token decision by defining signing-key
custody, rotation, public-key distribution, verification, compromise behavior,
and rollback. It does not change the five-minute access-token lifetime or the
browser/BFF boundary from ADR-0038 and ADR-0045.

ADR-0009 provider-neutral local key-loading principles and ADR-0037 OpenBao
selection remain current.

## Decision

### Signing algorithm and key size

Access tokens remain RS256. New signing keys are RSA 3072-bit key pairs.

Every signing key has an immutable random `kid`. A `kid` is never rebound to
different key material.

Receivers accept only the explicitly configured RS256 algorithm and expected
issuer/audience. The token header cannot select an arbitrary algorithm,
verification key source, URL, or JWK endpoint.

### Private-key custody

The private signing key is owned only by Identity Service and stored in OpenBao.
External Secrets Operator materializes the approved Identity signing-key ring to
a read-only mounted volume.

- private keys never enter Git, ConfigMaps, images, environment variables,
  logs, traces, metrics, events, or database rows;
- only the Identity ServiceAccount can receive the signing-key secret path;
- Identity atomically validates a candidate local key-ring snapshot before it
  replaces the active in-process snapshot;
- startup/readiness fails closed when the configured active signing key is not
  usable;
- signing is local; no OpenBao/remote signing call exists on the token-issuance
  hot path.

### Public verification bundle

Public JWK material is not secret. The canonical verification bundle is
versioned non-secret GitOps configuration and contains bounded `current`,
`next`, and `previous` public keys as required by rotation.

BFF/resource services load the public verification bundle locally. Normal JWT
verification performs no network call to Identity, OpenBao, or a remote JWKS
endpoint.

A verifier reload is atomic: an invalid candidate bundle does not replace the
last valid bundle. A verifier that cannot load any valid approved bundle is not
Ready for user-token-protected traffic.

### Normal rotation

Normal signing-key rotation occurs every 90 days:

1. generate a new RSA-3072 pair and new `kid`;
2. store the private key in OpenBao but do not sign with it yet;
3. add the new public key as `next` through reviewed GitOps;
4. deploy/verify the expanded public bundle on all token-verifying workloads;
5. switch Identity's active `kid` to the new key;
6. retain the prior public key for at least 24 hours;
7. retain prior private material only for the bounded rollback window, then
   destroy it from the authoritative secret source when no rollback can sign
   with it.

A rollout never starts signing with a `kid` before the matching public key has
been distributed and verified.

### Compromise rotation

Suspected private-key compromise is a security incident.

Emergency response may remove the compromised public key immediately after the
replacement trust bundle is distributed, intentionally invalidating still-live
five-minute access tokens signed by the compromised key. Existing BFF sessions
must obtain fresh internal access context through the normal trusted flow.

Compromise response never extends token TTL or accepts an unknown/old key as a
fallback.

### Verification rules

Every protected service validates at least:

- signature with an approved local public key selected by bounded `kid`;
- algorithm exactly RS256;
- issuer;
- audience;
- `sub`;
- active tenant and membership claims where required;
- session identifier where required;
- `jti`;
- `iat` and `exp`;
- canonical token size/claim bounds.

Authorization permission lists are not trusted from the token. ADR-0039 online
`CheckPermission` remains the authorization source for protected operations.

## Verification Requirements

Tests cover RSA key generation/size, `kid` uniqueness, algorithm confusion,
unknown `kid`, invalid issuer/audience/signature, prepublish-before-sign ordering,
mixed current/next/previous bundles, atomic reload failure, 90-day rotation,
24-hour previous-public overlap, emergency key removal, five-minute token expiry,
readiness without signing/verification material, file permissions, and proof
that private keys never appear in telemetry or Git.

A staging rotation exercise is required before production and at least annually,
in addition to normal scheduled rotations.

## Consequences

Token verification remains local, fast, and independent of Identity/OpenBao
availability on every request. Rotation has an explicit safe overlap and
compromise path. The cost is a coordinated GitOps public-key rollout, which is
acceptable because public keys are non-secret and rotations are infrequent.

## Rollback or Migration Considerations

Rollback may sign with the prior key only while its bounded private rollback
material and public verification key are still intentionally retained. A
rollback must never resurrect a destroyed or compromised private key, reuse a
`kid`, or make verifiers fetch arbitrary network JWKS locations.
