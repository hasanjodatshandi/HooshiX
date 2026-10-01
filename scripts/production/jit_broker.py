#!/usr/bin/env python3
"""JIT operator CLI and uninstalled protected broker; see production-jit-usage-fa.md.

Only `request` and `bundle` work from a developer checkout. Privileged operations require the
fixed root-owned installation and a real protected audit adapter. No installer,
sudoers change, provider probe or production bootstrap is performed by this file.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import pwd
import re
import resource
import select
import signal
import stat
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

CODE = Path("/usr/local/libexec/hooshix-jit")
CONFIG = Path("/etc/hooshix/jit")
STATE = Path("/var/lib/hooshix/jit")
ENV = {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LC_ALL": "C"}
LIMIT = 8192


class BrokerDenied(ValueError):
    pass


def root_path(path: Path, mode: int, directory: bool = False) -> None:
    """Reject writable/symlink ancestors before trusting privileged code/config."""
    if not path.is_absolute() or ".." in path.parts:
        raise BrokerDenied("unsafe installation path")
    for item in (*reversed(path.parents), path):
        try:
            info = item.lstat()
        except OSError:
            raise BrokerDenied("protected installation incomplete") from None
        if (info.st_uid != 0 or stat.S_ISLNK(info.st_mode)
                or stat.S_IMODE(info.st_mode) & 0o022):
            raise BrokerDenied("unsafe installation ownership")
        if item != path and not stat.S_ISDIR(info.st_mode):
            raise BrokerDenied("unsafe installation ancestor")
        if item == path:
            kind = stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)
            if (stat.S_IMODE(info.st_mode) != mode or not kind
                    or (not directory and info.st_nlink != 1)):
                raise BrokerDenied("unsafe installation file")


def load_core(privileged: bool):
    source = CODE / "jit_runtime.py" if privileged else Path(__file__).with_name("jit_runtime.py")
    if privileged:
        if Path(__file__).absolute() != CODE / "jit_broker.py":
            raise BrokerDenied("broker is not installed")
        root_path(CODE / "jit_broker.py", 0o644)
        root_path(source, 0o644)
    spec = importlib.util.spec_from_file_location("hooshix_jit_runtime", source)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def caller_identity(core, environ: dict) -> tuple[str, int]:
    if os.geteuid() != 0:
        raise BrokerDenied("privileged broker requires the reviewed sudo entrypoint")
    root_path(CONFIG / "operator.json", 0o600)
    fd = core.protected_fd(CONFIG / "operator.json", 0)
    try:
        raw = os.read(fd, LIMIT + 1)
    finally:
        os.close(fd)
    try:
        policy = json.loads(raw, object_pairs_hook=core._unique)
    except (ValueError, UnicodeError, RecursionError):
        raise BrokerDenied("invalid operator inventory") from None
    if (len(raw) > LIMIT or not isinstance(policy, dict)
            or set(policy) != {"schema_version", "operator", "uid"}
            or type(policy["schema_version"]) is not int or policy["schema_version"] != 1
            or type(policy["uid"]) is not int or policy["uid"] < 1
            or not isinstance(policy["operator"], str)):
        raise BrokerDenied("invalid operator inventory")
    # These fields are authoritative only behind fixed sudo NOSETENV + isolated Python.
    # The future installer must NOT grant sudo on arbitrary Python/module arguments.
    if (environ.get("SUDO_USER") != policy["operator"]
            or environ.get("SUDO_UID") != str(policy["uid"])):
        raise BrokerDenied("operator does not match authenticated sudo identity")
    try:
        account = pwd.getpwnam(policy["operator"])
    except KeyError:
        raise BrokerDenied("operator account unavailable") from None
    if account.pw_uid != policy["uid"]:
        raise BrokerDenied("operator uid mapping changed")
    return policy["operator"], policy["uid"]


def _output_limit():
    # Single-threaded CLI child only; prevents helper output filling RAM or disk.
    resource.setrlimit(resource.RLIMIT_FSIZE, (LIMIT, LIMIT))


def bounded_call(argv: list[str], payload: bytes | None = None, timeout: int = 5) -> bytes:
    with tempfile.TemporaryFile() as output:
        process = None
        try:
            process = subprocess.Popen(argv, stdin=subprocess.PIPE if payload is not None else subprocess.DEVNULL,
                                       stdout=output, stderr=subprocess.DEVNULL, env=ENV,
                                       preexec_fn=_output_limit, start_new_session=True)
            process.communicate(input=payload, timeout=timeout)
        except (OSError, subprocess.TimeoutExpired):
            if process is not None:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.communicate(timeout=3)
            raise BrokerDenied("protected command unavailable") from None
        output.seek(0)
        value = output.read(LIMIT + 1)
        if process.returncode != 0 or len(value) > LIMIT:
            raise BrokerDenied("protected command failed")
        return value


def audit_delivery(payload: bytes) -> bytes:
    root_path(CODE / "audit-deliver", 0o755)
    if bounded_call(["/usr/bin/systemctl", "is-active", "auditd.service"]).strip() != b"active":
        raise BrokerDenied("OS audit service unavailable")
    raw = bounded_call(["/usr/sbin/auditctl", "-s"])
    try:
        status = dict(line.split() for line in raw.decode("ascii").splitlines())
    except (ValueError, UnicodeError):
        raise BrokerDenied("OS audit status unavailable") from None
    if status.get("enabled") not in ("1", "2") or status.get("lost") != "0":
        raise BrokerDenied("OS audit unhealthy")
    # Adapter validates configured audit coverage and persists the actual event;
    # a boolean or a receipt supplied on broker stdin cannot replace this call.
    return bounded_call([str(CODE / "audit-deliver")], payload=payload, timeout=10)


def request_bytes(core, action: str, target: str, ticket: str, seconds: int) -> bytes:
    if os.geteuid() == 0:
        raise BrokerDenied("request must be made by the named non-root operator")
    try:
        name = pwd.getpwuid(os.getuid()).pw_name
        boot_id = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
    except (OSError, KeyError):
        raise BrokerDenied("local operator or boot identity unavailable") from None
    now = time.clock_gettime_ns(time.CLOCK_BOOTTIME)
    data = {"schema_version": 1, "request_id": str(uuid.uuid4()), "requester": name,
            "reviewer": name, "action": action, "target": target, "ticket": ticket,
            "duration_seconds": seconds, "boot_id": boot_id, "issued_boottime_ns": now}
    return core.parse_request(core.canonical(data), name, boot_id, now).payload


def envelope(core, raw: bytes) -> tuple[bytes, bytes]:
    if len(raw) > LIMIT:
        raise BrokerDenied("approval envelope too large")
    try:
        value = json.loads(raw, object_pairs_hook=core._unique)
    except (ValueError, UnicodeError, RecursionError):
        raise BrokerDenied("invalid approval envelope") from None
    if (not isinstance(value, dict) or set(value) != {"request", "signature"}
            or not isinstance(value["request"], dict) or not isinstance(value["signature"], str)):
        raise BrokerDenied("invalid approval envelope fields")
    try:
        return core.canonical(value["request"]), value["signature"].encode("ascii")
    except (ValueError, UnicodeError, RecursionError):
        raise BrokerDenied("invalid approval envelope encoding") from None


def bundle_bytes(core, request_file: Path, signature_file: Path) -> bytes:
    values = []
    for path in (request_file, signature_file):
        os.close(core.protected_fd(path.parent, os.getuid(), True))
        fd = core.protected_fd(path, os.getuid())
        try:
            value = os.read(fd, LIMIT + 1)
        finally:
            os.close(fd)
        if len(value) > LIMIT:
            raise BrokerDenied("approval file too large")
        values.append(value)
    try:
        request = json.loads(values[0], object_pairs_hook=core._unique)
        if not isinstance(request, dict) or values[0] != core.canonical(request):
            raise BrokerDenied("request file is not the exact canonical signing input")
        result = core.canonical({"request": request, "signature": values[1].decode("ascii")})
    except (ValueError, UnicodeError, RecursionError):
        raise BrokerDenied("invalid approval files") from None
    if len(result) > LIMIT:
        raise BrokerDenied("approval envelope too large")
    return result


def read_envelope(fd: int) -> bytes:
    deadline = time.monotonic() + 5
    value = bytearray()
    while len(value) <= LIMIT:
        remaining = deadline - time.monotonic()
        if remaining <= 0 or not select.select([fd], [], [], max(0, remaining))[0]:
            raise BrokerDenied("approval input timed out")
        chunk = os.read(fd, LIMIT + 1 - len(value))
        if not chunk:
            return bytes(value)
        value.extend(chunk)
    raise BrokerDenied("approval envelope too large")


def event_ack(core, payload: bytes) -> None:
    root_path(STATE / "events", 0o700, True)
    core.consume_once(core.Request({"request_id": str(uuid.uuid4())}, payload, 0), STATE / "events", 0)
    core.validate_audit_ack(core.Request({}, payload, 0), audit_delivery(payload))


def revoke(core, name: str, uid: int) -> None:
    # Deliberately operator-wide: stop this operator's current unit, never another
    # caller or an arbitrary service. It is not a request-ID compare/stop race.
    raw = bounded_call(["/usr/bin/systemctl", "list-units", "--all", "--plain", "--full",
                        "--no-legend", "--no-pager", "--state=active,activating,deactivating",
                        f"hooshix-jit-u{uid}-r*.service"])
    units = []
    for line in raw.decode("ascii").splitlines():
        unit = line.split()[0]
        if not re.fullmatch(rf"hooshix-jit-u{uid}-r[0-9a-f]{{32}}\.service", unit):
            raise BrokerDenied("unexpected operator job inventory")
        units.append(unit)
    payload = core.canonical({"schema_version": 1, "event": "revoke", "operator": name,
                              "units": units})
    if units:
        bounded_call(["/usr/bin/systemctl", "stop", "--", *units])
    try:
        event_ack(core, payload)
    except (core.Denied, BrokerDenied):
        raise BrokerDenied("job stopped; revoke audit delivery unavailable") from None
    # Audit outage may deny new grants but may NEVER prevent removing privilege.


def execute(core, raw: bytes, name: str, uid: int) -> int:
    root_path(CONFIG / "allowed_signers", 0o600)
    for directory in (STATE / "replay", STATE / "private", core.LOCK_ROOT):
        # Protect ancestors as well as the final private directory.
        root_path(directory, 0o700, True)
        os.close(core.protected_fd(directory, 0, True))
    request_raw, signature = envelope(core, raw)
    boot_id = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
    argv = core.prepare_execution(request_raw, signature, caller=name, caller_uid=uid,
                                  boot_id=boot_id, signers=CONFIG / "allowed_signers",
                                  ledger=STATE / "replay", private=STATE / "private",
                                  protected_uid=0, audit=audit_delivery)
    request = core.parse_request(request_raw, name, boot_id, time.clock_gettime_ns(time.CLOCK_BOOTTIME))
    process = None
    cancelled = False
    def cancel(_signum, _frame):
        nonlocal cancelled
        cancelled = True
    old_handlers = {s: signal.signal(s, cancel) for s in (signal.SIGTERM, signal.SIGHUP, signal.SIGINT)}
    try:
        process = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL, env=ENV, start_new_session=True)
        while process.poll() is None:
            if cancelled or time.clock_gettime_ns(time.CLOCK_BOOTTIME) >= request.deadline_ns:
                # Only stop a unit carrying this exact signed request ID.
                unit = core.service_unit(request, uid)
                description = bounded_call(["/usr/bin/systemctl", "show", unit,
                                            "--property=Description", "--value"]).strip()
                if description == f"hooshix-jit:{request.data['request_id']}".encode("ascii"):
                    bounded_call(["/usr/bin/systemctl", "stop", unit])
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait(timeout=3)
                raise BrokerDenied("job cancelled or expired; do not replay")
            time.sleep(0.1)
        result = "Passed" if process.returncode == 0 else "Failed"
        payload = core.canonical({"schema_version": 1, "event": "outcome",
                                  "request_id": request.data["request_id"], "result": result})
        try:
            event_ack(core, payload)
        except (core.Denied, BrokerDenied):
            raise BrokerDenied("job ended; outcome audit unavailable; do not replay") from None
        return 0 if result == "Passed" else 1
    finally:
        for s, handler in old_handlers.items():
            signal.signal(s, handler)
        if process is not None and process.poll() is None:
            # Native RuntimeMaxSec remains authoritative if broker/client dies.
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=3)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="operation", required=True)
    request = commands.add_parser("request", help="render a request; grants no privilege")
    request.add_argument("--action", required=True, choices=("service-inspect", "service-restart"))
    request.add_argument("--target", required=True, choices=("caddy.service", "k3s.service"))
    request.add_argument("--ticket", required=True)
    request.add_argument("--seconds", type=int, default=60)
    bundle = commands.add_parser("bundle", help="package exact request and detached signature; grants nothing")
    bundle.add_argument("--request-file", type=Path, required=True)
    bundle.add_argument("--signature-file", type=Path, required=True)
    commands.add_parser("execute", help="installed privileged broker only; JSON envelope on stdin")
    commands.add_parser("revoke", help="installed privileged broker only; stop own current job")
    args = parser.parse_args()
    try:
        core = load_core(privileged=args.operation not in ("request", "bundle"))
        if args.operation == "request":
            # The signature covers these exact bytes: do not append a newline.
            sys.stdout.buffer.write(request_bytes(core, args.action, args.target, args.ticket, args.seconds))
            return 0
        if args.operation == "bundle":
            sys.stdout.buffer.write(bundle_bytes(core, args.request_file, args.signature_file))
            return 0
        name, uid = caller_identity(core, dict(os.environ))
        if args.operation == "revoke":
            revoke(core, name, uid)
            print("JIT_REVOKE=Passed")
            return 0
        result = execute(core, read_envelope(sys.stdin.fileno()), name, uid)
        print("JIT_OPERATION=Passed" if result == 0 else "JIT_OPERATION=Failed")
        return result
    except BrokerDenied as exc:
        print(f"JIT=Denied; {exc}", file=sys.stderr)
        return 1
    except (ValueError, OSError):
        # The detailed internal error may contain a filesystem/provider diagnostic.
        print("JIT=Denied; installation, identity, approval, audit or lifetime unavailable", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
