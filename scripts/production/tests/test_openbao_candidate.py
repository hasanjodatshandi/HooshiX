import contextlib
import io
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import render_openbao_candidate as renderer


class OpenBaoCandidateTest(unittest.TestCase):
    def setUp(self):
        self.manifest = renderer.candidate("reviewed-storage")
        self.items = {item["kind"]: item for item in self.manifest["items"]}
        self.workload = self.items["StatefulSet"]["spec"]
        self.pod = self.workload["template"]["spec"]
        self.container = self.pod["containers"][0]

    def test_render_is_deterministic_and_copies_only_public_pins_and_configuration(self):
        self.assertEqual(self.manifest, renderer.candidate("reviewed-storage"))
        config = json.loads((renderer.SECRETS / "openbao-server.json").read_text())
        self.assertEqual(config, json.loads(self.items["ConfigMap"]["data"]["server.json"]))
        pin = json.loads((renderer.SECRETS / "openbao-image.json").read_text())
        self.assertEqual(pin["image"], self.container["image"])
        self.assertEqual(8, len(self.manifest["items"]))
        self.assertNotIn("Secret", self.items)
        self.assertNotIn("ClusterRoleBinding", self.items)
        self.assertNotIn("RoleBinding", self.items)
        self.assertNotIn("HorizontalPodAutoscaler", self.items)
        self.assertNotIn("PodDisruptionBudget", self.items)

    def test_storage_class_is_explicit_and_rejects_paths_flags_and_invalid_names(self):
        for invalid in ("", "../private", "--production", "x y", "UPPER", "a..b", "a" * 64):
            with self.subTest(value=invalid), self.assertRaises(ValueError):
                renderer.candidate(invalid)
        self.assertEqual("local.storage", renderer.candidate("local.storage")["items"][-1]["spec"]
                         ["volumeClaimTemplates"][0]["spec"]["storageClassName"])

    def test_one_raft_identity_and_retained_pvc_with_explicit_storage_request(self):
        self.assertEqual(1, self.workload["replicas"])
        self.assertEqual("openbao", self.workload["serviceName"])
        self.assertEqual({"type": "OnDelete"}, self.workload["updateStrategy"])
        self.assertEqual({"whenDeleted": "Retain", "whenScaled": "Retain"},
                         self.workload["persistentVolumeClaimRetentionPolicy"])
        claim = self.workload["volumeClaimTemplates"][0]
        self.assertEqual("Prune=confirm", claim["metadata"]["annotations"]["argocd.argoproj.io/sync-options"])
        self.assertEqual("8Gi", claim["spec"]["resources"]["requests"]["storage"])
        self.assertEqual(["ReadWriteOnce"], claim["spec"]["accessModes"])

    def test_nonroot_readonly_capability_free_finite_workload(self):
        security = self.pod["securityContext"]
        self.assertTrue(security["runAsNonRoot"])
        self.assertEqual(10001, security["runAsUser"])
        self.assertEqual(10001, security["runAsGroup"])
        self.assertEqual(10001, security["fsGroup"])
        self.assertEqual({"type": "RuntimeDefault"}, security["seccompProfile"])
        self.assertFalse(self.container["securityContext"]["allowPrivilegeEscalation"])
        self.assertTrue(self.container["securityContext"]["readOnlyRootFilesystem"])
        self.assertEqual({"drop": ["ALL"]}, self.container["securityContext"]["capabilities"])
        for kind in ("requests", "limits"):
            self.assertEqual({"cpu", "memory", "ephemeral-storage"}, set(self.container["resources"][kind]))
        self.assertEqual(30, self.pod["terminationGracePeriodSeconds"])
        self.assertEqual(["/usr/bin/bao"], self.container["command"])
        self.assertEqual(["server", "-config=/openbao/config/server.json"], self.container["args"])
        for forbidden in ("hostNetwork", "hostPID", "hostIPC", "initContainers"):
            self.assertNotIn(forbidden, self.pod)
        self.assertTrue(all("hostPath" not in volume for volume in self.pod["volumes"]))

    def test_tls_secret_reference_only_no_token_or_tls_skip_or_retries(self):
        volumes = {volume["name"]: volume for volume in self.pod["volumes"]}
        tls = volumes["tls"]["secret"]
        self.assertEqual("openbao-server-tls", tls["secretName"])
        self.assertEqual(0o440, tls["defaultMode"])
        self.assertEqual({"tls.crt", "tls.key", "ca.crt"}, {item["key"] for item in tls["items"]})
        mounts = {mount["name"]: mount for mount in self.container["volumeMounts"]}
        self.assertTrue(mounts["tls"]["readOnly"])
        self.assertTrue(mounts["config"]["readOnly"])
        self.assertEqual("16Mi", volumes["tmp"]["emptyDir"]["sizeLimit"])
        env = {item["name"]: item["value"] for item in self.container["env"]}
        self.assertEqual("https://127.0.0.1:8200", env["BAO_ADDR"])
        self.assertEqual("/openbao/tls/ca.crt", env["BAO_CACERT"])
        self.assertEqual("3s", env["BAO_CLIENT_TIMEOUT"])
        self.assertEqual("0", env["BAO_MAX_RETRIES"])
        self.assertNotIn("BAO_TOKEN", env)
        self.assertNotIn("BAO_SKIP_VERIFY", env)

    def test_service_is_private_and_network_mesh_and_token_access_fail_closed(self):
        self.assertEqual("ClusterIP", self.items["Service"]["spec"]["type"])
        self.assertTrue(self.items["Service"]["spec"]["publishNotReadyAddresses"])
        self.assertFalse(self.items["ServiceAccount"]["automountServiceAccountToken"])
        self.assertFalse(self.pod["automountServiceAccountToken"])
        network = self.items["NetworkPolicy"]["spec"]
        self.assertEqual({}, network["podSelector"])
        self.assertEqual(["Ingress", "Egress"], network["policyTypes"])
        self.assertEqual([], network["ingress"])
        self.assertEqual([{ "to": [{
            "namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "kube-system"}},
            "podSelector": {"matchLabels": {"k8s-app": "kube-dns"}}}],
            "ports": [{"protocol": "UDP", "port": 53}, {"protocol": "TCP", "port": 53}]}], network["egress"])
        self.assertEqual({"mtls": {"mode": "STRICT"}}, self.items["PeerAuthentication"]["spec"])
        self.assertEqual({}, self.items["AuthorizationPolicy"]["spec"])
        self.assertEqual("ambient", self.items["Namespace"]["metadata"]["labels"]["istio.io/dataplane-mode"])

    def test_native_probes_keep_tls_sealed_safety_without_shell_or_tcp_bypass(self):
        self.assertEqual(["/usr/bin/bao", "read", "-field=sealed", "sys/seal-status"], renderer.ALIVE)
        self.assertEqual(["/usr/bin/bao", "status", "-format=json"], renderer.STATUS)
        for name, command in (("startupProbe", renderer.ALIVE), ("livenessProbe", renderer.ALIVE),
                              ("readinessProbe", renderer.STATUS)):
            probe = self.container[name]
            self.assertEqual(command, probe["exec"]["command"])
            self.assertEqual(5, probe["timeoutSeconds"])
            self.assertNotIn("httpGet", probe)
            self.assertNotIn("tcpSocket", probe)
            self.assertNotIn("/bin/sh", probe["exec"]["command"])
        self.assertNotIn("sealedcode", json.dumps(self.manifest))

    def test_cli_requires_candidate_mode_and_never_prints_bad_config(self):
        output = io.StringIO()
        with patch.object(sys, "argv", ["render", "--candidate", "--storage-class", "private-value"]), \
                patch.object(renderer, "candidate", side_effect=ValueError("secret exception")), \
                contextlib.redirect_stderr(output), self.assertRaises(SystemExit) as failure:
            renderer.main()
        self.assertEqual(1, failure.exception.code)
        self.assertNotIn("secret exception", output.getvalue())
        with patch.object(sys, "argv", ["render", "--storage-class", "reviewed"]), \
                contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as failure:
            renderer.main()
        self.assertEqual(2, failure.exception.code)


if __name__ == "__main__":
    unittest.main()
