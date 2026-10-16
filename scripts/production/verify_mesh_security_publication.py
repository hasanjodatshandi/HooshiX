"""Download the exact protected-main source publication for limited mesh staging."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

import render_mesh_security_upgrade as upgrade
import verify_platform_publication_run as publication


def refresh_scans(directory: Path) -> None:
    # Re-evaluate the authenticated immutable inventories; never rebuild, push or re-sign.
    if not re.search(r'(?<![\w.])0\.117\.0(?![\w.])', upgrade.build.run(['grype', 'version'])):
        raise ValueError('pinned Grype required for fresh publication scans')
    upgrade.build.run(['grype', 'db', 'update'], timeout=180)
    database = upgrade.build.run(['grype', 'db', 'status', '-o', 'json'])
    for component in (*upgrade.COMPONENTS, 'eso'):
        folder = directory / component
        if folder.is_symlink() or not folder.is_dir(): raise ValueError('owned public component directory required')
        (folder / 'database.json').write_text(database)
        upgrade.build.scan(folder)


def download(directory: Path) -> None:
    selected = upgrade.pin()
    run_id = selected['publication_run']
    record = json.loads(publication.run(['api', 'repos/' + publication.REPOSITORY + '/actions/runs/' + run_id]))
    publication.check_run(record)
    if (record['head_sha'] != selected['repository_revision']
            or record['run_attempt'] != selected['publication_attempt']):
        raise ValueError('exact authenticated source publication required')
    artifacts = json.loads(publication.run(['api', 'repos/' + publication.REPOSITORY
        + '/actions/runs/' + run_id + '/artifacts']))['artifacts']
    name = 'patched-publication-' + run_id + '-' + str(selected['publication_attempt'])
    matching = [a for a in artifacts if a['name'] == name]
    if (len(matching) != 1 or matching[0]['expired']
            or not 0 < matching[0]['size_in_bytes'] <= 32 * 1024 * 1024
            or directory.exists() or directory.is_symlink()):
        raise ValueError('new bounded source publication download required')
    publication.run(['run', 'download', run_id, '--repo', publication.REPOSITORY,
                     '--name', name, '--dir', str(directory)])
    refresh_scans(directory)
    upgrade.validate_publication(directory, datetime.now(timezone.utc))
    from render_eso_commissioning import validate_publication as validate_eso
    validate_eso(directory)
    components = (*upgrade.COMPONENTS, 'eso')
    result = {'schema_version': 1, 'source_publication_run': run_id,
        'source_publication_attempt': selected['publication_attempt'],
        'observed_at': datetime.now(timezone.utc).isoformat(), 'rescan': 'Passed',
        'images': {c: upgrade.build.load(directory / 'publication.json')['images'][c] for c in components},
        'sha256': {c + '/' + name: hashlib.sha256((directory / c / name).read_bytes()).hexdigest()
            for c in components for name in ('syft.json', 'cyclonedx.json', 'grype.json', 'database.json')}}
    (directory / 'rescan.json').write_text(json.dumps(result, sort_keys=True) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        download(args.output)
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError):
        parser.exit(1, 'MESH_SOURCE_PUBLICATION=Failed; no deployment\n')
    print('MESH_SOURCE_PUBLICATION=Passed; native staging and target remain Not verified')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
