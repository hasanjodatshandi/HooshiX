import base64
import contextlib
import hashlib
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import activate_openbao_host as activation
import backup_openbao_host as host
import backup_openbao_operator as operator
import openbao_snapshot_crypto as crypto
import parspack_snapshot_transport as cloud

IDENTIFIER = '74f8c0cd-1f8b-4a0f-9561-8f047e7206fb'
CIPHER = b'\xc1' + b'synthetic-ciphertext' * 20
DENIED = (ValueError, activation.custody.BootstrapFailed)


def response(version=b'fixture+/=', body=b'', status=b'200 OK', extra=b''):
    return b'HTTP/1.1 ' + status + b'\r\nx-amz-version-id: ' + version + b'\r\n' + extra + b'\r\n' + body


class SnapshotTransportTest(unittest.TestCase):
    def test_conditional_put_exact_version_readback_and_fixed_prefix(self):
        with patch.object(cloud, 'transfer', side_effect=[response(), response(body=CIPHER)]) as transfer:
            receipt, fetched = cloud.deliver(CIPHER, 'fixture-access', 'fixture-secret', IDENTIFIER)
        self.assertEqual(CIPHER, fetched)
        self.assertEqual(hashlib.sha256(CIPHER).hexdigest(), receipt['ciphertext_sha256'])
        self.assertEqual('c892683', receipt['bucket'])
        self.assertEqual('openbao-backups/' + IDENTIFIER + '.snap.pgp', receipt['object_key'])
        put, get = [call.args[0].decode() for call in transfer.call_args_list]
        self.assertIn('header = "If-None-Match: *"', put)
        self.assertIn('versionId=fixture%2B%2F%3D', get)
        self.assertIn('https://c892683.parspack.net/c892683/openbao-backups/', put)
        self.assertIn(base64.b64encode(hashlib.sha256(CIPHER).digest()).decode(), put)
        self.assertEqual(transfer.call_args_list[0].args[2], transfer.call_args_list[1].args[2])
        with self.assertRaises(OSError):
            os.fstat(transfer.call_args_list[0].args[1])

    def test_invalid_plaintext_uuid_credentials_never_send(self):
        cases = [(b'plainsecret' * 40, 'fixture', 'fixture', IDENTIFIER),
                 (CIPHER, 'fixture', 'fixture', '../outside'),
                 (CIPHER, 'user:other', 'fixture', IDENTIFIER),
                 (CIPHER, 'fixture', 'bad\nsecret', IDENTIFIER)]
        for args in cases:
            with self.subTest(args=args), patch.object(cloud, 'transfer') as transfer:
                with self.assertRaises(DENIED):
                    cloud.deliver(*args)
                transfer.assert_not_called()

    def test_missing_null_duplicate_error_versions_and_ambiguous_put_fail_closed(self):
        cases = [response(version=b'null'), response(version=b''), response(status=b'403 Forbidden'),
                 response(extra=b'X-Amz-Version-Id: second\r\n'), b'HTTP/1.1 200 OK\r\n\r\n']
        for raw in cases:
            with self.subTest(raw=raw), patch.object(cloud, 'transfer', return_value=raw) as transfer:
                with self.assertRaises(ValueError):
                    cloud.deliver(CIPHER, 'fixture', 'fixture', IDENTIFIER)
                self.assertEqual(1, transfer.call_count)

    def test_mismatched_version_or_content_denied_no_replay(self):
        for raw in [response(version=b'other', body=CIPHER), response(body=CIPHER + b'x')]:
            with patch.object(cloud, 'transfer', side_effect=[response(), raw]) as transfer:
                with self.assertRaises(ValueError):
                    cloud.deliver(CIPHER, 'fixture', 'fixture', IDENTIFIER)
                self.assertEqual(2, transfer.call_count)

    def test_response_bounds_and_redirects(self):
        for raw in [response(status=b'302 Found'), response(extra=b'X: ' + b'x' * 8192 + b'\r\n')]:
            with self.assertRaises(ValueError):
                cloud.response(raw)
        with patch.object(crypto, 'MAX_CIPHERTEXT', 192), self.assertRaises(ValueError):
            cloud.response(response(body=b'a' * 193))

    def test_native_curl_rejects_file_protocol_without_network_or_raw_errors(self):
        with cloud.tempfile.TemporaryFile() as source:
            with self.assertRaisesRegex(ValueError, '^snapshot transport failed; retain local ciphertext$'):
                cloud.transfer(b'url = "file:///dev/null"\n', source.fileno(), cloud.time.monotonic() + 5)


