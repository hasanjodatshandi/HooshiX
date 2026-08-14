# ADR-0021: Production Platform Compatibility and CNI v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-14

## Decision

### Compatibility authority

The exact approved production compatibility set is maintained in:

```text
docs/technology/technology-baseline.md
docs/technology/production-compatibility-matrix.md
```

Immutable deployment/provisioning metadata, image digests, chart locks/checksums, host package locks, dependency locks and service wrappers identify exact deployed artifacts.

This ADR does not duplicate the full patch-version table. Architecture prose uses product families/major-minor lines unless an exact version is itself a current architectural constraint. A baseline change must not silently change architecture, security semantics, protocol behavior or compatibility assumptions.

ADR-0042 selects `production-single-server` as the initial profile. The compatibility matrix therefore includes both the selected K3s single-server relationship and the `production-ha` Kubernetes relationship.

### CNI and mesh relationship

Calico OSS is the primary CNI and Kubernetes NetworkPolicy enforcement implementation in both production profiles. Production uses the approved Calico 3.32.x line with Kubernetes 1.35.x and exact supported patches in the Technology Baseline.

The platform uses standard Calico dataplane. Experimental/eBPF acceleration is not enabled in v1.

For `production-single-server` K3s:

- K3s Flannel is disabled;
- the K3s network-policy controller is disabled to avoid competing policy implementation;
- Calico is installed/configured as the custom CNI;
- bundled K3s Traefik/ServiceLB are disabled because repository-pinned edge components remain authoritative.

Istio Ambient remains the service mesh. NetworkPolicy is independent defense in depth and MUST account for required Ambient HBONE/health traffic without becoming allow-all. Positive and negative compatibility tests cover ordinary pod traffic, ztunnel/HBONE paths and selectively enrolled data workloads.

Single-server production additionally requires the ADR-0042 complete-stack Ambient benchmark. Capacity failure blocks production; it does not authorize disabling workload identity/mTLS.

### Argo CD topology

Argo CD remains non-HA in the initial profile because it is not an application request-path dependency and Git remains desired-state authority. Running workloads continue serving during temporary Argo CD controller outage when the Kubernetes/application runtime itself is healthy.

Argo CD HA is introduced only when measured deployment/reconciliation availability, recovery evidence, compliance or operational risk justifies additional control-plane footprint.

### Upgrade governance

Unattended automatic platform upgrades are prohibited.

A compatible patch update uses a reviewed Technology Baseline/GitOps/provisioning change plus upstream support/security review and CI/staging compatibility validation. A compatible minor may use the same process only when it does not change architecture/security semantics and is supported with the rest of the pinned compatibility set.

Major, incompatible-minor, product-substitution, protocol, datastore-semantics, CNI/mesh-model or security-model changes require a revised current ADR before implementation depends on them.

An upstream-EOL component is not production-eligible merely because an older baseline named it. The baseline must move to a supported compatible release before rollout.

### Artifact immutability

Production desired state references immutable image digests. Charts, rules, downloaded manifests, K3s binaries/packages and other supply-chain artifacts are pinned with integrity metadata where supported. Promotion uses the exact signed application artifact validated in staging; production rebuild of the application image is prohibited.

## Verification requirements

Compatibility CI/staging verifies the current selected profile/matrix, including:

- Kubernetes/K3s API and CRD compatibility;
- exact K3s artifact/integrity and custom-CNI configuration in single-server;
- Helm/Kustomize render and schema/policy validation;
- repository Gateway API/Traefik behavior and absence of conflicting K3s bundled edge in single-server;
- Istio Ambient plus `istioctl analyze`;
- Calico NetworkPolicy positive/negative flows including HBONE/health paths;
- CloudNativePG/PostgreSQL selected-profile behavior: shared single-instance isolation/recovery in single-server, dedicated failover in HA;
- Kafka/Redis/Kyverno selected-profile topology and security behavior;
- Kyverno admission-policy authoring RBAC and bounded external-context/SSRF tests;
- Argo CD reconciliation;
- exact immutable artifact identities;
- applicable load, backup/restore, rollback/fail-forward and security tests for the changed component set;
- single-server complete-stack resource headroom before production approval.

## Rollback considerations

Rollback uses the prior known-good compatibility set only when artifact/schema/data compatibility is proven. It MUST NOT restore an unsupported/EOL component, enable competing K3s networking/edge components, disable Calico/mesh/admission security, rebuild application artifacts, weaken OpenBao/MFA, or perform an unsafe datastore downgrade merely to recover an older version.
