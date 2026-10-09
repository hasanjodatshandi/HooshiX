"""Supervised recovery proof in a disposable local RAM-backed OpenBao, never VPS."""
from __future__ import annotations

import argparse
import contextlib
import getpass
import hashlib
import json
import os
import re
import resource
import stat
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
import warnings
from datetime import datetime, timezone
from pathlib import Path

import activate_openbao_host as host
import activate_openbao_operator as operator
import backup_openbao_operator as backup
import openbao_activation_transport as transport
import openbao_snapshot_crypto as crypto

ROOT = Path(__file__).resolve().parents[2]
LABEL = 'hooshix.isolated-restore'


def approved_image():
    pin = json.loads((ROOT / 'infrastructure/production/secrets/openbao-image.json').read_bytes())
    image = pin['image']
    host.require(pin['version'] == '2.6.4' and pin['platform'] == 'linux/amd64'
                 and re.fullmatch(r'ghcr\.io/openbao/openbao-distroless@sha256:[a-f0-9]{64}', image)
                 and image.split('@')[1] == host.IMAGE.split('@')[1], 'RESTORE_IMAGE_REJECTED')
    return image


def request(client, path, method='GET', body=None, token=None, expected=200):
    host.require((method, path) in {('POST', 'sys/storage/raft/snapshot-force'),
                 ('GET', 'auth/token/lookup-self'), ('GET', 'sys/audit'), ('GET', 'sys/mounts')},
                 'RESTORE_API_PATH_REJECTED')
    host.require(re.fullmatch(r'https://127\.0\.0\.1:[0-9]{1,5}/v1/', client.base),
                 'RESTORE_LOOPBACK_BIND_REQUIRED')
    host.require(isinstance(token, str) and re.fullmatch(r'[A-Za-z0-9_.-]{16,1024}', token),
                 'RESTORE_TOKEN_REJECTED')
    host.require((method == 'GET' and body is None and expected == 200)
                 or (method == 'POST' and isinstance(body, bytes)
                     and 1 <= len(body) <= crypto.MAX_SNAPSHOT and expected == 204), 'RESTORE_SNAPSHOT_BOUND')
    data = body
    headers = {'Content-Type': 'application/octet-stream'}
    if token:
        headers['X-Vault-Token'] = token
    req = urllib.request.Request(client.base + path, method=method, data=data, headers=headers)
    try:
        with client.opener.open(req, timeout=60 if data else 5) as response:
            content = response.read(32769)
            host.require(response.status == expected and len(content) <= 32768, 'RESTORE_API_REJECTED')
            value = json.loads(content) if content else {}
            host.require(isinstance(value, dict), 'RESTORE_API_REJECTED')
            return value
    except (OSError, ValueError, urllib.error.URLError):
        raise host.custody.BootstrapFailed('RESTORE_API_FAILED') from None


def wait(client, *, initialized, sealed):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            state = client.call('status')
            if state.get('initialized') is initialized and state.get('sealed') is sealed:
                host.require(state.get('version') == '2.6.4' and state.get('storage_type') == 'raft'
                             and state.get('type') == 'shamir', 'RESTORE_STATUS_REJECTED')
                return state
        except host.custody.BootstrapFailed:
            pass
        threading.Event().wait(0.2)
    raise host.custody.BootstrapFailed('RESTORE_STATUS_TIMEOUT')


