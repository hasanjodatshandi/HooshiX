#!/usr/bin/env python3
"""Isolated Windows Credential Manager input adapter for HooshiX Desktop MCP."""

from __future__ import annotations

import json
import os
import re
import subprocess
from pathlib import Path
from typing import Any

MAX_HELPER_OUTPUT_BYTES = 64 * 1024
MAX_CREDENTIAL_TARGET_CHARS = 256
MAX_CREDENTIAL_UTF16_CODE_UNITS = 256
CREDENTIAL_TARGET_RE = re.compile(r"^[^\x00-\x1f\x7f]{1,256}$")
SAFE_CHILD_ENV_KEYS = {
    "APPDATA",
    "LOCALAPPDATA",
    "PROGRAMDATA",
    "SYSTEMDRIVE",
    "SYSTEMROOT",
    "TEMP",
    "TMP",
    "USERPROFILE",
    "WINDIR",
}


class WindowsCredentialInputError(RuntimeError):
    """Expected policy/runtime/delivery failure from isolated credential input."""

    def __init__(self, message: str, *, code: str, data: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.data = data or {}


class IsolatedWindowsCredentialInput:
    """Resolve and inject one policy-bound generic credential inside a fixed helper.

    The Python parent receives only a non-secret Windows Credential Manager target
    name. CredReadW and KEYEVENTF_UNICODE delivery happen in the same short-lived
    helper process, so the credential value never crosses MCP/Python/stdout/stderr.
    """

    def __init__(self, *, helper_path: Path, timeout_seconds: int) -> None:
        self.helper_path = helper_path.resolve(strict=False)
        self.timeout_seconds = timeout_seconds
        system_root = Path(os.environ.get("SYSTEMROOT", r"C:\Windows"))
        self.powershell_path = system_root / "System32" / "WindowsPowerShell" / "v1.0" / "powershell.exe"
        if not self.helper_path.is_file():
            raise WindowsCredentialInputError("Windows credential input helper is unavailable", code="CREDENTIAL_INPUT_UNAVAILABLE")
        if not self.powershell_path.is_file():
            raise WindowsCredentialInputError("Windows PowerShell credential-input runtime is unavailable", code="CREDENTIAL_INPUT_UNAVAILABLE")

    @staticmethod
    def _environment() -> dict[str, str]:
        return {key: value for key, value in os.environ.items() if key.upper() in SAFE_CHILD_ENV_KEYS}

    @staticmethod
    def _validate_target(credential_target: str) -> str:
        if not isinstance(credential_target, str) or CREDENTIAL_TARGET_RE.fullmatch(credential_target) is None:
            raise WindowsCredentialInputError("credential target is invalid", code="CREDENTIAL_INPUT_UNAVAILABLE")
        return credential_target

    def send_credential(
        self,
        hwnd: int,
        expected_process_id: int,
        credential_target: str,
        *,
        focus_unique_password: bool = False,
    ) -> dict[str, Any]:
        if not isinstance(expected_process_id, int) or isinstance(expected_process_id, bool) or expected_process_id <= 0:
            raise WindowsCredentialInputError("expected process id is invalid", code="CREDENTIAL_INPUT_UNAVAILABLE")
        if not isinstance(focus_unique_password, bool):
            raise WindowsCredentialInputError("credential focus strategy is invalid", code="CREDENTIAL_INPUT_UNAVAILABLE")
        target = self._validate_target(credential_target)
        payload = json.dumps(
            {
                "hwnd": hwnd,
                "expected_process_id": expected_process_id,
                "credential_target": target,
                "focus_unique_password": focus_unique_password,
                "max_utf16_code_units": MAX_CREDENTIAL_UTF16_CODE_UNITS,
            },
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0
        try:
            completed = subprocess.run(
                [
                    str(self.powershell_path),
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(self.helper_path),
                ],
                input=payload,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=self._environment(),
                cwd=str(self.helper_path.parent),
                timeout=self.timeout_seconds,
                creationflags=creationflags,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            raise WindowsCredentialInputError(
                "Windows credential input helper timed out; credential input may be partial",
                code="CREDENTIAL_INPUT_TIMEOUT",
                data={"partial_input_possible": True},
            ) from exc
        except OSError as exc:
            raise WindowsCredentialInputError(
                "Windows credential input helper could not start",
                code="CREDENTIAL_INPUT_UNAVAILABLE",
            ) from exc

        if len(completed.stdout) > MAX_HELPER_OUTPUT_BYTES or len(completed.stderr) > MAX_HELPER_OUTPUT_BYTES:
            raise WindowsCredentialInputError(
                "Windows credential input helper output exceeded its bound",
                code="CREDENTIAL_INPUT_PROTOCOL_ERROR",
            )

        if completed.returncode != 0:
            error = None
            try:
                stderr_text = completed.stderr.decode("utf-8")
            except UnicodeDecodeError:
                stderr_text = ""
            for line in reversed(stderr_text.splitlines()):
                try:
                    candidate = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if isinstance(candidate, dict) and isinstance(candidate.get("code"), str):
                    error = candidate
                    break
            if isinstance(error, dict):
                data = error.get("data") if isinstance(error.get("data"), dict) else {}
                raise WindowsCredentialInputError(
                    str(error.get("message", "Windows credential input helper failed")),
                    code=error["code"],
                    data=data,
                )
            raise WindowsCredentialInputError(
                "Windows credential input helper failed",
                code="CREDENTIAL_INPUT_HELPER_FAILED",
                data={
                    "returncode": completed.returncode,
                    "stdout_bytes": len(completed.stdout),
                    "stderr_bytes": len(completed.stderr),
                    "structured_error_found": False,
                },
            )

        try:
            result = json.loads(completed.stdout.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise WindowsCredentialInputError(
                "Windows credential input helper returned invalid UTF-8 JSON",
                code="CREDENTIAL_INPUT_PROTOCOL_ERROR",
            ) from exc
        if not isinstance(result, dict) or result != {"credential_applied": True, "settle_ms": 500}:
            raise WindowsCredentialInputError(
                "Windows credential input helper returned an unexpected result",
                code="CREDENTIAL_INPUT_PROTOCOL_ERROR",
            )
        return result
