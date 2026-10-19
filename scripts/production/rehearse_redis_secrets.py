"""Real six-identity Redis delivery using the already signed Ambient data ESO."""
import hashlib
import json

import install_redis_secrets as installer
import openbao_scoped_auth as auth
import postgresql_secret_delivery as pg
import redis_secret_delivery as contract
import render_postgresql_secrets as renderer
from bootstrap_redis_secrets import preserved, quarantine


def rehearse(k, chart, helm, api, root, ca, audiences, addresses):
    plan = installer.render(renderer.render(chart, helm, addresses))
    plan['public_ca_sha256'] = hashlib.sha256(ca.encode()).hexdigest()

    def kube(*args, body=None, **options):
        return k(*args, data=json.dumps(body).encode() if body is not None else None, **options)

    def token_request(namespace, account, requested):
        return k('-n', namespace, 'create', 'token', account, '--duration=10m',
                 *['--audience=' + a for a in requested]).decode().strip()

    def identities():
        paths = ['sys/policies/acl/hooshix-read-' + s for s in auth.SERVICES]
        paths += ['auth/' + auth.MOUNT + '/role/eso-' + s for s in auth.SERVICES]
        paths += ['sys/policies/acl/' + v['policy'] for v in pg.ENTRIES.values()]
        paths += ['auth/' + auth.MOUNT + '/role/' + v['account'] for v in pg.ENTRIES.values()]
        return [api.call(path, token=root)['data'] for path in paths]

    before = identities()
    protected = preserved(kube)
    pg_secrets = {name: k('-n', pg.NAMESPACE, 'get', 'secret', name,
        '-o', 'jsonpath={.metadata.uid}:{.metadata.resourceVersion}') for name in pg.ENTRIES}
    result = installer.execute(plan, kube=kube, api=api, root=root, ca=ca,
        audiences=audiences, token_request=token_request)
    first = {name: k('-n', contract.NAMESPACE, 'get', 'secret', name,
        '-o', 'jsonpath={.metadata.uid}:{.metadata.resourceVersion}') for name in contract.AUTH_ENTRIES}
    installer.execute(plan, kube=kube, api=api, root=root, ca=ca,
        audiences=audiences, token_request=token_request)
    if any(k('-n', contract.NAMESPACE, 'get', 'secret', n,
        '-o', 'jsonpath={.metadata.uid}:{.metadata.resourceVersion}') != v for n, v in first.items()):
        raise ValueError('REDIS_SECRET_REENTRY_CHANGED_CREDENTIALS')
    result['idempotent_reconciliation'] = 'Passed'
    if before != identities() or any(k('-n', pg.NAMESPACE, 'get', 'secret', n,
        '-o', 'jsonpath={.metadata.uid}:{.metadata.resourceVersion}') != v for n, v in pg_secrets.items()):
        raise ValueError('REDIS_SECRET_EXISTING_AUTHORITY_CHANGED')
    if preserved(kube) != protected:
        raise ValueError('REDIS_SECRET_BOUND_DATABASE_CHANGED')
    current_volume = quarantine.get(kube, 'pv', quarantine.volume.VOLUME)
    result['bound_database_preservation'] = ('Passed' if current_volume['status']['phase'] == 'Bound' else 'Not run')
    # Existing application identity reads only its own new credential, never server ACL/other owners.
    for user, owner in contract.OWNERS.items():
        jwt = token_request(auth.NAMESPACE, 'eso-' + owner, [*audiences, auth.AUDIENCE])
        token = api.call('auth/' + auth.MOUNT + '/login', 'POST', {'role': 'eso-' + owner, 'jwt': jwt})['auth']['client_token']
        try:
            api.call('hooshix/data/' + contract.entry('security-redis-' + user)['key'], token=token)
            for name, item in contract.AUTH_ENTRIES.items():
                if name != 'security-redis-' + user:
                    api.call('hooshix/data/' + item['key'], token=token, expected=403)
        finally: api.call('auth/token/revoke-self', 'POST', {}, token, 204)
    result['existing_identities_preserved'] = 'Passed'
    values = [api.call('hooshix/data/' + e['key'], token=root)['data']['data']['password']
              for e in contract.ENTRIES.values()]
    markers = [root, *values, *[hashlib.sha256(v.encode()).hexdigest() for v in values]]
    for ns, name in [(contract.NAMESPACE, 'deployment/' + contract.ACCOUNT), ('hooshix-secrets', 'openbao-0')]:
        logs = k('-n', ns, 'logs', name, '--tail=200')
        if any(v.encode() in logs for v in markers):
            raise ValueError('REDIS_SECRET_CONTAINER_LOG_LEAK')
    result['container_log_privacy'] = 'Passed'
    return result
