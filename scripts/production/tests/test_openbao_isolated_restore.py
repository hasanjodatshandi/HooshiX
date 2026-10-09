import contextlib
import hashlib
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import restore_openbao_isolated as restore

KEYS = ['a' * 66, 'b' * 66]  # PGP custody decrypts hexadecimal shares, matching the live adapter.
TOKEN = 'fixture-original-root-token'
SNAPSHOT = b'\x1f\x8b\x08fixture-encrypted-raft'
DENIED = restore.host.custody.BootstrapFailed


class IsolatedRestoreTest(unittest.TestCase):
    def test_rejects_inputs_before_any_container_or_write(self):
        for snapshot, keys, token in [(b'plaintext', KEYS, TOKEN), (SNAPSHOT, KEYS[:1], TOKEN),
                                     (SNAPSHOT, [KEYS[0]] * 2, TOKEN), (SNAPSHOT, [{}, {}], TOKEN),
                                     (SNAPSHOT, KEYS, 'bad\nsecret')]:
            with patch.object(restore.operator, 'command') as command, self.assertRaises(DENIED):
                restore.run(snapshot, keys, token, Path('/unused'))
            command.assert_not_called()

    def test_request_fixed_loopback_paths_header_only_and_bounds(self):
        response = Mock(status=204)
        response.read.return_value = b''
        context = Mock(__enter__=Mock(return_value=response), __exit__=Mock(return_value=False))
        client = Mock(base='https://127.0.0.1:32789/v1/')
        client.opener.open.return_value = context
        self.assertEqual({}, restore.request(client, 'sys/storage/raft/snapshot-force', 'POST', SNAPSHOT, TOKEN, 204))
        request = client.opener.open.call_args.args[0]
        self.assertEqual(SNAPSHOT, request.data)
        self.assertEqual(TOKEN, request.get_header('X-vault-token'))
        self.assertNotIn(TOKEN, request.full_url)
        self.assertEqual(60, client.opener.open.call_args.kwargs['timeout'])
        response.read.assert_called_once_with(32769)
        for path, body in [('sys/init', SNAPSHOT), ('sys/storage/raft/snapshot-force', 'not-bytes')]:
            with self.assertRaises(DENIED):
                restore.request(client, path, 'POST', body, TOKEN, 204)
        client.base = 'https://production.invalid/v1/'
        with self.assertRaises(DENIED):
            restore.request(client, 'sys/mounts', token=TOKEN)

    def test_native_errors_never_reveal_token_or_response(self):
        client = Mock(base='https://127.0.0.1:32789/v1/')
        client.opener.open.side_effect = OSError(TOKEN)
        with self.assertRaisesRegex(DENIED, '^RESTORE_API_FAILED$'):
            restore.request(client, 'sys/mounts', token=TOKEN)

    def test_status_wrong_version_rejected_with_bounded_polling(self):
        client = Mock()
        client.call.return_value = {'initialized': True, 'sealed': False, 'version': 'wrong',
                                    'storage_type': 'raft', 'type': 'shamir'}
        with patch.object(restore.time, 'monotonic', side_effect=[0, 1, 31]), \
                patch.object(restore.threading, 'Event'), self.assertRaisesRegex(DENIED, 'RESTORE_STATUS_TIMEOUT'):
            restore.wait(client, initialized=True, sealed=False)
        client.call.assert_called_once_with('status')

    def exercise(self, *, api_failure=False, cleanup_failure=False, wrong_label=False, bad_auth=False, bad_audit=False):
        commands, created = [], set()
        def command(argv, **kwargs):
            commands.append(argv)
            if argv[0] == '/usr/bin/openssl':
                Path(argv[argv.index('-keyout') + 1]).write_bytes(b'fixture-disposable-key')
                Path(argv[argv.index('-out') + 1]).write_bytes(b'fixture-public-leaf')
                return b''
            self.assertEqual('/usr/bin/docker', argv[0])
            self.assertEqual(['--host', 'unix:///var/run/docker.sock', '--config'], argv[1:4])
            args = argv[5:]
            if args[0] == 'version':
                return b'29.6.2\n'
            if args[0] == 'info':
                return b'true\n'
            if args[:2] == ['network', 'create']:
                created.add(args[-1])
            if args[0] == 'create':
                created.add(args[args.index('--name') + 1])
            if args[0] == 'port':
                return b'127.0.0.1:32789\n'
            if args[1:2] == ['ls']:
                identity = args[args.index('--filter') + 1][6:-1]
                return b'fixture-owned-id\n' if identity in created else b''
            if args[1:2] == ['inspect']:
                if args[args.index('--format') + 1] == '{{.Internal}}':
                    return b'true\n'
                return json.dumps({restore.LABEL: 'wrong' if wrong_label else args[-1].split('-')[-1]}).encode()
            if args[1:2] == ['rm']:
                if cleanup_failure:
                    raise DENIED('RESTORE_CLEANUP_FIXTURE_FAILED')
                created.remove(args[-1])
            return b''
        def request(client, path, *args, **kwargs):
            if api_failure:
                raise DENIED('RESTORE_API_FAILED')
            if path == 'auth/token/lookup-self':
                return {'data': {'policies': [] if bad_auth else ['root']}}
            if path == 'sys/audit':
                return {'data': {'protected/': {'type': 'file', 'options': {
                    'file_path': '/openbao/data/audit.jsonl', 'log_raw': 'true' if bad_audit else 'false'}}}}
            if path == 'sys/mounts':
                return {'data': {'cubbyhole/': {}, 'identity/': {}}}
            return {}
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
            for target, name, kwargs in [
                (restore.operator, 'command', {'side_effect': command}), (restore, 'wait', {}),
                (restore.host, 'unseal', {}), (restore, 'request', {'side_effect': request}),
                (restore.transport, 'Client', {'return_value': Mock(call=Mock(return_value={
                    'keys_base64': KEYS, 'root_token': 'fixture-temporary-root'}))})]:
                stack.enter_context(patch.object(target, name, **kwargs))
            if api_failure or cleanup_failure or wrong_label or bad_auth or bad_audit:
                with self.assertRaises(DENIED):
                    restore.run(SNAPSHOT, KEYS, TOKEN, Path(directory))
            else:
                proof = restore.run(SNAPSHOT, KEYS, TOKEN, Path(directory))
                self.assertEqual('Passed', proof['isolated_target_cleanup'])
                self.assertEqual('Not run', proof['vps_restore'])
            self.assertEqual([], list(Path(directory).iterdir()))
        return commands, created

    def test_clone_is_ram_backed_no_egress_no_logs_no_prod_mounts_and_cleaned(self):
        commands, created = self.exercise()
        self.assertFalse(created)
        argv = next(argv for argv in commands if argv[5:6] == ['create'])
        for value in ['--read-only', 'ALL', 'no-new-privileges', '512m', '1', '64', 'none',
                      '127.0.0.1::8200']:
            self.assertIn(value, argv)
        self.assertTrue(any(value.startswith('/openbao/data:rw,nosuid,nodev,noexec,size=128m') for value in argv))
        self.assertTrue(any(argv[5:7] == ['network', 'create'] and '--internal' in argv for argv in commands))
        for argv in commands:
            self.assertNotIn(TOKEN, ' '.join(argv))
            self.assertNotIn('sudo', argv)
            self.assertNotIn('ssh.exe', argv)
            self.assertNotIn('/var/lib/hooshixstorage', ' '.join(argv))

    def test_api_failure_still_removes_owned_clone_and_network(self):
        _, created = self.exercise(api_failure=True)
        self.assertFalse(created)

    def test_cleanup_error_cannot_return_success(self):
        _, created = self.exercise(cleanup_failure=True)
        self.assertTrue(created)

    def test_original_authentication_and_non_raw_audit_are_required(self):
        for options in [{'bad_auth': True}, {'bad_audit': True}]:
            _, created = self.exercise(**options)
            self.assertFalse(created)

    def test_cleanup_never_removes_a_foreign_label(self):
        commands, created = self.exercise(wrong_label=True)
        self.assertTrue(created)
        self.assertFalse(any(argv[6:7] == ['rm'] for argv in commands))


