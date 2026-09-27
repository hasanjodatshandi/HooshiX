"""Read-only, allow-list host/cluster inventory; never a readiness approval."""

from __future__ import annotations

import json
import os
import re
import subprocess
from datetime import datetime, timezone

KUBECTL = ["/usr/local/bin/k3s", "kubectl", "--request-timeout=15s"]
ENV = {"PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"}


def run(args: list[str]) -> str | None:
    """No shell, credentials, retry, raw command output or raw exception logging."""
    try:
        result = subprocess.run(
            args, capture_output=True, text=True, timeout=20, env=ENV, check=False
        )
        if result.returncode == 0:
            return result.stdout
    except (OSError, UnicodeError, subprocess.TimeoutExpired):
        pass
    return None


def name(value: object) -> str | None:
    if isinstance(value, str) and re.fullmatch(r"[a-z0-9][a-z0-9.-]{0,252}", value):
        return value
    return None


def summarize_pods(raw: str) -> list[dict]:
    data = json.loads(raw)
    summaries = []
    for pod in data["items"]:
        metadata = pod.get("metadata", {})
        containers = pod.get("status", {}).get("containerStatuses", [])
        summaries.append(
            {
                "namespace": name(metadata.get("namespace")),
                "name": name(metadata.get("name")),
                "container_count": len(containers),
                "ready_count": sum(c.get("ready") is True for c in containers),
                "restart_count": sum(
                    c.get("restartCount", 0)
                    for c in containers
                    if type(c.get("restartCount", 0)) is int
                    and c.get("restartCount", 0) >= 0
                ),
            }
        )
    return summaries


def collect() -> dict:
    services = {}
    for service in ["wg-quick@wg-hooshix", "k3s", "caddy", "nginx", "postfix", "dovecot"]:
        status = run(["/usr/bin/systemctl", "is-active", service])
        services[service] = "active" if status and status.strip() == "active" else "Not verified"
    result = {
        "schema_version": 1,
        "observed_at": datetime.now(timezone.utc).isoformat(),
        "production_readiness": "Not verified",
        "scope": "read-only inventory; no admission, recovery, capacity or provider tests",
        "privileged": os.geteuid() == 0,
        "services": services,
        "api_ready": "Not verified",
        "pods": None,
        "pod_inventory": "Not verified",
    }
    if result["privileged"]:
        ready = run(KUBECTL + ["get", "--raw=/readyz"])
        result["api_ready"] = "Passed" if ready and ready.strip() == "ok" else "Not verified"
        raw = run(KUBECTL + ["get", "pods", "--all-namespaces", "-o", "json"])
        if raw is not None:
            try:
                result["pods"] = summarize_pods(raw)
                result["pod_inventory"] = "Passed"
            except (ValueError, KeyError, TypeError, AttributeError):
                result["pod_inventory"] = "Failed: malformed API response"
    return result


if __name__ == "__main__":
    print(json.dumps(collect(), ensure_ascii=True, sort_keys=True))
