"""Owner-local ESO bootstrap; preserve existing OpenBao, CA, mesh and data."""
from __future__ import annotations

import argparse
import base64
import copy
import fcntl
import hashlib
import ipaddress
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
from upgrade_mesh_security import rendered_fields_match

NAMESPACE = 'platform-apps'
MANAGER = 'hooshix-eso-commissioning'
ACCOUNT = 'hooshix-eso'
ACCOUNTS = (ACCOUNT, ACCOUNT + '-webhook', ACCOUNT + '-cert-controller')
OLD_SELECTOR = {'matchExpressions': [{'key': 'kubernetes.io/metadata.name', 'operator': 'In',
                                      'values': ['istio-system', 'hooshix-secrets']}]}
SELECTOR = copy.deepcopy(OLD_SELECTOR)
SELECTOR['matchExpressions'][0]['values'].append(NAMESPACE)


def require(value, code):
    if not value:
        raise ValueError(code)


def addresses(kube):
    service = json.loads(kube('-n', 'default', 'get', 'service/kubernetes', '-o', 'json'))
    endpoints = json.loads(kube('-n', 'default', 'get', 'endpointslices', '-l',
                               'kubernetes.io/service-name=kubernetes', '-o', 'json'))
    result = {service['spec']['clusterIP']}
    for item in endpoints['items']:
        require(item.get('addressType') == 'IPv4', 'ESO_IPV4_API_REQUIRED')
        for endpoint in item['endpoints']:
            result.update(endpoint['addresses'])
    require(1 <= len(result) <= 16, 'ESO_API_ADDRESS_BOUND')
    for address in result:
        ip = ipaddress.IPv4Address(address)
        require(str(ip) == address and not (ip.is_loopback or ip.is_link_local or ip.is_multicast
            or ip.is_reserved or ip.is_unspecified or address.startswith('0.')), 'ESO_API_ADDRESS_REJECTED')
    return sorted(result)


def preserved(kube):
    result = []
    for namespace, kind, name in [('istio-system', 'secret', 'cacerts'),
            ('hooshix-secrets', 'secret', 'openbao-server-tls')]:
        result.append(kube('-n', namespace, 'get', kind, name, '-o',
                           'jsonpath={.metadata.uid}:{.metadata.resourceVersion}'))
        require(result[-1].strip(), 'ESO_PRESERVED_CA_REQUIRED')
    for namespace, kind, name in [('istio-system', 'deployment', 'istiod'),
            ('istio-system', 'daemonset', 'istio-cni-node'), ('istio-system', 'daemonset', 'ztunnel'),
            ('hooshix-secrets', 'statefulset', 'openbao'),
            ('hooshix-secrets', 'pvc', 'data-openbao-0'), ('hooshix-secrets', 'pod', 'openbao-0')]:
        value = json.loads(kube('-n', namespace, 'get', kind, name, '-o', 'json'))
        if kind == 'pod':
            statuses = value.get('status', {}).get('containerStatuses', [])
            require(value.get('status', {}).get('phase') == 'Running'
                    and statuses and all(item['ready'] for item in statuses), 'ESO_UNSEALED_BAO_REQUIRED')
            result.append([item['restartCount'] for item in statuses])
        result.append({'uid': value['metadata']['uid'], 'spec': value['spec']})
    return result


def probe(value, kube, *, expected=0, reason=None):
    deadline = time.monotonic() + 45
    while True:
        try:
            kube('create', '--dry-run=server', '-f', '-', body=value,
                 expected=expected, required_error=reason)
            return
        except (ValueError, host.custody.BootstrapFailed):
            if time.monotonic() >= deadline:
                raise
            time.sleep(3)


