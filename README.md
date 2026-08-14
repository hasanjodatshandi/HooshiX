# HooshiX

Current architecture, production profile, technology and engineering rules are documented under `docs/`.

Start with:

1. `AGENTS.md`;
2. `docs/architecture/README.md`;
3. `docs/architecture/implementation-status.md` when implementation/evidence presence matters;
4. `docs/architecture/SOURCES.md`;
5. `docs/adr/decision-register.md`.

The selected initial production topology is the explicit non-HA `production-single-server` profile in ADR-0042. ADR-0043 defines production client-address trust and the single-server WireGuard management network. `production-ha` remains the expansion profile.

OpenBao and end-user MFA security semantics are unchanged by the single-server/network decisions. Architecture documentation does not imply that the planned executable platform is implemented or production-verified; use `docs/architecture/implementation-status.md` and `docs/architecture/PRODUCTION-READINESS-CHECKLIST.md` for that distinction.
