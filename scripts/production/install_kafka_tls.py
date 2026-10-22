"""Create-only Kafka leaf delivery; no CA, broker, data, network or admission writes."""
import base64
import copy
import hashlib
import json

import install_redis_secrets as previous
import install_redis_runtime as redis
import kafka_secret_delivery as contract
import kafka_tls_material as certificates
from install_eso import desired_fields_match

require = certificates.require

# Read-only compatibility with the installed quarantine, never deployment/rollback inputs.
RETAINED_DATABASE_IMAGES = {
    'operator': 'ghcr.io/hasanjodatshandi/hooshix/platform-postgresql-operator-v2-private@sha256:'
                '04bac3c474f9e144c38afdfc8bc79a1a395d5ff53f92d6cbb7678ae95a7bb345',
    'postgresql': 'ghcr.io/hasanjodatshandi/hooshix/platform-postgresql-postgresql-private@sha256:'
                  '4f0dca725887fab09f652314b774bfb0b9430eaa818a61cf6e56c360e923edde'}


def render(existing):
    from render_postgresql_quarantine import boundary
    from render_redis_runtime import boundary as redis_boundary, pod
    from render_redis_runtime import render as runtime
    result = previous.render(existing)
    last = copy.deepcopy(existing['boundary'])
    last['spec'] = copy.deepcopy(result['allowed_boundary_specs'][-1])
    result['allowed_boundary_specs'].append(runtime(last)['boundary']['spec'])
    retained = boundary(existing['boundary'], RETAINED_DATABASE_IMAGES)
    result['allowed_boundary_specs'].append(redis_boundary(retained, pod())['spec'])
    result['kafka_tls_delivery'] = True
    return result


