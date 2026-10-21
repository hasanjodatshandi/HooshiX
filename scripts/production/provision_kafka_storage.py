"""Independent 16GiB Kafka log foundation; no Kafka, PV, TLS or credentials."""
import argparse
import base64
import hashlib
import json
import os
import re
import resource
import signal
import types
import zlib
from pathlib import Path

SIZE = 16 * 1024**3
BASE = Path('/var/lib/hooshixstorage')
PRESERVED = ('ssh.service', 'wg-quick@wg-hooshix.service', 'auditd.service', 'k3s.service',
             'hooshix-openbao-storage-guard.service', 'hooshix-postgresql-storage-guard.service',
             'hooshix-redis-storage-guard.service',
             'caddy.service', 'nginx.service', 'postfix.service', 'dovecot.service', 'mariadb.service')
EXISTING_GUARDS = ('hooshix-openbao-storage-guard.service', 'hooshix-postgresql-storage-guard.service',
                   'hooshix-redis-storage-guard.service')


def storage(source):
    """Reuse the reviewed engine in isolated globals, leaving all three existing profiles intact."""
    if not isinstance(source, bytes) or not 0 < len(source) <= 32768:
        raise ValueError('VERIFIED_CORE_REQUIRED')
    n = {'__name__': 'hooshix_kafka_storage_core',
         '__file__': str(Path(__file__).with_name('provision_openbao_storage.py'))}
    exec(compile(source, n['__file__'], 'exec'), n)
    n.update(SIZE=SIZE, DATA_UID=10001, DATA_GID=10001, IMAGE=BASE / 'kafka.ext4',
        MOUNT=BASE / 'kafka', STATE=BASE / 'kafka-storage.json', LOCK=BASE / 'kafka-storage.lock',
        UNIT_NAME='var-lib-hooshixstorage-kafka.mount',
        UNIT=Path('/etc/systemd/system/var-lib-hooshixstorage-kafka.mount'),
        GUARD=BASE / 'kafka-storage-guard.py', GUARD_NAME='hooshix-kafka-storage-guard.service',
        GUARD_UNIT=Path('/etc/systemd/system/hooshix-kafka-storage-guard.service'),
        K3S_DROPIN=Path('/etc/systemd/system/k3s.service.d/36-hooshix-kafka-storage.conf'))
    n['UNIT_TEXT'] = (f'[Unit]\nDescription=HooshiX Kafka log storage (16GiB, non-HA)\nBefore=k3s.service\n\n'
        f'[Mount]\nWhat={n["IMAGE"]}\nWhere={n["MOUNT"]}\nType=ext4\nOptions={n["OPTIONS"]}\n'
        'TimeoutSec=30\n\n[Install]\nWantedBy=local-fs.target\n')
    n['GUARD_TEXT'] = n['guard_unit'](n['GUARD'], n['UNIT_NAME'], 'Kafka')
    n['DROPIN_TEXT'] = f'[Unit]\nBindsTo={n["GUARD_NAME"]}\nAfter={n["GUARD_NAME"]}\n'
    return types.SimpleNamespace(**n)


def guard_source(core, own):
    if any(not isinstance(b, bytes) or not 0 < len(b) <= 32768 for b in (core, own)):
        raise ValueError('VERIFIED_SOURCES_REQUIRED')
    packed = [base64.b64encode(zlib.compress(b)).decode('ascii') for b in (core, own)]
    program = ('import base64,zlib\n'
        f'core,own=[zlib.decompress(base64.b64decode(s)) for s in {packed!r}]\n'
        "exec(compile(own,'provision_kafka_storage.py','exec'),{'__name__':'__main__',"
        "'__file__':'provision_kafka_storage.py','__hooshix_source_bytes__':own,"
        "'__hooshix_core_source_bytes__':core})\n").encode()
    if len(program) > 32768:
        raise ValueError('BOUNDED_GUARD_REQUIRED')
    return program


