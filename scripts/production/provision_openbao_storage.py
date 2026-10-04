"""Fixed 8GiB VPS storage foundation. No cluster, secrets or partition changes."""
import argparse
import fcntl
import json
import os
import stat
import subprocess
from pathlib import Path

SIZE = 8 * 1024**3
HOST_NAME = 'mail.hooshix.com'
BASE = Path('/var/lib/hooshixstorage')
IMAGE = BASE / 'openbao.ext4'
MOUNT = BASE / 'openbao'
STATE = BASE / 'openbao-storage.json'
UNIT_NAME = 'var-lib-hooshixstorage-openbao.mount'
UNIT = Path('/etc/systemd/system') / UNIT_NAME
OPTIONS = 'loop,nosuid,nodev,noexec,noatime,errors=remount-ro'
HOST_PACKAGES = {'e2fsprogs': '1.47.2-3ubuntu4', 'python3': '3.14.3-0ubuntu2',
                 'systemd': '259.5-0ubuntu3.4', 'util-linux': '2.41.3-3ubuntu2.2'}
UNIT_TEXT = f'''[Unit]
Description=HooshiX bounded OpenBao storage (8GiB, non-HA)
Before=k3s.service

[Mount]
What={IMAGE}
Where={MOUNT}
Type=ext4
Options={OPTIONS}
TimeoutSec=30

[Install]
WantedBy=local-fs.target
'''


class StorageFailed(Exception):
    """Public bounded diagnostic only; never echo native configuration/output."""


def require(condition, code):
    if not condition:
        raise StorageFailed(code)


def run(args, timeout=30):
    try:
        result = subprocess.run(args, check=False, capture_output=True, timeout=timeout,
                                env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LC_ALL': 'C'})
    except (OSError, subprocess.TimeoutExpired) as error:
        raise StorageFailed('NATIVE_COMMAND_UNAVAILABLE_OR_TIMEOUT') from error
    require(result.returncode == 0, 'NATIVE_COMMAND_FAILED')
    require(len(result.stdout) <= 65536, 'NATIVE_OUTPUT_OVERSIZED')
    return result.stdout.decode('utf-8', errors='strict').strip()


def protected(path, directory=False, uid=0, gid=0, mode=None):
    info = path.lstat()
    require((stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)),
            'UNSAFE_PATH_TYPE')
    require(info.st_uid == uid and info.st_gid == gid, 'UNSAFE_PATH_OWNER')
    require(not info.st_mode & 0o022, 'UNSAFE_PATH_WRITABLE')
    if not directory:
        require(info.st_nlink == 1, 'UNSAFE_HARDLINK')
    if mode is not None:
        require(stat.S_IMODE(info.st_mode) == mode, 'UNSAFE_PATH_MODE')
    return info


def parents(path, missing=False):
    for parent in reversed(path.parents):
        if missing and not parent.exists() and not parent.is_symlink():
            continue
        protected(parent, directory=True)


def create_file(path, content):
    # Parent chain is root-owned and not writable by non-root. Never overwrite.
    parents(path)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())
    directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def reserve_and_format(path, size):
    require(size in (SIZE, 64 * 1024**2), 'INVALID_SIZE')
    parents(path)
    fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        os.posix_fallocate(fd, 0, size)
        os.fsync(fd)
    finally:
        os.close(fd)
    # No -F, no block devices, no discard or lazy initialization. New file only.
    require(protected(path, mode=0o600).st_size == size, 'IMAGE_SIZE_MISMATCH')
    run(['/usr/sbin/mkfs.ext4', '-q', '-b', '4096', '-m', '5', '-E',
         'nodiscard,lazy_itable_init=0,lazy_journal_init=0', str(path)], timeout=120)
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    info = protected(path, mode=0o600)
    require(info.st_blocks * 512 >= size, 'IMAGE_NOT_FULLY_RESERVED')


