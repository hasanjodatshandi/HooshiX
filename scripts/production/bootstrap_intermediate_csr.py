"""Controlled VPS intermediate CSR; no Root, cluster writes or workload installation."""
from __future__ import annotations

import argparse
import fcntl
import getpass
import hashlib
import json
import os
import re
import resource
import signal
import stat
import subprocess
import warnings
from datetime import datetime, timezone
from pathlib import Path

BASE = Path('/var/lib/hooshix-pki')
STATE = BASE / 'istio-system'
OPENSSL = '/usr/bin/openssl'
K3S = '/usr/local/bin/k3s'
SUBJECT = '/CN=hooshix-production Cluster Intermediate CA'
ROOT_SHA256 = 'f6d49249e221fa49138771c3d86037575f55eee5f581af373f4523008e424b94'
PACKAGE = '3.5.5-1ubuntu3.7'
ENV = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C', 'LC_ALL': 'C'}


class BootstrapFailed(Exception):
    """Only fixed public error codes may leave this boundary."""


def require(condition, code):
    if not condition:
        raise BootstrapFailed(code)


def deadline_expired(signum, frame):
    raise BootstrapFailed('BOOTSTRAP_DEADLINE_EXCEEDED_STATE_PRESERVED')


def native(argv, *, input_bytes=None, passphrase=None, timeout=20):
    # A pipe descriptor, not a password literal/file/environment variable, is argv.
    descriptors = ()
    read_fd = None
    if passphrase is not None:
        read_fd, write_fd = os.pipe()
        try:
            os.write(write_fd, passphrase + b'\n')
        finally:
            os.close(write_fd)
        argv = [*argv, '-passout', 'fd:' + str(read_fd)]
        descriptors = (read_fd,)
    try:
        result = subprocess.run(argv, input=input_bytes, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout, check=False,
                                env=ENV, pass_fds=descriptors)
        require(result.returncode == 0 and len(result.stdout) <= 32768,
                'NATIVE_OPERATION_FAILED')
        return result.stdout
    finally:
        if read_fd is not None:
            os.close(read_fd)


def protected(path, *, directory=False, exact_mode=None):
    info = path.lstat()
    expected = stat.S_ISDIR if directory else stat.S_ISREG
    require(expected(info.st_mode) and info.st_uid == 0 and info.st_gid == 0,
            'UNSAFE_PKI_PATH')
    require(not info.st_mode & 0o022, 'UNSAFE_PKI_PATH')
    if not directory:
        require(info.st_nlink == 1 and info.st_size <= 32768, 'UNSAFE_PKI_FILE')
    if exact_mode is not None:
        require(stat.S_IMODE(info.st_mode) == exact_mode, 'UNSAFE_PKI_PERMISSIONS')


def directory(path):
    for parent in reversed(path.parents):
        protected(parent, directory=True)
    if not path.exists() and not path.is_symlink():
        path.mkdir(mode=0o700)
    protected(path, directory=True, exact_mode=0o700)


def create(path, content):
    # Never replace existing material, including partial operations.
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                           0o600), 'wb') as stream:
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())
    descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def preflight():
    require(os.geteuid() == 0 and os.uname().nodename == 'mail.hooshix.com'
            and os.uname().machine == 'x86_64', 'WRONG_TARGET_OR_LOCAL_SUDO_REQUIRED')
    for package in ('openssl', 'libssl3t64:amd64'):
        require(native(['/usr/bin/dpkg-query', '-W', '-f=${Version}', package]).decode()
                == PACKAGE, 'HOST_CRYPTO_VERSION_REVIEW_REQUIRED')
    for unit in ('auditd.service', 'k3s.service'):
        require(native(['/usr/bin/systemctl', 'is-active', unit]).strip() == b'active',
                'AUDIT_OR_CLUSTER_UNAVAILABLE')
    audit = native(['/usr/sbin/auditctl', '-s']).decode()
    require(re.search(r'^enabled [12]$', audit, re.M)
            and re.search(r'^lost 0$', audit, re.M), 'AUDIT_UNHEALTHY')
    encryption = native([K3S, 'secrets-encrypt', 'status']).decode()
    require('Encryption Status: Enabled' in encryption
            and 'All hashes match' in encryption, 'CLUSTER_ENCRYPTION_NOT_VERIFIED')
    # No new CA is generated over a live Istio installation.
    pods = json.loads(native([K3S, 'kubectl', '--request-timeout=10s', 'get', 'pods',
                             '--all-namespaces', '-l', 'app=istiod', '-o', 'json']))
    require(pods.get('items') == [], 'EXISTING_CONTROL_PLANE_PRESERVED')


def validate_csr(path):
    protected(path, exact_mode=0o600)
    native([OPENSSL, 'req', '-in', str(path), '-verify', '-noout'])
    subject = native([OPENSSL, 'req', '-in', str(path), '-subject', '-nameopt',
                      'RFC2253', '-noout']).strip()
    require(subject == b'subject=CN=hooshix-production Cluster Intermediate CA',
            'CSR_SUBJECT_REJECTED')
    description = native([OPENSSL, 'req', '-in', str(path), '-text', '-noout'])
    require(b'Public Key Algorithm: rsaEncryption' in description
            and b'Public-Key: (4096 bit)' in description, 'CSR_ALGORITHM_REJECTED')


