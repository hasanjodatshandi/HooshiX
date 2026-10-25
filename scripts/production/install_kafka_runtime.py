"""Owner-local closed Kafka commissioning; existing stores and credentials are preserved."""
import argparse
import base64
import copy
import fcntl
import hashlib
import json
import os
import re
import resource
import signal
import stat
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import commission_platform as host
import install_kafka_local_volume as volume
import install_kafka_tls as tls
import install_postgresql_quarantine as pg
import kafka_secret_delivery as delivery
import kafka_tls_material as certificates
from install_eso import desired_fields_match, mismatched_path
from upgrade_mesh_security import replace_policy

NS, NAME, MANAGER = 'platform-data', 'kafka', 'hooshix-kafka-runtime'
HASH = 'hooshix.io/kafka-spec-sha256'
PULL_SECRET = 'hooshix-ghcr-read'
READINESS_TIMEOUT_SECONDS = 420
ADMISSION_RETRY_SECONDS = 120
ADMISSION_REQUEST_TIMEOUT_SECONDS = 60
TOOL_JVM_OPTIONS = '-XX:+UseSerialGC -XX:TieredStopAtLevel=1'
BOUNDARY_UID_PLACEHOLDER = 'KAFKA_STATEFULSET_UID_BOUNDARY_PLACEHOLDER'
require, get, digest = pg.require, pg.get, pg.digest


def node_secret(kube, ca_sha256):
    secret = get(kube, 'secret', delivery.NAME, NS)
    external = get(kube, 'externalsecret', delivery.NAME, NS)
    require(secret and external and desired_fields_match(external, delivery.external())
        and any(c.get('type') == 'Ready' and c.get('status') == 'True'
                for c in external.get('status', {}).get('conditions', []))
        and secret.get('type') == 'kubernetes.io/tls'
        and set(secret.get('data', {})) == set(certificates.FIELDS)
        and secret.get('metadata', {}).get('labels', {}).get('app.kubernetes.io/managed-by') == delivery.MANAGER
        and secret['metadata'].get('ownerReferences') == tls.owner(external), 'EXISTING_KAFKA_TLS_DELIVERY_REQUIRED')
    values = {key: base64.b64decode(value, validate=True).decode()
              for key, value in secret['data'].items()}
    require(hashlib.sha256(values['ca.crt'].encode()).hexdigest() == ca_sha256,
            'PINNED_KAFKA_ROOT_REQUIRED')
    pem_chain = certificates.platform.ca_import.pem_certificate_blocks(values['tls.crt'].encode())
    require(len(pem_chain) == 2, 'EXACT_KAFKA_NODE_CHAIN_REQUIRED')
    certificates.validate(values, values['ca.crt'], pem_chain[1].decode())
    return (secret['metadata']['uid'], secret['type'], secret['data'],
            external['metadata']['uid'], external['spec'])


def ensure_registry_pull_secret(kube):
    source = get(kube, 'secret', PULL_SECRET, 'hooshix-secrets')
    require(isinstance(source, dict), 'EXISTING_KAFKA_REGISTRY_SECRET_REQUIRED')
    metadata = source.get('metadata')
    data = source.get('data')
    require(isinstance(metadata, dict) and isinstance(data, dict)
        and isinstance(metadata.get('labels'), dict), 'EXISTING_KAFKA_REGISTRY_SECRET_REQUIRED')
    encoded = data.get('.dockerconfigjson')
    require(metadata.get('namespace') == 'hooshix-secrets'
        and metadata.get('name') == PULL_SECRET
        and all(metadata['labels'].get(key) == value for key, value in host.LABELS.items())
        and not metadata.get('deletionTimestamp') and not metadata.get('ownerReferences')
        and source.get('type') == 'kubernetes.io/dockerconfigjson'
        and set(data) == {'.dockerconfigjson'} and isinstance(encoded, str) and 1 <= len(encoded) <= 65536,
        'EXISTING_KAFKA_REGISTRY_SECRET_REQUIRED')
    try:
        config = json.loads(base64.b64decode(encoded, validate=True))
    except (TypeError, ValueError):
        raise ValueError('EXISTING_KAFKA_REGISTRY_SECRET_REQUIRED') from None
    auths = config.get('auths') if isinstance(config, dict) else None
    ghcr = auths.get('ghcr.io') if isinstance(auths, dict) else None
    auth = ghcr.get('auth') if isinstance(ghcr, dict) else None
    require(isinstance(config, dict) and set(config) == {'auths'}
        and isinstance(auths, dict) and set(auths) == {'ghcr.io'}
        and isinstance(ghcr, dict) and set(ghcr) == {'auth'}
        and isinstance(auth, str) and 1 <= len(auth) <= 8192,
        'EXISTING_KAFKA_REGISTRY_SECRET_REQUIRED')
    try:
        credential = base64.b64decode(auth, validate=True)
    except (TypeError, ValueError):
        raise ValueError('EXISTING_KAFKA_REGISTRY_SECRET_REQUIRED') from None
    prefix = b'hasanjodatshandi:'
    token = credential[len(prefix):] if credential.startswith(prefix) else b''
    require(credential.startswith(prefix) and 1 <= len(token) <= 4096
        and all(32 < char < 127 for char in token),
            'EXISTING_KAFKA_REGISTRY_SECRET_REQUIRED')
    del config, auths, ghcr, auth, credential, token
    host.write_registry_secret(NS, encoded, kube_call=kube)


