"""Build a public owner bundle from exact-main signed runtime staging evidence."""
import argparse
import hashlib
import json
import shutil
import tempfile
from pathlib import Path

import build_kafka_tls_bundle as previous
import build_platform_commissioning_bundle as staging
import render_kafka_runtime as renderer
import render_postgresql_quarantine as quarantine
import render_postgresql_secrets as postgresql
import render_redis_runtime as redis

SOURCES = (*previous.SOURCES, 'install_kafka_runtime.py')
WRAPPER = 'run-kafka-runtime.ps1'
CHECKS = ('kafka_runtime', 'mutual_tls_health', 'single_node_quorum', 'service_account_scope',
          'idempotent_reconciliation', 'pod_contract_positive_negative', 'source_identity_positive_negative',
          'retained_volume_process_restart', 'network_and_mesh_policy')


def verify_proof(receipt):
    previous.verify_proof(receipt)
    proof = receipt.get('kafka_closed_runtime', {})
    if (proof.get('schema_version') != 1 or proof.get('image') != renderer.pin()['image']
        or any(proof.get(key) != 'Passed' for key in CHECKS)
        or proof.get('business_topics') != 'None' or proof.get('storage_guard') != 'Not run'
        or proof.get('host_storage_guard') != 'Not run; disposable local path'
        or proof.get('existing_database_redis_openbao_eso_tls') != 'Preserved'
        or proof.get('application_ingress') != 'CLOSED' or proof.get('public_access') != 'CLOSED'
        or any(proof.get(key) != 'Not run' for key in ('automatic_renewal', 'provider_backup', 'root_revocation'))
        or proof.get('capacity') != 'Not verified' or proof.get('production_readiness') != 'Not verified'
        or not all(proof.get(key) for key in ('statefulset_uid', 'pvc_uid', 'volume_uid',
                                               'cluster_identity_sha256'))):
        raise ValueError('EXACT_CLOSED_KAFKA_NATIVE_RUNTIME_EVIDENCE_REQUIRED')


def build(output, run_id, chart, helm):
    root, revision = staging.mesh.ROOT, staging.git('rev-parse', 'HEAD')
    if (staging.git('status', '--porcelain') or output.exists() or output.is_symlink()
        or output.resolve().is_relative_to(root)
        or revision != staging.git('ls-remote', 'origin', 'refs/heads/main').split()[0]):
        raise ValueError('CLEAN_EXACT_MAIN_AND_NEW_PUBLIC_OUTPUT_REQUIRED')
    with tempfile.TemporaryDirectory(prefix='hooshix-kafka-runtime-public-') as temporary:
        receipt = staging.staged_receipt(run_id, Path(temporary) / 'staging', revision,
            mesh_security_upgrade=True, eso_commissioning=True, cert_manager_commissioning=True,
            cnpg_control_plane=True, barman_control_plane=True, kafka_closed_runtime=True)
        verify_proof(receipt)
        base = postgresql.render(chart, helm, ['10.43.0.1'])['boundary']
        base = quarantine.render(base, ['10.43.0.1'])['boundary']
        base = redis.boundary(base, redis.pod())
        plan = renderer.render(base)
        plan['repository_source_revision'] = revision
        plan['staging'] = {'source_revision': revision, 'run_id': run_id,
            'observed_at': receipt['observed_at'], 'result': 'Passed'}
        output.mkdir(mode=0o700)
        (output / 'plan.json').write_text(json.dumps(plan, sort_keys=True) + '\n')
        for name in (*SOURCES, WRAPPER):
            shutil.copyfile(root / 'scripts/production' / name, output / name)
        result = {'schema_version': 1, 'source_revision': revision, 'staging_run': run_id,
            'files': {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in output.iterdir()}}
        (output / 'bundle.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--staging-run', required=True)
    parser.add_argument('--chart', type=Path, required=True)
    parser.add_argument('--helm', required=True)
    args = parser.parse_args()
    try:
        build(args.output, args.staging_run, args.chart, args.helm)
    except (OSError, ValueError, KeyError, TypeError):
        parser.exit(1, 'KAFKA_RUNTIME_BUNDLE=Failed; no target changes\n')
    print('KAFKA_RUNTIME_BUNDLE=Passed; public owner bundle only; target Not run')
    print('PUBLIC_BUNDLE=' + str(args.output))


if __name__ == '__main__':
    main()
