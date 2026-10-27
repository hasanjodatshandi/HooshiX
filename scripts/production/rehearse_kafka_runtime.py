"""Exercise the exact closed Kafka Pod, TLS path, admission and retained data in staging."""
import copy
import hashlib
import json
import os
import re
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

import install_kafka_local_volume as volume
import install_kafka_runtime as installer
import install_kafka_tls as tls
import render_kafka_runtime as renderer
from rehearse_platform_admission import admission_result, apply, policy_ready
from rehearse_cert_manager import network_negative
from upgrade_mesh_security import replace_policy

SAFE_RUNTIME_FAILURE_CODES = frozenset({
    'ACTIVE_CLOSED_DATA_ADMISSION_REQUIRED', 'ADDITIONAL_KAFKA_MESH_ACCESS_REJECTED',
    'ADDITIONAL_KAFKA_NETWORK_ACCESS_REJECTED', 'CLOSED_KAFKA_RUNTIME_PLAN_REQUIRED',
    'KAFKA_ADMISSION_POSITIVE_PREFLIGHT_FAILED', 'KAFKA_ADMISSION_NEGATIVE_PREFLIGHT_FAILED',
    'KAFKA_EXISTING_POD_CONTRACT_REQUIRED',
    'CNPG_EXISTING_CERT_MANAGER_REQUIRED', 'EXACT_KAFKA_BINDING_REQUIRED',
    'EXACT_KAFKA_NODE_CHAIN_REQUIRED', 'EXISTING_CONTROLLERS_REQUIRED',
    'EXISTING_KAFKA_CLAIM_BINDING_REQUIRED', 'EXISTING_KAFKA_TLS_DELIVERY_REQUIRED',
    'EXISTING_REDIS_ACL_BINDING_REQUIRED', 'EXISTING_REDIS_CHAIN_REQUIRED',
    'EXISTING_REDIS_CREDENTIAL_REQUIRED', 'EXISTING_REDIS_ESO_DELIVERY_REQUIRED',
    'EXISTING_RESERVED_VOLUME_REQUIRED', 'EXISTING_STATIC_CLASS_REQUIRED',
    'FOREIGN_CLAIM_PRESERVED', 'FOREIGN_OR_CHANGED_KAFKA_RESOURCE_PRESERVED',
    'FOREIGN_OR_CHANGED_KAFKA_VOLUME_PRESERVED', 'FOREIGN_OR_CHANGED_REDIS_VOLUME_PRESERVED',
    'KAFKA_CLOSED_POLICY_REQUIRED', 'KAFKA_DEFAULTED_SPEC_DRIFT_PRESERVED',
    'KAFKA_EXISTING_REDIS_REQUIRED', 'KAFKA_ISOLATION_DRIFT', 'KAFKA_STRICT_AMBIENT_MTLS_REQUIRED',
    'KAFKA_TLS_BOUNDED_LIFETIME_REQUIRED', 'KAFKA_TLS_EXACT_MATERIAL_REQUIRED',
    'KAFKA_BOUNDARY_UID_TEMPLATE_REQUIRED', 'KAFKA_BOUNDARY_DRIFT_PRESERVED',
    'KAFKA_BOUNDARY_NOT_READY', 'KAFKA_DRY_RUN_POLICY_REJECTED',
    'KAFKA_FORMATTED_STORAGE_ID_REQUIRED', 'KAFKA_RESTART_CHANGED_RETAINED_IDENTITY',
    'KAFKA_STATEFULSET_REQUIRED', 'KAFKA_STATEFULSET_UID_REQUIRED',
    'KAFKA_TLS_LOG_PRIVACY_REQUIRED', 'KAFKA_WORKLOAD_AUTHORITY_REJECTED',
    'KAFKA_TLS_ISSUER_CHAIN_CONFLICT', 'KAFKA_TLS_KEY_CONFLICT',
    'KAFKA_TLS_NODE_CONSTRAINTS_REQUIRED', 'KAFKA_TLS_NODE_SUBJECT_REQUIRED',
    'KAFKA_TLS_PKCS8_REQUIRED', 'NATIVE_OPERATION_FAILED_STATE_PRESERVED',
    'PINNED_KAFKA_ROOT_REQUIRED', 'PINNED_REDIS_ROOT_REQUIRED',
    'REDIS_SECRET_EXISTING_QUARANTINE_REQUIRED', 'REDIS_SECRET_REVIEWED_VOLUME_REQUIRED',
    'REDIS_TLS_RAM_FILESYSTEM_REQUIRED', 'RESERVED_KAFKA_VOLUME_REQUIRED',
    'RESERVED_REDIS_VOLUME_REQUIRED', 'RESERVED_VOLUME_DRIFT_PRESERVED',
    'RETAIN_KAFKA_CLASS_REQUIRED', 'RETAIN_REDIS_CLASS_REQUIRED',
    'RESTRICTED_AMBIENT_KAFKA_NAMESPACE_REQUIRED', 'TEN_EXISTING_TYPED_SECRETS_REQUIRED',
    'ADDITIONAL_REDIS_ACCESS_REJECTED', 'CLOSED_REDIS_PLAN_REQUIRED',
    'EXISTING_TYPED_REDIS_SECRETS_REQUIRED', 'PREVIOUS_DATA_ADMISSION_CONFLICT',
    'REDIS_DEFAULTED_SPEC_DRIFT_PRESERVED', 'REDIS_DRY_RUN_POLICY_REJECTED',
    'REDIS_ISOLATION_DRIFT', 'RESTRICTED_AMBIENT_REQUIRED', 'UNRELAXED_STRICT_REQUIRED',
    'EMPTY_QUARANTINED_KAFKA_REQUIRED', 'ORPHANED_KAFKA_CLAIM_PRESERVED',
    'READY_OWNED_KAFKA_POD_REQUIRED', 'SINGLE_NODE_KAFKA_QUORUM_REQUIRED',
    'UNEXPECTED_KAFKA_POD_COUNT', 'UNOWNED_KAFKA_POD_PRESERVED',
    'BOUND_RETAIN_KAFKA_VOLUME_REQUIRED',
})
SAFE_PHASES = frozenset({'Pending', 'Running', 'Succeeded', 'Failed', 'Unknown'})
SAFE_STATES = frozenset({'waiting', 'running', 'terminated', 'unknown'})
SAFE_CONTAINER_REASONS = frozenset({
    'ContainerCannotRun', 'ContainerCreating', 'Completed', 'CreateContainerConfigError',
    'CreateContainerError', 'CrashLoopBackOff', 'DeadlineExceeded', 'ErrImagePull',
    'Error', 'ImagePullBackOff', 'OOMKilled', 'PodInitializing', 'RunContainerError',
    'StartError',
})
SAFE_EVENT_REASONS = frozenset({
    'BackOff', 'ErrImagePull', 'Failed', 'FailedAttachVolume', 'FailedCreate',
    'FailedCreatePodSandBox', 'FailedMount', 'FailedScheduling', 'ImagePullBackOff',
    'Killing', 'PodCompleted', 'Pulled', 'Scheduled', 'SandboxChanged', 'Started',
    'SuccessfulAttachVolume', 'SuccessfulCreate', 'SuccessfulMountVolume', 'Unhealthy',
})
SAFE_CREATE_FAILURE_CLASSES = frozenset({
    'ADMISSION_REJECTED', 'ADMISSION_WEBHOOK_UNAVAILABLE', 'POD_SECURITY_REJECTED',
    'REFERENCED_OBJECT_MISSING', 'RESOURCE_QUOTA_EXCEEDED', 'OTHER',
})
SAFE_KAFKA_ADMISSION_POLICIES = frozenset({
    'hooshix-platform-data-closed',
    'hooshix-kafka-source-signature',
    'hooshix-kafka-source-provenance', 'hooshix-kafka-source-sbom',
})
SAFE_KAFKA_ADMISSION_FAILURES = {
    'closed kafka runtime contract rejected': 'KAFKA_RUNTIME_BOUNDARY',
    'platform bootstrap identity/security exception rejected': 'PLATFORM_DATA_BOUNDARY',
    'platform image signature verification failed': 'KAFKA_IMAGE_SIGNATURE',
    'platform import source revision rejected': 'KAFKA_SOURCE_REVISION',
    'platform provenance signature verification failed': 'KAFKA_PROVENANCE_SIGNATURE',
    'platform signed sbom is not cyclonedx': 'KAFKA_SBOM_FORMAT',
    'platform signed sbom verification failed': 'KAFKA_SBOM_SIGNATURE',
    'kafka source build identity rejected': 'KAFKA_SOURCE_IDENTITY',
}


