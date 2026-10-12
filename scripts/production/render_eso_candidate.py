"""Render reviewed scoped ESO resources; no credentials, cluster writes or promotion."""
from __future__ import annotations

import argparse
import copy
import hashlib
import ipaddress
import json
import os
import shutil
import subprocess
from pathlib import Path

import yaml

from openbao_scoped_auth import NAMESPACE, SERVICES
from verify_eso_artifact import pin, published_pin

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
                    'serviceAccount': {'name': ACCOUNT + '-webhook'},
                    'replicaCount': 1, 'livenessProbe': {'enabled': True}},
        'certController': {'resources': resources, 'replicaCount': 1,
                           'serviceAccount': {'name': ACCOUNT + '-cert-controller'},
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


def network(api_addresses):
    if not isinstance(api_addresses, (list, tuple, set, frozenset)) or not 1 <= len(api_addresses) <= 16:
        raise ValueError('bounded exact API addresses required')
    if any(not isinstance(address, str) or str(ipaddress.IPv4Address(address)) != address
           for address in api_addresses):
        raise ValueError('canonical IPv4 API literals required')
    addresses = sorted({str(ipaddress.IPv4Address(address)) for address in api_addresses})
    if any(address.startswith('0.') or ipaddress.ip_address(address).is_reserved or
           ipaddress.ip_address(address).is_unspecified or
           ipaddress.ip_address(address).is_multicast or
           ipaddress.ip_address(address).is_loopback or
           ipaddress.ip_address(address).is_link_local for address in addresses):
        raise ValueError('unicast API addresses required')
    peers = [{'ipBlock': {'cidr': address + '/32'}} for address in addresses]
    namespace = lambda name: {'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': name}}}
    selector = lambda name: {'matchLabels': {'app.kubernetes.io/instance': ACCOUNT,
                                            'app.kubernetes.io/name': name}}
    port = lambda number: {'protocol': 'TCP', 'port': number}
    bao = namespace('hooshix-secrets') | {'podSelector': {'matchLabels': {'app.kubernetes.io/name': 'openbao'}}}
    controller = selector('external-secrets')

    def policy(name, pods, *, ingress=None, egress=None, ns=NAMESPACE):
        spec = {'podSelector': pods, 'policyTypes': []}
        for direction, rules in (('Ingress', ingress), ('Egress', egress)):
            if rules is not None:
                spec['policyTypes'].append(direction)
                spec[direction.lower()] = rules
        return {'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy',
                'metadata': {'name': name, 'namespace': ns}, 'spec': spec}

    return [policy('hooshix-eso-control-plane', {'matchLabels': {'app.kubernetes.io/instance': ACCOUNT}},
        ingress=[], egress=[{'to': peers, 'ports': [port(443), port(6443)]},
            {'to': [namespace('kube-system') | {'podSelector': {'matchLabels': {'k8s-app': 'kube-dns'}}}],
             'ports': [{'protocol': protocol, 'port': 53} for protocol in ('TCP', 'UDP')]}]),
        policy('hooshix-eso-webhook-api', selector('external-secrets-webhook'),
               ingress=[{'from': peers, 'ports': [port(10250)]}]),
        policy('hooshix-eso-mesh-ca', {'matchLabels': {'app.kubernetes.io/instance': ACCOUNT},
            'matchExpressions': [{'key': 'app.kubernetes.io/name', 'operator': 'In',
                                  'values': ['external-secrets', 'external-secrets-cert-controller']}]},
            egress=[{'to': [namespace('istio-system') | {'podSelector': {'matchLabels': {'app': 'istiod'}}}],
                     'ports': [port(15012)]}]),
        policy('hooshix-eso-openbao', controller,
               egress=[{'to': [bao], 'ports': [port(8200), port(15008)]}]),
        policy('hooshix-openbao-eso', bao['podSelector'], ns='hooshix-secrets',
            ingress=[{'from': [namespace(NAMESPACE) | {'podSelector': controller}],
                      'ports': [port(8200), port(15008)]}])]


def authorization():
    return {'apiVersion': 'security.istio.io/v1', 'kind': 'AuthorizationPolicy',
        'metadata': {'name': 'hooshix-openbao-eso', 'namespace': 'hooshix-secrets'},
        'spec': {'selector': {'matchLabels': {'app.kubernetes.io/name': 'openbao'}},
            'action': 'ALLOW', 'rules': [{'from': [{'source': {'principals': [
                'prod.sajtech.internal/ns/' + NAMESPACE + '/sa/' + ACCOUNT]}}],
                'to': [{'operation': {'ports': ['8200']}}]}]}}


