"""Supervised, create-only platform commissioning on the owner's existing VPS."""
from __future__ import annotations

import argparse
import base64
import fcntl
import getpass
import hashlib
import json
import os
import re
import resource
import signal
import stat
import subprocess
import sys
import tempfile
import time
import warnings
from datetime import datetime, timezone
from pathlib import Path

import bootstrap_intermediate_csr as custody
import import_intermediate_ca as ca_import
import verify_storage_guard as storage

STATE = custody.BASE / 'platform-commissioning'
MANAGER = 'hooshix-platform-commissioning'
LABELS = {'app.kubernetes.io/part-of': 'hooshix-platform',
          'app.kubernetes.io/managed-by': MANAGER}
BOUND = 2 * 1024 * 1024
PRESERVED = ('ssh.service', 'nginx.service', 'postfix.service', 'dovecot.service',
             'auditd.service', 'wg-quick@wg-hooshix.service', 'k3s.service')


def require(value, code):
    custody.require(value, code)


def native(argv, *, data=None, expected=0, timeout=30, password=None, required_error=None):
    read_fd = None
    try:
        descriptors = ()
        if password is not None:
            read_fd, write_fd = os.pipe()
            try:
                os.write(write_fd, password + b'\n')
            finally:
                os.close(write_fd)
            argv = [*argv, '-passin', 'fd:' + str(read_fd)]
            descriptors = (read_fd,)
        result = subprocess.run(argv, input=data, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE if required_error else subprocess.DEVNULL,
                                check=False, timeout=timeout,
                                env=custody.ENV, pass_fds=descriptors)
        require(result.returncode == expected and len(result.stdout) <= BOUND
                and (required_error is None or required_error in result.stderr),
                'NATIVE_OPERATION_FAILED_STATE_PRESERVED')
        return result.stdout
    finally:
        if read_fd is not None:
            os.close(read_fd)


def kube(*args, body=None, expected=0, timeout=45, required_error=None):
    return native([custody.K3S, 'kubectl', '--request-timeout=30s', *args],
                  data=json.dumps(body).encode() if body is not None else None,
                  expected=expected, timeout=timeout, required_error=required_error)


def get(kind, name, namespace=None):
    scope = ['-n', namespace] if namespace else []
    value = kube(*scope, 'get', kind, name, '--ignore-not-found', '-o', 'json')
    return json.loads(value) if value.strip() else None


def apply(value):
    # Never force field ownership or delete an existing object/PVC to fit a plan.
    kube('apply', '--server-side', '--field-manager=' + MANAGER, '-f', '-', body=value)


def create(value):
    kube('create', '-f', '-', body=value)


def snapshot_services():
    result = {}
    for unit in PRESERVED:
        require(native(['/usr/bin/systemctl', 'is-active', unit]).strip() == b'active',
                'PRESERVED_SERVICE_UNAVAILABLE')
        result[unit] = native(['/usr/bin/systemctl', 'show', unit, '--property=MainPID', '--value']).strip()
    return result


def kernel_audit():
    value = native(['/usr/sbin/auditctl', '-s']).decode()
    require(re.search(r'^enabled [12]$', value, re.M) and re.search(r'^lost 0$', value, re.M),
            'PROTECTED_AUDIT_UNHEALTHY')


def progress(label, revision):
    require(label in ('admission', 'tls', 'mesh', 'storage', 'openbao', 'verification'),
            'INVALID_COMMISSIONING_PHASE')
    kernel_audit()
    native(['/usr/sbin/auditctl', '-m', 'HooshiX PR181 platform commissioning '
            + revision + ' phase=' + label])
    print('PLATFORM_INSTALL_STEP=' + label, file=sys.stderr, flush=True)


def target_admission(plan):
    # Read-only API requests prove native Deny before relaxing namespace PSA.
    pod = next(r['spec']['template']['spec'] for stage in plan['mesh']['stages']
               for r in stage['manifest']['items'] if r['kind'] == 'Deployment')
    create_sa = {'apiVersion': 'v1', 'kind': 'ServiceAccount',
                 'metadata': {'name': pod['serviceAccountName'], 'namespace': 'istio-system'}}
    apply(create_sa)
    probe = {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {
        'name': 'hooshix-read-only-admission-probe', 'namespace': 'istio-system'}, 'spec': pod}
    # The wrong image/SA pairing is never actually executed.
    wrong = json.loads(json.dumps(probe))
    wrong['spec']['containers'][0]['image'] = plan['openbao']['items'][-1]['spec']['template']['spec']['containers'][0]['image']
    for value, expected, reason in ((wrong, 1, b'platform bootstrap identity/security exception rejected'),
                                    (probe, 0, None)):
        deadline = time.monotonic() + 45
        while True:
            try:
                kube('apply', '--dry-run=server', '-f', '-', body=value, expected=expected, required_error=reason)
                break
            except custody.BootstrapFailed as error:
                if str(error) != 'NATIVE_OPERATION_FAILED_STATE_PRESERVED' or time.monotonic() >= deadline:
                    raise
                time.sleep(1)


