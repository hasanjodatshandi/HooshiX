# Implementation and Evidence Status — Current State

This file is the canonical repository-level status view for architecture, implementation presence, runtime evidence, and production readiness.

Architecture documents describe approved targets. A target path named in documentation is not proof that executable implementation exists.

## Current repository state

At this revision the repository contains architecture documentation, the repository-governance baseline, the ADR-0046 Git-native Agent Context Engine and project context metadata, and executable service implementations. Under ADR-0051, Context/Ops/Desktop MCP runtime source is independently versioned on Windows and is not part of HooshiX. The canonical application checkout is `/home/coder/workspace/Hooshix` on native WSL storage. Executable services are under:

The neutral Protobuf contract artifact is version 1.5.0. It includes versioned Protovalidate
request rules, generic fail-closed gRPC server enforcement, tested protobuf-JSON consumer examples
for every published service contract, dependency locks/checksums, and repository gates against
version, validation, example, and server-wiring drift.

```text
services/compromised-password-service/
services/notification-service/
services/identity-service/
services/authorization-service/
services/web-bff/
```

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

The Notification service repository implementation now includes the durable SubmitNotification handoff, five Flyway migrations, bounded transactional dispatch claiming with 30-second leases and SKIP LOCKED, durable pre-provider DISPATCHING identity, authenticated AES-GCM exact-content escrow read/erasure, bounded provider-attempt retry planning, stale-dispatch recovery, ambiguity-safe reconciliation and observation windows, terminal result outbox, the 750ms one-attempt Identity result callback with seven-day durable retry ownership, and low-cardinality delivery-worker metrics. Purpose-specific registration, contact-verification, and password-recovery content remains caller-selected semantic data with active English/Persian email/SMS templates. Production adapter code is present for Liara authenticated SMTP with required STARTTLS and for IPPanel Edge Webservice SMS with the official one-recipient send contract and recipient-level delivery report statuses. Protobuf-contracts 1.3.0 retains the earlier callback/password lifecycle and adds backward-compatible Profile/Contact request identity, resend, and contact-verification content. Runtime provider credentials/endpoints remain secret-file owned and the delivery runtime stays disabled by default. Local service quality gates and provider fixture tests verify repository behavior; real Liara/IPPanel credentials, provider egress, SPF/DKIM/DMARC, external provider execution, staging/production delivery, and production readiness remain NOT VERIFIED.

The Identity service repository implementation includes registration, local-password authentication, server-side Session/RefreshFamily authority, rotating digest-only refresh credentials, logout, password change/recovery/reset, Profile/Contact lifecycle, TOTP MFA/recovery codes, ADR-0024 quota controls, local ADR-0023 RSA-3072/RS256 signing machinery with key-identifier rebinding rejection, and bounded global zero-queue gRPC admission independent from the per-connection transport cap. Password creation/reset applies the ADR-0053 NFC 12–128 Unicode-code-point policy and fail-closed compromised-password screening outside database transactions. V9 replaces the earlier placeholder contact confirmation with a purpose-separated HMAC challenge, encrypted Notification outbox, ten-minute expiry, five-failure exhaustion, 60-second resend spacing, UUIDv4/HMAC idempotency, bounded ten-contact aggregate, active-session checks, atomic first-primary selection, <=5-minute recent-auth primary/removal, and last-verified/primary removal protection. V10 adds AES-256-GCM TOTP-secret persistence, HMAC-SHA-256 six-digit TOTP with a 30-second step and ±1 window, five-minute/five-proof pre-auth challenges, ten HMAC-only single-use recovery codes, timestep replay prevention, recent-auth/session rotation and revocation, and MFA-specific fail-closed semantic quotas. The current repository implementation also includes Tenant/TenantMembership/Invitation lifecycle, durable Identity-to-Authorization provisioning/removal coordination, selectable-tenant queries, explicit selection, automatic post-login selection for exactly one selectable membership or a still-valid last selection, tenant-authenticated refresh-family state, tenant-scoped audience-token issuance, a dedicated fail-closed Notification result callback listener, idempotent terminal-result persistence, and bounded Tenant/password/Profile/MFA telemetry. Phone registration remains server-gated off by default. OIDC, erasure, deployed password/profile/MFA runtime, production key rotation, production quota thresholds, real host-time synchronization integration, load/recovery, and production readiness remain NOT VERIFIED.

