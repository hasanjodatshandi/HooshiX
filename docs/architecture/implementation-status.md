# Implementation and Evidence Status — Current State

This file is the canonical repository-level status view for architecture, implementation presence, runtime evidence, and production readiness.

Architecture documents describe approved targets. A target path named in documentation is not proof that executable implementation exists.

## Current repository state

At this revision the repository contains architecture documentation, the repository-governance baseline, the ADR-0046 Git-native Agent Context Engine and project context metadata, and executable service implementations. Under ADR-0051, Context/Ops/Desktop MCP runtime source is independently versioned on Windows and is not part of HooshiX. The canonical application checkout is `/home/coder/workspace/Hooshix` on native WSL storage. Executable services are under:

The repository-wide engineering audit remediation sequence is active in
`ENGINEERING-HARDENING-ROADMAP.md`. Its audit baseline is
`main@68cf66cf24c07dd6fca010ddae2789f42608aa31`. Protected `Repository baseline`
run `33105936814` passed at that exact commit, including neutral contract validation,
all five Java service security suites, and the final baseline aggregator. Protected
`Web frontend E2E` run `33105936555` also passed at that exact commit. These are
repository/CI results, not Production runtime evidence.

The neutral Protobuf contract artifact is version 1.8.0. It includes versioned Protovalidate
request rules, generic fail-closed gRPC server enforcement, tested protobuf-JSON consumer examples
for every published service contract, the typed Identity ExternalIdentity and data-subject-erasure
surfaces, versioned non-PII Kafka command/receipt events, dependency locks/checksums, and repository gates against
version, validation, example, and server-wiring drift.

```text
services/compromised-password-service/
services/notification-service/
services/identity-service/
services/authorization-service/
services/web-bff/
```

ADR-0054 and `services/conversation-service.md` now define the first core AI-product boundary:
private text Conversation plus asynchronous ModelRun through a platform-approved, stateless
`store=false` OpenAI adapter with no tools. `services/conversation-service/` is not present at this
revision; no provider credential, model execution, Conversation database, public route, or runtime
evidence is claimed.

Implemented repository-governance artifacts are:

```text
Makefile
context/
scripts/baseline/
scripts/context/context_engine.py
scripts/context/post_merge_checkpoint.py
.github/workflows/repository-baseline.yml
```

The repository baseline verifies file-index consistency, ADR/register coverage, dependency-registry/view consistency, current source references, guarded structure, ADR-0046 project Context Engine/bootstrap/routing/retrieval/checkpoint contracts, and the ADR-0051 externalized-MCP path guard. The independent Windows MCP runtime owns Context adapter, Ops, and Desktop runtime tests and is versioned at `https://github.com/hasanjodatshandi/HooshiXMcpRuntime.git`. The repository workflow still invokes all five implemented service security suites. Independent runtime `main@3be8d1d723d95691bccc978034505940d90473af` passed Context 14/14, Ops 32/32, and Desktop 60/60 unit/security tests after persistent process jobs and the job-state fail-closed follow-up merged.

The Agent Context Engine is developer/repository tooling only. HooshiX owns its Git-native engine, bounded retrieval, routing, and checkpoints. ADR-0051 places only the read-only MCP adapter in the independent Windows runtime. Linux Git in `/home/coder/workspace/Hooshix` is repository authority.

ADR-0047 defines ChatGPT Web Context access through Secure MCP Tunnel. Under ADR-0051, the independent Windows adapter invokes the project Context Engine in `/home/coder/workspace/Hooshix` through fixed WSL policy. Migration evidence on 2026-08-19 verified live `project.bootstrap`, clean Linux Git authority, and `repository_transport=windows-mcp-wsl-exec`. Earlier tunnel integrity/readiness/tool-discovery evidence remains host evidence.

ADR-0048 defines the separate developer-host Ops MCP. Under ADR-0051, its implementation, schemas, and tests are owned by the independent Windows MCP runtime. Runtime PR #1 added `process.start`, `process.status`, `process.logs`, and `process.cancel`; runtime PR #2 closed the fail-closed state-path reread found during final review. Their authoritative merge commits are `03e4516ddbbc6b671d2e29043bf40172aa1daeca` and `3be8d1d723d95691bccc978034505940d90473af`. GitHub reported no configured/reported status checks for the runtime PRs, so runtime evidence is the executed local/security/host verification, not a CI-green claim. The live elevated Ops policy retains the finite 300-second local command ceiling and the Context/Ops wrappers retain the one-hour MCP connection-TTL request. Live `ops.status` reports persistent bounds of 4 active jobs, 16 retained records, 24-hour cleanup age, 1 MiB maximum per persistent stdout/stderr stream, and 64 KiB maximum per log page. Direct live MCP discovery returned the reviewed 13-tool Ops surface; loopback `/healthz` and `/readyz` returned 200. A persistent WSL job completed after `135127 ms` with exit code 0 while observation used short polling calls, and a separate job reached terminal `cancelled` through job-ID-only cancellation. Protected Ops/job-state ACLs were inspected and no broad `Everyone` grant was observed. Earlier synchronous evidence still shows a shorter tunnel/control-plane response lifetime, including one exercised failure at about `122603 ms`; persistent-job completion therefore proves decoupled local execution, not a longer synchronous response SLA.

