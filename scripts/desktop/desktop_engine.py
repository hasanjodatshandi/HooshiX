#!/usr/bin/env python3
"""Policy-gated Windows interactive desktop engine for HooshiX Desktop MCP."""

from __future__ import annotations

import ctypes
import hashlib
import json
import os
import re
import signal
import subprocess
import threading
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, BinaryIO, Protocol

from windows_text_input import IsolatedWindowsUnicodeTextInput, WindowsTextInputError

POLICY_SCHEMA_VERSION = 1
MAX_POLICY_BYTES = 1024 * 1024
MAX_PATH_CHARS = 4096
MAX_SELECTOR_CHARS = 512
MAX_KEY_CHARS = 512
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
VERSION_RE = re.compile(r"^\s*(\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?)\s*$")
COORDINATE_RE = re.compile(r"^\s*-?\d+\s*,\s*-?\d+\s*$")
APP_RE = re.compile(r"^[A-Za-z0-9_. -]{1,128}$")
KEY_TOKEN_RE = re.compile(
    r"^(?:(?:ctrl|alt|shift|win)\+){0,3}(?:[a-z0-9]|f(?:[1-9]|1[0-2])|enter|tab|esc|escape|space|backspace|delete|up|down|left|right|home|end|pageup|pagedown)$",
    re.IGNORECASE,
)
SECRET_ENV_RE = re.compile(
    r"(?:TOKEN|SECRET|PASSWORD|PASSWD|PRIVATE[_-]?KEY|API[_-]?KEY|CREDENTIAL|COOKIE|SESSION)",
    re.IGNORECASE,
)
SAFE_ENVIRONMENT_KEYS = {
    "APPDATA",
    "HOME",
    "LOCALAPPDATA",
    "PATH",
    "PATHEXT",
    "PROGRAMDATA",
    "PROGRAMFILES",
    "PROGRAMFILES(X86)",
    "SYSTEMDRIVE",
    "SYSTEMROOT",
    "TEMP",
    "TMP",
    "USERPROFILE",
    "WINDIR",
}
POLICY_FIELDS = {
    "schema_version",
    "winapp_path",
    "expected_winapp_version",
    "allow_all_apps",
    "allowed_apps",
    "denied_apps",
    "audit_log",
    "capture_temp_dir",
    "require_interactive_session",
    "require_non_elevated",
    "allow_screenshot",
    "allow_capture_screen",
    "allow_uia_mutation",
    "allow_mouse_input",
    "allow_keyboard_input",
    "allow_system_keys",
    "max_command_seconds",
    "max_output_bytes",
    "max_screenshot_bytes",
    "max_text_chars",
    "max_inspect_depth",
    "max_audit_bytes",
    "audit_backups",
}


