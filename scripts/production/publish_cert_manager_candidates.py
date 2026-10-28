"""Publish the three approved cert-manager source candidates on protected CI only."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from build_cert_manager_candidates import (CANDIDATE_RECIPE, COMPONENTS, RECIPE, build,
                                           dockerfile, recipe, validate_inventory)
from build_patched_platform import published_image
from build_postgresql_candidates import local_endpoint
from publish_openbao_candidate import context, run
from publish_postgresql_candidates import sign_and_verify
from verify_openbao_artifact import load
from verify_postgresql_artifacts import scan_inventory
from verify_release import EXPECTED_CERTIFICATE_IDENTITY

PACKAGES = {component: 'hooshix/platform-cert-manager-' + component for component in COMPONENTS}
BUILD_TYPE = 'https://github.com/hasanjodatshandi/HooshiX/cert-manager-source-build/v1'


def repository(component):
    return 'ghcr.io/hasanjodatshandi/' + PACKAGES[component]


def package_visibility(component, *, allow_missing=False):
    if component not in COMPONENTS:
        raise ValueError('unapproved cert-manager publication component')
    api = 'users/hasanjodatshandi/packages/container/' + PACKAGES[component].replace('/', '%2F')
    try:
        visibility = run(['gh', 'api', api, '--jq', '.visibility']).strip()
    except subprocess.CalledProcessError as error:
        if allow_missing and '(HTTP 404)' in (error.stderr or ''):
            return
        raise
    # Owner approval covers only these three fixed infrastructure destinations.
    if visibility not in ('private', 'public'):
        raise ValueError('approved infrastructure package visibility required')
    return visibility


def source_predicate(component, selected, revision, invocation, recipe_path=RECIPE):
    if component not in COMPONENTS:
        raise ValueError('unapproved cert-manager component')
    source, runtime = selected['source'], selected['components'][component]
    dependencies = [
        {'uri': 'git+https://github.com/' + source['repository'], 'digest': {'gitCommit': source['revision']}},
        {'uri': 'https://codeload.github.com/' + source['repository'] + '/tar.gz/' + source['revision'],
         'digest': {'sha256': source['archive_sha256']}},
        {'uri': runtime, 'digest': {'sha256': runtime.split('@sha256:')[1]}},
        {'uri': selected['builder'], 'digest': {'sha256': selected['builder'].split('@sha256:')[1]}},
        {'uri': 'git+https://github.com/hasanjodatshandi/HooshiX.git', 'digest': {'gitCommit': revision}}]
    return {'buildDefinition': {'buildType': BUILD_TYPE,
        'externalParameters': {'component': component, 'operation': 'patched-upstream-source-build',
            'source': source, 'recipe': selected,
            'recipe_sha256': hashlib.sha256(recipe_path.read_bytes()).hexdigest(),
            'dockerfile_sha256': hashlib.sha256(dockerfile(component, selected, recipe_path).encode()).hexdigest(),
            'runtime_base': runtime, 'platform': 'linux/amd64', 'repository_revision': revision},
        'internalParameters': {}, 'resolvedDependencies': dependencies},
        'runDetails': {'builder': {'id': EXPECTED_CERTIFICATE_IDENTITY},
                       'metadata': {'invocationId': invocation}}}


def publish(directory, env):
    revision, invocation = context(env)
    if (env.get('RELEASE_KIND') != 'cert-manager-candidate'
            or run(['git', 'rev-parse', 'HEAD']).strip() != revision):
        raise ValueError('exact protected cert-manager dispatch required')
    endpoint = local_endpoint()
    directory.mkdir(mode=0o700)
    selected = recipe(CANDIDATE_RECIPE)
    for component in COMPONENTS:
        package_visibility(component, allow_missing=True)
    built = {}
    for component in COMPONENTS:
        print('CERT_MANAGER_PUBLICATION_STEP=' + component + '-build', flush=True)
        built[component] = build(component, directory / component, docker_host=endpoint)
    targets, visibilities = {}, {}
    for component, receipt in built.items():
        print('CERT_MANAGER_PUBLICATION_STEP=' + component + '-push-scan', flush=True)
        package_visibility(component, allow_missing=True)
        target = repository(component) + ':candidate-' + revision + '-' + invocation.replace(':', '-')
        run(['docker', '--host', endpoint, 'tag', receipt['local_engine_digest'], target])
        run(['docker', '--host', endpoint, 'push', target], timeout=300)
        visibilities[component] = package_visibility(component)
        folder = directory / component / 'registry'
        folder.mkdir(mode=0o700)
        (folder / 'scanner-config.json').write_text('{}\n')
        run(['syft', 'scan', target, '--from', 'registry', '--platform', 'linux/amd64',
             '--config', str(folder / 'scanner-config.json'),
             '-o', 'syft-json=' + str(folder / 'registry-resolution.json')], timeout=300)
        image = published_image(repository(component), load(folder / 'registry-resolution.json')['source'],
                                receipt['image_config_digest'])
        scan_inventory(folder, image, 'registry')
        if published_image(repository(component), load(folder / 'syft.json')['source'],
                           receipt['image_config_digest']) != image:
            raise ValueError('immutable registry manifest changed')
        checked = validate_inventory(folder, component, image, datetime.now(timezone.utc),
                                     receipt['image_config_digest'], CANDIDATE_RECIPE)
        targets[component] = image
        (folder / 'receipt.json').write_text(json.dumps(checked, indent=2) + '\n')
    # No signature until every final registry image has passed the complete scan.
    for component, image in targets.items():
        print('CERT_MANAGER_PUBLICATION_STEP=' + component + '-sign-verify', flush=True)
        sign_and_verify(directory / component / 'registry', image,
                        source_predicate(component, selected, revision, invocation, CANDIDATE_RECIPE))
    for component in COMPONENTS:
        visibilities[component] = package_visibility(component)
    result = {'schema_version': 1, 'repository_revision': revision, 'invocation_id': invocation,
        'images': targets, 'image_config_digests': {key: value['image_config_digest'] for key, value in built.items()},
        'provenance_kinds': {key: 'patched-upstream-source-build' for key in COMPONENTS},
        'registry_visibility': visibilities, 'publication': 'Passed', 'signature_provenance': 'Passed',
        'staging': 'Not verified', 'runtime_admission': 'Not verified',
        'deployment': 'Not verified', 'production_promotion': 'Not verified'}
    (directory / 'publication.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    args = parser.parse_args()
    try:
        publish(args.directory, dict(os.environ))
    except (ValueError, KeyError, TypeError, OSError, RuntimeError, subprocess.SubprocessError):
        parser.exit(1, 'CERT_MANAGER_PUBLICATION=Failed; state retained; no deployment or automatic replay\n')
    print('CERT_MANAGER_PUBLICATION=Passed; signed infrastructure candidates only; production Not verified')


if __name__ == '__main__':
    main()
