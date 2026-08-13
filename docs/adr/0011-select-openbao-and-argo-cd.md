# ADR-0011: Select OpenBao and Argo CD for the Initial Self-Hosted Platform

## Status

Accepted

## Date

2026-08-09

## Supersedes

This ADR supersedes only the pending Secret Manager and GitOps product-selection
clauses in ADR-0009. ADR-0009's provider-neutral application key-loading,
purpose separation, rotation, readiness, and secret-handling rules remain in
force.

## Context

The production environment is self-hosted. The architecture and ADR-0009 require
an external secret source, External Secrets Operator, and GitOps, but previously
left the concrete products unresolved.

The initial phase must remain operationally light while preserving a migration
path to higher availability. Production changes must remain Git-reviewed and
reproducible without putting secret values in Git.

## Decision

### OpenBao deployment

OpenBao is the authoritative external Secret Manager.

The initial deployment is:

- one OpenBao instance;
- Integrated Storage using Raft;
- one persistent volume;
- no HSM, auto-unseal, or multi-node HA in the initial phase;
- default Shamir sealing with three shares and a threshold of two;
- accepted manual unseal operations.

An encrypted Raft snapshot is created daily and before every upgrade or
significant change. Snapshots are stored outside the primary OpenBao persistent
volume.

The secret path and policy model must allow a future migration to a three-node
Raft cluster and then auto-unseal without changing application secret contracts.
HA or HSM is introduced only when production availability, compliance, or
operational evidence justifies it through a later decision.

### External Secrets authentication and scope

External Secrets Operator authenticates to OpenBao through Kubernetes Auth with
a dedicated ServiceAccount and short-lived token.

Each namespace uses its own namespace-scoped `SecretStore`. A shared
`ClusterSecretStore`, static token, and root token are prohibited for normal
delivery.

Every workload has an independent OpenBao role and policy restricted to the
exact secret paths it requires. Synchronized Kubernetes Secrets are mounted as
read-only volumes, not placed in ConfigMaps or environment variables.

Production Kubernetes Secret objects use KMS encryption at rest and
least-privilege RBAC.

### OpenBao Transit exception

External Secrets Operator remains the default path for delivering ordinary
runtime key material to workloads.

A separately approved service may use OpenBao Transit as a cryptographic
service when the key must remain inside OpenBao. This is not permission to read
or export the Transit key. Transit authentication, key policy, network access,
rotation, audit, timeout, and failure behavior remain least-privilege and
purpose-specific.

ADR-0012 approves this exception for Notification Service using the independent
`notification-delivery-escrow` Transit key.

### Argo CD and repository model

Argo CD is the GitOps controller. The initial deployment uses one non-HA Argo CD
instance.

GitOps desired state is held in an independent `platform-gitops` repository:

- the primary branch is `main`;
- `staging` and `production` are directories/Kustomize overlays, not separate
  branches;
- container images are pinned by immutable digest;
- production promotion requires a pull request, CI validation, and manual merge;
- merge to `main` is the normal human authorization for deployment;
- ordinary deployment does not use an imperative `kubectl apply` workflow;
- rollback uses Git revert;
- ApplicationSet and Argo CD HA are deferred until evidence requires them.

Production applications use:

```text
automated.enabled=true
selfHeal=true
prune=true
allowEmpty=false
PruneLast=true
```

No Sync Window or second manual Argo CD approval is required initially. After a
production pull request is merged, Argo CD synchronizes desired state
automatically.

Resources explicitly classified as destructive or critical, including
Namespaces, CRDs, and directly managed data-bearing storage resources, use
`Prune=confirm`. The additional confirmation applies only to pruning those
resources. Manual cluster drift is reverted to Git by self-heal.

## Consequences

- The initial self-hosted secret and GitOps platform remains small but has an
  explicit growth path.
- Manual OpenBao unseal and a single OpenBao/Argo CD instance create accepted
  initial availability limitations.
- OpenBao snapshots and tested recovery procedures are critical because the
  first deployment is not HA.
- Git merge becomes a production deployment authorization event and therefore
  requires protected branches, reviewed CI, and audited repository access.
- Application workloads remain independent from OpenBao client SDKs for normal
  mounted secrets; only separately approved Transit use cases call OpenBao at
  runtime.

## Alternatives considered

### Multi-node OpenBao with auto-unseal in the initial phase

Deferred because the current phase prioritizes a lighter operational footprint.

### Static OpenBao tokens for External Secrets

Rejected because Kubernetes Auth provides scoped, short-lived workload
authentication without distributing long-lived controller credentials.

### Cluster-wide SecretStore

Rejected for the baseline because it broadens cross-namespace secret access and
ownership.

### Flux

Not selected. Argo CD is the approved GitOps controller.

### Environment branches

Rejected in favor of one `main` branch with reviewable environment overlays.

### Manual production sync after merge

Rejected because the approved pull-request merge is the production deployment
authorization event.

## Rollback or migration considerations

No runtime installation is created by this ADR.

OpenBao recovery depends on encrypted off-PVC Raft snapshots and retained Shamir
shares. Migration to three-node Raft must use tested snapshot/peer procedures and
preserve secret paths, policies, and versions.

Changing GitOps controller requires exporting desired state and preserving the
same repository history, immutable image references, and promotion controls.
Disabling automatic production synchronization requires a later approved
operational decision.