def validate(items, selected):
    deployments = [item for item in items if item['kind'] == 'Deployment']
    if {item['metadata']['name'] for item in deployments} != {
            ACCOUNT, ACCOUNT + '-webhook', ACCOUNT + '-cert-controller'} or len(deployments) != 3:
        raise ValueError('exact three ESO workloads required')
    for item in deployments:
        pod = item['spec']['template']['spec']
        expected_mode = 'none' if item['metadata']['name'] == ACCOUNT + '-webhook' else 'ambient'
        if item['spec']['template'].get('metadata', {}).get('labels', {}).get(
                'istio.io/dataplane-mode') != expected_mode:
            raise ValueError('only the ESO webhook may opt out of Ambient')
        if (item['metadata'].get('namespace') != NAMESPACE or item['spec']['replicas'] != 1
                or pod.get('serviceAccountName') != item['metadata']['name']
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
                                                    ['validatingwebhookconfigurations'], ['endpointslices'])
                        or not set(rule['verbs']) <= {'get', 'list', 'watch', 'update', 'patch'}):
                    raise ValueError('only certificate metadata cluster permissions allowed')
                if item['kind'] == 'ClusterRole' and rule['resources'] == ['endpointslices'] and (
                        rule['apiGroups'] != ['discovery.k8s.io']
                        or rule['verbs'] != ['get', 'list', 'watch']):
                    raise ValueError('only read-only endpoint discovery allowed')
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


def render(chart: Path, helm: str, *, published: bool = False):
    selected = published_pin() if published else pin()
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
            item['spec']['template']['metadata']['labels']['istio.io/dataplane-mode'] = (
                'none' if item['metadata']['name'] == ACCOUNT + '-webhook' else 'ambient')
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
            # shortcut: upstream cert readiness uses an unscoped informer; remove when its cache can be namespace-bound.
            item['rules'].append({'apiGroups': ['discovery.k8s.io'], 'resources': ['endpointslices'],
                                  'verbs': ['get', 'list', 'watch']})
            items.append(local)
        if item['kind'] == 'ClusterRoleBinding' and item['metadata']['name'] == cert_name:
            local = copy.deepcopy(item)
            local['kind'] = 'RoleBinding'
            local['metadata']['namespace'] = NAMESPACE
            local['roleRef']['kind'] = 'Role'
            items.append(local)
    validate(items, selected)
    return {'apiVersion': 'v1', 'kind': 'List', 'items': items}


def crds(chart: Path, helm: str):
    # Render first to enforce the same chart and tool integrity boundary.
    render(chart, helm, published=True)
    result = subprocess.run([helm, 'template', ACCOUNT, str(chart), '--namespace', NAMESPACE,
        '--kube-version', '1.35.6', '--set', 'installCRDs=true', '--set', 'fullnameOverride=' + ACCOUNT,
        *[arg for name in ('externalsecret', 'generatorstate', 'secretstore')
          for arg in ('--show-only', 'templates/crds/' + name + '.yaml')]],
        check=True, capture_output=True, timeout=60)
    if len(result.stdout) > BOUND:
        raise ValueError('bounded scoped CRDs required')
    items = [item for item in yaml.safe_load_all(result.stdout) if item]
    if len(items) != 3 or {item['metadata']['name'] for item in items} != {
            'externalsecrets.external-secrets.io', 'secretstores.external-secrets.io',
            'generatorstates.generators.external-secrets.io'}:
        raise ValueError('exact scoped CRDs required')
    return items


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--chart', type=Path, required=True)
    parser.add_argument('--published-candidate', action='store_true',
                        help='render the reviewed published security digest; not deployment approval')
    parser.add_argument('--api-address', action='append', default=[],
                        help='exact discovered IPv4 API address; include bounded network/mesh resources')
    args = parser.parse_args()
    try:
        helm = shutil.which('helm')
        if not helm:
            raise ValueError('Helm required')
        result = render(args.chart, os.path.abspath(helm), published=args.published_candidate)
        if args.api_address:
            result['items'].extend(network(args.api_address))
            result['items'].append(authorization())
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError, yaml.YAMLError):
        parser.exit(1, 'ESO_RENDER=Failed; no cluster writes or installation\n')
    print(json.dumps(result, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
