"""Create-only import of the existing offline-signed intermediate before Istio."""
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
import signal
import stat
import tempfile
import warnings
from datetime import datetime, timezone
from pathlib import Path

import bootstrap_intermediate_csr as custody

PUBLIC_FILES = ('ca-cert.pem', 'cert-chain.pem', 'root-cert.pem', 'signing-receipt.json')
LABELS = {'app.kubernetes.io/part-of': 'hooshix-platform',
          'app.kubernetes.io/managed-by': 'hooshix-pki-bootstrap'}


def sha(content):
    return hashlib.sha256(content).hexdigest()


def read_public(folder):
    """Snapshot bounded regular PUBLIC files; never follow a final symlink."""
    descriptor = os.open(folder, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        custody.require(set(os.listdir(descriptor)) == set(PUBLIC_FILES), 'PUBLIC_INPUT_SET_REJECTED')
        result = {}
        for name in PUBLIC_FILES:
            with os.fdopen(os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK,
                                   dir_fd=descriptor), 'rb') as stream:
                info = os.fstat(stream.fileno())
                custody.require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1
                                and 0 < info.st_size <= 32768, 'PUBLIC_INPUT_REJECTED')
                content = stream.read(32769)
                custody.require(len(content) <= 32768, 'PUBLIC_INPUT_TOO_LARGE')
                result[name] = content
        return result
    finally:
        os.close(descriptor)


def pem_certificates(content):
    pattern = rb'-----BEGIN CERTIFICATE-----\s+[A-Za-z0-9+/=\s]+?-----END CERTIFICATE-----'
    blocks = re.findall(pattern, content)
    custody.require(blocks and not re.sub(rb'\s', b'', re.sub(pattern, b'', content)),
                    'CERTIFICATE_PEM_REJECTED')
    return [custody.native([custody.OPENSSL, 'x509', '-outform', 'DER'], input_bytes=block)
            for block in blocks]


def validate_public(files, marker, work):
    receipt = json.loads(files['signing-receipt.json'].decode('utf-8-sig'))
    custody.require(set(receipt) == {'installation_id', 'schema_version',
                    'intermediate_certificate_sha256', 'root_certificate_sha256',
                    'source_revision', 'chain_verification', 'csr_sha256', 'production_readiness'},
                    'SIGNING_RECEIPT_REJECTED')
    custody.require(receipt['schema_version'] == 1
                    and receipt['installation_id'] == 'hooshix-production'
                    and receipt['chain_verification'] == 'Passed'
                    and re.fullmatch(r'[a-f0-9]{40}', receipt['source_revision'])
                    and receipt['csr_sha256'] == marker['csr_sha256']
                    and receipt['root_certificate_sha256'].lower() == custody.ROOT_SHA256
                    and sha(files['root-cert.pem']) == custody.ROOT_SHA256
                    and receipt['intermediate_certificate_sha256'].lower() == sha(files['ca-cert.pem']),
                    'SIGNING_RECEIPT_HASH_CONFLICT')
    root = pem_certificates(files['root-cert.pem'])
    cert = pem_certificates(files['ca-cert.pem'])
    chain = pem_certificates(files['cert-chain.pem'])
    custody.require(len(root) == len(cert) == 1 and chain == cert + root, 'CERTIFICATE_CHAIN_REJECTED')
    for name in ('root-cert.pem', 'ca-cert.pem'):
        custody.create(work / name, files[name])
    custody.native([custody.OPENSSL, 'verify', '-CAfile', str(work / 'root-cert.pem'),
                    '-no-CApath', '-no-CAstore', '-check_ss_sig', str(work / 'ca-cert.pem')])
    subject = custody.native([custody.OPENSSL, 'x509', '-in', str(work / 'ca-cert.pem'),
                              '-subject', '-nameopt', 'RFC2253', '-noout']).strip()
    custody.require(subject == b'subject=CN=hooshix-production Cluster Intermediate CA',
                    'CERTIFICATE_SUBJECT_REJECTED')
    constraints = custody.native([custody.OPENSSL, 'x509', '-in', str(work / 'ca-cert.pem'),
                                  '-noout', '-ext', 'basicConstraints,keyUsage']).decode()
    custody.require(re.fullmatch(r'X509v3 Basic Constraints: critical\s+CA:TRUE, pathlen:0\s+'
                    r'X509v3 Key Usage: critical\s+Certificate Sign, CRL Sign\s*', constraints),
                    'CERTIFICATE_CA_CONSTRAINTS_REJECTED')
    description = custody.native([custody.OPENSSL, 'x509', '-in', str(work / 'ca-cert.pem'),
                                   '-noout', '-text'])
    custody.require(b'Public Key Algorithm: rsaEncryption' in description
                    and b'Public-Key: (4096 bit)' in description, 'CERTIFICATE_ALGORITHM_REJECTED')
    custody.native([custody.OPENSSL, 'x509', '-in', str(work / 'ca-cert.pem'),
                    '-noout', '-checkend', str(90 * 86400)])
    dates = custody.native([custody.OPENSSL, 'x509', '-in', str(work / 'ca-cert.pem'),
                            '-noout', '-dates']).decode().splitlines()
    start, end = (datetime.strptime(line.split('=', 1)[1], '%b %d %H:%M:%S %Y %Z')
                  for line in dates)
    custody.require(0 < (end - start).total_seconds() <= 366 * 86400,
                    'CERTIFICATE_LIFETIME_REJECTED')
    public_key = custody.native([custody.OPENSSL, 'x509', '-in', str(work / 'ca-cert.pem'),
                                '-pubkey', '-noout'])
    csr_key = custody.native([custody.OPENSSL, 'req', '-in',
                             str(custody.STATE / 'cluster-intermediate.csr.pem'), '-pubkey', '-noout'])
    custody.require(public_key == csr_key, 'CERTIFICATE_CSR_KEY_CONFLICT')
    return public_key


