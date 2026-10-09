import base64
import io
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from urllib.error import HTTPError

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import activate_openbao_host as host
import activate_openbao_operator as operator
import bootstrap_openbao_auth as target
import openbao_scoped_auth as auth
from render_gitops import SERVICE_ACCOUNTS


class ScopedAuthTest(unittest.TestCase):
    def test_exact_service_set_and_read_only_paths(self):
        self.assertEqual(set(SERVICE_ACCOUNTS) - {'web-frontend'}, set(auth.SERVICES))
        for name in auth.SERVICES:
            self.assertEqual(f'path "hooshix/data/production/{name}/*" {{ capabilities = ["read"] }}\n',
                             auth.policy(name))
            role = auth.role(name)
            self.assertEqual(['eso-' + name], role['bound_service_account_names'])
            self.assertEqual(['platform-apps'], role['bound_service_account_namespaces'])
            self.assertEqual(300, role['token_explicit_max_ttl'])
            self.assertTrue(role['token_no_default_policy'])
        for invalid in ('default', '*', '../root', 'web-frontend'):
            with self.assertRaises(host.custody.BootstrapFailed):
                auth.policy(invalid)

    def test_only_six_delivery_accounts_can_create_tokenreview(self):
        objects = auth.foundation()
        roles = [o for o in objects if o['kind'] == 'ClusterRole']
        self.assertEqual([{'apiGroups': ['authentication.k8s.io'], 'resources': ['tokenreviews'],
                           'verbs': ['create']}], roles[0]['rules'])
        binding = next(o for o in objects if o['kind'] == 'ClusterRoleBinding')
        self.assertEqual({'eso-' + name for name in auth.SERVICES}, {s['name'] for s in binding['subjects']})
        self.assertTrue(all(s['namespace'] == auth.NAMESPACE for s in binding['subjects']))
        accounts = [o for o in objects if o['kind'] == 'ServiceAccount']
        self.assertEqual(6, len(accounts))
        self.assertTrue(all(o['automountServiceAccountToken'] is False for o in accounts))
        self.assertEqual(['Ingress', 'Egress'], next(o for o in objects if o['kind'] == 'NetworkPolicy')['spec']['policyTypes'])

    def test_namespaced_store_uses_public_ca_and_fresh_jwt_no_root(self):
        value = auth.store('identity-service', ['https://kubernetes.default.svc'])
        self.assertEqual('SecretStore', value['kind'])
        vault = value['spec']['provider']['vault']
        self.assertEqual({'type': 'ConfigMap', 'name': 'hooshix-openbao-ca', 'key': 'ca.crt'}, vault['caProvider'])
        self.assertEqual(['https://kubernetes.default.svc', auth.AUDIENCE],
                         vault['auth']['kubernetes']['serviceAccountRef']['audiences'])
        self.assertNotIn('secretRef', json.dumps(value))
        self.assertNotIn('tokenSecretRef', json.dumps(value))

    def test_audience_discovery_rejects_unbounded_and_malformed_claims(self):
        def jwt(aud):
            payload = base64.urlsafe_b64encode(json.dumps({'aud': aud}).encode()).decode().rstrip('=')
            return 'header.' + payload + '.signature'
        self.assertEqual(['api'], auth.jwt_audiences(jwt('api')))
        for aud in ([], ['same', 'same'], ['a'] * 5, None, ['a', 12]):
            with self.assertRaises((host.custody.BootstrapFailed, TypeError)):
                auth.jwt_audiences(jwt(aud))

    def test_resource_conflict_preserves_original_without_write(self):
        role = next(o for o in auth.foundation() if o['kind'] == 'ClusterRole')
        with patch.object(target.host, 'kube', return_value=json.dumps(role).encode()) as kube:
            target.reconcile(role)
            self.assertEqual(1, kube.call_count)
        hostile = json.loads(json.dumps(role))
        hostile['rules'][0]['verbs'].append('*')
        with patch.object(target.host, 'kube', return_value=json.dumps(hostile).encode()) as kube, \
                self.assertRaises(host.custody.BootstrapFailed):
            target.reconcile(role)
        self.assertEqual(1, kube.call_count)
        with patch.object(target.host, 'kube', side_effect=[b'', b'']) as kube:
            target.reconcile(role)
        self.assertEqual('create', kube.call_args.args[0])

    def test_api_keeps_error_diagnostics_private_and_bounds_requests(self):
        opener = Mock()
        opener.open.side_effect = HTTPError('https://loopback', 403, 'private-diagnostic', {},
                                           io.BytesIO(b'private-token-never-display'))
        api = target.API(Mock(base='https://127.0.0.1:1234/v1/', opener=opener))
        self.assertEqual({}, api.call('sys/mounts', token='secret-fixture', expected=403))
        request = opener.open.call_args.args[0]
        self.assertEqual('secret-fixture', request.get_header('X-vault-token'))
        self.assertEqual(10, opener.open.call_args.kwargs['timeout'])
        with self.assertRaises(host.custody.BootstrapFailed) as failure:
            api.call('sys/mounts', token='secret-fixture')
        self.assertNotIn('private', str(failure.exception))
        with self.assertRaises(host.custody.BootstrapFailed):
            api.call('sys/mounts', 'POST', {'value': 'x' * host.BOUND})

    def test_supervisor_hash_binding_and_unknown_entrypoint_denial(self):
        script = operator.bootstrap('/home/hooshixadmin/.cache/public', 'a' * 40,
            [('bootstrap_openbao_auth.py', 'b' * 64)], 'bootstrap_openbao_auth')
        self.assertIn("sys.modules['bootstrap_openbao_auth'].main", script)
        self.assertIn('hashlib.sha256(content).hexdigest()!=digest', script)
        with self.assertRaises(host.custody.BootstrapFailed):
            operator.bootstrap('/tmp/public', 'a' * 40, [], 'unknown')

    def test_namespace_security_conflict_does_not_overwrite(self):
        namespace = auth.foundation()[0]
        hostile = json.loads(json.dumps(namespace))
        hostile['metadata']['labels']['pod-security.kubernetes.io/enforce'] = 'privileged'
        with patch.object(target.host, 'kube', return_value=json.dumps(hostile).encode()) as kube, \
                self.assertRaises(host.custody.BootstrapFailed):
            target.reconcile(namespace)
        self.assertEqual(1, kube.call_count)

    def test_token_request_has_no_token_or_static_secret_in_argv(self):
        with patch.object(target.host, 'kube', return_value=b'opaque-jwt') as kube:
            self.assertEqual('opaque-jwt', target.token_request(auth.NAMESPACE, 'eso-web-bff', ['api', auth.AUDIENCE]))
        self.assertIn('--duration=10m', kube.call_args.args)
        self.assertIn('--audience=hooshix-openbao', kube.call_args.args)
        self.assertNotIn('opaque-jwt', str(kube.call_args))

    def test_existing_foreign_mount_is_not_adopted_or_overwritten(self):
        api = Mock()
        api.call.return_value = {'data': {'hooshix/': {'type': 'kv', 'options': {'version': '2'},
                                                     'description': 'foreign'}}}
        with self.assertRaises(host.custody.BootstrapFailed):
            auth.configure(api, 'root-fixture', 'https://api', '-----BEGIN CERTIFICATE-----\nfixture')
        self.assertEqual(1, api.call.call_count)

    def test_static_reviewer_or_configuration_drift_stops_before_policy_mutation(self):
        certificate = '-----BEGIN CERTIFICATE-----\nfixture'
        for difference in ({'token_reviewer_jwt_set': True}, {'disable_local_ca_jwt': False},
                           {'pem_keys': ['unapproved-key']}, {'issuer': 'unapproved-issuer'}):
            api = Mock()
            api.call.side_effect = [
                {'data': {'hooshix/': {'type': 'kv', 'options': {'version': '2'}, 'description': auth.DESCRIPTION}}},
                {'data': {auth.MOUNT + '/': {'type': 'kubernetes', 'description': auth.DESCRIPTION}}},
                {'data': {'kubernetes_host': 'https://api', 'kubernetes_ca_cert': certificate,
                          'disable_local_ca_jwt': True, 'disable_iss_validation': True, **difference}}]
            with self.assertRaises(host.custody.BootstrapFailed):
                auth.configure(api, 'root-fixture', 'https://api', certificate)
            self.assertEqual(3, api.call.call_count)
            self.assertTrue(all(call.kwargs.get('token') == 'root-fixture' for call in api.call.call_args_list))

    def test_unexpected_login_scope_still_revokes_the_test_token(self):
        api = Mock()
        api.call.return_value = {'auth': {'client_token': 'fixture-short-lived-token',
                                        'policies': ['default'], 'lease_duration': 300}}
        with self.assertRaises(host.custody.BootstrapFailed):
            auth.verify(api, 'fixture-root-token', Mock(return_value='fixture-jwt'), ['api'])
        self.assertEqual('auth/token/revoke', api.call.call_args.args[0])
        self.assertEqual({'token': 'fixture-short-lived-token'}, api.call.call_args.args[2])

    def test_network_api_omitted_empty_arrays_are_not_policy_drift(self):
        policy = next(o for o in auth.foundation() if o['kind'] == 'NetworkPolicy')
        old = json.loads(json.dumps(policy))
        old['spec'].pop('ingress')
        old['spec'].pop('egress')
        with patch.object(target.host, 'kube', return_value=json.dumps(old).encode()) as kube:
            target.reconcile(policy)
        self.assertEqual(1, kube.call_count)
        old['spec']['egress'] = [{}]
        with patch.object(target.host, 'kube', return_value=json.dumps(old).encode()), \
                self.assertRaises(host.custody.BootstrapFailed):
            target.reconcile(policy)


if __name__ == '__main__':
    unittest.main()