The implemented service security suites install digest-verified OSV-Scanner 2.4.0 and scan locked Gradle dependencies for known vulnerabilities. Because the repository baseline invokes all five reusable service security suites on schedule, the same locked-dependency advisory scans also run on the scheduled repository security cadence. All five implemented Java service suites configure immutable-digest Gitleaks 8.30.0 with mandatory positive detection controls plus redacted current-tree and full-Git-history scans for the repository. Current Identity, Notification, and Compromised Password local fixture/current-tree/full-history executions pass; protected evidence remains commit-specific. Protected PR verification passed for the authentication/session/JWT source head `159d551`, and the post-merge `Repository baseline` run `32210656621` passed the Compromised Password, Notification, and Identity security suites plus the final baseline aggregator on `main@a3766bd` on 2026-08-19. The expanded five-service protected PR baseline run `32261626399` also passed on implementation head `7de8b17` on 2026-08-19. This is repository/early dependency-advisory evidence only; it is not final-image/SBOM vulnerability or deployed-runtime evidence.

This repository evidence includes the executed local kind/staging integration lane described below. It is not proof of approved production HIBP acquisition/provenance/licensing, current corpus freshness, real complete-corpus cardinality/response measurements and reviewed production bounds, Notification production-provider integration, production staging/environment deployment, load, recovery, final-image SBOM/vulnerability correlation, artifact signing, release-evidence admission, or production readiness.

ADR-0045 defines the repository target for DevSecOps source/secret/dependency-advisory/final-artifact security: Semgrep SAST, Gitleaks current-tree/Git-history secret scanning, OSV-Scanner early declared/locked dependency advisory scanning, Syft CycloneDX SBOM, Grype final-artifact vulnerability correlation, Cosign signature/provenance/signed-SBOM attestation, and Kyverno admission. This architecture decision does not by itself prove repository implementation or production execution; the current evidence below records those states separately.

Current repository and developer-host evidence is:

- project Agent Context Engine source/contracts/tests are present; Context MCP adapter source is external under ADR-0051; CI evidence remains commit-specific;
- ADR-0047/0051 Context tunnel integration is external; current migration evidence verifies live Context authority at `/home/coder/workspace/Hooshix` through `windows-mcp-wsl-exec`;
- ADR-0048/0051 Ops source/schema/tests are external in `hasanjodatshandi/HooshiXMcpRuntime`; Context 14/14, Ops 32/32, and Desktop 60/60 runtime suites passed on current runtime main; live 13-tool discovery, 135-second persistent execution with short polling, runner-owned cancellation, ACL, health/readiness, and audit-redaction evidence passed;
- ADR-0049/0050/0051 Desktop source/schema/tests are external; the HooshiX runbook remains, external runtime tests and live `desktop.status` passed, and prior GUI/credential host evidence remains valid; remaining host negatives stay NOT VERIFIED;
- service-specific Semgrep enforcement exists for Compromised Password, Notification, Identity, Authorization, and Web BFF; local/protected evidence remains commit-specific, with prior protected Compromised Password/Notification/Identity execution passed on `main@a3766bd` and the expanded five-service protected PR baseline passed on implementation head `7de8b17` in run `32261626399`;
- OSV-Scanner locked-dependency advisory scanning exists for Compromised Password, Notification, Identity, Authorization, and Web BFF and is wired into PR/push/scheduled security verification; prior merged-main Compromised Password/Notification/Identity scans passed on `main@a3766bd`, while the expanded five-service protected PR baseline passed on implementation head `7de8b17` in run `32261626399`;
- Gitleaks 8.30.0 redacted current-tree and full-Git-history scanning plus negative/current-tree-positive/commit-then-delete history fixtures is PRESENT in all five implemented Java service security workflows; current Identity, Notification, and Compromised Password local executions passed the fixtures, current tree, and full current Git history, while protected evidence remains commit-specific;
- Syft/Grype/Cosign production release automation is IMPLEMENTED as a protected main-only exact-digest evidence workflow with checksum-pinned tools, retained SBOM/scan/database metadata, signature, SLSA provenance, signed CycloneDX attestation, and a two-hour deployed-digest rescan workflow; no real production release/deployed-digest execution is yet VERIFIED;
- production Kyverno admission generation is IMPLEMENTED in the reviewed release renderer using stable policies.kyverno.io/v1 fail-closed ValidatingPolicy/ImageValidatingPolicy controls bound to the exact release digest and signer identity; production cluster admission execution remains NOT VERIFIED.

