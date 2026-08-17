# ChatGPT Web Secure MCP Tunnel Runbook

## Scope

This runbook connects ChatGPT Web to the HooshiX Git-native Context Engine on an approved always-on Windows developer PC through OpenAI Secure MCP Tunnel.

This is developer tooling only. It does not expose HooshiX production services and does not create a production dependency.

Authoritative architecture decisions:

- `docs/adr/0046-adopt-git-native-agent-context-engine-v1.md`
- `docs/adr/0047-adopt-openai-secure-mcp-tunnel-for-chatgpt-web-context-access-v1.md`

## 1. Security and authority invariants

The approved path is:

```text
ChatGPT Web
-> OpenAI Secure MCP Tunnel
-> tunnel-client on Windows
-> HooshiX scripts/context/mcp_server.py over stdio
-> Git-native Context Engine
-> dedicated HooshiX checkout
```

The HooshiX MCP tool list remains exactly:

```text
project.bootstrap
project.context_for_task
project.search
project.latest_checkpoint
project.changed_context
```

Do not:

- expose `mcp_server.py` on a public port;
- add a public HTTP/SSE/WebSocket MCP wrapper;
- add router port-forwarding or an inbound Internet firewall rule for MCP;
- add `shell.run`, arbitrary filesystem, arbitrary URL fetch, Git write, deployment, credential-read, or production tools;
- paste OpenAI runtime/admin API keys into ChatGPT;
- commit tunnel credentials, secret-bearing profiles, or host-local startup configuration to Git;
- use an OpenAI admin key for the long-lived runtime daemon;
- make a successful tunnel connection evidence that the local checkout is current;
- automatically `reset`, `merge`, `checkout`, or otherwise rewrite the dedicated checkout to hide repository drift.

Current Git remains authority. ChatGPT conversation state, model memory, checkpoint prose, and tunnel availability never outrank the exact repository revision returned by the Context Engine.

## 2. Evidence states

Repository evidence can prove the MCP code contract, tests, documentation, and reviewed developer-tool pin. It cannot prove the operator PC is correctly configured.

Treat these as separate checks:

```text
Repository-side MCP contract       -> repository/CI evidence
Dedicated checkout currentness     -> operator-host Git evidence
Tunnel-client installation/digest  -> operator-host evidence
Runtime key permissions            -> OpenAI/host evidence
/readyz and tunnel connectivity    -> operator-host runtime evidence
ChatGPT app tool discovery         -> real ChatGPT Web evidence
project.bootstrap through ChatGPT  -> end-to-end evidence
```

Do not report the last six as `Passed` until they are actually executed in the operator environment.

## 3. Prerequisites

Required on the approved Windows host:

- Windows 11 Pro or another separately reviewed supported Windows host;
- Git;
- Python 3 capable of running the repository Context Engine;
- a dedicated local HooshiX checkout that is not the normal edit/work-in-progress directory;
- OpenAI `tunnel-client` v0.0.11 under the current ADR-0047 pin;
- a Secure MCP Tunnel created/selected in the applicable OpenAI/ChatGPT management surface;
- a dedicated restricted OpenAI runtime API key with only the tunnel runtime permissions required by ADR-0047.

Actual installed state is evidence. Documentation is not proof of installation or permission state.

## 4. Dedicated Context Engine checkout

Example only:

```powershell
New-Item -ItemType Directory -Force D:\HooshiXContext | Out-Null
Set-Location D:\HooshiXContext
git clone https://github.com/hasanjodatshandi/HooshiX.git repo
Set-Location .\repo
git switch main
git pull --ff-only origin main
```

The tunnel checkout is a read source, not the operator's normal feature branch.

Before trusted use, verify:

```powershell
git -C D:\HooshiXContext\repo status --short
git -C D:\HooshiXContext\repo rev-parse HEAD
python D:\HooshiXContext\repo\scripts\context\context_engine.py verify
python D:\HooshiXContext\repo\scripts\context\context_engine.py bootstrap
```

Expected safety condition:

```text
Git status: no unexpected changes
bootstrap verification: valid
trusted_for_targeted_review: true
```

Important: `project.bootstrap` verifies the local checkout and its authority inputs. By itself it does **not** contact GitHub or prove that local `HEAD` still equals the latest `origin/main`. Repository freshness is a separate startup/session gate below.

## 5. Install and verify tunnel-client

