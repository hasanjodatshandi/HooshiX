"""Owner-local custody proof and hash-bound scoped-auth supervisor; no cloud calls."""
from __future__ import annotations

import argparse
import getpass
import json
import os
import re
import resource
import subprocess
import uuid
import warnings
from pathlib import Path

import activate_openbao_host as host
import activate_openbao_operator as operator

SOURCES = (*operator.SOURCES, 'openbao_scoped_auth.py', 'bootstrap_openbao_auth.py')
BASE = Path('/home/coder/.local/share/hooshix-openbao-auth')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--custody', type=Path, required=True)
    parser.add_argument('--rescue-and-second-session-ready', action='store_true', required=True)
    args = parser.parse_args()
    os.umask(0o077)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    warnings.simplefilter('error', getpass.GetPassWarning)
    try:
        host.require(os.getuid() == 1000 and operator.sys_tty()
                     and 'microsoft' in os.uname().release.lower(), 'AUTH_OWNER_WSL_TTY_REQUIRED')
        host.require(operator.command(['/usr/bin/git', '-C', str(operator.ROOT), 'status', '--porcelain']) == b'',
                     'AUTH_CLEAN_CHECKOUT_REQUIRED')
        revision = operator.command(['/usr/bin/git', '-C', str(operator.ROOT), 'rev-parse', 'HEAD']).decode().strip()
        operator.command(['/usr/bin/git', '-C', str(operator.ROOT), 'merge-base', '--is-ancestor', revision, 'origin/main'])
        host.require(args.custody.parent == operator.BASE
                     and re.fullmatch(r'[a-f0-9]{32}', args.custody.name), 'AUTH_CUSTODY_PATH_REJECTED')
        operator.custody_directory(args.custody)
        host.require(input('Working rescue VNC and second private SSH session: type READY: ') == 'READY',
                     'AUTH_RESCUE_REQUIRED')
        password = getpass.getpass('EXISTING OpenBao custody passphrase (not Root/CA/sudo): ')
        host.require(20 <= len(password) <= 128 and not any(c in password for c in '\r\n\x00'),
                     'AUTH_CUSTODY_PASSPHRASE_REJECTED')
        recipients = json.loads(operator.read_private(args.custody / 'recipients.json'))
        digest = host.public_keys(recipients)
        encrypted = host.encrypted_result(json.loads(operator.read_private(args.custody / 'encrypted.json')))
        root = operator.decrypt(args.custody, 1, password, encrypted['root_token'])
        del password
        remote, hashes = operator.stage_sources(SOURCES)
        result = operator.rpc(remote, revision, hashes, operator.sudo_password(),
            {'root_token': root, 'recipients': recipients}, supervisor='bootstrap_openbao_auth')
        del root
        host.require(result.get('recipient_sha256') == digest and result.get('image') == host.IMAGE
                     and result.get('scoped_authentication') == 'Passed', 'AUTH_RECEIPT_REJECTED')
        BASE.mkdir(mode=0o700, exist_ok=True)
        operator.custody_directory(BASE)
        receipt = BASE / ('auth-' + uuid.uuid4().hex + '.json')
        operator.create(receipt, json.dumps(result).encode())
        print('OPENBAO_SCOPED_AUTH=Passed; six read-only identities; root preserved; ESO not installed')
        print('PUBLIC_RECEIPT=' + str(receipt))
        return 0
    except host.custody.BootstrapFailed as error:
        print('OPENBAO_SCOPED_AUTH=Failed; reason=' + str(error) + '; state preserved; no reinit')
        return 1
    except (OSError, ValueError, KeyError, TypeError, EOFError, subprocess.SubprocessError, getpass.GetPassWarning):
        print('OPENBAO_SCOPED_AUTH=Failed; reason=AUTH_LOCAL_FAILED_STATE_PRESERVED; no reinit')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
