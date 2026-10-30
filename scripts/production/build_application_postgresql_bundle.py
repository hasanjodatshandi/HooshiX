"""Public projection-only bundle bound to reviewed main and exact-tree native proof."""
import argparse
import hashlib
import json
import shutil
import tempfile
from pathlib import Path

import build_postgresql_secrets_bundle as pg
import build_platform_commissioning_bundle as staging
import activate_openbao_host as host
from verify_eso_artifact import published_pin
from kafka_quota_commissioning import RETAINED_ESO

SOURCES = tuple(dict.fromkeys((*pg.SOURCES, 'application_postgresql_delivery.py',
    'install_application_postgresql_delivery.py', 'bootstrap_application_postgresql_delivery.py')))
CHECKS = ('application_postgresql_delivery', 'scoped_authentication',
          'idempotent_reconciliation', 'container_log_privacy')


def verify_proof(receipt):
    proof = receipt.get('application_postgresql_delivery', {})
    if (proof.get('schema_version') != 1 or proof.get('namespace') != 'platform-apps'
        or proof.get('existing_eso_image') != published_pin()['image']
        or proof.get('credential_count') != 10 or any(proof.get(k) != 'Passed' for k in CHECKS)
        or proof.get('existing_state') != 'Preserved'
        or proof.get('application_ingress') != 'CLOSED' or proof.get('public_access') != 'CLOSED'
        or proof.get('database') != 'Not run' or proof.get('kv_writes') != 'Not run'
        or proof.get('root_revocation') != 'Not run' or proof.get('production_readiness') != 'Not verified'):
        raise ValueError('PG_APP_EXACT_NATIVE_EVIDENCE_REQUIRED')


def build(output, run_id, existing_eso_image):
    if existing_eso_image not in (published_pin()["image"], RETAINED_ESO):
        raise ValueError("PG_APP_REVIEWED_EXISTING_ESO_IMAGE_REQUIRED")
    revision = staging.git('rev-parse', 'HEAD')
    if (staging.git('status', '--porcelain') or output.exists() or output.is_symlink()
        or revision != staging.git('ls-remote', 'origin', 'refs/heads/main').split()[0]):
        raise ValueError('PG_APP_CLEAN_EXACT_MAIN_AND_NEW_OUTPUT_REQUIRED')
    with tempfile.TemporaryDirectory(prefix='hooshix-app-pg-public-') as temporary:
        receipt = staging.staged_receipt(run_id, Path(temporary), revision,
            mesh_security_upgrade=True, eso_commissioning=True, cert_manager_commissioning=True,
            cnpg_control_plane=True, barman_control_plane=True, kafka_closed_runtime=True)
        verify_proof(receipt)
        plan = {'schema_version': 1, 'installation_id': 'hooshix-production',
            'application_postgresql_delivery': True, 'database': 'Not run',
            'application_ingress': 'CLOSED', 'public_access': 'CLOSED',
            'public_ca_sha256': host.custody.ROOT_SHA256,
            'existing_eso_image': existing_eso_image,
            'staging': {'source_revision': revision, 'run_id': run_id,
                        'observed_at': receipt['observed_at'], 'result': 'Passed'}}
        output.mkdir(mode=0o700)
        (output / 'plan.json').write_text(json.dumps(plan, sort_keys=True) + '\n')
        for name in SOURCES:
            shutil.copyfile(staging.mesh.ROOT / 'scripts/production' / name, output / name)
        manifest = {'schema_version': 1, 'source_revision': revision, 'staging_run': run_id,
            'files': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in output.iterdir()}}
        (output / 'bundle.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--staging-run', required=True)
    parser.add_argument('--existing-eso-image', required=True,
                        choices=(published_pin()['image'], RETAINED_ESO))
    args = parser.parse_args()
    build(args.output, args.staging_run, args.existing_eso_image)
    print('APPLICATION_POSTGRESQL_BUNDLE=Passed; target Not run')


if __name__ == '__main__':
    main()
