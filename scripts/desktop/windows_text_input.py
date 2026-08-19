#!/usr/bin/env python3
"""Isolated Windows Unicode text-input adapter for HooshiX Desktop MCP."""

from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path
from typing import Any

MAX_HELPER_OUTPUT_BYTES = 64 * 1024
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


class WindowsTextInputError(RuntimeError):
    """Expected policy/runtime/delivery failure from isolated Windows text input."""

    def __init__(self, message: str, *, code: str, data: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.data = data or {}


def _normalized_utf16_code_units(text: str) -> int:
    normalized = text.replace("\r\n", "\r").replace("\n", "\r")
    return len(normalized.encode("utf-16-le", errors="strict")) // 2


class IsolatedWindowsUnicodeTextInput:
    """Run one fixed PowerShell/C# KEYEVENTF_UNICODE helper over stdin.

    Host verification showed that WinApp CLI 0.6.0 text synthesis and direct
    long-lived Python SendInput were not text-fidelity safe on the target host.
    The fixed isolated C# P/Invoke helper delivered ASCII/mixed-case/Persian
    Unicode exactly in the same non-elevated interactive Windows session.
    """

    PER_CODE_UNIT_SECONDS = 0.005
    FINAL_SETTLE_SECONDS = 0.5
    STARTUP_SLACK_SECONDS = 5.0

    def __init__(self, *, helper_path: Path, timeout_seconds: int) -> None:
        self.helper_path = helper_path.resolve(strict=False)
        self.timeout_seconds = timeout_seconds
        system_root = Path(os.environ.get("SYSTEMROOT", r"C:\Windows"))
        self.powershell_path = system_root / "System32" / "WindowsPowerShell" / "v1.0" / "powershell.exe"
        if not self.helper_path.is_file():
            raise WindowsTextInputError("Windows text input helper is unavailable", code="TEXT_INPUT_UNAVAILABLE")
        if not self.powershell_path.is_file():
            raise WindowsTextInputError("Windows PowerShell text-input runtime is unavailable", code="TEXT_INPUT_UNAVAILABLE")

    @staticmethod
    def _environment() -> dict[str, str]:
        return {key: value for key, value in os.environ.items() if key.upper() in SAFE_CHILD_ENV_KEYS}

    @classmethod
    def estimated_seconds(cls, text: str) -> float:
        return (
            _normalized_utf16_code_units(text) * cls.PER_CODE_UNIT_SECONDS
            + cls.FINAL_SETTLE_SECONDS
            + cls.STARTUP_SLACK_SECONDS
        )

    def _preflight(self, text: str) -> None:
        estimate = self.estimated_seconds(text)
        if estimate >= self.timeout_seconds:
            raise WindowsTextInputError(
                "text is too long for the configured command timeout",
                code="TEXT_INPUT_LIMIT",
                data={"estimated_seconds": round(estimate, 3), "timeout_seconds": self.timeout_seconds},
            )

    def send_text(self, hwnd: int, text: str) -> dict[str, Any]:
        self._preflight(text)
        payload = json.dumps({"hwnd": hwnd, "text": text}, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
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
            raise WindowsTextInputError(
                "Windows text input helper timed out; input may be partially applied",
                code="TEXT_INPUT_TIMEOUT",
                data={"partial_input_possible": True},
            ) from exc
        except OSError as exc:
            raise WindowsTextInputError("Windows text input helper could not start", code="TEXT_INPUT_UNAVAILABLE") from exc

        if len(completed.stdout) > MAX_HELPER_OUTPUT_BYTES or len(completed.stderr) > MAX_HELPER_OUTPUT_BYTES:
            raise WindowsTextInputError("Windows text input helper output exceeded its bound", code="TEXT_INPUT_PROTOCOL_ERROR")

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
                raise WindowsTextInputError(str(error.get("message", "Windows text input helper failed")), code=error["code"], data=data)
            raise WindowsTextInputError(
                "Windows text input helper failed",
                code="TEXT_INPUT_HELPER_FAILED",
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
            raise WindowsTextInputError("Windows text input helper returned invalid UTF-8 JSON", code="TEXT_INPUT_PROTOCOL_ERROR") from exc
        if not isinstance(result, dict):
            raise WindowsTextInputError("Windows text input helper returned an unexpected result", code="TEXT_INPUT_PROTOCOL_ERROR")
        result["characters"] = len(text)
        return result
