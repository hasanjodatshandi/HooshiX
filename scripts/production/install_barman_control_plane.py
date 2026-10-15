"""Supervised Barman internal controller; no database, backup or provider access."""
import argparse
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
import install_cnpg_control_plane as cnpg
import install_postgresql_local_volume as volume
from install_eso import addresses, desired_fields_match, established_crd, mismatched_path
from upgrade_mesh_security import replace_policy

MANAGER = 'hooshix-barman-control-plane'
NAMESPACE = 'cnpg-system'
ACCOUNT = 'plugin-barman-cloud'
DEPLOYMENT = 'barman-cloud'
TLS = {'barman-cloud-client-tls': 'barman-cloud-client', 'barman-cloud-server-tls': 'barman-cloud-server'}


def require(value, code):
    if not value:
        print('BARMAN_INVARIANT=' + code, file=sys.stderr, flush=True)
        raise ValueError(code)


def protected(kube):
    result = cnpg.protected(kube)
    d = json.loads(kube('-n', NAMESPACE, 'get', 'deployment', cnpg.DEPLOYMENT, '-o', 'json'))
    require(d['status'].get('availableReplicas') == 1, 'BARMAN_EXISTING_CNPG_REQUIRED')
    result.append({'uid': d['metadata']['uid'], 'spec': d['spec']})
    for desired in volume.candidate():
        obj = json.loads(kube('get', desired['kind'], desired['metadata']['name'], '-o', 'json'))
        require(volume.matches(obj, desired), 'BARMAN_GUARDED_RESERVATION_REQUIRED')
        result.append({'uid': obj['metadata']['uid'], 'spec': obj.get('spec', {
            k: obj[k] for k in ('provisioner', 'reclaimPolicy', 'volumeBindingMode', 'allowVolumeExpansion')
            if k in obj})})
    return result


def verify_rbac(kube):
    actor = '--as=system:serviceaccount:' + NAMESPACE + ':' + ACCOUNT
    # SubjectAccessReview only; no real Secret values are requested.
    for namespace in ('platform-data', NAMESPACE, 'platform-apps', 'hooshix-secrets', 'istio-system'):
        for verb in ('get', 'list', 'watch', 'create', 'patch', 'delete'):
            value = kube('auth', 'can-i', verb, 'secrets', '-n', namespace, actor, expected=1)
            require(value.strip() == b'no', 'BARMAN_SECRET_API_ACCESS_REJECTED')
    for group, names in (('barmancloud.cnpg.io', ('objectstores',)),
                         ('rbac.authorization.k8s.io', ('roles', 'rolebindings'))):
        for name in names:
            for verb in ('list', 'watch'):
                require(kube('auth', 'can-i', verb, name + '.' + group, '--all-namespaces', actor).strip() == b'yes',
                        'BARMAN_APPROVED_CACHE_READ_REQUIRED')
            for namespace in (NAMESPACE, 'platform-apps', 'hooshix-secrets'):
                for verb in ('create', 'patch', 'update', 'delete'):
                    require(kube('auth', 'can-i', verb, name + '.' + group, '-n', namespace,
                                 actor, expected=1).strip() == b'no', 'BARMAN_FOREIGN_WRITE_REJECTED')


