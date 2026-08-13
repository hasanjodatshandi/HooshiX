# ADR-0043: Use a Local Notification Delivery Key Ring in v1

## Status

Accepted

## Date

2026-08-10

## Supersedes

For Notification delivery escrow, this ADR supersedes ADR-0012/ADR-0029 requirements to call OpenBao Transit in the acceptance/dispatch hot path and the corresponding Transit exception in ADR-0011.

All plaintext-prohibition, exact-content retry, ciphertext-erasure, provider ambiguity, no-network-I/O-in-transaction, and key-purpose-separation rules remain accepted.

## Context

OpenBao Transit kept encryption keys non-exportable but made every sensitive Notification acceptance and dispatch depend on an online cryptographic service. That adds latency and an availability coupling to a path whose payload can already be decrypted by a compromised authorized Notification workload through Transit.

The platform already has an accepted provider-neutral mounted key-ring model in ADR-0009. Using that model removes the hot network dependency and keeps cryptographic handling local and bounded.

## Decision

Notification delivery escrow uses an independent local AES-256-GCM key ring whose source of truth is OpenBao and whose materialization path is External Secrets Operator -> Kubernetes Secret -> read-only volume.

No OpenBao network call occurs during `SubmitNotification`, provider dispatch, retry, reconciliation, or terminal erasure.

### Key ring

- dedicated purpose: `notification-delivery-escrow-local`;
- independent from Identity verification HMAC, Identity handoff escrow, TOTP, and other keys;
- one active key ID for new encryption;
- historical decrypt keys retained while dependent ciphertext exists plus 7 days;
- normal rotation every 90 days;
- key ID never reused for different bytes;
- AES-256-GCM, random unique 96-bit nonce, 128-bit authentication tag;
- AAD binds at least notification/request identity, channel, semantic type, resolved template version, format version, and key ID.

### Loading and refresh

The Secret is mounted read-only, not via environment variable and not using `subPath`.

Infrastructure loads candidate key snapshots from local files, validates the complete set, then atomically replaces the prior valid in-memory snapshot. Failed/incomplete refresh never partially replaces a valid snapshot.

Startup fails closed without a valid required ring. A valid running ring may remain usable during temporary OpenBao/ESO outage for a maximum operational staleness of 1 hour; beyond that, new operations requiring the ring fail closed and readiness reflects degradation. Existing terminal erasure does not wait for key refresh.

Temporary mutable key/plaintext buffers are cleared where the JVM controls them. Raw keys, recipient, code, and rendered content never enter logs/traces/metrics.

### Workload hardening

Notification uses its independent ServiceAccount, least-privilege OpenBao path policy, Kubernetes Secret encryption at rest, read-only volume, non-root container, read-only root filesystem where practical, RuntimeDefault seccomp, dropped capabilities, and NetworkPolicy/Istio restrictions.

### Escrow lifecycle

Sensitive ciphertext hard maximum remains 24 hours and is normally deleted earlier at the accepted terminal lifecycle point. Retry always decrypts the exact accepted content and never re-renders or generates a different code.

## Verification Requirements

Encryption/AAD/nonce tests, active+historical rotation, atomic refresh, staleness fail-closed behavior, corrupt/missing key handling, pod restart, ciphertext erasure, PII/key telemetry tests, ServiceAccount/Secret ACL tests, and proof that Notification performs no OpenBao call in request/dispatch hot paths.

## Consequences

OpenBao is removed from Notification request latency and runtime availability coupling. Key material exists inside the authorized Notification pod, increasing key-exfiltration blast radius versus Transit, but strong workload isolation and short-lived ciphertext keep the tradeoff bounded. This choice materially reduces operational complexity and latency for v1.
