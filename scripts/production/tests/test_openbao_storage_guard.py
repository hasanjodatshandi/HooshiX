import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import provision_openbao_storage as storage
from render_openbao_candidate import candidate as workload
from render_openbao_local_storage import NODE, PV_NAME, STORAGE_CLASS, candidate


class StorageGuardTest(unittest.TestCase):
    def check(self, *, options='rw,nosuid,nodev,noexec,noatime', inode=22, backing=None,
              uuid='f64a3c11-0000-0000-0000-000000000001', fstype='ext4', size=None,
              loop_count=1, offset=0, limit=0):
        state = {'schema_version': 1, 'bytes': storage.SIZE,
                 'uuid': 'f64a3c11-0000-0000-0000-000000000001'}
        image = SimpleNamespace(st_ino=22, st_dev=os.makedev(8, 1),
                                st_size=storage.SIZE, st_blocks=storage.SIZE // 512)
        def native(argv, **kwargs):
            self.assertEqual(2, kwargs['timeout'])
            if argv[0].endswith('findmnt'):
                return json.dumps({'filesystems': [{'target': str(storage.MOUNT),
                    'source': '/dev/loop0', 'fstype': fstype, 'options': options}]})
            if argv[0].endswith('losetup'):
                return json.dumps({'loopdevices': [{'back-file': backing or str(storage.IMAGE),
                    'back-ino': inode, 'back-maj:min': '8:1', 'offset': offset,
                    'sizelimit': limit}] * loop_count})
            return uuid
        with patch.object(storage, 'parents'), patch.object(storage, 'protected', return_value=image), \
                patch.object(Path, 'read_text', return_value=json.dumps(state)), \
                patch.object(storage, 'run', side_effect=native), patch.object(storage, 'data_owner'), \
                patch.object(os, 'statvfs', return_value=SimpleNamespace(
                    f_blocks=size or storage.SIZE, f_frsize=1)):
            storage.check_mount(storage.IMAGE, storage.MOUNT, storage.STATE, storage.SIZE)

    def test_exact_mount_identity(self):
        self.check()

    def test_replacement_readonly_foreign_uuid_overmount_or_loop_geometry_fails(self):
        for args in ({'inode': 23}, {'backing': '/foreign/loop'}, {'uuid': 'foreign'},
                     {'options': 'ro,nosuid,nodev,noexec,noatime'}, {'fstype': 'tmpfs'},
                     {'options': 'rw,nosuid,nodev,noexec,noatime,discard'},
                     {'size': storage.SIZE + 1}, {'loop_count': 0}, {'loop_count': 2},
                     {'offset': 4096}, {'limit': 4096}):
            with self.subTest(args=args), self.assertRaises(storage.StorageFailed):
                self.check(**args)

    def test_missing_mount_or_failed_native_check_is_not_healthy(self):
        with patch.object(storage, 'parents'), patch.object(storage, 'protected'), \
                patch.object(Path, 'read_text', return_value='{}'):
            with self.assertRaises(storage.StorageFailed):
                storage.check_mount(storage.IMAGE, storage.MOUNT, storage.STATE, storage.SIZE)

    def test_only_dedicated_uid_group_and_modes_are_accepted(self):
        path = Mock()
        for mode in (0o700, 0o770, 0o2770):
            path.lstat.return_value = SimpleNamespace(st_mode=stat.S_IFDIR | mode, st_uid=10001, st_gid=10001)
            storage.data_owner(path)
        for mode, uid, gid in ((0o777, 10001, 10001), (0o750, 10001, 10001),
                               (0o4770, 10001, 10001), (0o1770, 10001, 10001),
                               (0o770, 1000, 10001), (0o770, 10001, 0)):
            path.lstat.return_value = SimpleNamespace(st_mode=stat.S_IFDIR | mode, st_uid=uid, st_gid=gid)
            with self.assertRaises(storage.StorageFailed):
                storage.data_owner(path)
        path.lstat.return_value = SimpleNamespace(st_mode=stat.S_IFLNK | 0o700, st_uid=10001, st_gid=10001)
        with self.assertRaises(storage.StorageFailed):
            storage.data_owner(path)

    def test_worker_total_deadline_and_suppressed_diagnostics(self):
        with patch.object(subprocess, 'run', return_value=SimpleNamespace(returncode=0)) as native:
            storage.check_worker(storage.GUARD)
            self.assertEqual(12, native.call_args.kwargs['timeout'])
            self.assertEqual(subprocess.DEVNULL, native.call_args.kwargs['stdout'])
            self.assertEqual(subprocess.DEVNULL, native.call_args.kwargs['stderr'])
            self.assertIn('-I', native.call_args.args[0])
            self.assertNotIn('NOTIFY_SOCKET', native.call_args.kwargs['env'])
        with patch.object(subprocess, 'run', return_value=SimpleNamespace(returncode=1)):
            with self.assertRaises(storage.StorageFailed):
                storage.check_worker(storage.GUARD)
        with patch.object(subprocess, 'run', side_effect=subprocess.TimeoutExpired('check', 12)):
            with self.assertRaises(subprocess.TimeoutExpired):
                storage.check_worker(storage.GUARD)

    def test_notify_only_after_first_check_and_no_watchdog_after_failure(self):
        notify = Mock()
        with patch.dict(os.environ, {'NOTIFY_SOCKET': '@fixture'}, clear=True), \
                patch.object(storage.socket, 'socket') as sock, patch.object(storage.time, 'sleep'), \
                patch.object(storage, 'check_worker', side_effect=[None, storage.StorageFailed('failed')]):
            sock.return_value.__enter__.return_value = notify
            with self.assertRaises(storage.StorageFailed):
                storage.guard_loop(storage.GUARD)
        notify.connect.assert_called_once_with('\0fixture')
        notify.sendall.assert_called_once_with(b'READY=1\nWATCHDOG=1')
        with patch.dict(os.environ, {'NOTIFY_SOCKET': '/fixture'}, clear=True), \
                patch.object(storage.socket, 'socket') as sock, \
                patch.object(storage, 'check_worker', side_effect=storage.StorageFailed('failed')):
            notify = sock.return_value.__enter__.return_value
            with self.assertRaises(storage.StorageFailed):
                storage.guard_loop(storage.GUARD)
            notify.sendall.assert_not_called()

    def test_units_fail_closed_without_mount_namespace_or_automatic_rearm(self):
        self.assertIn('BindsTo=' + storage.UNIT_NAME, storage.GUARD_TEXT)
        self.assertIn('After=' + storage.UNIT_NAME, storage.GUARD_TEXT)
        for contract in ('Type=notify', 'Restart=no', 'WatchdogSec=30', 'TimeoutStartSec=15',
                         'TimeoutStopSec=5', 'MemoryMax=64M', 'TasksMax=8', 'CPUQuota=5%'):
            self.assertIn(contract, storage.GUARD_TEXT)
        # The guard must observe HOST mount changes, not an isolated mount snapshot.
        for option in ('PrivateMounts', 'ProtectSystem', 'ProtectHome', 'ReadOnlyPaths'):
            self.assertNotIn(option, storage.GUARD_TEXT)
        self.assertEqual('[Unit]\nBindsTo=' + storage.GUARD_NAME + '\nAfter=' +
                         storage.GUARD_NAME + '\n', storage.DROPIN_TEXT)

    def test_installer_requires_hash_verified_bytes_before_host_mutation(self):
        with patch.object(storage, 'execute') as execute:
            for source in (None, b'', b'x' * 32769):
                with self.assertRaises(storage.StorageFailed):
                    storage.install_guard(source)
            execute.assert_not_called()
        launcher = Path(storage.__file__).with_name('run-openbao-storage.ps1').read_text()
        self.assertIn('__hooshix_source_bytes__', launcher)
        self.assertIn('InstallApprovedGuard', launcher)
        self.assertIn('CHOOSE_ONE_STORAGE_OPERATION', launcher)
        self.assertLess(Path(storage.__file__).stat().st_size, 32768)

    def test_installer_checks_conflicts_before_writes_and_binds_only_after_health(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            guard, unit, dropin = (root / name for name in ('guard.py', 'guard.service', '30.conf'))
            events = []
            def native(argv, **_):
                events.append(tuple(argv))
                return storage.GUARD_NAME if '--property=BindsTo' in argv else 'active'
            def create(path, content):
                events.append(('create', path.name))
                path.write_bytes(content)
            with patch.multiple(storage, BASE=root, GUARD=guard, GUARD_UNIT=unit, K3S_DROPIN=dropin), \
                    patch.object(storage, 'execute', return_value={'storage_foundation': 'Passed'}), \
                    patch.object(storage, 'protected'), patch.object(storage, 'parents'), \
                    patch.object(storage, 'create_file', side_effect=create) as writes, \
                    patch.object(storage, 'run', side_effect=native), patch.object(storage, 'check_worker') as check:
                dropin.write_bytes(b'foreign policy')
                with self.assertRaisesRegex(storage.StorageFailed, 'GUARD_INSTALL_CONFLICT'):
                    storage.install_guard(b'public reviewed source')
                writes.assert_not_called()
                check.assert_not_called()
                dropin.unlink()
                result = storage.install_guard(b'public reviewed source')
                self.assertEqual('Passed', result['storage_guard'])
                self.assertFalse(result['k3s_restart_performed'])
                start = ('/usr/bin/systemctl', 'start', storage.GUARD_NAME)
                self.assertLess(events.index(start), events.index(('create', dropin.name)))
                self.assertFalse(any('restart' in event or 'reboot' in event for event in events))
                self.assertEqual(3, writes.call_count)
                writes.reset_mock()
                storage.install_guard(b'public reviewed source')
                writes.assert_not_called()

    def test_ci_rearm_does_not_reset_garbage_collected_inactive_dependents(self):
        source = Path(storage.__file__).with_name('rehearse_openbao_storage.py').read_text()
        self.assertIn("'reset-failed', guard_name]", source)
        self.assertNotIn("'reset-failed', guard_name, dummy_name", source)
        self.assertIn("'absent_source_bind_denied'", source)

    def test_review_only_static_local_pv_matches_stateful_claim_without_dynamic_fallback(self):
        manifest = candidate()
        sc, pv = manifest['items']
        self.assertEqual('kubernetes.io/no-provisioner', sc['provisioner'])
        self.assertEqual('WaitForFirstConsumer', sc['volumeBindingMode'])
        self.assertFalse(sc['allowVolumeExpansion'])
        self.assertEqual(STORAGE_CLASS, sc['metadata']['name'])
        self.assertEqual(PV_NAME, pv['metadata']['name'])
        spec = pv['spec']
        self.assertEqual('Retain', spec['persistentVolumeReclaimPolicy'])
        self.assertEqual('/var/lib/hooshixstorage/openbao/data', spec['local']['path'])
        self.assertNotIn('hostPath', spec)
        self.assertEqual({'namespace': 'hooshix-secrets', 'name': 'data-openbao-0'}, spec['claimRef'])
        self.assertEqual([NODE], spec['nodeAffinity']['required']['nodeSelectorTerms'][0]['matchExpressions'][0]['values'])
        claim = workload(STORAGE_CLASS)['items'][-1]['spec']['volumeClaimTemplates'][0]
        self.assertEqual('data', claim['metadata']['name'])
        self.assertEqual(STORAGE_CLASS, claim['spec']['storageClassName'])
        self.assertNotIn('volumeName', claim['spec'])  # WFFC scheduler owns binding, not a bypass.
        self.assertEqual('Prune=confirm', pv['metadata']['annotations']['argocd.argoproj.io/sync-options'])


if __name__ == '__main__':
    unittest.main()
