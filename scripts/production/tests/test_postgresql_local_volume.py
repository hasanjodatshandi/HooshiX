"""Reservation never becomes a credential, filesystem or database installer."""
import copy
import hashlib
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import install_postgresql_local_volume as volume
import provision_postgresql_storage as storage
import build_postgresql_local_volume_bundle as bundle
import rehearse_postgresql_local_volume as fixture


class LocalVolumeTest(unittest.TestCase):
    def test_fixture_uses_digest_pulled_by_cri_not_docker_archive_import(self):
        run = Mock()
        kube = Mock(side_effect=ValueError('namespace boundary reached'))
        with self.assertRaisesRegex(ValueError, 'namespace boundary reached'):
            fixture.exercise(kube, run, Mock(), 'disposable-control-plane', 'registry.invalid/image@sha256:' + 'a' * 64)
        self.assertEqual(2, run.call_count)
        for call in run.call_args_list:
            self.assertEqual(['docker', 'exec', 'disposable-control-plane'], call.args[0][:3])

    def test_reserved_capacity_and_source_are_bound_to_installed_guard(self):
        objects = volume.candidate()
        self.assertEqual(['StorageClass', 'PersistentVolume'], [o['kind'] for o in objects])
        sc, pv = objects
        self.assertEqual('WaitForFirstConsumer', sc['volumeBindingMode'])
        self.assertEqual('Retain', sc['reclaimPolicy'])
        self.assertFalse(sc['allowVolumeExpansion'])
        self.assertEqual({'storage': '29Gi'}, pv['spec']['capacity'])
        self.assertLess(volume.CAPACITY, 31764869120)
        self.assertEqual({'name': 'postgresql-1', 'namespace': 'platform-data'}, pv['spec']['claimRef'])
        self.assertEqual(str(volume.DATA), pv['spec']['local']['path'])
        root = Path(storage.__file__).parent
        guard = storage.guard_source((root / 'provision_openbao_storage.py').read_bytes(), Path(storage.__file__).read_bytes())
        self.assertEqual(volume.GUARD_SHA256, hashlib.sha256(guard).hexdigest())

    def test_defaulting_not_foreign_or_bound_state_is_accepted(self):
        sc, pv = volume.candidate()
        actual = copy.deepcopy(pv)
        actual['spec']['claimRef'].update(apiVersion='v1', kind='PersistentVolumeClaim')
        actual['status'] = {'phase': 'Available'}
        self.assertTrue(volume.matches(actual, pv))
        for field, value in (('uid', 'existing-claim'), ('kind', 'Secret'), ('namespace', 'other')):
            bad = copy.deepcopy(actual)
            bad['spec']['claimRef'][field] = value
            self.assertFalse(volume.matches(bad, pv))
        for state in ('Bound', 'Released', 'Failed'):
            self.assertFalse(volume.matches(actual | {'status': {'phase': state}}, pv))
        for field, value in (('mountOptions', ['suid']), ('hostPath', {'path': '/'})):
            bad = copy.deepcopy(actual)
            bad['spec'][field] = value
            self.assertFalse(volume.matches(bad, pv))
        for obj in (sc, pv):
            bad = copy.deepcopy(obj)
            bad['metadata']['labels'].clear()
            self.assertFalse(volume.matches(bad, obj))
        sc['metadata']['annotations']['storageclass.kubernetes.io/is-default-class'] = 'true'
        self.assertFalse(volume.matches(sc, volume.candidate()[0]))

    def test_all_conflicts_are_checked_before_any_create(self):
        calls = []
        def kube(*args, body=None):
            calls.append(args)
            if args[0] == 'get' and args[1] == 'StorageClass': return b''
            return json.dumps({'kind': 'PersistentVolume', 'metadata': {'labels': {}}}).encode()
        with self.assertRaisesRegex(ValueError, 'FOREIGN_OR_CHANGED_VOLUME_PRESERVED'):
            volume.reserve(volume.candidate(), kube)
        self.assertEqual(2, len(calls))
        self.assertTrue(all(c[0] == 'get' for c in calls))

    def test_create_and_reentry_never_apply_delete_or_patch(self):
        state, writes = {}, []
        def kube(*args, body=None):
            if args[0] == 'create':
                state[body['kind']] = copy.deepcopy(body)
                writes.append(body['kind'])
                return b''
            self.assertEqual('get', args[0])
            return json.dumps(state[args[1]]).encode() if args[1] in state else b''
        volume.reserve(volume.candidate(), kube)
        volume.reserve(volume.candidate(), kube)
        self.assertEqual(['StorageClass', 'PersistentVolume'], writes)

    def test_failed_host_or_namespace_boundary_prevents_reservation(self):
        kube = Mock()
        with self.assertRaises(ValueError):
            volume.execute(kube, Mock(side_effect=ValueError('guard failed')))
        kube.assert_not_called()
        kube.return_value = b'{"metadata":{"labels":{}}}'
        with patch.object(volume, 'reserve') as reserve:
            with self.assertRaisesRegex(ValueError, 'DATA_NAMESPACE_BOUNDARY_REQUIRED'):
                volume.execute(kube, Mock(return_value={}))
        reserve.assert_not_called()

    def test_bundle_requires_successful_complete_api_proof(self):
        proof = {'revision': 'b' * 40, 'checks': {'cleanup': 'Passed'}, 'postgresql_local_volume': {
            'source_sha256': hashlib.sha256(Path(volume.__file__).read_bytes()).hexdigest(),
            **dict.fromkeys(bundle.CHECKS, 'Passed')}}
        with patch.object(bundle.storage, 'check'), patch.object(bundle.storage.original, 'git', return_value='same-tree'):
            bundle.check({}, {}, proof, 'a' * 40)
            for field in bundle.CHECKS:
                bad = copy.deepcopy(proof)
                bad['postgresql_local_volume'][field] = 'Not run'
                with self.assertRaises(ValueError): bundle.check({}, {}, bad, 'a' * 40)
            with self.assertRaises(ValueError): bundle.check({}, {}, proof | {'revision': 'invalid'}, 'a' * 40)

    def test_complete_reservation_preserves_host_and_openbao_and_closed_boundary(self):
        import render_platform_admission as admission
        policy = admission.policy('ValidatingPolicy', 'hooshix-platform-data-closed', 'platform-data',
                                  [admission.validation('data plane not commissioned', 'false')])
        policy['status'] = {'conditionStatus': {'ready': True}}
        calls = []
        bao = {'metadata': {'uid': 'existing-openbao'}, 'status': {'phase': 'Bound'},
               'spec': {'volumeName': 'hooshix-openbao-local-8gib'}}
        def kube(*args, body=None):
            calls.append(args)
            if 'namespace' in args:
                value = {'metadata': {'labels': {'istio.io/dataplane-mode': 'ambient',
                    'pod-security.kubernetes.io/enforce': 'restricted'}}}
            elif 'validatingpolicy' in args: value = policy
            elif 'nodes' in args:
                value = {'items': [{'metadata': {'name': volume.NODE, 'labels': {'kubernetes.io/hostname': volume.NODE}},
                                   'status': {'conditions': [{'type': 'Ready', 'status': 'True'}]}}]}
            elif 'data-openbao-0' in args: value = bao
            else: value = {'items': []}
            return json.dumps(value).encode()
        with patch.object(volume, 'reserve') as reserve:
            result = volume.execute(kube, Mock(return_value={'services': 'unchanged'}))
            reserve.assert_called_once()
            self.assertEqual('Passed', result['postgresql_local_volume'])
            self.assertEqual('Not run', result['pvc'])
            for label, value in {'validationActions': ['Warn'], 'failurePolicy': 'Ignore',
                    'matchConditions': [{'name': 'bypass', 'expression': 'false'}],
                    'matchConstraints': {}, 'evaluation': {'admission': {'enabled': False}}}.items():
                altered = copy.deepcopy(policy)
                altered['spec'][label] = value
                with patch.dict(policy, altered):
                    reserve.reset_mock()
                    with self.assertRaises(ValueError): volume.execute(kube, Mock(return_value={}))
                    reserve.assert_not_called()
        self.assertFalse(any(c[0] in ('delete', 'patch', 'apply') for c in calls))


if __name__ == '__main__':
    unittest.main()
