"""Render the reviewed closed single-node Kafka runtime; never apply it."""
import copy
import hashlib
import json
from pathlib import Path

import install_kafka_local_volume as volume
import render_platform_admission as admission
from build_kafka_candidate import pins as candidate_pin
from publish_kafka_candidate import source_predicate
from render_mesh_security_upgrade import provenance_expression

NS, NAME, MANAGER = 'platform-data', 'kafka', 'hooshix-kafka-runtime'
LABELS = {'app.kubernetes.io/name': NAME, 'app.kubernetes.io/managed-by': MANAGER,
          'istio.io/dataplane-mode': 'ambient', 'hooshix.io/commissioning': 'closed'}
HASH = 'hooshix.io/kafka-spec-sha256'
SECRET = 'kafka-node-tls'
MATERIAL = '/run/kafka'
BOUNDARY_UID_PLACEHOLDER = 'KAFKA_STATEFULSET_UID_BOUNDARY_PLACEHOLDER'
RESOURCES = {'requests': {'cpu': '250m', 'memory': '768Mi', 'ephemeral-storage': '64Mi'},
             'limits': {'cpu': '1', 'memory': '2Gi', 'ephemeral-storage': '128Mi'}}
PROBE = ('/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server 127.0.0.1:9092 '
         '--command-config /etc/kafka/commissioned/client.properties >/dev/null 2>&1')


def pin():
    root = Path(__file__).resolve().parents[2]
    path = root / 'infrastructure/production/kafka/published-candidate.json'
    value = json.loads(path.read_bytes())
    if (value.get('schema_version') != 1 or value.get('version') != '4.2.2'
        or value.get('image') != 'ghcr.io/hasanjodatshandi/hooshix/platform-kafka-v2-private@sha256:'
            '9fe8fcb340ffe144e3ffa502df1b0694743a027df56595dd91e62b6f9e716556'
        or value.get('image_configuration_digest') != 'sha256:e12c940f9d38faa5a7034d622727156e4766a833135de66231e36ec20e854e83'
        or value.get('repository_revision') != '7d4d00d781060e723e6f231c5ea63660c9d87d13'
        or value.get('publication_run') != '39468920860' or value.get('publication_attempt') != 1
        or value.get('production_promotion') != 'blocked-until-runtime-staging-and-recovery-evidence'
        or not isinstance(value.get('publication_sha256'), str)
        or len(value['publication_sha256']) != 64
        or any(c not in '0123456789abcdef' for c in value['publication_sha256'])):
        raise ValueError('REVIEWED_BLOCKED_KAFKA_PIN_REQUIRED')
    value['recipe_sha256'] = hashlib.sha256((path.parent / 'candidate.json').read_bytes()).hexdigest()
    value['dockerfile_sha256'] = hashlib.sha256((path.parent / 'Dockerfile').read_bytes()).hexdigest()
    return value


def server_config():
    return '''process.roles=broker,controller
node.id=1
controller.quorum.bootstrap.servers=127.0.0.1:9093
listeners=SSL://0.0.0.0:9092,CONTROLLER://127.0.0.1:9093
advertised.listeners=SSL://kafka-0.kafka.platform-data.svc.cluster.local:9092
controller.listener.names=CONTROLLER
inter.broker.listener.name=SSL
listener.security.protocol.map=SSL:SSL,CONTROLLER:SSL
ssl.client.auth=required
ssl.keystore.type=PEM
ssl.keystore.location=/run/kafka/broker.pem
ssl.truststore.type=PEM
ssl.truststore.location=/run/kafka/ca.crt
ssl.endpoint.identification.algorithm=https
ssl.enabled.protocols=TLSv1.3,TLSv1.2
authorizer.class.name=org.apache.kafka.metadata.authorizer.StandardAuthorizer
allow.everyone.if.no.acl.found=false
super.users=User:CN=kafka-node-1
log.dirs=/var/lib/kafka/data
metadata.log.dir=/var/lib/kafka/data
num.partitions=1
default.replication.factor=1
min.insync.replicas=1
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
unclean.leader.election.enable=false
auto.create.topics.enable=false
'''


def client_config():
    return '''security.protocol=SSL
ssl.keystore.type=PEM
ssl.keystore.location=/run/kafka/broker.pem
ssl.truststore.type=PEM
ssl.truststore.location=/run/kafka/ca.crt
ssl.endpoint.identification.algorithm=https
ssl.enabled.protocols=TLSv1.3,TLSv1.2
'''


