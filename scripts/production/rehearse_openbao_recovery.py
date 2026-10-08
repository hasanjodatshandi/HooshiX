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

import activate_openbao_host as activation
import activate_openbao_operator as operator
import openbao_activation_transport as transport
import recover_openbao_initialization as lost_init
from render_openbao_candidate import ALIVE, STATUS

ROOT = Path(__file__).resolve().parents[2]
SECRETS = ROOT / "infrastructure/production/secrets"
BOUND = 8 * 1024 * 1024
STEPS = frozenset({"image-pull", "image-version", "tls-fixture", "source-start", "source-health",
                   "tls-negative", "source-init", "kv-and-acl", "snapshot", "restart", "restart-read",
                   "restore-start", "restore-init", "snapshot-restore", "restore-read",
                   "root-revoke", "audit-redaction", "cleanup", "probe-sealed", "probe-unsealed",
                   "kv-mount", "kv-write", "acl-policy", "acl-token", "acl-write-denied",
                   "lost-init-archive", "fresh-store-init", "archive-rollback", "archive-restart-read"})


class RehearsalFailed(Exception):
    """Deliberately does not include API, container, or credential diagnostics."""


def step(name: str) -> None:
    # Only fixed public labels may reach CI; never exception text, HTTP bodies or container logs.
    if name not in STEPS:
        raise RehearsalFailed("unknown fixture step")
    print(f"OPENBAO_STEP={name}", flush=True)


def command(args: list[str], *, timeout: int = 30, expected_exit: int = 0,
            data=None, output_bound=8192) -> bytes:
    result = subprocess.run(args, input=data, check=False, stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, timeout=timeout,
                            env={"PATH": "/usr/bin:/bin", "HOME": "/tmp", "LC_ALL": "C"})
    if result.returncode != expected_exit or len(result.stdout) > output_bound:
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
             expected: int = 200, raw: bool = False, timeout: int = 30):
        data = body if isinstance(body, bytes) else (
            json.dumps(body).encode() if body is not None else None)
        headers = {"Content-Type": "application/json"}
        if token:
            headers["X-Vault-Token"] = token
        request = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            response = self.opener.open(request, timeout=timeout)
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


def initialize_encrypted(client: Client, container: str, directory: Path) -> tuple[list[str], str]:
    # Production crypto and the exact one-attempt verified HTTPS write adapter.
    # Nothing in this rehearsal receives real keys, operator paths or VPS access.
    directory.mkdir(mode=0o700)
    password = 'CI only disposable custody passphrase, never production'
    recipients = [operator.generate_recipient(directory, i, password) for i in range(1, 4)]
    operator.create(directory / 'recipients.json', json.dumps(recipients).encode())
    recipients = operator.prepare(directory, password)

    # The disposable fixture's CA is public and identical to the mounted cert.
    public_ca = directory.parent / 'tls' / 'tls.crt'
    api = transport.Client(int(client.base.split(':')[2].split('/')[0]), public_ca.read_text()).call

    encrypted = activation.encrypted_result(api('write', 'sys/init', activation.init_request(recipients)))
    # Ciphertext-only write/readback; keys are recovered from fresh private exports.
    saved = directory / 'encrypted.json'
    operator.create(saved, json.dumps(encrypted).encode())
    encrypted = json.loads(operator.read_private(saved))
    keys = [operator.decrypt(directory, i + 1, password, value)
            for i, value in enumerate(encrypted['keys_base64'])]
    token = operator.decrypt(directory, 1, password, encrypted['root_token'])
    if len(set(keys)) != 3 or any(value.encode() in saved.read_bytes() for value in [*keys, token]):
        raise RehearsalFailed('encrypted custody leakage')
    client.wait_health(503)
    activation.unseal(keys[:2], api=api)
    client.wait_health(200)
    # Already-initialized server refuses another init; no state deletion/recovery shortcut.
    client.call('sys/init', 'PUT', activation.init_request(recipients), expected=400)
    return keys, token


