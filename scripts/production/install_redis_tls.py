"""Create-only Redis TLS delivery through existing ESO; no Redis, PVC or network writes."""
import base64
import hashlib
import json

import install_redis_secrets as previous
import redis_secret_delivery as contract
import redis_tls_material as certificates
from install_eso import desired_fields_match

require = previous.require
NAME = contract.TLS_NAME
ENTRY = contract.TLS_ENTRIES[NAME]


def render(existing):
    plan = previous.render(existing)
    plan['redis_tls_delivery'] = True
    return plan


def retained_credentials(api, root, kube):
    values, missing, acl, create_acl = previous.credential_plan(api, root)
    require(not missing and not create_acl and not contract.check_existing(api, root),
            'REDIS_TLS_EXISTING_CREDENTIAL_DELIVERY_REQUIRED')
    retained = []
    for name in contract.AUTH_ENTRIES:
        value = json.loads(kube('-n', contract.NAMESPACE, 'get', 'secret', name, '-o', 'json'))
        external = json.loads(kube('-n', contract.NAMESPACE, 'get', 'externalsecret', name, '-o', 'json'))
        expected = {'users.acl': acl['users.acl']} if name == contract.ACL_NAME else {
            key: values[name][key] for key in ('username', 'password')}
        require(value['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER
            and value['type'] == ('Opaque' if name == contract.ACL_NAME else 'kubernetes.io/basic-auth')
            and desired_fields_match(external, contract.external(name))
            and value['metadata'].get('ownerReferences') == [{'apiVersion': 'external-secrets.io/v1',
                'kind': 'ExternalSecret', 'name': name, 'uid': external['metadata']['uid'],
                'controller': True, 'blockOwnerDeletion': True}]
            and set(value['data']) == set(expected)
            and all(base64.b64decode(value['data'][key], validate=True).decode() == content
                    for key, content in expected.items()), 'REDIS_TLS_EXISTING_CREDENTIAL_BINDING_REQUIRED')
        retained.append((value['metadata']['uid'], value['data']))
    return retained


def execute(plan, *, kube, api, root, ca, audiences, token_request, issuer, issue):
    require(plan.get('schema_version') == 1 and plan.get('installation_id') == 'hooshix-production'
        and plan.get('redis_tls_delivery') is True and plan.get('public_access') == 'CLOSED'
        and plan.get('redis') == plan.get('database') == 'Not run'
        and hashlib.sha256(ca.encode()).hexdigest() == plan['public_ca_sha256'], 'REDIS_TLS_REVIEWED_PLAN_REQUIRED')
    before = previous.snapshot(kube, plan)
    namespace = json.loads(kube('get', 'namespace', contract.NAMESPACE, '-o', 'json'))
    require(namespace['metadata']['labels'].get('istio.io/dataplane-mode') == 'ambient'
        and namespace['metadata']['labels'].get('pod-security.kubernetes.io/enforce') == 'restricted',
        'REDIS_TLS_EXISTING_NAMESPACE_REQUIRED')
    reviewer = json.loads(kube('get', 'clusterrole', 'hooshix-openbao-tokenreview', '-o', 'json'))
    require(reviewer.get('rules') == [{'apiGroups': ['authentication.k8s.io'],
        'resources': ['tokenreviews'], 'verbs': ['create']}] and not reviewer.get('aggregationRule'),
        'REDIS_TLS_EXISTING_TOKENREVIEW_REQUIRED')
    credentials = retained_credentials(api, root, kube)
    previous.pg.webhook(plan, kube, commit=False)
    objects = [*contract.foundation(tls=True), contract.store(NAME, audiences), contract.external(NAME)]
    existing = {}
    for obj in objects:
        m = obj['metadata']
        scope = ['-n', m['namespace']] if 'namespace' in m else []
        raw = kube(*scope, 'get', obj['kind'], m['name'], '--ignore-not-found', '-o', 'json')
        old = json.loads(raw) if raw.strip() else None
        require(not old or old['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER
            and not old['metadata'].get('deletionTimestamp') and desired_fields_match(old, obj)
            and all(old.get(k) == obj[k] for k in ('rules', 'roleRef', 'subjects') if k in obj),
            'REDIS_TLS_FOREIGN_RESOURCE_PRESERVED')
        existing[(obj['kind'], m['name'])] = old
    path = 'hooshix/data/' + ENTRY['key']
    old = api.call(path, token=root, expected=(200, 404))
    contract.check_existing(api, root, entries=contract.TLS_ENTRIES)
    if old:
        material = old['data']['data']
        require(set(material) == {*certificates.FIELDS, 'managed_by', 'installation_id'}
            and material['managed_by'] == contract.MANAGER and material['installation_id'] == 'hooshix-production',
            'REDIS_TLS_FOREIGN_MATERIAL_PRESERVED')
    else:
        require(not api.call(path.replace('/data/', '/metadata/'), token=root, expected=(200, 404)),
                'REDIS_TLS_DELETED_HISTORY_PRESERVED')
    raw = kube('-n', contract.NAMESPACE, 'get', 'secret', NAME, '--ignore-not-found', '-o', 'jsonpath={.metadata}')
    if raw.strip():
        metadata = json.loads(raw)
        external = existing[('ExternalSecret', NAME)]
        require(old and external and metadata.get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER
            and metadata.get('ownerReferences') == [{'apiVersion': 'external-secrets.io/v1', 'kind': 'ExternalSecret',
                'name': NAME, 'uid': external['metadata']['uid'], 'controller': True, 'blockOwnerDeletion': True}],
            'REDIS_TLS_FOREIGN_TARGET_PRESERVED')
    # Complete preflight precedes issuing or persisting any new material. No automatic rotation.
    if not old:
        material = issue() | {'managed_by': contract.MANAGER, 'installation_id': 'hooshix-production'}
    tls_fields = {k: material[k] for k in certificates.FIELDS}
    certificates.validate(tls_fields, ca, issuer)
    if not old: api.call(path, 'POST', {'options': {'cas': 0}, 'data': material}, root)
    require(api.call(path, token=root)['data']['data'] == material, 'REDIS_TLS_READBACK_FAILED')
    contract.configure(api, root, entries=contract.TLS_ENTRIES)
    for obj in objects:
        if not existing[(obj['kind'], obj['metadata']['name'])]: kube('create', '-f', '-', body=obj)
        if obj['kind'] in ('SecretStore', 'ExternalSecret'):
            kube('-n', contract.NAMESPACE, 'wait', '--for=condition=Ready',
                 obj['kind'].lower() + '/' + NAME, '--timeout=60s', timeout=75)
    previous.verify_auth(api, root, token_request, audiences, entries=contract.TLS_ENTRIES)
    actor = '--as=system:serviceaccount:' + contract.NAMESPACE + ':' + contract.ACCOUNT
    require(kube('auth', 'can-i', 'create', 'serviceaccounts/' + ENTRY['account'], '--subresource=token',
        '-n', contract.NAMESPACE, actor).strip() == b'yes', 'REDIS_TLS_FIXED_TOKENREQUEST_REQUIRED')
    require(kube('auth', 'can-i', 'create', 'serviceaccounts/default', '--subresource=token',
        '-n', contract.NAMESPACE, actor, expected=1).strip() == b'no', 'REDIS_TLS_FOREIGN_TOKENREQUEST_REJECTED')
    secret = json.loads(kube('-n', contract.NAMESPACE, 'get', 'secret', NAME, '-o', 'json'))
    external = json.loads(kube('-n', contract.NAMESPACE, 'get', 'externalsecret', NAME, '-o', 'json'))
    require(secret['type'] == 'kubernetes.io/tls' and set(secret['data']) == set(certificates.FIELDS)
        and secret['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER
        and secret['metadata'].get('ownerReferences') == [{'apiVersion': 'external-secrets.io/v1',
            'kind': 'ExternalSecret', 'name': NAME, 'uid': external['metadata']['uid'],
            'controller': True, 'blockOwnerDeletion': True}]
        and all(base64.b64decode(secret['data'][k], validate=True).decode() == v for k, v in tls_fields.items()),
        'REDIS_TLS_ESO_READBACK_FAILED')
    require(previous.snapshot(kube, plan) == before and retained_credentials(api, root, kube) == credentials,
            'REDIS_TLS_EXISTING_STATE_CHANGED')
    return {'schema_version': 1, 'namespace': contract.NAMESPACE, 'tls_delivery': 'Passed',
        'server_chain_key_hostname': 'Passed', 'scoped_authentication': 'Passed',
        'existing_credentials_acl_controller_policy_storage': 'Preserved', 'public_access': 'CLOSED',
        'tls_lifetime_days': 30, 'automatic_renewal': 'Not run', 'redis': 'Not run',
        'database': 'Not run', 'provider_backup': 'Not run', 'root_revocation': 'Not run',
        'production_readiness': 'Not verified'}
