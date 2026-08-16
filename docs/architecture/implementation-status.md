# Implementation and Evidence Status — Current State

This file is the canonical repository-level status view for architecture, implementation presence, runtime evidence, and production readiness.

Architecture documents describe approved targets. A target path named in documentation is not proof that executable implementation exists.

## Current repository state

At this revision the repository contains architecture documentation, the repository-governance baseline, and the first executable service implementation under:

```text
services/compromised-password-service/
```

Implemented repository-governance artifacts are:

```text
Makefile
scripts/baseline/
.github/workflows/repository-baseline.yml
```

The repository baseline verifies file-index consistency, stable ADR identifiers/register coverage, dependency-registry/schema/Markdown-view consistency, current source references, and selected guarded structure rules. It also invokes the reusable Compromised Password service security suite on PR/push and on the scheduled repository security cadence.

The Compromised Password service repository implementation includes service-owned Java/Gradle source and wrapper, Protobuf/gRPC contract, immutable SQLite lookup adapter, deterministic tests, dependency locks/verification metadata, container definition, Helm/security policy package, Day-One service telemetry code, and service CI/static/architecture/deployment gates. It also includes the service-owned offline/local SHA-1 source-to-SQLite dataset builder, version-2 release-manifest schema, generated-fixture integration/CLI verification, explicit build/runtime prefix-cardinality and serialized-response compatibility bounds, exact runtime manifest SHA-256 binding to the SQLite artifact digest, raw-corpus/generated-database Git guards, privacy/architecture regression enforcement, and a runtime-JAR exclusion that keeps builder tooling out of the deployed application artifact. The builder has no URL/network/downloader path and normal PR CI uses only generated fixtures marked `GENERATED_TEST_FIXTURE`. Runtime image construction verifies the exact official Temurin 25.0.4+7 Linux/x64 archive SHA-256 before placing that JDK in the image.

The service security suite already installs digest-verified OSV-Scanner 2.4.0 and scans the locked Gradle dependencies for known vulnerabilities. Because the repository baseline invokes that reusable service security suite on schedule, the same locked-dependency advisory scan also runs on the scheduled repository security cadence. This is early dependency-advisory evidence only; it is not final-image/SBOM vulnerability evidence.

This is repository implementation evidence only. It is not proof of approved production HIBP acquisition/provenance/licensing, current corpus freshness, real complete-corpus cardinality/response measurements and reviewed production bounds, staging runtime, load, recovery, final-image SBOM/vulnerability correlation, artifact signing, admission, or production readiness.

ADR-0045 defines the repository target for DevSecOps source/secret/dependency-advisory/final-artifact security: Semgrep SAST, Gitleaks current-tree/Git-history secret scanning, OSV-Scanner early declared/locked dependency advisory scanning, Syft CycloneDX SBOM, Grype final-artifact vulnerability correlation, Cosign signature/provenance/signed-SBOM attestation, and Kyverno admission. This architecture decision does **not** mean the full release chain is implemented.

Current repository evidence is:

- service-specific Semgrep enforcement exists;
- OSV-Scanner locked-dependency advisory scanning exists for Compromised Password and runs in PR/push/scheduled security verification;
- Gitleaks is not present;
- Syft/Grype/Cosign release automation is not present;
- production Kyverno admission is not present.

Trivy and OWASP Dependency-Check are not selected current-baseline tools under ADR-0045. Their absence is not an implementation gap unless a later reviewed decision changes the selected control chain.

These root implementation areas are still not present:

```text
deploy/
infrastructure/
```

Other application services remain absent. No production platform runtime, complete observability backend, restore exercise, complete-stack load test, artifact-signing release pipeline, final-image vulnerability/admission gate, or production traffic readiness is claimed.

## Capability/service status

