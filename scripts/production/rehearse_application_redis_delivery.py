"""Exercise scoped ESO Redis projection with disposable credentials in real staging."""
import hashlib
import json

import application_redis_delivery as contract
import install_application_redis_delivery as installer
from verify_eso_artifact import published_pin


def rehearse(k, api, root, ca, audiences):
    def kube(*args, body=None, **options):
        return k(*args, data=json.dumps(body).encode() if body is not None else None, **options)
    plan = {'schema_version': 1, 'installation_id': 'hooshix-production',
        'application_redis_delivery': True, 'application_ingress': 'CLOSED', 'public_access': 'CLOSED',
        'public_ca_sha256': hashlib.sha256(ca.encode()).hexdigest(), 'existing_eso_image': published_pin()['image']}
    result = installer.execute(plan, kube=kube, api=api, root=root, ca=ca, audiences=audiences, token_request=None)
    for name, (service, source_name, _) in contract.ENTRIES.items():
        other = next(n for n in contract.ENTRIES if n != name)
        other_source = contract.ENTRIES[other][1]
        jwt = k('-n', contract.NAMESPACE, 'create', 'token', 'eso-' + service, '--duration=10m',
                *['--audience=' + a for a in [*audiences, contract.auth.AUDIENCE]]).decode().strip()
        session = api.call('auth/' + contract.auth.MOUNT + '/login', 'POST',
                           {'role': 'eso-' + service, 'jwt': jwt})['auth']['client_token']
        try:
            api.call('hooshix/data/' + contract.source.entry(source_name)['key'], token=session)
            api.call('hooshix/data/' + contract.source.entry(other_source)['key'], token=session, expected=403)
            api.call('hooshix/data/' + contract.source.entry(source_name)['key'], 'POST',
                     {'data': {'denied': 'fixture'}}, session, 403)
        finally:
            api.call('auth/token/revoke-self', 'POST', {}, session, 204)
    identities = {name: installer.pg.get(kube, 'secret', name, contract.NAMESPACE)['metadata']['uid']
                  for name in contract.ENTRIES}
    def no_write_kube(*args, **options):
        if args[:3] == ('create', '-f', '-') or 'patch' in args or 'delete' in args:
            raise ValueError('REDIS_APP_REENTRY_KUBERNETES_WRITE')
        return kube(*args, **options)
    class ReadOnlyProvider:
        def call(self, path, method='GET', *args, **kwargs):
            if method != 'GET':
                raise ValueError('REDIS_APP_REENTRY_PROVIDER_WRITE')
            return api.call(path, method, *args, **kwargs)
    installer.execute(plan, kube=no_write_kube, api=ReadOnlyProvider(), root=root, ca=ca,
                      audiences=audiences, token_request=None)
    if identities != {name: installer.pg.get(kube, 'secret', name, contract.NAMESPACE)['metadata']['uid']
                      for name in contract.ENTRIES}:
        raise ValueError('REDIS_APP_REENTRY_REPLACED_SECRET')
    values = installer.sources(kube)
    for namespace, workload in [('platform-apps', 'deployment/hooshix-eso'), ('hooshix-secrets', 'openbao-0')]:
        logs = k('-n', namespace, 'logs', workload, '--tail=200')
        if any(value.encode() in logs for value in [root, *[v[1] for v in values.values()]]):
            raise ValueError('REDIS_APP_CONTAINER_LOG_LEAK')
    result.update(scoped_authentication='Passed', idempotent_reconciliation='Passed', container_log_privacy='Passed')
    return result
