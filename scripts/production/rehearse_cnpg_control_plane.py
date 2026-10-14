"""Native CNPG control-plane checks in the existing caller-owned disposable cluster."""
from __future__ import annotations

import copy
import json

import install_cnpg_control_plane as installer
import render_cnpg_control_plane as renderer
import render_platform_admission as admission
from rehearse_cert_manager import network_negative
from rehearse_openbao_recovery import RehearsalFailed
from rehearse_platform_admission import admission_result, apply, policy_ready
from upgrade_mesh_security import replace_policy


def failure_classes(output):
    # Fixed diagnostics only; actual logs/Secret names or values never leave memory.
    return [m for m in (b'forbidden', b'cannot list', b'cannot get', b'cannot patch', b'cannot create',
        b'unknown namespace for the cache', b'permission denied', b'x509', b'no such file',
        b'connection refused', b'secrets', b'configmaps', b'nodes', b'leases', b'clusterimagecatalogs') if m in output.lower()]


def strict_negative(k, wait, platform):
    """Only synthetic probes get temporary metrics reachability; STRICT is never relaxed."""
    ns, label, boundary = 'hooshix-secrets', 'cnpg-mtls-probe', 'hooshix-hooshix-secrets-bootstrap-boundary'
    current = json.loads(k('get', 'validatingpolicy', boundary, '-o', 'json'))
    previous = {key: current[key] for key in ('apiVersion', 'kind', 'spec')}
    previous['metadata'] = {'name': boundary}
    original = platform.plan['openbao']['items'][-1]['spec']['template']['spec']
    pod = copy.deepcopy(original)
    pod.update(serviceAccountName='commissioning-probe', restartPolicy='Never')
    pod['volumes'] = []
    # Use the fixed Pod address; no extra Service, DNS or credential is needed.
    address = json.loads(k('-n', renderer.NAMESPACE, 'get', 'pods', '-l',
        'app.kubernetes.io/name=cloudnative-pg', '-o', 'json'))['items'][0]['status']['podIP']
    c = pod['containers'][0]
    c.update(name='probe', command=['/usr/bin/bao', 'status', '-format=json',
        '-address=http://' + address + ':8080'], args=[], volumeMounts=[])
    c['env'] = [v for v in c.get('env', []) if v['name'] not in ('BAO_CACERT', 'BAO_ADDR')]
    for field in ('startupProbe', 'readinessProbe', 'livenessProbe'): c.pop(field, None)
    bounded = admission.hardening(ns, {'openbao': original, 'commissioning-probe': pod})
    bounded['spec']['matchConstraints']['namespaceSelector'] = copy.deepcopy(renderer.certificates.BOUNDARY_SELECTOR)
    policies = [
        {'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy', 'metadata': {'name': label, 'namespace': ns},
         'spec': {'podSelector': {'matchLabels': {'hooshix-cnpg-probe': label}}, 'policyTypes': ['Egress'],
             'egress': [{'to': [{'ipBlock': {'cidr': address + '/32'}}], 'ports': [{'protocol': 'TCP', 'port': 8080}]}]}},
        {'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy', 'metadata': {'name': label, 'namespace': renderer.NAMESPACE},
         'spec': {'podSelector': {'matchLabels': {'app.kubernetes.io/name': 'cloudnative-pg'}}, 'policyTypes': ['Ingress'],
             'ingress': [{'from': [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': ns}},
                 'podSelector': {'matchLabels': {'hooshix-cnpg-probe': label}}}], 'ports': [{'protocol': 'TCP', 'port': 8080}]}]}},
        {'apiVersion': 'security.istio.io/v1', 'kind': 'AuthorizationPolicy', 'metadata': {'name': label, 'namespace': renderer.NAMESPACE},
         'spec': {'selector': {'matchLabels': {'app.kubernetes.io/name': 'cloudnative-pg'}}, 'action': 'ALLOW',
             'rules': [{'from': [{'source': {'principals': ['prod.sajtech.internal/ns/' + ns + '/sa/commissioning-probe']}}],
                 'to': [{'operation': {'ports': ['8080']}}]}]}}]
    try:
        replace_policy(bounded, get=lambda kind, name: json.loads(k('get', kind, name, '-o', 'json')),
                       kube=k, previous=previous)
        for obj in policies: apply(k, obj)
        for mode in ('ambient', 'none'):
            name = label + '-' + mode
            apply(k, {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': name, 'namespace': ns,
                'labels': {'hooshix-cnpg-probe': label, 'istio.io/dataplane-mode': mode}}, 'spec': pod})
            def terminated():
                statuses = json.loads(k('-n', ns, 'get', 'pod', name, '-o', 'json')).get('status', {}).get('containerStatuses', [])
                return statuses and statuses[0].get('state', {}).get('terminated')
            state = wait(terminated, seconds=40)
            logs = k('-n', ns, 'logs', name).lower()
            reached = b'code: 404' in logs
            if (state['exitCode'] != 1 or reached != (mode == 'ambient')
                    or mode == 'none' and not any(m in logs for m in (b'eof', b'reset', b'timeout', b'closed'))):
                raise RehearsalFailed('CNPG non-callback mTLS identity boundary failed')
    finally:
        for mode in ('ambient', 'none'):
            k('-n', ns, 'delete', 'pod', label + '-' + mode, '--ignore-not-found', '--timeout=30s', timeout=40)
        for obj in policies:
            k('-n', obj['metadata']['namespace'], 'delete', obj['kind'], label, '--ignore-not-found')
        replace_policy(previous, get=lambda kind, name: json.loads(k('get', kind, name, '-o', 'json')),
                       kube=k, previous=bounded)
        policy_ready(k, previous)


