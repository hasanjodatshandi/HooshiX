"""The controller bootstrap cannot turn into a database or a broad mesh exception."""
import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import render_cnpg_control_plane as renderer
import install_cnpg_control_plane as installer
import build_cnpg_control_plane_bundle as builder
from rehearse_cnpg_control_plane import failure_classes
from test_cert_manager_commissioning import upstream as certificate_upstream


class CnpgControlPlaneTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.manifest = Path(self.tmp.name) / 'cnpg.yaml'
        self.cert_manifest = Path(self.tmp.name) / 'certificates.yaml'
        self.cert_manifest.write_text(yaml.safe_dump_all(certificate_upstream()))
        self.cert_recipe = copy.deepcopy(renderer.certificates.recipe())
        self.cert_recipe['manifest']['sha256'] = hashlib.sha256(self.cert_manifest.read_bytes()).hexdigest()
        resources = ['backups', 'clusterimagecatalogs', 'clusters', 'databaseroles', 'databases',
            'failoverquorums', 'imagecatalogs', 'poolers', 'publications', 'scheduledbackups', 'subscriptions']
        objects = [{'apiVersion': 'apiextensions.k8s.io/v1', 'kind': 'CustomResourceDefinition',
            'metadata': {'name': name + '.postgresql.cnpg.io'},
            'spec': {'scope': 'Cluster' if name == 'clusterimagecatalogs' else 'Namespaced'}} for name in resources]
        objects.extend([{'apiVersion': 'rbac.authorization.k8s.io/v1', 'kind': 'ClusterRole',
            'metadata': {'name': renderer.ACCOUNT}, 'rules': [
                {'apiGroups': [''], 'resources': ['secrets'], 'verbs': ['get', 'list', 'watch', 'create']},
                {'apiGroups': [''], 'resources': ['nodes'], 'verbs': ['get']},
                {'apiGroups': ['postgresql.cnpg.io'], 'resources': ['clusterimagecatalogs', 'imagecatalogs'],
                 'verbs': ['get', 'list', 'watch']} ]},
            {'apiVersion': 'apps/v1', 'kind': 'Deployment', 'metadata': {'name': renderer.DEPLOYMENT,
                'namespace': renderer.NAMESPACE}, 'spec': {'replicas': 1, 'template': {'metadata': {
                'labels': {'app.kubernetes.io/name': 'cloudnative-pg'}}, 'spec': {
                'serviceAccountName': renderer.ACCOUNT, 'securityContext': {'runAsNonRoot': True,
                    'seccompProfile': {'type': 'RuntimeDefault'}},
                'volumes': [{'name': 'scratch-data', 'emptyDir': {}}], 'containers': [{
                    'name': 'manager', 'image': 'ghcr.io/cloudnative-pg/cloudnative-pg:1.30.1', 'command': ['/manager'],
                    'securityContext': {'allowPrivilegeEscalation': False, 'readOnlyRootFilesystem': True,
                        'capabilities': {'drop': ['ALL']}, 'seccompProfile': {'type': 'RuntimeDefault'}},
                    'volumeMounts': [{'name': 'scratch-data', 'mountPath': '/controller'}],
                    'startupProbe': {'httpGet': {'path': '/readyz', 'port': 9443, 'scheme': 'HTTPS'}},
                    'readinessProbe': {'httpGet': {'path': '/readyz', 'port': 9443, 'scheme': 'HTTPS'}},
                    'livenessProbe': {'httpGet': {'path': '/readyz', 'port': 9443, 'scheme': 'HTTPS'}}}]}}}}])
        for kind, name in zip(('MutatingWebhookConfiguration', 'ValidatingWebhookConfiguration'), renderer.CALLBACKS):
            objects.append({'apiVersion': 'admissionregistration.k8s.io/v1', 'kind': kind,
                'metadata': {'name': name}, 'webhooks': [{'name': 'cluster.cnpg.io', 'rules': [{
                    'apiGroups': ['postgresql.cnpg.io'], 'apiVersions': ['v1'], 'operations': ['CREATE'], 'resources': ['clusters']}],
                    'clientConfig': {'service': {'namespace': renderer.NAMESPACE, 'name': 'cnpg-webhook-service', 'port': 443}}}]})
        objects.append({'apiVersion': 'v1', 'kind': 'Service', 'metadata': {
            'name': 'cnpg-webhook-service', 'namespace': renderer.NAMESPACE},
            'spec': {'selector': {'app.kubernetes.io/name': 'cloudnative-pg'},
                'ports': [{'port': 443, 'targetPort': 9443}]}})
        self.manifest.write_text(yaml.safe_dump_all(objects))
        self.pins = copy.deepcopy(renderer.pins())
        self.pins['components']['operator']['manifest']['sha256'] = hashlib.sha256(self.manifest.read_bytes()).hexdigest()

    def plan(self):
        with patch.object(renderer, 'pins', return_value=self.pins), \
                patch.object(renderer.certificates, 'recipe', return_value=self.cert_recipe):
            return renderer.render(self.manifest, self.cert_manifest, ['10.43.0.1'])

    def test_exact_port_exception_and_no_data_or_credential(self):
        p = self.plan()
        self.assertEqual({'9443': {'mode': 'PERMISSIVE'}}, p['peer_authentication']['spec']['portLevelMtls'])
        self.assertEqual({'mode': 'STRICT'}, p['peer_authentication']['spec']['mtls'])
        d = next(o for o in p['objects'] if o['kind'] == 'Deployment')
        pod = d['spec']['template']['spec']
        self.assertEqual('ambient', d['spec']['template']['metadata']['labels']['istio.io/dataplane-mode'])
        self.assertFalse(pod['automountServiceAccountToken'])
        self.assertEqual('platform-data', next(v['value'] for v in pod['containers'][0]['env'] if v['name'] == 'WATCH_NAMESPACE'))
        self.assertFalse(any(o['kind'] in ('Secret', 'Cluster', 'Database', 'PersistentVolume', 'PersistentVolumeClaim') for o in p['objects']))
        self.assertEqual('Not run', p['database'])
        self.assertEqual([{'protocol': 'TCP', 'port': 9443}], p['network'][1]['spec']['ingress'][0]['ports'])
        self.assertEqual('false', p['source_policies'][-1]['spec']['validations'][0]['expression'])
        self.assertEqual(['Ingress', 'Egress'], p['network'][-1]['spec']['policyTypes'])
        for o in p['objects']:
            if o['kind'] == 'ClusterRole':
                self.assertNotIn('secrets', json.dumps(o))
        self.assertTrue(all(o['spec']['failurePolicy'] == 'Fail' for o in p['source_policies']))
        self.assertEqual(5, len(p['boundary']['spec']['validations']))

    def test_wrong_upstream_manifest_or_publication_is_rejected(self):
        self.manifest.write_text(self.manifest.read_text() + '\n')
        with self.assertRaises(ValueError): self.plan()
        with self.assertRaises(ValueError): renderer.network(['0.0.0.0/0'])

    def stateful(self):
        plan, states, calls = self.plan(), {}, []
        ns = {'metadata': {'name': renderer.NAMESPACE, 'labels': {'istio.io/dataplane-mode': 'ambient'}}}
        states[('namespace', None, renderer.NAMESPACE)] = ns
        previous = copy.deepcopy(plan['previous_boundary'])
        previous['metadata'].update(uid='previous-boundary', resourceVersion='1')
        states[('validatingpolicy', None, previous['metadata']['name'])] = previous
        def key(o): return (o['kind'].lower(), o['metadata'].get('namespace'), o['metadata']['name'])
        def kube(*args, body=None, **kwargs):
            calls.append((args, body, kwargs))
            scope = args[args.index('-n') + 1] if '-n' in args else None
            if 'get' in args:
                i = args.index('get'); kind = args[i + 1].lower()
                if scope == renderer.DATA_NAMESPACE and kind in ('pods', 'pvc', 'clusters.postgresql.cnpg.io'):
                    return b'{"items":[]}'
                if kind == 'pods':
                    d = states[('deployment', renderer.NAMESPACE, renderer.DEPLOYMENT)]
                    pod = copy.deepcopy(d['spec']['template']['spec'])
                    return json.dumps({'items': [{'metadata': d['spec']['template']['metadata'], 'spec': pod, 'status': {'containerStatuses': [{
                        'ready': True, 'imageID': pod['containers'][0]['image']}]}}]}).encode()
                value = states.get((kind, scope, args[i + 2]))
                return json.dumps(value).encode() if value else b''
            if args[0] == 'patch':
                old = states[(args[1].lower(), None, args[2])]
                operations = json.loads(args[-1])
                self.assertEqual(old['metadata']['resourceVersion'], operations[0]['value'])
                self.assertEqual(old['spec'], operations[1]['value'])
                old['spec'] = operations[2]['value']
            if args[0] in ('create', 'apply') and '--dry-run=server' not in args:
                if args[0] == 'create' and key(body) in states: raise ValueError('duplicate create')
                states[key(body)] = copy.deepcopy(body)
            return b''
        return plan, states, calls, kube

    def test_owned_reentry_and_metadata_only_preservation(self):
        p, states, calls, k = self.stateful()
        with patch.object(installer, 'protected', return_value=['same']), \
                patch.object(installer, 'addresses', return_value=['10.43.0.1']), \
                patch.object(installer, 'established_crd'):
            first = installer.execute(p, kube=k)
            self.assertEqual(first, installer.execute(p, kube=k))
        self.assertEqual('Passed', first['cnpg_control_plane'])
        self.assertEqual('Not run', first['database'])
        self.assertEqual('CLOSED', first['public_access'])
        data_reads = [args for args, _, _ in calls if '-n' in args and args[1] == renderer.DATA_NAMESPACE
                      and 'get' in args and args[3] in ('pods', 'pvc', 'clusters.postgresql.cnpg.io')]
        self.assertEqual(['pods', 'pvc', 'clusters.postgresql.cnpg.io'], [args[3] for args in data_reads])
        self.assertTrue(all('--ignore-not-found' not in args for args in data_reads))
        created = [body['kind'] for args, body, _ in calls if body and args[0] == 'create' and '--dry-run=server' not in args]
        self.assertLess(created.index('MutatingWebhookConfiguration'), created.index('Deployment'))
        self.assertLess(created.index('ValidatingWebhookConfiguration'), created.index('Deployment'))
        for args, body, _ in calls:
            self.assertNotIn('delete', args)
            self.assertNotIn('--force-conflicts', args)
            if body is not None and '--dry-run=server' not in args:
                self.assertNotIn(body['kind'], ('Secret', 'Cluster', 'PersistentVolume', 'PersistentVolumeClaim'))

    def test_existing_data_stops_before_writes(self):
        for kind in ('pods', 'pvc', 'clusters.postgresql.cnpg.io'):
            p, states, calls, k = self.stateful()
            states[('namespace', None, renderer.DATA_NAMESPACE)] = copy.deepcopy(
                next(o for o in p['objects'] if o['kind'] == 'Namespace'))
            crd = next(o for o in p['crds'] if o['metadata']['name'] == 'clusters.postgresql.cnpg.io')
            states[('customresourcedefinition', None, crd['metadata']['name'])] = crd
            def existing(*args, **kwargs):
                if args[:4] == ('-n', renderer.DATA_NAMESPACE, 'get', kind):
                    return b'{"items":[{"metadata":{"name":"existing"}}]}'
                return k(*args, **kwargs)
            with patch.object(installer, 'protected', return_value=[]), \
                    patch.object(installer, 'addresses', return_value=['10.43.0.1']), self.assertRaises(ValueError):
                installer.execute(p, kube=existing)
            self.assertFalse(any(args[0] in ('create', 'apply', 'patch') for args, _, _ in calls))

    def test_foreign_or_changed_state_and_wider_port_stop_before_writes(self):
        for mutate in ('foreign', 'port', 'boundary', 'privilege', 'network-selector', 'external-service'):
            p, states, calls, k = self.stateful()
            if mutate == 'foreign':
                d = copy.deepcopy(next(o for o in p['objects'] if o['kind'] == 'Deployment'))
                d['metadata']['labels']['app.kubernetes.io/managed-by'] = 'other'
                states[('deployment', renderer.NAMESPACE, renderer.DEPLOYMENT)] = d
            if mutate == 'port': p['peer_authentication']['spec']['portLevelMtls']['8080'] = {'mode': 'PERMISSIVE'}
            if mutate == 'boundary':
                states[('validatingpolicy', None, p['boundary']['metadata']['name'])]['spec'] = {}
            if mutate == 'privilege':
                d = copy.deepcopy(next(o for o in p['objects'] if o['kind'] == 'Deployment'))
                d['spec']['template']['spec']['containers'][0]['securityContext']['privileged'] = True
                states[('deployment', renderer.NAMESPACE, renderer.DEPLOYMENT)] = d
            if mutate == 'network-selector':
                o = copy.deepcopy(p['network'][-1])
                o['spec']['podSelector'] = {'matchLabels': {'skip': 'true'}}
                states[('networkpolicy', renderer.DATA_NAMESPACE, o['metadata']['name'])] = o
            if mutate == 'external-service':
                o = copy.deepcopy(next(o for o in p['objects'] if o['kind'] == 'Service'))
                o['spec']['externalIPs'] = ['188.240.196.151']
                states[('service', renderer.NAMESPACE, o['metadata']['name'])] = o
            with patch.object(installer, 'protected', return_value=[]), \
                    patch.object(installer, 'addresses', return_value=['10.43.0.1']), self.assertRaises(ValueError):
                installer.execute(p, kube=k)
            self.assertFalse(any(args[0] in ('create', 'apply', 'patch') for args, _, _ in calls))

    def test_bundle_requires_exact_main_native_gate_and_binds_every_file(self):
        revision = 'a' * 40
        with patch.object(builder.original, 'git', side_effect=['', revision, revision + '\trefs/heads/main']), \
                patch.object(builder.original, 'staged_receipt', return_value={'observed_at': '2026-10-14T00:00:00+00:00'}) as gate, \
                patch.object(builder, 'verify') as verify, patch.object(renderer, 'render', return_value=self.plan()):
            output = Path(self.tmp.name) / 'bundle'
            result = builder.build(output, '123', self.manifest, self.cert_manifest, Path('/public'))
            self.assertTrue(gate.call_args.kwargs['cnpg_control_plane'])
            verify.assert_called_once()
            for name, digest in result['files'].items():
                self.assertEqual(digest, hashlib.sha256((output / name).read_bytes()).hexdigest())
        with patch.object(builder.original, 'git', return_value='dirty'), self.assertRaises(ValueError):
            builder.build(Path(self.tmp.name) / 'dirty', '123', self.manifest, self.cert_manifest, Path('/public'))

    def test_partial_failure_preserves_created_state_and_log_diagnostics_are_fixed(self):
        p, states, calls, k = self.stateful()
        def fail(*args, body=None, **kwargs):
            if body and body['kind'] == 'Deployment' and args[0] == 'create':
                raise ValueError('native-create-failed')
            return k(*args, body=body, **kwargs)
        with patch.object(installer, 'protected', return_value=[]), \
                patch.object(installer, 'addresses', return_value=['10.43.0.1']), \
                patch.object(installer, 'established_crd'), self.assertRaisesRegex(ValueError, 'native-create-failed'):
            installer.execute(p, kube=fail)
        self.assertIn(('namespace', None, renderer.DATA_NAMESPACE), states)
        self.assertFalse(any('delete' in args for args, _, _ in calls))
        self.assertEqual([b'forbidden', b'cannot list', b'secrets'],
            failure_classes(b'Forbidden: cannot list secrets private-token=DO_NOT_PRINT'))

    def test_foreign_generated_ca_is_never_read_or_adopted(self):
        p, states, calls, k = self.stateful()
        def foreign(*args, **kwargs):
            if 'secret' in args and 'get' in args:
                self.assertTrue(args[-1].startswith('jsonpath={.metadata.uid}'))
                return b'foreign:Deployment,foreign,foreign-uid,true;'
            return k(*args, **kwargs)
        with patch.object(installer, 'protected', return_value=[]), \
                patch.object(installer, 'addresses', return_value=['10.43.0.1']), self.assertRaisesRegex(
                    ValueError, 'CNPG_FOREIGN_GENERATED_CA_PRESERVED'):
            installer.execute(p, kube=foreign)
        self.assertFalse(any(args[0] in ('create', 'apply', 'patch') for args, _, _ in calls))


if __name__ == '__main__':
    unittest.main()
