"""Explicit owner recovery of a sealed, lost-output first initialization only."""
from __future__ import annotations

import hashlib
import json
import os
import stat
import time
import uuid
from pathlib import Path

import bootstrap_intermediate_csr as custody

DATA = Path('/var/lib/hooshixstorage/openbao/data')
MAX_BYTES = 512 * 1024 * 1024


def file_identity(info):
    # Reading may update atime; that is not a content/ownership change.
    return (info.st_dev, info.st_ino, info.st_mode, info.st_uid, info.st_gid,
            info.st_nlink, info.st_size, info.st_mtime_ns, info.st_ctime_ns)


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def inventory(directory, *, owner=10001, group=10001):
    """Bounded stopped-store hash/metadata inventory; links/devices never followed."""
    result, total = {}, 0

    def visit(parent, depth):
        nonlocal total
        custody.require(depth <= 8, 'RECOVERY_TREE_BOUND')
        for path in sorted(parent.iterdir()):
            info = path.lstat()
            custody.require(len(result) < 128 and info.st_uid in (0, owner)
                            and info.st_gid in (0, group), 'RECOVERY_TREE_REJECTED')
            item = {'mode': stat.S_IMODE(info.st_mode), 'uid': info.st_uid, 'gid': info.st_gid,
                    'inode': info.st_ino, 'device': info.st_dev}
            if stat.S_ISDIR(info.st_mode):
                item['kind'] = 'directory'
                result[str(path.relative_to(directory))] = item
                visit(path, depth + 1)
            else:
                custody.require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1,
                                'RECOVERY_LINK_OR_SPECIAL_FILE_REJECTED')
                total += info.st_size
                custody.require(total <= MAX_BYTES, 'RECOVERY_STORE_TOO_LARGE_NO_MUTATION')
                digest = hashlib.sha256()
                with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as stream:
                    custody.require(file_identity(os.fstat(stream.fileno())) == file_identity(info), 'RECOVERY_FILE_CHANGED')
                    count = 0
                    while chunk := stream.read(1024 * 1024):
                        count += len(chunk)
                        custody.require(count <= info.st_size, 'RECOVERY_FILE_CHANGED')
                        digest.update(chunk)
                    custody.require(count == info.st_size
                                    and file_identity(os.fstat(stream.fileno())) == file_identity(info),
                                    'RECOVERY_FILE_CHANGED')
                item.update(kind='file', bytes=count, sha256=digest.hexdigest())
                result[str(path.relative_to(directory))] = item
    visit(directory, 0)
    custody.require(result and total > 0, 'RECOVERY_EXISTING_DATA_REQUIRED')
    return result


def move_contents(directory, archive, expected, *, owner=10001, group=10001):
    custody.require(not any(archive.iterdir()) and directory.stat().st_dev == archive.stat().st_dev,
                    'RECOVERY_ARCHIVE_DESTINATION_REJECTED')
    for path in sorted(directory.iterdir()):
        path.rename(archive / path.name)
    for path in (directory, archive, archive.parent):
        sync_directory(path)
    custody.require(inventory(archive, owner=owner, group=group) == expected,
                    'RECOVERY_ARCHIVE_VERIFICATION_FAILED')


def scale(host, replicas, expected_uid):
    workload = host.get('statefulset', 'openbao')
    custody.require(workload['metadata']['uid'] == expected_uid
                    and workload['spec']['replicas'] in (0, 1), 'RECOVERY_WORKLOAD_CONFLICT')
    patch = [{'op': 'test', 'path': '/metadata/resourceVersion',
              'value': workload['metadata']['resourceVersion']},
             {'op': 'test', 'path': '/spec/replicas', 'value': workload['spec']['replicas']},
             {'op': 'replace', 'path': '/spec/replicas', 'value': replicas}]
    host.kube('-n', 'hooshix-secrets', 'patch', 'statefulset', 'openbao', '--type=json',
              '-p', json.dumps(patch), operation='RECOVERY_SCALE')


def wait_fresh(host):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        output = host.kube('-n', 'hooshix-secrets', 'get', 'pod', 'openbao-0',
                           '--ignore-not-found', '-o', 'json', operation='RECOVERY_WAIT_POD')
        if output and json.loads(output).get('status', {}).get('phase') == 'Running':
            try:
                value = host.bao('status')
                custody.require(value['initialized'] is False and value['sealed'] is True,
                                'RECOVERY_FRESH_STORE_CONFLICT')
                return
            except custody.BootstrapFailed as error:
                if str(error) == 'RECOVERY_FRESH_STORE_CONFLICT':
                    raise
        time.sleep(0.5)
    raise custody.BootstrapFailed('RECOVERY_RESTART_DEADLINE_STATE_PRESERVED')


