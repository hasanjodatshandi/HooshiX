"""The new data mount must never become an OpenBao mutation or a size override."""
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
import provision_openbao_storage as original
import provision_postgresql_storage as pg
import build_postgresql_storage_bundle as bundle


class PostgresqlStorageTest(unittest.TestCase):
    def setUp(self):
        self.core = Path(original.__file__).read_bytes()
        self.own = Path(pg.__file__).read_bytes()
        self.s = pg.storage(self.core)

    def test_profile_is_separate_and_openbao_defaults_unchanged(self):
        self.assertEqual(32 * 1024**3, self.s.SIZE)
        self.assertEqual((26, 26), (self.s.DATA_UID, self.s.DATA_GID))
        self.assertEqual(8 * 1024**3, original.SIZE)
        self.assertEqual((10001, 10001), (original.DATA_UID, original.DATA_GID))
        self.assertIsNot(self.s.check_mount.__globals__, original.check_mount.__globals__)
        for field in ('IMAGE', 'MOUNT', 'STATE', 'LOCK', 'UNIT', 'GUARD', 'GUARD_UNIT', 'K3S_DROPIN'):
            self.assertNotEqual(getattr(original, field), getattr(self.s, field))
        self.assertEqual(self.s.SIZE, self.s.reserve_and_format.__globals__['SIZE'])
        self.assertIn('32GiB', self.s.UNIT_TEXT)
        self.assertIn('PostgreSQL', self.s.GUARD_TEXT)

    def test_data_uid_and_modes_fail_closed(self):
        path = Mock()
        for uid, gid, mode, accepted in ((26, 26, 0o700, True), (26, 26, 0o2770, True),
                (10001, 10001, 0o700, False), (26, 0, 0o700, False), (26, 26, 0o777, False)):
            path.lstat.return_value = SimpleNamespace(st_uid=uid, st_gid=gid, st_mode=stat.S_IFDIR | mode)
            if accepted: self.s.data_owner(path)
            else:
                with self.assertRaises(self.s.StorageFailed): self.s.data_owner(path)

    def test_frozen_guard_is_bounded_and_does_not_use_user_imports(self):
        value = pg.guard_source(self.core, self.own)
        self.assertLess(len(value), 32768)
        compile(value, 'guard.py', 'exec')
        self.assertNotIn(b'runpy', value)
        result = subprocess.run([sys.executable, '-I', '-c', value.decode(), '--help'],
                                capture_output=True, timeout=10, check=False)
        self.assertEqual(0, result.returncode)
        self.assertIn(b'--install-approved-32gib', result.stdout)
        for a, b in ((None, self.own), (self.core, b''), (b'x' * 32769, self.own)):
            with self.assertRaises(ValueError): pg.guard_source(a, b)

    def test_profile_rejects_other_sizes_before_creating_files(self):
        with patch.object(os, 'open') as opened:
            with self.assertRaises(self.s.StorageFailed):
                self.s.reserve_and_format(self.s.IMAGE, original.SIZE)
        opened.assert_not_called()

    def test_source_required_before_execution(self):
        for value in (None, b'', b'x' * 32769):
            with self.assertRaises(ValueError): pg.storage(value)

    def test_new_guard_and_owner_operation_preserve_existing_services(self):
        s = Mock()
        s.MOUNT = MagicMock()
        (s.MOUNT / 'data').iterdir.return_value = iter([])
        s.GUARD, s.GUARD_UNIT, s.K3S_DROPIN = [Mock() for _ in range(3)]
        for path in (s.GUARD, s.GUARD_UNIT, s.K3S_DROPIN):
            path.exists.return_value = path.is_symlink.return_value = False
        s.GUARD_TEXT, s.DROPIN_TEXT = 'guard', 'dependency'
        s.run.return_value = 'hooshix-openbao-storage-guard.service'
        s.install_guard.return_value = {'storage_foundation': 'Passed', 'storage_guard': 'Passed'}
        def require(value, code):
            if not value: raise ValueError(code)
        s.require.side_effect = require
        with patch.object(pg, 'storage', return_value=s), patch.object(pg, 'preserved', return_value={'same': 1}):
            result = pg.execute(self.core, self.own, True)
        s.execute.assert_called_once_with(True)
        self.assertEqual('Preserved', result['openbao_host_services'])
        self.assertEqual('Not run', result['database'])
        self.assertEqual(hashlib.sha256(self.own).hexdigest(), result['source_sha256'])
        s.GUARD.exists.return_value = True
        s.GUARD.read_bytes.return_value = b'foreign'
        s.execute.reset_mock()
        with patch.object(pg, 'storage', return_value=s), patch.object(pg, 'preserved', return_value={}):
            with self.assertRaises(ValueError): pg.execute(self.core, self.own, True)
        s.execute.assert_not_called()

    def test_existing_data_is_retained_and_guard_binding_is_not_installed(self):
        s = Mock()
        s.MOUNT = MagicMock()
        (s.MOUNT / 'data').iterdir.return_value = iter(['existing-database'])
        s.GUARD, s.GUARD_UNIT, s.K3S_DROPIN = [Mock() for _ in range(3)]
        for path in (s.GUARD, s.GUARD_UNIT, s.K3S_DROPIN):
            path.exists.return_value = path.is_symlink.return_value = False
        s.GUARD_TEXT, s.DROPIN_TEXT = 'guard', 'dependency'
        s.require.side_effect = lambda value, code: None if value else (_ for _ in ()).throw(ValueError(code))
        with patch.object(pg, 'storage', return_value=s), patch.object(pg, 'preserved', return_value={}):
            with self.assertRaisesRegex(ValueError, 'POSTGRESQL_DATA_ALREADY_PRESENT'):
                pg.execute(self.core, self.own, True)
        s.install_guard.assert_not_called()

    def test_no_format_or_mount_if_preservation_preflight_fails(self):
        s = Mock()
        with patch.object(pg, 'storage', return_value=s), patch.object(pg, 'preserved', side_effect=ValueError('failed')):
            with self.assertRaises(ValueError): pg.execute(self.core, self.own, True)
        s.execute.assert_not_called()
        s.install_guard.assert_not_called()

    def test_native_evidence_binds_uid_sources_full_checks_and_tree(self):
        now = datetime.now(timezone.utc).isoformat()
        record = {'path': '.github/workflows/repository-baseline.yml', 'status': 'completed', 'conclusion': 'success',
            'repository': {'full_name': 'hasanjodatshandi/HooshiX'}, 'head_repository': {'full_name': 'hasanjodatshandi/HooshiX'},
            'head_sha': 'a' * 40, 'event': 'pull_request', 'updated_at': now}
        evidence = {'profile': 'postgresql-32gib', 'runtime_uid': 26, 'fixture_bytes': 64 * 1024**2,
            'source_sha256': hashlib.sha256(self.own).hexdigest(), 'core_sha256': hashlib.sha256(self.core).hexdigest(),
            'guard_checks': dict.fromkeys(bundle.GUARD_CHECKS, 'Passed'),
            **dict.fromkeys(('enospc_nonroot', 'reserved_size', 'systemd_remount_persistence', 'mount_options', 'cleanup'), 'Passed')}
        with patch.object(bundle.original, 'git', return_value='same-tree'):
            bundle.check(record, evidence, 'b' * 40)
            for change in ({'runtime_uid': 10001}, {'source_sha256': '0' * 64}, {'guard_checks': {}}, {'cleanup': 'Not run'}):
                with self.assertRaises(ValueError): bundle.check(record, evidence | change, 'b' * 40)
            with self.assertRaises(ValueError): bundle.check(record | {'conclusion': 'failure'}, evidence, 'b' * 40)
            with self.assertRaises(ValueError): bundle.check(record | {'updated_at': '2020-01-01T00:00:00Z'}, evidence, 'b' * 40)
            with self.assertRaises(ValueError): bundle.check(record | {'repository': {'full_name': 'other/repository'}}, evidence, 'b' * 40)
        with patch.object(bundle.original, 'git', side_effect=['old-tree', 'different-tree']):
            with self.assertRaises(ValueError): bundle.check(record, evidence, 'b' * 40)


if __name__ == '__main__':
    unittest.main()
