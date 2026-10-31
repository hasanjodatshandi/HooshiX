"""Build and scan the exact non-root OpenBao snapshot agent image locally."""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
from datetime import datetime, timedelta, timezone
from pathlib import Path

from publish_openbao_candidate import run
from verify_openbao_artifact import ROOT, load
from verify_postgresql_artifacts import require_complete_inventory, require_unsuppressed_scan, scan_inventory

RECIPE = ROOT / 'infrastructure/production/snapshots/openbao-snapshot-candidate.json'
DOCKERFILE = ROOT / 'infrastructure/production/snapshots/Dockerfile'
SOURCES = ('openbao_snapshot_agent.py', 'openbao_snapshot_crypto.py',
           'openbao_snapshot_errors.py', 'parspack_snapshot_transport.py')
PACKAGES = {'ca-certificates-bundle': '20261020-r0', 'curl': '8.23.0-r0',
            'gpg': '2.4.9-r1', 'gpg-agent': '2.4.9-r1', 'gnupg-gpgconf': '2.4.9-r1',
            'libcrypto3': '3.5.9-r0', 'libssl3': '3.5.9-r0'}
REPOSITORY = 'ghcr.io/hasanjodatshandi/hooshix/platform-openbao-snapshot-private'


def recipe():
    value = load(RECIPE)
    if (value.get('schema_version') != 1 or value.get('component') != 'openbao-snapshot'
            or value.get('version') != '1' or value.get('platform') != 'linux/amd64'
            or value.get('runtime_base') != 'docker.io/library/python@sha256:eb2c62658b8d51bff0acc408504b76521d02030de56a8a3c1e830083e89b89d2'
            or value.get('python_version') != '3.14.10' or value.get('alpine_version') != '3.24.3'
            or value.get('packages') != PACKAGES
            or value.get('production_promotion') !=
               'blocked-until-protected-publication-gitops-reconciliation-and-target-restore'):
        raise ValueError('reviewed immutable snapshot candidate recipe required')
    return value


def validate(directory: Path, image: str, revision: str, now: datetime, *,
             expected_config_digest=None):
    if (not re.fullmatch(r'(?:sha256:[a-f0-9]{64}|[a-z0-9./_-]+@sha256:[a-f0-9]{64})', image)
            or not re.fullmatch(r'[a-f0-9]{40}', revision)
            or expected_config_digest is not None
            and not re.fullmatch(r'sha256:[a-f0-9]{64}', expected_config_digest)):
        raise ValueError('immutable image and source revision required')
    value = recipe()
    syft, cdx, report, db = (load(directory / name) for name in
                             ('syft.json', 'cyclonedx.json', 'grype.json', 'database.json'))
    require_complete_inventory(syft)
    metadata = syft['source']['metadata']
    expected_recipe = hashlib.sha256(RECIPE.read_bytes()).hexdigest()
    config_digest = image if expected_config_digest is None else expected_config_digest
    if (syft['source']['type'] != 'image' or metadata.get('userInput') != image
            or metadata.get('imageID') != config_digest or metadata.get('os') != 'linux'
            or metadata.get('architecture') != 'amd64'
            or metadata.get('labels', {}).get('org.opencontainers.image.version') != value['version']
            or metadata['labels'].get('org.opencontainers.image.revision') != revision
            or metadata['labels'].get('io.hooshix.recipe.sha256') != expected_recipe
            or metadata['labels'].get('org.opencontainers.image.source') !=
               'https://github.com/hasanjodatshandi/HooshiX'
            or metadata['labels'].get('io.hooshix.provenance.kind') !=
               'hooshix-openbao-hourly-encrypted-snapshot'
            or report['source']['target']['imageID'] != config_digest
            or report['source']['target']['userInput'] != image
            or expected_config_digest is not None and
               image not in metadata.get('repoDigests', [])
            or cdx.get('bomFormat') != 'CycloneDX' or not cdx.get('components')):
        raise ValueError('exact linux/amd64 image configuration and recipe evidence required')
    installed = {item['name']: item['version'] for item in syft['artifacts']
                 if item.get('type') == 'apk' and item.get('name') in PACKAGES}
    if (syft.get('distro', {}).get('id') != 'alpine'
            or syft['distro'].get('versionID') != value['alpine_version'] or installed != PACKAGES):
        raise ValueError('exact pinned runtime package inventory required')
    built = datetime.fromisoformat(db['built'].replace('Z', '+00:00'))
    if (db.get('valid') is not True or db.get('error') or not db.get('schemaVersion')
            or built.tzinfo is None or not timedelta(0) <= now - built <= timedelta(days=5)
            or not isinstance(report.get('matches'), list)):
        raise ValueError('fresh valid final-image vulnerability evidence required')
    require_unsuppressed_scan(report)
    return {'schema_version': 1, 'component': 'openbao-snapshot', 'image_config_digest': config_digest,
            'recipe_sha256': expected_recipe, 'scan': 'Passed',
            'runtime_packages': PACKAGES, 'high_critical_findings': 0,
            'publication': 'Not run', 'signature_provenance': 'Not run', 'deployment': 'Not verified'}