def audit_policy_preflight():
    # K3s default has no API audit policy. Unknown/custom body logging must be reviewed
    # before any Secret request; do not parse an arbitrary YAML policy permissively.
    pid = custody.native(['/usr/bin/systemctl', 'show', 'k3s.service', '-p', 'MainPID', '--value']).strip()
    custody.require(re.fullmatch(rb'[1-9][0-9]*', pid), 'CLUSTER_PROCESS_NOT_VERIFIED')
    proc = Path('/proc') / pid.decode()
    command = (proc / 'cmdline').read_bytes()
    environment = (proc / 'environ').read_bytes()
    custody.require(len(command) <= 32768 and len(environment) <= 32768
                    and b'audit-policy-file' not in command
                    and b'--config' not in command
                    and not any(arg == b'-c' or arg.startswith(b'-c=') for arg in command.split(b'\0'))
                    and not any(entry.startswith((b'K3S_CONFIG_FILE=', b'K3S_KUBE_APISERVER_ARG='))
                                for entry in environment.split(b'\0')), 'API_AUDIT_CONFIGURATION_REVIEW_REQUIRED')
    config = Path('/etc/rancher/k3s/config.yaml')
    dropins = Path('/etc/rancher/k3s/config.yaml.d')
    paths = [config] if config.exists() or config.is_symlink() else []
    if dropins.exists() or dropins.is_symlink():
        custody.protected(dropins, directory=True)
        paths += list(dropins.glob('*.yaml'))
    custody.require(len(paths) <= 16, 'API_AUDIT_CONFIGURATION_REVIEW_REQUIRED')
    for path in paths:
        custody.protected(path)
        custody.require(b'audit-policy-file' not in path.read_bytes(),
                        'API_AUDIT_CONFIGURATION_REVIEW_REQUIRED')


def kube(*args, body=None):
    return custody.native([custody.K3S, 'kubectl', '--request-timeout=10s', *args],
                          input_bytes=json.dumps(body).encode() if body is not None else None)


def ensure_secret(data):
    namespace = kube('get', 'namespace', 'istio-system', '--ignore-not-found', '-o', 'json')
    if not namespace.strip():
        # No workload admission exception is needed for a namespace holding only a Secret.
        kube('create', '-f', '-', '-o', 'name', body={
            'apiVersion': 'v1', 'kind': 'Namespace', 'metadata': {'name': 'istio-system',
            'labels': {**LABELS, 'pod-security.kubernetes.io/enforce': 'restricted',
                       'pod-security.kubernetes.io/audit': 'restricted',
                       'pod-security.kubernetes.io/warn': 'restricted'}}})
    expected = {'apiVersion': 'v1', 'kind': 'Secret', 'type': 'Opaque',
                'metadata': {'name': 'cacerts', 'namespace': 'istio-system', 'labels': LABELS},
                'data': {name: base64.b64encode(value).decode() for name, value in data.items()}}
    existing = kube('get', 'secret', 'cacerts', '-n', 'istio-system', '--ignore-not-found', '-o', 'json')
    if not existing.strip():
        # No apply/update/retry: uncertain create outcome is reconciled by a later invocation.
        kube('create', '-f', '-', '-o', 'name', body=expected)
        existing = kube('get', 'secret', 'cacerts', '-n', 'istio-system', '-o', 'json')
    observed = json.loads(existing)
    custody.require(observed.get('type') == 'Opaque' and observed.get('data') == expected['data']
                    and all(observed.get('metadata', {}).get('labels', {}).get(k) == v
                            for k, v in LABELS.items()), 'EXISTING_CA_SECRET_CONFLICT_PRESERVED')