ADR-0049/0050 define the Desktop MCP and optional credential broker. Under ADR-0051, their implementation, helpers, schemas, and tests are owned by the independent Windows MCP runtime. Migration smoke verified the repointed live Desktop runtime and `desktop.status`. Earlier UI/tunnel/credential host evidence remains host evidence.

The Compromised Password service repository implementation includes service-owned Java/Gradle source and wrapper, Protobuf/gRPC contract, immutable SQLite lookup adapter, deterministic tests, dependency locks/verification metadata, container definition, Helm/security policy package, Day-One service telemetry code, and service CI/static/architecture/deployment gates including pinned Gitleaks current-tree/Git-history scanning with negative/current-tree-positive/commit-then-delete fixtures. It also includes the service-owned offline/local SHA-1 source-to-SQLite dataset builder, version-2 release-manifest schema, generated-fixture integration/CLI verification, explicit build/runtime prefix-cardinality and serialized-response compatibility bounds, exact runtime manifest SHA-256 binding to the SQLite artifact digest, raw-corpus/generated-database Git guards, privacy/architecture regression enforcement, and a runtime-JAR exclusion that keeps builder tooling out of the deployed application artifact. The builder has no URL/network/downloader path and normal PR CI uses only generated fixtures marked `GENERATED_TEST_FIXTURE`. Runtime image construction verifies the exact official Temurin 25.0.4+7 Linux/x64 archive SHA-256 before placing that JDK in the image.

The Notification service repository implementation now includes the durable SubmitNotification handoff, six Flyway migrations, bounded transactional dispatch claiming with 30-second leases and SKIP LOCKED, durable pre-provider DISPATCHING identity, authenticated AES-GCM exact-content escrow read/erasure, bounded provider-attempt retry planning, stale-dispatch recovery, ambiguity-safe reconciliation and observation windows, terminal result outbox, the 750ms one-attempt Identity result callback with seven-day durable retry ownership, and low-cardinality delivery-worker metrics. It is also an ADR-0028 erasure participant with validated Kafka consumption, atomic durable Inbox/idempotency, Identity-owned target paging, service-owned subject-state deletion, a non-PII receipt Outbox, finite retry/exhaustion metrics, and alerting. Purpose-specific registration, contact-verification, and password-recovery content remains caller-selected semantic data with active English/Persian email/SMS templates. Production adapter code is present for Liara authenticated SMTP with required STARTTLS and for IPPanel Edge Webservice SMS with the official one-recipient send contract and recipient-level delivery report statuses. All service consumers are locked to neutral Protobuf-contracts 1.8.0. Runtime provider credentials/endpoints remain secret-file owned and the delivery runtime stays disabled by default. Local service quality gates, provider fixture tests, and the four-participant local Kafka smoke verify repository behavior; real Liara/IPPanel credentials, provider egress, SPF/DKIM/DMARC, external provider execution, staging/production delivery, and production readiness remain NOT VERIFIED.

The Identity service repository implementation includes registration, local-password and Google-evidence primary authentication, server-side Session/RefreshFamily authority, rotating digest-only refresh credentials, ExternalIdentity establish/link/unlink/status, logout, password change/recovery/reset, Profile/Contact lifecycle, TOTP MFA/recovery codes, ADR-0024 quota controls, local ADR-0023 RSA-3072/RS256 signing machinery with key-identifier rebinding rejection, and bounded global zero-queue gRPC admission independent from the per-connection transport cap. V11 adds issuer+subject binding and ExternalIdentity; V12 completes Tenant/Invitation lifecycle. V13 implements ADR-0028 global erasure coordination: atomic authenticated acceptance and authentication shutdown, Membership/invitation preconditions, legal-hold ledger, snapshotted participant policy, command/receipt Outbox/Inbox state, 35-day evidence, finite retry/exhaustion, non-PII Kafka events, Identity-local erasure, receipt-gated completion, metrics/alerts, and restore/replay procedures. The current repository implementation also includes durable Identity-to-Authorization provisioning/removal coordination, selectable-tenant queries, explicit/automatic selection, tenant-scoped audience-token issuance, and the dedicated Notification result callback. Phone registration remains server-gated off by default. The complete five-process developer runtime and real four-participant Kafka erasure smoke pass locally. Real Google-provider execution, production Kafka/erasure deployment, production key rotation, production quota thresholds, real host-time synchronization integration, load/recovery, and production readiness remain NOT VERIFIED.

