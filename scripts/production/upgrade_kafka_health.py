"""Exact, journaled old-to-current Kafka liveness migration; retained data and closed access only."""
import copy
import hashlib
import json

import install_kafka_client_tls as delivery
from upgrade_mesh_security import replace_policy

runtime = delivery.runtime
JOURNAL = 'hooshix-kafka-health-v1'
MANAGER = 'hooshix-kafka-health'
require = runtime.require


def fingerprint(value):
    def encode_bytes(item):
        if not isinstance(item, bytes):
            raise TypeError('KAFKA_HEALTH_SNAPSHOT_TYPE_REJECTED')
        return {'bytes_sha256': hashlib.sha256(item).hexdigest()}
    return hashlib.sha256(json.dumps(value, sort_keys=True, default=encode_bytes).encode()).hexdigest()


def preserved(kube, plan):
    ca = plan['public_ca_sha256']
    return [runtime.tls.preserved(kube, ca), runtime.node_secret(kube, ca),
            runtime.binding(kube, runtime.get(kube, 'pvc', runtime.volume.CLAIM, runtime.NS))]


def patch_spec(kube, current, spec):
    require(current['metadata'].get('annotations', {}).get(runtime.HASH) == runtime.digest(current['spec']),
            'KAFKA_HEALTH_SPEC_HASH_REQUIRED')
    kube('-n', runtime.NS, 'patch', 'statefulset', runtime.NAME, '--type=json', '-p', json.dumps([
        {'op': 'test', 'path': '/metadata/uid', 'value': current['metadata']['uid']},
        {'op': 'test', 'path': '/spec', 'value': current['spec']},
        {'op': 'replace', 'path': '/spec', 'value': spec},
        {'op': 'replace', 'path': '/metadata/annotations/' + runtime.HASH.replace('/', '~1'),
         'value': runtime.digest(spec)}]))


