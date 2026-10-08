import base64
import json
import os
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
