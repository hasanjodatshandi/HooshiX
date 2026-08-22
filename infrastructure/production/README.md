# Production Infrastructure Contract

This directory is the repository-owned desired-state and verification boundary for the selected
`production-single-server` profile. It does not claim that a production host, provider, registry,
secret authority, backup target, external monitor, or recovery exercise exists.

Current authority is ADR-0042 through ADR-0045 plus the current runtime/deployment, network,
security, observability, DevSecOps, Technology Baseline, and Production Readiness documents.

## Repository-owned scope

The repository defines and fail-closed verifies:

- the one-node K3s/Calico/Istio/Kyverno topology and disabled bundled components;
- the non-HA one-replica/no-HPA/no-availability-PDB workload contract;
- PostgreSQL, Redis, Kafka, OpenBao/External Secrets, edge, observability, and GitOps invariants;
- WireGuard-only management reachability and OpenSSH/FIDO2/JIT/off-host-audit requirements;
- immutable image digest, Syft CycloneDX, Grype High/Critical blocking, Cosign signature,
  provenance, and signed-SBOM release requirements;
- exact release metadata, external evidence references, and Kubernetes Secret reference names;
- backup/PITR/cold-DR targets and traffic-enable evidence;
- deterministic Argo CD/application and Kyverno release rendering from a verified release manifest.

No production secret value, private key, password, token, unseal material, provider credential, or
WireGuard private key belongs in Git. A release manifest contains public metadata and references
only.

## Verification

Repository conformance:

```bash
make production-verify
```

Concrete release metadata:

```bash
python3 scripts/production/verify_release.py --manifest /path/to/release.json
```

Render GitOps desired state only after the release metadata passes:

```bash
python3 scripts/production/render_gitops.py \
  --manifest /path/to/release.json \
  --output /safe/review/directory
```

The renderer never reads secret values and never mutates a cluster.

## Evidence boundary

Repository conformance is not production readiness. Production traffic remains blocked until the
current Production Readiness Checklist has real environment evidence for provider/firewall CIDRs,
capacity/headroom, HIBP production corpus, registry and signer identity, OpenBao custody, off-site
backup/PITR restore, external total-host monitoring, privileged audit, WireGuard/FIDO2/JIT host
access, provider delivery, final release artifacts, and the required cold-DR exercise.
