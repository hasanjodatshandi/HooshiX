# ADR-0050: Add Policy-Bound Desktop Credential Broker v1

- **Status:** Accepted
- **Decision date:** 2026-08-19
- **Scope:** Developer-host application login through Desktop MCP without exposing credential values to ChatGPT, MCP arguments/results, Python, or audit
- **Supersedes:** Only the ADR-0049 rule that Desktop MCP has no credential-entry capability. All ADR-0049 app/session/HWND, non-secret `desktop.type_text`, UAC/Secure-Desktop, audit, tunnel, and production-boundary rules remain in force.

## Context

ADR-0049 intentionally made `desktop.type_text` non-secret and prohibited a credential-entry channel. That boundary prevented ChatGPT from completing an operator-requested login unless the operator typed the password manually.

The operator requires a narrower capability: ChatGPT may request use of a credential that the operator already provisioned locally, but ChatGPT must not receive, request, store, log, or transmit the credential value.

A general secret-capable text tool would create an unnecessary exfiltration and confused-deputy surface. A credential reader would be worse because it would expose the secret value to the MCP boundary. The required capability is therefore **use without disclosure**, with the destination fixed by local policy rather than caller input.

Windows Credential Manager provides a per-logon-session credential store API. The Desktop implementation can resolve a Generic Credential locally with `CredReadW`, use the returned blob only inside one short-lived helper, and release it with `CredFree`. Windows UI Automation exposes `IsPassword`, which can be used as an additional local destination check before the helper resolves the credential.

## Decision

HooshiX adds one optional Desktop MCP tool:

```text
desktop.use_credential(hwnd, credential_id)
```

`credential_id` is an opaque non-secret reference. It is not the Windows Credential Manager target name and it is never a credential value.

The existing `desktop.type_text` contract remains non-secret. Passwords, API keys, OTPs, recovery codes, private keys, cookies/session values, and other credentials/secrets MUST NOT be passed through `desktop.type_text` or any other general text/input argument.

### 1. Capability is disabled by default

The Desktop policy adds two optional fields:

```json
{
  "allow_credential_input": false,
  "credential_bindings": []
}
```

Omitting both fields has the same behavior as `false` plus an empty list. This preserves compatibility with existing ADR-0049 local policies.

Enabling credential input requires:

- `allow_credential_input=true`;
- at least one valid credential binding;
- `allow_uia_mutation=true`;
- `allow_keyboard_input=true`.

A binding contains only:

```text
credential_id      opaque caller-visible reference
app                normalized process name already authorized by Desktop app policy
executable_path    exact absolute executable path
executable_sha256  exact SHA-256 of that executable file
target_selector    fixed semantic WinApp/UIA selector, or `@unique-password`
credential_target  local Windows Credential Manager Generic Credential target name
```

The caller cannot provide or override `app`, `executable_path`, `executable_sha256`, `target_selector`, or `credential_target` in a tool call. `target_selector=@unique-password` is a reserved local strategy for applications whose password control has no stable AutomationId; no other `@...` strategy is accepted.

Bindings are bounded, exact-shape policy objects. Duplicate credential IDs, duplicate Credential Manager targets, denied/unapproved apps, invalid/relative executable paths, invalid executable SHA-256 values, invalid selectors, and coordinate-only selectors fail policy loading. Credential use also has a code-level hard deny for `consent`, `CredentialUIBroker`, `LockApp`, `LogonUI`, and `SecurityHealthUI`, independent of a broader ordinary-app policy.

### 2. Fresh application and target authorization

For every `desktop.use_credential` call:

