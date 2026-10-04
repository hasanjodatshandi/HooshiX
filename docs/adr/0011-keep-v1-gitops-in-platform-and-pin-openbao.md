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

OpenBao is exactly `2.6.4`, pinned by immutable image digest. v1 uses a single OpenBao Raft instance/PVC with manual Shamir seal (`3` shares, threshold `2`) and hourly encrypted off-PVC snapshots. Normal application hot paths consume mounted/local key material; they do not make routine per-request OpenBao calls.

External Secrets Operator is the normal Kubernetes synchronization boundary. Secret values never enter Git, images, Helm/Kustomize values, logs, traces, metrics, or CI output. Logical secret/key names are stable; physical endpoint/materialization paths are typed configurable values.

The offline Istio Root CA private key is never stored in OpenBao or Kubernetes.

## Security patch selection

The 2026-10-04 artifact review selects upstream `2.6.4` within the existing 2.6
line, rather than introducing 2.7 or a custom rebuilt image. The previous 2.6.1
digest failed Grype with High/Critical OpenSSL matches. The official 2.6.4
security release also fixes audit-failure response handling, expired AppRole
SecretID authentication and Kubernetes JWT validation on renewal. Exact registry
manifest/config hashes, platform, version and source revision are checked when
pinning; final-image scanning and native TLS/Shamir/Raft recovery remain required
before merge. Current commissioning evidence does not establish a live OpenBao
store; this change performs no live datastore upgrade. This source
selection does not approve installation, signing, staging or production promotion.

The selected runtime flavor is official `openbao-ubi` (UBI10 minimal), not a local
rebuild. The Alpine 2.6.4 candidate passed native recovery but still failed Grype
on zlib 1.3.2-r0 / CVE-2026-85091. Upstream zlib has a source fix but no newer
published release at review time. UBI is independently scanned and rehearsed;
switching flavor is not vulnerability adjudication or an exception. Upstream uses
the same `/usr/bin/bao` binary layer for all flavors. The existing explicit UID,
read-only filesystem, shell probes, TLS mounts, resources and no-dev entrypoint
override are retained. Compressed artifact size is 142,186,491 bytes; target-disk
and complete-stack resource evidence remain deployment gates. Signature/provenance
and staging approval are still mandatory; no scanner suppression is added.

Sources: [official 2.6.4 release](https://github.com/openbao/openbao/releases/tag/v2.6.4),
[official flavor Dockerfile](https://github.com/openbao/openbao/blob/v2.6.4/Dockerfile),
[zlib upstream fix discussion](https://github.com/madler/zlib/issues/1310).

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
