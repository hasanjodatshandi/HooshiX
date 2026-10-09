"""Six namespace-bound delivery identities; no static reviewer or application secrets."""
from __future__ import annotations

import base64
import ipaddress
import json

import activate_openbao_host as host

NAMESPACE = 'platform-apps'
MOUNT = 'hooshix-kubernetes'
AUDIENCE = 'hooshix-openbao'
SERVICES = ('authorization-service', 'compromised-password-service', 'conversation-service',
            'identity-service', 'notification-service', 'web-bff')
LABELS = {'app.kubernetes.io/part-of': 'hooshix-platform',
          'app.kubernetes.io/managed-by': 'hooshix-openbao-scoped-auth'}
DESCRIPTION = 'HooshiX scoped secret delivery; client JWT TokenReview, no static reviewer'


def service(value):
    host.require(value in SERVICES, 'AUTH_SERVICE_REJECTED')
    return value


def policy(name):
    service(name)
    return (f'path "hooshix/data/production/{name}/*" {{ capabilities = ["read"] }}\n')


def role(name):
    service(name)
    return {'bound_service_account_names': ['eso-' + name],
            'bound_service_account_namespaces': [NAMESPACE], 'audience': AUDIENCE,
            'token_policies': ['hooshix-read-' + name], 'token_no_default_policy': True,
            'token_ttl': 300, 'token_max_ttl': 300, 'token_explicit_max_ttl': 300,
            'token_period': 0, 'token_num_uses': 0, 'token_type': 'service',
            'bound_service_account_namespace_selector': '', 'token_bound_cidrs': []}


def resource(api, kind, name, **body):
    metadata = {'name': name, 'labels': LABELS.copy()}
    if kind not in ('Namespace', 'ClusterRole', 'ClusterRoleBinding'):
        metadata['namespace'] = NAMESPACE
    return {'apiVersion': api, 'kind': kind, 'metadata': metadata, **body}


def foundation():
    namespace = resource('v1', 'Namespace', NAMESPACE)
    namespace['metadata']['labels'].update({'istio.io/dataplane-mode': 'ambient',
        'pod-security.kubernetes.io/enforce': 'restricted',
        'pod-security.kubernetes.io/enforce-version': 'v1.35'})
    objects = [namespace,
        resource('networking.k8s.io/v1', 'NetworkPolicy', 'hooshix-auth-default-deny', spec={
            'podSelector': {}, 'policyTypes': ['Ingress', 'Egress'], 'ingress': [], 'egress': []}),
        resource('security.istio.io/v1', 'PeerAuthentication', 'hooshix-auth-strict',
                 spec={'mtls': {'mode': 'STRICT'}}),
        resource('security.istio.io/v1', 'AuthorizationPolicy', 'hooshix-auth-default-deny', spec={}),
        resource('rbac.authorization.k8s.io/v1', 'ClusterRole', 'hooshix-openbao-tokenreview', rules=[{
            'apiGroups': ['authentication.k8s.io'], 'resources': ['tokenreviews'], 'verbs': ['create']}]),
        resource('rbac.authorization.k8s.io/v1', 'ClusterRoleBinding', 'hooshix-openbao-tokenreview',
            roleRef={'apiGroup': 'rbac.authorization.k8s.io', 'kind': 'ClusterRole',
                     'name': 'hooshix-openbao-tokenreview'},
            subjects=[{'kind': 'ServiceAccount', 'name': 'eso-' + name, 'namespace': NAMESPACE}
                      for name in SERVICES])]
    objects.extend(resource('v1', 'ServiceAccount', 'eso-' + name,
                            automountServiceAccountToken=False) for name in SERVICES)
    return objects


def tokenreview_egress(addresses):
    host.require(isinstance(addresses, list) and 1 <= len(addresses) <= 8
                 and all(str(ipaddress.IPv4Address(a)) == a for a in addresses),
                 'AUTH_API_ADDRESS_REJECTED')
    value = resource('networking.k8s.io/v1', 'NetworkPolicy', 'hooshix-openbao-tokenreview',
        spec={'podSelector': {'matchLabels': {'app.kubernetes.io/name': 'openbao'}},
              'policyTypes': ['Egress'], 'egress': [{
                  'to': [{'ipBlock': {'cidr': address + '/32'}} for address in sorted(set(addresses))],
                  'ports': [{'protocol': 'TCP', 'port': 6443}, {'protocol': 'TCP', 'port': 443}]}]})
    value['metadata']['namespace'] = 'hooshix-secrets'
    return value


def store(name, api_audiences):
    service(name)
    host.require(isinstance(api_audiences, list) and 1 <= len(api_audiences) <= 4
                 and all(isinstance(a, str) and 1 <= len(a) <= 256 for a in api_audiences),
                 'AUTH_API_AUDIENCE_REJECTED')
    return resource('external-secrets.io/v1', 'SecretStore', 'hooshix-' + name, spec={
        'provider': {'vault': {'server': 'https://openbao.hooshix-secrets.svc:8200',
            'path': 'hooshix', 'version': 'v2',
            'caProvider': {'type': 'ConfigMap', 'name': 'hooshix-openbao-ca', 'key': 'ca.crt'},
            'auth': {'kubernetes': {'mountPath': MOUNT, 'role': 'eso-' + name,
                'serviceAccountRef': {'name': 'eso-' + name,
                    'audiences': list(dict.fromkeys([*api_audiences, AUDIENCE]))}}}}}})