def local_endpoint():
    endpoint = json.loads(run(['docker', 'context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}']))
    if not endpoint.startswith('unix://') or not os.environ.get('DOCKER_HOST', 'unix://').startswith('unix://'):
        raise ValueError('local Unix Docker socket required')
    return endpoint


def smoke(image_id, endpoint):
    base = ['docker', '--host', endpoint, 'run', '--rm', '--network', 'none', '--read-only',
            '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--memory', '512m',
            '--cpus', '0.5', '--pids-limit', '64', '--tmpfs', '/tmp:rw,noexec,nosuid,nodev,size=64m']
    run([*base, '--entrypoint', '/usr/local/bin/python3', image_id, '-I', '-c',
         'import openbao_snapshot_agent,openbao_snapshot_crypto,openbao_snapshot_errors,parspack_snapshot_transport'])
    for binary in ('/usr/bin/curl', '/usr/bin/gpg', '/usr/bin/gpgconf'):
        run([*base, '--entrypoint', binary, image_id, '--version'])


def build(directory: Path, revision: str, *, endpoint=None):
    selected = recipe()
    endpoint = local_endpoint() if endpoint is None else endpoint
    if not endpoint.startswith('unix://'):
        raise ValueError('local Unix Docker socket required')
    directory.mkdir(mode=0o700)
    context = directory / 'context'
    context.mkdir(mode=0o700)
    for name in SOURCES:
        shutil.copyfile(ROOT / 'scripts/production' / name, context / name)
    shutil.copyfile(DOCKERFILE, context / 'Dockerfile')
    (context / '.dockerignore').write_text('*\n!Dockerfile\n!' + '\n!'.join(SOURCES) + '\n')
    recipe_hash = hashlib.sha256(RECIPE.read_bytes()).hexdigest()
    image_tag = 'hooshix-openbao-snapshot:candidate'
    identity = directory / 'image-id.txt'
    run(['docker', '--host', endpoint, 'build', '--platform', 'linux/amd64', '--tag', image_tag,
         '--iidfile', str(identity), '--build-arg', 'REPOSITORY_REVISION=' + revision,
         '--build-arg', 'RECIPE_SHA256=' + recipe_hash, str(context)], timeout=1800)
    image_id = identity.read_text().strip()
    if not re.fullmatch(r'sha256:[a-f0-9]{64}', image_id):
        raise ValueError('immutable local image configuration required')
    config = json.loads(run(['docker', '--host', endpoint, 'image', 'inspect', '--format', '{{json .Config}}', image_id]))
    if (config.get('User') != '10001:10001'
            or config.get('Entrypoint') != ['/usr/local/bin/python3', '-I', '/app/openbao_snapshot_agent.py']
            or config.get('WorkingDir') != '/app'):
        raise ValueError('non-root immutable snapshot image contract required')
    smoke(image_id, endpoint)
    scan_inventory(directory, image_id, 'docker', docker_host=endpoint)
    receipt = validate(directory, image_id, revision, datetime.now(timezone.utc))
    receipt['native_smoke'] = 'Passed'
    (directory / 'receipt.json').write_text(json.dumps(receipt, sort_keys=True, indent=2) + '\n')
    return receipt
