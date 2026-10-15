"""Exact database credential identities and ESO projections; no credentials or I/O."""
from __future__ import annotations

import copy

import openbao_scoped_auth as application
from render_postgresql_ownership import DATABASES

NAMESPACE = 'platform-data'
ACCOUNT = 'hooshix-postgresql-eso'
MANAGER = 'hooshix-postgresql-secret-delivery'
LABELS = {'app.kubernetes.io/part-of': 'hooshix-platform', 'app.kubernetes.io/managed-by': MANAGER}
ENTRIES = {database.replace('_', '-') + '-db-' + mode: {
    'database': database, 'mode': mode, 'username': database + '_' + mode,
    'account': 'eso-pg-' + database.replace('_', '-') + '-' + mode,
    'policy': 'hooshix-pg-' + database.replace('_', '-') + '-' + mode,
    'key': 'production/postgresql/' + database + '/' + mode}
    for database in DATABASES for mode in ('runtime', 'migration')}


def entry(name):
    if name not in ENTRIES: raise ValueError('PG_SECRET_FIXED_IDENTITY_REQUIRED')
    return ENTRIES[name]


def resource(kind, name, *, api='v1', namespace=NAMESPACE, **body):
    metadata = {'name': name, 'labels': LABELS.copy()}
    if namespace is not None: metadata['namespace'] = namespace
    return {'apiVersion': api, 'kind': kind, 'metadata': metadata, **body}


def policy(name):
    key = entry(name)['key']
    return (f'path "hooshix/data/{key}" {{ capabilities = ["read"] }}\n'
            'path "auth/token/lookup-self" { capabilities = ["read"] }\n'
            'path "auth/token/revoke-self" { capabilities = ["update"] }\n')


def role(name):
    item = entry(name)
    # Preserve the established five-minute/self-only token contract, not its application identity.
    value = copy.deepcopy(application.role(application.SERVICES[0]))
    value.update(bound_service_account_names=[item['account']],
                 bound_service_account_namespaces=[NAMESPACE], token_policies=[item['policy']])
    return value


def foundation():
    binding = resource('ClusterRoleBinding', ACCOUNT + '-tokenreview', namespace=None,
        api='rbac.authorization.k8s.io/v1',
        roleRef={'apiGroup': 'rbac.authorization.k8s.io', 'kind': 'ClusterRole',
                 'name': 'hooshix-openbao-tokenreview'},
        subjects=[{'kind': 'ServiceAccount', 'namespace': NAMESPACE, 'name': item['account']}
                  for item in ENTRIES.values()])
    return [binding, *[resource('ServiceAccount', item['account'], automountServiceAccountToken=False)
                       for item in ENTRIES.values()]]


def store(name, audiences):
    item = entry(name)
    value = copy.deepcopy(application.store(application.SERVICES[0], audiences))
    value['metadata'] = resource('SecretStore', name)['metadata']
    auth = value['spec']['provider']['vault']['auth']['kubernetes']
    auth['role'] = item['account']
    auth['serviceAccountRef']['name'] = item['account']
    return value


def external(name):
    item = entry(name)
    return resource('ExternalSecret', name, api='external-secrets.io/v1', spec={
        'refreshInterval': '1m', 'secretStoreRef': {'kind': 'SecretStore', 'name': name},
        'target': {'name': name, 'creationPolicy': 'Owner', 'deletionPolicy': 'Retain',
                   'template': {'type': 'kubernetes.io/basic-auth', 'metadata': {'labels': LABELS.copy()}}},
        'data': [{'secretKey': key, 'remoteRef': {'key': item['key'], 'property': key}}
                 for key in ('username', 'password')]})


def check_existing(api, root):
    """Inspect the entire fixed set before creating any policy or role."""
    pending = []
    for name, item in ENTRIES.items():
        for path, body in [('sys/policies/acl/' + item['policy'], {'policy': policy(name)}),
                           ('auth/' + application.MOUNT + '/role/' + item['account'], role(name))]:
            old = api.call(path, token=root, expected=(200, 404))
            if old:
                current = old['data']
                if not all(current.get(k, '').strip() == v.strip() if k == 'policy'
                           else current.get(k) == v for k, v in body.items()):
                    raise ValueError('PG_SECRET_AUTH_CONFLICT_STATE_PRESERVED')
            else: pending.append((path, body))
    return pending


def configure(api, root):
    pending = check_existing(api, root)
    for path, body in pending:
        api.call(path, 'PUT' if path.startswith('sys/') else 'POST', body, root, 204)
    if check_existing(api, root): raise ValueError('PG_SECRET_AUTH_READBACK_FAILED')