def additional_owner_reference(owner):
    # ownerReferences merges by UID; an identical duplicate never reaches admission.
    return owner | {'name': owner['name'] + '-additional', 'uid': owner['uid'] + '-additional',
                    'controller': False, 'blockOwnerDeletion': False}


def kafka_pod_ready(kube):
    pod = installer.get(kube, 'pod', renderer.NAME + '-0', renderer.NS)
    statuses = (pod or {}).get('status', {}).get('containerStatuses', [])
    return len(statuses) == 1 and statuses[0].get('ready') is True


def failed_create_class(message):
    """Classify a controller event without retaining or emitting its private message."""
    text = message[:4096].casefold() if isinstance(message, str) else ''
    if 'exceeded quota' in text or 'exceeded its quota' in text:
        result = 'RESOURCE_QUOTA_EXCEEDED'
    elif any(value in text for value in ('failed calling webhook', 'no endpoints available',
                                         'context deadline exceeded', 'connection refused')):
        result = 'ADMISSION_WEBHOOK_UNAVAILABLE'
    elif 'violates podsecurity' in text or 'pod security standard' in text:
        result = 'POD_SECURITY_REJECTED'
    elif 'not found' in text:
        result = 'REFERENCED_OBJECT_MISSING'
    elif any(value in text for value in ('forbidden', 'denied the request', 'admission rejected',
                                         'failed to evaluate policy')):
        result = 'ADMISSION_REJECTED'
    else:
        result = 'OTHER'
    return result if result in SAFE_CREATE_FAILURE_CLASSES else 'OTHER'


