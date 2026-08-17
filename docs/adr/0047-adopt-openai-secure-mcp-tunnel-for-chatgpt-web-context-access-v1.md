# ADR-0047: OpenAI Secure MCP Tunnel for ChatGPT Web Context Access v1

## Status

Accepted — current effective decision

## Date

2026-08-18

## Context

HooshiX already has the Git-native Agent Context Engine from ADR-0046. Its MCP adapter is deliberately local, read-only, and stdio-only. That boundary is suitable for local MCP clients, but ChatGPT Web cannot directly attach to a developer-machine stdio process.

The current operator needs reliable ChatGPT Web access to the existing Context Engine from an always-on Windows developer PC without depending on Codex capacity and without exposing a new public MCP server or general remote shell.

OpenAI Secure MCP Tunnel provides a customer-run `tunnel-client` that keeps an outbound-only HTTPS connection to the OpenAI tunnel control plane and can launch a local stdio MCP server as its child process. This allows ChatGPT Web to use the existing HooshiX MCP surface without adding an HTTP listener to HooshiX.

## Decision

HooshiX adopts **OpenAI Secure MCP Tunnel** as the approved ChatGPT Web bridge to the existing ADR-0046 stdio MCP adapter.

The selected path is:

```text
ChatGPT Web
-> OpenAI Secure MCP Tunnel control plane
-> customer-run OpenAI tunnel-client on the approved developer PC
-> process-spawned HooshiX stdio MCP adapter
-> Git-native Context Engine
-> dedicated local HooshiX Git checkout
```

This is developer/agent tooling only. It is not an application bounded context, production service, production control plane, datastore, application request-path dependency, or cross-project memory service.

ADR-0046 remains authoritative for Context Engine semantics, Git precedence, bounded retrieval, checkpoint behavior, and the MCP tool contract.

## 1. Core MCP boundary remains stdio-only

The HooshiX MCP adapter remains stdio-only and opens no HTTP, SSE, WebSocket, TCP, or other network listener.

The tunnel bridge is external to the HooshiX MCP process. `tunnel-client` launches the existing stdio server and forwards MCP frames through the OpenAI-managed tunnel path.

No general-purpose proxy, arbitrary local port forwarding, arbitrary URL fetch, shell, filesystem browser, Git mutation, checkpoint mutation, deployment action, credential reader, or production operation is added to the HooshiX MCP surface.

The exposed HooshiX tool list remains exactly:

```text
project.bootstrap
project.context_for_task
project.search
project.latest_checkpoint
project.changed_context
```

Every tool remains read-only, non-destructive, bounded, and non-open-world under ADR-0046.

## 2. Git authority and checkout model

Current Git authority still outranks derived context, checkpoints, ChatGPT conversation state, and model memory.

The approved tunnel runtime uses a dedicated HooshiX checkout for context access. The checkout should be clean and synchronized to the intended reviewed Git state before the tunnel is considered ready for targeted-review use.

The MCP entry point resolves its repository root from its own tracked script location rather than relying on the tunnel-client process working directory. This prevents an arbitrary service/startup working directory from selecting the wrong Git repository or causing accidental root discovery failure.

A dirty authority/configuration path continues to make targeted-review trust fail safe. The tunnel does not convert a dirty or stale checkout into verified current context.

Repository synchronization is operator-owned Git work. The read-only MCP surface does not fetch, pull, checkout, reset, merge, commit, push, or otherwise mutate Git state.

## 3. Tunnel-client version and integrity

The approved initial developer-tool version is OpenAI `tunnel-client` **v0.0.11**, the current stable release reviewed on 2026-08-18.

Operators use the platform-appropriate official release archive and verify its SHA-256 digest against the release-published integrity metadata before first use or upgrade.

Windows developer hosts select the matching official `windows-amd64` or `windows-arm64` archive according to the actual machine architecture. Architecture is not guessed.

`tunnel-client` upgrades are reviewed as developer tooling changes. A new version does not silently broaden the HooshiX MCP tool list or authority.

## 4. Authentication and secret handling

The long-lived tunnel daemon uses a dedicated OpenAI **runtime API key** with the minimum tunnel permissions required for runtime operation: Tunnels `Read` + `Use`.

The long-lived daemon MUST NOT use an OpenAI admin key. An admin key is only for explicit tunnel-management CRUD when an operator intentionally chooses that path.

Runtime/admin keys:

- are never committed to Git;
- are never placed in checkpoint files, documentation examples as real values, screenshots intended for repository evidence, logs, metrics, traces, or support bundles;
- are never pasted into ChatGPT conversation content;
- are not passed as a literal command-line argument where they can be exposed by process inspection/history;
- are supplied through the supported local secret reference/environment mechanism with host access controls;
- are rotated/revoked if exposure is suspected.

Tunnel identifiers are not treated as secret credentials, but they are still bounded configuration and do not grant repository authority by themselves.

## 5. Network and host exposure

