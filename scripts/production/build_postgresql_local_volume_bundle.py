"""Bind a public LocalPV owner bundle to exact-tree filesystem and Kubernetes evidence."""
import argparse
import hashlib
import json
import re
import shutil
import tempfile
from pathlib import Path

import build_postgresql_storage_bundle as storage

ROOT = storage.ROOT
SOURCE = 'install_postgresql_local_volume.py'
WRAPPER = 'run-postgresql-local-volume.ps1'
CHECKS = ('reservation_reentry_conflict', 'wrong_claim_denied', 'node_affinity_and_mount',
          'pvc_reuse_persistence', 'retain_after_claim_deletion')


def check(record, filesystem, kubernetes, revision):
    storage.check(record, filesystem, revision)
    proof = kubernetes['postgresql_local_volume']
    if (not re.fullmatch('[a-f0-9]{40}', kubernetes['revision'])
            or storage.original.git('rev-parse', kubernetes['revision'] + '^{tree}')
                != storage.original.git('rev-parse', revision + '^{tree}')
            or kubernetes['checks']['cleanup'] != 'Passed'
            or proof['source_sha256'] != hashlib.sha256((ROOT / 'scripts/production' / SOURCE).read_bytes()).hexdigest()
            or any(proof[k] != 'Passed' for k in CHECKS)):
        raise ValueError('exact successful LocalPV API/mount/retention fixture required')


def build(output, run_id):
    git, gh = storage.original.git, storage.original.publication.run
    revision = git('rev-parse', 'HEAD')
    if (not re.fullmatch('[1-9][0-9]{0,19}', run_id) or git('status', '--porcelain')
            or revision != git('ls-remote', 'origin', 'refs/heads/main').split()[0]
            or output.exists() or output.is_symlink() or output.resolve().is_relative_to(ROOT)):
        raise ValueError('clean exact main and new outside-Git output required')
    api = 'repos/hasanjodatshandi/HooshiX/actions/runs/' + run_id
    record = json.loads(gh(['api', api]))
    artifacts = json.loads(gh(['api', api + '/artifacts?per_page=100']))['artifacts']
    with tempfile.TemporaryDirectory(prefix='hooshix-pg-local-public-') as temp:
        records = {}
        for prefix, filename in (('postgresql-storage', 'postgresql-storage-receipt.json'),
                                 ('openbao-kubernetes', 'openbao-kubernetes-receipt.json')):
            name = prefix + '-' + run_id + '-' + str(record['run_attempt'])
            matches = [a for a in artifacts if a['name'] == name]
            if len(matches) != 1 or matches[0]['expired'] or not 0 < matches[0]['size_in_bytes'] <= 32768:
                raise ValueError('bounded exact native artifact required')
            directory = Path(temp) / prefix
            gh(['run', 'download', run_id, '--repo', 'hasanjodatshandi/HooshiX', '--name', name, '--dir', str(directory)])
            files = list(directory.iterdir())
            if len(files) != 1 or files[0].name != filename or files[0].is_symlink() or files[0].stat().st_size > 16384:
                raise ValueError('single bounded public proof required')
            records[prefix] = json.loads(files[0].read_bytes())
        check(record, records['postgresql-storage'], records['openbao-kubernetes'], revision)
        output.mkdir(mode=0o700)
        for filename in (SOURCE, WRAPPER): shutil.copyfile(ROOT / 'scripts/production' / filename, output / filename)
        for prefix, proof in records.items():
            (output / (prefix + '-receipt.json')).write_text(json.dumps(proof, indent=2) + '\n')
        manifest = {'schema_version': 1, 'source_revision': revision, 'native_run': run_id,
            'native_observed_at': record['updated_at'],
            'files': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in output.iterdir()}}
        (output / 'bundle.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--native-run', required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    build(args.output, args.native_run)
    print('POSTGRESQL_LOCAL_VOLUME_BUNDLE=Passed; target Not run')
    print('PUBLIC_BUNDLE=' + str(args.output))