class SnapshotBindingTest(unittest.TestCase):
    def test_rejects_arbitrary_paths_before_reading_private_files(self):
        with patch.object(restore.operator, 'read_private') as read, self.assertRaises(DENIED):
            restore.load_snapshot(Path('/tmp/arbitrary'), Path('/tmp/custody'))
        read.assert_not_called()

    def test_envelope_requires_private_mode_hash_and_original_recipient(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.ExitStack() as stack:
            base = Path(temporary)
            name = 'a' * 32
            snapshot, custody = base / 'backup' / name, base / 'custody' / name
            snapshot.mkdir(parents=True, mode=0o700)
            custody.mkdir(parents=True, mode=0o700)
            cipher = b'\xc1' + b'fixture-encrypted-envelope' * 20
            receipt = {'snapshot_id': str(restore.uuid.UUID(name)), 'recipient_sha256': 'b' * 64,
                       'image': restore.host.IMAGE, 'snapshot_export': 'Passed', 'snapshot_sha256': 'c' * 64,
                       'ciphertext_bytes': len(cipher), 'ciphertext_sha256': hashlib.sha256(cipher).hexdigest()}
            for path, value in [(snapshot / 'export-receipt.json', json.dumps(receipt).encode()),
                                (custody / 'recipients.json', b'[]'), (snapshot / 'snapshot.snap.pgp', cipher)]:
                restore.operator.create(path, value)
            stack.enter_context(patch.object(restore.backup, 'BASE', snapshot.parent))
            stack.enter_context(patch.object(restore.operator, 'BASE', custody.parent))
            stack.enter_context(patch.object(restore.operator, 'custody_directory'))
            stack.enter_context(patch.object(restore.host, 'public_keys', return_value='b' * 64))
            self.assertEqual(cipher, restore.load_snapshot(snapshot, custody)[1])
            path = snapshot / 'snapshot.snap.pgp'
            path.chmod(0o644)
            with self.assertRaisesRegex(DENIED, 'RESTORE_CIPHERTEXT_FILE_REJECTED'):
                restore.load_snapshot(snapshot, custody)
            path.chmod(0o600)
            path.write_bytes(cipher + b'x')
            with self.assertRaisesRegex(DENIED, 'RESTORE_ENVELOPE_HASH_REJECTED'):
                restore.load_snapshot(snapshot, custody)
            path.unlink()
            path.symlink_to(custody / 'recipients.json')
            with self.assertRaises(OSError):
                restore.load_snapshot(snapshot, custody)


if __name__ == '__main__':
    unittest.main()
