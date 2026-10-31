"""Build, scan, publish and sign only the private OpenBao snapshot candidate."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from build_openbao_snapshot_candidate import DOCKERFILE, RECIPE, REPOSITORY, build, validate
from build_postgresql_candidates import local_endpoint
from build_patched_platform import published_image
from publish_openbao_candidate import context, run
from publish_postgresql_candidates import sign_and_verify
from verify_openbao_artifact import load
from verify_postgresql_artifacts import scan_inventory
from verify_release import EXPECTED_CERTIFICATE_IDENTITY

PACKAGE_API = 'users/hasanjodatshandi/packages/container/hooshix%2Fplatform-openbao-snapshot-private'
BUILD_TYPE = 'https://github.com/hasanjodatshandi/HooshiX/openbao-snapshot-candidate/v1'


def visibility(*, allow_missing=False):
    try:
        result = run(['gh', 'api', PACKAGE_API, '--jq', '.visibility'], timeout=20).strip()
    except subprocess.CalledProcessError as error:
        if allow_missing and '(HTTP 404)' in (error.stderr or ''):
            return None
        raise
    if result != 'private':
        raise ValueError('snapshot candidate package must remain private')
    return result


def predicate(revision, invocation, image, config_digest):
    selected = load(RECIPE)
    sources = {name: hashlib.sha256((RECIPE.parents[3] / 'scripts/production' / name).read_bytes()).hexdigest()
               for name in ('openbao_snapshot_agent.py', 'openbao_snapshot_crypto.py',
                            'openbao_snapshot_errors.py', 'parspack_snapshot_transport.py')}
    return {'buildDefinition': {'buildType': BUILD_TYPE,
        'externalParameters': {'component': 'openbao-snapshot', 'image': image,
            'image_config_digest': config_digest, 'recipe': selected,
            'recipe_sha256': hashlib.sha256(RECIPE.read_bytes()).hexdigest(),
            'dockerfile_sha256': hashlib.sha256(DOCKERFILE.read_bytes()).hexdigest(),
            'source_sha256': sources, 'platform': 'linux/amd64', 'repository_revision': revision},
        'internalParameters': {}, 'resolvedDependencies': [
            {'uri': selected['runtime_base'], 'digest': {'sha256': selected['runtime_base'].split('@sha256:')[1]}},
            {'uri': 'git+https://github.com/hasanjodatshandi/HooshiX.git', 'digest': {'gitCommit': revision}}]},
        'runDetails': {'builder': {'id': EXPECTED_CERTIFICATE_IDENTITY},
                       'metadata': {'invocationId': invocation}}}


def publish(directory: Path, env):
    revision, invocation = context(env)
    if (env.get('RELEASE_KIND') != 'openbao-snapshot-candidate'
            or run(['git', 'rev-parse', 'HEAD']).strip() != revision):
        raise ValueError('exact protected snapshot dispatch required')
    endpoint = local_endpoint()
    directory.mkdir(mode=0o700)
    visibility(allow_missing=True)
    print('OPENBAO_SNAPSHOT_PUBLICATION_STEP=build-scan-native', flush=True)
    built = build(directory / 'candidate', revision, endpoint=endpoint)
    print('OPENBAO_SNAPSHOT_PUBLICATION_STEP=push-scan', flush=True)
    tag = REPOSITORY + ':candidate-' + revision + '-' + invocation.replace(':', '-')
    run(['docker', '--host', endpoint, 'tag', built['image_config_digest'], tag])
    run(['docker', '--host', endpoint, 'push', tag], timeout=300)
    visibility()
    folder = directory / 'registry'
    folder.mkdir(mode=0o700)
    (folder / 'scanner-config.json').write_text('{}\n')
    run(['syft', 'scan', tag, '--from', 'registry', '--platform', 'linux/amd64',
         '--config', str(folder / 'scanner-config.json'),
         '-o', 'syft-json=' + str(folder / 'registry-resolution.json')], timeout=300)
    image = published_image(REPOSITORY, load(folder / 'registry-resolution.json')['source'],
                            built['image_config_digest'])
    scan_inventory(folder, image, 'registry')
    if published_image(REPOSITORY, load(folder / 'syft.json')['source'],
                       built['image_config_digest']) != image:
        raise ValueError('exact signed registry image required')
    scanned = validate(folder, image, revision, datetime.now(timezone.utc),
                       expected_config_digest=built['image_config_digest'])
    (folder / 'receipt.json').write_text(json.dumps(scanned, sort_keys=True, indent=2) + '\n')
    print('OPENBAO_SNAPSHOT_PUBLICATION_STEP=sign-verify', flush=True)
    sign_and_verify(folder, image, predicate(revision, invocation, image, built['image_config_digest']))
    result = {'schema_version': 1, 'component': 'openbao-snapshot', 'repository_revision': revision,
        'invocation_id': invocation, 'image': image, 'image_config_digest': built['image_config_digest'],
        'registry_visibility': 'private', 'scan': 'Passed', 'signature_provenance_sbom': 'Passed',
        'native_smoke': built['native_smoke'], 'publication': 'Passed', 'deployment': 'Not run',
        'hourly_schedule': 'Not run', 'target_restore': 'Not run', 'production_readiness': 'Not verified'}
    (directory / 'publication.json').write_text(json.dumps(result, sort_keys=True, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True, type=Path)
    args = parser.parse_args()
    try:
        publish(args.directory, dict(os.environ))
    except (ValueError, KeyError, TypeError, OSError, RuntimeError, subprocess.SubprocessError):
        parser.exit(1, 'OPENBAO_SNAPSHOT_PUBLICATION=Failed; state retained; no deployment or replay\n')
    print('OPENBAO_SNAPSHOT_PUBLICATION=Passed; signed private candidate only; deployment Not verified')


if __name__ == '__main__':
    main()
