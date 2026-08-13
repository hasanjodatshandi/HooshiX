# ADR-0009: Define provider-neutral secret delivery and key lifecycle

## Status

Accepted

## Date

2026-08-08

## Context

The backend architecture already establishes these production secret-management
constraints:

- secrets are obtained through External Secrets Operator with Vault or a cloud
  Secret Manager;
- secret values never exist in Git, images, or Helm values;
- each workload has an independent Kubernetes ServiceAccount;
- workload identity and least privilege are mandatory;
- the concrete Secret Manager and GitOps controller remain ADR-controlled
  pending selections.

Identity registration verification also has a provider-neutral MAC key-loading
boundary and ADR-0008 proposes a second, independently keyed AES-256-GCM
delivery-secret escrow.

The repository does not currently select AWS, Google Cloud, Azure, Vault, or
another secret backend. It also does not select Argo CD, Flux, or another GitOps
controller. No cloud-secret SDK is present in Identity Service.

Production secret delivery must therefore define a provider-neutral operational
model without silently selecting either pending product.

## Decision

This ADR proposes the provider-neutral production secret-delivery and key
lifecycle below.

It does not select the external Secret Manager backend or the GitOps controller.
Those choices remain Pending and this ADR does not satisfy that separate
selection gate.

### External Secrets Operator boundary

External Secrets Operator is the Kubernetes secret synchronization boundary
already required by the backend architecture.

The selected external backend remains one of the separately approved Vault or
cloud Secret Manager products.

Application services do not use cloud-provider or Vault SDKs to fetch normal
runtime key material directly.

The external-secret controller authenticates to the selected secret backend
using the approved workload-identity mechanism for that provider. Long-lived
static cloud credentials in Kubernetes manifests are prohibited.

`SecretStore` is preferred when a secret source is owned by one namespace.
`ClusterSecretStore` requires an explicit cross-namespace ownership and access
justification.

### GitOps boundary

GitOps manages only non-secret desired state, including:

- `ExternalSecret` resources;
- `SecretStore` or approved `ClusterSecretStore` references;
- Kubernetes ServiceAccounts and RBAC;
- workload volume references;
- refresh and lifecycle policy;
- non-secret key IDs, paths, and configuration where appropriate.

Secret values, encryption keys, MAC keys, provider credentials, and recovery
material are never committed to Git or rendered into Helm values.

The concrete GitOps controller remains a separate pending selection.

### Kubernetes delivery to the workload

Secrets synchronized by External Secrets Operator are materialized as Kubernetes
`Secret` objects and consumed by Identity workloads through dedicated read-only
Secret volume mounts.

Secret key material is not injected through environment variables for the
rotating key-ring path.

The mount must not use `subPath`, because mounted Secret updates must remain
eligible for Kubernetes propagation.

The application does not require Kubernetes API `get`, `list`, or `watch`
permissions merely to read the mounted files.

Production clusters must enable encryption at rest for Kubernetes Secret data
and restrict Secret RBAC to least privilege.

### Local key-loading boundary

Application and Domain code remain unaware of Kubernetes and External Secrets
Operator.

Infrastructure reads the locally mounted secret files through provider-neutral
key-material providers.

Key loading is local filesystem I/O. Secret Manager, Kubernetes API, or other
network calls are prohibited inside a business database transaction.

A candidate key set is fully read, parsed, defensively copied, and validated
before it can replace the currently active in-process key ring.

Invalid or incomplete candidate material never partially replaces a valid key
ring.

Temporary mutable key byte arrays are cleared after construction where the JVM
implementation controls those buffers.

### Separate key purposes

Verification-code HMAC keys and verification-delivery escrow encryption keys are
separate cryptographic key families.

A key from one family must never be reused by the other family.

Each family has:

- one active key ID for new cryptographic operations;
- historical keys required to process still-valid persisted data;
- explicit format/version metadata;
- independent rotation and compromise handling.

Key IDs are identifiers only and are safe to persist. They must not encode secret
material.

### Startup and readiness behavior

Startup fails closed when no valid required key ring can be constructed.

A workload is not Ready for operations requiring a key family until that key
family has a valid active key and all required historical keys.

During runtime refresh:

1. a new candidate snapshot is loaded separately;
2. the candidate is validated completely;
3. only a valid snapshot may atomically replace the prior valid snapshot;
4. a failed refresh emits bounded non-secret telemetry and never logs key
   material.

The exact maximum tolerated staleness interval is an operational policy and must
be explicitly configured before production. It is not hard-coded by this ADR.

If a required key ring exceeds its permitted staleness or loses required key
material, new operations depending on that ring fail closed and readiness must
reflect the degraded state.

### Rotation lifecycle

Rotation uses overlap rather than in-place mutation of an existing key ID.

For a normal rotation:

1. create a new key with a new immutable key ID in the selected external
   Secret Manager;
2. synchronize the expanded key set through External Secrets Operator;
3. verify workloads can load the expanded set while the old key remains active;
4. switch the active key ID to the new key;
5. allow all workloads to observe and use the new active key;
6. retain old key material for the full decrypt/verify horizon of data created
   under that key;
