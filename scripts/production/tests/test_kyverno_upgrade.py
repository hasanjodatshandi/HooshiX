import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import upgrade_kyverno as upgrade


class KyvernoUpgradeTest(unittest.TestCase):
    def test_crd_fingerprint_only_normalizes_documented_defaults(self):
        spec = {'group': 'policies.kyverno.io', 'versions': [{'name': 'v1'}]}
        self.assertEqual(upgrade.spec_hash(spec), upgrade.spec_hash(spec | {
            'conversion': {'strategy': 'None'}, 'preserveUnknownFields': False}))
        self.assertNotEqual(upgrade.spec_hash(spec), upgrade.spec_hash(spec | {'preserveUnknownFields': True}))
        self.assertNotEqual(upgrade.spec_hash(spec), upgrade.spec_hash(spec | {'versions': [{'name': 'other'}]}))

    def plan(self):
        return {'version': upgrade.VERSION, 'images': {name: 'new-' + image for name, image in upgrade.OLD_IMAGES.items()},
                'values': {}, 'inventory': [{'kind': 'ConfigMap', 'name': 'kyverno', 'namespace': 'kyverno'}],
                'crds': [{'kind': 'CustomResourceDefinition', 'metadata': {'name': 'sample.kyverno.io'},
                          'spec': {'versions': [{'name': 'v1'}]}}]}

    def get(self, kind, name, namespace=None):
        obj = {'metadata': {'labels': {'app.kubernetes.io/part-of': 'kyverno',
                                      'app.kubernetes.io/instance': 'kyverno'}}}
        if kind == 'deployment':
            key = name.removeprefix('kyverno-').removesuffix('-controller')
            obj['spec'] = {'template': {'spec': {'containers': [{'image': upgrade.OLD_IMAGES[key]}]}}}
        if kind == 'customresourcedefinition':
            obj['metadata']['labels']['app.kubernetes.io/part-of'] = 'kyverno-crds'
            obj['status'] = {'storedVersions': ['v1']}
        return obj

    def execute(self, get, pods=()):
        self.kube = Mock(return_value=json.dumps({'items': list(pods)}).encode())
        self.native = Mock()
        with patch.object(upgrade, 'public_artifact') as artifacts:
            with self.assertRaises(ValueError):
                upgrade.execute(self.plan(), Path('/not-used'), Path('/not-used'), Path('/not-used'),
                                kube=self.kube, native=self.native, get=get)
        self.native.assert_not_called()
        artifacts.assert_not_called()
        self.assertFalse(any(call.args[0] in ('replace', 'create', 'delete') for call in self.kube.call_args_list))

    def test_foreign_resource_rejected_before_any_mutation(self):
        def foreign(kind, name, namespace=None):
            obj = self.get(kind, name, namespace)
            if kind == 'configmap':
                obj['metadata']['labels']['app.kubernetes.io/instance'] = 'foreign'
            return obj
        self.execute(foreign)

    def test_unreviewed_image_rejected_before_any_mutation(self):
        def wrong(kind, name, namespace=None):
            obj = self.get(kind, name, namespace)
            if kind == 'deployment':
                obj['spec']['template']['spec']['containers'][0]['image'] = 'unreviewed'
            return obj
        self.execute(wrong)

    def test_stored_version_rejection_happens_before_all_mutations(self):
        def incompatible(kind, name, namespace=None):
            obj = self.get(kind, name, namespace)
            if kind == 'customresourcedefinition':
                obj['status']['storedVersions'] = ['removed-version']
            return obj
        self.execute(incompatible)

    def test_first_adoption_refuses_application_cluster(self):
        self.execute(self.get, pods=[{'metadata': {'namespace': 'application'}}])

    def test_public_artifacts_reject_hash_symlink_and_size(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'asset'
            path.write_bytes(b'fixture')
            digest = hashlib.sha256(b'fixture').hexdigest()
            self.assertEqual(b'fixture', upgrade.public_artifact(path, digest, 20))
            for actual, bound in (('0' * 64, 20), (digest, 2)):
                with self.assertRaises(ValueError):
                    upgrade.public_artifact(path, actual, bound)
            link = Path(temp) / 'link'
            link.symlink_to(path)
            with self.assertRaises(OSError):
                upgrade.public_artifact(link, digest, 20)


if __name__ == '__main__':
    unittest.main()
