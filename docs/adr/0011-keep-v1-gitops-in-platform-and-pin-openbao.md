# ADR-0011: Current GitOps and OpenBao Baseline

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

## Decision

Argo CD uses this repository as the v1 desired-state source. Environment roots are:

```text
deploy/clusters/staging
deploy/clusters/production
```

Service manifests, platform infrastructure, WAF, Istio policies, NetworkPolicies, datastore/operator resources, and admission/security policy live under reviewed `deploy/` configuration with environment composition through Helm/Kustomize as appropriate.

Promotion uses pull-request review, immutable image digests, automated sync, self-heal, prune, `allowEmpty=false`, `PruneLast=true`, and `Prune=confirm` for explicitly destructive critical resources. Direct unreviewed production cluster mutation is prohibited. Production rollback is Git revert only when rollback is safe for the corresponding schema/data state.

OpenBao is exactly `2.6.1`, pinned by immutable image digest. v1 uses a single OpenBao Raft instance/PVC with manual Shamir seal (`3` shares, threshold `2`) and hourly encrypted off-PVC snapshots. Normal application hot paths consume mounted/local key material; they do not make routine per-request OpenBao calls.

External Secrets Operator is the normal Kubernetes synchronization boundary. Secret values never enter Git, images, Helm/Kustomize values, logs, traces, metrics, or CI output. Logical secret/key names are stable; physical endpoint/materialization paths are typed configurable values.

The offline Istio Root CA private key is never stored in OpenBao or Kubernetes.

## Verification Requirements

- render staging and production desired state;
- run Helm/Kustomize/Kubernetes schema/policy validation;
- verify immutable image/chart digests and scan rendered output for secrets;
- run `istioctl analyze` where mesh resources are affected;
- verify OpenBao exact version/digest, Raft/PVC, Shamir configuration, snapshot/restore procedure, and recovery evidence;
- prove that production application hot paths do not depend on live OpenBao RPCs for routine cryptographic operations;
- verify the offline Root CA private key is absent from Kubernetes/OpenBao.

## Rollback Considerations

GitOps changes use reviewed Git revert when the resulting application/database state remains backward compatible. OpenBao rollback/recovery follows tested snapshot/restore and secret-rotation procedures; unsafe loss of newer secret/key state is not accepted merely to restore an older binary/configuration.
