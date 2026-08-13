# ADR-0046: Enforce Signed Artifacts and Provenance at Admission v1

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized and security-refreshed on 2026-08-13

## Decision

Production uses Kyverno with stable CEL-based `policies.kyverno.io/v1` policy APIs. New controls do not use deprecated legacy `ClusterPolicy` types.

Protected production workloads require:

- image reference by immutable `@sha256` digest;
- valid Cosign/Sigstore-compatible signature from the approved CI signer identity;
- signed build provenance/attestation bound to trusted CI source revision/workflow;
- signed CycloneDX SBOM attestation.

Signer trust uses exact OIDC issuer + subject constraints; broad wildcard trust is prohibited.

Staging validates new policy in audit mode before production deny/fail-closed admission. Production admission failure blocks new/updated workload scheduling; it does not terminate already running pods merely because a registry later becomes unavailable.

Kyverno admission runs with at least 3 replicas, topology spread, and disruption protection. Bootstrap/control-plane exclusions are narrow, versioned, owned, and tested. Emergency deployment still requires a separately trusted audited signer; unsigned production deployment is prohibited.

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

Verify digest-only references, valid signer, wrong signer, unsigned image, missing/invalid provenance, missing/invalid SBOM, exact signer identity, audit-to-deny promotion, HA admission, narrow exemptions, policy-authoring RBAC, disabled/unneeded HTTP context, destination allow-list/blocklist behavior when HTTP context is approved, cloud-metadata/loopback/private-target SSRF negatives, credential non-forwarding, and fail-closed external-context behavior.

## Rollback considerations

Rollback MUST preserve digest pinning, trusted signature/provenance/SBOM enforcement, narrow signer identity, admission HA, policy-authoring least privilege, and policy-engine network/SSRF controls. It MUST NOT restore deprecated policy types as new authority, broaden signer trust, enable unsigned emergency deployment, or enable unrestricted policy HTTP access.
