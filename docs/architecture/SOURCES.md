# Architecture Sources — Current State

- **Mode:** current implementation guidance + stable ADR identifiers
- **Policy:** `../engineering/current-only-documentation-policy.md`
- **Decision index:** `../adr/decision-register.md`
- **Selected production profile:** `production-single-server` under ADR-0042

This file is a routing/source index. It does not duplicate normative architecture.

## Repository authorities

| Topic | Primary current source |
| --- | --- |
| Current ADRs/stable IDs | `../adr/decision-register.md` |
| Agent Context Engine/bootstrap/task routing/checkpoints/MCP | ADR-0046 + `../engineering/agent-context-engine.md` + `../../context/routes.json` |
| ChatGPT Web Context Engine tunnel bridge | ADR-0047 + `../runbooks/chatgpt-web-secure-mcp-tunnel.md` + `../technology/local-development-baseline.md` |
| ChatGPT Web developer-host Ops MCP | ADR-0048 + `../runbooks/chatgpt-web-ops-mcp.md` + `../technology/local-development-baseline.md` |
| ChatGPT Web developer-host Desktop MCP | ADR-0049 + ADR-0050 + `../runbooks/chatgpt-web-desktop-mcp.md` + `../technology/local-development-baseline.md` |
| Production profile | ADR-0042 + `PRODUCTION-READINESS-CHECKLIST.md` |
| Network/client address | ADR-0043 + `network-architecture.md` |
| Semantic quota | ADR-0024 |
| Day-One observability | ADR-0044 + `reliability-and-observability.md` |
| PII-safe logging | ADR-0031 |
| DevSecOps source/secret/dependency-advisory/SBOM/vulnerability/signing/admission | ADR-0045 + `devsecops-security-toolchain.md`; ADR-0017/0035/0038/0039 remain owning decisions for their scopes |
| Compromised Password corpus/runtime | ADR-0040 + `services/compromised-password-service.md` |
| Reference Data capability/service trigger | ADR-0041 + `services/reference-data-service.md` |
| Authorization | ADR-0013/0026/0032/0036 + service doc |
| Identity/MFA/session | ADR-0012/0023 + service doc |
| BFF/browser security | ADR-0016 + service doc |
| Data/messaging | `data-and-messaging.md` |
| Testing/evidence | `testing-and-quality-gates.md` + security/readiness/fitness matrices |
| Build/CI | `../engineering/build-and-ci-quality-enforcement.md` |
| Technology pins | `../technology/technology-baseline.md` |
| Compatibility | `../technology/production-compatibility-matrix.md` |
| Chaos/DR | `../operations/chaos-engineering-program.md` + `../runbooks/production-cold-dr.md` |

## Agent Context Engine source rule

ADR-0046 keeps project context Git-native. `../../context/routes.json` is the canonical task-routing registry; `TASK-REVIEW-MATRIX.md` is its generated human view. Checkpoints under `../../context/checkpoints/` are historical evidence and never current architecture authority. Current Git source always outranks derived context or external/model memory.

The read-only Context MCP adapter and local retrieval are repository/developer tooling only. They do not create an application runtime dependency, new bounded context, production network edge, or central cross-project memory service.

ADR-0047 permits OpenAI Secure MCP Tunnel only as an external developer-tool bridge from ChatGPT Web to the unchanged HooshiX Context stdio MCP adapter. It does not create a HooshiX HTTP/network listener or broaden the MCP tool authority.

ADR-0048 permits a separate developer-host Ops MCP using the same approved tunnel transport pattern but a separate profile/key/policy. Ops does not broaden Context MCP, create production authority, or make retrieved context an execution authorization source.

ADR-0049 permits a third developer-host Desktop MCP for policy-gated interactive Windows UI observation/input. ADR-0050 narrowly extends that same Desktop boundary with an optional local credential-use broker: ChatGPT supplies only an opaque `credential_id`; a fixed helper resolves a policy-bound Windows Credential Manager Generic Credential after fresh app/process-image-path/SHA-256/password-target checks and never returns the credential value. Desktop remains outside production authority and does not add tools to Context or Ops.

## External primary sources used by current decisions

Use upstream/official primary sources for version, protocol, API, and support claims.

### Agent Context Engine interoperability

- Model Context Protocol specification: `https://modelcontextprotocol.io/specification/2026-07-28/`
- MCP server discovery: `https://modelcontextprotocol.io/specification/2026-07-28/basic/discovery`
- MCP tools: `https://modelcontextprotocol.io/specification/2026-07-28/server/tools`

ADR-0046 uses MCP only as a read-only local interoperability adapter. The repository remains context authority regardless of protocol/client metadata.

### ChatGPT Web Secure MCP Tunnel

- OpenAI tunnel-client repository: `https://github.com/openai/tunnel-client`
- OpenAI tunnel-client stable releases/integrity metadata: `https://github.com/openai/tunnel-client/releases/latest`
- OpenAI tunnel-client end-user guide: `https://github.com/openai/tunnel-client/blob/v0.0.11/docs/end-user-guide.md`
- OpenAI tunnel-client configuration reference: `https://github.com/openai/tunnel-client/blob/v0.0.11/docs/configuration.md`

ADR-0047 uses tunnel-client only as the outbound customer-run bridge to the existing stdio Context MCP. ADR-0048 and ADR-0049 reuse the reviewed transport pattern for separate developer-host Ops and Desktop profiles/credentials. Version/integrity/authentication facts are developer-tool inputs; they do not prove an operator-PC tunnel is installed, ready, running with the intended Windows token/session, or connected to ChatGPT.

