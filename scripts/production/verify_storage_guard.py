"""Owner-confirmed target maintenance; no installation, formatting or secret access."""
import argparse
import fcntl
import hashlib
import json
import os
import stat
import time
from datetime import datetime, timezone
from pathlib import Path

SOURCE = Path('/var/lib/hooshixstorage/openbao-storage-guard.py')
SOURCE_SHA256 = '2f061f547c5db602d1e7da3caa70c5c138e377b96a5948be9a2ae331a4f00933'
RECOVERY = 'hooshix-storage-maintenance-recovery'
K3S = 'k3s.service'
PRESERVED = ('ssh.service', 'caddy.service', 'nginx.service', 'postfix.service',
             'dovecot.service', 'mariadb.service', 'auditd.service',
             'wg-quick@wg-hooshix.service')


def load_storage():
    if os.geteuid() != 0 or os.uname().nodename != 'mail.hooshix.com':
        raise ValueError('WRONG_TARGET_OR_LOCAL_SUDO_REQUIRED')
    for path in (*reversed(SOURCE.parents), SOURCE):
        info = path.lstat()
        if info.st_uid != 0 or info.st_gid != 0 or info.st_mode & 0o022:
            raise ValueError('UNSAFE_INSTALLED_SOURCE')
        expected = stat.S_ISREG if path == SOURCE else stat.S_ISDIR
        if not expected(info.st_mode):
            raise ValueError('UNSAFE_INSTALLED_SOURCE')
    if info.st_nlink != 1 or stat.S_IMODE(info.st_mode) != 0o600:
        raise ValueError('UNSAFE_INSTALLED_SOURCE')
    with SOURCE.open('rb') as stream:
        content = stream.read(32769)
    if len(content) > 32768 or hashlib.sha256(content).hexdigest() != SOURCE_SHA256:
        raise ValueError('INSTALLED_SOURCE_REVIEW_REQUIRED')
    namespace = {'__name__': 'hooshix_verified_storage'}
    exec(compile(content, str(SOURCE), 'exec'), namespace)
    return namespace


def show(s, unit, property_name='ActiveState'):
    return s['run'](['/usr/bin/systemctl', 'show', unit,
                     '--property=' + property_name, '--value'], timeout=5)


def identities(s):
    result = {}
    for unit in PRESERVED:
        s['require'](show(s, unit) == 'active', 'PRESERVED_SERVICE_NOT_ACTIVE')
        pid = show(s, unit, 'MainPID')
        s['require'](pid.isdigit(), 'PRESERVED_SERVICE_PID_INVALID')
        result[unit] = pid
    return result


def ready(s):
    s['require'](s['run'](['/usr/local/bin/k3s', 'kubectl', '--request-timeout=15s',
                         'get', '--raw=/readyz'], timeout=20) == 'ok', 'K3S_API_NOT_READY')


def inactive(s, unit):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if show(s, unit) in ('inactive', 'failed'):
            return
        time.sleep(0.5)
    raise s['StorageFailed']('DEPENDENT_DID_NOT_STOP')


def recover(s):
    s['run'](['/usr/bin/systemctl', 'start', s['UNIT_NAME']], timeout=45)
    s['check_worker'](s['GUARD'])
    s['run'](['/usr/bin/systemctl', 'reset-failed', s['GUARD_NAME']], timeout=5)
    s['run'](['/usr/bin/systemctl', 'start', K3S], timeout=90)
    s['require'](show(s, s['GUARD_NAME']) == 'active' and show(s, K3S) == 'active',
                 'EXPLICIT_RECOVERY_FAILED')
    ready(s)


