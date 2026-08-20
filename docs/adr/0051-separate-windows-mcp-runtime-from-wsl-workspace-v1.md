# ADR-0051: Separate Windows MCP Runtime from WSL Workspace v1

## Status

Accepted — current effective decision

## Date

2026-08-19

## Context

HooshiX application development now uses WSL2 Ubuntu. Context, Ops, and Desktop MCP still need Windows host authority for their approved roles. Keeping their runtime source in the HooshiX application repository couples host-control tooling to application source. Windows Git also gives an incorrect executable-bit view when it reads the WSL checkout through a Windows filesystem path.

## Decision

Use this developer-host split:

```text
Windows 11
- OpenAI tunnel-client
- independent Context/Ops/Desktop MCP runtime
- protected local MCP policy, audit, and credential state
- WSL2 Ubuntu bridge

WSL2 Ubuntu
- /home/coder/workspace/Hooshix
- canonical HooshiX Git checkout
- application edit/build/test/runtime
- project Agent Context Engine and context metadata
```

The active HooshiX checkout MUST be on the native WSL Linux filesystem. Do not use `/mnt/c`, `/mnt/d`, or another Windows-mounted filesystem as the active application checkout.

The current Windows MCP runtime root is `D:\Projects\HooshiXMcpRuntime`. This path is developer-host state. It is not production state and is not part of the HooshiX Git tree.

## 1. Repository ownership

HooshiX continues to own project Context governance:

```text
context/bootstrap.json
context/routes.json
context/*.schema.json
context/checkpoints/
scripts/context/context_engine.py
scripts/context/post_merge_checkpoint.py
project Context Engine/checkpoint tests
```

HooshiX no longer owns these MCP runtime artifacts:

```text
scripts/context/mcp_server.py
scripts/ops/
scripts/desktop/
ops/policy.schema.json
ops/policy.example.json
desktop/policy.schema.json
desktop/policy.example.json
```

The independent Windows MCP runtime owns these artifacts and their runtime tests.

## 2. Context MCP

ADR-0046 remains authoritative for Git precedence, routing, retrieval, checkpoints, and the exact five read-only Context tools.

The Context MCP adapter runs on Windows. It invokes the project Context Engine inside WSL. Protected local policy fixes the WSL distribution, Linux repository root, Context Engine path, and `wsl.exe` path.

The adapter MUST NOT use Windows Git against the WSL checkout through `\\wsl.localhost`, `\\wsl$`, or another Windows filesystem view. Git and tracked-file semantics run inside WSL.

The bridge uses fixed argument arrays. Caller data MUST NOT select a different distribution, repository root, executable, or shell command.

## 3. Ops MCP

ADR-0048 remains authoritative for Ops policy, filesystem/process authority, audit, elevation, and developer-host-only scope.

Ops remains Windows-hosted. HooshiX project commands run through an explicit `wsl.exe` policy alias and use `/home/coder/workspace/Hooshix`. Long developer-host commands use the ADR-0048 persistent process-job surface when they can exceed one synchronous tunnel response lifetime; job state remains in protected Windows Ops state and does not move application Git authority out of WSL.

The old Windows HooshiX checkout is not an approved application mutation root. A broad PowerShell, Python, or WSL alias is still broad host authority. It is not a sandbox and it is not production authority.

## 4. Desktop MCP

ADR-0049 and ADR-0050 remain authoritative for Desktop policy, WinApp, input/capture, credential use, UIPI, audit, and developer-host-only scope.

Desktop stays in the interactive Windows session. Its implementation and fixed helpers do not depend on the HooshiX application checkout after extraction.

Context, Ops, and Desktop keep separate processes, tunnel profiles, runtime credentials, policies, and authority surfaces.

## 5. Application development

All normal HooshiX application engineering is Linux-native in WSL. This includes Git, source edits, Java/Gradle, Node, Docker/Compose, local datastores, kubectl/kind/Helm/Istio, service runtime, and integration tests.

Windows stays the host for the three MCP surfaces and Windows-only developer tools. Windows-hosted MCP use does not move application execution back to Windows.

Exact local tool versions remain in `docs/technology/local-development-baseline.md`.

## 6. Security and verification

- Linux Git in the canonical WSL checkout is current repository authority.
- Windows Git MUST NOT decide whether the WSL worktree is clean.
- MCP runtime source, host policies, tunnel credentials, audit state, and Desktop credential state stay outside HooshiX Git.
- Context repository root and engine path come from protected local policy, not caller input.
- Ops does not authorize direct writes to protected `main`.
- Desktop keeps current non-elevated, UIPI, and Secure Desktop limits.
- None of the MCP surfaces becomes production authority.

HooshiX CI continues to verify project Context metadata, routing, checkpoints, generated task matrix, repository structure, and application/service gates. HooshiX CI does not claim to test the external Windows MCP runtime after extraction.

Host evidence must verify that all three live MCP backends run from the independent Windows runtime, Context reports `/home/coder/workspace/Hooshix`, Ops can run a harmless Git command through WSL in that checkout, the reviewed Ops process-job surface survives one MCP response lifetime with bounded polling/cancellation, Desktop reports expected policy state, and no live MCP backend command references the old Windows HooshiX checkout.

Repository baseline verification must reject reintroduction of the MCP runtime paths externalized by this ADR.

## 7. Migration and rollback

Remove repository MCP runtime source only after the independent runtime is versioned, tested, connected to the three tunnel profiles, reloaded, and verified.

The old Windows HooshiX checkout can remain temporarily only as rollback evidence. It is not current project authority and MUST NOT receive new application work.

Rollback can select a known-good independent MCP runtime revision. Rollback MUST NOT make the Windows application checkout authoritative, use Windows Git for WSL authority, merge the three MCP trust surfaces, expose a public MCP listener, or weaken repository/production security controls.

## Relationship to current decisions

ADR-0046 remains the project Context Engine and read-only Context contract authority. ADR-0047 remains the Secure MCP Tunnel transport decision. ADR-0048 remains the Ops authority decision. ADR-0049 and ADR-0050 remain the Desktop and credential-broker decisions. ADR-0030 remains production human-access authority. ADR-0041 Reference Data gating is unchanged.