def readiness_snapshot(kube):
    """Return bounded Pod/StatefulSet failure classes; never retain API messages or logs."""
    def marker(value, allowed):
        return value if isinstance(value, str) and value in allowed else ('Other' if value else None)

    stateful = installer.get(kube, 'statefulset', renderer.NAME, renderer.NS)
    pod = installer.get(kube, 'pod', renderer.NAME + '-0', renderer.NS)
    pod_status = (pod or {}).get('status') or {}
    stateful_spec = (stateful or {}).get('spec') or {}
    stateful_metadata = (stateful or {}).get('metadata') or {}
    stateful_status = (stateful or {}).get('status') or {}
    states = []
    for status in pod_status.get('initContainerStatuses', []) + pod_status.get('containerStatuses', []):
        name = status.get('name')
        if name not in ('kafka', 'tls-material'):
            continue
        current = status.get('state', {})
        kind = next((value for value in ('waiting', 'running', 'terminated') if value in current), 'unknown')
        state = current.get(kind, {}) if kind != 'unknown' else {}
        item = {'name': name, 'ready': status.get('ready') is True,
                'restarts': status.get('restartCount') if type(status.get('restartCount')) is int else None,
                'state': marker(kind, SAFE_STATES),
                'reason': marker(state.get('reason'), SAFE_CONTAINER_REASONS)}
        if kind == 'terminated':
            item['exit_code'] = state.get('exitCode') if type(state.get('exitCode')) is int else None
        last_exit = ((status.get('lastState') or {}).get('terminated') or {}).get('exitCode')
        if type(last_exit) is int:
            item['last_exit_code'] = last_exit
        states.append(item)

    conditions = []
    for condition in pod_status.get('conditions', []):
        kind = condition.get('type')
        status = condition.get('status')
        if kind not in ('PodScheduled', 'Initialized', 'ContainersReady', 'Ready') or status not in ('True', 'False', 'Unknown'):
            continue
        conditions.append({'type': kind, 'status': status,
                           'reason': marker(condition.get('reason'), SAFE_EVENT_REASONS)})

    stateful_conditions = []
    for condition in stateful_status.get('conditions', []):
        status = condition.get('status')
        if condition.get('type') != 'ReplicaFailure' or status not in ('True', 'False', 'Unknown'):
            continue
        stateful_conditions.append({'type': 'ReplicaFailure', 'status': status,
                                    'reason': marker(condition.get('reason'), SAFE_EVENT_REASONS)})

    warning_events = {}
    for object_kind, object_name in (('Pod', renderer.NAME + '-0'), ('StatefulSet', renderer.NAME)):
        try:
            raw = kube('-n', renderer.NS, 'get', 'events', '--field-selector',
                       f'involvedObject.kind={object_kind},involvedObject.name={object_name},type=Warning',
                       '-o', 'json', output_bound=32768)
            events = json.loads(raw).get('items', [])
            warnings = [event for event in events if event.get('type') == 'Warning']
            reasons = Counter(marker(event.get('reason'), SAFE_EVENT_REASONS) or 'Other'
                              for event in warnings)
            failed_creates = [event for event in warnings if event.get('reason') == 'FailedCreate']
            failure_classes = Counter(failed_create_class(event.get('message')) for event in failed_creates)
            known_policies = {name for event in failed_creates if isinstance(event.get('message'), str)
                              for name in SAFE_KAFKA_ADMISSION_POLICIES
                              if name in event['message'][:4096].casefold()}
            known_failures = {marker for event in failed_creates if isinstance(event.get('message'), str)
                              for phrase, marker in SAFE_KAFKA_ADMISSION_FAILURES.items()
                              if phrase in event['message'][:4096].casefold()}
            warning_events[object_kind.lower()] = {
                'count': sum(reasons.values()),
                'reasons': [{'reason': reason, 'count': count}
                            for reason, count in sorted(reasons.items())[:12]],
                'failed_create_classes': [{'class': name, 'count': count}
                    for name, count in sorted(failure_classes.items())],
                'known_admission_policies': sorted(known_policies),
                'known_admission_failures': sorted(known_failures)}
        except Exception:
            warning_events[object_kind.lower()] = {'available': False}

    return {'statefulset': {'present': stateful is not None,
            'desired_replicas': stateful_spec.get('replicas')
                if type(stateful_spec.get('replicas')) is int else None,
            'generation': stateful_metadata.get('generation')
                if type(stateful_metadata.get('generation')) is int else None,
            'observed_generation': stateful_status.get('observedGeneration')
                if type(stateful_status.get('observedGeneration')) is int else None,
            'replicas': stateful_status.get('replicas') if type(stateful_status.get('replicas')) is int else None,
            'ready_replicas': stateful_status.get('readyReplicas')
                if type(stateful_status.get('readyReplicas')) is int else 0,
            'conditions': stateful_conditions},
        'pod': {'present': pod is not None, 'phase': marker(pod_status.get('phase'), SAFE_PHASES),
            'conditions': conditions, 'containers': states}, 'warning_events': warning_events}


