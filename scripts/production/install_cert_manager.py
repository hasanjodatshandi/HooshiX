"""Supervised internal cert-manager installation; no Root, OpenBao or data changes."""
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
from install_eso import addresses, desired_fields_match, established_crd, mismatched_path, preserved, probe, require as eso_require

NAMESPACE = 'cnpg-system'
MANAGER = 'hooshix-cert-manager-commissioning'
ACCOUNTS = ('cert-manager', 'cert-manager-cainjector', 'cert-manager-webhook')
OLD_SELECTOR = {'matchExpressions': [{'key': 'kubernetes.io/metadata.name', 'operator': 'In',
    'values': ['istio-system', 'hooshix-secrets', 'platform-apps']}]}
SELECTOR = copy.deepcopy(OLD_SELECTOR)
SELECTOR['matchExpressions'][0]['values'].append(NAMESPACE)
BOUNDARIES = ('hooshix-istio-system-bootstrap-boundary', 'hooshix-hooshix-secrets-bootstrap-boundary',
              'hooshix-platform-apps-bootstrap-boundary')


def require(value, code):
    if not value:
        # Only fixed caller-owned invariant identifiers, never actual object values.
        print('CERT_MANAGER_INVARIANT=' + code, file=sys.stderr, flush=True)
    eso_require(value, code)


def protected(kube):
    result = preserved(kube)
    for account in ('hooshix-eso', 'hooshix-eso-webhook', 'hooshix-eso-cert-controller'):
        value = json.loads(kube('-n', 'platform-apps', 'get', 'deployment', account, '-o', 'json'))
        require(value['status'].get('availableReplicas') == 1, 'CERT_MANAGER_EXISTING_ESO_REQUIRED')
        result.append({'uid': value['metadata']['uid'], 'spec': value['spec']})
    return result


def verify_callbacks(plan, kube):
    # Configuration check, not an admission lock: the owner accepts exact-name injector writes.
    for obj in plan['manifest']['items']:
        if obj['kind'] not in ('MutatingWebhookConfiguration', 'ValidatingWebhookConfiguration'):
            continue
        current = json.loads(kube('get', obj['kind'], obj['metadata']['name'], '-o', 'json'))
        require(desired_fields_match(current, obj), 'CERT_MANAGER_CALLBACK_CONFIGURATION_DRIFT')
        hook = current['webhooks'][0]
        expected = obj['webhooks'][0]
        require(not hook.get('matchConditions') and not hook.get('objectSelector')
                and hook['namespaceSelector'] == expected['namespaceSelector']
                and hook['rules'] == expected['rules'] and not hook['clientConfig'].get('url')
                and hook['clientConfig']['service'] == expected['clientConfig']['service'],
                'CERT_MANAGER_CALLBACK_SCOPE_DRIFT')