The implemented service security suites install digest-verified OSV-Scanner 2.4.0 and scan locked Gradle dependencies for known vulnerabilities. Because the repository baseline invokes all five reusable service security suites on schedule, the same locked-dependency advisory scans also run on the scheduled repository security cadence. All five implemented Java service suites configure immutable-digest Gitleaks 8.30.0 with mandatory positive detection controls plus redacted current-tree and full-Git-history scans for the repository. Current Identity, Notification, and Compromised Password local fixture/current-tree/full-history executions pass. The current protected five-service evidence is `Repository baseline` run `33105936814` on `main@68cf66cf24c07dd6fca010ddae2789f42608aa31`; every service security-suite job and the final aggregator passed on 2026-08-27. This is repository/early dependency-advisory evidence only; it is not final-image/SBOM vulnerability or deployed-runtime evidence.

This repository evidence includes the executed local kind/staging integration lane described below. It is not proof of approved production HIBP acquisition/provenance/licensing, current corpus freshness, real complete-corpus cardinality/response measurements and reviewed production bounds, Notification production-provider integration, production staging/environment deployment, load, recovery, final-image SBOM/vulnerability correlation, artifact signing, release-evidence admission, or production readiness.

ADR-0045 defines the repository target for DevSecOps source/secret/dependency-advisory/final-artifact security: Semgrep SAST, Gitleaks current-tree/Git-history secret scanning, OSV-Scanner early declared/locked dependency advisory scanning, Syft CycloneDX SBOM, Grype final-artifact vulnerability correlation, Cosign signature/provenance/signed-SBOM attestation, and Kyverno admission. This architecture decision does not by itself prove repository implementation or production execution; the current evidence below records those states separately.

Current repository and developer-host evidence is:

- project Agent Context Engine source/contracts/tests are present; Context MCP adapter source is external under ADR-0051; CI evidence remains commit-specific;
- ADR-0047/0051 Context tunnel integration is external; current migration evidence verifies live Context authority at `/home/coder/workspace/Hooshix` through `windows-mcp-wsl-exec`;
- ADR-0048/0051 Ops source/schema/tests are external in `hasanjodatshandi/HooshiXMcpRuntime`; Context 14/14, Ops 32/32, and Desktop 60/60 runtime suites passed on current runtime main; live 13-tool discovery, 135-second persistent execution with short polling, runner-owned cancellation, ACL, health/readiness, and audit-redaction evidence passed;
- ADR-0049/0050/0051 Desktop source/schema/tests are external; the HooshiX runbook remains, external runtime tests and live `desktop.status` passed, and prior GUI/credential host evidence remains valid; remaining host negatives stay NOT VERIFIED;
- service-specific Semgrep enforcement exists for Compromised Password, Notification, Identity, Authorization, and Web BFF; all five protected service jobs passed on `main@68cf66c` in run `33105936814`;
- OSV-Scanner locked-dependency advisory scanning exists for Compromised Password, Notification, Identity, Authorization, and Web BFF and is wired into PR/push/scheduled security verification; all five protected scans passed on `main@68cf66c` in run `33105936814`;
- Gitleaks 8.30.0 redacted current-tree and full-Git-history scanning plus negative/current-tree-positive/commit-then-delete history fixtures is PRESENT in all five implemented Java service security workflows; all five protected service jobs passed their configured Gitleaks steps on `main@68cf66c` in run `33105936814`;
- Syft/Grype/Cosign production release automation is IMPLEMENTED as a protected main-only exact-digest evidence workflow with checksum-pinned tools, retained SBOM/scan/database metadata, signature, SLSA provenance, signed CycloneDX attestation, and a two-hour deployed-digest rescan workflow; no real production release/deployed-digest execution is yet VERIFIED;
- production Kyverno admission generation is IMPLEMENTED in the reviewed release renderer using stable policies.kyverno.io/v1 fail-closed ValidatingPolicy/ImageValidatingPolicy controls bound to the exact release digest and signer identity; production cluster admission execution remains NOT VERIFIED.

