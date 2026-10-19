"""Closed commissioning desired state; reuse the published Redis, ACL, TLS and Retain PV."""
import copy
import hashlib
import json

import render_platform_admission as admission
import render_redis_security as redis
import install_redis_local_volume as volume
from publish_redis_candidate import source_predicate
from render_mesh_security_upgrade import provenance_expression

NS = 'platform-data'
NAME = 'security-redis'
MANAGER = 'hooshix-redis-runtime'
LABELS = {'app.kubernetes.io/name': NAME, 'app.kubernetes.io/managed-by': MANAGER,
          'istio.io/dataplane-mode': 'ambient', 'hooshix.io/commissioning': 'closed'}
SELECTOR = {'matchLabels': {'app.kubernetes.io/name': NAME}}
RESOURCES = {'requests': {'cpu': '100m', 'memory': '128Mi', 'ephemeral-storage': '16Mi'},
             'limits': {'cpu': '500m', 'memory': '512Mi', 'ephemeral-storage': '64Mi'}}
HEALTH = redis.HEALTH


def pin():
    value = json.loads((redis.PIN.parent / 'runtime.json').read_bytes())
    redis.pins()
    if (value['schema_version'] != 1 or value['application_ingress'] != 'CLOSED'
        or value['production_readiness'] != 'Not verified' or value['maxmemory_bytes'] != 128 * 1024**2
        or value['recipe_sha256'] != hashlib.sha256(redis.PIN.read_bytes()).hexdigest()
        or value['dockerfile_sha256'] != hashlib.sha256((redis.PIN.parent / 'Dockerfile').read_bytes()).hexdigest()):
        raise ValueError('REVIEWED_REDIS_RUNTIME_PIN_REQUIRED')
    return value


def resource(kind, name=NAME, api='v1', **body):
    return {'apiVersion': api, 'kind': kind, 'metadata': {
        'name': name, 'namespace': NS, 'labels': LABELS.copy()}, **body}


def pod():
    mounts = [{'name': 'data', 'mountPath': '/data'},
        {'name': 'material', 'mountPath': redis.SECRET_PATH, 'readOnly': True},
        {'name': 'health', 'mountPath': '/run/secrets/health', 'readOnly': True}]
    probe = {'exec': {'command': ['/bin/sh', '-ec', HEALTH]}, 'timeoutSeconds': 4,
             'periodSeconds': 10, 'successThreshold': 1, 'failureThreshold': 3}
    container = {'name': 'redis', 'image': pin()['image'], 'imagePullPolicy': 'IfNotPresent',
        'command': ['/usr/local/bin/redis-server'], 'args': [redis.SECRET_PATH + '/redis.conf'],
        'securityContext': {'runAsNonRoot': True, 'runAsUser': 10001, 'runAsGroup': 10001,
            'allowPrivilegeEscalation': False, 'privileged': False, 'readOnlyRootFilesystem': True,
            'capabilities': {'drop': ['ALL']}, 'seccompProfile': {'type': 'RuntimeDefault'}},
        'resources': copy.deepcopy(RESOURCES), 'volumeMounts': mounts,
        'ports': [{'name': 'tls', 'containerPort': 6379, 'protocol': 'TCP'}],
        'terminationMessagePath': '/dev/termination-log', 'terminationMessagePolicy': 'File',
        'startupProbe': probe | {'failureThreshold': 30}, 'readinessProbe': copy.deepcopy(probe),
        'livenessProbe': probe | {'periodSeconds': 20, 'failureThreshold': 6}}
    return {'serviceAccountName': NAME, 'automountServiceAccountToken': False,
        'nodeSelector': {'kubernetes.io/hostname': volume.NODE}, 'terminationGracePeriodSeconds': 60,
        'securityContext': {'runAsNonRoot': True, 'runAsUser': 10001, 'runAsGroup': 10001,
            'fsGroup': 10001, 'fsGroupChangePolicy': 'OnRootMismatch',
            'seccompProfile': {'type': 'RuntimeDefault'}}, 'containers': [container],
        'volumes': [{'name': 'data', 'persistentVolumeClaim': {'claimName': volume.CLAIM}},
            {'name': 'material', 'projected': {'defaultMode': 0o440, 'sources': [
                {'secret': {'name': 'security-redis-tls', 'items': [{'key': n, 'path': n}
                    for n in ('tls.crt', 'tls.key', 'ca.crt')]}},
                {'secret': {'name': 'security-redis-acl', 'items': [{'key': 'users.acl', 'path': 'users.acl'}]}},
                {'configMap': {'name': NAME, 'items': [{'key': 'redis.conf', 'path': 'redis.conf'}]}}]}},
            {'name': 'health', 'secret': {'secretName': 'security-redis-health', 'defaultMode': 0o440,
                'items': [{'key': 'password', 'path': 'password'}]}}]}


