"""Public namespace-scoped credential-delivery bundle, gated by exact-tree native evidence."""
import argparse
import hashlib
import json
import shutil
import tempfile
from pathlib import Path

import activate_openbao_operator as operator
import build_platform_commissioning_bundle as staging
import render_eso_commissioning as eso
import render_postgresql_secrets as renderer
import verify_mesh_security_publication as publication

SOURCES = tuple(dict.fromkeys((*staging.SOURCES, *operator.SOURCES,
    'upgrade_mesh_security.py', 'install_eso.py', 'install_cert_manager.py', 'install_cnpg_control_plane.py',
    'install_postgresql_local_volume.py', 'install_barman_control_plane.py', 'openbao_scoped_auth.py',
    'bootstrap_openbao_auth.py', 'render_postgresql_ownership.py', 'postgresql_secret_delivery.py',
    'install_postgresql_secrets.py', 'bootstrap_postgresql_secrets.py')))
CHECKS = ('delivery', 'scoped_authentication', 'foreign_secret_and_tokenrequest_denial',
          'idempotent_reconciliation', 'source_identity_positive_negative', 'database_admission_closed',
          'container_log_privacy', 'existing_application_identities_preserved')


def verify_proof(receipt):
    p = receipt.get('postgresql_secret_delivery', {})
    if (p.get('schema_version') != 1 or p.get('namespace') != 'platform-data' or p.get('credential_count') != 10
        or p.get('image') != eso.published_pin()['image'] or p.get('public_access') != 'CLOSED'
        or p.get('root_mesh_openbao_application_eso_cnpg_barman_storage') != 'Preserved'
        or any(p.get(k) != 'Passed' for k in CHECKS)
        or any(p.get(k) != 'Not run' for k in ('database', 'provider_backup', 'root_revocation'))):
        raise ValueError('exact signed namespace-isolated PostgreSQL secret delivery evidence required')


def build(output, run_id, chart, helm):
    root = staging.mesh.ROOT
    revision = staging.git('rev-parse', 'HEAD')
    if (staging.git('status', '--porcelain') or output.exists() or output.is_symlink()
        or output.resolve().is_relative_to(root)
        or revision != staging.git('ls-remote', 'origin', 'refs/heads/main').split()[0]):
        raise ValueError('clean exact main and new outside-Git output required')
    with tempfile.TemporaryDirectory(prefix='hooshix-pg-secrets-public-') as temp:
        directory = Path(temp)
        receipt = staging.staged_receipt(run_id, directory / 'staging', revision, mesh_security_upgrade=True,
            eso_commissioning=True, cert_manager_commissioning=True, cnpg_control_plane=True, barman_control_plane=True)
        verify_proof(receipt)
        publication.download(directory / 'publication')
        eso.validate_publication(directory / 'publication')
        plan = renderer.render(chart, helm, ['10.43.0.1'])
        plan['staging'] = {'source_revision': revision, 'run_id': run_id,
            'observed_at': receipt['observed_at'], 'result': 'Passed'}
        output.mkdir(mode=0o700)
        (output / 'plan.json').write_text(json.dumps(plan, sort_keys=True) + '\n')
        for name in SOURCES: shutil.copyfile(root / 'scripts/production' / name, output / name)
        result = {'schema_version': 1, 'source_revision': revision, 'staging_run': run_id,
            'files': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in output.iterdir()}}
        (output / 'bundle.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--staging-run', required=True)
    p.add_argument('--chart', type=Path, required=True)
    p.add_argument('--helm', required=True)
    a = p.parse_args()
    try: build(a.output, a.staging_run, a.chart, a.helm)
    except (OSError, ValueError, KeyError, TypeError):
        p.exit(1, 'PG_SECRETS_BUNDLE=Failed; no target changes\n')
    print('PG_SECRETS_BUNDLE=Passed; public bundle only; target Not verified')
    print('PUBLIC_BUNDLE=' + str(a.output))


if __name__ == '__main__': main()
