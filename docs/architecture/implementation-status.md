# Implementation and Evidence Status — Current State

This file is the canonical repository-level status view for architecture, implementation presence, runtime evidence, and production readiness.

Architecture documents describe approved targets. A target path named in documentation is not proof that executable implementation exists.

## Current repository state

At this revision the repository contains architecture documentation, the repository-governance baseline, the Git-native Agent Context Engine under `context/` + `scripts/context/`, the ADR-0047 repository-side ChatGPT Web Context tunnel integration, the ADR-0048 policy-gated developer-host Ops MCP under `ops/` + `scripts/ops/`, the ADR-0049 policy-gated interactive Desktop MCP under `desktop/` + `scripts/desktop/`, and executable service implementations under:

```text
services/compromised-password-service/
services/notification-service/
```

Implemented repository-governance artifacts are:

```text
Makefile
context/
ops/
scripts/baseline/
scripts/context/
scripts/ops/
.github/workflows/repository-baseline.yml
```

The repository baseline verifies file-index consistency, stable ADR identifiers/register coverage, dependency-registry/schema/Markdown-view consistency, current source references, selected guarded structure rules, ADR-0046 Context Engine contracts/tests, ADR-0048 Ops MCP policy/stdio/filesystem/process/audit tests, and ADR-0049 Desktop MCP policy/version/app/HWND/capture/input/audit/stdio tests. Context verification covers current bootstrap/source paths, canonical task-routing/generation parity, commit-bound checkpoint shape, tracked-file bounded retrieval/provenance, conservative full-read escalation, CWD-independent stdio MCP startup, matching textual/structured object tool results, and read-only MCP behavior. Ops verification covers fail-closed policy parsing, path/symlink boundaries, bounded UTF-8 file mutation, command alias/cwd/timeout/output bounds, child-environment credential exclusion, bounded audit metadata, CWD-independent startup, and explicit UTF-8 stdio output. Desktop verification covers strict policy/version state, app/HWND authorization, bounded UI inspection/PNG capture, transient capture cleanup, UIA/mouse/keyboard/system-key controls, child-environment credential exclusion/telemetry opt-out, metadata-only fail-closed audit, screenshot MCP image representation, and explicit UTF-8 stdio output. The protected repository workflow also invokes the reusable Compromised Password and Notification service security suites on PR/push and on the scheduled repository security cadence.

The Agent Context Engine implementation is developer/repository tooling only. It has no application runtime service, datastore, HTTP/network listener, production dependency, or cross-project memory database. V1 uses Python standard library + Git CLI, local bounded tracked-file lexical/path retrieval, commit-bound historical checkpoints, and a read-only stdio MCP adapter. Current Git authority remains above every derived context/checkpoint/model memory result.

ADR-0047 adds repository-side readiness for ChatGPT Web to reach the Context stdio MCP adapter through the external OpenAI Secure MCP Tunnel bridge. Repository evidence includes the CWD-independent MCP entry point, unchanged five-tool read-only contract, reviewed tunnel-client v0.0.11 developer pin/integrity metadata, least-privilege credential rules, threat/security governance, and Windows operator runbook. HooshiX still adds no Context HTTP/SSE/TCP MCP listener or public MCP port. Operator execution on 2026-08-18 verified the reviewed v0.0.11 Windows archive digest, restricted runtime-key path, local health/readiness, exact five-tool ChatGPT Plugin discovery, the UTF-8 stdio interoperability repair, and real merged-main `project.bootstrap`, `project.search`, and `project.context_for_task` calls on `main` commit `1a1b265769fb24a054c1ffb1c5d7479416d50a2a`. The merged-main calls returned clean trusted Git authority and completed without the prior response-boundary HTTP 400. This passes the ADR-0047 merged-main Context tunnel retest for the exercised tools; unexercised Context tools retain only repository-level evidence. On 2026-08-19 host resilience testing additionally verified automatic recovery from tunnel-client child exit, complete parent-task stop, orphan same-profile cleanup, and internal Python MCP-child failure/unready state using a supervised wrapper plus the secret-free process/health-aware watchdog. These availability tests do not override Context trust; current checkout authority still depends on `project.bootstrap`.