def execute(revision, folder):
    custody.require(re.fullmatch(r'[a-f0-9]{40}', revision), 'REVIEWED_REVISION_REQUIRED')
    custody.require(re.fullmatch(r'/home/hooshixadmin/\.cache/hooshix-ca-import-[a-f0-9]{32}/public',
                                str(folder)), 'PUBLIC_INPUT_PATH_REJECTED')
    custody.preflight()
    audit_policy_preflight()
    custody.protected(custody.BASE, directory=True, exact_mode=0o700)
    custody.protected(custody.STATE, directory=True, exact_mode=0o700)
    lock_path = custody.STATE / 'bootstrap.lock'
    custody.protected(lock_path, exact_mode=0o600)
    with os.fdopen(os.open(lock_path, os.O_RDWR | os.O_NOFOLLOW), 'r+b') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        marker = custody.public_receipt(revision)
        files = read_public(folder)
        with tempfile.TemporaryDirectory(prefix='import-public-', dir=custody.BASE) as temporary:
            public_key = validate_public(files, marker, Path(temporary))
        with open('/dev/tty', 'r+b', buffering=0):
            pass
        warnings.simplefilter('error', getpass.GetPassWarning)
        password = getpass.getpass('EXISTING intermediate-key passphrase (not Root; never chat): ')
        custody.require(20 <= len(password) <= 128
                        and not any(ord(c) < 32 or ord(c) == 127 for c in password),
                        'PASSPHRASE_POLICY_REJECTED')
        private = custody.native([custody.OPENSSL, 'pkey', '-in', str(custody.STATE / 'ca-key.enc.pem'),
                                  '-passin', 'stdin'], input_bytes=password.encode() + b'\n')
        del password
        key_public = custody.native([custody.OPENSSL, 'pkey', '-pubout'], input_bytes=private)
        custody.require(key_public == public_key, 'INTERMEDIATE_PRIVATE_KEY_CONFLICT')
        ensure_secret({**{name: files[name] for name in PUBLIC_FILES if name.endswith('.pem')},
                       'ca-key.pem': private})
        del private
        # Recheck durable security prerequisites after the create, without any write retry.
        custody.preflight()
        return {'schema_version': 1, 'installation_id': 'hooshix-production',
                'phase': 'INTERMEDIATE_IMPORTED', 'checked_revision': revision,
                'observed_at': datetime.now(timezone.utc).isoformat(),
                'csr_sha256': marker['csr_sha256'], 'root_sha256': custody.ROOT_SHA256,
                'certificate_sha256': sha(files['ca-cert.pem']), 'ca_secret': 'Passed',
                'cluster_encryption': 'Passed', 'root_private_key_online': False,
                'encrypted_host_key_preserved': True, 'mesh_installation': 'Not run',
                'openbao_installation': 'Not run', 'production_readiness': 'Not verified'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reviewed-commit', required=True)
    parser.add_argument('--public-directory', required=True, type=Path)
    args = parser.parse_args()
    try:
        os.umask(0o077)
        resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
        signal.signal(signal.SIGALRM, custody.deadline_expired)
        signal.alarm(600)
        result = execute(args.reviewed_commit, args.public_directory)
    except Exception as error:
        reason = str(error) if isinstance(error, custody.BootstrapFailed) else 'CA_IMPORT_FAILED'
        print(json.dumps({'phase': 'Failed', 'reason': reason, 'existing_state': 'Preserved',
                          'ca_secret': 'Not verified', 'production_readiness': 'Not verified'}))
        return 1
    finally:
        signal.alarm(0)
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
