"""Disposable-runner platform staging hooks; no existing cluster or VPS access."""
from __future__ import annotations

import base64
import copy
import hashlib
import json
import os
import shutil
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

import commission_platform as commissioning
import render_mesh_candidate as mesh
import render_platform_admission as admission
import upgrade_kyverno
from rehearse_openbao_recovery import RehearsalFailed


def pin_file(relative: str) -> dict:
    return dict(line.split("=", 1) for line in (mesh.ROOT / relative).read_text().splitlines()
                if line and not line.startswith("#"))


def vendored(relative: str, digest: str) -> bytes:
    data = (mesh.ROOT / relative).read_bytes()
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValueError("staging vendor integrity failed")
    return data


def apply(k, resource):
    return k("apply", "--server-side", "--field-manager=hooshix-staging", "-f", "-",
             data=json.dumps(resource).encode())


def policy_ready(k, policy):
    resource = policy["kind"].lower() + "/" + policy["metadata"]["name"]
    try:
        k("wait", "--for=jsonpath={.status.conditionStatus.ready}=true", resource, "--timeout=60s", timeout=75)
    except (ValueError, OSError, RehearsalFailed):
        # Only the status of a public policy is read; never its registry Secret,
        # controller logs, environment or request/response body diagnostics.
        status = json.loads(k("get", resource, "-o", "json")).get("status", {})
        print("PLATFORM_POLICY_STATUS=" + json.dumps({"name": policy["metadata"]["name"],
              "conditions": status.get("conditionStatus", {})})[:4096], flush=True)
        raise


def admission_result(k, pod, wait, *, expected=0, message=None):
    # Policy Ready reports webhook configuration, not an updated cache generation.
    # Poll the harmless server-side dry-run while the reviewed policy converges.
    # Success still requires the exact denial reason, never just any HTTP error.
    def converged():
        try:
            k("apply", "--dry-run=server", "-f", "-", data=json.dumps(pod).encode(),
              expected=expected, public_schema=True, required_error=message)
            return True
        except RehearsalFailed:
            return False
    try:
        wait(converged, seconds=45)
    except RehearsalFailed:
        # Narrow public control-plane diagnostics only, never Secret/config/log
        # dumps. This distinguishes disabled evaluation, compilation/autogen and
        # an absent webhook without exposing registry or TLS material.
        for kind in ("validatingpolicies", "imagevalidatingpolicies"):
            values = json.loads(k("get", kind, "-o", "json"))["items"]
            for value in values:
                if value["metadata"]["name"].startswith("hooshix-"):
                    spec = value["spec"]
                    print("PLATFORM_ADMISSION_POLICY=" + json.dumps({
                        "name": value["metadata"]["name"], "status": value.get("status", {}),
                        "actions": spec.get("validationActions"), "evaluation": spec.get("evaluation"),
                        "autogen": spec.get("autogen")})[:8192], flush=True)
        values = json.loads(k("get", "validatingwebhookconfigurations", "-o", "json"))["items"]
        for value in values:
            if "kyverno" in value["metadata"]["name"]:
                print("PLATFORM_ADMISSION_WEBHOOK=" + json.dumps({
                    "name": value["metadata"]["name"], "webhooks": [{
                        key: webhook.get(key) for key in ("name", "rules", "namespaceSelector", "matchConditions")}
                        for webhook in value.get("webhooks", [])]})[:16384], flush=True)
        raise


def namespace(name: str, *, ambient: bool = False):
    return {"apiVersion": "v1", "kind": "Namespace", "metadata": {"name": name,
        "labels": ({"istio.io/dataplane-mode": "ambient"} if ambient else {})}}


def registry_secret(name: str):
    # GITHUB_TOKEN has packages:read only in this disposable job. Never token argv,
    # environment forwarding to kubectl, persisted config, diagnostics or receipt.
    token = os.environ.get("GITHUB_TOKEN", "")
    if not token or len(token) > 4096 or any(c in token for c in "\r\n\0"):
        raise ValueError("ephemeral read-only package token required")
    auth = base64.b64encode(("hasanjodatshandi:" + token).encode()).decode()
    config = json.dumps({"auths": {"ghcr.io": {"auth": auth}}}).encode()
    return {"apiVersion": "v1", "kind": "Secret", "metadata": {
        "name": admission.PULL_SECRET, "namespace": name}, "type": "kubernetes.io/dockerconfigjson",
        "data": {".dockerconfigjson": base64.b64encode(config).decode()}}