Trivy and OWASP Dependency-Check are not selected current-baseline tools under ADR-0045. Their absence is not an implementation gap unless a later reviewed decision changes the selected control chain.

A developer-only fast application-integration infrastructure is present under `infrastructure/local/` with the WSL runtime supervisor under `scripts/local/runtime.py`. It now runs pinned PostgreSQL, Redis, and a single combined KRaft Kafka broker plus all five current services, provisions versioned erasure command/receipt/DLT topics, and exposes an executable UUID-only four-participant erasure smoke. A separate local production-fidelity kind/staging lane is implemented under root `deploy/`, the versioned platform infrastructure roots, and `scripts/platform/`; that lane's prior evidence covers the three-node kind/Calico/Gateway API foundation, Istio Ambient STRICT mTLS/workload identity, Kyverno CEL admission, Traefik/WAF, local staging PostgreSQL/Redis, five services, and the observability stack, but it has not yet deployed Kafka or this erasure revision. This is local evidence only. Production K3s runtime deployment, CloudNativePG/Barman, Kafka, OpenBao/External Secrets, Argo CD reconciliation, host access, external host monitoring, real final-artifact release execution, capacity/DR, and production readiness remain absent or NOT VERIFIED as listed below.

Authorization and Web BFF application services are implemented as current repository slices. BFF OpenAPI 3.1 version 1.6.0 covers all 58 implemented public controller method/path mappings, including self-erasure, with schema validation, consumer examples, controller/OpenAPI parity, and generated frontend transport-type drift enforcement. Web BFF also provides trusted-client-address forwarding, RFC 9457 public errors, encrypted server-side security state, semantic quota, and an ADR-0028 Kafka participant that removes indexed subject sessions and emits durable non-PII receipts. Authorization V4 removes subject-linked tenant/platform authority through its own atomic Inbox participant while preserving tenant-owned policy. Local Gradle/contract/frontend/Helm/Prometheus/baseline gates and the complete local Kafka smoke pass for this revision passed; the current protected repository baseline and frontend E2E also passed on `main@68cf66c`. Production deployment remains environment-specific and `NOT VERIFIED`. No production platform runtime, real Google/Liara/IPPanel provider execution, production provider/corpus approval, restore exercise, complete-stack load test, executed production artifact-signing release/admission chain, or production traffic readiness is claimed.

## Capability/service status

| Capability | Architecture | Independent implementation | Runtime evidence | Production readiness | Planned target |
| --- | --- | --- | --- | --- | --- |
| Identity Service | DESIGNED | IMPLEMENTED registration/authentication/ExternalIdentity/Session/JWT/password/Profile/Contact/MFA/Tenant plus ADR-0028 erasure coordinator and participant | current local revision: Java 25 strict full check, PostgreSQL/Redis integration, thirteen Flyway migrations through V13 erasure, legal hold, Outbox/Inbox/idempotency, non-PII command/receipt events, finite retry and receipt-gated completion, Helm/Prometheus, and the real four-participant local Kafka smoke pass; current protected service suite passed on `main@68cf66c`; production erasure deployment remains NOT VERIFIED | NOT VERIFIED | `services/identity-service` |
| Authorization Service | DESIGNED | IMPLEMENTED current repository slice plus ADR-0028 participant | Java 25 strict full check, PostgreSQL/Redis integration, four Flyway migrations through V4 erasure, subject-authority removal, atomic Inbox/receipt Outbox, Buf compatibility, hardened Helm/Kafka network policy and erasure alert rules pass locally; current protected service suite passed on `main@68cf66c`; prior kind/staging V3 evidence predates this revision and production runtime remains NOT VERIFIED | NOT VERIFIED | `services/authorization-service` |
| Notification Service | DESIGNED | IMPLEMENTED delivery runtime plus ADR-0028 participant | Java 25 strict full check, PostgreSQL integration, six Flyway migrations through V6 erasure, bounded delivery/reconciliation, encrypted escrow, subject-target paging/deletion, atomic Inbox/receipt Outbox, provider fixtures, Buf, Helm/Prometheus and complete local Kafka smoke pass; real provider and production erasure execution remain NOT VERIFIED | NOT VERIFIED | `services/notification-service` |
| Web BFF | DESIGNED | IMPLEMENTED public facade plus ADR-0028 session-state participant | OpenAPI 3.1 version 1.6.0 covers all 58 public operations including self-erasure; schema/examples/parity/generated-type drift, PostgreSQL V1 participant state, Redis indexed-session erasure, atomic Inbox/receipt Outbox, strict full check, Helm/Prometheus and local Kafka smoke pass; current protected service suite passed on `main@68cf66c`; deployed Production evidence remains NOT VERIFIED | NOT VERIFIED | `services/web-bff` |
| Web Frontend | DESIGNED | IMPLEMENTED foundation/onboarding/profile/password/MFA/Tenant-lifecycle/erasure repository slices | React/TypeScript typecheck/build, generated OpenAPI schema, browser-to-BFF-only boundary, explicit erasure confirmation/MFA and immediate local-state clearing, plus all eleven Playwright journeys pass locally; secrets/challenges/recovery codes remain absent from browser persistence; broader accessibility/localization and deployed journey evidence remain incomplete | NOT VERIFIED | `apps/web-frontend` |
| Conversation Service | DESIGNED under ADR-0054 | NOT PRESENT | NOT RUN; architecture and first-slice acceptance exist, but no service source/build/contracts/database/provider adapter/deployment/runtime exists | NOT VERIFIED | `services/conversation-service` |
| Compromised Password Service | DESIGNED | IMPLEMENTED | canonical WSL Java 25 strict Gradle/integration/bootJar, Buf, Semgrep, OSV, Gitleaks tree/history, Helm/render, and observability-artifact gates pass locally for the current revision; the latest local integrated execution verified the repository-built `GENERATED_TEST_FIXTURE` dataset runtime Ready simultaneously with the other four services; repository CI evidence remains commit-specific; the local production-fidelity kind staging deployment is Ready with an exact-digest-bound `GENERATED_TEST_FIXTURE`; production HIBP corpus/runtime evidence NOT VERIFIED | NOT VERIFIED | `services/compromised-password-service` |
| Reference Data capability | DESIGNED | local immutable adapter permitted when needed | NOT VERIFIED | NOT VERIFIED | owning deployable bundle/module |
| Reference Data independent service | DESIGNED / GATED | PLANNED / GATED | NOT VERIFIED | NOT VERIFIED | `services/reference-data-service` only after ADR-0041 trigger |

