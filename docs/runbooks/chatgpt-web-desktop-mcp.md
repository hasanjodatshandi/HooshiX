# ChatGPT Web HooshiX Desktop MCP Runbook

## Scope

This runbook connects ChatGPT Web to the separate HooshiX **developer-host Desktop MCP** on Windows through OpenAI Secure MCP Tunnel.

Desktop MCP is interactive developer tooling. It is not a production-administration path and does not replace Context or Ops.

Authoritative decisions:

- ADR-0047 for the approved Secure MCP Tunnel transport pattern;
- ADR-0048 for the separate Ops authority boundary;
- ADR-0049 for Desktop UI observation/input authority;
- ADR-0050 for optional policy-bound local credential use without credential disclosure.

## 1. Trust model

```text
ChatGPT Web
-> dedicated OpenAI Secure MCP Tunnel
-> dedicated tunnel-client Desktop profile on Windows
-> scripts/desktop/mcp_server.py over stdio
-> protected local Desktop policy
-> pinned Microsoft WinApp CLI
-> optional fixed local Credential Manager helper under ADR-0050
-> current unlocked interactive Windows desktop
```

Keep Context, Ops, and Desktop profiles/tunnels/keys/processes separate.

Do not:

- add Desktop tools to Context or Ops;
- run Desktop as an administrator shell merely to bypass UI/input restrictions;
- automate UAC/Secure Desktop/Winlogon/workstation lock/Secure Attention Sequence;
- expose Desktop MCP on a public HTTP/SSE/TCP port;
- add a credential reader/list/writer/export tool, general secret-input tool, clipboard-read, arbitrary-command, or network-fetch tool;
- pass passwords, API keys, private keys, OTPs, recovery codes, cookies/session values, or other secrets through `desktop.type_text` or any MCP argument;
- put tunnel keys in Git, policy, argv, logs, screenshots, or ChatGPT content.

## 2. Install and verify WinApp CLI

Current reviewed local pin:

```text
WinGet ID: Microsoft.WinAppCli
Version: 0.6.0
x64 MSIX SHA-256: dc5d323f6d1601ef3342420746f0163651176f4cc183690f0354546a36648eec
```

Inspect the source before install:

```powershell
winget show --id Microsoft.WinAppCli --exact --source winget --accept-source-agreements --disable-interactivity
```

Install the exact reviewed version:

```powershell
winget install `
  --id Microsoft.WinAppCli `
  --exact `
  --version 0.6.0 `
  --source winget `
  --silent `
  --accept-package-agreements `
  --accept-source-agreements `
  --disable-interactivity
```

Verify:

```powershell
winapp --version
(Get-Command winapp).Source
```

Expected version is exactly `0.6.0`. Review a new version before changing the baseline/policy.

Desktop MCP sets `WINAPP_CLI_TELEMETRY_OPTOUT=1` for WinApp child invocations.

## 3. Repository verification

Before host integration:

```powershell
Set-Location D:\HooshiXContext\repo
python -B -m unittest discover -s .\scripts\desktop\tests -p "test_*.py" -v
python -B -m unittest discover -s .\scripts\ops\tests -p "test_*.py" -v
python -B -m unittest discover -s .\scripts\context\tests -p "test_*.py" -v
python -B .\scripts\context\context_engine.py verify
python -B .\scripts\baseline\verify_repository.py
```

Context must remain exactly five read-only tools. Ops must remain its reviewed separate surface.

## 4. Create protected local Desktop policy

Use a local path outside Git, for example:

```text
C:\ProgramData\HooshiX\desktop\policy.json
```

Start from:

```text
desktop/policy.example.json
```

Use the actual absolute WinApp alias path and expected `0.6.0` version.

Recommended initial policy keeps `allow_all_apps=false`, uses a small app allow-list, denies secure/credential broker process names, requires an interactive non-elevated process, enables screenshot/UIA/mouse/keyboard only as needed, keeps system keys/capture-screen disabled until explicitly required, and keeps `allow_credential_input=false` with no bindings until a specific application login is reviewed.

`allow_all_apps=true` is broad ordinary-desktop authority. `denied_apps` still wins. It does not create Secure Desktop authority.

### ACLs

