"""Barman metadata cache approval never grants Secret API or foreign writes."""
import copy
import hashlib
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import render_barman_control_plane as renderer
import install_barman_control_plane as installer
import build_barman_control_plane_bundle as builder
import test_cnpg_control_plane as cnpg_test
from rehearse_barman_control_plane import TLS_PROBE, tls_boundary


class BarmanControlPlaneTest(unittest.TestCase):
    def setUp(self):
        self.cnpg = cnpg_test.CnpgControlPlaneTest()
        self.cnpg.setUp()
        self.addCleanup(self.cnpg.doCleanups)
        self.manifest = Path(self.cnpg.tmp.name) / 'barman.yaml'
        objects = [renderer.resource('CustomResourceDefinition', 'objectstores.barmancloud.cnpg.io', None),
            renderer.resource('Deployment', renderer.DEPLOYMENT, apiVersion='apps/v1', spec={'replicas': 1,
                'selector': {'matchLabels': renderer.LABELS}, 'template': {'metadata': {'labels': renderer.LABELS},
                'spec': {'serviceAccountName': renderer.ACCOUNT, 'containers': [{'name': 'barman-cloud',
                    'image': 'ghcr.io/cloudnative-pg/plugin-barman-cloud:v0.15.1', 'volumeMounts': [
                        {'name': n, 'mountPath': '/' + n} for n in ('client', 'server')],
                    'securityContext': {'allowPrivilegeEscalation': False, 'readOnlyRootFilesystem': True,
                        'capabilities': {'drop': ['ALL']}, 'seccompProfile': {'type': 'RuntimeDefault'}}}],
                'volumes': [{'name': n, 'secret': {'secretName': 'barman-cloud-' + n + '-tls'}}
                    for n in ('client', 'server')]}}}),
            renderer.resource('Service', renderer.DEPLOYMENT, spec={'selector': renderer.LABELS,
                'ports': [{'port': 9090, 'targetPort': 9090}]})]
        for kind in ('client', 'server'):
            objects.append(renderer.resource('Certificate', 'barman-cloud-' + kind, apiVersion='cert-manager.io/v1',
                spec={'isCA': False, 'secretName': 'barman-cloud-' + kind + '-tls',
                      'issuerRef': {'name': 'selfsigned-issuer', 'kind': 'Issuer'}}))
        self.manifest.write_text(yaml.safe_dump_all(objects))
        self.pins = copy.deepcopy(self.cnpg.pins)
        self.pins['components']['barman-plugin']['manifest']['sha256'] = hashlib.sha256(self.manifest.read_bytes()).hexdigest()

    def plan(self):
        with patch.object(renderer, 'pins', return_value=self.pins), \
             patch.object(renderer.cnpg, 'pins', return_value=self.pins), \
             patch.object(renderer.cnpg.certificates, 'recipe', return_value=self.cnpg.cert_recipe):
            return renderer.render(self.manifest, self.cnpg.manifest, self.cnpg.cert_manifest, ['10.43.0.1'])

    def test_tls_probe_uses_selected_sidecar_source_recipe(self):
        plan = self.plan()
        with patch.object(renderer, 'source_predicate', wraps=renderer.source_predicate) as predicate, \
             patch('rehearse_barman_control_plane.replace_policy', side_effect=RuntimeError('stop before writes')):
            with self.assertRaises(RuntimeError):
                tls_boundary(lambda *a, **k: b'{}', None, None, plan)
        args, kwargs = predicate.call_args
        self.assertEqual('barman-sidecar', args[0])
        self.assertEqual(renderer.recipe(renderer.CANDIDATE_RECIPE), args[1])
        self.assertEqual(renderer.CANDIDATE_RECIPE, kwargs['recipe_path'])

    def test_exact_approved_cache_scope_and_tls(self):
        p = self.plan()
        roles = [o for o in p['objects'] if o['kind'] in ('Role', 'ClusterRole')]
        global_ = next(o for o in roles if o['kind'] == 'ClusterRole')
        self.assertEqual({'list', 'watch'}, {v for r in global_['rules'] for v in r['verbs']})
        self.assertNotIn('secrets', json.dumps(global_))
        plugin = [r for r in roles if r['metadata']['name'] == renderer.ACCOUNT]
        self.assertNotIn('secrets', json.dumps(plugin))
        self.assertNotIn('escalate', json.dumps(roles))
        for o in plugin:
            if o['kind'] == 'Role': self.assertEqual('platform-data', o['metadata']['namespace'])
        self.assertEqual({'mtls': {'mode': 'STRICT'}}, p['existing_strict']['spec'])
        self.assertEqual(6, len(p['boundary']['spec']['validations']))
        d = next(o for o in p['objects'] if o['kind'] == 'Deployment')
        self.assertEqual('ambient', d['spec']['template']['metadata']['labels']['istio.io/dataplane-mode'])
        self.assertFalse(d['spec']['template']['spec']['automountServiceAccountToken'])
        self.assertEqual([], [o for o in p['objects'] if o['kind'] in ('Secret', 'ObjectStore', 'Cluster', 'PersistentVolumeClaim')])
        certs = [o for o in p['objects'] if o['kind'] == 'Certificate']
        self.assertTrue(all(not o['spec']['isCA'] for o in certs))
        self.assertTrue(all(o['spec']['issuerRef']['name'] == 'hooshix-database-selfsigned' for o in certs))
        self.assertIn('prod.sajtech.internal/ns/cnpg-system/sa/cnpg-manager', json.dumps(p['authorization']))
        self.assertNotIn('0.0.0.0/0', json.dumps(p['network']))
        self.assertIn('install_postgresql_local_volume.py', builder.SOURCES)
        compile(TLS_PROBE, '<tls-probe>', 'exec')

    def stateful(self):
        p, states, calls = self.plan(), {}, []
        states[('namespace', None, renderer.NAMESPACE)] = {'metadata': {'labels': {'istio.io/dataplane-mode': 'ambient'}}}
        states[('peerauthentication', renderer.NAMESPACE, p['existing_strict']['metadata']['name'])] = p['existing_strict']
        old = copy.deepcopy(p['previous_boundary'])
        old['metadata'].update(uid='existing', resourceVersion='1')
        states[('validatingpolicy', None, old['metadata']['name'])] = old
        def kube(*args, body=None, **kwargs):
            calls.append((args, body, kwargs))
            ns = args[args.index('-n') + 1] if '-n' in args else None
            if args[:2] == ('auth', 'can-i'):
                return b'no' if kwargs.get('expected') == 1 else b'yes'
            if 'get' in args:
                i = args.index('get'); kind = args[i+1].lower()
                if ns == 'platform-data' and kind in ('pods', 'pvc', 'clusters.postgresql.cnpg.io'): return b'{"items":[]}'
                if kind == 'pods':
                    d = states[('deployment', renderer.NAMESPACE, renderer.DEPLOYMENT)]
                    return json.dumps({'items': [{'metadata': d['spec']['template']['metadata'],
                        'spec': d['spec']['template']['spec'], 'status': {'containerStatuses': [{
                            'ready': True, 'imageID': p['images']['barman-plugin']}]}}]}).encode()
                obj = states.get((kind, ns, args[i+2]))
                return json.dumps(obj).encode() if obj else b''
            if args[0] == 'patch':
                o = states[(args[1].lower(), None, args[2])]
                changes = body if body is not None else json.loads(args[-1])
                self.assertEqual(o['spec'], changes[1]['value'])
                o['spec'] = changes[2]['value']
            if args[0] in ('create', 'apply') and body:
                m = body['metadata']; key = body['kind'].lower(), m.get('namespace'), m['name']
                self.assertNotIn(key, states)
                states[key] = copy.deepcopy(body)
            return b''
        return p, states, calls, kube

    def test_idempotent_and_no_data_mutations(self):
        p, states, calls, k = self.stateful()
        with patch.object(installer, 'protected', return_value=['same']), \
                patch.object(installer, 'addresses', return_value=['10.43.0.1']), patch.object(installer, 'established_crd'):
            result = installer.execute(p, kube=k)
            self.assertEqual(result, installer.execute(p, kube=k))
        self.assertEqual('Passed', result['barman_control_plane'])
        self.assertEqual('Not run', result['provider_backup'])
        for args, body, _ in calls:
            self.assertNotEqual('delete', args[0])
            self.assertNotIn('--force-conflicts', args)
            if isinstance(body, list):
                self.assertEqual(('patch', 'ValidatingPolicy'), args[:2])
                self.assertEqual(['test', 'test', 'replace'], [op['op'] for op in body])
            elif body:
                self.assertNotIn(body['kind'], ('Secret', 'Cluster', 'ObjectStore', 'PersistentVolume', 'PersistentVolumeClaim'))

    def test_foreign_drift_or_secret_key_rejected_before_first_write(self):
        for case in ('foreign', 'extra-role', 'public-service', 'boundary', 'unlabeled-secret', 'data'):
            p, states, calls, k = self.stateful()
            role = copy.deepcopy(next(o for o in p['objects'] if o['kind'] == 'ClusterRole'))
            if case == 'foreign': role['metadata']['labels'] = {}
            if case == 'extra-role': role['rules'].append({'apiGroups': [''], 'resources': ['secrets'], 'verbs': ['get']})
            if case in ('foreign', 'extra-role'): states[('clusterrole', None, role['metadata']['name'])] = role
            if case == 'public-service':
                service = copy.deepcopy(next(o for o in p['objects'] if o['kind'] == 'Service'))
                service['spec']['externalIPs'] = ['192.0.2.1']
                states[('service', renderer.NAMESPACE, renderer.DEPLOYMENT)] = service
            if case == 'boundary': states[('validatingpolicy', None, p['boundary']['metadata']['name'])]['spec'] = {}
            def call(*args, **kw):
                if case == 'unlabeled-secret' and 'secret' in args: return b'foreign-uid:'
                if case == 'data' and args[:4] == ('-n', 'platform-data', 'get', 'pvc'): return b'{"items":[{}]}'
                return k(*args, **kw)
            with patch.object(installer, 'protected', return_value=[]), \
                    patch.object(installer, 'addresses', return_value=['10.43.0.1']), self.assertRaises(ValueError):
                installer.execute(p, kube=call)
            self.assertFalse(any(args[0] in ('create', 'apply', 'patch') for args, _, _ in calls))

    def test_approval_and_manifest_are_required(self):
        p, _, calls, k = self.stateful()
        p['metadata_cache_trust'] = 'unapproved'
        with self.assertRaises(ValueError): installer.execute(p, kube=k)
        self.assertEqual([], calls)
        self.manifest.write_text(self.manifest.read_text() + '\n')
        with self.assertRaises(ValueError): self.plan()

    def test_bundle_authenticates_native_scope_and_every_source(self):
        revision = 'a' * 40
        with patch.object(builder.original, 'git', side_effect=['', revision, revision + '\trefs/heads/main']), \
                patch.object(builder.original, 'staged_receipt', return_value={'observed_at': '2026-10-15T00:00:00+00:00'}) as gate, \
                patch.object(builder, 'verify') as verify, patch.object(renderer, 'render', return_value=self.plan()):
            output = Path(self.cnpg.tmp.name) / 'bundle'
            result = builder.build(output, '123', self.manifest, self.cnpg.manifest, self.cnpg.cert_manifest, Path('/public'))
            self.assertTrue(gate.call_args.kwargs['barman_control_plane'])
            self.assertTrue(gate.call_args.kwargs['cnpg_control_plane'])
            verify.assert_called_once()
            self.assertEqual(set(builder.SOURCES) | {'plan.json', builder.WRAPPER}, set(result['files']))
            for name, digest in result['files'].items():
                self.assertEqual(digest, hashlib.sha256((output / name).read_bytes()).hexdigest())
        with patch.object(builder.original, 'git', return_value='dirty'), self.assertRaises(ValueError):
            builder.build(Path(self.cnpg.tmp.name) / 'bad', '123', self.manifest, self.cnpg.manifest,
                          self.cnpg.cert_manifest, Path('/public'))


if __name__ == '__main__': unittest.main()