Trivy and OWASP Dependency-Check are not selected current-baseline tools under ADR-0045. Their absence is not an implementation gap unless a later reviewed decision changes the selected control chain.

A developer-only fast application-integration infrastructure is present under `infrastructure/local/` with the WSL runtime supervisor under `scripts/local/runtime.py`. A separate local production-fidelity kind/staging lane is now implemented under root `deploy/`, the versioned platform infrastructure roots, and `scripts/platform/`. Executed local verification covers the three-node kind/Calico/Gateway API foundation, Istio Ambient STRICT mTLS/workload identity, Kyverno CEL admission, Traefik/WAF, local staging PostgreSQL/Redis, all five current application services by exact digest, and Collector/Prometheus/Loki/Tempo/Grafana/Alertmanager including telemetry privacy and backend-outage behavior. This is local integration-fidelity evidence only. Production K3s runtime deployment, CloudNativePG/Barman, Kafka, OpenBao/External Secrets, Argo CD reconciliation, host access, external host monitoring, real final-artifact release execution, capacity/DR, and production readiness remain absent or NOT VERIFIED as listed below. Repository-owned production contracts, fail-closed release/readiness verification, exact-digest GitOps rendering, final-artifact workflow automation, scheduled digest rescanning, and stable Kyverno release-policy generation are present but do not substitute for environment evidence.

Authorization and Web BFF application services are implemented as current repository slices. BFF OpenAPI 3.1 version 1.3.0 covers all 43 implemented public controller method/path mappings, including the Identity MFA lifecycle, cookie-bound MFA completion, and same-origin CSRF recovery after document reload, with schema validation, consumer examples, controller/OpenAPI parity, and generated frontend transport-type drift enforcement; executed checks remain change-specific PR/CI evidence and are not inferred by this status summary. Web BFF also provides trusted-client-address forwarding, RFC 9457 public errors, server-side encrypted MFA challenge custody, and authoritative-security Identity edges. Identity has the registration, local-password authentication/Session/RefreshFamily/JWT-signing, MFA, and Tenant/Membership/Invitation/selection slices described above. Notification includes its delivery runtime and Liara/IPPanel adapter implementation, while real production provider execution remains NOT VERIFIED. The local kind production-fidelity lane provides deployed integration evidence for the current five services and local observability stack. No production platform runtime, production provider/corpus approval, restore exercise, complete-stack load test, executed production artifact-signing release, executed production admission chain, or production traffic readiness is claimed. The repository release/admission automation is implementation evidence only until a reviewed production release and environment run it.

## Capability/service status

