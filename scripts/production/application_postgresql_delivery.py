"""Namespace-local configtree projection of the ten existing PostgreSQL credentials."""
import copy

import openbao_scoped_auth as application
import postgresql_secret_delivery as source

NAMESPACE = application.NAMESPACE
ACCOUNT = 'hooshix-eso'
MANAGER = 'hooshix-application-postgresql-delivery'
ENTRIES = {name: {**item, 'account': 'eso-app-pg-' + name,
                 'policy': 'hooshix-app-pg-' + name} for name, item in source.ENTRIES.items()}
FIELDS = {'spring.datasource.username': 'username', 'spring.datasource.password': 'password'}


def entry(name):
    if name not in ENTRIES:
        raise ValueError('PG_APP_FIXED_IDENTITY_REQUIRED')
    return ENTRIES[name]


def resource(kind, name, *, api='v1', namespace=NAMESPACE, **body):
    value = source.resource(kind, name, api=api, namespace=namespace, **body)
    value['metadata']['labels']['app.kubernetes.io/managed-by'] = MANAGER
    return value


def policy(name):
    entry(name)
    return source.policy(name)


def role(name):
    item = entry(name)
    value = copy.deepcopy(source.role(name))
    value.update(bound_service_account_names=[item['account']],
                 bound_service_account_namespaces=[NAMESPACE], token_policies=[item['policy']])
    return value


def foundation():
    names = [item['account'] for item in ENTRIES.values()]
    objects = [resource('ServiceAccount', name, automountServiceAccountToken=False) for name in names]
    objects.append(resource('ClusterRoleBinding', MANAGER + '-tokenreview', namespace=None,
        api='rbac.authorization.k8s.io/v1',
        roleRef={'apiGroup': 'rbac.authorization.k8s.io', 'kind': 'ClusterRole',
                 'name': 'hooshix-openbao-tokenreview'},
        subjects=[{'kind': 'ServiceAccount', 'namespace': NAMESPACE, 'name': name} for name in names]))
    name = MANAGER + '-tokenrequest'
    objects.extend([resource('Role', name, api='rbac.authorization.k8s.io/v1', rules=[{
        'apiGroups': [''], 'resources': ['serviceaccounts/token'], 'verbs': ['create'],
        'resourceNames': names}]),
        resource('RoleBinding', name, api='rbac.authorization.k8s.io/v1',
            roleRef={'apiGroup': 'rbac.authorization.k8s.io', 'kind': 'Role', 'name': name},
            subjects=[{'kind': 'ServiceAccount', 'namespace': NAMESPACE, 'name': ACCOUNT}])])
    return objects


def store(name, audiences):
    value = source.store(name, audiences)
    value['metadata'] = resource('SecretStore', name)['metadata']
    auth = value['spec']['provider']['vault']['auth']['kubernetes']
    auth.update(role=entry(name)['account'])
    auth['serviceAccountRef']['name'] = entry(name)['account']
    return value


def external(name):
    value = source.external(name)
    value['metadata'] = resource('ExternalSecret', name)['metadata']
    value['spec']['target']['template'] = {'type': 'Opaque', 'metadata': {'labels': {
        'app.kubernetes.io/part-of': 'hooshix-platform', 'app.kubernetes.io/managed-by': MANAGER}}}
    value['spec']['data'] = [{'secretKey': field, 'remoteRef': {
        'key': entry(name)['key'], 'property': source_field}} for field, source_field in FIELDS.items()]
    return value


def auth_changes(api, root):
    pending = []
    for name, item in ENTRIES.items():
        for path, body in [('sys/policies/acl/' + item['policy'], {'policy': policy(name)}),
                ('auth/' + application.MOUNT + '/role/' + item['account'], role(name))]:
            old = api.call(path, token=root, expected=(200, 404))
            if old:
                if not all(old['data'].get(k) == v for k, v in body.items()):
                    raise ValueError('PG_APP_FOREIGN_AUTH_PRESERVED')
            else:
                pending.append((path, body))
    return pending
