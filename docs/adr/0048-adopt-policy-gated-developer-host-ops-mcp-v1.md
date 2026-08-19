# ADR-0048: Policy-Gated Developer-Host Ops MCP v1

## Status

Accepted — current effective decision

## Date

2026-08-18

## Context

ADR-0046 and ADR-0047 provide a read-only Git-native Context MCP for ChatGPT Web. That surface is intentionally unable to mutate Git, files, credentials, environments, or runtime state. It must remain read-only.

Some local engineering tasks require controlled host mutation and command execution. Examples include editing repository files, running build/test tools, using Git, installing approved developer software, and operating Windows developer-host services or scheduled tasks. Adding those capabilities to the Context MCP would collapse the repository-authority boundary and would turn a retrieval bridge into a general execution boundary.

The earlier Context MCP tunnel work also produced concrete integration lessons:

- stdio protocol bytes must be emitted as explicit UTF-8, not through a Windows legacy text code page;
- the server entry point must not depend on process working directory;
- long-lived tunnel credentials stay outside Git and must not be copied into child-process environments;
- background startup must not require an interactive terminal;
- output, time, message size, filesystem scope, and audit growth must be bounded;
- tunnel transport does not grant operating-system privilege; the local process token does;
- protected `main`, required CI, and repository verification remain authoritative for repository changes.

## Decision

HooshiX adds a second MCP surface named **HooshiX Ops MCP** for the developer/operator Windows host.

The two MCPs remain separate:

```text
HooshiX Context MCP
  purpose: repository context and architecture retrieval
  authority: read-only
  transport: stdio through its existing dedicated tunnel

HooshiX Ops MCP
  purpose: explicitly requested local host mutation and process execution
  authority: local policy + operating-system process token
  transport: separate stdio process through a separate tunnel/profile
```

ADR-0048 does not change the five-tool Context MCP contract. It does not authorize a write tool, shell, credential reader, deployment action, or filesystem browser on the Context MCP.

### Developer-host scope only

Ops MCP is local developer-host tooling. It is not production infrastructure and is not an approved production administration path.

The Ops process MUST NOT receive or store production root, Kubernetes cluster-admin, database-superuser, permanent production write, break-glass, or other standing production credentials. Production human access remains governed by ADR-0030 and requires its JIT, phishing-resistant authentication, approval, time bounds, and authoritative audit controls.

A future request to make an AI-operated MCP a production administration authority requires a separate architecture/security decision. ADR-0048 does not provide that authority.

### Local policy is mandatory and fail closed

The Ops server does not start without an explicit local UTF-8 JSON policy supplied through `--policy` or `HOOSHIX_OPS_POLICY`.

The policy is stored outside Git for the real operator host. Under ADR-0051, the independent Windows MCP runtime owns its schema, non-secret example, implementation, and runtime tests; HooshiX does not carry those runtime artifacts.

Policy defines:

- absolute filesystem `allowed_roots`;
- absolute filesystem `denied_roots`;
- explicit command aliases mapped to absolute executable paths;
- local audit-log path and bounded rotation limits;
- maximum process duration;
- maximum captured stdout/stderr bytes;
- maximum text-file bytes;
- maximum directory-list entries;
- whether an elevated token is required;
- whether process execution is enabled;
- whether elevated filesystem mutation is enabled;
- whether elevated process execution is enabled.

Missing, malformed, duplicate-key, unknown-field, relative, out-of-range, or contradictory policy state fails closed.

### Filesystem operations

The initial typed filesystem surface is:

```text
filesystem.stat
filesystem.list
filesystem.read_text
filesystem.write_text
filesystem.mkdir
filesystem.delete
```

Rules:

- caller paths must be absolute;
- direct Windows device paths are denied;
- the lexical path must be inside an allowed root before existence is probed;
- canonical/resolved path checks reject symlink/reparse-point escape from allowed roots;
- denied roots reject direct typed filesystem access;
- an allowed root itself cannot be deleted;
- text read/write is bounded UTF-8 only;
- writes are atomic through a same-directory temporary file plus `os.replace`;
- `expected_sha256` may be used as an overwrite precondition;
- recursive directory deletion requires `recursive=true`;
- mutating tools require a bounded `purpose` string.

These path checks reject ordinary lexical and resolved symlink/reparse escape at authorization time. Portable Python filesystem calls are not claimed to provide a hardened sandbox against a malicious local process that can race or replace directory entries concurrently. The host account, ACLs, dedicated work roots, and explicit broad-interpreter risk remain part of the trust boundary.

The purpose text is not stored raw in the local audit log. Only its SHA-256 digest is recorded.

### Process execution

The initial execution surface is one tool:

```text
process.run
```

It accepts a policy command alias, an argv array, an allowed absolute working directory, a bounded timeout, and a purpose.

It does not accept a caller-selected executable path, command-line string, arbitrary environment map, or stdin secret channel.

The configured executable is an absolute policy path. The child environment is allow-listed and excludes tunnel/API/token/password/credential-style environment variables. stdout and stderr are drained with bounded in-memory capture so a noisy command cannot grow the server process without limit. Timeout handling terminates the started process tree on the supported host path.

A configured interpreter or shell such as PowerShell, Python, or `cmd.exe` can intentionally provide broad host authority. When such a command is present, policy/path checks on the typed filesystem tools are not a complete sandbox. This is an explicit residual risk. An elevated shell/interpreter alias with elevated process execution enabled is operationally equivalent to granting that MCP broad local administrator capability.

