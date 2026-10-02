"""Install only the reviewed Ubuntu OS-audit prerequisite, not audit/JIT readiness.

No rules, sudo policy, SSH, provider credentials or cluster resources are changed.
The package transaction must finish: do not kill dpkg to enforce a wall-clock budget.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
from pathlib import Path

VERSION = "1:4.1.2-1ubuntu0.1"
PACKAGES = ("auditd", "libauparse0t64", "libauplugin1")
ENV = {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LC_ALL": "C",
       "DEBIAN_FRONTEND": "noninteractive"}
APT = ["/usr/bin/apt-get", "--yes", "--no-remove", "--no-upgrade",
       "--no-install-recommends", "-o", "APT::Get::AllowUnauthenticated=false",
       "-o", "Acquire::AllowInsecureRepositories=false",
       "-o", "Acquire::AllowDowngradeToInsecureRepositories=false",
       "-o", "Acquire::Retries=0", "-o", "Acquire::http::Timeout=15",
       "-o", "Acquire::https::Timeout=15", "-o", "DPkg::Lock::Timeout=10"]


def read_command(argv: list[str]) -> str:
    result = subprocess.run(argv, stdin=subprocess.DEVNULL, capture_output=True,
                            env=ENV, timeout=20, check=True)
    if len(result.stdout) > 65536:
        raise ValueError("preflight output exceeds limit")
    return result.stdout.decode("ascii")


def validate_plan(raw: str) -> None:
    summary = re.findall(r"^(\d+) upgraded, (\d+) newly installed, (\d+) to remove", raw, re.M)
    if len(summary) != 1 or summary[0][0] != "0" or summary[0][2] != "0":
        raise ValueError("package transaction is not installation-only")
    install_lines = [line for line in raw.splitlines() if line.startswith("Inst ")]
    planned = []
    for line in install_lines:
        match = re.fullmatch(r"Inst ([a-z0-9]+) \(" + re.escape(VERSION)
                             + r" Ubuntu:26\.04/resolute-updates \[amd64\]\)", line)
        if not match or match[1] not in PACKAGES or match[1] in planned:
            raise ValueError("unreviewed package or version in transaction")
        planned.append(match[1])
    if int(summary[0][1]) != len(planned):
        raise ValueError("inconsistent package transaction")
    if any(line.startswith("Remv ") for line in raw.splitlines()):
        raise ValueError("package removal prohibited")


def validate_log_bounds(raw: str) -> None:
    values = {}
    for line in raw.splitlines():
        active = line.split("#", 1)[0].strip()
        if not active:
            continue
        if "=" not in active:
            raise ValueError("invalid audit configuration")
        key, value = (part.strip() for part in active.split("=", 1))
        if key in values:
            raise ValueError("duplicate audit configuration")
        values[key] = value
    if (values.get("max_log_file") != "8" or values.get("num_logs") != "5"
            or values.get("max_log_file_action", "").upper() != "ROTATE"
            or values.get("disk_full_action", "").upper() != "SUSPEND"
            or values.get("disk_error_action", "").upper() != "SUSPEND"):
        raise ValueError("audit log bounds differ; review before continuing")


def validate_download_bound(raw: str) -> None:
    sizes = []
    for line in raw.splitlines():
        if not line.startswith("'"):
            continue
        match = re.fullmatch(r"'https?://[^\s']+' ([a-zA-Z0-9_.%+~-]+\.deb) (\d+) \S+", line)
        if not match:
            raise ValueError("invalid package download plan")
        sizes.append(int(match[2]))
    if len(sizes) > len(PACKAGES) or sum(sizes) > 16 * 1024 * 1024:
        raise ValueError("package download exceeds reviewed size budget")


def verify_installed() -> dict:
    """Read-only post-install checks; safe after an incomplete verification."""
    if os.geteuid() != 0:
        raise ValueError("verification requires local interactive sudo")
    stage = "packages"
    try:
        for package in PACKAGES:
            expected = "install ok installed " + VERSION
            actual = read_command(["/usr/bin/dpkg-query", "-W", "-f=${Status} ${Version}", package])
            if actual.strip() != expected:
                raise ValueError("installed package verification failed")
        stage = "log_bounds"
        validate_log_bounds(Path("/etc/audit/auditd.conf").read_text(encoding="utf-8"))
        stage = "daemon"
        if read_command(["/usr/bin/systemctl", "is-active", "auditd.service"]).strip() != "active":
            raise ValueError("audit daemon inactive")
        stage = "kernel"
        # auditctl includes multi-word informational values (e.g. loginuid_immutable
        # "0 unlocked"). Preserve them; never allow duplicates to replace authority.
        pairs = [line.split(maxsplit=1) for line in
                 read_command(["/usr/sbin/auditctl", "-s"]).splitlines()]
        status = dict(pairs)
        if (len(status) != len(pairs) or status.get("enabled") not in ("1", "2")
                or status.get("lost") != "0"):
            raise ValueError("kernel audit status unhealthy")
    except (OSError, ValueError, UnicodeError, subprocess.SubprocessError):
        # Only an allow-listed stage name is public; never raw config, stdout or exceptions.
        raise ValueError("POST_INSTALL_CHECK_FAILED=" + stage) from None
    return {"installed": True, "daemon": "Passed", "kernel": "Passed", "log_bounds": "Passed",
            "package_version": VERSION, "audit_readiness": "Not verified",
            "jit_readiness": "Not verified", "standing_admin_removed": False}


def install(apply: bool) -> dict:
    os_release = Path("/etc/os-release").read_text(encoding="utf-8")
    if ('ID=ubuntu\n' not in os_release or 'VERSION_ID="26.04"\n' not in os_release
            or read_command(["/usr/bin/dpkg", "--print-architecture"]).strip() != "amd64"):
        raise ValueError("installer supports only the reviewed Ubuntu 26.04 amd64 host")
    if apply and os.geteuid() != 0:
        raise ValueError("installation requires local interactive sudo")
    # A small prerequisite, not an upgrade or a corpus download. Preserve shared caches.
    if any(shutil.disk_usage(path).free < 256 * 1024 * 1024 for path in ("/usr", "/var")):
        raise ValueError("insufficient disk headroom")
    command = [*APT, "install", *(f"{name}={VERSION}" for name in PACKAGES)]
    validate_plan(read_command([*command, "--simulate"]))
    validate_download_bound(read_command([*command, "--print-uris"]))
    receipt = {"schema_version": 1, "preflight": "Passed", "applied": False,
               "package_version": VERSION, "audit_readiness": "Not verified",
               "jit_readiness": "Not verified", "standing_admin_removed": False}
    if not apply:
        return receipt
    # No timeout/automatic retry across the dpkg transaction: interrupted package
    # state needs operator recovery, not blind replay. Network and lock waits are finite.
    subprocess.run(command, stdin=subprocess.DEVNULL, env=ENV, check=True)
    receipt.update(verify_installed(), applied=True)
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--install", action="store_true")
    mode.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    try:
        receipt = verify_installed() if args.verify_only else install(args.install)
        print(json.dumps(receipt, sort_keys=True))
    except (OSError, ValueError, UnicodeError, subprocess.SubprocessError) as exc:
        stage = str(exc) if re.fullmatch(r"POST_INSTALL_CHECK_FAILED=(packages|log_bounds|daemon|kernel)",
                                       str(exc)) else "verification_or_transaction"
        # A partially applied package transaction is NOT rolled back or reported as success.
        raise SystemExit("AUDIT_PREREQUISITE=Failed; " + stage + "; do not repeat installation") from None


if __name__ == "__main__":
    main()
