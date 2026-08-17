# HooshiX

HooshiX is in active production-architecture and implementation-readiness design. This repository is the source of truth for current architecture, ADRs, engineering standards, technology pins, operational expectations, and implementation evidence status.

Start with:

1. `AGENTS.md`;
2. `docs/engineering/current-only-documentation-policy.md`;
3. `docs/engineering/repository-change-workflow.md`;
4. `docs/architecture/SOURCES.md`;
5. `docs/adr/decision-register.md`.

The repository governance entry point is:

```bash
make baseline-verify
```

It verifies repository/file-index consistency, ADR identifier/register invariants, canonical dependency-registry/Markdown operation parity, current source references, selected guarded structure rules, and the ADR-0046 Agent Context Engine contracts/tests. `.github/workflows/repository-baseline.yml` runs the same gate on pull requests and `main`.

For a new AI-agent/session, the Git-native Context Engine provides a verified current-project bootstrap and conservative task routing without making chat/model memory authoritative:

```bash
make context-verify
make context-bootstrap
python3 scripts/context/context_engine.py route --task '<task>'
```

`context/routes.json` is the canonical task router, `docs/architecture/TASK-REVIEW-MATRIX.md` is its generated human view, and `context/checkpoints/` contains commit-bound historical work receipts. The local MCP adapter is read-only/stdio-only:

```bash
python3 scripts/context/mcp_server.py
```

Current Git authority always outranks derived context/checkpoints/model memory. A central cross-project memory service is not selected in v1.

The first executable Java vertical slice is the internal Compromised Password service under `services/compromised-password-service/`. It implements the ADR-0040 immutable HIBP-derived SHA-1 prefix lookup boundary with its service-owned build, tests, CI, hardened Helm package, and Day-One observability source configuration. Repository source and CI evidence do **not** prove production corpus approval, deployed runtime controls, complete-stack capacity, disaster recovery, or production readiness.

The selected initial production topology is the explicit non-HA `production-single-server` profile in ADR-0042. ADR-0043 defines production client-address trust and the single-server WireGuard management path. ADR-0044 makes structured logging, Micrometer metrics, OpenTelemetry tracing, and the approved telemetry path Day-One implementation requirements. `production-ha` remains the expansion profile.

Current decisions also require the ADR-0040 offline HIBP SHA-1 compromised-password corpus release contract, ADR-0024 common-clock/cardinality/exact-IP quota hardening where applicable, ADR-0041 evidence-gated Reference Data service boundary, ADR-0045 DevSecOps responsibility map, ADR-0046 Git-native Agent Context Engine, and greenfield Kyverno CEL policy gates.

OpenBao, end-user MFA, Authorization decision semantics, and production runtime boundaries are unchanged by the Agent Context Engine.

Architecture documentation or a passing source CI gate does not imply that the planned production platform is implemented or production-verified. Use `docs/architecture/implementation-status.md` and `docs/architecture/PRODUCTION-READINESS-CHECKLIST.md` for that distinction.
