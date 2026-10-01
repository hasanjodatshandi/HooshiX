"""Uninstalled single-server JIT admission/execution core (ADR-0030).

No sudoers, accounts, keys, firewall rules or production grants are installed here.
The future privileged entrypoint must supply caller identity and protected paths;
none of those authorities may be taken from request JSON or CLI arguments.
"""
from __future__ import annotations

import fcntl
import hashlib
import json
import os
import re
import stat
import subprocess
import tempfile
import time
import uuid
from dataclasses import dataclass
from pathlib import Path

NAMESPACE = "hooshix-jit-v1"
MAX_BYTES = 8192
MAX_RECORDS = 4096
SERVICE_PROPERTIES = (
    "Type=exec", "ExitType=cgroup", "TimeoutStartSec=3s", "TimeoutStopSec=1s",
    "KillMode=control-group", "KillSignal=SIGKILL", "SendSIGKILL=yes",
    "Restart=no", "RemainAfterExit=no", "Delegate=no", "NoNewPrivileges=yes",
    "ProtectControlGroups=yes", "ProtectSystem=strict", "ProtectHome=yes",
    "TasksMax=16", "MemoryMax=64M", "CPUQuota=10%",
)
OPERATIONS = {
    "service-inspect": ("/usr/bin/systemctl", "show", "--property=ActiveState",
                        "--property=SubState", "--value"),
    "service-restart": ("/usr/bin/systemctl", "try-restart"),
}
TARGETS = frozenset(("caddy.service", "k3s.service"))
LOCK_ROOT = Path("/run/hooshix/jit/locks")


class Denied(ValueError):
    """Allow-listed denial text; never includes the submitted payload."""


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Denied("duplicate field")
        result[key] = value
    return result


def canonical(data: dict) -> bytes:
    return json.dumps(data, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=True, allow_nan=False).encode("ascii")


def _uuid(value: object, version: int | None = None) -> bool:
    if not isinstance(value, str):
        return False
    try:
        parsed = uuid.UUID(value)
        return str(parsed) == value and (version is None or parsed.version == version)
    except ValueError:
        return False


@dataclass(frozen=True)
class Request:
    data: dict
    payload: bytes
    deadline_ns: int

    def remaining_seconds(self, now_ns: int) -> int:
        # Reserve bounded activation/termination time, never round up a grant.
        remaining = (self.deadline_ns - now_ns) // 1_000_000_000 - 5
        if remaining < 1:
            raise Denied("request expired")
        return remaining


def parse_request(raw: bytes, caller: str, boot_id: str, now_ns: int) -> Request:
    if not isinstance(raw, bytes) or len(raw) > MAX_BYTES:
        raise Denied("invalid request size")
    try:
        data = json.loads(raw, object_pairs_hook=_unique)
    except (ValueError, UnicodeError, RecursionError):
        raise Denied("invalid JSON") from None
    fields = {"schema_version", "request_id", "requester", "reviewer", "action",
              "target", "ticket", "duration_seconds", "boot_id", "issued_boottime_ns"}
    if not isinstance(data, dict) or set(data) != fields:
        raise Denied("invalid request fields")
    if type(data["schema_version"]) is not int or data["schema_version"] != 1:
        raise Denied("unsupported schema")
    if not _uuid(data["request_id"], 4) or not _uuid(data["boot_id"]):
        raise Denied("invalid request identity")
    if data["boot_id"] != boot_id:
        raise Denied("request belongs to another boot")
    if (not isinstance(caller, str) or not re.fullmatch(r"[a-z][a-z0-9_-]{0,31}", caller)
            or data["requester"] != caller or data["reviewer"] != caller):
        raise Denied("unauthorized caller or reviewer")
    if (not isinstance(data["action"], str) or data["action"] not in OPERATIONS
            or not isinstance(data["target"], str) or data["target"] not in TARGETS):
        raise Denied("unapproved operation")
    if not isinstance(data["ticket"], str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,63}", data["ticket"]):
        raise Denied("invalid ticket reference")
    duration = data["duration_seconds"]
    issued = data["issued_boottime_ns"]
    if type(duration) is not int or not 10 <= duration <= 1800:
        raise Denied("invalid lifetime")
    if type(issued) is not int or issued < 0 or not 0 <= now_ns - issued <= 300_000_000_000:
        raise Denied("invalid request time")
    request = Request(data, canonical(data), issued + duration * 1_000_000_000)
    request.remaining_seconds(now_ns)
    return request


def protected_fd(path: Path, uid: int, directory: bool = False) -> int:
    flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK
    if directory:
        flags |= os.O_DIRECTORY
    try:
        fd = os.open(path, flags)
    except OSError:
        raise Denied("protected path unavailable") from None
    info = os.fstat(fd)
    expected = 0o700 if directory else 0o600
    kind = stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)
    if (not kind or info.st_uid != uid or stat.S_IMODE(info.st_mode) != expected
            or (not directory and info.st_nlink != 1)):
        os.close(fd)
        raise Denied("unsafe protected path")
    return fd


