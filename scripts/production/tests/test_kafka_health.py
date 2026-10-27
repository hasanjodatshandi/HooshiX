"""Retained Kafka liveness migration is exact, journaled and resumable after quiescence."""
import copy
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import upgrade_kafka_health as health
import render_kafka_runtime as renderer
import render_platform_admission as admission


class KafkaHealthTest(unittest.TestCase):
    def setUp(self):
        prior = admission.policy('ValidatingPolicy', 'closed', 'platform-data',
                                 [admission.validation('closed', 'false')])
        new = renderer.render(prior)
        keys = ('statefulset', 'objects', 'boundary', 'source_policies')
        self.plan = {'kafka_runtime': {k: new[k] for k in keys}, 'public_ca_sha256': 'a' * 64}
        self.plan['kafka_health_upgrade'] = renderer.legacy_health(self.plan['kafka_runtime'])
        self.current = copy.deepcopy(self.plan['kafka_health_upgrade']['statefulset'])
        self.current['metadata'].update(uid='fixed-uid', resourceVersion='1',
                                       annotations={health.runtime.HASH: health.runtime.digest(self.current['spec'])})
        self.boundary = health.runtime.boundary_for_statefulset(self.plan['kafka_health_upgrade'], 'fixed-uid')
        self.boundary['status'] = {'conditionStatus': {'ready': True}}
        self.journal, self.pod, self.writes, self.fail_after_stop = None, True, [], False

    def get(self, kube, kind, name, namespace=None):
        if kind == 'statefulset': return copy.deepcopy(self.current)
        if kind == 'pvc': return {'metadata': {'uid': 'fixed-pvc'}}
        if kind == 'configmap': return copy.deepcopy(self.journal)
        if kind == 'pod':
            return {'spec': self.current['spec']['template']['spec']} if self.pod else None
        return copy.deepcopy(self.boundary)

    def kube(self, *args, body=None, **kwargs):
        self.writes.append(args)
        if args == ('create', '-f', '-'):
            self.journal = copy.deepcopy(body)
        elif 'patch' in args:
            operations = json.loads(args[args.index('-p') + 1])
            for op in operations:
                if op['op'] == 'test': continue
                if op['path'] == '/spec': self.current['spec'] = op['value']
                elif op['path'] == '/spec/replicas': self.current['spec']['replicas'] = op['value']
                else: self.current['metadata']['annotations'][health.runtime.HASH] = op['value']
            self.current['metadata']['resourceVersion'] = str(int(self.current['metadata']['resourceVersion']) + 1)
            if self.current['spec']['replicas'] == 1: self.pod = True
        elif '--for=delete' in args:
            self.pod = False
            if self.fail_after_stop: raise ValueError('SIMULATED_INTERRUPTION')
        return b''

    def policy(self, value, **kwargs):
        self.boundary = copy.deepcopy(value)
        self.boundary['status'] = {'conditionStatus': {'ready': True}}

    def run_upgrade(self):
        with patch.object(health.runtime, 'get', side_effect=self.get), \
             patch.object(health.runtime, 'pod_inventory'), \
             patch.object(health.runtime, 'preflight_admission'), \
             patch.object(health.runtime, 'storage_identity', return_value=('fixed-id','a' * 64)), \
             patch.object(health.delivery, 'snapshot', return_value=[]), \
             patch.object(health, 'preserved', return_value=[[b'ca-uid:resource-version',
                 {'spec': {'replicas': 1}}, (b'public-uid', {'tls.crt': 'synthetic-only'})], 'retained-volume']), \
             patch.object(health, 'replace_policy', side_effect=self.policy):
            return health.execute(self.plan, kube=self.kube)

    def test_upgrade_preserves_owner_volume_image_and_reenters_without_patches(self):
        image = self.current['spec']['template']['spec']['containers'][0]['image']
        self.assertEqual('Passed', self.run_upgrade())
        self.assertTrue(self.journal['immutable'])
        self.assertEqual('fixed-uid', self.current['metadata']['uid'])
        self.assertEqual(image, self.current['spec']['template']['spec']['containers'][0]['image'])
        self.assertEqual(renderer.LIVENESS, self.current['spec']['template']['spec']['containers'][0]
                         ['livenessProbe']['exec']['command'][2])
        count = sum('patch' in x for x in self.writes)
        self.assertEqual('Passed', self.run_upgrade())
        self.assertEqual(count, sum('patch' in x for x in self.writes))

    def test_interruption_after_stop_resumes_without_new_journal_or_reset(self):
        self.fail_after_stop = True
        with self.assertRaisesRegex(ValueError, 'SIMULATED_INTERRUPTION'): self.run_upgrade()
        self.assertEqual(0, self.current['spec']['replicas'])
        retained = copy.deepcopy(self.journal)
        self.fail_after_stop = False
        self.assertEqual('Passed', self.run_upgrade())
        self.assertEqual(retained, self.journal)

    def test_foreign_probe_and_journal_fail_before_mutation(self):
        self.current['spec']['template']['spec']['containers'][0]['livenessProbe']['exec']['command'][2] = 'foreign'
        with self.assertRaises(ValueError): self.run_upgrade()
        self.assertFalse(self.writes)

    def test_snapshot_fingerprint_preserves_nested_byte_identity_without_serializing_values(self):
        value = [[b'ca-uid:version', {'pod': ('uid', 1)}], {'key': 'synthetic-only'}]
        self.assertEqual(health.fingerprint(value), health.fingerprint(copy.deepcopy(value)))
        self.assertNotEqual(health.fingerprint(value), health.fingerprint(
            [['ca-uid:version', {'pod': ('uid', 1)}], {'key': 'synthetic-only'}]))
        self.assertNotEqual(health.fingerprint(value), health.fingerprint(
            [[b'changed-ca-uid:version', {'pod': ('uid', 1)}], {'key': 'synthetic-only'}]))
        with self.assertRaises(TypeError): health.fingerprint(object())

    def test_foreign_journal_and_non_health_spec_drift_fail_before_mutation(self):
        self.run_upgrade()
        self.journal['data']['pvc_uid'] = 'foreign-volume'
        self.writes.clear()
        with self.assertRaises(ValueError): self.run_upgrade()
        self.assertFalse(self.writes)
        self.setUp()
        self.current['spec']['template']['spec']['containers'][0]['image'] = 'foreign-image'
        self.current['metadata']['annotations'][health.runtime.HASH] = health.runtime.digest(self.current['spec'])
        with self.assertRaises(ValueError): self.run_upgrade()
        self.assertFalse(self.writes)

    def test_current_profile_without_upgrade_journal_does_not_restart(self):
        self.current['spec'] = copy.deepcopy(self.plan['kafka_runtime']['statefulset']['spec'])
        self.current['metadata']['annotations'][health.runtime.HASH] = health.runtime.digest(self.current['spec'])
        self.boundary = health.runtime.boundary_for_statefulset(self.plan['kafka_runtime'], 'fixed-uid')
        self.boundary['status'] = {'conditionStatus': {'ready': True}}
        self.assertEqual('Not applicable; current health contract', self.run_upgrade())
        self.assertFalse(self.writes)


if __name__ == '__main__': unittest.main()