def headroom(size, filesystem):
    available = filesystem.f_bavail * filesystem.f_frsize
    total = filesystem.f_blocks * filesystem.f_frsize
    require(available - size >= (total * 30 + 99) // 100, 'ROOT_HEADROOM_BELOW_30_PERCENT')


def metadata():
    # Record versions of existing host tools; never install/upgrade packages here.
    versions = run(['/usr/bin/dpkg-query', '-W', '-f=${Package}=${Version}\n',
                    'util-linux', 'e2fsprogs', 'systemd', 'python3'])
    packages = dict(line.split('=', 1) for line in versions.splitlines())
    require(packages == HOST_PACKAGES, 'HOST_PACKAGE_REVIEW_REQUIRED')
    return {'host_packages': versions.splitlines(), 'schema_version': 1,
            'backing_bytes': SIZE, 'production_readiness': 'Not verified',
            'reboot_persistence': 'Not verified', 'cluster_storage': 'Not verified'}


def verify(state):
    info = protected(IMAGE, mode=0o600)
    require(info.st_size == SIZE and info.st_blocks * 512 >= SIZE, 'IMAGE_RESERVATION_MISMATCH')
    protected(STATE, mode=0o600)
    protected(UNIT, mode=0o600)
    require(UNIT.read_text() == UNIT_TEXT, 'MOUNT_UNIT_CONFLICT')
    uuid = run(['/usr/sbin/blkid', '-p', '-s', 'UUID', '-o', 'value', str(IMAGE)])
    require(state == {'schema_version': 1, 'bytes': SIZE, 'uuid': uuid}, 'STORAGE_STATE_MISMATCH')
    mounts = json.loads(run(['/usr/bin/findmnt', '--json', '--mountpoint', str(MOUNT),
                             '--output', 'TARGET,SOURCE,FSTYPE,OPTIONS']))['filesystems']
    require(len(mounts) == 1, 'MOUNT_AMBIGUOUS')
    mounted = mounts[0]
    require(mounted['target'] == str(MOUNT) and mounted['fstype'] == 'ext4', 'WRONG_FILESYSTEM')
    options = set(mounted['options'].split(','))
    require({'rw', 'nosuid', 'nodev', 'noexec', 'noatime'} <= options and 'discard' not in options,
            'MOUNT_OPTIONS_UNSAFE')
    source = mounted['source']
    require(source.startswith('/dev/loop') and source[9:].isdigit(), 'NOT_LOOP_DEVICE')
    backing = run(['/usr/sbin/losetup', '--noheadings', '--output', 'BACK-FILE', source])
    require(backing == str(IMAGE), 'WRONG_BACKING_FILE')
    fs = os.statvfs(MOUNT)
    require(0 < fs.f_blocks * fs.f_frsize <= SIZE, 'FILESYSTEM_LIMIT_MISMATCH')
    protected(MOUNT, directory=True, mode=0o755)
    protected(MOUNT / 'data', directory=True, uid=10001, gid=10001, mode=0o700)
    require(run(['/usr/bin/systemctl', 'is-enabled', UNIT_NAME]) == 'enabled', 'UNIT_NOT_ENABLED')
    require(run(['/usr/bin/systemctl', 'is-active', UNIT_NAME]) == 'active', 'UNIT_NOT_ACTIVE')
    result = metadata()
    result.update(storage_foundation='Passed', filesystem_bytes=fs.f_blocks * fs.f_frsize,
                  available_bytes=fs.f_bavail * fs.f_frsize, mounted=True)
    return result


def execute(install=False):
    require(os.geteuid() == 0, 'LOCAL_SUDO_REQUIRED')
    require(os.uname().machine == 'x86_64', 'HOST_ARCHITECTURE_UNSUPPORTED')
    require(os.uname().nodename == HOST_NAME, 'WRONG_TARGET_HOST')
    parents(BASE, missing=True)
    # No changes occur before host metadata/capacity/tool checks succeed.
    result = metadata()
    for tool in ('/usr/sbin/mkfs.ext4', '/usr/sbin/blkid', '/usr/sbin/losetup',
                 '/usr/bin/findmnt', '/usr/bin/systemctl'):
        require(Path(tool).is_file() and os.access(tool, os.X_OK), 'HOST_TOOL_MISSING')
    if not install:
        if STATE.exists():
            protected(BASE, directory=True, mode=0o755)
            protected(STATE, mode=0o600)
            return verify(json.loads(STATE.read_text()))
        require(not STATE.is_symlink(), 'UNSAFE_PATH_TYPE')
        headroom(SIZE, os.statvfs('/var/lib'))
        result.update(storage_foundation='Not verified', planned_bytes=SIZE, mutation=False)
        return result
    for directory in (BASE,):
        if not directory.exists():
            directory.mkdir(mode=0o755)
            os.chmod(directory, 0o755)
        protected(directory, directory=True, mode=0o755)
    lock_path = BASE / 'openbao-storage.lock'
    descriptor = os.open(lock_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        protected(lock_path, mode=0o600)
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return install_locked()
    finally:
        os.close(descriptor)


def install_locked():
    parents(UNIT)
    if UNIT.exists() or UNIT.is_symlink():
        protected(UNIT, mode=0o600)
        require(UNIT.read_text() == UNIT_TEXT, 'MOUNT_UNIT_CONFLICT')
    if STATE.exists() or STATE.is_symlink():
        protected(STATE, mode=0o600)
        state = json.loads(STATE.read_text())
        protected(IMAGE, mode=0o600)
        require(protected(IMAGE).st_size == SIZE, 'IMAGE_SIZE_MISMATCH')
        uuid = run(['/usr/sbin/blkid', '-p', '-s', 'UUID', '-o', 'value', str(IMAGE)])
        require(state == {'schema_version': 1, 'bytes': SIZE, 'uuid': uuid}, 'STORAGE_STATE_MISMATCH')
    else:
        require(not IMAGE.exists() and not IMAGE.is_symlink() and not UNIT.exists(),
                'PARTIAL_OR_FOREIGN_STORAGE_PRESERVED')
        require(not MOUNT.exists() and not MOUNT.is_symlink(), 'MOUNTPOINT_ALREADY_EXISTS')
        headroom(SIZE, os.statvfs(BASE))
        MOUNT.mkdir(mode=0o700)
        reserve_and_format(IMAGE, SIZE)
        uuid = run(['/usr/sbin/blkid', '-p', '-s', 'UUID', '-o', 'value', str(IMAGE)])
        require(len(uuid) == 36, 'FILESYSTEM_UUID_MISSING')
        state = {'schema_version': 1, 'bytes': SIZE, 'uuid': uuid}
        create_file(STATE, (json.dumps(state, sort_keys=True) + '\n').encode())
    if not os.path.ismount(MOUNT):
        protected(MOUNT, directory=True, mode=0o700)
        require(not any(MOUNT.iterdir()), 'UNMOUNTED_DIRECTORY_NOT_EMPTY')
    if not UNIT.exists():
        create_file(UNIT, UNIT_TEXT.encode())
    run(['/usr/bin/systemd-analyze', 'verify', str(UNIT)])
    run(['/usr/bin/systemctl', 'daemon-reload'])
    run(['/usr/bin/systemctl', 'enable', UNIT_NAME])
    run(['/usr/bin/systemctl', 'start', UNIT_NAME], timeout=45)
    # Before any ownership mutation, prove this is exactly the allocated loop filesystem.
    source = run(['/usr/bin/findmnt', '--noheadings', '--mountpoint', str(MOUNT), '-o', 'SOURCE'])
    require(source.startswith('/dev/loop') and source[9:].isdigit(), 'NOT_LOOP_DEVICE')
    require(run(['/usr/sbin/losetup', '--noheadings', '-O', 'BACK-FILE', source]) == str(IMAGE),
            'WRONG_BACKING_FILE')
    os.chmod(MOUNT, 0o755)
    data = MOUNT / 'data'
    if not data.exists() and not data.is_symlink():
        data.mkdir(mode=0o700)
        os.chown(data, 10001, 10001)
    return verify(state)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--install-approved-8gib', action='store_true',
                        help='Create fixed VPS-only storage; requires owner-approved local sudo')
    args = parser.parse_args()
    try:
        print(json.dumps(execute(args.install_approved_8gib), sort_keys=True))
    except (StorageFailed, OSError, ValueError, KeyError) as error:
        code = str(error) if isinstance(error, StorageFailed) else 'LOCAL_STATE_FAILED'
        print(json.dumps({'storage_foundation': 'Failed', 'reason': code,
                          'partial_state': 'Preserved; inspect before retry',
                          'production_readiness': 'Not verified'}))
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