def verify(approved=False):
    s = load_storage()
    result = s['execute'](False)
    s['guard_check']()
    for path, text in ((s['GUARD_UNIT'], s['GUARD_TEXT']),
                       (s['K3S_DROPIN'], s['DROPIN_TEXT'])):
        s['parents'](path)
        s['protected'](path, mode=0o600)
        s['require'](path.read_text() == text, 'INSTALLED_UNIT_CONFLICT')
    guard = s['GUARD_NAME']
    s['require'](show(s, guard) == 'active' and show(s, K3S) == 'active', 'DEPENDENT_NOT_ACTIVE')
    for property_name in ('BindsTo', 'After'):
        s['require'](guard in show(s, K3S, property_name).split(), 'DEPENDENCY_MISSING')
    s['require'](show(s, guard, 'Restart') == 'no', 'AUTOMATIC_REARM_NOT_ALLOWED')
    ready(s)
    before = identities(s)
    result.update(storage_guard='Passed', mutation=False,
                  observed_at=datetime.now(timezone.utc).isoformat(),
                  target_process_fault='Not run', target_mount_loss='Not run',
                  target_startup_and_recovery='Not run')
    if not approved:
        return result
    # The mounted data directory and ALL cluster PVCs must still be empty.
    s['require'](not any((s['MOUNT'] / 'data').iterdir()), 'DATA_PRESENT_MAINTENANCE_REFUSED')
    claims = json.loads(s['run'](['/usr/local/bin/k3s', 'kubectl', '--request-timeout=15s',
                                'get', 'pvc', '--all-namespaces', '-o', 'json'], timeout=20))
    s['require'](isinstance(claims.get('items'), list) and not claims['items'],
                 'PVC_PRESENT_MAINTENANCE_REFUSED')
    for suffix in ('.timer', '.service'):
        s['require'](show(s, RECOVERY + suffix, 'LoadState') == 'not-found',
                     'RECOVERY_UNIT_ALREADY_EXISTS')
    # Owner-approved bounded safety recovery, not a normal automatic fault re-arm.
    # Starting K3s still requires the installed guard's validated READY=1.
    s['run'](['/usr/bin/systemd-run', '--unit=' + RECOVERY, '--on-active=480s',
              '--timer-property=AccuracySec=1s', '--property=TimeoutStartSec=120s',
              '/usr/bin/systemctl', 'start', K3S], timeout=10)
    s['require'](show(s, RECOVERY + '.timer') == 'active', 'RECOVERY_TIMER_NOT_ACTIVE')
    try:
        s['run'](['/usr/bin/systemctl', 'kill', '--kill-whom=main', '--signal=SIGKILL', guard], timeout=5)
        inactive(s, guard)
        inactive(s, K3S)
        result['target_process_fault'] = 'Passed'
        recover(s)
        s['run'](['/usr/bin/umount', str(s['MOUNT'])], timeout=10)
        inactive(s, guard)
        inactive(s, K3S)
        s['protected'](s['MOUNT'], directory=True, mode=0o700)
        s['require'](not any(s['MOUNT'].iterdir()), 'ROOT_FALLBACK_DIRECTORY_PRESENT')
        result['target_mount_loss'] = 'Passed'
        s['run'](['/usr/bin/systemctl', 'start', s['UNIT_NAME']], timeout=45)
        s['check_worker'](s['GUARD'])
        time.sleep(6)
        s['require'](show(s, K3S) in ('inactive', 'failed'), 'UNEXPECTED_AUTOMATIC_REARM')
    finally:
        # Never cancel the safety timer unless explicit recovery AND preserved services pass.
        recover(s)
        s['require'](identities(s) == before, 'PRESERVED_SERVICE_CHANGED')
        s['run'](['/usr/bin/systemctl', 'stop', RECOVERY + '.timer'], timeout=5)
    result.update(s['execute'](False))
    result.update(mutation=True, target_startup_and_recovery='Passed',
                  preserved_services='Passed; same PIDs and active states',
                  reboot_persistence='Not verified', cluster_storage='Not verified',
                  production_readiness='Not verified')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--approved-maintenance-with-rescue', action='store_true')
    args = parser.parse_args()
    try:
        if args.approved_maintenance_with_rescue:
            s = load_storage()
            lock = s['BASE'] / 'openbao-storage.lock'
            s['parents'](lock)
            descriptor = os.open(lock, os.O_RDWR | os.O_NOFOLLOW)
            try:
                s['protected'](lock, mode=0o600)
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                result = verify(True)
            finally:
                os.close(descriptor)
        else:
            result = verify(False)
    except Exception as error:
        # Native diagnostics and arbitrary exceptions never enter the public receipt.
        code = str(error) if error.__class__.__name__ == 'StorageFailed' else 'MAINTENANCE_CHECK_FAILED'
        print(json.dumps({'storage_guard_test': 'Failed', 'reason': code,
                          'recovery': 'Inspect safety timer/services; do not blindly retry',
                          'production_readiness': 'Not verified'}))
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
