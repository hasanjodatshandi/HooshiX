"""Build a public fixed-size owner-local bundle from authenticated native evidence."""
import argparse
import hashlib
import json
import re
import shutil
import tempfile
from datetime import datetime, timezone
from pathlib import Path

import build_platform_commissioning_bundle as original

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ('provision_openbao_storage.py', 'provision_postgresql_storage.py')
WRAPPER = 'run-postgresql-storage.ps1'
GUARD_CHECKS = {'startup_identity', 'live_dependency_binding', 'fsGroup_permissions',
    'readonly_fault_stop', 'backing_loss_stop', 'unexpected_unmount_stop', 'explicit_rearm_only',
    'unmounted_nonroot_fallback_denied', 'existing_bind_retained', 'absent_source_bind_denied'}


def check(record, evidence, revision):
    if (record['path'] != '.github/workflows/repository-baseline.yml'
            or record['status'] != 'completed' or record['conclusion'] != 'success'
            or record['repository']['full_name'] != 'hasanjodatshandi/HooshiX'
            or record['head_repository']['full_name'] != 'hasanjodatshandi/HooshiX'
            or record['event'] not in ('pull_request', 'push', 'workflow_dispatch')
            or not re.fullmatch('[a-f0-9]{40}', record['head_sha'])
            or original.git('rev-parse', record['head_sha'] + '^{tree}') != original.git('rev-parse', revision + '^{tree}')
            or evidence['profile'] != 'postgresql-32gib' or evidence['runtime_uid'] != 26
            or evidence['fixture_bytes'] != 64 * 1024**2
            or set(evidence['guard_checks']) != GUARD_CHECKS
            or any(v != 'Passed' for v in evidence['guard_checks'].values())
            or any(evidence[k] != 'Passed' for k in ('enospc_nonroot', 'reserved_size',
                'systemd_remount_persistence', 'mount_options', 'cleanup'))
            or evidence['source_sha256'] != hashlib.sha256((ROOT / 'scripts/production' / SOURCES[1]).read_bytes()).hexdigest()
            or evidence['core_sha256'] != hashlib.sha256((ROOT / 'scripts/production' / SOURCES[0]).read_bytes()).hexdigest()):
        raise ValueError('exact reviewed native storage evidence required')
    observed = datetime.fromisoformat(record['updated_at'].replace('Z', '+00:00'))
    if not 0 <= (datetime.now(timezone.utc) - observed).total_seconds() <= 5 * 86400:
        raise ValueError('fresh native evidence required')


def build(output, run_id):
    revision = original.git('rev-parse', 'HEAD')
    if (not re.fullmatch('[1-9][0-9]{0,19}', run_id) or original.git('status', '--porcelain')
            or revision != original.git('ls-remote', 'origin', 'refs/heads/main').split()[0]
            or output.exists() or output.is_symlink() or output.resolve().is_relative_to(ROOT)):
        raise ValueError('clean exact main and new outside-Git output required')
    gh = original.publication.run
    api = 'repos/hasanjodatshandi/HooshiX/actions/runs/' + run_id
    record = json.loads(gh(['api', api]))
    name = 'postgresql-storage-' + run_id + '-' + str(record['run_attempt'])
    matches = [a for a in json.loads(gh(['api', api + '/artifacts']))['artifacts'] if a['name'] == name]
    if len(matches) != 1 or matches[0]['expired'] or not 0 < matches[0]['size_in_bytes'] <= 16384:
        raise ValueError('exact bounded native artifact required')
    with tempfile.TemporaryDirectory(prefix='hooshix-pg-storage-public-') as t:
        directory = Path(t) / 'native'
        gh(['run', 'download', run_id, '--repo', 'hasanjodatshandi/HooshiX', '--name', name, '--dir', str(directory)])
        files = list(directory.iterdir())
        if (len(files) != 1 or files[0].name != 'postgresql-storage-receipt.json'
                or files[0].is_symlink() or files[0].stat().st_size > 8192):
            raise ValueError('bounded public receipt required')
        evidence = json.loads(files[0].read_bytes())
        check(record, evidence, revision)
        output.mkdir(mode=0o700)
        shutil.copyfile(files[0], output / files[0].name)
        for name in (*SOURCES, WRAPPER): shutil.copyfile(ROOT / 'scripts/production' / name, output / name)
        result = {'schema_version': 1, 'source_revision': revision, 'native_run': run_id,
            'native_observed_at': record['updated_at'],
            'files': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in output.iterdir()}}
        (output / 'bundle.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--native-run', required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    build(args.output, args.native_run)
    print('POSTGRESQL_STORAGE_BUNDLE=Passed; target Not run')
    print('PUBLIC_BUNDLE=' + str(args.output))
