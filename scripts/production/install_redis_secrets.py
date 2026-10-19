"""Create-only Redis ACL delivery through existing data ESO; no runtime or TLS writes."""
import base64
import copy
import hashlib
import json

import install_postgresql_secrets as pg
import openbao_scoped_auth as auth
import redis_secret_delivery as contract
from install_eso import desired_fields_match
from render_redis_acl import acl_file

require = auth.host.require


def render(existing):
    import render_postgresql_quarantine as quarantine
    from verify_published_postgresql import candidate
    images = {k: candidate()['publication']['images'][k] for k in ('operator', 'postgresql')}
    return {'schema_version': 1, 'installation_id': 'hooshix-production',
        'database': 'Not run', 'redis': 'Not run', 'public_access': 'CLOSED',
        'existing_controller': [o for o in existing['objects']
            if o['kind'] in ('Deployment', 'Role', 'RoleBinding', 'ServiceAccount')
            and o['metadata']['name'] in (contract.ACCOUNT, contract.ACCOUNT + '-controller')],
        'public_ca_sha256': existing['public_ca_sha256'],
        'webhook_names': existing['webhook_names'],
        'webhook_contracts': existing['webhook_contracts'],
        'webhook_selector': existing['webhook_selector'],
        'webhook_previous_selector': existing['webhook_selector'],
        'allowed_boundary_specs': [existing['boundary']['spec'],
            quarantine.boundary(existing['boundary'], images)['spec']]}


def credential_plan(api, root):
    values, missing = pg.credential_values(api, root, delivery=contract)
    material = {'users.acl': acl_file({v['username']: hashlib.sha256(v['password'].encode()).hexdigest()
                                     for v in values.values()}),
                'managed_by': contract.MANAGER, 'installation_id': 'hooshix-production'}
    path = 'hooshix/data/' + contract.ACL_ENTRY['key']
    old = api.call(path, token=root, expected=(200, 404))
    require(not old or old['data']['data'] == material, 'REDIS_SECRET_FOREIGN_ACL_PRESERVED')
    if not old:
        require(not api.call(path.replace('/data/', '/metadata/'), token=root, expected=(200, 404)),
                'REDIS_SECRET_DELETED_ACL_HISTORY_PRESERVED')
    return values, missing, material, not old


def verify_auth(api, root, token_request, audiences, *, entries=contract.AUTH_ENTRIES):
    names = list(entries)
    login = 'auth/' + auth.MOUNT + '/login'
    for name in names:
        item = entries[name]
        jwt = token_request(contract.NAMESPACE, item['account'], [*audiences, auth.AUDIENCE])
        session = api.call(login, 'POST', {'role': item['account'], 'jwt': jwt})['auth']
        token = session['client_token']
        try:
            require(session['policies'] == [item['policy']] and type(session['lease_duration']) is int
                and 0 < session['lease_duration'] <= 300, 'REDIS_SECRET_TOKEN_SCOPE_REJECTED')
            path = 'hooshix/data/' + item['key']
            api.call(path, token=token)
            for other in contract.ALL_AUTH_ENTRIES:
                if other != name:
                    api.call('hooshix/data/' + contract.ALL_AUTH_ENTRIES[other]['key'], token=token, expected=403)
            for foreign in ('production/postgresql/identity/runtime',
                            'production/notification-service/commissioning-probe'):
                api.call('hooshix/data/' + foreign, token=token, expected=403)
            api.call(path, 'POST', {'data': {'value': 'denied'}}, token, 403)
            api.call('sys/mounts', token=token, expected=403)
            other = next(n for n in contract.ALL_AUTH_ENTRIES if n != name)
            api.call(login, 'POST', {'role': contract.ALL_AUTH_ENTRIES[other]['account'],
                                    'jwt': jwt}, expected=403)
            for ns, account, requested in [(contract.NAMESPACE, item['account'], audiences),
                    ('default', 'default', [*audiences, auth.AUDIENCE])]:
                wrong = token_request(ns, account, requested)
                api.call(login, 'POST', {'role': item['account'], 'jwt': wrong}, expected=403)
            api.call('auth/token/lookup-self', token=token)
        finally:
            api.call('auth/token/revoke-self', 'POST', {}, token, 204)
        api.call(path, token=token, expected=403)
    require(api.call('auth/token/lookup-self', token=root)['data']['policies'] == ['root'],
            'REDIS_SECRET_ROOT_CHANGED')


