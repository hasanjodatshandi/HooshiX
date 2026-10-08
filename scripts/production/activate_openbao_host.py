"""Supervised Shamir activation; only encrypted initialization output is durable."""
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
from datetime import datetime, timezone

import bootstrap_intermediate_csr as custody
import import_intermediate_ca as ca_import
import verify_storage_guard as storage

STATE = custody.BASE / 'openbao-activation'
IMAGE = ('ghcr.io/hasanjodatshandi/hooshix/platform-openbao-private@sha256:'
         '02395cd0932aa478cff0da366233cd0f485a3f0693f68a6cf94f1848d12a0df9')
BOUND = 32768
PRESERVED = ('ssh.service', 'nginx.service', 'postfix.service', 'dovecot.service',
             'auditd.service', 'wg-quick@wg-hooshix.service', 'k3s.service')


def require(condition, code):
    custody.require(condition, code)


def public_keys(keys):
    require(isinstance(keys, list) and len(keys) == 3 and len(set(keys)) == 3,
            'THREE_DISTINCT_PUBLIC_RECIPIENTS_REQUIRED')
    for key in keys:
        require(isinstance(key, str) and 256 <= len(key) <= 8192, 'PUBLIC_RECIPIENT_REJECTED')
        content = base64.b64decode(key, validate=True)
        # RFC4880 public-key packet, never a secret-key packet.
        tag = content[0] & 63 if content[0] & 64 else (content[0] >> 2) & 15
        require(content[0] & 128 and tag == 6, 'PUBLIC_RECIPIENT_REJECTED')
    return hashlib.sha256(json.dumps(keys, separators=(',', ':')).encode()).hexdigest()


def ciphertext(value):
    require(isinstance(value, str) and 256 <= len(value) <= 8192, 'ENCRYPTED_OUTPUT_REQUIRED')
    content = base64.b64decode(value, validate=True)
    tag = content[0] & 63 if content[0] & 64 else (content[0] >> 2) & 15
    require(content[0] & 128 and tag == 1 and len(content) >= 192,
            'ENCRYPTED_OUTPUT_REQUIRED')
    return content


def encrypted_result(response):
    keys, token = response['keys_base64'], response['root_token']
    require(isinstance(keys, list) and len(keys) == 3 and len(set(keys)) == 3,
            'ENCRYPTED_SHARES_INVALID')
    for value in [*keys, token]:
        ciphertext(value)
    return {'keys_base64': keys, 'root_token': token}


def init_request(keys):
    public_keys(keys)
    return {'secret_shares': 3, 'secret_threshold': 2,
            'pgp_keys': keys, 'root_token_pgp_key': keys[0]}


def native(argv, *, data=None, expected=0, timeout=25, operation='NATIVE'):
    # Only code-owned operation identifiers and failure classes leave this edge.
    # Never echo argv, stdin, stdout, stderr or exception text (including init output).
    require(re.fullmatch(r'[A-Z0-9_]{1,55}', operation), 'OPERATION_LABEL_REJECTED')
    try:
        result = subprocess.run(argv, input=data, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, env=custody.ENV,
                                timeout=timeout, check=False)
    except subprocess.TimeoutExpired:
        raise custody.BootstrapFailed('ACTIVATION_' + operation + '_TIMEOUT') from None
    except OSError:
        raise custody.BootstrapFailed('ACTIVATION_' + operation + '_EXEC_FAILED') from None
    codes = expected if isinstance(expected, tuple) else (expected,)
    require(result.returncode in codes, 'ACTIVATION_' + operation + '_EXIT_FAILED')
    require(len(result.stdout) <= BOUND, 'ACTIVATION_' + operation + '_OUTPUT_BOUND')
    return result.stdout


def kube(*args, data=None, expected=0, operation='KUBECTL'):
    return native([custody.K3S, 'kubectl', '--request-timeout=15s', *args],
                  data=data, expected=expected, operation=operation)


def get(kind, name):
    operations = {'pod': 'GET_POD', 'statefulset': 'GET_STATEFULSET',
                  'pvc': 'GET_PVC', 'configmap': 'GET_CONFIGMAP'}
    require(kind in operations, 'GET_KIND_REJECTED')
    return json.loads(kube('-n', 'hooshix-secrets', 'get', kind, name, '-o', 'json',
                           operation=operations[kind]))


