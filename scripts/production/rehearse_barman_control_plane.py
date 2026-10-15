"""Real signed Barman/TLS/authority checks in the caller-owned disposable cluster."""
import copy
import json
import traceback

import install_barman_control_plane as installer
import install_postgresql_local_volume as volume
import render_barman_control_plane as renderer
import render_platform_admission as admission
from rehearse_cnpg_control_plane import failure_classes
from rehearse_openbao_recovery import RehearsalFailed
from rehearse_platform_admission import admission_result, apply, policy_ready
from upgrade_mesh_security import replace_policy


TLS_PROBE = '''import socket,ssl,sys
mode=sys.argv[1]
ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
ctx.load_verify_locations('/server/tls.crt')
ctx.verify_flags=ssl.VERIFY_X509_PARTIAL_CHAIN|ssl.VERIFY_X509_TRUSTED_FIRST
ctx.set_alpn_protocols(['h2'])
if mode!='no-client':
 path='/server' if mode=='wrong-client' else '/client'
 ctx.load_cert_chain(path+'/tls.crt',path+'/tls.key')
try:
 with socket.create_connection(('barman-cloud.cnpg-system.svc',9090),timeout=8) as sock:
  with ctx.wrap_socket(sock,server_hostname='wrong.invalid' if mode=='hostname' else 'barman-cloud') as tls:
   tls.sendall(b'PRI * HTTP/2.0\\r\\n\\r\\nSM\\r\\n\\r\\n')
   header=b''
   while len(header)<9:
    part=tls.recv(9-len(header))
    if not part: break
    header+=part
   ok=len(header)==9 and header[3]==4
except (OSError,ssl.SSLError): ok=False
print('BARMAN_TLS=Passed' if ok else 'BARMAN_TLS=Denied')
sys.exit(0 if ok else 1)
'''


