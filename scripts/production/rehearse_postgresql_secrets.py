"""Real signed ESO/ten-identity delivery in the existing disposable Ambient fixture."""
import copy
import hashlib
import json
import sys

import install_barman_control_plane as platform
import install_postgresql_secrets as installer
import openbao_scoped_auth as auth
import postgresql_secret_delivery as contract
import render_postgresql_secrets as renderer
from rehearse_platform_admission import admission_result, policy_ready
from upgrade_mesh_security import replace_policy


def rehearse(k, wait, chart, helm, api, root, ca, audiences, addresses):
    print('PG_SECRET_NATIVE_STEP=delivery', flush=True)
    plan = renderer.render(chart, helm, addresses)
    plan['public_ca_sha256'] = hashlib.sha256(ca.encode()).hexdigest()
    def adapter(*args, body=None, **options):
        return k(*args, data=json.dumps(body).encode() if body is not None else None, **options)
    def token_request(namespace, account, requested):
        return k('-n', namespace, 'create', 'token', account, '--duration=10m',
                 *['--audience=' + a for a in requested]).decode().strip()
    before = platform.protected(adapter)
    old_identities = [(api.call('sys/policies/acl/hooshix-read-' + s, token=root)['data'],
                       api.call('auth/' + auth.MOUNT + '/role/eso-' + s, token=root)['data']) for s in auth.SERVICES]
    # Exercise the already-installed VPS's exact old selector -> bounded two-namespace transition.
    for name in plan['webhook_names']:
        obj = json.loads(k('get', 'validatingwebhookconfiguration', name, '-o', 'json'))
        hooks = copy.deepcopy(obj['webhooks'])
        for hook in hooks: hook['namespaceSelector'] = copy.deepcopy(plan['webhook_previous_selector'])
        k('patch', 'validatingwebhookconfiguration', name, '--type=json', '-p', json.dumps([
            {'op': 'test', 'path': '/metadata/resourceVersion', 'value': obj['metadata']['resourceVersion']},
            {'op': 'replace', 'path': '/webhooks', 'value': hooks}]))
    result = installer.execute(plan, kube=adapter, api=api, root=root, ca=ca,
        audiences=audiences, token_request=token_request)
    secrets_before = {name: k('-n', contract.NAMESPACE, 'get', 'secret', name,
        '-o', 'jsonpath={.metadata.uid}:{.metadata.resourceVersion}') for name in contract.ENTRIES}
    installer.execute(plan, kube=adapter, api=api, root=root, ca=ca,
        audiences=audiences, token_request=token_request)
    if any(k('-n', contract.NAMESPACE, 'get', 'secret', name,
             '-o', 'jsonpath={.metadata.uid}:{.metadata.resourceVersion}') != value for name, value in secrets_before.items()):
        raise ValueError('PG_SECRET_REENTRY_ROTATED_OR_REPLACED_SECRET')
    result['idempotent_reconciliation'] = 'Passed'
    print('PG_SECRET_NATIVE_STEP=admission', flush=True)
    dep = next(o for o in plan['objects'] if o['kind'] == 'Deployment')
    pod = {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': 'pg-secret-admission-probe',
        'namespace': contract.NAMESPACE, 'labels': dep['spec']['template']['metadata']['labels']},
        'spec': copy.deepcopy(dep['spec']['template']['spec'])}
    admission_result(k, pod, wait, public_schema=False)
    for mode in ('default', 'database', 'privileged', 'plaintext', 'arbitrary-command', 'token-volume'):
        bad = copy.deepcopy(pod)
        if mode in ('default', 'database'): bad['spec']['serviceAccountName'] = 'default' if mode == 'default' else 'postgresql'
        if mode == 'privileged': bad['spec']['containers'][0]['securityContext']['privileged'] = True
        if mode == 'plaintext': bad['metadata']['labels']['istio.io/dataplane-mode'] = 'none'
        if mode == 'arbitrary-command': bad['spec']['containers'][0]['command'] = ['/bin/sh']
        if mode == 'token-volume': bad['spec']['volumes'] = [{'name': 'arbitrary', 'secret': {'secretName': 'unapproved'}}]
        admission_result(k, bad, wait, expected=1, public_schema=False)
    result['database_admission_closed'] = 'Passed'
    for original in plan['source_policies']:
        wrong = copy.deepcopy(original)
        name = original['metadata']['name']
        if name.endswith('-signature'):
            wrong['spec']['attestors'][0]['cosign']['keyless']['identities'][0]['subject'] = 'https://wrong.invalid'
        elif name.endswith('-sbom'):
            wrong['spec']['attestations'][0]['intoto']['type'] = 'https://missing.invalid/sbom'
        else:
            revision = renderer.existing.pin()['published_security_candidate']['repository_revision']
            wrong['spec']['validations'][-1]['expression'] = wrong['spec']['validations'][-1]['expression'].replace(revision, '0' * 40)
        if wrong == original: raise ValueError('PG_SECRET_SOURCE_NEGATIVE_MUST_CHANGE_POLICY')
        print('PG_SECRET_NATIVE_STEP=' + name, file=sys.stderr, flush=True)
        try:
            replace_policy(wrong, get=lambda kind, name: json.loads(k('get', kind, name, '-o', 'json')),
                           kube=adapter, previous=original)
            admission_result(k, pod, wait, expected=1, public_schema=False)
        finally:
            replace_policy(original, get=lambda kind, name: json.loads(k('get', kind, name, '-o', 'json')),
                           kube=adapter, previous=wrong)
            policy_ready(k, original)
        admission_result(k, pod, wait, public_schema=False)
    result['source_identity_positive_negative'] = 'Passed'
    auth.verify(api, root, lambda ns, account, requested: token_request(ns, account, requested), audiences)
    after = [(api.call('sys/policies/acl/hooshix-read-' + s, token=root)['data'],
              api.call('auth/' + auth.MOUNT + '/role/eso-' + s, token=root)['data']) for s in auth.SERVICES]
    if after != old_identities or platform.protected(adapter) != before:
        raise ValueError('PG_SECRET_EXISTING_AUTHORITY_CHANGED')
    result['existing_application_identities_preserved'] = 'Passed'
    passwords = [api.call('hooshix/data/' + e['key'], token=root)['data']['data']['password']
                 for e in contract.ENTRIES.values()]
    for ns, name in [(contract.NAMESPACE, 'deployment/' + contract.ACCOUNT), ('hooshix-secrets', 'openbao-0')]:
        logs = k('-n', ns, 'logs', name, '--tail=200')
        if any(value.encode() in logs for value in [root, *passwords]):
            raise ValueError('PG_SECRET_CONTAINER_LOG_LEAK')
    result['container_log_privacy'] = 'Passed'
    result['root_mesh_openbao_application_eso_cnpg_barman_storage'] = 'Preserved'
    return result
