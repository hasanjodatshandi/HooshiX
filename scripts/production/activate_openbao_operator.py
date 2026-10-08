"""Local WSL operator custody and supervised Windows-SSH OpenBao activation."""
from __future__ import annotations

import argparse
import base64
import fcntl
import getpass
import hashlib
import json
import os
import re
import resource
import select
import shlex
import stat
import subprocess
import tempfile
import uuid
import warnings
from pathlib import Path

import activate_openbao_host as host

ROOT = Path(__file__).resolve().parents[2]
BASE = Path('/home/coder/.local/share/hooshix-openbao-custody')
SSH = '/mnt/c/Windows/System32/OpenSSH/ssh.exe'
SCP = '/mnt/c/Windows/System32/OpenSSH/scp.exe'
SOURCES = ('bootstrap_intermediate_csr.py', 'import_intermediate_ca.py',
           'verify_storage_guard.py', 'activate_openbao_host.py')
ENV = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8'}


def command(argv, *, data=None, timeout=30, expected=0, env=ENV, descriptors=()):
    result = subprocess.run(argv, input=data, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            timeout=timeout, check=False, env=env, pass_fds=descriptors)
    host.require(result.returncode == expected and len(result.stdout) <= host.BOUND,
                 'LOCAL_OPERATION_FAILED_PRIVATE_STATE_PRESERVED')
    return result.stdout


def create(path, content):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600), 'wb') as stream:
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())
    descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def read_private(path):
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as stream:
        info = os.fstat(stream.fileno())
        host.require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid()
                     and stat.S_IMODE(info.st_mode) == 0o600 and info.st_nlink == 1
                     and 0 < info.st_size <= host.BOUND, 'LOCAL_PRIVATE_FILE_REJECTED')
        content = stream.read(host.BOUND + 1)
    host.require(len(content) <= host.BOUND, 'LOCAL_PRIVATE_FILE_REJECTED')
    return content


def custody_directory(path):
    for parent in reversed(path.parents):
        info = parent.lstat()
        host.require(stat.S_ISDIR(info.st_mode) and info.st_uid in (0, os.getuid())
                     and not info.st_mode & 0o022, 'UNSAFE_LOCAL_CUSTODY_PARENT')
    info = path.lstat()
    host.require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid()
                 and stat.S_IMODE(info.st_mode) == 0o700, 'UNSAFE_LOCAL_CUSTODY_DIRECTORY')


def kill_agent(home):
    command(['/usr/bin/gpgconf', '--homedir', str(home), '--kill', 'gpg-agent'])


def gpg(home, *args, password=None, data=None, expected=0):
    argv = ['/usr/bin/gpg', '--homedir', str(home), '--batch', '--no-tty', '--pinentry-mode', 'loopback']
    descriptor = None
    try:
        if password is not None:
            descriptor, writer = os.pipe()
            try:
                os.write(writer, password.encode() + b'\n')
            finally:
                os.close(writer)
            argv += ['--passphrase-fd', str(descriptor)]
        return command([*argv, *args], data=data, expected=expected,
                       timeout=90, descriptors=(descriptor,) if descriptor is not None else ())
    finally:
        if descriptor is not None:
            os.close(descriptor)
        kill_agent(home)


def generate_recipient(directory, index, password):
    # Short temporary keyring avoids Unix-domain socket path limits; only the
    # passphrase-protected export survives on the operator device.
    with tempfile.TemporaryDirectory(prefix='hooshix-custody-gen-') as temporary:
        home = Path(temporary)
        home.chmod(0o700)
        try:
            return generate_export(directory, index, password, home)
        finally:
            kill_agent(home)