def execute(plan, *, kube):
    legacy = plan.get('kafka_health_upgrade')
    if legacy is None:
        return 'Not run'
    desired = plan['kafka_runtime']
    expected_legacy = copy.deepcopy(desired)
    container = expected_legacy['statefulset']['spec']['template']['spec']['containers'][0]
    old_command = container['startupProbe']['exec']['command']
    new_command = container['livenessProbe']['exec']['command']
    require(old_command[:2] == new_command[:2] == ['/bin/sh', '-ec']
        and len(old_command) == len(new_command) == 3, 'KAFKA_HEALTH_EXACT_TRANSITION_REQUIRED')
    container['livenessProbe']['exec']['command'] = copy.deepcopy(old_command)
    count = 0
    for check in expected_legacy['boundary']['spec']['validations']:
        count += check['expression'].count(json.dumps(new_command[2]))
        check['expression'] = check['expression'].replace(json.dumps(new_command[2]), json.dumps(old_command[2]))
    require(count == 2 and legacy == expected_legacy, 'KAFKA_HEALTH_EXACT_TRANSITION_REQUIRED')
    current = runtime.get(kube, 'statefulset', runtime.NAME, runtime.NS)
    require(current and current['spec'].get('replicas') in (0, 1)
        and not current['spec'].get('volumeClaimTemplates'), 'KAFKA_HEALTH_EXISTING_BROKER_REQUIRED')
    old_probe = legacy['statefulset']['spec']['template']['spec']['containers'][0]['livenessProbe']
    new_probe = desired['statefulset']['spec']['template']['spec']['containers'][0]['livenessProbe']
    actual_probe = current['spec']['template']['spec']['containers'][0]['livenessProbe']
    require(actual_probe in (old_probe, new_probe), 'KAFKA_HEALTH_FOREIGN_PROBE_PRESERVED')
    selected = legacy if actual_probe == old_probe else desired
    runtime.owned(current, selected['statefulset'])
    boundaries = [runtime.boundary_for_statefulset(value, current['metadata']['uid']) for value in (legacy, desired)]
    boundary = runtime.get(kube, boundaries[0]['kind'], boundaries[0]['metadata']['name'])
    require(boundary and any(boundary['spec'] == value['spec'] for value in boundaries)
        and boundary.get('status', {}).get('conditionStatus', {}).get('ready') is True,
        'KAFKA_HEALTH_FOREIGN_BOUNDARY_PRESERVED')
    old = runtime.get(kube, 'configmap', JOURNAL, runtime.NS)
    pod = runtime.get(kube, 'pod', runtime.NAME + '-0', runtime.NS)
    if actual_probe == new_probe and current['spec']['replicas'] == 1 and old is None:
        require(pod and pod['spec']['containers'][0]['livenessProbe'] == new_probe,
                'KAFKA_HEALTH_UNJOURNALED_TRANSITION_PRESERVED')
        delivery.snapshot(kube, plan)
        return 'Not applicable; current health contract'
    retained = fingerprint(preserved(kube, plan))
    expected = {'statefulset_uid': current['metadata']['uid'],
        'pvc_uid': runtime.get(kube, 'pvc', runtime.volume.CLAIM, runtime.NS)['metadata']['uid'],
        'contract_sha256': fingerprint([legacy, desired]), 'retained_sha256': retained}
    if old is None:
        require(actual_probe == old_probe and current['spec']['replicas'] == 1
            and boundary['spec'] == boundaries[0]['spec'], 'KAFKA_HEALTH_UNJOURNALED_TRANSITION_PRESERVED')
        delivery.snapshot(kube, {**plan, 'kafka_runtime': legacy})
        expected['cluster_identity_sha256'] = runtime.storage_identity(kube)[1]
        old = {'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': JOURNAL, 'namespace': runtime.NS,
            'labels': {'app.kubernetes.io/managed-by': MANAGER}}, 'immutable': True, 'data': expected}
        kube('create', '-f', '-', body=old)
        old = runtime.get(kube, 'configmap', JOURNAL, runtime.NS)
    require(old and old.get('immutable') is True and not old['metadata'].get('deletionTimestamp')
        and not old['metadata'].get('ownerReferences')
        and old['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == MANAGER
        and set(old.get('data', {})) == {*expected, 'cluster_identity_sha256'}
        and all(old['data'].get(k) == v for k, v in expected.items()), 'KAFKA_HEALTH_FOREIGN_JOURNAL_PRESERVED')
    needs_replacement = actual_probe != new_probe or (pod and
        pod['spec']['containers'][0]['livenessProbe'] != new_probe)
    if needs_replacement or current['spec']['replicas'] == 0:
        runtime.pod_inventory(kube, current)
        if current['spec']['replicas'] == 1:
            spec = copy.deepcopy(current['spec']); spec['replicas'] = 0
            patch_spec(kube, current, spec)
        if pod:
            kube('-n', runtime.NS, 'wait', '--for=delete', 'pod/' + runtime.NAME + '-0',
                 '--timeout=120s', timeout=135)
        current = runtime.get(kube, 'statefulset', runtime.NAME, runtime.NS)
        require(current['spec']['replicas'] == 0, 'KAFKA_HEALTH_QUIESCENT_BROKER_REQUIRED')
        replace_policy(boundaries[1], get=lambda kind, name: runtime.get(kube, kind, name),
                       kube=kube, previous=boundaries[0])
        if actual_probe != new_probe:
            spec = copy.deepcopy(current['spec'])
            spec['template']['spec']['containers'][0]['livenessProbe'] = copy.deepcopy(new_probe)
            patch_spec(kube, current, spec)
            current = runtime.get(kube, 'statefulset', runtime.NAME, runtime.NS)
        runtime.owned(current, desired['statefulset'])
        runtime.preflight_admission(kube, current)
        runtime.activate_statefulset(kube, current)
    kube('-n', runtime.NS, 'wait', '--for=jsonpath={.status.readyReplicas}=1',
         'statefulset/' + runtime.NAME, '--timeout=420s', timeout=435)
    delivery.snapshot(kube, plan)
    require(fingerprint(preserved(kube, plan)) == retained
        and runtime.storage_identity(kube)[1] == old['data']['cluster_identity_sha256'],
        'KAFKA_HEALTH_RETAINED_IDENTITY_CHANGED')
    return 'Passed'