def owned(old, desired):
    if old is None:
        return
    meta = old.get('metadata', {})
    require(meta.get('labels', {}).get('app.kubernetes.io/managed-by') == MANAGER
        and not meta.get('deletionTimestamp') and not meta.get('ownerReferences'),
        'FOREIGN_OR_CHANGED_KAFKA_RESOURCE_PRESERVED')
    if 'spec' in desired:
        require(meta.get('annotations', {}).get(HASH) == digest(old['spec']),
                'KAFKA_DEFAULTED_SPEC_DRIFT_PRESERVED')
    actual = old
    if desired.get('kind') == 'Service' and desired['spec'].get('publishNotReadyAddresses') is False:
        actual = copy.deepcopy(old)
        # The typed Kubernetes API omits this boolean when it is false.
        actual['spec'].setdefault('publishNotReadyAddresses', False)
    if desired.get('kind') == 'StatefulSet' and old.get('spec', {}).get('replicas') == 0:
        actual = copy.deepcopy(old)
        actual['spec']['replicas'] = desired['spec']['replicas']
    matches = desired_fields_match(actual, desired)
    if not matches:
        print('KAFKA_PUBLIC_DRIFT=' + json.dumps({'kind': desired['kind'],
            'name': desired['metadata']['name'], 'field': mismatched_path(actual, desired)}),
            file=sys.stderr, flush=True)
    require(matches, 'FOREIGN_OR_CHANGED_KAFKA_RESOURCE_PRESERVED')


def activate_statefulset(kube, stateful):
    require(isinstance(stateful, dict), 'KAFKA_STATEFULSET_REQUIRED')
    spec = copy.deepcopy(stateful['spec'])
    if spec.get('replicas') == 1:
        return
    annotations = stateful.get('metadata', {}).get('annotations', {})
    require(spec.get('replicas') == 0 and annotations.get(HASH) == digest(spec),
            'QUIESCENT_KAFKA_STATEFULSET_REQUIRED')
    active = copy.deepcopy(spec)
    active['replicas'] = 1
    patch = [
        {'op': 'test', 'path': '/spec', 'value': spec},
        {'op': 'test', 'path': '/spec/replicas', 'value': 0},
        {'op': 'test', 'path': '/metadata/annotations/' + HASH.replace('/', '~1'), 'value': annotations[HASH]},
        {'op': 'replace', 'path': '/spec/replicas', 'value': 1},
        {'op': 'replace', 'path': '/metadata/annotations/' + HASH.replace('/', '~1'), 'value': digest(active)},
    ]
    kube('patch', 'statefulset', NAME, '-n', NS, '--type=json', '-p', json.dumps(patch))


def admission_candidate(stateful):
    metadata = stateful.get('metadata', {})
    uid = metadata.get('uid')
    template = stateful.get('spec', {}).get('template', {})
    template_metadata = template.get('metadata', {})
    labels = template_metadata.get('labels')
    require(metadata.get('name') == NAME and metadata.get('namespace') == NS
        and isinstance(uid, str) and re.fullmatch('[A-Za-z0-9-]{1,128}', uid)
        and isinstance(labels, dict) and labels.get('app.kubernetes.io/name') == NAME
        and labels.get('istio.io/dataplane-mode') == 'ambient'
        and isinstance(template.get('spec'), dict), 'KAFKA_STATEFULSET_REQUIRED')
    pod_metadata = {'name': NAME + '-0', 'namespace': NS, 'labels': copy.deepcopy(labels),
        'ownerReferences': [{'apiVersion': 'apps/v1', 'kind': 'StatefulSet', 'name': NAME,
            'uid': uid, 'controller': True, 'blockOwnerDeletion': True}]}
    if template_metadata.get('annotations'):
        pod_metadata['annotations'] = copy.deepcopy(template_metadata['annotations'])
    return {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': pod_metadata,
        'spec': copy.deepcopy(template['spec'])}


