"""Render pinned single-server mesh commissioning resources; no cluster writes."""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NAMESPACE = 'istio-system'
BOUND = 2 * 1024 * 1024
COMPONENTS = ('base', 'istiod', 'cni', 'ztunnel')


def pins():
    return dict(line.split('=', 1) for line in (ROOT / 'infrastructure/istio/pins.env')
                .read_text().splitlines() if line and not line.startswith('#'))


def values(component, pin):
    result = {'global': {'hub': 'docker.io/istio', 'tag': pin['ISTIO_VERSION'],
                        'trustDomain': pin['ISTIO_TRUST_DOMAIN']}}
    if component == 'base':
        return result
    image_component = 'PILOT' if component == 'istiod' else component.upper()
    digest = pin['ISTIO_' + image_component + '_DISTROLESS_AMD64_DIGEST']
    if not re.fullmatch(r'sha256:[a-f0-9]{64}', digest):
        raise ValueError('mesh digest required')
    image_name = {'istiod': 'pilot', 'cni': 'install-cni', 'ztunnel': 'ztunnel'}[component]
    result['image'] = 'docker.io/istio/' + image_name + '@' + digest
    result['profile'] = 'ambient'
    resources = {'requests': {'cpu': '100m', 'memory': '128Mi'},
                 'limits': {'cpu': '1', 'memory': '512Mi'}}
    if component == 'istiod':
        resources['requests']['memory'] = '256Mi'
        result.update({'autoscaleEnabled': False, 'replicaCount': 1,
                       'resources': resources,
                       'meshConfig': {'trustDomain': pin['ISTIO_TRUST_DOMAIN']}})
        result['global'].update({'meshID': 'platform-prod', 'multiCluster': {'clusterName': 'hooshix-production-1'}})
    elif component == 'cni':
        result.update({'resources': resources,
                       'cniConfDir': '/var/lib/rancher/k3s/agent/etc/cni/net.d',
                       'cniBinDir': '/var/lib/rancher/k3s/data/current/bin/'})
    else:
        result.update({'resources': resources, 'image': 'docker.io/istio/ztunnel@' + digest,
                       'meshID': 'platform-prod', 'clusterName': 'hooshix-production-1',
                       'trustDomain': pin['ISTIO_TRUST_DOMAIN']})
    return result


def validate_node_exception(component, pod, pin):
    """Bound owner-approved network privileges; this is not runtime admission evidence."""
    account, name, group, capabilities, paths = {
        'cni': ('istio-cni', 'install-cni', 0,
                {'NET_ADMIN', 'NET_RAW', 'SYS_PTRACE', 'SYS_ADMIN', 'DAC_OVERRIDE'},
                {'/proc', '/var/run/ztunnel', '/var/run/istio-cni', '/var/run/netns',
                 values('cni', pin)['cniConfDir'], values('cni', pin)['cniBinDir']}),
        'ztunnel': ('ztunnel', 'istio-proxy', 1337,
                    {'NET_ADMIN', 'SYS_ADMIN', 'NET_RAW'}, {'/var/run/ztunnel'}),
    }[component]
    containers = pod.get('containers', [])
    if (pod.get('serviceAccountName') != account or pod.get('hostNetwork', False)
            or pod.get('hostPID', False) or pod.get('hostIPC', False)
            or pod.get('initContainers') or pod.get('ephemeralContainers')
            or len(containers) != 1 or containers[0].get('name') != name):
        raise ValueError('network exception identity or host namespace rejected')
    context = containers[0].get('securityContext', {})
    caps = context.get('capabilities', {})
    if (context.get('privileged') is not False or context.get('runAsUser') != 0
            or context.get('runAsGroup') != group or context.get('runAsNonRoot') is not False
            or caps.get('drop') != ['ALL'] or set(caps.get('add', [])) != capabilities):
        raise ValueError('network exception security context rejected')
    if component == 'ztunnel' and (context.get('allowPrivilegeEscalation') is not True
                                  or context.get('readOnlyRootFilesystem') is not True):
        raise ValueError('ztunnel context differs from approved baseline')
    mounts = [v['hostPath']['path'] for v in pod.get('volumes', []) if 'hostPath' in v]
    if len(mounts) != len(paths) or set(mounts) != paths:
        raise ValueError('network exception host mounts rejected')


