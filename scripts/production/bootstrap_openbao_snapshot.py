"""Set one bounded snapshot identity and its private bucket credentials."""
from __future__ import annotations

import base64
import fcntl
import hashlib
import json
import os
import re
import signal
import subprocess
import sys

import activate_openbao_host as host
import openbao_activation_transport as transport
from bootstrap_openbao_auth import API

MOUNT = 'hooshix-kubernetes'
ROLE = 'hooshix-openbao-snapshot'
POLICY = 'hooshix-openbao-snapshot'
KV_PATH = 'hooshix/data/production/openbao-backup'
ACL = ('path "sys/storage/raft/snapshot" { capabilities = ["read"] }\n'
       'path "hooshix/data/production/openbao-backup" { capabilities = ["read"] }\n'
       'path "auth/token/revoke-self" { capabilities = ["update"] }\n')
ROLE_BODY = {'bound_service_account_names': ['openbao-snapshot'],
    'bound_service_account_namespaces': ['hooshix-secrets'], 'audience': 'hooshix-openbao',
    'token_policies': [POLICY], 'token_no_default_policy': True, 'token_ttl': 300,
    'token_max_ttl': 300, 'token_explicit_max_ttl': 300, 'token_type': 'service',
    'token_num_uses': 0, 'token_period': 0, 'bound_service_account_namespace_selector': '',
    'token_bound_cidrs': []}


def configure(api, root, access, secret, recipients):
    host.require(isinstance(access, str) and ':' not in access
                 and isinstance(secret, str), 'SNAPSHOT_BUCKET_CREDENTIALS_REJECTED')
    for value in (access, secret):
        host.require(1 <= len(value) <= 4096 and all(33 <= ord(c) <= 126 for c in value),
                     'SNAPSHOT_BUCKET_CREDENTIALS_REJECTED')
    policy_path = 'sys/policies/acl/' + POLICY
    old_policy = api.call(policy_path, token=root, expected=(200, 404))
    if old_policy:
        host.require(old_policy.get('data', {}).get('policy', '').strip() == ACL.strip(),
                     'SNAPSHOT_POLICY_CONFLICT')
    old_role = api.call('auth/' + MOUNT + '/role/' + ROLE, token=root, expected=(200, 404))
    if old_role:
        host.require(all(old_role.get('data', {}).get(key) == value for key, value in ROLE_BODY.items()),
                     'SNAPSHOT_ROLE_CONFLICT')
    recipient_digest = host.public_keys(recipients)
    existing = api.call(KV_PATH, token=root, expected=(200, 404))
    if old_policy and old_role and existing:
        data = existing.get('data', {}).get('data')
        metadata = existing.get('data', {}).get('metadata', {})
        host.require(isinstance(data, dict) and set(data) == {'access_key', 'secret_key', 'recipients'}
                     and data['access_key'] == access and data['secret_key'] == secret
                     and data['recipients'] == recipients
                     and metadata.get('version') == 1, 'SNAPSHOT_CREDENTIALS_ALREADY_CONFIGURED')
    else:
        host.require(not existing, 'SNAPSHOT_CREDENTIALS_ALREADY_CONFIGURED')
    if not old_policy:
        api.call(policy_path, 'PUT', {'policy': ACL}, root, 204)
        host.require(api.call(policy_path, token=root).get('data', {}).get('policy', '').strip() == ACL.strip(),
                     'SNAPSHOT_POLICY_VERIFY_FAILED')
    if not old_role:
        api.call('auth/' + MOUNT + '/role/' + ROLE, 'POST', ROLE_BODY, root, 204)
        role = api.call('auth/' + MOUNT + '/role/' + ROLE, token=root).get('data', {})
        host.require(all(role.get(key) == value for key, value in ROLE_BODY.items()),
                     'SNAPSHOT_ROLE_VERIFY_FAILED')
    if not existing:
        api.call(KV_PATH, 'POST', {'options': {'cas': 0},
            'data': {'access_key': access, 'secret_key': secret, 'recipients': recipients}}, root, 204)
        stored = api.call(KV_PATH, token=root).get('data', {})
        value, metadata = stored.get('data'), stored.get('metadata', {})
        host.require(isinstance(value, dict) and set(value) == {'access_key', 'secret_key', 'recipients'}
                     and value['access_key'] == access and value['secret_key'] == secret
                     and value['recipients'] == recipients
                     and metadata.get('version') == 1, 'SNAPSHOT_CREDENTIALS_VERIFY_FAILED')


