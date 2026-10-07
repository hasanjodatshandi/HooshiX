import copy
import json
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import render_platform_admission as target

NOW = datetime(2026, 10, 7, 12, tzinfo=timezone.utc)


def receipt(component):
    result = {"schema_version": 1, "component": component, "publication": "Passed",
        "provenance_kind": "unchanged-upstream-import", "signer": target.EXPECTED_CERTIFICATE_IDENTITY,
        "issuer": target.EXPECTED_OIDC_ISSUER, "repository_revision": "a" * 40,
        "observed_at": NOW.isoformat()}
    parts = target.mesh_publication.targets() if component == "mesh" else {"openbao": {
        "image": "ghcr.io/hasanjodatshandi/hooshix/platform-openbao-private@"
        + json.loads(target.BAO_PIN.read_bytes())["image"].split("@")[1]}}
    values = {name: {"image": value["image"], "scan": "Passed", "signature_provenance": "Passed",
        "wrong_signer": "Passed", "registry_visibility": "private", "approved_exceptions": [],
        "severity_counts": {"High": 0, "Critical": 0}, "database_built_at": NOW.isoformat()}
        for name, value in parts.items()}
    if component == "mesh":
        result["components"] = values
    else:
        result.update(values["openbao"])
    return result


class PlatformAdmissionTest(unittest.TestCase):
    def validate_receipt(self, data):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "receipt.json"
            path.write_text(json.dumps(data))
            return target.publication(path, data["component"], NOW)

    def test_exact_publication_inventory_identity_visibility_and_freshness(self):
        for component in ("mesh", "openbao"):
            good = receipt(component)
            self.assertEqual(good, self.validate_receipt(good))
            for field, value in (("schema_version", 2), ("signer", "untrusted"), ("issuer", "wrong"),
                                 ("repository_revision", "main"), ("publication", "Not verified"),
                                 ("observed_at", "2026-09-01T00:00:00+00:00"),
                                 ("observed_at", "2026-10-07T12:01:00+00:00"),
                                 ("observed_at", "2026-10-07T12:00:00")):
                bad = copy.deepcopy(good)
                bad[field] = value
                with self.subTest(component=component, field=field), self.assertRaises(ValueError):
                    self.validate_receipt(bad)
            bad = copy.deepcopy(good)
            record = bad["components"]["cni"] if component == "mesh" else bad
            for field, value in (("image", "image:latest"), ("scan", "Failed"),
                                 ("signature_provenance", "Not verified"), ("wrong_signer", "Failed"),
                                 ("severity_counts", {"High": 1, "Critical": 0}),
                                 ("severity_counts", {}), ("approved_exceptions", ["waiver"]),
                                 ("database_built_at", "2026-10-01T00:00:00+00:00")):
                old = record[field]
                record[field] = value
                with self.subTest(component=component, field=field), self.assertRaises(ValueError):
                    self.validate_receipt(bad)
                record[field] = old
        bad = receipt("openbao")
        bad["registry_visibility"] = "public"
        with self.assertRaises(ValueError):
            self.validate_receipt(bad)
        bad = receipt("mesh")
        bad["components"].pop("cni")
        with self.assertRaises(ValueError):
            self.validate_receipt(bad)

    def test_receipt_file_symlinks_and_bounds_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "receipt.json"
            path.write_bytes(b"x" * (target.BOUND + 1))
            with self.assertRaises(ValueError):
                target.publication(path, "mesh", NOW)
            link = Path(directory) / "link"
            link.symlink_to(path)
            with self.assertRaises(ValueError):
                target.publication(link, "mesh", NOW)

    def test_stable_fail_closed_policies_bind_exact_signed_revision(self):
        image = receipt("openbao")["image"]
        policy = target.image_policy("hooshix-secrets", [image], "b" * 40)
        spec = policy["spec"]
        self.assertEqual("policies.kyverno.io/v1", policy["apiVersion"])
        self.assertEqual("Fail", spec["failurePolicy"])
        self.assertEqual(["Deny"], spec["validationActions"])
        self.assertEqual({'timeoutSeconds': 30}, spec['webhookConfiguration'])
        self.assertEqual({"podControllers": {"controllers": []}}, spec["autogen"])
        self.assertEqual({"admission": {"enabled": True}, "background": {"enabled": True}}, spec["evaluation"])
        self.assertEqual([{"glob": image}], spec["matchImageReferences"])
        self.assertEqual({"mutateDigest": False, "required": True, "verifyDigest": True},
                         spec["validationConfigurations"])
        self.assertEqual({"secrets": ["hooshix-ghcr-read"]}, spec["credentials"])
        expressions = json.dumps(spec["validations"] + spec["variables"])
        self.assertIn("b" * 40, expressions)
        for required in ("verifyImageSignatures", "verifyAttestationSignatures", "extractPayload", "CycloneDX"):
            self.assertIn(required, expressions)
        self.assertNotIn("insecureIgnore", json.dumps(policy))

    def test_intoto_payload_checks_use_the_verified_statement_predicate(self):
        spec = target.image_policy('istio-system', ['image'], 'a' * 40)['spec']
        validations = spec['validations']
        self.assertIn('.predicate.buildDefinition.externalParameters.gitRevision', validations[2]['expression'])
        self.assertIn('.predicate.bomFormat', validations[4]['expression'])
        for index, name in ((2, 'provenanceVerified'), (4, 'sbomVerified')):
            self.assertTrue(validations[index]['expression'].startswith('variables.' + name + ' && '))
        self.assertEqual('variables.provenanceVerified', validations[1]['expression'])
        self.assertEqual('variables.sbomVerified', validations[3]['expression'])
        self.assertEqual(2, json.dumps(spec).count('verifyAttestationSignatures'))
        self.assertIn('extractPayload(images.containers[0], attestations.provenance)',
                      validations[2]['messageExpression'])
        self.assertNotIn('string(images.containers.map', validations[2]['messageExpression'])

    def test_network_component_token_mounts_are_explicit_bounded_and_pinned(self):
        plan = target.render(receipt('mesh'), receipt('openbao'), target.mesh.candidate())
        for stage in plan['mesh']['stages']:
            if stage['component'] not in ('cni', 'ztunnel'):
                continue
            pod = next(r['spec']['template']['spec'] for r in stage['manifest']['items']
                       if r['kind'] == 'DaemonSet')
            self.assertIs(False, pod['automountServiceAccountToken'])
            token = next(v for v in pod['volumes'] if v['name'] == 'hooshix-api-token')
            self.assertEqual({'path': 'token', 'expirationSeconds': 3600},
                             token['projected']['sources'][0]['serviceAccountToken'])
            mount = pod['containers'][0]['volumeMounts'][-1]
            self.assertEqual('/var/run/secrets/kubernetes.io/serviceaccount', mount['mountPath'])
            self.assertIs(True, mount['readOnly'])
        expression = plan['admission']['items'][0]['spec']['validations'][0]['expression']
        self.assertIn('object.spec.automountServiceAccountToken == false', expression)
        self.assertIn('object.spec.volumes.size()', expression)

    def test_security_context_tree_is_typed_cel_not_map_comparison(self):
        expression = target.scalar_tree("c.securityContext", {"runAsUser": 0,
            "capabilities": {"drop": ["ALL"], "add": ["NET_ADMIN"]}})
        self.assertIn("has(c.securityContext.runAsUser)", expression)
        self.assertIn("c.securityContext.capabilities.add.size() == 1", expression)
        self.assertNotIn(" == {", expression)
        with self.assertRaises(ValueError):
            target.scalar_tree("c", {"unsafe/key": "value"})

    def test_openbao_namespace_has_no_root_mount_or_token_exception(self):
        pod = target.bao.candidate("hooshix-openbao-local")["items"][-1]["spec"]["template"]["spec"]
        expression = target.hardening("hooshix-secrets", {"openbao": pod})["spec"]["validations"][0]["expression"]
        for required in ("!has(v.hostPath)", "RuntimeDefault", "hostNetwork", "hostPID", "hostIPC",
                         "initContainers", "ephemeralContainers", "automountServiceAccountToken == false",
                         "runAsUser == 10001", "allowPrivilegeEscalation == false"):
            self.assertIn(required, expression)
        self.assertNotIn("NET_ADMIN", expression)

    def test_shared_webhook_selector_cannot_drop_either_namespace(self):
        pod = target.bao.candidate("hooshix-openbao-local")["items"][-1]["spec"]["template"]["spec"]
        selectors = []
        for name in target.NAMESPACES:
            spec = target.hardening(name, {"openbao": pod})["spec"]
            selectors.append(spec["matchConstraints"]["namespaceSelector"])
            self.assertIn("pods/ephemeralcontainers", spec["matchConstraints"]["resourceRules"][0]["resources"])
            self.assertTrue(spec["validations"][0]["expression"].startswith(
                'object.metadata.namespace != "' + name + '" || ('))
        self.assertEqual(selectors[0], selectors[1])
        self.assertEqual(list(target.NAMESPACES), selectors[0]["matchExpressions"][0]["values"])


if __name__ == "__main__":
    unittest.main()
