"""Render reviewed scoped ESO resources; no credentials, cluster writes or promotion."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path

import yaml

from openbao_scoped_auth import NAMESPACE, SERVICES
from verify_eso_artifact import pin

ACCOUNT = 'hooshix-eso'
BOUND = 16 * 1024 * 1024
SELECTOR = {'matchLabels': {'kubernetes.io/metadata.name': NAMESPACE}}


def values():
    resources = {'requests': {'cpu': '50m', 'memory': '64Mi', 'ephemeral-storage': '16Mi'},
                 'limits': {'cpu': '500m', 'memory': '256Mi', 'ephemeral-storage': '64Mi'}}
    return {'fullnameOverride': ACCOUNT, 'scopedNamespace': NAMESPACE, 'scopedRBAC': True,
        'installCRDs': False, 'replicaCount': 1, 'leaderElect': False,
        'crds': {'createClusterSecretStore': False, 'createClusterExternalSecret': False,
                 'createClusterGenerator': False, 'createClusterPushSecret': False,
                 'createPushSecret': False},
        'processClusterExternalSecret': False, 'processClusterStore': False,
        'processClusterPushSecret': False, 'processPushSecret': False,
        'processClusterGenerator': False, 'systemAuthDelegator': False,
        'openshiftFinalizers': False, 'genericTargets': {'enabled': False},
        'rbac': {'serviceAccountTokenCreate': False, 'leaderElection': {'create': False},
                 'servicebindings': {'create': False}, 'aggregateToView': False,
                 'aggregateToEdit': False, 'aggregateToAdmin': False},
        'serviceAccount': {'name': ACCOUNT}, 'resources': resources,
        'concurrent': 1, 'log': {'level': 'warn'},
        'livenessProbe': {'enabled': True}, 'readinessProbe': {'enabled': True},
        'webhook': {'resources': resources, 'failurePolicy': 'Fail',
                    'replicaCount': 1, 'livenessProbe': {'enabled': True}},
        'certController': {'resources': resources, 'replicaCount': 1,
                           'enablePartialCache': True,
                           'livenessProbe': {'enabled': True}}}


def token_delegation():
    name = 'hooshix-eso-delivery-tokenrequest'
    metadata = {'name': name, 'namespace': NAMESPACE}
    return [{'apiVersion': 'rbac.authorization.k8s.io/v1', 'kind': 'Role',
        'metadata': metadata, 'rules': [{'apiGroups': [''], 'resources': ['serviceaccounts/token'],
            'resourceNames': ['eso-' + service for service in SERVICES], 'verbs': ['create']}]},
        {'apiVersion': 'rbac.authorization.k8s.io/v1', 'kind': 'RoleBinding', 'metadata': metadata,
         'subjects': [{'kind': 'ServiceAccount', 'name': ACCOUNT, 'namespace': NAMESPACE}],
         'roleRef': {'apiGroup': 'rbac.authorization.k8s.io', 'kind': 'Role', 'name': name}}]


def validate(items, selected):
    deployments = [item for item in items if item['kind'] == 'Deployment']
    if {item['metadata']['name'] for item in deployments} != {
            ACCOUNT, ACCOUNT + '-webhook', ACCOUNT + '-cert-controller'} or len(deployments) != 3:
        raise ValueError('exact three ESO workloads required')
    for item in deployments:
        pod = item['spec']['template']['spec']
        if (item['metadata'].get('namespace') != NAMESPACE or item['spec']['replicas'] != 1
                or pod.get('serviceAccountName') in (None, '', 'default')
                or any(pod.get(flag, False) for flag in ('hostNetwork', 'hostPID', 'hostIPC'))
                or pod.get('initContainers') or pod.get('ephemeralContainers')
                or any('hostPath' in volume for volume in pod.get('volumes', []))
                or len(pod['containers']) != 1):
            raise ValueError('bounded namespace-only ESO workload required')
        container = pod['containers'][0]
        security = container['securityContext']
        if (container['image'] != selected['image'] or security.get('runAsNonRoot') is not True
                or security.get('runAsUser', 0) <= 0 or security.get('privileged', False)
                or security.get('allowPrivilegeEscalation') is not False
                or security.get('readOnlyRootFilesystem') is not True
                or security.get('capabilities') != {'drop': ['ALL']}
                or security.get('seccompProfile') != {'type': 'RuntimeDefault'}
                or container['resources'] != values()['resources']
                or not container.get('readinessProbe') or not container.get('livenessProbe')):
            raise ValueError('pinned restricted ESO workload required')
        if item['metadata']['name'] == ACCOUNT:
            args = container['args']
            required = ['--namespace=' + NAMESPACE, '--enable-cluster-store-reconciler=false',
                '--enable-cluster-external-secret-reconciler=false',
                '--enable-cluster-push-secret-reconciler=false', '--enable-push-secret-reconciler=false']
            if not all(arg in args for arg in required):
                raise ValueError('scoped read-only reconcilers required')
    for item in items:
        if item['kind'] not in ('ClusterRole', 'ClusterRoleBinding', 'ValidatingWebhookConfiguration'):
            if item['metadata'].get('namespace') != NAMESPACE:
                raise ValueError('namespace boundary required')
        if item['kind'] == 'Secret' and (item.get('data') or item.get('stringData')):
            raise ValueError('credential-free candidate required')
        if item['kind'] in ('Role', 'ClusterRole'):
            for rule in item['rules']:
                if '*' in rule['resources'] or '*' in rule['verbs']:
                    raise ValueError('wildcard ESO RBAC rejected')
                if item['kind'] == 'ClusterRole' and 'secrets' in rule['resources']:
                    raise ValueError('cluster-wide secret access rejected')
                if item['kind'] == 'ClusterRole' and (
                        item['metadata']['name'] != ACCOUNT + '-cert-controller'
                        or rule['resources'] not in (['customresourcedefinitions'],
                                                    ['validatingwebhookconfigurations'])
                        or not set(rule['verbs']) <= {'get', 'list', 'watch', 'update', 'patch'}):
                    raise ValueError('only certificate metadata cluster permissions allowed')
                if 'serviceaccounts/token' in rule['resources']:
                    if item != token_delegation()[0]:
                        raise ValueError('only exact six-account TokenRequest delegation allowed')
        if item['kind'] == 'ValidatingWebhookConfiguration':
            for webhook in item['webhooks']:
                if (webhook['failurePolicy'] != 'Fail' or webhook['timeoutSeconds'] != 5
                        or webhook.get('namespaceSelector') != SELECTOR):
                    raise ValueError('fail-closed bounded ESO webhooks required')
    if not all(item in items for item in token_delegation()):
        raise ValueError('exact delivery token delegation required')


def render(chart: Path, helm: str):
    selected = pin()
    if chart.is_symlink() or not chart.is_file() or chart.stat().st_size > BOUND:
        raise ValueError('bounded regular official chart required')
    if hashlib.sha256(chart.read_bytes()).hexdigest() != selected['chart']['sha256']:
        raise ValueError('ESO chart integrity failed')
    environment = {'PATH': '/usr/bin:/bin', 'HOME': '/tmp', 'LC_ALL': 'C'}
    version = subprocess.run([helm, 'version', '--short'], check=True, capture_output=True,
                             timeout=10, env=environment)
    if not version.stdout.startswith(b'v4.2.4+'):
        raise ValueError('reviewed Helm 4.2.4 required')
    result = subprocess.run([helm, 'template', ACCOUNT, str(chart), '--namespace', NAMESPACE,
        '--kube-version', '1.35.6', *[argument for key, value in values().items()
            for argument in ('--set-json', key + '=' + json.dumps(value))]],
        check=True, capture_output=True, timeout=60, env=environment)
    if len(result.stdout) > BOUND:
        raise ValueError('ESO render output bound exceeded')
    items = [item for item in yaml.safe_load_all(result.stdout) if item is not None]
    items = [item for item in items if not (item['kind'] == 'Role'
             and item['metadata']['name'] in (ACCOUNT + '-view', ACCOUNT + '-edit'))]
    # This official chart has tag-only image templates. Normalize only its exact expected tag.
    for item in items:
        if item['kind'] == 'Deployment':
            for container in item['spec']['template']['spec']['containers']:
                if container['image'] != 'ghcr.io/external-secrets/external-secrets:v' + selected['version']:
                    raise ValueError('unexpected upstream ESO image')
                container['image'] = selected['image']
        if item['kind'] == 'ValidatingWebhookConfiguration':
            for webhook in item['webhooks']:
                webhook['namespaceSelector'] = copy.deepcopy(SELECTOR)
    items.extend(token_delegation())
    # Upstream cert-controller RBAC is cluster-wide even with scopedRBAC enabled.
    cert_name = ACCOUNT + '-cert-controller'
    for item in list(items):
        if item['kind'] == 'ClusterRole' and item['metadata']['name'] == cert_name:
            local = copy.deepcopy(item)
            local['kind'] = 'Role'
            local['metadata']['namespace'] = NAMESPACE
            local['rules'] = [rule for rule in item['rules']
                              if rule['apiGroups'] in ([''], ['discovery.k8s.io'], ['coordination.k8s.io'])]
            item['rules'] = [rule for rule in item['rules'] if rule not in local['rules']]
            items.append(local)
        if item['kind'] == 'ClusterRoleBinding' and item['metadata']['name'] == cert_name:
            local = copy.deepcopy(item)
            local['kind'] = 'RoleBinding'
            local['metadata']['namespace'] = NAMESPACE
            local['roleRef']['kind'] = 'Role'
            items.append(local)
    validate(items, selected)
    return {'apiVersion': 'v1', 'kind': 'List', 'items': items}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--chart', type=Path, required=True)
    args = parser.parse_args()
    try:
        helm = shutil.which('helm')
        if not helm:
            raise ValueError('Helm required')
        result = render(args.chart, os.path.abspath(helm))
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError, yaml.YAMLError):
        parser.exit(1, 'ESO_RENDER=Failed; no cluster writes or installation\n')
    print(json.dumps(result, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