1. Desktop resolves the supplied HWND again and applies the existing real-process allow/deny policy.
2. The resolved process name must equal the app bound to `credential_id`.
3. Desktop resolves the real process image path from the target PID, requires an exact normalized match to `executable_path`, hashes that executable, and requires an exact match to `executable_sha256`.
4. For a normal semantic selector, Desktop uses WinApp only to focus the selector stored in local policy. For `@unique-password`, Desktop does not pass an unstable caller/UI selector to WinApp.
5. Desktop resolves the HWND/process and executable path/SHA-256 again after the optional WinApp focus operation and requires the PID to be unchanged.
6. The fixed credential helper verifies that the requested HWND still belongs to the expected PID and is the foreground window.
7. For a normal selector, the helper verifies that the currently focused UI Automation element has `IsPassword=true` and belongs to the requested HWND. For `@unique-password`, UI Automation remains the first authority: more than one enabled/visible `IsPassword=true` descendant fails closed; exactly one is focused and verified. Only when UI Automation reports **zero** eligible password descendants may the helper use the legacy native-edit fallback. That fallback enumerates descendant HWNDs and requires exactly one enabled/visible single-line `Edit` or `WindowsForms10.EDIT.*` control in the same PID with `ES_PASSWORD` set and a non-zero `EM_GETPASSWORDCHAR` result obtained through bounded `SendMessageTimeoutW`; zero, multiple, hung/unverifiable, wrong-process, non-child, disabled, hidden, multiline, or unmasked candidates fail closed.
8. For the native fallback, the helper temporarily attaches only its input thread to the already-authorized target GUI thread to set focus, detaches immediately, then verifies the exact native focus HWND through `GetGUIThreadInfo`. The same native HWND/PID/foreground/password predicates are rechecked before every delivered UTF-16 code unit.
9. Only after the applicable UIA/native password-target checks pass may the helper call `CredReadW`.
10. `SendInput` remains subject to Windows UIPI. The broker does not bypass integrity boundaries: a Desktop/helper process cannot inject into a target at a higher integrity level, and that case remains a host/runtime incompatibility rather than a reason to elevate or weaken the ordinary Desktop profile automatically.

A focus/app/window change fails closed. When partial input may already have occurred, the operation reports only that boolean condition and MUST NOT retry automatically.

### 3. Credential storage and local helper

V1 supports Windows Credential Manager **Generic Credentials** only.

Credential enrollment is out of band and local to the operator account. Desktop MCP has no tool to create, write, update, list, enumerate, export, reveal, or delete credentials.

The helper contract is:

- one fixed PowerShell script owned by the independent Windows MCP runtime;
- one fixed embedded C# implementation;
- `-NoProfile -NonInteractive`;
- bounded UTF-8 JSON stdin containing only HWND, expected PID, Credential Manager target name, the policy-derived unique-password focus flag, and a size bound;
- sanitized child environment;
- `CredReadW(..., CRED_TYPE_GENERIC, ...)` only;
- at most 256 UTF-16 code units in v1;
- no embedded NUL;
- `KEYEVENTF_UNICODE` input with the ADR-0049 5 ms pacing and 500 ms final drain;
- `CredFree` in `finally`;
- bounded structured stdout/stderr metadata only.

The helper does not convert the credential blob to a managed `String`, does not return its length/value, and does not write it to stdout, stderr, argv, environment, audit, files, or MCP results.

The Credential Manager target name is a non-secret policy identifier. It is sent to the fixed helper over stdin and is not put in child argv or environment.

Windows defines the `CRED_TYPE_GENERIC` credential blob as application-defined; it does not define a password encoding for that blob. HooshiX v1 therefore defines its own provisioning contract: the operator-provisioned Generic Credential blob used by this broker MUST contain the password as UTF-16LE code units. Unsupported or oversized blobs fail closed.

### 4. MCP contract and output

`desktop.use_credential` accepts only:

```json
{
  "hwnd": 12345,
  "credential_id": "example-login"
}
```

`additionalProperties=false` is mandatory. A caller-supplied field such as `password`, `secret`, `selector`, or `credential_target` is rejected before engine execution.

A successful result contains only non-secret state:

```json
{
  "hwnd": 12345,
  "credential_applied": true
}
```

No credential value, username, credential length, Credential Manager target, or selector is returned.

### 5. Audit and privacy

Audit remains metadata-only and fail-closed under ADR-0049.

For credential use, audit may contain:

- action/outcome/event/time;
- HWND and normalized authorized process metadata already allowed by ADR-0049;
- SHA-256 of the local `credential_id` reference;
- SHA-256 of the fixed selector;
- safe failure code.

Audit MUST NOT contain the credential value, Credential Manager target name, raw `credential_id`, raw selector, typed code units, credential length, username, or helper diagnostics that may contain uncontrolled content.

### 6. Security boundaries that do not change

This decision does not authorize:

- UAC or Secure Desktop automation;
- Winlogon, workstation-lock, or Secure Attention Sequence automation;
- `CredentialUIBroker`, `LogonUI`, `consent`, or another denied secure process;
- production privileged administration or a replacement for ADR-0030;
- credential reading/revealing/export;
- arbitrary Windows Credential Manager operations;
- a general secret-input tool;
- reuse of Context/Ops tunnel credentials or authority;
- weakening of the existing Desktop non-elevated interactive-session default.

