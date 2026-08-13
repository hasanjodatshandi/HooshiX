# ADR-0046: Enforce Signed Artifacts and Provenance at Admission v1

## Status

Accepted

## Date

2026-08-10

## Decision

Production uses Kyverno with stable CEL-based `policies.kyverno.io/v1` policy APIs. New controls do not use deprecated legacy `ClusterPolicy` types.

Protected production workloads require:

- image reference by immutable `@sha256` digest;
- valid Cosign/Sigstore-compatible signature from the approved CI signer identity;
- signed build provenance/attestation bound to trusted CI source revision/workflow;
- signed CycloneDX SBOM attestation.

Signer trust uses exact OIDC issuer + subject constraints; broad wildcard trust is prohibited.

Staging validates new policy in audit mode before production deny/fail-closed admission. Production admission failure blocks new/updated workload scheduling; it does not terminate already running pods merely because a registry later becomes unavailable.

Kyverno admission runs with at least 3 replicas, topology spread, and disruption protection. Bootstrap/control-plane exclusions are narrow and versioned. Emergency deployment still requires a separately trusted audited signer; unsigned production deployment is prohibited.

The Technology Baseline must use an upstream-supported Kyverno minor compatible with the selected Kubernetes minor at production rollout time.

## Verification Requirements

Digest-only, valid signer, wrong signer, unsigned image, missing/invalid provenance, missing/invalid SBOM, exact identity, audit->deny promotion, HA admission, and exemption tests.

## Consequences

Supply-chain signatures become an enforcement control without adding an application request-path hop.
