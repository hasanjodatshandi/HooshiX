#!/usr/bin/env python3
"""CI-only Kubernetes/PVC/TLS rehearsal, not staging or production promotion."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import socket
import subprocess
import tarfile
import tempfile
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

import import_intermediate_ca as ca_import
from rehearse_openbao_recovery import BOUND, Client, RehearsalFailed, initialize
from render_openbao_candidate import ALIVE, NAMESPACE, ROOT, STATUS, candidate
from render_openbao_local_storage import candidate as local_storage_candidate

STEPS = frozenset({"tools", "cluster", "ca-import", "schema", "schema-crds", "schema-candidate",
                   "schema-pod", "schema-psa-negative", "tls", "workload", "sealed",
                   "unseal", "acl", "restart", "pvc-retain", "revoke", "privacy", "cleanup"})


def step(name: str) -> None:
    if name not in STEPS:
        raise RehearsalFailed("unknown Kubernetes step")
    print("OPENBAO_KUBERNETES_STEP=" + name, flush=True)


def run(args: list[str], *, data: bytes | None = None, timeout: int = 30,
        expected: int = 0, bound: int = BOUND, public_schema: bool = False,
        required_error: bytes | None = None) -> bytes:
    # Native argv only. Secret input/output stays in memory, never diagnostics.
    result = subprocess.run(args, input=data, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE if public_schema else subprocess.DEVNULL,
                            timeout=timeout, check=False,
                            env={"PATH": "/usr/bin:/bin", "HOME": "/tmp", "LC_ALL": "C"})
    if (result.returncode != expected or len(result.stdout) > bound or
            (required_error is not None and required_error not in (result.stderr or b""))):
        if public_schema:
            # Only public schema input, before TLS/Secret/init. Escape CI controls and bound output.
            print("OPENBAO_PUBLIC_SCHEMA_ERROR=" + json.dumps(
                (result.stderr or b"")[:4096].decode("utf-8", errors="replace")), flush=True)
        raise RehearsalFailed("Kubernetes fixture command failed")
    return result.stdout


def pins() -> dict[str, str]:
    return dict(line.split("=", 1) for line in (ROOT / "infrastructure/kind/pins.env")
                .read_text().splitlines() if line and not line.startswith("#"))


def runner_paths(tools: Path, receipt: Path) -> None:
    runtime = os.environ.get("RUNNER_TEMP", "")
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.getuid() == 0 or not runtime:
        raise RehearsalFailed("disposable non-root GitHub runner required")
    root = Path(runtime).resolve(strict=True)
    for path in (tools, receipt.parent):
        if path.is_symlink() or not path.resolve(strict=True).is_relative_to(root):
            raise RehearsalFailed("runner-owned paths required")
    if receipt.exists() or receipt.is_symlink():
        raise RehearsalFailed("new receipt required")


def wait(check, *, seconds: int = 90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = check()
        if value:
            return value
        threading.Event().wait(1)
    raise RehearsalFailed("Kubernetes fixture deadline")


def ca_import_fixture(k):
    # Real API create/read/reconcile with disposable data; never the operator's CA.
    def native_kube(*args, body=None):
        return k(*args, data=json.dumps(body).encode() if body is not None else None)
    data = {name: b'ci-disposable-not-a-production-certificate-or-key'
            for name in ('ca-cert.pem', 'ca-key.pem', 'root-cert.pem', 'cert-chain.pem')}
    with patch.object(ca_import, 'kube', side_effect=native_kube):
        ca_import.ensure_secret(data)
        ca_import.ensure_secret(data)
        try:
            ca_import.ensure_secret(data | {'ca-key.pem': b'conflicting-ci-key'})
        except ca_import.custody.BootstrapFailed:
            pass
        else:
            raise RehearsalFailed('CA fixture conflict was not rejected')
        ca_import.ensure_secret(data)  # Prove conflict rejection preserved the original.


def rehearse(tools: Path, receipt: Path, platform_directory: Path | None = None) -> None:
    runner_paths(tools, receipt)
    platform = None
    if platform_directory is not None:
        root = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True)
        if platform_directory.is_symlink() or not platform_directory.resolve(strict=True).is_relative_to(root):
            raise RehearsalFailed("runner-owned publication directory required")
        from rehearse_platform_admission import Staging
        platform = Staging(platform_directory)
    pin = pins()
    step("tools")
    for tool, key in (("kind", "KIND_LINUX_AMD64_SHA256"),
                      ("kubectl", "KUBECTL_LINUX_AMD64_SHA256")):
        binary = tools / tool
        if binary.is_symlink() or hashlib.sha256(binary.read_bytes()).hexdigest() != pin[key]:
            raise RehearsalFailed("pinned fixture tool required")
    kind, kubectl = str(tools / "kind"), str(tools / "kubectl")
    if not re.fullmatch(r"kindest/node:v1\.35\.5@sha256:[a-f0-9]{64}", pin["KIND_NODE_IMAGE"]):
        raise RehearsalFailed("pinned fixture node required")
    if f"v{pin['KIND_VERSION']}".encode() not in run([kind, "version"]):
        raise RehearsalFailed("fixture kind version mismatch")
    version = json.loads(run([kubectl, "version", "--client", "-o", "json"]))
    if version["clientVersion"]["gitVersion"] != "v" + pin["KUBECTL_VERSION"]:
        raise RehearsalFailed("fixture kubectl version mismatch")
    name = "hooshix-bao-ci-" + uuid.uuid4().hex[:12]
    image = candidate("standard")["items"][-1]["spec"]["template"]["spec"]["containers"][0]["image"]
    if platform is not None:
        image = platform.bao_receipt["image"]
    started = False
    with tempfile.TemporaryDirectory(prefix="hooshix-bao-k8s-", dir=os.environ["RUNNER_TEMP"]) as temp:
        directory = Path(temp)
        kubeconfig = directory / "kubeconfig"
        cluster_config = directory / "kind.json"
        cluster_configuration = {"kind": "Cluster", "apiVersion": "kind.x-k8s.io/v1alpha4",
                                 "nodes": [{"role": "control-plane"}]}
        if platform is not None:
            cluster_configuration["networking"] = {"disableDefaultCNI": True,
                "podSubnet": "192.168.0.0/16", "serviceSubnet": "10.96.0.0/16"}
        cluster_config.write_text(json.dumps(cluster_configuration))

        schema_only = True

        def k(*args: str, data: bytes | None = None, timeout: int = 30, expected: int = 0,
              public_schema: bool = False, required_error: bytes | None = None) -> bytes:
            if public_schema and not schema_only:
                raise RehearsalFailed("schema diagnostics disabled after secret generation")
            return run([kubectl, "--kubeconfig", str(kubeconfig), "--context", "kind-" + name,
                        "--request-timeout=30s", *args], data=data, timeout=timeout, expected=expected,
                       public_schema=public_schema, required_error=required_error)

        def get(kind_name: str, resource: str):
            return json.loads(k("-n", NAMESPACE, "get", kind_name, resource, "-o", "json"))

        def pod_running():
            pods = json.loads(k("-n", NAMESPACE, "get", "pods", "-o", "json"))["items"]
            statuses = pods[0]["status"].get("containerStatuses", []) if pods else []
            return statuses and statuses[0].get("state", {}).get("running")

        def cli(command: list[str], expected: int = 0, *, wrong_name: bool = False):
            # kubectl exec cannot set env in a shell-free image; native CLI flags override it.
            flags = ["-address=https://127.0.0.1:8200", "-ca-cert=/openbao/tls/ca.crt"]
            if wrong_name:
                flags.append("-tls-server-name=wrong.invalid")
            return k("-n", NAMESPACE, "exec", "openbao-0", "--", command[0], command[1],
                     *flags, *command[2:], expected=expected, timeout=15)

        def forward(ca: Path):
            # Select a free loopback port; a race fails closed rather than falling back remotely.
            with socket.socket() as sock:
                sock.bind(("127.0.0.1", 0))
                port = sock.getsockname()[1]
            process = subprocess.Popen([kubectl, "--kubeconfig", str(kubeconfig), "--context", "kind-" + name,
                "-n", NAMESPACE, "port-forward", "pod/openbao-0", f"{port}:8200", "--address=127.0.0.1"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                env={"PATH": "/usr/bin:/bin", "HOME": "/tmp", "LC_ALL": "C"})
            return process, Client(port, ca)

        def stop(process):
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

        try:
            step("cluster")
            if name in run([kind, "get", "clusters"]).decode().splitlines():
                raise RehearsalFailed("existing cluster must not be reused")
            started = True  # Also remove a partially-created cluster on failure.
            run([kind, "create", "cluster", "--name", name, "--image", pin["KIND_NODE_IMAGE"],
                 "--config", str(cluster_config), "--kubeconfig", str(kubeconfig), "--wait",
                 "0s" if platform is not None else "120s"], timeout=240)
            kubeconfig.chmod(0o600)
            if platform is not None:
                platform.setup(k, run, directory, wait)
            server = json.loads(k("version", "-o", "json"))["serverVersion"]["gitVersion"]
            if server != "v" + pin["KUBERNETES_VERSION"]:
                raise RehearsalFailed("fixture API version mismatch")
            step("schema")
            istio = dict(line.split("=", 1) for line in (ROOT / "infrastructure/istio/pins.env")
                         .read_text().splitlines() if line and not line.startswith("#"))
            archive = ROOT / f"infrastructure/istio/chart/{istio['ISTIO_VERSION']}/base-{istio['ISTIO_VERSION']}.tgz"
            if hashlib.sha256(archive.read_bytes()).hexdigest() != istio["ISTIO_BASE_CHART_SHA256"]:
                raise RehearsalFailed("pinned CRD chart required")
            with tarfile.open(archive) as chart:
                crd = chart.extractfile("base/files/crd-all.gen.yaml")
                if crd is None:
                    raise RehearsalFailed("CRDs missing")
                step("schema-crds")
                k("apply", "--server-side", "-f", "-", data=crd.read(BOUND + 1), public_schema=True)
            for crd_name in ("peerauthentications.security.istio.io", "authorizationpolicies.security.istio.io"):
                k("wait", "--for=condition=Established", "crd/" + crd_name, "--timeout=30s", timeout=40,
                  public_schema=True)
            manifest = candidate("standard")
            if platform is not None:
                manifest = platform.manifest("standard")
            step("schema-candidate")
            k("apply", "-f", "-", data=json.dumps(manifest["items"][0]).encode(), public_schema=True)
            k("apply", "--dry-run=server", "-f", "-", data=json.dumps(manifest).encode(), public_schema=True)
            # Review-only local PV schema; never bind it to the unrelated kind node.
            k("apply", "--dry-run=server", "-f", "-",
              data=json.dumps(local_storage_candidate()).encode(), public_schema=True)
            # Server dry-run does not persist the candidate's ServiceAccount, required by Pod admission.
            k("apply", "-f", "-", data=json.dumps(manifest["items"][1]).encode(), public_schema=True)
            # Prove restricted PSA rejects an unsafe Pod, not just intended labels.
            pod_spec = json.loads(json.dumps(manifest["items"][-1]["spec"]["template"]["spec"]))
            pod_spec["volumes"].append({"name": "data", "emptyDir": {}})
            test_pod = {"apiVersion": "v1", "kind": "Pod", "metadata": {
                "name": "ci-admission-probe", "namespace": NAMESPACE}, "spec": pod_spec}
            step("schema-pod")
            k("apply", "--dry-run=server", "-f", "-", data=json.dumps(test_pod).encode(), public_schema=True)
            pod_spec["containers"][0]["securityContext"]["privileged"] = True
            # Keep the unsafe fixture structurally valid so PSA, not core schema validation, denies it.
            pod_spec["containers"][0]["securityContext"]["allowPrivilegeEscalation"] = True
            step("schema-psa-negative")
            k("apply", "--dry-run=server", "-f", "-", data=json.dumps(test_pod).encode(), expected=1,
              public_schema=True, required_error=b'violates PodSecurity "restricted:v1.35": privileged')
            schema_only = False
            if platform is None:
                step("ca-import")
                ca_import_fixture(k)
            step("tls")
            key, cert = directory / "tls.key", directory / "tls.crt"
            run(["/usr/bin/openssl", "req", "-x509", "-newkey", "rsa:3072", "-sha256", "-nodes",
                 "-days", "1", "-subj", "/CN=hooshix-ci-only", "-addext",
                 "subjectAltName=IP:127.0.0.1,DNS:openbao.hooshix-secrets.svc,DNS:openbao-0.openbao.hooshix-secrets.svc",
                 "-keyout", str(key), "-out", str(cert)])
            key.chmod(0o600)
            # Generated fixture Secret via stdin; never a file, argv value or uploaded artifact.
            secret = {"apiVersion": "v1", "kind": "Secret", "metadata": {
                "name": "openbao-server-tls", "namespace": NAMESPACE}, "type": "Opaque",
                "data": {label: base64.b64encode(path.read_bytes()).decode() for label, path in
                         (("tls.crt", cert), ("ca.crt", cert), ("tls.key", key))}}
            k("apply", "-f", "-", data=json.dumps(secret).encode())
            step("workload")
            k("apply", "-f", "-", data=json.dumps(manifest).encode())
            wait(pod_running, seconds=180)
            actual = get("pod", "openbao-0")
            if (actual["spec"]["containers"][0]["image"] != image or
                    not actual["status"]["containerStatuses"][0]["imageID"].endswith(image.split("@", 1)[1])):
                raise RehearsalFailed("fixture image mismatch")
            claim_uid = get("pvc", "data-openbao-0")["metadata"]["uid"]
            step("sealed")
            cli(ALIVE)
            cli(STATUS, 2)
            cli(ALIVE, 2, wrong_name=True)
            cli(STATUS, 1, wrong_name=True)
            threading.Event().wait(20)
            status = get("pod", "openbao-0")["status"]["containerStatuses"][0]
            if status["ready"] or status["restartCount"] != 0:
                raise RehearsalFailed("sealed probe restarted or exposed ready workload")
            if platform is not None:
                platform.network(k, wait)
            process, client = forward(cert)
            try:
                client.wait_health(501)
                step("unseal")
                keys, token = initialize(client)
                k("-n", NAMESPACE, "wait", "--for=condition=Ready", "pod/openbao-0", "--timeout=60s", timeout=70)
                step("acl")
                client.call("sys/mounts/fixture", "POST", {"type": "kv", "options": {"version": "2"}}, token, 204)
                canary = "ci-only-" + uuid.uuid4().hex
                client.call("fixture/data/value", "POST", {"data": {"value": canary}}, token)
                client.call("sys/policies/acl/fixture-reader", "PUT", {"policy":
                    'path "fixture/data/value" { capabilities = ["read"] }'}, token, 204)
                reader = client.call("auth/token/create", "POST", {"policies": ["fixture-reader"],
                    "no_default_policy": True, "ttl": "5m", "explicit_max_ttl": "5m"}, token)["auth"]["client_token"]
                client.call("fixture/data/value", "POST", {"data": {"value": "denied"}}, reader, 403)
                logs = k("-n", NAMESPACE, "logs", "openbao-0", "--tail=-1")
                if any(value.encode() in logs for value in (canary, token, reader, *keys)):
                    raise RehearsalFailed("fixture container log leak")
            finally:
                stop(process)
            step("restart")
            k("-n", NAMESPACE, "delete", "pod/openbao-0", "--wait=true", "--timeout=60s", timeout=70)
            wait(pod_running)
            cli(ALIVE)
            cli(STATUS, 2)
            step("pvc-retain")
            k("-n", NAMESPACE, "scale", "statefulset/openbao", "--replicas=0")
            k("-n", NAMESPACE, "wait", "--for=delete", "pod/openbao-0", "--timeout=60s", timeout=70)
            if get("pvc", "data-openbao-0")["metadata"]["uid"] != claim_uid:
                raise RehearsalFailed("PVC retention failed")
            k("-n", NAMESPACE, "scale", "statefulset/openbao", "--replicas=1")
            wait(pod_running)
            process, client = forward(cert)
            try:
                client.wait_health(503)
                for share in keys[:2]:
                    client.call("sys/unseal", "PUT", {"key": share})
                client.wait_health(200)
                k("-n", NAMESPACE, "wait", "--for=condition=Ready", "pod/openbao-0", "--timeout=60s", timeout=70)
                if client.call("fixture/data/value", token=reader)["data"]["data"]["value"] != canary:
                    raise RehearsalFailed("PVC data persistence failed")
                step("revoke")
                client.call("auth/token/revoke-self", "POST", {}, token, 204)
                client.call("sys/mounts", token=token, expected=403)
                step("privacy")
                logs = k("-n", NAMESPACE, "logs", "openbao-0", "--tail=-1")
                if any(value.encode() in logs for value in (canary, token, reader, *keys)):
                    raise RehearsalFailed("fixture container log leak")
            finally:
                stop(process)
        finally:
            if started:
                step("cleanup")
                run([kind, "delete", "cluster", "--name", name], timeout=120)
                if name in run([kind, "get", "clusters"]).decode().splitlines():
                    raise RehearsalFailed("fixture cleanup failed")
    # Only public evidence persists, after private fixture files and cluster are removed.
    result = {"schema_version": 1, "scope": "Disposable Kubernetes foundation; not approved staging",
        "observed_at": datetime.now(timezone.utc).isoformat(),
        "revision": run(["/usr/bin/git", "rev-parse", "HEAD"]).decode().strip(), "image": image,
        "node_image": pin["KIND_NODE_IMAGE"], "kubernetes": server,
        "checks": {label: "Passed" for label in ("ca_secret_create_reconcile_conflict", "server_schema", "static_local_pv_schema", "restricted_workload", "tls_mount_fsGroup",
            "sealed_probes", "tls_hostname_negative", "shamir_3_2", "kv_acl", "restart", "pvc_retention",
            "pvc_data_persistence", "root_revocation", "container_log_privacy", "cleanup")},
        "network_mesh_admission": "Not verified: default kind CNI; Istio CRDs only",
        "production_storage_quota_and_capacity": "Not verified: disposable local-path fixture",
        "signing_staging_promotion": "Not verified", "production_readiness": "Not verified"}
    if platform is not None:
        result.update({"scope": "Disposable Calico/Ambient/Kyverno signed platform staging",
            "platform_checks": platform.checks,
            "mesh_images": {name: record["image"] for name, record in platform.mesh_receipt["components"].items()},
            "publication_revisions": {"mesh": platform.mesh_receipt["repository_revision"],
                                      "openbao": platform.bao_receipt["repository_revision"]},
            "network_mesh_admission": "Passed for disposable kind fixture",
            "production_target": "Not verified: K3s paths, imported owner CA and guarded 8GiB PV not exercised",
            "ca_secret_reconcile": "Not applicable: foundation job owns separate conflict fixture"})
        result["checks"].pop("ca_secret_create_reconcile_conflict")
    with receipt.open("x", encoding="utf-8") as output:
        json.dump(result, output, indent=2)
        output.write("\n")
    receipt.chmod(0o600)
    print("OPENBAO_KUBERNETES=Passed; public receipt only; no promotion authority")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ci", action="store_true", required=True)
    parser.add_argument("--tools-dir", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--platform-publications", type=Path)
    args = parser.parse_args()
    try:
        rehearse(args.tools_dir, args.receipt, args.platform_publications)
        return 0
    except (OSError, ValueError, KeyError, IndexError, subprocess.SubprocessError,
            tarfile.TarError, RehearsalFailed):
        print("OPENBAO_KUBERNETES=Failed; inspect fixed CI step; secret diagnostics suppressed")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