Protect policy/audit state and capture temporary directory for only the intended interactive account plus SYSTEM/Administrators as needed for operator recovery. Do not grant ordinary unrelated users or `Everyone`.

Example locations:

```text
C:\ProgramData\HooshiX\desktop\policy.json
C:\ProgramData\HooshiX\desktop\audit.ndjson
%LOCALAPPDATA%\HooshiX\desktop-capture\
```

Capture PNG files are temporary and are deleted by Desktop MCP after bounded readback.

### 4.1 Optional ADR-0050 credential broker

Keep the broker disabled unless a specific developer-host application login needs unattended credential use. Existing policies that omit the ADR-0050 fields keep the broker disabled.

A reviewed binding has this shape:

```json
{
  "allow_credential_input": true,
  "credential_bindings": [
    {
      "credential_id": "example-login",
      "app": "exampleapp",
      "executable_path": "C:\\Program Files\\Example\\exampleapp.exe",
      "executable_sha256": "<64-hex-sha256>",
      "target_selector": "@unique-password",
      "credential_target": "HooshiX/Desktop/example-login"
    }
  ]
}
```

Use the real normalized process name from `desktop.list_windows`. Resolve the exact executable path for that PID and calculate SHA-256 from the executable file, then store both in the protected local binding. A binary update changes the hash and must fail closed until the binding is reviewed and updated. Prefer a stable semantic selector confirmed through `desktop.inspect`. If the password control has no stable AutomationId and the target window has exactly one enabled/visible password control, use the reserved `@unique-password` strategy instead of copying a transient numeric AutomationId. Do not use a coordinate-only selector. The fixed helper always requires UI Automation password semantics and HWND/PID continuity before credential resolution.

Provision the password **locally under the same interactive Windows account that runs Desktop MCP** as a Windows Credential Manager Generic Credential whose target exactly matches `credential_target`. Enter the real secret only through the trusted local Windows credential-management UI/path. Do not paste the secret into ChatGPT, PowerShell command arguments, repository files, policy JSON, logs, or MCP tools. Desktop MCP intentionally has no credential enrollment/list/read/delete API.

V1 expects that Generic Credential blob to represent the password as UTF-16LE and rejects unsupported/oversized blobs. First validate the host path with a disposable credential and disposable/password-capable target. Do not enable a valuable credential until wrong-window/focus-change negatives and audit/process redaction are verified.

The Windows user account remains part of the trust boundary. ADR-0050 prevents the ChatGPT/MCP/Python path from receiving the value; it does not claim to protect the credential from malware or compromise running with the same Windows-user authority.

To disable immediately, set `allow_credential_input=false`, set `credential_bindings=[]`, restart Desktop MCP, and confirm `desktop.status` reports the broker disabled with zero bindings.

## 5. Direct stdio validation

Run from a **normal non-elevated interactive PowerShell** when policy requires `require_non_elevated=true`:

```powershell
python -B D:\HooshiXContext\repo\scripts\desktop\mcp_server.py `
  --policy C:\ProgramData\HooshiX\desktop\policy.json
