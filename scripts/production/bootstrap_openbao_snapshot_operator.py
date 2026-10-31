"""Configure the existing OpenBao store for hourly encrypted off-host snapshots."""
from __future__ import annotations

import getpass
import json
import os
import re
import resource
import subprocess
import sys
import uuid
import warnings
from pathlib import Path

import activate_openbao_host as host
import activate_openbao_operator as operator

SOURCES = (*operator.SOURCES, 'openbao_scoped_auth.py', 'bootstrap_openbao_auth.py',
           'bootstrap_openbao_snapshot.py')
BASE = Path('/home/coder/.local/share/hooshix-openbao-snapshot')


def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--custody', type=Path, required=True)
    parser.add_argument('--rescue-and-second-session-ready', action='store_true', required=True)
    args = parser.parse_args()
    os.umask(0o077)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    warnings.simplefilter('error', getpass.GetPassWarning)
    try:
        host.require(os.getuid() == 1000 and operator.sys_tty()
                     and 'microsoft' in os.uname().release.lower(), 'SNAPSHOT_OWNER_WSL_TTY_REQUIRED')
        host.require(operator.command(['/usr/bin/git', '-C', str(operator.ROOT), 'status', '--porcelain']) == b'',
                     'SNAPSHOT_CLEAN_CHECKOUT_REQUIRED')
        revision = operator.command(['/usr/bin/git', '-C', str(operator.ROOT), 'rev-parse', 'HEAD']).decode().strip()
        operator.command(['/usr/bin/git', '-C', str(operator.ROOT), 'merge-base', '--is-ancestor', revision, 'origin/main'])
        host.require(args.custody.parent == operator.BASE
                     and re.fullmatch(r'[a-f0-9]{32}', args.custody.name), 'SNAPSHOT_CUSTODY_PATH_REJECTED')
        operator.custody_directory(args.custody)
        host.require(input('Working rescue VNC and second private SSH session: type READY: ') == 'READY',
                     'SNAPSHOT_RESCUE_REQUIRED')
        access = getpass.getpass('New ParsPack bucket Access Key (hidden; never chat): ')
        secret = getpass.getpass('New ParsPack bucket Secret Key (hidden; never chat): ')
        password = getpass.getpass('EXISTING OpenBao custody passphrase (not Root/CA/sudo): ')
        host.require(20 <= len(password) <= 128 and not any(c in password for c in '\r\n\x00'),
                     'SNAPSHOT_CUSTODY_PASSPHRASE_REJECTED')
        recipients = json.loads(operator.read_private(args.custody / 'recipients.json'))
        digest = host.public_keys(recipients)
        encrypted = host.encrypted_result(json.loads(operator.read_private(args.custody / 'encrypted.json')))
        root = operator.decrypt(args.custody, 1, password, encrypted['root_token'])
        del password
        remote, staged_sources = operator.stage_sources(SOURCES)
        result = operator.rpc(remote, revision, staged_sources, operator.sudo_password(),
            {'root_token': root, 'recipients': recipients, 'access_key': access, 'secret_key': secret},
            supervisor='bootstrap_openbao_snapshot')
        del root, access, secret
        host.require(result.get('recipient_sha256') == digest
                     and result.get('scoped_identity') == result.get('policy') == result.get('bucket_credentials') == 'Passed'
                     and result.get('credential_values_recorded') is False,
                     'SNAPSHOT_BOOTSTRAP_RECEIPT_REJECTED')
        BASE.mkdir(mode=0o700, exist_ok=True)
        operator.custody_directory(BASE)
        path = BASE / ('bootstrap-' + uuid.uuid4().hex + '.json')
        operator.create(path, json.dumps(result).encode())
        print('OPENBAO_SNAPSHOT_BOOTSTRAP=Passed; scoped role and bucket keys stored in OpenBao; schedule Not run')
        print('PUBLIC_RECEIPT=' + str(path))
        return 0
    except host.custody.BootstrapFailed as error:
        print('OPENBAO_SNAPSHOT_BOOTSTRAP=Failed; reason=' + str(error) + '; state preserved; no automatic retry')
        return 1
    except (OSError, ValueError, KeyError, TypeError, EOFError, subprocess.SubprocessError, getpass.GetPassWarning):
        print('OPENBAO_SNAPSHOT_BOOTSTRAP=Failed; reason=SNAPSHOT_LOCAL_FAILED_STATE_PRESERVED; no reinit')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