def preserved(s):
    s.require(os.geteuid() == 0 and os.uname().nodename == s.HOST_NAME, 'WRONG_TARGET_OR_SUDO_REQUIRED')
    result = {}
    for name in PRESERVED:
        value = s.run(['/usr/bin/systemctl', 'show', name, '--property=ActiveState,MainPID'], timeout=5)
        if name in PRESERVED[:7]:
            s.require('ActiveState=active' in value.splitlines(), 'PRESERVED_SERVICE_NOT_ACTIVE')
        result[name] = value
    # Existing database files stay live: compare storage identity, not mutable database contents.
    for profile in ('openbao', 'postgresql', 'redis'):
        guard = BASE / (profile + '-storage-guard.py')
        s.parents(guard)
        s.protected(guard, mode=0o600)
        s.check_worker(guard)
        for suffix in ('.ext4', '-storage.json', '-storage-guard.py'):
            name = profile + suffix
            path = BASE / name
            info = s.protected(path, mode=0o600)
            result[name] = [info.st_dev, info.st_ino, info.st_size]
            if suffix != '.ext4':
                result[name].append(hashlib.sha256(path.read_bytes()).hexdigest())
    bindings = s.run(['/usr/bin/systemctl', 'show', 'k3s.service', '--property=BindsTo', '--value']).split()
    s.require(set(EXISTING_GUARDS) <= set(bindings), 'EXISTING_STORAGE_BINDING_MISSING')
    return result


def execute(core, own, approved=False):
    s = storage(core)
    before = preserved(s)
    s.run(['/usr/sbin/auditctl', '-m', 'HooshiX Kafka log storage foundation 16GiB'])
    if approved:
        for path, content in ((s.GUARD, guard_source(core, own)),
                (s.GUARD_UNIT, s.GUARD_TEXT.encode()), (s.K3S_DROPIN, s.DROPIN_TEXT.encode())):
            s.parents(path, missing=True)
            if path.exists() or path.is_symlink():
                s.protected(path, mode=0o600)
                s.require(path.read_bytes() == content, 'KAFKA_GUARD_INSTALL_CONFLICT')
        s.execute(True)
        s.require(not any((s.MOUNT / 'data').iterdir()), 'KAFKA_DATA_ALREADY_PRESENT')
        result = s.install_guard(guard_source(core, own))
    else:
        result = s.execute(False)
    s.require(preserved(s) == before, 'KAFKA_PRESERVED_STATE_CHANGED')
    result.update(kafka_storage=result.pop('storage_foundation'), runtime_uid=10001,
        existing_storage_host_services='Preserved', kafka='Not run',
        persistent_volume='Not run', secret_delivery='Not run', public_access='CLOSED',
        source_sha256=hashlib.sha256(own).hexdigest(), core_sha256=hashlib.sha256(core).hexdigest())
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    mode = p.add_mutually_exclusive_group()
    mode.add_argument('--install-approved-16gib', action='store_true')
    mode.add_argument('--guard', action='store_true', help=argparse.SUPPRESS)
    mode.add_argument('--guard-check', action='store_true', help=argparse.SUPPRESS)
    args = p.parse_args()
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    try:
        core, own = globals().get('__hooshix_core_source_bytes__'), globals().get('__hooshix_source_bytes__')
        s = storage(core)
        if args.guard:
            s.guard_loop(s.GUARD)
            return 0
        signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(ValueError('STORAGE_DEADLINE')))
        signal.alarm(900)
        if args.guard_check:
            s.guard_check()
        else:
            print(json.dumps(execute(core, own, args.install_approved_16gib), sort_keys=True))
    except Exception as error:
        reason = str(error) if error.__class__.__name__ == 'StorageFailed' else 'LOCAL_STATE_FAILED'
        if not re.fullmatch(r'[A-Z_]{1,80}', reason): reason = 'LOCAL_STATE_FAILED'
        print(json.dumps({'kafka_storage': 'Failed', 'reason': reason,
            'partial_state': 'Preserved; no automatic retry', 'production_readiness': 'Not verified'}))
        return 1
    finally:
        signal.alarm(0)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