class DesktopError(RuntimeError):
    """Expected Desktop policy, runtime, or operation failure."""

    def __init__(self, message: str, *, code: str = "DESKTOP_ERROR", data: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.data = data or {}


@dataclass(frozen=True)
class DesktopPolicy:
    path: Path
    fingerprint: str
    winapp_path: Path
    expected_winapp_version: str
    allow_all_apps: bool
    allowed_apps: frozenset[str]
    denied_apps: frozenset[str]
    audit_log: Path
    capture_temp_dir: Path
    require_interactive_session: bool
    require_non_elevated: bool
    allow_screenshot: bool
    allow_capture_screen: bool
    allow_uia_mutation: bool
    allow_mouse_input: bool
    allow_keyboard_input: bool
    allow_system_keys: bool
    max_command_seconds: int
    max_output_bytes: int
    max_screenshot_bytes: int
    max_text_chars: int
    max_inspect_depth: int
    max_audit_bytes: int
    audit_backups: int


@dataclass(frozen=True)
class RunnerResult:
    exit_code: int
    timed_out: bool
    duration_ms: int
    stdout: bytes
    stderr: bytes
    stdout_truncated: bool = False
    stderr_truncated: bool = False


@dataclass(frozen=True)
class ScreenshotResult:
    metadata: dict[str, Any]
    png: bytes


class Runner(Protocol):
    def run(self, args: list[str], *, timeout_seconds: int) -> RunnerResult: ...


def _canonical(path: Path) -> Path:
    return path.expanduser().resolve(strict=False)


def _strict_json_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DesktopError(f"Desktop policy contains duplicate JSON key: {key}", code="INVALID_POLICY")
        result[key] = value
    return result


def _require_int(data: dict[str, Any], key: str, minimum: int, maximum: int) -> int:
    value = data.get(key)
    if not isinstance(value, int) or isinstance(value, bool) or not minimum <= value <= maximum:
        raise DesktopError(
            f"policy field {key} must be an integer in [{minimum}, {maximum}]",
            code="INVALID_POLICY",
        )
    return value


def _normalize_app(value: str) -> str:
    normalized = value.strip().casefold()
    if normalized.endswith(".exe"):
        normalized = normalized[:-4]
    return normalized


def _load_apps(data: dict[str, Any], key: str) -> frozenset[str]:
    raw = data.get(key)
    if not isinstance(raw, list):
        raise DesktopError(f"Desktop policy {key} must be an array", code="INVALID_POLICY")
    result: list[str] = []
    for item in raw:
        if not isinstance(item, str) or not APP_RE.fullmatch(item.strip()):
            raise DesktopError(f"Desktop policy contains invalid {key} entry", code="INVALID_POLICY")
        result.append(_normalize_app(item))
    if len(result) != len(set(result)):
        raise DesktopError(f"Desktop policy {key} contains duplicate normalized app names", code="INVALID_POLICY")
    return frozenset(result)


def _absolute_policy_path(data: dict[str, Any], key: str) -> Path:
    value = data.get(key)
    if not isinstance(value, str) or not value.strip() or len(value) > MAX_PATH_CHARS:
        raise DesktopError(f"Desktop policy {key} must be a bounded absolute path", code="INVALID_POLICY")
    path = Path(value).expanduser()
    if not path.is_absolute():
        raise DesktopError(f"Desktop policy {key} must be absolute", code="INVALID_POLICY")
    return _canonical(path)


def load_policy(path: str | os.PathLike[str]) -> DesktopPolicy:
    policy_path = _canonical(Path(path))
    if not policy_path.is_file():
        raise DesktopError(f"Desktop policy file does not exist: {policy_path}", code="INVALID_POLICY")
    raw = policy_path.read_bytes()
    if len(raw) > MAX_POLICY_BYTES:
        raise DesktopError("Desktop policy exceeds 1 MiB", code="INVALID_POLICY")
    try:
        data = json.loads(raw.decode("utf-8"), object_pairs_hook=_strict_json_object)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise DesktopError(f"Desktop policy is not valid UTF-8 JSON: {exc}", code="INVALID_POLICY") from exc
    if not isinstance(data, dict) or data.get("schema_version") != POLICY_SCHEMA_VERSION:
        raise DesktopError("Desktop policy schema_version must be 1", code="INVALID_POLICY")
    missing = sorted(POLICY_FIELDS - set(data))
    if missing:
        raise DesktopError("Desktop policy is missing required fields: " + ", ".join(missing), code="INVALID_POLICY")
    extra = sorted(set(data) - POLICY_FIELDS)
    if extra:
        raise DesktopError("Desktop policy contains unsupported fields: " + ", ".join(extra), code="INVALID_POLICY")

    version = data.get("expected_winapp_version")
    if not isinstance(version, str) or len(version) > 64 or VERSION_RE.fullmatch(version) is None:
        raise DesktopError("expected_winapp_version must be a semantic version string", code="INVALID_POLICY")

    bools: dict[str, bool] = {}
    for key in (
        "allow_all_apps",
        "require_interactive_session",
        "require_non_elevated",
        "allow_screenshot",
        "allow_capture_screen",
        "allow_uia_mutation",
        "allow_mouse_input",
        "allow_keyboard_input",
        "allow_system_keys",
    ):
        value = data.get(key)
        if not isinstance(value, bool):
            raise DesktopError(f"Desktop policy {key} must be boolean", code="INVALID_POLICY")
        bools[key] = value

    allowed = _load_apps(data, "allowed_apps")
    denied = _load_apps(data, "denied_apps")
    if not bools["allow_all_apps"] and not allowed:
        raise DesktopError("allowed_apps must be non-empty when allow_all_apps is false", code="INVALID_POLICY")
    overlap = sorted(allowed & denied)
    if overlap:
        raise DesktopError("allowed_apps and denied_apps overlap: " + ", ".join(overlap), code="INVALID_POLICY")
    if bools["allow_capture_screen"] and not bools["allow_screenshot"]:
        raise DesktopError("allow_capture_screen requires allow_screenshot", code="INVALID_POLICY")
    if bools["allow_system_keys"] and not bools["allow_keyboard_input"]:
        raise DesktopError("allow_system_keys requires allow_keyboard_input", code="INVALID_POLICY")

    return DesktopPolicy(
        path=policy_path,
        fingerprint=hashlib.sha256(raw).hexdigest(),
        winapp_path=_absolute_policy_path(data, "winapp_path"),
        expected_winapp_version=version,
        allow_all_apps=bools["allow_all_apps"],
        allowed_apps=allowed,
        denied_apps=denied,
        audit_log=_absolute_policy_path(data, "audit_log"),
        capture_temp_dir=_absolute_policy_path(data, "capture_temp_dir"),
        require_interactive_session=bools["require_interactive_session"],
        require_non_elevated=bools["require_non_elevated"],
        allow_screenshot=bools["allow_screenshot"],
        allow_capture_screen=bools["allow_capture_screen"],
        allow_uia_mutation=bools["allow_uia_mutation"],
        allow_mouse_input=bools["allow_mouse_input"],
        allow_keyboard_input=bools["allow_keyboard_input"],
        allow_system_keys=bools["allow_system_keys"],
        max_command_seconds=_require_int(data, "max_command_seconds", 1, 120),
        max_output_bytes=_require_int(data, "max_output_bytes", 4096, 16 * 1024 * 1024),
        max_screenshot_bytes=_require_int(data, "max_screenshot_bytes", 65536, 32 * 1024 * 1024),
        max_text_chars=_require_int(data, "max_text_chars", 1, 16384),
        max_inspect_depth=_require_int(data, "max_inspect_depth", 1, 12),
        max_audit_bytes=_require_int(data, "max_audit_bytes", 1024 * 1024, 1024 * 1024 * 1024),
        audit_backups=_require_int(data, "audit_backups", 1, 20),
    )


def _is_elevated() -> bool:
    if os.name != "nt":
        geteuid = getattr(os, "geteuid", None)
        return bool(geteuid is not None and geteuid() == 0)
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except (AttributeError, OSError):
        return False


def _window_station_name() -> str | None:
    if os.name != "nt":
        return None
    try:
        user32 = ctypes.windll.user32
        handle = user32.GetProcessWindowStation()
        if not handle:
            return None
        needed = ctypes.c_ulong(0)
        user32.GetUserObjectInformationW(handle, 2, None, 0, ctypes.byref(needed))
        if needed.value <= 2 or needed.value > 4096:
            return None
        buffer = ctypes.create_unicode_buffer(needed.value // ctypes.sizeof(ctypes.c_wchar) + 1)
        if not user32.GetUserObjectInformationW(handle, 2, buffer, ctypes.sizeof(buffer), ctypes.byref(needed)):
            return None
        return buffer.value
    except (AttributeError, OSError, ValueError):
        return None


def _is_interactive_session() -> bool:
    if os.name != "nt":
        return False
    return (_window_station_name() or "").casefold() == "winsta0"


class SubprocessWinAppRunner:
    def __init__(self, policy: DesktopPolicy) -> None:
        self.policy = policy

    def run(self, args: list[str], *, timeout_seconds: int) -> RunnerResult:
        if not self.policy.winapp_path.is_file():
            raise DesktopError("configured WinApp executable is unavailable", code="WINAPP_UNAVAILABLE")
        started = time.monotonic()
        creationflags = 0
        start_new_session = False
        if os.name == "nt":
            creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0) | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
        else:
            start_new_session = True
        try:
            process = subprocess.Popen(
                [str(self.policy.winapp_path), *args],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=self._environment(),
                creationflags=creationflags,
                start_new_session=start_new_session,
            )
        except OSError as exc:
            raise DesktopError("failed to start WinApp CLI", code="WINAPP_START_FAILED") from exc
        stdout_state: dict[str, Any] = {"data": bytearray(), "truncated": False}
        stderr_state: dict[str, Any] = {"data": bytearray(), "truncated": False}
        threads = [
            threading.Thread(target=self._drain, args=(process.stdout, stdout_state), daemon=True),
            threading.Thread(target=self._drain, args=(process.stderr, stderr_state), daemon=True),
        ]
        for thread in threads:
            thread.start()
        timed_out = False
        try:
            process.wait(timeout=timeout_seconds)
        except subprocess.TimeoutExpired:
            timed_out = True
            self._terminate(process)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        finally:
            for thread in threads:
                thread.join(timeout=5)
        return RunnerResult(
            exit_code=process.returncode,
            timed_out=timed_out,
            duration_ms=int((time.monotonic() - started) * 1000),
            stdout=bytes(stdout_state["data"]),
            stderr=bytes(stderr_state["data"]),
            stdout_truncated=bool(stdout_state["truncated"]),
            stderr_truncated=bool(stderr_state["truncated"]),
        )

    def _drain(self, stream: BinaryIO | None, state: dict[str, Any]) -> None:
        if stream is None:
            return
        cap = self.policy.max_output_bytes
        try:
            while True:
                chunk = stream.read(8192)
                if not chunk:
                    break
                remaining = cap - len(state["data"])
                if remaining > 0:
                    state["data"].extend(chunk[:remaining])
                if len(chunk) > max(0, remaining):
                    state["truncated"] = True
        finally:
            stream.close()

    @staticmethod
    def _terminate(process: subprocess.Popen[bytes]) -> None:
        if process.poll() is not None:
            return
        if os.name == "nt":
            taskkill = Path(os.environ.get("SYSTEMROOT", r"C:\Windows")) / "System32" / "taskkill.exe"
            try:
                subprocess.run(
                    [str(taskkill), "/PID", str(process.pid), "/T", "/F"],
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    timeout=5,
                    check=False,
                )
                return
            except (OSError, subprocess.SubprocessError):
                process.kill()
                return
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            process.kill()

    @staticmethod
    def _environment() -> dict[str, str]:
        environment: dict[str, str] = {}
        for key, value in os.environ.items():
            if key.upper() not in SAFE_ENVIRONMENT_KEYS:
                continue
            if SECRET_ENV_RE.search(key):
                continue
            environment[key] = value
        environment["WINAPP_CLI_TELEMETRY_OPTOUT"] = "1"
        environment["HOOSHIX_DESKTOP_CHILD"] = "1"
        return environment


class DesktopEngine:
    def __init__(self, policy: DesktopPolicy, runner: Runner, *, runtime_version: str, text_input: Any | None = None) -> None:
        self.policy = policy
        self.runner = runner
        self.runtime_version = runtime_version
        self.text_input = text_input
        self._audit_lock = threading.Lock()
        try:
            self.policy.audit_log.parent.mkdir(parents=True, exist_ok=True)
            self.policy.capture_temp_dir.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            raise DesktopError("Desktop audit/capture directory is unavailable", code="AUDIT_UNAVAILABLE") from exc
        self._audit("desktop.start", "passed", {"policy_fingerprint": policy.fingerprint, "runtime_version": runtime_version})

    @classmethod
    def from_policy_path(cls, path: str | os.PathLike[str]) -> "DesktopEngine":
        policy = load_policy(path)
        if os.name != "nt":
            raise DesktopError("Desktop MCP requires Windows", code="WINDOWS_REQUIRED")
        cls._validate_runtime_state(policy, elevated=_is_elevated(), interactive=_is_interactive_session())
        runner = SubprocessWinAppRunner(policy)
        version = cls._probe_version(policy, runner)
        return cls(policy, runner, runtime_version=version, text_input=IsolatedWindowsUnicodeTextInput(helper_path=Path(__file__).with_name("windows_text_input_helper.ps1"), timeout_seconds=policy.max_command_seconds))

    @staticmethod
    def _validate_runtime_state(policy: DesktopPolicy, *, elevated: bool, interactive: bool) -> None:
        if policy.require_non_elevated and elevated:
            raise DesktopError("Desktop policy requires a non-elevated process", code="ELEVATION_DENIED")
        if policy.require_interactive_session and not interactive:
            raise DesktopError("Desktop policy requires the interactive WinSta0 session", code="INTERACTIVE_SESSION_REQUIRED")

    @staticmethod
    def _probe_version(policy: DesktopPolicy, runner: Runner) -> str:
        result = runner.run(["--version"], timeout_seconds=min(10, policy.max_command_seconds))
        if result.timed_out or result.exit_code != 0 or result.stdout_truncated:
            raise DesktopError("unable to verify WinApp CLI version", code="WINAPP_VERSION_UNAVAILABLE")
        text = result.stdout.decode("utf-8", errors="replace")
        versions = [match.group(1) for line in text.splitlines() if (match := VERSION_RE.fullmatch(line))]
        if not versions:
            raise DesktopError("WinApp CLI version output is not recognized", code="WINAPP_VERSION_UNAVAILABLE")
        actual = versions[-1]
        if actual != policy.expected_winapp_version:
            raise DesktopError(
                "WinApp CLI version does not match local policy",
                code="WINAPP_VERSION_MISMATCH",
                data={"expected": policy.expected_winapp_version, "actual": actual},
            )
        return actual

    def status(self) -> dict[str, Any]:
        return {
            "schema_version": 1,
            "mode": "developer-host-desktop",
            "policy_fingerprint": self.policy.fingerprint,
            "policy_path": str(self.policy.path),
            "winapp_path": str(self.policy.winapp_path),
            "expected_winapp_version": self.policy.expected_winapp_version,
            "runtime_winapp_version": self.runtime_version,
            "elevated": _is_elevated(),
            "interactive_session": _is_interactive_session(),
            "require_interactive_session": self.policy.require_interactive_session,
            "require_non_elevated": self.policy.require_non_elevated,
            "allow_all_apps": self.policy.allow_all_apps,
            "allowed_apps": sorted(self.policy.allowed_apps),
            "denied_apps": sorted(self.policy.denied_apps),
            "capabilities": {
                "screenshot": self.policy.allow_screenshot,
                "capture_screen": self.policy.allow_capture_screen,
                "uia_mutation": self.policy.allow_uia_mutation,
                "mouse_input": self.policy.allow_mouse_input,
                "keyboard_input": self.policy.allow_keyboard_input,
                "text_input_backend": "isolated-windows-unicode-sendinput" if self.text_input is not None else "unavailable",
                "system_keys": self.policy.allow_system_keys,
            },
            "limits": {
                "max_command_seconds": self.policy.max_command_seconds,
                "max_output_bytes": self.policy.max_output_bytes,
                "max_screenshot_bytes": self.policy.max_screenshot_bytes,
                "max_text_chars": self.policy.max_text_chars,
                "max_inspect_depth": self.policy.max_inspect_depth,
                "max_audit_bytes": self.policy.max_audit_bytes,
                "audit_backups": self.policy.audit_backups,
            },
        }

    def audit_tail(self, limit: int = 20) -> dict[str, Any]:
        if not isinstance(limit, int) or isinstance(limit, bool) or not 1 <= limit <= 100:
            raise DesktopError("audit limit must be an integer in [1, 100]", code="INVALID_ARGUMENT")
        if not self.policy.audit_log.exists():
            return {"entries": []}
        entries: list[dict[str, Any]] = []
        for line in self._tail_lines(self.policy.audit_log, limit):
            try:
                value = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(value, dict):
                entries.append(value)
        return {"entries": entries}

    def list_windows(self) -> dict[str, Any]:
        self._audit("desktop.list_windows", "started", {})
        try:
            windows = [self._public_window(item) for item in self._raw_windows() if self._app_allowed(str(item.get("processName", "")))]
        except DesktopError as exc:
            self._audit("desktop.list_windows", "failed", {"error": exc.code})
            raise
        self._audit("desktop.list_windows", "passed", {"window_count": len(windows)})
        return {"windows": windows}

    def inspect(self, hwnd: int, *, selector: str | None = None, depth: int = 4, interactive: bool = True) -> dict[str, Any]:
        target = self._authorize_window(hwnd)
        if not isinstance(depth, int) or isinstance(depth, bool) or not 1 <= depth <= self.policy.max_inspect_depth:
            raise DesktopError(f"depth must be in [1, {self.policy.max_inspect_depth}]", code="INVALID_ARGUMENT")
        if not isinstance(interactive, bool):
            raise DesktopError("interactive must be boolean", code="INVALID_ARGUMENT")
        selector = self._selector(selector, optional=True)
        audit = self._target_audit(target, selector)
        audit.update({"depth": depth, "interactive": interactive})
        self._audit("desktop.inspect", "started", audit)
        args = ["ui", "inspect"]
        if selector is not None:
            args.append(selector)
        args.extend(["-w", str(hwnd), "--json", "--depth", str(depth)])
        if interactive:
            args.append("--interactive")
        try:
            value = self._run_json(args)
        except DesktopError as exc:
            self._audit("desktop.inspect", "failed", {**audit, "error": exc.code})
            raise
        self._audit("desktop.inspect", "passed", {**audit, "output_sha256": self._hash_json(value)})
        return value if isinstance(value, dict) else {"result": value}

    def screenshot(self, hwnd: int, *, selector: str | None = None, capture_screen: bool = False) -> ScreenshotResult:
        if not self.policy.allow_screenshot:
            raise DesktopError("screenshot is disabled by local policy", code="SCREENSHOT_DENIED")
        if not isinstance(capture_screen, bool):
            raise DesktopError("capture_screen must be boolean", code="INVALID_ARGUMENT")
        if capture_screen and not self.policy.allow_capture_screen:
            raise DesktopError("capture-screen is disabled by local policy", code="CAPTURE_SCREEN_DENIED")
        target = self._authorize_window(hwnd)
        selector = self._selector(selector, optional=True)
        audit = self._target_audit(target, selector)
        audit["capture_screen"] = capture_screen
        self._audit("desktop.screenshot", "started", audit)
        temp_path = self.policy.capture_temp_dir / f"capture-{uuid.uuid4().hex}.png"
        args = ["ui", "screenshot"]
        if selector is not None:
            args.append(selector)
        args.extend(["-w", str(hwnd), "--json", "--output", str(temp_path)])
        if capture_screen:
            args.append("--capture-screen")
        try:
            report = self._run_json(args)
            if not temp_path.is_file():
                raise DesktopError("WinApp did not produce the expected screenshot file", code="SCREENSHOT_FAILED")
            try:
                size = temp_path.stat().st_size
                if size > self.policy.max_screenshot_bytes:
                    raise DesktopError("screenshot exceeds configured max_screenshot_bytes", code="LIMIT_EXCEEDED")
                png = temp_path.read_bytes()
            except DesktopError:
                raise
            except OSError as exc:
                raise DesktopError("screenshot temporary file could not be read", code="SCREENSHOT_FAILED") from exc
            if not png.startswith(PNG_SIGNATURE):
                raise DesktopError("screenshot output is not a PNG", code="SCREENSHOT_FAILED")
            try:
                temp_path.unlink()
            except OSError as exc:
                raise DesktopError("screenshot temporary file could not be removed", code="SCREENSHOT_CLEANUP_FAILED") from exc
            metadata = {
                "hwnd": hwnd,
                "processName": target["processName"],
                "bytes": len(png),
                "sha256": hashlib.sha256(png).hexdigest(),
                "width": report.get("width") if isinstance(report, dict) else None,
                "height": report.get("height") if isinstance(report, dict) else None,
                "capture_screen": capture_screen,
            }
            self._audit("desktop.screenshot", "passed", {k: v for k, v in metadata.items() if k not in {"processName"}} | {"app": _normalize_app(target["processName"])})
            return ScreenshotResult(metadata=metadata, png=png)
        except DesktopError as exc:
            self._audit("desktop.screenshot", "failed", {**audit, "error": exc.code})
            raise
        finally:
            try:
                temp_path.unlink(missing_ok=True)
            except OSError:
                pass

    def invoke(self, hwnd: int, selector: str) -> dict[str, Any]:
        self._require_uia_mutation()
        return self._action("desktop.invoke", hwnd, selector, ["ui", "invoke", selector])

    def focus(self, hwnd: int, selector: str) -> dict[str, Any]:
        self._require_uia_mutation()
        return self._action("desktop.focus", hwnd, selector, ["ui", "focus", selector])

    def click(self, hwnd: int, selector: str, *, button: str = "left", double: bool = False) -> dict[str, Any]:
        if not self.policy.allow_mouse_input:
            raise DesktopError("mouse input is disabled by local policy", code="MOUSE_INPUT_DENIED")
        if button not in {"left", "right"}:
            raise DesktopError("button must be left or right", code="INVALID_ARGUMENT")
        if not isinstance(double, bool):
            raise DesktopError("double must be boolean", code="INVALID_ARGUMENT")
        args = ["ui", "click", selector]
        if button == "right":
            args.append("--right")
        if double:
            args.append("--double")
        return self._action("desktop.click", hwnd, selector, args, extra={"button": button, "double": double})

    def hover(self, hwnd: int, selector: str) -> dict[str, Any]:
        if not self.policy.allow_mouse_input:
            raise DesktopError("mouse input is disabled by local policy", code="MOUSE_INPUT_DENIED")
        return self._action("desktop.hover", hwnd, selector, ["ui", "hover", selector])

    def drag(self, hwnd: int, from_selector: str, to_selector: str) -> dict[str, Any]:
        if not self.policy.allow_mouse_input:
            raise DesktopError("mouse input is disabled by local policy", code="MOUSE_INPUT_DENIED")
        source = self._selector(from_selector)
        target_selector = self._selector(to_selector)
        target = self._authorize_window(hwnd)
        audit = self._target_audit(target)
        audit.update({"from_selector_sha256": self._hash_text(source), "to_selector_sha256": self._hash_text(target_selector)})
        self._audit("desktop.drag", "started", audit)
        try:
            value = self._run_json(["ui", "drag", source, target_selector, "-w", str(hwnd), "--json"])
        except DesktopError as exc:
            self._audit("desktop.drag", "failed", {**audit, "error": exc.code})
            raise
        self._audit("desktop.drag", "passed", audit)
        return value if isinstance(value, dict) else {"result": value}

    def type_text(self, hwnd: int, text: str, *, target_selector: str | None = None) -> dict[str, Any]:
        if not self.policy.allow_keyboard_input:
            raise DesktopError("keyboard input is disabled by local policy", code="KEYBOARD_INPUT_DENIED")
        if not isinstance(text, str) or not text or len(text) > self.policy.max_text_chars:
            raise DesktopError(f"text must contain 1-{self.policy.max_text_chars} characters", code="INVALID_ARGUMENT")
        if self.text_input is None:
            raise DesktopError("exact Windows text input backend is unavailable", code="TEXT_INPUT_UNAVAILABLE")
        selector = self._selector(target_selector, optional=True)
        target = self._authorize_window(hwnd)
        audit = self._target_audit(target, selector)
        audit.update({"text_chars": len(text), "text_sha256": self._hash_text(text)})
        self._audit("desktop.type_text", "started", audit)
        try:
            if selector is not None:
                self._run_json(["ui", "focus", selector, "-w", str(hwnd), "--json"])
            value = self.text_input.send_text(hwnd, text)
        except WindowsTextInputError as exc:
            error = DesktopError(str(exc), code=exc.code, data=exc.data)
            self._audit("desktop.type_text", "failed", {**audit, "error": error.code})
            raise error from exc
        except DesktopError as exc:
            self._audit("desktop.type_text", "failed", {**audit, "error": exc.code})
            raise
        self._audit("desktop.type_text", "passed", {**audit, "utf16_code_units": value.get("utf16_code_units"), "chunks": value.get("chunks")})
        return {"hwnd": hwnd, **value}

    def key_press(self, hwnd: int, keys: str, *, target_selector: str | None = None) -> dict[str, Any]:
        if not self.policy.allow_keyboard_input:
            raise DesktopError("keyboard input is disabled by local policy", code="KEYBOARD_INPUT_DENIED")
        normalized = self._validate_keys(keys)
        winapp_keys = self._normalize_winapp_chords(normalized)
        selector = self._selector(target_selector, optional=True)
        target = self._authorize_window(hwnd)
        audit = self._target_audit(target, selector)
        audit.update({"keys_sha256": self._hash_text(normalized), "key_tokens": len(normalized.split())})
        self._audit("desktop.key_press", "started", audit)
        args = ["ui", "send-keys", winapp_keys, "-w", str(hwnd), "--via", "send-input", "--json"]
        if selector is not None:
            args.extend(["--target", selector])
        if self.policy.allow_system_keys:
            args.append("--allow-system-keys")
        try:
            value = self._run_json(args)
        except DesktopError as exc:
            self._audit("desktop.key_press", "failed", {**audit, "error": exc.code})
            raise
        self._audit("desktop.key_press", "passed", audit)
        return value if isinstance(value, dict) else {"result": value}

    def _action(
        self,
        action: str,
        hwnd: int,
        selector: str,
        args: list[str],
        *,
        extra: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        selector = self._selector(selector)
        target = self._authorize_window(hwnd)
        audit = self._target_audit(target, selector)
        if extra:
            audit.update(extra)
        self._audit(action, "started", audit)
        try:
            value = self._run_json([*args, "-w", str(hwnd), "--json"])
        except DesktopError as exc:
            self._audit(action, "failed", {**audit, "error": exc.code})
            raise
        self._audit(action, "passed", audit)
        return value if isinstance(value, dict) else {"result": value}

    def _require_uia_mutation(self) -> None:
        if not self.policy.allow_uia_mutation:
            raise DesktopError("UI Automation mutation is disabled by local policy", code="UIA_MUTATION_DENIED")

    def _raw_windows(self) -> list[dict[str, Any]]:
        value = self._run_json(["ui", "list-windows", "--json"])
        if not isinstance(value, list):
            raise DesktopError("WinApp list-windows returned an unexpected shape", code="WINAPP_PROTOCOL_ERROR")
        return [item for item in value if isinstance(item, dict)]

    def _authorize_window(self, hwnd: int) -> dict[str, Any]:
        if not isinstance(hwnd, int) or isinstance(hwnd, bool) or hwnd <= 0:
            raise DesktopError("hwnd must be a positive integer", code="INVALID_ARGUMENT")
        self._audit("desktop.window_authorize", "started", {"hwnd": hwnd})
        audit_fields: dict[str, Any] = {"hwnd": hwnd}
        try:
            for item in self._raw_windows():
                if item.get("hwnd") != hwnd:
                    continue
                process_name = item.get("processName")
                if not isinstance(process_name, str) or not process_name.strip():
                    raise DesktopError("window process identity is unavailable", code="WINDOW_IDENTITY_UNAVAILABLE")
                audit_fields["app"] = _normalize_app(process_name)
                self._authorize_app(process_name)
                self._audit("desktop.window_authorize", "passed", audit_fields)
                return item
            raise DesktopError("window is not currently visible", code="WINDOW_NOT_FOUND")
        except DesktopError as exc:
            self._audit("desktop.window_authorize", "failed", {**audit_fields, "error": exc.code})
            raise

    def _authorize_app(self, process_name: str) -> None:
        app = _normalize_app(process_name)
        if not app:
            raise DesktopError("window process identity is unavailable", code="WINDOW_IDENTITY_UNAVAILABLE")
        if app in self.policy.denied_apps:
            raise DesktopError("window process is denied by local policy", code="APP_DENIED")
        if not self.policy.allow_all_apps and app not in self.policy.allowed_apps:
            raise DesktopError("window process is not allowed by local policy", code="APP_DENIED")

    def _app_allowed(self, process_name: str) -> bool:
        try:
            self._authorize_app(process_name)
            return True
        except DesktopError:
            return False

    @staticmethod
    def _public_window(item: dict[str, Any]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key in ("hwnd", "processId", "processName", "title", "width", "height", "isForeground", "className"):
            if key in item:
                result[key] = item[key]
        return result

    def _selector(self, selector: str | None, *, optional: bool = False) -> str | None:
        if selector is None:
            if optional:
                return None
            raise DesktopError("selector is required", code="INVALID_ARGUMENT")
        if not isinstance(selector, str) or not selector.strip() or len(selector) > MAX_SELECTOR_CHARS:
            raise DesktopError(f"selector must contain 1-{MAX_SELECTOR_CHARS} characters", code="INVALID_ARGUMENT")
        selector = selector.strip()
        if COORDINATE_RE.fullmatch(selector):
            raise DesktopError("coordinate-only selectors are not exposed by Desktop MCP v1", code="COORDINATE_DENIED")
        return selector

    @staticmethod
    def _normalize_winapp_chords(keys: str) -> str:
        """Map caller-safe alphanumeric chord keys to explicit VKs for WinApp stability.

        WinApp CLI 0.6.0 resolves a single-character chord key with VkKeyScan(),
        which can return -1 in a Scheduled Task thread even when the interactive
        desktop uses a normal keyboard layout. The public Desktop API still rejects
        raw vk= syntax; this internal mapping covers only the already-validated
        ASCII a-z / 0-9 chord main keys.
        """
        normalized_tokens: list[str] = []
        modifiers = {"ctrl", "alt", "shift", "win"}
        for token in keys.split():
            parts = token.split("+")
            if len(parts) >= 2 and all(part in modifiers for part in parts[:-1]):
                main = parts[-1]
                if len(main) == 1 and "a" <= main <= "z":
                    main = f"vk=0x{ord(main.upper()):02X}"
                elif len(main) == 1 and "0" <= main <= "9":
                    main = f"vk=0x{ord(main):02X}"
                token = "+".join([*parts[:-1], main])
            normalized_tokens.append(token)
        return " ".join(normalized_tokens)

    def _validate_keys(self, keys: str) -> str:
        if not isinstance(keys, str) or not keys.strip() or len(keys) > MAX_KEY_CHARS:
            raise DesktopError(f"keys must contain 1-{MAX_KEY_CHARS} characters", code="INVALID_ARGUMENT")
        normalized = " ".join(keys.strip().split()).casefold()
        lowered = normalized.casefold()
        if "text=" in lowered or "vk=" in lowered or "\\" in normalized:
            raise DesktopError("literal-text/raw-virtual-key grammar is not allowed in key_press", code="KEYS_DENIED")
        tokens = normalized.split()
        if not all(KEY_TOKEN_RE.fullmatch(token) for token in tokens):
            raise DesktopError("keys contains an unsupported key token", code="KEYS_DENIED")
        hard_blocked = {"win+l", "ctrl+alt+delete", "ctrl+alt+del"}
        if any(token in hard_blocked for token in tokens):
            raise DesktopError("workstation-lock/Secure-Attention keys are prohibited", code="SYSTEM_KEYS_DENIED")
        system = {"alt+f4", "alt+tab", "ctrl+esc", "ctrl+shift+esc"}
        if not self.policy.allow_system_keys and any(token.startswith("win+") or token in system for token in tokens):
            raise DesktopError("system/shell-reserved keys are disabled by local policy", code="SYSTEM_KEYS_DENIED")
        return normalized

    def _run_json(self, args: list[str]) -> Any:
        result = self.runner.run(args, timeout_seconds=self.policy.max_command_seconds)
        if result.timed_out:
            raise DesktopError("WinApp command timed out", code="WINAPP_TIMEOUT")
        if result.stdout_truncated:
            raise DesktopError("WinApp stdout exceeded configured max_output_bytes", code="LIMIT_EXCEEDED")
        if result.exit_code != 0:
            raise DesktopError(
                "WinApp command failed",
                code="WINAPP_COMMAND_FAILED",
                data={"exit_code": result.exit_code, "stderr_sha256": hashlib.sha256(result.stderr).hexdigest()},
            )
        try:
            return json.loads(result.stdout.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise DesktopError("WinApp command returned invalid UTF-8 JSON", code="WINAPP_PROTOCOL_ERROR") from exc

    @staticmethod
    def _target_audit(target: dict[str, Any], selector: str | None = None) -> dict[str, Any]:
        fields: dict[str, Any] = {
            "hwnd": target.get("hwnd"),
            "app": _normalize_app(str(target.get("processName", ""))),
        }
        if selector is not None:
            fields["selector_sha256"] = hashlib.sha256(selector.encode("utf-8")).hexdigest()
        return fields

    def _audit(self, action: str, outcome: str, fields: dict[str, Any]) -> None:
        record = {
            "schema_version": 1,
            "event_id": str(uuid.uuid4()),
            "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "action": action,
            "outcome": outcome,
            **fields,
        }
        payload = json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n"
        try:
            with self._audit_lock:
                self._rotate_audit_if_needed(len(payload.encode("utf-8")))
                with self.policy.audit_log.open("a", encoding="utf-8", newline="\n") as handle:
                    handle.write(payload)
                    handle.flush()
        except OSError as exc:
            raise DesktopError("Desktop audit log is unavailable", code="AUDIT_UNAVAILABLE") from exc

    def _rotate_audit_if_needed(self, incoming_bytes: int) -> None:
        current = self.policy.audit_log.stat().st_size if self.policy.audit_log.exists() else 0
        if current + incoming_bytes <= self.policy.max_audit_bytes:
            return
        for index in range(self.policy.audit_backups, 0, -1):
            source = self.policy.audit_log if index == 1 else Path(f"{self.policy.audit_log}.{index - 1}")
            target = Path(f"{self.policy.audit_log}.{index}")
            if not source.exists():
                continue
            if index == self.policy.audit_backups and target.exists():
                target.unlink()
            os.replace(source, target)

    @staticmethod
    def _tail_lines(path: Path, limit: int, *, max_scan_bytes: int = 1024 * 1024) -> list[str]:
        with path.open("rb") as handle:
            handle.seek(0, os.SEEK_END)
            end = handle.tell()
            start = max(0, end - max_scan_bytes)
            handle.seek(start)
            raw = handle.read(end - start)
        if start > 0:
            first_newline = raw.find(b"\n")
            raw = b"" if first_newline < 0 else raw[first_newline + 1 :]
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise DesktopError("Desktop audit log is not valid UTF-8", code="AUDIT_CORRUPT") from exc
        return text.splitlines()[-limit:]

    @staticmethod
    def _hash_text(value: str) -> str:
        return hashlib.sha256(value.encode("utf-8")).hexdigest()

    @staticmethod
    def _hash_json(value: Any) -> str:
        payload = json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
        return hashlib.sha256(payload).hexdigest()