def registry_restart(k):
    # Execute the production Secret update path against the disposable API.
    # Synthetic values only: reproduce create -> SSA conflict -> conditional
    # update, then prove stale-write rejection and same-value idempotency.
    def registry_kube(*args, body=None, output_bound=commissioning.BOUND, **options):
        if output_bound != commissioning.BOUND:
            raise ValueError('registry read bound changed')
        return k(*args, data=json.dumps(body).encode() if body is not None else None, **options)

    first = base64.b64encode(b'{"auths":{"fixture.invalid":{"auth":"Zmlyc3Q="}}}').decode()
    second = base64.b64encode(b'{"auths":{"fixture.invalid":{"auth":"c2Vjb25k"}}}').decode()
    with patch.object(commissioning, 'kube', registry_kube):
        for ns in ('kyverno', *admission.NAMESPACES):
            commissioning.write_registry_secret(ns, first)
            before = commissioning.get('secret', admission.PULL_SECRET, ns)
            conflicting = copy.deepcopy(before)
            conflicting['metadata'].pop('managedFields', None)
            conflicting['data']['.dockerconfigjson'] = second
            k('apply', '--server-side', '--field-manager=' + commissioning.MANAGER, '-f', '-',
              data=json.dumps(conflicting).encode(), expected=1, public_schema=True, required_error=b'conflict')
            commissioning.write_registry_secret(ns, second)
            after = commissioning.get('secret', admission.PULL_SECRET, ns)
            if (after['metadata']['uid'] != before['metadata']['uid']
                    or after['data'] != {'.dockerconfigjson': second}):
                raise ValueError('registry rotation recreated Secret or lost update')
            commissioning.write_registry_secret(ns, second)
            unchanged = commissioning.get('secret', admission.PULL_SECRET, ns)
            if unchanged['metadata']['resourceVersion'] != after['metadata']['resourceVersion']:
                raise ValueError('identical registry reentry wrote state')
            k('replace', '--field-manager=' + commissioning.MANAGER, '-f', '-',
              data=json.dumps(before).encode(), expected=1, public_schema=True,
              required_error=b'the object has been modified')
            retained = commissioning.get('secret', admission.PULL_SECRET, ns)
            if retained['data'] != after['data'] or retained['metadata']['uid'] != after['metadata']['uid']:
                raise ValueError('stale registry update changed state')
            # Materialize the ephemeral read-only CI token through the same
            # owned update path, without public-schema/credential diagnostics.
            commissioning.write_registry_secret(ns, registry_secret(ns)['data']['.dockerconfigjson'])


