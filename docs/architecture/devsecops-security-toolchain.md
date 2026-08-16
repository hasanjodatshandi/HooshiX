# DevSecOps Security Toolchain — Current State

## 1. Purpose and authority

This document is the implementation-facing map for pre-runtime source, secret, dependency-integrity, SBOM, vulnerability, signing/provenance, and admission controls.

ADR-0045 owns the selected tool responsibility model. ADR-0017 owns signed artifact/provenance/admission semantics. ADR-0035 and ADR-0038 own vulnerability correlation, feed freshness, severity, exceptions/VEX, ownership, and response. ADR-0039 owns executable Java quality gates.

These controls operate in developer/CI/release/admission paths. They are not application runtime dependencies and do not change service business authority.

## 2. Control chain

```text
Git/source
  -> Gitleaks: current tree + Git-history secret detection
  -> Semgrep: first-party SAST + repository source policy
  -> ArchUnit/SpotBugs/format/tests/contract/dependency-integrity gates
  -> build final immutable OCI image
  -> Syft: CycloneDX JSON SBOM for exact final image digest
  -> Grype: final-image/SBOM vulnerability correlation
  -> Cosign: image signature + provenance + signed SBOM attestation
  -> Helm/Kubernetes/Istio/Kyverno policy verification
  -> staging
  -> promote the exact same signed digest
```

Independent checks may run in parallel only when their inputs and authority permit it. Production promotion requires all mandatory predecessor evidence.

## 3. Source SAST — Semgrep

Semgrep remains the selected blocking source SAST/source-policy mechanism.

Use it for high-signal first-party rules such as:

- framework/security misuse;
- unsafe logging/telemetry patterns;
- prohibited runtime/provider/network paths;
- project-specific code patterns that are precise enough to enforce statically.

Rules and execution are pinned/reviewed. Positive and negative fixtures are required for custom rules where practical.

Semgrep CLI use does not mean separate Semgrep Secrets, Semgrep Supply Chain, or hosted product capabilities are enabled. Those require a separate reviewed adoption decision.

## 4. Secret scanning — Gitleaks

Gitleaks CLI is the dedicated secret-scanning tool.

Required coverage:

- current repository files/tree;
- Git history relevant to the protected repository;
- source, scripts, CI, manifests, values, fixtures, and documentation.

The protected CI/release implementation uses the pinned native Gitleaks tool artifact and verifies its official checksum/digest. Output is fully redacted and must not export discovered secret values to CI logs or artifacts.

A real committed credential is not remediated merely by deleting the latest line. When exposure is plausible:

1. revoke/rotate the credential;
2. contain and preserve required incident/forensic evidence;
3. remove the secret from current source;
4. perform approved history remediation when required;
5. verify the replacement credential is not present in Git or logs.

Allow-list/ignore entries are exact, justified, owned, reviewed, and bounded. A live credential cannot be made acceptable through an ignore rule.

## 5. Dependency integrity is not vulnerability scanning

Gradle dependency verification and lock files remain mandatory for expected dependency integrity/reproducibility.

They do not answer whether a dependency has a known vulnerability. Vulnerability authority is the final-artifact/SBOM pipeline under ADR-0035/0038.

This distinction is mandatory in review and reporting:

```text
Gradle verification/locks -> did we resolve the expected artifact?
Syft + Grype               -> what is in the final artifact and what known findings affect it?
```

## 6. SBOM — Syft

Syft generates CycloneDX JSON from the final releasable image, not only from Gradle manifests or source dependency metadata.

The SBOM is indexed/bound to the exact image digest and becomes part of the signed release evidence. Native/runtime/OS/transitive components must remain visible.

Technology Baseline owns the approved Syft version. Exact release artifact integrity is pinned in CI metadata.

## 7. Vulnerability correlation — Grype

Grype scans the exact final image/SBOM.

ADR-0035/0038 remain authoritative for:

- Critical/High promotion behavior;
- database/feed freshness;
- continuous deployed-digest rescanning;
- CISA KEV/threat-intelligence prioritization;
- VEX/exceptions and expiry;
- remediation ownership and response targets.

A scanner/feed outage cannot silently disable a required promotion gate or reuse evidence beyond its approved freshness window.

## 8. Signing and attestations — Cosign

Cosign binds release evidence to the exact final image digest.

Required production evidence includes:

- trusted image signature;
- build provenance/attestation tied to reviewed source/workflow identity;
- signed CycloneDX SBOM attestation.

Signer trust is exact and narrow. An advisory exception never authorizes an unsigned or unprovenanced artifact.

## 9. Admission — Kyverno

Kyverno remains the production admission enforcement point for digest/signature/provenance/SBOM and applicable workload-security controls.

New production policies use stable `policies.kyverno.io/v1` CEL APIs under current architecture. Admission fails closed for protected creates/updates when required evidence cannot be verified.

Kyverno does not synchronously query the vulnerability database. Promotion CI and continuous digest-indexed rescanning own vulnerability freshness.

## 10. Tools intentionally not selected

### Trivy

Trivy is not a default HooshiX scanner in the current baseline. Its image/dependency/SBOM/IaC coverage substantially overlaps the selected Syft + Grype + Kubernetes/Helm/Kyverno verification chain.

Reconsider only after evidence identifies a distinct high-value failure class that current controls do not adequately detect. The proposal must define owner, expected signal, false-positive/exception behavior, CI cost, and interaction with existing authorities.

### OWASP Dependency-Check

OWASP Dependency-Check is not a default HooshiX Java SCA gate in the current baseline. Final-artifact/SBOM vulnerability correlation belongs to Syft + Grype while Gradle verification/locks own dependency integrity.

Reconsider only after evidence shows a JVM/ecosystem advisory-identification gap that the selected pipeline does not adequately cover.

## 11. Tool-selection rule

Security depth comes from distinct failure-class coverage, not scanner count.

A second or replacement scanner requires:

- a distinct documented coverage objective;
- ownership;
- security/license/data-flow review;
- immutable version/integrity pinning;
- expected signal and false-positive policy;
- exception lifecycle;
- runtime/CI operational cost;
- proof that the new tool does not weaken or create ambiguous ownership with current controls.

## 12. Current implementation status

Architecture selection is not implementation evidence.

At the current repository state:

- repository Semgrep rules exist for the implemented Compromised Password service;
- Gitleaks CI/repository-history scanning is **NOT PRESENT / NOT VERIFIED**;
- final-image Syft SBOM generation is **NOT PRESENT / NOT VERIFIED**;
- final-image/SBOM Grype vulnerability gating is **NOT PRESENT / NOT VERIFIED**;
- Cosign signing/provenance/SBOM attestation release automation is **NOT PRESENT / NOT VERIFIED**;
- Kyverno production admission implementation is **NOT PRESENT / NOT VERIFIED**;
- Trivy and OWASP Dependency-Check are **NOT SELECTED**, not missing required implementation.

`implementation-status.md` is the repository-level status authority and must stay aligned with actual files/checks.

## 13. Verification

Before the selected DevSecOps chain is reported as implemented, executable evidence covers:

- Gitleaks current-tree and Git-history positive/negative fixtures with redacted output;
- a committed-then-deleted synthetic secret that is still detected in history;
- Semgrep custom-rule positive/negative fixtures;
- final-image Syft CycloneDX generation bound to exact image digest;
- Grype final-image/SBOM scan with ADR-0035/0038 policy behavior;
- separate dependency-integrity vs vulnerability-finding failure cases;
- Cosign correct/wrong signer, unsigned, provenance, and signed-SBOM cases;
- Kyverno admission positives/negatives for mandatory artifact evidence;
- immutable tool/action pins and verified checksums/digests;
- no secret values in scanner logs/artifacts;
- no bypass from scanner/feed outage or stale evidence.

Documentation-only completion remains `NOT VERIFIED` for executable controls.