ADR-0048 adds a separate developer-host Ops MCP. Repository implementation includes a mandatory local-policy parser, typed bounded filesystem operations, policy-alias process execution, sanitized child environment, bounded output/time/audit behavior, explicit UTF-8 stdio framing, and deterministic tests. Operator execution on 2026-08-18 verified protected local Ops policy/key ACLs, intended elevated Windows token, separate tunnel/profile/runtime key, health/readiness, ChatGPT `ops.status`, bounded filesystem/process smoke, metadata audit, Scheduled Task highest-privilege background operation, and reboot recovery on the operator PC. Ops MCP remains developer-host only and does not change ADR-0030. On 2026-08-19 tunnel diagnostics proved that stdio MCP-child failure can make tunnel-client v0.0.11 request shutdown and exit on EOF. The host now uses bounded per-profile logs, a 15-second wrapper supervisor, a secret-free one-minute process/health-aware watchdog, and Task Scheduler Operational logging. Tunnel-child, Python MCP-child/unready, and complete parent-task recovery all restored exactly one Ops tunnel plus `live/ready` without manual restart. A 70-second low-output call and a 128 KiB non-sensitive response both passed. The operator later raised only the local Ops `max_output_bytes` policy from 1 MiB to 10 MiB (within the existing 16 MiB schema ceiling); the MCP JSON-RPC input-message bound remains separate and unchanged.

ADR-0049 adds a third developer-host Desktop MCP. Repository implementation includes strict local Desktop policy/version parsing, a pinned WinApp CLI adapter, fresh HWND/process app authorization, bounded UI inspection and transient PNG capture, UIA/mouse/keyboard/system-key controls, a fixed isolated PowerShell/C# `KEYEVENTF_UNICODE` literal-text helper, sanitized child environments, metadata-only fail-closed audit, screenshot MCP image output, explicit UTF-8 stdio framing, and deterministic tests. Operator execution on 2026-08-18 verified Microsoft WinApp CLI `0.6.0`, protected final Desktop policy/ACLs, and repeated temporary **non-elevated interactive** Notepad host smoke. Host debugging found two WinApp/input integration defects and closed both in repository code: alphanumeric modifier chords can fail WinApp `VkKeyScan()` inside Scheduled Task threads, so only already-validated chord main keys receive internal VK normalization; WinApp literal text synthesis and same-process Python `SendInput` were not fidelity-safe, so literal text now uses a fixed isolated PowerShell/C# helper with bounded UTF-8 stdin, per-code-unit foreground checks, 5 ms pacing, and 500 ms queue drain. The final production-path smoke persisted mixed-case `HooshiX`, `✓`, and Persian Unicode exactly, then passed `Ctrl+S`, window screenshot, full-screen capture, and zero remaining capture files. On 2026-08-19 the separate Desktop tunnel/profile/key, ChatGPT discovery/`desktop.status`, non-elevated persistent logon task, tunnel-child recovery, parent-task recovery, orphan cleanup, and internal MCP-child/unready recovery were verified; recovery preserved `elevated=false`, `interactive_session=true`, and `live/ready`. A later host check traced a recurring visible Windows Terminal window to direct interactive PowerShell Scheduled Task actions. Ops/Desktop were moved to secret-free `wscript.exe` GUI-subsystem launchers that wait on hidden PowerShell supervisors, while the shared one-minute watchdog was moved to `SYSTEM` / `ServiceAccount` Session 0. A full watchdog recurrence produced no `WindowsTerminal.exe`/`OpenConsole.exe` process and all three profiles remained `live/ready`. A real logoff/logon cycle and revocation/rollback remain **NOT VERIFIED**. Desktop MCP is not a credential/UAC/Secure-Desktop or production administration path.

