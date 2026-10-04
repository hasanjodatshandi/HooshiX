"""64MiB disposable GitHub-runner filesystem limit and systemd persistence check."""
import argparse
import errno
import hashlib
import json
import os
import shutil
import tempfile
from pathlib import Path

import provision_openbao_storage as storage


def rehearse():
    storage.require(os.geteuid() == 0 and os.environ.get('GITHUB_ACTIONS') == 'true',
                    'DISPOSABLE_ROOT_RUNNER_ONLY')
    storage.require(os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted',
                    'GITHUB_HOSTED_RUNNER_ONLY')
    root = Path(tempfile.mkdtemp(prefix='hooshix-storage-ci-', dir='/var/lib'))
    os.chmod(root, 0o755)
    image, volume = root / 'fixture.ext4', root / 'volume'
    name = storage.run(['/usr/bin/systemd-escape', '--path', '--suffix=mount', str(volume)])
    unit = Path('/etc/systemd/system') / name
    size = 64 * 1024**2
    started = False
    try:
        volume.mkdir(mode=0o700)
        storage.reserve_and_format(image, size)
        text = (f'[Mount]\nWhat={image}\nWhere={volume}\nType=ext4\n'
                f'Options={storage.OPTIONS}\nTimeoutSec=30\n[Install]\nWantedBy=local-fs.target\n')
        storage.create_file(unit, text.encode())
        storage.run(['/usr/bin/systemd-analyze', 'verify', str(unit)])
        storage.run(['/usr/bin/systemctl', 'daemon-reload'])
        storage.run(['/usr/bin/systemctl', 'enable', name])
        started = True  # Partial start failures also require cleanup.
        storage.run(['/usr/bin/systemctl', 'start', name])
        os.chmod(volume, 0o755)
        data = volume / 'data'
        data.mkdir(mode=0o700)
        os.chown(data, 10001, 10001)
        marker = data / 'marker'
        # No secret fixture: persistence marker only, owned by the intended runtime UID.
        child = os.fork()
        if child == 0:
            try:
                os.setgroups([])
                os.setgid(10001)
                os.setuid(10001)
                marker.write_bytes(b'HooshiX-storage-fixture')
                chunk = bytes(1024**2)
                filled = 0
                try:
                    with (data / 'fill').open('wb', buffering=0) as stream:
                        for _ in range(65):
                            filled += stream.write(chunk)
                            os.fsync(stream.fileno())
                except OSError as error:
                    if error.errno != errno.ENOSPC or filled >= size:
                        os._exit(2)
                else:
                    os._exit(3)
                (data / 'fill').unlink()
                os._exit(0)
            except Exception:
                os._exit(4)
        _, status = os.waitpid(child, 0)
        storage.require(os.waitstatus_to_exitcode(status) == 0, 'NON_ROOT_ENOSPC_TEST_FAILED')
        before = hashlib.sha256(marker.read_bytes()).hexdigest()
        storage.run(['/usr/bin/systemctl', 'stop', name])
        storage.require(not os.path.ismount(volume), 'STOP_DID_NOT_UNMOUNT')
        storage.run(['/usr/bin/systemctl', 'start', name])
        storage.require(hashlib.sha256(marker.read_bytes()).hexdigest() == before, 'PERSISTENCE_FAILED')
        fs = os.statvfs(volume)
        storage.require(0 < fs.f_blocks * fs.f_frsize <= size, 'FILESYSTEM_LIMIT_FAILED')
        storage.require(storage.protected(image, mode=0o600).st_blocks * 512 >= size,
                        'PREALLOCATION_LOST')
        storage.require(storage.run(['/usr/bin/systemctl', 'is-enabled', name]) == 'enabled',
                        'BOOT_ENABLE_FAILED')
        mount = json.loads(storage.run(['/usr/bin/findmnt', '--json', '--mountpoint', str(volume),
                                       '-o', 'OPTIONS']))['filesystems'][0]
        storage.require({'rw', 'nodev', 'nosuid', 'noexec', 'noatime'} <= set(mount['options'].split(',')),
                        'OPTIONS_TEST_FAILED')
        result = {'schema_version': 1, 'fixture_bytes': size, 'enospc_nonroot': 'Passed',
                  'reserved_size': 'Passed', 'systemd_remount_persistence': 'Passed',
                  'mount_options': 'Passed', 'target_vps': 'Not verified',
                  'reboot_persistence': 'Not verified', 'production_readiness': 'Not verified'}
    finally:
        if started:
            storage.run(['/usr/bin/systemctl', 'stop', name])
            storage.run(['/usr/bin/systemctl', 'disable', name])
        storage.require(not os.path.ismount(volume), 'CLEANUP_REFUSES_MOUNTED_DIRECTORY')
        if unit.exists():
            unit.unlink()
            storage.run(['/usr/bin/systemctl', 'daemon-reload'])
        # Exact root-owned, uniquely created fixture only; never production paths.
        shutil.rmtree(root)
    result['cleanup'] = 'Passed'
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ci', required=True, action='store_true')
    parser.parse_args()
    print(json.dumps(rehearse(), sort_keys=True))
