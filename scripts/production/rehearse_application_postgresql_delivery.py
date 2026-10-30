"""Exercise real scoped Vault auth and signed ESO configtree projection in disposable staging."""
import hashlib
import json

import application_postgresql_delivery as contract
import install_application_postgresql_delivery as installer
from verify_eso_artifact import published_pin


def rehearse(k, api, root, ca, audiences):
    def kube(*args, body=None, **options):
        return k(*args, data=json.dumps(body).encode() if body is not None else None, **options)
    def token_request(namespace, account, requested):
        return k('-n', namespace, 'create', 'token', account, '--duration=10m',
                 *['--audience=' + a for a in requested]).decode().strip()
    plan = {'schema_version': 1, 'installation_id': 'hooshix-production',
        'application_postgresql_delivery': True, 'application_ingress': 'CLOSED', 'public_access': 'CLOSED',
        'public_ca_sha256': hashlib.sha256(ca.encode()).hexdigest(),
        'existing_eso_image': published_pin()['image']}
    result = installer.execute(plan, kube=kube, api=api, root=root, ca=ca,
                               audiences=audiences, token_request=token_request)
    # Real write-denial probes target disposable fixture values only, never production credentials.
    for name, item in contract.ENTRIES.items():
        jwt = token_request(contract.NAMESPACE, item['account'], [*audiences, contract.application.AUDIENCE])
        session = api.call('auth/' + contract.application.MOUNT + '/login', 'POST',
                           {'role': item['account'], 'jwt': jwt})['auth']['client_token']
        try:
            api.call('hooshix/data/' + item['key'], 'POST', {'data': {'denied': 'fixture'}}, session, 403)
        finally:
            api.call('auth/token/revoke-self', 'POST', {}, session, 204)
    identities = {name: installer.get(kube, 'secret', name, contract.NAMESPACE)['metadata']['uid']
                  for name in contract.ENTRIES}
    # A completed reentry must not write any provider/Kubernetes object.
    def no_write_kube(*args, **options):
        if args[:3] == ('create', '-f', '-') or 'patch' in args or 'delete' in args:
            raise ValueError('PG_APP_REENTRY_KUBERNETES_WRITE')
        return kube(*args, **options)
    class ReadOnlyProvider:
        def call(self, path, method='GET', *args, **kwargs):
            if method != 'GET' and (path.startswith('sys/policies/') or '/role/' in path
                                     or path.startswith('hooshix/')):
                raise ValueError('PG_APP_REENTRY_PROVIDER_WRITE')
            return api.call(path, method, *args, **kwargs)
    installer.execute(plan, kube=no_write_kube, api=ReadOnlyProvider(), root=root, ca=ca,
                      audiences=audiences, token_request=token_request)
    if identities != {name: installer.get(kube, 'secret', name, contract.NAMESPACE)['metadata']['uid']
                      for name in contract.ENTRIES}:
        raise ValueError('PG_APP_REENTRY_REPLACED_SECRET')
    result['idempotent_reconciliation'] = 'Passed'
    values, missing = installer.pg.credential_values(api, root)
    if missing:
        raise ValueError('PG_APP_EXISTING_CREDENTIALS_REQUIRED')
    for namespace, workload in [('platform-apps', 'deployment/hooshix-eso'), ('hooshix-secrets', 'openbao-0')]:
        logs = k('-n', namespace, 'logs', workload, '--tail=200')
        if any(value.encode() in logs for value in [root, *[v['password'] for v in values.values()]]):
            raise ValueError('PG_APP_CONTAINER_LOG_LEAK')
    result['container_log_privacy'] = 'Passed'
    return result