class SnapshotHostTest(unittest.TestCase):
    def test_export_writes_only_envelope_and_public_receipt_and_binds_custody(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.ExitStack() as stack:
            state = Path(temporary) / 'state'
            state.mkdir()
            destination = Path(temporary) / 'export'
            intent = {'recipient_sha256': 'b' * 64, 'pvc_uid': 'fixture-pvc', 'image': activation.IMAGE}
            (state / 'attempt.json').write_text(json.dumps(intent))
            ca = b'fixture-public-ca'
            for target, name, options in [
                (host.host, 'STATE', {'new': state}),
                (host.host, 'public_keys', {'return_value': 'b' * 64}),
                (host.host, 'preflight', {'return_value': ({'ssh': 1}, 'fixture-pvc')}),
                (host.host, 'bao', {'return_value': {'initialized': True, 'sealed': False, 'n': 3, 't': 2}}),
                (host.host.custody, 'directory', {}), (host.host.custody, 'protected', {}),
                (host.host.custody, 'ROOT_SHA256', {'new': hashlib.sha256(ca).hexdigest()}),
                (host.host, 'kube', {'return_value': base64.b64encode(ca)}),
                (host.host, 'service_identity', {'return_value': {'ssh': 1}}),
                (host.host, 'storage_preflight', {}), (host, 'Path', {'return_value': destination}),
                (host.os, 'chown', {}), (host.pwd, 'getpwnam', {'return_value': Mock(pw_uid=1000, pw_gid=1000)}),
                (host, 'snapshot', {'return_value': b'fixture-raft-barrier-bytes'}),
                (host.crypto, 'seal', {'return_value': CIPHER}), (host.transport, 'forward', {})]:
                stack.enter_context(patch.object(target, name, **options))
            native = stack.enter_context(patch.object(host.host, 'native'))
            request = {'root_token': 'fixture-root-never-save', 'recipients': [], 'snapshot_id': IDENTIFIER}
            receipt = host.export(request, 'a' * 40)
            self.assertEqual(CIPHER, (destination / 'snapshot.snap.pgp').read_bytes())
            self.assertEqual('Passed', receipt['snapshot_export'])
            self.assertEqual('Not run', receipt['hourly_schedule'])
            self.assertNotIn(request['root_token'], (destination / 'receipt.json').read_text())
            self.assertEqual(0o600, (destination / 'snapshot.snap.pgp').stat().st_mode & 0o777)
            native.assert_called_once()
            native.reset_mock()
            intent['recipient_sha256'] = 'c' * 64
            (state / 'attempt.json').write_text(json.dumps(intent))
            with self.assertRaisesRegex(activation.custody.BootstrapFailed, '^SNAPSHOT_CUSTODY_IDENTITY_CONFLICT$'):
                host.export(request, 'a' * 40)
            native.assert_not_called()

    def test_snapshot_get_token_only_in_header_not_body_and_bounded(self):
        response = Mock(status=200)
        response.read.return_value = b'\x1f\x8b\x08encrypted-raft-snapshot'
        context = Mock()
        context.__enter__ = Mock(return_value=response)
        context.__exit__ = Mock(return_value=False)
        opener = Mock()
        opener.open.return_value = context
        client = Mock(base='https://127.0.0.1:32789/v1/', opener=opener)
        self.assertEqual(b'\x1f\x8b\x08encrypted-raft-snapshot', host.snapshot(client, 'fixture-root-token'))
        request = opener.open.call_args.args[0]
        self.assertEqual('GET', request.method)
        self.assertEqual('https://127.0.0.1:32789/v1/sys/storage/raft/snapshot', request.full_url)
        self.assertIsNone(request.data)
        self.assertEqual('fixture-root-token', request.get_header('X-vault-token'))
        self.assertEqual(60, opener.open.call_args.kwargs['timeout'])
        response.read.assert_called_once_with(crypto.MAX_SNAPSHOT + 1)

    def test_snapshot_token_bound_response_error_and_timeout_are_sanitized(self):
        for token in ['short', 'fixture\nsecret', 'x' * 1025]:
            with self.assertRaises(activation.custody.BootstrapFailed):
                host.snapshot(Mock(), token)
        client = Mock(base='https://127.0.0.1:32789/v1/')
        client.opener.open.side_effect = OSError('do-not-print-fixture-token')
        with self.assertRaisesRegex(activation.custody.BootstrapFailed, '^SNAPSHOT_API_FAILED_STATE_PRESERVED$'):
            host.snapshot(client, 'fixture-root-token')

    def test_invalid_request_never_preflights_or_mutates(self):
        for body in [{}, {'root_token': 'fixture', 'recipients': [], 'snapshot_id': IDENTIFIER, 'action': 'init'},
                     {'root_token': 'fixture', 'recipients': [], 'snapshot_id': '../outside'}]:
            with patch.object(host.host, 'preflight') as preflight:
                with self.assertRaises(DENIED):
                    host.export(body, 'a' * 40)
                preflight.assert_not_called()

    def test_sealed_store_blocks_before_any_snapshot_or_export(self):
        with patch.object(host.host, 'public_keys', return_value='a' * 64), \
                patch.object(host.host, 'preflight', return_value=({}, 'fixture-pvc')), \
                patch.object(host.host, 'bao', return_value={'initialized': True, 'sealed': True, 'n': 3, 't': 2}), \
                patch.object(host, 'snapshot') as snapshot, patch.object(host.crypto, 'seal') as seal:
            with self.assertRaisesRegex(activation.custody.BootstrapFailed, '^ACTIVE_SHAMIR_STORE_REQUIRED$'):
                host.export({'root_token': 'fixture-root', 'recipients': [], 'snapshot_id': IDENTIFIER}, 'a' * 40)
            snapshot.assert_not_called()
            seal.assert_not_called()

    def test_host_supervisor_has_no_init_unseal_restore_or_root_creation_paths(self):
        source = Path(host.__file__).read_text()
        for forbidden in ['sys/init', 'sys/unseal', 'snapshot-force', 'auth/token/create', 'systemctl restart']:
            self.assertNotIn(forbidden, source)

    def test_rpc_failure_never_discloses_request_or_native_exception(self):
        body = {'root_token': 'fixture-secret-never-show', 'recipients': [], 'snapshot_id': IDENTIFIER}
        stream = Mock(buffer=io.BytesIO(json.dumps(body).encode() + b'\n'))
        output = io.StringIO()
        with patch.object(host.sys, 'stdin', stream), patch.object(host.sys, 'stdout', output), \
                patch.object(host.signal, 'signal'), patch.object(host.signal, 'alarm'), \
                patch.object(host, 'export', side_effect=OSError('fixture-secret-never-show')):
            self.assertEqual(1, host.main('a' * 40))
        self.assertNotIn('fixture-secret-never-show', output.getvalue())
        self.assertIn('SNAPSHOT_FAILED_STATE_PRESERVED', output.getvalue())


class SnapshotOperatorTest(unittest.TestCase):
    def test_download_rejects_unsafe_path_before_any_process(self):
        with patch.object(operator.subprocess, 'Popen') as spawn:
            for remote, size in [('/etc; id', 192), ('/var/tmp/hooshix-openbao-snapshot-' + 'a' * 32, -1)]:
                with self.assertRaisesRegex(activation.custody.BootstrapFailed, '^SNAPSHOT_DOWNLOAD_PATH_REJECTED$'):
                    operator.remote_ciphertext(remote, size)
            spawn.assert_not_called()

    def test_native_download_stream_bound_short_response_and_timeout(self):
        original = operator.subprocess.Popen
        remote = '/var/tmp/hooshix-openbao-snapshot-' + 'a' * 32
        for count in [191, 192, 193]:
            def spawn(argv, **kwargs):
                self.assertEqual('cat -- ' + remote + '/snapshot.snap.pgp', argv[-1])
                # Only a local synthetic producer; no VPS or credential interaction.
                return original(['/usr/bin/head', '-c', str(count), '/dev/zero'], **kwargs)
            with patch.object(operator.subprocess, 'Popen', side_effect=spawn):
                if count == 192:
                    self.assertEqual(b'\0' * 192, operator.remote_ciphertext(remote, 192))
                else:
                    with self.assertRaises(activation.custody.BootstrapFailed):
                        operator.remote_ciphertext(remote, 192)
        with patch.object(operator.subprocess, 'Popen', side_effect=spawn), \
                patch.object(operator.select, 'select', return_value=([], [], [])):
            with self.assertRaisesRegex(activation.custody.BootstrapFailed, '^SNAPSHOT_DOWNLOAD_TIMEOUT$'):
                operator.remote_ciphertext(remote, 192)

    def test_invalid_receipt_never_downloads_or_creates_file(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(operator, 'remote_ciphertext') as command:
            with self.assertRaisesRegex(activation.custody.BootstrapFailed, '^SNAPSHOT_RECEIPT_REJECTED$'):
                operator.download({'remote_directory': '/etc'}, Path(temporary), 'a' * 40, IDENTIFIER, 'b' * 64)
            command.assert_not_called()
            self.assertEqual([], list(Path(temporary).iterdir()))

    def test_download_reads_exact_ciphertext_and_preserves_bound_hash(self):
        receipt = {'schema_version': 1, 'source_revision': 'a' * 40,
            'remote_directory': '/var/tmp/hooshix-openbao-snapshot-' + IDENTIFIER.replace('-', ''),
            'snapshot_id': IDENTIFIER, 'recipient_sha256': 'b' * 64, 'image': activation.IMAGE,
            'snapshot_export': 'Passed', 'ciphertext_bytes': len(CIPHER),
            'ciphertext_sha256': hashlib.sha256(CIPHER).hexdigest(), 'snapshot_sha256': 'c' * 64}
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            with patch.object(operator, 'remote_ciphertext', return_value=CIPHER):
                self.assertEqual(CIPHER, operator.download(receipt, directory, 'a' * 40, IDENTIFIER, 'b' * 64))
            self.assertEqual(0o600, (directory / 'snapshot.snap.pgp').stat().st_mode & 0o777)


class SnapshotCryptoTest(unittest.TestCase):
    def test_invalid_size_or_plaintext_fails_without_gpg(self):
        with patch.object(crypto.subprocess, 'Popen') as spawn:
            for value in [b'', b'not-a-pgp-ciphertext' * 20]:
                with self.assertRaises(activation.custody.BootstrapFailed):
                    crypto.recover(value, b'fixture', 'fixture password at least 20 characters')
            with self.assertRaises(activation.custody.BootstrapFailed):
                crypto.seal(b'', [])
            spawn.assert_not_called()

    def test_oversized_envelope_denied(self):
        with patch.object(crypto, 'MAX_CIPHERTEXT', 200), self.assertRaises(activation.custody.BootstrapFailed):
            crypto.validate_ciphertext(CIPHER)

    def test_native_crypto_output_bound_and_invalid_import_are_sanitized(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            with self.assertRaisesRegex(activation.custody.BootstrapFailed, '^SNAPSHOT_CRYPTO_FAILED$'):
                crypto.run(home, ['--import'], b'fixture-not-a-key', bound=32768)
            with self.assertRaisesRegex(activation.custody.BootstrapFailed, '^SNAPSHOT_CRYPTO_OUTPUT_BOUND$'):
                crypto.run(home, ['--version'], bound=10)


if __name__ == '__main__':
    unittest.main()
