"""Namespace boundary, source preservation, complete preflight and safe reentry."""
import base64
import copy
import hashlib
import json
import sys
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import application_postgresql_delivery as contract
import install_application_postgresql_delivery as installer
import build_application_postgresql_bundle as bundle
import activate_openbao_operator as transport


class Fixture:
    ca = 'public-test-ca'
    image = 'ghcr.io/example/eso@sha256:' + 'a' * 64

    def __init__(self):
        self.objects, self.values, self.auth, self.creates, self.writes = {}, {}, {}, [], []
        for index, (name, item) in enumerate(contract.source.ENTRIES.items()):
            values = {'username': item['username'], 'password': str(index).zfill(64),
                      'managed_by': contract.source.MANAGER, 'installation_id': 'hooshix-production'}
            self.values['hooshix/data/' + item['key']] = values
            self.put('Secret', name, contract.source.NAMESPACE,
                type='kubernetes.io/basic-auth', data={k: base64.b64encode(values[k].encode()).decode()
                                                     for k in ('username', 'password')},
                labels={'app.kubernetes.io/managed-by': contract.source.MANAGER})
            external = contract.source.external(name)
            external['metadata']['uid'] = 'source-' + name
            external['status'] = {'conditions': [{'type': 'Ready', 'status': 'True'}]}
            self.objects[('externalsecret', contract.source.NAMESPACE, name)] = external
            self.objects[('secret', contract.source.NAMESPACE, name)]['metadata']['ownerReferences'] = installer.owner(external)
        for namespace in ('platform-apps', 'platform-data', 'hooshix-secrets'):
            self.put('Namespace', namespace, labels={'istio.io/dataplane-mode': 'ambient',
                                                   'pod-security.kubernetes.io/enforce': 'restricted'})
        self.put('ClusterRole', 'hooshix-openbao-tokenreview', rules=[{'apiGroups': ['authentication.k8s.io'],
            'resources': ['tokenreviews'], 'verbs': ['create']}])
        self.put('ConfigMap', 'hooshix-openbao-ca', 'platform-apps', data={'ca.crt': self.ca})
        self.put('AuthorizationPolicy', 'hooshix-auth-default-deny', 'platform-apps', spec={})
        self.put('PeerAuthentication', 'hooshix-auth-strict', 'platform-apps', spec={'mtls': {'mode': 'STRICT'}})
        self.put('Deployment', 'hooshix-eso', 'platform-apps', status={'availableReplicas': 1},
                 spec={'template': {'spec': {'serviceAccountName': 'hooshix-eso', 'containers': [{'image': self.image}]}}})

    def put(self, kind, name, namespace=None, labels=None, **fields):
        value = {'kind': kind, 'metadata': {'name': name, 'uid': kind + '-' + name,
                 'labels': labels or {}, **({'namespace': namespace} if namespace else {})}, **fields}
        self.objects[(kind.lower(), namespace, name)] = value
        return value

    def call(self, path, method='GET', data=None, token=None, expected=200):
        if method == 'GET':
            if path in self.values:
                return {'data': {'data': copy.deepcopy(self.values[path])}}
            return {'data': copy.deepcopy(self.auth[path])} if path in self.auth else {}
        if path.startswith('hooshix/'):
            raise AssertionError('credential write forbidden')
        self.writes.append(path)
        self.auth[path] = copy.deepcopy(data)
        return {}

    def kube(self, *args, body=None, **options):
        if args[0] == 'create':
            self.creates.append(copy.deepcopy(body))
            value = copy.deepcopy(body)
            m = value['metadata']
            m['uid'] = body['kind'] + '-' + m['name']
            if body['kind'] in ('SecretStore', 'ExternalSecret'):
                value['status'] = {'conditions': [{'type': 'Ready', 'status': 'True'}]}
            self.objects[(body['kind'].lower(), m.get('namespace'), m['name'])] = value
            if body['kind'] == 'ExternalSecret':
                source = self.values['hooshix/data/' + contract.entry(m['name'])['key']]
                secret = self.put('Secret', m['name'], 'platform-apps', type='Opaque',
                    data={field: base64.b64encode(source[key].encode()).decode()
                          for field, key in contract.FIELDS.items()},
                    labels={'app.kubernetes.io/managed-by': contract.MANAGER})
                secret['metadata']['ownerReferences'] = installer.owner(value)
            return b''
        if args[0] == 'auth':
            return b'no'
        if 'wait' in args:
            return b''
        index = args.index('get')
        namespace = args[1] if args[0] == '-n' else None
        kind = args[index + 1].lower()
        if args[index + 2] == '-o':
            return b'{"items":[]}'
        value = self.objects.get((kind, namespace, args[index + 2]))
        return json.dumps(value).encode() if value else b''

    def execute(self):
        plan = {'schema_version': 1, 'installation_id': 'hooshix-production',
            'application_postgresql_delivery': True, 'application_ingress': 'CLOSED', 'public_access': 'CLOSED',
            'public_ca_sha256': hashlib.sha256(self.ca.encode()).hexdigest(), 'existing_eso_image': self.image}
        with patch.object(installer.pg, 'verify_auth'):
            return installer.execute(plan, kube=self.kube, api=self, root='test-root', ca=self.ca,
                                     audiences=['api'], token_request=None)