def recover(host, keys, claim_uid, revision):
    """Called under the existing root activation lock, never calls sys/init."""
    state = host.STATE
    marker = state / 'attempt.json'
    for name in ('encrypted.json', 'recovery-pending.json', 'recovery-receipt.json'):
        path = state / name
        custody.require(not path.exists() and not path.is_symlink(), 'RECOVERY_STATE_CONFLICT_NO_RESET')
    custody.protected(marker, exact_mode=0o600)
    attempt = json.loads(marker.read_bytes())
    identity = {'recipient_sha256': host.public_keys(keys), 'pvc_uid': claim_uid, 'image': host.IMAGE}
    custody.require(all(attempt.get(key) == value for key, value in identity.items()),
                    'RECOVERY_CUSTODY_IDENTITY_CONFLICT')
    status = host.bao('status')
    custody.require(status['initialized'] is True and status['sealed'] is True
                    and status['n'] == 3 and status['t'] == 2, 'LOST_SEALED_INITIALIZATION_REQUIRED')
    host.storage_preflight()
    info = DATA.lstat()
    custody.require(stat.S_ISDIR(info.st_mode) and info.st_uid == info.st_gid == 10001
                    and stat.S_IMODE(info.st_mode) in (0o700, 0o770, 0o2770), 'EXACT_DATA_DIRECTORY_REQUIRED')
    data_identity = (info.st_dev, info.st_ino)
    custody.protected(DATA.parent, directory=True)
    workload = host.get('statefulset', 'openbao')
    uid = workload['metadata']['uid']
    # RWO is node-scoped, not pod-exclusive: reject other consumers explicitly.
    consumers = host.kube('-n', 'hooshix-secrets', 'get', 'pods', '-o',
        'jsonpath={range .items[*]}{.metadata.name}{"|"}{range .spec.volumes[*]}'
        '{.persistentVolumeClaim.claimName}{","}{end}{"\\n"}{end}', operation='RECOVERY_PVC_CONSUMERS').decode()
    for line in consumers.splitlines():
        name, claims = line.split('|', 1)
        custody.require('data-openbao-0' not in claims.split(',') or name == 'openbao-0',
                        'RECOVERY_OTHER_PVC_CONSUMER_NO_RESET')
    identifier = uuid.uuid4().hex
    archive = DATA.parent / ('lost-initialization-' + identifier)
    journal = state / ('lost-initialization-' + identifier)
    pending = state / 'recovery-pending.json'
    custody.create(pending, json.dumps(identity | {'source_revision': revision,
        'data_archive': str(archive), 'journal_archive': str(journal), 'statefulset_uid': uid}).encode())
    stopped = False
    try:
        # Mark first: even an interrupted scale-down cannot authorize a new init.
        stopped = True
        scale(host, 0, uid)
        host.native([custody.K3S, 'kubectl', '--request-timeout=65s', '-n', 'hooshix-secrets',
                     'wait', '--for=delete', 'pod/openbao-0', '--timeout=60s'],
                    timeout=70, operation='RECOVERY_WAIT_STOP')
        original = inventory(DATA)
        # These are fresh root-only directories. Rename retains every old byte,
        # inode and metadata, without copying a potentially full 8GiB filesystem.
        archive.mkdir(mode=0o700)
        journal.mkdir(mode=0o700)
        custody.create(journal / 'manifest.json', json.dumps(original, sort_keys=True).encode())
        move_contents(DATA, archive, original)
        marker.rename(journal / 'attempt.json')
        sync_directory(state)
        sync_directory(journal)
        custody.require(not any(DATA.iterdir())
                        and (DATA.stat().st_dev, DATA.stat().st_ino) == data_identity,
                        'RECOVERY_DATA_DIRECTORY_CHANGED')
        host.storage_preflight()
        scale(host, 1, uid)
        stopped = False
        wait_fresh(host)
        custody.require(host.get('pvc', 'data-openbao-0')['metadata']['uid'] == claim_uid,
                        'RECOVERY_PVC_IDENTITY_CHANGED')
        result = identity | {'recovery': 'Passed', 'archive_verification': 'Passed',
                 'data_archive': str(archive), 'journal_archive': str(journal),
                 'statefulset_uid': uid, 'source_revision': revision,
                 'initialization': 'Not run', 'off_host_backup': 'Not verified',
                 'production_readiness': 'Not verified'}
        custody.create(state / 'recovery-receipt.json', json.dumps(result).encode())
        pending.rename(journal / 'recovery-intent.json')
        sync_directory(state)
        sync_directory(journal)
        return result
    finally:
        if stopped:
            # Never restore over new/partial data or delete anything on failure.
            # Restore the desired replica count only; pending intent blocks init.
            scale(host, 1, uid)