def memory_snapshot(kube):
    raw = kube('-n', renderer.NS, 'exec', renderer.NAME + '-0', '-c', 'kafka', '--',
        '/bin/sh', '-ec', 'cat /sys/fs/cgroup/memory.events; '
        'cat /sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.max', output_bound=1024)
    result = {}
    for line in raw.decode().splitlines():
        fields = line.split()
        if len(fields) == 2 and fields[0] in ('low', 'high', 'max', 'oom', 'oom_kill', 'oom_group_kill'):
            if re.fullmatch('[0-9]{1,20}', fields[1]):
                result[fields[0]] = int(fields[1])
    numbers = raw.decode().splitlines()[-2:]
    if len(numbers) == 2 and all(re.fullmatch('[0-9]{1,20}', value) for value in numbers):
        result.update(current_bytes=int(numbers[0]), limit_bytes=int(numbers[1]))
    return result


def admission_policy_snapshot(kube):
    policy = installer.get(kube, 'validatingpolicy', 'hooshix-platform-data-closed')
    if policy is None:
        return {'present': False}
    metadata = policy.get('metadata', {})
    status = policy.get('status', {}).get('conditionStatus', {})
    conditions = []
    for condition in status.get('conditions', []):
        kind, value = condition.get('type'), condition.get('status')
        if kind not in ('WebhookConfigured', 'RBACPermissionsGranted') or value not in ('True', 'False', 'Unknown'):
            continue
        observed = condition.get('observedGeneration')
        reason = condition.get('reason')
        conditions.append({'type': kind, 'status': value,
            'reason': reason if reason in ('Succeeded', 'Failed') else 'Other',
            'observed_generation': observed if type(observed) is int else None})
    generation = metadata.get('generation')
    ready = status.get('ready')
    return {'present': True, 'generation': generation if type(generation) is int else None,
        'ready': ready if type(ready) is bool else None, 'conditions': conditions}