def bao(operation, path=None, body=None):
    # No secret in argv, environment, file or diagnostics. API audit is Metadata.
    args = ['-n', 'hooshix-secrets', 'exec', '-i', 'openbao-0', '-c', 'openbao', '--',
            '/usr/bin/bao', operation, '-format=json', '-address=https://127.0.0.1:8200',
            '-ca-cert=/openbao/tls/ca.crt']
    if path:
        require(operation == 'write' and path in ('sys/init', 'sys/unseal'), 'API_PATH_REJECTED')
        args += [path, '-']
    if operation == 'status':
        value = json.loads(kube(*args, expected=(0, 2), operation='BAO_STATUS'))
    else:
        value = json.loads(kube(*args, data=json.dumps(body).encode(),
                               operation='BAO_INIT' if path == 'sys/init' else 'BAO_UNSEAL'))['data']
    require(isinstance(value, dict), 'API_RESPONSE_REJECTED')
    return value


def service_identity():
    result = {}
    for unit in PRESERVED:
        label = unit.split('.')[0].replace('@', '_').replace('-', '_').upper()
        require(native(['/usr/bin/systemctl', 'is-active', unit],
                       operation='SERVICE_ACTIVE_' + label).strip() == b'active',
                'PRESERVED_SERVICE_UNAVAILABLE')
        result[unit] = native(['/usr/bin/systemctl', 'show', unit, '-p', 'MainPID', '--value'],
                              operation='SERVICE_PID_' + label).strip()
    return result


def storage_preflight():
    try:
        installed = storage.load_storage()
    except (OSError, ValueError):
        raise custody.BootstrapFailed('ACTIVATION_STORAGE_SOURCE_REVIEW_REQUIRED') from None
    try:
        installed['guard_check']()
        require(storage.show(installed, installed['GUARD_NAME']) == 'active', 'STORAGE_GUARD_REQUIRED')
    except installed['StorageFailed']:
        raise custody.BootstrapFailed('ACTIVATION_STORAGE_GUARD_FAILED') from None


def preflight():
    require(os.geteuid() == 0 and os.uname().nodename == 'mail.hooshix.com', 'EXACT_VPS_SUDO_REQUIRED')
    before = service_identity()
    audit = native(['/usr/sbin/auditctl', '-s'], operation='AUDIT_STATUS').decode()
    require(re.search(r'^enabled [12]$', audit, re.M) and re.search(r'^lost 0$', audit, re.M),
            'PROTECTED_AUDIT_UNHEALTHY')
    ca_import.audit_policy_preflight()
    storage_preflight()
    pod, workload, claim = get('pod', 'openbao-0'), get('statefulset', 'openbao'), get('pvc', 'data-openbao-0')
    require(pod['status']['phase'] == 'Running' and pod['spec']['serviceAccountName'] == 'openbao'
            and len(pod['spec']['containers']) == 1 and pod['spec']['containers'][0]['image'] == IMAGE
            and workload['spec']['replicas'] == 1
            and workload['spec']['template']['spec']['containers'][0]['image'] == IMAGE
            and any(r.get('controller') is True and r.get('uid') == workload['metadata']['uid']
                    for r in pod['metadata']['ownerReferences'])
            and claim['status']['phase'] == 'Bound'
            and claim['spec']['volumeName'] == 'hooshix-openbao-local-8gib', 'INSTALLED_OPENBAO_REQUIRED')
    require(pod['spec']['containers'][0].get('env') == [
        {'name': 'BAO_ADDR', 'value': 'https://127.0.0.1:8200'},
        {'name': 'BAO_CACERT', 'value': '/openbao/tls/ca.crt'},
        {'name': 'BAO_CLIENT_TIMEOUT', 'value': '3s'},
        {'name': 'BAO_MAX_RETRIES', 'value': '0'},
        {'name': 'HOME', 'value': '/tmp'}], 'NO_TOKEN_OR_RETRY_ENVIRONMENT_REQUIRED')
    config = json.loads(get('configmap', 'openbao-config')['data']['server.json'])
    require(config.get('audit') == [{'file': {'protected': {
        'description': 'Protected OpenBao audit; export is a separate commissioning gate',
        'options': {'file_path': '/openbao/data/audit.jsonl', 'mode': '0600', 'log_raw': 'false'}}}}],
        'NON_RAW_DECLARATIVE_AUDIT_REQUIRED')
    status = bao('status')
    require(status['version'] == '2.6.4' and status['type'] == 'shamir'
            and status['storage_type'] == 'raft', 'EXACT_RUNTIME_REQUIRED')
    return before, claim['metadata']['uid']