def snapshot(kube, plan):
    retained = []
    for obj in plan['existing_controller']:
        m = obj['metadata']
        value = json.loads(kube('-n', contract.NAMESPACE, 'get', obj['kind'], m['name'], '-o', 'json'))
        require(value['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == pg.contract.MANAGER
            and desired_fields_match(value, obj), 'REDIS_SECRET_EXISTING_CONTROLLER_CONFLICT')
        if obj['kind'] == 'Role':
            require(value['rules'] == obj['rules'], 'REDIS_SECRET_EXISTING_CONTROLLER_RBAC_DRIFT')
        if obj['kind'] == 'RoleBinding':
            require(value['subjects'] == obj['subjects'] and value['roleRef'] == obj['roleRef'],
                    'REDIS_SECRET_EXISTING_CONTROLLER_SUBJECT_DRIFT')
        if obj['kind'] == 'Deployment':
            require(value['status'].get('availableReplicas') == 1, 'REDIS_SECRET_EXISTING_CONTROLLER_REQUIRED')
        retained.append({k: copy.deepcopy(value[k]) for k in ('spec', 'rules', 'subjects', 'roleRef') if k in value}
                        | {'uid': value['metadata']['uid']})
    for kind in ('validatingpolicy', 'peerauthentication', 'authorizationpolicy', 'networkpolicy',
                 'clusters.postgresql.cnpg.io', 'pvc'):
        if kind == 'validatingpolicy':
            objects = [json.loads(kube('get', kind, 'hooshix-platform-data-closed', '-o', 'json'))]
            require(objects[0]['spec'] in plan['allowed_boundary_specs']
                and objects[0].get('status', {}).get('conditionStatus', {}).get('ready') is True,
                'REDIS_SECRET_EXISTING_CLOSED_ADMISSION_REQUIRED')
        else:
            objects = json.loads(kube('-n', contract.NAMESPACE, 'get', kind, '-o', 'json'))['items']
        if kind == 'peerauthentication':
            require(any(o['metadata']['name'] == 'hooshix-postgresql-strict'
                and o['spec'] == {'mtls': {'mode': 'STRICT'}} for o in objects)
                and all(o['spec'].get('mtls', {}).get('mode') == 'STRICT'
                        and not o['spec'].get('portLevelMtls') for o in objects),
                'REDIS_SECRET_EXISTING_STRICT_REQUIRED')
        retained.append(sorted((v['metadata']['name'], v['metadata']['uid'], v.get('spec', {}))
                               for v in objects))
    return retained


def execute(plan, *, kube, api, root, ca, audiences, token_request):
    require(plan.get('schema_version') == 1 and plan.get('installation_id') == 'hooshix-production'
        and plan.get('redis') == plan.get('database') == 'Not run' and plan.get('public_access') == 'CLOSED'
        and len(plan.get('existing_controller', [])) == 4
        and hashlib.sha256(ca.encode()).hexdigest() == plan['public_ca_sha256'],
        'REDIS_SECRET_REVIEWED_PLAN_REQUIRED')
    ns = json.loads(kube('get', 'namespace', contract.NAMESPACE, '-o', 'json'))
    require(ns['metadata']['labels'].get('istio.io/dataplane-mode') == 'ambient'
        and ns['metadata']['labels'].get('pod-security.kubernetes.io/enforce') == 'restricted',
        'REDIS_SECRET_EXISTING_NAMESPACE_REQUIRED')
    before = snapshot(kube, plan)
    pg.webhook(plan, kube, commit=False)  # Validate, but never change the installed webhook.
    reviewer = json.loads(kube('get', 'clusterrole', 'hooshix-openbao-tokenreview', '-o', 'json'))
    require(reviewer.get('rules') == [{'apiGroups': ['authentication.k8s.io'],
        'resources': ['tokenreviews'], 'verbs': ['create']}] and not reviewer.get('aggregationRule'),
        'REDIS_SECRET_EXACT_TOKENREVIEW_REQUIRED')
    objects = [*contract.foundation(), *[contract.store(n, audiences) for n in contract.AUTH_ENTRIES],
               *[contract.external(n) for n in contract.AUTH_ENTRIES]]
    actuals = {}
    for obj in objects:
        m = obj['metadata']
        scope = ['-n', m['namespace']] if 'namespace' in m else []
        raw = kube(*scope, 'get', obj['kind'], m['name'], '--ignore-not-found', '-o', 'json')
        actual = json.loads(raw) if raw.strip() else None
        actuals[(obj['kind'], m['name'])] = actual
        require(not actual or actual['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER
            and desired_fields_match(actual, obj), 'REDIS_SECRET_FOREIGN_RESOURCE_PRESERVED')
        if actual:
            require(all(actual.get(k) == obj.get(k) for k in ('rules', 'roleRef', 'subjects') if k in obj),
                    'REDIS_SECRET_EXTRA_PERMISSION_REJECTED')
    for name in contract.AUTH_ENTRIES:
        raw = kube('-n', contract.NAMESPACE, 'get', 'secret', name, '--ignore-not-found', '-o', 'jsonpath={.metadata}')
        if not raw.strip(): continue
        metadata = json.loads(raw)
        external = actuals[('ExternalSecret', name)]
        require(metadata.get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER
            and external and metadata.get('ownerReferences') == [{'apiVersion': 'external-secrets.io/v1',
                'kind': 'ExternalSecret', 'name': name, 'uid': external['metadata']['uid'],
                'controller': True, 'blockOwnerDeletion': True}], 'REDIS_SECRET_FOREIGN_TARGET_PRESERVED')
    contract.check_existing(api, root)
    values, missing, material, create_acl = credential_plan(api, root)
    # Whole-set preflight precedes every write; CAS=0 preserves partial state for safe reentry.
    pg.persist_credentials(api, root, values, missing, delivery=contract)
    path = 'hooshix/data/' + contract.ACL_ENTRY['key']
    if create_acl: api.call(path, 'POST', {'options': {'cas': 0}, 'data': material}, root)
    require(api.call(path, token=root)['data']['data'] == material, 'REDIS_SECRET_ACL_READBACK_FAILED')
    contract.configure(api, root)
    for obj in objects:
        if not actuals[(obj['kind'], obj['metadata']['name'])]: kube('create', '-f', '-', body=obj)
        if obj['kind'] in ('SecretStore', 'ExternalSecret'):
            kube('-n', contract.NAMESPACE, 'wait', '--for=condition=Ready',
                obj['kind'].lower() + '/' + obj['metadata']['name'], '--timeout=60s', timeout=75)
    verify_auth(api, root, token_request, audiences)
    for name in contract.AUTH_ENTRIES:
        expected = {'users.acl': material['users.acl']} if name == contract.ACL_NAME else {
            k: values[name][k] for k in ('username', 'password')}
        secret = json.loads(kube('-n', contract.NAMESPACE, 'get', 'secret', name, '-o', 'json'))
        external = json.loads(kube('-n', contract.NAMESPACE, 'get', 'ExternalSecret', name, '-o', 'json'))
        require(secret['type'] == ('Opaque' if name == contract.ACL_NAME else 'kubernetes.io/basic-auth')
            and set(secret['data']) == set(expected)
            and secret['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER
            and secret['metadata'].get('ownerReferences') == [{'apiVersion': 'external-secrets.io/v1',
                'kind': 'ExternalSecret', 'name': name, 'uid': external['metadata']['uid'],
                'controller': True, 'blockOwnerDeletion': True}]
            and all(base64.b64decode(secret['data'][key], validate=True).decode() == value
                    for key, value in expected.items()), 'REDIS_SECRET_DELIVERY_READBACK_FAILED')
    actor = '--as=system:serviceaccount:' + contract.NAMESPACE + ':' + contract.ACCOUNT
    for item in contract.AUTH_ENTRIES.values():
        require(kube('auth', 'can-i', 'create', 'serviceaccounts/' + item['account'], '--subresource=token',
            '-n', contract.NAMESPACE, actor).strip() == b'yes', 'REDIS_SECRET_FIXED_TOKENREQUEST_REQUIRED')
    require(kube('auth', 'can-i', 'create', 'serviceaccounts/default', '--subresource=token',
        '-n', contract.NAMESPACE, actor, expected=1).strip() == b'no', 'REDIS_SECRET_FOREIGN_TOKENREQUEST_REJECTED')
    for namespace in ('platform-apps', 'hooshix-secrets', 'cnpg-system', 'default'):
        for verb in ('get', 'list', 'watch', 'create', 'patch', 'delete'):
            require(kube('auth', 'can-i', verb, 'secrets', '-n', namespace, actor, expected=1).strip() == b'no',
                    'REDIS_SECRET_FOREIGN_SECRET_ACCESS_REJECTED')
    require(snapshot(kube, plan) == before, 'REDIS_SECRET_EXISTING_STATE_CHANGED')
    return {'schema_version': 1, 'delivery': 'Passed', 'credential_count': 5, 'namespace': contract.NAMESPACE,
        'acl_binding': 'Passed', 'scoped_authentication': 'Passed',
        'foreign_secret_and_tokenrequest_denial': 'Passed', 'existing_data_controller_policy_storage': 'Preserved',
        'redis': 'Not run', 'tls': 'Not run', 'database': 'Not run', 'root_revocation': 'Not run',
        'provider_backup': 'Not run', 'public_access': 'CLOSED', 'production_readiness': 'Not verified'}