def execute(plan, *, kube=host.kube):
    print('CERT_MANAGER_INSTALL_STEP=ownership-preflight', file=sys.stderr, flush=True)
    require(plan.get('schema_version') == 1 and plan.get('installation_id') == 'hooshix-production'
            and plan.get('profile') == 'production-single-server'
            and plan.get('callback_trust_boundary') == 'owner-accepted-exact-name-rbac'
            and set(plan['pods']) == set(ACCOUNTS), 'CERT_MANAGER_BOUNDED_PLAN_REQUIRED')
    before = protected(kube)
    items = copy.deepcopy(plan['manifest']['items'])
    networks = copy.deepcopy(plan['network'])
    peers = [{'ipBlock': {'cidr': address + '/32'}} for address in addresses(kube)]
    networks[0]['spec']['egress'][0]['to'] = peers
    networks[1]['spec']['ingress'][0]['from'] = peers
    objects = [plan['namespace'], *plan['crds'], plan['strict'], *networks, *items, *plan['policies']]
    for obj in objects:
        obj['metadata'].setdefault('labels', {})['app.kubernetes.io/managed-by'] = MANAGER

    def read(obj):
        meta = obj['metadata']
        scope = ['-n', meta['namespace']] if 'namespace' in meta else []
        output = ('jsonpath={.metadata.uid}:{.metadata.labels.app\\.kubernetes\\.io/managed-by}'
                  if obj['kind'] == 'Secret' else 'json')
        raw = kube(*scope, 'get', obj['kind'], meta['name'], '--ignore-not-found', '-o', output,
                   output_bound=host.CRD_BOUND if obj['kind'] == 'CustomResourceDefinition' else host.BOUND)
        if obj['kind'] == 'Secret':
            return {'metadata': {'labels': {'app.kubernetes.io/managed-by': raw.decode().split(':', 1)[-1]}}} if raw.strip() else None
        return json.loads(raw) if raw.strip() else None

    # Inspect every destination before any write; never adopt foreign/changed state.
    exists = {}
    for obj in objects:
        old = read(obj)
        key = (obj['kind'], obj['metadata']['name'])
        exists[key] = bool(old)
        if old:
            if obj['kind'] != 'Secret' and not desired_fields_match(old, obj):
                print('CERT_MANAGER_PUBLIC_DRIFT=' + json.dumps({
                    'kind': obj['kind'], 'name': obj['metadata']['name'],
                    'field': mismatched_path(old, obj)}), file=sys.stderr, flush=True)
            require(old['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == MANAGER
                    and (obj['kind'] == 'Secret' or desired_fields_match(old, obj)), 'CERT_MANAGER_OWNERSHIP_OR_DRIFT_CONFLICT')
            if obj['kind'] == 'Deployment':
                pod = old['spec']['template']['spec']
                context = pod.get('securityContext', {})
                require(not any(pod.get(f) for f in ('hostNetwork', 'hostPID', 'hostIPC', 'shareProcessNamespace'))
                        and not context.get('sysctls') and not context.get('windowsOptions'),
                        'CERT_MANAGER_EXTRA_POD_PRIVILEGE_REJECTED')
                for container in pod['containers']:
                    security = container.get('securityContext', {})
                    require(not container.get('command') and not container.get('envFrom')
                        and not security.get('privileged') and not security.get('capabilities', {}).get('add')
                        and security.get('procMount', 'Default') == 'Default' and not security.get('windowsOptions'),
                        'CERT_MANAGER_EXTRA_CONTAINER_PRIVILEGE_REJECTED')
    if exists[('Namespace', NAMESPACE)]:
        existing = json.loads(kube('-n', NAMESPACE, 'get', 'pods', '-o', 'json'))['items']
        require(all(p['spec'].get('serviceAccountName') in ACCOUNTS for p in existing),
                'CERT_MANAGER_FOREIGN_WORKLOAD_PRESERVED')
    boundaries = []
    for name in BOUNDARIES:
        old = json.loads(kube('get', 'validatingpolicy', name, '-o', 'json'))
        require(old['spec']['matchConstraints']['namespaceSelector'] in (OLD_SELECTOR, SELECTOR),
                'CERT_MANAGER_EXISTING_SELECTOR_CONFLICT')
        boundaries.append(old)

    def create(obj):
        key = (obj['kind'], obj['metadata']['name'])
        if not exists[key]:
            if obj['kind'] == 'CustomResourceDefinition':
                kube('apply', '--server-side', '--field-manager=' + MANAGER, '-f', '-', body=obj)
            else:
                kube('create', '-f', '-', body=obj)
            exists[key] = True

    refresh_discovery = any(not exists[('CustomResourceDefinition', obj['metadata']['name'])] for obj in plan['crds'])
    create(plan['namespace'])
    for obj in plan['crds']:
        create(obj)
        print('CERT_MANAGER_INSTALL_STEP=crd-' + obj['metadata']['name'], file=sys.stderr, flush=True)
        established_crd(obj['metadata']['name'], kube)
    if refresh_discovery:
        # Kyverno's populated discovery cache must see newly established certificate kinds.
        print('CERT_MANAGER_INSTALL_STEP=verifier-discovery-refresh', file=sys.stderr, flush=True)
        host.refresh_image_verifiers(plan['verifier_images'], kube_client=kube)
    for obj in [plan['strict'], *networks]:
        create(obj)
    # Reporting metadata is aggregated before policy readiness; no Secret read is granted.
    create(next(obj for obj in items if obj['kind'] == 'ClusterRole'
                and obj['metadata']['name'] == 'hooshix-cert-manager-report-reader'))
    for old in boundaries:
        if old['spec']['matchConstraints']['namespaceSelector'] != SELECTOR:
            kube('patch', 'validatingpolicy', old['metadata']['name'], '--type=json', '-p', json.dumps([
                {'op': 'test', 'path': '/metadata/uid', 'value': old['metadata']['uid']},
                {'op': 'test', 'path': '/spec', 'value': old['spec']},
                {'op': 'replace', 'path': '/spec/matchConstraints/namespaceSelector', 'value': SELECTOR}]))
    for obj in plan['policies']:
        create(obj)
        print('CERT_MANAGER_INSTALL_STEP=policy-' + obj['metadata']['name'], file=sys.stderr, flush=True)
        kube('wait', '--for=jsonpath={.status.conditionStatus.ready}=true',
             obj['kind'].lower() + '/' + obj['metadata']['name'], '--timeout=60s', timeout=75)
    # RBAC precedes workloads; callbacks only match new namespaced certificate resources.
    for obj in items:
        if obj['kind'] not in ('Deployment', 'MutatingWebhookConfiguration', 'ValidatingWebhookConfiguration'):
            create(obj)
    for obj in items:
        if obj['kind'] == 'Deployment':
            create(obj)
    for account in ACCOUNTS:
        obj = next(obj for obj in items if obj['kind'] == 'Deployment' and obj['metadata']['name'] == account)
        test = {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': 'cert-manager-admission-probe',
            'namespace': NAMESPACE, 'labels': obj['spec']['template']['metadata']['labels']},
            'spec': obj['spec']['template']['spec']}
        probe(test, kube)
        wrong = copy.deepcopy(test)
        wrong['spec']['serviceAccountName'] = 'default'
        probe(wrong, kube, expected=1, reason=b'platform bootstrap identity/security exception rejected')
        print('CERT_MANAGER_INSTALL_STEP=rollout-' + account, file=sys.stderr, flush=True)
        kube('-n', NAMESPACE, 'rollout', 'status', 'deployment/' + account, '--timeout=180s', timeout=195)
    for obj in items:
        if obj['kind'] in ('MutatingWebhookConfiguration', 'ValidatingWebhookConfiguration'):
            create(obj)
    internal = {'apiVersion': 'cert-manager.io/v1', 'kind': 'Issuer', 'metadata': {
        'name': 'hooshix-cert-manager-callback-probe', 'namespace': NAMESPACE}, 'spec': {'selfSigned': {}}}
    probe(internal, kube)
    external = copy.deepcopy(internal)
    external['spec'] = {'acme': {'server': 'https://unapproved.invalid/directory',
                                'privateKeySecretRef': {'name': 'unapproved'}}}
    probe(external, kube, expected=1, reason=b'only internal CA or self-signed issuers allowed')
    verify_callbacks(plan, kube)
    print('CERT_MANAGER_INSTALL_STEP=runtime-verification', file=sys.stderr, flush=True)
    pods = json.loads(kube('-n', NAMESPACE, 'get', 'pods', '-l',
                          'app.kubernetes.io/instance=cert-manager', '-o', 'json'))['items']
    by_account = {pod['spec']['serviceAccountName']: pod for pod in pods}
    require(len(pods) == 3 and set(by_account) == set(ACCOUNTS), 'CERT_MANAGER_EXACT_RUNTIME_REQUIRED')
    for obj in items:
        if obj['kind'] == 'Deployment':
            account = obj['spec']['template']['spec']['serviceAccountName']
            image = obj['spec']['template']['spec']['containers'][0]['image']
            pod = by_account[account]
            require(pod['spec']['containers'][0]['image'] == image
                and pod['status']['containerStatuses'][0]['ready']
                and pod['status']['containerStatuses'][0]['imageID'].endswith(image.split('@')[1]),
                'CERT_MANAGER_RUNTIME_DIGEST_MISMATCH')
    require(protected(kube) == before, 'CERT_MANAGER_PROTECTED_STATE_CHANGED')
    return {'schema_version': 1, 'cert_manager_installation': 'Passed', 'images': plan['images'],
        'api_callback_tls': 'Passed', 'external_issuer_denied': 'Passed', 'callback_configuration': 'Passed',
        'callback_trust_boundary': 'owner-accepted-exact-name-rbac',
        'root_mesh_openbao_eso_storage': 'Preserved', 'certificate_issuance': 'Not verified',
        'public_access': 'CLOSED', 'production_readiness': 'Not verified'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--sha256', required=True)
    args = parser.parse_args()
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(ValueError('CERT_MANAGER_DEADLINE')))
    signal.alarm(1200)
    try:
        require(os.getuid() == 0 and re.fullmatch(r'[a-f0-9]{64}', args.sha256), 'CERT_MANAGER_LOCAL_AUTH_REQUIRED')
        require(not args.plan.is_symlink() and args.plan.is_file()
                and args.plan.stat().st_size <= host.PLAN_BOUND, 'CERT_MANAGER_PUBLIC_PLAN_BOUND')
        data = args.plan.read_bytes()
        require(hashlib.sha256(data).hexdigest() == args.sha256, 'CERT_MANAGER_PLAN_HASH_MISMATCH')
        plan = json.loads(data)
        evidence = plan['staging']
        observed = datetime.fromisoformat(evidence['observed_at'])
        require(observed.tzinfo and 0 <= (datetime.now(timezone.utc) - observed).total_seconds() <= 5 * 86400
                and evidence['result'] == 'Passed' and re.fullmatch(r'[a-f0-9]{40}', evidence['source_revision'])
                and re.fullmatch(r'[1-9][0-9]{0,19}', evidence['run_id']), 'CERT_MANAGER_FRESH_STAGING_REQUIRED')
        services = host.preflight()
        with os.fdopen(os.open('/run/lock/hooshix-cert-manager-commissioning.lock',
                              os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600), 'r+b') as lock:
            info = os.fstat(lock.fileno())
            require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and info.st_nlink == 1
                    and stat.S_IMODE(info.st_mode) == 0o600, 'CERT_MANAGER_SAFE_LOCK_REQUIRED')
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            host.native(['/usr/sbin/auditctl', '-m', 'HooshiX cert-manager commissioning plan=' + args.sha256])
            result = execute(plan)
        require(host.snapshot_services() == services, 'CERT_MANAGER_HOST_SERVICE_CHANGED')
        host.kernel_audit()
        result['plan_sha256'] = args.sha256
        print(json.dumps(result, separators=(',', ':')))
        return 0
    except (ValueError, KeyError, IndexError, TypeError, OSError, subprocess.SubprocessError, host.custody.BootstrapFailed):
        print('CERT_MANAGER_INSTALLATION=Failed; partial state preserved; no automatic retry', file=sys.stderr)
        return 1
    finally:
        signal.alarm(0)


if __name__ == '__main__':
    raise SystemExit(main())
