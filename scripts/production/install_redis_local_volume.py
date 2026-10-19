"""Reserve existing guarded storage only; never create a PVC or Redis."""
import argparse
import copy
import fcntl
import hashlib
import json
import os
import re
import resource
import signal
import stat
import subprocess
from pathlib import Path

MANAGER = 'hooshix-redis-local-volume'
NODE = 'hooshix-production-1'
NAMESPACE = 'platform-data'
CLASS = 'hooshix-redis-local'
VOLUME = 'hooshix-redis-local-7gib'
CLAIM = 'data-security-redis-0'
CAPACITY = 7 * 1024**3
DATA = Path('/var/lib/hooshixstorage/redis/data')
GUARD = Path('/var/lib/hooshixstorage/redis-storage-guard.py')
GUARD_SHA256 = 'c3c8d4a14f1f90e20ce5483818327d02a740d9574e860495f2ef7a74bc2be224'
UNITS = ('ssh.service', 'wg-quick@wg-hooshix.service', 'auditd.service', 'k3s.service',
         'hooshix-openbao-storage-guard.service', 'hooshix-postgresql-storage-guard.service',
         'hooshix-redis-storage-guard.service',
         'caddy.service', 'nginx.service', 'postfix.service', 'dovecot.service', 'mariadb.service')


def require(value, code):
    if not value:
        raise ValueError(code)


def native(argv, body=None):
    r = subprocess.run(argv, input=json.dumps(body).encode() if body is not None else None,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=25, check=False,
        env={'PATH': '/usr/bin:/bin', 'LC_ALL': 'C', 'HOME': '/root'})
    require(r.returncode == 0 and len(r.stdout) <= 1024**2, 'NATIVE_OPERATION_FAILED')
    return r.stdout


def kube(*args, body=None):
    return native(['/usr/local/bin/k3s', 'kubectl', '--request-timeout=15s', *args], body)


def candidate():
    def metadata(name):
        return {'name': name, 'labels': {'app.kubernetes.io/managed-by': MANAGER},
                'annotations': {'argocd.argoproj.io/sync-options': 'Prune=confirm'}}
    return [
        {'apiVersion': 'storage.k8s.io/v1', 'kind': 'StorageClass', 'metadata': metadata(CLASS),
         'provisioner': 'kubernetes.io/no-provisioner', 'reclaimPolicy': 'Retain',
         'volumeBindingMode': 'WaitForFirstConsumer', 'allowVolumeExpansion': False},
        {'apiVersion': 'v1', 'kind': 'PersistentVolume', 'metadata': metadata(VOLUME), 'spec': {
            'capacity': {'storage': '7Gi'}, 'volumeMode': 'Filesystem', 'accessModes': ['ReadWriteOnce'],
            'persistentVolumeReclaimPolicy': 'Retain', 'storageClassName': CLASS,
            'local': {'path': str(DATA)}, 'claimRef': {'namespace': NAMESPACE, 'name': CLAIM},
            'nodeAffinity': {'required': {'nodeSelectorTerms': [{'matchExpressions': [
                {'key': 'kubernetes.io/hostname', 'operator': 'In', 'values': [NODE]}]}]}}}}]


def matches(actual, desired):
    if actual['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') != MANAGER:
        return False
    annotations = actual['metadata'].get('annotations', {})
    if (actual['metadata'].get('deletionTimestamp')
            or annotations.get('argocd.argoproj.io/sync-options') != 'Prune=confirm'):
        return False
    if any(annotations.get(k) == 'true' for k in ('storageclass.kubernetes.io/is-default-class',
            'storageclass.beta.kubernetes.io/is-default-class')):
        return False
    if actual['kind'] == 'StorageClass':
        fields = ('provisioner', 'reclaimPolicy', 'volumeBindingMode', 'allowVolumeExpansion')
        return (all(actual.get(k) == desired[k] for k in fields)
                and not any(actual.get(k) for k in ('parameters', 'mountOptions', 'allowedTopologies')))
    spec = copy.deepcopy(actual['spec'])
    # API defaulting may add these two fields before a claim exists; a bound UID is not adopted.
    ref = spec.get('claimRef', {})
    if ref.get('apiVersion', 'v1') != 'v1' or ref.get('kind', 'PersistentVolumeClaim') != 'PersistentVolumeClaim':
        return False
    ref.pop('apiVersion', None)
    ref.pop('kind', None)
    return spec == desired['spec'] and actual.get('status', {}).get('phase') in (None, 'Pending', 'Available')


