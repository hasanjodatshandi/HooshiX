"""Build/scan/test one private local Redis candidate; never publish or install it."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from render_redis_security import PIN, pins
from test_postgresql_isolation_native import docker
from test_redis_security_native import exercise
from verify_openbao_artifact import MAX_BYTES, MAX_DB_AGE, load
from verify_postgresql_artifacts import (require_complete_inventory,
                                         require_unsuppressed_scan, scan_inventory)


def validate_candidate_scan(directory, now):
    pin = pins()
    inventory, report = load(directory / 'syft.json'), load(directory / 'grype.json')
    require_complete_inventory(inventory)
    require_unsuppressed_scan(report)
    metadata = inventory['source']['metadata']
    if (inventory['source']['type'] != 'image'
            or not re.fullmatch('sha256:[a-f0-9]{64}', metadata['imageID'])
            or metadata['imageID'] != report['source']['target']['imageID']
            or metadata['architecture'] != 'amd64' or metadata['os'] != 'linux'
            or inventory['distro'].get('versionID') != '3.24.2'
            or inventory['distro'].get('id') != 'alpine'
            or metadata['labels'].get('org.opencontainers.image.version') != pin['version']
            or metadata['labels'].get('io.hooshix.provenance.kind') != pin['provenance_kind']
            or metadata['labels'].get('io.hooshix.redis.source.revision') != pin['source_revision']):
        raise ValueError('exact candidate inventory/configuration required')
    versions = {item['name']: item['version'] for item in inventory['artifacts']}
    for name, version in {'libcrypto3': '3.5.9-r0', 'libssl3': '3.5.9-r0',
                          'zlib': '1.3.2-r1', 'libgcc': '15.2.0-r5',
                          'libstdc++': '15.2.0-r5'}.items():
        if versions.get(name) != version:
            raise ValueError('exact fixed runtime package inventory required')
    cdx = load(directory / 'cyclonedx.json')
    if (not isinstance(inventory.get('artifacts'), list) or not inventory['artifacts']
            or cdx.get('bomFormat') != 'CycloneDX'
            or not isinstance(cdx.get('components'), list) or not cdx['components']
            or not isinstance(report.get('matches'), list)
            or any(match['vulnerability']['severity'] not in
                   ('Negligible', 'Low', 'Medium', 'Unknown') for match in report['matches'])):
        raise ValueError('complete unsuppressed candidate inventory without High/Critical required')
    database = load(directory / 'database.json')
    built = datetime.fromisoformat(database['built'].replace('Z', '+00:00'))
    if (database.get('valid') is not True or database.get('error')
            or not database.get('schemaVersion')
            or built.tzinfo is None or not 0 <= (now - built).total_seconds() <= MAX_DB_AGE.total_seconds()):
        raise ValueError('fresh valid scanner database required')
    return {'image_configuration_digest': metadata['imageID'], 'scan': 'Passed',
        'database_built_at': built.isoformat(),
        'sha256': {name: hashlib.sha256((directory / name).read_bytes()).hexdigest()
                   for name in ('syft.json', 'cyclonedx.json', 'grype.json', 'database.json')}}


def build(directory):
    pin = pins()
    selected = docker('context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}')
    if (selected.returncode != 0 or not os.environ.get('DOCKER_HOST', 'unix://').startswith('unix://')
            or not isinstance(endpoint := json.loads(selected.stdout), str)
            or not endpoint.startswith('unix://')):
        raise ValueError('local Unix Docker endpoint required')
    directory.mkdir(mode=0o700)  # Fresh output cannot retain a previous success receipt.
    context = directory / 'context'
    context.mkdir(mode=0o700)
    for name in ('Dockerfile', '.dockerignore'):
        content = (PIN.parent / name).read_bytes()
        (context / name).write_bytes(content)
    url = 'https://codeload.github.com/redis/redis/tar.gz/' + pin['source_revision']
    with urllib.request.urlopen(url, timeout=40) as response:
        archive = response.read(MAX_BYTES + 1)
    if len(archive) > MAX_BYTES or hashlib.sha256(archive).hexdigest() != pin['source_archive_sha256']:
        raise ValueError('exact official source archive required')
    (context / 'source.tar.gz').write_bytes(archive)
    iid = directory / 'image-id.txt'
    result = docker('build', '--platform', 'linux/amd64', '--iidfile', str(iid), str(context),
                    timeout=300, docker_host=endpoint)
    if result.returncode != 0:
        raise RuntimeError('REDIS_CANDIDATE_BUILD_FAILED')
    image = iid.read_text().strip()
    if not re.fullmatch('sha256:[a-f0-9]{64}', image):
        raise ValueError('immutable local image identity required')
    inspection = docker('image', 'inspect', '--format', '{{json .Config}}', image, docker_host=endpoint)
    if inspection.returncode != 0:
        raise ValueError('built image inspection required')
    config = json.loads(inspection.stdout)
    if (config.get('User') != '10001:10001' or config.get('WorkingDir') != '/data'
            or config.get('Entrypoint') != ['/usr/local/bin/redis-server']
            or config.get('Cmd') != ['/run/secrets/security-redis/redis.conf']):
        raise ValueError('fixed non-root Redis runtime contract required')
    scan_inventory(directory, image, 'docker', docker_host=endpoint)
    now = datetime.now(timezone.utc)
    scan = validate_candidate_scan(directory, now)
    native = exercise(image, endpoint)
    result = {'schema_version': 1, 'component': 'security-redis', 'redis': pin['version'],
        'observed_at': now.isoformat(), 'local_image_id': image, **scan, 'native': native,
        'recipe_sha256': hashlib.sha256(PIN.read_bytes()).hexdigest(),
        'dockerfile_sha256': hashlib.sha256((context / 'Dockerfile').read_bytes()).hexdigest(),
        'source_image': pin['image'], 'runtime_base': pin['runtime_base'],
        'provenance_kind': pin['provenance_kind'], 'upstream_build_provenance': 'Not verified',
        'publication_signature_provenance_sbom': 'Not run', 'staging': 'Not run',
        'deployment': 'Not run', 'application_ingress': 'CLOSED', 'production_readiness': 'Not verified'}
    (directory / 'candidate.json').write_text(json.dumps(result, sort_keys=True, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True, type=Path)
    args = parser.parse_args()
    try:
        result = build(args.directory)
    except (ValueError, RuntimeError, KeyError, TypeError, OSError, subprocess.SubprocessError) as error:
        code = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        parser.exit(1, 'REDIS_CANDIDATE=Failed; reason=' + code + '; no publication or target writes\n')
    print(json.dumps(result, sort_keys=True, indent=2))


if __name__ == '__main__':
    main()
