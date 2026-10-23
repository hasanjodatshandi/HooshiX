"""Exercise the exact closed Kafka Pod, TLS path, admission and retained data in staging."""
import copy
import hashlib
import json
import os
import re
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
    'KAFKA_BOUNDARY_UID_TEMPLATE_REQUIRED', 'KAFKA_DRY_RUN_POLICY_REJECTED',
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
SAFE_RUNTIME_EXCEPTION_TYPES = frozenset({
    'AttributeError', 'BootstrapFailed', 'CalledProcessError', 'IndexError', 'JSONDecodeError', 'KeyError',
    'OSError', 'PermissionError', 'RuntimeError', 'TimeoutExpired', 'TypeError', 'ValueError',
})


def safe_failure_code(error):
    code = error.args[0] if len(error.args) == 1 else None
    if isinstance(code, str) and code in SAFE_RUNTIME_FAILURE_CODES:
        return code
    if isinstance(error, installer.host.custody.BootstrapFailed) and isinstance(code, str) \
            and re.fullmatch('[A-Z][A-Z0-9_]{2,127}', code):
        return code
    return None


def safe_failure_type(error):
    name = type(error).__name__
    return name if name in SAFE_RUNTIME_EXCEPTION_TYPES else 'Other'


def rehearse(k, wait, run, ca, node, platform):
    if not re.fullmatch('hooshix-bao-ci-[a-f0-9]{12}-control-plane', node):
        raise ValueError('OWNED_KAFKA_RUNTIME_FIXTURE_REQUIRED')
    def kube(*args, body=None, **options):
        return k(*args, data=json.dumps(body).encode() if body is not None else None, **options)

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
        plan = renderer.render(previous)
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
            else:
                print('KAFKA_RUNTIME_PREFLIGHT_FAILURE_TYPE=' + safe_failure_type(error), flush=True)
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
                bad['metadata']['ownerReferences'].append(copy.deepcopy(bad['metadata']['ownerReferences'][0]))
            if mode == 'owner-uid':
                bad['metadata']['ownerReferences'][0]['uid'] = 'foreign-statefulset-uid'
            print('KAFKA_RUNTIME_POD_ADMISSION_NEGATIVE=' + mode, flush=True)
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
        wait(lambda: json.loads(k('-n', renderer.NS, 'get', 'pod', renderer.NAME + '-0', '-o', 'json'))
            .get('status', {}).get('containerStatuses', [{}])[0].get('ready') is True, seconds=180)
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
        return result
