"""Empty database and admission/reentry negatives in the caller-owned disposable fixture."""
import base64
import copy
import json
import re
import traceback
from unittest.mock import patch

import install_postgresql_local_volume as volume
import install_postgresql_quarantine as installer
import render_postgresql_quarantine as renderer
import render_postgresql_secrets as secrets
from rehearse_openbao_recovery import RehearsalFailed
from rehearse_cert_manager import network_negative
from rehearse_cnpg_control_plane import failure_classes
from rehearse_platform_admission import admission_result, apply, policy_ready
from upgrade_mesh_security import replace_policy


def private_logs(k, pods, initdb_logs):
    # Disposable credential only; no production Secret read or raw log output.
    secret = json.loads(k('-n', renderer.NAMESPACE, 'get', 'secret', 'identity-db-migration', '-o', 'json'))
    encoded = secret['data']['password'].encode()
    password = base64.b64decode(encoded, validate=True)
    if len(password) < 20: raise ValueError('STRONG_SYNTHETIC_PRIVACY_CANARY_REQUIRED')
    if password in initdb_logs or encoded in initdb_logs: raise ValueError('QUARANTINE_LOG_PRIVACY_REJECTED')
    for pod in pods:
        for container in (*pod['spec'].get('initContainers', []), *pod['spec']['containers']):
            logs = k('-n', renderer.NAMESPACE, 'logs', pod['metadata']['name'], '-c', container['name'], '--tail=-1')
            if password in logs or encoded in logs: raise ValueError('QUARANTINE_LOG_PRIVACY_REJECTED')


