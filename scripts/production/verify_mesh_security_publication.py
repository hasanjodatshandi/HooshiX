"""Download the exact protected-main source publication for limited mesh staging."""
from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

import render_mesh_security_upgrade as upgrade
import verify_platform_publication_run as publication


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
    upgrade.validate_publication(directory, datetime.now(timezone.utc))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        download(args.output)
    except (ValueError, KeyError, TypeError, OSError):
        parser.exit(1, 'MESH_SOURCE_PUBLICATION=Failed; no deployment\n')
    print('MESH_SOURCE_PUBLICATION=Passed; native staging and target remain Not verified')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