`IMPLEMENTED` means the repository artifacts for the implemented slice exist. It does not mean the service, production corpus, production provider integration, or release artifact has been deployed or approved.

## Platform and DevSecOps status

| Platform/control area | Architecture | Implementation | Evidence |
| --- | --- | --- | --- |
| Local integrated WSL application runtime | DESIGNED as fast application lane | IMPLEMENTED under `infrastructure/local/` + `scripts/local/runtime.py` | pinned PostgreSQL/Redis/Kafka, isolated DB roles/Flyway, versioned erasure topics/DLT, generated Git-ignored security/TLS material, all five service readiness/gRPC checks, local HTTPS bootstrap/unauthenticated-negative route, and executable four-participant erasure completion smoke passed; not staging/production evidence |
| Local production-fidelity kind/staging lane | DESIGNED as integration-fidelity lane | IMPLEMENTED under root `deploy/`, versioned platform infrastructure roots, and `scripts/platform/` | local composite verification passes kind/Calico/Gateway API, Istio Ambient, Kyverno CEL admission, Traefik/WAF, staging PostgreSQL/Redis, exact-digest five-service deployment, and full local observability; generated secrets/dataset only; not production K3s/readiness evidence |
| Repository governance baseline | DESIGNED | IMPLEMENTED | CI evidence is commit-specific; `make baseline-verify` is the local entry point |
| Git-native Agent Context Engine | DESIGNED under ADR-0046/0051 | IMPLEMENTED project engine | bootstrap/router/checkpoint/retrieval source + deterministic tests present; MCP adapter is external; CI evidence is commit-specific |
| ChatGPT Web Context Engine tunnel bridge | DESIGNED under ADR-0047/0051 | IMPLEMENTED external Windows runtime + host integration | current migration path VERIFIED with `/home/coder/workspace/Hooshix` through `windows-mcp-wsl-exec`; prior tunnel evidence remains host-specific |
| ChatGPT Web developer-host Ops MCP | DESIGNED under ADR-0048/0051 | IMPLEMENTED external Windows runtime + host integration | runtime `main@3be8d1d` passes Context 14/14, Ops 32/32, Desktop 60/60; live Ops discovery has 13 reviewed tools; local timeout remains 300s and wrappers request 1h MCP connection TTL; a 135127ms persistent WSL job completed through short polling and job-ID-only cancellation reached `cancelled`; synchronous tunnel lifetime remains a separate shorter bound and is not extended by persistent jobs; production authority NOT APPLICABLE |
| ChatGPT Web developer-host Desktop MCP | DESIGNED under ADR-0049/0050/0051 | IMPLEMENTED external Windows runtime + host integration | independent runtime unit/security suite and live `desktop.status` passed during migration; prior UI/tunnel/credential-use evidence remains host-specific; real logoff/logon, selected negative cases, and revocation/rollback NOT VERIFIED |
| Cross-project/central agent memory service | NOT SELECTED / GATED under ADR-0046 | NOT APPLICABLE | NOT APPLICABLE until evidence trigger + new ADR |
| Compromised Password service CI/architecture/security/dataset-build gates | DESIGNED | IMPLEMENTED | CI evidence is commit-specific |
| Notification service CI/architecture/security/migration/deployment gates | DESIGNED | IMPLEMENTED | CI evidence is commit-specific |
| Identity registration/authentication CI/architecture/security/migration/quota/deployment gates | DESIGNED | IMPLEMENTED for current repository slices | protected merged-main execution passed Gitleaks, OSV, strict Gradle verification, unit/integration/architecture/SpotBugs, Buf, Semgrep, Helm render hardening, Prometheus/dashboard, runtime-image, generated-file, and final baseline gates on `main@68cf66c` in run `33105936814`; local authentication/session/JWT and kind/staging evidence also passed; production deployed-runtime evidence remains NOT VERIFIED |
| Semgrep source SAST/policy | DESIGNED under ADR-0039/0045 | PARTIAL | rules/workflows are present for Compromised Password, Notification, Identity, Authorization, and Web BFF; all five protected service suites passed their Semgrep steps on `main@68cf66c` in run `33105936814`; frontend-specific JS/TS SAST remains absent and is tracked in the hardening roadmap |
| Gitleaks current-tree/Git-history secret scanning | DESIGNED under ADR-0045 | IMPLEMENTED | immutable-digest Gitleaks 8.30.0 all five implemented Java service workflows include negative/current-tree-positive/commit-then-delete fixtures and reviewed narrow false-positive policy; all five protected service jobs passed their configured Gitleaks steps on `main@68cf66c` in run `33105936814` |
| OSV-Scanner declared/locked dependency advisory scan | DESIGNED under ADR-0045 | PARTIAL | OSV-Scanner 2.4.0 is wired for Compromised Password, Notification, Identity, Authorization, and Web BFF PR/push/scheduled service-security suites; all five protected service scans passed on `main@68cf66c` in run `33105936814`; the frontend lockfile advisory gate remains absent and is tracked in the hardening roadmap |
| Syft final-image CycloneDX SBOM generation | DESIGNED under ADR-0035/0045 | IMPLEMENTED in protected production release workflow | repository unit/static verification PASSED; real production image execution NOT VERIFIED |
| Grype final-image/SBOM vulnerability correlation | DESIGNED under ADR-0035/0038/0045 | IMPLEMENTED in protected release + two-hour deployed-digest rescan workflows with retained DB/scan evidence | repository unit/static verification PASSED; production registry/feed/deployed-digest execution NOT VERIFIED |
| Cosign image signature/provenance/signed-SBOM release automation | DESIGNED under ADR-0017/0045 | IMPLEMENTED with exact protected main workflow identity and GitHub OIDC issuer | repository unit/static verification PASSED; real production signing/verification execution NOT VERIFIED |
| Kyverno production release admission | DESIGNED under ADR-0017/0045 | IMPLEMENTED as stable CEL release-policy generation | repository render/static verification PASSED; production cluster enforcement NOT VERIFIED |
| Trivy / OWASP Dependency-Check | NOT SELECTED under ADR-0045 | NOT APPLICABLE | NOT APPLICABLE |
| Production K3s/Kubernetes/Calico | DESIGNED | NOT PRESENT | local kind/Kubernetes/Calico integration lane IMPLEMENTED and locally VERIFIED; selected production K3s runtime NOT VERIFIED |
| Istio Ambient runtime | DESIGNED | LOCAL IMPLEMENTED; production deployment NOT VERIFIED | local 1.30.3 foundation plus STRICT mTLS/workload-identity positive/negative verification PASSED; production runtime NOT VERIFIED |
| Kyverno CEL policy/admission set | DESIGNED | LOCAL IMPLEMENTED; production deployment NOT VERIFIED | local 1.18.2 stable CEL digest/workload hardening positives/negatives PASSED, including exact Collector hostPath denial; release signature/provenance/SBOM admission and production runtime NOT VERIFIED |
| Traefik + Caddy/Coraza edge | DESIGNED | LOCAL IMPLEMENTED; production deployment NOT VERIFIED | local exact-pinned route, direct-bypass denial, workload identity, WAF, and secret-canary verification PASSED; upstream production L4/DDoS/client-address environment evidence NOT VERIFIED |
| WireGuard management overlay | DESIGNED | NOT PRESENT | NOT VERIFIED |
| CloudNativePG/PostgreSQL | DESIGNED | local staging PostgreSQL IMPLEMENTED; production CloudNativePG/Barman NOT PRESENT | local PostgreSQL 18.4 role/database isolation and Flyway evidence PASSED; production CNPG/PITR/restore NOT VERIFIED |
| Security Redis | DESIGNED | local staging Redis IMPLEMENTED; production deployment NOT VERIFIED | local Redis 8.2.8 `noeviction`/AOF policy and application integration PASSED; production TLS/ACL/recovery/capacity evidence NOT VERIFIED |
| Kafka | DESIGNED | developer-only local integrated runtime IMPLEMENTED; kind/staging and production deployment NOT PRESENT | pinned local KRaft broker, explicit command/receipt/DLT topics, host/internal listeners and real four-participant erasure replay passed; production durability/ACL/capacity/recovery NOT VERIFIED |
| OpenBao + External Secrets | DESIGNED | NOT PRESENT | NOT VERIFIED |
| GitOps/Argo CD | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Cross-service CI/security/supply-chain release gates | DESIGNED | PARTIAL | reusable Semgrep/OSV/Gitleaks-capable workflows are present for the implemented Java services; repository Syft/Grype/Cosign exact-digest release automation, two-hour deployed-digest rescanning, and stable Kyverno release-admission generation are IMPLEMENTED; real production release/rescan/admission execution remains NOT VERIFIED |
| OpenTelemetry Collector | DESIGNED under ADR-0044 | LOCAL IMPLEMENTED; production deployment NOT VERIFIED | three-node local Collector DaemonSet, bounded queues/privacy config, exact read-only pod-log hostPath, metrics, and OTLP integration PASSED |
| Prometheus/Alertmanager/Grafana | DESIGNED | LOCAL IMPLEMENTED; production deployment NOT VERIFIED | local exact-pinned deployments, five-service/Collector target health, Grafana datasource, and no-plugin-update hardening PASSED |
| Loki log backend | DESIGNED under ADR-0044 | LOCAL IMPLEMENTED; production deployment NOT VERIFIED | Collector -> Loki safe-log canary, privacy-filter negative, and backend-outage non-authority behavior PASSED |
| Tempo trace backend | DESIGNED under ADR-0044 | LOCAL IMPLEMENTED; production deployment NOT VERIFIED | Collector -> Tempo OTLP trace canary and backend-outage non-authority behavior PASSED |
| External host-down monitoring | REQUIRED / PROVIDER TBD | NOT PRESENT | NOT VERIFIED |
| Authoritative privileged/security audit | DESIGNED | NOT PRESENT | NOT VERIFIED |
| Backup/PITR/cold-DR automation | DESIGNED | NOT PRESENT | NOT VERIFIED |

