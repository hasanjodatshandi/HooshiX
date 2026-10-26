"""Create-only application TLS delivery; existing Kafka and application permissions stay closed."""
import base64
import hashlib
import json

import install_kafka_runtime as runtime
import kafka_client_tls as contract
from install_eso import desired_fields_match, MANAGER as ESO_MANAGER

require = contract.require
MARKERS = {'managed_by': contract.MANAGER, 'installation_id': 'hooshix-production'}


def ready(value):
    return any(c.get('type') == 'Ready' and c.get('status') == 'True'
               for c in value.get('status', {}).get('conditions', []))


def snapshot(kube, plan):
    expected = plan['kafka_runtime']
    ca = plan['public_ca_sha256']
    retained = runtime.tls.preserved(kube, ca)
    current = runtime.get(kube, 'statefulset', runtime.NAME, runtime.NS)
    runtime.owned(current, expected['statefulset'])
    require(current and current['spec']['replicas'] == 1
        and current.get('status', {}).get('readyReplicas') == 1,
        'KAFKA_CLIENT_READY_CLOSED_BROKER_REQUIRED')
    boundary = runtime.boundary_for_statefulset(expected, current['metadata']['uid'])
    active = runtime.get(kube, 'validatingpolicy', boundary['metadata']['name'])
    require(active and active['spec'] == boundary['spec']
        and active.get('status', {}).get('conditionStatus', {}).get('ready') is True,
        'KAFKA_CLIENT_CLOSED_BOUNDARY_REQUIRED')
    runtime.isolation(expected, kube, require_desired=True)
    runtime.pod_inventory(kube, current, required=True)
    retained += [current['metadata']['uid'], current['spec'], active['metadata']['uid'], active['spec'],
        runtime.binding(kube, runtime.get(kube, 'pvc', runtime.volume.CLAIM, runtime.NS)),
        runtime.node_secret(kube, ca)]
    for desired in [*expected['objects'], *expected['source_policies']]:
        if desired['kind'] == 'PersistentVolumeClaim':
            continue  # Exact Retain-volume/PVC identity is verified by binding above.
        metadata = desired['metadata']
        existing = runtime.get(kube, desired['kind'], metadata['name'], metadata.get('namespace'))
        require(existing, 'KAFKA_CLIENT_EXISTING_BROKER_RESOURCE_REQUIRED')
        runtime.owned(existing, desired)
        if desired['kind'] == 'ImageValidatingPolicy':
            require(existing.get('status', {}).get('conditionStatus', {}).get('ready') is True,
                    'KAFKA_CLIENT_ACTIVE_SOURCE_VERIFICATION_REQUIRED')
        retained.append((existing['metadata']['uid'], existing.get('spec'), existing.get('data')))
    namespace = runtime.get(kube, 'namespace', contract.NAMESPACE)
    labels = namespace['metadata'].get('labels', {})
    require(labels.get('istio.io/dataplane-mode') == 'ambient'
        and labels.get('pod-security.kubernetes.io/enforce') == 'restricted',
        'KAFKA_CLIENT_RESTRICTED_NAMESPACE_REQUIRED')
    deployment = runtime.get(kube, 'deployment', 'hooshix-eso', contract.NAMESPACE)
    require(deployment and deployment.get('status', {}).get('availableReplicas') == 1
        and deployment['spec']['template']['spec']['serviceAccountName'] == 'hooshix-eso',
        'KAFKA_CLIENT_EXISTING_APPLICATION_ESO_REQUIRED')
    retained += [namespace['metadata']['uid'], labels, deployment['metadata']['uid'], deployment['spec']]
    for service in contract.SERVICES:
        store = runtime.get(kube, 'secretstore', 'hooshix-' + service, contract.NAMESPACE)
        configured = (store or {}).get('spec', {}).get('provider', {}).get('vault', {}).get(
            'auth', {}).get('kubernetes', {}).get('serviceAccountRef', {}).get('audiences', [])
        require(isinstance(configured, list) and contract.auth.AUDIENCE in configured,
                'KAFKA_CLIENT_EXISTING_AUDIENCES_REQUIRED')
        audiences = plan.get('audiences', [a for a in configured if a != contract.auth.AUDIENCE])
        expected_store = contract.auth.store(service, audiences)
        expected_store['metadata']['labels']['app.kubernetes.io/managed-by'] = ESO_MANAGER
        require(store and ready(store)
            and desired_fields_match(store, expected_store),
            'KAFKA_CLIENT_EXISTING_STORE_REQUIRED')
        retained.append((store['metadata']['uid'], store['spec']))
    return retained


def credentials(api, root, ca, issuer, issue, existing_services=()):
    values, missing = {}, []
    for service in contract.SERVICES:
        path = 'hooshix/data/' + contract.entry(service)['key']
        old = api.call(path, token=root, expected=(200, 404))
        if old:
            value = old['data']['data']
            require(set(value) == {*contract.FIELDS.values(), *contract.SETTINGS, *MARKERS}
                and all(value[k] == v for k, v in MARKERS.items()),
                'KAFKA_CLIENT_FOREIGN_VALUE_PRESERVED')
            fields = {k: v for k, v in value.items() if k not in MARKERS}
            contract.validate(service, fields, ca, issuer)
            values[service] = fields
        else:
            require(not api.call(path.replace('/data/', '/metadata/'), token=root, expected=(200, 404)),
                    'KAFKA_CLIENT_DELETED_HISTORY_PRESERVED')
            missing.append(service)
    require(not set(missing).intersection(existing_services), 'KAFKA_CLIENT_ORPHANED_DELIVERY_PRESERVED')
    for service in missing:
        values[service] = issue(service)
        contract.validate(service, values[service], ca, issuer)
    for field in ('tls.key', 'tls.crt'):
        require(len({values[s][contract.FIELDS[field]] for s in contract.SERVICES}) == len(contract.SERVICES),
                'KAFKA_CLIENT_DISTINCT_IDENTITY_REQUIRED')
    return values, missing


