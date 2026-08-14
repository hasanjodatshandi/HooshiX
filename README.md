# HooshiX

Current architecture, production profile, technology, security, observability, and engineering rules are documented under `docs/`.

Start with:

1. `AGENTS.md`;
2. `docs/architecture/README.md`;
3. `docs/architecture/implementation-status.md` when implementation/evidence presence matters;
4. `docs/architecture/SOURCES.md`;
5. `docs/adr/decision-register.md`.

The selected initial production topology is the explicit non-HA `production-single-server` profile in ADR-0042. ADR-0043 defines production client-address trust and the single-server WireGuard management path. ADR-0044 makes structured logging, Micrometer metrics, OpenTelemetry tracing, and the approved telemetry path Day-One implementation requirements. `production-ha` remains the expansion profile.

Before the first executable vertical slice, current decisions also require the ADR-0040 offline HIBP SHA-1 compromised-password corpus contract, ADR-0024 common-clock/cardinality/exact-IP quota hardening, ADR-0041 evidence-gated Reference Data service boundary, and greenfield Kyverno CEL policy gates.

OpenBao, end-user MFA, and Authorization decision semantics are unchanged by these implementation-readiness decisions.

Architecture documentation does not imply that the planned executable platform is implemented or production-verified. Use `docs/architecture/implementation-status.md` and `docs/architecture/PRODUCTION-READINESS-CHECKLIST.md` for that distinction.