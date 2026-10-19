"""Fixed namespace-local Redis delivery identities; no values or Kubernetes I/O."""
import copy

import openbao_scoped_auth as application
import postgresql_secret_delivery as pg
from render_redis_acl import USERS

NAMESPACE = pg.NAMESPACE
ACCOUNT = pg.ACCOUNT  # Reuse the already approved data-namespace Secret cache.
MANAGER = 'hooshix-redis-secret-delivery'
LABELS = {'app.kubernetes.io/part-of': 'hooshix-platform', 'app.kubernetes.io/managed-by': MANAGER}
OWNERS = {'authorization': 'authorization-service', 'identity': 'identity-service', 'webbff': 'web-bff'}
ENTRIES = {'security-redis-' + user: {'username': user, 'account': 'eso-redis-' + user,
    'policy': 'hooshix-redis-' + user,
    'key': 'production/' + OWNERS.get(user, 'security-redis') + '/' +
           ('security-redis' if user in OWNERS else user)} for user in USERS}
ACL_NAME = 'security-redis-acl'
ACL_ENTRY = {'account': 'eso-redis-acl', 'policy': 'hooshix-redis-acl',
             'key': 'production/security-redis/acl'}
AUTH_ENTRIES = {**ENTRIES, ACL_NAME: ACL_ENTRY}
TLS_NAME = 'security-redis-tls'
TLS_ENTRIES = {TLS_NAME: {'account': 'eso-redis-tls', 'policy': 'hooshix-redis-tls',
                         'key': 'production/security-redis/tls'}}
ALL_AUTH_ENTRIES = {**AUTH_ENTRIES, **TLS_ENTRIES}


def entry(name):
    if name not in ENTRIES: raise ValueError('REDIS_SECRET_FIXED_IDENTITY_REQUIRED')
    return ENTRIES[name]


def resource(kind, name, **body):
    value = pg.resource(kind, name, **body)
    value['metadata']['labels'] = LABELS.copy()
    return value


def policy(name):
    key = ALL_AUTH_ENTRIES[name]['key']
    return (f'path "hooshix/data/{key}" {{ capabilities = ["read"] }}\n'
            'path "auth/token/lookup-self" { capabilities = ["read"] }\n'
            'path "auth/token/revoke-self" { capabilities = ["update"] }\n')


def role(name):
    value = copy.deepcopy(application.role(application.SERVICES[0]))
    item = ALL_AUTH_ENTRIES[name]
    value.update(bound_service_account_names=[item['account']],
        bound_service_account_namespaces=[NAMESPACE], token_policies=[item['policy']])
    return value


def check_existing(api, root, *, entries=AUTH_ENTRIES):
    pending = []
    for name, item in entries.items():
        for path, body in [('sys/policies/acl/' + item['policy'], {'policy': policy(name)}),
                ('auth/' + application.MOUNT + '/role/' + item['account'], role(name))]:
            old = api.call(path, token=root, expected=(200, 404))
            if old:
                application.host.require(all(old['data'].get(k, '').strip() == v.strip() if k == 'policy'
                    else old['data'].get(k) == v for k, v in body.items()), 'REDIS_SECRET_AUTH_CONFLICT')
            else: pending.append((path, body))
    return pending


def configure(api, root, *, entries=AUTH_ENTRIES):
    for path, body in check_existing(api, root, entries=entries):
        api.call(path, 'PUT' if path.startswith('sys/') else 'POST', body, root, 204)
    application.host.require(not check_existing(api, root, entries=entries), 'REDIS_SECRET_AUTH_READBACK_FAILED')


def foundation(*, tls=False):
    accounts = [item['account'] for item in (TLS_ENTRIES if tls else AUTH_ENTRIES).values()]
    binding = 'hooshix-redis-tls' if tls else MANAGER
    objects = [resource('ServiceAccount', name, automountServiceAccountToken=False) for name in accounts]
    objects.append(resource('ClusterRoleBinding', binding + '-tokenreview', namespace=None,
        api='rbac.authorization.k8s.io/v1',
        roleRef={'apiGroup': 'rbac.authorization.k8s.io', 'kind': 'ClusterRole',
                 'name': 'hooshix-openbao-tokenreview'},
        subjects=[{'kind': 'ServiceAccount', 'name': name, 'namespace': NAMESPACE} for name in accounts]))
    objects += [resource('Role', binding + '-tokenrequest', api='rbac.authorization.k8s.io/v1',
        rules=[{'apiGroups': [''], 'resources': ['serviceaccounts/token'], 'verbs': ['create'],
                'resourceNames': accounts}]),
        resource('RoleBinding', binding + '-tokenrequest', api='rbac.authorization.k8s.io/v1',
            roleRef={'apiGroup': 'rbac.authorization.k8s.io', 'kind': 'Role',
                     'name': binding + '-tokenrequest'},
            subjects=[{'kind': 'ServiceAccount', 'name': ACCOUNT, 'namespace': NAMESPACE}])]
    return objects


def store(name, audiences):
    item = ALL_AUTH_ENTRIES[name]
    value = application.store(application.SERVICES[0], audiences)
    value['metadata'] = resource('SecretStore', name)['metadata']
    auth = value['spec']['provider']['vault']['auth']['kubernetes']
    auth['role'] = item['account']
    auth['serviceAccountRef']['name'] = item['account']
    return value


def external(name):
    fields = ('tls.crt', 'tls.key', 'ca.crt') if name == TLS_NAME else (
        ('users.acl',) if name == ACL_NAME else ('username', 'password'))
    return resource('ExternalSecret', name, api='external-secrets.io/v1', spec={
        'refreshInterval': '1m', 'secretStoreRef': {'kind': 'SecretStore', 'name': name},
        'target': {'name': name, 'creationPolicy': 'Owner', 'deletionPolicy': 'Retain',
            'template': {'type': 'kubernetes.io/tls' if name == TLS_NAME else (
                             'Opaque' if name == ACL_NAME else 'kubernetes.io/basic-auth'),
                         'metadata': {'labels': LABELS.copy()}}},
        'data': [{'secretKey': key, 'remoteRef': {'key': ALL_AUTH_ENTRIES[name]['key'], 'property': key}}
                 for key in fields]})
