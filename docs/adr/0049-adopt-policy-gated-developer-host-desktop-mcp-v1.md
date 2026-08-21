# ADR-0049: Policy-Gated Developer-Host Desktop MCP v1

- **Status:** Accepted
- **Decision date:** 2026-08-18
- **Scope:** Windows developer-host interactive desktop observation and input for ChatGPT through a separate MCP/tunnel boundary

## Context

ADR-0046 keeps the HooshiX Context MCP exactly read-only and Git-authoritative. ADR-0048 adds a separate developer-host Ops MCP for explicit local filesystem/process authority. Neither boundary provides a reviewed screen/UI/mouse/keyboard contract.

The operator requires ChatGPT to inspect and interact with the currently logged-in Windows desktop for developer workflows. This capability can expose visible PII or sensitive screen content and can trigger application actions through UI Automation or synthetic input. It therefore creates a material trust boundary and cannot be added implicitly to Context or Ops.

Microsoft WinApp CLI provides a developer CLI over Windows UI Automation and input/capture APIs. HooshiX pins the reviewed developer-host version and wraps only a bounded subset behind a local policy and MCP contract.

## Decision

HooshiX adopts a third, separate **Desktop MCP** for developer-host interactive GUI access.

The boundaries are:

```text
Context MCP -> read-only Git/repository context
Ops MCP     -> local filesystem/process/administrator operations
Desktop MCP -> interactive-window inspection/capture/UIA/mouse/keyboard operations
```

Desktop MCP MUST NOT add tools to Context MCP or Ops MCP. It uses a separate local policy, separate OpenAI Secure MCP Tunnel profile/tunnel/runtime key, separate audit file, and separate Windows runtime process.

### 1. Runtime identity and session

Desktop MCP runs in the operator's interactive Windows session. V1 defaults to and may require a **non-elevated** process token. This avoids treating desktop automation as another administrator shell and keeps normal input/UIPI behavior aligned with the interactive applications it controls.

Desktop MCP does not bypass UAC, Secure Desktop, Winlogon, workstation lock, or the Windows Secure Attention Sequence. It does not inject `Ctrl+Alt+Del` or automate credential/UAC secure desktops.

Ops MCP remains the separate place for administrator process/filesystem work. Desktop MCP is not a privilege-escalation mechanism.

### 2. WinApp CLI pin

The initial reviewed developer-host dependency is:

```text
Microsoft WinApp CLI 0.6.0
WinGet package: Microsoft.WinAppCli
Windows x64 MSIX SHA-256 reviewed from current WinGet metadata:
dc5d323f6d1601ef3342420746f0163651176f4cc183690f0354546a36648eec
```

The local policy contains the absolute `winapp.exe` path and expected version. Desktop MCP fails startup when the configured executable/version does not match the reviewed policy. `WINAPP_CLI_TELEMETRY_OPTOUT=1` is set for Desktop MCP child invocations.

WinApp CLI is developer tooling/public preview. A version change requires compatibility/security/output-contract review before the baseline/policy changes.

### 3. Policy

The server does not start without explicit UTF-8 JSON policy supplied through `--policy` or `HOOSHIX_DESKTOP_POLICY`.

Policy controls include:

- exact WinApp executable and expected version;
- app allow/deny rules;
- interactive/non-elevated requirements;
- screenshot and broader capture-screen opt-ins;
- UIA mutation, mouse, keyboard, and system-key opt-ins;
- command/output/screenshot/text/inspection bounds;
- bounded rotating audit path.

Unknown fields, missing fields, duplicate keys, invalid values, unsupported paths, or incompatible runtime state fail closed.

`allow_all_apps=true` is explicit broad observation/control authority for ordinary visible applications. `denied_apps` still wins. It is not authority for Secure Desktop or production administration.

### 4. Target selection

Mutation tools target a current numeric HWND obtained from fresh `desktop.list_windows` discovery. Before each operation the engine resolves that HWND again, reads its actual process name, and applies app policy. Caller-provided window titles do not authorize an application.

Semantic UIA selectors are preferred over coordinates. V1 does not expose arbitrary coordinate-only click or arbitrary WinApp command execution.

### 5. Tool surface

