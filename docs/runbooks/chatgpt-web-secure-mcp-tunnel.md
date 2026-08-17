# ChatGPT Web Secure MCP Tunnel Runbook

## Scope

This runbook connects ChatGPT Web to the HooshiX Git-native Context Engine on an always-on Windows developer PC through OpenAI Secure MCP Tunnel.

This is developer tooling only. It does not expose HooshiX production services and does not create a production dependency.

Authoritative architecture decisions:

- `docs/adr/0046-adopt-git-native-agent-context-engine-v1.md`
- `docs/adr/0047-adopt-openai-secure-mcp-tunnel-for-chatgpt-web-context-access-v1.md`

## 1. Security model

The approved path is:

```text
ChatGPT Web
-> OpenAI Secure MCP Tunnel
-> tunnel-client on Windows
-> HooshiX scripts/context/mcp_server.py over stdio
-> Git-native Context Engine
-> dedicated HooshiX checkout
```

Do not:

- expose `mcp_server.py` on a public port;
- add a public HTTP/SSE MCP wrapper;
- add router port-forwarding for MCP;
- add `shell.run`, arbitrary filesystem, arbitrary URL fetch, Git write, deployment, credential-read, or production tools;
- paste OpenAI runtime/admin API keys into ChatGPT;
- commit tunnel credentials to Git;
- use an OpenAI admin key for the long-lived runtime daemon.

The HooshiX MCP tool list remains exactly:

```text
project.bootstrap
project.context_for_task
project.search
project.latest_checkpoint
project.changed_context
```

## 2. Prerequisites

Required on the always-on Windows host:

- Windows 11 Pro or another reviewed supported Windows host;
- Git;
- Python 3 capable of running the repository Context Engine;
- a dedicated local HooshiX checkout;
- OpenAI `tunnel-client` v0.0.11;
- a tunnel created in the ChatGPT/OpenAI tunnel-management UI;
- a restricted OpenAI runtime API key with Tunnels `Read` + `Use`.

Actual installed state is evidence. Documentation does not prove these prerequisites are present.

## 3. Create a dedicated Context Engine checkout

Use a checkout that is not the normal edit/work-in-progress directory. Example only:

```powershell
New-Item -ItemType Directory -Force D:\HooshiXContext | Out-Null
Set-Location D:\HooshiXContext
git clone https://github.com/hasanjodatshandi/HooshiX.git repo
Set-Location .\repo
git switch main
git pull --ff-only origin main
```

Before starting the tunnel for trusted targeted review, verify the checkout is on the intended state:

```powershell
git status --short
git rev-parse HEAD
python .\scripts\context\context_engine.py verify
python .\scripts\context\context_engine.py bootstrap
```

Expected safety condition:

```text
Git status: no unexpected changes
bootstrap verification: valid
trusted_for_targeted_review: true
```

If these conditions are not true, do not describe the tunnel context as verified targeted-review context.

The MCP server itself does not perform `git pull`, `checkout`, `reset`, or other Git mutation. Synchronization stays an explicit operator action.

## 4. Install and verify tunnel-client

ADR-0047 pins OpenAI `tunnel-client` v0.0.11 for the initial integration.

Select the official archive for the actual Windows architecture:

```text
tunnel-client-v0.0.11-windows-amd64.zip
tunnel-client-v0.0.11-windows-arm64.zip
```

Release asset SHA-256 values reviewed on 2026-08-18:

```text
windows-amd64:
eb912c86c6ccde90cda805cb17009507176a656725cf86c36fabe1901a12e29b

windows-arm64:
38f015a720404c8ccd5976a0d6aed18d931899697eaf208548b5eb3d0f6e8592
```

Verify the downloaded archive against the official release-published `SHA256SUMS.txt` / asset digest before extracting it. Example for amd64 after placing the archive in the current directory:

```powershell
(Get-FileHash .\tunnel-client-v0.0.11-windows-amd64.zip -Algorithm SHA256).Hash.ToLowerInvariant()
```

The result must exactly match the reviewed official digest. Do not continue on mismatch.

After extraction:

```powershell
.\tunnel-client.exe help quickstart
.\tunnel-client.exe help doctor
.\tunnel-client.exe profiles samples list
```

Use the binary's current help as the exact CLI contract if a future reviewed version changes syntax.

## 5. Create/select the tunnel in ChatGPT Web

In ChatGPT Web:

```text
Settings
-> Plugins
-> New Plugin
-> Connection: Tunnel
-> Create tunnel
```

Create a tunnel for HooshiX context access. Record the returned `tunnel_id` locally. The runtime daemon and ChatGPT Plugin must use the same tunnel.

The tunnel ID is configuration, not the runtime API credential.