### Windows desktop automation

- Microsoft WinApp CLI documentation: `https://learn.microsoft.com/windows/apps/dev-tools/winapp-cli/`
- Microsoft WinApp CLI UI automation documentation: `https://learn.microsoft.com/windows/apps/dev-tools/winapp-cli/ui-automation`
- Microsoft WinApp CLI repository/releases: `https://github.com/microsoft/WinAppCli`
- Microsoft `SendInput` / `KEYBDINPUT` reference: `https://learn.microsoft.com/windows/win32/api/winuser/ns-winuser-keybdinput`
- Microsoft `INPUT` structure reference: `https://learn.microsoft.com/windows/win32/api/winuser/ns-winuser-input`
- Microsoft `QueryFullProcessImageNameW` reference: `https://learn.microsoft.com/windows/win32/api/winbase/nf-winbase-queryfullprocessimagenamew`
- Microsoft `CredReadW` reference: `https://learn.microsoft.com/windows/win32/api/wincred/nf-wincred-credreadw`
- Microsoft `CREDENTIALW` reference: `https://learn.microsoft.com/windows/win32/api/wincred/ns-wincred-credentialw`
- Microsoft UI Automation `AutomationElement.IsPasswordProperty`: `https://learn.microsoft.com/dotnet/api/system.windows.automation.automationelement.ispasswordproperty`

ADR-0049 uses WinApp CLI only as pinned developer-host UI automation tooling behind HooshiX policy/MCP controls. ADR-0050 uses Windows Credential Manager/UI Automation only for a policy-bound use-without-disclosure broker. Neither decision creates production authority, a credential reader, or a UAC/Secure-Desktop bypass.

### DevSecOps security toolchain

- Gitleaks repository/releases: `https://github.com/gitleaks/gitleaks`
- Gitleaks release assets/checksums: `https://github.com/gitleaks/gitleaks/releases`
- OSV-Scanner repository/releases: `https://github.com/google/osv-scanner`
- OSV-Scanner releases/checksums: `https://github.com/google/osv-scanner/releases`
- Syft repository/releases: `https://github.com/anchore/syft`
- Grype repository/releases: `https://github.com/anchore/grype`
- Cosign documentation/releases: `https://docs.sigstore.dev/cosign/` and `https://github.com/sigstore/cosign/releases`
- Kyverno image verification: `https://kyverno.io/docs/policy-types/image-validating-policy/`

ADR-0045 converts these tool capabilities into the HooshiX responsibility map. OSV-Scanner is early declared/locked dependency advisory feedback; Syft+Grype remain final-image release/deployed-artifact vulnerability authority. Upstream tool capability does not prove that the corresponding repository gate exists or passed.

### Compromised Password

- HIBP API / Pwned Passwords documentation: `https://haveibeenpwned.com/API/V3`
  - Pwned Passwords corpus is available as SHA-1 or NTLM;
  - default range protocol uses first five SHA-1 characters;
  - complete corpus can be downloaded for offline use.
- Official HIBP Pwned Passwords Downloader: `https://github.com/HaveIBeenPwned/PwnedPasswordsDownloader`

ADR-0040 converts those facts into HooshiX-specific offline SHA-1 corpus/freshness/provenance policy. HIBP source facts do not change Argon2id password storage.

### Spring application observability

- Spring Boot Observability: `https://docs.spring.io/spring-boot/reference/actuator/observability.html`
- Spring Boot Tracing: `https://docs.spring.io/spring-boot/reference/actuator/tracing.html`
- Spring Boot Metrics: `https://docs.spring.io/spring-boot/reference/actuator/metrics.html`

Current Spring Boot baseline uses Micrometer Observation/Tracing, Prometheus-compatible metrics, and OpenTelemetry/OTLP trace export under ADR-0044.

### OpenTelemetry Collector

- deployment patterns: `https://opentelemetry.io/docs/collector/deploy/`
- agent pattern: `https://opentelemetry.io/docs/collector/deploy/agent/`
- gateway pattern: `https://opentelemetry.io/docs/collector/deploy/gateway/`
- official releases: `https://github.com/open-telemetry/opentelemetry-collector-releases/releases`

Technology Baseline pins `otelcol-contrib` 0.157.0; deployment/security behavior remains governed by ADR-0044.

### Grafana observability backends

- Loki release notes: `https://grafana.com/docs/loki/latest/release-notes/`
- Loki releases: `https://github.com/grafana/loki/releases`
- Tempo release notes: `https://grafana.com/docs/tempo/latest/release-notes/`
- Tempo releases: `https://github.com/grafana/tempo/releases`

Technology Baseline pins Loki 3.7.4 and Tempo 3.0.2 for the reviewed current single-server observability target.

### Kyverno

- Policy Types overview: `https://kyverno.io/docs/policy-types/overview/`
- ImageValidatingPolicy: `https://kyverno.io/docs/policy-types/image-validating-policy/`

Kyverno 1.18 marks `policies.kyverno.io/v1` CEL types stable and legacy ClusterPolicy/CleanupPolicy families deprecated. HooshiX greenfield production policy gates therefore reject new legacy policy manifests.

## Source-use rules

- Do not copy upstream release notes into multiple repository authorities.
- Record exact production versions only in Technology Baseline and deployment locks/digests.
- Developer-only integration pins may live in Local Development Baseline when they do not affect production runtime.
- A source URL is evidence input, not proof that repository implementation exists.
- When an upstream statement changes materially, review affected ADR/baseline/compatibility/evidence gates in one coherent change.