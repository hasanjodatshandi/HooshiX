# DevSecOps Security Toolchain — Current State

## 1. Purpose and authority

This document is the implementation-facing map for pre-runtime source, secret, dependency-integrity, dependency-advisory, SBOM, final-artifact vulnerability, signing/provenance, and admission controls.

ADR-0045 owns the selected tool responsibility model. ADR-0017 owns signed artifact/provenance/admission semantics. ADR-0035 and ADR-0038 own release/deployed-artifact vulnerability correlation, feed freshness, severity, exceptions/VEX, ownership, and response. ADR-0039 owns executable Java quality gates.

These controls operate in developer/CI/release/admission paths. They are not application runtime dependencies and do not change service business authority.

## 2. Control chain

```text
Git/source
  -> Gitleaks: current tree + Git-history secret detection
  -> Semgrep: first-party SAST + repository source policy
  -> Gradle verification/locks: dependency integrity
  -> OSV-Scanner: locked/source dependency advisory feedback
  -> ArchUnit/SpotBugs/format/tests/contracts
  -> build final immutable OCI image
  -> Syft: CycloneDX JSON SBOM for exact final image digest
  -> Grype: final-image/SBOM vulnerability release decision
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

The protected CI implementation uses the immutable-digest official Gitleaks container image pinned by the service workflows. Output and tool identity are verified by the workflow controls. Output is fully redacted and must not export discovered secret values to CI logs or artifacts.

A real committed credential is not remediated merely by deleting the latest line. When exposure is plausible:

1. revoke/rotate the credential;
2. contain and preserve required incident/forensic evidence;
3. remove the secret from current source;
4. perform approved history remediation when required;
5. verify the replacement credential is not present in Git or logs.

Allow-list/ignore entries are exact, justified, owned, reviewed, and bounded. A live credential cannot be made acceptable through an ignore rule.

## 5. Dependency integrity — Gradle

Gradle dependency verification and lock files remain mandatory for expected dependency integrity/reproducibility.

They answer whether the build resolved the expected dependency bytes/metadata. They do not answer whether that version has a known vulnerability.

## 6. Early dependency advisory scanning — OSV-Scanner

OSV-Scanner is the selected early advisory scanner for supported declared/locked dependency evidence.

Current repository implementation pins OSV-Scanner 2.4.0 in the implemented Compromised Password,
Notification, Identity, Authorization, and Web BFF service security suites and in the frontend
workflow. It scans the five Gradle lockfiles and the frontend npm lockfile during blocking
verification; the service suites also re-run from the scheduled repository workflow. Exact
downloaded-binary checksum ownership remains in each implemented workflow.

This is useful fast feedback, but it is not the final-artifact vulnerability authority:

```text
OSV-Scanner -> declared/locked dependency advisory feedback
Syft+Grype  -> exact final releasable/deployed artifact inventory and vulnerability decision
```

A passing OSV result does not cover OS packages, JDK/runtime files, native libraries, or other image content absent from the lockfile.

## 7. SBOM — Syft

Syft generates CycloneDX JSON from the final releasable image, not only from Gradle manifests or source dependency metadata.

The SBOM is indexed/bound to the exact image digest and becomes part of the signed release evidence. Native/runtime/OS/transitive components must remain visible.

Technology Baseline owns the approved Syft version. Exact release artifact integrity is pinned in CI metadata.

## 8. Final-artifact vulnerability correlation — Grype

Grype scans the exact final image/SBOM and owns the release/deployed-artifact vulnerability decision.

ADR-0035/0038 remain authoritative for:

- Critical/High promotion behavior;
- database/feed freshness;
- continuous deployed-digest rescanning;
- CISA KEV/threat-intelligence prioritization;
- VEX/exceptions and expiry;
- remediation ownership and response targets.

A scanner/feed outage cannot silently disable a required promotion gate or reuse evidence beyond its approved freshness window.

OSV-Scanner remains complementary early feedback and does not replace this release boundary.

## 9. Signing and attestations — Cosign

Cosign binds release evidence to the exact final image digest.

Required production evidence includes:

- trusted image signature;
- build provenance/attestation tied to reviewed source/workflow identity;
- signed CycloneDX SBOM attestation.

Signer trust is exact and narrow. An advisory exception never authorizes an unsigned or unprovenanced artifact.

## 10. Admission — Kyverno

Kyverno remains the production admission enforcement point for digest/signature/provenance/SBOM and applicable workload-security controls.

New production policies use stable `policies.kyverno.io/v1` CEL APIs under current architecture. Admission fails closed for protected creates/updates when required evidence cannot be verified.

Kyverno does not synchronously query the vulnerability database. Promotion CI and continuous digest-indexed rescanning own vulnerability freshness.

## 11. Tools intentionally not selected

### Trivy

Trivy is not a default HooshiX scanner in the current baseline. Its image/dependency/SBOM/IaC coverage substantially overlaps the selected OSV-Scanner + Syft + Grype + Kubernetes/Helm/Kyverno verification chain.

Reconsider only after evidence identifies a distinct high-value failure class that current controls do not adequately detect. The proposal must define owner, expected signal, false-positive/exception behavior, CI cost, and interaction with existing authorities.

### OWASP Dependency-Check

OWASP Dependency-Check is not a default HooshiX Java SCA gate in the current baseline. Early declared/locked dependency advisory scanning belongs to OSV-Scanner; final-artifact/SBOM vulnerability correlation belongs to Syft + Grype; Gradle verification/locks own dependency integrity.

Reconsider only after evidence shows a JVM/ecosystem advisory-identification gap that the selected controls do not adequately cover.

## 12. Tool-selection rule

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

## 13. Current implementation status

Architecture selection is not implementation evidence.

At the current repository state:

- repository Semgrep rules exist for the implemented Compromised Password, Notification, Identity registration/authentication/session/JWT/tenant slices, Authorization, Web BFF, and frontend; protected service run `33301549810` and frontend run `33301549573` passed on implementation commit `209684a`, including the frontend positive and negative fixtures;
- OSV-Scanner 2.4.0 locked-dependency scanning is **IMPLEMENTED** for Compromised Password, Notification, Identity, Authorization, Web BFF, and the frontend npm lockfile; protected service run `33301549810` and frontend run `33301549573` passed on implementation commit `209684a`;
- Gitleaks 8.30.0 current-tree/Git-history scanning is **IMPLEMENTED** in all five implemented Java service security workflows with redacted negative/current-tree-positive/commit-then-delete history fixtures and reviewed narrow false-positive policy; every protected service job passed its configured Gitleaks steps on `main@68cf66c` in repository baseline run `33105936814`;
- final-image Syft SBOM generation is **IMPLEMENTED / PRODUCTION EXECUTION NOT VERIFIED** for all six application release components, including `web-frontend`, in the protected production release workflow and is retained as digest-bound CycloneDX evidence;
- final-image/SBOM Grype vulnerability gating is **IMPLEMENTED / PRODUCTION EXECUTION NOT VERIFIED** with retained scan/database metadata plus a scheduled two-hour tracked-production-digest rescan path;
- Cosign signing/provenance/SBOM attestation release automation is **IMPLEMENTED / PRODUCTION EXECUTION NOT VERIFIED** and is restricted to the protected main production-release workflow identity with GitHub OIDC;
- Kyverno production admission generation is **IMPLEMENTED / PRODUCTION CLUSTER EXECUTION NOT VERIFIED** using stable policies.kyverno.io/v1 fail-closed release allow-list and image-validation policy output for all six release digests;
- Trivy and OWASP Dependency-Check are **NOT SELECTED**, not missing required implementation.

`implementation-status.md` is the repository-level status authority and must stay aligned with actual files/checks.

## 14. Verification

Before the selected DevSecOps chain is reported as complete, executable evidence covers:

- Gitleaks current-tree and Git-history positive/negative fixtures with redacted output;
- a committed-then-deleted synthetic secret that is still detected in history;
- Semgrep custom-rule positive/negative fixtures;
- OSV-Scanner exact binary/checksum pin plus blocking declared/locked dependency scan and scheduled execution;
- final-image Syft CycloneDX generation bound to exact image digest;
- Grype final-image/SBOM scan with ADR-0035/0038 policy behavior;
- separate Gradle-integrity, OSV dependency-advisory, and Grype final-artifact failure cases;
- Cosign correct/wrong signer, unsigned, provenance, and signed-SBOM cases;
- Kyverno admission positives/negatives for mandatory artifact evidence;
- immutable tool/action pins and verified checksums/digests;
- no secret values in scanner logs/artifacts;
- no bypass from scanner/feed outage or stale evidence.

Repository implementation is not production evidence. Real production registry, signer, Grype feed/database, release-image, admission-controller, and deployed-digest executions remain NOT VERIFIED until protected workflows and the production environment produce the required evidence.
