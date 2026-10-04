"""64MiB disposable GitHub-runner filesystem limit and systemd persistence check."""
import argparse
import errno
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

import provision_openbao_storage as storage


def wait_inactive(name):
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        state = storage.run(['/usr/bin/systemctl', 'show', name, '--property=ActiveState', '--value'])
        if state in ('inactive', 'failed'):
            return
        time.sleep(0.5)
    raise storage.StorageFailed('GUARD_DEPENDENT_STOP_TIMEOUT')


def guard_rehearsal(root, image, volume, mount_name, size):
    """Real systemd guard with a harmless sleep service, never the runner's K3s."""
    state = root / 'state.json'
    uuid = storage.run(['/usr/sbin/blkid', '-p', '-s', 'UUID', '-o', 'value', str(image)])
    receipt = {'schema_version': 1, 'bytes': size, 'uuid': uuid}
    storage.create_file(state, json.dumps(receipt).encode())
    source = root / 'source.py'
    script = root / 'guard.py'
    storage.create_file(source, Path(storage.__file__).read_bytes())
    wrapper = (f'import runpy,sys\nfrom pathlib import Path\nn=runpy.run_path({str(source)!r})\n'
               "n=n['check_mount'].__globals__\n"
               f"n['guard_check']=lambda:n['check_mount'](Path({str(image)!r}),"
               f'Path({str(volume)!r}),Path({str(state)!r}),{size})\n'
               f"n['guard_check']() if sys.argv[1]=='--guard-check' else n['guard_loop'](Path({str(script)!r}))\n")
    storage.create_file(script, wrapper.encode())
    guard_name = root.name + '-guard.service'
    dummy_name = root.name + '-dependent.service'
    guard_unit = Path('/etc/systemd/system') / guard_name
    dummy_unit = Path('/etc/systemd/system') / dummy_name
    created = []
    existing_bind = root / 'existing-bind'
    missing_bind = root / 'new-bind'
    try:
        for path, text in ((guard_unit, storage.guard_unit(script, mount_name)),
                           (dummy_unit, '[Service]\nType=exec\nExecStart=/usr/bin/sleep infinity\nRestart=no\n')):
            storage.create_file(path, text.encode())
            created.append(path)
        storage.run(['/usr/bin/systemd-analyze', 'verify', str(guard_unit), str(dummy_unit)])
        storage.run(['/usr/bin/systemctl', 'daemon-reload'])
        # Native kubelet-compatible permissions must not cause a false trip.
        os.chmod(volume / 'data', 0o2770)
        storage.run(['/usr/bin/systemctl', 'start', dummy_name], timeout=20)
        storage.run(['/usr/bin/systemctl', 'start', guard_name], timeout=20)
        storage.require(storage.run(['/usr/bin/systemctl', 'is-active', guard_name]) == 'active',
                        'GUARD_STARTUP_FAILED')
        # Mirror installer integration: attach a healthy guard to an already running service.
        dummy_unit.write_text(f'[Unit]\nBindsTo={guard_name}\nAfter={guard_name}\n'
                              '[Service]\nType=exec\nExecStart=/usr/bin/sleep infinity\nRestart=no\n')
        storage.run(['/usr/bin/systemctl', 'daemon-reload'])
        storage.require(guard_name in storage.run(['/usr/bin/systemctl', 'show', dummy_name,
                         '--property=BindsTo', '--value']).split(), 'LIVE_BIND_NOT_LOADED')
        storage.run(['/usr/bin/mount', '-o', 'remount,ro', str(volume)])
        wait_inactive(dummy_name)
        wait_inactive(guard_name)
        storage.run(['/usr/bin/mount', '-o', 'remount,rw', str(volume)])
        time.sleep(6)
        wait_inactive(dummy_name)  # No automatic restart/re-arm after fault recovery.
        # Inactive non-failed dependents may already be garbage-collected by systemd.
        # Reset only the failed guard; starting a dependent loads it again normally.
        storage.run(['/usr/bin/systemctl', 'reset-failed', guard_name])
        storage.run(['/usr/bin/systemctl', 'start', dummy_name], timeout=20)
        # Backing pathname replacement/loss must stop the dependent too.
        retained = root / 'retained.ext4'
        image.rename(retained)
        try:
            wait_inactive(dummy_name)
            wait_inactive(guard_name)
        finally:
            retained.rename(image)
        # A wrong UUID prevents READY=1 and therefore prevents dependent startup.
        state.write_text(json.dumps(receipt | {'uuid': '00000000-0000-0000-0000-000000000000'}))
        storage.run(['/usr/bin/systemctl', 'reset-failed', guard_name])
        denied = subprocess.run(['/usr/bin/systemctl', 'start', dummy_name], timeout=20,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        storage.require(denied.returncode != 0, 'INVALID_STORAGE_STARTUP_ALLOWED')
        wait_inactive(dummy_name)
        state.write_text(json.dumps(receipt))
        storage.run(['/usr/bin/systemctl', 'reset-failed', guard_name])
        storage.run(['/usr/bin/systemctl', 'start', dummy_name], timeout=20)
        existing_bind.mkdir(mode=0o700)
        missing_bind.mkdir(mode=0o700)
        storage.run(['/usr/bin/mount', '--bind', str(volume / 'data'), str(existing_bind)])
        marker_hash = hashlib.sha256((existing_bind / 'marker').read_bytes()).hexdigest()
        # Kernel unmount, not a systemctl stop: tests unexpected mount disappearance.
        storage.run(['/usr/bin/umount', str(volume)])
        wait_inactive(dummy_name)
        wait_inactive(guard_name)
        storage.protected(volume, directory=True, mode=0o700)
        storage.require(not any(volume.iterdir()), 'ROOT_FALLBACK_DIRECTORY_PRESENT')
        storage.require(hashlib.sha256((existing_bind / 'marker').read_bytes()).hexdigest() == marker_hash,
                        'EXISTING_BIND_CHANGED_FILESYSTEM')
        absent = subprocess.run(['/usr/bin/mount', '--bind', str(volume / 'data'), str(missing_bind)],
                                timeout=5, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        storage.require(absent.returncode != 0 and not os.path.ismount(missing_bind),
                        'ABSENT_SOURCE_BIND_ALLOWED')
        child = os.fork()
        if child == 0:
            try:
                os.setgroups([])
                os.setgid(10001)
                os.setuid(10001)
                (volume / 'data').mkdir()
            except PermissionError:
                os._exit(0)
            except Exception:
                os._exit(2)
            os._exit(1)
        _, status = os.waitpid(child, 0)
        storage.require(os.waitstatus_to_exitcode(status) == 0, 'NONROOT_ROOT_FALLBACK_ALLOWED')
        storage.run(['/usr/bin/systemctl', 'start', mount_name])
        return {label: 'Passed' for label in ('startup_identity', 'live_dependency_binding', 'fsGroup_permissions',
                'readonly_fault_stop', 'backing_loss_stop', 'unexpected_unmount_stop',
                'explicit_rearm_only', 'unmounted_nonroot_fallback_denied',
                'existing_bind_retained', 'absent_source_bind_denied')}
    finally:
        if created:
            storage.run(['/usr/bin/systemctl', 'stop', *(path.name for path in reversed(created))])
        for bind in (missing_bind, existing_bind):
            if os.path.ismount(bind):
                storage.run(['/usr/bin/umount', str(bind)])
        for path in reversed(created):
            path.unlink()
        storage.run(['/usr/bin/systemctl', 'daemon-reload'])


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
        guard_checks = guard_rehearsal(root, image, volume, name, size)
        result = {'schema_version': 1, 'fixture_bytes': size, 'enospc_nonroot': 'Passed',
                  'reserved_size': 'Passed', 'systemd_remount_persistence': 'Passed',
                  'mount_options': 'Passed', 'guard_checks': guard_checks, 'target_vps': 'Not verified',
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
