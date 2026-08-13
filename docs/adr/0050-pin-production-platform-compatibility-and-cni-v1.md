# ADR-0050: Production Platform Compatibility and CNI v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

## Decision

### Compatibility authority

The exact approved production compatibility set is maintained in:

```text
docs/technology/technology-baseline.md
docs/technology/production-compatibility-matrix.md
```

Immutable deployment metadata, image digests, chart locks/checksums, dependency locks, and service wrappers identify the exact deployed artifacts.

This ADR intentionally does **not** duplicate the full patch-version table. Architecture prose uses product families/major-minor lines unless an exact version is itself a current architectural constraint. A baseline change must not silently change architecture, security semantics, protocol behavior, or compatibility assumptions.

### CNI and mesh relationship

Calico OSS is the v1 primary CNI and Kubernetes NetworkPolicy enforcement implementation. Production uses the approved Calico 3.32.x line with Kubernetes 1.35.x and the exact supported patches in the Technology Baseline.

The platform uses the standard Calico dataplane. Experimental/eBPF acceleration is not enabled in v1.

Istio Ambient remains the service mesh. NetworkPolicy is independent defense in depth and MUST account for required Ambient HBONE/health traffic without becoming allow-all. Positive and negative compatibility tests cover ordinary pod traffic, ztunnel/HBONE paths, and selectively enrolled data workloads.

### Argo CD topology

Argo CD remains non-HA in the initial v1 topology because it is not an application request-path dependency and Git remains the desired-state source of truth. Running production workloads continue serving during a temporary Argo CD controller outage.

Argo CD HA is introduced only when measured deployment/reconciliation availability, recovery evidence, compliance, or operational risk justifies the additional control-plane footprint. This is a current explicit availability/cost trade-off, not an unresolved decision.

### Upgrade governance

Unattended automatic platform upgrades are prohibited.

A compatible patch update uses a reviewed Technology Baseline/GitOps change plus upstream support/security review and CI/staging compatibility validation. A compatible minor may use the same process only when it does not change architecture/security semantics and is supported with the rest of the pinned compatibility set.

Major, incompatible-minor, product-substitution, protocol, datastore-semantics, CNI/mesh-model, or security-model changes require a new or revised current ADR before implementation depends on them.

An upstream-EOL component is not production-eligible merely because an older baseline named it. The baseline must move to a supported compatible release before rollout.

### Artifact immutability

Production desired state references immutable image digests. Charts, rule sets, downloaded manifests, and other supply-chain artifacts are pinned with integrity metadata where supported. Promotion uses the exact signed artifact validated in staging; production rebuild is prohibited.

## Verification requirements

Compatibility CI/staging verifies the current matrix, including:

- Kubernetes API/CRD compatibility;
- Helm/Kustomize render and schema/policy validation;
- Gateway API/Traefik behavior;
- Istio Ambient plus `istioctl analyze`;
- Calico NetworkPolicy positive/negative flows including HBONE/health paths;
- CloudNativePG/PostgreSQL failover and compatibility;
- Kyverno admission behavior, admission-policy authoring RBAC, and bounded CEL HTTP-context egress/SSRF positive/negative behavior from ADR-0046;
- Argo CD reconciliation;
- exact immutable artifact identities;
- applicable load, backup/restore, rollback/fail-forward, and security tests for the changed component set.

## Rollback considerations

Rollback uses the prior known-good compatibility set only when artifact/schema/data compatibility is proven. It MUST NOT restore an unsupported/EOL component, disable NetworkPolicy/mesh/admission-security controls, rebuild artifacts, or perform an unsafe datastore downgrade merely to recover an older version.