def preflight_admission(kube, stateful):
    candidate = admission_candidate(stateful)

    def admission_command(*args, **options):
        try:
            return kube(*args, **options)
        except Exception:
            # The CI kubectl shim uses a separate exception type and can carry command details.
            raise subprocess.SubprocessError from None

    deadline = time.monotonic() + ADMISSION_RETRY_SECONDS
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ValueError('KAFKA_ADMISSION_POSITIVE_PREFLIGHT_FAILED')
        try:
            raw = admission_command('-n', NS, 'get', 'pod', NAME + '-0', '--ignore-not-found', '-o', 'json',
                timeout=min(10, remaining))
            existing = json.loads(raw) if raw.strip() else None
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ValueError('KAFKA_ADMISSION_POSITIVE_PREFLIGHT_FAILED')
            if existing is None:
                admission_command('create', '--dry-run=server', '-f', '-', body=candidate,
                    timeout=min(ADMISSION_REQUEST_TIMEOUT_SECONDS, remaining))
                operation = 'create'
            else:
                require(desired_fields_match(existing, candidate), 'KAFKA_EXISTING_POD_CONTRACT_REQUIRED')
                probe = {'metadata': {'annotations': {
                    'hooshix.io/admission-preflight': str(time.monotonic_ns())}}}
                admission_command('-n', NS, 'patch', 'pod', NAME + '-0', '--type=merge', '-p',
                    json.dumps(probe), '--dry-run=server',
                    timeout=min(ADMISSION_REQUEST_TIMEOUT_SECONDS, remaining))
                operation = 'patch'
            break
        except subprocess.SubprocessError as error:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ValueError('KAFKA_ADMISSION_POSITIVE_PREFLIGHT_FAILED') from error
            time.sleep(min(2, remaining))

    wrong_uid = '00000000-0000-4000-8000-000000000001'
    if wrong_uid == candidate['metadata']['ownerReferences'][0]['uid']:
        wrong_uid = '00000000-0000-4000-8000-000000000002'
    try:
        if operation == 'create':
            wrong_owner = copy.deepcopy(candidate)
            wrong_owner['metadata']['ownerReferences'][0]['uid'] = wrong_uid
            admission_command('create', '--dry-run=server', '-f', '-', body=wrong_owner, expected=1,
                required_error=b'closed Kafka runtime contract rejected',
                timeout=ADMISSION_REQUEST_TIMEOUT_SECONDS)
        else:
            wrong_owner = {'metadata': {'ownerReferences': copy.deepcopy(
                candidate['metadata']['ownerReferences']), 'annotations': {
                    'hooshix.io/admission-preflight': str(time.monotonic_ns())}}}
            wrong_owner['metadata']['ownerReferences'][0]['uid'] = wrong_uid
            admission_command('-n', NS, 'patch', 'pod', NAME + '-0', '--type=merge', '-p',
                json.dumps(wrong_owner), '--dry-run=server', expected=1,
                required_error=b'closed Kafka runtime contract rejected',
                timeout=ADMISSION_REQUEST_TIMEOUT_SECONDS)
    except subprocess.SubprocessError as error:
        raise ValueError('KAFKA_ADMISSION_NEGATIVE_PREFLIGHT_FAILED') from error


def boundary_for_statefulset(plan, uid):
    require(isinstance(uid, str) and re.fullmatch('[A-Za-z0-9-]{1,128}', uid),
            'KAFKA_STATEFULSET_UID_REQUIRED')
    result = copy.deepcopy(plan['boundary'])
    needle = json.dumps(BOUNDARY_UID_PLACEHOLDER)
    replacement = json.dumps(uid)
    uid_checks = 0
    for validation in result.get('spec', {}).get('validations', []):
        expression = validation.get('expression')
        require(isinstance(expression, str), 'KAFKA_BOUNDARY_UID_TEMPLATE_REQUIRED')
        count = expression.count(needle)
        require(count in (0, 1), 'KAFKA_BOUNDARY_UID_TEMPLATE_REQUIRED')
        if count:
            validation['expression'] = expression.replace(needle, replacement)
            uid_checks += count
    require(uid_checks == 1, 'KAFKA_BOUNDARY_UID_TEMPLATE_REQUIRED')
    return result


