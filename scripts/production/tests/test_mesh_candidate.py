import hashlib
import json
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import render_mesh_candidate as target


class MeshCandidateTest(unittest.TestCase):
    def test_network_exception_rejects_every_broader_privilege(self):
        pin = target.pins()
        pod = {'serviceAccountName': 'ztunnel', 'containers': [{
            'name': 'istio-proxy', 'securityContext': {
                'privileged': False, 'runAsUser': 0, 'runAsGroup': 1337,
                'runAsNonRoot': False, 'allowPrivilegeEscalation': True,
                'readOnlyRootFilesystem': True, 'capabilities': {
                    'drop': ['ALL'], 'add': ['NET_ADMIN', 'SYS_ADMIN', 'NET_RAW']}}}],
            'volumes': [{'name': 'ztunnel', 'hostPath': {'path': '/var/run/ztunnel'}}]}
        target.validate_node_exception('ztunnel', pod, pin)
        for field in ('hostNetwork', 'hostPID', 'hostIPC', 'account', 'container',
                      'privileged', 'capability', 'mount', 'writable-root', 'init', 'ephemeral'):
            changed = json.loads(json.dumps(pod))
            context = changed['containers'][0]['securityContext']
            if field in ('hostNetwork', 'hostPID', 'hostIPC'):
                changed[field] = True
            elif field == 'account':
                changed['serviceAccountName'] = 'other'
            elif field == 'container':
                changed['containers'].append(changed['containers'][0].copy())
            elif field == 'privileged':
                context['privileged'] = True
            elif field == 'capability':
                context['capabilities']['add'].append('SYS_MODULE')
            elif field == 'mount':
                changed['volumes'].append({'hostPath': {'path': '/'}})
            elif field == 'writable-root':
                context['readOnlyRootFilesystem'] = False
            else:
                changed['initContainers' if field == 'init' else 'ephemeralContainers'] = [{'name': 'other'}]
            with self.subTest(field=field), self.assertRaises(ValueError):
                target.validate_node_exception('ztunnel', changed, pin)

    def test_existing_chart_integrity_and_three_exact_images(self):
        pin = target.pins()
        for component in target.COMPONENTS:
            chart = target.ROOT / f'infrastructure/istio/chart/1.30.5/{component}-1.30.5.tgz'
            self.assertEqual(pin['ISTIO_' + component.upper() + '_CHART_SHA256'],
                             hashlib.sha256(chart.read_bytes()).hexdigest())
            values = target.values(component, pin)
            if component != 'base':
                self.assertRegex(values['image'], r'^docker.io/istio/[^@]+@sha256:[a-f0-9]{64}$')
                self.assertEqual('ambient', values['profile'])

    def test_single_node_resources_and_k3s_paths(self):
        pin = target.pins()
        pilot = target.values('istiod', pin)
        self.assertFalse(pilot['autoscaleEnabled'])
        self.assertEqual(1, pilot['replicaCount'])
        self.assertEqual('prod.sajtech.internal', pilot['meshConfig']['trustDomain'])
        self.assertEqual('platform-prod', pilot['global']['meshID'])
        for component in ('istiod', 'cni', 'ztunnel'):
            resources = target.values(component, pin)['resources']
            self.assertEqual('1', resources['limits']['cpu'])
            self.assertEqual('512Mi', resources['limits']['memory'])
        cni = target.values('cni', pin)
        self.assertEqual('/var/lib/rancher/k3s/agent/etc/cni/net.d', cni['cniConfDir'])
        self.assertEqual('/var/lib/rancher/k3s/data/current/bin/', cni['cniBinDir'])

    def test_chart_or_baseline_tampering_and_native_errors_are_rejected(self):
        pin = target.pins()
        for change in ({'ISTIO_VERSION': 'latest'}, {'ISTIO_TRUST_DOMAIN': 'other'},
                       {'ISTIO_BASE_CHART_SHA256': '0' * 64}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                target.render('helm', 'base', pin | change)
        for result in (subprocess.CompletedProcess([], 1, b'', b'private diagnostic'),
                       subprocess.CompletedProcess([], 0, b'x' * (target.BOUND + 1), b''),
                       subprocess.CompletedProcess([], 0, b'[]', b'')):
            with patch.object(subprocess, 'run', return_value=result), self.assertRaises(ValueError):
                target.render('helm', 'base', pin)

    def test_unpinned_images_default_account_and_unbounded_limits_are_rejected(self):
        pin = target.pins()
        pod = {'serviceAccountName': 'ztunnel', 'containers': [{
            'image': target.values('ztunnel', pin)['image'],
            'resources': {'limits': {'cpu': '1', 'memory': '512Mi'}}}]}
        for change in ('image', 'account', 'limits'):
            value = json.loads(json.dumps(pod))
            if change == 'image':
                value['containers'][0]['image'] = 'docker.io/istio/ztunnel:latest'
            elif change == 'account':
                value['serviceAccountName'] = 'default'
            else:
                value['containers'][0]['resources']['limits'] = {}
            output = {'apiVersion': 'apps/v1', 'kind': 'DaemonSet',
                      'spec': {'template': {'spec': value}}}
            with patch.object(subprocess, 'run', return_value=subprocess.CompletedProcess(
                    [], 0, json.dumps(output).encode(), b'')), self.assertRaises(ValueError):
                target.render('helm', 'ztunnel', pin)

    def test_existing_ca_is_mandatory_and_never_rendered(self):
        pin = target.pins()
        pod = {'serviceAccountName': 'istiod', 'containers': [{
            'image': target.values('istiod', pin)['image'],
            'resources': {'limits': {'cpu': '1', 'memory': '512Mi'}}}],
               'volumes': [{'name': 'cacerts', 'secret': {'secretName': 'cacerts', 'optional': True}}]}
        output = {'apiVersion': 'apps/v1', 'kind': 'Deployment', 'spec': {
            'replicas': 1, 'template': {'spec': pod}}}
        with patch.object(subprocess, 'run', return_value=subprocess.CompletedProcess(
                [], 0, json.dumps(output).encode(), b'')) as native:
            rendered = target.render('helm', 'istiod', pin)
            self.assertFalse(rendered[0]['spec']['template']['spec']['volumes'][0]['secret']['optional'])
            command = native.call_args.args[0]
            self.assertEqual(['helm', 'template', 'istiod'], command[:3])
            self.assertIn('--kube-version', command)
            self.assertNotIn('install', command)
            self.assertNotIn('upgrade', command)
        self.assertNotIn('PRIVATE KEY', json.dumps(rendered))

    def test_candidate_requires_exact_helm_and_is_not_promotion(self):
        with patch.object(target.shutil, 'which', return_value=None), self.assertRaises(ValueError):
            target.candidate()
        with patch.object(target.shutil, 'which', return_value='helm'), \
                patch.object(subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, b'v4.2.5', b'')), \
                self.assertRaises(ValueError):
            target.candidate()
        with patch.object(target.shutil, 'which', return_value='helm'), \
                patch.object(subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, b'v4.2.4', b'')), \
                patch.object(target, 'render', return_value=[{'kind': 'ConfigMap'}]):
            result = target.candidate()
        self.assertEqual('Not verified', result['promotion'])
        self.assertEqual(list(target.COMPONENTS), [stage['component'] for stage in result['stages']])


if __name__ == '__main__':
    unittest.main()