def execute(plan, *, kube=host.kube):
    require(plan.get('schema_version') == 1 and plan.get('installation_id') == 'hooshix-production'
        and plan.get('profile') == 'production-single-server'
        and plan.get('metadata_cache_trust') == 'owner-approved-global-list-watch-no-secret-api'
        and plan.get('database') == 'Not run', 'BARMAN_BOUNDED_CONTROL_PLANE_REQUIRED')
    print('BARMAN_INSTALL_STEP=ownership-preflight', file=sys.stderr, flush=True)
    before = protected(kube)
    strict = plan['existing_strict']
    require(json.loads(kube('-n', NAMESPACE, 'get', 'peerauthentication', strict['metadata']['name'], '-o', 'json'))['spec']
        == {'mtls': {'mode': 'STRICT'}}, 'BARMAN_EXISTING_STRICT_REQUIRED')
    ns = json.loads(kube('get', 'namespace', NAMESPACE, '-o', 'json'))
    require(ns['metadata']['labels'].get('istio.io/dataplane-mode') == 'ambient', 'BARMAN_AMBIENT_REQUIRED')
    for kind in ('pods', 'pvc', 'clusters.postgresql.cnpg.io'):
        require(not json.loads(kube('-n', 'platform-data', 'get', kind, '-o', 'json'))['items'], 'BARMAN_EXISTING_DATA_PRESERVED')
    networks = copy.deepcopy(plan['network'])
    peers = [{'ipBlock': {'cidr': a + '/32'}} for a in addresses(kube)]
    networks[0]['spec']['egress'][0]['to'] = peers
    networks[0]['spec']['ingress'][0]['from'] = peers
    objects = [*plan['crds'], *networks, plan['authorization'], *plan['source_policies'], *plan['objects']]
    require(not any(o['kind'] in ('Secret', 'Cluster', 'Database', 'ObjectStore', 'PersistentVolume',
                                  'PersistentVolumeClaim') for o in objects), 'BARMAN_DATA_OR_CREDENTIAL_WRITE_REJECTED')
    exists = {}
    for desired in objects:
        m = desired['metadata']
        scope = ['-n', m['namespace']] if 'namespace' in m else []
        raw = kube(*scope, 'get', desired['kind'], m['name'], '--ignore-not-found', '-o', 'json',
                   output_bound=host.CRD_BOUND if desired['kind'] == 'CustomResourceDefinition' else host.BOUND)
        actual = json.loads(raw) if raw.strip() else None
        exists[(desired['kind'], m.get('namespace'), m['name'])] = bool(actual)
        if actual and not desired_fields_match(actual, desired):
            print('BARMAN_PUBLIC_DRIFT=' + json.dumps({'kind': desired['kind'], 'name': m['name'],
                'field': mismatched_path(actual, desired)}), file=sys.stderr, flush=True)
        require(not actual or (actual['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == MANAGER
            and desired_fields_match(actual, desired)), 'BARMAN_FOREIGN_OR_CHANGED_STATE_PRESERVED')
        if not actual: continue
        kind = desired['kind']
        if kind in ('Role', 'ClusterRole'):
            require(actual['rules'] == desired['rules'], 'BARMAN_EXTRA_RBAC_REJECTED')
        elif kind in ('RoleBinding', 'ClusterRoleBinding'):
            require(actual['subjects'] == desired['subjects'] and actual['roleRef'] == desired['roleRef'], 'BARMAN_EXTRA_SUBJECT_REJECTED')
        elif kind == 'Deployment':
            require(cnpg.safe_pod(actual['spec']['template']['spec']), 'BARMAN_EXTRA_PRIVILEGE_REJECTED')
        elif kind == 'Service':
            require(cnpg.private_service(actual), 'BARMAN_PUBLIC_SERVICE_REJECTED')
        elif kind in ('NetworkPolicy', 'AuthorizationPolicy'):
            spec = copy.deepcopy(actual['spec'])
            for direction in ('ingress', 'egress'):
                if kind == 'NetworkPolicy' and desired['spec'].get(direction) == []: spec.setdefault(direction, [])
            require(spec == desired['spec'] and actual['metadata'].get('annotations', {}).get('istio.io/dry-run')
                    in (None, 'false'), 'BARMAN_NETWORK_OR_MESH_DRIFT')
        elif kind in ('ValidatingPolicy', 'ImageValidatingPolicy'):
            require(not actual['spec'].get('matchConditions')
                and actual['spec']['matchConstraints'] == desired['spec']['matchConstraints'], 'BARMAN_ADMISSION_SCOPE_DRIFT')
    for secret, certificate in TLS.items():
        raw = kube('-n', NAMESPACE, 'get', 'secret', secret, '--ignore-not-found', '-o',
                   'jsonpath={.metadata.uid}:{.metadata.labels.app\\.kubernetes\\.io/managed-by}')
        require(not raw.strip() or raw.strip().endswith(b':' + MANAGER.encode())
            and exists[('Certificate', NAMESPACE, certificate)], 'BARMAN_FOREIGN_TLS_KEY_PRESERVED')
    previous = json.loads(kube('get', 'validatingpolicy', plan['boundary']['metadata']['name'], '-o', 'json'))
    require(previous['spec'] in (plan['previous_boundary']['spec'], plan['boundary']['spec']), 'BARMAN_PREVIOUS_BOUNDARY_CONFLICT')

    def create(obj):
        m = obj['metadata']
        key = obj['kind'], m.get('namespace'), m['name']
        if not exists[key]:
            if obj['kind'] == 'CustomResourceDefinition':
                kube('apply', '--server-side', '--field-manager=' + MANAGER, '-f', '-', body=obj)
            else: kube('create', '-f', '-', body=obj)
            exists[key] = True

    for obj in plan['crds']:
        create(obj)
        established_crd(obj['metadata']['name'], kube)
    for obj in [*networks, plan['authorization']]: create(obj)
    if previous['spec'] != plan['boundary']['spec']:
        replace_policy(plan['boundary'], get=lambda kind, name: json.loads(kube('get', kind, name, '-o', 'json')),
                       kube=kube, previous=plan['previous_boundary'])
    for obj in [plan['boundary'], *plan['source_policies']]:
        if obj is not plan['boundary']: create(obj)
        kube('wait', '--for=jsonpath={.status.conditionStatus.ready}=true',
             obj['kind'].lower() + '/' + obj['metadata']['name'], '--timeout=60s', timeout=75)
    for obj in plan['objects']:
        if obj['kind'] != 'Deployment': create(obj)
    print('BARMAN_INSTALL_STEP=internal-certificates', file=sys.stderr, flush=True)
    for name in TLS.values():
        kube('-n', NAMESPACE, 'wait', '--for=condition=Ready', 'certificate/' + name, '--timeout=120s', timeout=135)
    verify_rbac(kube)
    print('BARMAN_INSTALL_STEP=controller-rollout', file=sys.stderr, flush=True)
    desired = next(o for o in plan['objects'] if o['kind'] == 'Deployment')
    create(desired)
    kube('-n', NAMESPACE, 'rollout', 'status', 'deployment/' + DEPLOYMENT, '--timeout=180s', timeout=195)
    pods = json.loads(kube('-n', NAMESPACE, 'get', 'pods', '-l', 'app=barman-cloud', '-o', 'json'))['items']
    require(len(pods) == 1 and cnpg.safe_pod(pods[0]['spec']) and desired_fields_match(
        {'kind': 'Deployment', 'spec': {'template': pods[0]}},
        {'kind': 'Deployment', 'spec': {'template': desired['spec']['template']}})
        and pods[0]['status']['containerStatuses'][0]['ready']
        and pods[0]['status']['containerStatuses'][0]['imageID'].endswith(plan['images']['barman-plugin'].split('@')[1]),
        'BARMAN_EXACT_READY_PLUGIN_REQUIRED')
    require(cnpg.private_service(json.loads(kube('-n', NAMESPACE, 'get', 'service', DEPLOYMENT, '-o', 'json'))),
            'BARMAN_PUBLIC_SERVICE_REJECTED')
    require(protected(kube) == before, 'BARMAN_PROTECTED_COMPONENT_CHANGED')
    return {'schema_version': 1, 'barman_control_plane': 'Passed', 'images': plan['images'],
        'internal_certificates': 'Passed', 'secret_api_denied': 'Passed', 'foreign_write_denied': 'Passed',
        'root_mesh_openbao_eso_cnpg_storage': 'Preserved', 'database': 'Not run',
        'barman_sidecar': 'Not run', 'provider_backup': 'Not run', 'public_access': 'CLOSED',
        'production_readiness': 'Not verified'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--plan', type=Path, required=True)
    p.add_argument('--sha256', required=True)
    a = p.parse_args()
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(ValueError('BARMAN_DEADLINE')))
    signal.alarm(1200)
    try:
        require(os.geteuid() == 0 and re.fullmatch('[a-f0-9]{64}', a.sha256), 'BARMAN_LOCAL_AUTH_REQUIRED')
        require(not a.plan.is_symlink() and a.plan.is_file() and a.plan.stat().st_size <= host.PLAN_BOUND, 'BARMAN_BOUNDED_PLAN_REQUIRED')
        content = a.plan.read_bytes()
        require(hashlib.sha256(content).hexdigest() == a.sha256, 'BARMAN_PLAN_HASH_MISMATCH')
        plan = json.loads(content)
        evidence = plan['staging']
        observed = datetime.fromisoformat(evidence['observed_at'])
        require(observed.tzinfo and 0 <= (datetime.now(timezone.utc) - observed).total_seconds() <= 5 * 86400
            and evidence['result'] == 'Passed' and re.fullmatch('[a-f0-9]{40}', evidence['source_revision'])
            and re.fullmatch('[1-9][0-9]{0,19}', evidence['run_id']), 'BARMAN_FRESH_NATIVE_EVIDENCE_REQUIRED')
        services = host.preflight()
        before = volume.foundation()
        with os.fdopen(os.open('/run/lock/hooshix-barman-control-plane.lock',
                os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600), 'r+b') as lock:
            info = os.fstat(lock.fileno())
            require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and info.st_nlink == 1
                and stat.S_IMODE(info.st_mode) == 0o600, 'BARMAN_SAFE_LOCK_REQUIRED')
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            host.native(['/usr/sbin/auditctl', '-m', 'HooshiX bounded Barman control plane plan=' + a.sha256])
            result = execute(plan)
        require(volume.foundation() == before and host.snapshot_services() == services, 'BARMAN_HOST_OR_STORAGE_CHANGED')
        host.kernel_audit()
        result['plan_sha256'] = a.sha256
        print(json.dumps(result, separators=(',', ':')))
        return 0
    except (ValueError, KeyError, IndexError, TypeError, OSError, subprocess.SubprocessError, host.custody.BootstrapFailed):
        print('BARMAN_CONTROL_PLANE=Failed; partial state preserved; no automatic retry', file=sys.stderr)
        return 1
    finally:
        signal.alarm(0)


if __name__ == '__main__':
    raise SystemExit(main())
