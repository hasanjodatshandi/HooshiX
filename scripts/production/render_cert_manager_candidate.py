"""Render bounded internal cert-manager TLS; no keys, installation or public routes."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path

import yaml

import render_platform_admission as admission
from build_cert_manager_candidates import CANDIDATE_RECIPE, recipe
from publish_cert_manager_candidates import source_predicate
from render_eso_candidate import network as eso_network
from render_mesh_security_upgrade import provenance_expression
from verify_published_cert_manager import candidate

NAMESPACE = 'cnpg-system'
MANAGER = 'hooshix-cert-manager-commissioning'
ACCOUNTS = {'controller': 'cert-manager', 'cainjector': 'cert-manager-cainjector',
            'webhook': 'cert-manager-webhook'}
CRDS = {'certificates.cert-manager.io', 'certificaterequests.cert-manager.io',
        'issuers.cert-manager.io'}
ISSUERS = ('hooshix-database-selfsigned', 'hooshix-database-ca')
SIGNERS = ['issuers.cert-manager.io/' + NAMESPACE + '.' + name for name in ISSUERS]
SELECTOR = {'matchLabels': {'kubernetes.io/metadata.name': NAMESPACE}}
BOUNDARY_SELECTOR = {'matchExpressions': [{'key': 'kubernetes.io/metadata.name', 'operator': 'In',
    'values': ['istio-system', 'hooshix-secrets', 'platform-apps', NAMESPACE]}]}
BOUND = 16 * 1024 * 1024
CONTROLLERS = ('issuers', 'certificates-metrics', 'certificaterequests-approver',
    'certificaterequests-issuer-ca', 'certificaterequests-issuer-selfsigned',
    'certificates-trigger', 'certificates-issuing', 'certificates-key-manager',
    'certificates-request-manager', 'certificates-readiness', 'certificates-revision-manager')
RESOURCES = {'requests': {'cpu': '50m', 'memory': '64Mi', 'ephemeral-storage': '16Mi'},
             'limits': {'cpu': '500m', 'memory': '256Mi', 'ephemeral-storage': '64Mi'}}
TOKEN = {'name': 'hooshix-api-token', 'projected': {'defaultMode': 420, 'sources': [
    {'serviceAccountToken': {'path': 'token', 'expirationSeconds': 3600}},
    {'configMap': {'name': 'kube-root-ca.crt', 'items': [{'key': 'ca.crt', 'path': 'ca.crt'}]}},
    {'downwardAPI': {'items': [{'path': 'namespace', 'fieldRef': {
        'apiVersion': 'v1', 'fieldPath': 'metadata.namespace'}}]}}]}}
MOUNT = {'name': 'hooshix-api-token', 'mountPath': '/var/run/secrets/kubernetes.io/serviceaccount', 'readOnly': True}


def verifier_images():
    pins = dict(line.split('=', 1) for line in (Path(__file__).resolve().parents[2]
        / 'infrastructure/kyverno/pins.env').read_text().splitlines() if line and not line.startswith('#'))
    return {component: 'reg.kyverno.io/kyverno/' + repository + '@'
        + pins['KYVERNO_' + component.upper() + '_AMD64_DIGEST']
        for component, repository in (('admission', 'kyverno'), ('reports', 'reports-controller'))}


def resource(kind, name, *, namespaced=True, **fields):
    api = ('rbac.authorization.k8s.io/v1' if kind in ('Role', 'RoleBinding', 'ClusterRole',
           'ClusterRoleBinding') else 'v1')
    return {'apiVersion': api, 'kind': kind, 'metadata': {'name': name,
        **({'namespace': NAMESPACE} if namespaced else {}),
        'labels': {'app.kubernetes.io/managed-by': MANAGER}}, **fields}


def serving_secret():
    # Empty owned destination only; the webhook generates/rotates its key by exact-name UPDATE.
    obj = resource('Secret', 'cert-manager-webhook-ca', type='Opaque')
    obj['metadata']['annotations'] = {'cert-manager.io/allow-direct-injection': 'true'}
    return obj


def permissions():
    read = ['get', 'list', 'watch']
    write = ['create', 'update', 'patch', 'delete']
    rule = lambda group, resources, verbs, **extra: {
        'apiGroups': [group], 'resources': resources, 'verbs': verbs, **extra}
    roles = [resource('Role', ACCOUNTS['controller'], rules=[
        rule('cert-manager.io', ['issuers', 'certificates', 'certificaterequests'], [*read, *write]),
        rule('cert-manager.io', ['issuers/status', 'certificates/status',
             'certificaterequests/status', 'certificaterequests/approval'], ['update', 'patch']),
        rule('', ['secrets'], [*read, *write]), rule('', ['events'], ['create', 'patch'])]),
        resource('ClusterRole', ACCOUNTS['controller'], namespaced=False, rules=[
            rule('cert-manager.io', ['signers'], ['approve'], resourceNames=SIGNERS)]),
        resource('Role', ACCOUNTS['cainjector'], rules=[rule('', ['secrets'], read)]),
        resource('Role', ACCOUNTS['webhook'], rules=[
            rule('', ['secrets'], [*read, 'update'], resourceNames=['cert-manager-webhook-ca'])]),
        resource('ClusterRole', ACCOUNTS['cainjector'], namespaced=False, rules=[
            rule('admissionregistration.k8s.io', ['mutatingwebhookconfigurations',
                 'validatingwebhookconfigurations'], read),
            rule('admissionregistration.k8s.io', ['mutatingwebhookconfigurations',
                 'validatingwebhookconfigurations'], ['update', 'patch'],
                 resourceNames=['cert-manager-webhook'])]),
        resource('ClusterRole', ACCOUNTS['webhook'], namespaced=False,
                 rules=[rule('authorization.k8s.io', ['subjectaccessreviews'], ['create'])])]
    bindings = []
    for role in roles:
        cluster = role['kind'] == 'ClusterRole'
        bindings.append(resource('ClusterRoleBinding' if cluster else 'RoleBinding',
            role['metadata']['name'], namespaced=not cluster,
            subjects=[{'kind': 'ServiceAccount', 'name': role['metadata']['name'], 'namespace': NAMESPACE}],
            roleRef={'apiGroup': 'rbac.authorization.k8s.io', 'kind': role['kind'],
                     'name': role['metadata']['name']}))
    reports = resource('ClusterRole', 'hooshix-cert-manager-report-reader', namespaced=False,
                       rules=[rule('cert-manager.io', ['issuers'], read)])
    reports['metadata']['labels']['rbac.kyverno.io/aggregate-to-reports-controller'] = 'true'
    return [*roles, *bindings, reports]


def arguments(component):
    common = ['--v=0', '--logging-format=json']
    if component == 'controller':
        return [*common, '--namespace=' + NAMESPACE, '--leader-elect=false',
            '--issuer-ambient-credentials=false', '--cluster-issuer-ambient-credentials=false',
            '--controllers=' + ','.join(CONTROLLERS), '--kube-api-qps=5', '--kube-api-burst=10']
    if component == 'cainjector':
        return [*common, '--namespace=' + NAMESPACE, '--leader-elect=false',
            '--enable-certificates-data-source=false', '--enable-customresourcedefinitions-injectable=false',
            '--enable-apiservices-injectable=false']
    return [*common, '--secure-port=10250', '--dynamic-serving-ca-secret-namespace=' + NAMESPACE,
        '--dynamic-serving-ca-secret-name=cert-manager-webhook-ca',
        '--dynamic-serving-dns-names=cert-manager-webhook.' + NAMESPACE + '.svc']


def network(addresses):
    # Reuse the exact bounded canonical API-address validation, not ESO's OpenBao edge.
    base = eso_network(addresses)[:2]
    base[0]['metadata'].update(name='hooshix-cert-manager-control-plane', namespace=NAMESPACE)
    base[0]['spec']['podSelector'] = {'matchLabels': {'app.kubernetes.io/instance': 'cert-manager'}}
    base[1]['metadata'].update(name='hooshix-cert-manager-webhook-api', namespace=NAMESPACE)
    base[1]['spec']['podSelector'] = {'matchLabels': {'app.kubernetes.io/name': 'webhook',
                                                  'app.kubernetes.io/instance': 'cert-manager'}}
    mesh = copy.deepcopy(base[0])
    mesh['metadata']['name'] = 'hooshix-cert-manager-mesh-ca'
    mesh['spec'] = {'podSelector': {'matchLabels': {'app.kubernetes.io/instance': 'cert-manager'},
        'matchExpressions': [{'key': 'app.kubernetes.io/name', 'operator': 'In',
                              'values': ['cert-manager', 'cainjector']}]},
        'policyTypes': ['Egress'], 'egress': [{'to': [{'namespaceSelector': {'matchLabels': {
            'kubernetes.io/metadata.name': 'istio-system'}}, 'podSelector': {'matchLabels': {'app': 'istiod'}}}],
            'ports': [{'protocol': 'TCP', 'port': 15012}]}]}
    return [*base, mesh]


def policies(pods):
    selected = candidate()['publication']
    if any(value != 'public' for value in selected['registry_visibility'].values()):
        raise ValueError('this credential-free internal candidate requires the three approved public packages')
    boundary = admission.hardening(NAMESPACE, pods)
    boundary['spec']['matchConstraints']['namespaceSelector'] = copy.deepcopy(BOUNDARY_SELECTOR)
    for component, account in ACCOUNTS.items():
        mode = 'none' if component == 'webhook' else 'ambient'
        check = ('object.metadata.namespace != "cnpg-system" || object.spec.serviceAccountName != '
            + admission.literal(account) + ' || (has(object.metadata.labels) && '
            + '"istio.io/dataplane-mode" in object.metadata.labels && '
            + 'object.metadata.labels["istio.io/dataplane-mode"] == ' + admission.literal(mode)
            + ' && has(object.spec.automountServiceAccountToken) && !object.spec.automountServiceAccountToken'
            + ' && !has(object.spec.containers[0].command) && !has(object.spec.containers[0].envFrom)'
            + ' && has(object.spec.volumes) && (' + admission.scalar_tree('object.spec.volumes', [TOKEN]) + ')'
            + ' && has(object.spec.containers[0].volumeMounts) && ('
            + admission.scalar_tree('object.spec.containers[0].volumeMounts', [MOUNT]) + ')'
            + ' && has(object.spec.containers[0].env) && ('
            + admission.scalar_tree('object.spec.containers[0].env', pods[account]['containers'][0]['env']) + ')'
            + ' && has(object.spec.containers[0].args) && ('
            + admission.scalar_tree('object.spec.containers[0].args', arguments(component)) + '))')
        boundary['spec']['validations'].append(admission.validation('cert-manager scope/enrollment rejected', check))
    result = [boundary]
    for component, image in selected['images'].items():
        p = admission.image_policy(NAMESPACE, [image], selected['repository_revision'])
        p['metadata']['name'] = 'hooshix-cert-manager-' + component + '-source'
        p['spec'].pop('credentials')  # Only the three approved public infrastructure packages.
        expected = source_predicate(component, recipe(CANDIDATE_RECIPE), selected['repository_revision'],
                                    selected['invocation_id'], CANDIDATE_RECIPE)
        p['spec']['validations'][2] = admission.validation('cert-manager source identity rejected',
                                                         provenance_expression(expected))
        result.extend(admission.split_image_policy(p))
    issuers = admission.policy('ValidatingPolicy', 'hooshix-cert-manager-internal-issuers', NAMESPACE,
        [admission.validation('only internal CA or self-signed issuers allowed',
             'object.metadata.namespace != "cnpg-system" || ((has(object.spec.ca) != has(object.spec.selfSigned))'
             ' && !has(object.spec.acme) && !has(object.spec.vault) && !has(object.spec.venafi))')])
    issuers['spec']['matchConstraints']['namespaceSelector'] = copy.deepcopy(BOUNDARY_SELECTOR)
    issuers['spec']['matchConstraints']['resourceRules'] = [{'apiGroups': ['cert-manager.io'],
        'apiVersions': ['v1'], 'operations': ['CREATE', 'UPDATE'], 'resources': ['issuers'], 'scope': 'Namespaced'}]
    result.append(issuers)
    return result


def validate(plan):
    if plan['callback_trust_boundary'] != 'owner-accepted-exact-name-rbac':
        raise ValueError('explicit owner-accepted injector trust boundary required')
    if plan['verifier_images'] != verifier_images():
        raise ValueError('exact existing verifier images required for bounded discovery refresh')
    items = plan['manifest']['items']
    deployments = [item for item in items if item['kind'] == 'Deployment']
    if len(deployments) != 3 or {d['metadata']['name'] for d in deployments} != set(ACCOUNTS.values()):
        raise ValueError('exact three certificate workloads required')
    images = candidate()['publication']['images']
    for component, account in ACCOUNTS.items():
        obj = next(d for d in deployments if d['metadata']['name'] == account)
        template = obj['spec']['template']
        pod = template['spec']
        if (obj['metadata']['namespace'] != NAMESPACE or obj['spec']['replicas'] != 1
                or pod['serviceAccountName'] != account or len(pod['containers']) != 1
                or any(pod.get(f) for f in ('hostNetwork', 'hostPID', 'hostIPC', 'initContainers', 'ephemeralContainers'))
                or any('hostPath' in volume for volume in pod.get('volumes', []))
                or template['metadata']['labels']['istio.io/dataplane-mode'] != ('none' if component == 'webhook' else 'ambient')):
            raise ValueError('restricted certificate Pod boundary required')
        container = pod['containers'][0]
        security = container['securityContext']
        if (container['image'] != images[component] or container['args'] != arguments(component)
                or container['resources'] != RESOURCES or security != {
                    'runAsNonRoot': True, 'runAsUser': 65532, 'runAsGroup': 65532,
                    'seccompProfile': {'type': 'RuntimeDefault'}, 'allowPrivilegeEscalation': False,
                    'capabilities': {'drop': ['ALL']}, 'readOnlyRootFilesystem': True}
                or pod != plan['pods'][account] or pod.get('automountServiceAccountToken') is not False
                or pod.get('volumes') != [TOKEN] or container.get('volumeMounts') != [MOUNT]
                or container.get('command') or container.get('envFrom')
                or not all(container.get(f) for f in ('startupProbe', 'readinessProbe', 'livenessProbe'))):
            raise ValueError('exact source image, scope and resource limits required')
    if [item for item in items if item['kind'] in ('Role', 'ClusterRole', 'RoleBinding', 'ClusterRoleBinding')] != permissions():
        raise ValueError('exact namespace-scoped permission set required')
    if [item for item in items if item['kind'] == 'Secret'] != [serving_secret()]:
        raise ValueError('only the exact EMPTY serving destination may enter public plans')
    for item in items:
        if item['kind'] in ('MutatingWebhookConfiguration', 'ValidatingWebhookConfiguration'):
            for hook in item['webhooks']:
                if (hook['failurePolicy'] != 'Fail' or hook['timeoutSeconds'] != 5
                        or hook['namespaceSelector'] != SELECTOR
                        or hook['clientConfig']['service']['namespace'] != NAMESPACE
                        or any(rule.get('scope') != 'Namespaced' for rule in hook['rules'])):
                    raise ValueError('namespace-bound fail-closed HTTPS callback required')
    if plan['policies'] != policies(plan['pods']) or plan['strict']['spec'] != {'mtls': {'mode': 'STRICT'}}:
        raise ValueError('exact fail-closed policies required')


def render(manifest, addresses):
    source = recipe()
    selected = candidate()['publication']
    if (manifest.is_symlink() or not manifest.is_file() or manifest.stat().st_size > BOUND
            or hashlib.sha256(manifest.read_bytes()).hexdigest() != source['manifest']['sha256']):
        raise ValueError('exact bounded official cert-manager manifest required')
    upstream = [obj for obj in yaml.safe_load_all(manifest.read_bytes()) if obj]
    crds = [obj for obj in upstream if obj['kind'] == 'CustomResourceDefinition'
            and obj['metadata']['name'] in CRDS]
    if len(crds) != 3:
        raise ValueError('exact three namespace-scoped certificate CRDs required')
    objects, pods = [], {}
    for component, account in ACCOUNTS.items():
        matches = [obj for obj in upstream if obj['kind'] == 'Deployment' and obj['metadata']['name'] == account]
        if len(matches) != 1:
            raise ValueError('exact official three-workload set required')
        obj = matches[0]
        obj['metadata']['namespace'] = NAMESPACE
        obj['spec']['replicas'] = 1
        template = obj['spec']['template']
        template['metadata']['labels']['istio.io/dataplane-mode'] = 'none' if component == 'webhook' else 'ambient'
        pod = template['spec']
        if len(pod['containers']) != 1 or pod.get('initContainers') or pod.get('hostNetwork'):
            raise ValueError('unexpected upstream workload shape')
        container = pod['containers'][0]
        if container['image'] != 'quay.io/jetstack/cert-manager-' + component + ':v' + source['version']:
            raise ValueError('unexpected upstream image')
        container.update(image=selected['images'][component], args=arguments(component), resources=copy.deepcopy(RESOURCES))
        pod['automountServiceAccountToken'] = False
        pod['volumes'] = [copy.deepcopy(TOKEN)]
        container['volumeMounts'] = [copy.deepcopy(MOUNT)]
        container['securityContext'].update(runAsNonRoot=True, runAsUser=65532, runAsGroup=65532,
            seccompProfile={'type': 'RuntimeDefault'})
        if component == 'cainjector':
            # shortcut: upstream has no health endpoint; TCP metrics proves process health only, TLS tests prove injection.
            container['livenessProbe'] = {'tcpSocket': {'port': 'http-metrics'}, 'periodSeconds': 10,
                                           'timeoutSeconds': 1, 'failureThreshold': 6}
        if component != 'webhook':
            container['readinessProbe'] = copy.deepcopy(container['livenessProbe'])
        # Kubernetes omits zero initialDelaySeconds; omit the inherited delay for stable reentry.
        container['startupProbe'] = {key: value for key, value in copy.deepcopy(container['livenessProbe']).items()
                                     if key != 'initialDelaySeconds'}
        container['startupProbe'].update(periodSeconds=5, failureThreshold=36)
        pod['terminationGracePeriodSeconds'] = 30
        pods[account] = pod
        objects.extend([resource('ServiceAccount', account), obj])
    for obj in upstream:
        if obj['kind'] == 'Service' and obj['metadata']['name'] in ACCOUNTS.values():
            obj['metadata']['namespace'] = NAMESPACE
            objects.append(obj)
        if obj['kind'] in ('MutatingWebhookConfiguration', 'ValidatingWebhookConfiguration'):
            obj['metadata']['annotations']['cert-manager.io/inject-ca-from-secret'] = NAMESPACE + '/cert-manager-webhook-ca'
            for hook in obj['webhooks']:
                hook.update(failurePolicy='Fail', timeoutSeconds=5, namespaceSelector=copy.deepcopy(SELECTOR))
                hook['clientConfig']['service']['namespace'] = NAMESPACE
                hook['clientConfig']['service']['port'] = 443
                for rule in hook['rules']:
                    rule['scope'] = 'Namespaced'
                if obj['kind'] == 'ValidatingWebhookConfiguration':
                    hook['rules'] = [{'apiGroups': ['cert-manager.io'], 'apiVersions': ['v1'],
                        'operations': ['CREATE', 'UPDATE'], 'resources': ['certificates', 'certificaterequests',
                            'certificaterequests/status', 'issuers'], 'scope': 'Namespaced'}]
            objects.append(obj)
    objects.extend([serving_secret(), *permissions()])
    for obj in [*crds, *objects]:
        obj['metadata'].setdefault('labels', {})['app.kubernetes.io/managed-by'] = MANAGER
    ns = resource('Namespace', NAMESPACE, namespaced=False)
    ns['metadata']['labels'].update({'istio.io/dataplane-mode': 'ambient',
        'pod-security.kubernetes.io/enforce': 'restricted', 'pod-security.kubernetes.io/enforce-version': 'v1.35'})
    strict = {'apiVersion': 'security.istio.io/v1', 'kind': 'PeerAuthentication',
        'metadata': {'name': 'hooshix-cert-manager-strict', 'namespace': NAMESPACE},
        'spec': {'mtls': {'mode': 'STRICT'}}}
    plan = {'schema_version': 1, 'installation_id': 'hooshix-production', 'profile': 'production-single-server',
        'namespace': ns, 'crds': crds, 'manifest': {'apiVersion': 'v1', 'kind': 'List', 'items': objects},
        'pods': pods, 'policies': policies(pods), 'network': network(addresses), 'strict': strict,
        'callback_trust_boundary': 'owner-accepted-exact-name-rbac',
        'images': selected['images'], 'verifier_images': verifier_images(),
        'certificate_issuance': 'Not verified', 'deployment': 'Not verified'}
    validate(plan)
    return plan


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--api-address', action='append', required=True)
    args = parser.parse_args()
    try:
        result = render(args.manifest, args.api_address)
    except (ValueError, KeyError, TypeError, OSError, yaml.YAMLError):
        parser.exit(1, 'CERT_MANAGER_RENDER=Failed; no cluster writes\n')
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
