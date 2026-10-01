#!/usr/bin/env python3
"""Disposable CI-only TLS/Raft recovery rehearsal; never receives real secrets."""
from __future__ import annotations

import argparse
import contextlib
import json
import os
import re
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SECRETS = ROOT / "infrastructure/production/secrets"
BOUND = 8 * 1024 * 1024


class RehearsalFailed(Exception):
    """Deliberately does not include API, container, or credential diagnostics."""


def command(args: list[str], *, timeout: int = 30) -> bytes:
    result = subprocess.run(args, check=False, stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, timeout=timeout,
                            env={"PATH": "/usr/bin:/bin", "HOME": "/tmp", "LC_ALL": "C"})
    if result.returncode or len(result.stdout) > 8192:
        raise RehearsalFailed("fixture command failed")
    return result.stdout


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RehearsalFailed("redirect denied")


class Client:
    def __init__(self, port: int, ca: Path):
        self.base = f"https://127.0.0.1:{port}/v1/"
        context = ssl.create_default_context(cafile=str(ca))
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context), NoRedirect())

    def call(self, path: str, method: str = "GET", body=None, token: str | None = None,
             expected: int = 200, raw: bool = False):
        data = body if isinstance(body, bytes) else (
            json.dumps(body).encode() if body is not None else None)
        headers = {"Content-Type": "application/json"}
        if token:
            headers["X-Vault-Token"] = token
        request = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            response = self.opener.open(request, timeout=5)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            content = response.read(BOUND + 1)
            if response.status != expected or len(content) > BOUND:
                raise RehearsalFailed("fixture API status or size mismatch")
        return content if raw else (json.loads(content) if content else {})

    def wait_health(self, status: int):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            try:
                return self.call("sys/health", expected=status)
            except (OSError, RehearsalFailed):
                threading.Event().wait(0.2)
        raise RehearsalFailed("fixture health deadline")


def initialize(client: Client) -> tuple[list[str], str]:
    result = client.call("sys/init", "PUT", {"secret_shares": 3, "secret_threshold": 2})
    keys, token = result["keys_base64"], result["root_token"]
    if len(keys) != 3 or len(set(keys)) != 3 or not token:
        raise RehearsalFailed("fixture Shamir output mismatch")
    client.wait_health(503)
    state = client.call("sys/unseal", "PUT", {"key": keys[0]})
    if state["sealed"] is not True or state["progress"] != 1 or state["t"] != 2 or state["n"] != 3:
        raise RehearsalFailed("fixture insufficient-share bypass")
    client.call("sys/unseal", "PUT", {"key": keys[1]})
    client.wait_health(200)
    return keys, token


