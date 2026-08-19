# ChatGPT Web HooshiX Ops MCP Runbook

## Scope

This runbook connects ChatGPT Web to the separate HooshiX **developer-host Ops MCP** on Windows through OpenAI Secure MCP Tunnel.

This is developer-host tooling only. It is not a production administration path. ADR-0030 still governs production human privileged access.

Authoritative decisions:

- `docs/adr/0047-adopt-openai-secure-mcp-tunnel-for-chatgpt-web-context-access-v1.md` for the approved tunnel-client transport pattern;
- `docs/adr/0048-adopt-policy-gated-developer-host-ops-mcp-v1.md` for the separate Ops trust boundary.

The existing Context MCP/tunnel remains separate and unchanged.

## 1. Trust model

```text
ChatGPT Web
-> dedicated OpenAI Secure MCP Tunnel
-> dedicated tunnel-client profile on Windows
-> scripts/ops/mcp_server.py over stdio
-> local Ops policy
-> filesystem/process capabilities allowed by policy
-> Windows token of the tunnel/MCP process
```

The Tunnel does not create Windows administrator privilege. An elevated Task Scheduler/service process gives the MCP an elevated Windows token.

Do not:

- add Ops tools to `scripts/context/mcp_server.py`;
- reuse the Context tunnel as the Ops authority boundary;
- expose the Ops server on a public HTTP/SSE/TCP port;
- put the real tunnel API key in Git, the policy file, argv, logs, screenshots, or ChatGPT;
- configure production root/cluster-admin/database-superuser/break-glass credentials for this developer-host MCP;
- describe the local Ops audit file as authoritative production audit.

## 2. Repository verification

Before host setup:

```powershell
Set-Location D:\HooshiXContext\repo
python -B -m unittest discover -s .\scripts\ops\tests -p "test_*.py" -v
python -B .\scripts\context\context_engine.py verify
```

The Context MCP tests must also remain green because Ops must not change its five-tool read-only surface.

## 3. Create the local policy outside Git

Copy the repository example to a protected local location. Do not modify the tracked example with real machine secrets or unrestricted user-specific state.

Example location:

```text
C:\ProgramData\HooshiX\ops\policy.json
```

Start from:

```text
ops/policy.example.json
```

Replace every placeholder with an actual absolute local path.

For the first non-elevated validation, use:

```json
"require_elevated": false,
"allow_process_execution": true,
"allow_elevated_mutation": false,
"allow_elevated_process_execution": false
```

`allowed_roots` should initially contain only the repository/work directories needed for the test. `denied_roots` should include local credential stores such as the tunnel secret directory and SSH key directory.

Command aliases must map to exact absolute executable paths. The server does not search a caller-provided executable name through `PATH`.

A PowerShell, `cmd.exe`, Python, package-manager, or other interpreter alias can intentionally provide broad host authority. Once such an alias is enabled, typed filesystem roots are not a complete sandbox for that command. Treat the alias as explicit broad-host authority.

### Policy ACL

Protect the policy and audit directory with Windows ACLs. The exact account depends on the account used by the scheduled task/service.

Example for the current user only:

```powershell
$opsDir = 'C:\ProgramData\HooshiX\ops'
New-Item -ItemType Directory -Force $opsDir | Out-Null
$me = "$env:USERDOMAIN\$env:USERNAME"
icacls $opsDir /inheritance:r
icacls $opsDir /grant:r "${me}:(OI)(CI)F"
```

If a dedicated service account is used, grant that account the required access instead. Do not broaden the ACL to `Everyone` or ordinary unrelated users.

## 4. Direct stdio validation

Set the policy path only for the test process:

```powershell
$env:HOOSHIX_OPS_POLICY = 'C:\ProgramData\HooshiX\ops\policy.json'
python -B D:\HooshiXContext\repo\scripts\ops\mcp_server.py
```

