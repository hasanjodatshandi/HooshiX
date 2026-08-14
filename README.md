# HooshiX

Current architecture, production profile, technology and engineering rules are documented under `docs/`.

Start with:

1. `AGENTS.md`;
2. `docs/architecture/README.md`;
3. `docs/architecture/SOURCES.md`;
4. `docs/adr/decision-register.md`.

The selected initial production topology is the explicit non-HA `production-single-server` profile in ADR-0042. `production-ha` remains the expansion profile. OpenBao and end-user MFA security semantics are unchanged by the single-server profile.