def reserve(objects, call=kube):
    require(objects == candidate(), 'EXACT_RESERVATION_REQUIRED')
    current = []
    # Check BOTH objects before any write; never overwrite or forcibly adopt existing state.
    for desired in objects:
        raw = call('get', desired['kind'], desired['metadata']['name'], '--ignore-not-found', '-o', 'json')
        old = json.loads(raw) if raw.strip() else None
        require(not old or matches(old, desired), 'FOREIGN_OR_CHANGED_VOLUME_PRESERVED')
        current.append(old)
    for desired, old in zip(objects, current):
        if old is None:
            call('create', '-f', '-', body=desired)
    for desired in objects:
        actual = json.loads(call('get', desired['kind'], desired['metadata']['name'], '-o', 'json'))
        require(matches(actual, desired), 'RESERVATION_VERIFICATION_FAILED')


def verify_data(*, initialized=False):
    info = DATA.lstat()
    require(stat.S_ISDIR(info.st_mode) and (info.st_uid, info.st_gid) == (10001, 10001)
        and stat.S_IMODE(info.st_mode) in (0o700, 0o770, 0o2770), 'GUARDED_DATA_OWNER_REQUIRED')
    if not initialized:
        require(not any(DATA.iterdir()), 'EMPTY_GUARDED_DATA_REQUIRED')
    else:
        require(all(p.name == 'appendonlydir' and p.is_dir() and not p.is_symlink()
                    for p in DATA.iterdir()), 'UNEXPECTED_REDIS_DATA_PRESERVED')
    fs = os.statvfs(DATA)
    reserve = fs.f_blocks * fs.f_frsize * 3 // 10 if initialized else CAPACITY
    require(fs.f_bavail * fs.f_frsize >= reserve, 'USABLE_CAPACITY_BELOW_RESERVATION')


def foundation(*, initialized=False):
    require(os.geteuid() == 0 and os.uname().nodename == 'mail.hooshix.com', 'LOCAL_SUDO_TARGET_REQUIRED')
    for path in (*reversed(GUARD.parents), GUARD):
        info = path.lstat()
        require(info.st_uid == 0 and info.st_gid == 0 and not info.st_mode & 0o022
            and (stat.S_ISREG(info.st_mode) if path == GUARD else stat.S_ISDIR(info.st_mode)), 'UNSAFE_GUARD_PATH')
    require(info.st_nlink == 1 and stat.S_IMODE(info.st_mode) == 0o600
        and GUARD.stat().st_size <= 32768 and hashlib.sha256(GUARD.read_bytes()).hexdigest() == GUARD_SHA256,
        'REVIEWED_INSTALLED_GUARD_REQUIRED')
    native(['/usr/bin/python3', '-I', str(GUARD), '--guard-check'])
    verify_data(initialized=initialized)
    units = {u: native(['/usr/bin/systemctl', 'show', u, '--property=ActiveState,MainPID']) for u in UNITS}
    require(all(b'ActiveState=active' in units[u].splitlines() for u in UNITS[:7]), 'REQUIRED_SERVICE_NOT_ACTIVE')
    bindings = native(['/usr/bin/systemctl', 'show', 'k3s.service', '--property=BindsTo', '--value']).decode().split()
    require(set(UNITS[4:7]) <= set(bindings), 'ALL_STORAGE_GUARDS_REQUIRED')
    audit = native(['/usr/sbin/auditctl', '-s']).decode().splitlines()
    require('lost 0' in audit and any(x in audit for x in ('enabled 1', 'enabled 2')), 'PROTECTED_AUDIT_REQUIRED')
    return units