def commission(request, revision):
    host.require(isinstance(request, dict) and set(request) == {
        'root_token', 'recipients', 'access_key', 'secret_key'}, 'SNAPSHOT_BOOTSTRAP_REQUEST_REJECTED')
    root, access, secret = request['root_token'], request['access_key'], request['secret_key']
    host.require(isinstance(root, str) and 10 <= len(root) <= 1024
                 and all(33 <= ord(c) <= 126 for c in root), 'SNAPSHOT_ROOT_TOKEN_REJECTED')
    digest = host.public_keys(request['recipients'])
    before, claim_uid = host.preflight()
    state = host.bao('status')
    host.require(state['initialized'] is True and state['sealed'] is False
                 and state['n'] == 3 and state['t'] == 2, 'SNAPSHOT_ACTIVE_STORE_REQUIRED')
    host.custody.protected(host.STATE, directory=True, exact_mode=0o700)
    with os.fdopen(os.open(host.STATE / 'activation.lock', os.O_RDWR | os.O_NOFOLLOW), 'r+b') as lock:
        host.custody.protected(host.STATE / 'activation.lock', exact_mode=0o600)
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        host.custody.protected(host.STATE / 'attempt.json', exact_mode=0o600)
        intent = json.loads((host.STATE / 'attempt.json').read_bytes())
        host.require(intent['recipient_sha256'] == digest and intent['pvc_uid'] == claim_uid
                     and intent['image'] == host.IMAGE, 'SNAPSHOT_CUSTODY_STORE_CONFLICT')
        ca = base64.b64decode(host.kube('-n', 'hooshix-secrets', 'get', 'secret',
            'openbao-server-tls', '-o', 'jsonpath={.data.ca\\.crt}', operation='SNAPSHOT_PUBLIC_CA'), validate=True)
        host.require(hashlib.sha256(ca).hexdigest() == host.custody.ROOT_SHA256,
                     'SNAPSHOT_ROOT_CA_CONFLICT')
        host.native(['/usr/sbin/auditctl', '-m', 'HooshiX hourly OpenBao snapshot bootstrap ' + revision],
                    operation='SNAPSHOT_BOOTSTRAP_AUDIT')
        with transport.forward(ca.decode('ascii'), as_client=True) as client:
            api = API(client)
            host.require(api.call('auth/token/lookup-self', token=root).get('data', {}).get('policies') == ['root'],
                         'SNAPSHOT_EXISTING_ROOT_REQUIRED')
            configure(api, root, access, secret, request['recipients'])
        host.require(before == host.service_identity()
                     and host.get('pvc', 'data-openbao-0')['metadata']['uid'] == claim_uid,
                     'SNAPSHOT_PRESERVED_TARGET_CHANGED')
        state = host.bao('status')
        host.require(state['initialized'] is True and state['sealed'] is False,
                     'SNAPSHOT_TARGET_STATUS_CHANGED')
    return {'schema_version': 1, 'source_revision': revision, 'pvc_uid': claim_uid,
        'image': host.IMAGE, 'recipient_sha256': digest, 'scoped_identity': 'Passed',
        'policy': 'Passed', 'bucket_credentials': 'Passed', 'credential_values_recorded': False,
        'hourly_schedule': 'Not run', 'target_restore': 'Not run', 'root_revocation': 'Not run',
        'production_readiness': 'Not verified'}


def main(revision):
    os.umask(0o077)
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(
        host.custody.BootstrapFailed('SNAPSHOT_BOOTSTRAP_DEADLINE')))
    signal.alarm(600)
    try:
        host.require(re.fullmatch(r'[a-f0-9]{40}', revision), 'SNAPSHOT_REVISION_REJECTED')
        print('SNAPSHOT_BOOTSTRAP_RPC_READY', flush=True)
        raw = sys.stdin.buffer.readline(host.BOUND + 1)
        host.require(len(raw) <= host.BOUND, 'SNAPSHOT_BOOTSTRAP_REQUEST_BOUND')
        print(json.dumps(commission(json.loads(raw), revision)), flush=True)
        return 0
    except host.custody.BootstrapFailed as error:
        print(json.dumps({'reason': str(error), 'state_preserved': True}), flush=True)
        return 1
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        print(json.dumps({'reason': 'SNAPSHOT_BOOTSTRAP_FAILED_STATE_PRESERVED', 'state_preserved': True}), flush=True)
        return 1
    finally:
        signal.alarm(0)
