"""Owner-supervised scoped authentication on the existing store; never init/restart."""
from __future__ import annotations

import base64
import fcntl
import hashlib
import json
import os
import re
import resource
import signal
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

import activate_openbao_host as host
import openbao_activation_transport as transport
import openbao_scoped_auth as auth


class API:
    def __init__(self, client):
        self.client = client

    def call(self, path, method='GET', body=None, token=None, expected=200):
        host.require(re.fullmatch(r'[a-zA-Z0-9_/-]{1,256}', path) and method in ('GET', 'POST', 'PUT'),
                     'AUTH_API_REQUEST_REJECTED')
        headers = {'Content-Type': 'application/json'}
        if token is not None:
            host.require(isinstance(token, str) and 10 <= len(token) <= 1024
                         and all(33 <= ord(c) <= 126 for c in token), 'AUTH_TOKEN_REJECTED')
            headers['X-Vault-Token'] = token
        content = json.dumps(body).encode() if body is not None else None
        host.require(content is None or len(content) <= host.BOUND, 'AUTH_API_REQUEST_BOUND')
        request = urllib.request.Request(self.client.base + path, data=content, method=method, headers=headers)
        codes = expected if isinstance(expected, tuple) else (expected,)
        try:
            try:
                response = self.client.opener.open(request, timeout=10)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                host.require(response.code in codes, 'AUTH_API_FAILED_STATE_PRESERVED')
                raw = response.read(host.BOUND + 1)
                host.require(len(raw) <= host.BOUND, 'AUTH_API_RESPONSE_BOUND')
                # Error bodies (possibly containing third-party diagnostics) never leave this adapter.
                if response.code >= 400 or not raw:
                    return {}
                value = json.loads(raw)
                host.require(isinstance(value, dict), 'AUTH_API_RESPONSE_REJECTED')
                return value
        except (OSError, ValueError, urllib.error.URLError):
            raise host.custody.BootstrapFailed('AUTH_API_FAILED_STATE_PRESERVED') from None


def reconcile(value):
    metadata = value['metadata']
    namespace = metadata.get('namespace')
    args = ['-n', namespace] if namespace else []
    raw = host.kube(*args, 'get', value['kind'], metadata['name'], '--ignore-not-found', '-o', 'json',
                    operation='AUTH_RESOURCE_READ')
    if not raw.strip():
        host.kube('create', '-f', '-', data=json.dumps(value).encode(), operation='AUTH_RESOURCE_CREATE')
        return
    old = json.loads(raw)
    if value['kind'] == 'Namespace':
        expected = {k: v for k, v in metadata['labels'].items() if k not in auth.LABELS}
        host.require(all(old['metadata'].get('labels', {}).get(k) == v for k, v in expected.items()),
                     'AUTH_NAMESPACE_BOUNDARY_CONFLICT')
    else:
        if value['kind'] == 'NetworkPolicy':
            # The API omits empty ingress/egress arrays; they still mean default deny.
            old.setdefault('spec', {}).setdefault('ingress', [])
            old['spec'].setdefault('egress', [])
            value = json.loads(json.dumps(value))
            value['spec'].setdefault('ingress', [])
            value['spec'].setdefault('egress', [])
        host.require(all(old['metadata'].get('labels', {}).get(k) == v for k, v in auth.LABELS.items())
                     and (value['kind'] != 'ClusterRole' or not old.get('aggregationRule'))
                     and all(old.get(k) == v for k, v in value.items()
                             if k not in ('apiVersion', 'kind', 'metadata')), 'AUTH_RESOURCE_OWNERSHIP_CONFLICT')


def token_request(namespace, account, audiences):
    host.require(namespace in (auth.NAMESPACE, 'default')
                 and account in ('default', *['eso-' + s for s in auth.SERVICES]), 'AUTH_TOKEN_IDENTITY_REJECTED')
    host.require(isinstance(audiences, list) and len(audiences) <= 5
                 and all(isinstance(a, str) and 1 <= len(a) <= 256
                         and not any(c in a for c in '\r\n\x00') for a in audiences),
                 'AUTH_TOKEN_AUDIENCE_REJECTED')
    args = ['-n', namespace, 'create', 'token', account, '--duration=10m']
    args.extend('--audience=' + audience for audience in audiences)
    return host.kube(*args, operation='AUTH_TOKENREQUEST').decode().strip()