def binding(kube, claim):
    storage_class, _ = volume.candidate()
    require(volume.matches(get(kube, storage_class['kind'], storage_class['metadata']['name']),
                           storage_class), 'RETAIN_KAFKA_CLASS_REQUIRED')
    pv = get(kube, 'pv', volume.VOLUME)
    require(pv, 'RESERVED_KAFKA_VOLUME_REQUIRED')
    normalized = copy.deepcopy(pv)
    if claim and pv.get('status', {}).get('phase') == 'Bound':
        ref = normalized['spec']['claimRef']
        require(claim['status']['phase'] == 'Bound' and claim['spec']['volumeName'] == volume.VOLUME
            and ref.get('uid') == claim['metadata']['uid'], 'EXACT_KAFKA_BINDING_REQUIRED')
        for field in ('uid', 'resourceVersion'):
            ref.pop(field, None)
        normalized['status']['phase'] = 'Available'
    require(volume.matches(normalized, volume.candidate()[1]),
            'FOREIGN_OR_CHANGED_KAFKA_VOLUME_PRESERVED')
    return pv['metadata']['uid']


def isolation(plan, kube, *, require_desired=False):
    labels = plan['statefulset']['spec']['template']['metadata']['labels']
    expected = {obj['kind'].lower(): obj for obj in plan['objects']
                if obj['kind'] in ('NetworkPolicy', 'AuthorizationPolicy')}
    for kind, selector_field in (('networkpolicies', 'podSelector'),
                                 ('authorizationpolicies', 'selector')):
        items = json.loads(kube('-n', NS, 'get', kind, '-o', 'json'))['items']
        singular = 'networkpolicy' if kind == 'networkpolicies' else 'authorizationpolicy'
        seen = False
        for obj in items:
            spec = obj.get('spec', {})
            if not pg.selects(spec.get(selector_field, {}), labels):
                continue
            if obj['metadata']['name'] == expected[singular]['metadata']['name']:
                require(desired_fields_match(obj, expected[singular]), 'KAFKA_ISOLATION_DRIFT')
                seen = True
            elif kind == 'networkpolicies':
                require(not spec.get('ingress') and not spec.get('egress'),
                        'ADDITIONAL_KAFKA_NETWORK_ACCESS_REJECTED')
            else:
                action = spec.get('action', 'ALLOW')
                require(action == 'DENY' or (action == 'ALLOW' and not spec.get('rules')),
                        'ADDITIONAL_KAFKA_MESH_ACCESS_REJECTED')
            require(obj.get('metadata', {}).get('annotations', {}).get('istio.io/dry-run') in (None, 'false'),
                    'KAFKA_DRY_RUN_POLICY_REJECTED')
        require(not require_desired or seen, 'KAFKA_CLOSED_POLICY_REQUIRED')
    peers = json.loads(kube('-n', NS, 'get', 'peerauthentications', '-o', 'json'))['items']
    require(len(peers) == 1 and peers[0]['spec'] == {'mtls': {'mode': 'STRICT'}},
            'KAFKA_STRICT_AMBIENT_MTLS_REQUIRED')


def pod_inventory(kube, stateful, *, required=False):
    pods = json.loads(kube('-n', NS, 'get', 'pods', '-l',
        'app.kubernetes.io/name=kafka', '-o', 'json'))['items']
    if stateful is None:
        require(not pods, 'UNOWNED_KAFKA_POD_PRESERVED')
        return pods
    require(len(pods) <= 1 and (not required or len(pods) == 1), 'UNEXPECTED_KAFKA_POD_COUNT')
    for pod in pods:
        labels = pod.get('metadata', {}).get('labels', {})
        refs = pod.get('metadata', {}).get('ownerReferences', [])
        require(pod['metadata'].get('name') == NAME + '-0'
            and labels.get('app.kubernetes.io/managed-by') == MANAGER
            and len(refs) == 1 and refs[0].get('kind') == 'StatefulSet'
            and refs[0].get('uid') == stateful['metadata']['uid'] and refs[0].get('controller') is True,
            'UNOWNED_KAFKA_POD_PRESERVED')
    return pods


