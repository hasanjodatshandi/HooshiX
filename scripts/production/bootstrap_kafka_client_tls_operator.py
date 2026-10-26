"""Local hidden owner authentication; five fixed application credentials, never node replacement."""
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
from build_kafka_client_tls_bundle import SOURCES

host = operator.host


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bundle', type=Path, required=True)
    p.add_argument('--custody', type=Path)
    p.add_argument('--validate-only', action='store_true')
    p.add_argument('--rescue-and-second-session-ready', action='store_true')
    a = p.parse_args()
    os.umask(0o077)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    warnings.simplefilter('error', getpass.GetPassWarning)
    try:
        host.require(operator.command(['/usr/bin/git', '-C', str(operator.ROOT), 'status', '--porcelain']) == b'',
                     'KAFKA_CLIENT_CLEAN_REVIEWED_CHECKOUT_REQUIRED')
        revision = operator.command(['/usr/bin/git', '-C', str(operator.ROOT), 'rev-parse', 'HEAD']).decode().strip()
        operator.command(['/usr/bin/git', '-C', str(operator.ROOT), 'merge-base', '--is-ancestor', revision, 'origin/main'])
        content = pg.validate_bundle(a.bundle, revision, sources=SOURCES)
        plan = json.loads(content)
        host.require(plan.get('kafka_client_tls') is True
            and plan.get('public_access') == plan.get('application_ingress') == 'CLOSED',
            'KAFKA_CLIENT_CLOSED_PLAN_REQUIRED')
        if a.validate_only:
            print('KAFKA_CLIENT_TLS_BUNDLE_VALIDATION=Passed; no VPS or custody access')
            return 0
        host.require(a.rescue_and_second_session_ready and a.custody is not None
            and os.getuid() == 1000 and operator.sys_tty() and 'microsoft' in os.uname().release.lower(),
            'KAFKA_CLIENT_INTERACTIVE_OWNER_AND_RESCUE_REQUIRED')
        host.require(a.custody.parent == operator.BASE and re.fullmatch(r'[a-f0-9]{32}', a.custody.name),
                     'KAFKA_CLIENT_ORIGINAL_CUSTODY_REQUIRED')
        operator.custody_directory(a.custody)
        host.require(input('Working rescue VNC and second private SSH: type READY: ') == 'READY',
                     'KAFKA_CLIENT_RESCUE_REQUIRED')
        password = getpass.getpass('EXISTING OpenBao custody passphrase (not Root/CA/sudo): ')
        host.require(20 <= len(password) <= 128 and not any(c in password for c in '\r\n\x00'),
                     'KAFKA_CLIENT_CUSTODY_PASSPHRASE_REJECTED')
        recipients = json.loads(operator.read_private(a.custody / 'recipients.json'))
        digest = host.public_keys(recipients)
        encrypted = host.encrypted_result(json.loads(operator.read_private(a.custody / 'encrypted.json')))
        root = operator.decrypt(a.custody, 1, password, encrypted['root_token'])
        del password
        password = getpass.getpass('EXISTING intermediate-key passphrase (not Root/custody/sudo): ')
        host.require(20 <= len(password) <= 128 and not any(ord(c) < 32 or ord(c) == 127 for c in password),
                     'KAFKA_CLIENT_ISSUER_PASSPHRASE_REJECTED')
        remote, sources = operator.stage_sources(SOURCES)
        result = operator.rpc(remote, revision, sources, operator.sudo_password(), {'root_token': root,
            'recipients': recipients, 'plan': base64.b64encode(zlib.compress(content)).decode(),
            'plan_sha256': hashlib.sha256(content).hexdigest(), 'intermediate_passphrase': password},
            supervisor='bootstrap_kafka_client_tls')
        del root, password
        host.require(result.get('kafka_client_tls_delivery') == result.get('client_only_identity') == 'Passed'
            and result.get('scoped_authentication') == 'Passed' and result.get('credential_count') == 5
            and result.get('recipient_sha256') == digest and result.get('existing_state') == 'Preserved'
            and result.get('public_access') == result.get('application_ingress') == 'CLOSED'
            and result.get('topics_acl') == 'Not run', 'KAFKA_CLIENT_TARGET_RECEIPT_REJECTED')
        directory = operator.BASE.parent / 'hooshix-kafka-client-tls'
        directory.mkdir(mode=0o700, exist_ok=True)
        operator.custody_directory(directory)
        path = directory / ('delivery-' + uuid.uuid4().hex + '.json')
        operator.create(path, json.dumps(result).encode())
        print('KAFKA_CLIENT_TLS_DELIVERY=Passed; five isolated application identities; broker/topics/access unchanged CLOSED')
        print('PUBLIC_RECEIPT=' + str(path))
        return 0
    except host.custody.BootstrapFailed as error:
        print('KAFKA_CLIENT_TLS_DELIVERY=Failed; reason=' + str(error) + '; state preserved; no automatic retry')
        return 1
    except (OSError, ValueError, KeyError, TypeError, EOFError, subprocess.SubprocessError, getpass.GetPassWarning):
        print('KAFKA_CLIENT_TLS_DELIVERY=Failed; reason=LOCAL_OPERATION_FAILED_STATE_PRESERVED')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