def run(snapshot, keys, token, base):
    """Create the target internally; no caller-supplied endpoint or existing store."""
    host.require(isinstance(snapshot, bytes) and snapshot.startswith(b'\x1f\x8b\x08')
                 and len(snapshot) <= crypto.MAX_SNAPSHOT, 'RESTORE_SNAPSHOT_REJECTED')
    host.require(isinstance(keys, list) and len(keys) == 2
                 and all(isinstance(k, str) and re.fullmatch(r'(?:[a-fA-F0-9]{66}|[A-Za-z0-9+/]{44})', k) for k in keys)
                 and len(set(keys)) == 2,
                 'RESTORE_SHARES_REJECTED')
    host.require(isinstance(token, str) and re.fullmatch(r'[A-Za-z0-9_.-]{16,1024}', token),
                 'RESTORE_TOKEN_REJECTED')
    image, nonce = approved_image(), uuid.uuid4().hex
    name, network = 'hooshix-bao-restore-' + nonce, 'hooshix-bao-restore-net-' + nonce
    with tempfile.TemporaryDirectory(prefix='.isolated-restore-', dir=base) as temporary:
        directory = Path(temporary)
        directory.chmod(0o700)

        def docker(*args, expected=0, timeout=30):
            # Ignore remote Docker contexts and workstation credential configuration.
            return operator.command(['/usr/bin/docker', '--host', 'unix:///var/run/docker.sock',
                '--config', str(directory), *args], expected=expected, timeout=timeout)

        def remove(kind, identity):
            listing = (kind, 'ls', '--all') if kind == 'container' else (kind, 'ls')
            listed = docker(*listing, '--filter', 'name=^' + identity + '$', '--format', '{{.ID}}').strip()
            if not listed:
                return
            fmt = '{{json .Config.Labels}}' if kind == 'container' else '{{json .Labels}}'
            result = docker(kind, 'inspect', '--format', fmt, identity)
            host.require(json.loads(result).get(LABEL) == nonce, 'RESTORE_CLEANUP_IDENTITY_REJECTED')
            args = (kind, 'rm', '--force', identity) if kind == 'container' else (kind, 'rm', identity)
            docker(*args)

        docker('pull', '--quiet', image, timeout=180)
        version = docker('version', '--format', '{{.Server.Version}}').strip()
        host.require(re.fullmatch(rb'[0-9]+\.[0-9]+\.[0-9]+', version) and int(version.split(b'.')[0]) >= 28,
                     'RESTORE_DOCKER_LOOPBACK_SAFETY_REQUIRED')
        host.require(docker('info', '--format', '{{.SwapLimit}}').strip() == b'true',
                     'RESTORE_SWAP_LIMIT_REQUIRED')
        operator.command(['/usr/bin/openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-sha256',
            '-nodes', '-days', '1', '-subj', '/CN=hooshix-disposable-recovery-only',
            '-addext', 'subjectAltName=IP:127.0.0.1', '-keyout', str(directory / 'tls.key'),
            '-out', str(directory / 'tls.crt')])
        (directory / 'tls.key').chmod(0o600)
        config = json.loads((ROOT / 'infrastructure/production/secrets/openbao-server.json').read_bytes())
        config.update(api_addr='https://127.0.0.1:8200', cluster_addr='https://127.0.0.1:8201')
        config['listener'][0]['tcp']['max_request_size'] = crypto.MAX_SNAPSHOT
        config['listener'][0]['tcp']['max_request_duration'] = '60s'
        config['default_max_request_duration'] = '60s'
        operator.create(directory / 'server.json', json.dumps(config).encode())
        with contextlib.ExitStack() as cleanup:
            cleanup.callback(remove, 'network', network)
            docker('network', 'create', '--internal', '--label', LABEL + '=' + nonce, network)
            host.require(docker('network', 'inspect', '--format', '{{.Internal}}', network).strip() == b'true',
                         'RESTORE_EGRESS_ISOLATION_REQUIRED')
            cleanup.callback(remove, 'container', name)
            docker('create', '--name', name, '--label', LABEL + '=' + nonce, '--network', network,
                '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                '--user', f'{os.getuid()}:{os.getgid()}', '--memory', '512m', '--memory-swap', '512m',
                '--cpus', '1', '--pids-limit', '64', '--log-driver', 'none',
                '--tmpfs', '/tmp:rw,nosuid,nodev,noexec,size=16m',
                '--tmpfs', f'/openbao/data:rw,nosuid,nodev,noexec,size=128m,mode=0700,uid={os.getuid()},gid={os.getgid()}',
                '--mount', f'type=bind,src={directory / "tls.key"},dst=/openbao/tls/tls.key,readonly',
                '--mount', f'type=bind,src={directory / "tls.crt"},dst=/openbao/tls/tls.crt,readonly',
                '--mount', f'type=bind,src={directory / "server.json"},dst=/openbao/config.json,readonly',
                '--publish', '127.0.0.1::8200', '--entrypoint', '/usr/bin/bao', image,
                'server', '-config=/openbao/config.json')
            docker('start', name)
            match = re.fullmatch(rb'127\.0\.0\.1:([0-9]{1,5})\s*', docker('port', name, '8200/tcp'))
            host.require(match is not None, 'RESTORE_LOOPBACK_BIND_REQUIRED')
            client = transport.Client(int(match[1]), (directory / 'tls.crt').read_text())
            wait(client, initialized=False, sealed=True)
            # Only the newly created RAM-backed clone is initialized/force-restored.
            initial = client.call('write', 'sys/init', {'secret_shares': 3, 'secret_threshold': 2})
            host.unseal(initial['keys_base64'][:2], api=client.call)
            request(client, 'sys/storage/raft/snapshot-force', 'POST', snapshot, initial['root_token'], 204)
            del initial
            wait(client, initialized=True, sealed=True)
            host.unseal(keys, api=client.call)
            restored = request(client, 'auth/token/lookup-self', token=token)
            host.require('root' in restored.get('data', {}).get('policies', []), 'RESTORE_ORIGINAL_AUTH_FAILED')
            audit = request(client, 'sys/audit', token=token).get('data', {}).get('protected/', {})
            host.require(audit.get('type') == 'file'
                         and audit.get('options', {}).get('file_path') == '/openbao/data/audit.jsonl'
                         and audit.get('options', {}).get('log_raw') == 'false', 'RESTORE_AUDIT_REQUIRED')
            mounts = request(client, 'sys/mounts', token=token).get('data', {})
            host.require(isinstance(mounts, dict) and len(mounts) >= 2, 'RESTORE_MOUNT_STATE_REJECTED')
            proof = {'isolated_restore': 'Passed', 'original_shamir_quorum': 'Passed',
                     'original_authentication': 'Passed', 'protected_audit': 'Passed',
                     'restored_mount_count': len(mounts), 'runtime_image': image,
                     'target': 'operator-local disposable RAM-backed clone',
                     'vps_restore': 'Not run', 'cloud_version_readback': 'Not verified',
                     'production_readiness': 'Not verified'}
        # Both clone and its private network must be gone before success is returned.
    return {**proof, 'isolated_target_cleanup': 'Passed'}


