"""Supervised image-only istiod/CNI upgrade. Never install or initialize OpenBao."""
from __future__ import annotations

import argparse
import copy
import fcntl
import hashlib
import json
import os
import re
import resource
import stat
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import commission_platform as host

NAMESPACE = 'istio-system'
BOUNDARY = 'hooshix-istio-system-bootstrap-boundary'
MANAGER = 'hooshix-mesh-security-upgrade'


def require(value, message):
    if not value:
        # Messages are fixed source literals, never API responses or credentials.
        print('MESH_UPGRADE_CHECK_FAILED=' + message.upper().replace(' ', '_'), file=sys.stderr, flush=True)
        raise ValueError(message)


def rendered_fields_match(actual, expected, path=()):
    """Retain every rendered field while accepting API-added map defaults."""
    if isinstance(expected, dict):
        return isinstance(actual, dict) and all(
            (key not in actual and key == 'value' and value == ''
             and len(path) >= 2 and path[-2] == 'env') or (
                key in actual and rendered_fields_match(actual[key], value, (*path, key)))
            for key, value in expected.items())
    if isinstance(expected, list):
        return isinstance(actual, list) and len(actual) == len(expected) and all(
            rendered_fields_match(left, right, (*path, index))
            for index, (left, right) in enumerate(zip(actual, expected)))
    # Kubernetes Quantity is serialized as a string; empty EnvVar.value is omitted.
    if len(path) >= 3 and path[-3] == 'resources' and path[-2] in ('limits', 'requests'):
        return str(actual) == str(expected)
    return actual == expected


def replace_policy(value, *, get, kube, previous=None):
    kind, name = value['kind'], value['metadata']['name']
    print('MESH_UPGRADE_POLICY=' + name, file=sys.stderr, flush=True)
    before = get(kind, name)
    if before is None:
        require(previous is None, 'required policy is absent')
        kube('create', '-f', '-', body=value)
    else:
        require(before['spec'] == value['spec'] or (
            previous is not None and before['spec'] == previous['spec']), 'policy drift preserved')
        if before['spec'] != value['spec']:
            kube('patch', kind, name, '--type=json', '-p', json.dumps([
                {'op': 'test', 'path': '/metadata/resourceVersion', 'value': before['metadata']['resourceVersion']},
                {'op': 'test', 'path': '/spec', 'value': before['spec']},
                {'op': 'replace', 'path': '/spec', 'value': value['spec']}]))
    kube('wait', '--for=jsonpath={.status.conditionStatus.ready}=true',
         kind + '/' + name, '--timeout=60s', timeout=75)


def probe(pod, *, kube, expected=0, reason=None):
    value = {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {
        'name': 'hooshix-mesh-upgrade-preflight', 'namespace': NAMESPACE}, 'spec': pod}
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


