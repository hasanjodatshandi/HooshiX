"""Supervised CNPG controller only: no PostgreSQL instance or data allocation."""
from __future__ import annotations

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
import install_cert_manager as certificates
from install_eso import addresses, desired_fields_match, established_crd, mismatched_path, probe
from upgrade_mesh_security import replace_policy

MANAGER = 'hooshix-cnpg-control-plane'
NAMESPACE = 'cnpg-system'
DATA_NAMESPACE = 'platform-data'
DEPLOYMENT = 'cnpg-controller-manager'


def require(value, code):
    if not value:
        print('CNPG_INVARIANT=' + code, file=sys.stderr, flush=True)
        raise ValueError(code)


def protected(kube):
    result = certificates.protected(kube)
    for name in certificates.ACCOUNTS:
        value = json.loads(kube('-n', NAMESPACE, 'get', 'deployment', name, '-o', 'json'))
        require(value['status'].get('availableReplicas') == 1, 'CNPG_EXISTING_CERT_MANAGER_REQUIRED')
        result.append({'uid': value['metadata']['uid'], 'spec': value['spec']})
    return result


def safe_pod(pod):
    if any(pod.get(k) for k in ('hostNetwork', 'hostPID', 'hostIPC', 'shareProcessNamespace',
            'initContainers', 'ephemeralContainers')) or len(pod.get('containers', [])) != 1:
        return False
    context = pod.get('securityContext', {})
    c = pod['containers'][0]
    security = c.get('securityContext', {})
    return (not context.get('sysctls') and not context.get('windowsOptions') and not c.get('envFrom')
        and not security.get('privileged') and not security.get('capabilities', {}).get('add')
        and security.get('procMount', 'Default') == 'Default' and not security.get('windowsOptions')
        and not any(p.get('hostPort') for p in c.get('ports', []))
        and not any('hostPath' in v for v in pod.get('volumes', []))
        and not any(v.get('subPath') or v.get('subPathExpr') for v in c.get('volumeMounts', [])))


def private_service(obj):
    spec = obj['spec']
    return (spec.get('type', 'ClusterIP') == 'ClusterIP' and not spec.get('externalIPs')
        and not spec.get('externalName') and not spec.get('loadBalancerIP')
        and not any(p.get('nodePort') for p in spec['ports']))


def verify_callbacks(plan, kube):
    for obj in plan['objects']:
        if obj['kind'] not in ('MutatingWebhookConfiguration', 'ValidatingWebhookConfiguration'):
            continue
        current = json.loads(kube('get', obj['kind'], obj['metadata']['name'], '-o', 'json'))
        require(desired_fields_match(current, obj), 'CNPG_CALLBACK_CONFIGURATION_DRIFT')
        for actual, expected in zip(current['webhooks'], obj['webhooks']):
            require(not actual.get('matchConditions') and not actual.get('objectSelector')
                and actual['namespaceSelector'] == expected['namespaceSelector']
                and actual['rules'] == expected['rules'] and not actual['clientConfig'].get('url')
                and actual['clientConfig']['service'] == expected['clientConfig']['service'],
                'CNPG_CALLBACK_SCOPE_DRIFT')


def cluster_probe():
    return {'apiVersion': 'postgresql.cnpg.io/v1', 'kind': 'Cluster', 'metadata': {
        'name': 'hooshix-cnpg-callback-probe', 'namespace': DATA_NAMESPACE}, 'spec': {
        'instances': 1, 'imageCatalogRef': {'apiGroup': 'postgresql.cnpg.io', 'kind': 'ImageCatalog',
            'name': 'hooshix-postgresql-18-candidate', 'major': 18},
        'storage': {'size': '1Gi', 'storageClass': 'not-provisioned-callback-probe'}}}


