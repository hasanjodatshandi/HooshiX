"""Owner-local existing custody use; passwords never enter argv, files, logs or Git."""
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

import activate_openbao_host as host
import activate_openbao_operator as operator
from build_postgresql_secrets_bundle import SOURCES


def validate_bundle(directory, revision):
    host.require(not directory.is_symlink() and directory.is_dir(), 'PG_SECRET_PUBLIC_BUNDLE_REQUIRED')
    path = directory / 'bundle.json'
    host.require(not path.is_symlink() and path.stat().st_size <= host.BOUND, 'PG_SECRET_BUNDLE_MANIFEST_BOUND')
    manifest = json.loads(path.read_bytes())
    host.require(manifest.get('schema_version') == 1 and manifest.get('source_revision') == revision
        and set(manifest['files']) == {'plan.json', *SOURCES}, 'PG_SECRET_EXACT_BUNDLE_REQUIRED')
    for name, digest in manifest['files'].items():
        path = directory / name
        host.require(not path.is_symlink() and path.is_file() and path.stat().st_size <= 512 * 1024
            and hashlib.sha256(path.read_bytes()).hexdigest() == digest, 'PG_SECRET_BUNDLE_HASH_MISMATCH')
        if name != 'plan.json':
            host.require(hashlib.sha256((operator.ROOT / 'scripts/production' / name).read_bytes()).hexdigest() == digest,
                         'PG_SECRET_REVIEWED_SOURCE_REQUIRED')
    content = (directory / 'plan.json').read_bytes()
    plan = json.loads(content)
    host.require(plan.get('schema_version') == 1 and plan.get('database') == 'Not run'
        and plan['staging']['source_revision'] == revision and plan['staging']['result'] == 'Passed',
        'PG_SECRET_STAGING_BINDING_REQUIRED')
    return content


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
                     'PG_SECRET_CLEAN_REVIEWED_CHECKOUT_REQUIRED')
        revision = operator.command(['/usr/bin/git', '-C', str(operator.ROOT), 'rev-parse', 'HEAD']).decode().strip()
        operator.command(['/usr/bin/git', '-C', str(operator.ROOT), 'merge-base', '--is-ancestor', revision, 'origin/main'])
        content = validate_bundle(a.bundle, revision)
        if a.validate_only:
            print('PG_SECRETS_BUNDLE_VALIDATION=Passed; no VPS or custody access')
            return 0
        host.require(a.rescue_and_second_session_ready and a.custody is not None
            and os.getuid() == 1000 and operator.sys_tty() and 'microsoft' in os.uname().release.lower(),
            'PG_SECRET_INTERACTIVE_OWNER_AND_RESCUE_REQUIRED')
        host.require(a.custody.parent == operator.BASE and re.fullmatch(r'[a-f0-9]{32}', a.custody.name),
                     'PG_SECRET_ORIGINAL_CUSTODY_REQUIRED')
        operator.custody_directory(a.custody)
        host.require(input('Working rescue VNC and second private SSH: type READY: ') == 'READY',
                     'PG_SECRET_RESCUE_REQUIRED')
        password = getpass.getpass('EXISTING OpenBao custody passphrase (not Root/CA/sudo): ')
        host.require(20 <= len(password) <= 128 and not any(c in password for c in '\r\n\x00'),
                     'PG_SECRET_CUSTODY_PASSPHRASE_REJECTED')
        recipients = json.loads(operator.read_private(a.custody / 'recipients.json'))
        digest = host.public_keys(recipients)
        encrypted = host.encrypted_result(json.loads(operator.read_private(a.custody / 'encrypted.json')))
        root = operator.decrypt(a.custody, 1, password, encrypted['root_token'])
        del password
        remote, sources = operator.stage_sources(SOURCES)
        result = operator.rpc(remote, revision, sources, operator.sudo_password(), {'root_token': root,
            'recipients': recipients, 'plan': base64.b64encode(zlib.compress(content)).decode(),
            'plan_sha256': hashlib.sha256(content).hexdigest()}, supervisor='bootstrap_postgresql_secrets')
        del root
        host.require(result.get('delivery') == 'Passed' and result.get('credential_count') == 10
            and result.get('recipient_sha256') == digest and result.get('database') == 'Not run',
            'PG_SECRET_TARGET_RECEIPT_REJECTED')
        directory = operator.BASE.parent / 'hooshix-postgresql-secrets'
        directory.mkdir(mode=0o700, exist_ok=True)
        operator.custody_directory(directory)
        path = directory / ('delivery-' + uuid.uuid4().hex + '.json')
        operator.create(path, json.dumps(result).encode())
        print('POSTGRESQL_SECRET_DELIVERY=Passed; ten distinct basic-auth Secrets; database/provider Not run; root preserved')
        print('PUBLIC_RECEIPT=' + str(path))
        return 0
    except host.custody.BootstrapFailed as error:
        print('POSTGRESQL_SECRET_DELIVERY=Failed; reason=' + str(error) + '; state preserved; no automatic retry')
        return 1
    except (OSError, ValueError, KeyError, TypeError, EOFError, subprocess.SubprocessError, getpass.GetPassWarning):
        print('POSTGRESQL_SECRET_DELIVERY=Failed; reason=PG_SECRET_LOCAL_FAILED_STATE_PRESERVED')
        return 1


if __name__ == '__main__': raise SystemExit(main())