def rehearse(k, wait, run, chart, helm, addresses, platform):
    initdb_pod, initdb_logs = None, None
    def adapter(*args, body=None, **options):
        nonlocal initdb_pod, initdb_logs
        if 'wait' in args and 'cluster/postgresql' in args and initdb_pod is None:
            def initial_pod():
                pods = json.loads(k('-n', renderer.NAMESPACE, 'get', 'pods', '-l', 'cnpg.io/cluster=postgresql', '-o', 'json'))['items']
                return next((p for p in pods if p['metadata']['name'].startswith('postgresql-1-initdb-')), None)
            initdb_pod = wait(initial_pod, seconds=60)
            # Keep the synthetic stream in memory before CNPG removes the completed Job Pod.
            initdb_logs = k('-n', renderer.NAMESPACE, 'logs', initdb_pod['metadata']['name'], '-c', 'initdb',
                '--follow', '--pod-running-timeout=180s', timeout=210)
        return k(*args, data=json.dumps(body).encode() if body is not None else None, **options)
    previous = secrets.render(chart, helm, addresses)['boundary']
    plan = renderer.render(previous, addresses)
    nodes = json.loads(k('get', 'nodes', '-o', 'json'))['items']
    if len(nodes) != 1: raise ValueError('SINGLE_OWNED_FIXTURE_NODE_REQUIRED')
    node = nodes[0]['metadata']['name']
    if not re.fullmatch('hooshix-bao-ci-[a-f0-9]{12}-control-plane', node):
        raise ValueError('CALLER_OWNED_KIND_NODE_REQUIRED')
    info = json.loads(run(['docker', 'inspect', node]))[0]
    if info['Config']['Labels'].get('io.x-k8s.kind.cluster') != node.removesuffix('-control-plane'):
        raise ValueError('OWNED_DISPOSABLE_CONTAINER_REQUIRED')
    pv = installer.get(adapter, 'pv', volume.VOLUME)
    if not volume.matches(pv, volume.candidate()[1]) or installer.get(adapter, 'pvc', volume.CLAIM, renderer.NAMESPACE):
        raise ValueError('ONLY_UNUSED_METADATA_PLACEHOLDER_MAY_BE_REPLACED')
    # The earlier controller-only fixture reserves metadata, not storage. Never relabel its OpenBao node.
    k('delete', 'pv', volume.VOLUME, '--wait=true', '--timeout=30s')
    run(['docker', 'exec', node, '/bin/sh', '-ec',
        'test ! -e /var/lib/hooshixstorage/postgresql/data && '
        'mkdir -p /var/lib/hooshixstorage/postgresql/data && '
        'chown 26:26 /var/lib/hooshixstorage/postgresql/data && chmod 0700 /var/lib/hooshixstorage/postgresql/data'])
    plan['cluster']['spec']['affinity']['nodeSelector']['kubernetes.io/hostname'] = node
    with patch.object(volume, 'NODE', node):
        apply(k, volume.candidate()[1])
        print('POSTGRESQL_QUARANTINE_NATIVE_STEP=empty-bootstrap', flush=True)
        try:
            result = installer.execute(plan, kube=adapter, guard=lambda **_: {})
            again = installer.execute(plan, kube=adapter, guard=lambda **_: {})
        except (RehearsalFailed, ValueError, KeyError, IndexError) as error:
            frames = [{'function': f.name, 'line': f.lineno} for f in traceback.extract_tb(error.__traceback__)
                      if f.filename.endswith(('/install_postgresql_quarantine.py', '/rehearse_postgresql_quarantine.py'))]
            print('POSTGRESQL_QUARANTINE_FIXED_FAILURE=' + json.dumps({'type': type(error).__name__, 'frames': frames}), flush=True)
            # Fixed vocabulary only; no container logs or Secret bodies are emitted.
            for kind in ('clusters', 'pvc', 'jobs', 'pods'):
                objects = json.loads(k('-n', renderer.NAMESPACE, 'get', kind, '-o', 'json'))['items']
                print('POSTGRESQL_QUARANTINE_STATES=' + json.dumps([{
                    'kind': kind, 'name': o['metadata']['name'],
                    'phase': o.get('status', {}).get('phase'),
                    'conditions': [{'type': c['type'], 'status': c['status'], 'reason': c.get('reason')}
                                   for c in o.get('status', {}).get('conditions', [])]} for o in objects]), flush=True)
            logs = k('-n', 'cnpg-system', 'logs', 'deployment/cnpg-controller-manager', '--tail=100', '--limit-bytes=65536')
            print('POSTGRESQL_QUARANTINE_LOG_CLASSES=' + json.dumps([m.decode() for m in failure_classes(logs)]), flush=True)
            events = json.loads(k('-n', renderer.NAMESPACE, 'get', 'events', '-o', 'json'))['items']
            messages = '\n'.join(e.get('message', '') for e in events)
            print('POSTGRESQL_QUARANTINE_EVENT_CLASSES=' + json.dumps([m for m in (
                'quarantined PostgreSQL contract rejected', 'failed to evaluate', 'no such key', 'no such overload',
                'signature', 'PodSecurity', 'Insufficient memory', 'Insufficient cpu', 'volume node affinity conflict',
                'unbound immediate PersistentVolumeClaims') if m in messages]), flush=True)
            raise
    if any(result[k] != again[k] for k in ('cluster_uid', 'pvc_uid', 'volume_uid')):
        raise ValueError('QUARANTINE_REENTRY_REPLACED_DATA_IDENTITY')
    result['idempotent_reconciliation'] = 'Passed'
    pods = json.loads(k('-n', renderer.NAMESPACE, 'get', 'pods', '-l', 'cnpg.io/cluster=postgresql', '-o', 'json'))['items']
    if initdb_pod is None or initdb_logs is None: raise ValueError('REAL_INITDB_PRIVACY_AND_POD_EVIDENCE_REQUIRED')
    private_logs(k, pods, initdb_logs)
    result['container_log_privacy'] = 'Passed'
    if not any(p['metadata']['uid'] == initdb_pod['metadata']['uid'] for p in pods): pods.append(initdb_pod)
    # Exercise both the real operator-generated instance and initdb Job Pod contracts.
    for pod in pods:
        obj = {key: pod[key] for key in ('apiVersion', 'kind', 'spec')}
        obj['metadata'] = {key: pod['metadata'][key] for key in ('name', 'namespace', 'labels', 'ownerReferences')}
        admission_result(k, obj, wait, public_schema=False)
        for mode in ('account', 'image', 'command', 'ambient', 'privilege', 'secret-volume', 'env', 'host-path'):
            bad = copy.deepcopy(obj)
            if mode == 'account': bad['spec']['serviceAccountName'] = 'default'
            if mode == 'image': bad['spec']['containers'][0]['image'] = bad['spec']['containers'][0]['image'].split('@')[0] + ':latest'
            if mode == 'command': bad['spec']['containers'][0]['command'] = ['/bin/sh']
            if mode == 'ambient': bad['metadata']['labels']['istio.io/dataplane-mode'] = 'none'
            if mode == 'privilege': bad['spec']['containers'][0]['securityContext']['privileged'] = True
            if mode == 'secret-volume': bad['spec']['volumes'].append({'name': 'foreign', 'secret': {'secretName': 'unapproved'}})
            if mode == 'host-path': bad['spec']['volumes'][0] = {'name': 'pgdata', 'hostPath': {'path': '/etc'}}
            if mode == 'env': bad['spec']['containers'][0]['env'].append({'name': 'PGOPTIONS', 'value': '-c log_statement=all'})
            admission_result(k, bad, wait, expected=1, public_schema=False)
    result['pod_contract_positive_negative'] = 'Passed'
    pod = next(p for p in pods if p['metadata']['name'] == 'postgresql-1')
    obj = {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {k: pod['metadata'][k]
        for k in ('name', 'namespace', 'labels', 'ownerReferences')}, 'spec': pod['spec']}
    for original in plan['source_policies']:
        wrong = copy.deepcopy(original)
        name = original['metadata']['name']
        # The exact operator image is exercised by the same Pod's init container.
        if name.endswith('-signature'):
            wrong['spec']['attestors'][0]['cosign']['keyless']['identities'][0]['subject'] = 'https://wrong.invalid'
        elif name.endswith('-sbom'):
            wrong['spec']['attestations'][0]['intoto']['type'] = 'https://missing.invalid/sbom'
        else:
            revision = renderer.candidate()['publication']['repository_revision']
            wrong['spec']['validations'][-1]['expression'] = wrong['spec']['validations'][-1]['expression'].replace(revision, '0' * 40)
        if wrong == original: raise ValueError('QUARANTINE_SOURCE_NEGATIVE_REQUIRED')
        try:
            replace_policy(wrong, get=lambda kind, name: installer.get(adapter, kind, name), kube=adapter, previous=original)
            admission_result(k, obj, wait, expected=1, public_schema=False)
        finally:
            replace_policy(original, get=lambda kind, name: installer.get(adapter, kind, name), kube=adapter, previous=wrong)
            policy_ready(k, original)
        admission_result(k, obj, wait, public_schema=False)
    result['source_identity_positive_negative'] = 'Passed'
    # A different Pod must be the source: self-Pod traffic is not NetworkPolicy isolation evidence.
    network_negative(k, wait, platform, selector='cnpg.io/cluster=postgresql', port=5432, namespace=renderer.NAMESPACE)
    result['service_sql_network_denied'] = 'Passed'
    result['guard'] = 'Not run'
    result['host_storage_guard'] = 'Not run; disposable node path; separate filesystem guard rehearsal required'
    return result
