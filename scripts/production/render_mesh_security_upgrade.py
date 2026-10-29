"""Render only the reviewed two-image mesh upgrade; never write to a cluster."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path

import build_patched_platform as build
import render_platform_admission as admission

PIN = build.ROOT / 'infrastructure/istio/security-upgrade.json'
COMPONENTS = ('istiod', 'cni')


def pin() -> dict:
    value = build.load(PIN)
    if (value.get('schema_version') != 1 or set(value.get('images', {})) != set(COMPONENTS)
            or value.get('production_promotion') != 'Not verified'
            or not re.fullmatch(r'[a-f0-9]{40}', value.get('repository_revision', ''))
            or not re.fullmatch(r'[a-f0-9]{40}', value.get('predecessor_import_revision', ''))
            or not re.fullmatch(r'[1-9][0-9]{0,19}', value.get('publication_run', ''))
            or type(value.get('publication_attempt')) is not int
            or not 1 <= value['publication_attempt'] <= 1000
            or value.get('recipe_sha256') != hashlib.sha256(build.CANDIDATE_RECIPE.read_bytes()).hexdigest()
            or set(value.get('module_files_sha256', {})) != {'go.mod', 'go.sum'}
            or any(not re.fullmatch(r'[a-f0-9]{64}', h)
                   for h in value['module_files_sha256'].values())):
        raise ValueError('reviewed source upgrade identity required')
    for component, image in value['images'].items():
        if not re.fullmatch('ghcr.io/hasanjodatshandi/hooshix/platform-' + component
                            + r'-patched-private@sha256:[a-f0-9]{64}', image):
            raise ValueError('exact source image destination required')
    return value


def predicate(component: str) -> dict:
    if component not in COMPONENTS:
        raise ValueError('only istiod and CNI are authorized')
    selected, identity = build.recipe(build.CANDIDATE_RECIPE), pin()
    source = selected['sources']['istio']
    return {'buildDefinition': {'buildType': build.BUILD_TYPE, 'externalParameters': {
        'component': component, 'source': source, 'recipe_sha256': identity['recipe_sha256'],
        'modules': selected['modules'], 'module_files_sha256': identity['module_files_sha256'],
        'source_patches': [],
        'builder': selected['builder'], 'runtime_base': build.base_image(component),
        'go_version': selected['go_version'], 'repository_revision': identity['repository_revision']},
        'internalParameters': {}, 'resolvedDependencies': [{
            'uri': 'git+https://github.com/' + source['repository'],
            'digest': {'gitCommit': source['revision']}}]},
        'runDetails': {'builder': {'id': admission.EXPECTED_CERTIFICATE_IDENTITY},
            'metadata': {'invocationId': 'github:' + identity['publication_run'] + ':'
                         + str(identity['publication_attempt'])}}}


def exact_payload(path: str, value) -> str:
    """Compare heterogeneous JSON structurally without CEL map type inference."""
    if isinstance(value, dict):
        return path + '.size() == ' + str(len(value)) + ''.join(
            ' && (' + exact_payload(path + '[' + admission.literal(key) + ']', child) + ')'
            for key, child in value.items())
    if isinstance(value, list):
        return path + '.size() == ' + str(len(value)) + ''.join(
            ' && (' + exact_payload(path + '[' + str(index) + ']', child) + ')'
            for index, child in enumerate(value))
    return path + ' == ' + admission.literal(value)


def provenance_expression(expected: dict) -> str:
    return ('variables.provenanceVerified && images.containers.map(image, '
            'extractPayload(image, attestations.provenance).predicate).map(payload, '
            + exact_payload('payload', expected)
            + ').all(e, e)')


def source_policy(component: str) -> dict:
    expected = predicate(component)
    result = admission.image_policy('istio-system', [pin()['images'][component]],
                                    pin()['repository_revision'])
    result['metadata']['name'] = 'hooshix-mesh-source-' + component
    result['spec']['validations'][2] = admission.validation(
        'platform source build identity rejected',
        provenance_expression(expected))
    return result


def validate_publication(directory: Path, now: datetime) -> dict:
    """Validate public artifact bytes; native admission still verifies signatures."""
    identity = pin()
    publication = build.load(directory / 'publication.json')
    if (publication.get('schema_version') != 1
            or publication.get('publication') != 'Passed'
            or publication.get('provenance_kind') != 'patched-upstream-source-build'
            or publication.get('repository_revision') != identity['repository_revision']
            or any(publication.get('images', {}).get(c) != identity['images'][c] for c in COMPONENTS)):
        raise ValueError('exact published source upgrade required')
    for component in COMPONENTS:
        folder = directory / component
        receipt = build.load(folder / 'receipt.json')
        if (receipt.get('component') != component
                or receipt.get('module_files_sha256') != identity['module_files_sha256']
                or build.load(folder / 'source-provenance.json') != predicate(component)):
            raise ValueError('exact source recipe and module locks required')
        evidence = build.validate_local(folder, component, receipt['image_config_digest'], now,
                                        build.CANDIDATE_RECIPE)
        if any(receipt.get(key) != value for key, value in evidence.items()):
            raise ValueError('source receipt differs from actual scanned image')
        image = identity['images'][component]
        if build.published_image(image.split('@')[0], build.load(folder / 'syft.json')['source'],
                                 receipt['image_config_digest']) != image:
            raise ValueError('published digest/configuration mismatch')
        build.registry_scan(folder, image, now)
    return publication


def render() -> dict:
    old_images = {name: record['image'] for name, record in admission.mesh_publication.targets().items()}
    _, old_pods = admission.mesh_workloads(old_images, admission.mesh.candidate())
    new_images = {**old_images, **pin()['images']}
    _, new_pods = admission.mesh_workloads(new_images, admission.mesh.candidate())
    before = admission.hardening('istio-system', old_pods)
    after = admission.hardening('istio-system', new_pods)
    transition = copy.deepcopy(after)
    old_expression = before['spec']['validations'][0]['expression']
    new_expression = after['spec']['validations'][0]['expression']
    transition['spec']['validations'][0]['expression'] = '(' + old_expression + ') || (' + new_expression + ')'
    resources = {'istiod': ('deployment', 'istiod', 'discovery'),
                 'cni': ('daemonset', 'istio-cni-node', 'install-cni')}
    return {'schema_version': 1, 'installation_id': 'hooshix-production',
        'profile': 'production-single-server', 'publication': pin(),
        'changes': [{'component': name, 'kind': kind, 'name': resource, 'container': container,
                     'before': old_images[name], 'after': new_images[name],
                     'expected_pod': old_pods['istiod' if name == 'istiod' else 'istio-cni']}
                    for name, (kind, resource, container) in resources.items()],
        'source_policies': [part for component in COMPONENTS
                            for part in admission.split_image_policy(source_policy(component))],
        'boundary_before': before, 'boundary_transition': transition, 'boundary_after': after,
        'deployment': 'Not verified'}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--publication', type=Path, required=True)
    args = parser.parse_args()
    try:
        validate_publication(args.publication, datetime.now(timezone.utc))
        print(json.dumps(render(), separators=(',', ':')))
    except (ValueError, KeyError, TypeError, OSError):
        parser.exit(1, 'MESH_SECURITY_UPGRADE=Failed; no cluster writes\n')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
