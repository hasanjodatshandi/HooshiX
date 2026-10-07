"""Bounded adoption of the existing bootstrap Kyverno into its pinned Helm release."""
from __future__ import annotations

import hashlib
import json
import os
import stat
import tarfile
import tempfile
from pathlib import Path

VERSION = '1.19.1'
CHART_SHA = '7b7fe51a431b5b133b0ce7eb9dcb222b6a37fb967c163223cf480054ad14d752'
HELM_ARCHIVE_SHA = 'c306b46f719b0a4da32d0f78ee21bf90ce8d602f15b22ab753f0674d1670a7f3'
HELM_BINARY_SHA = '92e191314f44aac173711bb0247c38c727f3ddf65ad16a01c0861d509a63a9e1'
OLD_IMAGES = {
    'admission': 'reg.kyverno.io/kyverno/kyverno@sha256:11cc08bf7116e0881c00412729dc25c299f29fa3dc6b8e1e53fc623d5158c182',
    'background': 'reg.kyverno.io/kyverno/background-controller@sha256:92193ee48d319d86692673b38abbb10177d5d13bdcb50e85feb97b03b4bf4920',
    'cleanup': 'reg.kyverno.io/kyverno/cleanup-controller@sha256:2084dacbfc3365cdbeb8b3ccb4a768d3a94b2d1a243f0f36c3d02cc5ce26d5df',
    'reports': 'reg.kyverno.io/kyverno/reports-controller@sha256:207138d7a52ca4f8ebfcefae203ec22ff57ac21a5bc9437ee428882ca69dbcf2',
}


def require(value, message):
    if not value:
        raise ValueError(message)


def candidate():
    # Rendering is developer/CI-only. The privileged host path needs stdlib only.
    import shutil
    import subprocess
    import yaml
    root = Path(__file__).resolve().parents[2]
    pin = dict(line.split('=', 1) for line in (root / 'infrastructure/kyverno/pins.env').read_text().splitlines()
               if line and not line.startswith('#'))
    require(pin['KYVERNO_VERSION'] == VERSION, 'Kyverno upgrade version mismatch')
    chart = root / 'infrastructure/kyverno/chart/3.9.1/kyverno-3.9.1.tgz'
    require(hashlib.sha256(chart.read_bytes()).hexdigest() == CHART_SHA, 'Kyverno chart integrity failed')
    values = {'crds': {'install': False, 'migration': {'enabled': False}},
              'webhooksCleanup': {'enabled': False},
              'features': {'forceFailurePolicyIgnore': {'enabled': False},
                           'dumpPayload': {'enabled': False}, 'dumpPatches': {'enabled': False},
                           'generateValidatingAdmissionPolicy': {'enabled': False},
                           'logging': {'format': 'json', 'verbosity': 0}}}
    images = {}
    for name, key in (('admission', 'ADMISSION'), ('background', 'BACKGROUND'),
                       ('cleanup', 'CLEANUP'), ('reports', 'REPORTS')):
        repository = 'kyverno/kyverno' if name == 'admission' else 'kyverno/' + name + '-controller'
        digest = pin['KYVERNO_' + key + '_AMD64_DIGEST']
        images[name] = 'reg.kyverno.io/' + repository + '@' + digest
        image = {'registry': 'reg.kyverno.io', 'repository': repository + '@sha256', 'tag': digest.split(':')[1]}
        resource = {'requests': {'cpu': '100m', 'memory': '128Mi' if name == 'admission' else '64Mi'},
                    'limits': {'cpu': '1', 'memory': '768Mi' if name == 'admission' else '256Mi'}}
        values[name + 'Controller'] = {'replicas': 1, 'resources': resource, 'image': image}
        if name == 'admission':
            values['admissionController'] = {'replicas': 1, 'container': {'image': image, 'resources': resource},
                                            'initContainer': {'image': {
                                                'registry': 'reg.kyverno.io', 'repository': 'kyverno/kyvernopre@sha256',
                                                'tag': pin['KYVERNO_PRE_AMD64_DIGEST'].split(':')[1]}}}
    helm = shutil.which('helm')
    require(helm, 'pinned Helm required')
    version = subprocess.run([helm, 'version', '--short'], capture_output=True, timeout=10, check=False)
    require(version.returncode == 0 and version.stdout.startswith(b'v4.2.4+'), 'pinned Helm version required')
    result = subprocess.run([helm, 'template', 'kyverno', str(chart), '-n', 'kyverno', '--no-hooks',
                            '--kube-version', '1.35.6', *[argument for key, value in values.items()
                            for argument in ('--set-json', key + '=' + json.dumps(value))]],
                            capture_output=True, timeout=60, check=False)
    require(result.returncode == 0 and len(result.stdout) < 2 * 1024**2, 'bounded Kyverno render failed')
    resources = list(yaml.safe_load_all(result.stdout))
    inventory = []
    for item in resources:
        require(item['kind'] in ('ServiceAccount', 'ConfigMap', 'ClusterRole', 'ClusterRoleBinding',
                                'Role', 'RoleBinding', 'Service', 'Deployment'), 'unexpected adoption resource')
        inventory.append({'kind': item['kind'], 'name': item['metadata']['name'],
                          'namespace': item['metadata'].get('namespace')})
        if item['kind'] == 'Deployment':
            pod = item['spec']['template']['spec']
            component = item['metadata']['name'].removeprefix('kyverno-').removesuffix('-controller')
            require(item['spec']['replicas'] == 1 and pod['containers'][0]['image'] == images[component],
                    'pinned single-server Kyverno required')
            for container in pod['containers'] + pod.get('initContainers', []):
                require('@sha256:' in container['image'] and container['securityContext']['runAsNonRoot']
                        and container['securityContext']['allowPrivilegeEscalation'] is False
                        and container['securityContext']['readOnlyRootFilesystem'] is True
                        and container['resources']['limits'].get('cpu')
                        and container['resources']['limits'].get('memory'), 'bounded hardened Kyverno required')
    manifest = root / 'infrastructure/kyverno/vendor' / VERSION / 'install.yaml'
    require(hashlib.sha256(manifest.read_bytes()).hexdigest() == pin['KYVERNO_INSTALL_SHA256'],
            'Kyverno CRD integrity failed')
    crds = [item for item in yaml.safe_load_all(manifest.read_bytes()) if item and item['kind'] == 'CustomResourceDefinition']
    return {'version': VERSION, 'values': values, 'images': images, 'inventory': inventory, 'crds': crds}