def rehearse():
    # No production host, owner credential path, key environment, or cluster mutation exists here.
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.getuid() == 0:
        raise RehearsalFailed("disposable non-root GitHub runner required")
    image = json.loads((SECRETS / "openbao-image.json").read_text())["image"]
    if not re.fullmatch(r"ghcr\.io/openbao/openbao@sha256:[a-f0-9]{64}", image):
        raise RehearsalFailed("fixture immutable image required")
    command(["/usr/bin/docker", "pull", "--quiet", image], timeout=180)
    version = command(["/usr/bin/docker", "run", "--rm", "--network", "none", "--read-only",
                       "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                       "--user", str(os.getuid()), "--entrypoint", "bao", image, "version"])
    if not re.search(rb"\bOpenBao v2\.6\.1\b", version):
        raise RehearsalFailed("fixture exact version mismatch")
    with tempfile.TemporaryDirectory(prefix="hooshix-openbao-ci-") as temporary:
        with contextlib.ExitStack() as cleanup:
            directory = Path(temporary)
            command(["/usr/bin/openssl", "req", "-x509", "-newkey", "rsa:3072", "-sha256",
                     "-nodes", "-days", "1", "-subj", "/CN=hooshix-ci-only",
                     "-addext", "subjectAltName=IP:127.0.0.1,DNS:localhost",
                     "-keyout", str(directory / "tls.key"), "-out", str(directory / "tls.crt")])
            (directory / "tls.key").chmod(0o600)
            config = json.loads((SECRETS / "openbao-server.json").read_text())
            config["api_addr"] = "https://127.0.0.1:8200"
            config["cluster_addr"] = "https://127.0.0.1:8201"
            (directory / "config.json").write_text(json.dumps(config))

            def start(label: str) -> tuple[str, Client, Path]:
                name = "hooshix-bao-ci-" + label + "-" + uuid.uuid4().hex
                # Registered before create; containers stop BEFORE temporary mounts are removed.
                cleanup.callback(command, ["/usr/bin/docker", "rm", "--force", "--volumes", name])
                data = directory / label
                data.mkdir(mode=0o700)
                command(["/usr/bin/docker", "run", "--detach", "--name", name,
                         "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                         "--user", f"{os.getuid()}:{os.getgid()}", "--memory", "512m",
                         "--memory-swap", "512m", "--cpus", "1", "--pids-limit", "64",
                         "--log-driver", "none", "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=16m",
                         "--mount", f"type=bind,src={directory},dst=/openbao/tls,readonly",
                         "--mount", f"type=bind,src={directory / 'config.json'},dst=/openbao/config.json,readonly",
                         "--mount", f"type=bind,src={data},dst=/openbao/data",
                         "--publish", "127.0.0.1::8200", "--entrypoint", "bao", image,
                         "server", "-config=/openbao/config.json"])
                port_output = command(["/usr/bin/docker", "port", name, "8200/tcp"]).decode().strip()
                match = re.fullmatch(r"127\.0\.0\.1:([0-9]{1,5})", port_output)
                if not match:
                    raise RehearsalFailed("fixture loopback binding required")
                return name, Client(int(match[1]), directory / "tls.crt"), data

            source, client, data = start("source")
            health = client.wait_health(501)
            if health["version"] != "2.6.1":
                raise RehearsalFailed("fixture health version mismatch")
            # A client trusting the system roots must reject this disposable, untrusted certificate.
            try:
                urllib.request.build_opener(urllib.request.ProxyHandler({})).open(client.base + "sys/health", timeout=5)
            except urllib.error.URLError as error:
                if not isinstance(error.reason, ssl.SSLCertVerificationError):
                    raise RehearsalFailed("fixture TLS negative inconclusive") from None
            else:
                raise RehearsalFailed("fixture TLS verification bypass")
            keys, root_token = initialize(client)
            client.call("sys/mounts/fixture", "POST", {"type": "kv", "options": {"version": "2"}}, root_token, 204)
            canary = "ci-only-" + uuid.uuid4().hex
            client.call("fixture/data/audit", "POST", {"data": {"value": canary}}, root_token)
            client.call("sys/policies/acl/audit-reader", "PUT", {"policy":
                'path "fixture/data/audit" { capabilities = ["read"] }'}, root_token, 204)
            reader = client.call("auth/token/create", "POST", {"policies": ["audit-reader"],
                "no_default_policy": True, "ttl": "5m", "explicit_max_ttl": "5m"}, root_token)["auth"]["client_token"]
            client.call("fixture/data/audit", "POST", {"data": {"value": "must-not-write"}}, reader, 403)
            snapshot = client.call("sys/storage/raft/snapshot", token=root_token, raw=True)
            if not snapshot or canary.encode() in snapshot:
                raise RehearsalFailed("fixture snapshot encryption mismatch")
            command(["/usr/bin/docker", "restart", "--time", "10", source])
            client.wait_health(503)
            for key in keys[:2]:
                client.call("sys/unseal", "PUT", {"key": key})
            client.wait_health(200)
            if client.call("fixture/data/audit", token=reader)["data"]["data"]["value"] != canary:
                raise RehearsalFailed("fixture Raft restart persistence mismatch")

            _, restored, restored_data = start("restore")
            restored.wait_health(501)
            _, temporary_root = initialize(restored)
            # Force is confined to this newly initialized EMPTY fixture, never existing/production state.
            restored.call("sys/storage/raft/snapshot-force", "POST", snapshot, temporary_root, 204)
            restored.wait_health(503)
            for key in keys[:2]:
                restored.call("sys/unseal", "PUT", {"key": key})
            restored.wait_health(200)
            if restored.call("fixture/data/audit", token=reader)["data"]["data"]["value"] != canary:
                raise RehearsalFailed("fixture restored value mismatch")
            restored.call("fixture/data/audit", "POST", {"data": {"value": "must-not-write"}}, reader, 403)
            client.call("auth/token/revoke-self", "POST", {}, root_token, 204)
            client.call("sys/mounts", token=root_token, expected=403)
            restored.call("auth/token/revoke-self", "POST", {}, root_token, 204)
            restored.call("sys/mounts", token=root_token, expected=403)
            for audit_data in (data, restored_data):
                audit = (audit_data / "audit.jsonl").read_bytes()
                if not audit or len(audit) > BOUND or any(value.encode() in audit for value in
                                                        [canary, reader, root_token, temporary_root, *keys]):
                    raise RehearsalFailed("fixture audit leak or size mismatch")
            print("OPENBAO_RECOVERY=Passed; TLS; Shamir 3/2; Raft restart; isolated restore; ACL; audit redaction; root revocation")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ci", action="store_true", required=True)
    parser.parse_args()
    try:
        rehearse()
        return 0
    except (OSError, ValueError, KeyError, subprocess.SubprocessError, RehearsalFailed):
        print("OPENBAO_RECOVERY=Failed; inspect the named CI step; no secret diagnostics emitted")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
