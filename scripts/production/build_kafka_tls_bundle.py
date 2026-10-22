"""Exact-main public Kafka leaf bundle gated by authenticated native TLS/ESO evidence."""
import argparse
import hashlib
import json
import shutil
import tempfile
from pathlib import Path

import build_redis_runtime_bundle as previous
import install_kafka_tls as installer

SOURCES = (*previous.SOURCES, 'install_kafka_local_volume.py', 'kafka_secret_delivery.py',
           'kafka_tls_material.py', 'install_kafka_tls.py', 'bootstrap_kafka_tls.py')
CHECKS = ('tls_delivery', 'server_client_chain_key_hostname', 'scoped_authentication',
          'idempotent_reconciliation', 'existing_identity_tls_denials', 'container_log_privacy',
          'bound_database_redis_preservation')


def verify_proof(receipt):
    previous.verify_proof(receipt)
    value = receipt.get('kafka_tls_delivery', {})
    if (value.get('schema_version') != 1 or value.get('namespace') != 'platform-data'
        or value.get('tls_lifetime_days') != 30 or value.get('public_access') != 'CLOSED'
        or any(value.get(k) != 'Passed' for k in CHECKS)
        or value.get('existing_state') != 'Preserved' or value.get('existing_identities') != 'Preserved'
        or any(value.get(k) != 'Not run' for k in ('kafka', 'automatic_renewal', 'root_revocation',
                                                  'host_guard', 'provider_backup'))
        or value.get('production_readiness') != 'Not verified'):
        raise ValueError('EXACT_NATIVE_KAFKA_TLS_EVIDENCE_REQUIRED')


def build(output, run_id, chart, helm):
    staging = previous.previous.pg.staging
    root, revision = staging.mesh.ROOT, staging.git('rev-parse', 'HEAD')
    if (staging.git('status', '--porcelain') or output.exists() or output.is_symlink()
        or output.resolve().is_relative_to(root)
        or revision != staging.git('ls-remote', 'origin', 'refs/heads/main').split()[0]):
        raise ValueError('CLEAN_EXACT_MAIN_AND_NEW_PUBLIC_OUTPUT_REQUIRED')
    with tempfile.TemporaryDirectory(prefix='hooshix-kafka-tls-public-') as temporary:
        receipt = staging.staged_receipt(run_id, Path(temporary) / 'staging', revision,
            mesh_security_upgrade=True, eso_commissioning=True, cert_manager_commissioning=True,
            cnpg_control_plane=True, barman_control_plane=True)
        verify_proof(receipt)
        plan = installer.render(previous.previous.pg.renderer.render(chart, helm, ['10.43.0.1']))
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
        p.exit(1, 'KAFKA_TLS_BUNDLE=Failed; no target changes\n')
    print('KAFKA_TLS_BUNDLE=Passed; public owner bundle only; target Not run')
    print('PUBLIC_BUNDLE=' + str(a.output))


if __name__ == '__main__': main()
