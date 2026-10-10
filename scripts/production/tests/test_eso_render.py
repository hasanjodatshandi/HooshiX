"""Credential-free ESO rendering keeps namespace and six-identity boundaries."""
import copy
import hashlib
import subprocess
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import render_eso_candidate as target


class ESORenderTest(unittest.TestCase):
    def setUp(self):
        self.selected = target.pin()
        self.items = []
        for suffix in ('', '-webhook', '-cert-controller'):
            container = {'image': self.selected['image'], 'securityContext': {
                'runAsNonRoot': True, 'runAsUser': 1000, 'allowPrivilegeEscalation': False,
                'readOnlyRootFilesystem': True, 'capabilities': {'drop': ['ALL']},
                'seccompProfile': {'type': 'RuntimeDefault'}},
                'resources': target.values()['resources'],
                'readinessProbe': {'httpGet': {'path': '/readyz', 'port': 8081}},
                'livenessProbe': {'httpGet': {'path': '/healthz', 'port': 8081}},
                'args': ['--namespace=' + target.NAMESPACE,
                    '--enable-cluster-store-reconciler=false',
                    '--enable-cluster-external-secret-reconciler=false',
                    '--enable-cluster-push-secret-reconciler=false',
                    '--enable-push-secret-reconciler=false']}
            self.items.append({'kind': 'Deployment', 'metadata': {
                'name': target.ACCOUNT + suffix, 'namespace': target.NAMESPACE},
                'spec': {'replicas': 1, 'template': {'spec': {
                    'serviceAccountName': target.ACCOUNT + suffix, 'containers': [container]}}}})
        self.items.extend(target.token_delegation())
        self.items.append({'kind': 'ValidatingWebhookConfiguration', 'metadata': {'name': 'probe'},
            'webhooks': [{'failurePolicy': 'Fail', 'timeoutSeconds': 5,
                          'namespaceSelector': copy.deepcopy(target.SELECTOR)}]})

    def test_valid_candidate_and_exact_six_identity_tokenrequest(self):
        target.validate(self.items, self.selected)
        role = target.token_delegation()[0]
        self.assertEqual(6, len(role['rules'][0]['resourceNames']))
        self.assertEqual(['create'], role['rules'][0]['verbs'])
        self.assertFalse(target.values()['rbac']['serviceAccountTokenCreate'])
        self.assertFalse(target.values()['installCRDs'])

    def test_reject_workload_drift(self):
        for location, key, value in [('pod', 'hostNetwork', True),
                ('pod', 'serviceAccountName', 'default'), ('container', 'image', 'eso:latest'),
                ('container', 'resources', {}), ('container', 'readinessProbe', {}),
                ('security', 'runAsUser', 0), ('security', 'allowPrivilegeEscalation', True)]:
            items = copy.deepcopy(self.items)
            pod = items[0]['spec']['template']['spec']
            destination = {'pod': pod, 'container': pod['containers'][0],
                           'security': pod['containers'][0]['securityContext']}[location]
            destination[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                target.validate(items, self.selected)

    def test_reject_boundary_rbac_and_secret_drift(self):
        for modify in (
                lambda items: items[0]['metadata'].update(namespace='other'),
                lambda items: items[3]['rules'][0].pop('resourceNames'),
                lambda items: items[3]['rules'][0].update(verbs=['*']),
                lambda items: items[4]['subjects'][0].update(name='other'),
                lambda items: items[-1]['webhooks'][0].update(failurePolicy='Ignore'),
                lambda items: items[-1]['webhooks'][0].pop('namespaceSelector'),
                lambda items: items.append({'kind': 'Secret', 'metadata': {
                    'name': 'bad', 'namespace': target.NAMESPACE}, 'data': {'token': 'bad'}}),
                lambda items: items.append({'kind': 'ClusterRole', 'metadata': {
                    'name': 'bad'}, 'rules': [{'resources': ['secrets'], 'verbs': ['get']}]})):
            items = copy.deepcopy(self.items)
            modify(items)
            with self.assertRaises(ValueError):
                target.validate(items, self.selected)

    def test_official_render_normalizes_digest_and_scopes_cert_secret_role(self):
        upstream = copy.deepcopy(self.items[:3])
        for item in upstream:
            item['spec']['template']['spec']['containers'][0]['image'] = (
                'ghcr.io/external-secrets/external-secrets:v' + self.selected['version'])
        name = target.ACCOUNT + '-cert-controller'
        upstream.extend([{'kind': 'ClusterRole', 'metadata': {'name': name}, 'rules': [
            {'apiGroups': [''], 'resources': ['secrets'], 'verbs': ['get']},
            {'apiGroups': ['apiextensions.k8s.io'], 'resources': ['customresourcedefinitions'],
             'verbs': ['get', 'list', 'watch']}]},
            {'kind': 'ClusterRoleBinding', 'metadata': {'name': name}, 'roleRef': {
                'kind': 'ClusterRole', 'name': name}, 'subjects': []}, self.items[-1]])
        selected = copy.deepcopy(self.selected)
        selected['chart']['sha256'] = hashlib.sha256(b'chart').hexdigest()
        results = [subprocess.CompletedProcess([], 0, stdout=b'v4.2.4+test'),
            subprocess.CompletedProcess([], 0, stdout=yaml.safe_dump_all(upstream).encode())]
        with patch.object(target, 'pin', return_value=selected), \
                patch.object(Path, 'is_symlink', return_value=False), \
                patch.object(Path, 'is_file', return_value=True), \
                patch.object(Path, 'stat', return_value=SimpleNamespace(st_size=5)), \
                patch.object(Path, 'read_bytes', return_value=b'chart'), \
                patch.object(target.subprocess, 'run', side_effect=results) as runner:
            rendered = target.render(Path('chart.tgz'), '/reviewed/helm')['items']
        self.assertEqual(2, runner.call_count)
        self.assertTrue(all(item['spec']['template']['spec']['containers'][0]['image'] ==
            selected['image'] for item in rendered if item['kind'] == 'Deployment'))
        local = next(item for item in rendered if item['kind'] == 'Role'
                     and item['metadata']['name'] == name)
        self.assertEqual(target.NAMESPACE, local['metadata']['namespace'])
        self.assertEqual(['secrets'], local['rules'][0]['resources'])

    def test_chart_integrity_failure_never_invokes_helm(self):
        with patch.object(Path, 'is_symlink', return_value=False), \
                patch.object(Path, 'is_file', return_value=True), \
                patch.object(Path, 'stat', return_value=SimpleNamespace(st_size=5)), \
                patch.object(Path, 'read_bytes', return_value=b'wrong'), \
                patch.object(target.subprocess, 'run') as runner, self.assertRaises(ValueError):
            target.render(Path('chart.tgz'), '/reviewed/helm')
        runner.assert_not_called()


if __name__ == '__main__':
    unittest.main()