def rehearse(k, wait, manifest, cert_manifest, platform):
    plan = renderer.render(manifest, cert_manifest, ['10.43.0.1'])
    def adapter(*args, body=None, **options):
        return k(*args, data=json.dumps(body).encode() if body is not None else None, **options)
    try:
        result = installer.execute(plan, kube=adapter)
        installer.execute(plan, kube=adapter)
    except (RehearsalFailed, ValueError):
        try:
            pods = json.loads(k('-n', renderer.NAMESPACE, 'get', 'pods', '-l',
                'app.kubernetes.io/name=cloudnative-pg', '-o', 'json'))['items']
            if pods:
                logs = k('-n', renderer.NAMESPACE, 'logs', 'deployment/' + renderer.DEPLOYMENT, '--tail=100')
                print('CNPG_FIXED_LOG_CLASSES=' + json.dumps([m.decode() for m in failure_classes(logs)]), flush=True)
        except RehearsalFailed:
            pass
        raise
    result['idempotent_reconciliation'] = 'Passed'
    print('CNPG_REHEARSAL_STEP=workload-boundary', flush=True)
    deployment = next(o for o in plan['objects'] if o['kind'] == 'Deployment')
    test = {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': 'cnpg-control-plane-probe',
        'namespace': renderer.NAMESPACE, 'labels': deployment['spec']['template']['metadata']['labels']},
        'spec': deployment['spec']['template']['spec']}
    admission_result(k, test, wait, public_schema=False)
    for field in ('account', 'scope', 'ambient', 'privilege'):
        wrong = copy.deepcopy(test)
        if field == 'account': wrong['spec']['serviceAccountName'] = 'default'
        if field == 'scope': wrong['spec']['containers'][0]['env'][-1]['value'] = '*'
        if field == 'ambient': wrong['metadata']['labels']['istio.io/dataplane-mode'] = 'none'
        if field == 'privilege': wrong['spec']['containers'][0]['securityContext']['privileged'] = True
        admission_result(k, wrong, wait, expected=1, public_schema=False)
    closed = copy.deepcopy(test)
    closed['metadata']['namespace'] = renderer.DATA_NAMESPACE
    closed['spec']['serviceAccountName'] = 'default'
    admission_result(k, closed, wait, expected=1, message=b'data plane not commissioned', public_schema=False)
    result['data_namespace_closed'] = 'Passed'
    print('CNPG_REHEARSAL_STEP=source-evidence', flush=True)
    for original in [p for p in plan['source_policies'] if p['kind'] == 'ImageValidatingPolicy']:
        wrong = copy.deepcopy(original)
        suffix = original['metadata']['name'].rsplit('-', 1)[-1]
        if suffix == 'signature':
            wrong['spec']['attestors'][0]['cosign']['keyless']['identities'][0]['subject'] = 'https://wrong.invalid'
        elif suffix == 'sbom':
            wrong['spec']['attestations'][0]['intoto']['type'] = 'https://missing.invalid/sbom'
        else:
            wrong['spec']['validations'][1]['expression'] = wrong['spec']['validations'][1]['expression'].replace(
                renderer.candidate()['publication']['repository_revision'], '0' * 40)
        try:
            replace_policy(wrong, get=lambda kind, name: json.loads(k('get', kind, name, '-o', 'json')),
                           kube=adapter, previous=original)
            admission_result(k, test, wait, expected=1, public_schema=False)
        finally:
            replace_policy(original, get=lambda kind, name: json.loads(k('get', kind, name, '-o', 'json')),
                           kube=adapter, previous=wrong)
            policy_ready(k, original)
        admission_result(k, test, wait, public_schema=False)
    result['source_identity_positive_negative'] = 'Passed'
    print('CNPG_REHEARSAL_STEP=callback-network-and-rbac', flush=True)
    network_negative(k, wait, platform, selector='app.kubernetes.io/name=cloudnative-pg', port=9443)
    result['wrong_source_webhook_network'] = 'Passed'
    identity = '--as=system:serviceaccount:cnpg-system:cnpg-manager'
    for ns in ('platform-apps', 'istio-system', 'hooshix-secrets'):
        if k('auth', 'can-i', 'get', 'secrets', '-n', ns, identity, expected=1).strip() != b'no':
            raise RehearsalFailed('CNPG unrelated namespace Secret access accepted')
    if k('auth', 'can-i', 'get', 'secrets/cert-manager-webhook-ca', '-n', renderer.NAMESPACE,
         identity, expected=1).strip() != b'no':
        raise RehearsalFailed('CNPG certificate-manager CA access accepted')
    for kind in ('mutatingwebhookconfigurations', 'validatingwebhookconfigurations'):
        if k('auth', 'can-i', 'patch', kind + '/other-webhook', identity, expected=1).strip() != b'no':
            raise RehearsalFailed('CNPG unrelated webhook write accepted')
    result['rbac_namespace_negative'] = 'Passed'
    print('CNPG_REHEARSAL_STEP=non-callback-strict-mtls', flush=True)
    strict_negative(k, wait, platform)
    result['non_callback_strict_mtls'] = 'Passed'
    logs = k('-n', renderer.NAMESPACE, 'logs', 'deployment/' + renderer.DEPLOYMENT, '--tail=-1')
    if b'PRIVATE KEY-----' in logs:
        raise RehearsalFailed('CNPG private key marker in controller logs')
    result['container_key_log_markers'] = 'Passed'
    result['production_target'] = 'Not verified'
    return result
