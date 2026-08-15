# HooshiX

HooshiX is in active production-architecture and implementation-readiness design. This repository is the source of truth for current architecture, ADRs, engineering standards, technology pins, operational expectations, and implementation evidence status.

Start with:

1. `AGENTS.md`;
2. `docs/engineering/current-only-documentation-policy.md`;
3. `docs/engineering/repository-change-workflow.md`;
4. `docs/architecture/SOURCES.md`;
5. `docs/adr/decision-register.md`.

The repository bootstrap provides a real governance entry point:

```bash
make baseline-verify
```

It verifies repository/file-index consistency, ADR identifier/register invariants, canonical dependency-registry/Markdown operation parity, current source references, and selected guarded structure rules. `.github/workflows/repository-baseline.yml` runs the same gate on pull requests and `main`.

The first executable Java vertical slice is the internal Compromised Password service under `services/compromised-password-service/`. It implements the ADR-0040 immutable HIBP-derived SHA-1 prefix lookup boundary with its service-owned build, tests, CI, hardened Helm package, and Day-One observability source configuration. Repository source and CI evidence do **not** prove production corpus approval, deployed runtime controls, complete-stack capacity, disaster recovery, or production readiness.

The selected initial production topology is the explicit non-HA `production-single-server` profile in ADR-0042. ADR-0043 defines production client-address trust and the single-server WireGuard management path. ADR-0044 makes structured logging, Micrometer metrics, OpenTelemetry tracing, and the approved telemetry path Day-One implementation requirements. `production-ha` remains the expansion profile.

Current decisions also require the ADR-0040 offline HIBP SHA-1 compromised-password corpus release contract, ADR-0024 common-clock/cardinality/exact-IP quota hardening where applicable, ADR-0041 evidence-gated Reference Data service boundary, and greenfield Kyverno CEL policy gates.

OpenBao, end-user MFA, and Authorization decision semantics are unchanged by these implementation-readiness decisions.

Architecture documentation or a passing source CI gate does not imply that the planned production platform is implemented or production-verified. Use `docs/architecture/implementation-status.md` and `docs/architecture/PRODUCTION-READINESS-CHECKLIST.md` for that distinction.
