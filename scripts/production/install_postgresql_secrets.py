"""Supervised namespace-only ESO reconciliation; no database or provider operation."""
from __future__ import annotations

import base64
import copy
import hashlib
import json
import re
import secrets
import sys

import postgresql_secret_delivery as contract
import openbao_scoped_auth as auth
from install_eso import desired_fields_match, addresses
from upgrade_mesh_security import replace_policy


def require(value, reason):
    if not value:
        print('PG_SECRET_INVARIANT=' + reason, file=sys.stderr, flush=True)
        raise ValueError(reason)


def credentials(api, root):
    """Whole-set preflight and create-only CAS; reentry never rotates or adopts a foreign value."""
    values, missing = {}, []
    for name, item in contract.ENTRIES.items():
        value = api.call('hooshix/data/' + item['key'], token=root, expected=(200, 404))
        if value:
            raw = value['data']['data']
            require(set(raw) == {'username', 'password', 'managed_by', 'installation_id'}
                and raw['username'] == item['username'] and raw['managed_by'] == contract.MANAGER
                and raw['installation_id'] == 'hooshix-production'
                and isinstance(raw['password'], str) and re.fullmatch(r'[A-Za-z0-9_-]{64}', raw['password']),
                'PG_SECRET_FOREIGN_CREDENTIAL_PRESERVED')
        else:
            require(not api.call('hooshix/metadata/' + item['key'], token=root, expected=(200, 404)),
                    'PG_SECRET_DELETED_HISTORY_PRESERVED')
            raw = {'username': item['username'], 'password': secrets.token_urlsafe(48),
                   'managed_by': contract.MANAGER, 'installation_id': 'hooshix-production'}
            missing.append(name)
        values[name] = raw
    require(len({v['password'] for v in values.values()}) == len(contract.ENTRIES),
            'PG_SECRET_DISTINCT_PASSWORDS_REQUIRED')
    for name in missing:
        api.call('hooshix/data/' + contract.entry(name)['key'], 'POST',
                 {'options': {'cas': 0}, 'data': values[name]}, root)
    for name, item in contract.ENTRIES.items():
        require(api.call('hooshix/data/' + item['key'], token=root)['data']['data'] == values[name],
                'PG_SECRET_CREDENTIAL_READBACK_FAILED')
    return values


def verify_auth(api, root, token_request, audiences):
    accounts = list(contract.ENTRIES)
    for index, name in enumerate(accounts):
        item = contract.entry(name)
        jwt = token_request(contract.NAMESPACE, item['account'], [*audiences, auth.AUDIENCE])
        login = 'auth/' + auth.MOUNT + '/login'
        session = api.call(login, 'POST', {'role': item['account'], 'jwt': jwt})['auth']
        token = session['client_token']
        try:
            require(session['policies'] == [item['policy']] and type(session['lease_duration']) is int
                    and 0 < session['lease_duration'] <= 300, 'PG_SECRET_TOKEN_SCOPE_REJECTED')
            path = 'hooshix/data/' + item['key']
            api.call(path, token=token)
            for other in accounts:
                if other != name:
                    api.call('hooshix/data/' + contract.entry(other)['key'], token=token, expected=403)
            api.call(path, 'POST', {'data': {'value': 'denied'}}, token, 403)
            api.call('sys/mounts', token=token, expected=403)
            api.call('hooshix/data/production/identity-service/commissioning-probe', token=token, expected=403)
            api.call(login, 'POST', {'role': contract.entry(accounts[(index + 1) % 10])['account'],
                                     'jwt': jwt}, expected=403)
            for namespace, account, requested in [(contract.NAMESPACE, item['account'], audiences),
                ('default', 'default', [*audiences, auth.AUDIENCE])]:
                wrong = token_request(namespace, account, requested)
                api.call(login, 'POST', {'role': item['account'], 'jwt': wrong}, expected=403)
            api.call('auth/token/lookup-self', token=token)
        finally:
            api.call('auth/token/revoke-self', 'POST', {}, token, 204)
        api.call(path, token=token, expected=403)
    require(api.call('auth/token/lookup-self', token=root)['data']['policies'] == ['root'],
            'PG_SECRET_ROOT_CHANGED')