class ApplicationPostgreSQLTest(unittest.TestCase):
    def test_exact_single_leaf_namespace_and_configtree_contract(self):
        self.assertEqual(10, len(contract.ENTRIES))
        original = copy.deepcopy(contract.source.ENTRIES)
        for name, item in contract.ENTRIES.items():
            self.assertEqual(contract.source.entry(name)['key'], item['key'])
            self.assertNotIn('*', contract.policy(name))
            role = contract.role(name)
            self.assertEqual(['platform-apps'], role['bound_service_account_namespaces'])
            self.assertEqual([item['account']], role['bound_service_account_names'])
            self.assertEqual([item['policy']], role['token_policies'])
            self.assertEqual(300, role['token_explicit_max_ttl'])
            external = contract.external(name)
            self.assertEqual({'spring.datasource.username', 'spring.datasource.password'},
                             {d['secretKey'] for d in external['spec']['data']})
            self.assertEqual('Opaque', external['spec']['target']['template']['type'])
            self.assertEqual('Retain', external['spec']['target']['deletionPolicy'])
            self.assertEqual(['api', 'hooshix-openbao'], contract.store(name, ['api'])['spec'][
                'provider']['vault']['auth']['kubernetes']['serviceAccountRef']['audiences'])
        self.assertEqual(original, contract.source.ENTRIES)
        for invalid in ('../root', 'postgres', ''):
            with self.assertRaises(ValueError):
                contract.entry(invalid)

    def test_only_exact_accounts_gain_tokenrequest_and_tokenreview(self):
        objects = contract.foundation()
        self.assertEqual({'ServiceAccount', 'ClusterRoleBinding', 'Role', 'RoleBinding'},
                         {o['kind'] for o in objects})
        role = next(o for o in objects if o['kind'] == 'Role')
        self.assertEqual([{'apiGroups': [''], 'resources': ['serviceaccounts/token'], 'verbs': ['create'],
                          'resourceNames': [i['account'] for i in contract.ENTRIES.values()]}], role['rules'])
        self.assertTrue(all(o.get('automountServiceAccountToken') is False for o in objects
                            if o['kind'] == 'ServiceAccount'))

    def test_success_preserves_sources_and_second_entry_writes_nothing(self):
        fixture = Fixture()
        before = copy.deepcopy(fixture.values)
        result = fixture.execute()
        self.assertEqual('Passed', result['application_postgresql_delivery'])
        self.assertEqual(33, len(fixture.creates))
        count = len(fixture.writes)
        fixture.execute()
        self.assertEqual(33, len(fixture.creates))
        self.assertEqual(count, len(fixture.writes))
        self.assertEqual(before, fixture.values)

    def test_missing_source_foreign_last_object_or_auth_fail_before_any_mutation(self):
        last = list(contract.ENTRIES)[-1]
        for mode in ('wrong-image', 'missing', 'source-not-ready', 'foreign-secret', 'foreign-auth', 'extra-rbac', 'extra-secret-input'):
            with self.subTest(mode=mode):
                fixture = Fixture()
                if mode == 'wrong-image':
                    fixture.objects[('deployment', 'platform-apps', 'hooshix-eso')]['spec']['template']['spec']['containers'][0]['image'] = 'foreign'
                elif mode == 'missing':
                    fixture.values.pop('hooshix/data/' + contract.entry(last)['key'])
                elif mode == 'source-not-ready':
                    fixture.objects[('externalsecret', contract.source.NAMESPACE, last)]['status'] = {}
                elif mode == 'extra-secret-input':
                    obj = contract.external(last)
                    obj['metadata']['uid'] = 'owned'
                    obj['spec']['dataFrom'] = [{'extract': {'key': 'foreign'}}]
                    fixture.objects[('externalsecret', contract.NAMESPACE, last)] = obj
                elif mode == 'foreign-secret':
                    fixture.put('Secret', last, 'platform-apps', data={})
                elif mode == 'foreign-auth':
                    fixture.auth['sys/policies/acl/' + contract.entry(last)['policy']] = {'policy': 'foreign'}
                else:
                    obj = next(o for o in contract.foundation() if o['kind'] == 'Role')
                    obj['rules'][0]['verbs'].append('delete')
                    obj['metadata']['uid'] = 'owned'
                    fixture.objects[('role', 'platform-apps', obj['metadata']['name'])] = obj
                with self.assertRaises(ValueError):
                    fixture.execute()
                self.assertEqual([], fixture.creates)
                self.assertEqual([], fixture.writes)

    def test_owned_but_changed_target_is_preserved_before_other_writes(self):
        fixture = Fixture()
        fixture.execute()
        fixture.creates.clear()
        fixture.writes.clear()
        name = list(contract.ENTRIES)[-1]
        changed = fixture.objects[('secret', contract.NAMESPACE, name)]
        changed['data']['spring.datasource.password'] = base64.b64encode(b'drift').decode()
        with self.assertRaisesRegex(ValueError, 'PG_APP_EXISTING_SECRET_DRIFT'):
            fixture.execute()
        self.assertEqual([], fixture.creates)
        self.assertEqual([], fixture.writes)

    def test_transport_only_admits_fixed_reviewed_supervisor(self):
        script = transport.bootstrap('/private/public-sources', 'a' * 40, (),
                                     'bootstrap_application_postgresql_delivery')
        self.assertIn('bootstrap_application_postgresql_delivery', script)
        self.assertIn('O_NOFOLLOW', script)
        with self.assertRaises(transport.host.custody.BootstrapFailed):
            transport.bootstrap('/private', 'a' * 40, (), 'arbitrary')

    def test_target_auth_verification_never_posts_to_existing_kv(self):
        calls = []
        accounts = {item['account']: item for item in contract.ENTRIES.values()}
        def call(path, method='GET', data=None, token=None, expected=200):
            calls.append((path, method))
            self.assertFalse(path.startswith('hooshix/') and method != 'GET')
            if path.endswith('/login') and expected == 200:
                return {'auth': {'client_token': 'synthetic-session', 'lease_duration': 300,
                                 'policies': [accounts[data['role']]['policy']]}}
            if path == 'sys/capabilities':
                return {'data': {data['paths'][0]: ['read']}}
            if path == 'auth/token/lookup-self' and token == 'root':
                return {'data': {'policies': ['root']}}
            return {}
        api = type('API', (), {'call': staticmethod(call)})()
        installer.pg.verify_auth(api, 'root', lambda *a: 'synthetic-jwt', ['api'],
                                 delivery=contract, read_only_values=True)
        self.assertEqual(10, calls.count(('sys/capabilities', 'POST')))

    def test_real_hash_bound_source_graph_loads_without_repository_import_path(self):
        source = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            hashes = []
            for name in bundle.SOURCES:
                content = (source / name).read_bytes()
                self.assertLessEqual(len(content), 32768)
                (folder / name).write_bytes(content)
                hashes.append((name, hashlib.sha256(content).hexdigest()))
            script = transport.bootstrap(temporary, 'a' * 40, hashes,
                                         'bootstrap_application_postgresql_delivery')
            script = script[:script.rindex('raise SystemExit(')] + (
                'assert len(sys.modules["application_postgresql_delivery"].ENTRIES)==10\n'
                'print("SOURCE_GRAPH=Passed")\n')
            result = subprocess.run([sys.executable, '-I', '-c', script], capture_output=True,
                                    text=True, timeout=15, check=False)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('SOURCE_GRAPH=Passed', result.stdout.strip())

    def test_proof_rejects_unexecuted_or_open_projection(self):
        proof = Fixture().execute()
        proof.update(idempotent_reconciliation='Passed', container_log_privacy='Passed',
                     existing_eso_image=bundle.published_pin()['image'])
        bundle.verify_proof({'application_postgresql_delivery': proof})
        for key, value in [('application_ingress', 'OPEN'), ('kv_writes', 'Passed'),
                           ('credential_count', 9), ('scoped_authentication', 'Not run')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                bundle.verify_proof({'application_postgresql_delivery': {**proof, key: value}})


if __name__ == '__main__':
    unittest.main()