def public_receipt(revision):
    key, csr, marker = (STATE / name for name in
                        ('ca-key.enc.pem', 'cluster-intermediate.csr.pem', 'bootstrap-receipt.json'))
    for path in (key, csr, marker):
        protected(path, exact_mode=0o600)
    receipt = json.loads(marker.read_bytes())
    require(set(receipt) == {'schema_version', 'installation_id', 'phase', 'source_revision',
                            'created_at', 'csr_sha256', 'encrypted_key_sha256', 'root_sha256'},
            'EXISTING_STATE_CONFLICT')
    require(receipt['schema_version'] == 1 and receipt['installation_id'] == 'hooshix-production'
            and receipt['phase'] == 'CSR_CREATED_PENDING_OFFLINE_SIGNATURE'
            and receipt['root_sha256'] == ROOT_SHA256
            and re.fullmatch(r'[a-f0-9]{40}', receipt['source_revision'])
            and receipt['csr_sha256'] == hashlib.sha256(csr.read_bytes()).hexdigest()
            and receipt['encrypted_key_sha256'] == hashlib.sha256(key.read_bytes()).hexdigest(),
            'EXISTING_STATE_CONFLICT')
    require(key.read_bytes().startswith(b'-----BEGIN ENCRYPTED PRIVATE KEY-----\n'),
            'UNENCRYPTED_KEY_REJECTED')
    validate_csr(csr)
    return {**receipt, 'checked_revision': revision, 'csr_pem': csr.read_text(),
            'intermediate_custody': 'Passed; encrypted root-only VPS boundary',
            'openbao_installation': 'Not run', 'production_readiness': 'Not verified'}


def execute(revision):
    require(re.fullmatch(r'[a-f0-9]{40}', revision), 'REVIEWED_REVISION_REQUIRED')
    preflight()
    directory(BASE)
    directory(STATE)
    lock_path = STATE / 'bootstrap.lock'
    with os.fdopen(os.open(lock_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600), 'r+b') as lock:
        protected(lock_path, exact_mode=0o600)
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        names = {path.name for path in STATE.iterdir()}
        if names != {'bootstrap.lock'}:
            require(names == {'bootstrap.lock', 'ca-key.enc.pem', 'cluster-intermediate.csr.pem',
                              'bootstrap-receipt.json'}, 'PARTIAL_STATE_PRESERVED_REVIEW_REQUIRED')
            return public_receipt(revision)
        # getpass must never fall back to echoing on stdin or recording a transcript.
        with open('/dev/tty', 'r+b', buffering=0):
            pass
        warnings.simplefilter('error', getpass.GetPassWarning)
        password = getpass.getpass('New intermediate-key passphrase (password manager; never chat): ')
        require(20 <= len(password) <= 128 and not any(ord(c) < 32 or ord(c) == 127 for c in password),
                'PASSPHRASE_POLICY_REJECTED')
        require(password == getpass.getpass('Repeat intermediate-key passphrase: '),
                'PASSPHRASE_CONFIRMATION_FAILED')
        secret = password.encode('utf-8')
        private = native([OPENSSL, 'genpkey', '-algorithm', 'RSA', '-pkeyopt',
                          'rsa_keygen_bits:4096'], timeout=120)
        encrypted = native([OPENSSL, 'pkcs8', '-topk8', '-v2', 'aes-256-cbc',
                            '-v2prf', 'hmacWithSHA256', '-iter', '1000000'],
                           input_bytes=private, passphrase=secret)
        del private
        key, csr = STATE / 'ca-key.enc.pem', STATE / 'cluster-intermediate.csr.pem'
        create(key, encrypted)
        csr_bytes = native([OPENSSL, 'req', '-new', '-sha256', '-key', str(key),
                            '-passin', 'stdin', '-subj', SUBJECT], input_bytes=secret + b'\n')
        del password, secret
        create(csr, csr_bytes)
        validate_csr(csr)
        receipt = {'schema_version': 1, 'installation_id': 'hooshix-production',
                   'phase': 'CSR_CREATED_PENDING_OFFLINE_SIGNATURE', 'source_revision': revision,
                   'created_at': datetime.now(timezone.utc).isoformat(),
                   'root_sha256': ROOT_SHA256,
                   'csr_sha256': hashlib.sha256(csr_bytes).hexdigest(),
                   'encrypted_key_sha256': hashlib.sha256(encrypted).hexdigest()}
        create(STATE / 'bootstrap-receipt.json', json.dumps(receipt, sort_keys=True).encode())
        return public_receipt(revision)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reviewed-commit', required=True)
    args = parser.parse_args()
    try:
        os.umask(0o077)
        resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
        signal.signal(signal.SIGALRM, deadline_expired)
        signal.alarm(600)
        result = execute(args.reviewed_commit)
    except Exception as error:
        reason = str(error) if isinstance(error, BootstrapFailed) else 'PKI_BOOTSTRAP_FAILED'
        print(json.dumps({'phase': 'Failed', 'reason': reason, 'existing_state': 'Preserved',
                          'openbao_installation': 'Not run', 'production_readiness': 'Not verified'}))
        return 1
    finally:
        signal.alarm(0)
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