def diagnose():
    """Read-only preflight/status and journal metadata; no private file contents."""
    require(os.geteuid() == 0 and os.uname().nodename == 'mail.hooshix.com', 'EXACT_VPS_SUDO_REQUIRED')
    result = {'scope': 'read-only activation preflight, seal status and protected journal presence',
              'mutation': False, 'production_readiness': 'Not verified'}
    try:
        preflight()
        result['preflight'] = {'status': 'Passed'}
    except custody.BootstrapFailed as error:
        result['preflight'] = {'status': 'Failed', 'reason': str(error)}
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        result['preflight'] = {'status': 'Failed', 'reason': 'ACTIVATION_PREFLIGHT_REVIEW_REQUIRED'}
    # Status can still establish init/seal state if an earlier preflight step failed.
    try:
        status = bao('status')
        require(isinstance(status.get('initialized'), bool) and isinstance(status.get('sealed'), bool),
                'SEAL_STATUS_REJECTED')
        require(status.get('version') == '2.6.4' and status.get('storage_type') == 'raft'
                and status.get('type') == 'shamir'
                and all(type(status.get(key)) is int and 0 <= status[key] <= 3 for key in ('n', 't')),
                'SEAL_STATUS_REJECTED')
        result['openbao'] = {key: status[key] for key in
                             ('initialized', 'sealed', 'n', 't', 'version', 'storage_type', 'type')
                             if key in status}
    except custody.BootstrapFailed as error:
        result['openbao'] = {'status': 'Not verified', 'reason': str(error)}
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        result['openbao'] = {'status': 'Not verified', 'reason': 'SEAL_STATUS_REVIEW_REQUIRED'}
    result['journal'] = {}
    if STATE.exists() or STATE.is_symlink():
        for parent in (*reversed(STATE.parents), STATE):
            custody.protected(parent, directory=True)
        custody.protected(STATE, directory=True, exact_mode=0o700)
        for name in ('attempt.json', 'encrypted.json'):
            path = STATE / name
            present = path.exists() or path.is_symlink()
            if present:
                custody.protected(path, exact_mode=0o600)
            result['journal'][name] = {'present': present}
    else:
        result['journal'] = {name: {'present': False} for name in ('attempt.json', 'encrypted.json')}
    return result


def initialize(keys, claim_uid, revision, api=bao):
    digest = public_keys(keys)
    marker, saved = STATE / 'attempt.json', STATE / 'encrypted.json'
    identity = {'recipient_sha256': digest, 'pvc_uid': claim_uid, 'image': IMAGE}
    status = api('status')
    if saved.exists():
        for path in (marker, saved):
            custody.protected(path, exact_mode=0o600)
        attempt = json.loads(marker.read_bytes())
        require(all(attempt.get(k) == v for k, v in identity.items()), 'CUSTODY_IDENTITY_CONFLICT')
        require(status['initialized'] is True, 'INITIALIZED_STORAGE_CONFLICT')
        return encrypted_result(json.loads(saved.read_bytes()))
    require(not marker.exists() and not marker.is_symlink(), 'INCOMPLETE_INIT_REQUIRES_RECOVERY_REVIEW')
    require(status['initialized'] is False and status['sealed'] is True, 'ALREADY_INITIALIZED_NO_REINIT')
    # Durable intent before the ONE irreversible call. Unknown outcome is never retried.
    custody.create(marker, json.dumps(identity | {'source_revision': revision}).encode())
    result = encrypted_result(api('write', 'sys/init', init_request(keys)))
    custody.create(saved, json.dumps(result).encode())
    after = api('status')
    require(after['initialized'] is True and after['sealed'] is True and after['n'] == 3 and after['t'] == 2,
            'INIT_STATUS_CONFLICT_ENCRYPTED_CUSTODY_PRESERVED')
    return result


