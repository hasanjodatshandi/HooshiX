# Architecture Documentation

This directory contains the **current-state implementation-facing architecture** of HooshiX.

## How to read

Start with:

1. `../../AGENTS.md`;
2. `../engineering/current-only-documentation-policy.md`;
3. `../engineering/repository-change-workflow.md`;
4. `SOURCES.md`;
5. `../adr/decision-register.md`;
6. `APPLICATION-IMPLEMENTATION-ROADMAP.md` for current application continuation/next-step sequencing;
7. `ENGINEERING-HARDENING-ROADMAP.md` while the ordered audit-remediation track is active;
8. `PRODUCTION-DECISION-SUMMARY.md`;
9. applicable service/platform/security/data/reliability documents;
10. `PRODUCTION-READINESS-CHECKLIST.md` for executable evidence.

ADR-0046 may compile this reading scope through the verified Git-native Context Engine. A verified targeted route does not change document authority or bypass a real `full-read` trigger. `context/routes.json` is canonical for machine-readable task routing; `TASK-REVIEW-MATRIX.md` is its generated human view.

ADR IDs are stable after merge. Current-state documents remain current-only; a superseded ADR retained for provenance is not current implementation authority.

## Current production profile

Initial profile is `production-single-server` under ADR-0042. `production-ha` remains the expansion profile.

Single-server intentionally accepts one-host availability/failure-domain risk. It does not weaken MFA, Authorization, tenant RLS, OpenBao, WAF/client trust, admission/supply-chain, required audit, backup/PITR, or fail-closed security dependencies.

## Current implementation-readiness decisions

Executable vertical slices must preserve these current architecture requirements:

- ADR-0040: official offline HIBP Pwned Passwords SHA-1 corpus with provenance/freshness/full-corpus bounds; SHA-1 screening-only, Argon2id storage unchanged;
- ADR-0024: exact-IP hard quota identity, aggregate-prefix pressure, common-mode wall-clock guard, and high-cardinality Redis allocation protection;
- ADR-0041: Reference Data remains local immutable capability until an independent-service trigger is evidenced;
- ADR-0044: structured logging, Micrometer metrics, OpenTelemetry tracing, Collector/Loki/Tempo/Prometheus/Grafana/Alertmanager integration, and external host-down detection from Day-1;
- ADR-0045: Gitleaks secret scanning + Semgrep source SAST + Gradle integrity + OSV-Scanner early dependency advisory + Syft/Grype/Cosign/Kyverno final-artifact security chain, with distinct tool responsibilities and no duplicate scanner by default;
- ADR-0046: Git-native verified agent bootstrap, conservative task routing, commit-bound historical checkpoints, bounded local retrieval, and read-only stdio MCP; no central cross-project memory service without a later evidence trigger;
- ADR-0047: separate approved Secure MCP Tunnel bridge for ChatGPT Web access to the unchanged read-only Context MCP;
- ADR-0048: separate policy-gated developer-host Ops MCP for explicit local mutation/execution, including bounded persistent process jobs for work that can exceed one synchronous tunnel response; no production administration authority and no change to Context MCP;
- ADR-0051: WSL-native HooshiX application workspace at `/home/coder/workspace/Hooshix`; Context/Ops/Desktop MCP runtime source is independently versioned on Windows and stays outside the application repository;
- ADR-0017/build gates: Kyverno new production policies use stable CEL-based `policies.kyverno.io/v1` APIs;
- stable merged ADR identifiers and coherent-change PR governance.

ADR-0046/0047/0048 are repository/developer-host tooling only. They do not change application services, production topology, data ownership, production security authority, or runtime availability. ADR-0048 local administrator mode is not ADR-0030 production JIT authority.

These are target decisions. `implementation-status.md` remains authoritative for whether code/deployment/CI/runtime evidence exists.

## Document map

- `platform-architecture.md` — platform/service topology.
- `network-architecture.md` — public/management/workload trust paths.
- `security-architecture.md` / `threat-model.md` — security objectives, boundaries, threats.
- `devsecops-security-toolchain.md` — selected source/secret/dependency-advisory/SBOM/final-artifact-vulnerability/signing/admission control chain.
- `data-and-messaging.md` — data ownership/PostgreSQL/Redis/Kafka/reference datasets.
- `reliability-and-observability.md` — reliability and Day-One telemetry runtime.
- `performance-and-bottlenecks.md` — capacity/saturation/evidence triggers.
- `runtime-and-deployment.md` — runtime/GitOps/profile behavior.
- `testing-and-quality-gates.md` — executable verification portfolio.
- `security-verification-matrix.md` / `architecture-fitness-functions.md` — traceable security/architecture properties.
- `PRODUCTION-READINESS-CHECKLIST.md` — production traffic gate.
- `implementation-status.md` — actual repository implementation/evidence presence.
- `APPLICATION-IMPLEMENTATION-ROADMAP.md` — current application milestone order and cross-chat continuation authority after Git reconciliation.
- `ENGINEERING-HARDENING-ROADMAP.md` — repository-wide audit finding register, ordered remediation stages, completion gates, and interruption-safe continuation state.
- `../engineering/agent-context-engine.md` — developer/agent operating interface for ADR-0046.
- `../runbooks/chatgpt-web-ops-mcp.md` — operator setup for the separate ADR-0048 developer-host Ops MCP.
- `services/` — implementation-facing service contracts.

## Authority rule

Do not create a second normative copy merely for convenience. ADR/current architecture/standards have the precedence defined in Documentation Standards. Lower-level documents may add implementation context but cannot weaken higher-level decisions.

Derived context/search/checkpoints/model memory are never a second authority. Current Git authority wins.

A path or component name in documentation is not proof it exists. Only actual repository/runtime evidence may be reported as implemented/passed.
