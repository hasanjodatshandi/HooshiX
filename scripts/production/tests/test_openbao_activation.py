import base64
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import activate_openbao_host as host
import activate_openbao_operator as operator


def packet(tag, value):
    return base64.b64encode(bytes([0xc0 | tag]) + value * 300).decode()


class OpenBaoActivationTest(unittest.TestCase):
    def test_native_failure_identifies_edge_without_disclosing_any_output(self):
        sentinel = b'fixture-secret-stdout-stderr-never-publish'
        for result, reason in ((Mock(returncode=1, stdout=sentinel), 'EXIT_FAILED'),
                               (Mock(returncode=0, stdout=sentinel * host.BOUND), 'OUTPUT_BOUND')):
            with patch.object(host.subprocess, 'run', return_value=result), \
                    self.assertRaises(host.custody.BootstrapFailed) as failure:
                host.native(['fixture', 'secret-not-an-operation-label'], data=sentinel,
                            operation='GET_STATEFULSET')
            self.assertEqual('ACTIVATION_GET_STATEFULSET_' + reason, str(failure.exception))
            self.assertNotIn(sentinel.decode(), str(failure.exception))
        with patch.object(host.subprocess, 'run', side_effect=subprocess.TimeoutExpired(
                'fixture-secret-argv', 25, output=sentinel)), \
                self.assertRaises(host.custody.BootstrapFailed) as failure:
            host.native(['fixture'], operation='BAO_INIT')
        self.assertEqual('ACTIVATION_BAO_INIT_TIMEOUT', str(failure.exception))
        with patch.object(host.subprocess, 'run', return_value=Mock(returncode=2, stdout=b'{}')):
            self.assertEqual(b'{}', host.native(['fixture'], expected=(0, 2), operation='BAO_STATUS'))

    def test_diagnosis_reports_failed_preflight_and_init_state_without_reading_custody(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(host, 'STATE', Path(temporary)), \
                patch.object(host.os, 'geteuid', return_value=0), \
                patch.object(host.os, 'uname', return_value=Mock(nodename='mail.hooshix.com')), \
                patch.object(host.custody, 'protected'), \
                patch.object(host, 'preflight', side_effect=host.custody.BootstrapFailed(
                    'ACTIVATION_GET_STATEFULSET_OUTPUT_BOUND')), \
                patch.object(host, 'bao', return_value={'initialized': True, 'sealed': True,
                    'n': 3, 't': 2, 'version': '2.6.4', 'type': 'shamir', 'storage_type': 'raft'}), \
                patch.object(Path, 'read_bytes', side_effect=AssertionError('no custody contents')), \
                patch.object(host, 'initialize') as initialize, patch.object(host, 'unseal') as unseal:
            (host.STATE / 'attempt.json').touch()
            result = host.diagnose()
            self.assertEqual('ACTIVATION_GET_STATEFULSET_OUTPUT_BOUND', result['preflight']['reason'])
            self.assertEqual({'attempt.json': {'present': True}, 'encrypted.json': {'present': False}},
                             result['journal'])
            self.assertTrue(result['openbao']['initialized'])
            self.assertFalse(result['mutation'])
            initialize.assert_not_called()
            unseal.assert_not_called()

    def test_diagnosis_does_not_create_state_and_rejects_unsafe_journal(self):
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(host.os, 'geteuid', return_value=0), \
                patch.object(host.os, 'uname', return_value=Mock(nodename='mail.hooshix.com')), \
                patch.object(host, 'preflight', return_value=({}, 'fixture-pvc')), \
                patch.object(host, 'bao', side_effect=host.custody.BootstrapFailed('ACTIVATION_BAO_STATUS_TIMEOUT')):
            path = Path(temporary) / 'absent'
            with patch.object(host, 'STATE', path):
                result = host.diagnose()
            self.assertFalse(path.exists())
            self.assertEqual('ACTIVATION_BAO_STATUS_TIMEOUT', result['openbao']['reason'])
            path.symlink_to(temporary, target_is_directory=True)
            with patch.object(host, 'STATE', path), self.assertRaises(host.custody.BootstrapFailed):
                host.diagnose()

    def test_diagnostic_rpc_dispatch_precedes_all_state_mutation(self):
        stdin = Mock(buffer=io.BytesIO(b'{"action":"diagnose"}\n'))
        result = {'mutation': False, 'preflight': {'status': 'Failed', 'reason': 'FIXED_TEST_FAILURE'}}
        with patch.object(host.sys, 'stdin', stdin), patch.object(host.sys, 'stdout', io.StringIO()), \
                patch.object(host.signal, 'signal'), patch.object(host.signal, 'alarm'), \
                patch.object(host, 'diagnose', return_value=result), patch.object(host, 'preflight') as preflight, \
                patch.object(host.custody, 'directory') as directory, patch.object(host, 'native') as native:
            self.assertEqual(0, host.main('a' * 40))
            preflight.assert_not_called()
            directory.assert_not_called()
            native.assert_not_called()
        for request in ({'action': 'diagnose', 'recipients': []}, ['diagnose'], {'action': 'bogus'}):
            stdin = Mock(buffer=io.BytesIO(json.dumps(request).encode() + b'\n'))
            with patch.object(host.sys, 'stdin', stdin), patch.object(host.sys, 'stdout', io.StringIO()), \
                    patch.object(host.signal, 'signal'), patch.object(host.signal, 'alarm'), \
                    patch.object(host, 'preflight') as preflight, patch.object(host.custody, 'directory') as directory:
                self.assertEqual(1, host.main('a' * 40))
                preflight.assert_not_called()
                directory.assert_not_called()

    def test_diagnostic_operator_requires_no_gpg_custody_or_rescue_prompt(self):
        with patch.object(sys, 'argv',
                          ['activate_openbao_operator.py', '--diagnose-only']), \
                patch.object(operator.os, 'getuid', return_value=1000), \
                patch.object(operator.os, 'uname', return_value=Mock(release='microsoft')), \
                patch.object(operator, 'sys_tty', return_value=True), \
                patch.object(operator, 'command', side_effect=[b'', b'a' * 40, b'']), \
                patch.object(operator, 'diagnose', return_value=0) as diagnose, \
                patch.object(operator, 'prepare') as prepare, patch.object(operator, 'gpg') as gpg, \
                patch.object(operator.getpass, 'getpass') as prompt:
            self.assertEqual(0, operator.main())
            diagnose.assert_called_once_with('a' * 40)
            prepare.assert_not_called()
            gpg.assert_not_called()
            prompt.assert_not_called()

    def test_resume_prompt_requires_existing_not_new_custody_password(self):
        value = 'Disposable test custody passphrase'
        for resuming, label in ((True, 'EXISTING'), (False, 'NEW')):
            with patch.object(operator.getpass, 'getpass', return_value=value) as prompt:
                self.assertEqual(value, operator.custody_passphrase(resuming))
                self.assertIn(label, prompt.call_args_list[0].args[0])

    def test_installed_storage_failure_is_sanitized_and_blocks_activation(self):
        class StorageFailed(Exception):
            pass
        installed = {'StorageFailed': StorageFailed,
                     'guard_check': Mock(side_effect=StorageFailed('fixture-private-native-output'))}
        with patch.object(host.storage, 'load_storage', return_value=installed), \
                self.assertRaises(host.custody.BootstrapFailed) as failure:
            host.storage_preflight()
        self.assertEqual('ACTIVATION_STORAGE_GUARD_FAILED', str(failure.exception))
        with patch.object(host.storage, 'load_storage', side_effect=ValueError('fixture-private-path')), \
                self.assertRaises(host.custody.BootstrapFailed) as failure:
            host.storage_preflight()
        self.assertEqual('ACTIVATION_STORAGE_SOURCE_REVIEW_REQUIRED', str(failure.exception))

    def recipients(self):
        return [packet(6, bytes([i])) for i in (1, 2, 3)]

    def encrypted(self):
        return {'keys_base64': [packet(1, bytes([i])) for i in (4, 5, 6)],
                'root_token': packet(1, b'7')}

    def test_public_key_count_distinctness_and_private_packet_rejection(self):
        self.assertEqual(64, len(host.public_keys(self.recipients())))
        for bad in ([], self.recipients()[:2], [self.recipients()[0]] * 3,
                    [packet(5, b'1'), *self.recipients()[1:]], ['not base64'] * 3):
            with self.assertRaises((host.custody.BootstrapFailed, ValueError)):
                host.public_keys(bad)
        value = host.init_request(self.recipients())
        self.assertEqual(3, value['secret_shares'])
        self.assertEqual(2, value['secret_threshold'])
        self.assertEqual(self.recipients()[0], value['root_token_pgp_key'])

    def test_plaintext_duplicate_or_truncated_output_never_saved(self):
        self.assertEqual(self.encrypted(), host.encrypted_result(self.encrypted()))
        for field in ('keys_base64', 'root_token'):
            value = self.encrypted()
            value[field] = ['a' * 44] * 3 if field == 'keys_base64' else 's.plaintext-initial-root'
            with self.assertRaises(host.custody.BootstrapFailed):
                host.encrypted_result(value)
        for value in ('aaaa', packet(6, b'9'), base64.b64encode(b'\xc1short').decode()):
            with self.assertRaises(host.custody.BootstrapFailed):
                host.ciphertext(value)

    def test_init_is_one_shot_journal_before_api_and_ciphertext_only_durable(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(host, 'STATE', Path(temporary)):
            status = {'initialized': False, 'sealed': True}
            after = {'initialized': True, 'sealed': True, 'n': 3, 't': 2}
            def api(operation, path=None, body=None):
                if operation == 'status':
                    return status if not (host.STATE / 'encrypted.json').exists() else after
                self.assertTrue((host.STATE / 'attempt.json').exists())
                self.assertEqual(host.init_request(self.recipients()), body)
                return self.encrypted()
            result = host.initialize(self.recipients(), 'pvc-fixture', 'a' * 40, api=api)
            self.assertEqual(self.encrypted(), result)
            self.assertEqual(result, json.loads((host.STATE / 'encrypted.json').read_bytes()))
            mock = Mock(side_effect=lambda operation: after)
            with patch.object(host.custody, 'protected'):
                self.assertEqual(result, host.initialize(self.recipients(), 'pvc-fixture', 'b' * 40, api=mock))
            self.assertEqual([('status',)], [call.args for call in mock.call_args_list])
            with patch.object(host.custody, 'protected'), self.assertRaises(host.custody.BootstrapFailed):
                host.initialize(self.recipients(), 'different-pvc', 'a' * 40, api=mock)

    def test_unknown_init_outcome_and_existing_storage_never_reinitialized(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(host, 'STATE', Path(temporary)):
            api = Mock(side_effect=[{'initialized': False, 'sealed': True}, TimeoutError])
            with self.assertRaises(TimeoutError):
                host.initialize(self.recipients(), 'pvc', 'a' * 40, api=api)
            self.assertTrue((host.STATE / 'attempt.json').exists())
            self.assertFalse((host.STATE / 'encrypted.json').exists())
            api = Mock(return_value={'initialized': True, 'sealed': True})
            with self.assertRaises(host.custody.BootstrapFailed):
                host.initialize(self.recipients(), 'pvc', 'a' * 40, api=api)
            api.assert_called_once_with('status')

    def test_unseal_one_share_negative_reset_and_two_share_positive_no_argv_secret(self):
        sealed = {'initialized': True, 'sealed': True, 'n': 3, 't': 2}
        api = Mock(side_effect=[sealed, {}, {'sealed': True, 'progress': 1}, {}, sealed | {'sealed': False}])
        keys = ['a' * 66, 'b' * 66]
        self.assertEqual('Passed', host.unseal(keys, api=api)['unseal'])
        self.assertEqual({'reset': True}, api.call_args_list[1].args[2])
        self.assertEqual({'key': keys[0]}, api.call_args_list[2].args[2])
        for bad in ([], [keys[0]] * 2, ['invalid', keys[1]]):
            api = Mock()
            with self.assertRaises(host.custody.BootstrapFailed):
                host.unseal(bad, api=api)
            api.assert_not_called()
        api = Mock(side_effect=[sealed, {}, {'sealed': False, 'progress': 1}])
        with self.assertRaises(host.custody.BootstrapFailed):
            host.unseal(keys, api=api)
        self.assertEqual(3, api.call_count)

    def test_write_uses_json_stdin_verified_tls_and_no_credential_environment(self):
        sensitive = 'a' * 66
        with patch.object(host, 'kube', return_value=b'{"data":{"sealed":true}}') as kube:
            host.bao('write', 'sys/unseal', {'key': sensitive})
        args = kube.call_args.args
        self.assertNotIn(sensitive, ' '.join(args))
        self.assertNotIn('-tls-skip-verify', args)
        self.assertIn('-ca-cert=/openbao/tls/ca.crt', args)
        self.assertEqual({'key': sensitive}, json.loads(kube.call_args.kwargs['data']))

    def test_activation_projects_only_ca_from_the_installed_tls_secret(self):
        from render_openbao_candidate import candidate
        manifest = candidate('standard')['items'][-1]
        tls = next(v for v in manifest['spec']['template']['spec']['volumes'] if v['name'] == 'tls')
        certificate = b'disposable-public-root-fixture'
        expected = self.encrypted()
        with patch.object(host, 'kube', return_value=base64.b64encode(certificate)) as kube, \
                patch.object(host.custody, 'ROOT_SHA256', hashlib.sha256(certificate).hexdigest()), \
                patch.object(host.transport, 'forward') as forward, \
                patch.object(host, 'initialize', return_value=expected) as initialize:
            result = host.activate({'action': 'initialize', 'recipients': self.recipients()},
                                   'fixture-pvc', 'a' * 40)
        kube.assert_called_once_with('-n', 'hooshix-secrets', 'get', 'secret', tls['secret']['secretName'],
                                    '-o', r'jsonpath={.data.ca\.crt}', operation='GET_PUBLIC_CA')
        forward.assert_called_once_with(certificate.decode('ascii'))
        initialize.assert_called_once_with(self.recipients(), 'fixture-pvc', 'a' * 40,
                                           api=forward.return_value.__enter__.return_value)
        self.assertEqual({'encrypted': expected}, result)

    def test_ca_failure_precedes_forwarding_and_all_initialization(self):
        for value, reason in ((b'', 'ROOT_CA_IDENTITY_CONFLICT'),
                              (base64.b64encode(b'untrusted-public-root'), 'ROOT_CA_IDENTITY_CONFLICT'),
                              (None, 'ACTIVATION_GET_PUBLIC_CA_EXIT_FAILED')):
            with self.subTest(reason=reason, missing=value is None), \
                    patch.object(host, 'kube', return_value=value) as kube, \
                    patch.object(host.transport, 'forward') as forward, \
                    patch.object(host, 'initialize') as initialize, \
                    self.assertRaises(host.custody.BootstrapFailed) as failure:
                if value is None:
                    kube.side_effect = host.custody.BootstrapFailed(reason)
                host.activate({'action': 'initialize', 'recipients': self.recipients()}, 'fixture-pvc', 'a' * 40)
            self.assertEqual(reason, str(failure.exception))
            forward.assert_not_called()
            initialize.assert_not_called()

    def test_exclusive_private_files_and_link_denial(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'private'
            operator.create(path, b'fixture')
            self.assertEqual(b'fixture', operator.read_private(path))
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError):
                operator.create(path, b'replacement')
            link = path.parent / 'link'
            link.symlink_to(path)
            with self.assertRaises(OSError):
                operator.read_private(link)
            hard = path.parent / 'hard'
            os.link(path, hard)
            with self.assertRaises(host.custody.BootstrapFailed):
                operator.read_private(path)

    def test_bootstrap_bound_and_hash_checks_before_execution(self):
        value = operator.bootstrap('/home/hooshixadmin/.cache/hooshix-bao-activation-' + 'a' * 32,
                                   'b' * 40, [(name, 'c' * 64) for name in operator.SOURCES])
        self.assertLess(len(value), 4096)
        self.assertIn('os.O_NOFOLLOW', value)
        self.assertIn('hashlib.sha256(content).hexdigest()!=digest', value)
        self.assertNotIn('password', value)

    def test_rpc_waits_for_root_before_body_and_never_places_secrets_in_argv(self):
        revision = 'a' * 40
        password, share = 'Local sudo fixture only', 'b' * 66
        process = Mock()
        process.stdin, process.stdout = io.BytesIO(), io.BytesIO(b'ACTIVATION_RPC_READY\n')
        process.returncode = 0
        def communicate(data, timeout):
            self.assertEqual(password.encode() + b'\n', process.stdin.getvalue())
            self.assertEqual({'action': 'unseal', 'keys': [share]}, json.loads(data))
            self.assertEqual(190, timeout)
            return json.dumps({'source_revision': revision, 'schema_version': 1}).encode(), None
        process.communicate.side_effect = communicate
        process.poll.return_value = 0
        with patch.object(operator.subprocess, 'Popen', return_value=process) as launch, \
                patch.object(operator.select, 'select', return_value=([process.stdout], [], [])):
            operator.rpc('/public-fixture', revision, [], password, {'action': 'unseal', 'keys': [share]})
        argv = ' '.join(launch.call_args.args[0])
        self.assertNotIn(password, argv)
        self.assertNotIn(share, argv)
        self.assertIn('sudo -k -S', argv)
        self.assertEqual(operator.ENV, launch.call_args.kwargs['env'])
        self.assertTrue(process.stdin.closed and process.stdout.closed)

    def test_rpc_authentication_failure_never_sends_api_body_and_reaps_process(self):
        process = Mock()
        process.stdin, process.stdout = io.BytesIO(), io.BytesIO()
        process.poll.return_value = None
        with patch.object(operator.subprocess, 'Popen', return_value=process), \
                patch.object(operator.select, 'select', return_value=([], [], [])), \
                self.assertRaises(host.custody.BootstrapFailed):
            operator.rpc('/public-fixture', 'a' * 40, [], 'fixture sudo', {'action': 'initialize'})
        process.communicate.assert_not_called()
        process.kill.assert_called_once()
        process.wait.assert_called_once_with(timeout=10)
        self.assertTrue(process.stdin.closed and process.stdout.closed)

    def test_real_local_pgp_export_wrong_password_and_fresh_import_recovery(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            password = 'Disposable native test custody passphrase only'
            public = operator.generate_recipient(directory, 1, password)
            self.assertEqual(6, (base64.b64decode(public)[0] >> 2) & 15)
            secret = operator.read_private(directory / 'recipient-1.secret.pgp')
            self.assertNotIn(password.encode(), secret)
            self.assertNotIn(b'BEGIN PRIVATE KEY', secret)


if __name__ == '__main__':
    unittest.main()
