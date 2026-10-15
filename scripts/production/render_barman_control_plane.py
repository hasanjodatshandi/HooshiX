"""Pinned internal Barman plugin only; no database, ObjectStore or provider access."""
import argparse
import copy
import hashlib
import json
from pathlib import Path

import yaml

import render_cnpg_control_plane as cnpg
import render_platform_admission as admission
from build_postgresql_candidates import recipe
from publish_postgresql_candidates import source_predicate
from render_mesh_security_upgrade import provenance_expression
from verify_published_postgresql import candidate, pins

NAMESPACE = cnpg.NAMESPACE
DATA_NAMESPACE = cnpg.DATA_NAMESPACE
ACCOUNT = 'plugin-barman-cloud'
DEPLOYMENT = 'barman-cloud'
MANAGER = 'hooshix-barman-control-plane'
TLS_NAMES = ('barman-cloud-client-tls', 'barman-cloud-server-tls')
LABELS = {'app': 'barman-cloud'}


def resource(kind, name, namespace=NAMESPACE, **fields):
    value = cnpg.resource(kind, name, namespace, **fields)
    value['metadata']['labels']['app.kubernetes.io/managed-by'] = MANAGER
    return value


def permissions():
    # Owner-approved global LIST/WATCH only. No Secret read or cluster-wide mutation.
    rules = [{'apiGroups': ['barmancloud.cnpg.io'], 'resources': ['objectstores'], 'verbs': ['list', 'watch']},
             {'apiGroups': ['rbac.authorization.k8s.io'], 'resources': ['roles', 'rolebindings'], 'verbs': ['list', 'watch']}]
    roles = [resource('ClusterRole', ACCOUNT, None, rules=rules),
        resource('Role', ACCOUNT, DATA_NAMESPACE, rules=[
            {'apiGroups': ['rbac.authorization.k8s.io'], 'resources': ['roles', 'rolebindings'], 'verbs': ['create']},
            {'apiGroups': ['rbac.authorization.k8s.io'], 'resources': ['roles', 'rolebindings'],
             'verbs': ['get', 'patch', 'update'], 'resourceNames': ['postgresql-barman-cloud']}])]
    bindings = [resource('ClusterRoleBinding' if r['kind'] == 'ClusterRole' else 'RoleBinding',
        ACCOUNT, r['metadata'].get('namespace'),
        subjects=[{'kind': 'ServiceAccount', 'name': ACCOUNT, 'namespace': NAMESPACE}],
        roleRef={'apiGroup': 'rbac.authorization.k8s.io', 'kind': r['kind'], 'name': ACCOUNT}) for r in roles]
    # Only CNPG, not Barman, reads these exact generated TLS keys for plugin mTLS.
    tls_role = resource('Role', 'hooshix-cnpg-barman-tls', rules=[{'apiGroups': [''], 'resources': ['secrets'],
        'verbs': ['get'], 'resourceNames': list(TLS_NAMES)}])
    binding = resource('RoleBinding', tls_role['metadata']['name'],
        subjects=[{'kind': 'ServiceAccount', 'name': cnpg.ACCOUNT, 'namespace': NAMESPACE}],
        roleRef={'apiGroup': 'rbac.authorization.k8s.io', 'kind': 'Role', 'name': tls_role['metadata']['name']})
    return [*roles, *bindings, tls_role, binding]


def network(addresses):
    previous = cnpg.network(addresses)
    result = [copy.deepcopy(previous[0]), copy.deepcopy(previous[2])]
    for value, name in zip(result, ('hooshix-barman-control-plane', 'hooshix-barman-mesh-ca')):
        value['metadata'].update(name=name, labels={'app.kubernetes.io/managed-by': MANAGER})
        value['spec']['podSelector'] = {'matchLabels': LABELS}
    # The existing API/DNS/istiod edges stay bounded; no object-storage/internet edge is opened.
    result[0]['spec']['ingress'] = [{'from': [{'ipBlock': {'cidr': a + '/32'}} for a in addresses],
        'ports': [{'protocol': 'TCP', 'port': 8081}]}, {'from': [{'podSelector': {'matchLabels': {
            'app.kubernetes.io/name': 'cloudnative-pg'}}}],
        'ports': [{'protocol': 'TCP', 'port': p} for p in (9090, 15008)]}]
    result.append(resource('NetworkPolicy', 'hooshix-cnpg-barman-client', apiVersion='networking.k8s.io/v1',
        spec={'podSelector': {'matchLabels': {'app.kubernetes.io/name': 'cloudnative-pg'}}, 'policyTypes': ['Egress'],
              'egress': [{'to': [{'podSelector': {'matchLabels': LABELS}}],
                         'ports': [{'protocol': 'TCP', 'port': p} for p in (9090, 15008)]}]}))
    return result


