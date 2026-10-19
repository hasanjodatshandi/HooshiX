"""Owner-local closed Redis commissioning; existing secrets/data are never replaced."""
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
from datetime import datetime, timezone
from pathlib import Path

import commission_platform as host
import install_postgresql_quarantine as pg
import install_redis_local_volume as volume
import redis_secret_delivery as delivery
import redis_tls_material as certificates
from bootstrap_redis_secrets import preserved
from install_eso import desired_fields_match
from render_redis_acl import acl_file
from upgrade_mesh_security import replace_policy

NS, NAME, MANAGER = 'platform-data', 'security-redis', 'hooshix-redis-runtime'
HASH = 'hooshix.io/redis-spec-sha256'
require, get, digest = pg.require, pg.get, pg.digest


def secrets(kube, ca_sha256):
    retained, values = [], {}
    for name in delivery.ALL_AUTH_ENTRIES:
        obj = get(kube, 'secret', name, NS)
        external = get(kube, 'externalsecret', name, NS)
        require(obj and external and desired_fields_match(external, delivery.external(name))
            and external.get('status', {}).get('conditions')
            and any(c['type'] == 'Ready' and c['status'] == 'True' for c in external['status']['conditions'])
            and obj['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == delivery.MANAGER
            and obj['metadata'].get('ownerReferences') == [{'apiVersion': 'external-secrets.io/v1',
                'kind': 'ExternalSecret', 'name': name, 'uid': external['metadata']['uid'],
                'controller': True, 'blockOwnerDeletion': True}], 'EXISTING_REDIS_ESO_DELIVERY_REQUIRED')
        values[name] = {k: base64.b64decode(v, validate=True).decode() for k, v in obj['data'].items()}
        expected_type = ('kubernetes.io/tls' if name == delivery.TLS_NAME else
                         'Opaque' if name == delivery.ACL_NAME else 'kubernetes.io/basic-auth')
        require(obj['type'] == expected_type, 'EXISTING_TYPED_REDIS_SECRETS_REQUIRED')
        retained.append((obj['metadata']['uid'], obj['type'], obj['data'], external['metadata']['uid'], external['spec']))
    hashes = {}
    for name in delivery.ENTRIES:
        data = values[name]
        require(set(data) == {'username', 'password'} and data['username'] == name.removeprefix('security-redis-')
            and re.fullmatch('[A-Za-z0-9_-]{64}', data['password']), 'EXISTING_REDIS_CREDENTIAL_REQUIRED')
        hashes[data['username']] = hashlib.sha256(data['password'].encode()).hexdigest()
    require(values[delivery.ACL_NAME] == {'users.acl': acl_file(hashes)}, 'EXISTING_REDIS_ACL_BINDING_REQUIRED')
    tls = values[delivery.TLS_NAME]
    require(hashlib.sha256(tls['ca.crt'].encode()).hexdigest() == ca_sha256, 'PINNED_REDIS_ROOT_REQUIRED')
    chain = re.findall(r'-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----\n?', tls['tls.crt'])
    require(len(chain) == 2, 'EXISTING_REDIS_CHAIN_REQUIRED')
    certificates.validate(tls, tls['ca.crt'], chain[1])
    return retained


def owned(old, desired):
    if not old: return
    require(old['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == MANAGER
        and not old['metadata'].get('deletionTimestamp') and not old['metadata'].get('ownerReferences')
        and desired_fields_match(old, desired),
        'FOREIGN_OR_CHANGED_REDIS_RESOURCE_PRESERVED')
    if 'spec' in desired:
        require(old['metadata'].get('annotations', {}).get(HASH) == digest(old['spec']),
                'REDIS_DEFAULTED_SPEC_DRIFT_PRESERVED')


def binding(kube, claim):
    sc, desired = volume.candidate()
    require(volume.matches(get(kube, sc['kind'], sc['metadata']['name']), sc), 'RETAIN_REDIS_CLASS_REQUIRED')
    pv = get(kube, 'pv', volume.VOLUME)
    require(pv, 'RESERVED_REDIS_VOLUME_REQUIRED')
    compared = copy.deepcopy(pv)
    if claim and pv.get('status', {}).get('phase') == 'Bound':
        require(claim['status']['phase'] == 'Bound' and claim['spec']['volumeName'] == volume.VOLUME
            and pv['spec']['claimRef'].get('uid') == claim['metadata']['uid'], 'EXACT_REDIS_BINDING_REQUIRED')
        for field in ('uid', 'resourceVersion'): compared['spec']['claimRef'].pop(field, None)
        compared['status']['phase'] = 'Available'
    require(volume.matches(compared, desired), 'FOREIGN_OR_CHANGED_REDIS_VOLUME_PRESERVED')
    return pv['metadata']['uid']


def isolation(plan, kube):
    labels = plan['statefulset']['spec']['template']['metadata']['labels']
    for kind, selector in (('networkpolicies', 'podSelector'), ('authorizationpolicies', 'selector')):
        expected = next(o for o in plan['objects'] if o['kind'].lower() == kind[:-3] + 'y')
        for obj in json.loads(kube('-n', NS, 'get', kind, '-o', 'json'))['items']:
            if not pg.selects(obj['spec'].get(selector, {}), labels): continue
            if obj['metadata']['name'] == expected['metadata']['name']:
                require(desired_fields_match(obj, expected), 'REDIS_ISOLATION_DRIFT')
            else:
                require(not any(obj['spec'].get(k) for k in ('ingress', 'egress', 'rules'))
                    and (kind != 'authorizationpolicies' or obj['spec'].get('action', 'ALLOW') == 'ALLOW'),
                    'ADDITIONAL_REDIS_ACCESS_REJECTED')
            require(obj['metadata'].get('annotations', {}).get('istio.io/dry-run') in (None, 'false'),
                    'REDIS_DRY_RUN_POLICY_REJECTED')
    peers = json.loads(kube('-n', NS, 'get', 'peerauthentications', '-o', 'json'))['items']
    require(len(peers) == 1 and peers[0]['spec'] == {'mtls': {'mode': 'STRICT'}}, 'UNRELAXED_STRICT_REQUIRED')


def execute(plan, *, kube=host.kube, guard=volume.foundation, preserve=preserved):
    require(plan.get('schema_version') == 1 and plan.get('installation_id') == 'hooshix-production'
        and plan.get('profile') == 'production-single-server'
        and plan.get('application_ingress') == plan.get('public_access') == 'CLOSED'
        and plan.get('production_readiness') == 'Not verified', 'CLOSED_REDIS_PLAN_REQUIRED')
    print('REDIS_RUNTIME_STEP=preflight', file=sys.stderr, flush=True)
    before, credentials = preserve(kube), secrets(kube, plan['public_ca_sha256'])
    labels = get(kube, 'namespace', NS)['metadata']['labels']
    require(labels.get('istio.io/dataplane-mode') == 'ambient'
        and labels.get('pod-security.kubernetes.io/enforce') == 'restricted', 'RESTRICTED_AMBIENT_REQUIRED')
    desired_objects = [*plan['objects'], plan['statefulset'], *plan['source_policies']]
    existing = []
    for obj in desired_objects:
        m = obj['metadata']
        old = get(kube, obj['kind'], m['name'], m.get('namespace'))
        owned(old, obj)
        existing.append(old)
    claim = get(kube, 'pvc', volume.CLAIM, NS)
    pv_uid = binding(kube, claim)
    stateful = get(kube, 'statefulset', NAME, NS)
    units = guard(initialized=bool(stateful))
    previous = get(kube, 'validatingpolicy', plan['boundary']['metadata']['name'])
    require(previous and previous['spec'] in (plan['previous_boundary']['spec'], plan['boundary']['spec']),
            'PREVIOUS_DATA_ADMISSION_CONFLICT')
    isolation(plan, kube)
    # Whole-set preflight above precedes any admission, claim or workload write.
    print('REDIS_RUNTIME_STEP=admission', file=sys.stderr, flush=True)
    for obj, old in zip(desired_objects, existing, strict=True):
        if old or obj['kind'] in ('StatefulSet', 'PersistentVolumeClaim'): continue
        obj = copy.deepcopy(obj)
        if 'spec' in obj:
            defaulted = json.loads(kube('create', '--dry-run=server', '-f', '-', '-o', 'json', body=obj))
            obj['metadata'].setdefault('annotations', {})[HASH] = digest(defaulted['spec'])
        kube('create', '-f', '-', body=obj)
    if previous['spec'] != plan['boundary']['spec']:
        replace_policy(plan['boundary'], get=lambda kind, name: get(kube, kind, name),
                       kube=kube, previous=plan['previous_boundary'])
    for obj in [plan['boundary'], *plan['source_policies']]:
        kube('wait', '--for=jsonpath={.status.conditionStatus.ready}=true',
             obj['kind'].lower() + '/' + obj['metadata']['name'], '--timeout=60s', timeout=75)
    for obj, old in zip(desired_objects, existing, strict=True):
        if old or obj['kind'] not in ('PersistentVolumeClaim', 'StatefulSet'): continue
        print('REDIS_RUNTIME_STEP=' + ('claim' if obj['kind'] == 'PersistentVolumeClaim' else 'workload'),
              file=sys.stderr, flush=True)
        defaulted = json.loads(kube('create', '--dry-run=server', '-f', '-', '-o', 'json', body=obj))
        obj = copy.deepcopy(obj)
        obj['metadata'].setdefault('annotations', {})[HASH] = digest(defaulted['spec'])
        kube('create', '-f', '-', body=obj)
    # OnDelete deliberately has no automatic rollout; kubectl rollout status rejects that strategy.
    print('REDIS_RUNTIME_STEP=readiness', file=sys.stderr, flush=True)
    kube('-n', NS, 'wait', '--for=jsonpath={.status.readyReplicas}=1',
         'statefulset/' + NAME, '--timeout=180s', timeout=195)
    pod = get(kube, 'pod', NAME + '-0', NS)
    current = get(kube, 'statefulset', NAME, NS)
    require(current and pod and pod['spec']['serviceAccountName'] == NAME
        and desired_fields_match(pod, {'kind': 'Pod', **plan['statefulset']['spec']['template']})
        and pod['spec']['containers'][0]['image'] == plan['image']
        and pod['metadata'].get('labels', {}).get('istio.io/dataplane-mode') == 'ambient'
        and pod['status']['containerStatuses'][0]['ready']
        and any(r['uid'] == current['metadata']['uid'] for r in pod['metadata'].get('ownerReferences', [])),
        'READY_OWNED_REDIS_REQUIRED')
    print('REDIS_RUNTIME_STEP=verification', file=sys.stderr, flush=True)
    for obj in desired_objects:
        m = obj['metadata']
        owned(get(kube, obj['kind'], m['name'], m.get('namespace')), obj)
    claim = get(kube, 'pvc', volume.CLAIM, NS)
    require(claim and claim['status']['phase'] == 'Bound' and binding(kube, claim) == pv_uid,
            'BOUND_RETAIN_REDIS_VOLUME_REQUIRED')
    # Probe uses the PING-only identity and verified native TLS, never a password argument.
    check = plan['statefulset']['spec']['template']['spec']['containers'][0]['readinessProbe']['exec']['command']
    require(kube('-n', NS, 'exec', NAME + '-0', '-c', 'redis', '--', *check).strip() == b'', 'REDIS_TLS_PING_REQUIRED')
    actor = '--as=system:serviceaccount:' + NS + ':' + NAME
    for verb, target in (('get', 'secret/security-redis-health'), ('list', 'secrets'),
                         ('create', 'pods'), ('create', 'serviceaccounts/eso-redis-tls')):
        require(kube('auth', 'can-i', verb, target, '-n', NS, actor, expected=1).strip() == b'no',
                'REDIS_SERVICE_ACCOUNT_AUTHORITY_REJECTED')
    for service in json.loads(kube('-n', NS, 'get', 'services', '-o', 'json'))['items']:
        require(pg.cnpg.private_service(service), 'PUBLIC_DATA_SERVICE_REJECTED')
    isolation(plan, kube)
    require(preserve(kube) == before and secrets(kube, plan['public_ca_sha256']) == credentials
        and guard(initialized=True) == units, 'EXISTING_REDIS_FOUNDATION_CHANGED')
    return {'schema_version': 1, 'redis_runtime': 'Passed', 'image': plan['image'],
        'statefulset_uid': current['metadata']['uid'], 'pvc_uid': claim['metadata']['uid'], 'volume_uid': pv_uid,
        'tls_health': 'Passed', 'service_account_scope': 'Passed', 'guard': 'Passed',
        'existing_database_openbao_eso_credentials': 'Preserved', 'application_ingress': 'CLOSED',
        'public_access': 'CLOSED', 'automatic_renewal': 'Not run', 'provider_backup': 'Not run',
        'capacity': 'Not verified', 'production_readiness': 'Not verified'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--plan', required=True, type=Path)
    p.add_argument('--sha256', required=True)
    a = p.parse_args()
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(ValueError('REDIS_RUNTIME_DEADLINE')))
    signal.alarm(600)
    try:
        require(os.geteuid() == 0 and re.fullmatch('[a-f0-9]{64}', a.sha256), 'LOCAL_OWNER_AUTH_REQUIRED')
        with os.fdopen(os.open(a.plan, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as stream:
            info = os.fstat(stream.fileno())
            require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and 0 < info.st_size <= host.PLAN_BOUND,
                    'BOUNDED_PUBLIC_PLAN_REQUIRED')
            content = stream.read(host.PLAN_BOUND + 1)
        require(hashlib.sha256(content).hexdigest() == a.sha256, 'PLAN_HASH_MISMATCH')
        plan = json.loads(content)
        evidence = plan['staging']
        observed = datetime.fromisoformat(evidence['observed_at'])
        require(observed.tzinfo and 0 <= (datetime.now(timezone.utc) - observed).total_seconds() <= 5 * 86400
            and evidence['result'] == 'Passed' and re.fullmatch('[a-f0-9]{40}', evidence['source_revision'])
            and re.fullmatch('[1-9][0-9]{0,19}', evidence['run_id']), 'FRESH_REDIS_NATIVE_EVIDENCE_REQUIRED')
        services = host.preflight()
        with os.fdopen(os.open('/var/lib/hooshixstorage/redis-storage.lock',
                os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK), 'r+b') as lock:
            info = os.fstat(lock.fileno())
            require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and info.st_nlink == 1
                and stat.S_IMODE(info.st_mode) == 0o600, 'EXISTING_SAFE_REDIS_LOCK_REQUIRED')
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            host.native(['/usr/sbin/auditctl', '-m', 'HooshiX closed Redis runtime plan=' + a.sha256])
            result = execute(plan)
        require(host.snapshot_services() == services, 'HOST_SERVICE_CHANGED')
        host.kernel_audit()
        result['plan_sha256'] = a.sha256
        print(json.dumps(result, separators=(',', ':')))
        return 0
    except (ValueError, KeyError, IndexError, TypeError, OSError, subprocess.SubprocessError, host.custody.BootstrapFailed):
        print('REDIS_RUNTIME=Failed; state preserved; no reset or automatic retry', file=sys.stderr)
        return 1
    finally: signal.alarm(0)


if __name__ == '__main__': raise SystemExit(main())
