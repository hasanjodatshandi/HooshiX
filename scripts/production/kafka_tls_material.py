"""First dual-purpose Kafka node leaf, signed only by the existing encrypted issuer."""
import os
import re
from datetime import datetime, timezone

from redis_tls_material import ram_work, existing_issuer, platform, custody, require, FIELDS

COMMON_NAME = 'kafka-node-1'
DNS_NAMES = ('kafka.platform-data.svc', 'kafka.platform-data.svc.cluster.local',
             'kafka-0.kafka.platform-data.svc', 'kafka-0.kafka.platform-data.svc.cluster.local')
EXTENSIONS = ('[node]\nbasicConstraints=critical,CA:FALSE\n'
    'keyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth,clientAuth\n'
    'subjectAltName=IP:127.0.0.1,' + ','.join('DNS:' + n for n in DNS_NAMES) + '\n').encode()


def validate(material, root, issuer):
    require(set(material) == set(FIELDS) and material['ca.crt'] == root
        and all(isinstance(v, str) and 0 < len(v) <= 16384 for v in material.values()),
        'KAFKA_TLS_EXACT_MATERIAL_REQUIRED')
    with ram_work() as work:
        for name, value in {**material, 'issuer.crt': issuer}.items():
            custody.create(work / name, value.encode())
        leaf, key, ca = (str(work / n) for n in ('tls.crt', 'tls.key', 'ca.crt'))
        native = platform.native
        blocks = platform.ca_import.pem_certificates(material['tls.crt'].encode())
        require(len(blocks) == 2 and blocks[1:] == platform.ca_import.pem_certificates(issuer.encode()),
                'KAFKA_TLS_ISSUER_CHAIN_CONFLICT')
        require(native([custody.OPENSSL, 'x509', '-in', leaf, '-pubkey', '-noout']) ==
            native([custody.OPENSSL, 'pkey', '-in', key, '-pubout']), 'KAFKA_TLS_KEY_CONFLICT')
        require(material['tls.key'].startswith('-----BEGIN PRIVATE KEY-----\n'),
                'KAFKA_TLS_PKCS8_REQUIRED')
        common = [custody.OPENSSL, 'verify', '-CAfile', ca, '-no-CApath', '-no-CAstore',
                  '-untrusted', str(work / 'issuer.crt')]
        for name in DNS_NAMES:
            native([*common, '-purpose', 'sslserver', '-verify_hostname', name, leaf])
        native([*common, '-purpose', 'sslserver', '-verify_ip', '127.0.0.1', leaf])
        native([*common, '-purpose', 'sslclient', leaf])
        native([*common, '-purpose', 'sslserver', '-verify_hostname', 'foreign.platform-data.svc', leaf], expected=2)
        require(native([custody.OPENSSL, 'x509', '-in', leaf, '-noout', '-subject', '-nameopt', 'RFC2253'])
            .strip() == ('subject=CN=' + COMMON_NAME).encode(), 'KAFKA_TLS_NODE_SUBJECT_REQUIRED')
        constraints = native([custody.OPENSSL, 'x509', '-in', leaf, '-noout',
            '-ext', 'basicConstraints,keyUsage,extendedKeyUsage,subjectAltName']).decode()
        description = native([custody.OPENSSL, 'x509', '-in', leaf, '-noout', '-text'])
        names = 'IP Address:127.0.0.1, ' + ', '.join('DNS:' + n for n in DNS_NAMES)
        require(b'Public Key Algorithm: rsaEncryption' in description and b'Public-Key: (3072 bit)' in description
            and b'Signature Algorithm: sha256WithRSAEncryption' in description
            and re.fullmatch(r'X509v3 Basic Constraints: critical\s+CA:FALSE\s+'
                r'X509v3 Key Usage: critical\s+Digital Signature, Key Encipherment\s+'
                r'X509v3 Extended Key Usage:\s+TLS Web Server Authentication, TLS Web Client Authentication\s+'
                r'X509v3 Subject Alternative Name:\s+' + re.escape(names) + r'\s*', constraints),
                'KAFKA_TLS_NODE_CONSTRAINTS_REQUIRED')
        native([custody.OPENSSL, 'x509', '-in', leaf, '-checkend', str(7 * 86400), '-noout'])
        dates = native([custody.OPENSSL, 'x509', '-in', leaf, '-noout', '-dates']).decode().splitlines()
        start, end = (datetime.strptime(line.split('=', 1)[1], '%b %d %H:%M:%S %Y %Z')
            .replace(tzinfo=timezone.utc) for line in dates)
        require(0 < (end - start).total_seconds() <= 30 * 86400, 'KAFKA_TLS_BOUNDED_LIFETIME_REQUIRED')


def issue(root, issuer, key_path, password):
    with ram_work() as work:
        custody.create(work / 'issuer.crt', issuer.encode())
        custody.create(work / 'node.cnf', EXTENSIONS)
        platform.native([custody.OPENSSL, 'req', '-new', '-newkey', 'rsa:3072', '-sha256', '-nodes',
            '-subj', '/CN=' + COMMON_NAME, '-keyout', str(work / 'tls.key'), '-out', str(work / 'tls.csr')], timeout=60)
        platform.native([custody.OPENSSL, 'x509', '-req', '-in', str(work / 'tls.csr'),
            '-CA', str(work / 'issuer.crt'), '-CAkey', str(key_path), '-set_serial', '0x' + os.urandom(16).hex(),
            '-days', '30', '-sha256', '-extfile', str(work / 'node.cnf'), '-extensions', 'node',
            '-out', str(work / 'tls.crt')], password=password)
        material = {'tls.crt': (work / 'tls.crt').read_text() + issuer,
                    'tls.key': (work / 'tls.key').read_text(), 'ca.crt': root}
        validate(material, root, issuer)
        return material