## 6. Create the runtime API key

Create a dedicated **Restricted** OpenAI runtime API key with only:

```text
Tunnels: Read
Tunnels: Use
```

Do not select broad `All` access when the restricted tunnel permissions are sufficient.

Do not use `OPENAI_ADMIN_KEY` for `doctor` or the long-lived `run` daemon. Admin credentials are only for explicit tunnel-management CRUD.

The official tunnel-client configuration supports secret-bearing values through `env:VARNAME` or `file:/path/to/secret` references. Prefer one of these references so the real API key is not placed in argv or profile YAML.

For an interactive foreground test, load the environment variable without typing the secret as part of a PowerShell command line. One Windows PowerShell-compatible pattern is:

```powershell
$secure = Read-Host 'CONTROL_PLANE_API_KEY' -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
  $env:CONTROL_PLANE_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
} finally {
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
}
```

The key is then present only in the current process environment for this test. Clear it when finished:

```powershell
Remove-Item Env:CONTROL_PLANE_API_KEY -ErrorAction SilentlyContinue
```

For persistent operation, prefer the tunnel-client supported `file:` or protected secret-reference mechanism rather than storing a plaintext literal in a startup command, scheduled-task argument, profile, or Git file.

Do not store the real key in this repository, shell transcripts, screenshots, logs, support bundles, or ChatGPT messages.

## 7. Configure the stdio MCP profile

Use the official stdio sample and an absolute path to the HooshiX MCP entry point.

First inspect the installed binary's sample/help:

```powershell
.\tunnel-client.exe profiles samples list
.\tunnel-client.exe help quickstart
```

Then initialize a dedicated profile. Replace placeholders locally with the real tunnel ID, Python executable, and repository path:

```powershell
.\tunnel-client.exe init `
  --sample sample_mcp_stdio_local `
  --profile hooshix-context `
  --tunnel-id <TUNNEL_ID> `
  --mcp-command "<PYTHON_EXE> -B <ABSOLUTE_REPO_PATH>\scripts\context\mcp_server.py"
```

`-B` prevents Python bytecode cache files from dirtying the dedicated authority checkout. If either local path contains spaces, use the quoting form shown by the installed `tunnel-client` help/profile sample rather than guessing a different command encoding. On Windows, validate the generated command with `doctor`; if the installed parser does not preserve a backslash-form path, use the equivalent absolute forward-slash path form accepted by the installed Python executable rather than weakening the checkout or moving the MCP server.

The generated profile must keep `control_plane.api_key` as an `env:` or `file:` secret reference. Do not replace it with a literal key.

The HooshiX MCP entry point resolves the repository root from its own script path. The tunnel-client service working directory is therefore not repository authority.

For a host where TCP 8080 is already in use, keep the health/admin surface on loopback and select a free loopback port. The reviewed v0.0.11 client supports an ephemeral listener such as `127.0.0.1:0`; do not resolve a port conflict by binding the health/admin surface to `0.0.0.0` or another LAN/public address.

## 8. Validate before connecting ChatGPT

Run:

```powershell
.\tunnel-client.exe doctor --profile hooshix-context --explain
```

Resolve all material errors before continuing.

Start the foreground runtime:

```powershell
.\tunnel-client.exe run --profile hooshix-context
```

Keep this terminal open for the first validation.

`tunnel-client` exposes local operator health/readiness/UI surfaces. Use the URL/address reported by the binary. Verify:

```text
/healthz -> HTTP 200
/readyz  -> HTTP 200
/ui      -> active tunnel and MCP target are healthy
```

The local operator surface must remain loopback-only unless a separate reviewed change says otherwise.

## 9. Connect the ChatGPT Plugin

While the foreground tunnel runtime is healthy:

1. return to `Settings -> Plugins -> New Plugin`;
2. set `Connection` to `Tunnel`;
3. refresh/select the same HooshiX tunnel;
4. use no downstream OAuth/authentication for the current local stdio MCP when the UI offers `None`/no authentication;
5. complete the custom Plugin setup;
6. in a new/appropriate HooshiX chat, enable/use the Plugin.

The runtime API key authenticates tunnel-client to the OpenAI control plane. It is not a downstream MCP application credential and must not be pasted into the Plugin authentication field.

The first functional check is tool discovery. Exactly five HooshiX tools must be visible.

Then call/use:

```text
project.bootstrap
```

Verify the response reports the expected repository, branch/HEAD, authority provenance, dirty state, and bootstrap trust result.

Next verify a routed task through:

```text
project.context_for_task
```

Successful object-shaped HooshiX tool results carry matching JSON text and structured object content. A client may display only one representation; the two representations must encode the same object. This compatibility shape does not add a tool or expose additional repository data.