## Repository governance now enforced

The bootstrap baseline makes these current repository invariants executable:

- `FILE_INDEX.txt` must exactly match the clean repository file set and remain sorted;
- the automation-safe final-report contract retains the exact `Outcome`, `Remaining work`, `Continuation action`, `Retryable`, and `Human action required` keys and canonical token sets used by local task-supervision automation;
- ADR file identifiers, headings, and the Decision Register must remain consistent and non-reused;
- dependency-registry version/classes/required edge fields/policy references must match the current schema constraints enforced by the bootstrap verifier;
- the dependency Markdown operation list must match canonical YAML exactly and in canonical order;
- current architecture source references checked by the baseline must resolve to repository files;
- `context/bootstrap.json`, `context/routes.json`, and checkpoint contracts resolve to current tracked repository authorities;
- `docs/architecture/TASK-REVIEW-MATRIX.md` must exactly match canonical `context/routes.json` generation;
- Context Engine targeted review trust fails safe when configured authority state is dirty/invalid or routing is ambiguous;
- Context Engine retrieval remains tracked-file/local/bounded/provenance-bearing and Context MCP remains read-only/stdio-only;
- object-shaped Context MCP successes preserve matching JSON text and `structuredContent`, so the tunnel/client boundary does not depend on reparsing the only result representation;
- Context MCP startup is independent of caller working directory; protected local policy fixes the WSL repository and project Context Engine path; Linux Git inside WSL is authority;
- ADR-0047 tunnel integration must remain an external stdio bridge and cannot add a HooshiX network listener/write/general-shell authority;
- ADR-0048 Ops MCP must remain separate from Context MCP, require local fail-closed policy, use explicit UTF-8 stdio, bound filesystem/process/audit behavior, sanitize child credential environment, and remain developer-host only;
- ADR-0049/0050 Desktop MCP must remain separate from Context/Ops, require strict local WinApp/session/app/HWND/capability policy, keep general text non-secret, allow credential use only through explicit bounded local app/executable-path/SHA-256/password-target bindings with no credential value in MCP/Python/audit/argv/environment, use explicit UTF-8 stdio, bound/redact transient capture/input/audit behavior, and remain developer-host only;
- the ADR-0041-gated `services/reference-data-service` path is rejected until the architecture/trigger evidence is intentionally revised;
- root `services/common` and `services/shared` dumping grounds are rejected;
- the Compromised Password Gradle wrapper must retain executable state.