def load_snapshot(directory, custody):
    host.require(directory.parent == backup.BASE and re.fullmatch(r'[a-f0-9]{32}', directory.name)
                 and custody.parent == operator.BASE and re.fullmatch(r'[a-f0-9]{32}', custody.name),
                 'RESTORE_PRIVATE_PATH_REJECTED')
    operator.custody_directory(directory)
    operator.custody_directory(custody)
    receipt = json.loads(operator.read_private(directory / 'export-receipt.json'))
    recipients = json.loads(operator.read_private(custody / 'recipients.json'))
    host.require(receipt.get('snapshot_id') == str(uuid.UUID(directory.name))
                 and receipt.get('recipient_sha256') == host.public_keys(recipients)
                 and receipt.get('image') == host.IMAGE and receipt.get('snapshot_export') == 'Passed'
                 and all(isinstance(receipt.get(key), str) and re.fullmatch(r'[a-f0-9]{64}', receipt[key])
                         for key in ('snapshot_sha256', 'ciphertext_sha256')), 'RESTORE_RECEIPT_REJECTED')
    with os.fdopen(os.open(directory / 'snapshot.snap.pgp', os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as stream:
        info = os.fstat(stream.fileno())
        host.require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid() and info.st_nlink == 1
                     and stat.S_IMODE(info.st_mode) == 0o600 and 192 <= info.st_size <= crypto.MAX_CIPHERTEXT,
                     'RESTORE_CIPHERTEXT_FILE_REJECTED')
        content = stream.read(crypto.MAX_CIPHERTEXT + 1)
    host.require(len(content) == receipt.get('ciphertext_bytes')
                 and hashlib.sha256(content).hexdigest() == receipt['ciphertext_sha256'], 'RESTORE_ENVELOPE_HASH_REJECTED')
    crypto.validate_ciphertext(content)
    return receipt, content


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', type=Path, required=True)
    parser.add_argument('--custody', type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    warnings.simplefilter('error', getpass.GetPassWarning)
    try:
        host.require(os.getuid() == 1000 and operator.sys_tty() and 'microsoft' in os.uname().release.lower()
                     and os.environ.get('GITHUB_ACTIONS') != 'true', 'RESTORE_OWNER_LOCAL_TTY_REQUIRED')
        host.require(operator.command(['/usr/bin/git', '-C', str(ROOT), 'status', '--porcelain']) == b'',
                     'RESTORE_CLEAN_CHECKOUT_REQUIRED')
        revision = operator.command(['/usr/bin/git', '-C', str(ROOT), 'rev-parse', 'HEAD']).decode().strip()
        operator.command(['/usr/bin/git', '-C', str(ROOT), 'merge-base', '--is-ancestor', revision, 'origin/main'])
        operator.custody_directory(backup.BASE)
        receipt, encrypted = load_snapshot(args.snapshot, args.custody)
        print('Isolated LOCAL recovery only. No VPS, sudo, upload, root revocation or original-file changes.', flush=True)
        password = getpass.getpass('EXISTING OpenBao custody passphrase (not Root/CA/sudo): ')
        snapshot = crypto.recover(encrypted, operator.read_private(args.custody / 'recipient-1.secret.pgp'), password)
        host.require(hashlib.sha256(snapshot).hexdigest() == receipt['snapshot_sha256'], 'RESTORE_SNAPSHOT_HASH_REJECTED')
        initial = host.encrypted_result(json.loads(operator.read_private(args.custody / 'encrypted.json')))
        keys = [operator.decrypt(args.custody, i + 1, password, initial['keys_base64'][i]) for i in range(2)]
        token = operator.decrypt(args.custody, 1, password, initial['root_token'])
        del password, initial
        proof = run(snapshot, keys, token, backup.BASE)
        del snapshot, keys, token
        proof.update(schema_version=1, source_revision=revision, snapshot_id=receipt['snapshot_id'],
                     snapshot_sha256=receipt['snapshot_sha256'], observed_at=datetime.now(timezone.utc).isoformat())
        destination = args.snapshot / ('isolated-restore-' + uuid.uuid4().hex + '.json')
        operator.create(destination, json.dumps(proof).encode())
        print('OPENBAO_ISOLATED_RECOVERY=Passed; original quorum/authentication; RAM-backed clone removed')
        print('PUBLIC_RECEIPT=' + str(destination))
        return 0
    except host.custody.BootstrapFailed as error:
        print('OPENBAO_ISOLATED_RECOVERY=Failed; reason=' + str(error) + '; original state preserved')
    except (OSError, ValueError, KeyError, TypeError, EOFError, subprocess.SubprocessError,
            getpass.GetPassWarning, KeyboardInterrupt):
        print('OPENBAO_ISOLATED_RECOVERY=Failed; private state preserved; no provider/VPS replay')
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