def tls_boundary(k, wait, adapter, plan):
    """TLS/mesh probes use disposable mounted keys, never Secret API permissions or key export."""
    ns, name, account = renderer.NAMESPACE, 'barman-tls-probe', 'barman-tls-probe'
    dep = next(o for o in plan['objects'] if o['kind'] == 'Deployment')
    pod = copy.deepcopy(dep['spec']['template']['spec'])
    pod.update(serviceAccountName=account, restartPolicy='Never', automountServiceAccountToken=False)
    pod['volumes'] = [v for v in pod['volumes'] if v['name'] != 'hooshix-api-token']
    c = pod['containers'][0]
    c.update(image=plan['images']['barman-sidecar'], name='probe', command=['python3', '-c', TLS_PROBE],
             args=['positive'], env=[])
    c['volumeMounts'] = [m for m in c['volumeMounts'] if m['name'] != 'hooshix-api-token']
    for f in ('startupProbe', 'readinessProbe', 'livenessProbe'): c.pop(f, None)
    source = admission.image_policy(ns, [c['image']], renderer.candidate()['publication']['repository_revision'])
    source['spec'].pop('credentials')
    source['metadata']['name'] = name
    source['spec']['validations'][2] = admission.validation('Barman source identity rejected', renderer.provenance_expression(
        renderer.source_predicate('barman-sidecar', renderer.recipe(),
            renderer.candidate()['publication']['repository_revision'], renderer.candidate()['publication']['invocation_id'])))
    sources = admission.split_image_policy(source)
    boundary = copy.deepcopy(plan['boundary'])
    boundary['spec']['validations'][0] = admission.hardening(ns, {**{
        o['spec']['template']['spec']['serviceAccountName']: o['spec']['template']['spec']
        for o in plan['objects'] if o['kind'] == 'Deployment'}, account: pod,
        account + '-denied': pod})['spec']['validations'][0]
    # Only the new probe contract is needed while all existing controllers are stable.
    # Existing four controller contracts/constraints remain verbatim in the original expression.
    original = plan['boundary']['spec']['validations'][0]['expression']
    probe = boundary['spec']['validations'][0]['expression']
    boundary['spec']['validations'][0]['expression'] = '(' + original + ') || (' + probe + ')'
    objects = [renderer.resource('ServiceAccount', account), renderer.resource('ServiceAccount', account + '-denied'), *sources,
        renderer.resource('NetworkPolicy', name, apiVersion='networking.k8s.io/v1', spec={
            'podSelector': {'matchLabels': {'barman-probe': name}}, 'policyTypes': ['Egress'], 'egress': [
                {'to': [{'podSelector': {'matchLabels': renderer.LABELS}}],
                 'ports': [{'protocol': 'TCP', 'port': p} for p in (9090, 15008)]},
                plan['network'][0]['spec']['egress'][1], plan['network'][1]['spec']['egress'][0]]}),
        renderer.resource('NetworkPolicy', name + '-server', apiVersion='networking.k8s.io/v1', spec={
            'podSelector': {'matchLabels': renderer.LABELS}, 'policyTypes': ['Ingress'], 'ingress': [
                {'from': [{'podSelector': {'matchLabels': {'barman-probe': name}}}],
                 'ports': [{'protocol': 'TCP', 'port': p} for p in (9090, 15008)]}]}),
        renderer.resource('AuthorizationPolicy', name, apiVersion='security.istio.io/v1', spec={
            'selector': {'matchLabels': renderer.LABELS}, 'action': 'ALLOW', 'rules': [{'from': [{'source': {
                'principals': ['prod.sajtech.internal/ns/' + ns + '/sa/' + account]}}],
                'to': [{'operation': {'ports': ['9090']}}]}]})]
    names = []
    try:
        replace_policy(boundary, get=lambda kind, name: json.loads(k('get', kind, name, '-o', 'json')),
                       kube=adapter, previous=plan['boundary'])
        for obj in objects: apply(k, obj)
        for obj in sources: policy_ready(k, obj)
        for mode, enrollment in (('positive', 'ambient'), ('no-client', 'ambient'), ('wrong-client', 'ambient'),
                                  ('hostname', 'ambient'), ('identity', 'ambient'), ('plaintext', 'none')):
            name_ = name + '-' + mode
            names.append(name_)
            spec = copy.deepcopy(pod)
            spec['containers'][0]['args'] = [mode]
            if mode == 'identity': spec['serviceAccountName'] = account + '-denied'
            apply(k, {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': name_, 'namespace': ns,
                'labels': {'barman-probe': name, 'istio.io/dataplane-mode': enrollment}}, 'spec': spec})
            def done():
                s = json.loads(k('-n', ns, 'get', 'pod', name_, '-o', 'json')).get('status', {}).get('containerStatuses', [])
                return s and s[0].get('state', {}).get('terminated')
            state = wait(done, seconds=90)
            logs = k('-n', ns, 'logs', name_).strip()
            positive = mode == 'positive'
            if state['exitCode'] != (0 if positive else 1) or logs != (
                    b'BARMAN_TLS=Passed' if positive else b'BARMAN_TLS=Denied'):
                raise RehearsalFailed('Barman TLS or STRICT identity boundary failed')
    finally:
        for name_ in names: k('-n', ns, 'delete', 'pod', name_, '--ignore-not-found', '--timeout=30s')
        for obj in reversed(objects):
            m = obj['metadata']
            scope = ['-n', m['namespace']] if 'namespace' in m else []
            k(*scope, 'delete', obj['kind'], m['name'], '--ignore-not-found')
        replace_policy(plan['boundary'], get=lambda kind, name: json.loads(k('get', kind, name, '-o', 'json')),
                       kube=adapter, previous=boundary)
        policy_ready(k, plan['boundary'])


