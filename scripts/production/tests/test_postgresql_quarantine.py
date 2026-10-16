"""Empty commissioning cannot become a data restore, credential writer or public database."""
import copy
import json
import sys
import stat
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import install_postgresql_local_volume as volume
import install_postgresql_quarantine as installer
import render_platform_admission as admission
import render_postgresql_quarantine as renderer
import build_postgresql_quarantine_bundle as bundle


class QuarantineTest(unittest.TestCase):
    def plan(self):
        old = admission.policy('ValidatingPolicy', 'hooshix-platform-data-closed', 'platform-data',
            [admission.validation('existing ESO contract', 'object.spec.serviceAccountName == "hooshix-postgresql-eso"')])
        return renderer.render(old, ['10.43.0.1'])

    def test_exact_quarantine_has_no_business_or_provider_edge(self):
        p = self.plan()
        self.assertEqual('owner-approved-empty-quarantine-2026-10-16', p['approval'])
        spec = p['cluster']['spec']
        self.assertEqual(1, spec['instances'])
        self.assertFalse(spec['enableSuperuserAccess'])
        self.assertEqual(['host all all all reject'], spec['postgresql']['pg_hba'])
        self.assertEqual('identity-db-migration', spec['bootstrap']['initdb']['secret']['name'])
        for name in ('managed', 'backup', 'plugins', 'env', 'envFrom', 'externalClusters', 'superuserSecret'):
            self.assertNotIn(name, spec)
        self.assertEqual({'volumeName': 'hooshix-postgresql-local-29gib'}, spec['storage']['pvcTemplate'])
        self.assertNotIn('imageName', spec)
        self.assertEqual({'apiGroup': 'postgresql.cnpg.io', 'kind': 'ImageCatalog',
                          'name': 'hooshix-postgresql-quarantine', 'major': 18}, spec['imageCatalogRef'])
        self.assertEqual({'images': [{'major': 18, 'image': p['images']['postgresql']}]}, p['catalog']['spec'])
        self.assertNotIn('5432', json.dumps(p['network']) + json.dumps(p['authorization']))
        self.assertEqual({'operator', 'postgresql'}, set(p['images']))
        self.assertEqual(6, len(p['source_policies']))
        self.assertEqual('Not verified', p['production_readiness'])
        self.assertNotIn('stringData', json.dumps(p))

    def test_existing_eso_contract_is_preserved_with_strict_new_contract(self):
        p = self.plan()
        before = p['previous_boundary']['spec']['validations']
        after = p['boundary']['spec']['validations']
        self.assertEqual(before[0]['expression'], after[0]['expression'].split(' || (', 1)[1][:-1])
        source = after[-1]['expression']
        for marker in ('env', 'volumeMounts', 'persistentVolumeClaim', 'hostNetwork', 'hostPath',
                       'capabilities', 'procMount', 'ephemeralContainers'):
            if marker == 'hostPath':
                self.assertNotIn('has(v.hostPath)', source)  # Exact volume source allow-list instead.
            else: self.assertIn(marker, source)
        for obj in [p['boundary'], *p['source_policies']]:
            self.assertEqual('Fail', obj['spec']['failurePolicy'])
            self.assertEqual(['Deny'], obj['spec']['validationActions'])
            self.assertFalse(obj['spec'].get('matchConditions'))

    def fixture(self):
        plan = self.plan()
        state, writes, sql = {}, [], []
        def key(o): return (o['kind'].lower(), o['metadata'].get('namespace'), o['metadata']['name'])
        for obj in volume.candidate():
            obj['metadata']['uid'] = 'reserved-' + obj['kind']
            if obj['kind'] == 'PersistentVolume': obj['status'] = {'phase': 'Available'}
            state[key(obj)] = obj
        old = copy.deepcopy(plan['previous_boundary'])
        state[key(old)] = old
        ns = {'kind': 'Namespace', 'metadata': {'name': 'platform-data', 'labels': {
            'istio.io/dataplane-mode': 'ambient', 'pod-security.kubernetes.io/enforce': 'restricted'}}}
        state[key(ns)] = ns
        def kube(*args, body=None, **options):
            namespace = args[args.index('-n') + 1] if '-n' in args else None
            if 'can-i' in args: return b'no' if options.get('expected') == 1 else b'yes'
            if 'get' in args:
                i = args.index('get'); kind = args[i + 1].lower()
                kind = {'pv': 'persistentvolume', 'pvc': 'persistentvolumeclaim'}.get(kind, kind)
                if '-o' in args and args[args.index('-o') + 1].startswith('jsonpath='): return b''
                if args[i + 2] == '-o':
                    singular = {'clusters': 'cluster', 'pods': 'pod', 'jobs': 'job',
                        'pvc': 'persistentvolumeclaim', 'persistentvolumeclaim': 'persistentvolumeclaim',
                        'services': 'service', 'networkpolicies': 'networkpolicy',
                        'authorizationpolicies': 'authorizationpolicy', 'peerauthentications': 'peerauthentication'}.get(kind, kind)
                    items = [v for (k, n, _), v in state.items() if k == singular and n == namespace]
                    if kind == 'peerauthentications': items = [{'spec': {'mtls': {'mode': 'STRICT'}}}]
                    return json.dumps({'items': items}).encode()
                obj = state.get((kind, namespace, args[i + 2]))
                return json.dumps(obj).encode() if obj else b''
            if 'exec' in args:
                statement = args[-1]
                if 'expected' in options:
                    self.assertEqual(2, options['expected'])
                    self.assertIn('sslmode=verify-full', args[args.index('-d') + 1])
                    self.assertIn('required_error', options)
                    return b''
                sql.append(statement)
                if 'string_agg' in statement: return b'identity,postgres'
                if 'count(*)' in statement: return b'0'
                return b't'
            if 'wait' in args: return b''
            self.assertEqual('create', args[0])
            if '--dry-run=server' in args: return json.dumps(body).encode()
            writes.append(key(body))
            obj = copy.deepcopy(body)
            obj['metadata']['uid'] = 'owned-' + obj['metadata']['name']
            state[key(obj)] = obj
            if obj['kind'] == 'Cluster':
                uid = obj['metadata']['uid']
                owner = [{'uid': uid}]
                pvc = {'kind': 'PersistentVolumeClaim', 'metadata': {'name': volume.CLAIM, 'namespace': 'platform-data',
                    'uid': 'own-claim', 'ownerReferences': owner}, 'spec': {'volumeName': volume.VOLUME}, 'status': {'phase': 'Bound'}}
                state[key(pvc)] = pvc
                pv = state[('persistentvolume', None, volume.VOLUME)]
                pv['spec']['claimRef']['uid'] = 'own-claim'; pv['status']['phase'] = 'Bound'
                pod = {'kind': 'Pod', 'metadata': {'name': 'postgresql-1', 'namespace': 'platform-data', 'ownerReferences': owner},
                    'spec': {'serviceAccountName': 'postgresql', 'containers': [{'image': plan['images']['postgresql']}],
                        'initContainers': [{'image': plan['images']['operator']}]}, 'status': {'containerStatuses': [{'ready': True}]}}
                state[key(pod)] = pod
            return b''
        def replace(obj, **_): state[key(obj)] = copy.deepcopy(obj)
        return plan, state, writes, sql, kube, replace

    def execute(self, fixture):
        plan, state, writes, sql, kube, replace = fixture
        with patch.object(installer, 'protected', return_value=['preserved']), \
             patch.object(installer, 'addresses', return_value=['10.43.0.1']), \
             patch.object(installer, 'replace_policy', side_effect=replace):
            return installer.execute(plan, kube=kube, guard=Mock(return_value={'services': 'unchanged'}))

    def test_empty_install_and_reentry_do_not_replace_data_or_execute_writes(self):
        f = self.fixture()
        first = self.execute(f)
        writes = list(f[2])
        again = self.execute(f)
        self.assertEqual(writes, f[2])
        self.assertEqual(first, again)
        self.assertTrue(all(s.startswith('SELECT ') for s in f[3]))
        self.assertEqual('Passed', first['postgresql_quarantine'])
        for key in ('provider_backup', 'application_migrations', 'root_revocation'):
            self.assertEqual('Not run', first[key])

    def test_foreign_or_changed_resources_stop_before_any_write(self):
        for mode in ('approval', 'cluster', 'late-policy', 'foreign-claim', 'other-cluster', 'additional-access', 'catalog'):
            f = self.fixture(); plan, state, writes, *_ = f
            if mode == 'approval': plan['approval'] = 'bypass'
            if mode == 'cluster': state[('cluster', 'platform-data', 'postgresql')] = copy.deepcopy(plan['cluster'])
            if mode == 'late-policy':
                obj = copy.deepcopy(plan['source_policies'][-1]); obj['spec']['failurePolicy'] = 'Ignore'
                state[(obj['kind'].lower(), None, obj['metadata']['name'])] = obj
            if mode == 'foreign-claim': state[('persistentvolumeclaim', 'platform-data', 'foreign')] = {'metadata': {'name': 'foreign'}}
            if mode == 'other-cluster': state[('cluster', 'platform-data', 'other')] = {'metadata': {'name': 'other'}}
            if mode == 'catalog':
                obj = copy.deepcopy(plan['catalog']); obj['spec']['images'][0]['major'] = 17
                state[('imagecatalog', 'platform-data', obj['metadata']['name'])] = obj
            if mode == 'additional-access': state[('networkpolicy', 'platform-data', 'foreign')] = {
                'metadata': {'name': 'foreign'}, 'spec': {'podSelector': {}, 'ingress': [{}]}}
            with self.subTest(mode=mode), self.assertRaises((ValueError, KeyError)):
                self.execute(f)
            self.assertEqual([], writes)

    def test_reentry_rejects_spec_change_and_preserves_identities(self):
        f = self.fixture()
        self.execute(f)
        before = list(f[2])
        f[1][('cluster', 'platform-data', 'postgresql')]['spec']['env'] = [{'name': 'bad', 'value': 'unsafe'}]
        with self.assertRaisesRegex(ValueError, 'FOREIGN_OR_CHANGED_CLUSTER_PRESERVED'): self.execute(f)
        self.assertEqual(before, f[2])

    def test_selectors_are_evaluated_conservatively(self):
        self.assertTrue(installer.selects({}, {}))
        self.assertFalse(installer.selects({'matchLabels': {'app': 'other'}}, {'app': 'postgresql'}))
        for op, match in (('In', True), ('NotIn', False), ('Exists', True), ('DoesNotExist', False)):
            self.assertEqual(match, installer.selects({'matchExpressions': [
                {'key': 'app', 'operator': op, 'values': ['postgresql']}]}, {'app': 'postgresql'}))

    def test_initialized_guard_mode_never_adopts_other_or_incomplete_data(self):
        data, pgdata, version = MagicMock(), MagicMock(), MagicMock()
        data.lstat.return_value = SimpleNamespace(st_mode=stat.S_IFDIR | 0o700, st_uid=26, st_gid=26)
        data.__truediv__.return_value = pgdata
        pgdata.__truediv__.return_value = version
        pgdata.is_symlink.return_value = version.is_symlink.return_value = False
        pgdata.is_dir.return_value = version.is_file.return_value = True
        pgdata.stat.return_value = SimpleNamespace(st_uid=26)
        version.stat.return_value = SimpleNamespace(st_size=3)
        version.read_text.return_value = '18\n'
        with patch.object(volume, 'DATA', data), patch.object(volume.os, 'statvfs', return_value=SimpleNamespace(f_bavail=1, f_frsize=1)):
            data.iterdir.return_value = []
            with self.assertRaisesRegex(ValueError, 'USABLE_CAPACITY_BELOW_RESERVATION'): volume.verify_data()
            data.iterdir.return_value = [SimpleNamespace(name='pgdata')]
            with self.assertRaisesRegex(ValueError, 'EMPTY_GUARDED_DATA_REQUIRED'): volume.verify_data()
            volume.verify_data(initialized=True)
            for mode in ('symlink', 'version', 'extra', 'owner'):
                pgdata.is_symlink.return_value = mode == 'symlink'
                version.read_text.return_value = '17\n' if mode == 'version' else '18\n'
                data.iterdir.return_value = [SimpleNamespace(name=n) for n in (['pgdata', 'foreign'] if mode == 'extra' else ['pgdata'])]
                pgdata.stat.return_value.st_uid = 1000 if mode == 'owner' else 26
                with self.subTest(mode=mode), self.assertRaises(ValueError): volume.verify_data(initialized=True)

    def test_bundle_requires_all_exact_runtime_proofs_without_inventing_host_guard(self):
        p = {'schema_version': 1, **dict.fromkeys(bundle.CHECKS, 'Passed'), 'images': self.plan()['images'],
            'guard': 'Not run', 'root_mesh_openbao_eso_cnpg_barman': 'Preserved', 'application_ingress': 'CLOSED',
            'production_readiness': 'Not verified', **dict.fromkeys(('cluster_uid', 'pvc_uid', 'volume_uid'), 'fixture'),
            **dict.fromkeys(('provider_backup', 'root_revocation', 'application_migrations'), 'Not run')}
        with patch.object(bundle.secrets, 'verify_proof'):
            bundle.verify_proof({'postgresql_quarantine': p})
            for key in (*bundle.CHECKS, 'guard', 'production_readiness', 'application_ingress'):
                bad = copy.deepcopy(p)
                bad[key] = 'Passed' if key == 'production_readiness' else 'Not verified'
                with self.subTest(key=key), self.assertRaises(ValueError): bundle.verify_proof({'postgresql_quarantine': bad})


if __name__ == '__main__': unittest.main()