def init_command():
    return ('umask 077; cat /source/tls.crt /source/tls.key > /run/kafka/broker.pem; '
        'cp /source/ca.crt /run/kafka/ca.crt; chmod 0400 /run/kafka/broker.pem /run/kafka/ca.crt')


def start_script():
    return '''#!/bin/sh
set -eu
data=/var/lib/kafka/data
config=/etc/kafka/commissioned/server.properties
if [ -z "$(find "$data" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
  cluster_id=$(/opt/kafka/bin/kafka-storage.sh random-uuid 2>/dev/null) || { echo KAFKA_ID_FAILED; exit 1; }
  case "$cluster_id" in ''|*[!A-Za-z0-9_-]*) echo KAFKA_ID_REJECTED; exit 1;; esac
  /opt/kafka/bin/kafka-storage.sh format --standalone --cluster-id "$cluster_id" --config "$config" >/dev/null 2>&1 || { echo KAFKA_FORMAT_FAILED; exit 1; }
  /opt/kafka/bin/kafka-storage.sh info --config "$config" >/dev/null 2>&1 || { echo KAFKA_FORMAT_VERIFY_FAILED; exit 1; }
  grep -Fqx "cluster.id=$cluster_id" "$data/meta.properties" && grep -Fqx 'node.id=1' "$data/meta.properties" && grep -Fqx 'version=1' "$data/meta.properties" || { echo KAFKA_FORMAT_VERIFY_FAILED; exit 1; }
  umask 077
  printf 'hooshix-kafka-runtime-v1:%s\\n' "$cluster_id" > "$data/.hooshix-runtime-marker.tmp"
  chmod 0600 "$data/.hooshix-runtime-marker.tmp"
  mv "$data/.hooshix-runtime-marker.tmp" "$data/.hooshix-runtime-marker"
else
  marker=$(cat "$data/.hooshix-runtime-marker" 2>/dev/null) || { echo KAFKA_EXISTING_DATA_REJECTED; exit 1; }
  case "$marker" in hooshix-kafka-runtime-v1:*) cluster_id=${marker#hooshix-kafka-runtime-v1:};; *) echo KAFKA_EXISTING_DATA_REJECTED; exit 1;; esac
  case "$cluster_id" in ''|*[!A-Za-z0-9_-]*) echo KAFKA_EXISTING_DATA_REJECTED; exit 1;; esac
  /opt/kafka/bin/kafka-storage.sh info --config "$config" >/dev/null 2>&1 || { echo KAFKA_EXISTING_DATA_REJECTED; exit 1; }
  grep -Fqx "cluster.id=$cluster_id" "$data/meta.properties" && grep -Fqx 'node.id=1' "$data/meta.properties" && grep -Fqx 'version=1' "$data/meta.properties" || { echo KAFKA_EXISTING_DATA_REJECTED; exit 1; }
fi
exec /opt/kafka/bin/kafka-server-start.sh "$config"
'''


