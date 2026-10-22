"""Real node-certificate validation and create-only delivery/recovery boundaries."""
import base64
import copy
import hashlib
import json
import subprocess
from datetime import datetime, timedelta, timezone
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import install_kafka_tls as installer
import kafka_secret_delivery as contract
import kafka_tls_material as material
import redis_tls_material as shared
import bootstrap_kafka_tls as bootstrap
import build_kafka_tls_bundle as bundle
import render_platform_admission as admission
import render_postgresql_quarantine as quarantine
import render_redis_runtime as runtime
import activate_openbao_operator as operator
import rehearse_openbao_kubernetes as rehearsal
from rehearse_redis_tls import fixture_issuer
from test_postgresql_secrets import CredentialAPI


class KafkaTLSTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        ordinary = shared.Path.read_text
        guard = patch.object(shared.Path, 'read_text', lambda p, *a, **k:
            'Filename Type Size Used Priority\n' if str(p) == '/proc/swaps' else ordinary(p, *a, **k))
        guard.start()
        cls.addClassCleanup(guard.stop)
        cls.ram = material.ram_work()
        cls.work = cls.ram.__enter__()
        cls.addClassCleanup(cls.ram.__exit__, None, None, None)
        material.platform.native([material.custody.OPENSSL, 'req', '-x509', '-newkey', 'rsa:3072', '-nodes',
            '-days', '365', '-subj', '/CN=synthetic-kafka-root', '-keyout', str(cls.work / 'root.key'),
            '-out', str(cls.work / 'root.crt')], timeout=60)
        cls.root = (cls.work / 'root.crt').read_text()
        cls.signer = fixture_issuer(cls.root, cls.work / 'root.key')
        cls.issuer, cls.key, cls.password = cls.signer.__enter__()
        cls.addClassCleanup(cls.signer.__exit__, None, None, None)
        cls.value = material.issue(cls.root, cls.issuer, cls.key, cls.password)

    def test_exact_dual_use_node_certificate_and_existing_encrypted_issuer(self):
        material.validate(self.value, self.root, self.issuer)
        before = hashlib.sha256(self.key.read_bytes()).hexdigest()
        with self.assertRaises(material.custody.BootstrapFailed):
            material.issue(self.root, self.issuer, self.key, b'wrong-passphrase')
        self.assertEqual(before, hashlib.sha256(self.key.read_bytes()).hexdigest())
        self.assertNotIn('PRIVATE KEY', self.root + self.issuer)

    def test_usage_subject_wildcard_key_and_expiry_mutations_fail(self):
        ordinary = material.platform.native
        for mode in ('server-only', 'client-only', 'extra-usage', 'wildcard', 'subject', 'weak-key', 'expiry'):
            def native(argv, **kwargs):
                argv = list(argv)
                if mode == 'weak-key' and 'req' in argv: argv[argv.index('rsa:3072')] = 'rsa:2048'
                if mode == 'expiry' and '-days' in argv: argv[argv.index('-days') + 1] = '1'
                if mode == 'subject' and '-subj' in argv: argv[argv.index('-subj') + 1] = '/CN=foreign-node'
                return ordinary(argv, **kwargs)
            extensions = material.EXTENSIONS
            if mode == 'server-only': extensions = extensions.replace(b'serverAuth,clientAuth', b'serverAuth')
            if mode == 'client-only': extensions = extensions.replace(b'serverAuth,clientAuth', b'clientAuth')
            if mode == 'extra-usage': extensions = extensions.replace(b'clientAuth', b'clientAuth,codeSigning')
            if mode == 'wildcard': extensions = extensions.replace(b'IP:127.0.0.1', b'DNS:*.platform-data.svc,IP:127.0.0.1')
            with self.subTest(mode=mode), patch.object(material, 'EXTENSIONS', extensions), \
                    patch.object(material.platform, 'native', side_effect=native), \
                    self.assertRaises(material.custody.BootstrapFailed):
                material.issue(self.root, self.issuer, self.key, self.password)

    def test_foreign_ca_key_chain_or_extra_field_rejected(self):
        for change in ({'ca.crt': self.issuer}, {'tls.key': 'not a key'},
                       {'tls.crt': self.root}, {'extra': 'invalid'}):
            with self.subTest(fields=list(change)), self.assertRaises(material.custody.BootstrapFailed):
                material.validate(self.value | change, self.root, self.issuer)

    def test_exact_reader_identity_and_independent_one_name_tokenrequest(self):
        role = contract.role()
        self.assertEqual(['platform-data'], role['bound_service_account_namespaces'])
        self.assertEqual(['eso-kafka-tls'], role['bound_service_account_names'])
        self.assertEqual(['hooshix-kafka-tls'], role['token_policies'])
        self.assertEqual(300, role['token_explicit_max_ttl'])
        self.assertTrue(role['token_no_default_policy'])
        self.assertNotIn('*', contract.policy())
        objects = contract.foundation()
        self.assertEqual(['eso-kafka-tls'], next(o for o in objects if o['kind'] == 'Role')['rules'][0]['resourceNames'])
        self.assertFalse(any(o['kind'] in ('Secret', 'Deployment', 'NetworkPolicy', 'ClusterRole') for o in objects))
        self.assertEqual(set(material.FIELDS), {d['secretKey'] for d in contract.external()['spec']['data']})

    def test_new_publication_does_not_upgrade_or_adopt_the_existing_database_boundary(self):
        base = admission.policy('ValidatingPolicy', 'hooshix-platform-data-closed', 'platform-data',
            [admission.validation('closed existing controller', 'false')])
        existing = dict(boundary=base, objects=[], public_ca_sha256='a' * 64,
            webhook_names=[], webhook_contracts=[], webhook_selector={})
        plan = installer.render(existing)
        retained = runtime.boundary(quarantine.boundary(base, installer.RETAINED_DATABASE_IMAGES), runtime.pod())
        self.assertIn(retained['spec'], plan['allowed_boundary_specs'])
        self.assertEqual(4, len(plan['allowed_boundary_specs']))
        changed = copy.deepcopy(installer.RETAINED_DATABASE_IMAGES)
        changed['postgresql'] = changed['postgresql'].split('@')[0] + '@sha256:' + 'f' * 64
        self.assertNotIn(runtime.boundary(quarantine.boundary(base, changed), runtime.pod())['spec'],
            plan['allowed_boundary_specs'])
        self.assertNotIn('objects', plan)  # No retained image is ever applied by leaf delivery.
        self.assertEqual('Not run', plan['database'])

    def test_supervisor_fixed_identity_audience_and_source_loader(self):
        for ns, account, audience in [('platform-apps', 'eso-kafka-tls', ['api']),
                ('platform-data', 'default', ['api']), ('platform-data', 'eso-kafka-tls', ['bad\nvalue'])]:
            with self.subTest(account=account), self.assertRaises(material.custody.BootstrapFailed):
                bootstrap.token_request(ns, account, audience)
        with patch.object(bootstrap.pg, 'kube', return_value=b'synthetic-jwt') as call:
            self.assertEqual('synthetic-jwt', bootstrap.token_request('platform-data', 'eso-kafka-tls', ['api']))
            self.assertIn('--duration=10m', call.call_args.args)
        root = Path(__file__).resolve().parents[1]
        sources = [(name, hashlib.sha256((root / name).read_bytes()).hexdigest()) for name in bundle.SOURCES]
        program = operator.bootstrap(str(root), 'a' * 40, sources, 'bootstrap_kafka_tls')
        program = program.rsplit('raise SystemExit(', 1)[0]
        result = subprocess.run([sys.executable, '-I', '-c', program], capture_output=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stderr.decode())
        self.assertEqual(b'', result.stdout)
        self.assertIn('kafka-tls-delivery', rehearsal.STEPS)

    def test_bundle_needs_every_real_proof_and_does_not_grant_runtime(self):
        value = dict(schema_version=1, namespace='platform-data', tls_lifetime_days=30,
            existing_state='Preserved', existing_identities='Preserved', public_access='CLOSED',
            production_readiness='Not verified', **dict.fromkeys(bundle.CHECKS, 'Passed'),
            **dict.fromkeys(('kafka', 'automatic_renewal', 'root_revocation', 'host_guard', 'provider_backup'), 'Not run'))
        with patch.object(bundle.previous, 'verify_proof') as previous:
            bundle.verify_proof({'kafka_tls_delivery': value})
            previous.assert_called_once()
            for field in value:
                with self.subTest(field=field), self.assertRaises(ValueError):
                    bundle.verify_proof({'kafka_tls_delivery': value | {field: 'unverified'}})

    def test_stale_or_future_evidence_stops_before_issuer_and_root_use(self):
        for days in (-6, 1):
            plan = {'staging': {'observed_at': (datetime.now(timezone.utc) + timedelta(days=days)).isoformat()}}
            with self.subTest(days=days), patch.object(bootstrap.pg, 'decode_plan', return_value=plan), \
                    patch.object(bootstrap.pg, 'commission') as commission, \
                    self.assertRaises(material.custody.BootstrapFailed):
                bootstrap.commission({'plan': 'public', 'plan_sha256': 'a' * 64}, 'b' * 40)
            commission.assert_not_called()

    def fixture(self):
        api, state, creates = CredentialAPI(), {}, []
        plan = {'schema_version': 1, 'installation_id': 'hooshix-production', 'kafka_tls_delivery': True,
            'public_access': 'CLOSED', 'database': 'Not run', 'redis': 'Not run',
            'public_ca_sha256': hashlib.sha256(self.root.encode()).hexdigest()}
        def kube(*args, body=None, **options):
            if args[0] == '-n': args = args[2:]
            if args[:2] == ('get', 'namespace'):
                return json.dumps({'metadata': {'labels': {'istio.io/dataplane-mode': 'ambient',
                    'pod-security.kubernetes.io/enforce': 'restricted'}}}).encode()
            if args[:2] == ('get', 'clusterrole'):
                return json.dumps({'rules': [{'apiGroups': ['authentication.k8s.io'],
                    'resources': ['tokenreviews'], 'verbs': ['create']}]}).encode()
            if args[0] == 'create':
                obj = copy.deepcopy(body)
                obj['metadata']['uid'] = 'fixture-' + obj['metadata']['name']
                state[(obj['kind'].lower(), obj['metadata']['name'])] = obj
                creates.append(obj)
                if obj['kind'] == 'ExternalSecret':
                    data = api.values['hooshix/data/' + contract.ENTRY['key']]
                    state[('secret', contract.NAME)] = {'metadata': {'uid': 'delivered',
                        'labels': contract.LABELS.copy(), 'ownerReferences': installer.owner(obj)},
                        'type': 'kubernetes.io/tls',
                        'data': {k: base64.b64encode(data[k].encode()).decode() for k in material.FIELDS}}
                return b''
            if args[0] == 'get':
                obj = state.get((args[1].lower(), args[2]))
                if obj is None: return b''
                return json.dumps(obj['metadata'] if args[-1] == 'jsonpath={.metadata}' else obj).encode()
            if args[0] == 'wait': return b''
            if args[:2] == ('auth', 'can-i'): return b'no' if options.get('expected') == 1 else b'yes'
            raise AssertionError('unexpected fixture operation')
        return api, plan, kube, state, creates

    def execute(self, api, plan, kube, issue):
        with patch.object(installer.previous, 'snapshot', return_value=['unchanged-state']), \
                patch.object(installer.previous.pg, 'webhook'), \
                patch.object(installer.previous, 'verify_auth'), patch.object(contract, 'configure'), \
                patch.object(contract, 'check_existing', return_value=[]):
            return installer.execute(plan, kube=kube, api=api, root='synthetic-root', ca=self.root,
                audiences=['api'], token_request=Mock(), issuer=self.issuer, issue=issue)

    def test_first_delivery_and_no_write_reentry(self):
        api, plan, kube, state, creates = self.fixture()
        issue = Mock(return_value=self.value.copy())
        result = self.execute(api, plan, kube, issue)
        self.assertEqual('Passed', result['tls_delivery'])
        before = copy.deepcopy(api.values), copy.deepcopy(state), len(creates), len(api.writes)
        self.assertEqual(result, self.execute(api, plan, kube, Mock(side_effect=AssertionError('no reissue'))))
        self.assertEqual(before, (api.values, state, len(creates), len(api.writes)))
        issue.assert_called_once()

    def test_foreign_target_deleted_history_and_invalid_old_leaf_preserve_all_state(self):
        for mode in ('target', 'history', 'leaf', 'role'):
            api, plan, kube, state, creates = self.fixture()
            path = 'hooshix/data/' + contract.ENTRY['key']
            if mode == 'target': state[('secret', contract.NAME)] = {'metadata': {'uid': 'foreign'}}
            if mode == 'history': api.history.add(path.replace('/data/', '/metadata/'))
            if mode == 'leaf': api.values[path] = self.value | {'tls.key': 'invalid',
                'managed_by': contract.MANAGER, 'installation_id': 'hooshix-production'}
            if mode == 'role':
                obj = next(o for o in contract.foundation() if o['kind'] == 'Role')
                obj['rules'][0]['resourceNames'].append('default')
                state[('role', obj['metadata']['name'])] = obj
            before = copy.deepcopy(api.values), copy.deepcopy(state), len(api.writes), len(creates)
            with self.subTest(mode=mode), self.assertRaises(material.custody.BootstrapFailed):
                self.execute(api, plan, kube, Mock(side_effect=AssertionError('must not issue')))
            self.assertEqual(before, (api.values, state, len(api.writes), len(creates)))

    def test_partial_kv_commit_reuses_key_after_permission_failure(self):
        api, plan, kube, _, _ = self.fixture()
        with patch.object(installer.previous, 'snapshot', return_value=[]), \
                patch.object(installer.previous.pg, 'webhook'), \
                patch.object(contract, 'configure', side_effect=ValueError('interrupted')), \
                patch.object(contract, 'check_existing', return_value=[]), self.assertRaises(ValueError):
            installer.execute(plan, kube=kube, api=api, root='synthetic-root', ca=self.root,
                audiences=['api'], token_request=Mock(), issuer=self.issuer, issue=lambda: self.value.copy())
        committed = copy.deepcopy(api.values)
        self.execute(api, plan, kube, Mock(side_effect=AssertionError('must not reissue')))
        self.assertEqual(committed, api.values)


if __name__ == '__main__': unittest.main()
