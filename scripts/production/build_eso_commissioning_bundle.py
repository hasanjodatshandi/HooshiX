"""Build an outside-Git public ESO bundle from authenticated, exact-main staging."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import tempfile
from pathlib import Path

import build_platform_commissioning_bundle as original
import render_eso_commissioning as eso
import verify_mesh_security_publication as publication

SOURCES = (*original.SOURCES, 'upgrade_mesh_security.py', 'install_eso.py')
WRAPPER = 'run-eso-commissioning.ps1'


def build(output, staging_run, chart, helm):
    root = eso.build.ROOT
    if (original.git('status', '--porcelain') or output.exists() or output.is_symlink()
            or output.resolve().is_relative_to(root)):
        raise ValueError('clean main and a new outside-repository destination required')
    revision = original.git('rev-parse', 'HEAD')
    if revision != original.git('ls-remote', 'origin', 'refs/heads/main').split()[0]:
        raise ValueError('exact reviewed main required')
    with tempfile.TemporaryDirectory(prefix='hooshix-eso-public-') as temporary:
        directory = Path(temporary)
        receipt = original.staged_receipt(staging_run, directory / 'staging', revision,
                                         mesh_security_upgrade=True, eso_commissioning=True)
        publication.download(directory / 'publication')
        eso.validate_publication(directory / 'publication')
        plan = eso.render(chart, helm)
        plan['staging'] = {'run_id': staging_run, 'source_revision': revision,
                           'observed_at': receipt['observed_at'], 'result': 'Passed'}
        encoded = (json.dumps(plan, sort_keys=True) + '\n').encode()
        if len(encoded) > 8 * 1024 * 1024:
            raise ValueError('bounded public plan required')
        output.mkdir(mode=0o700)
        (output / 'plan.json').write_bytes(encoded)
        for name in (*SOURCES, WRAPPER):
            shutil.copyfile(root / 'scripts/production' / name, output / name)
        manifest = {'schema_version': 1, 'source_revision': revision, 'staging_run': staging_run,
            'files': {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in output.iterdir()}}
        (output / 'bundle.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--staging-run', required=True)
    parser.add_argument('--chart', type=Path, required=True)
    parser.add_argument('--helm', required=True)
    args = parser.parse_args()
    try:
        build(args.output, args.staging_run, args.chart, args.helm)
    except (ValueError, KeyError, TypeError, OSError):
        parser.exit(1, 'ESO_BUNDLE=Failed; no target changes\n')
    print('ESO_BUNDLE=Passed; owner-local sudo required; target Not verified')
    print('PUBLIC_BUNDLE=' + str(args.output))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