def read_plan(path, digest, revision):
    require(re.fullmatch(r'[a-f0-9]{64}', digest) and re.fullmatch(r'[a-f0-9]{40}', revision),
            'REVIEWED_SOURCE_AND_PLAN_REQUIRED')
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and 0 < info.st_size <= BOUND,
                'BOUNDED_REGULAR_PLAN_REQUIRED')
        content = stream.read(BOUND + 1)
    require(hashlib.sha256(content).hexdigest() == digest, 'PLAN_HASH_CONFLICT')
    value = json.loads(content)
    require(value.get('schema_version') == 1 and value.get('installation_id') == 'hooshix-production'
            and value.get('profile') == 'production-single-server'
            and [s['component'] for s in value['mesh']['stages']] == ['base', 'istiod', 'cni', 'ztunnel']
            and value['openbao']['items'][-1]['metadata']['name'] == 'openbao'
            and len(value['admission']['items']) == 4, 'REVIEWED_PLAN_SHAPE_REQUIRED')
    # Evidence was authenticated against the exact CI run by the bundle builder;
    # no caller-provided "Passed" string alone authorizes this plan.
    evidence = value.get('commissioning_evidence', {})
    require(evidence.get('source_revision') == revision and evidence.get('staging') == 'Passed'
            and isinstance(evidence.get('run_id'), int), 'COMMIT_BOUND_STAGING_REQUIRED')
    observed = datetime.fromisoformat(evidence['observed_at'])
    age = (datetime.now(timezone.utc) - observed).total_seconds() if observed.tzinfo else -1
    require(0 <= age <= 5 * 86400, 'FRESH_STAGING_REQUIRED')
    for policy in value['admission']['items']:
        require(policy['spec']['failurePolicy'] == 'Fail'
                and policy['spec']['validationActions'] == ['Deny'], 'BLOCKING_ADMISSION_REQUIRED')
    require(value.get('admission_prerequisites') == {
        'apiVersion': 'v1', 'kind': 'List', 'items': [{
            'apiVersion': 'rbac.authorization.k8s.io/v1', 'kind': 'ClusterRole',
            'metadata': {'name': 'hooshix-platform-ephemeral-report-reader', 'labels': {
                'rbac.kyverno.io/aggregate-to-reports-controller': 'true'}},
            'rules': [{'apiGroups': [''], 'resources': ['pods/ephemeralcontainers'],
                       'verbs': ['get', 'list', 'watch']}]}]}, 'READ_ONLY_REPORTING_RBAC_REQUIRED')
    return value


def preflight():
    require(os.geteuid() == 0 and os.uname().nodename == 'mail.hooshix.com'
            and os.uname().machine == 'x86_64', 'WRONG_TARGET_OR_LOCAL_SUDO_REQUIRED')
    require('v1.35.6+k3s1' in native([custody.K3S, '--version']).decode(), 'K3S_VERSION_REVIEW_REQUIRED')
    for package in ('openssl', 'libssl3t64:amd64'):
        require(native(['/usr/bin/dpkg-query', '-W', '-f=${Version}', package]).decode() == custody.PACKAGE,
                'HOST_CRYPTO_VERSION_REVIEW_REQUIRED')
    before = snapshot_services()
    kernel_audit()
    ca_import.audit_policy_preflight()
    encryption = native([custody.K3S, 'secrets-encrypt', 'status']).decode()
    require('Encryption Status: Enabled' in encryption and 'All hashes match' in encryption,
            'CLUSTER_ENCRYPTION_REQUIRED')
    require(kube('get', '--raw=/readyz').strip() == b'ok', 'CLUSTER_API_NOT_READY')
    node = get('node', 'hooshix-production-1')
    require(node and any(c['type'] == 'Ready' and c['status'] == 'True'
                        for c in node['status']['conditions']), 'EXACT_READY_NODE_REQUIRED')
    for name in ('kyverno-admission-controller', 'kyverno-reports-controller'):
        obj = get('deployment', name, 'kyverno')
        require(obj and obj['status'].get('availableReplicas', 0) >= 1, 'KYVERNO_UNAVAILABLE')
    installed = storage.load_storage()
    installed['guard_check']()
    require(storage.show(installed, installed['GUARD_NAME']) == 'active', 'STORAGE_GUARD_UNAVAILABLE')
    free = os.statvfs('/var/lib/rancher/k3s')
    require(free.f_bavail * free.f_frsize >= 2 * 1024**3
            and free.f_bavail / free.f_blocks >= 0.30, 'HOST_DISK_HEADROOM_REQUIRED')
    memory = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
    require(int(memory['MemAvailable'].split()[0]) >= 2 * 1024**2, 'BOOTSTRAP_MEMORY_HEADROOM_REQUIRED')
    # No complete-stack capacity or Production-readiness claim follows these checks.
    return before


