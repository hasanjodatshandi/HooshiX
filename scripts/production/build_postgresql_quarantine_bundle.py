"""Exact-main public quarantine bundle; no provider retries or target execution."""
import argparse
import hashlib
import json
import shutil
import tempfile
from pathlib import Path

import build_barman_control_plane_bundle as barman
import build_platform_commissioning_bundle as staging
import build_postgresql_secrets_bundle as secrets
import build_postgresql_storage_bundle as storage
import render_postgresql_quarantine as renderer
import render_postgresql_secrets as delivery
from verify_published_postgresql import candidate, verify

SOURCES = (*barman.SOURCES[:-1], 'install_postgresql_quarantine.py')
WRAPPER = 'run-postgresql-quarantine.ps1'
CHECKS = ('postgresql_quarantine', 'empty_database', 'tls_and_sql_denial',
          'service_account_scope',
          'idempotent_reconciliation', 'pod_contract_positive_negative',
          'source_identity_positive_negative', 'service_sql_network_denied')


def verify_proof(receipt):
    secrets.verify_proof(receipt)
    proof = receipt.get('postgresql_quarantine', {})
    expected = {k: candidate()['publication']['images'][k] for k in ('operator', 'postgresql')}
    if (proof.get('images') != expected or any(proof.get(k) != 'Passed' for k in CHECKS)
        or proof.get('root_mesh_openbao_eso_cnpg_barman') != 'Preserved'
        or proof.get('application_ingress') != 'CLOSED' or proof.get('production_readiness') != 'Not verified'
        or proof.get('guard') != 'Not run'
        or any(proof.get(k) != 'Not run' for k in ('provider_backup', 'root_revocation', 'application_migrations'))
        or not all(proof.get(k) for k in ('cluster_uid', 'pvc_uid', 'volume_uid'))):
        raise ValueError('exact signed empty quarantine and independent negative evidence required')


def build(output, run_id, storage_run, chart, helm, evidence):
    root = staging.mesh.ROOT
    revision = staging.git('rev-parse', 'HEAD')
    if (staging.git('status', '--porcelain') or output.exists() or output.is_symlink()
        or output.resolve().is_relative_to(root)
        or revision != staging.git('ls-remote', 'origin', 'refs/heads/main').split()[0]):
        raise ValueError('clean exact reviewed main and new outside-Git output required')
    with tempfile.TemporaryDirectory(prefix='hooshix-pg-quarantine-public-') as temp:
        receipt = staging.staged_receipt(run_id, Path(temp) / 'staging', revision, mesh_security_upgrade=True,
            eso_commissioning=True, cert_manager_commissioning=True, cnpg_control_plane=True, barman_control_plane=True)
        verify_proof(receipt)
        storage.build(Path(temp) / 'storage', storage_run)
        verify(evidence, Path(temp) / 'acceptance')
        plan = renderer.render(delivery.render(chart, helm, ['10.43.0.1'])['boundary'], ['10.43.0.1'])
        plan['staging'] = {'source_revision': revision, 'run_id': run_id,
            'observed_at': receipt['observed_at'], 'result': 'Passed'}
        plan['storage_native_run'] = storage_run
        output.mkdir(mode=0o700)
        (output / 'plan.json').write_text(json.dumps(plan, sort_keys=True) + '\n')
        for name in (*SOURCES, WRAPPER): shutil.copyfile(root / 'scripts/production' / name, output / name)
        result = {'schema_version': 1, 'source_revision': revision, 'staging_run': run_id,
            'files': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in output.iterdir()}}
        (output / 'bundle.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--staging-run', required=True)
    p.add_argument('--storage-run', required=True)
    p.add_argument('--chart', type=Path, required=True)
    p.add_argument('--helm', required=True)
    p.add_argument('--evidence-dir', type=Path, required=True)
    a = p.parse_args()
    try: build(a.output, a.staging_run, a.storage_run, a.chart, a.helm, a.evidence_dir)
    except (OSError, ValueError, KeyError, TypeError):
        p.exit(1, 'POSTGRESQL_QUARANTINE_BUNDLE=Failed; no target changes\n')
    print('POSTGRESQL_QUARANTINE_BUNDLE=Passed; public bundle only; target Not run')
    print('PUBLIC_BUNDLE=' + str(a.output))


if __name__ == '__main__': main()