def policies(pod, previous):
    certificates = cnpg.certificates
    # Recover the four existing exact Pod contracts from their reviewed renderers, not live workloads.
    prior_pods = previous['existing_pods']
    boundary = admission.hardening(NAMESPACE, {**prior_pods, ACCOUNT: pod})
    boundary['spec']['matchConstraints']['namespaceSelector'] = copy.deepcopy(certificates.BOUNDARY_SELECTOR)
    boundary['spec']['validations'].extend(copy.deepcopy(previous['boundary']['spec']['validations'][-4:]))
    check = ('object.metadata.namespace != "cnpg-system" || object.spec.serviceAccountName != '
        + admission.literal(ACCOUNT) + ' || (has(object.metadata.labels) && '
        'object.metadata.labels["istio.io/dataplane-mode"] == "ambient" && '
        'has(object.spec.automountServiceAccountToken) && !object.spec.automountServiceAccountToken && '
        '!has(object.spec.containers[0].envFrom) && '
        + ' && '.join('has(object.spec.containers[0].' + f + ') && ('
            + admission.scalar_tree('object.spec.containers[0].' + f, pod['containers'][0][f]) + ')'
            for f in ('command', 'args', 'env', 'volumeMounts'))
        + ' && has(object.spec.volumes) && (' + admission.scalar_tree('object.spec.volumes', pod['volumes']) + '))')
    boundary['spec']['validations'].append(admission.validation('Barman identity/enrollment rejected', check))
    selected = candidate()['publication']
    p = admission.image_policy(NAMESPACE, [selected['images']['barman-plugin']], selected['repository_revision'])
    p['metadata']['name'] = 'hooshix-barman-plugin-source'
    p['spec'].pop('credentials')
    p['spec']['validations'][2] = admission.validation('Barman source identity rejected', provenance_expression(
        source_predicate('barman-plugin', recipe(), selected['repository_revision'], selected['invocation_id'])))
    rbac = admission.policy('ValidatingPolicy', 'hooshix-barman-rbac-boundary', DATA_NAMESPACE,
        [admission.validation('Barman may mutate only its fixed PostgreSQL delegation',
            'request.userInfo.username != "system:serviceaccount:cnpg-system:plugin-barman-cloud" || '
            'object.metadata.name == "postgresql-barman-cloud"')])
    rbac['spec']['matchConstraints']['resourceRules'] = [{'apiGroups': ['rbac.authorization.k8s.io'],
        'apiVersions': ['v1'], 'operations': ['CREATE', 'UPDATE'], 'resources': ['roles', 'rolebindings'],
        'scope': 'Namespaced'}]
    rbac['spec']['evaluation']['background']['enabled'] = False  # Request identity exists only at admission.
    result = [*admission.split_image_policy(p), rbac]
    for value in result:
        value['metadata'].setdefault('labels', {})['app.kubernetes.io/managed-by'] = MANAGER
    return boundary, result


