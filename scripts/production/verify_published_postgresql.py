"""Verify the reviewed published set and disposable native smoke; never install."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from build_postgresql_candidates import (COMPONENTS, CANDIDATE_RECIPE, local_endpoint,
                                        native_entrypoint, provenance, recipe,
                                        validate_inventory)
from build_patched_platform import published_image
from publish_postgresql_candidates import (EXPECTED_CERTIFICATE_IDENTITY,
    EXPECTED_OIDC_ISSUER, repository, source_predicate)
from publish_openbao_candidate import run, verify_payload
from test_postgresql_isolation_native import exercise_candidate
from verify_openbao_artifact import load
from verify_postgresql_artifacts import PIN, pins, scan_inventory
from render_platform_admission import SIGSTORE_TUF_MIRROR


def candidate():
    pins()  # Upstream catalog and blocked promotion remain independent authority.
    selected = load(PIN)['published_security_candidate']
    publication = selected['publication']
    if (selected['recipe_sha256'] != hashlib.sha256(CANDIDATE_RECIPE.read_bytes()).hexdigest()
            or not re.fullmatch(r'[a-f0-9]{64}', selected['publication_sha256'])
            or publication['schema_version'] != 1
            or not re.fullmatch(r'[a-f0-9]{40}', publication['repository_revision'])
            or not re.fullmatch(r'github:[1-9][0-9]{0,19}:[1-9][0-9]{0,5}', publication['invocation_id'])
            or any(publication[name] != 'Passed' for name in ('publication', 'signature_provenance'))
            or any(publication[name] != 'Not verified' for name in
                   ('staging', 'runtime_admission', 'deployment', 'production_promotion'))):
        raise ValueError('reviewed blocked publication and exact recipe required')
    for name in ('images', 'image_config_digests', 'provenance_kinds', 'registry_visibility'):
        if set(publication[name]) != set(COMPONENTS):
            raise ValueError('complete fixed four-component set required')
    for component in COMPONENTS:
        if (not re.fullmatch(re.escape(repository(component)) + r'@sha256:[a-f0-9]{64}',
                             publication['images'][component])
                or not re.fullmatch(r'sha256:[a-f0-9]{64}', publication['image_config_digests'][component])
                or publication['provenance_kinds'][component] != provenance(component)
                or publication['registry_visibility'][component] not in ('private', 'public')):
            raise ValueError('fixed published destination, identities and visibility required')
    return selected


def cosign(args, cache):
    # Transport only; a fresh scoped cache keeps embedded TUF trust and user state separate.
    env = {key: value for key, value in os.environ.items()
           if not key.startswith(('COSIGN_', 'SIGSTORE_', 'TUF_'))}
    env['TUF_ROOT'] = str(cache)
    return subprocess.run(['cosign', *args], env=env, check=True, capture_output=True,
                          text=True, timeout=180).stdout


def verify(directory, output):
    selected = candidate()
    publication = load(directory / 'publication.json')
    if (publication != selected['publication']
            or hashlib.sha256((directory / 'publication.json').read_bytes()).hexdigest()
               != selected['publication_sha256']):
        raise ValueError('exact reviewed public publication receipt required')
    output.mkdir(mode=0o700)  # Fresh attempt; never overwrite publication or prior acceptance.
    scan_evidence = {}
    for component, image in publication['images'].items():
        print('POSTGRESQL_ACCEPTANCE_STEP=' + component + '-scan', flush=True)
        folder = output / component / 'registry'
        folder.mkdir(mode=0o700, parents=True)
        config = publication['image_config_digests'][component]
        scan_inventory(folder, image, 'registry')
        validate_inventory(folder, component, image, datetime.now(timezone.utc), config, recipe_path=CANDIDATE_RECIPE)
        if published_image(repository(component), load(folder / 'syft.json')['source'], config) != image:
            raise ValueError('exact reviewed registry manifest/config required')
        scan_evidence[component] = {name: hashlib.sha256((folder / name).read_bytes()).hexdigest()
            for name in ('syft.json', 'cyclonedx.json', 'grype.json', 'database.json', 'scanner-config.json')}
    cache = output.resolve() / 'sigstore-tuf'
    version = cosign(['version'], cache)
    if not re.search(r'(?<![\w.])v3\.0\.6(?![\w.])', version):
        raise ValueError('pinned Cosign required')
    cosign(['initialize', '--mirror', SIGSTORE_TUF_MIRROR], cache)
    flags = ['--certificate-identity', EXPECTED_CERTIFICATE_IDENTITY,
             '--certificate-oidc-issuer', EXPECTED_OIDC_ISSUER]
    source = recipe(CANDIDATE_RECIPE)
    for component, image in publication['images'].items():
        print('POSTGRESQL_ACCEPTANCE_STEP=' + component + '-signature', flush=True)
        cosign(['verify', *flags, image], cache)
        expected = source_predicate(component, source, publication['repository_revision'],
                                    publication['invocation_id'], recipe_path=CANDIDATE_RECIPE)
        for kind, uri, predicate in (
                ('slsaprovenance1', 'https://slsa.dev/provenance/v1', expected),
                ('cyclonedx', 'https://cyclonedx.org/bom',
                 load(directory / component / 'registry/cyclonedx.json'))):
            verify_payload(cosign(['verify-attestation', '--type', kind, *flags, image], cache),
                           uri, predicate, image.split('@sha256:')[1])
        try:
            cosign(['verify', '--certificate-identity', EXPECTED_CERTIFICATE_IDENTITY + '.wrong',
                    '--certificate-oidc-issuer', EXPECTED_OIDC_ISSUER, image], cache)
        except subprocess.CalledProcessError:
            pass
        else:
            raise ValueError('wrong signer accepted')
        cosign(['verify', *flags, image], cache)
    endpoint = local_endpoint()
    engines = {}
    for component, image in publication['images'].items():
        print('POSTGRESQL_ACCEPTANCE_STEP=' + component + '-native', flush=True)
        run(['docker', '--host', endpoint, 'pull', '--platform', 'linux/amd64', image], timeout=300)
        inspected = json.loads(run(['docker', '--host', endpoint, 'image', 'inspect',
                                   '--format', '{{json .}}', image]))
        config = inspected['Config']
        user = '26:26' if component in ('postgresql', 'barman-sidecar') else '65532:65532'
        entrypoint = ['/usr/local/bin/postgres'] if component == 'postgresql' else ['/manager']
        if (image not in inspected['RepoDigests'] or inspected['Os'] != 'linux'
                or inspected['Architecture'] != 'amd64' or config['User'] != user
                or config['Entrypoint'] != entrypoint
                or component in ('postgresql', 'barman-sidecar') and config.get('Cmd') not in (None, [])
                or not re.fullmatch(r'sha256:[a-f0-9]{64}', inspected['Id'])):
            raise ValueError('exact pulled digest, platform and non-root entrypoint required')
        engines[component] = inspected['Id']
        native_entrypoint(component, engines[component], endpoint, recipe_path=CANDIDATE_RECIPE)
    exercise_candidate(engines['postgresql'], docker_host=endpoint)
    result = {'schema_version': 1, 'observed_at': datetime.now(timezone.utc).isoformat(),
        'scope': 'published signatures/provenance/SBOM and network-isolated native smoke only',
        'publication_sha256': selected['publication_sha256'],
        'repository_revision': publication['repository_revision'],
        'invocation_id': publication['invocation_id'], 'images': publication['images'],
        'image_config_digests': publication['image_config_digests'], 'local_engine_digests': engines,
        'scan': 'Passed', 'scan_evidence_sha256': scan_evidence, 'signature_provenance': 'Passed', 'wrong_signer': 'Passed',
        'native_entrypoints': 'Passed', 'native_database_isolation': 'Passed',
        'staging': 'Not verified', 'runtime_admission': 'Not verified',
        'physical_recovery': 'Not verified', 'deployment': 'Not verified',
        'production_promotion': 'Not verified'}
    (output / 'acceptance.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence-dir', required=True, type=Path)
    parser.add_argument('--output-dir', required=True, type=Path)
    args = parser.parse_args()
    try:
        verify(args.evidence_dir, args.output_dir)
    except (ValueError, KeyError, TypeError, OSError, RuntimeError, subprocess.SubprocessError):
        parser.exit(1, 'POSTGRESQL_PUBLISHED_ACCEPTANCE=Failed; no install or automatic replay\n')
    print('POSTGRESQL_PUBLISHED_ACCEPTANCE=Passed; native smoke only; production Not verified')


if __name__ == '__main__':
    main()
