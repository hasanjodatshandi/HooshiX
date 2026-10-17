"""Publish the reviewed private Redis runtime candidate on protected CI; never deploy."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from build_patched_platform import published_image
from build_postgresql_candidates import local_endpoint
from build_redis_candidate import build, validate_candidate_scan
from publish_openbao_candidate import context, run
from publish_postgresql_candidates import sign_and_verify
from render_redis_security import PIN, pins
from verify_openbao_artifact import load
from verify_postgresql_artifacts import scan_inventory
from verify_release import EXPECTED_CERTIFICATE_IDENTITY

REPOSITORY = 'ghcr.io/hasanjodatshandi/hooshix/platform-security-redis-private'
PACKAGE_API = 'users/hasanjodatshandi/packages/container/hooshix%2Fplatform-security-redis-private'
BUILD_TYPE = 'https://github.com/hasanjodatshandi/HooshiX/redis-runtime-build/v1'


def package_visibility(*, allow_missing=False):
    try:
        visibility = run(['gh', 'api', PACKAGE_API, '--jq', '.visibility'], timeout=20).strip()
    except subprocess.CalledProcessError as error:
        if allow_missing and '(HTTP 404)' in (error.stderr or ''):
            return
        raise
    if visibility != 'private':
        raise ValueError('owned Redis package must remain private')
    return visibility


def source_predicate(revision, invocation):
    selected = pins()
    archive = 'https://codeload.github.com/redis/redis/tar.gz/' + selected['source_revision']
    return {'buildDefinition': {'buildType': BUILD_TYPE,
        'externalParameters': {'component': 'security-redis',
            'operation': selected['provenance_kind'], 'recipe': selected,
            'recipe_sha256': hashlib.sha256(PIN.read_bytes()).hexdigest(),
            'dockerfile_sha256': hashlib.sha256((PIN.parent / 'Dockerfile').read_bytes()).hexdigest(),
            'platform': 'linux/amd64', 'repository_revision': revision},
        'internalParameters': {}, 'resolvedDependencies': [
            {'uri': selected['image'], 'digest': {'sha256': selected['image'].split('@sha256:')[1]}},
            {'uri': selected['runtime_base'], 'digest': {'sha256': selected['runtime_base'].split('@sha256:')[1]}},
            {'uri': archive, 'digest': {'sha256': selected['source_archive_sha256']}},
            {'uri': 'git+https://github.com/hasanjodatshandi/HooshiX.git', 'digest': {'gitCommit': revision}}]},
        'runDetails': {'builder': {'id': EXPECTED_CERTIFICATE_IDENTITY},
                       'metadata': {'invocationId': invocation}}}


def publish(directory, env):
    revision, invocation = context(env)
    if (env.get('RELEASE_KIND') != 'redis-candidate'
            or run(['git', 'rev-parse', 'HEAD']).strip() != revision):
        raise ValueError('exact protected Redis dispatch required')
    endpoint = local_endpoint()
    directory.mkdir(mode=0o700)  # No stale success or replay of a partial attempt.
    package_visibility(allow_missing=True)
    print('REDIS_PUBLICATION_STEP=build-scan-native', flush=True)
    built = build(directory / 'candidate')
    print('REDIS_PUBLICATION_STEP=push-scan', flush=True)
    package_visibility(allow_missing=True)
    target = REPOSITORY + ':candidate-' + revision + '-' + invocation.replace(':', '-')
    run(['docker', '--host', endpoint, 'tag', built['local_image_id'], target])
    run(['docker', '--host', endpoint, 'push', target], timeout=300)
    package_visibility()
    folder = directory / 'registry'
    folder.mkdir(mode=0o700)
    (folder / 'scanner-config.json').write_text('{}\n')
    run(['syft', 'scan', target, '--from', 'registry', '--platform', 'linux/amd64',
         '--config', str(folder / 'scanner-config.json'),
         '-o', 'syft-json=' + str(folder / 'registry-resolution.json')], timeout=300)
    image = published_image(REPOSITORY, load(folder / 'registry-resolution.json')['source'],
                            built['image_configuration_digest'])
    scan_inventory(folder, image, 'registry')
    if published_image(REPOSITORY, load(folder / 'syft.json')['source'],
                       built['image_configuration_digest']) != image:
        raise ValueError('immutable registry manifest changed')
    checked = validate_candidate_scan(folder, datetime.now(timezone.utc))
    (folder / 'receipt.json').write_text(json.dumps(checked, indent=2) + '\n')
    # The registry configuration equals the local image that passed native TLS/ACL/AOF.
    print('REDIS_PUBLICATION_STEP=sign-verify', flush=True)
    sign_and_verify(folder, image, source_predicate(revision, invocation))
    visibility = package_visibility()
    result = {'schema_version': 1, 'component': 'security-redis',
        'repository_revision': revision, 'invocation_id': invocation,
        'image': image, 'image_configuration_digest': built['image_configuration_digest'],
        'provenance_kind': pins()['provenance_kind'], 'upstream_build_provenance': 'Not verified',
        'registry_visibility': visibility, 'publication': 'Passed', 'scan': 'Passed',
        'signature_provenance_sbom': 'Passed', 'native': built['native'],
        'staging': 'Not run', 'runtime_admission': 'Not run', 'deployment': 'Not run',
        'application_ingress': 'CLOSED', 'production_readiness': 'Not verified'}
    (directory / 'publication.json').write_text(json.dumps(result, sort_keys=True, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True, type=Path)
    args = parser.parse_args()
    try:
        publish(args.directory, dict(os.environ))
    except (ValueError, KeyError, TypeError, OSError, RuntimeError, subprocess.SubprocessError):
        parser.exit(1, 'REDIS_PUBLICATION=Failed; state retained; no deployment or automatic replay\n')
    print('REDIS_PUBLICATION=Passed; private signed candidate only; production Not verified')


if __name__ == '__main__':
    main()