def verifier_egress_repair(k, pod, wait):
    # CI-only reconstruction of the target's existing Egress restriction. API
    # addresses differ in kind; no target IP, credential or policy is imported.
    service = json.loads(k('-n', 'default', 'get', 'service/kubernetes', '-o', 'json'))
    endpoints = json.loads(k('-n', 'default', 'get', 'endpointslices',
                             '-l', 'kubernetes.io/service-name=kubernetes', '-o', 'json'))
    addresses = {service['spec']['clusterIP']}
    for item in endpoints['items']:
        for endpoint in item['endpoints']:
            addresses.update(endpoint['addresses'])
    fixture = {'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy',
        'metadata': {'name': 'private-admission-boundary', 'namespace': 'kyverno'},
        'spec': {'podSelector': {}, 'policyTypes': ['Egress'], 'egress': [
            {'ports': [{'port': 6443, 'protocol': 'TCP'}, {'port': 443, 'protocol': 'TCP'}],
             'to': [{'ipBlock': {'cidr': address + '/32'}} for address in sorted(addresses)]},
            {'ports': [{'port': 53, 'protocol': p} for p in ('TCP', 'UDP')], 'to': [{
                'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': 'kube-system'}},
                'podSelector': {'matchLabels': {'k8s-app': 'kube-dns'}}}]},
            {'ports': [{'port': 443, 'protocol': 'TCP'}, {'port': 9443, 'protocol': 'TCP'}],
             'to': [{'podSelector': {}}]}]}}
    apply(k, fixture)
    before = json.loads(k('-n', 'kyverno', 'get', 'networkpolicy/private-admission-boundary', '-o', 'json'))
    # Same correctly signed Pod, first denied by transport, then admitted after
    # the production repair. A generic failing request alone cannot pass this test.
    admission_result(k, pod, wait, expected=1, message=b'failed to evaluate policy')
    print('PLATFORM_VERIFIER_RESTRICTED_EGRESS=Denied', flush=True)

    def adapter(*args, body=None, **options):
        options.pop('output_bound', None)
        return k(*args, data=json.dumps(body).encode() if body is not None else None, **options)

    with patch.object(commissioning, 'kube', adapter):
        commissioning.install_image_verifier_egress(admission.platform_image_egress.candidate())
        commissioning.install_image_verifier_egress(admission.platform_image_egress.candidate())
    admission_result(k, pod, wait)
    after = json.loads(k('-n', 'kyverno', 'get', 'networkpolicy/private-admission-boundary', '-o', 'json'))
    if before['metadata']['uid'] != after['metadata']['uid'] or before['spec'] != after['spec']:
        raise ValueError('existing admission network boundary changed')
    print('PLATFORM_VERIFIER_HTTPS_REPAIR=Passed; existing boundary preserved', flush=True)


def tuf_trust_boundary(k, pod, policy, wait):
    # CI-only invalid public trust fixture: reaching the selected mirror cannot
    # grant trust without the embedded root. Never import the owner's PKI here.
    name = 'imagevalidatingpolicy/' + policy['metadata']['name']
    before = json.loads(k('get', name, '-o', 'json'))
    admission_result(k, pod, wait)
    changed = copy.deepcopy(policy)
    changed['spec']['attestors'][0]['cosign']['tuf']['root'] = {
        'data': base64.b64encode(b'{"untrusted":true}').decode()}
    try:
        apply(k, changed)
        admission_result(k, pod, wait, expected=1,
                         message=b'platform image signature verification failed')
        print('PLATFORM_TUF_UNTRUSTED_ROOT=Denied', flush=True)
    finally:
        apply(k, policy)
    admission_result(k, pod, wait)
    after = json.loads(k('get', name, '-o', 'json'))
    if before['metadata']['uid'] != after['metadata']['uid'] or before['spec'] != after['spec']:
        raise ValueError('TUF transport regression changed policy identity or trust')
    print('PLATFORM_SIGNED_TUF_MIRROR=Passed; embedded root and exact signer retained', flush=True)