def webhook_configuration(plan, kube, name, *, commit):
    old = json.loads(kube('get', 'validatingwebhookconfiguration', name, '-o', 'json'))
    hooks = copy.deepcopy(old['webhooks'])
    require(hooks and len(hooks) <= 8, 'PG_SECRET_EXISTING_WEBHOOK_REQUIRED')
    for hook in hooks:
        require(hook['failurePolicy'] == 'Fail' and hook['timeoutSeconds'] == 5
            and hook.get('namespaceSelector') in (plan['webhook_previous_selector'], plan['webhook_selector'])
            and hook['clientConfig'].get('caBundle')
            and hook['clientConfig'].get('service', {}).get('namespace') == 'platform-apps'
            and hook['clientConfig']['service'].get('name') == 'hooshix-eso-webhook'
            and not hook.get('matchConditions')
            and 'url' not in hook['clientConfig'] and hook.get('rules')
            and all(r.get('scope') == 'Namespaced' and set(r['resources']) <= {'secretstores', 'externalsecrets'}
                    for r in hook['rules']), 'PG_SECRET_WEBHOOK_BOUNDARY_CONFLICT')
        hook['namespaceSelector'] = copy.deepcopy(plan['webhook_selector'])
    if not commit: return
    if hooks != old['webhooks']:
        kube('patch', 'validatingwebhookconfiguration', name, '--type=json', '-p', json.dumps([
            {'op': 'test', 'path': '/metadata/resourceVersion', 'value': old['metadata']['resourceVersion']},
            {'op': 'test', 'path': '/webhooks', 'value': old['webhooks']},
            {'op': 'replace', 'path': '/webhooks', 'value': hooks}]))
    require(json.loads(kube('get', 'validatingwebhookconfiguration', name, '-o', 'json'))['webhooks'] == hooks,
            'PG_SECRET_WEBHOOK_READBACK_FAILED')


def webhook(plan, kube, *, commit=True):
    for name in plan['webhook_names']:
        webhook_configuration(plan, kube, name, commit=False)
    if commit:
        for name in plan['webhook_names']:
            webhook_configuration(plan, kube, name, commit=True)


