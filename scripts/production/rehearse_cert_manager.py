"""Native internal certificates in the caller-owned disposable CI cluster only."""
from __future__ import annotations

import base64
import copy
import json
import re
import tempfile
from pathlib import Path

import commission_platform as host
import install_cert_manager as installer
import render_cert_manager_candidate as renderer
import render_platform_admission as admission
from rehearse_openbao_recovery import RehearsalFailed
from rehearse_platform_admission import admission_result, apply, policy_ready
from upgrade_mesh_security import replace_policy


def network_negative(k, wait, platform):
    name = 'cert-manager-wrong-source'
    boundary_name = 'hooshix-hooshix-secrets-bootstrap-boundary'
    previous = json.loads(k('get', 'validatingpolicy', boundary_name, '-o', 'json'))
    original = {key: previous[key] for key in ('apiVersion', 'kind', 'spec')}
    original['metadata'] = {'name': boundary_name}
    pod = copy.deepcopy(platform.plan['openbao']['items'][-1]['spec']['template']['spec'])
    pod['serviceAccountName'] = 'commissioning-probe'
    pod['restartPolicy'] = 'Never'
    pod['volumes'] = [{'name': 'tls', 'secret': {'secretName': 'openbao-server-tls',
        'defaultMode': 288, 'items': [{'key': 'ca.crt', 'path': 'ca.crt'}]}}]
    target = json.loads(k('-n', renderer.NAMESPACE, 'get', 'pods', '-l',
        'app.kubernetes.io/name=webhook', '-o', 'json'))['items'][0]['status']['podIP']
    container = pod['containers'][0]
    container.update(name='probe', command=['/usr/bin/bao', 'status', '-format=json',
        '-address=https://' + target + ':10250', '-ca-cert=/openbao/tls/ca.crt'], args=[],
        volumeMounts=[{'name': 'tls', 'mountPath': '/openbao/tls', 'readOnly': True}])
    for field in ('startupProbe', 'readinessProbe', 'livenessProbe'): container.pop(field, None)
    bounded = admission.hardening('hooshix-secrets', {'openbao': platform.plan['openbao']['items'][-1]['spec']['template']['spec'],
                                                    'commissioning-probe': pod})
    bounded['spec']['matchConstraints']['namespaceSelector'] = copy.deepcopy(renderer.BOUNDARY_SELECTOR)
    egress = {'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy', 'metadata': {
        'name': name, 'namespace': 'hooshix-secrets'}, 'spec': {'podSelector': {
        'matchLabels': {'hooshix-cert-manager-probe': name}}, 'policyTypes': ['Egress'], 'egress': [{
        'to': [{'ipBlock': {'cidr': target + '/32'}}], 'ports': [{'protocol': 'TCP', 'port': 10250}]}]}}
    try:
        replace_policy(bounded, get=lambda kind, name: json.loads(k('get', kind, name, '-o', 'json')),
                       kube=k, previous=original)
        apply(k, egress)
        apply(k, {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': name, 'namespace': 'hooshix-secrets',
            'labels': {'hooshix-cert-manager-probe': name, 'istio.io/dataplane-mode': 'none'}}, 'spec': pod})
        def terminated():
            statuses = json.loads(k('-n', 'hooshix-secrets', 'get', 'pod', name, '-o', 'json')).get('status', {}).get('containerStatuses', [])
            return statuses and statuses[0].get('state', {}).get('terminated')
        state = wait(terminated, seconds=30)
        output = k('-n', 'hooshix-secrets', 'logs', name).lower()
        if (state['exitCode'] != 1 or not any(marker in output for marker in (b'i/o timeout', b'context deadline exceeded'))
                or any(marker in output for marker in (b'x509', b'no such file', b'too many arguments', b'no such host'))):
            raise RehearsalFailed('wrong-source webhook connection not blocked by networking')
    finally:
        k('-n', 'hooshix-secrets', 'delete', 'pod', name, '--ignore-not-found', '--timeout=30s', timeout=40)
        k('-n', 'hooshix-secrets', 'delete', 'networkpolicy', name, '--ignore-not-found')
        replace_policy(original, get=lambda kind, name: json.loads(k('get', kind, name, '-o', 'json')),
                       kube=k, previous=bounded)
        policy_ready(k, original)


