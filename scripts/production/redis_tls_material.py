"""Bounded Redis server leaf; existing issuer only, plaintext work exclusively in tmpfs."""
import base64
import hashlib
import json
import os
import re
import tempfile
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

import commission_platform as platform

custody = platform.custody
require = custody.require
DNS_NAMES = ('security-redis.platform-data.svc', 'security-redis.platform-data.svc.cluster.local')
FIELDS = ('tls.crt', 'tls.key', 'ca.crt')
EXTENSIONS = ('[server]\nbasicConstraints=critical,CA:FALSE\n'
    'keyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n'
    'subjectAltName=IP:127.0.0.1,' + ','.join('DNS:' + name for name in DNS_NAMES) + '\n').encode()


@contextmanager
def ram_work():
    require(platform.native(['/usr/bin/findmnt', '-n', '-o', 'FSTYPE', '--target', '/dev/shm']).strip() == b'tmpfs',
            'REDIS_TLS_RAM_FILESYSTEM_REQUIRED')
    # tmpfs can otherwise swap plaintext keys to disk.
    require(len(Path('/proc/swaps').read_text().splitlines()) == 1, 'REDIS_TLS_SWAP_MUST_BE_DISABLED')
    with tempfile.TemporaryDirectory(prefix='hooshix-redis-tls-', dir='/dev/shm') as directory:
        work = Path(directory)
        work.chmod(0o700)
        yield work


def validate(material, root, issuer):
    require(set(material) == set(FIELDS) and all(isinstance(v, str) and 0 < len(v) <= 16384
            for v in material.values()) and material['ca.crt'] == root,
            'REDIS_TLS_EXACT_MATERIAL_REQUIRED')
    with ram_work() as work:
        for name, value in {**material, 'issuer.crt': issuer}.items():
            custody.create(work / name, value.encode())
        leaf, ca, key = (str(work / n) for n in ('tls.crt', 'ca.crt', 'tls.key'))
        native = platform.native
        blocks = platform.ca_import.pem_certificates(material['tls.crt'].encode())
        require(len(blocks) == 2 and blocks[1:] == platform.ca_import.pem_certificates(issuer.encode()),
                'REDIS_TLS_ISSUER_CHAIN_CONFLICT')
        require(native([custody.OPENSSL, 'x509', '-in', leaf, '-pubkey', '-noout']) ==
                native([custody.OPENSSL, 'pkey', '-in', key, '-pubout']), 'REDIS_TLS_KEY_CONFLICT')
        common = [custody.OPENSSL, 'verify', '-CAfile', ca, '-no-CApath', '-no-CAstore',
                  '-untrusted', str(work / 'issuer.crt'), '-purpose', 'sslserver']
        for name in DNS_NAMES: native([*common, '-verify_hostname', name, leaf])
        native([*common, '-verify_ip', '127.0.0.1', leaf])
        native([*common, '-verify_hostname', 'foreign.platform-data.svc', leaf], expected=2)
        constraints = native([custody.OPENSSL, 'x509', '-in', leaf, '-noout',
            '-ext', 'basicConstraints,keyUsage,extendedKeyUsage,subjectAltName']).decode()
        description = native([custody.OPENSSL, 'x509', '-in', leaf, '-noout', '-text'])
        names = 'IP Address:127.0.0.1, ' + ', '.join('DNS:' + n for n in DNS_NAMES)
        require(b'Public Key Algorithm: rsaEncryption' in description and b'Public-Key: (3072 bit)' in description
            and b'Signature Algorithm: sha256WithRSAEncryption' in description
            and re.fullmatch(r'X509v3 Basic Constraints: critical\s+CA:FALSE\s+'
                r'X509v3 Key Usage: critical\s+Digital Signature, Key Encipherment\s+'
                r'X509v3 Extended Key Usage:\s+TLS Web Server Authentication\s+'
                r'X509v3 Subject Alternative Name:\s+' + re.escape(names) + r'\s*', constraints),
            'REDIS_TLS_SERVER_CONSTRAINTS_REQUIRED')
        native([custody.OPENSSL, 'x509', '-in', leaf, '-checkend', str(7 * 86400), '-noout'])
        dates = native([custody.OPENSSL, 'x509', '-in', leaf, '-noout', '-dates']).decode().splitlines()
        start, end = (datetime.strptime(line.split('=', 1)[1], '%b %d %H:%M:%S %Y %Z')
                      .replace(tzinfo=timezone.utc) for line in dates)
        require(0 < (end - start).total_seconds() <= 30 * 86400, 'REDIS_TLS_BOUNDED_LIFETIME_REQUIRED')


