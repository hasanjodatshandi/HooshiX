"""Public supervised cert-manager bundle only from exact-main native CI evidence."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import tempfile
from pathlib import Path

import build_platform_commissioning_bundle as original
import render_cert_manager_candidate as certificates
from verify_published_cert_manager import verify

SOURCES = (*original.SOURCES, 'upgrade_mesh_security.py', 'install_eso.py', 'install_cert_manager.py')
WRAPPER = 'run-cert-manager-commissioning.ps1'


def build(output, run_id, manifest, evidence):
    root = original.mesh.ROOT
    if (original.git('status', '--porcelain') or output.exists() or output.is_symlink()
            or output.resolve().is_relative_to(root)):
        raise ValueError('clean reviewed main and a fresh outside-Git destination required')
    revision = original.git('rev-parse', 'HEAD')
    if revision != original.git('ls-remote', 'origin', 'refs/heads/main').split()[0]:
        raise ValueError('exact reviewed main required')
    with tempfile.TemporaryDirectory(prefix='hooshix-cert-manager-bundle-public-') as temporary:
        directory = Path(temporary)
        receipt = original.staged_receipt(run_id, directory / 'staging', revision,
            mesh_security_upgrade=True, eso_commissioning=True, cert_manager_commissioning=True)
        verify(evidence, directory / 'acceptance')
        plan = certificates.render(manifest, ['10.43.0.1'])
        plan['staging'] = {'run_id': run_id, 'source_revision': revision,
                           'observed_at': receipt['observed_at'], 'result': 'Passed'}
        encoded = (json.dumps(plan, sort_keys=True) + '\n').encode()
        if len(encoded) > 8 * 1024 * 1024:
            raise ValueError('bounded public certificate plan required')
        output.mkdir(mode=0o700)
        (output / 'plan.json').write_bytes(encoded)
        for name in (*SOURCES, WRAPPER):
            shutil.copyfile(root / 'scripts/production' / name, output / name)
        manifest = {'schema_version': 1, 'source_revision': revision, 'staging_run': run_id,
            'files': {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in output.iterdir()}}
        (output / 'bundle.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--staging-run', required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--evidence-dir', type=Path, required=True)
    args = parser.parse_args()
    try:
        build(args.output, args.staging_run, args.manifest, args.evidence_dir)
    except (ValueError, KeyError, TypeError, OSError, RuntimeError, subprocess.SubprocessError):
        parser.exit(1, 'CERT_MANAGER_BUNDLE=Failed; no target changes\n')
    print('CERT_MANAGER_BUNDLE=Passed; owner-local sudo still required; target Not verified')
    print('PUBLIC_BUNDLE=' + str(args.output))


if __name__ == '__main__':
    main()