def preserved(kube, ca_digest):
    # Preserve the running Redis instance as well as all existing PG/CA/controller state.
    result = redis.preserved(kube)
    current = redis.get(kube, 'statefulset', redis.NAME, redis.NS)
    require(current and current['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == redis.MANAGER
        and current['metadata'].get('annotations', {}).get(redis.HASH) == redis.digest(current['spec'])
        and current.get('status', {}).get('readyReplicas') == 1, 'KAFKA_EXISTING_REDIS_REQUIRED')
    result += [{'uid': current['metadata']['uid'], 'spec': current['spec']},
        redis.binding(kube, redis.get(kube, 'pvc', redis.volume.CLAIM, redis.NS)),
        redis.secrets(kube, ca_digest)]
    return result


def execute(plan, *, kube, api, root, ca, audiences, token_request, issuer, issue):
    require(plan.get('schema_version') == 1 and plan.get('installation_id') == 'hooshix-production'
        and plan.get('kafka_tls_delivery') is True and plan.get('public_access') == 'CLOSED'
        and plan.get('database') == plan.get('redis') == 'Not run'
        and hashlib.sha256(ca.encode()).hexdigest() == plan['public_ca_sha256'], 'KAFKA_TLS_REVIEWED_PLAN_REQUIRED')
    before = previous.snapshot(kube, plan)
    previous.pg.webhook(plan, kube, commit=False)
    namespace = json.loads(kube('get', 'namespace', contract.NAMESPACE, '-o', 'json'))
    require(namespace['metadata']['labels'].get('istio.io/dataplane-mode') == 'ambient'
        and namespace['metadata']['labels'].get('pod-security.kubernetes.io/enforce') == 'restricted',
        'KAFKA_TLS_RESTRICTED_AMBIENT_REQUIRED')
    reviewer = json.loads(kube('get', 'clusterrole', 'hooshix-openbao-tokenreview', '-o', 'json'))
    require(reviewer.get('rules') == [{'apiGroups': ['authentication.k8s.io'],
        'resources': ['tokenreviews'], 'verbs': ['create']}] and not reviewer.get('aggregationRule'),
        'KAFKA_TLS_EXISTING_TOKENREVIEW_REQUIRED')
    objects = [*contract.foundation(), contract.store(audiences), contract.external()]
    existing = []
    for obj in objects:
        m = obj['metadata']
        raw = kube(*(['-n', m['namespace']] if 'namespace' in m else []),
                   'get', obj['kind'], m['name'], '--ignore-not-found', '-o', 'json')
        old = json.loads(raw) if raw.strip() else None
        require(not old or old['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER
            and not old['metadata'].get('deletionTimestamp') and desired_fields_match(old, obj)
            and all(old.get(k) == obj[k] for k in ('rules', 'roleRef', 'subjects') if k in obj),
            'KAFKA_TLS_FOREIGN_RESOURCE_PRESERVED')
        existing.append(old)
    path = 'hooshix/data/' + contract.ENTRY['key']
    old = api.call(path, token=root, expected=(200, 404))
    contract.check_existing(api, root)
    if old:
        material = old['data']['data']
        require(set(material) == {*certificates.FIELDS, 'managed_by', 'installation_id'}
            and material['managed_by'] == contract.MANAGER and material['installation_id'] == 'hooshix-production',
            'KAFKA_TLS_FOREIGN_MATERIAL_PRESERVED')
    else:
        require(not api.call(path.replace('/data/', '/metadata/'), token=root, expected=(200, 404)),
                'KAFKA_TLS_DELETED_HISTORY_PRESERVED')
    raw = kube('-n', contract.NAMESPACE, 'get', 'secret', contract.NAME,
               '--ignore-not-found', '-o', 'jsonpath={.metadata}')
    if raw.strip():
        metadata, external = json.loads(raw), existing[-1]
        require(old and external and metadata.get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER
            and metadata.get('ownerReferences') == owner(external), 'KAFKA_TLS_FOREIGN_TARGET_PRESERVED')
    # Finish whole-set conflict checks before signing; never overwrite or rotate on reentry.
    if not old:
        material = issue() | {'managed_by': contract.MANAGER, 'installation_id': 'hooshix-production'}
    fields = {k: material[k] for k in certificates.FIELDS}
    certificates.validate(fields, ca, issuer)
    if not old: api.call(path, 'POST', {'options': {'cas': 0}, 'data': material}, root)
    require(api.call(path, token=root)['data']['data'] == material, 'KAFKA_TLS_READBACK_FAILED')
    contract.configure(api, root)
    for obj, current in zip(objects, existing, strict=True):
        if current is None: kube('create', '-f', '-', body=obj)
        if obj['kind'] in ('SecretStore', 'ExternalSecret'):
            kube('-n', contract.NAMESPACE, 'wait', '--for=condition=Ready',
                 obj['kind'].lower() + '/' + contract.NAME, '--timeout=60s', timeout=75)
    previous.verify_auth(api, root, token_request, audiences, entries={contract.NAME: contract.ENTRY})
    actor = '--as=system:serviceaccount:' + contract.NAMESPACE + ':' + contract.ACCOUNT
    for account, expected in ((contract.ENTRY['account'], 0), ('default', 1)):
        require(kube('auth', 'can-i', 'create', 'serviceaccounts/' + account, '--subresource=token',
            '-n', contract.NAMESPACE, actor, expected=expected).strip() == (b'yes' if expected == 0 else b'no'),
            'KAFKA_TLS_TOKENREQUEST_SCOPE_REJECTED')
    secret = json.loads(kube('-n', contract.NAMESPACE, 'get', 'secret', contract.NAME, '-o', 'json'))
    external = json.loads(kube('-n', contract.NAMESPACE, 'get', 'externalsecret', contract.NAME, '-o', 'json'))
    require(secret['type'] == 'kubernetes.io/tls' and set(secret['data']) == set(certificates.FIELDS)
        and secret['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER
        and secret['metadata'].get('ownerReferences') == owner(external)
        and all(base64.b64decode(secret['data'][k], validate=True).decode() == v for k, v in fields.items()),
        'KAFKA_TLS_ESO_READBACK_FAILED')
    require(previous.snapshot(kube, plan) == before, 'KAFKA_TLS_EXISTING_STATE_CHANGED')
    return {'schema_version': 1, 'tls_delivery': 'Passed', 'server_client_chain_key_hostname': 'Passed',
        'scoped_authentication': 'Passed', 'existing_state': 'Preserved', 'public_access': 'CLOSED',
        'tls_lifetime_days': 30, 'automatic_renewal': 'Not run', 'kafka': 'Not run',
        'root_revocation': 'Not run', 'production_readiness': 'Not verified'}


def owner(external):
    return [{'apiVersion': 'external-secrets.io/v1', 'kind': 'ExternalSecret',
        'name': contract.NAME, 'uid': external['metadata']['uid'], 'controller': True, 'blockOwnerDeletion': True}]
