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
from build_kafka_topic_acl_bundle import SOURCES
import kafka_topic_acl as contract


def loader(remote, sources, plan_hash, *, installer='install_kafka_topic_acl'):
    if installer not in ('install_kafka_topic_acl', 'install_kafka_quotas'):
        raise ValueError('KAFKA_FIXED_INSTALLER_REQUIRED')
    return f'''import os,stat,hashlib,sys,types
p={remote!r}
for name,digest in {sources!r}:
 with os.fdopen(os.open(p+'/'+name,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK),'rb') as f:
  s=os.fstat(f.fileno()); content=f.read(32769)
 if not stat.S_ISREG(s.st_mode) or s.st_nlink!=1 or len(content)>32768 or hashlib.sha256(content).hexdigest()!=digest: raise SystemExit(1)
 m=types.ModuleType(name[:-3]); m.__file__=p+'/'+name; sys.modules[name[:-3]]=m
 exec(compile(content,name,'exec'),m.__dict__)
sys.argv=[{installer!r},'--plan',p+'/plan.json','--sha256',{plan_hash!r}]
raise SystemExit(sys.modules[{installer!r}].main())
'''


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
                'KAFKA_TOPIC_CLEAN_CHECKOUT_REQUIRED')
        revision = operator.command(['git', '-C', str(operator.ROOT), 'rev-parse', 'HEAD']).decode().strip()
        operator.command(['git', '-C', str(operator.ROOT), 'merge-base', '--is-ancestor', revision, 'origin/main'])
        content = bundle.validate_bundle(a.bundle, revision, sources=SOURCES)
        hashes = json.loads((a.bundle / 'bundle.json').read_bytes())['files']
        plan = json.loads(content)
        require(plan.get('kafka_topic_acl') is True
            and plan.get('public_access') == plan.get('application_ingress') == 'CLOSED',
            'KAFKA_TOPIC_CLOSED_PLAN_REQUIRED')
        if a.validate_only:
            print('KAFKA_TOPIC_ACL_BUNDLE_VALIDATION=Passed; no VPS access')
            return 0
        require(a.rescue_and_second_session_ready and operator.sys_tty()
            and os.getuid() == 1000 and 'microsoft' in os.uname().release.lower(), 'KAFKA_TOPIC_LOCAL_OWNER_REQUIRED')
        require(input('Working rescue VNC and second private SSH: type READY: ') == 'READY',
                'KAFKA_TOPIC_RESCUE_REQUIRED')
        remote, sources = operator.stage_sources(SOURCES)
        require(all(hashes.get(name) == digest for name, digest in sources),
                'KAFKA_TOPIC_STAGED_SOURCE_CHANGED')
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
        print('Migrate ONLY the exact legacy Kafka liveness if present, with one controlled broker stop/start on the SAME retained volume; then complete six topics and exact ACLs. Data/TLS and application/public CLOSED boundaries preserved.')
        print('Enter sudo password ONLY here; no custody/CA/GHCR prompt. No recording.', flush=True)
        result = subprocess.run([operator.SSH, '-t', '-o', 'ConnectTimeout=8', 'hooshix-server', command],
                                timeout=1000, env=operator.ENV, check=False)
        require(result.returncode == 0, 'KAFKA_TOPIC_TARGET_FAILED_STATE_PRESERVED')
        directory = operator.BASE.parent / 'hooshix-kafka-topics'
        directory.mkdir(mode=0o700, exist_ok=True)
        operator.custody_directory(directory)
        path = directory / ('receipt-' + uuid.uuid4().hex + '.json')
        operator.create(path, b'')
        windows = operator.command(['wslpath', '-w', str(path)]).decode().strip()
        operator.command([operator.SCP, '-q', 'hooshix-server:' + report, windows])
        receipt = json.loads(operator.read_private(path))
        require(receipt.get('kafka_topic_acl') == 'Passed' and receipt.get('plan_sha256') == plan_hash
            and receipt.get('topic_count') == 6 and receipt.get('credential_count') == 5
            and receipt.get('group_count') == 7 and receipt.get('acl_entries') == len(contract.permissions())
            and receipt.get('source_revision') == revision and receipt.get('quotas') == 'Not run'
            and receipt.get('production_readiness') == receipt.get('capacity') == 'Not verified'
            and receipt.get('application_ingress') == receipt.get('public_access') == 'CLOSED'
            and receipt.get('existing_state') == 'Preserved', 'KAFKA_TOPIC_RECEIPT_REJECTED')
        require(receipt.get('health_upgrade') in ('Passed', 'Not applicable; current health contract'),
                'KAFKA_TOPIC_HEALTH_RECEIPT_REQUIRED')
        print('KAFKA_TOPIC_ACL=Passed; six topics; five service identities; applications/public CLOSED')
        print('PUBLIC_RECEIPT=' + str(path))
        return 0
    except operator.host.custody.BootstrapFailed as error:
        print('KAFKA_TOPIC_ACL=Failed; reason=' + str(error) + '; state preserved; no automatic retry')
    except (OSError, ValueError, TypeError, KeyError, EOFError, subprocess.SubprocessError):
        print('KAFKA_TOPIC_ACL=Failed; reason=LOCAL_OPERATION_FAILED_STATE_PRESERVED')
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