def execute(plan, *, get=host.get, kube=host.kube):
    require(plan.get('schema_version') == 1 and plan.get('installation_id') == 'hooshix-production'
            and plan.get('profile') == 'production-single-server'
            and [c['component'] for c in plan['changes']] == ['istiod', 'cni'], 'bounded mesh plan required')
    # Reads of CA are metadata-only; no CA key, token or OpenBao data is requested.
    protected = [('-n', NAMESPACE, 'get', 'secret/cacerts', '-o',
                  'jsonpath={.metadata.uid}:{.metadata.resourceVersion}'),
                 ('-n', NAMESPACE, 'get', 'daemonset/ztunnel', '-o', 'json'),
                 ('-n', 'hooshix-secrets', 'get', 'statefulset/openbao', '-o', 'json'),
                 ('-n', 'hooshix-secrets', 'get', 'persistentvolumeclaims', '-o', 'json'),
                 ('-n', 'hooshix-secrets', 'get', 'pods', '-o', 'json')]
    def preserved():
        result = []
        for index, args in enumerate(protected):
            raw = kube(*args)
            if index == 0:
                require(bool(raw.strip()), 'existing CA metadata required')
                result.append(raw)
            else:
                value = json.loads(raw)
                items = value.get('items', [value])
                require(bool(items), 'preserved workload or storage absent')
                for item in items:
                    item['metadata'] = {key: item['metadata'].get(key)
                                        for key in ('uid', 'name', 'namespace', 'resourceVersion')}
                    if index == 4:
                        require(item.get('status', {}).get('phase') == 'Running'
                                and all(c.get('ready') for c in item.get('status', {}).get('containerStatuses', []))
                                and bool(item.get('status', {}).get('containerStatuses')),
                                'existing OpenBao must remain running and ready')
                        item['status'] = {key: item['status'].get(key)
                                          for key in ('startTime', 'containerStatuses')}
                    else:
                        item.pop('status', None)
                # Controller status may update resourceVersion during normal reconciliation.
                for item in items:
                    item['metadata'].pop('resourceVersion', None)
                if 'items' in value:
                    value.pop('metadata', None)
                result.append(value)
        return result
    before_protected = preserved()
    current = {}
    for change in plan['changes']:
        resource = get(change['kind'], change['name'], NAMESPACE)
        require(resource is not None, 'existing mesh required')
        pod = resource['spec']['template']['spec']
        expected = change['expected_pod']
        require(len(pod['containers']) == 1 and pod['serviceAccountName'] == expected['serviceAccountName']
                and pod['containers'][0]['name'] == change['container']
                and pod['containers'][0]['image'] in (change['before'], change['after']), 'mesh predecessor conflict')
        # Compare all explicitly rendered fields, including capabilities, argv,
        # resources and mount paths. API-added defaults are independently checked by admission.
        selected = copy.deepcopy(expected)
        selected['containers'][0]['image'] = pod['containers'][0]['image']
        for key, value in selected.items():
            if key == 'containers':
                require(rendered_fields_match(pod[key], value), 'mesh container drift')
            else:
                require(rendered_fields_match(pod.get(key), value), 'mesh pod drift')
        current[change['component']] = resource
    boundary = get('ValidatingPolicy', BOUNDARY)
    require(boundary is not None and boundary['spec'] in [plan[key]['spec'] for key in (
        'boundary_before', 'boundary_transition', 'boundary_after')], 'mesh security boundary conflict')
    for policy in plan['source_policies']:
        replace_policy(policy, get=get, kube=kube)
    # Do not reopen the old digest on a completed reentry.
    complete = all(r['spec']['template']['spec']['containers'][0]['image'] == c['after']
                   for c, r in ((c, current[c['component']]) for c in plan['changes']))
    target = plan['boundary_after'] if complete else plan['boundary_transition']
    replace_policy(target, get=get, kube=kube, previous=boundary)
    for change in plan['changes']:
        resource = get(change['kind'], change['name'], NAMESPACE)
        predecessor = current[change['component']]
        require(resource['metadata']['uid'] == predecessor['metadata']['uid']
                and resource['spec'] == predecessor['spec'], 'mesh changed after preflight')
        pod = copy.deepcopy(resource['spec']['template']['spec'])
        pod['containers'][0]['image'] = change['after']
        probe(pod, kube=kube)
        wrong = copy.deepcopy(pod)
        wrong['serviceAccountName'] = 'default'
        probe(wrong, kube=kube, expected=1, reason=b'platform bootstrap identity/security exception rejected')
        old = resource['spec']['template']['spec']['containers'][0]['image']
        if old != change['after']:
            require(old == change['before'], 'mesh predecessor changed')
            kube('-n', NAMESPACE, 'patch', change['kind'], change['name'], '--type=json', '-p', json.dumps([
                {'op': 'test', 'path': '/metadata/resourceVersion', 'value': resource['metadata']['resourceVersion']},
                {'op': 'test', 'path': '/spec/template/spec/containers/0/image', 'value': change['before']},
                {'op': 'replace', 'path': '/spec/template/spec/containers/0/image', 'value': change['after']}]))
        kube('-n', NAMESPACE, 'rollout', 'status', change['kind'] + '/' + change['name'],
             '--timeout=180s', timeout=195)
        after = get(change['kind'], change['name'], NAMESPACE)
        expected = copy.deepcopy(current[change['component']]['spec'])
        expected['template']['spec']['containers'][0]['image'] = change['after']
        require(after['metadata']['uid'] == current[change['component']]['metadata']['uid']
                and after['spec'] == expected, 'image-only upgrade invariant failed')
    replace_policy(plan['boundary_after'], get=get, kube=kube, previous=target)
    for change in plan['changes']:
        old = copy.deepcopy(change['expected_pod'])
        probe(old, kube=kube, expected=1, reason=b'platform bootstrap identity/security exception rejected')
    require(preserved() == before_protected, 'protected state changed; inspect before continuing')
    return {'schema_version': 1, 'mesh_security_upgrade': 'Passed',
            'images': {c['component']: c['after'] for c in plan['changes']},
            'openbao_ca_ztunnel_storage': 'Preserved', 'production_readiness': 'Not verified'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--sha256', required=True)
    args = parser.parse_args()
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    try:
        require(os.getuid() == 0 and re.fullmatch(r'[a-f0-9]{64}', args.sha256), 'supervised root required')
        require(not args.plan.is_symlink() and args.plan.is_file()
                and args.plan.stat().st_size <= host.PLAN_BOUND, 'bounded regular upgrade plan required')
        data = args.plan.read_bytes()
        require(hashlib.sha256(data).hexdigest() == args.sha256, 'reviewed plan hash mismatch')
        plan = json.loads(data)
        evidence = plan['staging']
        observed = datetime.fromisoformat(evidence['observed_at'])
        age = (datetime.now(timezone.utc) - observed).total_seconds() if observed.tzinfo else -1
        require(evidence.get('result') == 'Passed' and 0 <= age <= 5 * 86400
                and re.fullmatch(r'[a-f0-9]{40}', evidence.get('source_revision', ''))
                and re.fullmatch(r'[1-9][0-9]{0,19}', evidence.get('run_id', '')),
                'fresh reviewed staging required')
        host.kernel_audit()
        services = host.snapshot_services()
        with os.fdopen(os.open('/run/lock/hooshix-mesh-security-upgrade.lock',
                              os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600), 'r+b') as lock:
            info = os.fstat(lock.fileno())
            require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and info.st_nlink == 1
                    and stat.S_IMODE(info.st_mode) == 0o600, 'safe single-operator lock required')
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            host.native(['/usr/sbin/auditctl', '-m', 'HooshiX mesh security upgrade plan=' + args.sha256])
            result = execute(plan)
        require(host.snapshot_services() == services, 'preserved host service changed')
        host.kernel_audit()
        result['plan_sha256'] = args.sha256
        print(json.dumps(result, separators=(',', ':')))
    except (ValueError, KeyError, TypeError, OSError, host.custody.BootstrapFailed):
        print('MESH_SECURITY_UPGRADE=Failed; partial state preserved; no automatic rollback', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