def execute(call=kube, verify=foundation):
    before = verify()
    ns = json.loads(call('get', 'namespace', NAMESPACE, '-o', 'json'))['metadata']['labels']
    require(ns.get('istio.io/dataplane-mode') == 'ambient'
        and ns.get('pod-security.kubernetes.io/enforce') == 'restricted', 'DATA_NAMESPACE_BOUNDARY_REQUIRED')
    closed = json.loads(call('get', 'validatingpolicy', 'hooshix-platform-data-closed', '-o', 'json'))
    spec = closed['spec']
    require(spec.get('validationActions') == ['Deny'] and spec.get('failurePolicy') == 'Fail'
        and spec.get('evaluation', {}).get('admission', {}).get('enabled') is True
        and closed.get('status', {}).get('conditionStatus', {}).get('ready') is True,
        'ACTIVE_DATA_ADMISSION_REQUIRED')
    # Running PostgreSQL/ESO are expected; only the new Redis claim must be absent.
    require(not call('-n', NAMESPACE, 'get', 'pvc', CLAIM, '--ignore-not-found', '-o', 'json').strip(),
        'REDIS_CLAIM_ALREADY_PRESENT')
    nodes = json.loads(call('get', 'nodes', '-o', 'json'))['items']
    require(len(nodes) == 1 and nodes[0]['metadata']['name'] == NODE
        and nodes[0]['metadata']['labels'].get('kubernetes.io/hostname') == NODE
        and any(c['type'] == 'Ready' and c['status'] == 'True' for c in nodes[0]['status']['conditions']), 'EXACT_READY_NODE_REQUIRED')
    bindings = {}
    for namespace, name, expected in (('hooshix-secrets', 'data-openbao-0', 'hooshix-openbao-local-8gib'),
            (NAMESPACE, 'postgresql-1', 'hooshix-postgresql-local-29gib')):
        value = json.loads(call('-n', namespace, 'get', 'pvc', name, '-o', 'json'))
        require(value['status']['phase'] == 'Bound' and value['spec']['volumeName'] == expected,
            'EXISTING_DATABASE_BINDING_REQUIRED')
        bindings[(namespace, name)] = value
    reserve(candidate(), call)
    for (namespace, name), before_claim in bindings.items():
        require(json.loads(call('-n', namespace, 'get', 'pvc', name, '-o', 'json')) == before_claim,
            'PRESERVED_DATABASE_BINDING_CHANGED')
    after = json.loads(call('get', 'validatingpolicy', 'hooshix-platform-data-closed', '-o', 'json'))
    # Controller status/resourceVersion may advance; policy identity and security spec must not.
    require(after['metadata']['uid'] == closed['metadata']['uid'] and after['spec'] == spec
        and after.get('status', {}).get('conditionStatus', {}).get('ready') is True
        and verify() == before, 'PRESERVED_FOUNDATION_CHANGED')
    return {'schema_version': 1, 'redis_local_volume': 'Passed', 'volume': VOLUME,
        'storage_class': CLASS, 'capacity_bytes': CAPACITY, 'node': NODE,
        'reserved_claim': NAMESPACE + '/' + CLAIM, 'storage_guard': 'Passed',
        'existing_storage_host_services': 'Preserved', 'redis': 'Not run', 'pvc': 'Not run',
        'secret_delivery': 'Not run', 'admission': 'Unchanged', 'public_access': 'Unchanged',
        'production_readiness': 'Not verified'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--reserve-approved-volume', action='store_true')
    args = p.parse_args()
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    if not args.reserve_approved_volume:
        print(json.dumps(candidate(), sort_keys=True, indent=2))
        return 0
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(ValueError('RESERVATION_DEADLINE')))
    signal.alarm(180)
    try:
        source = globals().get('__hooshix_source_bytes__')
        require(isinstance(source, bytes) and 0 < len(source) <= 32768, 'REVIEWED_BUNDLE_ENTRYPOINT_REQUIRED')
        with os.fdopen(os.open('/var/lib/hooshixstorage/redis-storage.lock',
                os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK), 'r+b') as lock:
            info = os.fstat(lock.fileno())
            require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and info.st_nlink == 1
                and stat.S_IMODE(info.st_mode) == 0o600, 'SAFE_EXISTING_LOCK_REQUIRED')
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            native(['/usr/sbin/auditctl', '-m', 'HooshiX Redis guarded LocalPV reservation 7GiB'])
            result = execute()
            result['source_sha256'] = hashlib.sha256(source).hexdigest()
            print(json.dumps(result, sort_keys=True))
        return 0
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as error:
        reason = str(error) if isinstance(error, ValueError) else 'LOCAL_OPERATION_FAILED'
        if not re.fullmatch('[A-Z_]{1,80}', reason): reason = 'LOCAL_OPERATION_FAILED'
        print(json.dumps({'redis_local_volume': 'Failed', 'reason': reason,
                         'partial_state': 'Preserved; no automatic retry'}))
        return 1
    finally:
        signal.alarm(0)


if __name__ == '__main__':
    raise SystemExit(main())