def execute(plan, *, kube=host.kube):
    require(plan.get('schema_version') == 1 and plan.get('installation_id') == 'hooshix-production'
            and plan.get('profile') == 'production-single-server'
            and set(plan['pods']) == set(ACCOUNTS), 'ESO_BOUNDED_PLAN_REQUIRED')
    before = preserved(kube)
    for kind, name, component in (('deployment', 'istiod', 'istiod'),
            ('daemonset', 'istio-cni-node', 'cni'), ('daemonset', 'ztunnel', 'ztunnel')):
        obj = json.loads(kube('-n', 'istio-system', 'get', kind, name, '-o', 'json'))
        require(obj['spec']['template']['spec']['containers'][0]['image'] == plan['mesh_images'][component],
                'ESO_REVIEWED_MESH_REQUIRED')
    namespace = json.loads(kube('get', 'namespace', NAMESPACE, '-o', 'json'))
    labels = namespace['metadata'].get('labels', {})
    require(labels.get('istio.io/dataplane-mode') == 'ambient'
            and labels.get('pod-security.kubernetes.io/enforce') == 'restricted'
            and labels.get('pod-security.kubernetes.io/enforce-version') == 'v1.35', 'ESO_NAMESPACE_CONFLICT')
    workloads = json.loads(kube('-n', NAMESPACE, 'get', 'pods', '-o', 'json'))['items']
    require(all(pod['spec'].get('serviceAccountName') in ACCOUNTS for pod in workloads),
            'ESO_APPLICATION_WORKLOAD_ALREADY_PRESENT')
    ca = json.loads(kube('-n', NAMESPACE, 'get', 'configmap/hooshix-openbao-ca', '-o', 'json'))
    require(hashlib.sha256(ca['data']['ca.crt'].encode()).hexdigest() == plan['public_ca_sha256'],
            'ESO_EXISTING_PUBLIC_CA_CONFLICT')
    known_crds = {}
    for crd in plan['crds']:
        raw = kube('get', 'crd', crd['metadata']['name'], '--ignore-not-found', '-o', 'name')
        known_crds[crd['metadata']['name']] = bool(raw.strip())
    if known_crds['externalsecrets.external-secrets.io']:
        require(json.loads(kube('-n', NAMESPACE, 'get', 'externalsecrets', '-o', 'json')).get('items', []) == [],
                'ESO_APPLICATION_DELIVERY_ALREADY_PRESENT')
    # Discover audiences from a fresh authenticated API TokenRequest, never from owner input.
    account = plan['stores'][0]['spec']['provider']['vault']['auth']['kubernetes']['serviceAccountRef']['name']
    jwt = kube('-n', NAMESPACE, 'create', 'token', account, '--duration=10m').decode().strip()
    payload = jwt.split('.')[1]
    audiences = json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))['aud']
    audiences = [audiences] if isinstance(audiences, str) else audiences
    require(isinstance(audiences, list) and 1 <= len(audiences) <= 4
            and all(isinstance(a, str) and 1 <= len(a) <= 256 for a in audiences), 'ESO_API_AUDIENCE_REJECTED')
    network = copy.deepcopy(plan['network'])
    peers = [{'ipBlock': {'cidr': address + '/32'}} for address in addresses(kube)]
    network[0]['spec']['egress'][0]['to'] = peers
    network[1]['spec']['ingress'][0]['from'] = peers
    stores = copy.deepcopy(plan['stores'])
    for store in stores:
        ref = store['spec']['provider']['vault']['auth']['kubernetes']['serviceAccountRef']
        kube('-n', NAMESPACE, 'get', 'serviceaccount', ref['name'], '-o', 'name')
        ref['audiences'] = list(dict.fromkeys([*audiences, 'hooshix-openbao']))
    objects = copy.deepcopy([*plan['crds'], *network, plan['authorization'], *plan['manifest']['items']]) + stores
    for obj in objects:
        obj['metadata'].setdefault('labels', {})['app.kubernetes.io/managed-by'] = MANAGER

    def read(obj):
        metadata = obj['metadata']
        if obj['kind'] == 'SecretStore' and not known_crds['secretstores.external-secrets.io']:
            return None
        scope = ['-n', metadata['namespace']] if 'namespace' in metadata else []
        # Serving certificate material is owned by the cert-controller, not this installer.
        output = ('jsonpath={.metadata.uid}:{.metadata.labels.app\\.kubernetes\\.io/managed-by}'
                  if obj['kind'] == 'Secret' else 'json')
        raw = kube(*scope, 'get', obj['kind'], metadata['name'], '--ignore-not-found', '-o', output,
                   output_bound=host.CRD_BOUND if obj['kind'] == 'CustomResourceDefinition' else host.BOUND)
        return raw if obj['kind'] == 'Secret' else (json.loads(raw) if raw.strip() else None)

    # Preflight every object before any write. Never adopt unknown state or force ownership.
    existing = []
    for obj in objects:
        old = read(obj)
        if old:
            require((old.decode().split(':', 1)[-1] == MANAGER if obj['kind'] == 'Secret' else
                    old['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == MANAGER),
                    'ESO_RESOURCE_OWNERSHIP_CONFLICT')
            if obj['kind'] != 'Secret':
                require(rendered_fields_match(old, obj), 'ESO_RESOURCE_DRIFT_PRESERVED')
        existing.append(bool(old))
    for policy in plan['policies']:
        policy['metadata'].setdefault('labels', {})['app.kubernetes.io/managed-by'] = MANAGER
        old = read(policy)
        if old:
            require(old['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == MANAGER
                    and rendered_fields_match(old, policy), 'ESO_POLICY_DRIFT_PRESERVED')
    pull = json.loads(kube('-n', 'istio-system', 'get', 'secret', 'hooshix-ghcr-read', '-o', 'json'))
    require(all(pull['metadata'].get('labels', {}).get(k) == v for k, v in host.LABELS.items())
            and pull.get('type') == 'kubernetes.io/dockerconfigjson'
            and set(pull.get('data', {})) == {'.dockerconfigjson'}, 'ESO_OWNED_REGISTRY_REQUIRED')
    raw = kube('-n', NAMESPACE, 'get', 'secret', 'hooshix-ghcr-read', '--ignore-not-found', '-o', 'json')
    if raw.strip():
        old_pull = json.loads(raw)
        require(all(old_pull['metadata'].get('labels', {}).get(k) == v for k, v in host.LABELS.items())
                and old_pull.get('type') == pull['type'] and set(old_pull.get('data', {})) == {'.dockerconfigjson'}
                and (old_pull['data'] == pull['data'] or not old_pull.get('immutable')),
                'ESO_FOREIGN_REGISTRY_PRESERVED')
        del old_pull
    # Shared Kyverno ValidatingPolicy webhooks must cover the same selector union.
    boundaries = []
    for name in ('hooshix-istio-system-bootstrap-boundary', 'hooshix-hooshix-secrets-bootstrap-boundary'):
        old = json.loads(kube('get', 'validatingpolicy', name, '-o', 'json'))
        selector = old['spec']['matchConstraints']['namespaceSelector']
        require(selector in (OLD_SELECTOR, SELECTOR), 'ESO_EXISTING_BOUNDARY_SELECTOR_CONFLICT')
        boundaries.append(old)
    for old in boundaries:
        if old['spec']['matchConstraints']['namespaceSelector'] != SELECTOR:
            kube('patch', 'validatingpolicy', old['metadata']['name'], '--type=json', '-p', json.dumps([
                # Status reconciliation changes resourceVersion, not the reviewed spec or identity.
                {'op': 'test', 'path': '/metadata/uid', 'value': old['metadata']['uid']},
                {'op': 'test', 'path': '/spec', 'value': old['spec']},
                {'op': 'replace', 'path': '/spec/matchConstraints/namespaceSelector', 'value': SELECTOR}]))
    for policy in plan['policies']:
        kube('apply', '--server-side', '--field-manager=' + MANAGER, '-f', '-', body=policy)
        kube('wait', '--for=jsonpath={.status.conditionStatus.ready}=true', policy['kind'] + '/' +
             policy['metadata']['name'], '--timeout=60s', timeout=75)
    # Reuse only existing owned read-only pull credentials, in memory; never prompt or output them.
    host.write_registry_secret(NAMESPACE, pull['data']['.dockerconfigjson'], kube_call=kube)
    del pull
    for obj, present in zip(objects, existing):
        if obj['kind'] == 'SecretStore':
            continue
        if not present:
            kube('create', '-f', '-', body=obj) if obj['kind'] != 'CustomResourceDefinition' else kube(
                'apply', '--server-side', '--field-manager=' + MANAGER, '-f', '-', body=obj)
        if obj['kind'] == 'CustomResourceDefinition':
            kube('wait', '--for=condition=Established', 'crd/' + obj['metadata']['name'], '--timeout=30s', timeout=40)
    for name in ACCOUNTS:
        deployment = next(obj for obj in plan['manifest']['items'] if obj['kind'] == 'Deployment'
                          and obj['metadata']['name'] == name)
        test = {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': 'hooshix-eso-admission-probe',
            'namespace': NAMESPACE, 'labels': deployment['spec']['template']['metadata']['labels']},
            'spec': deployment['spec']['template']['spec']}
        probe(test, kube)
        wrong = copy.deepcopy(test)
        wrong['spec']['serviceAccountName'] = 'default'
        probe(wrong, kube, expected=1, reason=b'platform bootstrap identity/security exception rejected')
        print('ESO_INSTALL_STEP=rollout-' + name, file=sys.stderr, flush=True)
        kube('-n', NAMESPACE, 'rollout', 'status', 'deployment/' + name, '--timeout=180s', timeout=195)
    pods = json.loads(kube('-n', NAMESPACE, 'get', 'pods', '-l',
                          'app.kubernetes.io/instance=' + ACCOUNT, '-o', 'json'))['items']
    require(len(pods) == 3 and all(pod['spec']['containers'][0]['image'] == plan['image']
        and pod['status']['containerStatuses'][0]['ready']
        and pod['status']['containerStatuses'][0]['imageID'].endswith(plan['image'].split('@')[1])
        for pod in pods), 'ESO_RUNTIME_DIGEST_MISMATCH')
    for store, present in zip(stores, existing[-len(stores):]):
        store['metadata'].setdefault('labels', {})['app.kubernetes.io/managed-by'] = MANAGER
        if not present:
            kube('create', '-f', '-', body=store)
        kube('-n', NAMESPACE, 'wait', '--for=condition=Ready', 'secretstore/' + store['metadata']['name'],
             '--timeout=90s', timeout=105)
    require(preserved(kube) == before, 'ESO_PROTECTED_STATE_CHANGED')
    return {'schema_version': 1, 'eso_installation': 'Passed', 'image': plan['image'],
        'six_secretstores': 'Passed', 'openbao_ca_mesh_storage': 'Preserved',
        'application_secret_delivery': 'Not verified', 'root_revocation': 'Not run',
        'public_access': 'CLOSED', 'production_readiness': 'Not verified'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--sha256', required=True)
    args = parser.parse_args()
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(ValueError('ESO_DEADLINE')))
    signal.alarm(1200)
    try:
        require(os.getuid() == 0 and re.fullmatch(r'[a-f0-9]{64}', args.sha256), 'ESO_SUPERVISED_ROOT_REQUIRED')
        require(not args.plan.is_symlink() and args.plan.is_file()
                and args.plan.stat().st_size <= host.PLAN_BOUND, 'ESO_PUBLIC_PLAN_BOUND')
        data = args.plan.read_bytes()
        require(hashlib.sha256(data).hexdigest() == args.sha256, 'ESO_PLAN_HASH_MISMATCH')
        plan = json.loads(data)
        observed = datetime.fromisoformat(plan['staging']['observed_at'])
        require(observed.tzinfo and 0 <= (datetime.now(timezone.utc) - observed).total_seconds() <= 5 * 86400
                and plan['staging']['result'] == 'Passed'
                and re.fullmatch(r'[a-f0-9]{40}', plan['staging']['source_revision'])
                and re.fullmatch(r'[1-9][0-9]{0,19}', plan['staging']['run_id']), 'ESO_FRESH_STAGING_REQUIRED')
        host.kernel_audit()
        services = host.snapshot_services()
        with os.fdopen(os.open('/run/lock/hooshix-eso-commissioning.lock',
                              os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600), 'r+b') as lock:
            info = os.fstat(lock.fileno())
            require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and info.st_nlink == 1
                    and stat.S_IMODE(info.st_mode) == 0o600, 'ESO_SAFE_LOCK_REQUIRED')
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            host.native(['/usr/sbin/auditctl', '-m', 'HooshiX ESO commissioning plan=' + args.sha256])
            result = execute(plan)
        require(host.snapshot_services() == services, 'ESO_PRESERVED_HOST_SERVICE_CHANGED')
        host.kernel_audit()
        result['plan_sha256'] = args.sha256
        print(json.dumps(result, separators=(',', ':')))
        return 0
    except (ValueError, KeyError, IndexError, TypeError, OSError, subprocess.SubprocessError, host.custody.BootstrapFailed):
        print('ESO_INSTALLATION=Failed; partial state preserved; no rollback or reinit', file=sys.stderr)
        return 1
    finally:
        signal.alarm(0)


if __name__ == '__main__':
    raise SystemExit(main())