ADR-0047 pins OpenAI `tunnel-client` v0.0.11 for this integration until a later reviewed change updates it.

Select the official archive for the actual Windows architecture:

```text
tunnel-client-v0.0.11-windows-amd64.zip
tunnel-client-v0.0.11-windows-arm64.zip
```

Repository-reviewed SHA-256 values recorded for the ADR-0047 integration are:

```text
windows-amd64:
eb912c86c6ccde90cda805cb17009507176a656725cf86c36fabe1901a12e29b

windows-arm64:
38f015a720404c8ccd5976a0d6aed18d931899697eaf208548b5eb3d0f6e8592
```

At installation/upgrade time, compare the downloaded archive against the official release-published integrity metadata rather than relying only on a copied value in this runbook.

Example:

```powershell
(Get-FileHash .\tunnel-client-v0.0.11-windows-amd64.zip -Algorithm SHA256).Hash.ToLowerInvariant()
```

Do not continue on mismatch.

After extraction:

```powershell
.\tunnel-client.exe help quickstart
.\tunnel-client.exe help doctor
.\tunnel-client.exe profiles samples list
```

The installed binary's help is the exact CLI contract for that reviewed version.

## 6. Runtime credential and local secret storage

Use a dedicated **restricted runtime API key**, not an OpenAI admin key, for the long-lived daemon. ADR-0047 currently requires only:

```text
Tunnels: Read
Tunnels: Use
```

Use the tunnel-client supported `env:VARNAME`, `file:/path/to/secret`, or equivalent reviewed secret-reference mechanism. The real key must not be a literal in:

- Git;
- profile YAML committed to Git;
- Task Scheduler arguments;
- shell history/transcripts;
- screenshots;
- logs/support bundles;
- ChatGPT messages.

For the first interactive foreground test, one PowerShell-compatible pattern is:

```powershell
$secure = Read-Host 'CONTROL_PLANE_API_KEY' -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
  $env:CONTROL_PLANE_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
} finally {
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
}
```

Clear it after the foreground test:

```powershell
Remove-Item Env:CONTROL_PLANE_API_KEY -ErrorAction SilentlyContinue
```

For persistent operation, keep secret-bearing files and tunnel-local profile state **outside the repository**, under a host-local directory readable only by the account that runs the tunnel. Review the Windows ACL after creation. `.gitignore` is convenience only; it is not a secret-control boundary.

If a key may have been exposed, revoke/rotate it before restarting the tunnel.

## 7. Configure the stdio MCP profile

Inspect the installed sample/help first:

```powershell
.\tunnel-client.exe profiles samples list
.\tunnel-client.exe help quickstart
```

Initialize a dedicated profile using the official stdio-local sample pattern. Replace placeholders locally:

```powershell
.\tunnel-client.exe init `
  --sample sample_mcp_stdio_local `
  --profile hooshix-context `
  --tunnel-id <TUNNEL_ID> `
  --mcp-command "<PYTHON_EXE> <ABSOLUTE_REPO_PATH>\scripts\context\mcp_server.py"
```

If paths contain spaces, follow the quoting form shown by the installed tunnel-client version rather than inventing another command encoding.

Required profile properties:

- MCP child transport is stdio;
- MCP command points to the absolute tracked `scripts/context/mcp_server.py`;
- `control_plane.api_key` or equivalent remains an `env:`/`file:` reference, never a literal;
- local health/admin surfaces remain loopback-only;
- no arbitrary port forwarding or local shell target is added.

The MCP entry point resolves repository authority from its tracked script location, not the tunnel-client working directory.

## 8. Repository freshness gate before startup

An always-on tunnel can be healthy while serving an old but clean checkout. Therefore **tunnel readiness and Git freshness are independent conditions**.

Before starting/restarting the tunnel for trusted engineering use, update only the remote-tracking reference and fail closed if local `HEAD` is not the reviewed `origin/main`:

