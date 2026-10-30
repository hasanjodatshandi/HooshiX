"""Public Redis projection bundle bound to exact reviewed native staging."""
import argparse
import hashlib
import json
import shutil
import tempfile
from pathlib import Path

import build_application_postgresql_bundle as pg

SOURCES = tuple(dict.fromkeys((*pg.SOURCES, 'render_redis_acl.py', 'redis_secret_delivery.py',
    'application_redis_delivery.py', 'install_application_redis_delivery.py',
    'bootstrap_application_redis_delivery.py')))
CHECKS = ('application_redis_delivery', 'scoped_authentication', 'idempotent_reconciliation', 'container_log_privacy')


def verify_proof(receipt):
    proof = receipt.get('application_redis_delivery', {})
    if (proof.get('schema_version') != 1 or proof.get('namespace') != 'platform-apps'
        or proof.get('existing_eso_image') != pg.published_pin()['image']
        or proof.get('credential_count') != 3 or any(proof.get(k) != 'Passed' for k in CHECKS)
        or proof.get('existing_state') != 'Preserved'
        or proof.get('application_ingress') != 'CLOSED' or proof.get('public_access') != 'CLOSED'
        or proof.get('database') != 'Not run' or proof.get('kv_writes') != 'Not run'
        or proof.get('redis_runtime') != 'Not run' or proof.get('root_revocation') != 'Not run'
        or proof.get('production_readiness') != 'Not verified'):
        raise ValueError('REDIS_APP_EXACT_NATIVE_EVIDENCE_REQUIRED')


def build(output, run_id, existing_eso_image):
    if existing_eso_image not in (pg.published_pin()['image'], pg.RETAINED_ESO):
        raise ValueError('REDIS_APP_REVIEWED_EXISTING_ESO_IMAGE_REQUIRED')
    revision = pg.staging.git('rev-parse', 'HEAD')
    if (pg.staging.git('status', '--porcelain') or output.exists() or output.is_symlink()
        or output.resolve().is_relative_to(pg.staging.mesh.ROOT)
        or revision != pg.staging.git('ls-remote', 'origin', 'refs/heads/main').split()[0]):
        raise ValueError('REDIS_APP_CLEAN_EXACT_MAIN_AND_NEW_OUTPUT_REQUIRED')
    with tempfile.TemporaryDirectory(prefix='hooshix-app-redis-public-') as temporary:
        receipt = pg.staging.staged_receipt(run_id, Path(temporary), revision,
            mesh_security_upgrade=True, eso_commissioning=True, cert_manager_commissioning=True,
            cnpg_control_plane=True, barman_control_plane=True, kafka_closed_runtime=True)
        verify_proof(receipt)
        plan = {'schema_version': 1, 'installation_id': 'hooshix-production',
            'application_redis_delivery': True, 'database': 'Not run', 'application_ingress': 'CLOSED',
            'public_access': 'CLOSED', 'public_ca_sha256': pg.host.custody.ROOT_SHA256,
            'existing_eso_image': existing_eso_image,
            'staging': {'source_revision': revision, 'run_id': run_id,
                        'observed_at': receipt['observed_at'], 'result': 'Passed'}}
        output.mkdir(mode=0o700)
        (output / 'plan.json').write_text(json.dumps(plan, sort_keys=True) + '\n')
        for name in SOURCES:
            shutil.copyfile(pg.staging.mesh.ROOT / 'scripts/production' / name, output / name)
        manifest = {'schema_version': 1, 'source_revision': revision, 'staging_run': run_id,
            'files': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in output.iterdir()}}
        (output / 'bundle.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--staging-run', required=True)
    parser.add_argument('--existing-eso-image', required=True, choices=(pg.published_pin()['image'], pg.RETAINED_ESO))
    args = parser.parse_args()
    build(args.output, args.staging_run, args.existing_eso_image)
    print('APPLICATION_REDIS_BUNDLE=Passed; target Not run')


if __name__ == '__main__':
    main()