def public_artifact(path, digest, bound):
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and 0 < info.st_size <= bound,
                'bounded regular upgrade artifact required')
        content = stream.read(bound + 1)
    require(hashlib.sha256(content).hexdigest() == digest, 'upgrade artifact hash mismatch')
    return content


def execute(plan, directory, state, kubeconfig, *, kube, native, get):
    require(plan['version'] == VERSION and set(plan['images']) == set(OLD_IMAGES), 'reviewed upgrade required')
    already_owned = True
    for name, image in OLD_IMAGES.items():
        obj = get('deployment', 'kyverno-' + name + '-controller', 'kyverno')
        require(obj and obj['spec']['template']['spec']['containers'][0]['image'] in (image, plan['images'][name]),
                'unreviewed existing Kyverno image preserved')
        already_owned &= (obj['spec']['template']['spec']['containers'][0]['image'] == plan['images'][name]
                          and obj['metadata'].get('annotations', {}).get('meta.helm.sh/release-name') == 'kyverno')
    if not already_owned:
        pods = json.loads(kube('get', 'pods', '--all-namespaces', '-o', 'json'))['items']
        require(all(p['metadata']['namespace'] in ('kube-system', 'kyverno') for p in pods),
                'Kyverno adoption is restricted to the pre-application bootstrap')
    crd_updates = []
    for item in [*plan['inventory'], *({'kind': 'CustomResourceDefinition', 'name': c['metadata']['name'],
                                      'namespace': None} for c in plan['crds'])]:
        obj = get(item['kind'].lower(), item['name'], item['namespace'])
        if obj:
            labels = obj['metadata'].get('labels', {})
            annotations = obj['metadata'].get('annotations', {})
            part = 'kyverno-crds' if item['kind'] == 'CustomResourceDefinition' else 'kyverno'
            require(labels.get('app.kubernetes.io/part-of') == part
                    and labels.get('app.kubernetes.io/instance') == 'kyverno'
                    and annotations.get('meta.helm.sh/release-name', 'kyverno') == 'kyverno'
                    and annotations.get('meta.helm.sh/release-namespace', 'kyverno') == 'kyverno',
                    'foreign resource preserved before Helm adoption')
    for item in plan['crds']:
        existing = get('customresourcedefinition', item['metadata']['name'])
        if existing:
            supported = {v['name'] for v in item['spec']['versions']}
            require(set(existing.get('status', {}).get('storedVersions', [])) <= supported,
                    'CRD storage migration review required; existing data preserved')
        crd_updates.append((item, existing))
    chart = public_artifact(directory / 'kyverno-3.9.1.tgz', CHART_SHA, 1024**2)
    archive = public_artifact(directory / 'helm-linux-amd64.tar.gz', HELM_ARCHIVE_SHA, 32 * 1024**2)
    with tempfile.TemporaryDirectory(prefix='kyverno-upgrade-', dir=state) as temporary:
        work = Path(temporary)
        (work / 'helm.tar.gz').write_bytes(archive)
        with tarfile.open(work / 'helm.tar.gz', 'r:gz') as tar:
            member = tar.getmember('linux-amd64/helm')
            require(member.isfile() and 0 < member.size <= 96 * 1024**2, 'bounded Helm binary required')
            binary = tar.extractfile(member).read()
        require(hashlib.sha256(binary).hexdigest() == HELM_BINARY_SHA, 'Helm binary hash mismatch')
        executable = work / 'helm'
        executable.write_bytes(binary)
        executable.chmod(0o700)
        chart_path, values_path = work / 'kyverno.tgz', work / 'values.json'
        chart_path.write_bytes(chart)
        values_path.write_text(json.dumps(plan['values']))
        command = [str(executable), 'upgrade', '--install', 'kyverno', str(chart_path), '-n', 'kyverno',
                   '--kubeconfig', str(kubeconfig), '--values', str(values_path), '--take-ownership',
                   '--no-hooks', '--skip-crds', '--server-side=false', '--history-max=3', '--timeout=180s']
        # No automatic uninstall/rollback: a failed first adoption must preserve CRDs/policies.
        native([*command, '--dry-run=server', '--hide-secret'], timeout=195)
        for item, existing in crd_updates:
            if existing:
                desired = dict(item, metadata=existing['metadata'])
                kube('replace', '-f', '-', body=desired)
            else:
                kube('create', '-f', '-', body=item)
        native([*command, '--wait=watcher'], timeout=195)
    for name, image in plan['images'].items():
        obj = get('deployment', 'kyverno-' + name + '-controller', 'kyverno')
        require(obj['status'].get('availableReplicas', 0) == 1
                and obj['spec']['template']['spec']['containers'][0]['image'] == image,
                'upgraded Kyverno availability/image verification failed')
