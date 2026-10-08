"""Owner-local encrypted snapshot download, off-host readback and custody proof."""
from __future__ import annotations

import argparse
import getpass
import hashlib
import json
import os
import re
import resource
import select
import shlex
import subprocess
import time
import uuid
import warnings
from pathlib import Path

import activate_openbao_host as host
import activate_openbao_operator as operator
import openbao_snapshot_crypto as crypto
import parspack_snapshot_transport as cloud
import probe_parspack_audit_bucket as credentials

BASE = Path('/home/coder/.local/share/hooshix-openbao-backup')
SOURCES = (*operator.SOURCES, 'openbao_snapshot_crypto.py', 'backup_openbao_host.py')


def stage():
    remote = '/home/hooshixadmin/.cache/hooshix-bao-snapshot-' + uuid.uuid4().hex
    operator.command([operator.SSH, '-T', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=8',
                      'hooshix-server', 'umask 077; mkdir -p .cache; mkdir ' + remote])
    hashes = []
    for name in SOURCES:
        path = operator.ROOT / 'scripts/production' / name
        content = path.read_bytes()
        host.require(0 < len(content) <= host.BOUND, 'SNAPSHOT_SOURCE_BOUND')
        hashes.append((name, hashlib.sha256(content).hexdigest()))
        windows = operator.command(['/usr/bin/wslpath', '-w', str(path)]).decode().strip()
        operator.command([operator.SCP, '-q', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=8',
                          windows, 'hooshix-server:' + remote + '/' + name])
    return remote, hashes


def rpc(remote, revision, hashes, password, body):
    # Hash-bound imports from a private staging directory before privileged execution.
    bootstrap = operator.bootstrap(remote, revision, hashes).replace(
        "sys.modules['activate_openbao_host'].main", "sys.modules['backup_openbao_host'].main")
    argv = [operator.SSH, '-T', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=8', 'hooshix-server',
            'sudo -k -S -p "" /usr/bin/python3 -I -c ' + shlex.quote(bootstrap)]
    process = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, env=operator.ENV)
    try:
        process.stdin.write(password.encode() + b'\n')
        process.stdin.flush()
        ready, _, _ = select.select([process.stdout], [], [], 25)
        host.require(ready and process.stdout.readline(128).strip() == b'SNAPSHOT_RPC_READY',
                     'SNAPSHOT_LOCAL_SUDO_FAILED')
        output, _ = process.communicate(json.dumps(body).encode() + b'\n', timeout=250)
        host.require(len(output) <= host.BOUND, 'SNAPSHOT_RECEIPT_BOUND')
        receipt = json.loads(output)
        if process.returncode:
            reason = receipt.get('reason', '')
            host.require(re.fullmatch(r'[A-Z0-9_]{1,100}', reason), 'SNAPSHOT_REMOTE_FAILURE')
            raise host.custody.BootstrapFailed(reason)
        return receipt
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)
        process.stdin.close()
        process.stdout.close()


def remote_ciphertext(remote, size):
    # Stream a fixed ciphertext path through SSH with finite memory/time. Do not
    # let SCP write an unbounded file before its size can be checked on WSL.
    host.require(isinstance(remote, str)
                 and re.fullmatch(r'/var/tmp/hooshix-openbao-snapshot-[a-f0-9]{32}', remote)
                 and type(size) is int and 192 <= size <= crypto.MAX_CIPHERTEXT,
                 'SNAPSHOT_DOWNLOAD_PATH_REJECTED')
    process = subprocess.Popen([operator.SSH, '-T', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=8',
        'hooshix-server', 'cat -- ' + remote + '/snapshot.snap.pgp'], stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=operator.ENV)
    try:
        deadline, content = time.monotonic() + 90, bytearray()
        while True:
            remaining = deadline - time.monotonic()
            ready, _, _ = select.select([process.stdout], [], [], max(0, remaining))
            host.require(remaining > 0 and ready, 'SNAPSHOT_DOWNLOAD_TIMEOUT')
            chunk = os.read(process.stdout.fileno(), min(65536, size + 1 - len(content)))
            if not chunk:
                break
            content.extend(chunk)
            host.require(len(content) <= size, 'SNAPSHOT_DOWNLOAD_BOUND')
        process.wait(timeout=max(0.1, deadline - time.monotonic()))
        host.require(process.returncode == 0 and len(content) == size, 'SNAPSHOT_DOWNLOAD_FAILED')
        return bytes(content)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=5)
        process.stdout.close()