def safe_failure_code(error):
    code = error.args[0] if len(error.args) == 1 else None
    if isinstance(code, str) and code in SAFE_RUNTIME_FAILURE_CODES:
        return code
    if isinstance(error, installer.host.custody.BootstrapFailed) and isinstance(code, str) \
            and re.fullmatch('[A-Z][A-Z0-9_]{2,127}', code):
        return code
    return None


def safe_failure_type(error):
    error_type = type(error)
    module, name = error_type.__module__, error_type.__name__
    if re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,63}', name) and re.fullmatch(
            r'[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*){0,7}', module):
        return module + '.' + name
    return 'Other'


def safe_failure_location(error):
    frame = error.__traceback__
    while frame and frame.tb_next:
        frame = frame.tb_next
    if not frame:
        return 'Other'
    module = frame.tb_frame.f_globals.get('__name__', '')
    function = frame.tb_frame.f_code.co_name
    if (re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*){0,7}', module)
            and re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,63}', function)):
        return f'{module}.{function}:{frame.tb_lineno}'
    return 'Other'


def rehearse(k, wait, run, ca, node, platform, *, on_verified_runtime=None):
    if not re.fullmatch('hooshix-bao-ci-[a-f0-9]{12}-control-plane', node):
        raise ValueError('OWNED_KAFKA_RUNTIME_FIXTURE_REQUIRED')
    def kube(*args, body=None, **options):
        try:
            return k(*args, data=json.dumps(body).encode() if body is not None else None, **options)
        except Exception:
            if 'exec' in args and renderer.NAME + '-0' in args:
                operation = next((name for token, name in (
                    (renderer.PROBE, 'health'), ('.hooshix-runtime-marker', 'storage'),
                    ('kafka-metadata-quorum.sh', 'quorum'), ('kafka-topics.sh', 'topics'))
                    if any(token in part for part in args)), 'other')
                print('KAFKA_RUNTIME_EXEC_FAILURE=' + operation, flush=True)
                try:
                    memory = memory_snapshot(k)
                except Exception:
                    memory = {'available': False}
                print('KAFKA_RUNTIME_MEMORY_DIAGNOSIS=' + json.dumps(memory, sort_keys=True), flush=True)
            if ('wait' in args and 'statefulset/' + renderer.NAME in args
                    or 'exec' in args and renderer.NAME + '-0' in args):
                try:
                    diagnosis = readiness_snapshot(k)
                except Exception:
                    diagnosis = {'available': False}
                print('KAFKA_RUNTIME_READINESS_DIAGNOSIS=' + json.dumps(
                    diagnosis, separators=(',', ':'), sort_keys=True), flush=True)
            raise

    previous = json.loads(k('get', 'validatingpolicy', 'hooshix-platform-data-closed', '-o', 'json'))
    previous = {key: previous[key] for key in ('apiVersion', 'kind', 'metadata', 'spec')}
    # This path exists only inside the newly created CI control-plane container.
    run(['docker', 'exec', node, '/bin/sh', '-ec',
        'test ! -e /var/lib/hooshixstorage/kafka/data && '
        'mkdir -p /var/lib/hooshixstorage/kafka/data && '
        'chown 10001:10001 /var/lib/hooshixstorage/kafka/data && '
        'chmod 0700 /var/lib/hooshixstorage/kafka/data'])
    with patch.object(volume, 'NODE', node), patch.object(installer.volume, 'NODE', node), \
            patch.object(tls.redis.volume, 'NODE', node), patch.object(tls.redis.pg.volume, 'NODE', node):
        volume.reserve(volume.candidate(), kube)
        # Exercise the exact retained target profile, then its bounded health migration.
        plan = renderer.render(previous, legacy=True)
        plan['repository_source_revision'] = os.environ['GITHUB_SHA']
        plan['public_ca_sha256'] = hashlib.sha256(ca.encode()).hexdigest()
        plan['staging'] = {'source_revision': os.environ['GITHUB_SHA'],
            'run_id': os.environ['GITHUB_RUN_ID'], 'observed_at': datetime.now(timezone.utc).isoformat(),
            'result': 'Passed'}
        options = {'kube': kube, 'guard': lambda **_: {},
            'preserve': lambda call, _digest: tls.preserved(call, plan['public_ca_sha256'])}
        print('KAFKA_RUNTIME_NATIVE_STEP=closed-start', flush=True)
        try:
            result = installer.execute(plan, **options)
        except Exception as error:
            code = safe_failure_code(error)
            if code:
                print('KAFKA_RUNTIME_PREFLIGHT_FAILURE=' + code, flush=True)
                if code in ('KAFKA_ADMISSION_POSITIVE_PREFLIGHT_FAILED',
                            'KAFKA_ADMISSION_NEGATIVE_PREFLIGHT_FAILED'):
                    try:
                        diagnosis = admission_policy_snapshot(kube)
                    except Exception:
                        diagnosis = {'available': False}
                    print('KAFKA_RUNTIME_ADMISSION_DIAGNOSIS=' + json.dumps(
                        diagnosis, separators=(',', ':'), sort_keys=True), flush=True)
            else:
                print('KAFKA_RUNTIME_PREFLIGHT_FAILURE_TYPE=' + safe_failure_type(error), flush=True)
                print('KAFKA_RUNTIME_PREFLIGHT_FAILURE_AT=' + safe_failure_location(error), flush=True)
            raise
        first_identity = installer.storage_identity(kube)
        first_claim = installer.get(kube, 'pvc', volume.CLAIM, renderer.NS)['metadata']['uid']
        again = installer.execute(plan, **options)
        if any(result[key] != again[key] for key in ('statefulset_uid', 'pvc_uid', 'volume_uid')):
            raise ValueError('KAFKA_RUNTIME_REENTRY_REPLACED_IDENTITY')
        if first_identity != installer.storage_identity(kube):
            raise ValueError('KAFKA_RUNTIME_REENTRY_CHANGED_DATA')
        result['idempotent_reconciliation'] = 'Passed'

        actual = json.loads(k('-n', renderer.NS, 'get', 'pod', renderer.NAME + '-0', '-o', 'json'))
        pod = {key: actual[key] for key in ('apiVersion', 'kind', 'metadata', 'spec')}
        pod['metadata'] = {key: actual['metadata'][key] for key in ('name', 'namespace', 'labels', 'ownerReferences')}
        print('KAFKA_RUNTIME_POD_ADMISSION=positive', flush=True)
        admission_result(k, pod, wait, public_schema=False)
        for mode in ('account', 'image', 'privilege', 'ambient', 'secret-source', 'host-path',
                     'lifecycle', 'env-from', 'extra-container', 'owner-reference', 'owner-uid'):
            bad = copy.deepcopy(pod)
            container = bad['spec']['containers'][0]
            if mode == 'account': bad['spec']['serviceAccountName'] = 'default'
            if mode == 'image': container['image'] = container['image'].split('@')[0] + ':latest'
            if mode == 'privilege': container['securityContext']['privileged'] = True
            if mode == 'ambient': bad['metadata']['labels']['istio.io/dataplane-mode'] = 'none'
            if mode == 'secret-source': bad['spec']['volumes'][1] = {'name': 'source', 'hostPath': {'path': '/etc'}}
            if mode == 'host-path': bad['spec']['volumes'][0]['hostPath'] = {'path': '/etc'}
            if mode == 'lifecycle': container['lifecycle'] = {'postStart': {'exec': {'command': ['/bin/true']}}}
            if mode == 'env-from': container['envFrom'] = [{'secretRef': {'name': 'unapproved'}}]
            if mode == 'extra-container':
                extra = copy.deepcopy(container)
                extra['name'] = 'unreviewed'
                bad['spec']['containers'].append(extra)
            if mode == 'owner-reference':
                bad['metadata']['ownerReferences'].append(
                    additional_owner_reference(bad['metadata']['ownerReferences'][0]))
            if mode == 'owner-uid':
                bad['metadata']['ownerReferences'][0]['uid'] = 'foreign-statefulset-uid'
            print('KAFKA_RUNTIME_POD_ADMISSION_NEGATIVE=' + mode, flush=True)
            if mode in ('owner-reference', 'owner-uid'):
                k('-n', renderer.NS, 'patch', 'pod', pod['metadata']['name'], '--type=merge',
                  '-p', json.dumps({'metadata': {'ownerReferences': bad['metadata']['ownerReferences']}}),
                  '--dry-run=server', expected=1, public_schema=False,
                  required_error=b'closed Kafka runtime contract rejected')
            else:
                admission_result(k, bad, wait, expected=1, public_schema=False)
        result['pod_contract_positive_negative'] = 'Passed'

        for original in plan['source_policies']:
            wrong = copy.deepcopy(original)
            if original['metadata']['name'].endswith('-signature'):
                wrong['spec']['attestors'][0]['cosign']['keyless']['identities'][0]['subject'] = 'https://wrong.invalid'
            elif original['metadata']['name'].endswith('-sbom'):
                wrong['spec']['attestations'][0]['intoto']['type'] = 'https://missing.invalid/sbom'
            else:
                wrong['spec']['validations'][-1]['expression'] = wrong['spec']['validations'][-1]['expression'].replace(
                    renderer.pin()['repository_revision'], '0' * 40)
            if wrong == original:
                raise ValueError('KAFKA_SOURCE_NEGATIVE_REQUIRED')
            try:
                replace_policy(wrong, get=lambda kind, name: installer.get(kube, kind, name),
                               kube=kube, previous=original)
                admission_result(k, pod, wait, expected=1,
                    message=original['metadata']['name'].encode(), public_schema=False)
            finally:
                replace_policy(original, get=lambda kind, name: installer.get(kube, kind, name),
                               kube=kube, previous=wrong)
                policy_ready(k, original)
            admission_result(k, pod, wait, public_schema=False)
        result['source_identity_positive_negative'] = 'Passed'

        # A real controller restart proves the local volume is reused, never formatted again.
        k('-n', renderer.NS, 'delete', 'pod', renderer.NAME + '-0', '--wait=true', '--timeout=60s', timeout=70)
        wait(lambda: kafka_pod_ready(kube), seconds=installer.READINESS_TIMEOUT_SECONDS)
        recovered = installer.execute(plan, **options)
        if (recovered['pvc_uid'] != first_claim or recovered['statefulset_uid'] != result['statefulset_uid']
                or installer.storage_identity(kube) != first_identity):
            raise ValueError('KAFKA_RESTART_CHANGED_RETAINED_IDENTITY')
        result['retained_volume_process_restart'] = 'Passed'
        network_negative(k, wait, platform, selector='app.kubernetes.io/name=kafka',
                         port=9092, namespace=renderer.NS)
        result['network_and_mesh_policy'] = 'Passed'
        result['host_storage_guard'] = 'Not run; disposable local path'
        result['application_ingress'] = 'CLOSED'
        if on_verified_runtime is not None:
            on_verified_runtime(copy.deepcopy(plan))
        return result
