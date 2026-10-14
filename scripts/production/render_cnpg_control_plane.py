"""Public signed CNPG controller plan; no database, data volume or credential."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path

import yaml

import render_cert_manager_candidate as certificates
import render_platform_admission as admission
from build_postgresql_candidates import recipe
from publish_postgresql_candidates import source_predicate
from render_mesh_security_upgrade import provenance_expression
from verify_published_postgresql import candidate, pins

NAMESPACE = 'cnpg-system'
DATA_NAMESPACE = 'platform-data'
ACCOUNT = 'cnpg-manager'
DEPLOYMENT = 'cnpg-controller-manager'
MANAGER = 'hooshix-cnpg-control-plane'
CALLBACKS = ('cnpg-mutating-webhook-configuration', 'cnpg-validating-webhook-configuration')
BOUND = 16 * 1024 * 1024


def resource(kind, name, namespace=NAMESPACE, **fields):
    obj = certificates.resource(kind, name, namespaced=False, **fields)
    obj['metadata']['labels']['app.kubernetes.io/managed-by'] = MANAGER
    if namespace is not None:
        obj['metadata']['namespace'] = namespace
    return obj


def permissions(original):
    # Keep the upstream controller contract, but bind mutable data only in its watched namespace.
    scoped = []
    for rule in original['rules']:
        if 'nodes' in rule['resources'] or rule['apiGroups'] == ['admissionregistration.k8s.io']:
            continue
        rule = copy.deepcopy(rule)
        rule['resources'] = [r for r in rule['resources'] if r != 'clusterimagecatalogs']
        if rule['resources']:
            scoped.append(rule)
    own = [
        {'apiGroups': [''], 'resources': ['secrets'], 'verbs': ['create']},
        {'apiGroups': [''], 'resources': ['secrets'], 'verbs': ['get', 'update', 'patch'],
         'resourceNames': ['cnpg-ca-secret', 'cnpg-webhook-cert', 'cnpg-controller-manager-config']},
        {'apiGroups': [''], 'resources': ['configmaps'], 'verbs': ['get', 'list', 'watch'],
         'resourceNames': ['cnpg-controller-manager-config', 'cnpg-default-monitoring']},
        {'apiGroups': ['apps'], 'resources': ['deployments'], 'verbs': ['get', 'list', 'watch']},
        {'apiGroups': ['discovery.k8s.io'], 'resources': ['endpointslices'], 'verbs': ['get', 'list', 'watch']},
        {'apiGroups': [''], 'resources': ['services'], 'verbs': ['get', 'list', 'watch']},
    ]
    cluster = [
        {'apiGroups': [''], 'resources': ['nodes'], 'verbs': ['get', 'list', 'watch']},
        {'apiGroups': ['postgresql.cnpg.io'], 'resources': ['clusterimagecatalogs'], 'verbs': ['get', 'list', 'watch']},
        {'apiGroups': ['admissionregistration.k8s.io'],
         'resources': ['mutatingwebhookconfigurations', 'validatingwebhookconfigurations'],
         'verbs': ['get', 'patch'], 'resourceNames': list(CALLBACKS)},
    ]
    roles = [resource('Role', ACCOUNT, DATA_NAMESPACE, rules=scoped),
             resource('Role', ACCOUNT, rules=own), resource('ClusterRole', ACCOUNT, None, rules=cluster)]
    return [*roles, *[resource('ClusterRoleBinding' if r['kind'] == 'ClusterRole' else 'RoleBinding',
        ACCOUNT, r['metadata'].get('namespace'),
        subjects=[{'kind': 'ServiceAccount', 'name': ACCOUNT, 'namespace': NAMESPACE}],
        roleRef={'apiGroup': 'rbac.authorization.k8s.io', 'kind': r['kind'], 'name': ACCOUNT}) for r in roles]]


def network(addresses):
    network = copy.deepcopy(certificates.network(addresses))
    labels = {'app.kubernetes.io/name': 'cloudnative-pg'}
    for obj, name in zip(network, ('hooshix-cnpg-control-plane', 'hooshix-cnpg-webhook-api', 'hooshix-cnpg-mesh-ca')):
        obj['metadata']['name'] = name
        obj['metadata']['labels'] = {'app.kubernetes.io/managed-by': MANAGER}
        obj['spec']['podSelector'] = {'matchLabels': labels}
    network[1]['spec']['ingress'][0]['ports'] = [{'protocol': 'TCP', 'port': 9443}]
    # No database exists yet; data-plane and monitoring edges belong to their later commissioning.
    network.append({'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy', 'metadata': {
        'name': 'hooshix-data-default-deny', 'namespace': DATA_NAMESPACE,
        'labels': {'app.kubernetes.io/managed-by': MANAGER}}, 'spec': {'podSelector': {},
        'policyTypes': ['Ingress', 'Egress'], 'ingress': [], 'egress': []}})
    return network


def policies(pod, previous):
    combined = {**previous['pods'], ACCOUNT: pod}
    boundary = admission.hardening(NAMESPACE, combined)
    boundary['spec']['matchConstraints']['namespaceSelector'] = copy.deepcopy(certificates.BOUNDARY_SELECTOR)
    # Preserve the three certificate-component argument/token/enrollment constraints.
    boundary['spec']['validations'].extend(copy.deepcopy(previous['policies'][0]['spec']['validations'][-3:]))
    expression = ('object.metadata.namespace != "cnpg-system" || object.spec.serviceAccountName != "cnpg-manager" || ('
        'has(object.metadata.labels) && object.metadata.labels["istio.io/dataplane-mode"] == "ambient"'
        ' && !has(object.spec.containers[0].envFrom) && has(object.spec.automountServiceAccountToken)'
        ' && !object.spec.automountServiceAccountToken && '
        + ' && '.join('has(object.spec.containers[0].' + field + ') && ('
            + admission.scalar_tree('object.spec.containers[0].' + field, pod['containers'][0][field]) + ')'
            for field in ('args', 'command', 'env', 'volumeMounts'))
        + ' && has(object.spec.volumes) && (' + admission.scalar_tree('object.spec.volumes', pod['volumes']) + '))')
    boundary['spec']['validations'].append(admission.validation('CNPG scope/enrollment rejected', expression))
    selected = candidate()['publication']
    p = admission.image_policy(NAMESPACE, [selected['images']['operator']], selected['repository_revision'])
    p['metadata']['name'] = 'hooshix-cnpg-controller-source'
    p['spec'].pop('credentials')
    expected = source_predicate('operator', recipe(), selected['repository_revision'], selected['invocation_id'])
    p['spec']['validations'][2] = admission.validation('CNPG source identity rejected', provenance_expression(expected))
    closed = admission.policy('ValidatingPolicy', 'hooshix-platform-data-closed', DATA_NAMESPACE,
        [admission.validation('data plane not commissioned', 'false')])
    return boundary, [*admission.split_image_policy(p), closed]


def render(manifest, cert_manifest, addresses):
    if (manifest.is_symlink() or not manifest.is_file() or manifest.stat().st_size > BOUND
            or hashlib.sha256(manifest.read_bytes()).hexdigest() != pins()['components']['operator']['manifest']['sha256']):
        raise ValueError('exact bounded official CNPG manifest required')
    selected = candidate()['publication']
    if selected['registry_visibility']['operator'] != 'public':
        raise ValueError('reviewed public infrastructure operator required for credential-free plan')
    previous = certificates.render(cert_manifest, addresses)
    upstream = [d for d in yaml.safe_load_all(manifest.read_bytes()) if d]
    crds = [d for d in upstream if d['kind'] == 'CustomResourceDefinition']
    if len(crds) != 11 or any(not d['metadata']['name'].endswith('.postgresql.cnpg.io') for d in crds):
        raise ValueError('exact CNPG API set required')
    deployment = next(d for d in upstream if d['kind'] == 'Deployment' and d['metadata']['name'] == DEPLOYMENT)
    deployment['spec']['replicas'] = 1
    template = deployment['spec']['template']
    template['metadata'].setdefault('labels', {})['istio.io/dataplane-mode'] = 'ambient'
    pod = template['spec']
    pod.update(automountServiceAccountToken=False, terminationGracePeriodSeconds=30)
    pod['securityContext'].update(fsGroup=65532, fsGroupChangePolicy='OnRootMismatch')
    if len(pod['containers']) != 1 or pod['serviceAccountName'] != ACCOUNT:
        raise ValueError('exact official CNPG manager shape required')
    c = pod['containers'][0]
    if (c['image'] != 'ghcr.io/cloudnative-pg/cloudnative-pg:1.30.1' or c.get('command') != ['/manager']
            or pod.get('initContainers') or any(pod.get(k) for k in ('hostNetwork', 'hostPID', 'hostIPC'))
            or any('hostPath' in v for v in pod.get('volumes', []))):
        raise ValueError('unexpected upstream CNPG process or privilege')
    c.update(image=selected['images']['operator'], imagePullPolicy='IfNotPresent',
        args=['controller', '--max-concurrent-reconciles=2', '--webhook-port=9443', '--log-level=info'],
        resources={'requests': {'cpu': '100m', 'memory': '128Mi', 'ephemeral-storage': '16Mi'},
                   'limits': {'cpu': '500m', 'memory': '512Mi', 'ephemeral-storage': '128Mi'}})
    c['securityContext'].update(runAsNonRoot=True, runAsUser=65532, runAsGroup=65532)
    c['env'] = [{'name': 'OPERATOR_IMAGE_NAME', 'value': selected['images']['operator']},
        {'name': 'OPERATOR_NAMESPACE', 'valueFrom': {'fieldRef': {'apiVersion': 'v1', 'fieldPath': 'metadata.namespace'}}},
        {'name': 'WATCH_NAMESPACE', 'value': DATA_NAMESPACE}]
    pod['volumes'].append(copy.deepcopy(certificates.TOKEN))
    c['volumeMounts'].append(copy.deepcopy(certificates.MOUNT))
    c['startupProbe'].update(periodSeconds=5, failureThreshold=36)
    data = resource('Namespace', DATA_NAMESPACE, None)
    data['metadata']['labels'].update({'istio.io/dataplane-mode': 'ambient',
        'pod-security.kubernetes.io/enforce': 'restricted', 'pod-security.kubernetes.io/enforce-version': 'v1.35'})
    objects = [data, resource('ServiceAccount', ACCOUNT),
        *permissions(next(d for d in upstream if d['kind'] == 'ClusterRole' and d['metadata']['name'] == ACCOUNT)),
        *[d for d in upstream if d['kind'] == 'Service' and d['metadata']['name'] == 'cnpg-webhook-service'], deployment]
    objects.append(resource('ImageCatalog', 'hooshix-postgresql-18-candidate', DATA_NAMESPACE,
        apiVersion='postgresql.cnpg.io/v1', spec={'images': [{'major': 18, 'image': selected['images']['postgresql']}]}))
    callbacks = [d for d in upstream if d['kind'] in ('MutatingWebhookConfiguration', 'ValidatingWebhookConfiguration')]
    if {d['metadata']['name'] for d in callbacks} != set(CALLBACKS):
        raise ValueError('exact two CNPG callbacks required')
    for d in callbacks:
        for hook in d['webhooks']:
            hook.update(failurePolicy='Fail', timeoutSeconds=5,
                        namespaceSelector={'matchLabels': {'kubernetes.io/metadata.name': DATA_NAMESPACE}})
    objects.extend(callbacks)
    for d in [*crds, *objects]:
        d['metadata'].setdefault('labels', {})['app.kubernetes.io/managed-by'] = MANAGER
    strict = {'apiVersion': 'security.istio.io/v1', 'kind': 'PeerAuthentication', 'metadata': {
        'name': 'hooshix-cnpg-webhook-api', 'namespace': NAMESPACE,
        'labels': {'app.kubernetes.io/managed-by': MANAGER}}, 'spec': {
        'selector': {'matchLabels': {'app.kubernetes.io/name': 'cloudnative-pg'}},
        'mtls': {'mode': 'STRICT'}, 'portLevelMtls': {'9443': {'mode': 'PERMISSIVE'}}}}
    authorization = {'apiVersion': 'security.istio.io/v1', 'kind': 'AuthorizationPolicy', 'metadata': {
        'name': 'hooshix-cnpg-api-only', 'namespace': NAMESPACE,
        'labels': {'app.kubernetes.io/managed-by': MANAGER}}, 'spec': {
        'selector': {'matchLabels': {'app.kubernetes.io/name': 'cloudnative-pg'}}, 'action': 'ALLOW',
        'rules': [{'from': [{'source': {'ipBlocks': [a + '/32' for a in addresses]}}],
                   'to': [{'operation': {'ports': ['9443']}}]}]}}
    boundary, source = policies(pod, previous)
    for obj in source:
        obj['metadata'].setdefault('labels', {})['app.kubernetes.io/managed-by'] = MANAGER
    return {'schema_version': 1, 'installation_id': 'hooshix-production', 'profile': 'production-single-server',
        'crds': crds, 'objects': objects, 'network': network(addresses), 'peer_authentication': strict,
        'authorization': authorization,
        'previous_boundary': previous['policies'][0], 'boundary': boundary, 'source_policies': source,
        'images': {'operator': selected['images']['operator']}, 'deployment': 'Not verified',
        'database': 'Not run', 'production_readiness': 'Not verified'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--manifest', type=Path, required=True)
    p.add_argument('--cert-manager-manifest', type=Path, required=True)
    p.add_argument('--api-address', action='append', required=True)
    a = p.parse_args()
    print(json.dumps(render(a.manifest, a.cert_manager_manifest, a.api_address), sort_keys=True))


if __name__ == '__main__':
    main()