def download(receipt, directory, revision, snapshot_id, recipient_hash):
    identifier = uuid.UUID(snapshot_id)
    remote = '/var/tmp/hooshix-openbao-snapshot-' + identifier.hex
    host.require(receipt.get('schema_version') == 1 and receipt.get('source_revision') == revision
                 and receipt.get('remote_directory') == remote and receipt.get('snapshot_id') == snapshot_id
                 and receipt.get('recipient_sha256') == recipient_hash and receipt.get('image') == host.IMAGE
                 and receipt.get('snapshot_export') == 'Passed'
                 and type(receipt.get('ciphertext_bytes')) is int
                 and 192 <= receipt['ciphertext_bytes'] <= crypto.MAX_CIPHERTEXT
                 and all(re.fullmatch(r'[a-f0-9]{64}', receipt.get(key, ''))
                         for key in ('ciphertext_sha256', 'snapshot_sha256')), 'SNAPSHOT_RECEIPT_REJECTED')
    encrypted = remote_ciphertext(remote, receipt['ciphertext_bytes'])
    host.require(hashlib.sha256(encrypted).hexdigest() == receipt['ciphertext_sha256'],
                 'SNAPSHOT_DOWNLOAD_HASH_MISMATCH')
    crypto.validate_ciphertext(encrypted)
    # Exclusive owner-private ciphertext only, fsync file AND directory.
    operator.create(directory / 'snapshot.snap.pgp', encrypted)
    return encrypted


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--custody', type=Path, required=True)
    parser.add_argument('--rescue-and-second-session-ready', action='store_true', required=True)
    args = parser.parse_args()
    os.umask(0o077)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    warnings.simplefilter('error', getpass.GetPassWarning)
    directory = None
    try:
        host.require(os.getuid() == 1000 and operator.sys_tty()
                     and 'microsoft' in os.uname().release.lower(), 'SNAPSHOT_LOCAL_WSL_TTY_REQUIRED')
        host.require(operator.command(['/usr/bin/git', '-C', str(operator.ROOT), 'status', '--porcelain']) == b'',
                     'SNAPSHOT_CLEAN_REVIEWED_CHECKOUT_REQUIRED')
        revision = operator.command(['/usr/bin/git', '-C', str(operator.ROOT), 'rev-parse', 'HEAD']).decode().strip()
        operator.command(['/usr/bin/git', '-C', str(operator.ROOT), 'merge-base', '--is-ancestor', revision, 'origin/main'])
        host.require(args.custody.parent == operator.BASE
                     and re.fullmatch(r'[a-f0-9]{32}', args.custody.name), 'SNAPSHOT_CUSTODY_PATH_REJECTED')
        operator.custody_directory(args.custody)
        operator.custody_directory(BASE)
        # Only the OWNER process reads credentials. Never echo their values or provider errors.
        access, secret = credentials.load_private_credentials(str(BASE / 'credentials.json'),
                                                               'https://c892683.parspack.net')
        host.require(input('Working rescue VNC and second private SSH session: type READY: ') == 'READY',
                     'SNAPSHOT_RESCUE_REQUIRED')
        password = getpass.getpass('EXISTING OpenBao custody passphrase (not Root/CA/sudo): ')
        recipients = json.loads(operator.read_private(args.custody / 'recipients.json'))
        recipient_hash = host.public_keys(recipients)
        encrypted_init = host.encrypted_result(json.loads(operator.read_private(args.custody / 'encrypted.json')))
        root_token = operator.decrypt(args.custody, 1, password, encrypted_init['root_token'])
        snapshot_id = str(uuid.uuid4())
        directory = BASE / uuid.UUID(snapshot_id).hex
        directory.mkdir(mode=0o700)
        operator.create(directory / 'intent.json', json.dumps({'snapshot_id': snapshot_id,
            'source_revision': revision, 'recipient_sha256': recipient_hash}).encode())
        remote, hashes = stage()
        receipt = rpc(remote, revision, hashes, operator.sudo_password(),
                      {'snapshot_id': snapshot_id, 'recipients': recipients, 'root_token': root_token})
        del root_token
        encrypted = download(receipt, directory, revision, snapshot_id, recipient_hash)
        operator.create(directory / 'export-receipt.json', json.dumps(receipt).encode())
        print('ENCRYPTED_SNAPSHOT_DOWNLOAD=Passed', flush=True)
        # Durable intent before the one conditional PUT; ambiguous delivery is never replayed.
        operator.create(directory / 'delivery-intent.json', json.dumps({'snapshot_id': snapshot_id,
            'ciphertext_sha256': receipt['ciphertext_sha256'], 'bucket': 'c892683'}).encode())
        delivery, fetched = cloud.deliver(encrypted, access, secret, snapshot_id)
        del access, secret
        operator.create(directory / 'cloud-receipt.json', json.dumps(delivery).encode())
        recovered = crypto.recover(fetched, operator.read_private(args.custody / 'recipient-1.secret.pgp'), password)
        host.require(hashlib.sha256(recovered).hexdigest() == receipt['snapshot_sha256'],
                     'SNAPSHOT_RECOVERED_BYTES_MISMATCH')
        del recovered, password
        receipt.update(delivery, off_host_snapshot='Passed', version_specific_readback='Passed',
                       snapshot_decryption='Passed', target_restore='Not run', hourly_schedule='Not run')
        operator.create(directory / 'backup-receipt.json', json.dumps(receipt).encode())
        print('OPENBAO_OFF_HOST_SNAPSHOT=Passed; ciphertext only; exact-version readback and decrypt verified')
        print('PUBLIC_RECEIPT=' + str(directory / 'backup-receipt.json'))
        print('HOURLY_SCHEDULE=Not run; TARGET_RESTORE=Not run; PRODUCTION_READINESS=Not verified')
        return 0
    except host.custody.BootstrapFailed as error:
        print('OPENBAO_OFF_HOST_SNAPSHOT=Failed; reason=' + str(error) + '; all existing state retained')
        if directory:
            print('RETAINED_BACKUP_DIRECTORY=' + str(directory))
        return 1
    except (OSError, ValueError, KeyError, TypeError, EOFError, subprocess.SubprocessError,
            getpass.GetPassWarning, KeyboardInterrupt):
        print('OPENBAO_OFF_HOST_SNAPSHOT=Failed; private state retained; no automatic upload replay or reinit')
        if directory:
            print('RETAINED_BACKUP_DIRECTORY=' + str(directory))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
