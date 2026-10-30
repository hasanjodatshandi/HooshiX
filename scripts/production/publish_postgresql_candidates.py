"""Publish the complete reviewed PostgreSQL candidate set on protected CI; never deploy."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from build_patched_platform import published_image
from build_postgresql_candidates import (COMPONENTS, RECIPE, CANDIDATE_RECIPE, build, dockerfile,
                                        local_endpoint, pins, provenance, recipe,
                                        source_key, validate_inventory)
from publish_openbao_candidate import context, run, verify_payload
from verify_openbao_artifact import load
from verify_postgresql_artifacts import scan_inventory
from verify_release import EXPECTED_CERTIFICATE_IDENTITY, EXPECTED_OIDC_ISSUER

PACKAGES = {'operator': 'hooshix/platform-postgresql-operator-v2-private',
            'postgresql': 'hooshix/platform-postgresql-postgresql-private',
            'barman-plugin': 'hooshix/platform-postgresql-barman-plugin-private',
            'barman-sidecar': 'hooshix/platform-postgresql-barman-sidecar-private'}
BUILD_TYPE = 'https://github.com/hasanjodatshandi/HooshiX/postgresql-candidate-build/v1'


def repository(component):
    return 'ghcr.io/hasanjodatshandi/' + PACKAGES[component]


def package_visibility(component, *, allow_missing=False):
    if component not in COMPONENTS:
        raise ValueError('unapproved PostgreSQL publication component')
    api = 'users/hasanjodatshandi/packages/container/' + PACKAGES[component].replace('/', '%2F')
    try:
        visibility = run(['gh', 'api', api, '--jq', '.visibility']).strip()
    except subprocess.CalledProcessError as error:
        # Authentication/permission failures are not absence.
        if allow_missing and '(HTTP 404)' in (error.stderr or ''):
            return
        raise
    # Owner approval is only for the four fixed infrastructure destinations above.
    if visibility not in ('private', 'public'):
        actual = 'internal' if visibility == 'internal' else 'invalid'
        print(f'POSTGRESQL_PACKAGE_VISIBILITY=Rejected; component={component}; '
              f'actual={actual}; required=private-or-public', flush=True)
        raise ValueError('approved infrastructure package visibility required')
    return visibility


def source_predicate(component, selected, revision, invocation, recipe_path=RECIPE):
    source = selected['sources'][source_key(component)]
    runtime = (selected['postgresql_runtime'] if component == 'postgresql' else
               selected['sidecar_runtime'] if component == 'barman-sidecar' else
               selected['plugin_runtime'] if component == 'barman-plugin' else
               pins()['components']['operator']['image'])
    dependencies = [{'uri': 'git+https://github.com/' + source['repository'],
                     'digest': {'gitCommit': source['revision']}},
                    {'uri': 'https://codeload.github.com/' + source['repository'] + '/tar.gz/' + source['revision'],
                     'digest': {'sha256': source['archive_sha256']}},
                    {'uri': runtime, 'digest': {'sha256': runtime.split('@sha256:')[1]}},
                    {'uri': 'git+https://github.com/hasanjodatshandi/HooshiX.git',
                     'digest': {'gitCommit': revision}}]
    if component != 'postgresql':
        dependencies.append({'uri': selected['builder'],
                             'digest': {'sha256': selected['builder'].split('@sha256:')[1]}})
    return {'buildDefinition': {'buildType': BUILD_TYPE,
        'externalParameters': {'component': component, 'operation': provenance(component),
            'source': source, 'recipe': selected,
            'recipe_sha256': hashlib.sha256(recipe_path.read_bytes()).hexdigest(),
            'dockerfile_sha256': hashlib.sha256(dockerfile(component, selected, recipe_path).encode()).hexdigest(),
            'runtime_base': runtime, 'platform': 'linux/amd64', 'repository_revision': revision},
        'internalParameters': {}, 'resolvedDependencies': dependencies},
        'runDetails': {'builder': {'id': EXPECTED_CERTIFICATE_IDENTITY},
                       'metadata': {'invocationId': invocation}}}


def sign_and_verify(folder, image, predicate):
    flags = ['--certificate-identity', EXPECTED_CERTIFICATE_IDENTITY,
             '--certificate-oidc-issuer', EXPECTED_OIDC_ISSUER]
    path = folder / 'source-provenance.json'
    path.write_text(json.dumps(predicate, sort_keys=True) + '\n')
    run(['cosign', 'sign', '--yes', image])
    for kind, filename, uri, expected in (
            ('slsaprovenance1', path, 'https://slsa.dev/provenance/v1', predicate),
            ('cyclonedx', folder / 'cyclonedx.json', 'https://cyclonedx.org/bom', load(folder / 'cyclonedx.json'))):
        run(['cosign', 'attest', '--yes', '--predicate', str(filename), '--type', kind, image])
        verify_payload(run(['cosign', 'verify-attestation', '--type', kind, *flags, image]),
                       uri, expected, image.split('@sha256:')[1])
    run(['cosign', 'verify', *flags, image])
    try:
        run(['cosign', 'verify', '--certificate-identity', EXPECTED_CERTIFICATE_IDENTITY + '.wrong',
             '--certificate-oidc-issuer', EXPECTED_OIDC_ISSUER, image])
    except subprocess.CalledProcessError:
        pass
    else:
        raise ValueError('wrong signer accepted')
    run(['cosign', 'verify', *flags, image])  # Negative must not hide a transport failure.


def publish(directory, env):
    revision, invocation = context(env)
    if (env.get('RELEASE_KIND') != 'postgresql-candidate'
            or run(['git', 'rev-parse', 'HEAD']).strip() != revision):
        raise ValueError('exact protected PostgreSQL dispatch required')
    endpoint = local_endpoint()
    directory.mkdir(mode=0o700)  # Never reuse a stale publication/partial attempt.
    selected = recipe(CANDIDATE_RECIPE)
    for component in COMPONENTS:
        package_visibility(component, allow_missing=True)
    built = {}
    for component in COMPONENTS:
        print('POSTGRESQL_PUBLICATION_STEP=' + component + '-build', flush=True)
        built[component] = build(component, directory / component, docker_host=endpoint, recipe_path=CANDIDATE_RECIPE)
    targets, receipts, visibilities = {}, {}, {}
    for component, receipt in built.items():
        print('POSTGRESQL_PUBLICATION_STEP=' + component + '-push-scan', flush=True)
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
        receipts[component] = validate_inventory(folder, component, image, datetime.now(timezone.utc),
                                                 receipt['image_config_digest'], recipe_path=CANDIDATE_RECIPE)
        targets[component] = image
        (folder / 'receipt.json').write_text(json.dumps(receipts[component], indent=2) + '\n')
    # All final registry digests pass before ANY component is signed.
    for component, image in targets.items():
        print('POSTGRESQL_PUBLICATION_STEP=' + component + '-sign-verify', flush=True)
        sign_and_verify(directory / component / 'registry', image,
                        source_predicate(component, selected, revision, invocation, CANDIDATE_RECIPE))
    for component in COMPONENTS:
        visibilities[component] = package_visibility(component)
    result = {'schema_version': 1, 'repository_revision': revision, 'invocation_id': invocation,
        'images': targets, 'image_config_digests': {key: value['image_config_digest'] for key, value in built.items()},
        'provenance_kinds': {key: provenance(key) for key in COMPONENTS},
        'registry_visibility': visibilities,
        'publication': 'Passed', 'signature_provenance': 'Passed',
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
        parser.exit(1, 'POSTGRESQL_PUBLICATION=Failed; state retained; no deployment or automatic replay\n')
    print('POSTGRESQL_PUBLICATION=Passed; approved signed infrastructure candidates only; production Not verified')


if __name__ == '__main__':
    main()