| Capability | Architecture | Independent implementation | Runtime evidence | Production readiness | Planned target |
| --- | --- | --- | --- | --- | --- |
| Identity Service | DESIGNED | IMPLEMENTED registration + local-password authentication/Session/RefreshFamily/JWT + password/Profile/Contact + TOTP MFA/recovery-code lifecycle + Tenant/Membership/Invitation/selection repository slices | current local revision: canonical WSL Java 25 strict Gradle + PostgreSQL/Redis Testcontainers, ten Flyway migrations through V10 MFA state/assurance constraints, encrypted TOTP secret and digest-only challenge/recovery persistence, replay/session/concurrency/quota negatives, bounded MFA telemetry/alerts/dashboard, and existing password/Profile/Contact/Tenant evidence; protected CI and deployed password/Profile/MFA production runtime remain change/environment-specific and NOT VERIFIED until executed | NOT VERIFIED | `services/identity-service` |
| Authorization Service | DESIGNED | IMPLEMENTED current repository slice | current local revision: canonical WSL Java 25 strict Gradle + PostgreSQL/Redis Testcontainers, non-owner forced-RLS/pool-reuse negatives, three Flyway migrations including forced RLS on the tenant lifecycle projection, immutable Authorization HMAC and Identity JWT key identifiers across refresh, bounded global/per-caller `CheckPermission` admission with <=25ms queue wait, Identity/resource caller binding through the Authorization waypoint, audit-failure/owner-reservation telemetry, ADR-0032 service-side burn rules, Buf compatibility, Semgrep, OSV, Gitleaks, exact Helm 4.2.4 render, Prometheus/dashboard, and digest-pinned runtime-image construction pass; the current V3/waypoint revision is redeployed in the local production-fidelity kind/staging lane with exact-digest five-service deployment, Flyway V3 plus forced tenant-projection RLS verification, waypoint positive caller binding, spoof/missing-caller RBAC denial, STRICT mTLS/workload identity, persistence isolation, edge, and observability verification passing; protected CI remains PR-head-specific evidence; production runtime remains NOT VERIFIED | NOT VERIFIED | `services/authorization-service` |
| Notification Service | DESIGNED | IMPLEMENTED delivery-runtime repository slice | current canonical WSL Java 25 strict Gradle + PostgreSQL Testcontainers, five Flyway migrations, bounded dispatch/reconciliation workers, AES-GCM delivery escrow, terminal result outbox/Identity callback, purpose-specific registration/contact/password verification templates, Liara SMTP and IPPanel Webservice adapter contract fixtures, Buf, Semgrep, OSV, Gitleaks tree/history, Helm/render, Prometheus/dashboard, runtime-image, local-runtime/platform tests, and repository baseline gates pass; the latest fast local-runtime execution used simulated providers and verified durable registration-verification acceptance; real Liara/IPPanel credentials/egress/provider execution remain NOT VERIFIED; protected CI remains PR-head-specific | NOT VERIFIED | `services/notification-service` |
| Web BFF | DESIGNED | IMPLEMENTED current repository slice including Public Registration, Profile/Contact, Password Lifecycle, TOTP MFA, full public REST contract coverage + OpenAPI | OpenAPI 3.1 version 1.3.0 covers all 43 implemented public controller method/path mappings with schema validation, consumer examples, controller/OpenAPI parity, generated frontend transport-type drift, encrypted server-side MFA pre-auth custody, one-attempt Identity MFA calls, and same-origin CSRF recovery. This is repository implementation evidence; protected/deployed evidence remains PR/environment-specific. | NOT VERIFIED | `services/web-bff` |
| Web Frontend | DESIGNED | IMPLEMENTED foundation/onboarding/profile/password/MFA repository slices | React/TypeScript build, generated OpenAPI schema, browser-to-BFF-only boundary, reload-safe in-memory CSRF rotation, TOTP setup/replacement/disable, one-time recovery-code display, MFA login/recovery proof flows, and seven Playwright journeys pass locally; secrets/challenges/recovery codes are absent from browser persistence; broader accessibility/localization and deployed journey evidence remain incomplete; protected CI remains commit-specific | NOT VERIFIED | `apps/web-frontend` |
| Compromised Password Service | DESIGNED | IMPLEMENTED | canonical WSL Java 25 strict Gradle/integration/bootJar, Buf, Semgrep, OSV, Gitleaks tree/history, Helm/render, and observability-artifact gates pass locally for the current revision; the latest local integrated execution verified the repository-built `GENERATED_TEST_FIXTURE` dataset runtime Ready simultaneously with the other four services; repository CI evidence remains commit-specific; the local production-fidelity kind staging deployment is Ready with an exact-digest-bound `GENERATED_TEST_FIXTURE`; production HIBP corpus/runtime evidence NOT VERIFIED | NOT VERIFIED | `services/compromised-password-service` |
| Reference Data capability | DESIGNED | local immutable adapter permitted when needed | NOT VERIFIED | NOT VERIFIED | owning deployable bundle/module |
| Reference Data independent service | DESIGNED / GATED | PLANNED / GATED | NOT VERIFIED | NOT VERIFIED | `services/reference-data-service` only after ADR-0041 trigger |

`IMPLEMENTED` means the repository artifacts for the implemented slice exist. It does not mean the service, production corpus, production provider integration, or release artifact has been deployed or approved.

## Platform and DevSecOps status