def unseal(keys, api=bao):
    require(isinstance(keys, list) and len(keys) == 2 and len(set(keys)) == 2
            and all(isinstance(k, str) and re.fullmatch(r'(?:[a-fA-F0-9]{66}|[A-Za-z0-9+/]{44})', k)
                    for k in keys), 'TWO_VALID_DISTINCT_SHARES_REQUIRED')
    before = api('status')
    require(before['initialized'] is True and before['n'] == 3 and before['t'] == 2,
            'INITIALIZED_SHAMIR_3_2_REQUIRED')
    if before['sealed']:
        # Reset only incomplete in-memory progress, not storage or key material.
        api('write', 'sys/unseal', {'reset': True})
        first = api('write', 'sys/unseal', {'key': keys[0]})
        require(first['sealed'] is True and first['progress'] == 1, 'ONE_SHARE_MUST_REMAIN_SEALED')
        api('write', 'sys/unseal', {'key': keys[1]})
    status = api('status')
    require(status['sealed'] is False and status['initialized'] is True
            and status['n'] == 3 and status['t'] == 2, 'UNSEAL_FAILED_CUSTODY_PRESERVED')
    return {'initialization': 'Passed', 'unseal': 'Passed', 'shamir': '3/2',
            'root_revocation': 'Not run', 'off_host_snapshot': 'Not verified',
            'production_readiness': 'Not verified'}


def main(revision):
    os.umask(0o077)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    signal.signal(signal.SIGALRM, custody.deadline_expired)
    signal.alarm(180)
    print('ACTIVATION_RPC_READY', flush=True)
    try:
        require(re.fullmatch(r'[a-f0-9]{40}', revision), 'REVIEWED_REVISION_REQUIRED')
        line = sys.stdin.buffer.readline(BOUND + 1)
        require(0 < len(line) <= BOUND and line.endswith(b'\n'), 'BOUNDED_REQUEST_REQUIRED')
        request = json.loads(line)
        require(isinstance(request, dict), 'REQUEST_REJECTED')
        action = request.get('action')
        fields = {'diagnose': {'action'}, 'initialize': {'action', 'recipients'},
                  'unseal': {'action', 'keys', 'encrypted_sha256'}}
        require(isinstance(action, str) and action in fields, 'ACTION_REJECTED')
        require(set(request) == fields[action], 'REQUEST_REJECTED')
        if action == 'diagnose':
            result = diagnose()
            result.update(schema_version=1, source_revision=revision,
                          observed_at=datetime.now(timezone.utc).isoformat())
            print(json.dumps(result), flush=True)
            return 0
        before, claim_uid = preflight()
        custody.directory(STATE)
        lock_path = STATE / 'activation.lock'
        with os.fdopen(os.open(lock_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600), 'r+b') as lock:
            custody.protected(lock_path, exact_mode=0o600)
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            native(['/usr/sbin/auditctl', '-m', 'HooshiX OpenBao activation ' + revision + ' phase=' + action],
                   operation='AUDIT_ACTIVATION_EVENT')
            if action == 'initialize':
                result = {'encrypted': initialize(request['recipients'], claim_uid, revision)}
            else:
                for name in ('attempt.json', 'encrypted.json'):
                    custody.protected(STATE / name, exact_mode=0o600)
                attempt = json.loads((STATE / 'attempt.json').read_bytes())
                require(attempt['pvc_uid'] == claim_uid and attempt['image'] == IMAGE
                        and hashlib.sha256((STATE / 'encrypted.json').read_bytes()).hexdigest()
                        == request['encrypted_sha256'], 'CUSTODY_DOWNLOAD_PROOF_REQUIRED')
                result = unseal(request['keys'])
            storage_preflight()
            require(before == service_identity(), 'PRESERVED_SERVICE_STATE_CHANGED')
        result.update(schema_version=1, source_revision=revision,
                      observed_at=datetime.now(timezone.utc).isoformat())
        print(json.dumps(result), flush=True)
        return 0
    except custody.BootstrapFailed as error:
        print(json.dumps({'reason': str(error), 'state_preserved': True}), flush=True)
        return 1
    except (OSError, ValueError, KeyError, TypeError, EOFError, subprocess.SubprocessError):
        print(json.dumps({'reason': 'ACTIVATION_FAILED_STATE_PRESERVED', 'state_preserved': True}), flush=True)
        return 1
    finally:
        signal.alarm(0)