| Capability | Architecture | Independent implementation | Runtime evidence | Production readiness | Planned target |
| --- | --- | --- | --- | --- | --- |
| Identity Service | DESIGNED | NOT PRESENT | NOT VERIFIED | NOT VERIFIED | `services/identity-service` |
| Authorization Service | DESIGNED | NOT PRESENT | NOT VERIFIED | NOT VERIFIED | `services/authorization-service` |
| Notification Service | DESIGNED | NOT PRESENT | NOT VERIFIED | NOT VERIFIED | `services/notification-service` |
| Web BFF | DESIGNED | NOT PRESENT | NOT VERIFIED | NOT VERIFIED | `services/web-bff` |
| Compromised Password Service | DESIGNED | IMPLEMENTED | repository service/builder/runtime-package/OSV dependency-advisory CI evidence is commit-specific; production HIBP corpus/runtime evidence NOT VERIFIED | NOT VERIFIED | `services/compromised-password-service` |
| Reference Data capability | DESIGNED | local immutable adapter permitted when needed | NOT VERIFIED | NOT VERIFIED | owning deployable bundle/module |
| Reference Data independent service | DESIGNED / GATED | PLANNED / GATED | NOT VERIFIED | NOT VERIFIED | `services/reference-data-service` only after ADR-0041 trigger |

`IMPLEMENTED` means the repository artifacts for the implemented slice exist. It does not mean the service, production corpus, or release artifact has been deployed or approved.

## Platform and DevSecOps status

| Platform/control area | Architecture | Implementation | Evidence |
| --- | --- | --- | --- |
| Repository governance baseline | DESIGNED | IMPLEMENTED | CI evidence is commit-specific; `make baseline-verify` is the local entry point |
| Compromised Password service CI/architecture/security/dataset-build gates | DESIGNED | IMPLEMENTED | CI evidence is commit-specific |
| Semgrep source SAST/policy | DESIGNED under ADR-0039/0045 | PARTIAL | implemented for Compromised Password; cross-service coverage NOT VERIFIED |
| Gitleaks current-tree/Git-history secret scanning | DESIGNED under ADR-0045 | NOT PRESENT | NOT VERIFIED |
| OSV-Scanner declared/locked dependency advisory scan | DESIGNED under ADR-0045 | PARTIAL | OSV-Scanner 2.4.0 implemented for Compromised Password PR/push/scheduled service-security suite; future-service coverage NOT VERIFIED |
| Syft final-image CycloneDX SBOM generation | DESIGNED under ADR-0035/0045 | NOT PRESENT | NOT VERIFIED |
| Grype final-image/SBOM vulnerability correlation | DESIGNED under ADR-0035/0038/0045 | NOT PRESENT | NOT VERIFIED |
| Cosign image signature/provenance/signed-SBOM release automation | DESIGNED under ADR-0017/0045 | NOT PRESENT | NOT VERIFIED |
| Trivy / OWASP Dependency-Check | NOT SELECTED under ADR-0045 | NOT APPLICABLE | NOT APPLICABLE |
| K3s/Kubernetes/Calico | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Istio Ambient runtime | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Kyverno CEL policy/admission set | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Traefik + Caddy/Coraza edge | DESIGNED | NOT PRESENT | NOT VERIFIED |
| WireGuard management overlay | DESIGNED | NOT PRESENT | NOT VERIFIED |
| CloudNativePG/PostgreSQL | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Security Redis | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Kafka | DESIGNED | NOT PRESENT | NOT VERIFIED |
| OpenBao + External Secrets | DESIGNED | NOT PRESENT | NOT VERIFIED |
| GitOps/Argo CD | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Cross-service CI/security/supply-chain release gates | DESIGNED | PARTIAL | first service Semgrep/OSV repository gates exist; Gitleaks/Syft/Grype/Cosign/Kyverno release evidence NOT VERIFIED |
| OpenTelemetry Collector | DESIGNED under ADR-0044 | NOT PRESENT | NOT VERIFIED |
| Prometheus/Alertmanager/Grafana | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Loki log backend | DESIGNED under ADR-0044 | NOT PRESENT | NOT VERIFIED |
| Tempo trace backend | DESIGNED under ADR-0044 | NOT PRESENT | NOT VERIFIED |
| External host-down monitoring | REQUIRED / PROVIDER TBD | NOT PRESENT | NOT VERIFIED |
| Authoritative privileged/security audit | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Backup/PITR/cold-DR automation | DESIGNED | NOT PRESENT | NOT VERIFIED |

