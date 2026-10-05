import base64
import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bootstrap_intermediate_csr as custody
import import_intermediate_ca as target


class AuditPolicyTest(unittest.TestCase):
    policy = b'apiVersion: audit.k8s.io/v1\nkind: Policy\nomitStages: [RequestReceived]\nrules:\n- level: Metadata\n'
    original_preflight = staticmethod(target.audit_policy_preflight)

    def preflight(self, configs, policy=None, package=None):
        default = '/etc/rancher/k3s/config.yaml'
        dropins = '/etc/rancher/k3s/config.yaml.d'
        files = {default: configs[0]}
        files.update({dropins + '/' + name: content for name, content in configs[1:]})
        files['/etc/rancher/k3s/audit.yaml'] = self.policy if policy is None else policy
        with patch.object(custody, 'native', side_effect=[b'123\n',
                         (target.YAML_PACKAGE if package is None else package).encode()]), \
                patch.object(Path, 'read_bytes', side_effect=[b'k3s\0server\0', b'PATH=/usr/bin']), \
                patch.object(Path, 'exists', lambda p: str(p) == default
                             or (str(p) == dropins and len(configs) > 1)), \
                patch.object(Path, 'is_symlink', return_value=False), \
                patch.object(Path, 'glob', return_value=[Path(dropins + '/' + name)
                             for name, _ in reversed(configs[1:])]), \
                patch.object(custody, 'protected'), \
                patch.object(target, 'audit_file', side_effect=lambda p: target.audit_yaml(files[str(p)])):
            self.original_preflight()

    def test_existing_global_metadata_policy_is_supported(self):
        target.metadata_policy(target.audit_yaml(self.policy))
        self.preflight([b'kube-apiserver-arg:\n- audit-policy-file=/etc/rancher/k3s/audit.yaml\n- audit-log-maxbackup=10\n'])

    def test_dropin_replace_and_append_in_sorted_order(self):
        self.preflight([b'kube-apiserver-arg: [audit-policy-file=/unreviewed]\n',
                        ('20-append.yaml', b'kube-apiserver-arg+: [audit-log-maxbackup=10]\n'),
                        ('10-replace.yaml', b'kube-apiserver-arg: [audit-policy-file=/etc/rancher/k3s/audit.yaml]\n')])

    def test_unsafe_yaml_shapes_bounds_and_parser_drift_rejected(self):
        for content in (b'kind: Policy\nkind: Other\n', b'a: &x [1]\nb: *x\n',
                        b'a: !!python/object/apply:os.system [echo]\n', b'1: value\n',
                        b'a: x\n---\nb: y\n', b'[]', b'a: [' + b'[' * 17 + b']' * 17 + b']',
                        b'a: [' + b'0,' * 600 + b']', b'a: ' + b'x' * 32768):
            with self.subTest(content=content[:60]), self.assertRaises(custody.BootstrapFailed):
                target.audit_yaml(content)
        import yaml
        with patch.object(yaml, '__version__', 'unreviewed'), self.assertRaises(custody.BootstrapFailed):
            target.audit_yaml(self.policy)
        with self.assertRaisesRegex(custody.BootstrapFailed, 'PARSER_REVIEW_REQUIRED'):
            self.preflight([b'kube-apiserver-arg: []\n'], package='unreviewed')

    def test_body_logging_selectors_and_missing_completion_audit_rejected(self):
        safe = target.audit_yaml(self.policy)
        for policy in (safe | {'rules': [{'level': level}]} for level in ('None', 'Request', 'RequestResponse')):
            with self.subTest(policy=policy), self.assertRaises(custody.BootstrapFailed):
                target.metadata_policy(policy)
        for change in ({'rules': [{'level': 'Metadata', 'users': ['operator']}]},
                       {'rules': [{'level': 'Metadata'}, {'level': 'Request'}]},
                       {'omitStages': ['ResponseComplete']}, {'omitStages': ['Panic']},
                       {'omitStages': 'RequestReceived'}, {'omitManagedFields': 'yes'},
                       {'unknown': True}, {'kind': 'Other'}):
            with self.subTest(change=change), self.assertRaises(custody.BootstrapFailed):
                target.metadata_policy(safe | change)

    def test_ambiguous_args_and_verbose_body_diagnostics_rejected(self):
        for args in ('[audit-policy-file=x, audit-policy-file=y]', '[audit-policy-file]',
                     '[audit-policy-file x]', '[v=9]', '[--vmodule=x=9]', '[7]', 'null'):
            with self.subTest(args=args), self.assertRaises(custody.BootstrapFailed):
                self.preflight([('kube-apiserver-arg: ' + args + '\n').encode()])
        with self.assertRaises(custody.BootstrapFailed):
            self.preflight([b'kube-apiserver-arg: []\nkube-apiserver-arg+: []\n'])

    def test_unsafe_policy_blocks_before_password_and_secret_write(self):
        with patch.object(custody, 'preflight'), \
                patch.object(target, 'audit_policy_preflight', side_effect=lambda: self.preflight(
                    [b'kube-apiserver-arg: [audit-policy-file=/etc/rancher/k3s/audit.yaml]\n'],
                    self.policy.replace(b'Metadata', b'RequestResponse'))), \
                patch.object(target.getpass, 'getpass') as password, \
                patch.object(target, 'ensure_secret') as secret:
            with self.assertRaisesRegex(custody.BootstrapFailed, 'NOT_METADATA_ONLY'):
                target.execute('a' * 40, Path('/home/hooshixadmin/.cache/hooshix-ca-import-' + 'a' * 32 + '/public'))
            password.assert_not_called()
            secret.assert_not_called()

    def test_policy_file_requires_absolute_root_protected_path(self):
        with self.assertRaisesRegex(custody.BootstrapFailed, 'PATH_REJECTED'):
            target.audit_file(Path('relative.yaml'))
        with tempfile.TemporaryDirectory() as name:
            path = Path(name) / 'policy.yaml'
            path.write_bytes(self.policy)
            with patch.object(custody, 'protected') as protected:
                self.assertEqual('Policy', target.audit_file(path)['kind'])
                self.assertEqual(len(path.parents) + 1, protected.call_count)
            path.unlink()
            path.symlink_to(Path(name) / 'missing')
            with patch.object(custody, 'protected'), self.assertRaises(OSError):
                target.audit_file(path)


class IntermediateImportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = tempfile.TemporaryDirectory()
        cls.state = Path(cls.fixture.name)
        cls.password = 'synthetic-existing-intermediate-passphrase'
        cls.private = custody.native([custody.OPENSSL, 'genpkey', '-algorithm', 'RSA',
                                      '-pkeyopt', 'rsa_keygen_bits:4096'], timeout=120)
        encrypted = custody.native([custody.OPENSSL, 'pkcs8', '-topk8', '-v2', 'aes-256-cbc',
                                    '-iter', '1000000'], input_bytes=cls.private,
                                   passphrase=cls.password.encode())
        custody.create(cls.state / 'ca-key.enc.pem', encrypted)
        custody.create(cls.state / 'bootstrap.lock', b'')
        custody.create(cls.state / 'cluster-intermediate.csr.pem', custody.native([
            custody.OPENSSL, 'req', '-new', '-key', '/dev/stdin', '-subj', custody.SUBJECT], input_bytes=cls.private))
        root_key = custody.native([custody.OPENSSL, 'genpkey', '-algorithm', 'RSA',
                                   '-pkeyopt', 'rsa_keygen_bits:2048'])
        custody.create(cls.state / 'root-fixture.key', root_key)
        root = custody.native([custody.OPENSSL, 'req', '-new', '-x509', '-days', '730',
                               '-subj', '/CN=Synthetic disposable Root', '-key',
                               str(cls.state / 'root-fixture.key'), '-addext',
                               'basicConstraints=critical,CA:TRUE,pathlen:1', '-addext',
                               'keyUsage=critical,keyCertSign,cRLSign'])
        custody.create(cls.state / 'root-fixture.pem', root)
        cls.root = root
        cls.cert = cls.sign()
        cls.marker = {'csr_sha256': target.sha((cls.state / 'cluster-intermediate.csr.pem').read_bytes())}

    @classmethod
    def tearDownClass(cls):
        cls.fixture.cleanup()

    @classmethod
    def sign(cls, constraints='CA:TRUE,pathlen:0', days=365):
        with tempfile.TemporaryDirectory() as name:
            ext = Path(name) / 'extensions'
            custody.create(ext, ('basicConstraints=critical,' + constraints
                           + '\nkeyUsage=critical,keyCertSign,cRLSign\n').encode())
            return custody.native([custody.OPENSSL, 'x509', '-req', '-in',
                str(cls.state / 'cluster-intermediate.csr.pem'), '-CA',
                str(cls.state / 'root-fixture.pem'), '-CAkey', str(cls.state / 'root-fixture.key'),
                '-set_serial', '17', '-days', str(days), '-extfile', str(ext)])

    def files(self, cert=None):
        cert = cert if cert is not None else self.cert
        receipt = {'installation_id': 'hooshix-production', 'schema_version': 1,
                   'intermediate_certificate_sha256': target.sha(cert).upper(),
                   'root_certificate_sha256': target.sha(self.root).upper(),
                   'source_revision': 'd' * 40, 'chain_verification': 'Passed',
                   'csr_sha256': self.marker['csr_sha256'], 'production_readiness': 'Not verified'}
        return {'ca-cert.pem': cert, 'root-cert.pem': self.root,
                'cert-chain.pem': cert + self.root,
                'signing-receipt.json': b'\xef\xbb\xbf' + json.dumps(receipt).encode()}

    def validate(self, files):
        with tempfile.TemporaryDirectory() as name, patch.object(custody, 'STATE', self.state), \
                patch.object(custody, 'ROOT_SHA256', target.sha(self.root)):
            return target.validate_public(files, self.marker, Path(name))

    def test_real_chain_and_bom_receipt_match_existing_encrypted_key(self):
        public = self.validate(self.files())
        self.assertEqual(public, custody.native([custody.OPENSSL, 'pkey', '-pubout'],
                                                input_bytes=self.private))
        decrypted = custody.native([custody.OPENSSL, 'pkey', '-in', str(self.state / 'ca-key.enc.pem'),
                                    '-passin', 'stdin'], input_bytes=self.password.encode() + b'\n')
        self.assertEqual(self.private, decrypted)

    def test_chain_order_extra_material_and_hash_tampering_rejected(self):
        for name, content in (('cert-chain.pem', self.root + self.cert),
                              ('cert-chain.pem', self.cert + self.root + self.root),
                              ('cert-chain.pem', self.cert + self.root + b'PRIVATE KEY'),
                              ('root-cert.pem', self.root + b'\n'),
                              ('ca-cert.pem', self.cert + b'\n')):
            with self.subTest(name=name), self.assertRaises(custody.BootstrapFailed):
                self.validate(self.files() | {name: content})

    def test_path_length_ca_and_lifetime_fail_closed(self):
        for constraints, days in (('CA:TRUE,pathlen:1', 365), ('CA:FALSE', 365),
                                  ('CA:TRUE,pathlen:0', 1), ('CA:TRUE,pathlen:0', 500)):
            with self.subTest(constraints=constraints, days=days), self.assertRaises(custody.BootstrapFailed):
                self.validate(self.files(self.sign(constraints, days)))

    def test_csr_or_signing_receipt_conflict_preserved(self):
        files = self.files()
        receipt = json.loads(files['signing-receipt.json'].decode('utf-8-sig'))
        for field, value in (('csr_sha256', '0' * 64), ('installation_id', 'another-cluster'),
                              ('chain_verification', 'Not verified'), ('schema_version', 2)):
            with self.subTest(field=field), self.assertRaises(custody.BootstrapFailed):
                self.validate(files | {'signing-receipt.json': json.dumps(receipt | {field: value}).encode()})
        with patch.object(custody, 'native', wraps=custody.native) as native:
            self.validate(files)
            self.assertFalse(any('genpkey' in call.args[0] for call in native.call_args_list))

    def test_public_input_is_bounded_and_no_links_fifo_or_extra_files(self):
        for mode in ('extra', 'symlink', 'hardlink', 'oversize', 'fifo'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as name:
                folder = Path(name)
                for key, content in self.files().items():
                    custody.create(folder / key, content)
                cert = folder / 'ca-cert.pem'
                if mode == 'extra':
                    custody.create(folder / 'unexpected', b'x')
                else:
                    cert.unlink()
                    if mode == 'symlink':
                        cert.symlink_to(folder / 'root-cert.pem')
                    elif mode == 'hardlink':
                        os.link(folder / 'root-cert.pem', cert)
                    elif mode == 'oversize':
                        custody.create(cert, b'x' * 32769)
                    else:
                        os.mkfifo(cert)
                with self.assertRaises((custody.BootstrapFailed, OSError)):
                    target.read_public(folder)

    def test_create_once_reconcile_and_mismatch_no_overwrite(self):
        data = {'ca-cert.pem': self.cert, 'ca-key.pem': self.private,
                'root-cert.pem': self.root, 'cert-chain.pem': self.cert + self.root}
        expected = {'type': 'Opaque', 'metadata': {'labels': target.LABELS},
                    'data': {k: base64.b64encode(v).decode() for k, v in data.items()}}
        for exists in (False, True):
            values = [b'{}', json.dumps(expected).encode()] if exists else [b'{}', b'', b'secret/cacerts', json.dumps(expected).encode()]
            with patch.object(target, 'kube', side_effect=values) as kube:
                target.ensure_secret(data)
                self.assertEqual(0 if exists else 1, sum('create' in call.args for call in kube.call_args_list))
                self.assertFalse(any('apply' in call.args or 'patch' in call.args for call in kube.call_args_list))
        expected['data']['ca-key.pem'] = 'different-existing-secret'
        with patch.object(target, 'kube', side_effect=[b'{}', json.dumps(expected).encode()]) as kube:
            with self.assertRaisesRegex(custody.BootstrapFailed, 'CONFLICT_PRESERVED'):
                target.ensure_secret(data)
            self.assertEqual(2, kube.call_count)

    def test_ambiguous_create_is_not_retried(self):
        with patch.object(target, 'kube', side_effect=[b'{}', b'', TimeoutError('private diagnostic')]) as kube:
            with self.assertRaises(TimeoutError):
                target.ensure_secret({'ca-key.pem': self.private})
            self.assertEqual(3, kube.call_count)

    def test_wrong_password_and_main_sanitizes_failure(self):
        with self.assertRaisesRegex(custody.BootstrapFailed, '^NATIVE_OPERATION_FAILED$'):
            custody.native([custody.OPENSSL, 'pkey', '-in', str(self.state / 'ca-key.enc.pem'),
                            '-passin', 'stdin'], input_bytes=b'wrong-secret-password\n')
        output = io.StringIO()
        with patch.object(sys, 'argv', ['import', '--reviewed-commit', 'a' * 40,
                                      '--public-directory', '/no-input']), \
                patch.object(target, 'execute', side_effect=RuntimeError(self.password)), redirect_stdout(output):
            self.assertEqual(1, target.main())
        self.assertNotIn(self.password, output.getvalue())
        self.assertNotIn('PRIVATE KEY', output.getvalue())
        self.assertEqual('CA_IMPORT_FAILED', json.loads(output.getvalue())['reason'])

    def test_successful_import_uses_existing_key_memory_only_and_public_receipt(self):
        with patch.object(custody, 'STATE', self.state), patch.object(custody, 'BASE', self.state), \
                patch.object(custody, 'ROOT_SHA256', target.sha(self.root)), \
                patch.object(custody, 'preflight'), patch.object(custody, 'protected'), \
                patch.object(custody, 'public_receipt', return_value=self.marker), \
                patch.object(target, 'audit_policy_preflight'), \
                patch.object(target, 'read_public', return_value=self.files()), \
                patch.object(target, 'open', return_value=io.BytesIO(), create=True), \
                patch.object(target.getpass, 'getpass', return_value=self.password), \
                patch.object(target, 'ensure_secret') as secret:
            result = target.execute('a' * 40, Path('/home/hooshixadmin/.cache/hooshix-ca-import-' + 'a' * 32 + '/public'))
        self.assertEqual(self.private, secret.call_args.args[0]['ca-key.pem'])
        self.assertEqual('Passed', result['ca_secret'])
        self.assertEqual('Not run', result['openbao_installation'])
        self.assertNotIn('PRIVATE KEY', json.dumps(result))
        self.assertNotIn(self.password, json.dumps(result))
        self.assertFalse((self.state / 'ca-key.pem').exists())

    def test_custom_api_audit_policy_requires_review(self):
        for command, environment, accepted in ((b'k3s\0server\0', b'PATH=x', True),
                (b'k3s --audit-policy-file=x', b'', False),
                (b'k3s --config custom.yaml', b'', False),
                (b'k3s\0server\0-c\0custom.yaml', b'', False),
                (b'k3s', b'K3S_CONFIG_FILE=x', False),
                (b'k3s', b'K3S_KUBE_APISERVER_ARG=audit-policy-file=x', False)):
            with self.subTest(command=command, environment=environment), \
                    patch.object(custody, 'native', return_value=b'123\n'), \
                    patch.object(Path, 'read_bytes', side_effect=[command, environment]), \
                    patch.object(Path, 'exists', return_value=False), \
                    patch.object(Path, 'is_symlink', return_value=False):
                if accepted:
                    target.audit_policy_preflight()
                else:
                    with self.assertRaisesRegex(custody.BootstrapFailed, 'API_AUDIT_CONFIGURATION'):
                        target.audit_policy_preflight()

    def test_failed_import_password_never_reaches_secret_write(self):
        with patch.object(custody, 'STATE', self.state), patch.object(custody, 'BASE', self.state), \
                patch.object(custody, 'ROOT_SHA256', target.sha(self.root)), \
                patch.object(custody, 'preflight'), patch.object(custody, 'protected'), \
                patch.object(custody, 'public_receipt', return_value=self.marker), \
                patch.object(target, 'audit_policy_preflight'), \
                patch.object(target, 'read_public', return_value=self.files()), \
                patch.object(target, 'open', return_value=io.BytesIO(), create=True), \
                patch.object(target.getpass, 'getpass', return_value='wrong-password-never-output'), \
                patch.object(target, 'ensure_secret') as secret:
            with self.assertRaisesRegex(custody.BootstrapFailed, '^NATIVE_OPERATION_FAILED$'):
                target.execute('a' * 40, Path('/home/hooshixadmin/.cache/hooshix-ca-import-' + 'a' * 32 + '/public'))
            secret.assert_not_called()


if __name__ == '__main__':
    unittest.main()
