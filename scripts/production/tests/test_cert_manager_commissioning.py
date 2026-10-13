"""Internal cert-manager candidates cannot widen certificate or mesh authority."""
import copy
import hashlib
import json
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import render_cert_manager_candidate as target
import install_cert_manager as installer
import build_cert_manager_commissioning_bundle as operator_bundle
import build_platform_commissioning_bundle as bundle
import render_eso_commissioning as eso


def upstream():
    # Small structural input only; real official bytes are separately hash-bound/native-tested.
    items = [{'apiVersion': 'apiextensions.k8s.io/v1', 'kind': 'CustomResourceDefinition',
        'metadata': {'name': name}, 'spec': {'scope': 'Namespaced'}} for name in sorted(target.CRDS)]
    for component, account in target.ACCOUNTS.items():
        labels = {'app.kubernetes.io/instance': 'cert-manager',
                  'app.kubernetes.io/name': 'cert-manager' if component == 'controller' else component}
        container = {'name': 'cert-manager-' + component,
            'image': 'quay.io/jetstack/cert-manager-' + component + ':v1.20.3',
            'securityContext': {'allowPrivilegeEscalation': False,
                'capabilities': {'drop': ['ALL']}, 'readOnlyRootFilesystem': True},
            'env': [{'name': 'POD_NAMESPACE', 'valueFrom': {'fieldRef': {'fieldPath': 'metadata.namespace'}}}],
            'livenessProbe': {'httpGet': {'path': '/livez', 'port': 6080}},
            'readinessProbe': {'httpGet': {'path': '/healthz', 'port': 6080}}}
        items.append({'apiVersion': 'apps/v1', 'kind': 'Deployment', 'metadata': {'name': account},
            'spec': {'template': {'metadata': {'labels': labels}, 'spec': {
                'serviceAccountName': account, 'containers': [container],
                'securityContext': {'runAsNonRoot': True, 'seccompProfile': {'type': 'RuntimeDefault'}}}}}})
    for kind in ('MutatingWebhookConfiguration', 'ValidatingWebhookConfiguration'):
        items.append({'apiVersion': 'admissionregistration.k8s.io/v1', 'kind': kind,
            'metadata': {'name': 'cert-manager-webhook', 'annotations': {}}, 'webhooks': [{'name': 'webhook.cert-manager.io',
                'clientConfig': {'service': {'name': 'cert-manager-webhook', 'path': '/validate'}},
                'rules': [{'apiGroups': ['cert-manager.io'], 'resources': ['certificaterequests']}]}]})
    return items


class CertManagerCommissioningTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / 'manifest.yaml'
        self.path.write_text(yaml.safe_dump_all(upstream()))
        self.source = copy.deepcopy(target.recipe())
        self.source['manifest']['sha256'] = hashlib.sha256(self.path.read_bytes()).hexdigest()

    def plan(self):
        with patch.object(target, 'recipe', return_value=self.source):
            return target.render(self.path, ['10.43.0.1', '188.240.196.151'])

    def test_exact_internal_namespace_and_three_workloads(self):
        plan = self.plan()
        self.assertEqual(set(target.ACCOUNTS.values()), set(plan['pods']))
        self.assertEqual('ambient', plan['namespace']['metadata']['labels']['istio.io/dataplane-mode'])
        self.assertEqual('restricted', plan['namespace']['metadata']['labels']['pod-security.kubernetes.io/enforce'])
        self.assertEqual(target.CRDS, {r['metadata']['name'] for r in plan['crds']})
        self.assertEqual('Not verified', plan['certificate_issuance'])
        self.assertEqual('Not verified', plan['deployment'])
        self.assertEqual([target.serving_secret()], [o for o in plan['manifest']['items'] if o['kind'] == 'Secret'])
        self.assertEqual({'mtls': {'mode': 'STRICT'}}, plan['strict']['spec'])
        with patch.object(target, 'recipe', return_value=self.source): target.validate(plan)

    def test_critical_candidate_mutations_are_rejected(self):
        original = self.plan()
        for mutate in (
            lambda p: p['manifest']['items'].append(target.resource('Secret', 'key', data={'key': 'not-private'})),
            lambda p: p['manifest']['items'][1]['spec'].update(replicas=2),
            lambda p: p['manifest']['items'][1]['spec']['template']['metadata']['labels'].update({'istio.io/dataplane-mode': 'none'}),
            lambda p: p['manifest']['items'][1]['spec']['template']['spec'].update(serviceAccountName='default'),
            lambda p: p['manifest']['items'][1]['spec']['template']['spec']['containers'][0].update(image='unsigned:tag'),
            lambda p: p['manifest']['items'][1]['spec']['template']['spec']['containers'][0].update(args=['--controllers=*']),
            lambda p: p['manifest']['items'][1]['spec']['template']['spec']['containers'][0].update(command=['other-binary']),
            lambda p: p['manifest']['items'][1]['spec']['template']['spec']['containers'][0].update(envFrom=[{'secretRef': {'name': 'other'}}]),
            lambda p: p['manifest']['items'][1]['spec']['template']['spec']['containers'][0]['securityContext'].update(privileged=True),
            lambda p: p['strict']['spec']['mtls'].update(mode='PERMISSIVE'),
            lambda p: p['policies'][0]['spec'].update(failurePolicy='Ignore'),
            lambda p: p['manifest']['items'].append(target.resource('ClusterRole', 'extra', namespaced=False,
                rules=[{'apiGroups': [''], 'resources': ['secrets'], 'verbs': ['get']}]))):
            plan = copy.deepcopy(original)
            mutate(plan)
            with patch.object(target, 'recipe', return_value=self.source), self.assertRaises(ValueError): target.validate(plan)

    def test_bad_manifest_and_wide_api_network_rejected(self):
        self.path.write_text(self.path.read_text() + '\n')
        with patch.object(target, 'recipe', return_value=self.source), self.assertRaises(ValueError): self.plan()
        for addresses in ([], ['0.0.0.0'], ['127.0.0.1'], ['::1'], ['10.0.0.0/8'], ['169.254.1.1']):
            with self.assertRaises(ValueError): target.network(addresses)
        network = target.network(['10.43.0.1'])
        self.assertEqual([{'ipBlock': {'cidr': '10.43.0.1/32'}}], network[1]['spec']['ingress'][0]['from'])
        self.assertEqual([{'protocol': 'TCP', 'port': 10250}], network[1]['spec']['ingress'][0]['ports'])
        self.assertNotIn('hooshix-secrets', json.dumps(network))

    def test_scoped_permissions_and_source_admission(self):
        plan = self.plan()
        for obj in target.permissions():
            if obj['kind'] == 'ClusterRole':
                self.assertNotIn('secrets', json.dumps(obj))
                for rule in obj['rules']:
                    self.assertNotIn('*', rule['verbs'])
                    if set(rule['verbs']) & {'update', 'patch'}:
                        self.assertEqual(['cert-manager-webhook'], rule['resourceNames'])
        self.assertEqual(installer.SELECTOR, target.BOUNDARY_SELECTOR)
        for p in plan['policies']:
            self.assertEqual('Fail', p['spec']['failurePolicy'])
            self.assertEqual(['Deny'], p['spec']['validationActions'])
            if p['kind'] == 'ImageValidatingPolicy':
                self.assertNotIn('credentials', p['spec'])
                self.assertEqual(1, len(p['spec']['matchImageReferences']))
        issuers = next(p for p in plan['policies'] if p['metadata']['name'].endswith('-internal-issuers'))
        self.assertIn('!has(object.spec.acme)', issuers['spec']['validations'][0]['expression'])

    def test_foreign_state_preflight_prevents_all_mutations(self):
        plan = self.plan()
        writes = []
        def kube(*args, **kw):
            if args[0] in ('create', 'apply', 'patch'): writes.append(args)
            return json.dumps({'metadata': {'labels': {'app.kubernetes.io/managed-by': 'foreign'}}}).encode()
        with patch.object(installer, 'protected', return_value=[]), \
                patch.object(installer, 'addresses', return_value=['10.43.0.1']), self.assertRaisesRegex(
                    ValueError, 'CERT_MANAGER_OWNERSHIP_OR_DRIFT_CONFLICT'):
            installer.execute(plan, kube=kube)
        self.assertEqual([], writes)

    def stateful(self):
        plan, states, calls = self.plan(), {}, []
        for name in installer.BOUNDARIES:
            states[('validatingpolicy', name)] = {'metadata': {'name': name, 'uid': name},
                'spec': {'matchConstraints': {'namespaceSelector': copy.deepcopy(installer.OLD_SELECTOR)}}}
        def key(obj): return (obj['kind'].lower(), obj['metadata']['name'])
        def kube(*args, body=None, **kw):
            calls.append((args, body, kw))
            if 'get' in args:
                i = args.index('get')
                kind = args[i + 1].lower()
                if kind == 'pods':
                    pods = []
                    for value in states.values():
                        if value.get('kind') == 'Deployment':
                            pod = value['spec']['template']['spec']
                            pods.append({'spec': pod, 'status': {'containerStatuses': [{'ready': True,
                                'imageID': pod['containers'][0]['image']}]}})
                    return json.dumps({'items': pods}).encode()
                value = states.get((kind, args[i + 2]))
                if kind == 'secret' and value:
                    self.assertIn('jsonpath={.metadata.uid}', ' '.join(args))
                    return ('owned:' + installer.MANAGER).encode()
                return json.dumps(value).encode() if value else b''
            if args[0] == 'patch':
                value = states[(args[1].lower(), args[2])]
                patch_ = json.loads(args[-1])
                self.assertEqual(value['metadata']['uid'], patch_[0]['value'])
                self.assertEqual(value['spec'], patch_[1]['value'])
                value['spec']['matchConstraints']['namespaceSelector'] = patch_[-1]['value']
            if args[0] in ('apply', 'create') and '--dry-run=server' not in args:
                if args[0] == 'create' and key(body) in states:
                    raise ValueError('already exists')
                states[key(body)] = copy.deepcopy(body)
            return b''
        return plan, states, calls, kube

    def test_installer_reconciles_owned_state_and_never_reads_credentials(self):
        plan, states, calls, kube = self.stateful()
        with patch.object(installer, 'protected', return_value=['same']), \
                patch.object(installer, 'addresses', return_value=['10.43.0.1']), \
                patch.object(installer, 'established_crd'):
            first = installer.execute(plan, kube=kube)
            second = installer.execute(plan, kube=kube)
        self.assertEqual(first, second)
        self.assertEqual('Passed', first['api_callback_tls'])
        self.assertEqual('Not verified', first['certificate_issuance'])
        for name in installer.BOUNDARIES:
            self.assertEqual(installer.SELECTOR, states[('validatingpolicy', name)]['spec']['matchConstraints']['namespaceSelector'])
        for args, body, _ in calls:
            self.assertNotIn('delete', args)
            self.assertNotIn('--force-conflicts', args)
            if 'get' in args and 'Secret' in args:
                self.assertIn('jsonpath={.metadata.uid}', ' '.join(args))
        writes = [body for args, body, _ in calls if body and '--dry-run=server' not in args]
        self.assertFalse(any(o['kind'] in ('PersistentVolume', 'PersistentVolumeClaim', 'SecretStore') for o in writes))
        self.assertTrue(all(not o.get('data') and not o.get('stringData') for o in writes if o['kind'] == 'Secret'))
        self.assertTrue(all(o['metadata'].get('namespace', target.NAMESPACE) == target.NAMESPACE for o in writes))

    def test_partial_create_and_changed_existing_selector_are_preserved(self):
        plan, states, calls, kube = self.stateful()
        def failed(*args, body=None, **kw):
            if body and body['kind'] == 'Deployment' and body['metadata']['name'] == 'cert-manager-webhook':
                raise ValueError('native-create-failed')
            return kube(*args, body=body, **kw)
        with patch.object(installer, 'protected', return_value=['same']), \
                patch.object(installer, 'addresses', return_value=['10.43.0.1']), \
                patch.object(installer, 'established_crd'), self.assertRaisesRegex(ValueError, 'native-create-failed'):
            installer.execute(plan, kube=failed)
        self.assertIn(('deployment', 'cert-manager'), states)
        self.assertFalse(any('delete' in args for args, _, _ in calls))
        plan, states, calls, kube = self.stateful()
        states[('validatingpolicy', installer.BOUNDARIES[0])]['spec']['matchConstraints']['namespaceSelector'] = {}
        with patch.object(installer, 'protected', return_value=['same']), \
                patch.object(installer, 'addresses', return_value=['10.43.0.1']), self.assertRaisesRegex(
                    ValueError, 'CERT_MANAGER_EXISTING_SELECTOR_CONFLICT'):
            installer.execute(plan, kube=kube)
        self.assertFalse(any(body is not None for _, body, _ in calls))

    def test_bundle_requires_exact_main_receipt_and_binds_all_source_bytes(self):
        revision = 'a' * 40
        receipt = {'observed_at': datetime.now(timezone.utc).isoformat()}
        with tempfile.TemporaryDirectory() as tmp, \
                patch.object(bundle, 'git', side_effect=['', revision, revision + '\trefs/heads/main']), \
                patch.object(bundle, 'staged_receipt', return_value=receipt) as staging, \
                patch.object(operator_bundle, 'verify') as verify, \
                patch.object(target, 'render', return_value=self.plan()):
            output = Path(tmp) / 'bundle'
            result = operator_bundle.build(output, '123', Path('/public/manifest'), Path('/public/evidence'))
            self.assertEqual(set(operator_bundle.SOURCES) | {'plan.json', operator_bundle.WRAPPER}, set(result['files']))
            for name, digest in result['files'].items():
                self.assertEqual(digest, hashlib.sha256((output / name).read_bytes()).hexdigest())
            self.assertTrue(staging.call_args.kwargs['cert_manager_commissioning'])
            verify.assert_called_once()
        with patch.object(bundle, 'git', return_value='dirty'), self.assertRaises(ValueError):
            operator_bundle.build(Path(self.tmp.name) / 'out', '123', self.path, Path('/public'))

    def test_missing_native_certificate_evidence_cannot_build_target_bundle(self):
        revision = 'a' * 40
        record = {'path': bundle.WORKFLOW, 'status': 'completed', 'conclusion': 'success',
            'repository': {'full_name': bundle.publication.REPOSITORY},
            'head_repository': {'full_name': bundle.publication.REPOSITORY}, 'event': 'pull_request', 'head_sha': revision}
        delivery = {k: 'Passed' for k in ('six_identity_delivery_rotation', 'cross_service_denial',
                                         'owned_secret_cleanup', 'container_log_privacy')}
        delivery['image'] = eso.published_pin()['image']
        certs = {k: 'Passed' for k in ('cert_manager_installation', 'api_callback_tls', 'external_issuer_denied',
            'idempotent_reconciliation', 'source_identity_positive_negative', 'rbac_issuer_scope_negative',
            'certificate_issuance', 'certificate_rotation_hostname_negative',
            'wrong_source_webhook_network', 'container_key_log_markers', 'callback_security_negative')}
        certs.update(images=target.candidate()['publication']['images'], root_mesh_openbao_eso_storage='Preserved')
        receipt = {'revision': revision, 'scope': 'Disposable Calico/Ambient initialized-store internal cert-manager commissioning',
            'upgrade_publication': eso.mesh.pin(), 'mesh_images': {**{n: v['image'] for n, v in eso.admission.mesh_publication.targets().items()},
                                                                  **eso.mesh.pin()['images']},
            'observed_at': datetime.now(timezone.utc).isoformat(), 'checks': dict.fromkeys(bundle.FOUNDATION, 'Passed'),
            'platform_checks': dict.fromkeys(bundle.CHECKS | {'image_only_mesh_upgrade_initialized_bao_preserved',
                'source_identity_istiod_positive_negative', 'source_identity_cni_positive_negative'}, 'Passed'),
            'eso_commissioning': {'image': eso.published_pin()['image'], 'eso_installation': 'Passed',
                'six_secretstores': 'Passed', 'idempotent_reconciliation': 'Passed', 'source_identity_positive_negative': 'Passed',
                'openbao_ca_mesh_storage': 'Preserved', 'delivery': delivery}, 'cert_manager_commissioning': certs}
        with patch.object(bundle, 'git', return_value='same-tree'):
            bundle.check_staging(record, receipt, revision, mesh_security_upgrade=True,
                                 eso_commissioning=True, cert_manager_commissioning=True)
            for name in ('certificate_issuance', 'certificate_rotation_hostname_negative', 'rbac_issuer_scope_negative',
                         'wrong_source_webhook_network', 'container_key_log_markers', 'callback_security_negative'):
                wrong = copy.deepcopy(receipt)
                wrong['cert_manager_commissioning'][name] = 'Not verified'
                with self.assertRaises(ValueError): bundle.check_staging(record, wrong, revision,
                    mesh_security_upgrade=True, eso_commissioning=True, cert_manager_commissioning=True)


if __name__ == '__main__':
    unittest.main()
