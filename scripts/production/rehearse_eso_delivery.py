"""Synthetic ESO delivery inside the caller's disposable Kubernetes fixture only."""
from __future__ import annotations

import base64
import json
import uuid
from pathlib import Path

import yaml

import openbao_scoped_auth as auth
from rehearse_openbao_recovery import RehearsalFailed
from render_eso_candidate import ACCOUNT, BOUND, network, render
from verify_eso_artifact import published_pin


def external_secret(service: str, *, denied: bool = False):
    if service not in auth.SERVICES:
        raise ValueError('fixed service required')
    name = 'delivery-' + service + ('-denied' if denied else '')
    remote = auth.SERVICES[(auth.SERVICES.index(service) + 1) % len(auth.SERVICES)] if denied else service
    return {'apiVersion': 'external-secrets.io/v1', 'kind': 'ExternalSecret',
        'metadata': {'name': name, 'namespace': auth.NAMESPACE},
        'spec': {'refreshInterval': '5s', 'secretStoreRef': {'kind': 'SecretStore',
            'name': 'hooshix-' + service}, 'target': {'name': name, 'creationPolicy': 'Owner',
            'deletionPolicy': 'Retain'}, 'data': [{'secretKey': 'value',
            'remoteRef': {'key': 'production/' + remote + '/commissioning-probe', 'property': 'value'}}]}}


