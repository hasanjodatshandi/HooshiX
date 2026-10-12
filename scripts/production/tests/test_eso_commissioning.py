"""ESO commissioning fails closed, preserves custody and uses reviewed native evidence."""
import base64
import contextlib
import copy
import hashlib
import io
import json
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_platform_commissioning_bundle as bundle
import build_eso_commissioning_bundle as operator_bundle
import install_eso as target
import openbao_scoped_auth as auth
import render_eso_commissioning as renderer
import rehearse_openbao_kubernetes as fixture


class ESOCommissioningTest(unittest.TestCase):
    def test_crd_requires_positive_status_with_finite_deadline(self):
        with patch.object(target.time, 'monotonic', side_effect=[0, 31]):
            with self.assertRaisesRegex(ValueError, 'ESO_CRD_ESTABLISHMENT_TIMEOUT'):
                target.established_crd('owned-crd', lambda *args, **kw: b'[]')
        responses = iter([b'', b'[{"type":"Established","status":"True"}]'])
        with patch.object(target.time, 'sleep'):
            target.established_crd('owned-crd', lambda *args, **kw: next(responses))
        with self.assertRaisesRegex(ValueError, 'ESO_CRD_STATUS_REJECTED'):
            target.established_crd('owned-crd', lambda *args, **kw: b'{}')

    def test_network_rule_omission_retains_explicit_deny_direction(self):
        expected = renderer.eso.network(['10.43.0.1'])[0]
        actual = copy.deepcopy(expected)
        del actual['spec']['ingress']
        self.assertTrue(target.desired_fields_match(actual, expected))
        actual['spec']['policyTypes'].remove('Ingress')
        self.assertFalse(target.desired_fields_match(actual, expected))
        actual = copy.deepcopy(expected)
        actual['spec']['ingress'] = [{}]
        self.assertFalse(target.desired_fields_match(actual, expected))

    def fixture(self):
        pods = {name: {'serviceAccountName': name, 'containers': [{'image': renderer.published_pin()['image']}]}
                for name in target.ACCOUNTS}
        deployments = [{'apiVersion': 'apps/v1', 'kind': 'Deployment', 'metadata': {
            'name': name, 'namespace': target.NAMESPACE}, 'spec': {'template': {'metadata': {
                'labels': {'app.kubernetes.io/instance': target.ACCOUNT}}, 'spec': pod}}}
            for name, pod in pods.items()]
        crds = [{'apiVersion': 'apiextensions.k8s.io/v1', 'kind': 'CustomResourceDefinition', 'metadata': {
            'name': name}} for name in ('externalsecrets.external-secrets.io',
                'secretstores.external-secrets.io', 'generatorstates.generators.external-secrets.io')]
        plan = {'schema_version': 1, 'installation_id': 'hooshix-production', 'profile': 'production-single-server',
            'pods': pods, 'mesh_images': {**{n: v['image'] for n, v in renderer.admission.mesh_publication.targets().items()},
                                        **renderer.mesh.pin()['images']}, 'image': renderer.published_pin()['image'],
            'public_ca_sha256': hashlib.sha256(b'public-ca').hexdigest(), 'crds': crds,
            'manifest': {'kind': 'List', 'items': deployments + [{'apiVersion': 'v1', 'kind': 'Secret',
                'metadata': {'namespace': target.NAMESPACE, 'name': 'hooshix-eso-webhook'}}]},
            'policies': [renderer.source_policy()], 'network': renderer.eso.network(['10.43.0.1']),
            'authorization': renderer.eso.authorization(),
            'stores': [auth.store(service, ['api']) for service in auth.SERVICES]}
        states, calls = {}, []
        def key(obj):
            return (obj['metadata'].get('namespace'), obj['kind'].lower(), obj['metadata']['name'])
        pull = {'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': 'hooshix-ghcr-read',
            'namespace': 'istio-system', 'labels': copy.deepcopy(target.host.LABELS)},
            'type': 'kubernetes.io/dockerconfigjson', 'data': {'.dockerconfigjson': 'synthetic-pull-value'}}
        states[key(pull)] = pull
        for name in ('hooshix-istio-system-bootstrap-boundary', 'hooshix-hooshix-secrets-bootstrap-boundary'):
            obj = {'kind': 'ValidatingPolicy', 'metadata': {'name': name, 'uid': 'owned-' + name, 'resourceVersion': '1'},
                   'spec': {'matchConstraints': {'namespaceSelector': copy.deepcopy(target.OLD_SELECTOR)}}}
            states[key(obj)] = obj
        def kube(*args, body=None, **options):
            calls.append((args, body, options))
            if 'get' in args:
                ns = args[1] if args[0] == '-n' else None
                tail = args[args.index('get') + 1:]
                kind, _, embedded = tail[0].partition('/')
                name = embedded or (tail[1] if len(tail) > 1 and not tail[1].startswith('-') else '')
                if kind == 'namespace':
                    value = {'metadata': {'labels': {'istio.io/dataplane-mode': 'ambient',
                        'pod-security.kubernetes.io/enforce': 'restricted',
                        'pod-security.kubernetes.io/enforce-version': 'v1.35'}}}
                elif ns == 'istio-system' and kind in ('deployment', 'daemonset'):
                    component = {'istiod': 'istiod', 'istio-cni-node': 'cni', 'ztunnel': 'ztunnel'}[name]
                    value = {'spec': {'template': {'spec': {'containers': [{'image': plan['mesh_images'][component]}]}}}}
                elif kind == 'configmap':
                    value = {'data': {'ca.crt': 'public-ca'}}
                elif kind == 'service' and name == 'kubernetes':
                    value = {'spec': {'clusterIP': '10.43.0.1'}}
                elif kind == 'endpointslices':
                    value = {'items': [{'addressType': 'IPv4', 'endpoints': [{'addresses': ['192.0.2.1']}]}]}
                elif kind == 'pods':
                    value = {'items': [{'spec': pod, 'status': {'containerStatuses': [{
                        'ready': True, 'imageID': plan['image']}]}} for pod in pods.values()]
                        if (target.NAMESPACE, 'deployment', target.ACCOUNT) in states else []}
                elif kind == 'externalsecrets':
                    value = {'items': []}
                elif kind == 'serviceaccount':
                    return b'serviceaccount/owned'
                else:
                    value = states.get((ns, 'customresourcedefinition' if kind == 'crd' else kind.lower(), name))
                if value is None:
                    return b''
                if '-o' in args and args[args.index('-o') + 1].startswith('jsonpath='):
                    if '.status.conditions' in args[args.index('-o') + 1]:
                        return b'[{"type":"Established","status":"True"}]'
                    return ('uid:' + value['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by', '')).encode()
                return json.dumps(value).encode()
            if 'create' in args and 'token' in args:
                payload = base64.urlsafe_b64encode(json.dumps({'aud': ['api']}).encode()).decode().rstrip('=')
                return ('header.' + payload + '.signature').encode()
            if body is not None and '--dry-run=server' not in args:
                states[key(body)] = copy.deepcopy(body)
            if 'patch' in args:
                changes = json.loads(args[args.index('-p') + 1])
                states[(None, 'validatingpolicy', args[2])]['spec']['matchConstraints']['namespaceSelector'] = changes[-1]['value']
            return b''
        return plan, kube, states, calls

    def test_first_install_and_owned_reentry_never_initialize_or_expose_credentials(self):
        plan, kube, states, calls = self.fixture()
        with patch.object(target, 'preserved', return_value=['unchanged']), contextlib.redirect_stdout(io.StringIO()) as output:
            result = target.execute(plan, kube=kube)
            target.execute(plan, kube=kube)
        self.assertEqual('', output.getvalue())
        self.assertEqual('Passed', result['six_secretstores'])
        self.assertEqual('Not run', result['root_revocation'])
        self.assertEqual('Not verified', result['application_secret_delivery'])
        self.assertEqual(6, len([key for key in states if key[1] == 'secretstore']))
        self.assertNotIn('synthetic-pull-value', json.dumps([args for args, _, _ in calls]))
        self.assertNotIn('--force', json.dumps([args for args, _, _ in calls]))
        for args, _, _ in calls:
            if 'patch' in args:
                changes = json.loads(args[args.index('-p') + 1])
                self.assertEqual(['/metadata/uid', '/spec'], [item['path'] for item in changes if item['op'] == 'test'])
        self.assertFalse(any('delete' in args or 'exec' in args for args, _, _ in calls))
        for store in (obj for key, obj in states.items() if key[1] == 'secretstore'):
            self.assertEqual(['api', 'hooshix-openbao'], store['spec']['provider']['vault'][
                'auth']['kubernetes']['serviceAccountRef']['audiences'])

    def test_foreign_secret_policy_crd_and_changed_ca_fail_before_mutation(self):
        for kind, name, namespace in [('secret', 'hooshix-eso-webhook', target.NAMESPACE),
                ('imagevalidatingpolicy', 'hooshix-eso-source', None),
                ('customresourcedefinition', 'secretstores.external-secrets.io', None)]:
            plan, kube, states, calls = self.fixture()
            states[(namespace, kind, name)] = {'kind': kind, 'metadata': {'name': name, 'labels': {}}}
            with patch.object(target, 'preserved', return_value=['same']), self.assertRaises(ValueError):
                target.execute(plan, kube=kube)
            self.assertFalse(any(body is not None or 'patch' in args for args, body, _ in calls))
        plan, kube, _, calls = self.fixture()
        plan['public_ca_sha256'] = '0' * 64
        with patch.object(target, 'preserved', return_value=['same']), self.assertRaises(ValueError):
            target.execute(plan, kube=kube)
        self.assertFalse(any(body is not None for _, body, _ in calls))

    def test_signed_source_requires_exact_modules_and_verified_payload(self):
        expected = renderer.predicate()
        expression = renderer.source_policy()['spec']['validations'][2]['expression']
        self.assertEqual(renderer.mesh.provenance_expression(expected), expression)
        self.assertTrue(expression.startswith('variables.provenanceVerified && '))
        self.assertIn('["module_files_sha256"]["go.sum"]', expression)
        self.assertNotIn('messageExpression', renderer.source_policy()['spec']['validations'][2])

    def test_authenticated_staging_requires_combined_native_delivery_and_fresh_tree(self):
        revision = 'a' * 40
        record = {'path': bundle.WORKFLOW, 'status': 'completed', 'conclusion': 'success',
            'repository': {'full_name': bundle.publication.REPOSITORY},
            'head_repository': {'full_name': bundle.publication.REPOSITORY}, 'event': 'pull_request', 'head_sha': revision}
        receipt = {'revision': revision, 'scope': 'Disposable Calico/Ambient initialized-store ESO commissioning',
            'upgrade_publication': renderer.mesh.pin(), 'mesh_images': self.fixture()[0]['mesh_images'],
            'observed_at': datetime.now(timezone.utc).isoformat(),
            'checks': dict.fromkeys(bundle.FOUNDATION, 'Passed'),
            'platform_checks': dict.fromkeys(bundle.CHECKS | {'image_only_mesh_upgrade_initialized_bao_preserved',
                'source_identity_istiod_positive_negative', 'source_identity_cni_positive_negative'}, 'Passed'),
            'eso_commissioning': {'image': renderer.published_pin()['image'], 'eso_installation': 'Passed',
                'six_secretstores': 'Passed', 'idempotent_reconciliation': 'Passed',
                'source_identity_positive_negative': 'Passed', 'openbao_ca_mesh_storage': 'Preserved',
                'delivery': {'image': renderer.published_pin()['image'], 'six_identity_delivery_rotation': 'Passed',
                    'cross_service_denial': 'Passed', 'owned_secret_cleanup': 'Passed', 'container_log_privacy': 'Passed'}}}
        with patch.object(bundle, 'git', return_value='same-tree'):
            bundle.check_staging(record, receipt, revision, mesh_security_upgrade=True, eso_commissioning=True)
            receipt['eso_commissioning']['delivery']['cross_service_denial'] = 'Not verified'
            with self.assertRaises(ValueError):
                bundle.check_staging(record, receipt, revision, mesh_security_upgrade=True, eso_commissioning=True)

    def test_public_bundle_binds_exact_main_native_receipt_and_every_source_byte(self):
        revision = 'a' * 40
        receipt = {'observed_at': datetime.now(timezone.utc).isoformat()}
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(bundle, 'git', side_effect=['', revision, revision + '\trefs/heads/main']), \
                patch.object(bundle, 'staged_receipt', return_value=receipt) as staging, \
                patch.object(operator_bundle.publication, 'download') as publication, \
                patch.object(renderer, 'validate_publication') as validate, \
                patch.object(renderer, 'render', return_value=self.fixture()[0]):
            output = Path(directory) / 'public-bundle'
            manifest = operator_bundle.build(output, '123', Path('/public/chart'), '/public/helm')
            self.assertEqual(revision, manifest['source_revision'])
            self.assertEqual(set(operator_bundle.SOURCES) | {'plan.json', operator_bundle.WRAPPER},
                             set(manifest['files']))
            for name, digest in manifest['files'].items():
                self.assertEqual(digest, hashlib.sha256((output / name).read_bytes()).hexdigest())
            self.assertTrue(staging.call_args.kwargs['eso_commissioning'])
            self.assertTrue(staging.call_args.kwargs['mesh_security_upgrade'])
            publication.assert_called_once()
            validate.assert_called_once()

    def test_native_ci_exercises_same_installer_and_stdout_receipt_stays_json_only(self):
        root = Path(__file__).resolve().parents[3]
        workflow = (root / '.github/workflows/platform-commissioning.yml').read_text()
        self.assertIn('--eso-chart', workflow)
        self.assertIn(renderer.pin()['chart']['sha256'], workflow)
        fixture = (root / 'scripts/production/rehearse_eso_commissioning.py').read_text()
        self.assertIn('install_eso.execute(plan, kube=adapter)', fixture)
        self.assertIn('installed=True', fixture)
        wrapper = (root / 'scripts/production/run-eso-commissioning.ps1').read_text()
        self.assertIn("sys.modules['install_eso'].main()", wrapper)
        self.assertIn("$receipt.eso_installation -cne 'Passed'", wrapper)
        self.assertNotIn('2>&1', wrapper)
        source = (root / 'scripts/production/install_eso.py').read_text()
        self.assertIn('file=sys.stderr, flush=True', source)
        self.assertNotIn('initialize(', source)
        self.assertNotIn('revoke-self', source)

    def test_all_native_fixture_steps_are_registered(self):
        import ast
        tree = ast.parse(Path(fixture.__file__).read_text())
        steps = {node.args[0].value for node in ast.walk(tree) if isinstance(node, ast.Call)
                 and isinstance(node.func, ast.Name) and node.func.id == 'step'
                 and node.args and isinstance(node.args[0], ast.Constant)}
        self.assertLessEqual(steps, fixture.STEPS)


if __name__ == '__main__':
    unittest.main()