def generate_export(directory, index, password, home):
    gpg(home, '--quick-generate-key', 'HooshiX OpenBao custody ' + str(index),
        'rsa3072', 'encr', '0', password=password)
    public = gpg(home, '--export')
    secret = gpg(home, '--export-secret-keys', password=password)
    description = gpg(home, '--list-packets', data=secret)
    host.require(b'protected' in description and b'secret key packet' in description,
                 'PASSPHRASE_PROTECTED_PRIVATE_EXPORT_REQUIRED')
    create(directory / ('recipient-' + str(index) + '.secret.pgp'), secret)
    # Recovery test imports the exported backup into a FRESH keyring, not the generator.
    with tempfile.TemporaryDirectory(prefix='hooshix-custody-verify-') as temporary:
        recovered = Path(temporary)
        recovered.chmod(0o700)
        try:
            gpg(recovered, '--import', data=secret)
            fingerprint = next(line.split(b':')[9].decode() for line in
                               gpg(recovered, '--with-colons', '--list-keys').splitlines()
                               if line.startswith(b'fpr:'))
            canary = b'HooshiX local encrypted custody verification only'
            encrypted = gpg(recovered, '--trust-model', 'always', '--recipient', fingerprint,
                            '--encrypt', data=canary)
            gpg(recovered, '--decrypt', password='definitely-not-the-custody-password',
                data=encrypted, expected=2)
            host.require(gpg(recovered, '--decrypt', password=password, data=encrypted) == canary,
                         'PRIVATE_EXPORT_RECOVERY_FAILED')
        finally:
            kill_agent(recovered)
    return base64.b64encode(public).decode()


def decrypt(directory, index, password, encrypted):
    secret = read_private(directory / ('recipient-' + str(index) + '.secret.pgp'))
    with tempfile.TemporaryDirectory(prefix='hooshix-custody-open-') as temporary:
        home = Path(temporary)
        home.chmod(0o700)
        try:
            gpg(home, '--import', data=secret)
            return gpg(home, '--decrypt', password=password, data=host.ciphertext(encrypted)).decode().strip()
        finally:
            kill_agent(home)


def bootstrap(remote, revision, sources):
    # Short public command avoids the Windows command-line size failure.
    return f"""import os,stat,hashlib,sys,types
p={remote!r}
for name,digest in {sources!r}:
 with os.fdopen(os.open(p+'/'+name,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK),'rb') as f:
  s=os.fstat(f.fileno()); content=f.read(32769)
 if not stat.S_ISREG(s.st_mode) or s.st_nlink!=1 or len(content)>32768 or hashlib.sha256(content).hexdigest()!=digest: raise SystemExit(1)
 m=types.ModuleType(name[:-3]); m.__file__=p+'/'+name; sys.modules[name[:-3]]=m
 exec(compile(content,name,'exec'),m.__dict__)
raise SystemExit(sys.modules['activate_openbao_host'].main({revision!r}))
"""


def rpc(remote, revision, sources, password, body):
    payload = bootstrap(remote, revision, sources)
    argv = [SSH, '-T', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=8', 'hooshix-server',
            'sudo -k -S -p "" /usr/bin/python3 -I -c ' + shlex.quote(payload)]
    process = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, env=ENV)
    try:
        # Send ONLY password until root supervisor acknowledges readiness; avoid
        # sudo buffering/consuming the following API request as password input.
        process.stdin.write(password.encode() + b'\n')
        process.stdin.flush()
        ready, _, _ = select.select([process.stdout], [], [], 25)
        host.require(ready and process.stdout.readline(128).strip() == b'ACTIVATION_RPC_READY',
                     'LOCAL_SUDO_OR_REVIEWED_SUPERVISOR_FAILED')
        output, _ = process.communicate(json.dumps(body).encode() + b'\n', timeout=190)
        host.require(len(output) <= host.BOUND, 'RPC_OUTPUT_BOUND_EXCEEDED')
        result = json.loads(output)
        if process.returncode:
            code = result.get('reason', '')
            host.require(re.fullmatch(r'[A-Z0-9_]{1,100}', code), 'ACTIVATION_FAILED_STATE_PRESERVED')
            raise host.custody.BootstrapFailed(code)
        host.require(result['source_revision'] == revision and result['schema_version'] == 1,
                     'REVIEWED_RECEIPT_REQUIRED')
        return result
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)
        for stream in (process.stdin, process.stdout):
            stream.close()


