"""Five fixed application client identities and their configtree/ESO certificate contract."""
import os
import re
from datetime import datetime, timezone

import openbao_scoped_auth as auth
from redis_tls_material import ram_work, existing_issuer, platform, custody, require

SERVICES = ('authorization-service', 'conversation-service', 'identity-service',
            'notification-service', 'web-bff')
MANAGER = 'hooshix-kafka-client-tls'
NAMESPACE = auth.NAMESPACE
FIELDS = {'tls.crt': 'spring.kafka.ssl.key-store-certificate-chain',
          'tls.key': 'spring.kafka.ssl.key-store-key',
          'ca.crt': 'spring.kafka.ssl.trust-store-certificates'}
SETTINGS = {'spring.kafka.security.protocol': 'SSL', 'spring.kafka.ssl.key-store-type': 'PEM',
            'spring.kafka.ssl.trust-store-type': 'PEM',
            'spring.kafka.properties.ssl.endpoint.identification.algorithm': 'HTTPS'}
EXTENSIONS = (b'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n'
              b'extendedKeyUsage=clientAuth\n')


def entry(service):
    require(service in SERVICES, 'KAFKA_CLIENT_SERVICE_REJECTED')
    return {'key': 'production/' + service + '/kafka-tls',
            'name': service.removesuffix('-service') + '-kafka',
            'principal': 'CN=hooshix-' + service + '-kafka'}


def external(service):
    item = entry(service)
    value = auth.resource('external-secrets.io/v1', 'ExternalSecret', item['name'], spec={
        'refreshInterval': '1m', 'secretStoreRef': {'kind': 'SecretStore', 'name': 'hooshix-' + service},
        'target': {'name': item['name'], 'creationPolicy': 'Owner', 'deletionPolicy': 'Retain',
            'template': {'type': 'Opaque', 'metadata': {'labels': {
                'app.kubernetes.io/part-of': 'hooshix-platform', 'app.kubernetes.io/managed-by': MANAGER}}}},
        'data': [{'secretKey': field, 'remoteRef': {'key': item['key'], 'property': field}}
                 for field in (*FIELDS.values(), *SETTINGS)]})
    value['metadata']['labels']['app.kubernetes.io/managed-by'] = MANAGER
    return value


def owner(external_secret):
    return [{'apiVersion': 'external-secrets.io/v1', 'kind': 'ExternalSecret',
        'name': external_secret['metadata']['name'], 'uid': external_secret['metadata']['uid'],
        'controller': True, 'blockOwnerDeletion': True}]


def validate(service, values, root, issuer):
    item = entry(service)
    require(set(values) == {*FIELDS.values(), *SETTINGS}
        and all(isinstance(v, str) and 0 < len(v) <= 16384 for v in values.values())
        and all(values[k] == v for k, v in SETTINGS.items())
        and values[FIELDS['ca.crt']] == root, 'KAFKA_CLIENT_EXACT_CONFIG_REQUIRED')
    material = {key: values[field] for key, field in FIELDS.items()}
    require(material['tls.key'].startswith('-----BEGIN PRIVATE KEY-----\n'),
            'KAFKA_CLIENT_PKCS8_REQUIRED')
    blocks = platform.ca_import.pem_certificates(material['tls.crt'].encode())
    require(len(blocks) == 2 and blocks[1:] == platform.ca_import.pem_certificates(issuer.encode()),
            'KAFKA_CLIENT_ISSUER_CHAIN_REQUIRED')
    with ram_work() as work:
        for name, value in {**material, 'issuer.crt': issuer}.items():
            custody.create(work / name, value.encode())
        native, openssl = platform.native, custody.OPENSSL
        leaf = str(work / 'tls.crt')
        require(native([openssl, 'x509', '-in', leaf, '-pubkey', '-noout']) ==
            native([openssl, 'pkey', '-in', str(work / 'tls.key'), '-pubout']),
            'KAFKA_CLIENT_KEY_CONFLICT')
        verify = [openssl, 'verify', '-CAfile', str(work / 'ca.crt'), '-no-CApath', '-no-CAstore',
                  '-untrusted', str(work / 'issuer.crt')]
        native([*verify, '-purpose', 'sslclient', leaf])
        native([*verify, '-purpose', 'sslserver', leaf], expected=2)
        require(native([openssl, 'x509', '-in', leaf, '-noout', '-subject', '-nameopt', 'RFC2253'])
            .strip() == ('subject=' + item['principal']).encode(), 'KAFKA_CLIENT_SUBJECT_REQUIRED')
        constraints = native([openssl, 'x509', '-in', leaf, '-noout',
            '-ext', 'basicConstraints,keyUsage,extendedKeyUsage,subjectAltName']).decode()
        description = native([openssl, 'x509', '-in', leaf, '-noout', '-text'])
        require(b'Public Key Algorithm: rsaEncryption' in description
            and b'Public-Key: (3072 bit)' in description
            and b'Signature Algorithm: sha256WithRSAEncryption' in description
            and re.fullmatch(r'X509v3 Basic Constraints: critical\s+CA:FALSE\s+'
                r'X509v3 Key Usage: critical\s+Digital Signature, Key Encipherment\s+'
                r'X509v3 Extended Key Usage:\s+TLS Web Client Authentication\s*', constraints),
                'KAFKA_CLIENT_CONSTRAINTS_REQUIRED')
        native([openssl, 'x509', '-in', leaf, '-checkend', str(7 * 86400), '-noout'])
        dates = native([openssl, 'x509', '-in', leaf, '-noout', '-dates']).decode().splitlines()
        start, end = (datetime.strptime(line.split('=', 1)[1], '%b %d %H:%M:%S %Y %Z')
            .replace(tzinfo=timezone.utc) for line in dates)
        require(0 < (end - start).total_seconds() <= 30 * 86400, 'KAFKA_CLIENT_LIFETIME_REQUIRED')


def issue(service, root, issuer, key, password):
    subject = entry(service)['principal']
    with ram_work() as work:
        custody.create(work / 'issuer.crt', issuer.encode())
        custody.create(work / 'client.cnf', EXTENSIONS)
        platform.native([custody.OPENSSL, 'req', '-new', '-newkey', 'rsa:3072', '-sha256', '-nodes',
            '-subj', '/' + subject, '-keyout', str(work / 'tls.key'),
            '-out', str(work / 'tls.csr')], timeout=60)
        platform.native([custody.OPENSSL, 'x509', '-req', '-in', str(work / 'tls.csr'),
            '-CA', str(work / 'issuer.crt'), '-CAkey', str(key),
            '-set_serial', '0x' + os.urandom(16).hex(), '-days', '30', '-sha256',
            '-extfile', str(work / 'client.cnf'), '-out', str(work / 'tls.crt')], password=password)
        material = {'tls.crt': (work / 'tls.crt').read_text() + issuer,
                    'tls.key': (work / 'tls.key').read_text(), 'ca.crt': root}
        result = {**{field: material[k] for k, field in FIELDS.items()}, **SETTINGS}
        validate(service, result, root, issuer)
        return result