def commission(request, revision):
    host.require(isinstance(request, dict) and set(request) == {'root_token', 'recipients'},
                 'AUTH_REQUEST_REJECTED')
    digest = host.public_keys(request['recipients'])
    before, claim_uid = host.preflight()
    status = host.bao('status')
    host.require(status['initialized'] is True and status['sealed'] is False
                 and status['n'] == 3 and status['t'] == 2, 'AUTH_ACTIVE_STORE_REQUIRED')
    host.custody.protected(host.STATE, directory=True, exact_mode=0o700)
    lock_path = host.STATE / 'activation.lock'
    with os.fdopen(os.open(lock_path, os.O_RDWR | os.O_NOFOLLOW), 'r+b') as lock:
        host.custody.protected(lock_path, exact_mode=0o600)
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        host.custody.protected(host.STATE / 'attempt.json', exact_mode=0o600)
        intent = json.loads((host.STATE / 'attempt.json').read_bytes())
        host.require(intent['recipient_sha256'] == digest and intent['pvc_uid'] == claim_uid
                     and intent['image'] == host.IMAGE, 'AUTH_CUSTODY_STORE_CONFLICT')
        certificate = base64.b64decode(host.kube('-n', 'hooshix-secrets', 'get', 'secret',
            'openbao-server-tls', '-o', 'jsonpath={.data.ca\\.crt}', operation='GET_PUBLIC_CA'), validate=True)
        host.require(hashlib.sha256(certificate).hexdigest() == host.custody.ROOT_SHA256,
                     'AUTH_ROOT_CA_CONFLICT')
        kube_ca = json.loads(host.kube('-n', 'hooshix-secrets', 'get', 'configmap', 'kube-root-ca.crt',
            '-o', 'json', operation='AUTH_PUBLIC_KUBE_CA'))['data']['ca.crt']
        with transport.forward(certificate.decode('ascii'), as_client=True) as client:
            api = API(client)
            host.require(api.call('auth/token/lookup-self', token=request['root_token'])['data']['policies'] == ['root'],
                         'AUTH_EXISTING_ROOT_REQUIRED')
            host.native(['/usr/sbin/auditctl', '-m', 'HooshiX scoped OpenBao auth ' + revision],
                        operation='AUTH_AUDIT')
            for value in auth.foundation():
                reconcile(value)
            reconcile(auth.resource('v1', 'ConfigMap', 'hooshix-openbao-ca',
                                    data={'ca.crt': certificate.decode('ascii')}))
            policy = auth.resource('networking.k8s.io/v1', 'NetworkPolicy',
                'hooshix-openbao-tokenreview', spec={'podSelector': {
                    'matchLabels': {'app.kubernetes.io/name': 'openbao'}}, 'policyTypes': ['Egress'], 'egress': [{
                    'to': [{'ipBlock': {'cidr': '188.240.196.151/32'}},
                           {'ipBlock': {'cidr': '10.43.0.1/32'}}],
                    'ports': [{'protocol': 'TCP', 'port': 6443}, {'protocol': 'TCP', 'port': 443}]}]})
            policy['metadata']['namespace'] = 'hooshix-secrets'
            reconcile(policy)
            audiences = auth.jwt_audiences(token_request(auth.NAMESPACE, 'eso-' + auth.SERVICES[0], []))
            auth.configure(api, request['root_token'], 'https://kubernetes.default.svc:443', kube_ca)
            results = auth.verify(api, request['root_token'], token_request, audiences)
        host.storage_preflight()
        host.require(before == host.service_identity() and host.get('pvc', 'data-openbao-0')['metadata']['uid'] == claim_uid,
                     'AUTH_PRESERVED_TARGET_CHANGED')
        status = host.bao('status')
        host.require(status['initialized'] is True and status['sealed'] is False, 'AUTH_TARGET_STATUS_CHANGED')
    return {'schema_version': 1, 'source_revision': revision, 'pvc_uid': claim_uid,
        'image': host.IMAGE, 'recipient_sha256': digest, 'scoped_authentication': 'Passed',
        'identities': results, 'secret_stores': [auth.store(name, audiences) for name in auth.SERVICES],
        'eso_installation': 'Not run', 'application_secret_delivery': 'Not verified',
        'root_revocation': 'Not run', 'public_access': 'CLOSED', 'production_readiness': 'Not verified',
        'observed_at': datetime.now(timezone.utc).isoformat()}


def main(revision):
    os.umask(0o077)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(host.custody.BootstrapFailed('AUTH_DEADLINE')))
    signal.alarm(600)
    try:
        host.require(re.fullmatch(r'[a-f0-9]{40}', revision), 'AUTH_REVISION_REJECTED')
        print('AUTH_RPC_READY', flush=True)
        content = sys.stdin.buffer.readline(host.BOUND + 1)
        host.require(len(content) <= host.BOUND, 'AUTH_REQUEST_BOUND')
        print(json.dumps(commission(json.loads(content), revision)), flush=True)
        return 0
    except host.custody.BootstrapFailed as error:
        print(json.dumps({'reason': str(error), 'state_preserved': True}), flush=True)
        return 1
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        print(json.dumps({'reason': 'AUTH_FAILED_STATE_PRESERVED', 'state_preserved': True}), flush=True)
        return 1
    finally:
        signal.alarm(0)