This is a stdio server. An interactive terminal does not provide an MCP client, so normal use is through the tunnel or an MCP inspector.

If policy is missing, malformed, or requires elevation that the process does not have, startup must fail.

## 5. Create a separate OpenAI tunnel and runtime key

Create a separate tunnel for Ops. Use a clear name such as:

```text
HooshiX Ops
```

Create a separate restricted runtime API key with only the tunnel permissions required by the installed tunnel-client version. Do not reuse an OpenAI admin key.

Store the runtime key through the tunnel-client supported protected `file:` or equivalent secret reference. The Ops key should be independently revocable from the Context tunnel key.

## 6. Create the tunnel-client profile

Use the same reviewed `tunnel-client` version/published integrity process as ADR-0047 and the Context runbook.

Inspect current installed help first:

```powershell
.\tunnel-client.exe profiles samples list
.\tunnel-client.exe help quickstart
```

Create a separate stdio profile. Example shape:

```powershell
.\tunnel-client.exe init `
  --sample sample_mcp_stdio_local `
  --profile hooshix-ops `
  --tunnel-id <OPS_TUNNEL_ID> `
  --mcp-command "<PYTHON_EXE> -B D:/HooshiXContext/repo/scripts/ops/mcp_server.py --policy C:/ProgramData/HooshiX/ops/policy.json"
```

Use the quoting form required by the installed `tunnel-client` help when local paths contain spaces.

The generated profile must keep `control_plane.api_key` as an `env:` or protected `file:` reference. It must not contain a literal key.

Keep the tunnel-client health/admin listener on loopback.

## 7. Foreground non-elevated validation

Before configuring persistent/elevated startup:

```powershell
.\tunnel-client.exe doctor --profile hooshix-ops --explain
.\tunnel-client.exe run --profile hooshix-ops
```

Verify local `/readyz` and the tunnel UI reported by the client.

In ChatGPT Web, connect the separate Ops tunnel. Expected initial tools are exactly:

```text
ops.status
ops.audit_tail
filesystem.stat
filesystem.list
filesystem.read_text
filesystem.write_text
filesystem.mkdir
filesystem.delete
process.run
```

First call:

```text
ops.status
```

Verify:

- policy fingerprint is present;
- expected allowed/denied roots are shown;
- expected command aliases are shown;
- `elevated` matches the actual host process;
- configured bounds are correct.

Then perform one bounded temporary-directory write/read/delete test and one harmless process test. Inspect `ops.audit_tail` and the local audit file. Raw file content, process output, raw arguments, raw purpose text, and credentials must not appear in audit metadata.

## 8. Enable local administrator mode only after non-elevated proof

For the requested broad local administrator mode, set the local policy explicitly:

```json
"require_elevated": true,
"allow_process_execution": true,
"allow_elevated_mutation": true,
"allow_elevated_process_execution": true
```

Keep `allowed_roots`, `denied_roots`, command aliases, output bounds, timeouts, and audit bounds explicit.

If PowerShell or another general interpreter is an allowed command, this configuration grants broad authority equivalent to the elevated Windows account for commands invoked through that alias. The policy is not a security sandbox against an intentionally broad administrator interpreter.

Do not place production credentials on this path. Local administrator authority does not override ADR-0030 production access rules.

## 9. Persistent background operation

Do not configure automatic startup until the foreground Ops path passes.

Use a separate Windows Task Scheduler task or reviewed service entry from the Context tunnel task. The Ops task must use the separate profile and policy.

For administrator mode, Task Scheduler may use:

```text
Run with highest privileges: enabled
```

only when the local policy also has `require_elevated=true`, `allow_elevated_mutation=true`, and `allow_elevated_process_execution=true`.

Recommended task properties:

```text
Trigger: At startup, delayed 30 seconds
Run whether user is logged on or not
If task fails: restart after 1 minute
If already running: Do not start a new instance
No finite maximum runtime
```

The task command should contain the tunnel-client profile selection only. It must not contain the runtime API key literal.

