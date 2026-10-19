"""Exact-main owner bundle gated by authenticated native Redis/runtime evidence."""
import argparse
import hashlib
import json
import shutil
import tempfile
from pathlib import Path

import build_redis_secrets_bundle as previous
import build_postgresql_quarantine_bundle as quarantine
import render_redis_runtime as renderer
import verify_published_redis as publication

SOURCES = (*previous.SOURCES, 'install_redis_runtime.py')
WRAPPER = 'run-redis-runtime.ps1'
CHECKS = ('redis_runtime', 'tls_health', 'service_account_scope', 'idempotent_reconciliation',
          'pod_contract_positive_negative', 'source_identity_positive_negative',
          'tls_acl_plaintext_denials', 'aof_process_restart', 'container_log_privacy', 'application_network_denied')


def verify_proof(receipt):
    previous.verify_proof(receipt, tls=True)
    quarantine.verify_proof(receipt)
    proof = receipt.get('redis_runtime', {})
    if (proof.get('schema_version') != 1 or proof.get('image') != renderer.pin()['image']
        or any(proof.get(k) != 'Passed' for k in CHECKS)
        or proof.get('guard') != 'Not run' or proof.get('existing_database_openbao_eso_credentials') != 'Preserved'
        or proof.get('application_ingress') != 'CLOSED' or proof.get('public_access') != 'CLOSED'
        or proof.get('production_readiness') != 'Not verified'
        or proof.get('automatic_renewal') != 'Not run' or proof.get('provider_backup') != 'Not run'
        or proof.get('capacity') != 'Not verified'
        or not all(proof.get(k) for k in ('statefulset_uid', 'pvc_uid', 'volume_uid'))):
        raise ValueError('EXACT_CLOSED_REDIS_NATIVE_EVIDENCE_REQUIRED')


def build(output, run_id, chart, helm):
    staging = previous.pg.staging
    root, revision = staging.mesh.ROOT, staging.git('rev-parse', 'HEAD')
    if (staging.git('status', '--porcelain') or output.exists() or output.is_symlink()
        or output.resolve().is_relative_to(root)
        or revision != staging.git('ls-remote', 'origin', 'refs/heads/main').split()[0]):
        raise ValueError('CLEAN_EXACT_MAIN_AND_NEW_PUBLIC_OUTPUT_REQUIRED')
    with tempfile.TemporaryDirectory(prefix='hooshix-redis-runtime-public-') as temporary:
        receipt = staging.staged_receipt(run_id, Path(temporary) / 'staging', revision,
            mesh_security_upgrade=True, eso_commissioning=True, cert_manager_commissioning=True,
            cnpg_control_plane=True, barman_control_plane=True)
        verify_proof(receipt)
        publication.verify(Path(temporary) / 'acceptance')
        from render_postgresql_quarantine import render
        old = render(previous.pg.renderer.render(chart, helm, ['10.43.0.1'])['boundary'], ['10.43.0.1'])['boundary']
        plan = renderer.render(old)
        plan['staging'] = {'source_revision': revision, 'run_id': run_id,
            'observed_at': receipt['observed_at'], 'result': 'Passed'}
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
    p.add_argument('--chart', type=Path, required=True)
    p.add_argument('--helm', required=True)
    a = p.parse_args()
    try: build(a.output, a.staging_run, a.chart, a.helm)
    except (OSError, ValueError, KeyError, TypeError):
        p.exit(1, 'REDIS_RUNTIME_BUNDLE=Failed; no target changes\n')
    print('REDIS_RUNTIME_BUNDLE=Passed; public owner bundle only; target Not run')
    print('PUBLIC_BUNDLE=' + str(a.output))


if __name__ == '__main__': main()
