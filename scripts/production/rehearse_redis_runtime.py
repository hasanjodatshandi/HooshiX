"""Exact published Redis in the owned Calico/Ambient/admission fixture; synthetic data only."""
import base64
import copy
import hashlib
import json
import re
import traceback
from unittest.mock import patch

import install_redis_runtime as installer
import render_redis_runtime as renderer
from rehearse_cert_manager import network_negative
from rehearse_platform_admission import admission_result, apply, policy_ready
from rehearse_redis_secrets import preserve_fixture
from rehearse_redis_tls import synthetic_memory
from upgrade_mesh_security import replace_policy


def rehearse(k, wait, run, ca, node, platform):
    if not re.fullmatch('hooshix-bao-ci-[a-f0-9]{12}-control-plane', node):
        raise ValueError('OWNED_REDIS_FIXTURE_NODE_REQUIRED')
    def kube(*args, body=None, **options):
        try:
            return k(*args, data=json.dumps(body).encode() if body is not None else None, **options)
        except Exception as error:
            # Function/line only: never argv, API/Secret bodies, stderr or exception text.
            frames = [{'function': f.name, 'line': f.lineno} for f in traceback.extract_tb(error.__traceback__)
                      if f.filename.endswith(('/install_redis_runtime.py', '/rehearse_redis_runtime.py'))]
            print('REDIS_RUNTIME_FIXED_FAILURE=' + json.dumps({'type': type(error).__name__, 'frames': frames}), flush=True)
            raise
    previous = json.loads(k('get', 'validatingpolicy', 'hooshix-platform-data-closed', '-o', 'json'))
    previous = {key: previous[key] for key in ('apiVersion', 'kind', 'metadata', 'spec')}
    # No production path is formatted/replaced. This path exists only inside the fresh owned node.
    run(['docker', 'exec', node, '/bin/sh', '-ec',
        'test ! -e /var/lib/hooshixstorage/redis/data && mkdir -p /var/lib/hooshixstorage/redis/data && '
        'chown 10001:10001 /var/lib/hooshixstorage/redis/data && chmod 0700 /var/lib/hooshixstorage/redis/data'])
    with patch.object(installer.volume, 'NODE', node), synthetic_memory(ca):
        for obj in installer.volume.candidate(): apply(k, obj)
        plan = renderer.render(previous)
        plan['public_ca_sha256'] = hashlib.sha256(ca.encode()).hexdigest()
        options = {'kube': kube, 'guard': lambda **_: {}, 'preserve': lambda call: preserve_fixture(call, node)}
        print('REDIS_RUNTIME_NATIVE_STEP=closed-start', flush=True)
        result = installer.execute(plan, **options)
        again = installer.execute(plan, **options)
        if any(result[key] != again[key] for key in ('statefulset_uid', 'pvc_uid', 'volume_uid')):
            raise ValueError('REDIS_RUNTIME_REENTRY_REPLACED_IDENTITY')
        result['idempotent_reconciliation'] = 'Passed'
        actual = json.loads(k('-n', renderer.NS, 'get', 'pod', renderer.NAME + '-0', '-o', 'json'))
        pod = {key: actual[key] for key in ('apiVersion', 'kind', 'metadata', 'spec')}
        pod['metadata'] = {key: actual['metadata'][key] for key in ('name', 'namespace', 'labels', 'ownerReferences')}
        print('REDIS_RUNTIME_POD_ADMISSION=positive', flush=True)
        admission_result(k, pod, wait, public_schema=False)
        for mode in ('account', 'image', 'privilege', 'ambient', 'host-path', 'command', 'probe', 'token', 'extra-container', 'env'):
            bad = copy.deepcopy(pod)
            c = bad['spec']['containers'][0]
            if mode == 'account': bad['spec']['serviceAccountName'] = 'default'
            if mode == 'image': c['image'] = c['image'].split('@')[0] + ':latest'
            if mode == 'privilege': c['securityContext']['privileged'] = True
            if mode == 'ambient': bad['metadata']['labels']['istio.io/dataplane-mode'] = 'none'
            if mode == 'host-path': bad['spec']['volumes'][0] = {'name': 'data', 'hostPath': {'path': '/etc'}}
            if mode == 'command': c['command'] = ['/bin/sh']
            if mode == 'probe': c['readinessProbe']['exec']['command'] = ['/bin/sh', '-c', 'cat /run/secrets/security-redis/tls.key']
            if mode == 'token': bad['spec']['automountServiceAccountToken'] = True
            if mode == 'extra-container':
                extra = copy.deepcopy(c)
                extra['name'] = 'redis-extra'
                bad['spec']['containers'].append(extra)
            if mode == 'env': c['env'] = [{'name': 'REDISCLI_AUTH', 'value': 'not-secret'}]
            print('REDIS_RUNTIME_POD_ADMISSION_NEGATIVE=' + mode, flush=True)
            admission_result(k, bad, wait, expected=1, public_schema=False)
        result['pod_contract_positive_negative'] = 'Passed'
        for original in plan['source_policies']:
            wrong = copy.deepcopy(original)
            if original['metadata']['name'].endswith('-signature'):
                wrong['spec']['attestors'][0]['cosign']['keyless']['identities'][0]['subject'] = 'https://wrong.invalid'
            elif original['metadata']['name'].endswith('-sbom'):
                wrong['spec']['attestations'][0]['intoto']['type'] = 'https://missing.invalid/sbom'
            else:
                revision = renderer.pin()['repository_revision']
                wrong['spec']['validations'][-1]['expression'] = wrong['spec']['validations'][-1]['expression'].replace(revision, '0' * 40)
            if wrong == original: raise ValueError('REDIS_SOURCE_NEGATIVE_REQUIRED')
            try:
                replace_policy(wrong, get=lambda kind, name: installer.get(kube, kind, name), kube=kube, previous=original)
                admission_result(k, pod, wait, expected=1, message=original['metadata']['name'].encode(), public_schema=False)
            finally:
                replace_policy(original, get=lambda kind, name: installer.get(kube, kind, name), kube=kube, previous=wrong)
                policy_ready(k, original)
            admission_result(k, pod, wait, public_schema=False)
        result['source_identity_positive_negative'] = 'Passed'
        credentials = {name: base64.b64decode(json.loads(k('-n', renderer.NS, 'get', 'secret',
            'security-redis-' + name, '-o', 'json'))['data']['password']).decode()
            for name in ('identity', 'authorization', 'webbff', 'health', 'monitor')}
        def command(user, *args, tls=True):
            command = ['redis-cli', '-e', '--raw', '-t', '2', '-h', '127.0.0.1', '--user', user]
            if tls: command += ['--tls', '--cacert', '/run/secrets/security-redis/ca.crt', '--sni', 'security-redis.platform-data.svc']
            # Synthetic credential only; classify exact responses in memory, including expected denials.
            script = 'IFS= read -r REDISCLI_AUTH; export REDISCLI_AUTH; "$@" 2>&1 || true'
            return k('-n', renderer.NS, 'exec', '-i', renderer.NAME + '-0', '-c', 'redis', '--',
                '/bin/sh', '-ec', script, 'redis-probe', *command, *args, data=(credentials[user] + '\n').encode()).strip()
        if command('health', 'PING') != b'PONG' or command('webbff', 'SET', 'web-bff:commissioning:canary', 'synthetic') != b'OK':
            raise ValueError('REDIS_NATIVE_TLS_ACL_FAILED')
        for user in ('authorization', 'identity', 'health', 'monitor'):
            if b'NOPERM' not in command(user, 'GET', 'web-bff:commissioning:canary'):
                raise ValueError('REDIS_CROSS_OWNER_READ_ACCEPTED')
        if b'NOPERM' not in command('webbff', 'CONFIG', 'GET', '*') or command('health', 'PING', tls=False) == b'PONG':
            raise ValueError('REDIS_ADMIN_OR_PLAINTEXT_ACCEPTED')
        result['tls_acl_plaintext_denials'] = 'Passed'
        first = actual['status']['containerStatuses'][0]['restartCount']
        k('-n', renderer.NS, 'exec', renderer.NAME + '-0', '-c', 'redis', '--', '/bin/sh', '-c', 'kill -TERM 1')
        def restarted():
            value = json.loads(k('-n', renderer.NS, 'get', 'pod', renderer.NAME + '-0', '-o', 'json'))
            status = value.get('status', {}).get('containerStatuses', [])
            return status and status[0]['restartCount'] > first and status[0]['ready']
        wait(restarted, seconds=90)
        if command('webbff', 'GET', 'web-bff:commissioning:canary') != b'synthetic':
            raise ValueError('REDIS_AOF_RESTART_LOST_SYNTHETIC_STATE')
        if command('webbff', 'DEL', 'web-bff:commissioning:canary') != b'1':
            raise ValueError('REDIS_SYNTHETIC_CANARY_CLEANUP_FAILED')
        result['aof_process_restart'] = 'Passed'
        info = command('monitor', 'INFO', 'persistence')
        if b'aof_enabled:1' not in info or b'aof_last_write_status:ok' not in info:
            raise ValueError('REDIS_AOF_HEALTH_REQUIRED')
        logs = k('-n', renderer.NS, 'logs', renderer.NAME + '-0', '-c', 'redis', '--tail=-1')
        markers = [*credentials.values(), *[hashlib.sha256(v.encode()).hexdigest() for v in credentials.values()]]
        tls = json.loads(k('-n', renderer.NS, 'get', 'secret', 'security-redis-tls', '-o', 'json'))
        key = base64.b64decode(tls['data']['tls.key']).decode()
        markers += [key, *key.splitlines()[1:-1]]
        if any(v.encode() in logs for v in markers): raise ValueError('REDIS_RUNTIME_LOG_PRIVACY_FAILED')
        result['container_log_privacy'] = 'Passed'
        network_negative(k, wait, platform, selector='app.kubernetes.io/name=security-redis', port=6379, namespace=renderer.NS)
        result['application_network_denied'] = 'Passed'
        # The earlier OpenBao restart verifier must not mistake the deliberate PG/Redis closed boundary for drift.
        result['guard'] = 'Not run'
        result['host_storage_guard'] = 'Not run; owned disposable path; independent host guard evidence required'
        return result
