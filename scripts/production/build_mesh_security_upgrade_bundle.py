"""Build an outside-repository public upgrade bundle from exact main staging."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import tempfile
from pathlib import Path

import build_platform_commissioning_bundle as original
import render_mesh_security_upgrade as upgrade
import verify_mesh_security_publication as publication

SOURCES = (*original.SOURCES, 'upgrade_mesh_security.py')
WRAPPER = 'run-mesh-security-upgrade.ps1'


def build(output: Path, staging_run: str) -> dict:
    root = upgrade.build.ROOT
    if (original.git('status', '--porcelain') or output.exists() or output.is_symlink()
            or output.resolve().is_relative_to(root)):
        raise ValueError('clean reviewed checkout and new outside-repository bundle required')
    revision = original.git('rev-parse', 'HEAD')
    remote = original.git('ls-remote', 'origin', 'refs/heads/main').split()[0]
    if revision != remote:
        raise ValueError('exact current reviewed main required')
    with tempfile.TemporaryDirectory(prefix='hooshix-mesh-public-') as temporary:
        directory = Path(temporary)
        receipt = original.staged_receipt(staging_run, directory / 'staging', revision,
                                         mesh_security_upgrade=True)
        publication.download(directory / 'source-publication')
        plan = upgrade.render()
        plan['staging'] = {'run_id': staging_run, 'source_revision': revision,
                           'observed_at': receipt['observed_at'], 'result': 'Passed'}
        output.mkdir(mode=0o700)
        (output / 'plan.json').write_text(json.dumps(plan, sort_keys=True) + '\n')
        for name in (*SOURCES, WRAPPER):
            shutil.copyfile(root / 'scripts/production' / name, output / name)
        files = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in output.iterdir()}
        manifest = {'schema_version': 1, 'source_revision': revision,
                    'staging_run': staging_run, 'files': files}
        (output / 'bundle.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--staging-run', required=True)
    args = parser.parse_args()
    try:
        build(args.output, args.staging_run)
    except (ValueError, KeyError, TypeError, OSError):
        parser.exit(1, 'MESH_UPGRADE_BUNDLE=Failed; no target changes\n')
    print('MESH_UPGRADE_BUNDLE=Passed; owner-local execution required; target Not verified')
    print('PUBLIC_BUNDLE=' + str(args.output))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