def registry_credentials():
    # The operator pastes the existing read:packages token into a hidden local prompt.
    # No plaintext token file/argv/environment/diagnostic crosses this boundary.
    with open('/dev/tty', 'r+b', buffering=0):
        pass
    warnings.simplefilter('error', getpass.GetPassWarning)
    token = getpass.getpass('Existing GHCR read:packages token (hidden; never chat): ')
    require(1 <= len(token) <= 4096 and not any(ord(c) <= 32 or ord(c) >= 127 for c in token),
            'REGISTRY_TOKEN_REJECTED')
    auth = base64.b64encode(('hasanjodatshandi:' + token).encode()).decode()
    data = base64.b64encode(json.dumps({'auths': {'ghcr.io': {'auth': auth}}}).encode()).decode()
    del token, auth
    for ns in ('kyverno', 'istio-system', 'hooshix-secrets'):
        value = {'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {
            'name': 'hooshix-ghcr-read', 'namespace': ns, 'labels': LABELS},
            'type': 'kubernetes.io/dockerconfigjson', 'data': {'.dockerconfigjson': data}}
        existing = get('secret', 'hooshix-ghcr-read', ns)
        require(existing is None or all(existing['metadata'].get('labels', {}).get(k) == v
                                        for k, v in LABELS.items()), 'FOREIGN_REGISTRY_SECRET_PRESERVED')
        apply(value) if existing else create(value)


def tls_secret(public_directory, revision):
    files = ca_import.read_public(public_directory)
    marker = custody.public_receipt(revision)
    with tempfile.TemporaryDirectory(prefix='tls-', dir=STATE) as temp:
        work = Path(temp)
        ca_import.validate_public(files, marker, work)
        existing_ca = get('secret', 'cacerts', 'istio-system')
        require(existing_ca and all(existing_ca['metadata'].get('labels', {}).get(k) == v
                                   for k, v in ca_import.LABELS.items()), 'IMPORTED_CA_REQUIRED')
        for name in ('ca-cert.pem', 'cert-chain.pem', 'root-cert.pem'):
            require(base64.b64decode(existing_ca['data'][name], validate=True) == files[name],
                    'IMPORTED_CA_CERTIFICATE_CONFLICT')
        del existing_ca
        existing = get('secret', 'openbao-server-tls', 'hooshix-secrets')
        if existing:
            require(all(existing['metadata'].get('labels', {}).get(k) == v for k, v in LABELS.items())
                    and set(existing['data']) == {'tls.crt', 'tls.key', 'ca.crt'}, 'FOREIGN_TLS_SECRET_PRESERVED')
            data = {k: base64.b64decode(v, validate=True) for k, v in existing['data'].items()}
            require(data['ca.crt'] == files['root-cert.pem'], 'TLS_ROOT_CONFLICT')
            for name in ('tls.crt', 'tls.key'):
                custody.create(work / name, data[name])
        else:
            password = getpass.getpass('EXISTING intermediate-key passphrase (not Root; never chat): ')
            require(20 <= len(password) <= 128 and not any(ord(c) < 32 or ord(c) == 127 for c in password),
                    'PASSPHRASE_POLICY_REJECTED')
            secret = password.encode()
            del password
            native([custody.OPENSSL, 'req', '-new', '-newkey', 'rsa:3072', '-sha256', '-nodes',
                    '-subj', '/CN=openbao.hooshix-secrets.svc', '-keyout', str(work / 'tls.key'),
                    '-out', str(work / 'tls.csr')], timeout=60)
            custody.create(work / 'server.cnf', b'[server]\nbasicConstraints=critical,CA:FALSE\n'
                           b'keyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n'
                           b'subjectAltName=IP:127.0.0.1,DNS:openbao.hooshix-secrets.svc,'
                           b'DNS:openbao-0.openbao.hooshix-secrets.svc\n')
            native([custody.OPENSSL, 'x509', '-req', '-in', str(work / 'tls.csr'), '-CA', str(work / 'ca-cert.pem'),
                    '-CAkey', str(custody.STATE / 'ca-key.enc.pem'), '-set_serial', '0x' + os.urandom(16).hex(),
                    '-days', '30', '-sha256', '-extfile', str(work / 'server.cnf'), '-extensions', 'server',
                    '-out', str(work / 'tls.crt')], password=secret)
            del secret
            leaf = (work / 'tls.crt').read_bytes()
            (work / 'tls.crt').write_bytes(leaf + files['ca-cert.pem'])
            data = {'tls.crt': (work / 'tls.crt').read_bytes(), 'tls.key': (work / 'tls.key').read_bytes(),
                    'ca.crt': files['root-cert.pem']}
        cert_key = native([custody.OPENSSL, 'x509', '-in', str(work / 'tls.crt'), '-pubkey', '-noout'])
        key = native([custody.OPENSSL, 'pkey', '-in', str(work / 'tls.key'), '-pubout'])
        require(cert_key == key, 'TLS_KEY_CONFLICT')
        for mode, name in (('-verify_hostname', 'openbao.hooshix-secrets.svc'),
                           ('-verify_hostname', 'openbao-0.openbao.hooshix-secrets.svc'),
                           ('-verify_ip', '127.0.0.1')):
            native([custody.OPENSSL, 'verify', '-CAfile', str(work / 'root-cert.pem'), '-no-CApath',
                    '-no-CAstore', '-untrusted', str(work / 'ca-cert.pem'), '-purpose', 'sslserver',
                    mode, name, str(work / 'tls.crt')])
        native([custody.OPENSSL, 'x509', '-in', str(work / 'tls.crt'), '-checkend', '604800', '-noout'])
        if not existing:
            create({'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {
                'name': 'openbao-server-tls', 'namespace': 'hooshix-secrets', 'labels': LABELS},
                'type': 'Opaque', 'data': {k: base64.b64encode(v).decode() for k, v in data.items()}})