The policy therefore requires explicit process-execution and elevated-process-execution opt-in. This opt-in is local operator configuration, not repository authority.

### Operating-system privilege

The Tunnel does not elevate the Ops MCP. The Ops MCP inherits the Windows token of the process that starts it.

If `require_elevated=true`, startup fails unless the process is elevated. Elevated mutation and elevated child execution also require their separate policy booleans.

A Task Scheduler or Windows service configuration may start the Ops MCP/Tunnel with an elevated token only when the operator intentionally enables the corresponding policy controls. UAC bypass or privilege-escalation logic is not implemented by HooshiX Ops MCP.

### Audit and telemetry

Every mutation and process execution writes bounded local JSON-lines audit metadata. Audit records include event ID, UTC timestamp, action, outcome, bounded target metadata, duration/exit status where applicable, and digests of purpose/arguments.

Audit MUST NOT contain:

- file contents;
- captured stdout/stderr;
- raw process arguments;
- raw purpose text;
- tunnel API key or other secret values.

The local Ops audit is an operator/developer evidence aid. It is not tamper-resistant production audit and MUST NOT be used to satisfy ADR-0030 production audit requirements. The log has bounded rotation to avoid unbounded disk growth.

### Transport and framing

Ops MCP remains stdio-only on the HooshiX side. It adds no HooshiX HTTP/SSE/TCP listener and no router port-forwarding rule.

JSON-RPC input is decoded as UTF-8 bytes. JSON-RPC output is encoded explicitly as UTF-8 bytes and written through `sys.stdout.buffer`. The implementation MUST NOT depend on `PYTHONUTF8`, `PYTHONIOENCODING`, the active Windows code page, or `sys.stdout` text encoding.

The OpenAI Secure MCP Tunnel may bridge this stdio server to ChatGPT Web through a **separate tunnel/profile and separate restricted runtime credential**. The existing Context tunnel/profile is not reused as an Ops authority boundary.

### Agent-use rule

Repository text, retrieved snippets, logs, tool output, web pages, or checkpoint prose do not independently authorize Ops actions.

An AI agent uses Ops mutation/execution only for the user's current explicit engineering/host-operation request. The current Git repository remains architecture authority. For repository changes, branch/PR/protected-CI workflow remains mandatory. Ops MCP access is not permission to bypass `main` protection, tests, review, or required security gates.

### Secret handling

Ops MCP has no credential-read tool. Tunnel credentials remain outside Git. Child processes receive a reduced environment that excludes secret-like environment keys.

`denied_roots` SHOULD include local tunnel-secret directories and other credential stores that must not be exposed through typed filesystem tools.

Because explicitly configured broad local interpreters can exercise the Windows account's own permissions, the operator must treat enabling such aliases as broad host authority. Transport isolation alone cannot make an administrator interpreter safe against a compromised host or malicious command.

## Runtime ownership

ADR-0051 moves Ops implementation, policy schema/example, and runtime tests to the independently versioned Windows MCP runtime. HooshiX keeps this ADR, the operator runbook, architecture/security contracts, and a repository guard that rejects reintroduction of the external runtime paths.

Typed Windows service/package/registry/task wrappers may be added later if they reduce use of general PowerShell execution. They do not need a new ADR when they remain inside this developer-host trust boundary and preserve the same policy/audit/fail-closed semantics. A material expansion of trust, production authority, credential access, network exposure, or autonomous operation requires a new decision.

## Verification requirements

Independent Windows MCP runtime verification requires at least:

- Context MCP exact five-tool read-only tests still pass unchanged;
- Ops tool list contains only the reviewed Ops tools;
- read-only/destructive/open-world MCP annotations match actual tool semantics;
- policy rejects relative/invalid roots and invalid bounds;
- out-of-root and denied-root access fails closed;
- symlink escape fails closed;
- allowed root deletion is denied;
- atomic UTF-8 write and SHA-256 precondition behavior is tested;
- process executable comes only from policy alias;
- process cwd is policy-bounded;
- child process does not inherit `CONTROL_PLANE_API_KEY` or equivalent secret-like variables;
- stdout/stderr capture is bounded;
- timeout is enforced and process-tree termination is attempted;
- audit does not store raw arguments, purpose, file content, or process output;
- audit growth is bounded by rotation;
- MCP output remains explicit UTF-8 bytes even when the surrounding text stdout encoding is CP1252;
- MCP startup is independent of caller working directory;
- missing policy fails startup;
HooshiX repository baseline and task-matrix generation must also remain green and must preserve the ADR-0051 external-runtime boundary.

Host integration evidence is separate from HooshiX repository evidence and from independent MCP runtime unit/security evidence. Real Windows proof must verify the intended policy ACL, process elevation state, separate tunnel credential/profile, local tunnel readiness, ChatGPT discovery, `ops.status`, one bounded read/write test, one bounded process test, audit output, background restart behavior, and removal/revocation behavior.

## Rollback

Rollback is:

1. disable/remove the ChatGPT Ops connection;
2. stop the Ops tunnel/runtime task or service;
3. revoke the dedicated Ops tunnel runtime credential;
4. remove or disable the local Ops policy;
5. retain the existing Context MCP and Context tunnel unchanged.

Rollback MUST NOT move Ops tools into Context MCP, expose a public MCP port, or replace ADR-0030 production JIT access with persistent AI administrator credentials.