Service-specific CI adds stricter checks for implemented code, OSV locked-dependency advisory scanning, migrations where applicable, offline dataset-build tooling where applicable, runtime compatibility validation, telemetry/privacy controls, contracts, and deployment/runtime-image artifacts. Repository governance does not replace runtime/staging/release evidence.

ADR-0045 documents the selected control chain. All five implemented Java service Gitleaks workflows are implemented, and repository Syft/Grype/Cosign release workflows plus stable Kyverno release-policy generation now exist and pass repository verification. These repository controls must not be reported as real production execution/enforcement until the production registry, signer, vulnerability feed, deployed digests, and production admission controller produce the required evidence. OSV-Scanner must not be reported as final-image vulnerability evidence.

## Implementation/release gates still not evidenced

Current architecture still requires evidence that this repository slice does not create by itself:

- real Windows/ChatGPT Web ADR-0049 remaining evidence for an actual logoff/logon cycle and stop/revoke/rollback behavior; protected policy/session/tunnel/`desktop.status`/GUI smoke/persistent-task/recovery evidence is already recorded above;
- ADR-0050 remaining host evidence for provisioned wrong-window/focus/ambiguous-password negatives and rollback/rotation behavior; EOrgsetad process-image/SHA-256, legacy native-password-control diagnosis, Medium-integrity `asInvoker` credential injection, and application login are verified for the exercised host/session, while the initial inherited High-integrity launch remains correctly incompatible with lower-integrity Desktop `SendInput`;
- real production final-image Syft CycloneDX generation bound to the exact deployed/released image digest;
- real production Grype final-image/SBOM vulnerability execution, feed freshness, exception/VEX behavior, and deployed-digest rescanning;
- real production Cosign exact-digest signature/provenance/signed-SBOM execution plus production Kyverno admission enforcement positives/negatives;
- approved official complete HIBP Pwned Passwords SHA-1 acquisition/provenance/tool/licensing evidence, current freshness <=35 days, and a reviewed production dataset release artifact built from that local source;
- real complete-corpus row count, maximum prefix cardinality, exact serialized-response measurements and reviewed production runtime compatibility limits with safety margin;
- representative complete-corpus disk-backed p95/p99, saturation, and profile-specific runtime/recovery evidence for Compromised Password;
- real Liara SMTP/IPPanel credentials, provider egress/execution, provider-side ambiguity/reconciliation evidence, and deployed staging/production runtime evidence;
- production-environment Collector/Loki/Tempo/Prometheus deployment, external host-loss detection, production capacity/storage/retention evidence, and authoritative off-host audit; the local kind/staging Collector/Loki/Tempo/Prometheus canary/privacy/backend-fault integration has PASSED;
- signed final image/dataset release artifacts as applicable, CycloneDX SBOM, final-artifact vulnerability correlation, provenance, admission validation, and staging-to-production digest promotion;
- production K3s/Calico/Istio/Kyverno/OpenBao/edge/observability deployment and complete-stack capacity evidence; the repository local kind/Calico/Istio/Kyverno/edge/observability integration lane has PASSED;
- deployed Identity ADR-0024 Redis quota evidence, measured production capacity/allocation thresholds, real host-time synchronization integration, NAT/IPv6/collateral tests, and complete-stack cardinality/load/failure evidence;
- Reference Data deployable trigger evidence before any independent Reference Data service creation.
- executable ADR-0054 Conversation vertical-slice evidence: service/database/contracts/BFF/frontend,
  fixed-egress provider adapter and credential/data-control approval, cost/quota reconciliation,
  tenant lifecycle/erasure, privacy/failure/load evidence, and deployed runtime.

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