def rollout(kind, name):
    kube('-n', 'istio-system', 'rollout', 'status', kind + '/' + name, '--timeout=240s', timeout=255)


def verify_bao(image):
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        pod = get('pod', 'openbao-0', 'hooshix-secrets')
        status = pod['status'].get('containerStatuses', []) if pod else []
        if status and status[0].get('state', {}).get('running'):
            break
        time.sleep(2)
    else:
        require(False, 'OPENBAO_STARTUP_DEADLINE_STATE_PRESERVED')
    require(pod['spec']['containers'][0]['image'] == image
            and status[0]['imageID'].endswith(image.split('@')[1]), 'OPENBAO_RUNTIME_IMAGE_CONFLICT')
    claim = get('pvc', 'data-openbao-0', 'hooshix-secrets')
    require(claim['status']['phase'] == 'Bound' and claim['spec']['volumeName'] == 'hooshix-openbao-local-8gib',
            'EXACT_RETAINED_LOCAL_PVC_REQUIRED')
    command = ['/usr/bin/bao', 'status', '-format=json', '-address=https://127.0.0.1:8200',
               '-ca-cert=/openbao/tls/ca.crt']
    value = json.loads(kube('-n', 'hooshix-secrets', 'exec', 'openbao-0', '--', *command, expected=2))
    require(value['sealed'] is True and value['initialized'] is False, 'EXISTING_DATASTORE_PRESERVED')
    kube('-n', 'hooshix-secrets', 'exec', 'openbao-0', '--', '/usr/bin/bao', 'read',
         '-address=https://127.0.0.1:8200', '-ca-cert=/openbao/tls/ca.crt', '-field=sealed', 'sys/seal-status')
    kube('-n', 'hooshix-secrets', 'exec', 'openbao-0', '--', *command, '-tls-server-name=wrong.invalid', expected=1)
    # Sealed is intentionally not Ready, but must not cause liveness restarts.
    time.sleep(20)
    status = get('pod', 'openbao-0', 'hooshix-secrets')['status']['containerStatuses'][0]
    require(status['restartCount'] == 0 and not status['ready'], 'SEALED_PROBE_BEHAVIOR_CONFLICT')


