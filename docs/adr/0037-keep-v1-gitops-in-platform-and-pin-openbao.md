# ADR-0037: Keep v1 GitOps in Platform and Pin OpenBao 2.6.1

## Status

Accepted

## Date

2026-08-10

## Supersedes

This ADR supersedes ADR-0011 only where it required a separate
`platform-gitops` repository. Its Argo CD workflow, promotion, OpenBao topology,
Shamir seal, snapshots, and secret model remain accepted.

## Decision

The v1 Argo CD source is this `platform` repository. Desired state is rooted at
`deploy/clusters/staging` and `deploy/clusters/production`. Service manifests,
the Notification clock-health-agent/PostgreSQL sidecar, OpenBao, Istio policies,
NetworkPolicies, and managed infrastructure live under `deploy/` with Kustomize
environment overlays, immutable image digests, PR promotion, automated sync,
self-heal, prune, `allowEmpty=false`, `PruneLast=true`, and `Prune=confirm` for
explicitly critical destructive resources.

A later ownership/security boundary may extract `deploy/` without changing its
meaning. OpenBao is exactly `2.6.1`, pinned by immutable image digest. Logical
mount/key names are stable; physical API, address, and materialization paths
are typed configurable values. Secret values never enter Git.

## Verification Requirements

CI renders both environments, validates Helm/Kustomize/Kubernetes policy,
checks digests, scans rendered output for secrets, and runs `istioctl analyze`.
OpenBao checks cover exact version/digest, Shamir configuration, PVC, snapshots,
and absence of the root CA private key from Kubernetes.

## Rollback Considerations

Rollback is Git revert. Later extraction must preserve history, review gates,
overlays, and desired-state semantics.