def rehearse():
    # No production host, owner credential path, key environment, or cluster mutation exists here.
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.getuid() == 0:
        raise RehearsalFailed("disposable non-root GitHub runner required")
    image = json.loads((SECRETS / "openbao-image.json").read_text())["image"]
    if not re.fullmatch(r"ghcr\.io/openbao/openbao-distroless@sha256:[a-f0-9]{64}", image):
        raise RehearsalFailed("fixture immutable image required")
    step("image-pull")
    command(["/usr/bin/docker", "pull", "--quiet", image], timeout=180)
    step("image-version")
    version = command(["/usr/bin/docker", "run", "--rm", "--network", "none", "--read-only",
                       "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                       "--user", str(os.getuid()), "--entrypoint", "/usr/bin/bao", image, "version"])
    if not re.search(rb"^OpenBao v2\.6\.4(?:\s|$)", version):
        raise RehearsalFailed("fixture exact version mismatch")
    with tempfile.TemporaryDirectory(prefix="hooshix-openbao-ci-") as temporary:
        with contextlib.ExitStack() as cleanup:
            directory = Path(temporary)
            tls = directory / "tls"
            tls.mkdir(mode=0o700)
            # Raft must advertise a stable address reachable from the container after restart.
            # The API remains published only to host loopback; this bridge is disposable CI state.
            network = "hooshix-openbao-ci-" + uuid.uuid4().hex
            # TEST-NET-1 is reserved for disposable documentation/fixture traffic and is not
            # expected to overlap runner service networks.
            subnet = "192.0.2.0/24"
            cluster_ips = {"source": "192.0.2.2", "restore": "192.0.2.3"}
            command(["/usr/bin/docker", "network", "create", "--subnet", subnet, network])
            cleanup.callback(command, ["/usr/bin/docker", "network", "rm", network])
            step("tls-fixture")
            command(["/usr/bin/openssl", "req", "-x509", "-newkey", "rsa:3072", "-sha256",
                     "-nodes", "-days", "1", "-subj", "/CN=hooshix-ci-only",
                     "-addext", f"subjectAltName=IP:127.0.0.1,IP:{cluster_ips['source']},IP:{cluster_ips['restore']},DNS:localhost",
                     "-keyout", str(tls / "tls.key"), "-out", str(tls / "tls.crt")])
            (tls / "tls.key").chmod(0o600)
            config = json.loads((SECRETS / "openbao-server.json").read_text())
            config["api_addr"] = "https://127.0.0.1:8200"

            def mapped_port(name: str) -> int:
                port_output = command(["/usr/bin/docker", "port", name, "8200/tcp"]).decode().strip()
                match = re.fullmatch(r"127\.0\.0\.1:([0-9]{1,5})", port_output)
                if not match:
                    raise RehearsalFailed("fixture loopback binding required")
                return int(match[1])

            def start(label: str) -> tuple[str, Client, Path]:
                step(label + "-start")
                name = "hooshix-bao-ci-" + label + "-" + uuid.uuid4().hex
                # Registered before create; containers stop BEFORE temporary mounts are removed.
                cleanup.callback(command, ["/usr/bin/docker", "rm", "--force", "--volumes", name])
                data = directory / label
                data.mkdir(mode=0o700)
                fixture_config = directory / f"{label}-config.json"
                config["cluster_addr"] = f"https://{cluster_ips[label]}:8201"
                fixture_config.write_text(json.dumps(config))
                command(["/usr/bin/docker", "run", "--detach", "--name", name,
                         "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                         "--user", f"{os.getuid()}:{os.getgid()}", "--memory", "512m",
                         "--memory-swap", "512m", "--cpus", "1", "--pids-limit", "64",
                         "--log-driver", "none", "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=16m",
                         "--mount", f"type=bind,src={tls},dst=/openbao/tls,readonly",
                         "--network", network, "--ip", cluster_ips[label],
                         "--mount", f"type=bind,src={fixture_config},dst=/openbao/config.json,readonly",
                         "--mount", f"type=bind,src={data},dst=/openbao/data",
                         "--publish", "127.0.0.1::8200", "--entrypoint", "/usr/bin/bao", image,
                         "server", "-config=/openbao/config.json"])
                return name, Client(mapped_port(name), tls / "tls.crt"), data

            source, client, data = start("source")
            step("source-health")
            health = client.wait_health(501)
            if health["version"] != "2.6.4":
                raise RehearsalFailed("fixture health version mismatch")
            step("probe-sealed")
            def cli_probe(probe: list[str], expected_exit: int, *, wrong_hostname: bool = False):
                args = ["/usr/bin/docker", "exec", "-e", "BAO_ADDR=https://127.0.0.1:8200",
                        "-e", "BAO_CACERT=/openbao/tls/tls.crt", "-e", "BAO_CLIENT_TIMEOUT=3s",
                        "-e", "BAO_MAX_RETRIES=0", "-e", "HOME=/tmp"]
                if wrong_hostname:
                    args += ["-e", "BAO_TLS_SERVER_NAME=wrong.invalid"]
                return command(args + [source] + probe, expected_exit=expected_exit, timeout=5)

            cli_probe(ALIVE, 0)
            cli_probe(STATUS, 2)
            # A client trusting the system roots must reject this disposable, untrusted certificate.
            step("tls-negative")
            try:
                urllib.request.build_opener(urllib.request.ProxyHandler({})).open(client.base + "sys/health", timeout=5)
            except urllib.error.URLError as error:
                if not isinstance(error.reason, ssl.SSLCertVerificationError):
                    raise RehearsalFailed("fixture TLS negative inconclusive") from None
            else:
                raise RehearsalFailed("fixture TLS verification bypass")
            # Both native probe paths must fail closed on a certificate-name mismatch.
            cli_probe(ALIVE, 2, wrong_hostname=True)
            cli_probe(STATUS, 1, wrong_hostname=True)
            step("source-init")
            keys, root_token = initialize_encrypted(client, source, directory / 'encrypted-custody')
            step("probe-unsealed")
            cli_probe(ALIVE, 0)
            cli_probe(STATUS, 0)
            step("kv-and-acl")
            step("kv-mount")
            client.call("sys/mounts/fixture", "POST", {"type": "kv", "options": {"version": "2"}}, root_token, 204)
            canary = "ci-only-" + uuid.uuid4().hex
            step("kv-write")
            client.call("fixture/data/audit", "POST", {"data": {"value": canary}}, root_token)
            step("acl-policy")
            client.call("sys/policies/acl/audit-reader", "PUT", {"policy":
                'path "fixture/data/audit" { capabilities = ["read"] }'}, root_token, 204)
            step("acl-token")
            reader = client.call("auth/token/create", "POST", {"policies": ["audit-reader"],
                "no_default_policy": True, "ttl": "5m", "explicit_max_ttl": "5m"}, root_token)["auth"]["client_token"]
            step("acl-write-denied")
            client.call("fixture/data/audit", "POST", {"data": {"value": "must-not-write"}}, reader, 403)
            step("snapshot")
            snapshot = client.call("sys/storage/raft/snapshot", token=root_token, raw=True)
            if not snapshot or canary.encode() in snapshot:
                raise RehearsalFailed("fixture snapshot encryption mismatch")
            # Let the storage snapshot writer finish before exercising process restart.
            threading.Event().wait(2)
            step("restart")
            command(["/usr/bin/docker", "restart", "--time", "30", source])
            # Docker may reallocate an anonymous published host port after restart.
            # Re-read the loopback mapping before probing the restarted API.
            client = Client(mapped_port(source), tls / "tls.crt")
            client.wait_health(503)
            cli_probe(ALIVE, 0)
            cli_probe(STATUS, 2)
            for key in keys[:2]:
                client.call("sys/unseal", "PUT", {"key": key})
            client.wait_health(200)
            step("restart-read")
            if client.call("fixture/data/audit", token=reader)["data"]["data"]["value"] != canary:
                raise RehearsalFailed("fixture Raft restart persistence mismatch")

            _, restored, restored_data = start("restore")
            restored.wait_health(501)
            step("restore-init")
            _, temporary_root = initialize(restored)
            # Force is confined to this newly initialized EMPTY fixture, never existing/production state.
            step("snapshot-restore")
            restored.call("sys/storage/raft/snapshot-force", "POST", snapshot, temporary_root, 204)
            restored.wait_health(503)
            for key in keys[:2]:
                restored.call("sys/unseal", "PUT", {"key": key})
            restored.wait_health(200)
            step("restore-read")
            if restored.call("fixture/data/audit", token=reader)["data"]["data"]["value"] != canary:
                raise RehearsalFailed("fixture restored value mismatch")
            restored.call("fixture/data/audit", "POST", {"data": {"value": "must-not-write"}}, reader, 403)
            step('lost-init-archive')
            command(['/usr/bin/docker', 'stop', source], timeout=30)
            archive = directory / 'lost-initialization'
            archive.mkdir(mode=0o700)
            owners = {'owner': os.getuid(), 'group': os.getgid()}
            original = lost_init.inventory(data, **owners)
            inode = data.stat().st_ino
            lost_init.move_contents(data, archive, original, **owners)
            if any(data.iterdir()) or data.stat().st_ino != inode:
                raise RehearsalFailed('fixture original data directory changed')
            command(['/usr/bin/docker', 'start', source])
            client = Client(mapped_port(source), tls / 'tls.crt')
            client.wait_health(501)
            step('fresh-store-init')
            initialize_encrypted(client, source, directory / 'replacement-custody')
            step('archive-rollback')
            command(['/usr/bin/docker', 'stop', source], timeout=30)
            replacement = directory / 'replacement-initialization'
            replacement.mkdir(mode=0o700)
            lost_init.move_contents(data, replacement, lost_init.inventory(data, **owners), **owners)
            lost_init.move_contents(archive, data, original, **owners)
            command(['/usr/bin/docker', 'start', source])
            client = Client(mapped_port(source), tls / 'tls.crt')
            client.wait_health(503)
            for key in keys[:2]:
                client.call('sys/unseal', 'PUT', {'key': key})
            client.wait_health(200)
            step('archive-restart-read')
            if client.call('fixture/data/audit', token=reader)['data']['data']['value'] != canary:
                raise RehearsalFailed('fixture retained archive recovery mismatch')
            # Read persistence while its scoped reader is still valid. Revoking
            # the parent root must THEN revoke its descendants, not orphan them.
            step("root-revoke")
            client.call("auth/token/revoke-self", "POST", {}, root_token, 204)
            client.call("sys/mounts", token=root_token, expected=403)
            client.call('fixture/data/audit', token=reader, expected=403)
            restored.call("auth/token/revoke-self", "POST", {}, root_token, 204)
            restored.call("sys/mounts", token=root_token, expected=403)
            step("audit-redaction")
            for audit_data in (data, restored_data):
                audit = (audit_data / "audit.jsonl").read_bytes()
                if not audit or len(audit) > BOUND or any(value.encode() in audit for value in
                                                        [canary, reader, root_token, temporary_root, *keys]):
                    raise RehearsalFailed("fixture audit leak or size mismatch")
            step("cleanup")
    # Cleanup is part of success, not an action performed after the success receipt.
    print("OPENBAO_RECOVERY=Passed; TLS; encrypted custody; Shamir 3/2; Raft restart; isolated restore; ACL; audit redaction; root revocation")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ci", action="store_true", required=True)
    parser.parse_args()
    try:
        rehearse()
        return 0
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError,
            RehearsalFailed, activation.custody.BootstrapFailed):
        print("OPENBAO_RECOVERY=Failed; inspect the named CI step; no secret diagnostics emitted")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