def execute(path, digest, revision, public_directory):
    plan = read_plan(path, digest, revision)
    before = preflight()
    custody.directory(STATE)
    lock_path = STATE / 'install.lock'
    with os.fdopen(os.open(lock_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600), 'r+b') as lock:
        custody.protected(lock_path, exact_mode=0o600)
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        marker = STATE / 'plan.json'
        record = {'source_revision': revision, 'plan_sha256': digest}
        if marker.exists():
            custody.protected(marker, exact_mode=0o600)
            require(json.loads(marker.read_bytes()) == record, 'EXISTING_COMMISSIONING_PLAN_PRESERVED')
        else:
            for kind, name, ns in (('statefulset', 'openbao', 'hooshix-secrets'),
                                   ('deployment', 'istiod', 'istio-system'), ('daemonset', 'ztunnel', 'istio-system')):
                require(get(kind, name, ns) is None, 'UNOWNED_PLATFORM_INSTALLATION_PRESERVED')
            custody.create(marker, json.dumps(record).encode())
        # Keep the existing imported CA and the secrets namespace's Restricted PSA.
        for name in ('istio-system', 'hooshix-secrets'):
            apply({'apiVersion': 'v1', 'kind': 'Namespace', 'metadata': {'name': name}})
        progress('admission', revision)
        registry_credentials()
        apply(plan['admission_prerequisites'])
        apply(plan['admission'])
        for policy in plan['admission']['items']:
            kube('wait', '--for=jsonpath={.status.conditionStatus.ready}=true', policy['kind'].lower() + '/' + policy['metadata']['name'],
                 '--timeout=60s', timeout=75)
        target_admission(plan)
        # Native Deny is active before admitting the two narrowly reviewed root
        # networking components. No unrelated namespace or PSA setting changes.
        kube('label', 'namespace', 'istio-system', 'pod-security.kubernetes.io/enforce=privileged', '--overwrite')
        progress('tls', revision)
        tls_secret(public_directory=public_directory, revision=revision)
        progress('mesh', revision)
        for stage in plan['mesh']['stages']:
            apply(stage['manifest'])
            if stage['component'] != 'base':
                kind = 'deployment' if stage['component'] == 'istiod' else 'daemonset'
                name = 'istio-cni-node' if stage['component'] == 'cni' else stage['component']
                rollout(kind, name)
        progress('storage', revision)
        storage.load_storage()['guard_check']()
        for item in plan['storage']['items']:
            existing = get(item['kind'].lower(), item['metadata']['name'])
            if existing:
                if item['kind'] == 'PersistentVolume':
                    require(existing['spec'].get('local') == item['spec']['local']
                            and existing['spec'].get('claimRef', {}).get('namespace') == 'hooshix-secrets'
                            and existing['spec'].get('claimRef', {}).get('name') == 'data-openbao-0'
                            and existing['spec'].get('persistentVolumeReclaimPolicy') == 'Retain',
                            'EXISTING_PV_CONFLICT_PRESERVED')
            apply(item)
        progress('openbao', revision)
        apply(plan['openbao'])
        image = plan['openbao']['items'][-1]['spec']['template']['spec']['containers'][0]['image']
        progress('verification', revision)
        verify_bao(image)
        storage.load_storage()['guard_check']()
        require(snapshot_services() == before, 'PRESERVED_SERVICE_STATE_CHANGED')
        return {'schema_version': 1, 'source_revision': revision, 'plan_sha256': digest,
                'observed_at': datetime.now(timezone.utc).isoformat(), 'mesh_installation': 'Passed',
                'openbao_installation': 'Passed', 'openbao_state': 'Installed; sealed; not initialized',
                'tls_and_sealed_probes': 'Passed', 'retained_local_pvc': 'Passed',
                'protected_audit_and_storage_guard': 'Passed', 'mail_management_k3s_preserved': 'Passed',
                'initialization': 'Not run', 'off_host_snapshot': 'Not verified',
                'production_readiness': 'Not verified'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--plan-sha256', required=True)
    parser.add_argument('--reviewed-commit', required=True)
    parser.add_argument('--public-directory', type=Path, required=True)
    parser.add_argument('--rescue-and-second-session-ready', action='store_true', required=True)
    args = parser.parse_args()
    os.umask(0o077)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    signal.signal(signal.SIGALRM, custody.deadline_expired)
    signal.alarm(1200)
    try:
        receipt = execute(args.plan, args.plan_sha256, args.reviewed_commit, args.public_directory)
        print(json.dumps(receipt))
        return 0
    except custody.BootstrapFailed as error:
        print(json.dumps({'schema_version': 1, 'openbao_installation': 'Not verified',
                          'reason': str(error), 'state_preserved': True, 'production_readiness': 'Not verified'}))
        return 1
    except (OSError, ValueError, KeyError, TypeError, EOFError, subprocess.SubprocessError):
        print(json.dumps({'schema_version': 1, 'openbao_installation': 'Not verified',
                          'reason': 'COMMISSIONING_FAILED_STATE_PRESERVED', 'state_preserved': True,
                          'production_readiness': 'Not verified'}))
        return 1
    finally:
        signal.alarm(0)


if __name__ == '__main__':
    raise SystemExit(main())