def jwt_audiences(jwt):
    # Only decode a fresh TokenRequest returned by the authenticated Kubernetes API.
    # This is audience discovery, not JWT signature verification or identity authority.
    host.require(isinstance(jwt, str) and 20 <= len(jwt) <= 16384 and len(jwt.split('.')) == 3,
                 'AUTH_TOKENREQUEST_REJECTED')
    payload = jwt.split('.')[1]
    claims = json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))
    audiences = claims.get('aud')
    audiences = [audiences] if isinstance(audiences, str) else audiences
    host.require(isinstance(audiences, list) and 1 <= len(audiences) <= 4
                 and len(set(audiences)) == len(audiences)
                 and all(isinstance(a, str) and 1 <= len(a) <= 256 for a in audiences),
                 'AUTH_API_AUDIENCE_REJECTED')
    return audiences


def configure(api, root, kube_host, kube_ca):
    host.require(kube_host.startswith('https://') and kube_ca.startswith('-----BEGIN CERTIFICATE-----'),
                 'AUTH_KUBERNETES_TLS_REQUIRED')
    mounts = api.call('sys/mounts', token=root)['data']
    if 'hooshix/' not in mounts:
        api.call('sys/mounts/hooshix', 'POST', {'type': 'kv',
            'description': DESCRIPTION, 'options': {'version': '2'}}, root, 204)
    else:
        host.require(mounts['hooshix/']['type'] == 'kv'
                     and mounts['hooshix/'].get('options', {}).get('version') == '2'
                     and mounts['hooshix/'].get('description') == DESCRIPTION,
                     'AUTH_EXISTING_KV_CONFLICT')
    auth = api.call('sys/auth', token=root)['data']
    if MOUNT + '/' not in auth:
        api.call('sys/auth/' + MOUNT, 'POST', {'type': 'kubernetes', 'description': DESCRIPTION}, root, 204)
    else:
        host.require(auth[MOUNT + '/']['type'] == 'kubernetes'
                     and auth[MOUNT + '/']['description'] == DESCRIPTION, 'AUTH_MOUNT_OWNERSHIP_CONFLICT')
    config_path = 'auth/' + MOUNT + '/config'
    expected = {'kubernetes_host': kube_host, 'kubernetes_ca_cert': kube_ca,
                'disable_local_ca_jwt': True, 'disable_iss_validation': True}
    existing = api.call(config_path, token=root, expected=(200, 404))
    if not existing:
        api.call(config_path, 'POST', expected, root, 204)
        existing = api.call(config_path, token=root)
    config = existing['data']
    host.require(all(config.get(key) == value for key, value in expected.items())
                 and not config.get('token_reviewer_jwt_set', False)
                 and not config.get('pem_keys') and not config.get('issuer'), 'AUTH_CONFIG_CONFLICT')
    for name in SERVICES:
        path = 'sys/policies/acl/hooshix-read-' + name
        old = api.call(path, token=root, expected=(200, 404))
        if not old:
            api.call(path, 'PUT', {'policy': policy(name)}, root, 204)
            old = api.call(path, token=root)
        host.require(old['data']['policy'].strip() == policy(name).strip(), 'AUTH_POLICY_CONFLICT')
        path = 'auth/' + MOUNT + '/role/eso-' + name
        old = api.call(path, token=root, expected=(200, 404))
        if not old:
            api.call(path, 'POST', role(name), root, 204)
            old = api.call(path, token=root)
        host.require(all(old['data'].get(key) == value for key, value in role(name).items()),
                     'AUTH_ROLE_CONFLICT')


def verify(api, root, token_request, api_audiences):
    results = []
    for index, name in enumerate(SERVICES):
        other = SERVICES[(index + 1) % len(SERVICES)]
        login = 'auth/' + MOUNT + '/login'
        jwt = token_request(NAMESPACE, 'eso-' + name, [*api_audiences, AUDIENCE])
        response = api.call(login, 'POST', {'role': 'eso-' + name, 'jwt': jwt})['auth']
        token = response['client_token']
        try:
            host.require(response['policies'] == ['hooshix-read-' + name]
                         and type(response['lease_duration']) is int
                         and 0 < response['lease_duration'] <= 300, 'AUTH_TOKEN_SCOPE_REJECTED')
            paths = [f'hooshix/data/production/{name}/commissioning-probe',
                     f'hooshix/data/production/{other}/commissioning-probe', 'sys/mounts']
            capabilities = api.call('sys/capabilities', 'POST', {'token': token, 'paths': paths}, root)['data']
            host.require(capabilities[paths[0]] == ['read']
                         and capabilities[paths[1]] == ['deny']
                         and capabilities[paths[2]] == ['deny'], 'AUTH_CAPABILITY_SCOPE_FAILED')
            # Read-only empty path may return 404, but never a permission denial.
            api.call(paths[0], token=token, expected=(200, 404))
            api.call(paths[1], token=token, expected=403)
            api.call(paths[0], 'POST', {'data': {'value': 'synthetic-denial-probe'}}, token, 403)
            api.call('sys/mounts', token=token, expected=403)
            api.call(login, 'POST', {'role': 'eso-' + other, 'jwt': jwt}, expected=403)
            wrong_audience = token_request(NAMESPACE, 'eso-' + name, api_audiences)
            api.call(login, 'POST', {'role': 'eso-' + name, 'jwt': wrong_audience}, expected=403)
            wrong_namespace = token_request('default', 'default', [*api_audiences, AUDIENCE])
            api.call(login, 'POST', {'role': 'eso-' + name, 'jwt': wrong_namespace}, expected=403)
        finally:
            api.call('auth/token/revoke', 'POST', {'token': token}, root, 204)
        api.call(paths[0], token=token, expected=403)
        results.append({'service': name, 'login': 'Passed', 'own_read_capability': 'Passed',
            'cross_service_write_admin_identity_audience_denial': 'Passed', 'token_revocation': 'Passed'})
    host.require(api.call('auth/token/lookup-self', token=root)['data']['policies'] == ['root'],
                 'AUTH_ROOT_UNEXPECTEDLY_CHANGED')
    return results