def pod():
    probe = {'exec': {'command': ['/bin/sh', '-ec', PROBE]}, 'timeoutSeconds': 5,
        'periodSeconds': 10, 'successThreshold': 1, 'failureThreshold': 3}
    mounts = [{'name': 'data', 'mountPath': '/var/lib/kafka/data'},
        {'name': 'material', 'mountPath': MATERIAL, 'readOnly': True},
        {'name': 'config', 'mountPath': '/etc/kafka/commissioned', 'readOnly': True}]
    container = {'name': 'kafka', 'image': pin()['image'], 'imagePullPolicy': 'IfNotPresent',
        'command': ['/bin/sh', '/etc/kafka/commissioned/start.sh'],
        'env': [{'name': 'KAFKA_HEAP_OPTS', 'value': '-Xms512m -Xmx512m'}],
        'securityContext': {'runAsNonRoot': True, 'runAsUser': 10001, 'runAsGroup': 10001,
            'allowPrivilegeEscalation': False, 'privileged': False, 'readOnlyRootFilesystem': True,
            'capabilities': {'drop': ['ALL']}, 'seccompProfile': {'type': 'RuntimeDefault'}},
        'resources': copy.deepcopy(RESOURCES), 'volumeMounts': mounts,
        'ports': [{'name': 'tls', 'containerPort': 9092, 'protocol': 'TCP'}],
        'terminationMessagePath': '/dev/termination-log', 'terminationMessagePolicy': 'File',
        'startupProbe': probe | {'failureThreshold': 36}, 'readinessProbe': copy.deepcopy(probe),
        'livenessProbe': probe | {'periodSeconds': 20, 'failureThreshold': 6}}
    init = {'name': 'tls-material', 'image': pin()['image'], 'imagePullPolicy': 'IfNotPresent',
        'command': ['/bin/sh', '-ec', init_command()],
        'securityContext': copy.deepcopy(container['securityContext']),
        'resources': copy.deepcopy(RESOURCES), 'volumeMounts': [
            {'name': 'source', 'mountPath': '/source', 'readOnly': True},
            {'name': 'material', 'mountPath': MATERIAL}]}
    return {'serviceAccountName': NAME, 'automountServiceAccountToken': False,
        'imagePullSecrets': [{'name': admission.PULL_SECRET}],
        'nodeSelector': {'kubernetes.io/hostname': volume.NODE}, 'terminationGracePeriodSeconds': 60,
        'securityContext': {'runAsNonRoot': True, 'runAsUser': 10001, 'runAsGroup': 10001,
            'fsGroup': 10001, 'fsGroupChangePolicy': 'OnRootMismatch',
            'seccompProfile': {'type': 'RuntimeDefault'}},
        'initContainers': [init], 'containers': [container], 'volumes': [
            {'name': 'data', 'persistentVolumeClaim': {'claimName': volume.CLAIM}},
            {'name': 'source', 'projected': {'defaultMode': 0o440, 'sources': [
                {'secret': {'name': SECRET, 'items': [{'key': k, 'path': k}
                    for k in ('tls.crt', 'tls.key', 'ca.crt')]}}]}},
            {'name': 'material', 'emptyDir': {'medium': 'Memory', 'sizeLimit': '16Mi'}},
            {'name': 'config', 'configMap': {'name': NAME, 'defaultMode': 0o440,
                'items': [{'key': k, 'path': k} for k in ('server.properties', 'client.properties', 'start.sh')]}}]}