```

The process waits on stdio. Normal use is through the tunnel or an MCP inspector.

Startup must fail when policy is absent/invalid, the WinApp version does not match, the session is not interactive when required, or the process is elevated when non-elevated is required.

## 6. Expected Desktop tools

Exactly:

```text
desktop.status
desktop.audit_tail
desktop.list_windows
desktop.inspect
desktop.screenshot
desktop.invoke
desktop.focus
desktop.click
desktop.hover
desktop.drag
desktop.type_text
desktop.use_credential
desktop.key_press
```

There is no arbitrary WinApp command, process shell, filesystem browser, clipboard read, `get-value`, recording, touch, pen, credential reader/list/writer/export, general secret-input tool, or network fetch. `desktop.use_credential` is use-without-disclosure only and cannot return a credential value.

First call `desktop.status`, then `desktop.list_windows`.

Use fresh HWND values. Desktop MCP revalidates the current HWND/process against policy before every targeted operation.

## 7. Safe functional smoke

Use a disposable non-sensitive app such as Notepad.

Recommended sequence:

1. list allowed windows;
2. inspect one Notepad HWND;
3. take one bounded window screenshot;
4. focus the document/editor element;
5. type a non-secret marker;
6. click/invoke a harmless UI element;
7. inspect/screenshot again;
8. close/discard disposable content manually or through a harmless reviewed action;
9. inspect `desktop.audit_tail` and local audit.

Verify audit does not contain raw typed text, raw selectors, window titles, screenshot bytes/base64, or WinApp raw output. Verify the capture temporary directory is empty after successful screenshot readback.

## 8. Input rules

Prefer:

```text
inspect -> semantic selector -> invoke/focus/set-like action
```

over coordinate behavior.

V1 click/hover/drag accept semantic selectors only. Caller-selected arbitrary screen coordinates are not exposed.

`desktop.type_text` is bounded non-secret text. WinApp is used only to focus an optional semantic target; literal text then goes through the fixed isolated repository helper `scripts/desktop/windows_text_input_helper.ps1`. The parent sends text only as UTF-8 JSON on stdin. The helper uses C# `SendInput` + `KEYEVENTF_UNICODE`, checks that the requested HWND remains foreground before each UTF-16 code unit, paces delivery at 5 ms per code unit, waits 500 ms for the target queue to drain, and returns metadata only. The child environment is allow-listed and does not inherit tunnel/API credentials. Text that cannot fit `max_command_seconds` is rejected before injection. Do not use this tool for secrets. If Windows foreground/UIPI rules block delivery, treat the action as failed and do not retry automatically when partial input is possible.

`desktop.use_credential` accepts only a current HWND plus opaque `credential_id`. The engine resolves the fixed app/executable-path/SHA-256/target strategy/Credential Manager target from protected local policy, reauthorizes the real app and exact process image, optionally focuses only the fixed semantic selector, repeats process/PID/image verification, then starts `scripts/desktop/windows_credential_input_helper.ps1`. The helper verifies HWND/PID and foreground state plus focused UIA password semantics before `CredReadW`. With `@unique-password`, it finds exactly one enabled/visible password descendant and focuses it locally; zero/multiple matches fail. It verifies the same PID/focus/foreground before each delivered code unit and frees the credential buffer. The credential value is not accepted or returned by MCP/Python and is not written to argv/environment/audit. If partial input may have happened, treat the result as failed and do not retry automatically.

`desktop.key_press` accepts bounded key grammar and remains on WinApp. Raw virtual-key syntax and text-escape grammar are rejected at the MCP boundary. For WinApp CLI 0.6.0 compatibility in Scheduled Task sessions, already-validated `a-z`/`0-9` modifier-chord main keys are mapped internally to the equivalent explicit virtual key (for example `ctrl+s` -> `ctrl+vk=0x53`) before invoking WinApp; callers still cannot submit arbitrary `vk=` values. System/shell-reserved combinations require explicit local `allow_system_keys=true`; Windows/WinApp hard blocks still apply.

If the desktop is locked, Secure Desktop is active, or integrity/UIPI prevents input, treat the action as failed. Do not weaken Windows controls as a workaround.

## 9. Create separate OpenAI tunnel and runtime key

Create a third tunnel with a clear name such as:

```text
HooshiX Desktop
```

Create a separate restricted runtime API key with only the tunnel runtime permissions required by the installed tunnel-client. Do not reuse Context/Ops keys and do not use an OpenAI admin key as the long-lived daemon key.

Store the key through a supported protected `file:` or environment reference. The Desktop key must be independently revocable.

## 10. Desktop tunnel-client profile

Use the reviewed tunnel-client v0.0.11 transport pattern and a separate profile such as `hooshix-desktop`.

The stdio command is equivalent to:

```text
<PYTHON_EXE> -B D:/HooshiXContext/repo/scripts/desktop/mcp_server.py --policy C:/ProgramData/HooshiX/desktop/policy.json
```

Keep control-plane key as protected reference, local health/admin on `127.0.0.1`, and do not put a literal key in the profile/argv.

Validate:

```powershell
.\tunnel-client.exe doctor --profile hooshix-desktop --explain
.\tunnel-client.exe run --profile hooshix-desktop
```

Verify `/healthz` and `/readyz`, then connect the same Desktop tunnel in ChatGPT and confirm exact tool discovery.

## 11. Persistent interactive operation

Desktop differs from elevated Ops: it needs the user's interactive desktop session.

Recommended Task Scheduler properties:

```text
Trigger: At log on for the intended user, delayed if needed
Run only when user is logged on
Run with highest privileges: disabled
If task fails: restart after 1 minute
If already running: do not start a new instance
No finite maximum runtime
```

The wrapper/profile uses no literal runtime key. The task starts only after successful foreground verification.

Do not rely on the Task Scheduler `restart after` setting as the only recovery mechanism. The verified host uses the same secret-free two-layer recovery as Context/Ops: an internal wrapper supervisor restarts a terminated tunnel-client child, while the shared one-minute `HooshiX MCP Tunnel Watchdog` runs as `SYSTEM` / `ServiceAccount` in Session 0 and checks the named Desktop task/process plus loopback `/readyz` after startup grace. If the task is not running or remains unready, the watchdog removes same-profile orphan tunnel processes and starts exactly one clean task instance. The watchdog does not change the Desktop principal: Desktop remains interactive and `RunLevel=Limited`. To keep that interactive authority without creating a visible console window, the reviewed Windows task uses a GUI-subsystem hidden launcher (`wscript.exe` waiting on a hidden PowerShell supervisor) instead of invoking `powershell.exe` directly from Task Scheduler. This matters when Windows Terminal is configured as the default terminal host; direct interactive PowerShell task actions can create a small visible terminal window even with `-WindowStyle Hidden`. Record bounded tunnel logs and enable `Microsoft-Windows-TaskScheduler/Operational`.

Parent-task recovery must clean the full known Desktop profile tree before restart: matching `tunnel-client.exe`, the exact PowerShell supervisor, and the exact hidden `wscript.exe` launcher. Stopping only the Task Scheduler parent can orphan the PowerShell supervisor; after its restart delay that orphan can create a second tunnel. Pass requires exactly one launcher, one wrapper, and one tunnel after recovery while preserving `RunLevel=Limited` and the interactive session.

A startup-before-login Session-0 service is not the Desktop v1 runtime because it cannot represent the operator's interactive desktop authority.

## 12. Host evidence

Record separately from repository CI:

- exact installed WinApp `0.6.0` and package integrity evidence;
- policy/audit/capture ACLs;
- when ADR-0050 is enabled: disposable Generic Credential enrollment, exact executable path/SHA-256 binding, semantic or `@unique-password` target recognition, correct broker injection, wrong-window/focus-change failure, and no credential content in audit/argv/environment;
- non-elevated interactive Desktop process;
- separate tunnel/profile/key and loopback health/admin;
- `/readyz`;
- exact ChatGPT Desktop tool discovery and `desktop.status`;
- bounded list/inspect/screenshot;
- harmless UIA/mouse/keyboard smoke;
- audit redaction/rotation;
- logoff/logon background restart;
- stop/revoke/rollback behavior.

Repository tests cannot substitute for this Windows evidence.

## 13. Incident handling

If an unexpected Desktop tool appears, policy fingerprint/broker binding changes without operator intent, an unapproved process becomes controllable, screen/typed/credential data appears in audit/output, a credential is applied to the wrong target, or Desktop actions occur outside the user's request:

1. stop the Desktop task/tunnel runtime;
2. revoke the dedicated Desktop runtime key;
3. preserve bounded metadata without copying sensitive screen/typed content;
4. inspect local policy/task/profile and repository revision;
5. verify Context and Ops remained separate;
6. disable `allow_credential_input`, clear broker bindings, and treat wrong-target/partial/unexpected credential use as potential compromise; rotate/remove affected credentials through their owners when exposure or misuse is plausible;
7. treat unexpected automation of a security-sensitive app as potential developer-host compromise;
8. restore only after policy/tool/readiness/smoke tests pass.

## 14. Rollback

Rollback only Desktop:

1. disable/remove the ChatGPT Desktop connection;
2. stop/delete Desktop scheduled task/runtime;
3. stop Desktop tunnel;
4. revoke Desktop runtime key;
5. set `allow_credential_input=false`, clear `credential_bindings`, and remove/rotate any no-longer-needed Generic Credentials through the trusted local Windows path;
6. remove/disable local Desktop policy/audit/capture directory if no longer needed;
7. optionally uninstall the pinned WinApp CLI package;
8. leave Context and Ops unchanged.
