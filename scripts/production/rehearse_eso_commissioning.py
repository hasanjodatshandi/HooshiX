"""Exercise the production ESO installer and delivery in the owned CI cluster only."""
from __future__ import annotations

import copy
import hashlib
import json
import re

import commission_platform as host
import install_eso
import render_eso_commissioning as renderer
from rehearse_eso_delivery import rehearse as delivery
from rehearse_platform_admission import admission_result, apply, policy_ready
from rehearse_openbao_recovery import RehearsalFailed


def rehearse(k, wait, run, chart, helm, api, root, ca, audiences, addresses):
    plan = renderer.render(chart, helm)
    # Fixture-only CA substitution: the target bundle always pins the owner's existing Root.
    plan['public_ca_sha256'] = hashlib.sha256(ca.encode()).hexdigest()
    apply(k, {'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {
        'name': 'hooshix-openbao-ca', 'namespace': install_eso.NAMESPACE}, 'data': {'ca.crt': ca}})

    def adapter(*args, body=None, **options):
        try:
            return k(*args, data=json.dumps(body).encode() if body is not None else None, **options)
        except RehearsalFailed as error:
            # Only public resource identifiers; never body, token arguments or response text.
            verb = next((value for value in ('get', 'create', 'patch', 'apply', 'wait', 'rollout')
                         if value in args), 'other')
            print('ESO_FIXTURE_FAILED_OPERATION=' + verb, flush=True)
            if 'get' in args:
                index = args.index('get')
                print('ESO_FIXTURE_FAILED_GET=' + json.dumps(list(args[index + 1:index + 3])), flush=True)
            if verb == 'wait':
                resource = next((value for value in args if '/' in value and not value.startswith('-')), '')
                print('ESO_FIXTURE_FAILED_WAIT=' + json.dumps(resource), flush=True)
            raise host.custody.BootstrapFailed('NATIVE_ESO_FIXTURE_OPERATION_FAILED') from error

    try:
        result = install_eso.execute(plan, kube=adapter)
        install_eso.execute(plan, kube=adapter)  # Real idempotent owned-object reconciliation.
    except ValueError as error:
        if re.fullmatch(r'ESO_[A-Z_]{1,80}', str(error)):
            print('ESO_FIXTURE_CHECK_FAILED=' + str(error), flush=True)
        raise
    original = renderer.source_policy()
    # Test the signed payload itself, not just digest/Pod schema.
    apply(k, original)
    policy_ready(k, original)
    deployment = next(obj for obj in plan['manifest']['items'] if obj['kind'] == 'Deployment'
                      and obj['metadata']['name'] == install_eso.ACCOUNT)
    test = {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': 'eso-source-probe',
        'namespace': install_eso.NAMESPACE, 'labels': deployment['spec']['template']['metadata']['labels']},
        'spec': deployment['spec']['template']['spec']}
    admission_result(k, test, wait, public_schema=False)
    for field in ('wrong_signer', 'missing_sbom', 'component', 'module_files_sha256', 'repository_revision'):
        bad = copy.deepcopy(original)
        message = b'platform source build identity rejected'
        if field == 'wrong_signer':
            bad['spec']['attestors'][0]['cosign']['keyless']['identities'][0]['subject'] = 'https://wrong.invalid'
            message = b'platform image signature verification failed'
        elif field == 'missing_sbom':
            bad['spec']['attestations'][1]['intoto']['type'] = 'https://missing.invalid/sbom'
            message = b'platform signed SBOM verification failed'
        else:
            expected = renderer.predicate()
            expected['buildDefinition']['externalParameters'][field] = 'wrong'
            bad['spec']['validations'][2]['expression'] = renderer.mesh.provenance_expression(expected)
        try:
            apply(k, bad)
            admission_result(k, test, wait, expected=1, message=message, public_schema=False)
        finally:
            apply(k, original)
    k('delete', original['kind'], original['metadata']['name'])
    result['source_identity_positive_negative'] = 'Passed'
    result['idempotent_reconciliation'] = 'Passed'
    result['delivery'] = delivery(k, wait, run, chart, helm, api, root, ca, audiences, addresses, installed=True)
    return result
