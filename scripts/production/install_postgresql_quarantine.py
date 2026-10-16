"""Supervised empty CNPG quarantine; never open applications, reset data or waive backups."""
import argparse
import copy
import fcntl
import hashlib
import json
import os
import re
import resource
import signal
import stat
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

import commission_platform as host
import install_cnpg_control_plane as cnpg
import install_postgresql_local_volume as volume
from install_eso import addresses, desired_fields_match
from upgrade_mesh_security import replace_policy

MANAGER = 'hooshix-postgresql-quarantine'
NS = 'platform-data'
NAME = 'postgresql'
SPEC_HASH = 'hooshix.io/quarantine-spec-sha256'
SECRETS = tuple(db + '-db-' + role for db in ('authorization', 'conversation', 'identity', 'notification', 'web-bff')
                for role in ('runtime', 'migration'))


def require(value, code):
    if not value:
        print('POSTGRESQL_QUARANTINE_INVARIANT=' + code, file=sys.stderr, flush=True)
        raise ValueError(code)


def digest(spec):
    return hashlib.sha256(json.dumps(spec, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def get(kube, kind, name, namespace=None):
    raw = kube(*(['-n', namespace] if namespace else []), 'get', kind, name, '--ignore-not-found', '-o', 'json')
    return json.loads(raw) if raw.strip() else None


def protected(kube):
    result = cnpg.protected(kube)
    for namespace, name in (('cnpg-system', cnpg.DEPLOYMENT), ('cnpg-system', 'barman-cloud'), (NS, 'hooshix-postgresql-eso')):
        obj = get(kube, 'deployment', name, namespace)
        require(obj and obj.get('status', {}).get('availableReplicas') == 1, 'EXISTING_CONTROLLERS_REQUIRED')
        result.append({'uid': obj['metadata']['uid'], 'spec': obj['spec']})
    for name in SECRETS:
        raw = kube('-n', NS, 'get', 'secret', name, '-o', 'jsonpath={.metadata.uid}:{.metadata.resourceVersion}:{.type}')
        fields = raw.decode().split(':')
        require(len(fields) == 3 and all(fields[:2]) and fields[2] == 'kubernetes.io/basic-auth',
                'TEN_EXISTING_TYPED_SECRETS_REQUIRED')
        result.append(raw.decode())
    return result


def reserved_volume(kube, cluster):
    sc, desired = volume.candidate()
    require(volume.matches(get(kube, sc['kind'], sc['metadata']['name']), sc), 'EXISTING_STATIC_CLASS_REQUIRED')
    pv = get(kube, 'pv', volume.VOLUME)
    require(pv is not None, 'EXISTING_RESERVED_VOLUME_REQUIRED')
    compared = copy.deepcopy(pv)
    ref = compared['spec']['claimRef']
    pvc = get(kube, 'pvc', volume.CLAIM, NS)
    if pvc:
        require(cluster and pvc['spec'].get('volumeName') == volume.VOLUME
            and any(r['uid'] == cluster['metadata']['uid'] for r in pvc['metadata'].get('ownerReferences', []))
            and ref.get('uid') == pvc['metadata']['uid'], 'FOREIGN_CLAIM_PRESERVED')
        for key in ('uid', 'resourceVersion'): ref.pop(key, None)
        compared['status']['phase'] = 'Available'
    require(volume.matches(compared, desired), 'RESERVED_VOLUME_DRIFT_PRESERVED')
    return pv['metadata']['uid']


def selects(selector, labels):
    if any(labels.get(k) != v for k, v in selector.get('matchLabels', {}).items()): return False
    for expression in selector.get('matchExpressions', []):
        key, op, values = expression['key'], expression['operator'], expression.get('values', [])
        if not {'In': labels.get(key) in values, 'NotIn': labels.get(key) not in values,
                'Exists': key in labels, 'DoesNotExist': key not in labels}.get(op, True): return False
    return True


def permissions(kube):
    actor = '--as=system:serviceaccount:platform-data:postgresql'
    require(kube('auth', 'can-i', 'get', 'secret/identity-db-migration', '-n', NS, actor).strip() == b'yes',
            'EXISTING_BOOTSTRAP_CREDENTIAL_READ_REQUIRED')
    for namespace, verb, target in ((NS, 'get', 'secret/authorization-db-runtime'),
            (NS, 'list', 'secrets'), (NS, 'create', 'secrets'), (NS, 'create', 'serviceaccounts/token'),
            (NS, 'create', 'pods'), ('hooshix-secrets', 'get', 'secret/openbao-server-tls')):
        require(kube('auth', 'can-i', verb, target, '-n', namespace, actor, expected=1).strip() == b'no',
                'QUARANTINE_SERVICE_ACCOUNT_EXTRA_AUTHORITY_REJECTED')


def cache_permissions(kube):
    actor = '--as=system:serviceaccount:cnpg-system:cnpg-manager'
    for verb in ('list', 'watch'):
        require(kube('auth', 'can-i', verb, 'secrets', '-n', 'cnpg-system', actor).strip() == b'yes',
                'APPROVED_CNPG_SECRET_CACHE_READ_REQUIRED')
        for namespace in ('hooshix-secrets', 'platform-apps'):
            require(kube('auth', 'can-i', verb, 'secrets', '-n', namespace, actor, expected=1).strip() == b'no',
                    'FOREIGN_CNPG_SECRET_CACHE_REJECTED')
    for verb in ('patch', 'update', 'delete'):
        require(kube('auth', 'can-i', verb, 'secret/hooshix-unapproved-cache-write', '-n', 'cnpg-system',
            actor, expected=1).strip() == b'no', 'CNPG_SECRET_CACHE_EXTRA_WRITE_REJECTED')


def isolation(plan, kube):
    labels = {'cnpg.io/cluster': NAME, 'istio.io/dataplane-mode': 'ambient', 'hooshix.io/commissioning': 'quarantined'}
    for kind, selector, expected in (
        ('networkpolicies', 'podSelector', plan['network'][0]),
        ('authorizationpolicies', 'selector', plan['authorization'])):
        for obj in json.loads(kube('-n', NS, 'get', kind, '-o', 'json'))['items']:
            spec = obj['spec']
            if not selects(spec.get(selector, {}), labels): continue
            if obj['metadata']['name'] == expected['metadata']['name']:
                require(spec == expected['spec'], 'QUARANTINE_POLICY_DRIFT')
            else:
                require(not spec.get('ingress') and not spec.get('egress') and not spec.get('rules')
                    and (kind != 'authorizationpolicies' or spec.get('action', 'ALLOW') == 'ALLOW'),
                    'ADDITIONAL_DATA_ACCESS_REJECTED')
            require(obj['metadata'].get('annotations', {}).get('istio.io/dry-run') in (None, 'false'), 'DRY_RUN_AUTHORIZATION_REJECTED')
    peers = json.loads(kube('-n', NS, 'get', 'peerauthentications', '-o', 'json'))['items']
    require(len(peers) == 1 and peers[0]['spec'] == {'mtls': {'mode': 'STRICT'}}, 'EXISTING_UNRELAXED_STRICT_REQUIRED')


def execute(plan, *, kube=host.kube, guard=volume.foundation):
    require(plan.get('schema_version') == 1 and plan.get('installation_id') == 'hooshix-production'
        and plan.get('profile') == 'production-single-server'
        and plan.get('approval') == 'owner-approved-empty-quarantine-2026-10-16'
        and plan.get('cache_approval') == 'owner-approved-cnpg-system-secret-list-watch-2026-10-16'
        and plan.get('application_ingress') == 'CLOSED' and plan.get('provider_backup') == 'Not run'
        and plan.get('production_readiness') == 'Not verified', 'EXACT_EMPTY_QUARANTINE_APPROVAL_REQUIRED')
    plan = copy.deepcopy(plan)
    plan['network'][0]['spec']['egress'][0]['to'] = [{'ipBlock': {'cidr': a + '/32'}} for a in addresses(kube)]
    before = protected(kube)
    catalog = get(kube, 'imagecatalog', plan['catalog']['metadata']['name'], NS)
    require(catalog and catalog['spec'] == plan['catalog']['spec']
        and catalog['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == 'hooshix-cnpg-control-plane',
        'EXISTING_SIGNED_IMAGE_CATALOG_REQUIRED')
    ns = get(kube, 'namespace', NS)['metadata']['labels']
    require(ns.get('istio.io/dataplane-mode') == 'ambient' and ns.get('pod-security.kubernetes.io/enforce') == 'restricted',
            'EXISTING_RESTRICTED_AMBIENT_REQUIRED')
    old = get(kube, 'cluster', NAME, NS)
    desired = plan['cluster']
    # Compare the complete API-defaulted spec, not just a subset that could hide extra SQL/plugins/env.
    if old:
        expected_hash = old['metadata'].get('annotations', {}).get(SPEC_HASH, '')
        require(old['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == MANAGER
            and re.fullmatch('[a-f0-9]{64}', expected_hash) and desired_fields_match(old, desired)
            and digest(old['spec']) == expected_hash and not old['metadata'].get('deletionTimestamp'),
            'FOREIGN_OR_CHANGED_CLUSTER_PRESERVED')
    else:
        defaulted = json.loads(kube('create', '--dry-run=server', '-f', '-', '-o', 'json', body=desired))
        expected_hash = digest(defaulted['spec'])
    clusters = json.loads(kube('-n', NS, 'get', 'clusters', '-o', 'json'))['items']
    require(len(clusters) == bool(old), 'FOREIGN_DATA_CLUSTER_PRESERVED')
    for kind in ('databases', 'databaseroles', 'objectstores.barmancloud.cnpg.io', 'backups', 'scheduledbackups'):
        require(not json.loads(kube('-n', NS, 'get', kind, '-o', 'json'))['items'], 'QUARANTINE_REJECTS_DATA_OR_BACKUP_RESOURCES')
    jobs = json.loads(kube('-n', NS, 'get', 'jobs', '-o', 'json'))['items']
    allowed_owners = {old['metadata']['uid']} if old else set()
    for obj in jobs:
        require(old and any(r['uid'] in allowed_owners for r in obj['metadata'].get('ownerReferences', [])), 'FOREIGN_JOB_PRESERVED')
    allowed_owners.update(o['metadata']['uid'] for o in jobs)
    for obj in json.loads(kube('-n', NS, 'get', 'pods', '-o', 'json'))['items']:
        if obj['spec'].get('serviceAccountName') == 'hooshix-postgresql-eso': continue
        require(old and any(r['uid'] in allowed_owners for r in obj['metadata'].get('ownerReferences', [])), 'FOREIGN_POD_PRESERVED')
    claims = json.loads(kube('-n', NS, 'get', 'pvc', '-o', 'json'))['items']
    require(not claims or old and len(claims) == 1 and claims[0]['metadata']['name'] == volume.CLAIM, 'FOREIGN_CLAIM_PRESERVED')
    pv_uid = reserved_volume(kube, old)
    units = guard(initialized=bool(old))
    isolation(plan, kube)
    objects = [*plan['cache_rbac'], *plan['network'], plan['authorization'], *plan['source_policies']]
    existing = []
    for obj in objects:
        m = obj['metadata']
        actual = get(kube, obj['kind'], m['name'], m.get('namespace'))
        require(not actual or (actual['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == MANAGER
            and desired_fields_match(actual, obj)
            and all(actual.get(k) == obj.get(k) for k in ('spec', 'rules', 'roleRef', 'subjects'))),
            'FOREIGN_OR_CHANGED_RESOURCE_PRESERVED')
        existing.append(bool(actual))
    previous = get(kube, 'validatingpolicy', plan['boundary']['metadata']['name'])
    require(previous['spec'] in (plan['previous_boundary']['spec'], plan['boundary']['spec']), 'PREVIOUS_DATA_BOUNDARY_CONFLICT')
    print('POSTGRESQL_QUARANTINE_STEP=admission', file=sys.stderr, flush=True)
    for obj, present in zip(objects, existing):
        if not present: kube('create', '-f', '-', body=obj)
    cache_permissions(kube)
    if previous['spec'] != plan['boundary']['spec']:
        replace_policy(plan['boundary'], get=lambda kind, name: get(kube, kind, name), kube=kube, previous=plan['previous_boundary'])
    for obj in [plan['boundary'], *plan['source_policies']]:
        kube('wait', '--for=jsonpath={.status.conditionStatus.ready}=true',
            obj['kind'].lower() + '/' + obj['metadata']['name'], '--timeout=60s', timeout=75)
    if old is None:
        print('POSTGRESQL_QUARANTINE_STEP=empty-cluster', file=sys.stderr, flush=True)
        desired['metadata'].setdefault('annotations', {})[SPEC_HASH] = expected_hash
        kube('create', '-f', '-', body=desired)
    kube('-n', NS, 'wait', '--for=condition=Ready', 'cluster/' + NAME, '--timeout=300s', timeout=315)
    current = get(kube, 'cluster', NAME, NS)
    require(digest(current['spec']) == expected_hash and (old is None or current['metadata']['uid'] == old['metadata']['uid']),
            'CLUSTER_SPEC_OR_IDENTITY_CHANGED')
    pvc = get(kube, 'pvc', volume.CLAIM, NS)
    require(pvc and pvc['status']['phase'] == 'Bound' and reserved_volume(kube, current) == pv_uid,
            'EXACT_BOUND_RETAIN_VOLUME_REQUIRED')
    pod = get(kube, 'pod', 'postgresql-1', NS)
    require(pod and pod['spec']['serviceAccountName'] == NAME and pod['status']['containerStatuses'][0]['ready']
        and pod['spec']['containers'][0]['image'] == plan['images']['postgresql']
        and pod['spec']['initContainers'][0]['image'] == plan['images']['operator'], 'EXACT_READY_DATABASE_REQUIRED')
    for service in json.loads(kube('-n', NS, 'get', 'services', '-o', 'json'))['items']:
        require(cnpg.private_service(service), 'PUBLIC_DATA_SERVICE_REJECTED')
    def sql(database, statement):
        return kube('-n', NS, 'exec', 'postgresql-1', '-c', 'postgres', '--', 'psql', '-XAtw', '-v', 'ON_ERROR_STOP=1',
                    '-U', 'postgres', '-d', database, '-c', statement).strip()
    require(sql('postgres', "SELECT string_agg(datname, ',' ORDER BY datname) FROM pg_database WHERE NOT datistemplate")
        == b'identity,postgres', 'UNEXPECTED_DATABASE_PRESERVED')
    for database in ('postgres', 'identity'):
        require(sql(database, "SELECT count(*) FROM pg_class WHERE relnamespace IN (SELECT oid FROM pg_namespace "
            "WHERE nspname NOT IN ('pg_catalog','information_schema') AND nspname NOT LIKE 'pg_toast%' "
            "AND nspname NOT LIKE 'pg_temp%')") == b'0', 'BUSINESS_DATA_PRESENT_QUARANTINE_STOPPED')
    require(sql('postgres', "SELECT (SELECT rolpassword IS NULL FROM pg_authid WHERE rolname='postgres') "
        "AND current_setting('ssl')='on'") == b't', 'TLS_AND_NO_SUPERUSER_PASSWORD_REQUIRED')
    permissions(kube)
    cache_permissions(kube)
    # Read-only loopback TLS verification; pg_hba must still deny SQL, even with a valid server name.
    for hostname, marker in (('postgresql-rw.platform-data.svc', b'pg_hba.conf rejects'),
                              ('wrong.invalid', b'does not match host name')):
        kube('-n', NS, 'exec', 'postgresql-1', '-c', 'postgres', '--', 'psql', '-XAtw',
            '-d', 'host=' + hostname + ' hostaddr=127.0.0.1 dbname=identity user=identity_migration '
            'sslmode=verify-full sslrootcert=/controller/certificates/server-ca.crt connect_timeout=5',
            '-c', 'SELECT 1', expected=2, required_error=marker)
    isolation(plan, kube)
    require(get(kube, 'validatingpolicy', plan['boundary']['metadata']['name'])['spec'] == plan['boundary']['spec']
        and get(kube, 'imagecatalog', plan['catalog']['metadata']['name'], NS)['spec'] == plan['catalog']['spec']
        and get(kube, 'imagecatalog', plan['catalog']['metadata']['name'], NS)['metadata']['uid'] == catalog['metadata']['uid']
        and protected(kube) == before and guard(initialized=True) == units, 'PRESERVED_FOUNDATION_CHANGED')
    return {'schema_version': 1, 'postgresql_quarantine': 'Passed', 'images': plan['images'],
        'cluster_uid': current['metadata']['uid'], 'pvc_uid': pvc['metadata']['uid'], 'volume_uid': pv_uid,
        'empty_database': 'Passed', 'tls_and_sql_denial': 'Passed', 'guard': 'Passed',
        'service_account_scope': 'Passed',
        'cnpg_secret_cache_scope': 'Passed',
        'root_mesh_openbao_eso_cnpg_barman': 'Preserved', 'application_ingress': 'CLOSED',
        'application_migrations': 'Not run', 'provider_backup': 'Not run', 'root_revocation': 'Not run',
        'production_readiness': 'Not verified'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--plan', type=Path, required=True)
    p.add_argument('--sha256', required=True)
    a = p.parse_args()
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(ValueError('POSTGRESQL_QUARANTINE_DEADLINE')))
    signal.alarm(900)
    try:
        require(os.geteuid() == 0 and re.fullmatch('[a-f0-9]{64}', a.sha256), 'LOCAL_AUTH_REQUIRED')
        require(not a.plan.is_symlink() and a.plan.is_file() and a.plan.stat().st_size <= host.PLAN_BOUND, 'BOUNDED_PLAN_REQUIRED')
        content = a.plan.read_bytes()
        require(hashlib.sha256(content).hexdigest() == a.sha256, 'PLAN_HASH_MISMATCH')
        plan = json.loads(content)
        evidence = plan['staging']
        observed = datetime.fromisoformat(evidence['observed_at'])
        require(observed.tzinfo and 0 <= (datetime.now(timezone.utc) - observed).total_seconds() <= 5 * 86400
            and evidence['result'] == 'Passed' and re.fullmatch('[a-f0-9]{40}', evidence['source_revision'])
            and re.fullmatch('[1-9][0-9]{0,19}', evidence['run_id']), 'FRESH_NATIVE_EVIDENCE_REQUIRED')
        services = host.preflight()
        with os.fdopen(os.open('/var/lib/hooshixstorage/postgresql-storage.lock', os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK), 'r+b') as lock:
            info = os.fstat(lock.fileno())
            require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and info.st_nlink == 1
                and stat.S_IMODE(info.st_mode) == 0o600, 'EXISTING_SAFE_STORAGE_LOCK_REQUIRED')
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            host.native(['/usr/sbin/auditctl', '-m', 'HooshiX owner-approved empty PostgreSQL quarantine plan=' + a.sha256])
            result = execute(plan)
        require(host.snapshot_services() == services, 'HOST_SERVICE_CHANGED')
        host.kernel_audit()
        result['plan_sha256'] = a.sha256
        print(json.dumps(result, separators=(',', ':')))
        return 0
    except (ValueError, KeyError, IndexError, TypeError, OSError, subprocess.SubprocessError, host.custody.BootstrapFailed):
        print('POSTGRESQL_QUARANTINE=Failed; partial state preserved; no automatic reset or retry', file=sys.stderr)
        return 1
    finally: signal.alarm(0)


if __name__ == '__main__': raise SystemExit(main())