7. remove old key material only when no valid persisted data still depends on
   it.

Reusing a key ID for different key bytes is prohibited.

### Verification MAC key retention

The active verification MAC key signs new challenge MACs.

Historical MAC keys remain available until no open or otherwise verifiable
challenge can reference them.

The retention calculation must include challenge expiry plus an operational
propagation/clock-safety allowance defined by deployment policy.

Removing a historical MAC key while a valid challenge references its key ID is
prohibited.

### Delivery escrow key retention

The active delivery-escrow key encrypts new short-lived escrow records.

Historical escrow keys remain available until every escrow record encrypted
under those keys is irreversibly deleted or otherwise no longer decryptable by
policy.

A key rotation must not make a retryable delivery secret undecryptable.

Escrow ciphertext lifetime remains bounded by ADR-0008 and must not be extended
merely to simplify key rotation.

### Refresh semantics

External Secrets Operator refreshes the target Kubernetes Secret according to an
explicitly configured refresh policy.

Rotating key rings use a refresh mode that supports propagation of source
changes without requiring secret values in Git.

The exact refresh interval is environment policy. It must be shorter than the
approved rotation-overlap window and must account for both External Secrets
synchronization and Kubernetes Secret-volume propagation.

No security decision assumes instantaneous secret propagation.

### Compromise response

A suspected key compromise is a security incident.

The response must support:

- identifying the affected key family and key ID;
- stopping new encryption or MAC creation with the compromised key;
- creating and activating a replacement key;
- determining which still-valid records depend on the compromised key;
- invalidating or superseding affected verification challenges when required;
- deleting affected delivery escrow when safe and issuing replacement
  challenges through the normal resend policy when necessary;
- preserving only non-secret audit evidence;
- rotating any provider credentials implicated by the incident;
- documenting the incident timeline and recovery action.

Compromise handling must not log raw keys, OTPs, escrow plaintext, or provider
credentials.

### Kubernetes Secret lifecycle

The generated Kubernetes Secret is operational delivery state, not the source of
truth for key ownership.

The selected external Secret Manager remains the authoritative secret source.

Deleting or recreating an `ExternalSecret` must not be treated as cryptographic
key destruction. Cryptographic destruction is controlled at the authoritative
external backend and must account for all still-valid data encrypted or MACed by
the key.

### Scope and implementation gate

This ADR defines an integration and lifecycle contract only.

While this ADR is Proposed:

- no production External Secrets resource is introduced for Identity key
  material;
- no cloud or Vault SDK is added to Identity Service;
- no delivery-escrow persistence or runtime key integration is claimed
  production-ready because of this proposal alone;
- ADR-0008 remains Proposed and registration/resend gRPC runtime remains
  blocked.

After this ADR is accepted, provider-neutral filesystem key loading,
key-snapshot validation, and cryptographic lifecycle code may be implemented
without selecting a provider-specific SDK.

Production deployment still requires the separate accepted decision selecting
the Secret Manager backend and GitOps controller.

## Consequences

- Identity remains independent from AWS, Google Cloud, Azure, or Vault client
  libraries for normal runtime secret loading.
- External Secrets Operator becomes the explicit cluster-to-workload
  synchronization boundary already implied by the architecture.
- Read-only mounted files support key rotation without putting key bytes in
  application configuration or Git.
- Rotation requires overlapping active/historical key sets and operational
  propagation discipline.
- Runtime key refresh becomes an explicit lifecycle with readiness and
  fail-closed behavior.
- Kubernetes Secret objects become part of the secret threat model and require
  encryption at rest plus strict RBAC.
- Secret Manager backend selection and GitOps controller selection remain
  unresolved and must not be inferred from this ADR.

## Alternatives considered

### Let each Java service call the Secret Manager directly

Rejected for the default runtime key-loading path.

It introduces provider SDK coupling into every service, expands workload
permissions, and creates avoidable network behavior in application runtimes.
The architecture already establishes External Secrets Operator as the
cluster-level secret synchronization mechanism.

### Inject rotating keys through environment variables

Rejected.

Environment variables encourage immutable string handling and do not provide the
same mounted-file update path for rotation. Rotating key rings use read-only
Secret volumes instead.

### Store secret values in Helm values or Git-encrypted application files

Rejected.

The backend architecture explicitly prohibits secret values in Git and Helm
values. The external Secret Manager remains the secret source of truth.

### Use one key for verification HMAC and delivery escrow encryption

Rejected.

The two purposes have different algorithms, compromise impact, retention
horizons, and lifecycle requirements. Cryptographic key separation is mandatory.

### Replace key bytes while keeping the same key ID

Rejected.

Persisted HMAC and escrow records reference key IDs. Rebinding an existing key ID
to different bytes destroys deterministic key lookup and can make valid data
unverifiable or undecryptable.

### Select AWS, Google Cloud, Azure, Vault, Argo CD, or Flux in this ADR

Rejected.

The repository contains no accepted deployment-provider or GitOps-controller
decision that justifies such a selection. Those products remain controlled by
the separate pending decision.