V1 exposes only:

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
desktop.key_press
```

It does not expose arbitrary `winapp` argv, recording, touch, pen, clipboard read, credential read, arbitrary process execution, arbitrary filesystem access, or network fetch.

`desktop.type_text` accepts bounded non-secret text only. After fresh HWND/process authorization and optional semantic WinApp focus, the engine starts one fixed short-lived Windows PowerShell helper with `-NoProfile -NonInteractive`; the helper contains a fixed C# `SendInput` P/Invoke implementation using `KEYEVENTF_UNICODE`. Raw text is transferred only as bounded UTF-8 JSON on child stdin, never in child argv/environment/audit. The helper decodes stdin from raw bytes as UTF-8, verifies the requested HWND remains foreground before each UTF-16 code unit, uses evidence-backed 5 ms pacing plus a 500 ms final queue drain, and returns bounded metadata only. The helper path/command is fixed by the independently versioned Windows MCP runtime and is not an arbitrary PowerShell execution surface. Text whose estimated delivery cannot fit the configured timeout is rejected before the helper starts. It is not a credential-entry channel. Passwords, API keys, private keys, recovery codes, OTPs, session values, and other secrets MUST NOT be supplied through Desktop MCP tool arguments.

`desktop.key_press` remains on the reviewed WinApp input path. It rejects literal-text grammar, caller-supplied raw virtual-key syntax, and system/shell-reserved combinations unless the local policy explicitly enables system keys. Because WinApp CLI 0.6.0 can fail `VkKeyScan()` for alphanumeric modifier chords in Scheduled Task threads, only already-validated `a-z`/`0-9` chord main keys are mapped internally to their equivalent explicit virtual key before WinApp invocation. Windows/WinApp hard restrictions such as workstation lock and Secure Attention Sequence remain outside authority.

### 6. Screenshots and UI text

Desktop screenshots and UI tree output are sensitive developer-host observations. They may contain user-visible PII or confidential content.

Screenshot files exist only in a protected configured temporary directory long enough to read the bounded PNG result. The engine removes temporary capture files after reading them. Screenshot bytes are returned to the MCP client and are not copied into local audit metadata.

V1 intentionally has no `get-value`/clipboard-read/credential-reader tool. UI inspection is limited to the WinApp inspection contract and must not be described as secret-safe when visible screen content itself contains a secret.

### 7. Audit

Desktop audit is local developer-host evidence, not tamper-resistant production audit.

Every sensitive observation or mutation is audit-correlated with bounded metadata. Audit records may include event ID, timestamp, action, outcome, HWND, normalized process name, counts, output sizes/digests, and hashes of selectors/text/purpose-like inputs. They MUST NOT include screenshot bytes/base64, raw typed text, raw selectors, window titles, raw tool output, or credentials.

A required audit write failure blocks the associated Desktop operation before sensitive capture or mutation begins.

### 8. Secure MCP Tunnel

ChatGPT Web may reach Desktop MCP through OpenAI Secure MCP Tunnel using a separate tunnel/profile and separate restricted runtime credential. Context and Ops tunnels/credentials are not reused.

The local Desktop tunnel health/admin listener remains loopback-only. The runtime key stays outside Git, argv, logs, screenshots, and ChatGPT content and is independently revocable.

### 9. Production boundary

Desktop MCP is developer-host tooling only. It is not a production administration path, test evidence for production JIT, or authority to bypass ADR-0030, OpenBao, Kubernetes admission, branch protection, CI/security gates, or other product controls.

A future request to use AI desktop automation for production privileged administration requires a separate architecture/security decision.

## Security residual risks

- A permitted ordinary application may itself expose destructive or security-sensitive UI actions. Semantic targeting reduces ambiguity but does not make actions harmless.
- Visible screenshots/UI names may contain PII or confidential data. The model/client receives only what the operator-authorized Desktop tool returns, but local audit intentionally does not retain it.
- Synthetic input depends on the active unlocked interactive desktop and Windows integrity/UIPI behavior. Failure must remain failure; the implementation does not weaken Windows controls.
- An application can change between discovery and action. The engine revalidates HWND/process before each call, but UI state can still race after validation.
- WinApp CLI is public preview. Output/behavior changes require reviewed pin updates and regression tests.
- Literal text uses isolated `KEYEVENTF_UNICODE` delivery because host verification found WinApp 0.6.0 text synthesis and same-process Python `SendInput` were not fidelity-safe. The final helper passed exact mixed-case, `✓`, and Persian Unicode persistence in disposable Notepad host smoke. Target applications can still transform received text, so material workflows must verify resulting application state.

## Verification

Independent Windows MCP runtime evidence must prove at least:

- Context and Ops tool surfaces remain unchanged;
- Desktop exact tool list/annotations and modern/legacy UTF-8 MCP behavior;
- strict policy parsing and runtime/version fail-closed behavior;
- allow/deny app filtering and fresh HWND/process authorization;
- screenshot permission/size/PNG/temp-cleanup behavior;
- mutation/mouse/keyboard/system-key policy negatives;
- fixed isolated Unicode helper path/argv, stdin-only raw text, sanitized environment, explicit UTF-8 input, foreground/UIPI failure semantics, safe alphanumeric-chord VK normalization, and pre-injection timeout bound;
- bounded text/selectors/depth/output/time;
- audit redaction/rotation/fail-closed behavior;
- no credential/clipboard/arbitrary-command tool exists.

Host evidence separately proves the real WinApp version/integrity, protected policy/audit/capture ACLs, non-elevated interactive runtime, separate tunnel/profile/key, local readiness, ChatGPT tool discovery, screenshot/inspect, harmless UIA/mouse/keyboard smoke, audit behavior, background logon restart, and revocation/rollback.

## Rollback

Rollback disables/removes only the Desktop ChatGPT connection, Desktop scheduled task/runtime, Desktop tunnel, Desktop runtime key, and local Desktop policy/capture/audit state. Context and Ops remain unchanged.

## Consequences

This gives ChatGPT explicit desktop observation/input capability without converting Context MCP into a write surface or Ops MCP into an implicit GUI controller. It adds a third privileged developer-host boundary, a pinned public-preview dependency, privacy-sensitive outputs, and environment-specific host verification obligations.
