"""Kafka log allocation must preserve all three existing datastore foundations."""
import hashlib
import os
import stat
import subprocess
import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_kafka_storage_bundle as bundle
import provision_openbao_storage as original
import provision_postgresql_storage as pg
import provision_redis_storage as redis
import provision_kafka_storage as target


class KafkaStorageTest(unittest.TestCase):
    def setUp(self):
        self.core = Path(original.__file__).read_bytes()
        self.own = Path(target.__file__).read_bytes()
        self.s = target.storage(self.core)

    def test_profile_is_separate_from_all_existing_storage_engines(self):
        self.assertEqual(16 * 1024**3, self.s.SIZE)
        self.assertEqual((10001, 10001), (self.s.DATA_UID, self.s.DATA_GID))
        postgresql = pg.storage(self.core)
        self.assertEqual(32 * 1024**3, postgresql.SIZE)
        for existing in (original, postgresql, redis.storage(self.core)):
            self.assertIsNot(self.s.check_mount.__globals__, existing.check_mount.__globals__)
            for field in ('IMAGE', 'MOUNT', 'STATE', 'LOCK', 'UNIT', 'GUARD', 'GUARD_UNIT', 'K3S_DROPIN'):
                self.assertNotEqual(getattr(existing, field), getattr(self.s, field))
        self.assertIn('16GiB', self.s.UNIT_TEXT)
        self.assertIn('Kafka', self.s.GUARD_TEXT)
        self.assertEqual(self.s.SIZE, self.s.reserve_and_format.__globals__['SIZE'])

    def test_runtime_owner_modes_and_unsupported_sizes_fail_closed(self):
        path = Mock()
        for uid, gid, mode, accepted in ((10001, 10001, 0o700, True), (10001, 10001, 0o2770, True),
                (26, 26, 0o700, False), (10001, 0, 0o700, False), (10001, 10001, 0o777, False)):
            path.lstat.return_value = SimpleNamespace(st_uid=uid, st_gid=gid, st_mode=stat.S_IFDIR | mode)
            if accepted: self.s.data_owner(path)
            else:
                with self.assertRaises(self.s.StorageFailed): self.s.data_owner(path)
        with patch.object(os, 'open') as opened, self.assertRaises(self.s.StorageFailed):
            self.s.reserve_and_format(self.s.IMAGE, pg.SIZE)
        opened.assert_not_called()

    def test_frozen_guard_runs_without_user_directory_imports_and_sources_are_bounded(self):
        frozen = target.guard_source(self.core, self.own)
        self.assertLess(len(frozen), 32768)
        self.assertNotIn(b'runpy', frozen)
        result = subprocess.run([sys.executable, '-I', '-c', frozen.decode(), '--help'],
                                capture_output=True, timeout=10, check=False)
        self.assertEqual(0, result.returncode)
        self.assertIn(b'--install-approved-16gib', result.stdout)
        for value in (None, b'', b'x' * 32769):
            with self.assertRaises(ValueError): target.storage(value)
            with self.assertRaises(ValueError): target.guard_source(value, self.own)
            with self.assertRaises(ValueError): target.guard_source(self.core, value)

    def preserved_mock(self):
        s = Mock()
        s.HOST_NAME = os.uname().nodename
        s.protected.return_value = SimpleNamespace(st_dev=1, st_ino=2, st_size=3)
        s.run.side_effect = lambda argv, **kwargs: (' '.join(target.EXISTING_GUARDS)
            if '--property=BindsTo' in argv else 'ActiveState=active\nMainPID=1')
        s.require.side_effect = lambda value, code: None if value else (_ for _ in ()).throw(ValueError(code))
        return s

    def test_preservation_checks_all_guards_storage_identity_and_bindings_without_cluster_reads(self):
        s = self.preserved_mock()
        with patch.object(os, 'geteuid', return_value=0), patch.object(Path, 'read_bytes', return_value=b'reviewed'):
            result = target.preserved(s)
        self.assertEqual(3, s.check_worker.call_count)
        for profile in ('openbao', 'postgresql', 'redis'):
            self.assertEqual([1, 2, 3], result[profile + '.ext4'])
            self.assertEqual(4, len(result[profile + '-storage-guard.py']))
        self.assertFalse(any('kubectl' in call.args[0] for call in s.run.call_args_list))
        s.run.side_effect = lambda argv, **kwargs: ('hooshix-openbao-storage-guard.service'
            if '--property=BindsTo' in argv else 'ActiveState=active\nMainPID=1')
        with patch.object(os, 'geteuid', return_value=0), patch.object(Path, 'read_bytes', return_value=b'reviewed'):
            with self.assertRaisesRegex(ValueError, 'EXISTING_STORAGE_BINDING_MISSING'):
                target.preserved(s)
        for name in target.PRESERVED[:7]:
            s = self.preserved_mock()
            normal = s.run.side_effect
            s.run.side_effect = lambda argv, name=name, **kw: 'ActiveState=inactive' if name in argv else normal(argv)
            with patch.object(os, 'geteuid', return_value=0):
                with self.assertRaisesRegex(ValueError, 'PRESERVED_SERVICE_NOT_ACTIVE'): target.preserved(s)

    def operation_mock(self, contents=()):
        s = Mock()
        s.MOUNT = MagicMock()
        (s.MOUNT / 'data').iterdir.side_effect = lambda: iter(contents)
        s.GUARD, s.GUARD_UNIT, s.K3S_DROPIN = [Mock() for _ in range(3)]
        for path in (s.GUARD, s.GUARD_UNIT, s.K3S_DROPIN):
            path.exists.return_value = path.is_symlink.return_value = False
        s.GUARD_TEXT, s.DROPIN_TEXT = 'guard', 'dependency'
        s.install_guard.return_value = {'storage_foundation': 'Passed', 'storage_guard': 'Passed'}
        s.execute.return_value = {'storage_foundation': 'Not verified'}
        s.require.side_effect = lambda value, code: None if value else (_ for _ in ()).throw(ValueError(code))
        return s

    def test_approved_allocation_starts_guard_but_does_not_claim_kafka_or_volume(self):
        s = self.operation_mock()
        with patch.object(target, 'storage', return_value=s), patch.object(target, 'preserved', return_value={'same': 1}):
            result = target.execute(self.core, self.own, True)
        s.execute.assert_called_once_with(True)
        s.install_guard.assert_called_once_with(target.guard_source(self.core, self.own))
        self.assertEqual('Preserved', result['existing_storage_host_services'])
        for name in ('kafka', 'persistent_volume', 'secret_delivery'):
            self.assertEqual('Not run', result[name])
        self.assertEqual('CLOSED', result['public_access'])
        self.assertEqual(hashlib.sha256(self.own).hexdigest(), result['source_sha256'])

    def test_without_approval_only_existing_readonly_plan_is_called(self):
        s = self.operation_mock()
        with patch.object(target, 'storage', return_value=s), patch.object(target, 'preserved', return_value={}):
            target.execute(self.core, self.own)
        s.execute.assert_called_once_with(False)
        s.install_guard.assert_not_called()

    def test_preflight_or_foreign_guard_prevents_allocation(self):
        s = self.operation_mock()
        with patch.object(target, 'storage', return_value=s), patch.object(target, 'preserved', side_effect=ValueError('preflight')):
            with self.assertRaises(ValueError): target.execute(self.core, self.own, True)
        s.execute.assert_not_called()
        s.GUARD.exists.return_value = True
        s.GUARD.read_bytes.return_value = b'foreign'
        with patch.object(target, 'storage', return_value=s), patch.object(target, 'preserved', return_value={}):
            with self.assertRaisesRegex(ValueError, 'KAFKA_GUARD_INSTALL_CONFLICT'):
                target.execute(self.core, self.own, True)
        s.execute.assert_not_called()

    def test_existing_kafka_data_is_retained_no_new_guard_or_overwrite(self):
        s = self.operation_mock(('existing-log',))
        with patch.object(target, 'storage', return_value=s), patch.object(target, 'preserved', return_value={}):
            with self.assertRaisesRegex(ValueError, 'KAFKA_DATA_ALREADY_PRESENT'):
                target.execute(self.core, self.own, True)
        s.install_guard.assert_not_called()

    def test_preservation_drift_does_not_emit_success(self):
        s = self.operation_mock()
        with patch.object(target, 'storage', return_value=s), patch.object(target, 'preserved', side_effect=[{'pid': 1}, {'pid': 2}]):
            with self.assertRaisesRegex(ValueError, 'KAFKA_PRESERVED_STATE_CHANGED'):
                target.execute(self.core, self.own, True)

    def test_bundle_requires_exact_tree_fresh_authenticated_native_evidence_and_complete_failures(self):
        record = {'path': '.github/workflows/repository-baseline.yml', 'status': 'completed', 'conclusion': 'success',
            'repository': {'full_name': 'hasanjodatshandi/HooshiX'}, 'head_repository': {'full_name': 'hasanjodatshandi/HooshiX'},
            'head_sha': 'a' * 40, 'event': 'pull_request', 'updated_at': datetime.now(timezone.utc).isoformat()}
        evidence = {'profile': 'kafka-16gib', 'runtime_uid': 10001, 'fixture_bytes': 64 * 1024**2,
            'source_sha256': hashlib.sha256(self.own).hexdigest(), 'core_sha256': hashlib.sha256(self.core).hexdigest(),
            'guard_checks': dict.fromkeys(bundle.GUARD_CHECKS, 'Passed'),
            **dict.fromkeys(('enospc_nonroot', 'reserved_size', 'systemd_remount_persistence', 'mount_options', 'cleanup'), 'Passed')}
        with patch.object(bundle.original, 'git', return_value='same-tree'):
            bundle.check(record, evidence, 'b' * 40)
            for change in ({'profile': 'postgresql-32gib'}, {'runtime_uid': 26}, {'source_sha256': '0' * 64},
                           {'core_sha256': '0' * 64}, {'guard_checks': {}}, {'cleanup': 'Not run'}):
                with self.assertRaises(ValueError): bundle.check(record, evidence | change, 'b' * 40)
            for change in ({'conclusion': 'failure'}, {'updated_at': '2020-01-01T00:00:00Z'},
                           {'repository': {'full_name': 'other/repository'}}):
                with self.assertRaises(ValueError): bundle.check(record | change, evidence, 'b' * 40)
        with patch.object(bundle.original, 'git', side_effect=['old-tree', 'different-tree']):
            with self.assertRaises(ValueError): bundle.check(record, evidence, 'b' * 40)


if __name__ == '__main__':
    unittest.main()
