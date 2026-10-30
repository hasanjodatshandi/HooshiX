"""Create only three service-scoped Redis connection projections; never open access."""
import base64
import hashlib
import re

import application_redis_delivery as contract
import install_application_postgresql_delivery as pg
from install_eso import desired_fields_match

require = pg.require


def references_safe(external):
    for item in external.get('spec', {}).get('data', []):
        if set(item) != {'secretKey', 'remoteRef'}:
            return False
        ref = item.get('remoteRef', {})
        if any(key not in ('key', 'property', 'version', 'conversionStrategy', 'decodingStrategy', 'metadataPolicy')
               for key in ref):
            return False
        if (ref.get('version', '') != '' or ref.get('conversionStrategy', 'Default') != 'Default'
            or ref.get('decodingStrategy', 'None') != 'None' or ref.get('metadataPolicy', 'None') != 'None'):
            return False
    return True


def sources(kube):
    values = {}
    for name, (_, source_name, _) in contract.ENTRIES.items():
        item = contract.source.entry(source_name)
        secret = pg.get(kube, 'secret', source_name, contract.source.NAMESPACE)
        external = pg.get(kube, 'externalsecret', source_name, contract.source.NAMESPACE)
        source_spec = (external or {}).get('spec', {})
        source_template = source_spec.get('target', {}).get('template', {})
        require(secret and external and pg.ready(external)
            and desired_fields_match(external, contract.source.external(source_name))
            and not source_spec.get('dataFrom') and not source_template.get('templateFrom')
            and not source_template.get('data') and references_safe(external)
            and secret.get('type') == 'kubernetes.io/basic-auth'
            and secret['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.source.MANAGER
            and secret['metadata'].get('ownerReferences') == pg.owner(external)
            and set(secret.get('data', {})) == {'username', 'password'}, 'REDIS_APP_EXISTING_SOURCE_REQUIRED')
        username = base64.b64decode(secret['data']['username'], validate=True).decode()
        password = base64.b64decode(secret['data']['password'], validate=True).decode()
        require(username == item['username'] and re.fullmatch(r'[A-Za-z0-9_-]{64}', password),
                'REDIS_APP_EXISTING_CREDENTIAL_REQUIRED')
        values[name] = (username, password, secret['metadata']['uid'], external['metadata']['uid'])
    return values


def execute(plan, *, kube, api, root, ca, audiences, token_request):
    require(plan.get('schema_version') == 1 and plan.get('installation_id') == 'hooshix-production'
        and plan.get('application_redis_delivery') is True
        and plan.get('application_ingress') == plan.get('public_access') == 'CLOSED'
        and hashlib.sha256(ca.encode()).hexdigest() == plan.get('public_ca_sha256'),
        'REDIS_APP_EXACT_CLOSED_PLAN_REQUIRED')
    before, values = pg.snapshot(kube), sources(kube)
    provider_values, missing = pg.pg.credential_values(api, root, delivery=contract.source)
    require(not missing and all(values[name][:2] == (provider_values[source_name]['username'],
        provider_values[source_name]['password']) for name, (_, source_name, _) in contract.ENTRIES.items()),
        'REDIS_APP_PROVIDER_SOURCE_MISMATCH')
    boundary = pg.get(kube, 'authorizationpolicy', 'hooshix-auth-default-deny', contract.NAMESPACE)
    strict = pg.get(kube, 'peerauthentication', 'hooshix-auth-strict', contract.NAMESPACE)
    require(boundary and boundary.get('spec', {}) == {} and strict
        and strict.get('spec') == {'mtls': {'mode': 'STRICT'}}, 'REDIS_APP_CLOSED_MESH_REQUIRED')
    deployment = pg.get(kube, 'deployment', 'hooshix-eso', contract.NAMESPACE)
    require(deployment and deployment.get('status', {}).get('availableReplicas') == 1
        and deployment['spec']['template']['spec'].get('serviceAccountName') == 'hooshix-eso'
        and [c.get('image') for c in deployment['spec']['template']['spec'].get('containers', [])]
            == [plan.get('existing_eso_image')], 'REDIS_APP_EXISTING_ESO_REQUIRED')
    trust = pg.get(kube, 'configmap', 'hooshix-openbao-ca', contract.NAMESPACE)
    require(trust and trust.get('data') == {'ca.crt': ca}, 'REDIS_APP_EXISTING_CA_REQUIRED')
    pending = []
    for name, (service, _, prop) in contract.ENTRIES.items():
        store = pg.get(kube, 'secretstore', 'hooshix-' + service, contract.NAMESPACE)
        expected_store = contract.auth.store(service, audiences)
        require(store and pg.ready(store) and desired_fields_match(store, expected_store)
                and store['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by')
                    == contract.auth.LABELS['app.kubernetes.io/managed-by']
                and set(store.get('spec', {}).get('provider', {})) == {'vault'}
                and set(store['spec']['provider']['vault'].get('auth', {})) == {'kubernetes'},
                'REDIS_APP_EXISTING_SERVICE_STORE_REQUIRED')
        vault, desired = store['spec']['provider']['vault'], expected_store['spec']['provider']['vault']
        require(all(key in desired or key in ('readYourWrites', 'forwardInconsistent') and value is False
                    for key, value in vault.items())
            and set(vault['auth']['kubernetes']) == set(desired['auth']['kubernetes']),
            'REDIS_APP_EXTRA_PROVIDER_OPTION_REJECTED')
        policy = api.call('sys/policies/acl/hooshix-read-' + service, token=root, expected=200)
        role = api.call('auth/' + contract.auth.MOUNT + '/role/eso-' + service, token=root, expected=200)
        require(policy and policy.get('data', {}).get('policy', '').strip() == contract.auth.policy(service).strip()
            and role and all(role.get('data', {}).get(key) == value
                             for key, value in contract.auth.role(service).items()),
            'REDIS_APP_EXISTING_SCOPED_AUTH_REQUIRED')
        obj = contract.external(name)
        external = pg.get(kube, 'externalsecret', name, contract.NAMESPACE)
        secret = pg.get(kube, 'secret', name, contract.NAMESPACE)
        if external:
            spec = external.get('spec', {})
            template = spec.get('target', {}).get('template', {})
            require(external['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER
                and not external['metadata'].get('ownerReferences') and not external['metadata'].get('deletionTimestamp')
                and not spec.get('dataFrom') and not template.get('templateFrom')
                and template.get('data') == obj['spec']['target']['template']['data']
                and template.get('mergePolicy', 'Replace') == 'Replace'
                and references_safe(external)
                and desired_fields_match(external, obj), 'REDIS_APP_FOREIGN_EXTERNAL_SECRET_PRESERVED')
        else:
            require(not secret, 'REDIS_APP_FOREIGN_SECRET_PRESERVED')
            pending.append(obj)
        if secret:
            require(external and secret['metadata'].get('ownerReferences') == pg.owner(external)
                and secret['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER
                and secret.get('type') == 'Opaque' and set(secret.get('data', {})) == {prop}
                and base64.b64decode(secret['data'][prop], validate=True).decode()
                    == 'rediss://' + values[name][0] + ':' + values[name][1] + '@' + contract.HOST,
                'REDIS_APP_EXISTING_SECRET_DRIFT')
    for obj in pending:
        kube('create', '-f', '-', body=obj)
    for name, (_, _, prop) in contract.ENTRIES.items():
        kube('-n', contract.NAMESPACE, 'wait', '--for=condition=Ready', 'externalsecret/' + name,
             '--timeout=60s', timeout=75)
        external = pg.get(kube, 'externalsecret', name, contract.NAMESPACE)
        secret = pg.get(kube, 'secret', name, contract.NAMESPACE)
        template = (external or {}).get('spec', {}).get('target', {}).get('template', {})
        require(pg.ready(external) and desired_fields_match(external, contract.external(name))
            and not external['spec'].get('dataFrom') and not template.get('templateFrom')
            and template.get('data') == contract.external(name)['spec']['target']['template']['data']
            and template.get('mergePolicy', 'Replace') == 'Replace'
            and references_safe(external)
            and secret and secret.get('type') == 'Opaque' and secret['metadata'].get('ownerReferences') == pg.owner(external)
            and secret['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER
            and set(secret.get('data', {})) == {prop}
            and base64.b64decode(secret['data'][prop], validate=True).decode()
                == 'rediss://' + values[name][0] + ':' + values[name][1] + '@' + contract.HOST,
            'REDIS_APP_PROJECTED_SECRET_MISMATCH')
    after_provider, missing = pg.pg.credential_values(api, root, delivery=contract.source)
    require(before == pg.snapshot(kube) and values == sources(kube)
        and not missing and after_provider == provider_values, 'REDIS_APP_EXISTING_STATE_CHANGED')
    return {'schema_version': 1, 'application_redis_delivery': 'Passed', 'credential_count': 3,
        'namespace': contract.NAMESPACE, 'existing_eso_image': plan['existing_eso_image'],
        'existing_state': 'Preserved', 'kv_writes': 'Not run', 'database': 'Not run',
        'redis_runtime': 'Not run', 'root_revocation': 'Not run',
        'application_ingress': 'CLOSED', 'public_access': 'CLOSED', 'production_readiness': 'Not verified'}
