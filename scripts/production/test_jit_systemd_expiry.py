#!/usr/bin/env python3
"""Tiny synthetic cgroup expiry test; never runs an application operation.

Default: unprivileged user manager. --system is only for disposable CI runners.
"""
import argparse
import os
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

from jit_runtime import SERVICE_PROPERTIES


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--system", action="store_true")
    args = parser.parse_args()
    if args.system and (os.geteuid() != 0 or os.environ.get("GITHUB_ACTIONS") != "true"):
        parser.error("system-manager test is restricted to disposable GitHub CI")
    manager = [] if args.system else ["--user"]
    unit = f"hooshix-jit-expiry-test-{uuid.uuid4().hex}.service"
    with tempfile.TemporaryDirectory(prefix="hooshix-jit-expiry-") as temp:
        receipt = Path(temp) / "pids"
        # Parent exits immediately, while the background child remains active.
        fixture = ("import os,time,pathlib; child=os.fork(); "
                   "pathlib.Path(__import__('sys').argv[1]).write_text(str(os.getpid())) "
                   "if child == 0 else None; "
                   "time.sleep(60) if child == 0 else None")
        argv = ["/usr/bin/systemd-run", *manager, "--quiet", "--unit", unit]
        for prop in (*SERVICE_PROPERTIES, "RuntimeMaxSec=4s"):
            # User managers cannot provide root-only mount sandboxing; CI tests all properties.
            if not args.system and prop.startswith(("ProtectSystem=", "ProtectHome=", "ProtectControlGroups=")):
                continue
            argv.extend(("--property", prop))
        if args.system:
            # Only this disposable fixture receipt needs a writable filesystem path.
            argv.extend(("--property", f"ReadWritePaths={temp}"))
        argv.extend(("--", sys.executable, "-c", fixture, str(receipt)))
        started = time.monotonic()
        try:
            subprocess.run(argv, check=True, timeout=5, stdout=subprocess.DEVNULL)
            # Submission client has exited: the manager still owns lifetime/children.
            deadline = started + 10
            while not receipt.exists() and time.monotonic() < deadline:
                time.sleep(0.1)
            if not receipt.exists():
                raise RuntimeError("synthetic child did not start")
            child = int(receipt.read_text())
            initial = subprocess.check_output(["/usr/bin/systemctl", *manager, "show", unit,
                                               "--property=ActiveState", "--value"], timeout=3).strip()
            if initial != b"active":
                raise RuntimeError("background child was not tracked after parent exit")
            while time.monotonic() < deadline:
                state = subprocess.check_output(["/usr/bin/systemctl", *manager, "show", unit,
                                                 "--property=ActiveState", "--value"], timeout=3).strip()
                if state in (b"failed", b"inactive"):
                    break
                time.sleep(0.1)
            else:
                raise RuntimeError("native expiry deadline exceeded")
            result = subprocess.check_output(["/usr/bin/systemctl", *manager, "show", unit,
                                              "--property=Result", "--value"], timeout=3).strip()
            if result != b"timeout" or not 3 <= time.monotonic() - started <= 10:
                raise RuntimeError("job ended for a reason other than bounded runtime expiry")
            process = Path(f"/proc/{child}/stat")
            if process.exists() and process.read_text().split(")", 1)[1].split()[0] != "Z":
                raise RuntimeError("background child survived expiry")
            print("NATIVE_JIT_CGROUP_EXPIRY=Passed; parent exit, background child, bounded deadline")
        finally:
            subprocess.run(["/usr/bin/systemctl", *manager, "stop", unit], check=False,
                           timeout=5, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["/usr/bin/systemctl", *manager, "reset-failed", unit], check=False,
                           timeout=5, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
