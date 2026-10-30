"""Create-only application projections; no KV writes, database, network or workload changes."""
import base64
import hashlib
import json

import application_postgresql_delivery as contract
import install_postgresql_secrets as pg
from install_eso import desired_fields_match

require = pg.require


def get(kube, kind, name, namespace=None):
    scope = ['-n', namespace] if namespace else []
    raw = kube(*scope, 'get', kind, name, '--ignore-not-found', '-o', 'json')
    return json.loads(raw) if raw.strip() else None


def owner(external):
    return [{'apiVersion': 'external-secrets.io/v1', 'kind': 'ExternalSecret',
        'name': external['metadata']['name'], 'uid': external['metadata']['uid'],
        'controller': True, 'blockOwnerDeletion': True}]


def ready(value):
    return any(c.get('type') == 'Ready' and c.get('status') == 'True'
               for c in (value or {}).get('status', {}).get('conditions', []))


def snapshot(kube):
    result = []
    for namespace in ('platform-apps', 'platform-data', 'hooshix-secrets'):
        ns = get(kube, 'namespace', namespace)
        labels = (ns or {}).get('metadata', {}).get('labels', {})
        require(labels.get('istio.io/dataplane-mode') == 'ambient'
            and labels.get('pod-security.kubernetes.io/enforce') == 'restricted',
            'PG_APP_RESTRICTED_AMBIENT_REQUIRED')
        result.append((ns['metadata']['uid'], labels))
        for kind in ('networkpolicies', 'authorizationpolicies.security.istio.io',
                     'peerauthentications.security.istio.io', 'deployments', 'statefulsets', 'pvc'):
            objects = json.loads(kube('-n', namespace, 'get', kind, '-o', 'json'))['items']
            result.append(sorted((o['metadata']['uid'], json.dumps(o.get('spec', {}), sort_keys=True))
                                 for o in objects))
    for name in contract.ENTRIES:
        source = get(kube, 'secret', name, contract.source.NAMESPACE)
        require(source and source.get('type') == 'kubernetes.io/basic-auth'
            and source['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.source.MANAGER,
            'PG_APP_EXISTING_DATA_DELIVERY_REQUIRED')
        external = get(kube, 'externalsecret', name, contract.source.NAMESPACE)
        require(ready(external) and desired_fields_match(external, contract.source.external(name))
            and source['metadata'].get('ownerReferences') == owner(external),
            'PG_APP_EXISTING_SOURCE_OWNER_REQUIRED')
        result.append((external['metadata']['uid'], external['spec']))
        result.append((source['metadata']['uid'], hashlib.sha256(json.dumps(
            source['data'], sort_keys=True).encode()).hexdigest()))
    return result


def execute(plan, *, kube, api, root, ca, audiences, token_request):
    require(plan.get('schema_version') == 1 and plan.get('installation_id') == 'hooshix-production'
        and plan.get('application_postgresql_delivery') is True
        and plan.get('application_ingress') == plan.get('public_access') == 'CLOSED'
        and hashlib.sha256(ca.encode()).hexdigest() == plan.get('public_ca_sha256'),
        'PG_APP_EXACT_CLOSED_PLAN_REQUIRED')
    before = snapshot(kube)
    boundary = get(kube, 'authorizationpolicy', 'hooshix-auth-default-deny', contract.NAMESPACE)
    strict = get(kube, 'peerauthentication', 'hooshix-auth-strict', contract.NAMESPACE)
    require(boundary and boundary.get('spec', {}) == {} and strict
        and strict.get('spec') == {'mtls': {'mode': 'STRICT'}}, 'PG_APP_CLOSED_MESH_REQUIRED')
    deployment = get(kube, 'deployment', contract.ACCOUNT, contract.NAMESPACE)
    require(deployment and deployment.get('status', {}).get('availableReplicas') == 1
        and deployment['spec']['template']['spec']['serviceAccountName'] == contract.ACCOUNT,
        'PG_APP_EXISTING_READY_CONTROLLER_REQUIRED')
    containers = deployment['spec']['template']['spec'].get('containers', [])
    require(len(containers) == 1 and containers[0].get('image') == plan.get('existing_eso_image')
        and isinstance(plan.get('existing_eso_image'), str) and '@sha256:' in plan['existing_eso_image'],
        'PG_APP_EXACT_EXISTING_ESO_IMAGE_REQUIRED')
    reviewer = get(kube, 'clusterrole', 'hooshix-openbao-tokenreview')
    require(reviewer and reviewer.get('rules') == [{'apiGroups': ['authentication.k8s.io'],
        'resources': ['tokenreviews'], 'verbs': ['create']}] and not reviewer.get('aggregationRule'),
        'PG_APP_EXACT_TOKENREVIEW_REQUIRED')
    trust = get(kube, 'configmap', 'hooshix-openbao-ca', contract.NAMESPACE)
    require(trust and trust.get('data') == {'ca.crt': ca}, 'PG_APP_PINNED_CA_REQUIRED')
    values, missing = pg.credential_values(api, root)
    require(not missing, 'PG_APP_EXISTING_CREDENTIALS_ONLY')
    for name, expected in values.items():
        old = get(kube, 'secret', name, contract.source.NAMESPACE)
        require(set(old.get('data', {})) == {'username', 'password'} and all(
            base64.b64decode(old['data'][key], validate=True).decode() == expected[key]
            for key in ('username', 'password')), 'PG_APP_SOURCE_VALUE_MISMATCH')
    pending_auth = contract.auth_changes(api, root)
    objects = [*contract.foundation(), *[contract.store(n, audiences) for n in contract.ENTRIES],
               *[contract.external(n) for n in contract.ENTRIES]]
    existing = {}
    for obj in objects:
        m = obj['metadata']
        old = get(kube, obj['kind'], m['name'], m.get('namespace'))
        existing[(obj['kind'], m.get('namespace'), m['name'])] = old
        if old:
            if obj['kind'] == 'ExternalSecret':
                spec = old.get('spec', {})
                template = spec.get('target', {}).get('template', {})
                require(not spec.get('dataFrom') and not template.get('data')
                    and not template.get('templateFrom'), 'PG_APP_EXTRA_SECRET_INPUT_REJECTED')
            if obj['kind'] == 'SecretStore':
                provider = old.get('spec', {}).get('provider', {})
                require(set(provider) == {'vault'} and set(provider['vault'].get('auth', {})) == {'kubernetes'},
                        'PG_APP_EXTRA_PROVIDER_REJECTED')
                vault, desired = provider['vault'], obj['spec']['provider']['vault']
                require(all(key in desired or key in ('readYourWrites', 'forwardInconsistent') and value is False
                    for key, value in vault.items())
                    and set(vault['auth']['kubernetes']) == set(desired['auth']['kubernetes']),
                    'PG_APP_EXTRA_PROVIDER_OPTION_REJECTED')
            require(old['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER
                and not old['metadata'].get('deletionTimestamp')
                and not old['metadata'].get('ownerReferences')
                and desired_fields_match(old, obj), 'PG_APP_FOREIGN_RESOURCE_PRESERVED')
            for field in ('rules', 'subjects', 'roleRef'):
                if field in obj:
                    require(old.get(field) == obj[field], 'PG_APP_EXTRA_PERMISSION_OR_DRIFT')
    for name in contract.ENTRIES:
        old = get(kube, 'secret', name, contract.NAMESPACE)
        external = existing[('ExternalSecret', contract.NAMESPACE, name)]
        require(not old or external and old['metadata'].get('ownerReferences') == owner(external)
            and old['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER,
            'PG_APP_FOREIGN_SECRET_PRESERVED')
        if old:
            require(old.get('type') == 'Opaque' and set(old.get('data', {})) == set(contract.FIELDS)
                and all(base64.b64decode(old['data'][field], validate=True).decode() == values[name][source]
                        for field, source in contract.FIELDS.items()), 'PG_APP_EXISTING_SECRET_DRIFT')
    for path, body in pending_auth:
        api.call(path, 'PUT' if path.startswith('sys/') else 'POST', body, root, 204)
    require(not contract.auth_changes(api, root), 'PG_APP_AUTH_READBACK_REQUIRED')
    for obj in objects:
        m = obj['metadata']
        if existing[(obj['kind'], m.get('namespace'), m['name'])] is None:
            kube('create', '-f', '-', body=obj)
        if obj['kind'] in ('SecretStore', 'ExternalSecret'):
            kube('-n', contract.NAMESPACE, 'wait', '--for=condition=Ready',
                obj['kind'].lower() + '/' + m['name'], '--timeout=60s', timeout=75)
    pg.verify_auth(api, root, token_request, audiences, delivery=contract, read_only_values=True)
    actor = '--as=system:serviceaccount:' + contract.NAMESPACE + ':' + contract.ACCOUNT
    require(kube('auth', 'can-i', 'get', 'secrets', '-n', contract.source.NAMESPACE,
                 actor, expected=1).strip() == b'no', 'PG_APP_CROSS_NAMESPACE_SECRET_DENIAL_REQUIRED')
    for name, expected in values.items():
        external = get(kube, 'externalsecret', name, contract.NAMESPACE)
        secret = get(kube, 'secret', name, contract.NAMESPACE)
        require(ready(external) and desired_fields_match(external, contract.external(name))
            and secret and secret.get('type') == 'Opaque'
            and secret['metadata'].get('ownerReferences') == owner(external)
            and secret['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER
            and set(secret.get('data', {})) == set(contract.FIELDS)
            and all(base64.b64decode(secret['data'][field], validate=True).decode() == expected[source]
                    for field, source in contract.FIELDS.items()), 'PG_APP_CONFIGTREE_READBACK_REQUIRED')
    require(before == snapshot(kube), 'PG_APP_EXISTING_STATE_CHANGED')
    return {'schema_version': 1, 'application_postgresql_delivery': 'Passed', 'credential_count': 10,
        'namespace': contract.NAMESPACE, 'existing_eso_image': plan['existing_eso_image'],
        'scoped_authentication': 'Passed',
        'existing_state': 'Preserved', 'kv_writes': 'Not run', 'database': 'Not run',
        'root_revocation': 'Not run', 'application_ingress': 'CLOSED', 'public_access': 'CLOSED',
        'production_readiness': 'Not verified'}
