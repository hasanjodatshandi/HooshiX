"""Real Kafka leaf delivery on the signed Ambient ESO/OpenBao fixture; synthetic CA only."""
import hashlib
import json
import re
from unittest.mock import Mock, patch

import install_kafka_tls as installer
import kafka_secret_delivery as contract
import kafka_tls_material as material
import openbao_scoped_auth as auth
import postgresql_secret_delivery as pg
import redis_secret_delivery as redis
from rehearse_redis_tls import fixture_issuer, synthetic_memory
from rehearse_redis_secrets import quarantine


def rehearse(k, plan, api, root, ca, root_key, audiences, node):
    if not re.fullmatch('hooshix-bao-ci-[a-f0-9]{12}-control-plane', node):
        raise ValueError('OWNED_KAFKA_TLS_FIXTURE_REQUIRED')
    def kube(*args, body=None, **options):
        return k(*args, data=json.dumps(body).encode() if body is not None else None, **options)
    def token_request(namespace, account, requested):
        return k('-n', namespace, 'create', 'token', account, '--duration=10m',
            *['--audience=' + a for a in requested]).decode().strip()
    with patch.object(quarantine.volume, 'NODE', node), patch.object(installer.redis.volume, 'NODE', node), synthetic_memory(ca):
        before = installer.preserved(kube, plan['public_ca_sha256'])
        entries = [*pg.ENTRIES.values(), *redis.ALL_AUTH_ENTRIES.values()]
        paths = ['sys/policies/acl/' + e['policy'] for e in entries]
        paths += ['auth/' + auth.MOUNT + '/role/' + e['account'] for e in entries]
        identities = [api.call(p, token=root)['data'] for p in paths]
        with fixture_issuer(ca, root_key) as (issuer, key, password):
            options = dict(kube=kube, api=api, root=root, ca=ca, issuer=issuer,
                audiences=audiences, token_request=token_request)
            result = installer.execute(plan, **options, issue=lambda: material.issue(ca, issuer, key, password))
            first = k('-n', contract.NAMESPACE, 'get', 'secret', contract.NAME,
                '-o', 'jsonpath={.metadata.uid}:{.metadata.resourceVersion}')
            path = 'hooshix/data/' + contract.ENTRY['key']
            version = api.call(path, token=root)['data']['metadata']['version']
            no_issue = Mock(side_effect=ValueError('KAFKA_TLS_REENTRY_MUST_NOT_REISSUE'))
            installer.execute(plan, **options, issue=no_issue)
            no_issue.assert_not_called()
            if first != k('-n', contract.NAMESPACE, 'get', 'secret', contract.NAME,
                '-o', 'jsonpath={.metadata.uid}:{.metadata.resourceVersion}') or version != api.call(
                    path, token=root)['data']['metadata']['version']:
                raise ValueError('KAFKA_TLS_REENTRY_CHANGED_MATERIAL')
            result['idempotent_reconciliation'] = 'Passed'
            # All existing data/application identities remain unable to read the new node key.
            bound = [(contract.NAMESPACE, e['account']) for e in entries]
            bound += [(auth.NAMESPACE, 'eso-' + name) for name in auth.SERVICES]
            for namespace, account in bound:
                jwt = token_request(namespace, account, [*audiences, auth.AUDIENCE])
                token = api.call('auth/' + auth.MOUNT + '/login', 'POST',
                    {'role': account, 'jwt': jwt})['auth']['client_token']
                try: api.call(path, token=token, expected=403)
                finally: api.call('auth/token/revoke-self', 'POST', {}, token, 204)
            result['existing_identity_tls_denials'] = 'Passed'
            private = api.call(path, token=root)['data']['data']['tls.key']
            markers = [root, private, *private.splitlines()[1:-1]]
            for namespace, name in ((contract.NAMESPACE, 'deployment/' + contract.ACCOUNT),
                                    ('hooshix-secrets', 'openbao-0')):
                logs = k('-n', namespace, 'logs', name, '--tail=200')
                if any(m.encode() in logs for m in markers): raise ValueError('KAFKA_TLS_CONTAINER_LOG_LEAK')
            result['container_log_privacy'] = 'Passed'
        if installer.preserved(kube, plan['public_ca_sha256']) != before or identities != [
            api.call(p, token=root)['data'] for p in paths]:
            raise ValueError('KAFKA_TLS_EXISTING_AUTHORITY_CHANGED')
        result['bound_database_redis_preservation'] = 'Passed'
    result.update(namespace=contract.NAMESPACE, existing_identities='Preserved',
        host_guard='Not run', provider_backup='Not run')
    return result