| Platform/control area | Architecture | Implementation | Evidence |
| --- | --- | --- | --- |
| Local integrated WSL application runtime | DESIGNED as fast application lane | IMPLEMENTED under `infrastructure/local/` + `scripts/local/runtime.py` | pinned PostgreSQL/Redis, isolated DB roles/Flyway, generated Git-ignored security/TLS material, all five current service processes and readiness checks passed together; local HTTPS bootstrap passed; not staging/production evidence |
| Local production-fidelity kind/staging lane | DESIGNED as integration-fidelity lane | IMPLEMENTED under root `deploy/`, versioned platform infrastructure roots, and `scripts/platform/` | local composite verification passes kind/Calico/Gateway API, Istio Ambient, Kyverno CEL admission, Traefik/WAF, staging PostgreSQL/Redis, exact-digest five-service deployment, and full local observability; generated secrets/dataset only; not production K3s/readiness evidence |
| Repository governance baseline | DESIGNED | IMPLEMENTED | CI evidence is commit-specific; `make baseline-verify` is the local entry point |
| Git-native Agent Context Engine | DESIGNED under ADR-0046/0051 | IMPLEMENTED project engine | bootstrap/router/checkpoint/retrieval source + deterministic tests present; MCP adapter is external; CI evidence is commit-specific |
| ChatGPT Web Context Engine tunnel bridge | DESIGNED under ADR-0047/0051 | IMPLEMENTED external Windows runtime + host integration | current migration path VERIFIED with `/home/coder/workspace/Hooshix` through `windows-mcp-wsl-exec`; prior tunnel evidence remains host-specific |
| ChatGPT Web developer-host Ops MCP | DESIGNED under ADR-0048/0051 | IMPLEMENTED external Windows runtime + host integration | runtime `main@3be8d1d` passes Context 14/14, Ops 32/32, Desktop 60/60; live Ops discovery has 13 reviewed tools; local timeout remains 300s and wrappers request 1h MCP connection TTL; a 135127ms persistent WSL job completed through short polling and job-ID-only cancellation reached `cancelled`; synchronous tunnel lifetime remains a separate shorter bound and is not extended by persistent jobs; production authority NOT APPLICABLE |
| ChatGPT Web developer-host Desktop MCP | DESIGNED under ADR-0049/0050/0051 | IMPLEMENTED external Windows runtime + host integration | independent runtime unit/security suite and live `desktop.status` passed during migration; prior UI/tunnel/credential-use evidence remains host-specific; real logoff/logon, selected negative cases, and revocation/rollback NOT VERIFIED |
| Cross-project/central agent memory service | NOT SELECTED / GATED under ADR-0046 | NOT APPLICABLE | NOT APPLICABLE until evidence trigger + new ADR |
| Compromised Password service CI/architecture/security/dataset-build gates | DESIGNED | IMPLEMENTED | CI evidence is commit-specific |
| Notification service CI/architecture/security/migration/deployment gates | DESIGNED | IMPLEMENTED | CI evidence is commit-specific |
| Identity registration/authentication CI/architecture/security/migration/quota/deployment gates | DESIGNED | IMPLEMENTED for current repository slices | protected merged-main execution passed Gitleaks, OSV, strict Gradle verification, unit/integration/architecture/SpotBugs, Buf, Semgrep, Helm render hardening, Prometheus/dashboard, runtime-image, generated-file, and final baseline gates on `main@a3766bd`; local authentication/session/JWT evidence also passed WSL Java 25 unit/contract plus executable Cucumber-JVM 7.34.6/Gherkin acceptance scenarios, PostgreSQL/Flyway + Redis Testcontainers integration, ArchUnit, SpotBugs main/test, Spotless, bootJar, Buf lint/build/breaking, pinned Semgrep, OSV-Scanner, Gitleaks current-tree, Helm enabled/disabled render hardening, Prometheus/dashboard validation, and pinned runtime-image construction; local kind/staging deployment evidence PASSED; production deployed-runtime evidence remains NOT VERIFIED |
| Semgrep source SAST/policy | DESIGNED under ADR-0039/0045 | PARTIAL | rules/workflows are present for Compromised Password, Notification, Identity, Authorization, and Web BFF; prior protected Compromised Password/Notification/Identity execution passed on `main@a3766bd`; the expanded five-service protected PR baseline passed on implementation head `7de8b17` in run `32261626399` |
| Gitleaks current-tree/Git-history secret scanning | DESIGNED under ADR-0045 | IMPLEMENTED | immutable-digest Gitleaks 8.30.0 all five implemented Java service workflows include negative/current-tree-positive/commit-then-delete fixtures and reviewed narrow false-positive policy; current Identity, Notification, and Compromised Password local fixture/current-tree/full-current-history executions passed; protected execution evidence remains commit-specific |
| OSV-Scanner declared/locked dependency advisory scan | DESIGNED under ADR-0045 | PARTIAL | OSV-Scanner 2.4.0 is wired for Compromised Password, Notification, Identity, Authorization, and Web BFF PR/push/scheduled service-security suites; prior protected Compromised Password/Notification/Identity scans passed on `main@a3766bd`; the expanded five-service protected PR baseline passed on implementation head `7de8b17` in run `32261626399` |
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
| Kafka | DESIGNED | NOT PRESENT | NOT VERIFIED |
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
