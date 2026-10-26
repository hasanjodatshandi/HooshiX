"""Existing local owner custody and encrypted issuer, application delivery only."""
import hashlib
from datetime import datetime, timezone

import bootstrap_postgresql_secrets as pg
import install_kafka_client_tls as installer
import kafka_client_tls as material


def kube(*args, **options):
    if args in (('get', 'validatingpolicy', 'hooshix-platform-data-closed', '-o', 'json'),
                ('get', 'validatingpolicy', 'hooshix-platform-data-closed', '--ignore-not-found', '-o', 'json')):
        return material.platform.kube(*args, output_bound=512 * 1024, timeout=60)
    return pg.kube(*args, **options)


def token_request(namespace, account, audiences):
    material.require(namespace == material.NAMESPACE and account in {
        'eso-' + service for service in material.SERVICES}
        and isinstance(audiences, list) and 1 <= len(audiences) <= 5
        and all(isinstance(a, str) and pg.re.fullmatch(r'[a-zA-Z0-9:/_.-]{1,256}', a) for a in audiences),
        'KAFKA_CLIENT_TOKENREQUEST_BOUNDARY')
    return pg.kube('-n', namespace, 'create', 'token', account, '--duration=10m',
                   *['--audience=' + a for a in audiences]).decode().strip()


def commission(request, revision):
    request = request.copy()
    plan = pg.decode_plan(request['plan'], request['plan_sha256'])
    observed = datetime.fromisoformat(plan['staging']['observed_at'])
    material.require(observed.tzinfo and 0 <= (datetime.now(timezone.utc) - observed).total_seconds()
        <= 5 * 86400, 'KAFKA_CLIENT_FRESH_NATIVE_PROOF_REQUIRED')
    password = request.pop('intermediate_passphrase', None)
    material.require(isinstance(password, str) and 20 <= len(password) <= 128
        and not any(ord(c) < 32 or ord(c) == 127 for c in password),
        'KAFKA_CLIENT_ISSUER_PASSPHRASE_REJECTED')

    def execute(plan, **options):
        options['kube'] = kube
        for package in ('openssl', 'libssl3t64:amd64'):
            material.require(material.platform.native(['/usr/bin/dpkg-query', '-W', '-f=${Version}', package])
                .decode() == material.custody.PACKAGE, 'KAFKA_CLIENT_HOST_CRYPTO_VERSION_REQUIRED')
        issuer, key = material.existing_issuer(options['kube'], options['ca'])
        before = hashlib.sha256(key.read_bytes()).hexdigest()
        result = installer.execute(plan, **options, issuer=issuer,
            issue=lambda service: material.issue(service, options['ca'], issuer, key, password.encode()))
        material.require(material.existing_issuer(options['kube'], options['ca']) == (issuer, key)
            and hashlib.sha256(key.read_bytes()).hexdigest() == before, 'KAFKA_CLIENT_ISSUER_CHANGED')
        return result

    return pg.commission(request, revision, execute=execute, token_requester=token_request,
        audit_label='Kafka', preserve=lambda _: installer.snapshot(kube, plan))


def main(revision):
    return pg.main(revision, commissioner=commission)
