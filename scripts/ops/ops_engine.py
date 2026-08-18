#!/usr/bin/env python3
"""Policy-gated developer-host operations engine for HooshiX Ops MCP."""

from __future__ import annotations

import hashlib
import json
import locale
import os
import re
import shutil
import signal
import subprocess
import threading
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, BinaryIO

POLICY_SCHEMA_VERSION = 1
MAX_POLICY_BYTES = 1024 * 1024
MAX_PURPOSE_CHARS = 512
MAX_PATH_CHARS = 4096
MAX_ARGUMENTS = 128
MAX_ARGUMENT_CHARS = 8192
MAX_ARGUMENT_BYTES = 32768
SAFE_ENVIRONMENT_KEYS = {
    "APPDATA",
    "COMSPEC",
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
SECRET_ENV_RE = re.compile(
    r"(?:TOKEN|SECRET|PASSWORD|PASSWD|PRIVATE[_-]?KEY|API[_-]?KEY|CREDENTIAL|COOKIE|SESSION)",
    re.IGNORECASE,
)
COMMAND_ALIAS_RE = re.compile(r"^[a-z][a-z0-9._-]{0,63}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
POLICY_FIELDS = {
    "schema_version",
    "allowed_roots",
    "denied_roots",
    "commands",
    "audit_log",
    "require_elevated",
    "allow_process_execution",
    "allow_elevated_mutation",
    "allow_elevated_process_execution",
    "max_command_seconds",
    "max_output_bytes",
    "max_file_bytes",
    "max_list_entries",
    "max_audit_bytes",
    "audit_backups",
}


class OpsError(RuntimeError):
    """Expected policy, validation, or operation failure."""

    def __init__(self, message: str, *, code: str = "OPS_ERROR", data: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.data = data or {}


@dataclass(frozen=True)
class OpsPolicy:
    path: Path
    fingerprint: str
    allowed_roots: tuple[Path, ...]
    denied_roots: tuple[Path, ...]
    commands: dict[str, Path]
    audit_log: Path
    require_elevated: bool
    allow_process_execution: bool
    allow_elevated_mutation: bool
    allow_elevated_process_execution: bool
    max_command_seconds: int
    max_output_bytes: int
    max_file_bytes: int
    max_list_entries: int
    max_audit_bytes: int
    audit_backups: int


@dataclass(frozen=True)
class AuthorizedPath:
    requested: Path
    resolved: Path
    root: Path


def _canonical(path: Path) -> Path:
    return path.expanduser().resolve(strict=False)


def _path_key(path: Path) -> str:
    return os.path.normcase(os.path.abspath(os.fspath(path)))


def _is_within(path: Path, root: Path) -> bool:
    path_key = _path_key(path)
    root_key = _path_key(root)
    try:
        return os.path.commonpath([path_key, root_key]) == root_key
    except ValueError:
        return False


def _is_elevated() -> bool:
    if os.name == "nt":
        try:
            import ctypes

            return bool(ctypes.windll.shell32.IsUserAnAdmin())
        except (AttributeError, OSError):
            return False
    geteuid = getattr(os, "geteuid", None)
    return bool(geteuid is not None and geteuid() == 0)


def _strict_json_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise OpsError(f"Ops policy contains duplicate JSON key: {key}", code="INVALID_POLICY")
        result[key] = value
    return result


def _require_int(data: dict[str, Any], key: str, minimum: int, maximum: int) -> int:
    value = data.get(key)
    if not isinstance(value, int) or isinstance(value, bool) or not minimum <= value <= maximum:
        raise OpsError(
            f"policy field {key} must be an integer in [{minimum}, {maximum}]",
            code="INVALID_POLICY",
        )
    return value


def load_policy(path: str | os.PathLike[str]) -> OpsPolicy:
    policy_path = _canonical(Path(path))
    if not policy_path.is_file():
        raise OpsError(f"Ops policy file does not exist: {policy_path}", code="INVALID_POLICY")
    raw = policy_path.read_bytes()
    if len(raw) > MAX_POLICY_BYTES:
        raise OpsError("Ops policy exceeds 1 MiB", code="INVALID_POLICY")
    try:
        data = json.loads(raw.decode("utf-8"), object_pairs_hook=_strict_json_object)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise OpsError(f"Ops policy is not valid UTF-8 JSON: {exc}", code="INVALID_POLICY") from exc
    if not isinstance(data, dict) or data.get("schema_version") != POLICY_SCHEMA_VERSION:
        raise OpsError("Ops policy schema_version must be 1", code="INVALID_POLICY")
    missing_fields = sorted(POLICY_FIELDS - set(data))
    if missing_fields:
        raise OpsError(
            "Ops policy is missing required fields: " + ", ".join(missing_fields),
            code="INVALID_POLICY",
        )
    extra_fields = sorted(set(data) - POLICY_FIELDS)
    if extra_fields:
        raise OpsError(
            "Ops policy contains unsupported fields: " + ", ".join(extra_fields),
            code="INVALID_POLICY",
        )

    allowed_raw = data.get("allowed_roots")
    if not isinstance(allowed_raw, list) or not allowed_raw:
        raise OpsError("Ops policy allowed_roots must be a non-empty array", code="INVALID_POLICY")
    allowed: list[Path] = []
    for item in allowed_raw:
        if not isinstance(item, str) or not item.strip() or len(item) > MAX_PATH_CHARS:
            raise OpsError("Ops policy contains an invalid allowed root", code="INVALID_POLICY")
        candidate = Path(item).expanduser()
        if not candidate.is_absolute():
            raise OpsError(f"allowed root must be absolute: {item}", code="INVALID_POLICY")
        allowed.append(_canonical(candidate))
    if len({_path_key(item) for item in allowed}) != len(allowed):
        raise OpsError("Ops policy allowed_roots contains duplicate canonical paths", code="INVALID_POLICY")

    denied_raw = data.get("denied_roots")
    if not isinstance(denied_raw, list):
        raise OpsError("Ops policy denied_roots must be an array", code="INVALID_POLICY")
    denied: list[Path] = []
    for item in denied_raw:
        if not isinstance(item, str) or not item.strip() or len(item) > MAX_PATH_CHARS:
            raise OpsError("Ops policy contains an invalid denied root", code="INVALID_POLICY")
        candidate = Path(item).expanduser()
        if not candidate.is_absolute():
            raise OpsError(f"denied root must be absolute: {item}", code="INVALID_POLICY")
        denied.append(_canonical(candidate))
    if len({_path_key(item) for item in denied}) != len(denied):
        raise OpsError("Ops policy denied_roots contains duplicate canonical paths", code="INVALID_POLICY")

    commands_raw = data.get("commands")
    if not isinstance(commands_raw, dict) or not commands_raw:
        raise OpsError("Ops policy commands must be a non-empty object", code="INVALID_POLICY")
    commands: dict[str, Path] = {}
    for alias, item in commands_raw.items():
        if not isinstance(alias, str) or not COMMAND_ALIAS_RE.fullmatch(alias):
            raise OpsError(f"invalid command alias: {alias!r}", code="INVALID_POLICY")
        if not isinstance(item, str) or not item.strip() or len(item) > MAX_PATH_CHARS:
            raise OpsError(f"invalid command path for alias {alias}", code="INVALID_POLICY")
        candidate = Path(item).expanduser()
        if not candidate.is_absolute():
            raise OpsError(f"command path must be absolute for alias {alias}", code="INVALID_POLICY")
        commands[alias] = _canonical(candidate)

    audit_raw = data.get("audit_log")
    if not isinstance(audit_raw, str) or not audit_raw.strip() or len(audit_raw) > MAX_PATH_CHARS:
        raise OpsError("Ops policy audit_log must be an absolute path", code="INVALID_POLICY")
    audit_path = Path(audit_raw).expanduser()
    if not audit_path.is_absolute():
        raise OpsError("Ops policy audit_log must be an absolute path", code="INVALID_POLICY")
    audit_path = _canonical(audit_path)

    bool_fields: dict[str, bool] = {}
    for key in (
        "require_elevated",
        "allow_process_execution",
        "allow_elevated_mutation",
        "allow_elevated_process_execution",
    ):
        value = data.get(key)
        if not isinstance(value, bool):
            raise OpsError(f"Ops policy {key} must be boolean", code="INVALID_POLICY")
        bool_fields[key] = value

    policy = OpsPolicy(
        path=policy_path,
        fingerprint=hashlib.sha256(raw).hexdigest(),
        allowed_roots=tuple(dict.fromkeys(allowed)),
        denied_roots=tuple(dict.fromkeys(denied)),
        commands=commands,
        audit_log=audit_path,
        require_elevated=bool_fields["require_elevated"],
        allow_process_execution=bool_fields["allow_process_execution"],
        allow_elevated_mutation=bool_fields["allow_elevated_mutation"],
        allow_elevated_process_execution=bool_fields["allow_elevated_process_execution"],
        max_command_seconds=_require_int(data, "max_command_seconds", 1, 3600),
        max_output_bytes=_require_int(data, "max_output_bytes", 1024, 16 * 1024 * 1024),
        max_file_bytes=_require_int(data, "max_file_bytes", 1024, 16 * 1024 * 1024),
        max_list_entries=_require_int(data, "max_list_entries", 1, 5000),
        max_audit_bytes=_require_int(data, "max_audit_bytes", 1024 * 1024, 1024 * 1024 * 1024),
        audit_backups=_require_int(data, "audit_backups", 1, 20),
    )
    if policy.require_elevated and not _is_elevated():
        raise OpsError(
            "Ops policy requires an elevated process, but the server is not elevated",
            code="ELEVATION_REQUIRED",
        )
    return policy


class OpsEngine:
    def __init__(self, policy: OpsPolicy) -> None:
        self.policy = policy
        self._audit_lock = threading.Lock()
        try:
            self.policy.audit_log.parent.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            raise OpsError(
                "Ops audit directory is unavailable",
                code="AUDIT_UNAVAILABLE",
            ) from exc
        self._audit("ops.start", "passed", {"policy_fingerprint": policy.fingerprint})

    @classmethod
    def from_policy_path(cls, path: str | os.PathLike[str]) -> "OpsEngine":
        return cls(load_policy(path))

    def status(self) -> dict[str, Any]:
        return {
            "schema_version": 1,
            "mode": "developer-host-ops",
            "elevated": _is_elevated(),
            "require_elevated": self.policy.require_elevated,
            "allow_process_execution": self.policy.allow_process_execution,
            "allow_elevated_mutation": self.policy.allow_elevated_mutation,
            "allow_elevated_process_execution": self.policy.allow_elevated_process_execution,
            "policy_fingerprint": self.policy.fingerprint,
            "policy_path": str(self.policy.path),
            "allowed_roots": [str(path) for path in self.policy.allowed_roots],
            "denied_roots": [str(path) for path in self.policy.denied_roots],
            "command_aliases": sorted(self.policy.commands),
            "limits": {
                "max_command_seconds": self.policy.max_command_seconds,
                "max_output_bytes": self.policy.max_output_bytes,
                "max_file_bytes": self.policy.max_file_bytes,
                "max_list_entries": self.policy.max_list_entries,
                "max_audit_bytes": self.policy.max_audit_bytes,
                "audit_backups": self.policy.audit_backups,
            },
        }

    def audit_tail(self, limit: int = 20) -> dict[str, Any]:
        if not isinstance(limit, int) or isinstance(limit, bool) or not 1 <= limit <= 100:
            raise OpsError("audit limit must be an integer in [1, 100]", code="INVALID_ARGUMENT")
        if not self.policy.audit_log.exists():
            return {"entries": []}
        lines = self._tail_lines(self.policy.audit_log, limit)
        entries: list[dict[str, Any]] = []
        for line in lines:
            try:
                item = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(item, dict):
                entries.append(item)
        return {"entries": entries}

    def stat(self, path: str) -> dict[str, Any]:
        auth = self._authorize(path, must_exist=True)
        p = auth.requested
        info = p.lstat()
        return {
            "path": str(p),
            "resolved_path": str(auth.resolved),
            "type": "symlink" if p.is_symlink() else "directory" if p.is_dir() else "file" if p.is_file() else "other",
            "size": info.st_size,
            "mtime_ns": info.st_mtime_ns,
            "sha256": self._file_sha256(p) if p.is_file() and not p.is_symlink() else None,
        }

    def list_dir(self, path: str, limit: int | None = None) -> dict[str, Any]:
        auth = self._authorize(path, must_exist=True)
        if not auth.requested.is_dir():
            raise OpsError("filesystem.list requires a directory", code="NOT_DIRECTORY")
        effective_limit = self.policy.max_list_entries if limit is None else limit
        if not isinstance(effective_limit, int) or isinstance(effective_limit, bool) or not 1 <= effective_limit <= self.policy.max_list_entries:
            raise OpsError(
                f"list limit must be an integer in [1, {self.policy.max_list_entries}]",
                code="INVALID_ARGUMENT",
            )
        entries: list[dict[str, Any]] = []
        truncated = False
        for index, child in enumerate(sorted(auth.requested.iterdir(), key=lambda item: item.name.casefold())):
            if index >= effective_limit:
                truncated = True
                break
            try:
                child_auth = self._authorize(str(child), must_exist=True)
                child_type = "symlink" if child.is_symlink() else "directory" if child.is_dir() else "file" if child.is_file() else "other"
                entries.append({"name": child.name, "path": str(child), "resolved_path": str(child_auth.resolved), "type": child_type})
            except OpsError:
                entries.append({"name": child.name, "path": str(child), "type": "denied"})
        return {"path": str(auth.requested), "entries": entries, "truncated": truncated}

    def read_text(self, path: str) -> dict[str, Any]:
        auth = self._authorize(path, must_exist=True)
        p = auth.requested
        if not p.is_file() or p.is_symlink():
            raise OpsError("filesystem.read_text requires a regular file", code="NOT_FILE")
        size = p.stat().st_size
        if size > self.policy.max_file_bytes:
            raise OpsError(
                f"file exceeds configured max_file_bytes ({self.policy.max_file_bytes})",
                code="LIMIT_EXCEEDED",
            )
        raw = p.read_bytes()
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise OpsError("file is not valid UTF-8 text", code="INVALID_TEXT_FILE") from exc
        return {"path": str(p), "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw), "text": text}

    def write_text(
        self,
        path: str,
        text: str,
        *,
        purpose: str,
        expected_sha256: str | None = None,
        create_parents: bool = False,
    ) -> dict[str, Any]:
        purpose = self._validate_purpose(purpose)
        self._guard_mutation()
        if not isinstance(text, str):
            raise OpsError("text must be a string", code="INVALID_ARGUMENT")
        raw = text.encode("utf-8")
        if len(raw) > self.policy.max_file_bytes:
            raise OpsError(
                f"text exceeds configured max_file_bytes ({self.policy.max_file_bytes})",
                code="LIMIT_EXCEEDED",
            )
        if expected_sha256 is not None and (not isinstance(expected_sha256, str) or not SHA256_RE.fullmatch(expected_sha256)):
            raise OpsError("expected_sha256 must be a lowercase SHA-256 hex digest", code="INVALID_ARGUMENT")
        auth = self._authorize(path, must_exist=False)
        p = auth.requested
        if p.exists() and p.is_dir():
            raise OpsError("filesystem.write_text target is a directory", code="IS_DIRECTORY")
        if p.exists() and p.is_symlink():
            raise OpsError("filesystem.write_text does not write through symlinks", code="SYMLINK_DENIED")
        if expected_sha256 is not None:
            if not p.is_file():
                raise OpsError("expected_sha256 was supplied but target is not an existing file", code="PRECONDITION_FAILED")
            actual = self._file_sha256(p)
            if actual != expected_sha256:
                raise OpsError(
                    "target SHA-256 does not match expected_sha256",
                    code="PRECONDITION_FAILED",
                    data={"actual_sha256": actual},
                )
        audit_base = {
            "path": str(p),
            "purpose_sha256": self._hash_text(purpose),
        }
        self._audit("filesystem.write_text", "started", audit_base)
        parent = p.parent
        if create_parents:
            self._authorize(str(parent), must_exist=False)
            parent.mkdir(parents=True, exist_ok=True)
        if not parent.is_dir():
            raise OpsError("target parent directory does not exist", code="PARENT_MISSING")
        self._authorize(str(parent), must_exist=True)

        temp_path = parent / f".{p.name}.hooshix-{uuid.uuid4().hex}.tmp"
        try:
            with temp_path.open("xb") as handle:
                handle.write(raw)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp_path, p)
        finally:
            if temp_path.exists():
                temp_path.unlink(missing_ok=True)
        result = {"path": str(p), "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}
        self._audit("filesystem.write_text", "passed", {"path": str(p), "purpose_sha256": self._hash_text(purpose), **result})
        return result

    def mkdir(self, path: str, *, purpose: str, parents: bool = True) -> dict[str, Any]:
        purpose = self._validate_purpose(purpose)
        self._guard_mutation()
        if not isinstance(parents, bool):
            raise OpsError("parents must be boolean", code="INVALID_ARGUMENT")
        auth = self._authorize(path, must_exist=False)
        p = auth.requested
        self._audit(
            "filesystem.mkdir",
            "started",
            {"path": str(p), "purpose_sha256": self._hash_text(purpose)},
        )
        p.mkdir(parents=parents, exist_ok=False)
        self._authorize(str(p), must_exist=True)
        result = {"path": str(p), "created": True}
        self._audit("filesystem.mkdir", "passed", {"path": str(p), "purpose_sha256": self._hash_text(purpose)})
        return result

    def delete(self, path: str, *, purpose: str, recursive: bool = False) -> dict[str, Any]:
        purpose = self._validate_purpose(purpose)
        self._guard_mutation()
        if not isinstance(recursive, bool):
            raise OpsError("recursive must be boolean", code="INVALID_ARGUMENT")
        auth = self._authorize(path, must_exist=True)
        p = auth.requested
        if any(_path_key(auth.resolved) == _path_key(root) for root in self.policy.allowed_roots):
            raise OpsError("deleting an allowed root is prohibited", code="ROOT_DELETE_DENIED")
        kind = "symlink" if p.is_symlink() else "directory" if p.is_dir() else "file"
        self._audit(
            "filesystem.delete",
            "started",
            {
                "path": str(p),
                "deleted_type": kind,
                "recursive": recursive,
                "purpose_sha256": self._hash_text(purpose),
            },
        )
        if p.is_symlink() or p.is_file():
            p.unlink()
        elif p.is_dir():
            if recursive:
                shutil.rmtree(p)
            else:
                p.rmdir()
        else:
            raise OpsError("unsupported filesystem object type", code="UNSUPPORTED_FILE_TYPE")
        result = {"path": str(p), "deleted_type": kind, "recursive": recursive}
        self._audit("filesystem.delete", "passed", {**result, "purpose_sha256": self._hash_text(purpose)})
        return result

    def run_process(
        self,
        command: str,
        args: list[str],
        *,
        cwd: str,
        purpose: str,
        timeout_seconds: int | None = None,
    ) -> dict[str, Any]:
        purpose = self._validate_purpose(purpose)
        self._guard_process_execution()
        if not isinstance(command, str) or command not in self.policy.commands:
            raise OpsError("command is not an allowed policy alias", code="COMMAND_DENIED")
        if not isinstance(args, list) or not all(isinstance(item, str) for item in args):
            raise OpsError("args must be an array of strings", code="INVALID_ARGUMENT")
        if len(args) > MAX_ARGUMENTS:
            raise OpsError(f"args exceeds {MAX_ARGUMENTS} entries", code="LIMIT_EXCEEDED")
        if any(len(item) > MAX_ARGUMENT_CHARS for item in args):
            raise OpsError("a process argument exceeds the configured character limit", code="LIMIT_EXCEEDED")
        if sum(len(item.encode("utf-8")) for item in args) > MAX_ARGUMENT_BYTES:
            raise OpsError("process arguments exceed the configured byte limit", code="LIMIT_EXCEEDED")
        cwd_auth = self._authorize(cwd, must_exist=True)
        if not cwd_auth.requested.is_dir():
            raise OpsError("cwd must be a directory", code="NOT_DIRECTORY")
        executable = self.policy.commands[command]
        if not executable.is_file():
            raise OpsError(f"configured command does not exist: {command}", code="COMMAND_UNAVAILABLE")
        timeout = self.policy.max_command_seconds if timeout_seconds is None else timeout_seconds
        if not isinstance(timeout, int) or isinstance(timeout, bool) or not 1 <= timeout <= self.policy.max_command_seconds:
            raise OpsError(
                f"timeout_seconds must be an integer in [1, {self.policy.max_command_seconds}]",
                code="INVALID_ARGUMENT",
            )

        argv = [str(executable), *args]
        audit_base = {
            "command": command,
            "cwd": str(cwd_auth.requested),
            "argument_count": len(args),
            "arguments_sha256": self._hash_json(args),
            "purpose_sha256": self._hash_text(purpose),
        }
        self._audit("process.run", "started", audit_base)
        started = time.monotonic()
        creationflags = 0
        start_new_session = False
        if os.name == "nt":
            creationflags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
        else:
            start_new_session = True
        try:
            process = subprocess.Popen(
                argv,
                cwd=str(cwd_auth.requested),
                env=self._child_environment(),
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                creationflags=creationflags,
                start_new_session=start_new_session,
            )
        except OSError as exc:
            self._audit("process.run", "failed", {**audit_base, "error_type": type(exc).__name__})
            raise OpsError(f"failed to start command alias {command}: {exc}", code="PROCESS_START_FAILED") from exc

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
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            self._terminate_process_tree(process)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        finally:
            for thread in threads:
                thread.join(timeout=5)

        duration_ms = int((time.monotonic() - started) * 1000)
        result = {
            "command": command,
            "exit_code": process.returncode,
            "timed_out": timed_out,
            "duration_ms": duration_ms,
            "stdout": self._decode_output(bytes(stdout_state["data"])),
            "stderr": self._decode_output(bytes(stderr_state["data"])),
            "stdout_truncated": bool(stdout_state["truncated"]),
            "stderr_truncated": bool(stderr_state["truncated"]),
        }
        outcome = "timed_out" if timed_out else "passed" if process.returncode == 0 else "failed"
        self._audit(
            "process.run",
            outcome,
            {
                **audit_base,
                "exit_code": process.returncode,
                "timed_out": timed_out,
                "duration_ms": duration_ms,
                "stdout_truncated": result["stdout_truncated"],
                "stderr_truncated": result["stderr_truncated"],
            },
        )
        return result

    def _authorize(self, raw_path: str, *, must_exist: bool) -> AuthorizedPath:
        if not isinstance(raw_path, str) or not raw_path.strip() or len(raw_path) > MAX_PATH_CHARS:
            raise OpsError("path must be a non-empty bounded string", code="INVALID_ARGUMENT")
        if raw_path.startswith(("\\\\?\\", "\\\\.\\")):
            raise OpsError("Windows device paths are prohibited", code="PATH_DENIED")
        candidate = Path(raw_path).expanduser()
        if not candidate.is_absolute():
            raise OpsError("path must be absolute", code="PATH_DENIED")
        requested = Path(os.path.abspath(os.fspath(candidate)))
        lexical_roots = [root for root in self.policy.allowed_roots if _is_within(requested, root)]
        if not lexical_roots:
            raise OpsError("path is outside configured allowed_roots", code="PATH_DENIED")
        if any(_is_within(requested, denied) for denied in self.policy.denied_roots):
            raise OpsError("path is inside a configured denied_root", code="PATH_DENIED")
        if must_exist and not requested.exists() and not requested.is_symlink():
            raise OpsError(f"path does not exist: {requested}", code="PATH_NOT_FOUND")
        resolved = requested.resolve(strict=False)
        roots = [root for root in lexical_roots if _is_within(resolved, root)]
        if not roots:
            raise OpsError("path resolves outside configured allowed_roots", code="PATH_DENIED")
        if any(_is_within(resolved, denied) for denied in self.policy.denied_roots):
            raise OpsError("path resolves inside a configured denied_root", code="PATH_DENIED")
        root = max(roots, key=lambda item: len(_path_key(item)))
        return AuthorizedPath(requested=requested, resolved=resolved, root=root)

    def _validate_purpose(self, purpose: str) -> str:
        if not isinstance(purpose, str) or not purpose.strip() or len(purpose) > MAX_PURPOSE_CHARS:
            raise OpsError(f"purpose must contain 1-{MAX_PURPOSE_CHARS} characters", code="INVALID_ARGUMENT")
        return purpose.strip()

    def _guard_mutation(self) -> None:
        if _is_elevated() and not self.policy.allow_elevated_mutation:
            raise OpsError(
                "elevated filesystem mutation is disabled by local policy",
                code="ELEVATED_MUTATION_DENIED",
            )

    def _guard_process_execution(self) -> None:
        if not self.policy.allow_process_execution:
            raise OpsError("process execution is disabled by local policy", code="PROCESS_EXECUTION_DENIED")
        if _is_elevated() and not self.policy.allow_elevated_process_execution:
            raise OpsError(
                "elevated process execution is disabled by local policy",
                code="ELEVATED_PROCESS_DENIED",
            )

    def _child_environment(self) -> dict[str, str]:
        environment: dict[str, str] = {}
        for key, value in os.environ.items():
            if key.upper() not in SAFE_ENVIRONMENT_KEYS:
                continue
            if SECRET_ENV_RE.search(key):
                continue
            environment[key] = value
        environment["HOOSHIX_OPS_CHILD"] = "1"
        return environment

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
                if len(chunk) > remaining:
                    state["truncated"] = True
        finally:
            stream.close()

    @staticmethod
    def _decode_output(raw: bytes) -> str:
        if not raw:
            return ""
        encodings = ["utf-8", locale.getpreferredencoding(False)]
        if os.name == "nt":
            encodings.extend(["cp65001", "cp1252"])
        seen: set[str] = set()
        for encoding in encodings:
            if not encoding or encoding.lower() in seen:
                continue
            seen.add(encoding.lower())
            try:
                return raw.decode(encoding)
            except (LookupError, UnicodeDecodeError):
                continue
        return raw.decode("utf-8", errors="replace")

    @staticmethod
    def _terminate_process_tree(process: subprocess.Popen[bytes]) -> None:
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
            raise OpsError(
                "Ops audit log is unavailable",
                code="AUDIT_UNAVAILABLE",
            ) from exc

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
            raise OpsError("Ops audit log is not valid UTF-8", code="AUDIT_CORRUPT") from exc
        return text.splitlines()[-limit:]

    @staticmethod
    def _file_sha256(path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()

    @staticmethod
    def _hash_text(value: str) -> str:
        return hashlib.sha256(value.encode("utf-8")).hexdigest()

    @staticmethod
    def _hash_json(value: Any) -> str:
        payload = json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
        return hashlib.sha256(payload).hexdigest()
