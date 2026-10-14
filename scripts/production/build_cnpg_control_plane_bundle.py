"""Public owner-local controller bundle from exact-main authenticated native evidence."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import tempfile
from pathlib import Path

import build_cert_manager_commissioning_bundle as certificates
import build_platform_commissioning_bundle as original
import render_cnpg_control_plane as renderer
from verify_published_postgresql import verify

SOURCES = (*certificates.SOURCES, 'install_cnpg_control_plane.py')
WRAPPER = 'run-cnpg-control-plane.ps1'


def build(output, run_id, manifest, cert_manifest, evidence):
    root = original.mesh.ROOT
    if (original.git('status', '--porcelain') or output.exists() or output.is_symlink()
            or output.resolve().is_relative_to(root)):
        raise ValueError('clean reviewed main and new outside-Git directory required')
    revision = original.git('rev-parse', 'HEAD')
    if revision != original.git('ls-remote', 'origin', 'refs/heads/main').split()[0]:
        raise ValueError('exact reviewed main required')
    with tempfile.TemporaryDirectory(prefix='hooshix-cnpg-control-plane-public-') as temporary:
        directory = Path(temporary)
        receipt = original.staged_receipt(run_id, directory / 'staging', revision,
            mesh_security_upgrade=True, eso_commissioning=True, cert_manager_commissioning=True, cnpg_control_plane=True)
        verify(evidence, directory / 'acceptance')
        plan = renderer.render(manifest, cert_manifest, ['10.43.0.1'])
        plan['staging'] = {'run_id': run_id, 'source_revision': revision,
            'observed_at': receipt['observed_at'], 'result': 'Passed'}
        content = (json.dumps(plan, sort_keys=True) + '\n').encode()
        if len(content) > 8 * 1024 * 1024: raise ValueError('bounded public CNPG plan required')
        output.mkdir(mode=0o700)
        (output / 'plan.json').write_bytes(content)
        for name in (*SOURCES, WRAPPER): shutil.copyfile(root / 'scripts/production' / name, output / name)
        result = {'schema_version': 1, 'source_revision': revision, 'staging_run': run_id,
            'files': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in output.iterdir()}}
        (output / 'bundle.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--staging-run', required=True)
    p.add_argument('--manifest', type=Path, required=True)
    p.add_argument('--cert-manager-manifest', type=Path, required=True)
    p.add_argument('--evidence-dir', type=Path, required=True)
    a = p.parse_args()
    try: build(a.output, a.staging_run, a.manifest, a.cert_manager_manifest, a.evidence_dir)
    except (ValueError, KeyError, TypeError, OSError, RuntimeError, subprocess.SubprocessError):
        p.exit(1, 'CNPG_CONTROL_PLANE_BUNDLE=Failed; no target changes\n')
    print('CNPG_CONTROL_PLANE_BUNDLE=Passed; local sudo required; database Not run')
    print('PUBLIC_BUNDLE=' + str(a.output))


if __name__ == '__main__':
    main()