```powershell
$repo = 'D:\HooshiXContext\repo'
$python = '<PYTHON_EXE>'

& git -C $repo fetch --prune origin main
if ($LASTEXITCODE -ne 0) { throw 'git fetch origin main failed' }

$head = (& git -C $repo rev-parse HEAD).Trim()
$remote = (& git -C $repo rev-parse refs/remotes/origin/main).Trim()
if ($LASTEXITCODE -ne 0 -or -not $remote) { throw 'origin/main cannot be resolved' }
if ($head -ne $remote) {
  throw "Dedicated checkout is stale: HEAD=$head origin/main=$remote. Synchronize explicitly with git pull --ff-only after review."
}

$dirty = & git -C $repo status --porcelain=v1
if ($LASTEXITCODE -ne 0) { throw 'git status failed' }
if ($dirty) { throw 'Dedicated checkout is dirty; tunnel startup refused' }

& $python "$repo\scripts\context\context_engine.py" verify
if ($LASTEXITCODE -ne 0) { throw 'Context Engine verification failed' }

$bootstrap = (& $python "$repo\scripts\context\context_engine.py" bootstrap | ConvertFrom-Json)
if ($LASTEXITCODE -ne 0) { throw 'Context Engine bootstrap failed' }
if (-not $bootstrap.verification.trusted_for_targeted_review) {
  throw 'Context bootstrap is not trusted for targeted review'
}
```

This preflight deliberately does **not** run `reset`, `merge`, `checkout`, `rebase`, or `pull`. A stale checkout stops startup and requires an explicit operator-reviewed fast-forward synchronization. The MCP surface itself remains Git-read-only and network-free.

If the host cannot reach `origin/main`, do not pretend freshness was proven. Either restore connectivity and run the gate or treat targeted current-state use as not verified.

## 9. Foreground validation before persistence

Run:

```powershell
.\tunnel-client.exe doctor --profile hooshix-context --explain
```

Resolve material errors before continuing.

Start in the foreground:

```powershell
.\tunnel-client.exe run --profile hooshix-context
```

Use the loopback URL/address reported by the installed binary and verify its local operator surfaces, including the version-appropriate equivalents of:

```text
/healthz -> healthy
/readyz  -> ready
/ui      -> expected tunnel and MCP target healthy
```

Do not add a public/LAN bind merely to make these checks easier.

## 10. Configure the custom app in ChatGPT Web

OpenAI's current ChatGPT developer-mode workflow uses **Apps**, not the older `Plugins -> New Plugin` creation path.

Use the current workspace/user flow exposed for the account, generally:

```text
Workspace settings or Settings
-> Apps
-> Create
```

Enable Developer mode if the plan/workspace requires it. Choose the Secure MCP Tunnel/Tunnel connection option when offered, select the same tunnel used by the runtime, then **Scan Tools** before creating/publishing the app.

The scan must expose exactly these five tools and no others:

```text
project.bootstrap
project.context_for_task
project.search
project.latest_checkpoint
project.changed_context
```

All five are read-only. On workspace plans that expose action controls, keep only the intended read actions enabled.

After changing MCP tool definitions, do not assume ChatGPT automatically adopts the new action schema. Refresh/rescan/review the app actions according to the current ChatGPT workspace controls before relying on the changed definition.

## 11. End-to-end validation

With the tunnel ready and the custom app enabled, execute real ChatGPT calls in this order:

1. tool discovery: exactly five tools;
2. `project.bootstrap`;
3. `project.context_for_task` for a known task;
4. `project.search` for a term known to produce repository matches;
5. optionally `project.latest_checkpoint` and `project.changed_context` for bounded validation.

Verify `project.bootstrap` reports the same `HEAD` that passed the startup freshness gate.

For `project.search`, verify a **positive-result** call succeeds through the real ChatGPT tunnel path. A no-match search is not sufficient reliability evidence.

If discovery succeeds but positive-result `project.search` fails, record it as a tunnel/client/MCP integration failure. Do not work around it by adding broad filesystem or shell tools.

## 12. Persistent Windows startup

Configure persistence only after Sections 8-11 pass in the foreground.

The persistent mechanism must preserve:

- the same reviewed tunnel-client binary/version;
- the same dedicated profile/tunnel ID;
- the same dedicated checkout;
- startup freshness gate before tunnel execution;
- runtime key through a protected supported secret reference;
- least-privilege local user/service account;
- outbound-only tunnel networking;
- loopback-only local operator surfaces;
- visible non-zero failure when Git freshness, Context verification, credential loading, `doctor`, or tunnel runtime fails;
- no concurrent duplicate tunnel-client instance for the same profile.

For Windows Task Scheduler or another reviewed host startup mechanism, configure it to run only after networking is available and to restart the task on unexpected process failure with a bounded retry policy. The action must execute the freshness/verification preflight before `tunnel-client run` and must not place the runtime API key in its command line.

