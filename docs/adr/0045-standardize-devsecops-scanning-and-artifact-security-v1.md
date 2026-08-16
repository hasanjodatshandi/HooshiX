# ADR-0045: Standardize DevSecOps Scanning and Artifact Security v1

## Status

Accepted — current effective decision

## Date

2026-08-16

## Context

HooshiX already requires blocking source analysis, dependency integrity, dependency advisory scanning, final-artifact SBOM/vulnerability correlation, signed provenance, and fail-closed production admission. The current repository already uses OSV-Scanner for the implemented service's locked-dependency advisory scan and selects Semgrep, Syft, Grype, Cosign, and Kyverno for other parts of the chain, but the documents did not define one exact responsibility map and did not select a dedicated Git secret scanner.

Overlapping scanners can create duplicated findings without closing a distinct failure class. Missing responsibility boundaries can also cause false assumptions, such as treating Gradle dependency verification as CVE scanning, treating OSV locked-dependency scanning as final-image coverage, or treating the use of Semgrep CLI as proof that separate Semgrep Secrets or Supply Chain products are enabled.

This decision standardizes the DevSecOps control chain without adding any application runtime dependency.

## Decision

### 1. Tool responsibility map

HooshiX uses the following responsibility separation:

| Control | Selected tool/mechanism | Authority |
| --- | --- | --- |
| first-party source SAST and repository-owned source policy | Semgrep CLI with repository rules | source/security/framework/logging misuse |
| committed/current-tree and Git-history secret detection | Gitleaks CLI | secret discovery before merge/release |
| dependency artifact integrity and reproducibility | Gradle dependency verification + locks | expected dependency bytes/metadata, not vulnerability authority |
| locked/source dependency advisory scan | OSV-Scanner | early PR + scheduled known-vulnerability feedback for declared/locked dependencies |
| final-image software inventory | Syft | CycloneDX JSON SBOM for the exact final image digest |
| final-image/SBOM vulnerability correlation | Grype | release/deployed-artifact authority under ADR-0035/0038 |
| image signature, provenance, and signed SBOM attestation | Cosign | ADR-0017 artifact identity/attestation |
| production admission verification | Kyverno CEL policy APIs | digest/signature/provenance/SBOM/workload-policy enforcement |

No scanner above is an application runtime dependency. Application services do not call a scanner or vulnerability feed on a request path.

### 2. Semgrep scope

Repository Semgrep remains the blocking high-signal SAST/source-policy mechanism for first-party source code. Rules are repository-owned where HooshiX-specific invariants need enforcement and tool/rule execution is pinned in CI.

Use of Semgrep CLI does **not** imply that separate Semgrep Secrets, Semgrep Supply Chain, or hosted Semgrep product capabilities are enabled. Adoption of one of those products requires a separate reviewed change with ownership, licensing, data-flow, security, and overlap analysis.

### 3. Gitleaks secret scanning

Gitleaks CLI is the selected dedicated secret scanner. Technology Baseline owns the approved version; exact release asset checksum/digest is pinned by CI/tool metadata.

Required coverage includes:

- current repository files/tree;
- Git history relevant to the protected repository, including committed secrets that were later deleted from the working tree;
- CI configuration, manifests, values, scripts, source, fixtures, and documentation where secret material could be committed.

A detected real secret is a security incident/remediation item. Removing the text from the latest commit is not sufficient if the credential may have been exposed: the owning team must revoke/rotate the credential and follow incident/forensic requirements as applicable.

Secret values MUST NOT be emitted into CI logs, annotations, artifacts, or ordinary telemetry. Gitleaks output is redacted/bounded. Exceptions are exact, narrow, justified, owned, reviewed, and time-bounded. An exception cannot make a real active credential safe.

HooshiX selects the native Gitleaks CLI/tool artifact rather than depending on a GitHub Action wrapper as the architectural requirement. A future action wrapper requires the normal third-party action/license/security review and immutable action pinning.

### 4. Dependency integrity and advisory scanning

Gradle dependency verification and locks remain mandatory for expected dependency integrity and reproducibility. They do not answer whether an expected dependency version has a known vulnerability.

OSV-Scanner is the selected early dependency advisory scanner for declared/locked dependency evidence where supported. It runs on applicable PR CI and scheduled security verification with an exact pinned binary/checksum.

OSV-Scanner and Grype have different responsibilities:

```text
OSV-Scanner -> early declared/locked dependency advisory feedback
Syft + Grype -> exact final releasable/deployed artifact inventory and vulnerability authority
```

A passing OSV-Scanner result does not prove the final image is vulnerability-clean because the final image can contain OS packages, JDK/runtime files, native components, and transitive packaged content not represented by the scanned lockfile. A passing Grype final-artifact decision does not remove the value of earlier PR feedback.

ADR-0035 and ADR-0038 remain authoritative for release/deployed-digest vulnerability severity, freshness, continuous rescanning, VEX/exception behavior, ownership, and response targets.

### 5. Final-artifact SBOM and vulnerability correlation

Syft generates the CycloneDX JSON SBOM from the **final releasable image**, not only from source dependency manifests. The SBOM is bound to the exact image digest.

Grype consumes the final image/SBOM for vulnerability correlation so operating-system packages, transitive application dependencies, the JDK/runtime, and packaged native components remain visible. The final-image/SBOM Grype decision is the release/deployed-artifact vulnerability authority under ADR-0035/0038.

Gradle verification/locks and OSV-Scanner remain complementary upstream controls; neither replaces the final-image Syft/Grype release gate.

### 6. Signing, provenance, and admission

Cosign signs the exact final image digest and creates/verifies the required provenance and signed CycloneDX SBOM attestations under ADR-0017.