The Compromised Password service repository implementation includes service-owned Java/Gradle source and wrapper, Protobuf/gRPC contract, immutable SQLite lookup adapter, deterministic tests, dependency locks/verification metadata, container definition, Helm/security policy package, Day-One service telemetry code, and service CI/static/architecture/deployment gates. It also includes the service-owned offline/local SHA-1 source-to-SQLite dataset builder, version-2 release-manifest schema, generated-fixture integration/CLI verification, explicit build/runtime prefix-cardinality and serialized-response compatibility bounds, exact runtime manifest SHA-256 binding to the SQLite artifact digest, raw-corpus/generated-database Git guards, privacy/architecture regression enforcement, and a runtime-JAR exclusion that keeps builder tooling out of the deployed application artifact. The builder has no URL/network/downloader path and normal PR CI uses only generated fixtures marked `GENERATED_TEST_FIXTURE`. Runtime image construction verifies the exact official Temurin 25.0.4+7 Linux/x64 archive SHA-256 before placing that JDK in the image.

The Notification service repository implementation includes the internal `SubmitNotification` Protobuf/gRPC contract, service-owned PostgreSQL persistence with Flyway and jOOQ/JDBC, durable `ACCEPTED` handoff semantics, idempotency and versioned HMAC intent fingerprinting, lifecycle/state-machine and bounded retry/reconciliation primitives, versioned database templates and bounded rendering, local AES-GCM delivery escrow and fail-closed key-ring integration, Email/SMS provider ports with local-only non-production adapters, Day-One structured logging/metrics/tracing/health source configuration, hardened container/Helm/ServiceAccount/NetworkPolicy/Istio artifacts, and service-owned formatting/static/architecture/dependency/contract/migration/deployment CI gates. Production Liara SMTP and IPPanel dispatch are not implemented by this slice. Repository source and CI evidence do not prove deployed provider behavior or production readiness.

The implemented service security suites install digest-verified OSV-Scanner 2.4.0 and scan locked Gradle dependencies for known vulnerabilities. Because the repository baseline invokes both reusable service security suites on schedule, the same locked-dependency advisory scans also run on the scheduled repository security cadence. This is early dependency-advisory evidence only; it is not final-image/SBOM vulnerability evidence.

This is repository implementation evidence only. It is not proof of approved production HIBP acquisition/provenance/licensing, current corpus freshness, real complete-corpus cardinality/response measurements and reviewed production bounds, Notification production-provider integration, staging runtime, load, recovery, final-image SBOM/vulnerability correlation, artifact signing, admission, or production readiness.

ADR-0045 defines the repository target for DevSecOps source/secret/dependency-advisory/final-artifact security: Semgrep SAST, Gitleaks current-tree/Git-history secret scanning, OSV-Scanner early declared/locked dependency advisory scanning, Syft CycloneDX SBOM, Grype final-artifact vulnerability correlation, Cosign signature/provenance/signed-SBOM attestation, and Kyverno admission. This architecture decision does **not** mean the full release chain is implemented.

Current repository evidence is:

- Agent Context Engine source/contracts/tests are present; CI evidence remains commit-specific;
- ADR-0047 ChatGPT Web Context tunnel repository integration artifacts are present; operator execution verified reviewed tunnel-client integrity/runtime/readiness, exact five-tool Plugin discovery, and real merged-main `project.bootstrap`, `project.search`, and `project.context_for_task` on `main@1a1b265`;
- ADR-0048 Ops MCP source/policy schema/tests/runbook are present; operator Windows policy/elevation/separate-tunnel/ChatGPT/background/reboot smoke passed on 2026-08-18;
- ADR-0049 Desktop MCP source/policy schema/tests/runbook are present; final protected-policy GUI smoke, separate tunnel/ChatGPT discovery/status, persistent interactive task, and tunnel/MCP/parent recovery all passed; real logoff/logon and revocation/rollback remain NOT VERIFIED;
- service-specific Semgrep enforcement exists for Compromised Password and Notification;
- OSV-Scanner locked-dependency advisory scanning exists for Compromised Password and Notification and runs in PR/push/scheduled security verification;
- Gitleaks is not present;
- Syft/Grype/Cosign release automation is not present;
- production Kyverno admission is not present.

Trivy and OWASP Dependency-Check are not selected current-baseline tools under ADR-0045. Their absence is not an implementation gap unless a later reviewed decision changes the selected control chain.

These root implementation areas are still not present:

```text
deploy/
infrastructure/
```

