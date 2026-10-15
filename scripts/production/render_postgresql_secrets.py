"""Public single-controller data-namespace ESO plan; database admission remains closed."""
from __future__ import annotations

import copy

import postgresql_secret_delivery as contract
import render_eso_candidate as eso
import render_eso_commissioning as existing
import render_platform_admission as admission

WEBHOOK_SELECTOR = {'matchExpressions': [{'key': 'kubernetes.io/metadata.name', 'operator': 'In',
                                         'values': ['platform-apps', contract.NAMESPACE]}]}


def renamed(value):
    if isinstance(value, dict): return {k: renamed(v) for k, v in value.items()}
    if isinstance(value, list): return [renamed(v) for v in value]
    if isinstance(value, str):
        if value == 'platform-apps': return contract.NAMESPACE
        return value.replace(eso.ACCOUNT, contract.ACCOUNT).replace(
            '--namespace=platform-apps', '--namespace=platform-data').replace('/ns/platform-apps/', '/ns/platform-data/')
    return value


def render(chart, helm, addresses):
    upstream = eso.render(chart, helm, published=True)['items']
    objects = [renamed(o) for o in upstream if o['kind'] in ('Deployment', 'ServiceAccount', 'Role', 'RoleBinding')
               and o['metadata']['name'] == (eso.ACCOUNT + '-controller' if o['kind'] in ('Role', 'RoleBinding') else eso.ACCOUNT)]
    if len(objects) != 4: raise ValueError('PG_SECRET_EXACT_CONTROLLER_RESOURCES_REQUIRED')
    deployment = next(o for o in objects if o['kind'] == 'Deployment')
    pod = deployment['spec']['template']['spec']
    pod.pop('imagePullSecrets', None)  # This existing signed infrastructure digest is public.
    pod.setdefault('securityContext', {})['seccompProfile'] = {'type': 'RuntimeDefault'}
    if pod.get('volumes') or pod['containers'][0].get('volumeMounts'):
        raise ValueError('PG_SECRET_UNEXPECTED_CONTROLLER_MOUNT')
    pod['automountServiceAccountToken'] = False
    pod['volumes'] = [{'name': 'hooshix-api-token', 'projected': {'defaultMode': 292, 'sources': [
        {'serviceAccountToken': {'expirationSeconds': 600, 'path': 'token'}},
        {'configMap': {'name': 'kube-root-ca.crt', 'items': [{'key': 'ca.crt', 'path': 'ca.crt'}]}},
        {'downwardAPI': {'items': [{'path': 'namespace', 'fieldRef': {'apiVersion': 'v1',
            'fieldPath': 'metadata.namespace'}}]}}]}}]
    pod['containers'][0]['volumeMounts'] = [{'name': 'hooshix-api-token',
        'mountPath': '/var/run/secrets/kubernetes.io/serviceaccount', 'readOnly': True}]
    role = next(o for o in objects if o['kind'] == 'Role')
    if any('*' in rule['verbs'] or '*' in rule['resources']
           or 'serviceaccounts/token' in rule['resources'] for rule in role['rules']):
        raise ValueError('PG_SECRET_BROAD_CONTROLLER_RBAC_REJECTED')
    objects.extend(contract.foundation())
    role = contract.resource('Role', contract.ACCOUNT + '-tokenrequest', api='rbac.authorization.k8s.io/v1',
        rules=[{'apiGroups': [''], 'resources': ['serviceaccounts/token'], 'verbs': ['create'],
                'resourceNames': [e['account'] for e in contract.ENTRIES.values()]}])
    objects += [role, contract.resource('RoleBinding', role['metadata']['name'], api='rbac.authorization.k8s.io/v1',
        roleRef={'apiGroup': 'rbac.authorization.k8s.io', 'kind': 'Role', 'name': role['metadata']['name']},
        subjects=[{'kind': 'ServiceAccount', 'name': contract.ACCOUNT, 'namespace': contract.NAMESPACE}])]
    objects += [contract.resource('PeerAuthentication', 'hooshix-postgresql-strict',
        api='security.istio.io/v1', spec={'mtls': {'mode': 'STRICT'}}),
        contract.resource('AuthorizationPolicy', 'hooshix-postgresql-default-deny', api='security.istio.io/v1', spec={})]
    boundary = admission.hardening(contract.NAMESPACE, {contract.ACCOUNT: pod})
    boundary['metadata']['name'] = 'hooshix-platform-data-closed'
    boundary['spec']['matchConstraints']['namespaceSelector'] = admission.match_constraints(
        contract.NAMESPACE)['namespaceSelector']
    boundary['spec']['validations'].append(admission.validation('PostgreSQL secret controller contract rejected',
        'object.metadata.namespace != "platform-data" || ('
        'has(object.metadata.labels) && object.metadata.labels["istio.io/dataplane-mode"] == "ambient"'
        ' && has(object.spec.automountServiceAccountToken) && !object.spec.automountServiceAccountToken'
        ' && has(object.spec.volumes) && (' + admission.scalar_tree('object.spec.volumes', pod['volumes']) + ')'
        + ''.join(' && has(object.spec.containers[0].' + field + ') && ('
            + admission.scalar_tree('object.spec.containers[0].' + field, pod['containers'][0][field]) + ')'
            if pod['containers'][0].get(field) else ' && (!has(object.spec.containers[0].' + field
                + ') || size(object.spec.containers[0].' + field + ') == 0)'
            for field in ('args', 'command', 'env', 'envFrom', 'volumeMounts')) + ')'))
    source = existing.source_policy()
    source['metadata']['name'] = 'hooshix-postgresql-eso-source'
    source['spec']['matchConstraints']['namespaceSelector'] = {
        'matchLabels': {'kubernetes.io/metadata.name': contract.NAMESPACE}}
    source['spec'].pop('credentials', None)
    networks = [renamed(o) for o in eso.network(addresses)
                if o['metadata']['name'] != 'hooshix-eso-webhook-api']
    authorization = renamed(eso.authorization())
    for value in [*networks, authorization]:
        value['metadata']['name'] = 'hooshix-pg-' + value['metadata']['name']
    sources = admission.split_image_policy(source)
    for value in [*objects, *networks, authorization, boundary, *sources]:
        value['metadata'].setdefault('labels', {}).update(contract.LABELS)
    # Existing webhook/cert-controller and their TLS keys stay in platform-apps.
    hooks = [o for o in upstream if o['kind'] == 'ValidatingWebhookConfiguration']
    if {o['metadata']['name'] for o in hooks} != {'secretstore-validate', 'externalsecret-validate'}:
        raise ValueError('PG_SECRET_EXISTING_VALIDATION_WEBHOOK_REQUIRED')
    return {'schema_version': 1, 'installation_id': 'hooshix-production',
        'image': existing.published_pin()['image'], 'objects': objects, 'network': networks,
        'authorization': authorization, 'boundary': boundary,
        'source_policies': sources,
        'previous_boundary': admission.policy('ValidatingPolicy', 'hooshix-platform-data-closed', contract.NAMESPACE,
            [admission.validation('data plane not commissioned', 'false')]),
        'webhook_names': [o['metadata']['name'] for o in hooks], 'webhook_selector': WEBHOOK_SELECTOR,
        'webhook_previous_selector': {'matchLabels': {'kubernetes.io/metadata.name': 'platform-apps'}},
        'public_ca_sha256': existing.custody.ROOT_SHA256, 'database': 'Not run'}
