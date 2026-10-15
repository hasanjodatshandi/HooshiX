"""Ten independent credentials, create-only persistence and bounded ESO validation."""
import base64
import copy
import hashlib
import json
import sys
import unittest
import zlib
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import activate_openbao_operator as supervisor
import bootstrap_postgresql_secrets as bootstrap
import build_postgresql_secrets_bundle as bundle
import install_postgresql_secrets as installer
import openbao_scoped_auth as application
import postgresql_secret_delivery as contract
import render_postgresql_secrets as renderer


class CredentialAPI:
    def __init__(self):
        self.values, self.writes, self.history = {}, [], set()

    def call(self, path, method='GET', data=None, token=None, expected=200):
        if method != 'GET':
            if path in self.values: raise ValueError('CAS_CONFLICT')
            if data['options'] != {'cas': 0}: raise AssertionError('create-only required')
            self.writes.append(path)
            self.values[path] = copy.deepcopy(data['data'])
            return {}
        if '/metadata/' in path: return {'data': {}} if path in self.history else {}
        return {'data': {'data': copy.deepcopy(self.values[path])}} if path in self.values else {}


class PostgreSQLSecretsTest(unittest.TestCase):
    def test_exact_ten_leaf_policies_and_typed_credential_projections(self):
        self.assertEqual(10, len(contract.ENTRIES))
        old = [application.role(s) for s in application.SERVICES]
        for name, item in contract.ENTRIES.items():
            policy, role = contract.policy(name), contract.role(name)
            self.assertNotIn('*', policy)
            self.assertIn('hooshix/data/' + item['key'], policy)
            self.assertEqual(3, policy.count('path '))
            self.assertEqual([item['account']], role['bound_service_account_names'])
            self.assertEqual(['platform-data'], role['bound_service_account_namespaces'])
            self.assertEqual([item['policy']], role['token_policies'])
            self.assertEqual(300, role['token_explicit_max_ttl'])
            self.assertTrue(role['token_no_default_policy'])
            external = contract.external(name)
            self.assertEqual('kubernetes.io/basic-auth', external['spec']['target']['template']['type'])
            self.assertEqual({'username', 'password'}, {d['secretKey'] for d in external['spec']['data']})
            self.assertEqual({item['key']}, {d['remoteRef']['key'] for d in external['spec']['data']})
            store = contract.store(name, ['api'])['spec']['provider']['vault']
            self.assertEqual(item['account'], store['auth']['kubernetes']['serviceAccountRef']['name'])
            self.assertEqual(['api', application.AUDIENCE], store['auth']['kubernetes']['serviceAccountRef']['audiences'])
            self.assertNotIn('secretRef', json.dumps(store))
        self.assertEqual(old, [application.role(s) for s in application.SERVICES])
        with self.assertRaises(ValueError): contract.entry('../root')

    def test_tokenreview_reuses_exact_role_without_changing_application_subjects(self):
        objects = contract.foundation()
        self.assertFalse(any(o['kind'] == 'ClusterRole' for o in objects))
        binding = objects[0]
        self.assertEqual('hooshix-openbao-tokenreview', binding['roleRef']['name'])
        self.assertEqual(10, len(binding['subjects']))
        self.assertEqual({'platform-data'}, {s['namespace'] for s in binding['subjects']})
        self.assertTrue(all(o['automountServiceAccountToken'] is False for o in objects[1:]))

    def test_random_distinct_create_only_and_reentry_without_rotation(self):
        api = CredentialAPI()
        first = installer.credentials(api, 'fixture-root')
        self.assertEqual(10, len(set(v['password'] for v in first.values())))
        self.assertTrue(all(len(v['password']) == 64 for v in first.values()))
        self.assertEqual(first, installer.credentials(api, 'fixture-root'))
        self.assertEqual(10, len(api.writes))

    def test_late_foreign_or_deleted_value_prevents_all_writes(self):
        name = list(contract.ENTRIES)[-1]
        path = 'hooshix/data/' + contract.entry(name)['key']
        for case in ('foreign', 'history'):
            api = CredentialAPI()
            if case == 'foreign': api.values[path] = {'foreign': 'retain'}
            else: api.history.add(path.replace('/data/', '/metadata/'))
            with self.subTest(case=case), self.assertRaises(ValueError):
                installer.credentials(api, 'fixture-root')
            self.assertEqual([], api.writes)

    def test_duplicate_passwords_are_rejected_before_write(self):
        api = CredentialAPI()
        with patch.object(installer.secrets, 'token_urlsafe', return_value='x' * 64), self.assertRaises(ValueError):
            installer.credentials(api, 'fixture-root')
        self.assertEqual([], api.writes)

    def test_partial_cas_is_preserved_and_safe_to_resume(self):
        api = CredentialAPI()
        original = api.call
        def request(*args, **kwargs):
            if len(api.writes) == 3 and len(args) > 1 and args[1] == 'POST':
                raise ValueError('simulated transport failure')
            return original(*args, **kwargs)
        with patch.object(api, 'call', side_effect=request), self.assertRaises(ValueError):
            installer.credentials(api, 'fixture-root')
        retained = copy.deepcopy(api.values)
        installer.credentials(api, 'fixture-root')
        self.assertEqual(10, len(api.writes))
        self.assertTrue(all(api.values[p] == v for p, v in retained.items()))

    def test_auth_whole_set_conflict_prevents_earlier_creates(self):
        api = Mock()
        api.call.side_effect = [{}] * 19 + [{'data': {'bound_service_account_names': ['foreign']}}]
        with self.assertRaises(ValueError): contract.configure(api, 'root')
        self.assertEqual(20, api.call.call_count)
        self.assertTrue(all(c.kwargs.get('token') == 'root' for c in api.call.call_args_list))

    def test_plan_integrity_rejects_corrupt_truncated_extra_and_inflated_data(self):
        raw = b'{"schema_version":1}'
        digest = hashlib.sha256(raw).hexdigest()
        packed = zlib.compress(raw)
        self.assertEqual({'schema_version': 1}, bootstrap.decode_plan(base64.b64encode(packed).decode(), digest))
        for data, expected in [(packed[:-1], digest), (packed + b'extra', digest),
                (packed, '0' * 64), (zlib.compress(b'x' * (512 * 1024 + 1)), digest)]:
            with self.subTest(length=len(data)), self.assertRaises(bootstrap.host.custody.BootstrapFailed):
                bootstrap.decode_plan(base64.b64encode(data).decode(), expected)

    def hooks(self):
        return {'metadata': {'resourceVersion': '7'}, 'webhooks': [{'failurePolicy': 'Fail', 'timeoutSeconds': 5,
            'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': 'platform-apps'}},
            'clientConfig': {'caBundle': 'public-ca', 'service': {'namespace': 'platform-apps',
                'name': 'hooshix-eso-webhook', 'path': '/validate'}},
            'rules': [{'scope': 'Namespaced', 'resources': ['secretstores']}]}]}

    def test_webhook_upgrade_is_compare_and_swap_only_two_namespaces(self):
        obj = self.hooks()
        original = copy.deepcopy(obj)
        calls = []
        def kube(*args):
            calls.append(args)
            if args[0] == 'patch':
                operations = json.loads(args[-1])
                self.assertEqual(original['webhooks'], operations[1]['value'])
                self.assertEqual('7', operations[0]['value'])
                obj['webhooks'] = operations[-1]['value']
            return json.dumps(obj).encode()
        plan = {'webhook_names': ['one', 'two'], 'webhook_selector': renderer.WEBHOOK_SELECTOR,
                'webhook_previous_selector': obj['webhooks'][0]['namespaceSelector']}
        installer.webhook(plan, kube)
        self.assertEqual(1, sum(c[0] == 'patch' for c in calls))
        self.assertEqual(original['webhooks'][0]['clientConfig'], obj['webhooks'][0]['clientConfig'])
        self.assertEqual(renderer.WEBHOOK_SELECTOR, obj['webhooks'][0]['namespaceSelector'])

    def test_webhook_late_conflict_prevents_any_patch(self):
        good, bad = self.hooks(), self.hooks()
        bad['webhooks'][0]['failurePolicy'] = 'Ignore'
        kube = Mock(side_effect=[json.dumps(good).encode(), json.dumps(bad).encode()])
        plan = {'webhook_names': ['one', 'two'], 'webhook_selector': renderer.WEBHOOK_SELECTOR,
                'webhook_previous_selector': good['webhooks'][0]['namespaceSelector']}
        with self.assertRaises(ValueError): installer.webhook(plan, kube)
        self.assertEqual(2, kube.call_count)

    def test_exact_proof_is_required_and_no_database_or_provider_claim(self):
        proof = {'schema_version': 1, 'namespace': 'platform-data', 'credential_count': 10,
            'image': renderer.existing.published_pin()['image'], 'public_access': 'CLOSED',
            'root_mesh_openbao_application_eso_cnpg_barman_storage': 'Preserved',
            **{key: 'Passed' for key in bundle.CHECKS},
            **{key: 'Not run' for key in ('database', 'provider_backup', 'root_revocation')}}
        bundle.verify_proof({'postgresql_secret_delivery': proof})
        for key in proof:
            bad = copy.deepcopy(proof)
            del bad[key]
            with self.subTest(key=key), self.assertRaises(ValueError):
                bundle.verify_proof({'postgresql_secret_delivery': bad})

    def test_hash_bound_supervisor_and_source_order_are_executable(self):
        self.assertEqual(len(bundle.SOURCES), len(set(bundle.SOURCES)))
        script = supervisor.bootstrap('/home/hooshixadmin/.cache/public', 'a' * 40,
            [('bootstrap_postgresql_secrets.py', 'b' * 64)], 'bootstrap_postgresql_secrets')
        self.assertIn("sys.modules['bootstrap_postgresql_secrets'].main", script)
        for name in bundle.SOURCES:
            compile((Path(__file__).resolve().parents[1] / name).read_bytes(), name, 'exec')


if __name__ == '__main__': unittest.main()