def storage_identity(kube):
    script = ('set -eu; export KAFKA_HEAP_OPTS="-Xms32m -Xmx128m"; '
        f'export KAFKA_JVM_PERFORMANCE_OPTS="{TOOL_JVM_OPTIONS}"; '
        'data=/var/lib/kafka/data; marker=$(cat "$data/.hooshix-runtime-marker"); '
        'case "$marker" in hooshix-kafka-runtime-v1:*) id=${marker#hooshix-kafka-runtime-v1:};; *) exit 1;; esac; '
        'case "$id" in ""|*[!A-Za-z0-9_-]*) exit 1;; esac; '
        'grep -Fqx "cluster.id=$id" "$data/meta.properties"; '
        'grep -Fqx "node.id=1" "$data/meta.properties"; grep -Fqx "version=1" "$data/meta.properties"; '
        '/opt/kafka/bin/kafka-storage.sh info --config /etc/kafka/commissioned/server.properties >/dev/null 2>&1; '
        'printf "%s\\n" "$id"')
    raw = kube('-n', NS, 'exec', NAME + '-0', '-c', 'kafka', '--', '/bin/sh', '-ec', script).decode().strip()
    require(re.fullmatch('[A-Za-z0-9_-]{16,64}', raw), 'KAFKA_FORMATTED_STORAGE_ID_REQUIRED')
    return raw, hashlib.sha256(raw.encode()).hexdigest()


def quorum_and_topics(kube, cluster_id):
    status = kube('-n', NS, 'exec', NAME + '-0', '-c', 'kafka', '--',
        '/usr/bin/env', 'KAFKA_HEAP_OPTS=-Xms32m -Xmx128m',
        'KAFKA_JVM_PERFORMANCE_OPTS=' + TOOL_JVM_OPTIONS,
        '/opt/kafka/bin/kafka-metadata-quorum.sh', '--command-config',
        '/etc/kafka/commissioned/client.properties', '--bootstrap-server', '127.0.0.1:9092',
        'describe', '--status').decode()
    fields = {key.strip(): value.strip() for line in status.splitlines()
              if ':' in line for key, value in (line.split(':', 1),)}
    voters = json.loads(fields.get('CurrentVoters', 'null'))
    require(fields.get('ClusterId') == cluster_id and fields.get('LeaderId') == '1'
        and isinstance(voters, list) and len(voters) == 1
        and voters[0].get('id') == 1, 'SINGLE_NODE_KAFKA_QUORUM_REQUIRED')
    topics = kube('-n', NS, 'exec', NAME + '-0', '-c', 'kafka', '--',
        '/usr/bin/env', 'KAFKA_HEAP_OPTS=-Xms32m -Xmx128m',
        'KAFKA_JVM_PERFORMANCE_OPTS=' + TOOL_JVM_OPTIONS,
        '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', '127.0.0.1:9092',
        '--command-config', '/etc/kafka/commissioned/client.properties', '--list')
    require(not topics.strip(), 'EMPTY_QUARANTINED_KAFKA_REQUIRED')