def execute(plan, *, kube, api, root, ca, audiences, token_request):
    print('PG_SECRET_STEP=preflight', file=sys.stderr, flush=True)
    require(plan.get('schema_version') == 1 and plan.get('installation_id') == 'hooshix-production'
        and plan.get('database') == 'Not run' and hashlib.sha256(ca.encode()).hexdigest() == plan['public_ca_sha256'],
        'PG_SECRET_REVIEWED_PLAN_AND_CA_REQUIRED')
    barman = json.loads(kube('-n', 'cnpg-system', 'get', 'deployment', 'barman-cloud', '-o', 'json'))
    require(barman['status'].get('availableReplicas') == 1, 'PG_SECRET_EXISTING_BARMAN_REQUIRED')
    ns = json.loads(kube('get', 'namespace', contract.NAMESPACE, '-o', 'json'))
    require(ns['metadata']['labels'].get('istio.io/dataplane-mode') == 'ambient'
        and ns['metadata']['labels'].get('pod-security.kubernetes.io/enforce') == 'restricted',
        'PG_SECRET_EXISTING_RESTRICTED_NAMESPACE_REQUIRED')
    for kind in ('pvc', 'clusters.postgresql.cnpg.io'):
        require(not json.loads(kube('-n', contract.NAMESPACE, 'get', kind, '-o', 'json'))['items'],
                'PG_SECRET_EXISTING_DATABASE_PRESERVED')
    reviewer = json.loads(kube('get', 'clusterrole', 'hooshix-openbao-tokenreview', '-o', 'json'))
    require(reviewer.get('rules') == [{'apiGroups': ['authentication.k8s.io'],
        'resources': ['tokenreviews'], 'verbs': ['create']}] and not reviewer.get('aggregationRule'),
        'PG_SECRET_EXACT_EXISTING_TOKENREVIEW_ROLE_REQUIRED')
    peers = [{'ipBlock': {'cidr': a + '/32'}} for a in addresses(kube)]
    networks = copy.deepcopy(plan['network'])
    networks[0]['spec']['egress'][0]['to'] = peers
    objects = [*plan['objects'], *networks, plan['authorization'], *plan['source_policies'],
        contract.resource('ConfigMap', 'hooshix-openbao-ca', data={'ca.crt': ca}),
        *[contract.store(name, audiences) for name in contract.ENTRIES],
        *[contract.external(name) for name in contract.ENTRIES]]
    exists = {}
    actuals = {}
    for obj in objects:
        m = obj['metadata']
        require(obj['kind'] not in ('Secret', 'Cluster', 'Database', 'PersistentVolume', 'PersistentVolumeClaim'),
                'PG_SECRET_DIRECT_DATA_WRITE_REJECTED')
        scope = ['-n', m['namespace']] if 'namespace' in m else []
        raw = kube(*scope, 'get', obj['kind'], m['name'], '--ignore-not-found', '-o', 'json')
        actual = json.loads(raw) if raw.strip() else None
        exists[(obj['kind'], m.get('namespace'), m['name'])] = bool(actual)
        actuals[(obj['kind'], m.get('namespace'), m['name'])] = actual
        require(not actual or actual['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER
            and desired_fields_match(actual, obj), 'PG_SECRET_FOREIGN_OR_CHANGED_RESOURCE_PRESERVED')
        if actual and obj['kind'] in ('Role', 'ClusterRoleBinding', 'RoleBinding'):
            require(all(actual.get(key) == obj.get(key) for key in ('rules', 'roleRef', 'subjects') if key in obj),
                    'PG_SECRET_EXTRA_PERMISSION_REJECTED')
        if actual and obj['kind'] in ('NetworkPolicy', 'AuthorizationPolicy', 'PeerAuthentication'):
            spec = copy.deepcopy(actual.get('spec', {}))
            for direction in ('ingress', 'egress'):
                if obj['spec'].get(direction) == []: spec.setdefault(direction, [])
            require(spec == obj['spec'] and actual['metadata'].get('annotations', {}).get('istio.io/dry-run')
                    in (None, 'false'), 'PG_SECRET_NETWORK_OR_MESH_DRIFT')
    for name in contract.ENTRIES:
        raw = kube('-n', contract.NAMESPACE, 'get', 'secret', name, '--ignore-not-found', '-o', 'jsonpath={.metadata}')
        if not raw.strip(): continue
        metadata = json.loads(raw)
        external = actuals[('ExternalSecret', contract.NAMESPACE, name)]
        require(metadata.get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER
            and external and metadata.get('ownerReferences') == [{'apiVersion': 'external-secrets.io/v1',
                'kind': 'ExternalSecret', 'name': name, 'uid': external['metadata']['uid'],
                'controller': True, 'blockOwnerDeletion': True}], 'PG_SECRET_FOREIGN_TARGET_SECRET_PRESERVED')
    previous = json.loads(kube('get', 'validatingpolicy', plan['boundary']['metadata']['name'], '-o', 'json'))
    require(previous['spec'] in (plan['previous_boundary']['spec'], plan['boundary']['spec']),
            'PG_SECRET_DATABASE_ADMISSION_CONFLICT')
    contract.check_existing(api, root)
    webhook(plan, kube, commit=False)
    # Credential conflicts are found before Kubernetes writes; partial CAS writes remain safely resumable.
    print('PG_SECRET_STEP=credential-and-auth', file=sys.stderr, flush=True)
    values = credentials(api, root)
    contract.configure(api, root)
    webhook(plan, kube)

    def create(obj):
        m = obj['metadata']
        if exists[(obj['kind'], m.get('namespace'), m['name'])]: return
        kube('create', '-f', '-', body=obj)

    for obj in objects:
        if obj['kind'] not in ('Deployment', 'ExternalSecret', 'SecretStore'): create(obj)
    for p in plan['source_policies']:
        kube('wait', '--for=jsonpath={.status.conditionStatus.ready}=true',
             p['kind'].lower() + '/' + p['metadata']['name'], '--timeout=60s', timeout=75)
    if previous['spec'] != plan['boundary']['spec']:
        replace_policy(plan['boundary'], get=lambda kind, name: json.loads(kube('get', kind, name, '-o', 'json')),
                       kube=kube, previous=plan['previous_boundary'])
    kube('wait', '--for=jsonpath={.status.conditionStatus.ready}=true',
         'validatingpolicy/' + plan['boundary']['metadata']['name'], '--timeout=60s', timeout=75)
    dep = next(o for o in objects if o['kind'] == 'Deployment')
    print('PG_SECRET_STEP=controller', file=sys.stderr, flush=True)
    create(dep)
    kube('-n', contract.NAMESPACE, 'rollout', 'status', 'deployment/' + contract.ACCOUNT,
         '--timeout=180s', timeout=195)
    for obj in objects:
        if obj['kind'] not in ('ExternalSecret', 'SecretStore'): continue
        create(obj)
        kube('-n', contract.NAMESPACE, 'wait', '--for=condition=Ready',
             obj['kind'].lower() + '/' + obj['metadata']['name'], '--timeout=60s', timeout=75)
    print('PG_SECRET_STEP=verification', file=sys.stderr, flush=True)
    verify_auth(api, root, token_request, audiences)
    for name in contract.ENTRIES:
        actual = json.loads(kube('-n', contract.NAMESPACE, 'get', 'secret', name, '-o', 'json'))
        require(actual['type'] == 'kubernetes.io/basic-auth' and set(actual['data']) == {'username', 'password'}
            and actual['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.MANAGER
            and all(base64.b64decode(actual['data'][key], validate=True).decode() == values[name][key]
                    for key in ('username', 'password')), 'PG_SECRET_DELIVERY_READBACK_FAILED')
    actor = '--as=system:serviceaccount:' + contract.NAMESPACE + ':' + contract.ACCOUNT
    for ns in ('platform-apps', 'hooshix-secrets', 'cnpg-system', 'default'):
        for verb in ('get', 'list', 'watch', 'create', 'patch', 'delete'):
            require(kube('auth', 'can-i', verb, 'secrets', '-n', ns, actor, expected=1).strip() == b'no',
                    'PG_SECRET_FOREIGN_SECRET_ACCESS_REJECTED')
    for item in contract.ENTRIES.values():
        require(kube('auth', 'can-i', 'create', 'serviceaccounts/' + item['account'], '--subresource=token',
            '-n', contract.NAMESPACE, actor).strip() == b'yes', 'PG_SECRET_FIXED_TOKENREQUEST_REQUIRED')
    require(kube('auth', 'can-i', 'create', 'serviceaccounts/default', '--subresource=token',
        '-n', contract.NAMESPACE, actor, expected=1).strip() == b'no', 'PG_SECRET_FOREIGN_TOKENREQUEST_REJECTED')
    pods = json.loads(kube('-n', contract.NAMESPACE, 'get', 'pods', '-o', 'json'))['items']
    require(len(pods) == 1 and pods[0]['spec']['serviceAccountName'] == contract.ACCOUNT
        and pods[0]['spec']['containers'][0]['image'] == plan['image']
        and pods[0]['status']['containerStatuses'][0]['ready']
        and pods[0]['status']['containerStatuses'][0]['imageID'].endswith(plan['image'].split('@')[1]),
        'PG_SECRET_EXACT_READY_CONTROLLER_REQUIRED')
    retained = json.loads(kube('-n', 'cnpg-system', 'get', 'deployment', 'barman-cloud', '-o', 'json'))
    require(retained['metadata']['uid'] == barman['metadata']['uid'] and retained['spec'] == barman['spec']
        and retained['status'].get('availableReplicas') == 1, 'PG_SECRET_EXISTING_BARMAN_CHANGED')
    return {'schema_version': 1, 'image': plan['image'], 'namespace': contract.NAMESPACE,
        'credential_count': len(values), 'delivery': 'Passed', 'scoped_authentication': 'Passed',
        'foreign_secret_and_tokenrequest_denial': 'Passed', 'root_revocation': 'Not run',
        'database': 'Not run', 'provider_backup': 'Not run', 'public_access': 'CLOSED',
        'production_readiness': 'Not verified'}
