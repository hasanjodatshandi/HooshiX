"""Owner-local native SSH/sudo only; no custody, issuer or registry credential required."""
import argparse
import hashlib
import json
import os
import resource
import shlex
import subprocess
import uuid
from pathlib import Path

import activate_openbao_operator as operator
import bootstrap_postgresql_secrets_operator as bundle
from build_kafka_quota_bundle import SOURCES
import kafka_quota_profile as contract


def loader(remote, sources, plan_hash):
    from bootstrap_kafka_topic_acl_operator import loader as existing_loader
    return existing_loader(remote, sources, plan_hash, installer='install_kafka_quotas')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bundle', required=True, type=Path)
    p.add_argument('--validate-only', action='store_true')
    p.add_argument('--rescue-and-second-session-ready', action='store_true')
    a = p.parse_args()
    os.umask(0o077)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    require = operator.host.require
    try:
        require(operator.command(['git', '-C', str(operator.ROOT), 'status', '--porcelain']) == b'',
                'KAFKA_QUOTA_CLEAN_CHECKOUT_REQUIRED')
        revision = operator.command(['git', '-C', str(operator.ROOT), 'rev-parse', 'HEAD']).decode().strip()
        operator.command(['git', '-C', str(operator.ROOT), 'merge-base', '--is-ancestor', revision, 'origin/main'])
        content = bundle.validate_bundle(a.bundle, revision, sources=SOURCES)
        hashes = json.loads((a.bundle / 'bundle.json').read_bytes())['files']
        plan = json.loads(content)
        require(plan.get('kafka_quotas') is True
            and plan.get('public_access') == plan.get('application_ingress') == 'CLOSED',
            'KAFKA_QUOTA_CLOSED_PLAN_REQUIRED')
        if a.validate_only:
            print('KAFKA_QUOTAS_BUNDLE_VALIDATION=Passed; no VPS access')
            return 0
        require(a.rescue_and_second_session_ready and operator.sys_tty()
            and os.getuid() == 1000 and 'microsoft' in os.uname().release.lower(), 'KAFKA_QUOTA_LOCAL_OWNER_REQUIRED')
        require(input('Working rescue VNC and second private SSH: type READY: ') == 'READY',
                'KAFKA_QUOTA_RESCUE_REQUIRED')
        remote, sources = operator.stage_sources(SOURCES)
        require(all(hashes.get(name) == digest for name, digest in sources),
                'KAFKA_QUOTA_STAGED_SOURCE_CHANGED')
        plan_hash = hashlib.sha256(content).hexdigest()
        program = loader(remote, sources, plan_hash).encode()
        public = a.bundle / ('owner-loader-' + uuid.uuid4().hex + '.py')
        operator.create(public, program)
        try:
            for path, name in ((public, 'owner-loader.py'), (a.bundle / 'plan.json', 'plan.json')):
                windows = operator.command(['wslpath', '-w', str(path)]).decode().strip()
                operator.command([operator.SCP, '-q', windows, 'hooshix-server:' + remote + '/' + name])
        finally:
            public.unlink()
        digest = hashlib.sha256(program).hexdigest()
        checked = f'''import os,stat,hashlib
with os.fdopen(os.open({remote + '/owner-loader.py'!r},os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK),'rb') as f:
 s=os.fstat(f.fileno()); b=f.read(32769)
if not stat.S_ISREG(s.st_mode) or s.st_nlink!=1 or len(b)>32768 or hashlib.sha256(b).hexdigest()!={digest!r}: raise SystemExit(1)
exec(compile(b,'owner-loader.py','exec'))
'''
        report = remote + '/receipt.json'
        command = ('umask 077; sudo /usr/bin/python3 -I -c ' + shlex.quote(checked)
                   + ' > ' + shlex.quote(report))
        print('Apply ONLY the five exact user bandwidth quotas on the existing closed Kafka broker. No messages, topic/ACL/key/image/storage/network changes or restart.')
        print('Enter sudo password ONLY here; no custody/CA/GHCR prompt. No recording.', flush=True)
        result = subprocess.run([operator.SSH, '-t', '-o', 'ConnectTimeout=8', 'hooshix-server', command],
                                timeout=1000, env=operator.ENV, check=False)
        require(result.returncode == 0, 'KAFKA_QUOTA_TARGET_FAILED_STATE_PRESERVED')
        directory = operator.BASE.parent / 'hooshix-kafka-quotas'
        directory.mkdir(mode=0o700, exist_ok=True)
        operator.custody_directory(directory)
        path = directory / ('receipt-' + uuid.uuid4().hex + '.json')
        operator.create(path, b'')
        windows = operator.command(['wslpath', '-w', str(path)]).decode().strip()
        operator.command([operator.SCP, '-q', 'hooshix-server:' + report, windows])
        receipt = json.loads(operator.read_private(path))
        require(receipt.get('kafka_quotas') == 'Passed' and receipt.get('plan_sha256') == plan_hash
            and receipt.get('credential_count') == 5
            and receipt.get('producer_byte_rate') == 65536 and receipt.get('consumer_byte_rate') == 262144
            and receipt.get('source_revision') == revision
            and receipt.get('contract_sha256') == hashlib.sha256(json.dumps(
                contract.quotas(plan['kafka_quota_profile']), sort_keys=True).encode()).hexdigest()
            and receipt.get('production_readiness') == receipt.get('capacity') == 'Not verified'
            and receipt.get('application_ingress') == receipt.get('public_access') == 'CLOSED'
            and receipt.get('existing_state') == 'Preserved', 'KAFKA_QUOTA_RECEIPT_REJECTED')
        print('KAFKA_QUOTAS=Passed; five user quotas; applications/public CLOSED; capacity Not verified')
        print('PUBLIC_RECEIPT=' + str(path))
        return 0
    except operator.host.custody.BootstrapFailed as error:
        print('KAFKA_QUOTAS=Failed; reason=' + str(error) + '; state preserved; no automatic retry')
    except (OSError, ValueError, TypeError, KeyError, EOFError, subprocess.SubprocessError):
        print('KAFKA_QUOTAS=Failed; reason=LOCAL_OPERATION_FAILED_STATE_PRESERVED')
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