def issue(root, issuer, key_path, password):
    """Only code-owned paths reach OpenSSL; the issuer passphrase travels through a pipe FD."""
    with ram_work() as work:
        custody.create(work / 'issuer.crt', issuer.encode())
        custody.create(work / 'server.cnf', EXTENSIONS)
        platform.native([custody.OPENSSL, 'req', '-new', '-newkey', 'rsa:3072', '-sha256', '-nodes',
            '-subj', '/CN=' + DNS_NAMES[0], '-keyout', str(work / 'tls.key'), '-out', str(work / 'tls.csr')], timeout=60)
        platform.native([custody.OPENSSL, 'x509', '-req', '-in', str(work / 'tls.csr'),
            '-CA', str(work / 'issuer.crt'), '-CAkey', str(key_path), '-set_serial', '0x' + os.urandom(16).hex(),
            '-days', '30', '-sha256', '-extfile', str(work / 'server.cnf'), '-extensions', 'server',
            '-out', str(work / 'tls.crt')], password=password)
        material = {'tls.crt': (work / 'tls.crt').read_text() + issuer,
                    'tls.key': (work / 'tls.key').read_text(), 'ca.crt': root}
        validate(material, root, issuer)
        return material


def existing_issuer(kube, root):
    require(hashlib.sha256(root.encode()).hexdigest() == custody.ROOT_SHA256, 'REDIS_TLS_EXISTING_ROOT_REQUIRED')
    metadata = json.loads(kube('-n', 'istio-system', 'get', 'secret', 'cacerts', '-o', 'jsonpath={.metadata}'))
    require(all(metadata.get('labels', {}).get(k) == v for k, v in platform.ca_import.LABELS.items())
            and not metadata.get('deletionTimestamp'), 'REDIS_TLS_IMPORTED_ISSUER_REQUIRED')
    # Select PUBLIC fields only; never request cacerts/ca-key.pem.
    public = {}
    for name in ('root-cert.pem', 'ca-cert.pem'):
        raw = kube('-n', 'istio-system', 'get', 'secret', 'cacerts', '-o', 'jsonpath={.data.' +
                   name.replace('.', '\\.') + '}')
        public[name] = base64.b64decode(raw, validate=True).decode('ascii')
    require(public['root-cert.pem'] == root, 'REDIS_TLS_IMPORTED_ROOT_CONFLICT')
    for parent in (custody.BASE, custody.STATE): custody.protected(parent, directory=True, exact_mode=0o700)
    key = custody.STATE / 'ca-key.enc.pem'
    custody.protected(key, exact_mode=0o600)
    require(key.read_bytes().startswith(b'-----BEGIN ENCRYPTED PRIVATE KEY-----'), 'REDIS_TLS_ENCRYPTED_ISSUER_REQUIRED')
    with ram_work() as work:
        for name, value in public.items(): custody.create(work / name, value.encode())
        platform.native([custody.OPENSSL, 'verify', '-CAfile', str(work / 'root-cert.pem'),
                         '-no-CApath', '-no-CAstore', str(work / 'ca-cert.pem')])
        platform.native([custody.OPENSSL, 'x509', '-in', str(work / 'ca-cert.pem'), '-checkend', str(90 * 86400), '-noout'])
        custody.protected(custody.STATE / 'cluster-intermediate.csr.pem', exact_mode=0o600)
        require(platform.native([custody.OPENSSL, 'x509', '-in', str(work / 'ca-cert.pem'), '-pubkey', '-noout']) ==
            platform.native([custody.OPENSSL, 'req', '-in', str(custody.STATE / 'cluster-intermediate.csr.pem'),
                             '-pubkey', '-noout']), 'REDIS_TLS_EXISTING_CSR_CONFLICT')
    return public['ca-cert.pem'], key