def execute(plan, *, kube=host.kube, guard=volume.foundation, preserve=tls.preserved):
    require(plan.get('schema_version') == 1 and plan.get('installation_id') == 'hooshix-production'
        and plan.get('profile') == 'production-single-server'
        and plan.get('image') == 'ghcr.io/hasanjodatshandi/hooshix/platform-kafka-v2-private@sha256:'
            '9fe8fcb340ffe144e3ffa502df1b0694743a027df56595dd91e62b6f9e716556'
        and plan.get('repository_revision') == '7d4d00d781060e723e6f231c5ea63660c9d87d13'
        and plan.get('publication_run') == '39468920860' and plan.get('publication_attempt') == 1
        and plan.get('publication_sha256') == 'b1a61c3bf16252ec41b49673b81b67e20114bd3fab43839c2358a888848131a0'
        and plan.get('image_configuration_digest') == 'sha256:e12c940f9d38faa5a7034d622727156e4766a833135de66231e36ec20e854e83'
        and re.fullmatch('[a-f0-9]{40}', plan.get('repository_source_revision', ''))
        and isinstance(plan.get('staging'), dict)
        and plan['staging'].get('source_revision') == plan['repository_source_revision']
        and plan['staging'].get('result') == 'Passed'
        and re.fullmatch('[1-9][0-9]{0,19}', plan['staging'].get('run_id', ''))
        and re.fullmatch('[a-f0-9]{64}', plan.get('public_ca_sha256', ''))
        and plan.get('public_access') == plan.get('application_ingress') == 'CLOSED'
        and plan.get('production_readiness') == 'Not verified', 'CLOSED_KAFKA_RUNTIME_PLAN_REQUIRED')
    print('KAFKA_RUNTIME_STEP=preflight', file=sys.stderr, flush=True)
    before = preserve(kube, plan['public_ca_sha256'])
    cert_before = node_secret(kube, plan['public_ca_sha256'])
    namespace = get(kube, 'namespace', NS)['metadata']['labels']
    require(namespace.get('istio.io/dataplane-mode') == 'ambient'
        and namespace.get('pod-security.kubernetes.io/enforce') == 'restricted',
        'RESTRICTED_AMBIENT_KAFKA_NAMESPACE_REQUIRED')
    boundary = get(kube, 'validatingpolicy', plan['boundary']['metadata']['name'])
    stateful = get(kube, 'statefulset', NAME, NS)
    claim = get(kube, 'pvc', volume.CLAIM, NS)
    if not stateful:
        require(claim is None, 'ORPHANED_KAFKA_CLAIM_PRESERVED')
    desired_objects = [*plan['objects'], plan['statefulset'], *plan['source_policies']]
    existing = []
    for obj in desired_objects:
        meta = obj['metadata']
        old = get(kube, obj['kind'], meta['name'], meta.get('namespace'))
        owned(old, obj)
        existing.append(old)
    if stateful:
        stateful_boundary = boundary_for_statefulset(plan, stateful.get('metadata', {}).get('uid'))
        allowed_boundaries = (plan['previous_boundary']['spec'], stateful_boundary['spec'])
    else:
        stateful_boundary = None
        allowed_boundaries = (plan['previous_boundary']['spec'],)
    require(boundary and boundary.get('spec') in allowed_boundaries
        and boundary.get('spec', {}).get('validationActions') == ['Deny']
        and boundary.get('spec', {}).get('failurePolicy') == 'Fail'
        and boundary.get('spec', {}).get('evaluation', {}).get('admission', {}).get('enabled') is True
        and boundary.get('status', {}).get('conditionStatus', {}).get('ready') is True,
        'ACTIVE_CLOSED_DATA_ADMISSION_REQUIRED')
    volume_uid = binding(kube, claim)
    if claim:
        require(claim.get('status', {}).get('phase') == 'Bound'
            and claim.get('spec', {}).get('volumeName') == volume.VOLUME,
            'EXISTING_KAFKA_CLAIM_BINDING_REQUIRED')
    marker = volume.DATA / '.hooshix-runtime-marker'
    initialized = bool(stateful and marker.is_file() and not marker.is_symlink())
    units = guard(initialized=initialized)
    isolation(plan, kube)
    pod_inventory(kube, stateful)
    ensure_registry_pull_secret(kube)

    print('KAFKA_RUNTIME_STEP=admission', file=sys.stderr, flush=True)
    for obj, old in zip(desired_objects, existing, strict=True):
        if old or obj['kind'] in ('StatefulSet', 'PersistentVolumeClaim'):
            continue
        desired = copy.deepcopy(obj)
        if 'spec' in desired:
            defaulted = json.loads(kube('create', '--dry-run=server', '-f', '-', '-o', 'json', body=desired))
            desired['metadata'].setdefault('annotations', {})[HASH] = digest(defaulted['spec'])
        kube('create', '-f', '-', body=desired)
    for policy in plan['source_policies']:
        kube('wait', '--for=jsonpath={.status.conditionStatus.ready}=true',
             policy['kind'].lower() + '/' + policy['metadata']['name'], '--timeout=60s', timeout=75)
    for obj, old in zip(desired_objects, existing, strict=True):
        if old or obj['kind'] not in ('PersistentVolumeClaim', 'StatefulSet'):
            continue
        print('KAFKA_RUNTIME_STEP=' + ('claim' if obj['kind'] == 'PersistentVolumeClaim' else 'workload'),
              file=sys.stderr, flush=True)
        desired = copy.deepcopy(obj)
        if obj['kind'] == 'StatefulSet':
            desired['spec']['replicas'] = 0
        defaulted = json.loads(kube('create', '--dry-run=server', '-f', '-', '-o', 'json', body=desired))
        desired['metadata'].setdefault('annotations', {})[HASH] = digest(defaulted['spec'])
        kube('create', '-f', '-', body=desired)

    stateful = get(kube, 'statefulset', NAME, NS)
    require(stateful, 'KAFKA_STATEFULSET_REQUIRED')
    stateful_boundary = boundary_for_statefulset(plan, stateful.get('metadata', {}).get('uid'))
    current_boundary = get(kube, 'validatingpolicy', plan['boundary']['metadata']['name'])
    require(current_boundary and current_boundary.get('spec') in (
        plan['previous_boundary']['spec'], stateful_boundary['spec']), 'KAFKA_BOUNDARY_DRIFT_PRESERVED')
    if current_boundary['spec'] != stateful_boundary['spec']:
        replace_policy(stateful_boundary, get=lambda kind, name: get(kube, kind, name),
            kube=kube, previous=plan['previous_boundary'])
    active_boundary = get(kube, 'validatingpolicy', stateful_boundary['metadata']['name'])
    require(active_boundary and active_boundary.get('spec') == stateful_boundary['spec']
        and active_boundary.get('spec', {}).get('validationActions') == ['Deny']
        and active_boundary.get('spec', {}).get('failurePolicy') == 'Fail',
        'KAFKA_BOUNDARY_APPLY_REQUIRED')
    print('KAFKA_RUNTIME_STEP=admission-preflight', file=sys.stderr, flush=True)
    preflight_admission(kube, stateful)
    print('KAFKA_RUNTIME_STEP=data-admission', file=sys.stderr, flush=True)

    print('KAFKA_RUNTIME_STEP=activate', file=sys.stderr, flush=True)
    activate_statefulset(kube, get(kube, 'statefulset', NAME, NS))

    print('KAFKA_RUNTIME_STEP=readiness', file=sys.stderr, flush=True)
    kube('-n', NS, 'wait', '--for=jsonpath={.status.readyReplicas}=1',
         'statefulset/' + NAME, f'--timeout={READINESS_TIMEOUT_SECONDS}s',
         timeout=READINESS_TIMEOUT_SECONDS + 15)
    pod = get(kube, 'pod', NAME + '-0', NS)
    current = get(kube, 'statefulset', NAME, NS)
    expected = {'kind': 'Pod', 'spec': plan['statefulset']['spec']['template']['spec']}
    statuses = pod.get('status', {}).get('containerStatuses', []) if pod else []
    init_statuses = pod.get('status', {}).get('initContainerStatuses', []) if pod else []
    require(current and pod and desired_fields_match(pod, expected)
        and pod['metadata'].get('labels', {}).get('istio.io/dataplane-mode') == 'ambient'
        and len(statuses) == 1 and statuses[0].get('name') == 'kafka' and statuses[0].get('ready')
        and len(init_statuses) == 1 and init_statuses[0].get('name') == 'tls-material'
        and init_statuses[0].get('state', {}).get('terminated', {}).get('exitCode') == 0
        and any(ref.get('uid') == current['metadata']['uid'] and ref.get('controller') is True
                for ref in pod['metadata'].get('ownerReferences', [])), 'READY_OWNED_KAFKA_POD_REQUIRED')
    pod_inventory(kube, current, required=True)

    print('KAFKA_RUNTIME_STEP=verification', file=sys.stderr, flush=True)
    for obj in desired_objects:
        meta = obj['metadata']
        owned(get(kube, obj['kind'], meta['name'], meta.get('namespace')), obj)
    isolation(plan, kube, require_desired=True)
    claim = get(kube, 'pvc', volume.CLAIM, NS)
    require(claim and claim.get('status', {}).get('phase') == 'Bound'
        and claim.get('spec', {}).get('volumeName') == volume.VOLUME and binding(kube, claim) == volume_uid,
        'BOUND_RETAIN_KAFKA_VOLUME_REQUIRED')
    health = plan['statefulset']['spec']['template']['spec']['containers'][0]['readinessProbe']['exec']['command']
    require(kube('-n', NS, 'exec', NAME + '-0', '-c', 'kafka', '--', *health).strip() == b'',
            'KAFKA_MUTUAL_TLS_HEALTH_REQUIRED')
    cluster_id, identity = storage_identity(kube)
    quorum_and_topics(kube, cluster_id)
    final_boundary = get(kube, 'validatingpolicy', plan['boundary']['metadata']['name'])
    require(final_boundary and final_boundary.get('spec') == stateful_boundary['spec']
        and final_boundary.get('spec', {}).get('failurePolicy') == 'Fail'
        and final_boundary.get('spec', {}).get('validationActions') == ['Deny']
        and final_boundary.get('status', {}).get('conditionStatus', {}).get('ready') is True,
        'KAFKA_BOUNDARY_NOT_READY')
    actor = '--as=system:serviceaccount:' + NS + ':' + NAME
    for verb, target in (('get', 'secret/' + delivery.NAME), ('list', 'secrets'),
                         ('create', 'pods'), ('create', 'serviceaccounts/' + delivery.ENTRY['account'])):
        require(kube('auth', 'can-i', verb, target, '-n', NS, actor, expected=1).strip() == b'no',
                'KAFKA_WORKLOAD_AUTHORITY_REJECTED')
    logs = []
    for container in ('tls-material', 'kafka'):
        logs.append(kube('-n', NS, 'logs', NAME + '-0', '-c', container, '--tail=300'))
    material = json.loads(kube('-n', NS, 'get', 'secret', delivery.NAME, '-o', 'json'))['data']
    markers = [base64.b64decode(material['tls.key'], validate=True)]
    markers.extend(line for line in markers[0].splitlines() if line and b'PRIVATE KEY' not in line)
    require(not any(marker and marker in b'\n'.join(logs) for marker in markers),
            'KAFKA_TLS_LOG_PRIVACY_REQUIRED')
    require(preserve(kube, plan['public_ca_sha256']) == before
        and node_secret(kube, plan['public_ca_sha256']) == cert_before
        and guard(initialized=True) == units, 'EXISTING_PLATFORM_STATE_CHANGED')
    return {'schema_version': 1, 'kafka_runtime': 'Passed', 'image': plan['image'],
        'statefulset_uid': current['metadata']['uid'], 'pvc_uid': claim['metadata']['uid'],
        'volume_uid': volume_uid, 'cluster_identity_sha256': identity,
        'data_admission_boundary': 'Passed',
        'mutual_tls_health': 'Passed', 'single_node_quorum': 'Passed', 'business_topics': 'None',
        'service_account_scope': 'Passed', 'storage_guard': 'Passed' if units else 'Not run',
        'existing_database_redis_openbao_eso_tls': 'Preserved', 'application_ingress': 'CLOSED',
        'public_access': 'CLOSED', 'automatic_renewal': 'Not run', 'provider_backup': 'Not run',
        'root_revocation': 'Not run',
        'capacity': 'Not verified', 'production_readiness': 'Not verified'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', required=True, type=Path)
    parser.add_argument('--sha256', required=True)
    args = parser.parse_args()
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(ValueError('KAFKA_RUNTIME_DEADLINE')))
    signal.alarm(900)
    try:
        require(os.geteuid() == 0 and re.fullmatch('[a-f0-9]{64}', args.sha256), 'LOCAL_OWNER_AUTH_REQUIRED')
        with os.fdopen(os.open(args.plan, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as stream:
            info = os.fstat(stream.fileno())
            require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1
                and 0 < info.st_size <= host.PLAN_BOUND, 'BOUNDED_PUBLIC_PLAN_REQUIRED')
            content = stream.read(host.PLAN_BOUND + 1)
        require(hashlib.sha256(content).hexdigest() == args.sha256, 'PLAN_HASH_MISMATCH')
        plan = json.loads(content)
        evidence = plan['staging']
        observed = datetime.fromisoformat(evidence['observed_at'])
        require(observed.tzinfo and 0 <= (datetime.now(timezone.utc) - observed).total_seconds() <= 5 * 86400
            and evidence.get('result') == 'Passed' and re.fullmatch('[a-f0-9]{40}', evidence.get('source_revision', ''))
            and evidence['source_revision'] == plan['repository_source_revision']
            and re.fullmatch('[1-9][0-9]{0,19}', evidence.get('run_id', '')),
            'FRESH_KAFKA_NATIVE_EVIDENCE_REQUIRED')
        services = host.preflight()
        lock_path = '/var/lib/hooshixstorage/kafka-storage.lock'
        with os.fdopen(os.open(lock_path, os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK), 'r+b') as lock:
            info = os.fstat(lock.fileno())
            require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and info.st_nlink == 1
                and stat.S_IMODE(info.st_mode) == 0o600, 'EXISTING_SAFE_KAFKA_LOCK_REQUIRED')
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            host.native(['/usr/sbin/auditctl', '-m', 'HooshiX closed Kafka runtime plan=' + args.sha256])
            result = execute(plan)
        require(host.snapshot_services() == services, 'HOST_SERVICE_CHANGED')
        host.kernel_audit()
        result['plan_sha256'] = args.sha256
        print(json.dumps(result, separators=(',', ':')))
        return 0
    except (ValueError, KeyError, IndexError, TypeError, OSError, subprocess.SubprocessError,
            host.custody.BootstrapFailed):
        print('KAFKA_RUNTIME=Failed; state preserved; no reset or automatic retry', file=sys.stderr)
        return 1
    finally:
        signal.alarm(0)


if __name__ == '__main__':
    raise SystemExit(main())
