"""Build a public operator bundle from reviewed main and authenticated CI evidence."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

import render_mesh_candidate as mesh
import render_openbao_local_storage as storage
import render_platform_admission as admission
import import_intermediate_ca as ca_import
import verify_platform_publication_run as publication
import upgrade_kyverno

SOURCES = ('bootstrap_intermediate_csr.py', 'import_intermediate_ca.py',
           'verify_storage_guard.py', 'upgrade_kyverno.py', 'commission_platform.py')
WORKFLOW = '.github/workflows/platform-commissioning.yml'
CHECKS = frozenset({'calico_runtime', 'kyverno_upgrade', 'admission_audit_negative', 'admission_deny_negative',
                   'signed_mesh_admission_and_runtime', 'admission_wrong_signer',
                   'admission_missing_sbom', 'admission_wrong_provenance_revision',
                   'mtls_positive', 'wrong_serviceaccount', 'plaintext_negative'})
FOUNDATION = frozenset({'server_schema', 'static_local_pv_schema', 'restricted_workload',
                       'tls_mount_fsGroup', 'sealed_probes', 'tls_hostname_negative', 'shamir_3_2',
                       'kv_acl', 'restart', 'pvc_retention', 'pvc_data_persistence',
                       'root_revocation', 'container_log_privacy', 'cleanup'})


def git(*args):
    result = subprocess.run(['git', '-C', str(mesh.ROOT), *args], stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, timeout=30, check=False)
    if result.returncode or len(result.stdout) > 256 * 1024:
        raise ValueError('reviewed source verification failed')
    return result.stdout.decode().strip()


def check_staging(record, receipt, revision):
    if (record.get('path') != WORKFLOW or record.get('status') != 'completed'
            or record.get('conclusion') != 'success'
            or record.get('repository', {}).get('full_name') != publication.REPOSITORY
            or record.get('head_repository', {}).get('full_name') != publication.REPOSITORY
            or record.get('event') not in ('pull_request', 'workflow_dispatch')
            or not re.fullmatch(r'[a-f0-9]{40}', record.get('head_sha', ''))
            or receipt.get('revision') != record['head_sha']
            or receipt.get('scope') != 'Disposable Calico/Ambient/Kyverno signed platform staging'
            or set(receipt.get('platform_checks', {})) != CHECKS
            or set(receipt.get('checks', {})) != FOUNDATION
            or any(v != 'Passed' for v in receipt['platform_checks'].values())
            or any(v != 'Passed' for v in receipt['checks'].values())
            or git('rev-parse', record['head_sha'] + '^{tree}') != git('rev-parse', revision + '^{tree}')):
        raise ValueError('successful staging for the exact reviewed source tree required')
    observed = datetime.fromisoformat(receipt['observed_at'])
    age = (datetime.now(timezone.utc) - observed).total_seconds() if observed.tzinfo else -1
    if not 0 <= age <= 5 * 86400:
        raise ValueError('fresh staging required')


def staged_receipt(run_id, directory, revision):
    if not re.fullmatch(r'[1-9][0-9]{0,19}', run_id):
        raise ValueError('numeric staging run required')
    record = json.loads(publication.run(['api', 'repos/' + publication.REPOSITORY + '/actions/runs/' + run_id]))
    name = 'platform-staging-' + run_id + '-' + str(record['run_attempt'])
    artifacts = json.loads(publication.run(['api', 'repos/' + publication.REPOSITORY
                                            + '/actions/runs/' + run_id + '/artifacts']))['artifacts']
    matches = [a for a in artifacts if a['name'] == name]
    if len(matches) != 1 or matches[0]['expired'] or not 0 < matches[0]['size_in_bytes'] <= 65536:
        raise ValueError('exact bounded public staging artifact required')
    publication.run(['run', 'download', run_id, '--repo', publication.REPOSITORY,
                     '--name', name, '--dir', str(directory)])
    files = list(directory.iterdir())
    if len(files) != 1 or files[0].name != 'platform-staging-receipt.json' or files[0].is_symlink() \
            or not files[0].is_file() or files[0].stat().st_size > 32768:
        raise ValueError('bounded public staging receipt required')
    receipt = json.loads(files[0].read_bytes())
    check_staging(record, receipt, revision)
    return receipt


def resume_record(previous_bundle, plan, revision, directory):
    manifest_path = previous_bundle / 'bundle.json'
    if manifest_path.is_symlink() or not manifest_path.is_file() or manifest_path.stat().st_size > 32768:
        raise ValueError('bounded previous public manifest required')
    manifest = json.loads(manifest_path.read_bytes())
    previous_revision = manifest['source_revision']
    digest = manifest['files']['plan.json']
    if manifest.get('schema_version') != 1 or not re.fullmatch(r'[a-f0-9]{40}', previous_revision) \
            or not re.fullmatch(r'[a-f0-9]{64}', digest):
        raise ValueError('exact previous public plan identity required')
    git('merge-base', '--is-ancestor', previous_revision, revision)
    content = upgrade_kyverno.public_artifact(previous_bundle / 'plan.json', digest, 8 * 1024**2)
    previous = json.loads(content)
    evidence = previous.pop('commissioning_evidence')
    authenticated = staged_receipt(str(evidence['run_id']), directory / 'previous-staging', previous_revision)
    if evidence != {'source_revision': previous_revision, 'staging': 'Passed',
                    'run_id': evidence['run_id'], 'observed_at': authenticated['observed_at']}:
        raise ValueError('authenticated previous commissioning evidence required')
    desired = {key: value for key, value in plan.items() if key != 'commissioning_evidence'}
    if previous != desired:
        raise ValueError('resumption cannot change any platform desired state')
    return {'source_revision': previous_revision, 'plan_sha256': digest}


def build(output, staging_run, mesh_run, bao_run, public_ca, resume_bundle=None):
    revision = git('rev-parse', 'HEAD')
    main = json.loads(publication.run(['api', 'repos/' + publication.REPOSITORY + '/commits/main']))['sha']
    if revision != main or git('status', '--porcelain') or output.exists() or output.is_symlink() \
            or output.resolve().is_relative_to(mesh.ROOT):
        raise ValueError('clean reviewed current main and new outside-repository output required')
    with tempfile.TemporaryDirectory(prefix='hooshix-public-commissioning-') as temp:
        directory = Path(temp)
        receipts = directory / 'publications'
        receipts.mkdir(mode=0o700)
        publication.download(mesh_run, 'mesh', receipts)
        publication.download(bao_run, 'openbao', receipts)
        staging = staged_receipt(staging_run, directory / 'staging', revision)
        now = datetime.now(timezone.utc)
        mesh_receipt = admission.publication(receipts / 'mesh/receipt.json', 'mesh', now)
        bao_receipt = admission.publication(receipts / 'openbao/receipt.json', 'openbao', now)
        if (staging['publication_revisions'] != {'mesh': mesh_receipt['repository_revision'],
                                                'openbao': bao_receipt['repository_revision']}
                or staging['image'] != bao_receipt['image']
                or staging['mesh_images'] != {k: v['image'] for k, v in mesh_receipt['components'].items()}):
            raise ValueError('staging and publication identities differ')
        plan = admission.render(mesh_receipt, bao_receipt, mesh.candidate())
        plan['kyverno_upgrade'] = upgrade_kyverno.candidate()
        plan['storage'] = storage.candidate()
        plan['commissioning_evidence'] = {'source_revision': revision, 'staging': 'Passed',
                                         'run_id': int(staging_run), 'observed_at': staging['observed_at']}
        if resume_bundle is not None:
            plan['resume_from'] = resume_record(resume_bundle, plan, revision, directory)
        content = json.dumps(plan, separators=(',', ':')).encode()
        if len(content) > 8 * 1024 * 1024:
            raise ValueError('bounded public plan required')
        output.mkdir(mode=0o700, parents=False)
        (output / 'plan.json').write_bytes(content)
        shutil.copyfile(mesh.ROOT / 'infrastructure/kyverno/chart/3.9.1/kyverno-3.9.1.tgz', output / 'kyverno-3.9.1.tgz')
        request = urllib.request.Request('https://get.helm.sh/helm-v4.2.4-linux-amd64.tar.gz')
        with urllib.request.urlopen(request, timeout=30) as response:
            archive = response.read(32 * 1024**2 + 1)
        if len(archive) > 32 * 1024**2 or hashlib.sha256(archive).hexdigest() != upgrade_kyverno.HELM_ARCHIVE_SHA:
            raise ValueError('pinned bounded public Helm archive required')
        (output / 'helm-linux-amd64.tar.gz').write_bytes(archive)
        for name in (*SOURCES, 'run-platform-commissioning.ps1'):
            shutil.copyfile(mesh.ROOT / 'scripts/production' / name, output / name)
        shutil.copyfile(mesh.ROOT / 'docs/operations/production-platform-commissioning-fa.md', output / 'USAGE-fa.md')
        (output / 'public').mkdir(mode=0o700)
        for name, content in ca_import.read_public(public_ca).items():
            (output / 'public' / name).write_bytes(content)
        files = {p.relative_to(output).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in output.rglob('*') if p.is_file()}
        (output / 'bundle.json').write_text(json.dumps({'schema_version': 1, 'source_revision': revision,
                                                      'files': files}, indent=2) + '\n')
    print('PUBLIC_COMMISSIONING_BUNDLE=' + str(output))
    print('VPS_INSTALLATION=Not run; operator authentication is still required')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--staging-run', required=True)
    parser.add_argument('--public-ca', type=Path, required=True)
    parser.add_argument('--mesh-run', default='37588183736')
    parser.add_argument('--openbao-run', default='37228262995')
    parser.add_argument('--resume-bundle', type=Path, help='Previous public bundle; all desired state must be identical')
    args = parser.parse_args()
    try:
        build(args.output, args.staging_run, args.mesh_run, args.openbao_run, args.public_ca, args.resume_bundle)
        return 0
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        print('COMMISSIONING_BUNDLE=Failed; no credential read or VPS mutation')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
