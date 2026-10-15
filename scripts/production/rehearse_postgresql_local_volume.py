"""Static reservation API/mount/retention fixture in the owned disposable kind node."""
import copy
import hashlib
import json
from pathlib import Path
from unittest.mock import patch

import install_postgresql_local_volume as volume


def exercise(k, run, wait, node, pin_image):
    namespace = 'hooshix-pg-local-volume-ci'
    path = '/tmp/hooshix-pg-local-volume-ci'
    objects = volume.candidate()
    objects[0]['metadata']['name'] = 'hooshix-pg-local-ci'
    pv = objects[1]
    pv['metadata']['name'] = 'hooshix-pg-local-ci'
    pv['spec']['storageClassName'] = objects[0]['metadata']['name']
    pv['spec']['capacity']['storage'] = '16Mi'
    pv['spec']['local']['path'] = path
    pv['spec']['claimRef']['namespace'] = namespace
    pv['spec']['nodeAffinity']['required']['nodeSelectorTerms'][0]['matchExpressions'][0]['values'] = [node]
    def call(*args, body=None):
        return k(*args, data=json.dumps(body).encode() if body is not None else None)
    print('POSTGRESQL_LOCAL_VOLUME_TEST=fixture', flush=True)
    run(['docker', 'exec', node, 'mkdir', '-m', '0700', path])
    run(['docker', 'exec', node, 'chown', '26:26', path])
    # Let CRI pull the exact digest; Docker archive imports can create incompatible digest aliases.
    call('create', '-f', '-', body={'apiVersion': 'v1', 'kind': 'Namespace', 'metadata': {
        'name': namespace, 'labels': {'pod-security.kubernetes.io/enforce': 'restricted'}}})
    with patch.object(volume, 'candidate', return_value=objects):
        print('POSTGRESQL_LOCAL_VOLUME_TEST=reservation', flush=True)
        volume.reserve(objects, call)
        volume.reserve(objects, call)
        wrong = copy.deepcopy(objects)
        wrong[1]['spec']['local']['path'] = '/wrong'
        with patch.object(volume, 'candidate', return_value=wrong):
            try:
                volume.reserve(wrong, call)
            except ValueError:
                pass
            else:
                raise ValueError('NATIVE_VOLUME_DRIFT_ADOPTED')
    claim = {'apiVersion': 'v1', 'kind': 'PersistentVolumeClaim', 'metadata': {
        'name': 'wrong-claim', 'namespace': namespace}, 'spec': {'accessModes': ['ReadWriteOnce'],
        'storageClassName': objects[0]['metadata']['name'], 'volumeName': pv['metadata']['name'],
        'resources': {'requests': {'storage': '16Mi'}}}}
    call('create', '-f', '-', body=claim)
    print('POSTGRESQL_LOCAL_VOLUME_TEST=wrong-claim', flush=True)
    # Binding controller must reconcile the conflicting request and report a real denial.
    wait(lambda: any(e.get('reason') == 'FailedBinding' for e in json.loads(call('-n', namespace,
        'get', 'events', '--field-selector=involvedObject.name=wrong-claim', '-o', 'json'))['items']))
    wrong = json.loads(call('-n', namespace, 'get', 'pvc', 'wrong-claim', '-o', 'json'))
    if wrong['status'].get('phase') == 'Bound':
        raise ValueError('NATIVE_CLAIM_RESERVATION_BYPASSED')
    claim['metadata']['name'] = volume.CLAIM
    print('POSTGRESQL_LOCAL_VOLUME_TEST=reserved-claim', flush=True)
    call('create', '-f', '-', body=claim)
    wait(lambda: json.loads(call('-n', namespace, 'get', 'pvc', volume.CLAIM, '-o', 'json'))
         .get('status', {}).get('phase') == 'Bound')
    pod = {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': 'volume-writer', 'namespace': namespace},
        'spec': {'restartPolicy': 'Never', 'automountServiceAccountToken': False,
            'securityContext': {'runAsNonRoot': True, 'runAsUser': 26, 'runAsGroup': 26,
                                'seccompProfile': {'type': 'RuntimeDefault'}},
            'containers': [{'name': 'fixture', 'image': pin_image,
                'command': ['/bin/sh', '-ec', 'printf synthetic-storage-fixture > /data/proof'],
                'securityContext': {'readOnlyRootFilesystem': True, 'allowPrivilegeEscalation': False,
                                    'capabilities': {'drop': ['ALL']}},
                'resources': {'requests': {'cpu': '10m', 'memory': '16Mi'},
                              'limits': {'cpu': '100m', 'memory': '64Mi'}},
                'volumeMounts': [{'name': 'data', 'mountPath': '/data'}]}],
            'volumes': [{'name': 'data', 'persistentVolumeClaim': {'claimName': volume.CLAIM}}]}}
    call('create', '-f', '-', body=pod)
    print('POSTGRESQL_LOCAL_VOLUME_TEST=mount', flush=True)
    wait(lambda: json.loads(call('-n', namespace, 'get', 'pod', 'volume-writer', '-o', 'json'))
         .get('status', {}).get('phase') == 'Succeeded', seconds=120)
    actual = json.loads(call('-n', namespace, 'get', 'pod', 'volume-writer', '-o', 'json'))
    if (actual['spec']['nodeName'] != node
            or not actual['status']['containerStatuses'][0]['imageID'].endswith(pin_image.split('@')[1])
            or run(['docker', 'exec', node, 'cat', path + '/proof']) != b'synthetic-storage-fixture'):
        raise ValueError('NATIVE_LOCAL_MOUNT_OR_AFFINITY_FAILED')
    call('-n', namespace, 'delete', 'pod', 'volume-writer', '--wait=true', '--timeout=30s')
    pod['metadata']['name'] = 'volume-reader'
    pod['spec']['containers'][0]['command'][-1] = 'test "$(cat /data/proof)" = synthetic-storage-fixture'
    call('create', '-f', '-', body=pod)
    wait(lambda: json.loads(call('-n', namespace, 'get', 'pod', 'volume-reader', '-o', 'json'))
         .get('status', {}).get('phase') == 'Succeeded')
    call('-n', namespace, 'delete', 'pod', 'volume-reader', '--wait=true', '--timeout=30s')
    call('-n', namespace, 'delete', 'pvc', volume.CLAIM, '--wait=true', '--timeout=30s')
    print('POSTGRESQL_LOCAL_VOLUME_TEST=retain', flush=True)
    wait(lambda: json.loads(call('get', 'pv', pv['metadata']['name'], '-o', 'json'))
         .get('status', {}).get('phase') == 'Released')
    if run(['docker', 'exec', node, 'cat', path + '/proof']) != b'synthetic-storage-fixture':
        raise ValueError('NATIVE_RETAIN_DATA_LOST')
    # All fixture objects/data die with the fresh owned kind cluster, never a target volume.
    return {'source_sha256': hashlib.sha256(Path(volume.__file__).read_bytes()).hexdigest(),
        'reservation_reentry_conflict': 'Passed', 'wrong_claim_denied': 'Passed',
        'node_affinity_and_mount': 'Passed', 'pvc_reuse_persistence': 'Passed', 'retain_after_claim_deletion': 'Passed'}