Kyverno remains the production admission authority for digest/signature/provenance/SBOM and applicable workload-security policy. Production admission does not synchronously query a vulnerability database; promotion CI and continuous digest-indexed rescanning own vulnerability freshness under ADR-0035/0038.

A vulnerability scanner finding or exception never authorizes an unsigned, unprovenanced, or un-attested production artifact.

### 7. Tools not selected in the current baseline

Trivy is **not** selected as an additional default scanner in the current baseline. Its image/dependency/SBOM/IaC capabilities substantially overlap the selected OSV-Scanner + Syft + Grype + existing Kubernetes/Helm/Kyverno validation chain. It may be reconsidered only when a measured coverage gap identifies a distinct failure class that the current controls do not adequately detect.

OWASP Dependency-Check is **not** selected as an additional default Java SCA gate in the current baseline. Early dependency advisory scanning is owned by OSV-Scanner; final-artifact/SBOM vulnerability correlation is owned by Syft + Grype; Gradle verification/locks own dependency integrity. Dependency-Check may be reconsidered if evidence shows a JVM/ecosystem vulnerability-identification gap that is not adequately covered by the selected controls.

A second scanner is not added only to increase tool count. It requires a documented distinct coverage objective, owner, expected signal, false-positive/exception model, operational cost, and proof that it does not weaken or ambiguously duplicate the current authority.

### 8. CI/release ordering

Logical authority order is:

```text
Gitleaks secret scan
-> Semgrep/static/architecture/format/dependency-integrity gates
-> OSV-Scanner locked-dependency advisory scan
-> unit/integration/contract/security tests
-> build final immutable image
-> Syft final-image CycloneDX SBOM
-> Grype final-image/SBOM vulnerability gate
-> Cosign signature + provenance + signed SBOM attestation
-> Helm/Kubernetes/Istio/Kyverno render/policy verification
-> staging validation
-> promote the same signed digest
```

Independent checks may run in parallel where their inputs and authority permit it. Parallelization never permits a downstream release/promotion gate to execute without all required predecessor evidence.

OSV-Scanner also runs at the repository's scheduled security cadence for current locked dependencies. Scheduled early dependency scanning complements, but does not replace, ADR-0035 continuous deployed-digest final-artifact rescanning.

### 9. Tool integrity and privileges

Security/build tools and third-party actions are pinned to reviewed immutable versions/checksums/digests/commit SHAs as applicable. CI uses least privilege and does not expose production-capable secrets to untrusted pull-request code.

Scanner databases/feeds and tool failures follow the freshness/fail-closed promotion semantics appropriate to their owned gate. ADR-0035/0038 govern final-artifact vulnerability freshness/response. A scanner outage is not permission to disable a required gate or silently reuse stale evidence beyond the approved window.

### 10. Ownership

- Platform Engineering owns reusable CI/tool installation, immutable pins, and integration mechanics.
- Security owns scanner policy, secret-rule governance, severity/VEX/exception policy, advisory/feed policy, and security escalation.
- Each service/deployed-artifact owner owns remediation of first-party findings and all direct/transitive findings present in its declared dependencies or final artifact.
- A committed-secret finding is jointly handled by the repository/service owner and Security; revoke/rotate first when exposure is plausible, then remediate repository/history according to incident requirements.

## Verification requirements

Executable implementation must prove at least:

- Gitleaks current-tree and Git-history positive/negative fixtures, redacted output, and blocking behavior;
- a synthetic committed secret remains detectable after deletion from the latest tree;
- Semgrep positive/negative fixtures for repository-owned high-signal rules;
- OSV-Scanner exact binary/checksum pin and a known-vulnerable locked-dependency positive fixture where a safe synthetic fixture can be maintained;
- Gradle verification failure, OSV dependency advisory failure, and final-artifact Grype failure remain distinct failure classes;
- final-image Syft CycloneDX generation bound to exact image digest;
- Grype scanning of that exact final image/SBOM with ADR-0035/0038 severity/freshness/exception behavior;
- Cosign correct-signer/wrong-signer/unsigned/provenance/SBOM positive and negative cases;
- Kyverno blocks missing/wrong digest/signature/provenance/SBOM as required;
- no production promotion can bypass a required secret/SAST/dependency-advisory/SBOM/vulnerability/signature/provenance/admission gate;
- selected tool binaries/actions/checksums/digests are immutable and verified;
- scanner output/artifacts contain no revealed secret material;
- Trivy and OWASP Dependency-Check are not silently introduced as competing authorities without a reviewed decision.

Documentation alone is not implementation evidence. Until the corresponding CI/release controls exist and execute successfully, their status remains `NOT PRESENT`, `PARTIAL`, or `NOT VERIFIED` as appropriate.

## Relationship to current decisions

This ADR complements ADR-0017, ADR-0035, ADR-0038, and ADR-0039. It does not supersede their artifact-signing, vulnerability-response, exception, or executable-quality semantics. It records the already-implemented OSV-Scanner early dependency advisory role, adds the missing dedicated secret-scanning decision, and makes the selected tool responsibilities explicit.

It does not change service boundaries, runtime dependencies, persistence, network trust, authentication, Authorization, Kafka, OpenBao, or observability authority.

## Rollback considerations

Rollback or tool replacement MUST preserve equivalent or stronger secret detection, source SAST, early dependency advisory coverage, final-artifact inventory/vulnerability correlation, signature/provenance/SBOM evidence, and fail-closed production admission.

Rollback MUST NOT return to no committed-secret scanning, no locked-dependency advisory signal, source-only vulnerability visibility, unsigned production artifacts, unverified provenance/SBOM, or admission that accepts missing required evidence. A tool may be replaced only through a reviewed change that preserves the owned failure classes and evidence contracts.