The customer-run `tunnel-client` initiates the tunnel connection outbound to OpenAI. HooshiX does not require an inbound Internet port, router port-forward, public DNS record, public MCP URL, or inbound firewall exception for the MCP server.

Local tunnel-client health/readiness/operator surfaces remain bound to loopback unless a separately reviewed local-operations change proves another binding is necessary. They are not exposed to the public Internet or ordinary LAN clients by default.

The developer PC is not production infrastructure. Tunnel availability affects only AI context access. Tunnel or PC failure cannot change production authentication, Authorization, tenant isolation, secrets, data, deployment, or runtime behavior.

## 6. Prompt-injection and data boundary

The tunnel changes transport reachability, not instruction precedence.

Repository search results, source comments, fixtures, generated content, and checkpoint prose remain retrieved **data**. They do not become higher-priority instructions merely because they arrived through ChatGPT Web.

The Context Engine continues to enforce tracked-file-only retrieval, sensitive-filename exclusion, bounded query/result/file sizes, repository-root confinement, Git/blob/worktree provenance, and conservative routing.

The tunnel carries only what the selected MCP tools return for an explicit ChatGPT tool call. It does not authorize arbitrary PC filesystem enumeration or arbitrary local process access.

## 7. Operational workflow

The supported first-use sequence is:

1. create or select the tunnel in the ChatGPT/OpenAI tunnel management surface;
2. install the approved `tunnel-client` release and verify its published digest;
3. create a restricted runtime API key with Tunnels `Read` + `Use` and store it only on the operator host through the supported secret mechanism;
4. use the official `sample_mcp_stdio_local` profile pattern to launch the absolute HooshiX `scripts/context/mcp_server.py` path with the approved Python interpreter;
5. run `tunnel-client doctor --profile <profile> --explain`;
6. start `tunnel-client run --profile <profile>` and verify local `/readyz` before connecting ChatGPT;
7. in ChatGPT, configure the custom Plugin/MCP connection with `Connection: Tunnel` and select the same tunnel;
8. verify discovery exposes only the five approved read-only tools;
9. call `project.bootstrap` and confirm current Git provenance before targeted engineering work.

Persistent Windows startup is configured only after this foreground/manual path succeeds. Persistence must preserve the same binary, profile, secret, checkout, and least-privilege boundaries.

## 8. Failure behavior

Tunnel/control-plane/key/PC/MCP-child failure means **context tooling unavailable**. It does not authorize fallback to stale memory as current Git authority.

When tunnel context is unavailable, agents follow the existing ADR-0046/`AGENTS.md` fallback: use current repository authority through available repository tooling and do not reduce review scope because the Context Engine could not run.

A failed tunnel must not trigger automatic credential disclosure, public listener creation, firewall weakening, general shell exposure, or write-capable MCP fallback.

## 9. Verification requirements

Executable/review evidence must prove at least:

- the HooshiX MCP server starts and discovers correctly when launched from a working directory outside the repository;
- the repository root is derived from the tracked MCP entry point, not caller working directory;
- the five-tool list is unchanged and every tool remains read-only/non-destructive;
- unknown/write-like MCP tool requests still fail safely;
- no HooshiX network listener is introduced;
- tunnel documentation selects stdio child mode and does not instruct operators to expose a public MCP port;
- tunnel runtime/admin credentials are absent from repository content and test fixtures;
- the pinned tunnel-client release/version and official digest-verification procedure are documented;
- operator setup requires restricted Tunnels `Read` + `Use` runtime credentials;
- real end-to-end ChatGPT Web tunnel discovery and `project.bootstrap` remain `NOT VERIFIED` until executed on the operator PC after merge.

## Security and performance impact

The change adds one external developer-tool data path from the approved PC to OpenAI through `tunnel-client`. The data visible to ChatGPT is bounded by the existing MCP tool outputs.

No product/runtime request path is changed. Product latency, service capacity, database, Redis, Kafka, Istio, Kubernetes, provider, and production availability behavior are unchanged.

The only expected local overhead is the tunnel-client process plus the existing on-demand Context Engine work. This is outside production capacity accounting.

## Relationship to current decisions

- ADR-0046 remains the Context Engine and MCP authority.
- ADR-0045 remains developer/security tool integrity guidance where applicable; tunnel-client is not a DevSecOps scanner or application runtime dependency.
- Current repository secret-handling and reporting rules remain authoritative.
- This ADR does not satisfy or trigger the deferred central cross-project memory service in ADR-0046.

## Rollback considerations

Rollback removes the ChatGPT Web tunnel integration and returns to local stdio MCP plus repository/GitHub fallback.

Rollback MUST NOT:

- replace the tunnel with a public unauthenticated MCP listener;
- expose a general remote shell or arbitrary filesystem tool;
- broaden the five-tool read-only MCP contract;
- use an admin key as a long-lived runtime credential;
- make ChatGPT/model memory outrank current Git;
- create a central memory database/service without the separate ADR-0046 trigger and review.