Identity, Authorization, and Web BFF application services remain absent. Notification is implemented as a repository vertical slice, while production Liara/IPPanel provider integration remains absent. No production platform runtime, complete observability backend, restore exercise, complete-stack load test, artifact-signing release pipeline, final-image vulnerability/admission gate, or production traffic readiness is claimed.

## Capability/service status

| Capability | Architecture | Independent implementation | Runtime evidence | Production readiness | Planned target |
| --- | --- | --- | --- | --- | --- |
| Identity Service | DESIGNED | NOT PRESENT | NOT VERIFIED | NOT VERIFIED | `services/identity-service` |
| Authorization Service | DESIGNED | NOT PRESENT | NOT VERIFIED | NOT VERIFIED | `services/authorization-service` |
| Notification Service | DESIGNED | IMPLEMENTED | repository service/persistence/contract/security/migration/deployment CI evidence is commit-specific; production Liara/IPPanel provider/runtime evidence NOT VERIFIED | NOT VERIFIED | `services/notification-service` |
| Web BFF | DESIGNED | NOT PRESENT | NOT VERIFIED | NOT VERIFIED | `services/web-bff` |
| Compromised Password Service | DESIGNED | IMPLEMENTED | repository service/builder/runtime-package/OSV dependency-advisory CI evidence is commit-specific; production HIBP corpus/runtime evidence NOT VERIFIED | NOT VERIFIED | `services/compromised-password-service` |
| Reference Data capability | DESIGNED | local immutable adapter permitted when needed | NOT VERIFIED | NOT VERIFIED | owning deployable bundle/module |
| Reference Data independent service | DESIGNED / GATED | PLANNED / GATED | NOT VERIFIED | NOT VERIFIED | `services/reference-data-service` only after ADR-0041 trigger |

`IMPLEMENTED` means the repository artifacts for the implemented slice exist. It does not mean the service, production corpus, production provider integration, or release artifact has been deployed or approved.

## Platform and DevSecOps status

