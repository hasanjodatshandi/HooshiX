import errno
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
import rehearse_openbao_storage as rehearsal


class OpenBaoStorageTest(unittest.TestCase):
    def test_install_is_explicit_fixed_size_without_path_override(self):
        self.assertEqual(8 * 1024**3, storage.SIZE)
        self.assertEqual('/var/lib/hooshix/storage/openbao.ext4', str(storage.IMAGE))
        self.assertEqual('/var/lib/hooshix/storage/openbao', str(storage.MOUNT))
        text = Path(storage.__file__).read_text()
        self.assertNotIn('hostPath', text)
        self.assertNotIn('kubectl', text)
        self.assertNotIn('shell=True', text)
        self.assertNotIn('truncate(', text)
        self.assertNotIn('--size', text)

    def test_nonroot_and_wrong_architecture_fail_before_mutation(self):
        with patch.object(os, 'geteuid', return_value=1000), patch.object(storage, 'run') as run:
            with self.assertRaisesRegex(storage.StorageFailed, 'LOCAL_SUDO_REQUIRED'):
                storage.execute(True)
            run.assert_not_called()
        with patch.object(os, 'geteuid', return_value=0), \
                patch.object(os, 'uname', return_value=SimpleNamespace(machine='aarch64')):
            with self.assertRaisesRegex(storage.StorageFailed, 'ARCHITECTURE'):
                storage.execute(True)

    def test_native_command_bound_clean_environment_and_output(self):
        result = Mock(returncode=0, stdout=b'public', stderr=b'never-expose-config')
        with patch.object(subprocess, 'run', return_value=result) as native:
            self.assertEqual('public', storage.run(['/usr/bin/findmnt']))
            self.assertEqual(30, native.call_args.kwargs['timeout'])
            self.assertEqual({'PATH', 'LC_ALL'}, set(native.call_args.kwargs['env']))
            self.assertNotIn('shell', native.call_args.kwargs)
            result.returncode = 1
            with self.assertRaisesRegex(storage.StorageFailed, '^NATIVE_COMMAND_FAILED$'):
                storage.run(['/usr/bin/findmnt'])
            result.returncode = 0
            result.stdout = b'x' * 65537
            with self.assertRaisesRegex(storage.StorageFailed, 'OVERSIZED'):
                storage.run(['/usr/bin/findmnt'])
        with patch.object(subprocess, 'run', side_effect=subprocess.TimeoutExpired('tool', 30)):
            with self.assertRaisesRegex(storage.StorageFailed, 'TIMEOUT'):
                storage.run(['/usr/bin/findmnt'])

    def test_protected_rejects_symlink_hardlink_owner_and_writable_paths(self):
        path = Mock()
        valid = dict(st_mode=stat.S_IFREG | 0o600, st_uid=0, st_gid=0, st_nlink=1)
        path.lstat.return_value = SimpleNamespace(**valid)
        storage.protected(path, mode=0o600)
        for field, value in [('st_mode', stat.S_IFLNK | 0o600), ('st_nlink', 2),
                             ('st_uid', 1000), ('st_gid', 1000), ('st_mode', stat.S_IFREG | 0o622)]:
            path.lstat.return_value = SimpleNamespace(**(valid | {field: value}))
            with self.assertRaises(storage.StorageFailed):
                storage.protected(path, mode=0o600)
        path.lstat.return_value = SimpleNamespace(**(valid | {'st_mode': stat.S_IFREG | 0o644}))
        with self.assertRaisesRegex(storage.StorageFailed, 'MODE'):
            storage.protected(path, mode=0o600)

    def test_format_never_accepts_arbitrary_size_or_block_device(self):
        with patch.object(storage, 'parents') as parents, patch.object(os, 'open') as opened:
            with self.assertRaisesRegex(storage.StorageFailed, 'INVALID_SIZE'):
                storage.reserve_and_format(Path('/dev/vda1'), 1024)
            parents.assert_not_called()
            opened.assert_not_called()

    def test_format_reserves_before_native_mkfs_without_discard_or_force(self):
        size = storage.SIZE
        file_info = SimpleNamespace(st_size=size, st_blocks=size // 512)
        with patch.object(storage, 'parents'), patch.object(os, 'open', return_value=7) as opened, \
                patch.object(os, 'posix_fallocate') as allocate, patch.object(os, 'fsync'), \
                patch.object(os, 'close'), patch.object(storage, 'protected', return_value=file_info), \
                patch.object(storage, 'run') as run:
            storage.reserve_and_format(Path('/safe/new.ext4'), size)
            self.assertTrue(opened.call_args_list[0].args[1] & os.O_EXCL)
            self.assertTrue(opened.call_args_list[0].args[1] & os.O_NOFOLLOW)
            allocate.assert_called_once_with(7, 0, size)
            argv = run.call_args.args[0]
            self.assertNotIn('-F', argv)
            self.assertIn('nodiscard,lazy_itable_init=0,lazy_journal_init=0', argv)
            self.assertEqual(120, run.call_args.kwargs['timeout'])

    def test_enospc_or_sparse_allocation_never_formats_or_claims_reserved(self):
        with patch.object(storage, 'parents'), patch.object(os, 'open', return_value=7), \
                patch.object(os, 'posix_fallocate', side_effect=OSError(errno.ENOSPC, 'full')), \
                patch.object(os, 'close'), patch.object(storage, 'run') as run:
            with self.assertRaises(OSError):
                storage.reserve_and_format(Path('/safe/new.ext4'), storage.SIZE)
            run.assert_not_called()
        info = SimpleNamespace(st_size=storage.SIZE, st_blocks=10)
        with patch.object(storage, 'parents'), patch.object(os, 'open', return_value=7), \
                patch.object(os, 'posix_fallocate'), patch.object(os, 'fsync'), patch.object(os, 'close'), \
                patch.object(storage, 'protected', return_value=info), patch.object(storage, 'run'):
            with self.assertRaisesRegex(storage.StorageFailed, 'FULLY_RESERVED'):
                storage.reserve_and_format(Path('/safe/new.ext4'), storage.SIZE)

    def test_thirty_percent_post_allocation_headroom_is_not_readiness(self):
        fs = SimpleNamespace(f_blocks=100, f_frsize=1, f_bavail=38)
        storage.headroom(8, fs)
        fs.f_bavail = 37
        with self.assertRaisesRegex(storage.StorageFailed, 'HEADROOM'):
            storage.headroom(8, fs)

    def test_existing_or_partial_image_is_never_reformatted(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            image, state, unit, mount = (root / name for name in ('image', 'state', 'unit', 'mount'))
            image.write_bytes(b'keep-existing-data')
            with patch.multiple(storage, IMAGE=image, STATE=state, UNIT=unit, MOUNT=mount), \
                    patch.object(storage, 'parents'), patch.object(storage, 'reserve_and_format') as format_disk:
                with self.assertRaisesRegex(storage.StorageFailed, 'PARTIAL_OR_FOREIGN'):
                    storage.install_locked()
                format_disk.assert_not_called()
            self.assertEqual(b'keep-existing-data', image.read_bytes())

    def test_exclusive_creation_preserves_existing_file_even_with_valid_size(self):
        with tempfile.TemporaryDirectory() as temp:
            image = Path(temp) / 'image'
            image.write_bytes(b'keep')
            with patch.object(storage, 'parents'), patch.object(storage, 'run') as run:
                with self.assertRaises(FileExistsError):
                    storage.reserve_and_format(image, storage.SIZE)
                run.assert_not_called()
            self.assertEqual(b'keep', image.read_bytes())

    def test_host_version_change_requires_review_without_installation(self):
        versions = '\n'.join(f'{name}={version}' for name, version in storage.HOST_PACKAGES.items())
        with patch.object(storage, 'run', return_value=versions):
            result = storage.metadata()
            self.assertEqual('Not verified', result['production_readiness'])
        with patch.object(storage, 'run', return_value=versions.replace('259.5', '260.0')):
            with self.assertRaisesRegex(storage.StorageFailed, 'PACKAGE_REVIEW'):
                storage.metadata()

    def test_foreign_unit_or_state_is_preserved(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            unit = root / 'unit'
            unit.write_text('[Mount]\nWhat=/dev/vda1\n')
            with patch.object(storage, 'UNIT', unit), patch.object(storage, 'parents'), \
                    patch.object(storage, 'protected'), patch.object(storage, 'reserve_and_format') as format_disk:
                with self.assertRaisesRegex(storage.StorageFailed, 'UNIT_CONFLICT'):
                    storage.install_locked()
                format_disk.assert_not_called()
            self.assertIn('/dev/vda1', unit.read_text())

    def test_unit_is_isolated_boot_enabled_not_ssh_or_k3s_override(self):
        self.assertIn('WantedBy=local-fs.target', storage.UNIT_TEXT)
        self.assertIn('TimeoutSec=30', storage.UNIT_TEXT)
        self.assertIn('nosuid,nodev,noexec,noatime', storage.UNIT_TEXT)
        self.assertNotIn('ExecStart', storage.UNIT_TEXT)
        self.assertNotIn('RequiredBy=k3s', storage.UNIT_TEXT)

    def test_ci_denied_off_runner_before_mkdir(self):
        with patch.object(os, 'geteuid', return_value=0), patch.dict(os.environ, {}, clear=True), \
                patch.object(tempfile, 'mkdtemp') as create:
            with self.assertRaises(storage.StorageFailed):
                rehearsal.rehearse()
            create.assert_not_called()
        with patch.object(os, 'geteuid', return_value=0), \
                patch.dict(os.environ, {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'self-hosted'}), \
                patch.object(tempfile, 'mkdtemp') as create:
            with self.assertRaisesRegex(storage.StorageFailed, 'HOSTED_RUNNER_ONLY'):
                rehearsal.rehearse()
            create.assert_not_called()

    def test_launcher_numeric_bootstrap_hashes_same_bytes_and_no_credentials(self):
        source = (Path(storage.__file__).parent / 'run-openbao-storage.ps1').read_text()
        self.assertIn('exec(compile(b,str(p)', source)
        self.assertIn('bytes([', source)
        self.assertIn('Get-FileHash', source)
        self.assertNotIn('Read-Host', source)
        self.assertNotIn('-ExecutionPolicy', source)
        self.assertIn('$InstallApproved8GiB', source)


if __name__ == '__main__':
    unittest.main()