def verify_auth(api, root, token_request, audiences):
    login = 'auth/' + contract.auth.MOUNT + '/login'
    for service in contract.SERVICES:
        jwt = token_request(contract.NAMESPACE, 'eso-' + service,
                            [*audiences, contract.auth.AUDIENCE])
        session = api.call(login, 'POST', {'role': 'eso-' + service, 'jwt': jwt})['auth']
        token = session['client_token']
        try:
            require(session['policies'] == ['hooshix-read-' + service]
                and type(session['lease_duration']) is int and 0 < session['lease_duration'] <= 300,
                'KAFKA_CLIENT_EXISTING_TOKEN_SCOPE_REQUIRED')
            path = 'hooshix/data/' + contract.entry(service)['key']
            api.call(path, token=token)
            for other in contract.SERVICES:
                if other != service:
                    api.call('hooshix/data/' + contract.entry(other)['key'], token=token, expected=403)
            api.call('hooshix/data/production/kafka/node-tls', token=token, expected=403)
            api.call(path, 'POST', {'data': {'denied': True}}, token, 403)
            api.call('sys/mounts', token=token, expected=403)
        finally:
            api.call('auth/token/revoke-self', 'POST', {}, token, 204)
        api.call(path, token=token, expected=403)


def execute(plan, *, kube, api, root, ca, audiences, token_request, issuer, issue):
    require(plan.get('schema_version') == 1 and plan.get('installation_id') == 'hooshix-production'
        and plan.get('kafka_client_tls') is True and plan.get('database') == 'Not run'
        and plan.get('public_access') == plan.get('application_ingress') == 'CLOSED'
        and hashlib.sha256(ca.encode()).hexdigest() == plan['public_ca_sha256'],
        'KAFKA_CLIENT_REVIEWED_PLAN_REQUIRED')
    plan = {**plan, 'audiences': audiences}
    before = snapshot(kube, plan)
    objects = [contract.external(service) for service in contract.SERVICES]
    existing = []
    for service, obj in zip(contract.SERVICES, objects, strict=True):
        old = runtime.get(kube, 'externalsecret', obj['metadata']['name'], contract.NAMESPACE)
        require(not old or old['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER
            and not old['metadata'].get('deletionTimestamp') and desired_fields_match(old, obj),
            'KAFKA_CLIENT_FOREIGN_EXTERNAL_PRESERVED')
        secret = runtime.get(kube, 'secret', obj['metadata']['name'], contract.NAMESPACE)
        require(not secret or old and secret['metadata'].get('ownerReferences') == contract.owner(old)
            and secret['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER,
            'KAFKA_CLIENT_FOREIGN_TARGET_PRESERVED')
        role = api.call('auth/' + contract.auth.MOUNT + '/role/eso-' + service, token=root)['data']
        require(all(role.get(k) == v for k, v in contract.auth.role(service).items()),
                'KAFKA_CLIENT_EXISTING_AUTH_ROLE_REQUIRED')
        policy = api.call('sys/policies/acl/hooshix-read-' + service, token=root)['data']['policy']
        require(policy.strip() == contract.auth.policy(service).strip(),
                'KAFKA_CLIENT_EXISTING_AUTH_POLICY_REQUIRED')
        existing.append(old)
    values, missing = credentials(api, root, ca, issuer, issue,
        [service for service, old in zip(contract.SERVICES, existing, strict=True) if old])
    for service in missing:
        api.call('hooshix/data/' + contract.entry(service)['key'], 'POST',
                 {'options': {'cas': 0}, 'data': {**values[service], **MARKERS}}, root)
    for service, obj, old in zip(contract.SERVICES, objects, existing, strict=True):
        require(api.call('hooshix/data/' + contract.entry(service)['key'], token=root)['data']['data']
                == {**values[service], **MARKERS}, 'KAFKA_CLIENT_VAULT_READBACK_REQUIRED')
        if old is None:
            kube('create', '-f', '-', body=obj)
        name = obj['metadata']['name']
        kube('-n', contract.NAMESPACE, 'wait', '--for=condition=Ready',
             'externalsecret/' + name, '--timeout=90s', timeout=105)
        external = runtime.get(kube, 'externalsecret', name, contract.NAMESPACE)
        secret = runtime.get(kube, 'secret', name, contract.NAMESPACE)
        require(external and ready(external) and desired_fields_match(external, obj)
            and secret and secret.get('type') == 'Opaque'
            and secret['metadata'].get('ownerReferences') == contract.owner(external)
            and secret['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER
            and {k: base64.b64decode(v, validate=True).decode() for k, v in secret['data'].items()}
                == values[service], 'KAFKA_CLIENT_ESO_READBACK_REQUIRED')
    verify_auth(api, root, token_request, audiences)
    require(before == snapshot(kube, plan), 'KAFKA_CLIENT_EXISTING_STATE_CHANGED')
    return {'schema_version': 1, 'kafka_client_tls_delivery': 'Passed', 'credential_count': 5,
        'namespace': contract.NAMESPACE, 'tls_lifetime_days': 30, 'client_only_identity': 'Passed',
        'scoped_authentication': 'Passed', 'existing_state': 'Preserved',
        'application_ingress': 'CLOSED', 'public_access': 'CLOSED',
        'topics_acl': 'Not run', 'automatic_renewal': 'Not run', 'root_revocation': 'Not run',
        'production_readiness': 'Not verified'}
