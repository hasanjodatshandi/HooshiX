#!/usr/bin/env python3
"""Network-isolated WAF image privacy smoke; no real credentials or backend."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import time
import uuid


def docker(*args: str) -> str:
    return subprocess.check_output(["docker", *args], text=True, timeout=30).strip()


def request_status(name: str, url: str, headers: list[str]) -> int:
    command = ["docker", "exec", name, "wget", "-Y", "off", "-S", "-O", "/dev/null"]
    for header in headers:
        command.extend(("--header", header))
    command.append(url)
    result = subprocess.run(command, text=True, capture_output=True, timeout=10, check=False)
    matches = re.findall(r"HTTP/1\.[01] ([0-9]{3})", result.stderr)
    if not matches:
        raise RuntimeError("WAF synthetic request produced no HTTP status")
    return int(matches[-1])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("image")
    args = parser.parse_args()
    name = "hooshix-waf-smoke-" + uuid.uuid4().hex
    canary = "HOOSHIX_PII_CANARY_" + uuid.uuid4().hex
    container_created = False
    try:
        docker(
            "run", "--detach", "--name", name, "--network", "none",
            "--read-only", "--user", "10001:10001", "--cap-drop", "ALL",
            "--security-opt", "no-new-privileges", "--memory", "256m", "--cpus", "1",
            "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=16m",
            "--tmpfs", "/config:rw,noexec,nosuid,nodev,size=1m,uid=10001,gid=10001",
            "--tmpfs", "/data:rw,noexec,nosuid,nodev,size=1m,uid=10001,gid=10001",
            args.image,
        )
        container_created = True
        for attempt in range(30):
            result = subprocess.run(
                ["docker", "exec", name, "wget", "-qO-", "http://127.0.0.1:9090/healthz"],
                text=True,
                capture_output=True,
                timeout=5,
                check=False,
            )
            if result.returncode == 0 and result.stdout.strip() == "ok":
                break
            if attempt == 29:
                raise RuntimeError("WAF image did not become ready")
            time.sleep(1)
        for blocked, expected in ((False, 502), (True, 403)):
            headers = ["Authorization: Bearer " + canary, "Cookie: canary=" + canary]
            if blocked:
                headers.append("X-HooshiX-WAF-Test: block")
            status = request_status(name, "http://127.0.0.1:8080/?canary=" + canary, headers)
            if status != expected:
                raise RuntimeError(f"WAF synthetic boundary expected {expected}, got {status}")
        output = docker("logs", name)
        if canary in output:
            raise RuntimeError("WAF image exposed a synthetic secret")
        events = [json.loads(line) for line in output.splitlines() if line.strip()]
        if any("request" in event for event in events):
            raise RuntimeError("WAF image retained request metadata")
        if not any(e.get("msg") == "waf_rule_match" and e.get("rule_id") == 1000001 for e in events):
            raise RuntimeError("WAF image lacks safe blocking evidence")
        print("WAF isolated image privacy and backend-outage smoke PASSED")
    finally:
        if container_created:
            docker("rm", "--force", name)


if __name__ == "__main__":
    main()
