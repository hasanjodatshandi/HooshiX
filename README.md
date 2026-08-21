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

It verifies repository/file-index consistency, ADR identifier/register invariants, canonical dependency-registry/Markdown operation parity, current source references, guarded structure rules, and the ADR-0046 project Context Engine/checkpoint contracts. It also rejects reintroduction of the Windows MCP runtime paths externalized by ADR-0051. `.github/workflows/repository-baseline.yml` runs this repository gate and the implemented service security suites.

The current executable application slices can be run together in the canonical WSL checkout with the repository-owned local integration runtime:

```bash
make local-runtime-up
make local-runtime-status
```

This starts pinned local PostgreSQL/Redis, runs service-owned Flyway migrations with separate roles, creates Git-ignored local-only key/TLS material, and runs all five current services together. The local Web BFF endpoint is `https://localhost:18443`. This is application-integration evidence only; it does not replace the production-fidelity kind/mesh/edge lane or staging/production verification. See `docs/runbooks/local-integrated-runtime.md`.

The repository also implements a separate production-fidelity local kind/staging lane for Kubernetes, Calico, Istio Ambient, Kyverno CEL admission, Traefik/WAF, local staging PostgreSQL/Redis, all five current services, and the approved observability stack:

```bash
make production-fidelity-up
make production-fidelity-verify
```

This lane is local integration-fidelity evidence, not the production K3s deployment and not production readiness. See `docs/runbooks/local-production-fidelity-staging.md`.

For a new AI-agent/session, the Git-native Context Engine provides a verified current-project bootstrap and conservative task routing without making chat/model memory authoritative:

```bash
make context-verify
make context-bootstrap
python3 scripts/context/context_engine.py route --task '<task>'
```

`context/routes.json` is the canonical task router, `docs/architecture/TASK-REVIEW-MATRIX.md` is its generated human view, and `context/checkpoints/` contains commit-bound historical work receipts. The project Context Engine remains in this repository. ADR-0051 places the Context MCP adapter and the separate Ops/Desktop MCP runtimes in the independent Windows developer runtime. The adapters use the canonical WSL checkout at `/home/coder/workspace/Hooshix`; application Git/build/test/runtime work stays inside WSL.

```bash
cd /home/coder/workspace/Hooshix
make context-verify
make context-bootstrap
```

Context stays read-only. Ops remains policy-gated Windows host authority and uses the reviewed WSL bridge for HooshiX project commands. Desktop remains a separate interactive Windows authority. None is a production administration path.

The real Ops policy and tunnel credential stay outside Git. Repository work performed through Ops still follows the branch/PR/protected-CI workflow.

Current Git authority always outranks derived context/checkpoints/model memory. A central cross-project memory service is not selected in v1.

The first executable Java vertical slice is the internal Compromised Password service under `services/compromised-password-service/`. It implements the ADR-0040 immutable HIBP-derived SHA-1 prefix lookup boundary with its service-owned build, tests, CI, hardened Helm package, and Day-One observability source configuration. Repository source and CI evidence do **not** prove production corpus approval, deployed runtime controls, complete-stack capacity, disaster recovery, or production readiness.

An executable Notification vertical slice is also present under `services/notification-service/`. It includes the internal `SubmitNotification` contract, service-owned PostgreSQL/Flyway persistence, durable acceptance/idempotency primitives, versioned templates, local encrypted delivery material handling, Day-One observability, hardened deployment artifacts, and service-owned CI/security gates. Production Liara/IPPanel provider integration, deployed runtime evidence, and production readiness remain **NOT VERIFIED**.

Executable Identity repository slices are present under `services/identity-service/`. In addition to registration and local-password Session/RefreshFamily/JWT machinery, the current source implements Tenant/TenantMembership/Invitation lifecycle, durable Authorization provisioning/removal coordination, active-tenant selection, post-login single-membership/last-selected resolution, tenant-authenticated refresh-family state, and tenant-scoped audience-token issuance. MFA/OIDC/password-change/recovery/erasure, deployed runtime evidence, production key/threshold/time-source evidence, and production readiness remain **NOT VERIFIED**.

The selected initial production topology is the explicit non-HA `production-single-server` profile in ADR-0042. ADR-0043 defines production client-address trust and the single-server WireGuard management path. ADR-0044 makes structured logging, Micrometer metrics, OpenTelemetry tracing, and the approved telemetry path Day-One implementation requirements. `production-ha` remains the expansion profile.

Current decisions also require the ADR-0040 offline HIBP SHA-1 compromised-password corpus release contract, ADR-0024 common-clock/cardinality/exact-IP quota hardening where applicable, ADR-0041 evidence-gated Reference Data service boundary, ADR-0045 DevSecOps responsibility map, ADR-0046 Git-native Agent Context Engine, and greenfield Kyverno CEL policy gates.

OpenBao, end-user MFA, Authorization decision semantics, and production runtime boundaries are unchanged by the Agent Context Engine.

Architecture documentation or a passing source CI gate does not imply that the planned production platform is implemented or production-verified. Use `docs/architecture/implementation-status.md` and `docs/architecture/PRODUCTION-READINESS-CHECKLIST.md` for that distinction.