def rehearse(k, wait, run, manifest, platform):
    plan = renderer.render(manifest, ['10.43.0.1'])

    def adapter(*args, body=None, **kw):
        try:
            return k(*args, data=json.dumps(body).encode() if body is not None else None, **kw)
        except RehearsalFailed as error:
            resource = next((a for a in args if re.fullmatch(
                r'(?:validatingpolicy|imagevalidatingpolicy)/hooshix-[a-z0-9-]+', a)), None)
            if 'wait' in args and resource:
                # Compile status of these fixed PUBLIC policies, before any certificate keys exist.
                print('CERT_MANAGER_PUBLIC_POLICY_FAILURE=' + resource, flush=True)
                status = k('get', resource, '-o', 'jsonpath={.status}')
                if len(status) <= 16384:
                    print('CERT_MANAGER_PUBLIC_POLICY_STATUS=' + json.dumps(json.loads(status)), flush=True)
            raise host.custody.BootstrapFailed('CERT_MANAGER_NATIVE_OPERATION_FAILED') from error

    result = installer.execute(plan, kube=adapter)
    installer.execute(plan, kube=adapter)
    result['idempotent_reconciliation'] = 'Passed'
    print('CERT_MANAGER_REHEARSAL_STEP=pod-admission', flush=True)
    deployments = [o for o in plan['manifest']['items'] if o['kind'] == 'Deployment']
    for obj in deployments:
        pod = {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': 'cert-manager-native-probe',
            'namespace': renderer.NAMESPACE, 'labels': obj['spec']['template']['metadata']['labels']},
            'spec': obj['spec']['template']['spec']}
        admission_result(k, pod, wait, public_schema=False)
        for change in ('identity', 'ambient', 'scope', 'privileged', 'mutable-image'):
            wrong = copy.deepcopy(pod)
            if change == 'identity': wrong['spec']['serviceAccountName'] = 'default'
            if change == 'ambient':
                wrong['metadata']['labels']['istio.io/dataplane-mode'] = (
                    'ambient' if obj['metadata']['name'].endswith('-webhook') else 'none')
            if change == 'scope': wrong['spec']['containers'][0]['args'] = ['--controllers=*']
            if change == 'privileged': wrong['spec']['containers'][0]['securityContext']['privileged'] = True
            if change == 'mutable-image': wrong['spec']['containers'][0]['image'] = 'unapproved.invalid/image:latest'
            admission_result(k, wrong, wait, expected=1, public_schema=False)
    # Same digest, deliberately wrong trust/evidence; restore the policy even on failure.
    print('CERT_MANAGER_REHEARSAL_STEP=source-identity', flush=True)
    for original in [p for p in plan['policies'] if p['kind'] == 'ImageValidatingPolicy']:
        p = copy.deepcopy(original)
        suffix = p['metadata']['name'].rsplit('-', 1)[1]
        component = next(c for c in renderer.ACCOUNTS if '-' + c + '-source-' in p['metadata']['name'])
        deployment = next(d for d in deployments if d['metadata']['name'] == renderer.ACCOUNTS[component])
        test = {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': 'cert-manager-signature-probe',
            'namespace': renderer.NAMESPACE, 'labels': deployment['spec']['template']['metadata']['labels']},
            'spec': deployment['spec']['template']['spec']}
        if suffix == 'signature':
            p['spec']['attestors'][0]['cosign']['keyless']['identities'][0]['subject'] += '.wrong'
        elif suffix == 'sbom': p['spec']['attestations'][0]['intoto']['type'] = 'https://missing.invalid/sbom'
        else:
            p['spec']['validations'][1]['expression'] = p['spec']['validations'][1]['expression'].replace(
                renderer.candidate()['publication']['repository_revision'], '0' * 40)
        try:
            replace_policy(p, get=lambda kind, name: json.loads(k('get', kind, name, '-o', 'json')),
                           kube=k, previous=original)
            policy_ready(k, p)
            admission_result(k, test, wait, expected=1, public_schema=False)
        finally:
            replace_policy(original, get=lambda kind, name: json.loads(k('get', kind, name, '-o', 'json')),
                           kube=k, previous=p)
            policy_ready(k, original)
        admission_result(k, test, wait, public_schema=False)
    result['source_identity_positive_negative'] = 'Passed'
    print('CERT_MANAGER_REHEARSAL_STEP=callback-boundary', flush=True)
    for kind in ('mutatingwebhookconfiguration', 'validatingwebhookconfiguration'):
        k('patch', kind, 'cert-manager-webhook', '--dry-run=server', '--type=json', '-p', json.dumps([
            {'op': 'replace', 'path': '/webhooks/0/failurePolicy', 'value': 'Ignore'}]), expected=1,
            required_error=b'cert-manager callback security is immutable')
        k('patch', kind, 'cert-manager-webhook', '--dry-run=server', '--type=json', '-p', json.dumps([
            {'op': 'replace', 'path': '/webhooks/0/failurePolicy', 'value': 'Fail'}]))
    result['callback_security_negative'] = 'Passed'
    print('CERT_MANAGER_REHEARSAL_STEP=wrong-source-network', flush=True)
    network_negative(k, wait, platform)
    result['wrong_source_webhook_network'] = 'Passed'

    def object_(kind, name, spec):
        return {'apiVersion': 'cert-manager.io/v1', 'kind': kind,
                'metadata': {'name': name, 'namespace': renderer.NAMESPACE}, 'spec': spec}

    issuer = object_('Issuer', 'hooshix-cert-manager-probe-selfsigned', {'selfSigned': {}})
    admission_result(k, issuer, wait, public_schema=False)
    external = object_('Issuer', 'hooshix-cert-manager-probe-external', {'acme': {
        'server': 'https://unapproved.invalid/directory', 'privateKeySecretRef': {'name': 'unapproved'}}})
    admission_result(k, external, wait, expected=1,
                     message=b'only internal CA or self-signed issuers allowed', public_schema=False)
    # RBAC is an independent guard, not inferred from a deny-only image policy.
    print('CERT_MANAGER_REHEARSAL_STEP=rbac', flush=True)
    for account in renderer.ACCOUNTS.values():
        identity = 'system:serviceaccount:' + renderer.NAMESPACE + ':' + account
        for namespace in ('platform-apps', 'istio-system', 'hooshix-secrets'):
            if k('auth', 'can-i', 'get', 'secrets', '-n', namespace, '--as=' + identity,
                 expected=1).strip() != b'no':
                raise RehearsalFailed('cross-namespace certificate Secret access accepted')
    if k('auth', 'can-i', 'patch', 'validatingwebhookconfigurations/other-webhook',
         '--as=system:serviceaccount:cnpg-system:cert-manager-cainjector', expected=1).strip() != b'no':
        raise RehearsalFailed('foreign webhook patch accepted')
    denied = {'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': 'unapproved-serving-ca',
        'namespace': renderer.NAMESPACE}, 'type': 'Opaque'}
    k('create', '--dry-run=server', '-f', '-',
      '--as=system:serviceaccount:cnpg-system:cert-manager-webhook', data=json.dumps(denied).encode(),
      expected=1, required_error=b'forbidden')
    result['rbac_issuer_scope_negative'] = 'Passed'
    print('CERT_MANAGER_REHEARSAL_STEP=issuance-rotation', flush=True)
    apply(k, issuer)
    ca = object_('Certificate', 'hooshix-cert-manager-probe-ca', {'isCA': True,
        'commonName': 'HooshiX disposable internal TLS test', 'secretName': 'hooshix-cert-manager-probe-ca',
        'duration': '24h', 'renewBefore': '1h', 'privateKey': {'algorithm': 'RSA', 'size': 3072},
        'issuerRef': {'name': issuer['metadata']['name'], 'kind': 'Issuer'}})
    apply(k, ca)
    k('-n', renderer.NAMESPACE, 'wait', '--for=condition=Ready', 'certificate/' + ca['metadata']['name'],
      '--timeout=120s', timeout=135)
    signing = object_('Issuer', 'hooshix-cert-manager-probe-ca', {'ca': {'secretName': ca['spec']['secretName']}})
    apply(k, signing)
    k('-n', renderer.NAMESPACE, 'wait', '--for=condition=Ready', 'issuer/' + signing['metadata']['name'],
      '--timeout=60s', timeout=75)
    dns = 'barman-cloud.cnpg-system.svc'
    leaf = object_('Certificate', 'hooshix-cert-manager-probe-leaf', {
        'secretName': 'hooshix-cert-manager-probe-leaf', 'dnsNames': [dns],
        'duration': '24h', 'renewBefore': '1h', 'privateKey': {'algorithm': 'RSA', 'size': 3072,
            'rotationPolicy': 'Always'}, 'usages': ['server auth', 'client auth'],
        'issuerRef': {'name': signing['metadata']['name'], 'kind': 'Issuer'}})
    apply(k, leaf)
    k('-n', renderer.NAMESPACE, 'wait', '--for=condition=Ready', 'certificate/' + leaf['metadata']['name'],
      '--timeout=120s', timeout=135)

    def public_certificate(name):
        value = k('-n', renderer.NAMESPACE, 'get', 'secret', name, '-o', 'jsonpath={.data.tls\\.crt}')
        return base64.b64decode(value, validate=True)

    def verify(public_ca, cert, directory):
        (directory / 'ca.pem').write_bytes(public_ca)
        (directory / 'leaf.pem').write_bytes(cert)
        run(['/usr/bin/openssl', 'verify', '-CAfile', str(directory / 'ca.pem'),
             '-verify_hostname', dns, str(directory / 'leaf.pem')])
        run(['/usr/bin/openssl', 'verify', '-CAfile', str(directory / 'ca.pem'),
             '-verify_hostname', 'wrong.invalid', str(directory / 'leaf.pem')], expected=2)

    # Only PUBLIC certificates leave the disposable API; private keys never do.
    with tempfile.TemporaryDirectory(prefix='hooshix-cert-manager-public-tls-') as temp:
        directory = Path(temp)
        public_ca = public_certificate(ca['spec']['secretName'])
        original = public_certificate(leaf['spec']['secretName'])
        verify(public_ca, original, directory)
        leaf['spec']['dnsNames'].append('rotation.barman-cloud.cnpg-system.svc')
        apply(k, leaf)
        wait(lambda: public_certificate(leaf['spec']['secretName']) != original, seconds=120)
        verify(public_ca, public_certificate(leaf['spec']['secretName']), directory)
    result['certificate_issuance'] = 'Passed'
    result['certificate_rotation_hostname_negative'] = 'Passed'
    for account in renderer.ACCOUNTS.values():
        logs = k('-n', renderer.NAMESPACE, 'logs', 'deployment/' + account, '--tail=-1')
        if b'PRIVATE KEY-----' in logs or any(base64.b64encode(marker)[:28] in logs for marker in (
                b'-----BEGIN PRIVATE KEY', b'-----BEGIN RSA PRIVATE KEY', b'-----BEGIN EC PRIVATE KEY')):
            raise RehearsalFailed('private certificate key marker in controller logs')
    result['container_key_log_markers'] = 'Passed'
    result['production_target'] = 'Not verified'
    result['barman_runtime'] = 'Not run'
    return result