def prepare(directory, password):
    if (directory / 'recipients.json').exists():
        keys = json.loads(read_private(directory / 'recipients.json'))
        for index in range(1, 4):
            read_private(directory / ('recipient-' + str(index) + '.secret.pgp'))
    else:
        host.require(not any(directory.iterdir()), 'PARTIAL_LOCAL_CUSTODY_REQUIRES_REVIEW')
        keys = [generate_recipient(directory, i, password) for i in range(1, 4)]
        create(directory / 'recipients.json', json.dumps(keys).encode())
    host.public_keys(keys)
    return keys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resume', type=Path)
    parser.add_argument('--rescue-and-second-session-ready', action='store_true', required=True)
    args = parser.parse_args()
    os.umask(0o077)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    warnings.simplefilter('error', getpass.GetPassWarning)
    directory = None
    try:
        host.require(os.getuid() == 1000 and sys_tty() and 'microsoft' in os.uname().release.lower(),
                     'LOCAL_INTERACTIVE_WSL_OPERATOR_REQUIRED')
        host.require(command(['/usr/bin/git', '-C', str(ROOT), 'status', '--porcelain']) == b'',
                     'CLEAN_REVIEWED_CHECKOUT_REQUIRED')
        revision = command(['/usr/bin/git', '-C', str(ROOT), 'rev-parse', 'HEAD']).decode().strip()
        command(['/usr/bin/git', '-C', str(ROOT), 'merge-base', '--is-ancestor', revision, 'origin/main'])
        version = command(['/usr/bin/gpg', '--version']).splitlines()[0]
        host.require(version in (b'gpg (GnuPG) 2.4.4', b'gpg (GnuPG) 2.4.8'), 'LOCAL_GPG_VERSION_REVIEW_REQUIRED')
        host.require(input('Working rescue VNC and second private SSH session: type READY: ') == 'READY',
                     'RESCUE_CONFIRMATION_REQUIRED')
        password = getpass.getpass('OpenBao custody NEW passphrase, 20-128 characters (not Root/CA/sudo): ')
        host.require(20 <= len(password) <= 128 and not any(c in password for c in '\r\n\x00'),
                     'CUSTODY_PASSPHRASE_REJECTED')
        host.require(password == getpass.getpass('Repeat custody passphrase: '), 'PASSPHRASE_MISMATCH')
        BASE.mkdir(mode=0o700, parents=True, exist_ok=True)
        directory = args.resume or BASE / uuid.uuid4().hex
        host.require(directory.parent == BASE and re.fullmatch(r'[a-f0-9]{32}', directory.name),
                     'EXACT_CUSTODY_DIRECTORY_REQUIRED')
        if not args.resume:
            directory.mkdir(mode=0o700)
        custody_directory(directory)
        print('CUSTODY_DIRECTORY=' + str(directory), flush=True)
        # Lock outside the custody directory so an empty initial directory stays empty.
        lock_path = BASE / (directory.name + '.lock')
        with os.fdopen(os.open(lock_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600), 'r+b') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            recipients = prepare(directory, password)
            remote = '/home/hooshixadmin/.cache/hooshix-bao-activation-' + uuid.uuid4().hex
            command([SSH, '-T', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=8', 'hooshix-server',
                     'umask 077; mkdir -p .cache; mkdir ' + remote])
            sources = []
            for name in SOURCES:
                path = ROOT / 'scripts/production' / name
                content = path.read_bytes()
                host.require(0 < len(content) <= host.BOUND, 'PUBLIC_SOURCE_BOUND_EXCEEDED')
                sources.append((name, hashlib.sha256(content).hexdigest()))
                windows = command(['/usr/bin/wslpath', '-w', str(path)]).decode().strip()
                command([SCP, '-q', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=8', windows,
                         'hooshix-server:' + remote + '/' + name])
            sudo_password = getpass.getpass('VPS sudo password (hidden, local only): ')
            host.require(0 < len(sudo_password) <= 1024 and not any(c in sudo_password for c in '\r\n\x00'),
                         'LOCAL_SUDO_INPUT_REJECTED')
            received = rpc(remote, revision, sources, sudo_password,
                           {'action': 'initialize', 'recipients': recipients})
            del sudo_password
            encrypted = host.encrypted_result(received['encrypted'])
            saved = directory / 'encrypted.json'
            encoded = json.dumps(encrypted).encode()
            if saved.exists():
                host.require(read_private(saved) == encoded, 'LOCAL_CIPHERTEXT_CONFLICT_PRESERVED')
            else:
                create(saved, encoded)
            # Read back disk bytes, then decrypt EVERY share and root from exported private backups.
            encrypted = host.encrypted_result(json.loads(read_private(saved)))
            keys = [decrypt(directory, i + 1, password, v) for i, v in enumerate(encrypted['keys_base64'])]
            token = decrypt(directory, 1, password, encrypted['root_token'])
            host.require(len(set(keys)) == 3 and 10 <= len(token) <= 1024, 'CUSTODY_RECOVERY_FAILED')
            del token
            print('ENCRYPTED_CUSTODY_DOWNLOAD_AND_RECOVERY=Passed; no plaintext saved or displayed', flush=True)
            print('Copy recipients.json, encrypted.json and each recipient-*.secret.pgp outside this PC.', flush=True)
            print('Keep shares/private keys in separately protected custody; do NOT copy GPG keyrings or upload to VPS/Git.', flush=True)
            host.require(input('After verified copies in the two approved custody locations, type COPIED: ') == 'COPIED',
                         'OFF_HOST_CUSTODY_CONFIRMATION_REQUIRED')
            sudo_password = getpass.getpass('VPS sudo password again for unseal (hidden, local only): ')
            host.require(0 < len(sudo_password) <= 1024 and not any(c in sudo_password for c in '\r\n\x00'),
                         'LOCAL_SUDO_INPUT_REJECTED')
            receipt = rpc(remote, revision, sources, sudo_password, {'action': 'unseal', 'keys': keys[:2],
                          'encrypted_sha256': hashlib.sha256(encoded).hexdigest()})
            del keys, sudo_password, password
            receipt.update(encrypted_custody_recovery='Passed',
                           custody_distribution='Owner-attested; not independently verified')
            create(directory / ('activation-receipt-' + uuid.uuid4().hex + '.json'), json.dumps(receipt).encode())
            print('OPENBAO_ACTIVATION=Passed; initialized Shamir 3/2, unsealed, public access unchanged CLOSED')
            print('NEXT=hourly off-host snapshots/restore, scoped authentication/ESO, root revocation, audited JIT')
            print('PRODUCTION_READINESS=Not verified')
        return 0
    except host.custody.BootstrapFailed as error:
        print('OPENBAO_ACTIVATION=Failed; reason=' + str(error) + '; state preserved; no automatic reinit')
        if directory:
            print('RESUME_CUSTODY_DIRECTORY=' + str(directory))
        return 1
    except (OSError, ValueError, KeyError, TypeError, EOFError,
            subprocess.SubprocessError, getpass.GetPassWarning, KeyboardInterrupt):
        print('OPENBAO_ACTIVATION=Failed; all existing custody and server state preserved; no automatic reinit')
        if directory:
            print('RESUME_CUSTODY_DIRECTORY=' + str(directory))
        return 1


def sys_tty():
    import sys
    return sys.stdin.isatty() and sys.stdout.isatty()


if __name__ == '__main__':
    raise SystemExit(main())