def boundary(previous, spec, statefulset_uid):
    result = copy.deepcopy(previous)
    rules = [r for r in result['spec']['matchConstraints']['resourceRules']
        if '' in r['apiGroups'] and 'v1' in r['apiVersions'] and 'pods' in r['resources']]
    if not rules: raise ValueError('KAFKA_POD_ADMISSION_RULE_REQUIRED')
    for rule in rules: rule['operations'] = sorted(set(rule.get('operations', [])) | {'CREATE', 'UPDATE'})
    identity = ['object.spec.serviceAccountName == "kafka"',
        'object.metadata.name == "kafka-0"',
        'has(object.metadata.labels) && object.metadata.labels["app.kubernetes.io/name"] == "kafka"',
        'has(object.metadata.ownerReferences) && object.metadata.ownerReferences.size() == 1'
        ' && object.metadata.ownerReferences.all(r, r.apiVersion == "apps/v1"'
        ' && r.kind == "StatefulSet" && r.name == "kafka" && r.controller)']
    candidate = ' && '.join('(' + value + ')' for value in identity)
    for check in result['spec']['validations']:
        check['expression'] = 'object.spec.serviceAccountName == "kafka" || (' + check['expression'] + ')'
    checked = copy.deepcopy(spec)
    checked.pop('nodeSelector')
    checked['containers'][0].pop('resources')
    checked['initContainers'][0].pop('resources')
    expected_containers = copy.deepcopy(spec['containers'])
    expected_init_containers = copy.deepcopy(spec['initContainers'])
    expected_containers[0].pop('resources')
    expected_init_containers[0].pop('resources')
    terms = [admission.scalar_tree('object.spec.' + k, v) for k, v in checked.items()]
    terms += ['object.spec.nodeSelector.size() == 1 && object.spec.nodeSelector["kubernetes.io/hostname"] == '
              + admission.literal(volume.NODE),
        'object.metadata.name == "kafka-0"',
        'has(object.metadata.labels) && object.metadata.labels["istio.io/dataplane-mode"] == "ambient"'
        ' && object.metadata.labels["app.kubernetes.io/name"] == "kafka"',
        'has(object.metadata.ownerReferences) && object.metadata.ownerReferences.size() == 1'
        ' && object.metadata.ownerReferences[0].apiVersion == "apps/v1"'
        ' && object.metadata.ownerReferences[0].kind == "StatefulSet"'
        ' && object.metadata.ownerReferences[0].name == "kafka"'
        ' && object.metadata.ownerReferences[0].controller'
        ' && object.metadata.ownerReferences[0].uid == ' + admission.literal(statefulset_uid),
        *['object.spec.' + k + '.size() == 1 && ' + admission.scalar_tree('object.spec.' + k + '[0]', v)
          for k, v in (('containers', expected_containers[0]), ('initContainers', expected_init_containers[0]))],
        *['object.spec.' + container + '.resources.' + group + '.size() == 3 && ' + ' && '.join(
            admission.literal(key) + ' in object.spec.' + container + '.resources.' + group + ' && object.spec.'
            + container + '.resources.' + group + '[' + admission.literal(key) + '] == '
            + admission.literal(value) for key, value in values.items())
            for container in ('containers[0]', 'initContainers[0]') for group, values in RESOURCES.items()],
        'object.spec.containers.all(c, c.volumeMounts.all(m, !has(m.subPath) && !has(m.subPathExpr) && !has(m.mountPropagation)))',
        'object.spec.initContainers.all(c, c.volumeMounts.all(m, !has(m.subPath) && !has(m.subPathExpr) && !has(m.mountPropagation)))',
        'object.spec.volumes.all(v, !has(v.hostPath))',
        'object.spec.containers.all(c, !has(c.lifecycle) && !has(c.envFrom) && !has(c.volumeDevices))',
        'object.spec.initContainers.all(c, !has(c.lifecycle) && !has(c.envFrom) && !has(c.volumeDevices))',
        'object.spec.containers.all(c, (!has(c.stdin) || !c.stdin) && (!has(c.stdinOnce) || !c.stdinOnce) && (!has(c.tty) || !c.tty))',
        'object.spec.initContainers.all(c, (!has(c.stdin) || !c.stdin) && (!has(c.stdinOnce) || !c.stdinOnce) && (!has(c.tty) || !c.tty))',
        '!has(object.spec.ephemeralContainers)',
        '!has(object.spec.hostNetwork) || !object.spec.hostNetwork',
        '!has(object.spec.hostPID) || !object.spec.hostPID',
        '!has(object.spec.hostIPC) || !object.spec.hostIPC',
        '!has(object.spec.shareProcessNamespace) || !object.spec.shareProcessNamespace',
        '!has(object.spec.securityContext.sysctls) || object.spec.securityContext.sysctls.size() == 0',
        '!has(object.spec.securityContext.windowsOptions)',
        '!has(object.spec.securityContext.supplementalGroups) || object.spec.securityContext.supplementalGroups.size() == 0',
        'object.spec.containers[0].securityContext.capabilities.drop == ["ALL"]',
        '!has(object.spec.containers[0].securityContext.capabilities.add) || object.spec.containers[0].securityContext.capabilities.add.size() == 0',
        '!has(object.spec.containers[0].securityContext.procMount) || object.spec.containers[0].securityContext.procMount == "Default"',
        '!has(object.spec.containers[0].securityContext.windowsOptions)',
        'object.spec.initContainers[0].securityContext.capabilities.drop == ["ALL"]',
        '!has(object.spec.initContainers[0].securityContext.capabilities.add) || object.spec.initContainers[0].securityContext.capabilities.add.size() == 0',
        '!has(object.spec.initContainers[0].securityContext.procMount) || object.spec.initContainers[0].securityContext.procMount == "Default"',
        '!has(object.spec.initContainers[0].securityContext.windowsOptions)',
        'object.spec.containers[0].ports.all(p, !has(p.hostPort) || p.hostPort == 0)',
        '!has(object.spec.initContainers[0].ports) || object.spec.initContainers[0].ports.size() == 0']
    result['spec']['validations'].append(admission.validation('closed Kafka runtime contract rejected',
        'object.spec.serviceAccountName != "kafka" || ((' + candidate + ') && ('
        + ' && '.join('(' + x + ')' for x in terms) + '))'))
    return result


