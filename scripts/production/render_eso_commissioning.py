"""Public, bounded ESO bootstrap desired state; no credentials or cluster writes."""
from __future__ import annotations

import copy
from datetime import datetime, timezone

import bootstrap_intermediate_csr as custody
import build_patched_platform as build
import openbao_scoped_auth as auth
import render_eso_candidate as eso
import render_mesh_security_upgrade as mesh
import render_platform_admission as admission
from verify_eso_artifact import pin, published_pin

MANAGER = 'hooshix-eso-commissioning'
NAMESPACES = [*admission.NAMESPACES, auth.NAMESPACE]
SELECTOR = {'matchExpressions': [{'key': 'kubernetes.io/metadata.name',
                                  'operator': 'In', 'values': NAMESPACES}]}


def predicate():
    selected = pin()['published_security_candidate']
    recipe = build.recipe(build.CANDIDATE_RECIPE)
    source = recipe['sources']['eso']
    return {'buildDefinition': {'buildType': build.BUILD_TYPE, 'externalParameters': {
        'component': 'eso', 'source': source, 'recipe_sha256': selected['recipe_sha256'],
        'modules': recipe['modules'], 'module_files_sha256': selected['module_files_sha256'],
        'source_patches': [],
        'builder': recipe['builder'], 'runtime_base': build.base_image('eso'),
        'go_version': recipe['go_version'], 'repository_revision': selected['repository_revision']},
        'internalParameters': {}, 'resolvedDependencies': [{
            'uri': 'git+https://github.com/' + source['repository'],
            'digest': {'gitCommit': source['revision']}}]},
        'runDetails': {'builder': {'id': admission.EXPECTED_CERTIFICATE_IDENTITY},
            'metadata': {'invocationId': 'github:' + selected['publication_run_id'] + ':1'}}}


def source_policy():
    selected = pin()['published_security_candidate']
    value = admission.image_policy(auth.NAMESPACE, [selected['image']], selected['repository_revision'])
    value['metadata']['name'] = 'hooshix-eso-source'
    value['spec']['validations'][2] = admission.validation('platform source build identity rejected',
        mesh.provenance_expression(predicate()))
    value['spec']['validations'][2].pop('messageExpression', None)
    return value


def validate_publication(directory):
    selected = pin()['published_security_candidate']
    publication = build.load(directory / 'publication.json')
    folder = directory / 'eso'
    receipt = build.load(folder / 'receipt.json')
    if (publication.get('schema_version') != 1 or publication.get('publication') != 'Passed'
            or publication.get('provenance_kind') != 'patched-upstream-source-build'
            or publication.get('repository_revision') != selected['repository_revision']
            or publication.get('images', {}).get('eso') != selected['image']
            or receipt.get('component') != 'eso'
            or receipt.get('module_files_sha256') != selected['module_files_sha256']
            or build.load(folder / 'source-provenance.json') != predicate()):
        raise ValueError('exact ESO publication and recipe required')
    evidence = build.validate_local(folder, 'eso', receipt['image_config_digest'],
                                    datetime.now(timezone.utc), build.CANDIDATE_RECIPE)
    if any(receipt.get(key) != value for key, value in evidence.items()):
        raise ValueError('ESO scan receipt mismatch')
    if build.published_image(selected['image'].split('@')[0], build.load(folder / 'syft.json')['source'],
                             receipt['image_config_digest']) != selected['image']:
        raise ValueError('ESO manifest/configuration mismatch')
    build.registry_scan(folder, selected['image'], datetime.now(timezone.utc))


def render(chart, helm):
    manifest = eso.render(chart, helm, published=True)
    pods = {}
    for item in manifest['items']:
        item['metadata'].setdefault('labels', {})['app.kubernetes.io/managed-by'] = MANAGER
        if item['kind'] == 'Deployment':
            pod = item['spec']['template']['spec']
            pod.setdefault('securityContext', {})['seccompProfile'] = {'type': 'RuntimeDefault'}
            pod['imagePullSecrets'] = [{'name': admission.PULL_SECRET}]
            pods[pod['serviceAccountName']] = pod
    boundary = admission.hardening(auth.NAMESPACE, pods)
    boundary['spec']['matchConstraints']['namespaceSelector'] = copy.deepcopy(SELECTOR)
    modes = ' && '.join('(' + 'object.spec.serviceAccountName != ' + admission.literal(name)
        + ' || (has(object.metadata.labels) && "istio.io/dataplane-mode" in object.metadata.labels'
        + ' && object.metadata.labels["istio.io/dataplane-mode"] == '
        + admission.literal('none' if name.endswith('-webhook') else 'ambient') + '))' for name in pods)
    boundary['spec']['validations'].append(admission.validation('ESO Ambient enrollment rejected',
        'object.metadata.namespace != "platform-apps" || (' + modes + ')'))
    resources = eso.crds(chart, helm)
    for item in resources:
        item['metadata'].setdefault('labels', {})['app.kubernetes.io/managed-by'] = MANAGER
    return {'schema_version': 1, 'installation_id': 'hooshix-production',
        'profile': 'production-single-server', 'image': published_pin()['image'],
        'mesh_images': {**{n: v['image'] for n, v in admission.mesh_publication.targets().items()},
                        **mesh.pin()['images']},
        'public_ca_sha256': custody.ROOT_SHA256,
        'crds': resources, 'manifest': manifest, 'pods': pods,
        'policies': [boundary, *admission.split_image_policy(source_policy())],
        'network': eso.network(['10.43.0.1']), 'authorization': eso.authorization(),
        'stores': [auth.store(name, ['https://kubernetes.default.svc']) for name in auth.SERVICES],
        'deployment': 'Not verified'}