def boundary(previous, spec):
    result = copy.deepcopy(previous)
    for check in result['spec']['validations']:
        check['expression'] = 'object.spec.serviceAccountName == "security-redis" || (' + check['expression'] + ')'
    checked = copy.deepcopy(spec)
    checked.pop('nodeSelector')
    checked['containers'][0].pop('resources')
    terms = [admission.scalar_tree('object.spec.' + key, value) for key, value in checked.items()]
    terms += ['object.spec.nodeSelector.size() == 1 && object.spec.nodeSelector["kubernetes.io/hostname"] == '
              + admission.literal(volume.NODE)]
    terms += ['object.metadata.name == "security-redis-0"',
        'has(object.metadata.labels) && object.metadata.labels["istio.io/dataplane-mode"] == "ambient"'
        ' && object.metadata.labels["app.kubernetes.io/name"] == "security-redis"',
        'has(object.metadata.ownerReferences) && object.metadata.ownerReferences.size() == 1'
        ' && object.metadata.ownerReferences[0].kind == "StatefulSet"'
        ' && object.metadata.ownerReferences[0].name == "security-redis" && object.metadata.ownerReferences[0].controller',
        *['!has(object.spec.' + k + ')' for k in ('initContainers', 'ephemeralContainers', 'imagePullSecrets')],
        *['!has(object.spec.' + k + ') || !object.spec.' + k
          for k in ('hostNetwork', 'hostPID', 'hostIPC', 'shareProcessNamespace')],
        '!has(object.spec.securityContext.sysctls) || object.spec.securityContext.sysctls.size() == 0',
        '!has(object.spec.securityContext.windowsOptions)',
        '!has(object.spec.securityContext.supplementalGroups) || object.spec.securityContext.supplementalGroups.size() == 0']
    c = 'object.spec.containers[0]'
    for group, values in RESOURCES.items():
        terms.append(c + '.resources.' + group + '.size() == 3 && ' + ' && '.join(
            admission.literal(key) + ' in ' + c + '.resources.' + group + ' && '
            + c + '.resources.' + group + '[' + admission.literal(key) + '] == ' + admission.literal(value)
            for key, value in values.items()))
    terms += ['!has(' + c + '.' + k + ')' for k in ('env', 'envFrom', 'lifecycle', 'workingDir')]
    terms += ['!has(' + c + '.securityContext.capabilities.add) || ' + c + '.securityContext.capabilities.add.size() == 0',
        '!has(' + c + '.securityContext.procMount) || ' + c + '.securityContext.procMount == "Default"',
        '!has(' + c + '.securityContext.windowsOptions)',
        c + '.volumeMounts.all(m, !has(m.subPath) && !has(m.subPathExpr) && !has(m.mountPropagation))',
        c + '.ports.all(p, !has(p.hostPort) || p.hostPort == 0)']
    result['spec']['validations'].append(admission.validation('closed Redis runtime contract rejected',
        'object.spec.serviceAccountName != "security-redis" || (' + ' && '.join('(' + t + ')' for t in terms) + ')'))
    return result


def render(previous):
    selected, spec = pin(), pod()
    service = resource('Service', spec={'type': 'ClusterIP', 'clusterIP': 'None',
        'selector': SELECTOR['matchLabels'], 'ports': [{'name': 'tls', 'port': 6379, 'targetPort': 'tls'}]})
    claim = resource('PersistentVolumeClaim', volume.CLAIM, spec={'accessModes': ['ReadWriteOnce'],
        'volumeMode': 'Filesystem', 'volumeName': volume.VOLUME, 'storageClassName': volume.CLASS,
        'resources': {'requests': {'storage': '7Gi'}}})
    claim['metadata']['annotations'] = {'argocd.argoproj.io/sync-options': 'Prune=confirm'}
    objects = [resource('ServiceAccount', automountServiceAccountToken=False),
        resource('ConfigMap', data={'redis.conf': redis.server_config(selected['maxmemory_bytes'])}, immutable=True),
        service, resource('NetworkPolicy', 'hooshix-redis-closed', 'networking.k8s.io/v1',
            spec={'podSelector': SELECTOR, 'policyTypes': ['Ingress', 'Egress'], 'ingress': [],
                  'egress': [{'to': [{'namespaceSelector': {'matchLabels': {
                      'kubernetes.io/metadata.name': 'istio-system'}},
                      'podSelector': {'matchLabels': {'app': 'istiod'}}}],
                      'ports': [{'port': 15012, 'protocol': 'TCP'}]}]}),
        resource('AuthorizationPolicy', 'hooshix-redis-closed', 'security.istio.io/v1',
            spec={'selector': SELECTOR, 'action': 'ALLOW', 'rules': []}), claim]
    stateful = resource('StatefulSet', api='apps/v1', spec={'replicas': 1, 'serviceName': NAME,
        'selector': SELECTOR, 'updateStrategy': {'type': 'OnDelete'},
        'template': {'metadata': {'labels': LABELS.copy()}, 'spec': spec}})
    policy = admission.image_policy(NS, [selected['image']], selected['repository_revision'])
    policy['metadata'].update(name='hooshix-redis-source', labels=LABELS.copy())
    policy['metadata'].pop('namespace', None)
    policy['spec'].pop('credentials', None)
    policy['spec']['validations'][2] = admission.validation('Redis source build identity rejected',
        provenance_expression(source_predicate(selected['repository_revision'],
            'github:' + selected['publication_run'] + ':' + str(selected['publication_attempt']))))
    sources = admission.split_image_policy(policy)
    for item in sources:
        for check in [*item['spec']['variables'], *item['spec']['validations']]:
            for field in ('expression', 'messageExpression'):
                if field in check: check[field] = check[field].replace('images.containers', 'variables.redisImages')
        item['spec']['variables'].insert(0, {'name': 'redisImages', 'expression':
            '(images.containers + images.?initContainers.orValue([]) + images.?ephemeralContainers.orValue([]))'
            '.filter(image, image == ' + admission.literal(selected['image']) + ')'})
    return {'schema_version': 1, 'installation_id': 'hooshix-production',
        'profile': 'production-single-server', 'image': selected['image'],
        'public_ca_sha256': json.loads((redis.ROOT / 'scripts/production/offline-ca/root-authority.json')
            .read_bytes())['existing_root_exception']['certificate_file_sha256'],
        'previous_boundary': previous, 'boundary': boundary(previous, spec),
        'source_policies': sources, 'objects': objects, 'statefulset': stateful,
        'application_ingress': 'CLOSED', 'public_access': 'CLOSED',
        'automatic_renewal': 'Not run', 'production_readiness': 'Not verified'}