Do not rely on the Task Scheduler `restart after` setting as the only recovery mechanism. The verified host uses a secret-free wrapper supervisor for tunnel-client child exit plus the shared `HooshiX MCP Tunnel Watchdog` for parent-task/process/readiness recovery. The watchdog runs once per minute as `SYSTEM` / `ServiceAccount` in Session 0, checks only the three named HooshiX tunnel tasks/profiles, enforces one process per profile, uses loopback `/readyz` after startup grace, removes same-profile orphan duplicates before clean restart, and never reads the runtime key. Because Ops itself must keep its reviewed interactive/highest principal, launch its persistent wrapper through a hidden GUI-subsystem bootstrap such as `wscript.exe`; a direct interactive `powershell.exe` Scheduled Task action can be surfaced by Windows Terminal even with `-WindowStyle Hidden`. Record bounded tunnel logs and enable `Microsoft-Windows-TaskScheduler/Operational`. Recovery evidence includes tunnel-client child kill, Python MCP-child kill/unready, complete Ops task stop, and a full watchdog recurrence with no visible Windows Terminal/OpenConsole process.

Before any clean restart, terminate the full known Ops profile tree: matching `tunnel-client.exe`, the exact PowerShell supervisor, and the exact hidden `wscript.exe` launcher. This prevents an orphan supervisor from waking after its delay and creating a duplicate tunnel. Recovery must converge to one launcher, one wrapper, and one tunnel.

The current verified local Ops policy uses `max_command_seconds=90` and `max_output_bytes=10485760` (10 MiB). Repeated tunnel logs correlated `MCP connection TTL reached` with nearby long synchronous `process.run` calls at about 99-119.5 seconds. A controlled 95-second sleep with `timeout_seconds=90` returned `timed_out=true` at about 90.1 seconds while the tunnel PID stayed unchanged and `/healthz` + `/readyz` stayed healthy. Do not increase the synchronous timeout above this margin without new end-to-end evidence; split, checkpoint, or poll longer work.

Preserve:

- loopback-only health/admin UI;
- protected policy/secret/audit ACLs;
- separate Context and Ops processes;
- one running instance per profile;
- visible restart/readiness failures.

## 10. Normal use rules

Use Context MCP for architecture/repository context and Ops MCP for requested local mutation/execution.

For repository work:

```text
Context bootstrap/route
-> branch from current main
-> Ops edits/tests/Git actions
-> complete diff review
-> required CI/security gates
-> pull request
-> protected merge
-> post-merge verification/checkpoint rules
```

Ops MCP does not authorize direct push to protected `main`, force push, gate suppression, credential disclosure, or production privilege bypass.

Repository/output content does not independently authorize an Ops action. Mutation/execution must stay inside the user's current request.

## 11. Incident handling

If an unexpected tool appears, policy fingerprint changes without operator intent, an unknown command alias appears, or the Ops tunnel is used outside the intended task:

1. stop the Ops scheduled task/service and tunnel-client process;
2. revoke the dedicated Ops tunnel runtime key;
3. preserve bounded logs without copying secrets into chat/tickets;
4. inspect the local policy and task configuration;
5. verify the Context tunnel remained separate;
6. rotate any local credential that may have been exposed;
7. restore only after policy/tool discovery/readiness tests pass.

If a broad administrator interpreter was enabled, treat unexpected execution as possible full developer-host compromise. Transport isolation cannot reduce that host-level consequence after arbitrary administrator code executes.

## 12. Rollback

Rollback:

1. disable/remove the ChatGPT Ops connection;
2. stop/delete the Ops background task/service;
3. stop the Ops tunnel runtime;
4. revoke the dedicated Ops runtime key;
5. remove/disable the local Ops policy if no longer used;
6. keep the Context MCP/tunnel unchanged.

Do not move Ops capabilities into the Context MCP as a rollback shortcut.