class Staging:
    def __init__(self, directory: Path):
        now = datetime.now(timezone.utc)
        self.mesh_receipt = admission.publication(directory / "mesh/receipt.json", "mesh", now)
        self.bao_receipt = admission.publication(directory / "openbao/receipt.json", "openbao", now)
        self.plan = admission.render(self.mesh_receipt, self.bao_receipt, mesh.candidate())
        # Only kind's host CNI paths differ. Production source/render is not changed.
        replacements = {"/var/lib/rancher/k3s/data/current/bin/": "/opt/cni/bin",
                        "/var/lib/rancher/k3s/agent/etc/cni/net.d": "/etc/cni/net.d"}
        self.pods = {}
        for stage in self.plan["mesh"]["stages"]:
            for resource in stage["manifest"]["items"]:
                if resource["kind"] not in ("Deployment", "DaemonSet"):
                    continue
                pod = resource["spec"]["template"]["spec"]
                for volume in pod.get("volumes", []):
                    if "hostPath" in volume:
                        source = volume["hostPath"]["path"]
                        if source in replacements:
                            volume["hostPath"]["path"] = replacements[source]
                self.pods[pod["serviceAccountName"]] = pod
        self.plan["admission"]["items"][0] = admission.hardening("istio-system", self.pods)
        self.checks = {}

    def setup(self, k, run, directory: Path, wait):
        print("PLATFORM_STEP=calico", flush=True)
        calico = pin_file("infrastructure/calico/pins.env")
        data = vendored("infrastructure/calico/vendor/" + calico["CALICO_VERSION"] + "/calico.yaml",
                        calico["CALICO_MANIFEST_SHA256"])
        for component, key in (("cni", "CNI"), ("node", "NODE"), ("kube-controllers", "CONTROLLERS")):
            before = "quay.io/calico/" + component + "@" + calico["CALICO_" + key + "_INDEX_DIGEST"]
            after = "quay.io/calico/" + component + "@" + calico["CALICO_" + key + "_AMD64_DIGEST"]
            if before.encode() not in data:
                raise ValueError("exact Calico vendor image required")
            data = data.replace(before.encode(), after.encode())
        k("apply", "-f", "-", data=data)
        for kind, name in (("daemonset", "calico-node"), ("deployment", "calico-kube-controllers"),
                           ("deployment", "coredns")):
            k("-n", "kube-system", "rollout", "status", kind + "/" + name, "--timeout=180s", timeout=195)
        self.checks["calico_runtime"] = "Passed"
        k("wait", "--for=condition=Ready", "nodes", "--all", "--timeout=90s", timeout=105)
        # kind's dynamic provisioner is a fixture-only persistence substitution.
        # Defer it until after initial adoption to exercise the VPS bootstrap
        # guard unchanged. It is restored BEFORE the fixture PVC is created;
        # the actual VPS continues to use its reviewed static retained PV.
        provisioner = json.loads(k('-n', 'local-path-storage', 'get', 'deployment/local-path-provisioner', '-o', 'json'))
        k('-n', 'local-path-storage', 'delete', 'deployment/local-path-provisioner', '--timeout=30s', timeout=40)
        k('-n', 'local-path-storage', 'wait', '--for=delete', 'pods', '--all', '--timeout=30s', timeout=40)
        print("PLATFORM_STEP=kyverno", flush=True)
        # Start from the exact existing VPS release, then exercise the SAME
        # CRD-preserving Helm adoption as the supervised installer.
        data = vendored("infrastructure/kyverno/vendor/1.18.2/install.yaml",
                        '3dcd43eaf11f0719084217148cd0c82a8fa49faa9b1a783ea5bea2cf84041bda')
        old_images = dict(upgrade_kyverno.OLD_IMAGES)
        old_images['pre'] = 'reg.kyverno.io/kyverno/kyvernopre@sha256:341402e2860cce765a744735c0a3a9c0d0978a82dfdff7b76332afb8d3a2d20b'
        for component, new in old_images.items():
            image_name = 'kyverno' if component == 'admission' else 'kyvernopre' if component == 'pre' else component + '-controller'
            old = "reg.kyverno.io/kyverno/" + image_name + ":v1.18.2"
            if old.encode() not in data:
                raise ValueError("exact Kyverno vendor image required")
            data = data.replace(old.encode(), new.encode())
        # Avoid client-side last-applied annotations (large CRDs); no force conflicts.
        k("apply", "--server-side", "--field-manager=hooshix-staging", "-f", "-", data=data, timeout=90)
        for name in ("admission", "background", "cleanup", "reports"):
            k("-n", "kyverno", "rollout", "status", "deployment/kyverno-" + name + "-controller",
              "--timeout=180s", timeout=195)
        print('PLATFORM_STEP=kyverno-policy-retention', flush=True)
        preserved_policy = {'apiVersion': 'policies.kyverno.io/v1', 'kind': 'ValidatingPolicy',
            'metadata': {'name': 'fixture-upgrade-retention'}, 'spec': {'failurePolicy': 'Fail',
            'validationActions': ['Deny'], 'autogen': {'podControllers': {'controllers': []}},
            'matchConstraints': {'resourceRules': [{'apiGroups': [''], 'apiVersions': ['v1'],
                'operations': ['CREATE'], 'resources': ['configmaps']}]},
            'validations': [{'expression': 'true', 'message': 'fixture retention only'}]}}
        # Deployment Ready can precede the old release's webhook endpoint.
        # Wait on this harmless idempotent fixture CREATE, not a weakened
        # failurePolicy or a disabled webhook; expiry still fails staging.
        def retained_policy_available():
            try:
                k('apply', '--server-side', '--field-manager=hooshix-staging', '-f', '-',
                  data=json.dumps(preserved_policy).encode(), public_schema=True)
                return True
            except RehearsalFailed:
                return False

        wait(retained_policy_available, seconds=45)
        policy_before = json.loads(k('get', 'validatingpolicy', 'fixture-upgrade-retention', '-o', 'json'))
        print('PLATFORM_STEP=kyverno-render', flush=True)
        upgrade_plan = upgrade_kyverno.candidate()
        shutil.copyfile(mesh.ROOT / 'infrastructure/kyverno/chart/3.9.1/kyverno-3.9.1.tgz', directory / 'kyverno-3.9.1.tgz')
        tools = Path(os.environ['RUNNER_TEMP']) / 'platform-tools'
        shutil.copyfile(tools / 'helm.tar.gz', directory / 'helm-linux-amd64.tar.gz')

        def upgrade_kube(*args, body=None, **options):
            return k(*args, data=json.dumps(body).encode() if body is not None else None,
                     public_schema=True, **options)

        def upgrade_get(kind, name, namespace=None):
            scope = ['-n', namespace] if namespace else []
            data = k(*scope, 'get', kind, name, '--ignore-not-found', '-o', 'json')
            return json.loads(data) if data.strip() else None

        def upgrade_native(args, **options):
            return run(args, public_schema=True, **options)

        upgrade_kyverno.execute(upgrade_plan, directory, directory, directory / 'kubeconfig',
                               kube=upgrade_kube, native=upgrade_native, get=upgrade_get)
        policy_after = json.loads(k('get', 'validatingpolicy', 'fixture-upgrade-retention', '-o', 'json'))
        if (policy_before['metadata']['uid'] != policy_after['metadata']['uid']
                or policy_before['spec'] != policy_after['spec']):
            raise ValueError('existing Kyverno policy was not preserved')
        self.checks['kyverno_upgrade'] = 'Passed'
        apply(k, {'apiVersion': provisioner['apiVersion'], 'kind': 'Deployment',
                  'metadata': {'name': 'local-path-provisioner', 'namespace': 'local-path-storage'},
                  'spec': provisioner['spec']})
        k('-n', 'local-path-storage', 'rollout', 'status', 'deployment/local-path-provisioner',
          '--timeout=90s', timeout=105)
        for name in admission.NAMESPACES:
            apply(k, namespace(name))
        print('PLATFORM_STEP=registry-restart', flush=True)
        registry_restart(k)
        for account in ("istiod", "istio-cni", "ztunnel"):
            apply(k, {"apiVersion": "v1", "kind": "ServiceAccount", "metadata": {
                "name": account, "namespace": "istio-system"}})
        print("PLATFORM_STEP=admission-audit", flush=True)
        apply(k, self.plan["admission_prerequisites"])
        # Validate exact boundary in Audit first; nothing is promoted by this step.
        for policy in self.plan["admission"]["items"]:
            audit = copy.deepcopy(policy)
            audit["spec"]["validationActions"] = ["Audit"]
            apply(k, audit)
            policy_ready(k, policy)
        test = {"apiVersion": "v1", "kind": "Pod", "metadata": {
            "name": "audit-boundary-negative", "namespace": "istio-system"},
            "spec": copy.deepcopy(self.pods["istiod"])}
        test["spec"]["containers"][0]["image"] = self.bao_receipt["image"]
        test["spec"]["containers"][0]["command"] = ["/usr/bin/bao", "version"]
        test["spec"]["containers"][0]["args"] = []
        test["spec"]["containers"][0].pop("readinessProbe", None)
        # Only harmless version execution with the wrong allow-listed SA/image pair.
        test["spec"]["volumes"] = []
        test["spec"]["containers"][0]["volumeMounts"] = []
        apply(k, test)

        def audit_report():
            reports = json.loads(k("-n", "istio-system", "get", "policyreports", "-o", "json"))["items"]
            return any(result.get("policy") == "hooshix-istio-system-bootstrap-boundary"
                       and result.get("result") == "fail" for report in reports for result in report.get("results", []))

        wait(audit_report, seconds=90)
        k("-n", "istio-system", "delete", "pod/audit-boundary-negative", "--timeout=30s", timeout=40)
        self.checks["admission_audit_negative"] = "Passed"
        print("PLATFORM_STEP=admission-deny", flush=True)
        for policy in self.plan["admission"]["items"]:
            apply(k, policy)
            policy_ready(k, policy)
        admission_result(k, test, wait, expected=1,
                         message=b"platform bootstrap identity/security exception rejected")
        wrong_bao = {"apiVersion": "v1", "kind": "Pod", "metadata": {
            "name": "audit-boundary-negative", "namespace": "hooshix-secrets"},
            "spec": copy.deepcopy(self.plan["openbao"]["items"][-1]["spec"]["template"]["spec"])}
        # Ensure native enforcement in BOTH namespaces, not just whichever
        # selector the shared webhook controller last reconciled.
        apply(k, {"apiVersion": "v1", "kind": "ServiceAccount", "metadata": {
            "name": "openbao", "namespace": "hooshix-secrets"}})
        wrong_bao["spec"]["containers"][0]["image"] = self.mesh_receipt["components"]["istiod"]["image"]
        # StatefulSet claim templates supply this volume only to generated Pods;
        # a standalone server dry-run must declare a harmless placeholder itself.
        wrong_bao["spec"].setdefault("volumes", []).append({"name": "data", "emptyDir": {}})
        admission_result(k, wrong_bao, wait, expected=1,
                         message=b"platform bootstrap identity/security exception rejected")
        self.checks["admission_deny_negative"] = "Passed"
        print('PLATFORM_STEP=verifier-egress', flush=True)
        verifier_egress_repair(k, {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {
            'name': 'signed-egress-preflight', 'namespace': 'istio-system'},
            'spec': copy.deepcopy(self.pods['istiod'])}, wait)
        # Same bounded target recovery after the verifier's first TUF use.
        with patch.object(commissioning, 'get', upgrade_get), patch.object(commissioning, 'kube', upgrade_kube):
            commissioning.refresh_image_verifiers(upgrade_plan['images'])
        print('PLATFORM_VERIFIER_PROCESS_REFRESH=Passed', flush=True)
        tuf_trust_boundary(k, {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {
            'name': 'signed-tuf-preflight', 'namespace': 'istio-system'},
            'spec': copy.deepcopy(self.pods['istiod'])}, self.plan['admission']['items'][1], wait)
        print("PLATFORM_STEP=mesh", flush=True)
        # Fixture Root only. The owner's existing Root/CSR/credentials never enter CI.
        key, cert = directory / "mesh-ca.key", directory / "mesh-ca.crt"
        run(["/usr/bin/openssl", "req", "-x509", "-newkey", "rsa:3072", "-sha256", "-nodes",
             "-days", "1", "-subj", "/CN=hooshix-disposable-mesh-ca", "-addext",
             "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=critical,keyCertSign,cRLSign",
             "-keyout", str(key), "-out", str(cert)])
        key.chmod(0o600)
        apply(k, {"apiVersion": "v1", "kind": "Secret", "metadata": {"name": "cacerts", "namespace": "istio-system"},
            "data": {label: base64.b64encode(path.read_bytes()).decode() for label, path in (
                ("ca-key.pem", key), ("ca-cert.pem", cert), ("root-cert.pem", cert), ("cert-chain.pem", cert))}})
        for stage in self.plan["mesh"]["stages"]:
            print("PLATFORM_MESH_COMPONENT=" + stage["component"], flush=True)
            for resource in stage["manifest"]["items"]:
                if resource["kind"] in ("Deployment", "DaemonSet"):
                    probe = {"apiVersion": "v1", "kind": "Pod", "metadata": {
                        "name": "signed-mesh-preflight", "namespace": "istio-system"},
                        "spec": resource["spec"]["template"]["spec"]}
                    admission_result(k, probe, wait)
            apply(k, stage["manifest"])
            if stage["component"] != "base":
                kind = "deployment" if stage["component"] == "istiod" else "daemonset"
                name = "istio-cni-node" if stage["component"] == "cni" else stage["component"]
                k("-n", "istio-system", "rollout", "status", kind + "/" + name,
                  "--timeout=180s", timeout=195)
        self.checks["signed_mesh_admission_and_runtime"] = "Passed"
        print("PLATFORM_STEP=signature-negatives", flush=True)
        self.signature_negatives(k, wait)

    def signature_negatives(self, k, wait):
        test = {"apiVersion": "v1", "kind": "Pod", "metadata": {
            "name": "signed-positive", "namespace": "istio-system"}, "spec": self.pods["istiod"]}
        original = self.plan["admission"]["items"][1]
        admission_result(k, test, wait)
        for name in ("wrong_signer", "missing_sbom", "wrong_provenance_revision"):
            changed = copy.deepcopy(original)
            if name == "wrong_signer":
                changed["spec"]["attestors"][0]["cosign"]["keyless"]["identities"][0]["subject"] = "https://wrong.invalid"
                message = b"platform image signature verification failed"
            elif name == "missing_sbom":
                changed["spec"]["attestations"][1]["intoto"]["type"] = "https://missing.invalid/sbom"
                message = b"platform signed SBOM verification failed"
            else:
                changed["spec"]["validations"][2]["expression"] = changed["spec"]["validations"][2]["expression"].replace(
                    self.mesh_receipt["repository_revision"], "0" * 40)
                message = b"platform import source revision rejected"
            try:
                apply(k, changed)
                admission_result(k, test, wait, expected=1, message=message)
                if name == "wrong_signer":
                    forged = copy.deepcopy(test)
                    forged["metadata"]["annotations"] = {"kyverno.io/image-verification-outcomes": json.dumps({
                        original["metadata"]["name"]: {"name": original["metadata"]["name"],
                                                       "status": "pass", "ruleType": "ImageVerify"}})}
                    admission_result(k, forged, wait, expected=1, message=message)
                self.checks["admission_" + name] = "Passed"
            finally:
                apply(k, original)
        admission_result(k, test, wait)

    def manifest(self, storage_class: str):
        result = copy.deepcopy(self.plan["openbao"])
        result["items"][-1]["spec"]["volumeClaimTemplates"][0]["spec"]["storageClassName"] = storage_class
        return result

    def network(self, k, wait):
        # Temporary, precise fixture edge only. Production stays default deny.
        print("PLATFORM_STEP=ambient-network", flush=True)
        apply(k, {"apiVersion": "networking.k8s.io/v1", "kind": "NetworkPolicy", "metadata": {
            "name": "fixture-probe-ingress", "namespace": "hooshix-secrets"}, "spec": {
            "podSelector": {"matchLabels": {"app.kubernetes.io/name": "openbao"}}, "policyTypes": ["Ingress"],
            "ingress": [{"from": [{"podSelector": {"matchLabels": {"hooshix-fixture": "probe"}}}],
                         "ports": [{"protocol": "TCP", "port": 8200}, {"protocol": "TCP", "port": 15008}]}]}})
        apply(k, {"apiVersion": "security.istio.io/v1", "kind": "AuthorizationPolicy", "metadata": {
            "name": "fixture-probe-identity", "namespace": "hooshix-secrets"}, "spec": {
            "selector": {"matchLabels": {"app.kubernetes.io/name": "openbao"}}, "action": "ALLOW",
            "rules": [{"from": [{"source": {"principals": [
                "prod.sajtech.internal/ns/hooshix-secrets/sa/commissioning-probe"]}}],
                "to": [{"operation": {"ports": ["8200"]}}]}]}})
        apply(k, {"apiVersion": "networking.k8s.io/v1", "kind": "NetworkPolicy", "metadata": {
            "name": "fixture-probe-egress", "namespace": "hooshix-secrets"}, "spec": {
            "podSelector": {"matchLabels": {"hooshix-fixture": "probe"}}, "policyTypes": ["Egress"],
            "egress": [{"to": [{"podSelector": {"matchLabels": {"app.kubernetes.io/name": "openbao"}}}],
                        "ports": [{"protocol": "TCP", "port": 8200}, {"protocol": "TCP", "port": 15008}]}]}})
        original = self.plan["admission"]["items"][2]
        pod = copy.deepcopy(self.plan["openbao"]["items"][-1]["spec"]["template"]["spec"])
        pod.pop("securityContext", None)
        pod["securityContext"] = {"runAsNonRoot": True, "runAsUser": 10001, "runAsGroup": 10001,
                                  "fsGroup": 10001, "seccompProfile": {"type": "RuntimeDefault"}}
        pod["restartPolicy"] = "Never"
        pod["containers"][0]["name"] = "probe"
        pod["containers"][0]["command"] = ["/usr/bin/bao", "status", "-format=json",
            "-address=https://openbao.hooshix-secrets.svc:8200", "-ca-cert=/openbao/tls/ca.crt"]
        # Replacing Kubernetes command does not clear the server's args.
        # bao status rejects positional arguments rather than making a request.
        pod["containers"][0]["args"] = []
        pod["containers"][0]["volumeMounts"] = [{"name": "tls", "mountPath": "/openbao/tls", "readOnly": True}]
        for field in ("startupProbe", "readinessProbe", "livenessProbe"):
            pod["containers"][0].pop(field, None)
        pod["volumes"] = [{"name": "tls", "secret": {"secretName": "openbao-server-tls", "defaultMode": 288}}]
        bounded = admission.hardening("hooshix-secrets", {"openbao": self.plan["openbao"]["items"][-1]["spec"]["template"]["spec"],
            "commissioning-probe": pod, "commissioning-wrong": pod})
        try:
            apply(k, bounded)
            for name in ("commissioning-probe", "commissioning-wrong"):
                apply(k, {"apiVersion": "v1", "kind": "ServiceAccount", "metadata": {
                    "name": name, "namespace": "hooshix-secrets"}, "automountServiceAccountToken": False})
            for label, account, plaintext in (("mtls-positive", "commissioning-probe", False),
                                              ("wrong-serviceaccount", "commissioning-wrong", False),
                                              ("plaintext-negative", "commissioning-probe", True)):
                print('PLATFORM_NETWORK_PROBE=' + label, flush=True)
                spec = copy.deepcopy(pod)
                spec["serviceAccountName"] = account
                labels = {"hooshix-fixture": "probe"}
                if plaintext:
                    labels["istio.io/dataplane-mode"] = "none"
                apply(k, {"apiVersion": "v1", "kind": "Pod", "metadata": {
                    "name": label, "namespace": "hooshix-secrets", "labels": labels}, "spec": spec})

                def terminated():
                    actual = json.loads(k("-n", "hooshix-secrets", "get", "pod", label, "-o", "json"))
                    statuses = actual.get("status", {}).get("containerStatuses", [])
                    return statuses and statuses[0].get("state", {}).get("terminated")

                state = wait(terminated, seconds=60)
                output = k("-n", "hooshix-secrets", "logs", label)
                # CLI here only runs status on a sealed, uninitialized fixture.
                # Classify fixed public errors; never print raw workload logs.
                causes = [name for name in ('lookup', 'i/o timeout', 'connection refused', 'x509',
                                           'permission denied', 'EOF', 'no such file', 'context deadline', 'too many arguments')
                          if name.lower().encode() in output.lower()]
                print('PLATFORM_NETWORK_RESULT=' + json.dumps({'probe': label, 'exit_code': state['exitCode'],
                      'error_classes': causes}), flush=True)
                if label == "mtls-positive":
                    if state["exitCode"] != 2 or json.loads(output).get("sealed") is not True:
                        raise ValueError("authorized Ambient API probe failed")
                elif state["exitCode"] != 1:
                    raise ValueError("plaintext/wrong identity was not denied")
                self.checks[label.replace("-", "_")] = "Passed"
                k("-n", "hooshix-secrets", "delete", "pod/" + label, "--timeout=30s", timeout=40)
        finally:
            apply(k, original)
