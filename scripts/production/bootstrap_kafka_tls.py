"""Supervised first Kafka leaf; existing encrypted issuer, existing data ESO, no runtime."""
import hashlib
import json

import bootstrap_postgresql_secrets as pg
import commission_platform as platform
import install_kafka_local_volume as volume
import install_kafka_tls as installer
import kafka_tls_material as material
import kafka_secret_delivery as contract


def kube(*args, **options):
    if args == ('get', 'validatingpolicy', 'hooshix-platform-data-closed', '-o', 'json'):
        return platform.kube(*args, output_bound=512 * 1024, timeout=30)
    return pg.kube(*args, **options)


def token_request(namespace, account, audiences):
    material.require((namespace, account) in ((contract.NAMESPACE, contract.ENTRY['account']),
        ('default', 'default'))
        and isinstance(audiences, list) and len(audiences) <= 5
        and all(isinstance(a, str) and pg.re.fullmatch(r'[a-zA-Z0-9:/_.-]{1,256}', a) for a in audiences),
        'KAFKA_TLS_TOKENREQUEST_BOUNDARY')
    return pg.kube('-n', namespace, 'create', 'token', account, '--duration=10m',
        *['--audience=' + a for a in audiences]).decode().strip()


def preserved(_ordinary):
    return installer.preserved(kube, material.custody.ROOT_SHA256)


def commission(request, revision):
    request = request.copy()
    plan = pg.decode_plan(request['plan'], request['plan_sha256'])
    observed = pg.datetime.fromisoformat(plan['staging']['observed_at'])
    material.require(observed.tzinfo and 0 <= (pg.datetime.now(pg.timezone.utc) - observed).total_seconds()
        <= 5 * 86400, 'KAFKA_TLS_FRESH_NATIVE_EVIDENCE_REQUIRED')
    password = request.pop('intermediate_passphrase', None)
    material.require(isinstance(password, str) and 20 <= len(password) <= 128
        and not any(ord(c) < 32 or ord(c) == 127 for c in password), 'KAFKA_TLS_ISSUER_PASSPHRASE_REJECTED')

    def execute(plan, **options):
        units = volume.foundation()
        options['kube'] = kube
        reserved = []
        for obj in volume.candidate():
            current = json.loads(kube('get', obj['kind'], obj['metadata']['name'], '-o', 'json'))
            material.require(volume.matches(current, obj), 'KAFKA_TLS_RESERVED_VOLUME_REQUIRED')
            reserved.append(current['metadata']['uid'])
        material.require(not kube('-n', contract.NAMESPACE, 'get', 'pvc', volume.CLAIM,
            '--ignore-not-found', '-o', 'json').strip(), 'KAFKA_TLS_EXISTING_RUNTIME_PRESERVED')
        for package in ('openssl', 'libssl3t64:amd64'):
            material.require(material.platform.native(['/usr/bin/dpkg-query', '-W', '-f=${Version}', package])
                .decode() == material.custody.PACKAGE, 'KAFKA_TLS_HOST_CRYPTO_VERSION_REVIEW_REQUIRED')
        issuer, key = material.existing_issuer(kube, options['ca'])
        original = hashlib.sha256(key.read_bytes()).hexdigest()
        result = installer.execute(plan, **options, issuer=issuer,
            issue=lambda: material.issue(options['ca'], issuer, key, password.encode()))
        material.require(material.existing_issuer(kube, options['ca']) == (issuer, key)
            and hashlib.sha256(key.read_bytes()).hexdigest() == original
            and volume.foundation() == units, 'KAFKA_TLS_EXISTING_FOUNDATION_CHANGED')
        for obj, uid in zip(volume.candidate(), reserved, strict=True):
            current = json.loads(kube('get', obj['kind'], obj['metadata']['name'], '-o', 'json'))
            material.require(volume.matches(current, obj) and current['metadata']['uid'] == uid,
                             'KAFKA_TLS_RESERVED_VOLUME_CHANGED')
        return result

    return pg.commission(request, revision, execute=execute, token_requester=token_request,
                         audit_label='Kafka', preserve=preserved)


def main(revision):
    return pg.main(revision, commissioner=commission)
