"""Owner-local custody authentication for Redis delivery; no secret argv/files/output."""
import argparse
import base64
import getpass
import hashlib
import json
import os
import re
import resource
import subprocess
import uuid
import warnings
import zlib
from pathlib import Path

import activate_openbao_operator as operator
import bootstrap_postgresql_secrets_operator as pg
from build_redis_secrets_bundle import SOURCES

host = operator.host


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bundle', type=Path, required=True)
    p.add_argument('--custody', type=Path)
    p.add_argument('--validate-only', action='store_true')
    p.add_argument('--tls', action='store_true')
    p.add_argument('--rescue-and-second-session-ready', action='store_true')
    a = p.parse_args()
    label = 'REDIS_TLS_DELIVERY' if a.tls else 'REDIS_SECRET_DELIVERY'
    os.umask(0o077)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    warnings.simplefilter('error', getpass.GetPassWarning)
    try:
        host.require(operator.command(['/usr/bin/git', '-C', str(operator.ROOT), 'status', '--porcelain']) == b'',
                     'REDIS_SECRET_CLEAN_REVIEWED_CHECKOUT_REQUIRED')
        revision = operator.command(['/usr/bin/git', '-C', str(operator.ROOT), 'rev-parse', 'HEAD']).decode().strip()
        operator.command(['/usr/bin/git', '-C', str(operator.ROOT), 'merge-base', '--is-ancestor', revision, 'origin/main'])
        content = pg.validate_bundle(a.bundle, revision, sources=SOURCES)
        plan = json.loads(content)
        host.require(plan.get('redis') == 'Not run' and plan.get('public_access') == 'CLOSED',
                     'REDIS_SECRET_BOUNDED_PLAN_REQUIRED')
        host.require(plan.get('redis_tls_delivery', False) is a.tls, 'REDIS_SECRET_OPERATION_PLAN_CONFLICT')
        if a.validate_only:
            print('REDIS_SECRETS_BUNDLE_VALIDATION=Passed; no VPS or custody access')
            return 0
        host.require(a.rescue_and_second_session_ready and a.custody is not None
            and os.getuid() == 1000 and operator.sys_tty() and 'microsoft' in os.uname().release.lower(),
            'REDIS_SECRET_INTERACTIVE_OWNER_AND_RESCUE_REQUIRED')
        host.require(a.custody.parent == operator.BASE and re.fullmatch(r'[a-f0-9]{32}', a.custody.name),
                     'REDIS_SECRET_ORIGINAL_CUSTODY_REQUIRED')
        operator.custody_directory(a.custody)
        host.require(input('Working rescue VNC and second private SSH: type READY: ') == 'READY',
                     'REDIS_SECRET_RESCUE_REQUIRED')
        password = getpass.getpass('EXISTING OpenBao custody passphrase (not Root/CA/sudo): ')
        host.require(20 <= len(password) <= 128 and not any(c in password for c in '\r\n\x00'),
                     'REDIS_SECRET_CUSTODY_PASSPHRASE_REJECTED')
        recipients = json.loads(operator.read_private(a.custody / 'recipients.json'))
        digest = host.public_keys(recipients)
        encrypted = host.encrypted_result(json.loads(operator.read_private(a.custody / 'encrypted.json')))
        root = operator.decrypt(a.custody, 1, password, encrypted['root_token'])
        del password
        extra = {}
        if a.tls:
            password = getpass.getpass('EXISTING intermediate-key passphrase (not Root/custody/sudo): ')
            host.require(20 <= len(password) <= 128 and not any(ord(c) < 32 or ord(c) == 127 for c in password),
                         'REDIS_TLS_ISSUER_PASSPHRASE_REJECTED')
            extra['intermediate_passphrase'] = password
            del password
        remote, sources = operator.stage_sources(SOURCES)
        result = operator.rpc(remote, revision, sources, operator.sudo_password(), {'root_token': root,
            'recipients': recipients, 'plan': base64.b64encode(zlib.compress(content)).decode(),
            'plan_sha256': hashlib.sha256(content).hexdigest(), **extra},
            supervisor='bootstrap_redis_tls' if a.tls else 'bootstrap_redis_secrets')
        del root, extra
        delivered = (result.get('tls_delivery') == result.get('server_chain_key_hostname') == 'Passed') if a.tls else (
            result.get('delivery') == result.get('acl_binding') == 'Passed' and result.get('credential_count') == 5)
        host.require(delivered and result.get('recipient_sha256') == digest
            and result.get('redis') == 'Not run' and result.get('public_access') == 'CLOSED',
            'REDIS_SECRET_TARGET_RECEIPT_REJECTED')
        directory = operator.BASE.parent / 'hooshix-redis-secrets'
        directory.mkdir(mode=0o700, exist_ok=True)
        operator.custody_directory(directory)
        path = directory / (('tls-' if a.tls else 'delivery-') + uuid.uuid4().hex + '.json')
        operator.create(path, json.dumps(result).encode())
        print('REDIS_TLS_DELIVERY=Passed; existing issuer; OpenBao/ESO delivery; Redis Not run; root preserved' if a.tls else
              'REDIS_SECRET_DELIVERY=Passed; five distinct credentials and bound ACL; Redis/TLS Not run; root preserved')
        print('PUBLIC_RECEIPT=' + str(path))
        return 0
    except host.custody.BootstrapFailed as error:
        print(label + '=Failed; reason=' + str(error) + '; state preserved; no automatic retry')
        return 1
    except (OSError, ValueError, KeyError, TypeError, EOFError, subprocess.SubprocessError, getpass.GetPassWarning):
        print(label + '=Failed; reason=REDIS_SECRET_LOCAL_FAILED_STATE_PRESERVED')
        return 1


if __name__ == '__main__': raise SystemExit(main())
