"""Read-only verification of installed host audit rules; never a JIT/readiness grant.

Only public hashes, bounded counts and fixed result labels leave this process.
No log content, SQL, package stderr, credential or shell command is printed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shlex
import stat
import subprocess
from datetime import datetime, timezone
from pathlib import Path

RULE_FILE = Path("/etc/audit/rules.d/70-hooshix.rules")
ENV = {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LC_ALL": "C"}
LIMIT = 32768


def protected_rules(path: Path) -> bytes:
    # Reject symlink traversal through every parent as well as the file itself.
    for parent in reversed(path.parents):
        info = parent.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise ValueError("unsafe audit directory")
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        info = os.fstat(descriptor)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_gid != 0
                or stat.S_IMODE(info.st_mode) != 0o640 or info.st_nlink != 1
                or info.st_size > LIMIT):
            raise ValueError("unsafe audit file")
        raw = os.read(descriptor, LIMIT + 1)
        if len(raw) > LIMIT or b"\r" in raw or raw.startswith(b"\xef\xbb\xbf"):
            raise ValueError("invalid audit bytes")
        return raw
    finally:
        os.close(descriptor)


def canonical_rules(raw: str) -> dict[str, tuple]:
    """Compare aliases while preserving first-match order within each kernel list.

    auditctl can emit filter lists in a different group order from the input file;
    rules inside each list must still match in order, not just by set/count.
    """
    groups: dict[str, list] = {}
    rules = []
    for line in raw.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        tokens = shlex.split(line)
        if len(tokens) % 2 or tokens[0] not in ("-a", "-w"):
            raise ValueError("unsupported audit rule")
        if tokens[0] == "-w":
            group = "exit"
        else:
            parts = tokens[1].split(",")
            lists = set(parts) - {"never", "always"}
            if len(parts) != 2 or len(lists) != 1:
                raise ValueError("invalid audit filter")
            group = lists.pop()
        pairs = []
        for option, value in zip(tokens[::2], tokens[1::2], strict=True):
            if option == "-k":
                option, value = "-F", "key=" + value
            if option == "-F":
                value = {"auid!=-1": "auid!=4294967295",
                         "msgtype=1309": "msgtype=EXECVE",
                         "msgtype=1327": "msgtype=PROCTITLE"}.get(value, value)
            if option in ("-a", "-S", "-p"):
                value = ",".join(sorted(value.split(","))) if option != "-p" else "".join(sorted(value))
            pairs.append((option, value))
        rule = tuple(sorted(pairs))
        rules.append(rule)
        groups.setdefault(group, []).append(rule)
    if not rules or len(rules) > 128 or len(set(rules)) != len(rules):
        raise ValueError("missing, excessive or duplicate audit rules")
    return {group: tuple(entries) for group, entries in groups.items()}


def kernel_health(raw: str) -> dict[str, int]:
    pairs = [line.split(maxsplit=1) for line in raw.splitlines()]
    if any(len(pair) != 2 for pair in pairs):
        raise ValueError("invalid kernel status")
    status = dict(pairs)
    if len(status) != len(pairs):
        raise ValueError("duplicate kernel status")
    numbers = {}
    for key in ("enabled", "pid", "lost", "backlog", "backlog_limit"):
        if not re.fullmatch(r"[0-9]{1,10}", status.get(key, "")):
            raise ValueError("invalid kernel field")
        numbers[key] = int(status[key])
    if (numbers["enabled"] not in (1, 2) or numbers["pid"] == 0
            or numbers["lost"] != 0 or numbers["backlog_limit"] < 1
            or numbers["backlog"] * 4 >= numbers["backlog_limit"] * 3):
        raise ValueError("audit kernel unhealthy")
    return {key: numbers[key] for key in ("enabled", "lost", "backlog", "backlog_limit")}


def command(argv: list[str]) -> str:
    result = subprocess.run(argv, stdin=subprocess.DEVNULL, capture_output=True,
                            env=ENV, timeout=5, check=True)
    if len(result.stdout) > LIMIT:
        raise ValueError("audit command output exceeds bound")
    return result.stdout.decode("ascii")


def verify(expected_sha256: str) -> dict:
    stage = "permissions"
    try:
        if os.geteuid() != 0 or not re.fullmatch(r"[0-9a-f]{64}", expected_sha256):
            raise ValueError("root and reviewed SHA-256 required")
        raw = protected_rules(RULE_FILE)
        stage = "policy_digest"
        digest = hashlib.sha256(raw).hexdigest()
        if digest != expected_sha256:
            raise ValueError("audit policy changed")
        expected = canonical_rules(raw.decode("ascii"))
        stage = "daemon"
        if command(["/usr/bin/systemctl", "is-active", "auditd.service"]).strip() != "active":
            raise ValueError("audit daemon inactive")
        stage = "kernel"
        health = kernel_health(command(["/usr/sbin/auditctl", "-s"]))
        stage = "active_rules"
        if canonical_rules(command(["/usr/sbin/auditctl", "-l"])) != expected:
            raise ValueError("active audit policy mismatch")
    except (OSError, ValueError, UnicodeError, subprocess.SubprocessError):
        raise ValueError("HOST_AUDIT_CHECK_FAILED=" + stage) from None
    return {"schema_version": 1, "observed_at": datetime.now(timezone.utc).isoformat(),
            "local_rule_verification": "Passed", "policy_sha256": digest,
            "active_rule_count": sum(len(entries) for entries in expected.values()), "kernel": health,
            "scope": "rule-file and active-kernel consistency on this boot only",
            "event_coverage": "Not verified", "audit_readiness": "Not verified",
            "jit_readiness": "Not verified", "production_readiness": "Not verified"}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-rules-sha256", required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.expected_rules_sha256), sort_keys=True))
    except ValueError as error:
        raise SystemExit(str(error)) from None


if __name__ == "__main__":
    main()
