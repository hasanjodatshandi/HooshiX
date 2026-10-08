"""Supervised snapshot export of the existing store; no init, restore or scheduler."""
from __future__ import annotations

import base64
import fcntl
import hashlib
import json
import os
import pwd
import re
import resource
import signal
import subprocess
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path

import activate_openbao_host as host
import openbao_activation_transport as transport
import openbao_snapshot_crypto as crypto


def snapshot(client, token):
    import urllib.error
    import urllib.request
    host.require(isinstance(token, str) and 10 <= len(token) <= 1024
                 and all(33 <= ord(c) <= 126 for c in token), 'SNAPSHOT_TOKEN_REJECTED')
    request = urllib.request.Request(client.base + 'sys/storage/raft/snapshot',
                                    headers={'X-Vault-Token': token}, method='GET')
    try:
        with client.opener.open(request, timeout=60) as response:
            host.require(response.status == 200, 'SNAPSHOT_API_REJECTED')
            content = response.read(crypto.MAX_SNAPSHOT + 1)
        host.require(isinstance(content, bytes) and 1 <= len(content) <= crypto.MAX_SNAPSHOT,
                     'SNAPSHOT_API_BOUND')
        host.require(content.startswith(b'\x1f\x8b\x08'), 'SNAPSHOT_ARCHIVE_REQUIRED')
        return content
    except (OSError, ValueError, urllib.error.URLError):
        raise host.custody.BootstrapFailed('SNAPSHOT_API_FAILED_STATE_PRESERVED') from None


def export(request, revision):
    host.require(isinstance(request, dict) and set(request) == {'root_token', 'recipients', 'snapshot_id'},
                 'SNAPSHOT_REQUEST_REJECTED')
    identifier = uuid.UUID(request['snapshot_id'])
    host.require(identifier.version == 4 and str(identifier) == request['snapshot_id'],
                 'SNAPSHOT_ID_REJECTED')
    recipient_hash = host.public_keys(request['recipients'])
    before, claim_uid = host.preflight()
    status = host.bao('status')
    host.require(status['initialized'] is True and status['sealed'] is False
                 and status['n'] == 3 and status['t'] == 2, 'ACTIVE_SHAMIR_STORE_REQUIRED')
    host.custody.directory(host.STATE)
    lock_path = host.STATE / 'activation.lock'
    with os.fdopen(os.open(lock_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600), 'r+b') as lock:
        host.custody.protected(lock_path, exact_mode=0o600)
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        # Bind the snapshot to the successfully initialized recipients/PVC; never read shares/root from host.
        host.custody.protected(host.STATE / 'attempt.json', exact_mode=0o600)
        intent = json.loads((host.STATE / 'attempt.json').read_bytes())
        host.require(intent['recipient_sha256'] == recipient_hash and intent['pvc_uid'] == claim_uid
                     and intent['image'] == host.IMAGE, 'SNAPSHOT_CUSTODY_IDENTITY_CONFLICT')
        host.native(['/usr/sbin/auditctl', '-m', 'HooshiX OpenBao snapshot ' + revision],
                    operation='SNAPSHOT_AUDIT')
        certificate = base64.b64decode(host.kube('-n', 'hooshix-secrets', 'get', 'secret',
            'openbao-server-tls', '-o', 'jsonpath={.data.ca\\.crt}', operation='GET_PUBLIC_CA'), validate=True)
        host.require(hashlib.sha256(certificate).hexdigest() == host.custody.ROOT_SHA256,
                     'SNAPSHOT_ROOT_CA_CONFLICT')
        with transport.forward(certificate.decode('ascii'), as_client=True) as client:
            content = snapshot(client, request['root_token'])
            snapshot_hash = hashlib.sha256(content).hexdigest()
            encrypted = crypto.seal(content, request['recipients'])
            del content
        host.storage_preflight()
        host.require(before == host.service_identity(), 'SNAPSHOT_PRESERVED_SERVICES_CHANGED')
        destination = Path('/var/tmp/hooshix-openbao-snapshot-' + identifier.hex)
        # Exact new ciphertext-only directory; no replacement or cleanup of earlier exports.
        destination.mkdir(mode=0o700)
        host.custody.create(destination / 'snapshot.snap.pgp', encrypted)
        receipt = {'schema_version': 1, 'source_revision': revision,
            'snapshot_id': str(identifier), 'pvc_uid': claim_uid, 'image': host.IMAGE,
            'recipient_sha256': recipient_hash, 'snapshot_sha256': snapshot_hash,
            'ciphertext_sha256': hashlib.sha256(encrypted).hexdigest(), 'ciphertext_bytes': len(encrypted),
            'remote_directory': str(destination), 'snapshot_export': 'Passed',
            'hourly_schedule': 'Not run', 'target_restore': 'Not run', 'root_revocation': 'Not run',
            'production_readiness': 'Not verified', 'observed_at': datetime.now(timezone.utc).isoformat()}
        host.custody.create(destination / 'receipt.json', json.dumps(receipt).encode())
        owner = pwd.getpwnam('hooshixadmin')
        for path in (destination / 'snapshot.snap.pgp', destination / 'receipt.json', destination):
            os.chown(path, owner.pw_uid, owner.pw_gid, follow_symlinks=False)
        return receipt


def main(revision):
    os.umask(0o077)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(
        host.custody.BootstrapFailed('SNAPSHOT_DEADLINE')))
    signal.alarm(240)
    try:
        host.require(re.fullmatch(r'[a-f0-9]{40}', revision), 'SNAPSHOT_REVISION_REJECTED')
        print('SNAPSHOT_RPC_READY', flush=True)
        content = sys.stdin.buffer.readline(host.BOUND + 1)
        host.require(len(content) <= host.BOUND, 'SNAPSHOT_REQUEST_BOUND')
        result = export(json.loads(content), revision)
        print(json.dumps(result), flush=True)
        return 0
    except host.custody.BootstrapFailed as error:
        print(json.dumps({'reason': str(error), 'state_preserved': True}), flush=True)
        return 1
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        print(json.dumps({'reason': 'SNAPSHOT_FAILED_STATE_PRESERVED', 'state_preserved': True}), flush=True)
        return 1
    finally:
        signal.alarm(0)
