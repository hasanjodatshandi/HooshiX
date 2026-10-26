"""Build exact-main public client delivery tooling only after native signed-stack proof."""
import argparse
import hashlib
import json
import shutil
import tempfile
from pathlib import Path

import build_kafka_runtime_bundle as previous
import kafka_client_tls as contract

SOURCES = (*previous.SOURCES, 'kafka_client_tls.py', 'install_kafka_client_tls.py',
           'bootstrap_kafka_client_tls.py')
CHECKS = ('client_only_identity', 'scoped_authentication', 'broker_client_tls_denial', 'idempotent_reconciliation',
          'existing_identity_denials', 'container_log_privacy', 'broker_and_datastore_preservation')


def verify_proof(receipt):
    previous.verify_proof(receipt)
    proof = receipt.get('kafka_client_tls_delivery', {})
    if (proof.get('schema_version') != 1 or proof.get('credential_count') != 5
        or proof.get('namespace') != contract.NAMESPACE or proof.get('tls_lifetime_days') != 30
        or any(proof.get(k) != 'Passed' for k in CHECKS)
        or proof.get('existing_state') != 'Preserved'
        or proof.get('public_access') != proof.get('application_ingress') or proof.get('public_access') != 'CLOSED'
        or any(proof.get(k) != 'Not run' for k in ('topics_acl', 'automatic_renewal', 'root_revocation'))
        or proof.get('production_readiness') != 'Not verified'):
        raise ValueError('KAFKA_CLIENT_EXACT_NATIVE_PROOF_REQUIRED')


def plan(runtime):
    return {'schema_version': 1, 'installation_id': 'hooshix-production', 'database': 'Not run',
        'kafka_client_tls': True, 'public_ca_sha256': runtime['public_ca_sha256'],
        'application_ingress': 'CLOSED', 'public_access': 'CLOSED',
        'kafka_runtime': {key: runtime[key] for key in ('statefulset', 'objects', 'boundary', 'source_policies')}}


def build(output, run_id, chart, helm, retained_database_quarantine=False):
    staging = previous.staging
    revision = staging.git('rev-parse', 'HEAD')
    if (staging.git('status', '--porcelain') or output.exists() or output.is_symlink()
        or output.resolve().is_relative_to(staging.mesh.ROOT)
        or revision != staging.git('ls-remote', 'origin', 'refs/heads/main').split()[0]):
        raise ValueError('CLEAN_EXACT_MAIN_AND_NEW_PUBLIC_OUTPUT_REQUIRED')
    with tempfile.TemporaryDirectory(prefix='hooshix-kafka-client-public-') as temporary:
        receipt = staging.staged_receipt(run_id, Path(temporary) / 'staging', revision,
            mesh_security_upgrade=True, eso_commissioning=True, cert_manager_commissioning=True,
            cnpg_control_plane=True, barman_control_plane=True, kafka_closed_runtime=True)
        verify_proof(receipt)
        runtime = previous.renderer.render(previous.data_boundary(chart, helm, retained_database_quarantine))
        result = plan(runtime)
        result['staging'] = {'source_revision': revision, 'run_id': run_id,
                            'observed_at': receipt['observed_at'], 'result': 'Passed'}
        output.mkdir(mode=0o700)
        (output / 'plan.json').write_text(json.dumps(result, sort_keys=True) + '\n')
        for name in SOURCES:
            shutil.copyfile(staging.mesh.ROOT / 'scripts/production' / name, output / name)
        manifest = {'schema_version': 1, 'source_revision': revision, 'staging_run': run_id,
            'files': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in output.iterdir()}}
        (output / 'bundle.json').write_text(json.dumps(manifest, indent=2) + '\n')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--staging-run', required=True)
    p.add_argument('--chart', type=Path, required=True)
    p.add_argument('--helm', required=True)
    p.add_argument('--retained-database-quarantine', action='store_true')
    a = p.parse_args()
    try:
        build(a.output, a.staging_run, a.chart, a.helm, a.retained_database_quarantine)
    except (OSError, ValueError, KeyError, TypeError):
        p.exit(1, 'KAFKA_CLIENT_TLS_BUNDLE=Failed; no target changes\n')
    print('KAFKA_CLIENT_TLS_BUNDLE=Passed; public owner bundle only; target Not run')
    print('PUBLIC_BUNDLE=' + str(a.output))


if __name__ == '__main__':
    main()