Do not claim end-to-end tunnel integration is `Passed` until these real ChatGPT Web calls complete successfully.

## 10. Persistent operation on the always-on PC

Do not configure automatic startup until the foreground path passes.

For persistent operation, preserve these invariants:

- same reviewed tunnel-client binary/version;
- same dedicated profile and tunnel ID;
- same dedicated clean HooshiX checkout;
- runtime key supplied through a protected supported `env:` or `file:` secret reference, not a command-line literal or Git file;
- service account/user has only the filesystem/network permissions needed for the checkout and outbound tunnel;
- no public inbound firewall rule is added;
- tunnel-client local health/admin surface remains loopback-only;
- startup/restart failure remains visible through local health/readiness and does not silently fall back to a public listener.

Use the persistent-runtime mechanism documented by the installed OpenAI tunnel-client version. Do not invent background wrappers that lose health/readiness ownership.

## 11. Normal operating procedure

Before relying on the tunnel for a new non-trivial engineering session:

1. synchronize the dedicated checkout explicitly with reviewed `main` using normal operator Git workflow;
2. verify the checkout is clean;
3. run Context Engine verification/bootstrap locally when practical;
4. verify tunnel-client `/readyz`;
5. call `project.bootstrap` from ChatGPT Web;
6. route the task before selecting targeted documentation scope;
7. treat current Git as higher authority than checkpoint/chat/model memory.

If `project.bootstrap` reports dirty/invalid authority state, do not force targeted review.

## 12. Troubleshooting

### Tunnel exists but is not selectable in ChatGPT

Check:

- ChatGPT/Platform workspace scope;
- operator Tunnels `Read` + `Use` permission;
- tunnel-client is still running;
- local `/readyz` is healthy;
- the same tunnel ID is configured on both sides;
- allow time for newly created control-plane configuration to propagate.

### `doctor` reports missing runtime key

Provide `CONTROL_PLANE_API_KEY` through the approved local `env:`/`file:` secret reference with the restricted runtime key. Do not substitute an admin key and do not paste the key into ChatGPT.

### `doctor` reports the health listener address is already in use

Keep the listener on `127.0.0.1` and select another free loopback port or the supported ephemeral `127.0.0.1:0` form. Do not bind the operator surface to all interfaces as a workaround.

### MCP child fails to start

Check:

- Python executable path;
- absolute `mcp_server.py` path;
- repository checkout contains `context/bootstrap.json`;
- local Python can import/run the sibling Context Engine;
- tunnel-client profile quoting as shown by the installed binary's help.

Do not fix this by exposing a public MCP server.

### Tool discovery works but a tool response fails at the control-plane response boundary

If tunnel-client logs an HTTP 400 response-post/body-parsing error after forwarding a tool call:

1. preserve the bounded error/request identifiers without copying credentials;
2. run the repository MCP tests and verify the exact five-tool surface;
3. verify the raw stdio JSON-RPC response is valid JSON;
4. use the tunnel-client version's official embedded/minimal stdio test path to separate tunnel/control-plane transport from HooshiX MCP result behavior;
5. confirm object-shaped HooshiX successes contain matching JSON `content` and `structuredContent`;
6. do not add a public HTTP MCP wrapper or broaden authentication/tool authority to bypass the failure.

A passing embedded or minimal stdio tool call proves the general tunnel/control-plane/stdio path for that test. It does not by itself prove every HooshiX tool result. Retest `project.bootstrap` and `project.context_for_task` through ChatGPT Web after the repository fix is synchronized.

### Bootstrap reports stale/dirty context

Stop treating the context as targeted-review trusted. Inspect/synchronize the dedicated checkout using explicit operator Git actions. Restart/recheck the tunnel after the repository is in the intended state.

## 13. Incident and credential handling

If the runtime key may have been disclosed:

1. revoke/rotate it immediately through the approved OpenAI key-management surface;
2. stop the affected tunnel runtime;
3. inspect local process/log/history exposure without copying the secret into a ticket/chat/log;
4. create a new restricted runtime key;
5. rerun `doctor --explain` and `/readyz` before restoring use.

If unexpected MCP tools appear, stop the runtime and treat it as a configuration/integrity issue. HooshiX accepts only the five ADR-0046 read-only tools.

## 14. Rollback

To roll back ChatGPT Web tunnel access:

1. disable/remove the custom HooshiX Plugin connection;
2. stop the tunnel-client runtime;
3. revoke the dedicated runtime key if no longer needed;
4. retain local stdio Context Engine and GitHub/repository fallback behavior.

Rollback must not replace the tunnel with public MCP exposure or a general remote shell.
