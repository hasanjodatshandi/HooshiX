"""Redis secret values are isolated, create-only, fail-closed and never public evidence."""
import base64
import copy
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import activate_openbao_operator as operator
import bootstrap_redis_secrets as bootstrap
import build_redis_secrets_bundle as bundle
import install_postgresql_secrets as pg
import install_redis_secrets as target
import openbao_scoped_auth as auth
import redis_secret_delivery as contract
import render_redis_acl as acl
import render_redis_security as renderer
from test_postgresql_secrets import CredentialAPI


class RedisSecretTest(unittest.TestCase):
    def test_exact_five_passwords_and_six_leaf_policies(self):
        self.assertEqual(5, len(contract.ENTRIES))
        self.assertEqual(6, len(contract.AUTH_ENTRIES))
        self.assertEqual(set(acl.USERS), {v['username'] for v in contract.ENTRIES.values()})
        for name, item in contract.AUTH_ENTRIES.items():
            self.assertNotIn('*', contract.policy(name))
            self.assertIn('hooshix/data/' + item['key'], contract.policy(name))
            role = contract.role(name)
            self.assertEqual([item['account']], role['bound_service_account_names'])
            self.assertEqual(['platform-data'], role['bound_service_account_namespaces'])
            self.assertEqual([item['policy']], role['token_policies'])
            self.assertEqual(300, role['token_explicit_max_ttl'])
            self.assertEqual(auth.AUDIENCE, role['audience'])
            self.assertTrue(role['token_no_default_policy'])
        with self.assertRaises(ValueError): contract.entry('../other')

    def test_namespace_local_projection_and_no_static_credentials(self):
        for name in contract.AUTH_ENTRIES:
            store = contract.store(name, ['api'])
            vault = store['spec']['provider']['vault']
            self.assertEqual(['api', auth.AUDIENCE], vault['auth']['kubernetes']['serviceAccountRef']['audiences'])
            self.assertNotIn('secretRef', json.dumps(store))
            external = contract.external(name)
            expected = {'users.acl'} if name == contract.ACL_NAME else {'username', 'password'}
            self.assertEqual(expected, {e['secretKey'] for e in external['spec']['data']})
            self.assertEqual('Owner', external['spec']['target']['creationPolicy'])
            self.assertEqual('Retain', external['spec']['target']['deletionPolicy'])
            self.assertEqual('platform-data', external['metadata']['namespace'])

    def test_reuse_controller_and_exact_tokenrequest_with_no_cluster_secret_role(self):
        objects = contract.foundation()
        self.assertEqual(6, sum(o['kind'] == 'ServiceAccount' for o in objects))
        self.assertFalse(any(o['kind'] in ('Deployment', 'Secret', 'ClusterRole') for o in objects))
        role = next(o for o in objects if o['kind'] == 'Role')
        self.assertEqual([{'apiGroups': [''], 'resources': ['serviceaccounts/token'], 'verbs': ['create'],
            'resourceNames': [e['account'] for e in contract.AUTH_ENTRIES.values()]}], role['rules'])
        binding = next(o for o in objects if o['kind'] == 'RoleBinding')
        self.assertEqual([{'kind': 'ServiceAccount', 'namespace': 'platform-data', 'name': contract.ACCOUNT}],
                         binding['subjects'])

    def test_whole_set_preflight_then_exact_acl_and_no_rotation_on_reentry(self):
        api = CredentialAPI()
        values, missing, material, create_acl = target.credential_plan(api, 'synthetic-root')
        self.assertTrue(create_acl)
        self.assertEqual(5, len(missing))
        self.assertEqual([], api.writes)
        hashes = {v['username']: hashlib.sha256(v['password'].encode()).hexdigest() for v in values.values()}
        self.assertEqual(renderer.acl_file(hashes), material['users.acl'])
        self.assertFalse(any(v['password'] in material['users.acl'] for v in values.values()))
        pg.persist_credentials(api, 'synthetic-root', values, missing, delivery=contract)
        api.call('hooshix/data/' + contract.ACL_ENTRY['key'], 'POST', {'options': {'cas': 0}, 'data': material})
        second = target.credential_plan(api, 'synthetic-root')
        self.assertEqual((values, [], material, False), second)
        self.assertEqual(6, len(api.writes))

    def test_foreign_credentials_acl_and_deleted_history_all_stop_before_writes(self):
        for mode in ('credential', 'acl', 'acl-history', 'credential-history'):
            api = CredentialAPI()
            name = list(contract.ENTRIES)[-1]
            path = 'hooshix/data/' + (contract.entry(name)['key'] if mode.startswith('credential')
                                      else contract.ACL_ENTRY['key'])
            if mode.endswith('history'): api.history.add(path.replace('/data/', '/metadata/'))
            else: api.values[path] = {'foreign': 'preserve'}
            with self.subTest(mode=mode), self.assertRaises((ValueError, auth.host.custody.BootstrapFailed)):
                target.credential_plan(api, 'synthetic-root')
            self.assertEqual([], api.writes)

    def test_partial_credentials_are_not_replaced_after_interruption(self):
        api = CredentialAPI()
        values, missing, _, _ = target.credential_plan(api, 'synthetic-root')
        for name in missing[:2]:
            api.call('hooshix/data/' + contract.entry(name)['key'], 'POST',
                     {'options': {'cas': 0}, 'data': values[name]})
        retained = copy.deepcopy(api.values)
        next_values, next_missing, _, _ = target.credential_plan(api, 'synthetic-root')
        self.assertEqual(3, len(next_missing))
        self.assertEqual({n: v for n, v in values.items() if n not in missing[2:]},
                         {n: next_values[n] for n in missing[:2]})
        self.assertEqual(retained, api.values)

    def test_auth_late_conflict_prevents_all_mutations(self):
        api = Mock()
        api.call.side_effect = [{}] * 11 + [{'data': {'bound_service_account_names': ['foreign']}}]
        with self.assertRaises(auth.host.custody.BootstrapFailed): contract.configure(api, 'synthetic-root')
        self.assertEqual(12, api.call.call_count)
        self.assertTrue(all(c.kwargs.get('token') == 'synthetic-root' for c in api.call.call_args_list))

    def test_tokenrequest_rejects_foreign_identity_or_audience_before_kube(self):
        for ns, account, audiences in [('platform-apps', 'eso-redis-health', ['api']),
                ('platform-data', 'default', ['api']), ('platform-data', 'eso-redis-health', ['bad;value'])]:
            with patch.object(bootstrap.pg, 'kube') as kube, self.assertRaises(auth.host.custody.BootstrapFailed):
                bootstrap.token_request(ns, account, audiences)
            kube.assert_not_called()

    def test_transport_uses_exact_reviewed_supervisor_and_no_secret_arguments(self):
        source = operator.bootstrap('/fixed/public', 'a' * 40, [], supervisor='bootstrap_redis_secrets')
        self.assertIn("sys.modules['bootstrap_redis_secrets']", source)
        self.assertNotIn('root_token', source)
        with self.assertRaises(auth.host.custody.BootstrapFailed):
            operator.bootstrap('/fixed/public', 'a' * 40, [], supervisor='unreviewed')
        self.assertEqual(len(bundle.SOURCES), len(set(bundle.SOURCES)))
        self.assertTrue(all((operator.ROOT / 'scripts/production' / n).stat().st_size <= 32768 for n in bundle.SOURCES))
        with tempfile.TemporaryDirectory(prefix='hooshix-public-source-test-') as temp:
            hashes = []
            for name in bundle.SOURCES:
                path = operator.ROOT / 'scripts/production' / name
                shutil.copyfile(path, Path(temp) / name)
                hashes.append((name, hashlib.sha256(path.read_bytes()).hexdigest()))
            source = operator.bootstrap(temp, 'a' * 40, hashes, supervisor='bootstrap_redis_secrets')
            # Exercise the real isolated loader without calling any privileged operation.
            source = source.rsplit('raise SystemExit(', 1)[0]
            subprocess.run([sys.executable, '-I', '-c', source], check=True, timeout=20)

    def fixture(self):
        api = CredentialAPI()
        ordinary = api.call
        auth_values = {}
        def call(path, method='GET', data=None, token=None, expected=200):
            if path.startswith(('sys/', 'auth/')):
                if method == 'GET': return {'data': copy.deepcopy(auth_values[path])} if path in auth_values else {}
                auth_values[path] = copy.deepcopy(data)
                return {}
            return ordinary(path, method, data, token, expected)
        api.call = call
        desired = [contract.resource(kind, name, **body) for kind, name, body in (
            ('Deployment', contract.ACCOUNT, {'spec': {'replicas': 1, 'template': {'spec': {}}}}),
            ('Role', contract.ACCOUNT + '-controller', {'rules': []}),
            ('RoleBinding', contract.ACCOUNT + '-controller', {'subjects': [], 'roleRef': {}}),
            ('ServiceAccount', contract.ACCOUNT, {'automountServiceAccountToken': False}))]
        actuals = {}
        for obj in desired:
            obj['metadata']['labels']['app.kubernetes.io/managed-by'] = pg.contract.MANAGER
            actual = copy.deepcopy(obj)
            actual['metadata']['uid'] = 'existing-' + obj['kind']
            actual['status'] = {'availableReplicas': 1}
            actuals[(obj['kind'].lower(), obj['metadata']['name'])] = actual
        plan = {'schema_version': 1, 'installation_id': 'hooshix-production', 'redis': 'Not run',
            'database': 'Not run', 'public_access': 'CLOSED', 'existing_controller': desired,
            'public_ca_sha256': hashlib.sha256(b'synthetic-ca').hexdigest(),
            'allowed_boundary_specs': [{'validationActions': ['Deny']}]}
        policy = {'metadata': {'name': 'hooshix-platform-data-closed', 'uid': 'old-policy'},
                  'spec': plan['allowed_boundary_specs'][0], 'status': {'conditionStatus': {'ready': True}}}
        creates = []
        def kube(*args, body=None, **options):
            if args[:2] == ('-n', 'platform-data'): args = args[2:]
            if args[0] == 'auth':
                return b'no' if options.get('expected') == 1 else b'yes'
            if args[0] == 'wait': return b''
            if args[0] == 'create':
                creates.append(copy.deepcopy(body))
                obj = copy.deepcopy(body)
                obj['metadata']['uid'] = 'created-' + obj['metadata']['name']
                actuals[(obj['kind'].lower(), obj['metadata']['name'])] = obj
                if obj['kind'] == 'ExternalSecret':
                    data = {e['secretKey']: base64.b64encode(api.values['hooshix/data/' + e['remoteRef']['key']]
                        [e['remoteRef']['property']].encode()).decode() for e in obj['spec']['data']}
                    secret = contract.resource('Secret', obj['metadata']['name'],
                        type=obj['spec']['target']['template']['type'], data=data)
                    secret['metadata']['ownerReferences'] = [{'apiVersion': 'external-secrets.io/v1',
                        'kind': 'ExternalSecret', 'name': obj['metadata']['name'], 'uid': obj['metadata']['uid'],
                        'controller': True, 'blockOwnerDeletion': True}]
                    actuals[('secret', obj['metadata']['name'])] = secret
                return b''
            self.assertEqual('get', args[0])
            kind, name = args[1].lower(), args[2]
            if kind == 'namespace':
                return json.dumps({'metadata': {'labels': {'istio.io/dataplane-mode': 'ambient',
                    'pod-security.kubernetes.io/enforce': 'restricted'}}}).encode()
            if kind == 'validatingpolicy': return json.dumps(policy).encode()
            if kind == 'clusterrole':
                return json.dumps({'rules': [{'apiGroups': ['authentication.k8s.io'],
                    'resources': ['tokenreviews'], 'verbs': ['create']}]}).encode()
            if name == '-o':
                items = [{'metadata': {'name': 'hooshix-postgresql-strict', 'uid': 'strict'},
                    'spec': {'mtls': {'mode': 'STRICT'}}}] if kind == 'peerauthentication' else []
                return json.dumps({'items': items}).encode()
            value = actuals.get((kind, name))
            if not value: return b''
            return json.dumps(value['metadata'] if args[-1] == 'jsonpath={.metadata}' else value).encode()
        return api, plan, policy, creates, kube, actuals

    def test_execute_creates_only_delivery_objects_and_reentry_is_no_write(self):
        api, plan, _, creates, kube, _ = self.fixture()
        with patch.object(target.pg, 'webhook'), patch.object(target, 'verify_auth'):
            first = target.execute(plan, kube=kube, api=api, root='synthetic-root', ca='synthetic-ca',
                                   audiences=['api'], token_request=Mock())
            count, writes = len(creates), len(api.writes)
            second = target.execute(plan, kube=kube, api=api, root='synthetic-root', ca='synthetic-ca',
                                    audiences=['api'], token_request=Mock())
        self.assertEqual(first, second)
        self.assertEqual('Passed', first['delivery'])
        self.assertEqual(6, writes)
        self.assertEqual((count, writes), (len(creates), len(api.writes)))
        self.assertFalse(any(o['kind'] in ('Secret', 'Deployment', 'PersistentVolumeClaim', 'Cluster',
            'NetworkPolicy', 'ValidatingPolicy', 'AuthorizationPolicy') for o in creates))

    def test_unsafe_admission_or_late_foreign_secret_stop_before_all_writes(self):
        for mode in ('audit', 'unready', 'foreign-secret'):
            api, plan, policy, creates, kube, actuals = self.fixture()
            if mode == 'audit': policy['spec'] = {'validationActions': ['Audit']}
            elif mode == 'unready': policy['status']['conditionStatus']['ready'] = False
            else: actuals[('secret', contract.ACL_NAME)] = {'metadata': {'labels': {'owner': 'foreign'}}}
            with patch.object(target.pg, 'webhook'), self.subTest(mode=mode), self.assertRaises(auth.host.custody.BootstrapFailed):
                target.execute(plan, kube=kube, api=api, root='synthetic-root', ca='synthetic-ca',
                               audiences=['api'], token_request=Mock())
            self.assertEqual([], creates)
            self.assertEqual([], api.writes)

    def test_bundle_rejects_missing_native_or_changed_boundary_evidence(self):
        result = {'schema_version': 1, 'namespace': 'platform-data', 'credential_count': 5,
            'public_access': 'CLOSED', 'existing_data_controller_policy_storage': 'Preserved',
            **{key: 'Passed' for key in bundle.CHECKS},
            **{key: 'Not run' for key in ('redis', 'tls', 'database', 'provider_backup', 'root_revocation')}}
        bundle.verify_proof({'redis_secret_delivery': result})
        for key in result:
            with self.subTest(key=key), self.assertRaises(ValueError):
                bundle.verify_proof({'redis_secret_delivery': result | {key: 'unverified'}})


if __name__ == '__main__': unittest.main()