def render(manifest, cnpg_manifest, cert_manifest, addresses):
    source = pins()['components']['barman-plugin']['manifest']['sha256']
    if (manifest.is_symlink() or not manifest.is_file() or manifest.stat().st_size > cnpg.BOUND
            or hashlib.sha256(manifest.read_bytes()).hexdigest() != source):
        raise ValueError('bounded exact official Barman manifest required')
    selected = candidate()['publication']
    if selected['registry_visibility']['barman-plugin'] != 'public':
        raise ValueError('approved public infrastructure plugin required')
    previous = cnpg.render(cnpg_manifest, cert_manifest, addresses)
    certs = cnpg.certificates.render(cert_manifest, addresses)
    previous['existing_pods'] = {**certs['pods'], cnpg.ACCOUNT: next(o for o in previous['objects']
        if o['kind'] == 'Deployment')['spec']['template']['spec']}
    upstream = [o for o in yaml.safe_load_all(manifest.read_bytes()) if o]
    crds = [o for o in upstream if o['kind'] == 'CustomResourceDefinition']
    if len(crds) != 1 or crds[0]['metadata']['name'] != 'objectstores.barmancloud.cnpg.io':
        raise ValueError('exact Barman API required')
    deployment = copy.deepcopy(next(o for o in upstream if o['kind'] == 'Deployment'))
    if deployment['metadata']['name'] != DEPLOYMENT:
        raise ValueError('exact Barman process required')
    template = deployment['spec']['template']
    template['metadata']['labels']['istio.io/dataplane-mode'] = 'ambient'
    pod = template['spec']
    pod.update(automountServiceAccountToken=False, terminationGracePeriodSeconds=30,
        securityContext={'runAsNonRoot': True, 'runAsUser': 65532, 'runAsGroup': 65532,
                         'fsGroup': 65532, 'seccompProfile': {'type': 'RuntimeDefault'}})
    if pod['serviceAccountName'] != ACCOUNT or len(pod['containers']) != 1 or pod.get('initContainers'):
        raise ValueError('exact unprivileged Barman workload required')
    c = pod['containers'][0]
    if c['image'] != 'ghcr.io/cloudnative-pg/plugin-barman-cloud:v0.15.1':
        raise ValueError('reviewed Barman version required')
    c.update(command=['/manager'], image=selected['images']['barman-plugin'], imagePullPolicy='IfNotPresent',
        args=['operator', '--server-cert=/server/tls.crt', '--server-key=/server/tls.key',
              '--client-cert=/client/tls.crt', '--server-address=:9090', '--leader-elect=false',
              '--metrics-bind-address=0', '--health-probe-bind-address=:8081', '--log-level=info'],
        env=[{'name': 'SIDECAR_IMAGE', 'value': selected['images']['barman-sidecar']}],
        resources=copy.deepcopy(cnpg.certificates.RESOURCES))
    c['securityContext'].update(runAsNonRoot=True, runAsUser=65532, runAsGroup=65532)
    for field, path, period, failures in (('startupProbe', '/readyz', 5, 36),
            ('readinessProbe', '/readyz', 5, 3), ('livenessProbe', '/healthz', 15, 3)):
        c[field] = {'httpGet': {'path': path, 'port': 8081}, 'periodSeconds': period,
                    'timeoutSeconds': 3, 'failureThreshold': failures}
    c['volumeMounts'].append(copy.deepcopy(cnpg.certificates.MOUNT))
    for v in c['volumeMounts']: v['readOnly'] = True
    for v in pod['volumes']: v['secret']['defaultMode'] = 0o440
    pod['volumes'].append(copy.deepcopy(cnpg.certificates.TOKEN))
    certificates = [copy.deepcopy(o) for o in upstream if o['kind'] == 'Certificate']
    if {o['metadata']['name'] for o in certificates} != {'barman-cloud-client', 'barman-cloud-server'}:
        raise ValueError('exact two internal TLS certificates required')
    for o in certificates:
        o['spec']['issuerRef']['name'] = cnpg.certificates.ISSUERS[0]
        o['spec']['secretTemplate'] = {'labels': {'app.kubernetes.io/managed-by': MANAGER}}
        if o['metadata']['name'] == 'barman-cloud-server':
            o['spec']['dnsNames'] = [DEPLOYMENT + suffix for suffix in ('', '.' + NAMESPACE,
                '.' + NAMESPACE + '.svc', '.' + NAMESPACE + '.svc.cluster.local')]
    service = copy.deepcopy(next(o for o in upstream if o['kind'] == 'Service'))
    service['spec']['type'] = 'ClusterIP'
    objects = [resource('ServiceAccount', ACCOUNT), *permissions(),
        resource('Issuer', cnpg.certificates.ISSUERS[0], apiVersion='cert-manager.io/v1', spec={'selfSigned': {}}),
        *certificates, service, deployment]
    for o in [*crds, *objects]:
        o['metadata'].setdefault('labels', {})['app.kubernetes.io/managed-by'] = MANAGER
    boundary, source_policies = policies(pod, previous)
    auth = resource('AuthorizationPolicy', 'hooshix-barman-cnpg-only', apiVersion='security.istio.io/v1',
        spec={'selector': {'matchLabels': LABELS}, 'action': 'ALLOW', 'rules': [{'from': [{'source': {
            'principals': ['prod.sajtech.internal/ns/' + NAMESPACE + '/sa/' + cnpg.ACCOUNT]}}],
            'to': [{'operation': {'ports': ['9090']}}]}]})
    return {'schema_version': 1, 'installation_id': 'hooshix-production', 'profile': 'production-single-server',
        'crds': crds, 'objects': objects, 'network': network(addresses), 'authorization': auth,
        'existing_strict': certs['strict'],
        'previous_boundary': previous['boundary'], 'boundary': boundary, 'source_policies': source_policies,
        'images': {k: selected['images'][k] for k in ('barman-plugin', 'barman-sidecar')},
        'metadata_cache_trust': 'owner-approved-global-list-watch-no-secret-api',
        'database': 'Not run', 'provider_backup': 'Not run', 'production_readiness': 'Not verified'}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('manifest', 'cnpg-manifest', 'cert-manager-manifest'):
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--api-address', action='append', required=True)
    a = p.parse_args()
    print(json.dumps(render(a.manifest, a.cnpg_manifest, a.cert_manager_manifest, a.api_address), sort_keys=True))
