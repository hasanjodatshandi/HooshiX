"""One fixed Kafka node leaf through the existing data-namespace ESO."""
import copy

import openbao_scoped_auth as application
import postgresql_secret_delivery as pg

NAMESPACE, ACCOUNT = pg.NAMESPACE, pg.ACCOUNT
NAME, MANAGER = 'kafka-node-tls', 'hooshix-kafka-secret-delivery'
ENTRY = {'account': 'eso-kafka-tls', 'policy': 'hooshix-kafka-tls',
         'key': 'production/kafka/node-tls'}
LABELS = {'app.kubernetes.io/part-of': 'hooshix-platform', 'app.kubernetes.io/managed-by': MANAGER}


def resource(kind, name, **body):
    result = pg.resource(kind, name, **body)
    result['metadata']['labels'] = LABELS.copy()
    return result


def policy():
    return (f'path "hooshix/data/{ENTRY["key"]}" {{ capabilities = ["read"] }}\n'
            'path "auth/token/lookup-self" { capabilities = ["read"] }\n'
            'path "auth/token/revoke-self" { capabilities = ["update"] }\n')


def role():
    result = copy.deepcopy(application.role(application.SERVICES[0]))
    result.update(bound_service_account_names=[ENTRY['account']],
        bound_service_account_namespaces=[NAMESPACE], token_policies=[ENTRY['policy']])
    return result


def check_existing(api, root):
    pending = []
    for path, body in [('sys/policies/acl/' + ENTRY['policy'], {'policy': policy()}),
            ('auth/' + application.MOUNT + '/role/' + ENTRY['account'], role())]:
        old = api.call(path, token=root, expected=(200, 404))
        application.host.require(not old or all(old['data'].get(k, '').strip() == v.strip()
            if k == 'policy' else old['data'].get(k) == v for k, v in body.items()),
            'KAFKA_TLS_AUTH_CONFLICT')
        if not old: pending.append((path, body))
    return pending


def configure(api, root):
    for path, body in check_existing(api, root):
        api.call(path, 'PUT' if path.startswith('sys/') else 'POST', body, root, 204)
    application.host.require(not check_existing(api, root), 'KAFKA_TLS_AUTH_READBACK_FAILED')


def foundation():
    account, binding = ENTRY['account'], 'hooshix-kafka-tls'
    ref = {'apiGroup': 'rbac.authorization.k8s.io', 'kind': 'Role', 'name': binding + '-tokenrequest'}
    return [resource('ServiceAccount', account, automountServiceAccountToken=False),
        resource('ClusterRoleBinding', binding + '-tokenreview', namespace=None,
            api='rbac.authorization.k8s.io/v1',
            roleRef={'apiGroup': 'rbac.authorization.k8s.io', 'kind': 'ClusterRole',
                     'name': 'hooshix-openbao-tokenreview'},
            subjects=[{'kind': 'ServiceAccount', 'namespace': NAMESPACE, 'name': account}]),
        resource('Role', binding + '-tokenrequest', api='rbac.authorization.k8s.io/v1',
            rules=[{'apiGroups': [''], 'resources': ['serviceaccounts/token'],
                    'verbs': ['create'], 'resourceNames': [account]}]),
        resource('RoleBinding', binding + '-tokenrequest', api='rbac.authorization.k8s.io/v1',
            roleRef=ref, subjects=[{'kind': 'ServiceAccount', 'namespace': NAMESPACE, 'name': ACCOUNT}])]


def store(audiences):
    result = copy.deepcopy(application.store(application.SERVICES[0], audiences))
    result['metadata'] = resource('SecretStore', NAME)['metadata']
    auth = result['spec']['provider']['vault']['auth']['kubernetes']
    auth['role'] = auth['serviceAccountRef']['name'] = ENTRY['account']
    return result


def external():
    return resource('ExternalSecret', NAME, api='external-secrets.io/v1', spec={
        'refreshInterval': '1m', 'secretStoreRef': {'kind': 'SecretStore', 'name': NAME},
        'target': {'name': NAME, 'creationPolicy': 'Owner', 'deletionPolicy': 'Retain',
            'template': {'type': 'kubernetes.io/tls', 'metadata': {'labels': LABELS.copy()}}},
        'data': [{'secretKey': key, 'remoteRef': {'key': ENTRY['key'], 'property': key}}
                 for key in ('tls.crt', 'tls.key', 'ca.crt')]})