def execute(plan, *, kube=host.kube):
    require(plan.get('schema_version') == 1 and plan.get('installation_id') == 'hooshix-production'
        and plan.get('profile') == 'production-single-server' and plan.get('database') == 'Not run',
        'CNPG_CONTROL_PLANE_ONLY_REQUIRED')
    print('CNPG_INSTALL_STEP=ownership-preflight', file=sys.stderr, flush=True)
    before = protected(kube)
    strict = plan['peer_authentication']
    require(strict['spec'] == {'selector': {'matchLabels': {'app.kubernetes.io/name': 'cloudnative-pg'}},
        'mtls': {'mode': 'STRICT'}, 'portLevelMtls': {'9443': {'mode': 'PERMISSIVE'}}},
        'CNPG_EXACT_APPROVED_CALLBACK_PORT_REQUIRED')
    ns = json.loads(kube('get', 'namespace', NAMESPACE, '-o', 'json'))
    require(ns['metadata']['labels'].get('istio.io/dataplane-mode') == 'ambient', 'CNPG_AMBIENT_REQUIRED')
    networks = copy.deepcopy(plan['network'])
    peers = [{'ipBlock': {'cidr': address + '/32'}} for address in addresses(kube)]
    networks[0]['spec']['egress'][0]['to'] = peers
    networks[1]['spec']['ingress'][0]['from'] = peers
    authorization = copy.deepcopy(plan['authorization'])
    authorization['spec']['rules'][0]['from'][0]['source']['ipBlocks'] = [p['ipBlock']['cidr'] for p in peers]
    objects = [*plan['crds'], *networks, strict, authorization, *plan['source_policies'], *plan['objects']]
    require(not any(o['kind'] in ('Secret', 'Cluster', 'Database', 'DatabaseRole',
            'PersistentVolume', 'PersistentVolumeClaim') for o in objects), 'CNPG_DATA_OR_CREDENTIAL_WRITE_REJECTED')
    exists = {}
    controller_uid = None
    for obj in objects:
        meta = obj['metadata']
        scope = ['-n', meta['namespace']] if 'namespace' in meta else []
        new_api = obj['kind'] == 'ImageCatalog' and not exists.get(
            ('CustomResourceDefinition', None, 'imagecatalogs.postgresql.cnpg.io'))
        raw = b'' if new_api else kube(*scope, 'get', obj['kind'], meta['name'], '--ignore-not-found', '-o', 'json',
            output_bound=host.CRD_BOUND if obj['kind'] == 'CustomResourceDefinition' else host.BOUND)
        old = json.loads(raw) if raw.strip() else None
        exists[(obj['kind'], meta.get('namespace'), meta['name'])] = bool(old)
        if old and not desired_fields_match(old, obj):
            print('CNPG_PUBLIC_DRIFT=' + json.dumps({'kind': obj['kind'], 'name': meta['name'],
                'field': mismatched_path(old, obj)}), file=sys.stderr, flush=True)
        require(not old or (old['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == MANAGER
                and desired_fields_match(old, obj)), 'CNPG_FOREIGN_OR_CHANGED_STATE_PRESERVED')
        if old and obj['kind'] == 'Deployment':
            require(safe_pod(old['spec']['template']['spec']), 'CNPG_EXTRA_PRIVILEGE_PRESERVED_NOT_ADOPTED')
            controller_uid = old['metadata'].get('uid')
        if old and obj['kind'] == 'Service':
            require(private_service(old), 'CNPG_PUBLIC_SERVICE_DRIFT')
        if old and obj['kind'] == 'NetworkPolicy':
            spec = copy.deepcopy(old['spec'])
            for direction in ('ingress', 'egress'):
                if obj['spec'].get(direction) == []: spec.setdefault(direction, [])
            require(spec == obj['spec'], 'CNPG_NETWORK_SECURITY_DRIFT')
        if old and obj['kind'] in ('PeerAuthentication', 'AuthorizationPolicy'):
            require(old['spec'] == obj['spec'] and old['metadata'].get('annotations', {}).get('istio.io/dry-run')
                in (None, 'false'), 'CNPG_MESH_SECURITY_DRIFT')
        if old and obj['kind'] in ('ValidatingPolicy', 'ImageValidatingPolicy'):
            require(not old['spec'].get('matchConditions')
                and not old['spec']['matchConstraints'].get('excludeResourceRules')
                and old['spec']['matchConstraints'].get('namespaceSelector')
                    == obj['spec']['matchConstraints'].get('namespaceSelector'), 'CNPG_ADMISSION_SCOPE_DRIFT')
        if old and obj['kind'] == 'CustomResourceDefinition':
            require(old['spec'].get('conversion', {}).get('strategy', 'None')
                == obj['spec'].get('conversion', {}).get('strategy', 'None'), 'CNPG_CRD_CONVERSION_DRIFT')
    for name in ('cnpg-ca-secret', 'cnpg-webhook-cert'):
        # CNPG owns generated keys; inspect owner metadata only, never the Secret body.
        raw = kube('-n', NAMESPACE, 'get', 'secret', name, '--ignore-not-found', '-o',
            'jsonpath={.metadata.uid}:{range .metadata.ownerReferences[*]}{.kind},{.name},{.uid},{.controller};{end}')
        expected = ('Deployment,' + DEPLOYMENT + ',' + str(controller_uid) + ',true;').encode()
        require(not raw.strip() or controller_uid and raw.partition(b':')[2] == expected,
                'CNPG_FOREIGN_GENERATED_CA_PRESERVED')
    old = json.loads(kube('get', 'validatingpolicy', plan['boundary']['metadata']['name'], '-o', 'json'))
    require(old['spec'] in (plan['previous_boundary']['spec'], plan['boundary']['spec']), 'CNPG_EXISTING_BOUNDARY_CONFLICT')
    data = kube('-n', DATA_NAMESPACE, 'get', 'pods,pvc', '--ignore-not-found', '-o', 'json')
    require(not json.loads(data).get('items'), 'CNPG_EXISTING_DATA_WORKLOAD_PRESERVED')
    if exists[('CustomResourceDefinition', None, 'clusters.postgresql.cnpg.io')]:
        require(not json.loads(kube('-n', DATA_NAMESPACE, 'get', 'clusters.postgresql.cnpg.io', '-o', 'json')).get('items'),
                'CNPG_EXISTING_DATA_WORKLOAD_PRESERVED')

    def create(obj):
        if not exists[(obj['kind'], obj['metadata'].get('namespace'), obj['metadata']['name'])]:
            if obj['kind'] == 'CustomResourceDefinition':
                kube('apply', '--server-side', '--field-manager=' + MANAGER, '-f', '-', body=obj)
            else:
                kube('create', '-f', '-', body=obj)

    for obj in plan['objects']:
        if obj['kind'] == 'Namespace': create(obj)
    print('CNPG_INSTALL_STEP=crds', file=sys.stderr, flush=True)
    for obj in plan['crds']:
        create(obj)
        established_crd(obj['metadata']['name'], kube)
    for obj in [strict, authorization, *networks]: create(obj)
    if old['spec'] != plan['boundary']['spec']:
        replace_policy(plan['boundary'], get=lambda kind, name: json.loads(kube('get', kind, name, '-o', 'json')),
                       kube=kube, previous=plan['previous_boundary'])
    for obj in [plan['boundary'], *plan['source_policies']]:
        if obj is not plan['boundary']: create(obj)
        print('CNPG_INSTALL_STEP=policy-' + obj['metadata']['name'], file=sys.stderr, flush=True)
        kube('wait', '--for=jsonpath={.status.conditionStatus.ready}=true',
             obj['kind'].lower() + '/' + obj['metadata']['name'], '--timeout=60s', timeout=75)
    for obj in plan['objects']:
        if obj['kind'] not in ('Namespace', 'Deployment'):
            create(obj)
    # CNPG's own PKI setup patches these before it becomes Ready; data is still empty/closed.
    print('CNPG_INSTALL_STEP=controller-rollout', file=sys.stderr, flush=True)
    create(next(o for o in plan['objects'] if o['kind'] == 'Deployment'))
    kube('-n', NAMESPACE, 'rollout', 'status', 'deployment/' + DEPLOYMENT, '--timeout=180s', timeout=195)
    require(private_service(json.loads(kube('-n', NAMESPACE, 'get', 'service', 'cnpg-webhook-service', '-o', 'json'))),
            'CNPG_PUBLIC_SERVICE_DRIFT')
    probe(cluster_probe(), kube)
    verify_callbacks(plan, kube)
    pods = json.loads(kube('-n', NAMESPACE, 'get', 'pods', '-l', 'app.kubernetes.io/name=cloudnative-pg', '-o', 'json'))['items']
    deployment = next(o for o in plan['objects'] if o['kind'] == 'Deployment')
    require(len(pods) == 1 and pods[0]['spec']['serviceAccountName'] == 'cnpg-manager'
        and safe_pod(pods[0]['spec']) and desired_fields_match(
            {'kind': 'Deployment', 'spec': {'template': pods[0]}},
            {'kind': 'Deployment', 'spec': {'template': deployment['spec']['template']}})
        and pods[0]['spec']['containers'][0]['image'] == plan['images']['operator']
        and pods[0]['status']['containerStatuses'][0]['ready']
        and pods[0]['status']['containerStatuses'][0]['imageID'].endswith(plan['images']['operator'].split('@')[1]),
        'CNPG_EXACT_READY_RUNTIME_REQUIRED')
    require(json.loads(kube('-n', NAMESPACE, 'get', 'peerauthentication', strict['metadata']['name'], '-o', 'json'))['spec']
        == strict['spec'], 'CNPG_CALLBACK_PORT_DRIFT')
    require(protected(kube) == before, 'CNPG_PROTECTED_COMPONENT_CHANGED')
    return {'schema_version': 1, 'cnpg_control_plane': 'Passed', 'images': plan['images'],
        'api_callback_tls': 'Passed', 'callback_configuration': 'Passed', 'ambient_callback_port_scope': 'Passed',
        'root_mesh_openbao_eso_cert_manager_storage': 'Preserved', 'database': 'Not run',
        'barman_runtime': 'Not run', 'public_access': 'CLOSED', 'production_readiness': 'Not verified'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--plan', type=Path, required=True)
    p.add_argument('--sha256', required=True)
    a = p.parse_args()
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(ValueError('CNPG_DEADLINE')))
    signal.alarm(1200)
    try:
        require(os.getuid() == 0 and re.fullmatch(r'[a-f0-9]{64}', a.sha256), 'CNPG_LOCAL_AUTH_REQUIRED')
        require(not a.plan.is_symlink() and a.plan.is_file() and a.plan.stat().st_size <= host.PLAN_BOUND,
                'CNPG_BOUNDED_PUBLIC_PLAN_REQUIRED')
        content = a.plan.read_bytes()
        require(hashlib.sha256(content).hexdigest() == a.sha256, 'CNPG_PLAN_HASH_MISMATCH')
        plan = json.loads(content)
        evidence = plan['staging']
        observed = datetime.fromisoformat(evidence['observed_at'])
        require(observed.tzinfo and 0 <= (datetime.now(timezone.utc) - observed).total_seconds() <= 5 * 86400
            and evidence['result'] == 'Passed' and re.fullmatch(r'[a-f0-9]{40}', evidence['source_revision'])
            and re.fullmatch(r'[1-9][0-9]{0,19}', evidence['run_id']), 'CNPG_FRESH_NATIVE_EVIDENCE_REQUIRED')
        services = host.preflight()
        with os.fdopen(os.open('/run/lock/hooshix-cnpg-control-plane.lock',
                os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600), 'r+b') as lock:
            info = os.fstat(lock.fileno())
            require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and info.st_nlink == 1
                and stat.S_IMODE(info.st_mode) == 0o600, 'CNPG_SAFE_LOCK_REQUIRED')
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            host.native(['/usr/sbin/auditctl', '-m', 'HooshiX CNPG control plane plan=' + a.sha256])
            result = execute(plan)
        require(host.snapshot_services() == services, 'CNPG_HOST_SERVICE_CHANGED')
        host.kernel_audit()
        result['plan_sha256'] = a.sha256
        print(json.dumps(result, separators=(',', ':')))
        return 0
    except (ValueError, KeyError, IndexError, TypeError, OSError, subprocess.SubprocessError, host.custody.BootstrapFailed):
        print('CNPG_CONTROL_PLANE=Failed; partial state preserved; no automatic retry', file=sys.stderr)
        return 1
    finally:
        signal.alarm(0)


if __name__ == '__main__':
    raise SystemExit(main())
