"""Independently verify the published cert-manager set; never build, sign or install."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from build_cert_manager_candidates import (BLOCKED, COMPONENTS, RECIPE, ROOT,
    local_endpoint, native_entrypoint, recipe, validate_inventory)
from build_patched_platform import published_image
from publish_cert_manager_candidates import repository, source_predicate
from publish_openbao_candidate import run, verify_payload
from verify_openbao_artifact import load
from verify_postgresql_artifacts import scan_inventory
from verify_published_postgresql import cosign
from verify_release import EXPECTED_CERTIFICATE_IDENTITY, EXPECTED_OIDC_ISSUER
from render_platform_admission import SIGSTORE_TUF_MIRROR

PIN = ROOT / 'infrastructure/production/data/cert-manager-images.json'


def candidate():
    source = recipe()
    catalog = load(PIN)
    selected = catalog['published_security_candidate']
    publication = selected['publication']
    if (catalog['schema_version'] != 1 or catalog['version'] != source['version']
            or catalog['platform'] != 'linux/amd64' or catalog['production_promotion'] != BLOCKED
            or selected['recipe_sha256'] != hashlib.sha256(RECIPE.read_bytes()).hexdigest()
            or not re.fullmatch(r'[a-f0-9]{64}', selected['publication_sha256'])
            or publication['schema_version'] != 1
            or not re.fullmatch(r'[a-f0-9]{40}', publication['repository_revision'])
            or not re.fullmatch(r'github:[1-9][0-9]{0,19}:[1-9][0-9]{0,5}', publication['invocation_id'])
            or any(publication[name] != 'Passed' for name in ('publication', 'signature_provenance'))
            or any(publication[name] != 'Not verified' for name in
                   ('staging', 'runtime_admission', 'deployment', 'production_promotion'))):
        raise ValueError('reviewed blocked publication and unchanged source recipe required')
    for name in ('images', 'image_config_digests', 'provenance_kinds', 'registry_visibility'):
        if set(publication[name]) != set(COMPONENTS):
            raise ValueError('complete fixed three-component set required')
    for component in COMPONENTS:
        if (not re.fullmatch(re.escape(repository(component)) + r'@sha256:[a-f0-9]{64}', publication['images'][component])
                or not re.fullmatch(r'sha256:[a-f0-9]{64}', publication['image_config_digests'][component])
                or publication['provenance_kinds'][component] != 'patched-upstream-source-build'
                or publication['registry_visibility'][component] not in ('private', 'public')):
            raise ValueError('fixed destination, config identity and infrastructure visibility required')
    return selected


def verify(directory, output):
    selected = candidate()
    publication = load(directory / 'publication.json')
    if (publication != selected['publication']
            or hashlib.sha256((directory / 'publication.json').read_bytes()).hexdigest() != selected['publication_sha256']):
        raise ValueError('exact reviewed public receipt required')
    output.mkdir(mode=0o700)  # Fresh attempt; never overwrite publication or prior acceptance.
    scan_evidence = {}
    for component, image in publication['images'].items():
        print('CERT_MANAGER_ACCEPTANCE_STEP=' + component + '-scan', flush=True)
        folder = output / component / 'registry'
        folder.mkdir(mode=0o700, parents=True)
        config = publication['image_config_digests'][component]
        scan_inventory(folder, image, 'registry')
        validate_inventory(folder, component, image, datetime.now(timezone.utc), config)
        if published_image(repository(component), load(folder / 'syft.json')['source'], config) != image:
            raise ValueError('exact reviewed registry manifest/config required')
        scan_evidence[component] = {name: hashlib.sha256((folder / name).read_bytes()).hexdigest()
            for name in ('syft.json', 'cyclonedx.json', 'grype.json', 'database.json', 'scanner-config.json')}
    cache = output.resolve() / 'sigstore-tuf'
    if not re.search(r'(?<![\w.])v3\.0\.6(?![\w.])', cosign(['version'], cache)):
        raise ValueError('pinned Cosign required')
    cosign(['initialize', '--mirror', SIGSTORE_TUF_MIRROR], cache)
    flags = ['--certificate-identity', EXPECTED_CERTIFICATE_IDENTITY,
             '--certificate-oidc-issuer', EXPECTED_OIDC_ISSUER]
    source = recipe()
    for component, image in publication['images'].items():
        print('CERT_MANAGER_ACCEPTANCE_STEP=' + component + '-signature', flush=True)
        cosign(['verify', *flags, image], cache)
        for kind, uri, predicate in (
                ('slsaprovenance1', 'https://slsa.dev/provenance/v1',
                 source_predicate(component, source, publication['repository_revision'], publication['invocation_id'])),
                ('cyclonedx', 'https://cyclonedx.org/bom', load(directory / component / 'registry/cyclonedx.json'))):
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
    endpoint, engines = local_endpoint(), {}
    for component, image in publication['images'].items():
        print('CERT_MANAGER_ACCEPTANCE_STEP=' + component + '-native', flush=True)
        run(['docker', '--host', endpoint, 'pull', '--platform', 'linux/amd64', image], timeout=300)
        inspected = json.loads(run(['docker', '--host', endpoint, 'image', 'inspect', '--format', '{{json .}}', image]))
        if (image not in inspected['RepoDigests'] or inspected['Os'] != 'linux'
                or inspected['Architecture'] != 'amd64' or not re.fullmatch(r'sha256:[a-f0-9]{64}', inspected['Id'])):
            raise ValueError('exact pulled manifest, platform and engine required')
        engines[component] = inspected['Id']
        native_entrypoint(component, engines[component], endpoint)
    result = {'schema_version': 1, 'observed_at': datetime.now(timezone.utc).isoformat(),
        'scope': 'published signature/provenance/SBOM and restricted help-only native smoke',
        'publication_sha256': selected['publication_sha256'], 'repository_revision': publication['repository_revision'],
        'invocation_id': publication['invocation_id'], 'images': publication['images'],
        'image_config_digests': publication['image_config_digests'], 'local_engine_digests': engines,
        'scan': 'Passed', 'scan_evidence_sha256': scan_evidence, 'signature_provenance': 'Passed', 'wrong_signer': 'Passed', 'native_entrypoints': 'Passed',
        'certificate_issuance': 'Not verified', 'staging': 'Not verified', 'runtime_admission': 'Not verified',
        'deployment': 'Not verified', 'production_promotion': 'Not verified'}
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
        parser.exit(1, 'CERT_MANAGER_PUBLISHED_ACCEPTANCE=Failed; no install or automatic replay\n')
    print('CERT_MANAGER_PUBLISHED_ACCEPTANCE=Passed; help-only smoke; production Not verified')


if __name__ == '__main__':
    main()