Do not configure automatic startup to run `git reset --hard`, forced checkout, merge, rebase, or an unconditional pull. Repository movement must remain explicit and reviewable.

Persistent host startup remains `NOT VERIFIED` until the real startup/reboot/restart behavior is exercised on the operator PC.

## 13. Normal operating procedure

At the start of a non-trivial engineering session, and after any known `main` update that should become visible through the tunnel:

1. run/follow the repository freshness gate;
2. if stale, review and run `git pull --ff-only origin main` explicitly, then repeat the gate;
3. verify tunnel `/readyz`;
4. call `project.bootstrap` from ChatGPT Web;
5. verify the reported `HEAD` equals the revision accepted by the freshness gate;
6. route the task with `project.context_for_task` before selecting targeted scope;
7. use `project.search` only as bounded retrieved data with provenance;
8. keep current Git above checkpoint/chat/model memory.

A long-lived process is not proof of a long-lived fresh checkout.

## 14. Troubleshooting

### Tunnel/app exists but is not selectable

Check:

- account/workspace plan and Developer mode availability;
- workspace Apps permissions/access;
- the tunnel-client is running;
- local readiness is healthy;
- the same tunnel is selected on both sides;
- the app has been created/enabled for the current user/workspace;
- tool actions were scanned/refreshed after any definition change.

### `doctor` reports missing runtime key

Provide the restricted runtime key through the approved local `env:`/`file:` reference. Do not substitute an admin key and do not paste the key into ChatGPT.

### MCP child fails to start

Check:

- Python executable path;
- absolute `mcp_server.py` path;
- repository contains `context/bootstrap.json`;
- local Python can run the sibling Context Engine;
- tunnel-client profile quoting matches the installed binary's help.

Do not fix this by exposing a public MCP server.

### Bootstrap is valid but the repository may be stale

This is expected behavior: bootstrap proves local authority consistency, not remote freshness. Run the Section 8 fetch/compare gate and require `HEAD == origin/main` for trusted current-state use.

### Positive `project.search` fails through ChatGPT

Check separately:

1. local Context Engine search behavior;
2. local stdio MCP modern `tools/call` behavior;
3. tunnel-client health/readiness;
4. ChatGPT app action/tool snapshot;
5. real ChatGPT call again after a tool refresh if the server definition changed.

Do not infer that a no-result search proves the positive-result path works.

### Unexpected MCP tools appear

Stop the runtime and treat this as a configuration/integrity incident. HooshiX accepts only the five ADR-0046 read-only tools.

## 15. Credential/integrity incident handling

If the runtime key may have been disclosed:

1. revoke/rotate it immediately through the approved OpenAI key-management surface;
2. stop the affected tunnel runtime;
3. inspect local process/log/history exposure without copying the secret into a ticket/chat/log;
4. create a new restricted runtime key;
5. re-run startup preflight, `doctor --explain`, readiness, and ChatGPT discovery before restoring use.

If the tunnel-client archive/digest is unexpected, stop and reinstall only from the reviewed official release artifact after integrity verification.

If the dedicated checkout is dirty or diverged, stop treating it as trusted context and resolve Git state explicitly. Do not force-clean it as part of tunnel startup.

## 16. Rollback

To roll back ChatGPT Web tunnel access:

1. disable/remove the custom HooshiX app connection;
2. stop/disable the tunnel-client runtime/startup task;
3. revoke the dedicated runtime key if no longer needed;
4. retain local stdio Context Engine and Git/repository fallback behavior.

Rollback must not replace the tunnel with public MCP exposure, a general remote shell, or a write-capable MCP surface.

## 17. Required operational evidence checklist

Before calling the integration operationally verified, retain non-secret evidence for:

- [ ] tunnel-client version and official integrity verification;
- [ ] restricted runtime credential scope;
- [ ] dedicated checkout clean state;
- [ ] fetched `origin/main` and exact `HEAD == origin/main` comparison;
- [ ] Context Engine `verify` success;
- [ ] bootstrap `trusted_for_targeted_review=true`;
- [ ] `doctor --explain` success;
- [ ] local readiness success and loopback-only binding;
- [ ] ChatGPT Apps tool scan showing exactly five read-only tools;
- [ ] ChatGPT `project.bootstrap` returning the expected revision;
- [ ] ChatGPT positive-result `project.search` succeeding;
- [ ] persistent startup/reboot/restart behavior tested, if persistence is enabled.

Never include the runtime/admin API key itself in evidence.
