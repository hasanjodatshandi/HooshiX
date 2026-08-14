# ADR-0017: Enforce Signed Artifacts and Provenance at Admission v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized and security-refreshed on 2026-08-14

## Decision

Production uses Kyverno with stable CEL-based `policies.kyverno.io/v1` policy APIs. New controls do not use deprecated legacy `ClusterPolicy` types.

Protected production workloads require:

- image reference by immutable `@sha256` digest;
- valid Cosign/Sigstore-compatible signature from the approved CI signer identity;
- signed build provenance/attestation bound to trusted CI source revision/workflow;
- signed CycloneDX SBOM attestation.

Signer trust uses exact OIDC issuer + subject constraints; broad wildcard trust is prohibited.

Staging validates new policy in audit mode before production deny/fail-closed admission. Production admission failure blocks new/updated workload scheduling; it does not terminate already running pods merely because a registry or policy dependency later becomes unavailable.

### Profile-aware availability

`production-ha` runs Kyverno with at least 3 replicas, topology spread, and disruption protection.

`production-single-server` under ADR-0042 may run one Kyverno replica because multiple replicas on one physical server do not provide node HA and consume additional memory. This is an explicit availability reduction, not an enforcement reduction. Admission unavailability MUST fail closed for protected production creates/updates and MUST NOT become an allow/bypass path.

The single-server profile may reduce the number of active policies, but the retained high-value policy set MUST continue to enforce at least:

- digest-only images;
- approved signature/signer identity;
- build provenance/attestation;
- signed CycloneDX SBOM attestation;
- prohibited privileged, unsafe host-network/`hostPath`, and unsafe security-context patterns unless a specific current exception exists;
- dedicated ServiceAccount and critical deployment invariants that are reliable admission properties.

Policy reduction requires an inventory proving that a removed policy is redundant, non-security-critical for the profile, or enforced by another blocking control. Removing Kyverno itself or changing production enforcement to audit-only is prohibited.

Bootstrap/control-plane exclusions are narrow, versioned, owned, and tested. Emergency deployment still requires a separately trusted audited signer; unsigned production deployment is prohibited.

### Policy-engine network safety

Production admission policy is not an unrestricted HTTP client. CEL HTTP context loading is disabled wherever it is not required. If a reviewed policy genuinely requires HTTP context data:

- the exact destination and purpose are allow-listed and versioned;
- loopback, link-local/cloud metadata, unreviewed private-network targets, and arbitrary caller-influenced URLs are blocked;
- credentials/tokens are not forwarded to arbitrary destinations;
- response size, timeout, failure behavior, and data classification are bounded;
- the policy fails according to the explicit admission-security contract and does not silently convert lookup failure into allow;
- positive and negative SSRF/network-policy tests are required before production enforcement.

Only tightly controlled GitOps/CI identities may create or modify cluster-scoped admission policy. Application workloads and ordinary service identities cannot author admission policy.

The Technology Baseline must use an upstream-supported Kyverno minor compatible with the selected Kubernetes minor at production rollout time.

## Verification requirements

Both profiles verify digest-only references, valid signer, wrong signer, unsigned image, missing/invalid provenance, missing/invalid SBOM, exact signer identity, audit-to-deny promotion, narrow exemptions, policy-authoring RBAC, disabled/unneeded HTTP context, destination allow-list/blocklist behavior when HTTP context is approved, cloud-metadata/loopback/private-target SSRF negatives, credential non-forwarding, and fail-closed external-context behavior.

`production-ha` additionally proves admission remains available through one replica/node disruption. `production-single-server` proves the reduced policy inventory still covers all mandatory controls, one-replica admission fails closed when unavailable, and no removed policy creates an unsigned/privileged/identity bypass.

## Rollback considerations

Rollback MUST preserve digest pinning, trusted signature/provenance/SBOM enforcement, narrow signer identity, profile-required admission behavior, policy-authoring least privilege, and policy-engine network/SSRF controls. It MUST NOT restore deprecated policy types as new authority, broaden signer trust, enable unsigned emergency deployment, enable unrestricted policy HTTP access, remove Kyverno, or weaken production enforcement to audit-only.
