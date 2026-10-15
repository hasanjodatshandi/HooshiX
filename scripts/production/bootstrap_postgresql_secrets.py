"""Owner-supervised PostgreSQL secret delivery on the existing store; no init or cloud calls."""
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
import zlib
from datetime import datetime, timezone

import activate_openbao_host as host
import bootstrap_openbao_auth as established
import install_barman_control_plane as platform
import install_postgresql_secrets as installer
import openbao_scoped_auth as auth
import postgresql_secret_delivery as contract


def decode_plan(encoded, digest):
    host.require(isinstance(encoded, str) and len(encoded) <= 24000
        and isinstance(digest, str) and re.fullmatch(r'[a-f0-9]{64}', digest), 'PG_SECRET_PLAN_BOUND')
    stream = zlib.decompressobj()
    try:
        content = stream.decompress(base64.b64decode(encoded, validate=True), 512 * 1024 + 1)
    except (ValueError, zlib.error):
        raise host.custody.BootstrapFailed('PG_SECRET_PLAN_INTEGRITY_FAILED') from None
    host.require(len(content) <= 512 * 1024 and stream.eof and not stream.unused_data
        and not stream.unconsumed_tail and hashlib.sha256(content).hexdigest() == digest,
        'PG_SECRET_PLAN_INTEGRITY_FAILED')
    return json.loads(content)


def kube(*args, body=None, expected=0, required_error=None, timeout=30, output_bound=None):
    # The existing activation transport bounds and suppresses secret output/errors.
    host.require(output_bound is None or output_bound <= host.BOUND, 'PG_SECRET_RESPONSE_BOUND')
    verb = next((a for a in args if a in ('get', 'create', 'patch', 'wait', 'rollout', 'auth')), 'other')
    result = host.native(['/usr/local/bin/k3s', 'kubectl', '--request-timeout=180s', *args],
        data=json.dumps(body).encode() if body is not None else None,
        expected=expected, timeout=timeout, operation='PG_SECRET_KUBE_' + verb.upper())
    # Native errors stay private. Required-denial probes are independently classified in staging.
    return result


def token_request(namespace, account, audiences):
    host.require((namespace == contract.NAMESPACE and account in {e['account'] for e in contract.ENTRIES.values()}
                  or (namespace, account) == ('default', 'default'))
        and isinstance(audiences, list) and len(audiences) <= 5
        and all(isinstance(a, str) and re.fullmatch(r'[a-zA-Z0-9:/_.-]{1,256}', a) for a in audiences),
        'PG_SECRET_TOKENREQUEST_BOUNDARY')
    return kube('-n', namespace, 'create', 'token', account, '--duration=10m',
                *['--audience=' + a for a in audiences]).decode().strip()


def commission(request, revision):
    host.require(set(request) == {'root_token', 'recipients', 'plan', 'plan_sha256'}, 'PG_SECRET_REQUEST_REJECTED')
    plan = decode_plan(request['plan'], request['plan_sha256'])
    host.require(plan['staging']['source_revision'] == revision and plan['staging']['result'] == 'Passed',
        'PG_SECRET_REVIEWED_STAGING_REQUIRED')
    digest = host.public_keys(request['recipients'])
    before, claim_uid = host.preflight()
    retained = platform.protected(kube)
    guard = host.native(['/usr/bin/systemctl', 'is-active', 'hooshix-postgresql-storage-guard.service'],
                        operation='PG_SECRET_PRESERVE_GUARD')
    host.require(guard.strip() == b'active', 'PG_SECRET_EXISTING_GUARD_REQUIRED')
    with os.fdopen(os.open(host.STATE / 'activation.lock', os.O_RDWR | os.O_NOFOLLOW), 'r+b') as lock:
        host.custody.protected(host.STATE, directory=True, exact_mode=0o700)
        host.custody.protected(host.STATE / 'activation.lock', exact_mode=0o600)
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        host.custody.protected(host.STATE / 'attempt.json', exact_mode=0o600)
        intent = json.loads((host.STATE / 'attempt.json').read_bytes())
        host.require(intent['recipient_sha256'] == digest and intent['pvc_uid'] == claim_uid
            and intent['image'] == host.IMAGE, 'PG_SECRET_CUSTODY_STORE_CONFLICT')
        status = host.bao('status')
        host.require(status['initialized'] is True and status['sealed'] is False, 'PG_SECRET_EXISTING_UNSEALED_STORE_REQUIRED')
        certificate = base64.b64decode(host.kube('-n', 'hooshix-secrets', 'get', 'secret',
            'openbao-server-tls', '-o', 'jsonpath={.data.ca\\.crt}', operation='GET_PUBLIC_CA'), validate=True)
        host.require(hashlib.sha256(certificate).hexdigest() == host.custody.ROOT_SHA256,
                     'PG_SECRET_EXISTING_ROOT_REQUIRED')
        audience = auth.jwt_audiences(established.token_request(auth.NAMESPACE, 'eso-' + auth.SERVICES[0], []))
        with host.transport.forward(certificate.decode('ascii'), as_client=True) as client:
            api = established.API(client)
            host.require(api.call('auth/token/lookup-self', token=request['root_token'])['data']['policies'] == ['root'],
                         'PG_SECRET_SUPERVISED_ROOT_REQUIRED')
            host.native(['/usr/sbin/auditctl', '-m', 'HooshiX PostgreSQL secret delivery ' + revision],
                        operation='PG_SECRET_OS_AUDIT')
            result = installer.execute(plan, kube=kube, api=api, root=request['root_token'],
                ca=certificate.decode('ascii'), audiences=audience, token_request=token_request)
        host.storage_preflight()
        host.require(before == host.service_identity() and retained == platform.protected(kube)
            and host.get('pvc', 'data-openbao-0')['metadata']['uid'] == claim_uid,
            'PG_SECRET_PRESERVED_TARGET_CHANGED')
    result.update(source_revision=revision, recipient_sha256=digest, pvc_uid=claim_uid,
        plan_sha256=request['plan_sha256'], root_mesh_openbao_application_eso_cnpg_barman_storage='Preserved',
        observed_at=datetime.now(timezone.utc).isoformat())
    return result


def main(revision):
    os.umask(0o077)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    signal.signal(signal.SIGALRM, host.custody.deadline_expired)
    signal.alarm(600)
    try:
        host.require(re.fullmatch(r'[a-f0-9]{40}', revision), 'PG_SECRET_REVISION_REJECTED')
        print('PG_SECRET_RPC_READY', flush=True)
        content = sys.stdin.buffer.readline(host.BOUND + 1)
        host.require(0 < len(content) <= host.BOUND and content.endswith(b'\n'), 'PG_SECRET_REQUEST_BOUND')
        print(json.dumps(commission(json.loads(content), revision)), flush=True)
        return 0
    except host.custody.BootstrapFailed as error:
        print(json.dumps({'reason': str(error), 'state_preserved': True}), flush=True)
        return 1
    except ValueError as error:
        code = str(error) if re.fullmatch(r'PG_SECRET_[A-Z0-9_]{1,80}', str(error)) else 'PG_SECRET_BOOTSTRAP_FAILED_STATE_PRESERVED'
        print(json.dumps({'reason': code, 'state_preserved': True}), flush=True)
        return 1
    except (OSError, KeyError, TypeError, subprocess.SubprocessError):
        print(json.dumps({'reason': 'PG_SECRET_BOOTSTRAP_FAILED_STATE_PRESERVED', 'state_preserved': True}), flush=True)
        return 1
    finally:
        signal.alarm(0)
