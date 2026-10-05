import hashlib
import io
import json
import os
import stat
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bootstrap_intermediate_csr as target


class IntermediateCsrTest(unittest.TestCase):
    def test_native_diagnostics_and_password_never_leave_error_boundary(self):
        calls = []
        def failed(argv, **kwargs):
            calls.append((argv, kwargs))
            descriptor = kwargs['pass_fds'][0]
            self.assertEqual(b'never-output-this-password\n', os.read(descriptor, 1024))
            return SimpleNamespace(returncode=1, stdout=b'', stderr=b'private diagnostic')
        with patch.object(target.subprocess, 'run', side_effect=failed):
            with self.assertRaisesRegex(target.BootstrapFailed, '^NATIVE_OPERATION_FAILED$'):
                target.native(['openssl', 'pkcs8'], passphrase=b'never-output-this-password')
        self.assertNotIn('never-output-this-password', repr(calls))
        self.assertEqual(target.ENV, calls[0][1]['env'])
        with self.assertRaises(OSError):
            os.fstat(calls[0][1]['pass_fds'][0])

    def test_main_unknown_failures_are_sanitized(self):
        output = io.StringIO()
        with patch.object(sys, 'argv', ['csr', '--reviewed-commit', 'a' * 40]), \
                patch.object(target, 'execute', side_effect=RuntimeError('secret detail')), \
                redirect_stdout(output):
            self.assertEqual(1, target.main())
        self.assertNotIn('secret detail', output.getvalue())
        self.assertEqual('PKI_BOOTSTRAP_FAILED', json.loads(output.getvalue())['reason'])

    def test_operation_deadline_reports_no_private_details(self):
        with self.assertRaisesRegex(target.BootstrapFailed, 'BOOTSTRAP_DEADLINE_EXCEEDED'):
            target.deadline_expired(None, None)

    def test_initial_generation_executes_real_crypto_then_reexports_without_password(self):
        with tempfile.TemporaryDirectory() as name:
            state = Path(name)
            password = 'synthetic-controlled-intermediate-password'
            with patch.object(target, 'STATE', state), patch.object(target, 'BASE', state), \
                    patch.object(target, 'preflight'), patch.object(target, 'directory'), \
                    patch.object(target, 'protected'), \
                    patch.object(target, 'open', return_value=io.BytesIO(), create=True), \
                    patch.object(target.getpass, 'getpass', side_effect=[password, password]) as prompt:
                first = target.execute('a' * 40)
                self.assertEqual(2, prompt.call_count)
                second = target.execute('b' * 40)
                self.assertEqual(2, prompt.call_count)
            self.assertEqual(first['csr_sha256'], second['csr_sha256'])
            self.assertEqual('a' * 40, second['source_revision'])
            self.assertEqual({'bootstrap.lock', 'ca-key.enc.pem', 'cluster-intermediate.csr.pem',
                              'bootstrap-receipt.json'}, {entry.name for entry in state.iterdir()})
            self.assertNotIn(password, json.dumps(second))

    def test_preflight_failures_block_generation(self):
        def run_case(*, encryption=True, audit=True, pod=False, package=True):
            def native(argv, **kwargs):
                if 'dpkg-query' in argv[0]:
                    return (target.PACKAGE if package else 'wrong').encode()
                if 'systemctl' in argv[0]:
                    return b'active\n'
                if 'auditctl' in argv[0]:
                    return b'enabled 1\nlost 0\n' if audit else b'enabled 1\nlost 1\n'
                if 'secrets-encrypt' in argv:
                    return b'Encryption Status: Enabled\nServer Encryption Hashes: All hashes match\n' if encryption else b'Encryption Status: Disabled\n'
                return json.dumps({'items': [1] if pod else []}).encode()
            with patch.object(target.os, 'geteuid', return_value=0), \
                    patch.object(target.os, 'uname', return_value=SimpleNamespace(
                        nodename='mail.hooshix.com', machine='x86_64')), \
                    patch.object(target, 'native', side_effect=native):
                target.preflight()
        run_case()
        for options, reason in (({'encryption': False}, 'CLUSTER_ENCRYPTION_NOT_VERIFIED'),
                                ({'audit': False}, 'AUDIT_UNHEALTHY'),
                                ({'pod': True}, 'EXISTING_CONTROL_PLANE_PRESERVED'),
                                ({'package': False}, 'HOST_CRYPTO_VERSION_REVIEW_REQUIRED')):
            with self.subTest(options=options), self.assertRaisesRegex(target.BootstrapFailed, reason):
                run_case(**options)

    def test_unsafe_paths_are_rejected(self):
        safe = dict(st_mode=stat.S_IFREG | 0o600, st_uid=0, st_gid=0, st_nlink=1, st_size=100)
        for change in ({'st_mode': stat.S_IFLNK | 0o777}, {'st_uid': 1000},
                       {'st_mode': stat.S_IFREG | 0o644}, {'st_nlink': 2}, {'st_size': 32769}):
            path = SimpleNamespace(lstat=lambda: SimpleNamespace(**(safe | change)))
            with self.subTest(change=change), self.assertRaises(target.BootstrapFailed):
                target.protected(path, exact_mode=0o600)
        target.protected(SimpleNamespace(lstat=lambda: SimpleNamespace(**safe)), exact_mode=0o600)

    def test_create_is_private_durable_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as name:
            path = Path(name) / 'key'
            target.create(path, b'encrypted fixture')
            self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
            with self.assertRaises(FileExistsError):
                target.create(path, b'do not overwrite')
            link = Path(name) / 'link'
            link.symlink_to(path)
            with self.assertRaises(FileExistsError):
                target.create(link, b'do not follow')
            self.assertEqual(b'encrypted fixture', path.read_bytes())

    def test_partial_existing_state_is_preserved_without_password_or_crypto(self):
        with tempfile.TemporaryDirectory() as name:
            state = Path(name)
            (state / 'ca-key.enc.pem').write_bytes(b'existing partial ciphertext')
            with patch.object(target, 'STATE', state), patch.object(target, 'BASE', state), \
                    patch.object(target, 'preflight'), patch.object(target, 'directory'), \
                    patch.object(target, 'protected'), patch.object(target.getpass, 'getpass') as password:
                with self.assertRaisesRegex(target.BootstrapFailed, 'PARTIAL_STATE_PRESERVED'):
                    target.execute('a' * 40)
                password.assert_not_called()
            self.assertEqual(b'existing partial ciphertext', (state / 'ca-key.enc.pem').read_bytes())

    def test_real_crypto_encryption_csr_signature_and_wrong_password(self):
        # Synthetic, disposable host-CLI fixture; no Root or Production credential.
        secret = b'synthetic-intermediate-fixture-passphrase'
        private = target.native([target.OPENSSL, 'genpkey', '-algorithm', 'RSA',
                                 '-pkeyopt', 'rsa_keygen_bits:4096'], timeout=120)
        encrypted = target.native([target.OPENSSL, 'pkcs8', '-topk8', '-v2', 'aes-256-cbc',
                                   '-v2prf', 'hmacWithSHA256', '-iter', '1000000'],
                                  input_bytes=private, passphrase=secret)
        self.assertTrue(encrypted.startswith(b'-----BEGIN ENCRYPTED PRIVATE KEY-----'))
        self.assertNotIn(private, encrypted)
        with tempfile.TemporaryDirectory() as name:
            state = Path(name)
            key = state / 'ca-key.enc.pem'
            csr = state / 'cluster-intermediate.csr.pem'
            target.create(key, encrypted)
            target.create(csr, target.native([target.OPENSSL, 'req', '-new', '-sha256',
                                             '-key', str(key), '-passin', 'stdin', '-subj',
                                             target.SUBJECT], input_bytes=secret + b'\n'))
            with patch.object(target, 'protected'):
                target.validate_csr(csr)
            public = target.native([target.OPENSSL, 'pkey', '-in', str(key), '-passin',
                                    'stdin', '-pubout'], input_bytes=secret + b'\n')
            requested = target.native([target.OPENSSL, 'req', '-in', str(csr), '-pubkey', '-noout'])
            self.assertEqual(public, requested)
            with self.assertRaises(target.BootstrapFailed):
                target.native([target.OPENSSL, 'pkey', '-in', str(key), '-passin', 'stdin',
                               '-pubout'], input_bytes=b'wrong password\n')
            receipt = dict(schema_version=1, installation_id='hooshix-production',
                           phase='CSR_CREATED_PENDING_OFFLINE_SIGNATURE', source_revision='a' * 40,
                           created_at='2026-10-05T00:00:00Z', root_sha256=target.ROOT_SHA256,
                           csr_sha256=hashlib.sha256(csr.read_bytes()).hexdigest(),
                           encrypted_key_sha256=hashlib.sha256(encrypted).hexdigest())
            target.create(state / 'bootstrap-receipt.json', json.dumps(receipt).encode())
            with patch.object(target, 'STATE', state), patch.object(target, 'BASE', state), \
                    patch.object(target, 'protected'), patch.object(target, 'preflight'), \
                    patch.object(target, 'directory'), patch.object(target.getpass, 'getpass') as password:
                result = target.execute('b' * 40)
                password.assert_not_called()
                self.assertEqual('a' * 40, result['source_revision'])
                self.assertEqual('b' * 40, result['checked_revision'])
                self.assertNotIn('PRIVATE KEY', result['csr_pem'])
                self.assertNotIn(secret.decode(), json.dumps(result))
                self.assertEqual('Not run', result['openbao_installation'])
                csr.write_bytes(b'tampered public CSR')
                with self.assertRaisesRegex(target.BootstrapFailed, 'EXISTING_STATE_CONFLICT'):
                    target.public_receipt('b' * 40)

    def test_no_shell_environment_secrets_or_plain_key_file(self):
        source = Path(target.__file__).read_text()
        self.assertNotIn('shell=True', source)
        self.assertNotIn('-nocrypt', source)
        self.assertNotIn("create(key, private)", source)
        self.assertNotIn('os.environ', source)
        self.assertEqual('/var/lib/hooshix-pki/istio-system', str(target.STATE))


if __name__ == '__main__':
    unittest.main()
