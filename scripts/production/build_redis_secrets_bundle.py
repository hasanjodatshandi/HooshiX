"""Public Redis ACL delivery bundle bound to exact reviewed-main native evidence."""
import argparse
import hashlib
import json
import shutil
import tempfile
from pathlib import Path

import build_postgresql_secrets_bundle as pg
import install_redis_secrets as installer

SOURCES = (*pg.SOURCES, 'install_postgresql_quarantine.py', 'install_redis_local_volume.py',
           'render_redis_acl.py', 'redis_secret_delivery.py',
           'install_redis_secrets.py', 'bootstrap_redis_secrets.py', 'redis_tls_material.py',
           'install_redis_tls.py', 'bootstrap_redis_tls.py')
CHECKS = ('delivery', 'acl_binding', 'scoped_authentication', 'foreign_secret_and_tokenrequest_denial',
          'idempotent_reconciliation', 'existing_identities_preserved', 'container_log_privacy',
          'bound_database_preservation')


def verify_proof(receipt, *, tls=False):
    value = receipt.get('redis_secret_delivery', {})
    if (value.get('schema_version') != 1 or value.get('namespace') != 'platform-data'
        or value.get('credential_count') != 5 or value.get('public_access') != 'CLOSED'
        or value.get('existing_data_controller_policy_storage') != 'Preserved'
        or any(value.get(k) != 'Passed' for k in CHECKS)
        or any(value.get(k) != 'Not run' for k in ('redis', 'tls', 'database', 'provider_backup', 'root_revocation'))):
        raise ValueError('exact namespace-isolated Redis ACL delivery evidence required')
    if tls:
        value = receipt.get('redis_tls_delivery', {})
        if (value.get('schema_version') != 1 or value.get('namespace') != 'platform-data'
            or value.get('public_access') != 'CLOSED' or value.get('tls_lifetime_days') != 30
            or value.get('existing_credentials_acl_controller_policy_storage') != 'Preserved'
            or any(value.get(k) != 'Passed' for k in ('tls_delivery', 'server_chain_key_hostname',
                'scoped_authentication', 'idempotent_reconciliation', 'container_log_privacy',
                'existing_identity_tls_denials', 'bound_database_preservation'))
            or any(value.get(k) != 'Not run' for k in ('redis', 'database', 'automatic_renewal',
                                                       'provider_backup', 'root_revocation'))):
            raise ValueError('exact isolated Redis TLS delivery evidence required')


def build(output, run_id, chart, helm, *, tls=False):
    staging = pg.staging
    root = staging.mesh.ROOT
    revision = staging.git('rev-parse', 'HEAD')
    if (staging.git('status', '--porcelain') or output.exists() or output.is_symlink()
        or output.resolve().is_relative_to(root)
        or revision != staging.git('ls-remote', 'origin', 'refs/heads/main').split()[0]):
        raise ValueError('clean exact main and new outside-Git output required')
    with tempfile.TemporaryDirectory(prefix='hooshix-redis-secrets-public-') as temp:
        receipt = staging.staged_receipt(run_id, Path(temp) / 'staging', revision, mesh_security_upgrade=True,
            eso_commissioning=True, cert_manager_commissioning=True, cnpg_control_plane=True,
            barman_control_plane=True)
        verify_proof(receipt, tls=tls)
        pg.publication.download(Path(temp) / 'publication')
        pg.eso.validate_publication(Path(temp) / 'publication')
        if tls:
            from install_redis_tls import render
        else: render = installer.render
        plan = render(pg.renderer.render(chart, helm, ['10.43.0.1']))
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
    p.add_argument('--tls', action='store_true', help='Deliver only the first Redis server leaf, not Redis itself')
    a = p.parse_args()
    try: build(a.output, a.staging_run, a.chart, a.helm, tls=a.tls)
    except (OSError, ValueError, KeyError, TypeError):
        p.exit(1, 'REDIS_SECRETS_BUNDLE=Failed; no target changes\n')
    print('REDIS_SECRETS_BUNDLE=Passed; public bundle only; target Not verified')
    print('PUBLIC_BUNDLE=' + str(a.output))


if __name__ == '__main__': main()