def rehearse(k, wait, run, chart: Path, helm: str, api, root: str, ca: str, audiences, addresses):
    manifest = render(chart, helm, published=True)
    crds = run([helm, 'template', ACCOUNT, str(chart), '--namespace', auth.NAMESPACE,
        '--kube-version', '1.35.6', '--set', 'installCRDs=true', '--set', 'fullnameOverride=' + ACCOUNT,
        '--show-only', 'templates/crds/externalsecret.yaml',
        '--show-only', 'templates/crds/generatorstate.yaml',
        '--show-only', 'templates/crds/secretstore.yaml'], timeout=60, bound=BOUND)
    items = [item for item in yaml.safe_load_all(crds) if item]
    expected = {'externalsecrets.external-secrets.io', 'secretstores.external-secrets.io',
                'generatorstates.generators.external-secrets.io'}
    if len(items) != 3 or {item['metadata']['name'] for item in items} != expected:
        raise RehearsalFailed('exact namespace-scoped CRDs required')

    def apply(value):
        k('apply', '--server-side', '-f', '-', data=json.dumps(value).encode(), timeout=60)

    for item in items:
        apply(item)
        k('wait', '--for=condition=Established', 'crd/' + item['metadata']['name'],
          '--timeout=30s', timeout=40)
    for policy in network(addresses):
        apply(policy)
    apply(manifest)
    for name in (ACCOUNT + '-cert-controller', ACCOUNT + '-webhook', ACCOUNT):
        print('ESO_DELIVERY_STEP=rollout-' + name, flush=True)
        try:
            k('-n', auth.NAMESPACE, 'rollout', 'status', 'deployment/' + name,
              '--timeout=180s', timeout=190)
        except RehearsalFailed:
            pods = json.loads(k('-n', auth.NAMESPACE, 'get', 'pods', '-l',
                'app.kubernetes.io/instance=' + ACCOUNT, '-o', 'json'))['items']
            for pod in pods:
                logs = k('-n', auth.NAMESPACE, 'logs', pod['metadata']['name'], '--tail=100')
                phrases = [phrase for phrase in ('forbidden', 'cannot list', 'cannot get',
                    'permission denied', 'i/o timeout', 'x509', 'connection refused',
                    'OOMKilled', 'failed to sync', 'no matches for kind') if phrase.encode() in logs]
                reasons = [reason for status in pod.get('status', {}).get('containerStatuses', [])
                    for state in (status.get('state', {}), status.get('lastState', {}))
                    for entry in state.values() for reason in ('OOMKilled', 'CrashLoopBackOff',
                        'ImagePullBackOff', 'ErrImagePull', 'Error') if entry.get('reason') == reason]
                print('ESO_DELIVERY_FAILURE=' + json.dumps({'phrases': phrases, 'reasons': reasons}), flush=True)
            raise
    pods = json.loads(k('-n', auth.NAMESPACE, 'get', 'pods', '-l',
                        'app.kubernetes.io/instance=' + ACCOUNT, '-o', 'json'))['items']
    image = published_pin()['image']
    if len(pods) != 3 or any(pod['spec']['containers'][0]['image'] != image or
            not pod['status']['containerStatuses'][0]['imageID'].endswith(image.split('@')[1]) for pod in pods):
        raise RehearsalFailed('native ESO digest mismatch')
    apply({'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {
        'name': 'hooshix-openbao-ca', 'namespace': auth.NAMESPACE}, 'data': {'ca.crt': ca}})
    values = []
    for service in auth.SERVICES:
        value = 'ci-eso-only-' + uuid.uuid4().hex
        values.append(value)
        path = 'hooshix/data/production/' + service + '/commissioning-probe'
        api.call(path, 'POST', {'data': {'value': value}}, root)
        apply(auth.store(service, audiences))
        print('ESO_DELIVERY_STEP=store-' + service, flush=True)
        try:
            k('-n', auth.NAMESPACE, 'wait', '--for=condition=Ready',
              'secretstore/hooshix-' + service, '--timeout=60s', timeout=70)
        except RehearsalFailed:
            status = json.loads(k('-n', auth.NAMESPACE, 'get', 'secretstore/hooshix-' + service,
                                  '-o', 'json')).get('status', {})
            text = json.dumps(status).lower()
            phrases = [phrase for phrase in ('forbidden', 'cannot get', 'cannot create',
                'permission denied', 'i/o timeout', 'x509', 'connection refused', 'not found',
                'invalid role', 'unable to log in', 'unable to login', '403', '400',
                'invalidproviderconfig', 'invalidprovidervalidation') if phrase in text]
            print('ESO_STORE_FAILURE=' + json.dumps({'phrases': phrases}), flush=True)
            logs = k('-n', auth.NAMESPACE, 'logs', 'deployment/' + ACCOUNT, '--tail=100').lower()
            phrases = [phrase for phrase in ('forbidden', 'cannot get', 'cannot create',
                'cannot find secrets', 'permission denied', 'i/o timeout', 'x509', 'dial tcp',
                'connection refused', 'not found', 'invalid role', 'lookup-self', '403', '400',
                '404', '503', 'audience', 'certificate', 'no such host') if phrase.encode() in logs]
            print('ESO_PROVIDER_FAILURE=' + json.dumps({'phrases': phrases}), flush=True)
            raise
        obj = external_secret(service)
        apply(obj)
        k('-n', auth.NAMESPACE, 'wait', '--for=condition=Ready',
          'externalsecret/' + obj['metadata']['name'], '--timeout=60s', timeout=70)
        def delivered():
            secret = json.loads(k('-n', auth.NAMESPACE, 'get', 'secret', obj['metadata']['name'], '-o', 'json'))
            return base64.b64decode(secret['data']['value'], validate=True).decode() == value
        wait(delivered)
        rotated = 'ci-eso-rotated-' + uuid.uuid4().hex
        values.append(rotated)
        api.call(path, 'POST', {'data': {'value': rotated}}, root)
        value = rotated
        wait(delivered)
    denied = external_secret(auth.SERVICES[0], denied=True)
    apply(denied)
    k('-n', auth.NAMESPACE, 'wait', '--for=condition=Ready=False',
      'externalsecret/' + denied['metadata']['name'], '--timeout=60s', timeout=70)
    secrets = json.loads(k('-n', auth.NAMESPACE, 'get', 'secrets', '-o', 'json'))['items']
    if any(secret['metadata']['name'] == denied['metadata']['name'] for secret in secrets):
        raise RehearsalFailed('cross-service secret delivery was not denied')
    for pod in pods:
        logs = k('-n', auth.NAMESPACE, 'logs', pod['metadata']['name'], '--tail=-1')
        if any(value.encode() in logs for value in values + [root]):
            raise RehearsalFailed('ESO container log leak')
    for service in auth.SERVICES:
        obj = external_secret(service)
        k('-n', auth.NAMESPACE, 'delete', 'externalsecret/' + obj['metadata']['name'])
        k('-n', auth.NAMESPACE, 'wait', '--for=delete', 'secret/' + obj['metadata']['name'],
          '--timeout=60s', timeout=70)
    return {'image': image, 'six_identity_delivery_rotation': 'Passed',
            'cross_service_denial': 'Passed', 'owned_secret_cleanup': 'Passed',
            'container_log_privacy': 'Passed', 'production_target': 'Not verified'}
