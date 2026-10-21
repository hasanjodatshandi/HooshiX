"""Reservation never becomes a credential, filesystem or database installer."""
import copy
import hashlib
import json
import stat
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import install_kafka_local_volume as volume
import provision_kafka_storage as storage
import build_kafka_local_volume_bundle as bundle
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
        self.assertEqual({'storage': '15Gi'}, pv['spec']['capacity'])
        self.assertLess(volume.CAPACITY, 16500000000)
        self.assertEqual({'name': 'data-kafka-0', 'namespace': 'platform-data'}, pv['spec']['claimRef'])
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

    def test_nonempty_unsafe_or_undersized_data_is_never_reserved(self):
        data = Mock()
        data.lstat.return_value = Mock(st_mode=stat.S_IFDIR | 0o700, st_uid=10001, st_gid=10001)
        data.iterdir.return_value = iter(())
        filesystem = Mock(f_bavail=volume.CAPACITY, f_frsize=1)
        with patch.object(volume, 'DATA', data), patch.object(volume.os, 'statvfs', return_value=filesystem):
            volume.verify_data()
            data.iterdir.return_value = iter(('unexpected-existing-data',))
            with self.assertRaisesRegex(ValueError, 'EMPTY_GUARDED_DATA_REQUIRED'):
                volume.verify_data()
            data.iterdir.return_value = iter(())
            filesystem.f_bavail = volume.CAPACITY - 1
            with self.assertRaisesRegex(ValueError, 'USABLE_CAPACITY_BELOW_RESERVATION'):
                volume.verify_data()
            data.lstat.return_value.st_mode = stat.S_IFLNK | 0o700
            with self.assertRaisesRegex(ValueError, 'GUARDED_DATA_OWNER_REQUIRED'):
                volume.verify_data()

    def test_binding_identity_ignores_controller_metadata_but_not_replacement_or_deletion(self):
        original = {'metadata': {'uid': 'existing', 'resourceVersion': '1'},
                    'spec': {'volumeName': 'retained'}, 'status': {'phase': 'Bound'}}
        changed = copy.deepcopy(original)
        changed['metadata']['resourceVersion'] = '2'
        self.assertEqual(volume.claim_identity(original), volume.claim_identity(changed))
        changed['metadata']['uid'] = 'replacement'
        self.assertNotEqual(volume.claim_identity(original), volume.claim_identity(changed))
        changed['metadata']['deletionTimestamp'] = 'now'
        with self.assertRaisesRegex(ValueError, 'EXISTING_DATABASE_BINDING_REQUIRED'):
            volume.claim_identity(changed)

    def test_bundle_requires_successful_complete_api_proof(self):
        proof = {'revision': 'b' * 40, 'checks': {'cleanup': 'Passed'}, 'kafka_local_volume': {'runtime_uid': 10001,
            'source_sha256': hashlib.sha256(Path(volume.__file__).read_bytes()).hexdigest(),
            **dict.fromkeys(bundle.CHECKS, 'Passed')}}
        with patch.object(bundle.storage, 'check'), patch.object(bundle.storage.original, 'git', return_value='same-tree'):
            bundle.check({}, {}, proof, 'a' * 40)
            for field in bundle.CHECKS:
                bad = copy.deepcopy(proof)
                bad['kafka_local_volume'][field] = 'Not run'
                with self.assertRaises(ValueError): bundle.check({}, {}, bad, 'a' * 40)
            with self.assertRaises(ValueError): bundle.check({}, {}, proof | {'revision': 'invalid'}, 'a' * 40)

    def test_reservation_accepts_running_postgresql_but_preserves_admission_and_all_three_claims(self):
        policy = {'metadata': {'uid': 'existing-policy'},
                  'spec': {'validationActions': ['Deny'], 'failurePolicy': 'Fail',
                          'evaluation': {'admission': {'enabled': True}}},
                  'status': {'conditionStatus': {'ready': True}}}
        calls = []
        bindings = {'data-openbao-0': 'hooshix-openbao-local-8gib',
                    'postgresql-1': 'hooshix-postgresql-local-29gib',
                    'data-security-redis-0': 'hooshix-redis-local-7gib'}

        def kube(*args, body=None):
            calls.append(args)
            if 'namespace' in args:
                value = {'metadata': {'labels': {'istio.io/dataplane-mode': 'ambient',
                    'pod-security.kubernetes.io/enforce': 'restricted'}}}
            elif 'validatingpolicy' in args: value = policy
            elif volume.CLAIM in args: return b''
            elif 'nodes' in args:
                value = {'items': [{'metadata': {'name': volume.NODE, 'labels': {'kubernetes.io/hostname': volume.NODE}},
                                   'status': {'conditions': [{'type': 'Ready', 'status': 'True'}]}}]}
            else:
                name = next(name for name in bindings if name in args)
                value = {'metadata': {'uid': 'preserved-' + name}, 'status': {'phase': 'Bound'},
                         'spec': {'volumeName': bindings[name]}}
            return json.dumps(value).encode()

        with patch.object(volume, 'reserve') as reserve:
            result = volume.execute(kube, Mock(return_value={'services': 'unchanged'}))
            reserve.assert_called_once()
            self.assertEqual('Passed', result['kafka_local_volume'])
            for label in ('pvc', 'kafka', 'secret_delivery'): self.assertEqual('Not run', result[label])
            self.assertEqual('Unchanged', result['admission'])
            for field, value in {'validationActions': ['Warn'], 'failurePolicy': 'Ignore',
                    'evaluation': {'admission': {'enabled': False}}}.items():
                altered = copy.deepcopy(policy)
                altered['spec'][field] = value
                with patch.dict(policy, altered):
                    reserve.reset_mock()
                    with self.assertRaisesRegex(ValueError, 'ACTIVE_DATA_ADMISSION_REQUIRED'):
                        volume.execute(kube, Mock(return_value={}))
                    reserve.assert_not_called()
            def present_claim(*args, body=None):
                return b'{"spec":{}}' if volume.CLAIM in args else kube(*args, body=body)
            reserve.reset_mock()
            with self.assertRaisesRegex(ValueError, 'KAFKA_CLAIM_ALREADY_PRESENT'):
                volume.execute(present_claim, Mock(return_value={}))
            reserve.assert_not_called()
            def controller_status_only(*args, body=None):
                value = kube(*args, body=body)
                if 'validatingpolicy' in args:
                    changed = json.loads(value)
                    changed['metadata']['resourceVersion'] = str(len(calls))
                    changed['status']['observedAt'] = len(calls)
                    return json.dumps(changed).encode()
                return value
            result = volume.execute(controller_status_only, Mock(return_value={}))
            self.assertEqual('Passed', result['kafka_local_volume'])
            reserve.reset_mock()
            for claim in bindings:
                expected = bindings[claim]
                bindings[claim] = 'foreign-volume'
                with self.assertRaisesRegex(ValueError, 'EXISTING_DATABASE_BINDING_REQUIRED'):
                    volume.execute(kube, Mock(return_value={}))
                reserve.assert_not_called()
                bindings[claim] = expected
        self.assertFalse(any(c[0] in ('delete', 'patch', 'apply') or 'pods' in c for c in calls))

    def test_kafka_fixture_uses_runtime_uid_10001_in_separate_owned_namespace(self):
        run = Mock()
        kube = Mock(side_effect=ValueError('namespace boundary reached'))
        with self.assertRaisesRegex(ValueError, 'namespace boundary reached'):
            fixture.exercise(kube, run, Mock(), 'owned-control-plane', 'registry.invalid/image@sha256:' + 'a' * 64,
                             module=volume, runtime_uid=10001, profile='kafka')
        self.assertEqual(['docker', 'exec', 'owned-control-plane', 'chown', '10001:10001',
                          '/tmp/hooshix-kafka-local-volume-ci'], run.call_args_list[1].args[0])


if __name__ == '__main__':
    unittest.main()