### 7. Residual risk

A process running as the same Windows user may have authority to call user-scoped Credential Manager APIs. This broker does not claim to protect a credential from malware or a compromised same-user process. Its security objective is narrower: **the ChatGPT/MCP/Python interface does not receive the secret, and Desktop can use only an operator-provisioned credential at an operator-policy-bound process name + executable path + executable SHA-256 + password target**.

A permitted application can still process a password in an unexpected way after input. The operator must provision only credentials whose use in that application is acceptable and must rotate them if wrong-target or compromise is suspected.

UI state can race. Fresh process checks plus focused-password and same-focus checks reduce the confused-deputy window but cannot prove the application itself is trustworthy.

## Verification

Independent Windows MCP runtime evidence MUST prove at least:

- existing policies without the new fields keep credential input disabled;
- broker enablement requires explicit policy and valid bindings;
- a binding cannot target a denied/unapproved app, coordinate-only selector, or unsupported `@...` strategy;
- the MCP schema accepts only `hwnd` plus `credential_id` and has no credential-value argument;
- caller-supplied secret fields are rejected before engine execution;
- the engine performs fresh app authorization plus exact process-image path/SHA-256 verification before and after fixed-selector focus;
- wrong-app, wrong-executable-path/hash, and unknown-ID calls fail before the credential helper runs;
- audit contains hashes only and no raw binding identifiers/targets/selectors;
- the Python adapter passes only non-secret reference metadata to the fixed helper and sanitizes its environment;
- helper success/error protocols cannot return credential content or length;
- the helper verifies HWND/PID continuity plus focused `IsPassword=true` before `CredReadW`; `@unique-password` remains UIA-first and falls back only on zero UIA matches to exactly one same-PID, enabled/visible, single-line native `Edit`/`WindowsForms10.EDIT.*` child with `ES_PASSWORD` plus non-zero bounded `EM_GETPASSWORDCHAR`; multiple UIA matches never fall through to native matching;
- native fallback focus is set only after exact candidate selection, uses bounded thread-input attachment, is verified with `GetGUIThreadInfo`, and the same native target/PID/foreground/password predicates are checked during delivery;
- UIPI/elevated-target behavior remains fail-closed; the broker does not auto-elevate or bypass Windows integrity controls;
- `CredFree` always releases the credential buffer;
- timeout/partial-input failure does not create automatic retry;
- Context and Ops MCP tool surfaces remain unchanged.

Host evidence is separate. Before an application credential is considered usable, the operator must verify with a disposable/synthetic credential that:

- the credential exists under the intended interactive Windows account;
- the selected application/password element is uniquely targeted; UIA password semantics are preferred, while `@unique-password` may use the bounded native-edit fallback only when UIA reports zero matches and the helper proves exactly one eligible native password edit as defined above;
- the target application runs at an integrity level that the Desktop/helper process is permitted to receive `SendInput` from; higher-integrity targets are not made automatable by weakening UIPI or automatically elevating Desktop;
- the binding records the intended executable absolute path and SHA-256, and an executable update requires review/rebinding before credential use resumes;
- the broker can apply the disposable credential without exposing it in audit/process arguments/environment;
- wrong-window and focus-change cases fail;
- the real application login behavior is then verified separately.

Independent MCP runtime tests do not prove the security of the target application or the confidentiality of a credential against compromise of the Windows user account.

## Rollback

Immediate capability rollback is local-policy only:

```json
{
  "allow_credential_input": false,
  "credential_bindings": []
}
```

Then restart the Desktop MCP/tunnel process and verify `desktop.status` reports the broker disabled with zero bindings.

If compromise or wrong-target input is plausible, the credential owner must also remove/rotate the affected Generic Credential through the local trusted Windows credential-management path.

Code rollback removes `desktop.use_credential` and the fixed credential helper while keeping the rest of ADR-0049 Desktop MCP unchanged. Context and Ops remain unchanged.

## Consequences

This removes the need to transmit passwords through ChatGPT while allowing narrowly authorized developer-host application login. It adds a security-sensitive local credential-use primitive, Windows Credential Manager/UI Automation coupling, policy-management work, and host-specific verification obligations. It does not create a credential reader or general secret-input capability.
