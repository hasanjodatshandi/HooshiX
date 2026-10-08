"""Stopped-store preservation and bounded activation transport contracts."""
import base64
import contextlib
import hashlib
import io
import json
import os
import ssl
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import activate_openbao_host as host
import openbao_activation_transport as transport
import recover_openbao_initialization as recovery


class LostInitializationTest(unittest.TestCase):
    def keys(self):
        return [base64.b64encode(b'\xc6' + bytes([i]) * 300).decode() for i in (1, 2, 3)]

    @contextlib.contextmanager
    def fixture(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            state, data = root / 'state', root / 'mount' / 'data'
            state.mkdir(mode=0o700)
            data.mkdir(mode=0o700, parents=True)
            (data / 'raft').mkdir()
            (data / 'raft' / 'raft.db').write_bytes(b'fixture-existing-encrypted-raft')
            (data / 'audit.jsonl').write_bytes(b'fixture-protected-audit')
            identity = {'recipient_sha256': host.public_keys(self.keys()), 'pvc_uid': 'fixture-pvc', 'image': host.IMAGE}
            host.custody.create(state / 'attempt.json', json.dumps(identity).encode())
            real_lstat = Path.lstat
            def lstat(path):
                info = real_lstat(path)
                if path == data:
                    # Unit runner is non-root; exercise exact production owner gate.
                    values = list(info)
                    values[4] = values[5] = 10001
                    return os.stat_result(values)
                return info
            with patch.object(host, 'STATE', state), patch.object(recovery, 'DATA', data), \
                    patch.object(Path, 'lstat', lstat), patch.object(host.custody, 'protected'), \
                    patch.object(host, 'storage_preflight'), \
                    patch.object(host, 'bao', return_value={'initialized': True, 'sealed': True, 'n': 3, 't': 2}), \
                    patch.object(host, 'get', return_value={'metadata': {'uid': 'fixture-pvc'}}), \
                    patch.object(host, 'kube', return_value=b'openbao-0|data-openbao-0,\n'), \
                    patch.object(host, 'native'), patch.object(recovery, 'scale') as scale, \
                    patch.object(recovery, 'wait_fresh') as wait:
                yield state, data, scale, wait

    def test_unknown_outcome_recovery_never_initializes_and_preserves_all_bytes(self):
        # Tests run as root in the local environment and non-root (1001) in CI.
        with self.fixture() as (state, data, scale, wait), \
                patch.object(recovery, 'inventory') as inventory:
            before = {'raft/raft.db': {'sha256': hashlib.sha256(b'fixture-existing-encrypted-raft').hexdigest()}}
            inventory.return_value = before
            inode = data.stat().st_ino
            result = recovery.recover(host, self.keys(), 'fixture-pvc', 'a' * 40)
            archive, journal = Path(result['data_archive']), Path(result['journal_archive'])
            self.assertEqual(b'fixture-existing-encrypted-raft', (archive / 'raft' / 'raft.db').read_bytes())
            self.assertEqual(b'fixture-protected-audit', (archive / 'audit.jsonl').read_bytes())
            self.assertTrue((journal / 'attempt.json').exists())
            self.assertEqual(0o700, archive.stat().st_mode & 0o777)
            self.assertEqual(inode, data.stat().st_ino)
            self.assertFalse(any(data.iterdir()))
            self.assertFalse((state / 'attempt.json').exists())
            self.assertFalse((state / 'recovery-pending.json').exists())
            self.assertEqual('Not run', result['initialization'])
            self.assertEqual([0, 1], [call.args[1] for call in scale.call_args_list])
            wait.assert_called_once()
            self.assertEqual([('status',)], [call.args for call in host.bao.call_args_list])

    def test_existing_ciphertext_or_wrong_recipient_blocks_before_scale(self):
        for conflict in ('ciphertext', 'recipient', 'unsealed', 'pending', 'other-consumer'):
            with self.fixture() as (state, data, scale, wait):
                if conflict == 'ciphertext':
                    (state / 'encrypted.json').touch()
                elif conflict == 'recipient':
                    (state / 'attempt.json').write_text('{}')
                elif conflict == 'unsealed':
                    host.bao.return_value['sealed'] = False
                elif conflict == 'pending':
                    (state / 'recovery-pending.json').touch()
                else:
                    host.kube.return_value = b'another-pod|data-openbao-0,\n'
                with self.assertRaises(host.custody.BootstrapFailed):
                    recovery.recover(host, self.keys(), 'fixture-pvc', 'a' * 40)
                scale.assert_not_called()
                self.assertTrue((data / 'raft' / 'raft.db').exists())

    def test_archive_failure_preserves_pending_intent_and_replica_recovery(self):
        with self.fixture() as (state, data, scale, wait), \
                patch.object(recovery, 'inventory', side_effect=[{'fixture': 1}, {'fixture': 2}]):
            with self.assertRaises(host.custody.BootstrapFailed):
                recovery.recover(host, self.keys(), 'fixture-pvc', 'a' * 40)
            self.assertTrue((state / 'recovery-pending.json').exists())
            self.assertTrue((state / 'attempt.json').exists())
            self.assertEqual([0, 1], [call.args[1] for call in scale.call_args_list])
            wait.assert_not_called()
            api = Mock()
            with self.assertRaises(host.custody.BootstrapFailed):
                host.initialize(self.keys(), 'fixture-pvc', 'a' * 40, api=api)
            api.assert_not_called()

    def test_ciphertext_is_durable_before_forward_teardown_can_fail(self):
        def packet(value):
            return base64.b64encode(b'\xc1' + value * 300).decode()
        encrypted = {'keys_base64': [packet(bytes([i])) for i in (4, 5, 6)], 'root_token': packet(b'7')}
        with tempfile.TemporaryDirectory() as temporary, patch.object(host, 'STATE', Path(temporary)), \
                patch.object(host, 'kube', return_value=base64.b64encode(b'public-CA')), \
                patch.object(host.custody, 'ROOT_SHA256', hashlib.sha256(b'public-CA').hexdigest()):
            api = Mock(side_effect=[{'initialized': False, 'sealed': True}, encrypted,
                                   {'initialized': True, 'sealed': True, 'n': 3, 't': 2}])
            @contextlib.contextmanager
            def forward(certificate):
                yield api
                self.assertEqual(encrypted, json.loads((host.STATE / 'encrypted.json').read_bytes()))
                raise host.custody.BootstrapFailed('FIXTURE_TEARDOWN_FAILED')
            with patch.object(transport, 'forward', forward), self.assertRaises(host.custody.BootstrapFailed):
                host.activate({'action': 'initialize', 'recipients': self.keys()}, 'fixture-pvc', 'a' * 40)
            self.assertTrue((host.STATE / 'encrypted.json').exists())
            self.assertEqual(1, sum(call.args[:2] == ('write', 'sys/init') for call in api.call_args_list))

    def test_inventory_links_devices_bounds_and_metadata(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'db'
            source.write_bytes(b'fixture')
            # OS ownership is a separately tested production gate. Local CI UID
            # is outside the production allowlist, so synthetic owner metadata
            # retains all real inode/size/time fields for this filesystem test.
            real_lstat, real_fstat = Path.lstat, os.fstat
            def owned(info):
                return Mock(st_uid=0, st_gid=0, **{key: getattr(info, key) for key in
                    ('st_mode', 'st_nlink', 'st_size', 'st_ino', 'st_dev', 'st_mtime_ns', 'st_ctime_ns')})
            with patch.object(Path, 'lstat', lambda path: owned(real_lstat(path))), \
                    patch.object(recovery.os, 'fstat', lambda fd: owned(real_fstat(fd))):
                value = recovery.inventory(root)
                self.assertEqual(hashlib.sha256(b'fixture').hexdigest(), value['db']['sha256'])
                with patch.object(recovery, 'MAX_BYTES', 1), self.assertRaises(host.custody.BootstrapFailed):
                    recovery.inventory(root)
                link = root / 'alias'
                link.symlink_to(source)
                with self.assertRaises(host.custody.BootstrapFailed):
                    recovery.inventory(root)
                link.unlink()
                os.link(source, root / 'hard')
                with self.assertRaises(host.custody.BootstrapFailed):
                    recovery.inventory(root)

    def test_transport_one_attempt_fixed_paths_tls_and_init_timeout(self):
        response = Mock(status=200)
        response.read.return_value = b'{"initialized":true}'
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        with patch.object(transport.ssl, 'create_default_context', return_value=ssl.create_default_context()) as tls, \
                patch.object(transport.urllib.request, 'build_opener') as build:
            build.return_value.open.return_value = response
            client = transport.Client(18200, 'fixture-public-CA')
            value = client.call('write', 'sys/init', {'secret_shares': 3})
            self.assertTrue(value['initialized'])
            self.assertEqual(60, build.return_value.open.call_args.kwargs['timeout'])
            request = build.return_value.open.call_args.args[0]
            self.assertEqual('https://127.0.0.1:18200/v1/sys/init', request.full_url)
            self.assertEqual('PUT', request.method)
            self.assertEqual({'secret_shares': 3}, json.loads(request.data))
            tls.assert_called_once_with(cadata='fixture-public-CA')
            client.call('status')
            self.assertEqual(3, build.return_value.open.call_args.kwargs['timeout'])
            build.return_value.open.reset_mock()
            build.return_value.open.side_effect = TimeoutError('fixture-secret-response')
            with self.assertRaises(host.custody.BootstrapFailed) as failure:
                client.call('write', 'sys/init', {})
            self.assertEqual(1, build.return_value.open.call_count)
            self.assertNotIn('fixture-secret', str(failure.exception))
            with self.assertRaises(host.custody.BootstrapFailed):
                client.call('write', 'secret/data/private', {})
            with self.assertRaises(host.custody.BootstrapFailed):
                transport.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://public.invalid')

    def test_forward_reaps_even_when_consumer_or_startup_fails(self):
        for line in (b'Forwarding from 127.0.0.1:18200 -> 8200\n', b'Forwarding from 0.0.0.0:18200 -> 8200\n'):
            process = Mock(stdout=io.BytesIO(line))
            process.poll.return_value = None
            with patch.object(transport.subprocess, 'Popen', return_value=process) as launch, \
                    patch.object(transport.select, 'select', return_value=([process.stdout], [], [])), \
                    patch.object(transport, 'Client'), self.assertRaises((host.custody.BootstrapFailed, ValueError)):
                with transport.forward('public-ca'):
                    raise ValueError('consumer stopped')
            process.terminate.assert_called_once()
            process.wait.assert_called_once_with(timeout=5)
            self.assertTrue(process.stdout.closed)
            self.assertIn('--address=127.0.0.1', launch.call_args.args[0])


if __name__ == '__main__':
    unittest.main()
