"""Read-only, allow-list host/cluster inventory; never a readiness approval."""

from __future__ import annotations

import json
import base64
import hashlib
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
    for service in ["wg-quick@wg-hooshix", "k3s", "caddy", "nginx", "postfix", "dovecot", "auditd", "rsyslog"]:
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
        "management": {"scope": "global SSH config only; connection-specific Match, firewall, JIT and off-host audit not verified"},
    }
    if result["privileged"]:
        result["management"]["sshd_syntax"] = "Passed" if run(["/usr/sbin/sshd", "-t"]) is not None else "Not verified"
        raw_config = run(["/usr/sbin/sshd", "-T"])
        result["management"]["sshd_global"] = summarize_sshd(raw_config or "")
        raw_peers = run(["/usr/bin/wg", "show", "wg-hooshix", "allowed-ips"])
        try:
            result["management"]["wireguard_peers"] = summarize_peers(raw_peers) if raw_peers is not None else None
        except ValueError:
            result["management"]["wireguard_peers"] = None
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


def summarize_sshd(raw: str) -> dict:
    """Never export arbitrary config values, paths, commands or banners."""
    flags = {"permitrootlogin", "passwordauthentication", "kbdinteractiveauthentication",
             "pubkeyauthentication", "allowagentforwarding", "allowtcpforwarding",
             "x11forwarding", "permittunnel", "gatewayports"}
    result = {}
    for line in raw.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[0] in flags and parts[1] in {"yes", "no", "prohibit-password", "forced-commands-only"}:
            result[parts[0]] = parts[1]
    return result


def summarize_peers(raw: str) -> list[dict]:
    """Use allowed-ips, never dump/showconf: those commands contain private keys."""
    import ipaddress
    peers = []
    for line in raw.splitlines():
        key, addresses = line.split(maxsplit=1)
        decoded = base64.b64decode(key, validate=True)
        if len(decoded) != 32:
            raise ValueError("invalid public key")
        networks = [] if addresses == "(none)" else addresses.replace(",", " ").split()
        peers.append({"public_key_sha256": hashlib.sha256(decoded).hexdigest(),
                      "allowed_ips": [str(ipaddress.ip_network(item, strict=True)) for item in networks]})
    return peers


if __name__ == "__main__":
    print(json.dumps(collect(), ensure_ascii=True, sort_keys=True))
