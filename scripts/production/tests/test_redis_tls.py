"""Real synthetic certificate checks and no-write failure/reentry boundaries."""
import copy
import base64
import hashlib
import json
import ssl
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bootstrap_redis_tls as bootstrap
import install_redis_tls as target
import redis_secret_delivery as contract
import redis_tls_material as material
import rehearse_redis_tls as rehearsal
import build_redis_secrets_bundle as bundle
import test_redis_secrets as previous_tests


class RedisTLSTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Synthetic fixtures may run on WSL with swap; target enforcement remains fail-closed.
        read_text = material.Path.read_text
        cls.synthetic_swap = patch.object(material.Path, 'read_text',
            lambda path, *args, **kwargs: 'Filename Type Size Used Priority\n' if str(path) == '/proc/swaps'
                else read_text(path, *args, **kwargs))
        cls.synthetic_swap.start()
        cls.addClassCleanup(cls.synthetic_swap.stop)
        cls.ram = material.ram_work()
        cls.work = cls.ram.__enter__()
        cls.addClassCleanup(cls.ram.__exit__, None, None, None)
        material.platform.native([material.custody.OPENSSL, 'req', '-x509', '-newkey', 'rsa:3072', '-nodes',
            '-days', '365', '-subj', '/CN=synthetic-test-root', '-keyout', str(cls.work / 'root.key'),
            '-out', str(cls.work / 'root.crt')], timeout=60)
        cls.root = (cls.work / 'root.crt').read_text()
        cls.issuer_fixture = rehearsal.fixture_issuer(cls.root, cls.work / 'root.key')
        cls.issuer, cls.key, cls.password = cls.issuer_fixture.__enter__()
        cls.addClassCleanup(cls.issuer_fixture.__exit__, None, None, None)
        cls.value = material.issue(cls.root, cls.issuer, cls.key, cls.password)

    def test_real_encrypted_issuer_chain_names_and_server_only_leaf(self):
        material.validate(self.value, self.root, self.issuer)
        cert = self.work / 'inspect.crt'
        material.custody.create(cert, self.value['tls.crt'].encode())
        decoded = ssl._ssl._test_decode_cert(str(cert))
        self.assertEqual({('DNS', name) for name in material.DNS_NAMES} | {('IP Address', '127.0.0.1')},
                         set(decoded['subjectAltName']))
        self.assertEqual(30 * 86400, ssl.cert_time_to_seconds(decoded['notAfter']) -
                         ssl.cert_time_to_seconds(decoded['notBefore']))

    def test_wrong_password_does_not_issue_or_change_original_issuer(self):
        before = hashlib.sha256(self.key.read_bytes()).hexdigest()
        with self.assertRaises(material.custody.BootstrapFailed):
            material.issue(self.root, self.issuer, self.key, b'wrong-synthetic-password')
        self.assertEqual(before, hashlib.sha256(self.key.read_bytes()).hexdigest())

    def test_extra_san_client_auth_weak_rsa_and_short_expiry_are_rejected(self):
        ordinary = material.platform.native
        for mode in ('extra-san', 'client-auth', 'code-signing', 'weak-rsa', 'weak-signature', 'expiry'):
            def native(argv, **options):
                argv = list(argv)
                if mode == 'weak-rsa' and 'req' in argv:
                    argv[argv.index('rsa:3072')] = 'rsa:2048'
                if mode == 'expiry' and 'x509' in argv and '-days' in argv:
                    argv[argv.index('-days') + 1] = '1'
                if mode == 'weak-signature' and 'x509' in argv and '-sha256' in argv:
                    argv[argv.index('-sha256')] = '-sha1'
                return ordinary(argv, **options)
            extensions = material.EXTENSIONS
            if mode == 'extra-san': extensions = extensions.replace(b'IP:127.0.0.1', b'DNS:*.platform-data.svc,IP:127.0.0.1')
            if mode == 'client-auth': extensions = extensions.replace(b'serverAuth', b'serverAuth,clientAuth')
            if mode == 'code-signing': extensions = extensions.replace(b'serverAuth', b'serverAuth,codeSigning')
            with self.subTest(mode=mode), patch.object(material, 'EXTENSIONS', extensions), \
                    patch.object(material.platform, 'native', side_effect=native), \
                    self.assertRaises(material.custody.BootstrapFailed):
                material.issue(self.root, self.issuer, self.key, self.password)

    def test_malformed_key_chain_ca_and_extra_fields_are_rejected(self):
        for changes in ({'tls.key': 'not a key'}, {'ca.crt': self.issuer},
                {'tls.crt': self.root}, {'extra': 'not allowed'}):
            with self.subTest(fields=list(changes)), self.assertRaises(material.custody.BootstrapFailed):
                material.validate(self.value | changes, self.root, self.issuer)

    def test_existing_issuer_reads_only_public_kubernetes_fields(self):
        key = self.work / 'ca-key.enc.pem'
        material.custody.create(key, self.key.read_bytes())
        material.platform.native([material.custody.OPENSSL, 'req', '-new', '-key', str(key),
            '-subj', '/CN=synthetic-redis-intermediate', '-out', str(self.work / 'cluster-intermediate.csr.pem')],
            password=self.password)
        def kube(*args):
            self.assertEqual(('-n', 'istio-system', 'get', 'secret', 'cacerts', '-o'), args[:-1])
            field = args[-1]
            if field == 'jsonpath={.metadata}':
                return json.dumps({'labels': material.platform.ca_import.LABELS}).encode()
            fields = {'jsonpath={.data.root-cert\\.pem}': self.root,
                      'jsonpath={.data.ca-cert\\.pem}': self.issuer}
            self.assertIn(field, fields)  # No full Secret or ca-key.pem read is permitted.
            return base64.b64encode(fields[field].encode())
        with patch.object(material.custody, 'ROOT_SHA256', hashlib.sha256(self.root.encode()).hexdigest()), \
                patch.object(material.custody, 'STATE', self.work), patch.object(material.custody, 'protected'):
            self.assertEqual((self.issuer, key), material.existing_issuer(kube, self.root))

    def test_fixture_swap_substitution_cannot_use_the_production_root(self):
        with patch.object(material.custody, 'ROOT_SHA256', hashlib.sha256(self.root.encode()).hexdigest()):
            with self.assertRaises(material.custody.BootstrapFailed), rehearsal.synthetic_memory(self.root): pass

    def test_plaintext_work_requires_tmpfs_and_no_swap(self):
        with patch.object(material.platform, 'native', return_value=b'ext4'):
            with self.assertRaises(material.custody.BootstrapFailed), material.ram_work(): pass
        with patch.object(material.Path, 'read_text', return_value='header\n/dev/swap file 1 0 -2\n'):
            with self.assertRaises(material.custody.BootstrapFailed), material.ram_work(): pass

    def test_exact_new_identity_without_extending_old_six_bindings(self):
        old = contract.foundation()
        new = contract.foundation(tls=True)
        self.assertEqual(6, len(next(o for o in old if o['kind'] == 'Role')['rules'][0]['resourceNames']))
        self.assertEqual(['eso-redis-tls'], next(o for o in new if o['kind'] == 'Role')['rules'][0]['resourceNames'])
        self.assertFalse(any(o['kind'] in ('Deployment', 'Secret', 'ClusterRole', 'NetworkPolicy') for o in new))
        self.assertNotIn('*', contract.policy(contract.TLS_NAME))
        self.assertEqual(['platform-data'], contract.role(contract.TLS_NAME)['bound_service_account_namespaces'])
        external = contract.external(contract.TLS_NAME)
        self.assertEqual('kubernetes.io/tls', external['spec']['target']['template']['type'])
        self.assertEqual(set(material.FIELDS), {d['secretKey'] for d in external['spec']['data']})

    def fixture(self):
        api, plan, _, creates, kube, state = previous_tests.RedisSecretTest().fixture()
        with patch.object(target.previous.pg, 'webhook'), patch.object(target.previous, 'verify_auth'):
            target.previous.execute(plan, kube=kube, api=api, root='synthetic-root', ca='synthetic-ca',
                                    audiences=['api'], token_request=Mock())
        for (kind, name), obj in state.items():
            obj['metadata'].setdefault('uid', 'fixture-' + name)
        plan['redis_tls_delivery'] = True
        plan['public_ca_sha256'] = hashlib.sha256(self.root.encode()).hexdigest()
        return api, plan, creates, kube, state

    def execute(self, api, plan, kube, issue):
        with patch.object(target.previous.pg, 'webhook'), patch.object(target.previous, 'verify_auth'):
            return target.execute(plan, kube=kube, api=api, root='synthetic-root', ca=self.root,
                issuer=self.issuer, audiences=['api'], token_request=Mock(), issue=issue)

    def test_first_delivery_and_no_write_reentry_preserve_passwords_acl_and_data(self):
        api, plan, creates, kube, _ = self.fixture()
        previous = copy.deepcopy(api.values)
        issuer = Mock(return_value=copy.deepcopy(self.value))
        first = self.execute(api, plan, kube, issuer)
        self.assertEqual('Passed', first['tls_delivery'])
        writes, count = len(api.writes), len(creates)
        self.assertEqual(first, self.execute(api, plan, kube, Mock(side_effect=AssertionError('must not reissue'))))
        self.assertEqual((writes, count), (len(api.writes), len(creates)))
        self.assertEqual(previous, {p: api.values[p] for p in previous})
        self.assertEqual(set(material.FIELDS) | {'managed_by', 'installation_id'},
                         set(api.values['hooshix/data/' + target.ENTRY['key']]))
        issuer.assert_called_once()

    def test_missing_previous_credential_foreign_target_or_deleted_history_prevent_all_writes(self):
        for scenario in ('credential', 'foreign-target', 'deleted-history', 'bad-leaf'):
            api, plan, creates, kube, state = self.fixture()
            path = 'hooshix/data/' + target.ENTRY['key']
            if scenario == 'credential': del api.values['hooshix/data/' + next(iter(contract.ENTRIES.values()))['key']]
            elif scenario == 'foreign-target':
                state[('secret', contract.TLS_NAME)] = {'metadata': {'name': contract.TLS_NAME, 'uid': 'foreign'}}
            elif scenario == 'deleted-history': api.history.add(path.replace('/data/', '/metadata/'))
            else: api.values[path] = self.value | {'tls.key': 'invalid', 'managed_by': contract.MANAGER,
                                                  'installation_id': 'hooshix-production'}
            before = copy.deepcopy(api.values), len(api.writes), len(creates)
            issuer = Mock(side_effect=AssertionError('must not issue'))
            with self.subTest(scenario=scenario), self.assertRaises(material.custody.BootstrapFailed):
                self.execute(api, plan, kube, issuer)
            self.assertEqual(before, (api.values, len(api.writes), len(creates)))
            issuer.assert_not_called()

    def test_partial_kv_commit_reentry_reuses_exact_material_without_new_key(self):
        api, plan, creates, kube, _ = self.fixture()
        issuer = Mock(return_value=copy.deepcopy(self.value))
        ordinary = api.call
        def fail_auth(path, method='GET', *args, **kwargs):
            if method != 'GET' and path.startswith('sys/policies/'):
                raise ValueError('synthetic interrupted permission creation')
            return ordinary(path, method, *args, **kwargs)
        api.call = fail_auth
        with self.assertRaises(ValueError): self.execute(api, plan, kube, issuer)
        committed = copy.deepcopy(api.values)
        api.call = ordinary
        self.execute(api, plan, kube, Mock(side_effect=AssertionError('must not reissue')))
        self.assertEqual(committed, api.values)
        issuer.assert_called_once()

    def test_supervisor_rejects_missing_issuer_passphrase_before_target_access(self):
        with patch.object(bootstrap.previous.pg, 'commission') as commission:
            for password in (None, '', 'too-short', 'x' * 129, 'x' * 20 + '\n'):
                with self.assertRaises(material.custody.BootstrapFailed):
                    bootstrap.commission({'intermediate_passphrase': password}, 'a' * 40)
            commission.assert_not_called()

    def test_tls_bundle_requires_authenticated_complete_tls_evidence(self):
        previous = {'schema_version': 1, 'namespace': 'platform-data', 'credential_count': 5,
            'public_access': 'CLOSED', 'existing_data_controller_policy_storage': 'Preserved',
            **{key: 'Passed' for key in bundle.CHECKS},
            **{key: 'Not run' for key in ('redis', 'tls', 'database', 'provider_backup', 'root_revocation')}}
        evidence = {'schema_version': 1, 'namespace': 'platform-data', 'public_access': 'CLOSED',
            'tls_lifetime_days': 30, 'existing_credentials_acl_controller_policy_storage': 'Preserved',
            **{k: 'Passed' for k in ('tls_delivery', 'server_chain_key_hostname', 'scoped_authentication',
                'idempotent_reconciliation', 'container_log_privacy', 'existing_identity_tls_denials',
                'bound_database_preservation')},
            **{k: 'Not run' for k in ('redis', 'database', 'automatic_renewal', 'provider_backup', 'root_revocation')}}
        receipt = {'redis_secret_delivery': previous, 'redis_tls_delivery': evidence}
        bundle.verify_proof(receipt, tls=True)
        for key in evidence:
            with self.subTest(key=key), self.assertRaises(ValueError):
                bundle.verify_proof(receipt | {'redis_tls_delivery': evidence | {key: 'Not verified'}}, tls=True)
        with self.assertRaises(ValueError): bundle.verify_proof({'redis_secret_delivery': previous}, tls=True)
        self.assertNotIn('rehearse_redis_tls.py', bundle.SOURCES)


if __name__ == '__main__': unittest.main()