## Repository governance now enforced

The bootstrap baseline makes these current repository invariants executable:

- `FILE_INDEX.txt` must exactly match the clean repository file set and remain sorted;
- ADR file identifiers, headings, and the Decision Register must remain consistent and non-reused;
- dependency-registry version/classes/required edge fields/policy references must match the current schema constraints enforced by the bootstrap verifier;
- the dependency Markdown operation list must match canonical YAML exactly and in canonical order;
- current architecture source references checked by the baseline must resolve to repository files;
- the ADR-0041-gated `services/reference-data-service` path is rejected until the architecture/trigger evidence is intentionally revised;
- root `services/common` and `services/shared` dumping grounds are rejected;
- the Compromised Password Gradle wrapper must retain executable state.

Service-specific CI adds stricter checks for implemented code, OSV locked-dependency advisory scanning, offline dataset-build tooling, runtime dataset identity/compatibility validation, telemetry/privacy controls, and deployment/runtime-image artifacts. Repository governance does not replace runtime/staging/release evidence.

ADR-0045 documents additional target gates. Gitleaks/Syft/Grype/Cosign/Kyverno must not be reported as implemented until their repository workflows/policies exist and execute successfully. OSV-Scanner must not be reported as final-image vulnerability evidence.

## Implementation/release gates still not evidenced

Current architecture still requires evidence that this repository slice does not create by itself:

- blocking Gitleaks current-tree + protected Git-history scanning with redacted output and positive/negative fixtures;
- final-image Syft CycloneDX generation bound to exact image digest;
- Grype final-image/SBOM vulnerability policy, feed freshness, exception/VEX behavior, and deployed-digest rescanning;
- Cosign exact-digest signature, provenance, and signed-SBOM attestation plus Kyverno admission positives/negatives;
- approved official complete HIBP Pwned Passwords SHA-1 acquisition/provenance/tool/licensing evidence, current freshness <=35 days, and a reviewed production dataset release artifact built from that local source;
- real complete-corpus row count, maximum prefix cardinality, exact serialized-response measurements and reviewed production runtime compatibility limits with safety margin;
- representative complete-corpus disk-backed p95/p99, saturation, and profile-specific runtime/recovery evidence for Compromised Password;
- real Collector/Loki/Tempo/Prometheus integration, telemetry canary/privacy evidence, and telemetry-backend fault evidence beyond service-level code/tests;
- signed final image/dataset release artifacts as applicable, CycloneDX SBOM, final-artifact vulnerability correlation, provenance, admission validation, and staging-to-production digest promotion;
- K3s/Calico/Istio/Kyverno/OpenBao/edge/observability platform implementation and complete-stack capacity evidence;
- ADR-0024 quota implementation/evidence when a quota-owning service is implemented;
- Reference Data deployable trigger evidence before any independent Reference Data service creation.

These are implementation/release gates, not evidence that production is ready.

## Repository-level vocabulary

```text
Architecture:
  DESIGNED
  NOT DESIGNED

Implementation:
  IMPLEMENTED
  PARTIAL
  PLANNED / GATED
  NOT PRESENT
  NOT APPLICABLE

Evidence:
  PASS
  FAIL
  NOT RUN
  NOT VERIFIED
  NOT APPLICABLE
```

`IMPLEMENTED` means required repository artifact exists. It is not runtime proof.

`PASS` requires executed evidence from the applicable build/test/security/restore/load/environment gate.

## Update rule

When implementation is added/removed, update this file in the same coherent PR when repository-level status changes materially.

Runtime evidence remains in owning CI/report/environment artifact. This file may summarize but cannot replace evidence.

`PRODUCTION-READINESS-CHECKLIST.md` remains the traffic gate.