def render(previous):
    selected, spec = pin(), pod()
    labels = {'app.kubernetes.io/name': NAME}
    def resource(kind, name=NAME, api='v1', **body):
        return {'apiVersion': api, 'kind': kind, 'metadata': {'name': name, 'namespace': NS,
            'labels': LABELS.copy()}, **body}
    objects = [resource('ServiceAccount', automountServiceAccountToken=False),
        resource('ConfigMap', data={'server.properties': server_config(), 'client.properties': client_config(),
            'start.sh': start_script()}, immutable=True),
        resource('Service', spec={'type': 'ClusterIP', 'clusterIP': 'None', 'publishNotReadyAddresses': False,
            'selector': labels, 'ports': [{'name': 'tls', 'port': 9092, 'targetPort': 'tls'}]}),
        resource('NetworkPolicy', 'hooshix-kafka-closed', 'networking.k8s.io/v1', spec={
            'podSelector': {'matchLabels': labels}, 'policyTypes': ['Ingress', 'Egress'], 'ingress': [],
            'egress': [{'to': [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': 'istio-system'}},
                'podSelector': {'matchLabels': {'app': 'istiod'}}}], 'ports': [{'port': 15012, 'protocol': 'TCP'}]}]}),
        resource('AuthorizationPolicy', 'hooshix-kafka-closed', 'security.istio.io/v1',
            spec={'selector': {'matchLabels': labels}, 'action': 'ALLOW', 'rules': []}),
        resource('PersistentVolumeClaim', volume.CLAIM, spec={'accessModes': ['ReadWriteOnce'],
            'volumeMode': 'Filesystem', 'volumeName': volume.VOLUME, 'storageClassName': volume.CLASS,
            'resources': {'requests': {'storage': '14Gi'}}})]
    objects[-1]['metadata']['annotations'] = {'argocd.argoproj.io/sync-options': 'Prune=confirm'}
    stateful = resource('StatefulSet', api='apps/v1', spec={'replicas': 1, 'serviceName': NAME,
        'selector': {'matchLabels': labels}, 'updateStrategy': {'type': 'OnDelete'},
        'template': {'metadata': {'labels': LABELS.copy()}, 'spec': spec}})
    policy = admission.image_policy(NS, [selected['image']], selected['repository_revision'])
    policy['metadata'].update(name='hooshix-kafka-source', labels=LABELS.copy())
    policy['metadata'].pop('namespace', None)
    policy['spec']['validations'][2] = admission.validation('Kafka source build identity rejected',
        provenance_expression(source_predicate(candidate_pin(), selected['repository_revision'],
            'github:' + selected['publication_run'] + ':' + str(selected['publication_attempt']))))
    sources = admission.split_image_policy(policy)
    for item in sources:
        item['metadata']['name'] = item['metadata']['name'].replace('platform-data-supply-chain', 'kafka-source')
        for check in [*item['spec']['variables'], *item['spec']['validations']]:
            for field in ('expression', 'messageExpression'):
                if field in check: check[field] = check[field].replace('images.containers', 'variables.kafkaImages')
        item['spec']['variables'].insert(0, {'name': 'kafkaImages', 'expression':
            '(images.containers + images.?initContainers.orValue([]) + images.?ephemeralContainers.orValue([]))'
            '.filter(image, image == ' + admission.literal(selected['image']) + ')'})
    root = Path(__file__).resolve().parents[2]
    authority = json.loads((root / 'scripts/production/offline-ca/root-authority.json').read_bytes())
    ca_sha256 = authority['existing_root_exception']['certificate_file_sha256']
    if len(ca_sha256) != 64 or any(c not in '0123456789abcdef' for c in ca_sha256):
        raise ValueError('PINNED_PRODUCTION_ROOT_REQUIRED')
    return {'schema_version': 1, 'installation_id': 'hooshix-production', 'profile': 'production-single-server',
        'image': selected['image'], 'repository_revision': selected['repository_revision'],
        'publication_run': selected['publication_run'], 'publication_attempt': selected['publication_attempt'],
        'publication_sha256': selected['publication_sha256'], 'image_configuration_digest': selected['image_configuration_digest'],
        'recipe_sha256': selected['recipe_sha256'], 'dockerfile_sha256': selected['dockerfile_sha256'],
        'public_ca_sha256': ca_sha256, 'objects': objects, 'statefulset': stateful, 'source_policies': sources,
        'previous_boundary': previous,
        'boundary': boundary(previous, spec, BOUNDARY_UID_PLACEHOLDER),
        'public_access': 'CLOSED', 'application_ingress': 'CLOSED', 'production_readiness': 'Not verified'}
