# ADR-0014: Local Notification Delivery Key Ring v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

## Decision

Notification delivery escrow uses an independent local AES-256-GCM key ring. OpenBao is the authoritative secret source; External Secrets Operator materializes the approved key set into a Kubernetes Secret mounted read-only into Notification.

Routine `SubmitNotification`, provider dispatch, retry, reconciliation, and terminal erasure perform **no OpenBao network call**.

### Key purpose and cryptography

- purpose: `notification-delivery-escrow-local`;
- independent from Identity challenge HMAC, Identity handoff escrow, TOTP, BFF refresh/session protection, provider credentials, and other keys;
- one active key ID for new encryption;
- historical decrypt keys retained while dependent ciphertext exists plus seven days;
- normal rotation every 90 days;
- a key ID is immutable and never rebound to different bytes;
- AES-256-GCM;
- random unique 96-bit nonce per encryption under a key;
- 128-bit authentication tag;
- AAD binds stable request/notification identity, channel, semantic type, resolved template version, format version, and key ID.

### Loading and refresh

The key Secret is mounted read-only, not via ordinary environment variables and not through `subPath`.

Infrastructure reads candidate key snapshots from local files, fully parses/validates/defensively copies them, and atomically swaps only a complete valid candidate. Failed/incomplete refresh never partially replaces a valid ring.

Startup fails closed without the required valid ring. A valid running snapshot may remain usable across temporary OpenBao/ESO disruption for at most one hour. Beyond the configured staleness bound, new operations requiring the ring fail closed and readiness reflects degradation. Terminal ciphertext erasure does not wait for key refresh.

Temporary mutable key/plaintext buffers are cleared where the JVM implementation controls them. Raw keys, recipient, code, exact rendered sensitive content, and ciphertext never enter logs/traces/metrics.

### Workload hardening

Notification uses:

- independent ServiceAccount/workload identity;
- least-privilege OpenBao/External Secrets access;
- Kubernetes Secret encryption at rest and narrow RBAC;
- read-only secret mount;
- non-root execution;
- `allowPrivilegeEscalation=false`;
- default Linux capability drop;
- `RuntimeDefault` seccomp;
- read-only root filesystem where runtime permits it;
- deny-by-default NetworkPolicy and least-privilege Istio authorization.

### Escrow lifecycle

Sensitive delivery ciphertext has a 24-hour hard maximum and is normally erased earlier at the applicable terminal/cutoff state. Retry/reconciliation always uses the exact accepted recipient/content/template/deadline identity and never generates a replacement verification secret or silently re-renders a newer template.

Local key material increases the blast radius of a fully compromised authorized Notification workload versus non-exportable remote cryptographic service, so workload isolation, key-purpose separation, short ciphertext lifetime, secret RBAC, and PII-safe telemetry are mandatory compensating controls.

## Verification requirements

Test AES-GCM/AAD/nonce uniqueness, active+historical rotation, immutable key IDs, atomic refresh, staleness fail-closed behavior, corrupt/missing key handling, pod restart, ciphertext erasure, exact-content retry, PII/key telemetry, ServiceAccount/Secret/OpenBao path authorization, hardened pod security context, and proof that request/dispatch/retry/reconciliation paths perform no OpenBao RPC.

## Rollback considerations

Rollback MUST preserve decryptability of all still-valid ciphertext and the exact accepted-content contract. It cannot reintroduce routine OpenBao hot-path RPC, key-purpose reuse, key-ID rebinding, environment-variable key rings, or a weaker workload security context without a new reviewed current decision.