| Platform/control area | Architecture | Implementation | Evidence |
| --- | --- | --- | --- |
| Repository governance baseline | DESIGNED | IMPLEMENTED | CI evidence is commit-specific; `make baseline-verify` is the local entry point |
| Git-native Agent Context Engine | DESIGNED under ADR-0046 | IMPLEMENTED | bootstrap/router/checkpoint/retrieval/MCP source + deterministic tests present; protected CI evidence is commit-specific |
| ChatGPT Web Context Engine tunnel bridge | DESIGNED under ADR-0047 | IMPLEMENTED repository integration | PARTIALLY VERIFIED externally: reviewed v0.0.11 integrity/runtime/readiness, exact five-tool Plugin discovery, and real merged-main `project.bootstrap`/`project.search`/`project.context_for_task` passed on `main@1a1b265`; other unexercised Context tools retain repository evidence only |
| ChatGPT Web developer-host Ops MCP | DESIGNED under ADR-0048 | IMPLEMENTED repository + host integration | VERIFIED operator evidence on 2026-08-18 for policy ACL/elevation/separate tunnel/readiness/ChatGPT status/bounded mutation+process/audit/background/reboot; production authority remains NOT APPLICABLE |
| ChatGPT Web developer-host Desktop MCP | DESIGNED under ADR-0049 | IMPLEMENTED repository + host integration | repository tests plus separate-tunnel/ChatGPT/non-elevated persistent-task and tunnel/MCP/parent recovery evidence passed; real logoff/logon and revocation/rollback NOT VERIFIED |
| Cross-project/central agent memory service | NOT SELECTED / GATED under ADR-0046 | NOT APPLICABLE | NOT APPLICABLE until evidence trigger + new ADR |
| Compromised Password service CI/architecture/security/dataset-build gates | DESIGNED | IMPLEMENTED | CI evidence is commit-specific |
| Notification service CI/architecture/security/migration/deployment gates | DESIGNED | IMPLEMENTED | CI evidence is commit-specific |
| Semgrep source SAST/policy | DESIGNED under ADR-0039/0045 | PARTIAL | implemented for Compromised Password and Notification; future-service coverage NOT VERIFIED |
| Gitleaks current-tree/Git-history secret scanning | DESIGNED under ADR-0045 | NOT PRESENT | NOT VERIFIED |
| OSV-Scanner declared/locked dependency advisory scan | DESIGNED under ADR-0045 | PARTIAL | OSV-Scanner 2.4.0 implemented for Compromised Password and Notification PR/push/scheduled service-security suites; future-service coverage NOT VERIFIED |
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
| Cross-service CI/security/supply-chain release gates | DESIGNED | PARTIAL | implemented-service Semgrep/OSV repository gates exist; Gitleaks/Syft/Grype/Cosign/Kyverno release evidence NOT VERIFIED |
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
- `context/bootstrap.json`, `context/routes.json`, and checkpoint contracts resolve to current tracked repository authorities;
- `docs/architecture/TASK-REVIEW-MATRIX.md` must exactly match canonical `context/routes.json` generation;
- Context Engine targeted review trust fails safe when configured authority state is dirty/invalid or routing is ambiguous;
- Context Engine retrieval remains tracked-file/local/bounded/provenance-bearing and Context MCP remains read-only/stdio-only;
- object-shaped Context MCP successes preserve matching JSON text and `structuredContent`, so the tunnel/client boundary does not depend on reparsing the only result representation;
- Context MCP startup is independent of the caller working directory and resolves the repository from the tracked MCP entry point;
- ADR-0047 tunnel integration must remain an external stdio bridge and cannot add a HooshiX network listener/write/general-shell authority;
- ADR-0048 Ops MCP must remain separate from Context MCP, require local fail-closed policy, use explicit UTF-8 stdio, bound filesystem/process/audit behavior, sanitize child credential environment, and remain developer-host only;
- ADR-0049 Desktop MCP must remain separate from Context/Ops, require strict local WinApp/session/app/HWND/capability policy, use explicit UTF-8 stdio, bound/redact transient capture/input/audit behavior, and remain developer-host only;
- the ADR-0041-gated `services/reference-data-service` path is rejected until the architecture/trigger evidence is intentionally revised;
- root `services/common` and `services/shared` dumping grounds are rejected;
- the Compromised Password Gradle wrapper must retain executable state.

Service-specific CI adds stricter checks for implemented code, OSV locked-dependency advisory scanning, migrations where applicable, offline dataset-build tooling where applicable, runtime compatibility validation, telemetry/privacy controls, contracts, and deployment/runtime-image artifacts. Repository governance does not replace runtime/staging/release evidence.

ADR-0045 documents additional target gates. Gitleaks/Syft/Grype/Cosign/Kyverno must not be reported as implemented until their repository workflows/policies exist and execute successfully. OSV-Scanner must not be reported as final-image vulnerability evidence.

## Implementation/release gates still not evidenced

Current architecture still requires evidence that this repository slice does not create by itself:

- real Windows/ChatGPT Web ADR-0049 remaining evidence for an actual logoff/logon cycle and stop/revoke/rollback behavior; protected policy/session/tunnel/`desktop.status`/GUI smoke/persistent-task/recovery evidence is already recorded above;
- blocking Gitleaks current-tree + protected Git-history scanning with redacted output and positive/negative fixtures;
- final-image Syft CycloneDX generation bound to exact image digest;
- Grype final-image/SBOM vulnerability policy, feed freshness, exception/VEX behavior, and deployed-digest rescanning;
- Cosign exact-digest signature, provenance, and signed-SBOM attestation plus Kyverno admission positives/negatives;
- approved official complete HIBP Pwned Passwords SHA-1 acquisition/provenance/tool/licensing evidence, current freshness <=35 days, and a reviewed production dataset release artifact built from that local source;
- real complete-corpus row count, maximum prefix cardinality, exact serialized-response measurements and reviewed production runtime compatibility limits with safety margin;
- representative complete-corpus disk-backed p95/p99, saturation, and profile-specific runtime/recovery evidence for Compromised Password;
- production Liara SMTP/IPPanel Notification provider implementation plus ambiguity/reconciliation, provider-failure, and deployed-runtime evidence;
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