def rehearse(k, wait, manifest, cnpg_manifest, cert_manifest):
    plan = renderer.render(manifest, cnpg_manifest, cert_manifest, ['10.43.0.1'])
    def adapter(*args, body=None, **options):
        return k(*args, data=json.dumps(body).encode() if body is not None else None, **options)
    # Metadata-only reservation prerequisite in this disposable cluster; target guard has separate native evidence.
    for obj in volume.candidate(): apply(k, obj)
    try:
        result = installer.execute(plan, kube=adapter)
        installer.execute(plan, kube=adapter)
    except (RehearsalFailed, ValueError, KeyError, IndexError) as error:
        frames = [{'function': f.name, 'line': f.lineno} for f in traceback.extract_tb(error.__traceback__)
                  if f.filename.endswith(('/install_barman_control_plane.py', '/rehearse_barman_control_plane.py'))]
        print('BARMAN_FIXED_FAILURE=' + json.dumps({'type': type(error).__name__, 'frames': frames}), flush=True)
        try:
            logs = k('-n', renderer.NAMESPACE, 'logs', 'deployment/' + renderer.DEPLOYMENT, '--tail=100')
            print('BARMAN_FIXED_LOG_CLASSES=' + json.dumps([m.decode() for m in failure_classes(logs)]), flush=True)
        except RehearsalFailed: pass
        raise
    result['idempotent_reconciliation'] = 'Passed'
    dep = next(o for o in plan['objects'] if o['kind'] == 'Deployment')
    pod = {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': 'barman-admission-probe',
        'namespace': renderer.NAMESPACE, 'labels': dep['spec']['template']['metadata']['labels']},
        'spec': dep['spec']['template']['spec']}
    admission_result(k, pod, wait, public_schema=False)
    for mutation in ('identity', 'privilege', 'ambient'):
        wrong = copy.deepcopy(pod)
        if mutation == 'identity': wrong['spec']['serviceAccountName'] = 'default'
        if mutation == 'privilege': wrong['spec']['containers'][0]['securityContext']['privileged'] = True
        if mutation == 'ambient': wrong['metadata']['labels']['istio.io/dataplane-mode'] = 'none'
        admission_result(k, wrong, wait, expected=1, public_schema=False)
    for policy in [p for p in plan['source_policies'] if p['kind'] == 'ImageValidatingPolicy']:
        wrong = copy.deepcopy(policy)
        suffix = policy['metadata']['name'].rsplit('-', 1)[-1]
        if suffix == 'signature': wrong['spec']['attestors'][0]['cosign']['keyless']['identities'][0]['subject'] = 'https://wrong.invalid'
        elif suffix == 'sbom': wrong['spec']['attestations'][0]['intoto']['type'] = 'https://missing.invalid/sbom'
        else:
            wrong['spec']['validations'][1]['expression'] = wrong['spec']['validations'][1]['expression'].replace(
                renderer.candidate()['publication']['repository_revision'], '0' * 40)
        try:
            replace_policy(wrong, get=lambda kind, name: json.loads(k('get', kind, name, '-o', 'json')),
                           kube=adapter, previous=policy)
            admission_result(k, pod, wait, expected=1, public_schema=False)
        finally:
            replace_policy(policy, get=lambda kind, name: json.loads(k('get', kind, name, '-o', 'json')),
                           kube=adapter, previous=wrong)
            policy_ready(k, policy)
        admission_result(k, pod, wait, public_schema=False)
    result['source_identity_positive_negative'] = 'Passed'
    actor = '--as=system:serviceaccount:' + renderer.NAMESPACE + ':' + renderer.ACCOUNT
    for namespace in ('platform-apps', 'hooshix-secrets'):
        obj = renderer.resource('Role', 'postgresql-barman-cloud', namespace, rules=[])
        k('create', '--dry-run=server', '-f', '-', actor, data=json.dumps(obj).encode(),
          expected=1, required_error=b'forbidden')
    wrong = renderer.resource('Role', 'unapproved', 'platform-data', rules=[])
    k('create', '--dry-run=server', '-f', '-', actor, data=json.dumps(wrong).encode(),
      expected=1, required_error=b'fixed PostgreSQL delegation')
    valid = copy.deepcopy(wrong)
    valid['metadata']['name'] = 'postgresql-barman-cloud'
    k('create', '--dry-run=server', '-f', '-', actor, data=json.dumps(valid).encode())
    escalated = copy.deepcopy(valid)
    escalated['rules'] = [{'apiGroups': [''], 'resources': ['secrets'], 'verbs': ['get']}]
    k('create', '--dry-run=server', '-f', '-', actor, data=json.dumps(escalated).encode(),
      expected=1, required_error=b'forbidden')
    for ns in (renderer.NAMESPACE, 'platform-data'):
        k('-n', ns, 'get', 'secret', 'hooshix-no-credential-probe', actor,
          expected=1, required_error=b'forbidden')
    result['rbac_actual_positive_negative'] = 'Passed'
    tls_boundary(k, wait, adapter, plan)
    result['tls_client_hostname_and_strict_mtls'] = 'Passed'
    logs = k('-n', renderer.NAMESPACE, 'logs', 'deployment/' + renderer.DEPLOYMENT, '--tail=-1')
    if b'PRIVATE KEY-----' in logs: raise RehearsalFailed('Barman private-key log marker')
    result['container_key_log_markers'] = 'Passed'
    result['production_target'] = 'Not verified'
    return result
