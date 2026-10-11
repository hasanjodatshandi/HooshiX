"""Credential-free ESO rendering keeps namespace and six-identity boundaries."""
import copy
import hashlib
import json
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
                'spec': {'replicas': 1, 'template': {'metadata': {'labels': {
                    'istio.io/dataplane-mode': 'none' if suffix == '-webhook' else 'ambient'}}, 'spec': {
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
                ('pod', 'serviceAccountName', 'default'), ('pod', 'serviceAccountName', 'foreign'),
                ('container', 'image', 'eso:latest'),
                ('container', 'resources', {}), ('container', 'readinessProbe', {}),
                ('security', 'runAsUser', 0), ('security', 'allowPrivilegeEscalation', True)]:
            items = copy.deepcopy(self.items)
            pod = items[0]['spec']['template']['spec']
            destination = {'pod': pod, 'container': pod['containers'][0],
                           'security': pod['containers'][0]['securityContext']}[location]
            destination[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                target.validate(items, self.selected)

    def test_only_webhook_has_explicit_ambient_opt_out(self):
        for index in range(3):
            for mode in (None, 'sidecar', 'ambient' if index == 1 else 'none'):
                items = copy.deepcopy(self.items)
                labels = items[index]['spec']['template']['metadata']['labels']
                if mode is None:
                    labels.pop('istio.io/dataplane-mode')
                else:
                    labels['istio.io/dataplane-mode'] = mode
                with self.subTest(index=index, mode=mode), self.assertRaises(ValueError):
                    target.validate(items, self.selected)

    def test_network_keeps_webhook_api_only_and_openbao_controller_only(self):
        policies = {item['metadata']['name']: item for item in target.network({'10.43.0.1', '188.240.196.151'})}
        webhook = policies['hooshix-eso-webhook-api']['spec']
        self.assertEqual({'matchLabels': {'app.kubernetes.io/instance': target.ACCOUNT,
            'app.kubernetes.io/name': 'external-secrets-webhook'}}, webhook['podSelector'])
        self.assertEqual([{'from': [{'ipBlock': {'cidr': '10.43.0.1/32'}},
            {'ipBlock': {'cidr': '188.240.196.151/32'}}], 'ports': [
                {'protocol': 'TCP', 'port': 10250}]}], webhook['ingress'])
        common = policies['hooshix-eso-control-plane']['spec']
        self.assertEqual([], common['ingress'])
        self.assertNotIn('openbao', json.dumps(common))
        self.assertNotIn('10250', json.dumps(common['egress']))
        controller = policies['hooshix-eso-openbao']['spec']
        self.assertEqual('external-secrets', controller['podSelector']['matchLabels']['app.kubernetes.io/name'])
        self.assertEqual([8200, 15008], [p['port'] for p in controller['egress'][0]['ports']])
        ingress = policies['hooshix-openbao-eso']['spec']['ingress'][0]
        self.assertEqual(controller['podSelector'], ingress['from'][0]['podSelector'])
        self.assertEqual({'matchLabels': {'kubernetes.io/metadata.name': target.NAMESPACE}},
                         ingress['from'][0]['namespaceSelector'])
        self.assertEqual(['prod.sajtech.internal/ns/platform-apps/sa/hooshix-eso'],
            target.authorization()['spec']['rules'][0]['from'][0]['source']['principals'])
        self.assertEqual(['8200'], target.authorization()['spec']['rules'][0]['to'][0]['operation']['ports'])

    def test_network_rejects_unbounded_or_non_unicast_api_input(self):
        for addresses in ([], ['0.0.0.0'], ['0.0.0.1'], ['255.255.255.255'], [123], [True],
                          '10.43.0.1', ['::1'], ['127.0.0.1'], ['169.254.169.254'],
                          ['224.0.0.1'], ['10.43.0.0/16'], ['kubernetes.default.svc'],
                          ['10.0.0.' + str(n) for n in range(1, 18)]):
            with self.subTest(addresses=addresses), self.assertRaises(ValueError):
                target.network(addresses)

    def test_cli_includes_same_network_and_identity_boundary_without_cluster_writes(self):
        import io
        output = io.StringIO()
        with patch.object(sys, 'argv', ['render', '--chart', '/fixture/chart',
                '--published-candidate', '--api-address', '10.43.0.1',
                '--api-address', '188.240.196.151']), \
                patch.object(target.shutil, 'which', return_value='/fixture/helm'), \
                patch.object(target, 'render', return_value={'kind': 'List', 'items': []}) as render, \
                patch.object(sys, 'stdout', output):
            self.assertEqual(0, target.main())
        render.assert_called_once_with(Path('/fixture/chart'), '/fixture/helm', published=True)
        self.assertEqual(target.network(['10.43.0.1', '188.240.196.151']) + [target.authorization()],
                         json.loads(output.getvalue())['items'])

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

    def test_cert_endpoint_cache_read_only_does_not_allow_secret_or_endpoint_mutation(self):
        rule = {'apiGroups': ['discovery.k8s.io'], 'resources': ['endpointslices'],
                'verbs': ['get', 'list', 'watch']}
        items = copy.deepcopy(self.items) + [{'kind': 'ClusterRole', 'metadata': {
            'name': target.ACCOUNT + '-cert-controller'}, 'rules': [rule]}]
        target.validate(items, self.selected)
        for verbs in (['get', 'list', 'watch', 'update'], ['*'], ['patch']):
            rule['verbs'] = verbs
            with self.assertRaises(ValueError):
                target.validate(items, self.selected)

    def test_official_render_normalizes_digest_and_scopes_cert_secret_role(self):
        self.check_official_render(published=False)

    def test_published_render_uses_exact_security_candidate_for_all_three_workloads(self):
        self.check_official_render(published=True)

    def check_official_render(self, *, published):
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
        if published:
            selected['image'] = target.published_pin()['image']
        selected['chart']['sha256'] = hashlib.sha256(b'chart').hexdigest()
        results = [subprocess.CompletedProcess([], 0, stdout=b'v4.2.4+test'),
            subprocess.CompletedProcess([], 0, stdout=yaml.safe_dump_all(upstream).encode())]
        with patch.object(target, 'published_pin' if published else 'pin', return_value=selected), \
                patch.object(Path, 'is_symlink', return_value=False), \
                patch.object(Path, 'is_file', return_value=True), \
                patch.object(Path, 'stat', return_value=SimpleNamespace(st_size=5)), \
                patch.object(Path, 'read_bytes', return_value=b'chart'), \
                patch.object(target.subprocess, 'run', side_effect=results) as runner:
            rendered = target.render(Path('chart.tgz'), '/reviewed/helm', published=published)['items']
        self.assertEqual(2, runner.call_count)
        self.assertTrue(all(item['spec']['template']['spec']['containers'][0]['image'] ==
            selected['image'] for item in rendered if item['kind'] == 'Deployment'))
        for item in rendered:
            if item['kind'] == 'Deployment':
                self.assertEqual('none' if item['metadata']['name'].endswith('-webhook') else 'ambient',
                                 item['spec']['template']['metadata']['labels']['istio.io/dataplane-mode'])
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