def render(helm, component, pin):
    import yaml
    version = pin['ISTIO_VERSION']
    if version != '1.30.3' or pin['ISTIO_TRUST_DOMAIN'] != 'prod.sajtech.internal':
        raise ValueError('current mesh baseline required')
    chart = ROOT / f'infrastructure/istio/chart/{version}/{component}-{version}.tgz'
    if hashlib.sha256(chart.read_bytes()).hexdigest() != pin['ISTIO_' + component.upper() + '_CHART_SHA256']:
        raise ValueError('chart integrity failed')
    release = {'base': 'istio-base', 'cni': 'istio-cni'}.get(component, component)
    result = subprocess.run([helm, 'template', release, str(chart), '--namespace', NAMESPACE,
                            '--kube-version', '1.35.6', '--include-crds', '--set-json',
                            'global=' + json.dumps(values(component, pin)['global']),
                            *[argument for key, value in values(component, pin).items() if key != 'global'
                              for argument in ('--set-json', key + '=' + json.dumps(value))]],
                            check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=60, env={'PATH': '/usr/bin:/bin', 'HOME': '/tmp', 'LC_ALL': 'C'})
    if result.returncode or len(result.stdout) > BOUND:
        raise ValueError('bounded public mesh render failed')
    items = [item for item in yaml.safe_load_all(result.stdout) if item is not None]
    if not items or not all(isinstance(item, dict) and 'kind' in item for item in items):
        raise ValueError('mesh render schema failed')
    for item in items:
        if item['kind'] in ('Deployment', 'DaemonSet'):
            pod = item['spec']['template']['spec']
            expected_image = values(component, pin)['image']
            for container in pod['containers'] + pod.get('initContainers', []):
                image = container['image']
                if image != expected_image:
                    raise ValueError('unpinned mesh workload rejected')
            for container in pod['containers']:
                limits = container.get('resources', {}).get('limits', {})
                if not limits.get('cpu') or not limits.get('memory'):
                    raise ValueError('bounded mesh resources required')
            if pod.get('serviceAccountName') in (None, '', 'default'):
                raise ValueError('dedicated mesh service account required')
            if component in ('cni', 'ztunnel'):
                validate_node_exception(component, pod, pin)
            if component == 'istiod':
                if item['kind'] != 'Deployment' or item['spec'].get('replicas') != 1:
                    raise ValueError('single-server control plane required')
                ca = [v for v in pod.get('volumes', []) if v.get('name') == 'cacerts']
                if len(ca) != 1 or ca[0].get('secret', {}).get('secretName') != 'cacerts':
                    raise ValueError('existing plugin CA mount required')
                # Never fall back to an auto-generated CA if the imported Secret is absent.
                ca[0]['secret']['optional'] = False
    return items


def candidate():
    helm = shutil.which('helm')
    if not helm:
        raise ValueError('Helm 4.2.4 required')
    result = subprocess.run([helm, 'version', '--short'], capture_output=True,
                            check=False, timeout=10, env=os.environ.copy())
    if result.returncode or not re.fullmatch(rb'v4\.2\.4(?:\+[a-zA-Z0-9]+)?\s*', result.stdout):
        raise ValueError('Helm version rejected')
    pin = pins()
    return {'schema_version': 1, 'profile': 'production-single-server',
            'installation_id': 'hooshix-production', 'promotion': 'Not verified',
            'stages': [{'component': component, 'manifest': {'apiVersion': 'v1', 'kind': 'List',
                         'items': render(helm, component, pin)}} for component in COMPONENTS]}


if __name__ == '__main__':
    try:
        print(json.dumps(candidate(), sort_keys=True, indent=2))
    except (ValueError, OSError, subprocess.TimeoutExpired):
        raise SystemExit('MESH_CANDIDATE=Failed; no cluster writes performed') from None
