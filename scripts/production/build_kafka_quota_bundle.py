"""Exact-main public quota bundle, gated by preserved signed-stack and quota proof."""
import argparse
import hashlib
import json
import shutil
import tempfile
from pathlib import Path

import build_kafka_topic_acl_bundle as topics
import build_kafka_client_tls_bundle as previous
import kafka_quota_profile as profile

SOURCES = (*topics.SOURCES, 'kafka_quota_profile.py', 'kafka_quota_commissioning.py', 'install_kafka_quotas.py')


def verify_proof(receipt):
    topics.verify_proof(receipt)
    proof = receipt.get('kafka_client_tls_delivery', {}).get('kafka_quotas', {})
    if (proof.get('schema_version') != 1 or proof.get('kafka_quotas') != 'Passed'
        or proof.get('credential_count') != 5 or proof.get('producer_byte_rate') != 65536
        or proof.get('consumer_byte_rate') != 262144
        or any(proof.get(k) != 'Passed' for k in ('idempotent_reconciliation', 'partial_recovery'))
        or proof.get('existing_state') != 'Preserved'
        or proof.get('application_ingress') != proof.get('public_access') or proof.get('public_access') != 'CLOSED'
        or proof.get('capacity') != proof.get('production_readiness') or proof.get('capacity') != 'Not verified'):
        raise ValueError('KAFKA_QUOTA_EXACT_NATIVE_EVIDENCE_REQUIRED')


def build(output, run_id, chart, helm, retained_database_quarantine=False):
    staging = previous.previous.staging
    revision = staging.git('rev-parse', 'HEAD')
    if (staging.git('status', '--porcelain') or output.exists() or output.is_symlink()
        or output.resolve().is_relative_to(staging.mesh.ROOT)
        or revision != staging.git('ls-remote', 'origin', 'refs/heads/main').split()[0]):
        raise ValueError('CLEAN_EXACT_MAIN_AND_NEW_PUBLIC_OUTPUT_REQUIRED')
    with tempfile.TemporaryDirectory(prefix='hooshix-kafka-quota-public-') as temporary:
        receipt = staging.staged_receipt(run_id, Path(temporary) / 'staging', revision,
            mesh_security_upgrade=True, eso_commissioning=True, cert_manager_commissioning=True,
            cnpg_control_plane=True, barman_control_plane=True, kafka_closed_runtime=True)
        verify_proof(receipt)
        runtime = previous.previous.renderer.render(previous.previous.data_boundary(chart, helm, retained_database_quarantine))
        plan = {**previous.plan(runtime), 'kafka_quotas': True,
            'kafka_quota_profile': json.loads(profile.PATH.read_bytes()), 'repository_source_revision': revision,
            'staging': {'source_revision': revision, 'run_id': run_id,
                        'observed_at': receipt['observed_at'], 'result': 'Passed'}}
        output.mkdir(mode=0o700)
        (output / 'plan.json').write_text(json.dumps(plan, sort_keys=True) + '\n')
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
        p.exit(1, 'KAFKA_QUOTAS_BUNDLE=Failed; no target changes\n')
    print('KAFKA_QUOTAS_BUNDLE=Passed; public owner bundle only; target Not run')
    print('PUBLIC_BUNDLE=' + str(a.output))


if __name__ == '__main__':
    main()