def verify_approval(request: Request, signature: bytes, signers: Path, private: Path, uid: int) -> None:
    if not isinstance(signature, bytes) or not 1 <= len(signature) <= MAX_BYTES:
        raise Denied("invalid approval size")
    directory_fd = protected_fd(private, uid, True)
    signers_fd = None
    try:
        signers_fd = protected_fd(signers, uid)
        inventory = os.read(signers_fd, MAX_BYTES + 1)
        # Exact named identity and namespace, no wildcard principal or CA approval.
        pattern = (rb'([a-z][a-z0-9_-]{0,31}) namespaces="hooshix-jit-v1" '
                   rb'ssh-ed25519 ([A-Za-z0-9+/]+={0,2})\n?')
        match = re.fullmatch(pattern, inventory)
        if not match or match[1].decode("ascii") != request.data["reviewer"]:
            raise Denied("invalid reviewer inventory")
        with tempfile.TemporaryDirectory(prefix="verify-", dir=private) as temp:
            signature_file = Path(temp) / "approval.sig"
            signature_file.write_bytes(signature)
            signature_file.chmod(0o600)
            # Pass the already-open inventory inode; do not reopen an operator path.
            result = subprocess.run(
                ["/usr/bin/ssh-keygen", "-Y", "verify", "-f", f"/proc/self/fd/{signers_fd}",
                 "-I", request.data["reviewer"], "-n", NAMESPACE, "-s", str(signature_file)],
                input=request.payload, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                timeout=5, check=False, pass_fds=(signers_fd,),
                env={"PATH": "/usr/bin:/bin", "LC_ALL": "C"},
            )
            if result.returncode != 0:
                raise Denied("approval signature rejected")
    except (OSError, subprocess.TimeoutExpired):
        raise Denied("approval verification unavailable") from None
    finally:
        if signers_fd is not None:
            os.close(signers_fd)
        os.close(directory_fd)


def consume_once(request: Request, ledger: Path, uid: int) -> None:
    fd = protected_fd(ledger, uid, True)
    try:
        # Serialize capacity check and durable O_EXCL insert across all callers.
        fcntl.flock(fd, fcntl.LOCK_EX)
        with os.scandir(fd) as entries:
            if sum(1 for _ in entries) >= MAX_RECORDS:
                raise Denied("replay ledger full")
        try:
            record = os.open(request.data["request_id"], os.O_WRONLY | os.O_CREAT |
                             os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC, 0o600, dir_fd=fd)
        except FileExistsError:
            raise Denied("request already consumed") from None
        with os.fdopen(record, "wb") as stream:
            stream.write(request.payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.fsync(fd)
    except OSError:
        raise Denied("replay ledger unavailable") from None
    finally:
        os.close(fd)


def validate_audit_ack(request: Request, ack: bytes) -> None:
    # Only a protected audit adapter may supply this receipt, never the requester.
    if not isinstance(ack, bytes) or len(ack) > MAX_BYTES:
        raise Denied("audit acknowledgement missing")
    try:
        value = json.loads(ack, object_pairs_hook=_unique)
    except (ValueError, UnicodeError, RecursionError):
        raise Denied("invalid audit acknowledgement") from None
    expected = hashlib.sha256(request.payload).hexdigest()
    if (not isinstance(value, dict) or set(value) != {"sha256", "version_id"}
            or value["sha256"] != expected or not isinstance(value["version_id"], str)
            or value["version_id"] == "null"
            or not re.fullmatch(r"[A-Za-z0-9_.+/=-]{1,256}", value["version_id"])):
        raise Denied("audit acknowledgement mismatch")


def service_unit(request: Request, caller_uid: int) -> str:
    return f"hooshix-jit-u{caller_uid}-r{uuid.UUID(request.data['request_id']).hex}.service"


def service_command(request: Request, caller_uid: int, now_ns: int) -> list[str]:
    if type(caller_uid) is not int or caller_uid < 1:
        raise Denied("invalid caller uid")
    remaining = request.remaining_seconds(now_ns)
    # Unique unit names make cancellation request-bound. The in-job native lock
    # prevents overlapping operator jobs even if their submission broker dies.
    argv = ["/usr/bin/systemd-run", "--quiet", "--wait", "--pipe", "--collect",
            f"--unit={service_unit(request, caller_uid)}",
            f"--description=hooshix-jit:{request.data['request_id']}"]
    for prop in (*SERVICE_PROPERTIES, f"RuntimeMaxSec={remaining}s", f"ReadWritePaths={LOCK_ROOT}"):
        argv.extend(("--property", prop))
    argv.extend(("--", "/usr/bin/flock", "--nonblock", "--no-fork", "--",
                 str(LOCK_ROOT / f"u{caller_uid}.lock"),
                 *OPERATIONS[request.data["action"]], "--", request.data["target"]))
    return argv


def prepare_execution(raw: bytes, signature: bytes, *, caller: str, caller_uid: int,
                      boot_id: str, signers: Path, ledger: Path, private: Path,
                      protected_uid: int, audit) -> list[str]:
    """Trusted broker integration seam, NOT an installed sudo/production entrypoint.

    audit(request.payload) must synchronously validate local OS audit and return
    an off-host durable receipt. Missing/failed audit never returns an executable
    command. A consumed request is never retried after ambiguous delivery.
    """
    if type(caller_uid) is not int or caller_uid < 1:
        raise Denied("invalid caller uid")
    request = parse_request(raw, caller, boot_id, time.clock_gettime_ns(time.CLOCK_BOOTTIME))
    verify_approval(request, signature, signers, private, protected_uid)
    consume_once(request, ledger, protected_uid)
    if audit is None:
        raise Denied("protected audit adapter missing")
    try:
        ack = audit(request.payload)
    except Exception:
        raise Denied("audit delivery unavailable") from None
    validate_audit_ack(request, ack)
    return service_command(request, caller_uid, time.clock_gettime_ns(time.CLOCK_BOOTTIME))
