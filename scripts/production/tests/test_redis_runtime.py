"""Closed runtime contract, initialized-volume reentry and whole-set no-adoption checks."""
import copy
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_redis_runtime_bundle as bundle
import install_redis_runtime as installer
import render_redis_runtime as renderer
import render_platform_admission as admission
import install_redis_local_volume as volume
import verify_published_redis as publication


class RedisRuntimeTest(unittest.TestCase):
    def plan(self):
        old = admission.policy('ValidatingPolicy', 'hooshix-platform-data-closed', renderer.NS,
            [admission.validation('existing controller/PostgreSQL', 'false')])
        return renderer.render(old)

    def test_fixed_runtime_has_only_existing_material_and_no_application_network(self):
        p = self.plan()
        spec = p['statefulset']['spec']
        self.assertEqual(1, spec['replicas'])
        self.assertEqual('OnDelete', spec['updateStrategy']['type'])
        pod = spec['template']['spec']
        self.assertFalse(pod['automountServiceAccountToken'])
        self.assertEqual(10001, pod['securityContext']['runAsUser'])
        self.assertEqual(volume.CLAIM, pod['volumes'][0]['persistentVolumeClaim']['claimName'])
        self.assertEqual(renderer.pin()['image'], pod['containers'][0]['image'])
        self.assertEqual(renderer.RESOURCES, pod['containers'][0]['resources'])
        self.assertNotIn('env', pod['containers'][0])
        network = next(o for o in p['objects'] if o['kind'] == 'NetworkPolicy')
        self.assertEqual([], network['spec']['ingress'])
        self.assertEqual([{'port': 15012, 'protocol': 'TCP'}], network['spec']['egress'][0]['ports'])
        self.assertFalse(any(o['kind'] in ('Secret', 'Role', 'RoleBinding', 'ClusterRole') for o in p['objects']))
        config = next(o for o in p['objects'] if o['kind'] == 'ConfigMap')['data']['redis.conf']
        for value in ('port 0', 'tls-port 6379', 'noeviction', 'appendfsync everysec', 'maxmemory 134217728'):
            self.assertIn(value, config)
        self.assertIn('REDISCLI_AUTH', renderer.HEALTH)
        self.assertNotIn('--insecure', renderer.HEALTH)
        self.assertNotIn(' -a ', renderer.HEALTH)

    def test_boundary_retains_old_contract_and_binds_every_executable_secret_probe_and_resource(self):
        p = self.plan()
        checks = p['boundary']['spec']['validations']
        self.assertEqual('false', checks[0]['expression'].split(' || (', 1)[1][:-1])
        for term in ('security-redis-0', 'StatefulSet', 'automountServiceAccountToken',
            'ephemeralContainers', 'initContainers', 'imagePullSecrets', 'hostNetwork', 'sysctls',
            'capabilities.add', 'envFrom', 'lifecycle', 'subPathExpr', 'redis.conf', 'tls.key',
            'health/password', 'readinessProbe', 'terminationMessagePath', 'nodeSelector', 'ephemeral-storage'):
            self.assertIn(term, checks[-1]['expression'])
        self.assertEqual(3, len(p['source_policies']))
        for obj in p['source_policies']:
            self.assertEqual('Fail', obj['spec']['failurePolicy'])
            self.assertEqual(['Deny'], obj['spec']['validationActions'])
            self.assertNotIn('credentials', obj['spec'])
        source = json.dumps(p['source_policies'])
        for marker in ('redisImages', 'cyclonedx', 'cosign', renderer.pin()['repository_revision'],
                       'github:39109666003:1', 'redis-runtime-build/v1'):
            self.assertIn(marker, source)

    def fixture(self):
        plan, writes = self.plan(), []
        state = {}
        def put(obj):
            obj = copy.deepcopy(obj)
            obj['metadata'].setdefault('uid', 'uid-' + obj['metadata']['name'])
            if 'spec' in obj: obj['metadata'].setdefault('annotations', {})[installer.HASH] = installer.digest(obj['spec'])
            state[(obj['kind'].lower(), obj['metadata']['name'])] = obj
            return obj
        for obj in volume.candidate(): put(obj)
        state[('persistentvolume', volume.VOLUME)]['status'] = {'phase': 'Available'}
        put(plan['previous_boundary'])
        put({'kind': 'Namespace', 'metadata': {'name': renderer.NS, 'labels': {
            'istio.io/dataplane-mode': 'ambient', 'pod-security.kubernetes.io/enforce': 'restricted'}}})
        aliases = {'pv': 'persistentvolume', 'pvc': 'persistentvolumeclaim'}
        def kube(*args, body=None, **options):
            args = list(args)
            if args[:1] == ['-n']: args = args[2:]
            if args[0] == 'get':
                kind = aliases.get(args[1], args[1].lower())
                if args[1] == 'peerauthentications':
                    value = {'items': [{'spec': {'mtls': {'mode': 'STRICT'}}}]}
                elif args[1] in ('networkpolicies', 'authorizationpolicies'):
                    singular = args[1][:-3] + 'y'
                    value = {'items': [v for (k, _), v in state.items() if k == singular]}
                elif args[1] == 'services': value = {'items': [v for (k, _), v in state.items() if k == 'service']}
                else:
                    value = state.get((kind, args[2]))
                    if value is None: return b''
                return json.dumps(value).encode()
            if args[0] == 'create':
                if '--dry-run=server' in args: return json.dumps(body).encode()
                writes.append(body['kind'])
                obj = put(body)
                if body['kind'] == 'PersistentVolumeClaim':
                    obj['status'] = {'phase': 'Bound'}
                    pv = state[('persistentvolume', volume.VOLUME)]
                    pv['spec']['claimRef']['uid'] = obj['metadata']['uid']
                    pv['status']['phase'] = 'Bound'
                if body['kind'] == 'StatefulSet':
                    pod = put({'kind': 'Pod', 'metadata': {'name': renderer.NAME + '-0',
                        'labels': renderer.LABELS.copy(), 'ownerReferences': [{'uid': obj['metadata']['uid']}]},
                        'spec': body['spec']['template']['spec']})
                    pod['status'] = {'containerStatuses': [{'ready': True}]}
                return b''
            if args[0] == 'auth': return b'no'
            if args[0] in ('wait', 'rollout', 'exec'): return b''
            raise AssertionError(args)
        def replace(value, **_): put(value)
        return plan, state, writes, kube, replace

    def test_create_reentry_keeps_same_bound_claim_and_never_uses_empty_storage_check_after_start(self):
        plan, state, writes, kube, replace = self.fixture()
        guard = Mock(return_value={'guard': 'unchanged'})
        with patch.object(installer, 'secrets', return_value=['private-in-memory-only']), \
                patch.object(installer, 'replace_policy', side_effect=replace):
            first = installer.execute(plan, kube=kube, guard=guard, preserve=lambda _: ['existing-pg'])
            before = list(writes)
            again = installer.execute(plan, kube=kube, guard=guard, preserve=lambda _: ['existing-pg'])
        self.assertEqual(first, again)
        self.assertEqual(before, writes)
        self.assertEqual(['PersistentVolumeClaim', 'StatefulSet'], writes[-2:])
        self.assertEqual([False, True, True, True], [c.kwargs['initialized'] for c in guard.call_args_list])
        self.assertEqual('CLOSED', first['application_ingress'])

    def test_unknown_resource_stops_before_any_write_and_bound_uid_drift_is_not_adopted(self):
        plan, state, writes, kube, replace = self.fixture()
        old = copy.deepcopy(plan['objects'][1])
        old['metadata']['labels']['app.kubernetes.io/managed-by'] = 'foreign'
        state[('configmap', renderer.NAME)] = old
        with patch.object(installer, 'secrets', return_value=[]), self.assertRaises(ValueError):
            installer.execute(plan, kube=kube, guard=Mock(), preserve=lambda _: [])
        self.assertEqual([], writes)
        pv = state[('persistentvolume', volume.VOLUME)]
        pv['status']['phase'] = 'Bound'
        pv['spec']['claimRef']['uid'] = 'foreign'
        with self.assertRaises(ValueError): installer.binding(kube, {'metadata': {'uid': 'ours'},
            'status': {'phase': 'Bound'}, 'spec': {'volumeName': volume.VOLUME}})

    def test_full_defaulted_spec_drift_is_rejected_even_if_desired_subset_matches(self):
        desired = self.plan()['statefulset']
        actual = copy.deepcopy(desired)
        actual['metadata']['annotations'] = {installer.HASH: installer.digest(actual['spec'])}
        installer.owned(actual, desired)
        actual['spec']['template']['spec']['priorityClassName'] = 'unreviewed'
        with self.assertRaises(ValueError): installer.owned(actual, desired)

    def test_owner_loader_uses_only_hash_bound_sources_in_dependency_order(self):
        root = Path(__file__).resolve().parents[1]
        wrapper = (root / bundle.WRAPPER).read_text()
        names = re.findall(r"'([^']+\.py)'", wrapper.split('$sources = @(', 1)[1].split(')\n', 1)[0])
        self.assertEqual(list(bundle.SOURCES), names)
        self.assertNotIn('rehearse_redis_runtime.py', names)
        for name in names: self.assertLessEqual((root / name).stat().st_size, 32768)
        # Mirror owner loading in an isolated interpreter registry without contacting VPS or reading custody.
        script = ('import sys,types,pathlib\np=pathlib.Path(' + repr(str(root)) + ')\n'
            'for name in ' + repr(names) + ':\n'
            ' m=types.ModuleType(name[:-3]); m.__file__=str(p/name); sys.modules[name[:-3]]=m\n'
            ' exec(compile((p/name).read_bytes(),name,"exec"),m.__dict__)\n')
        import subprocess
        subprocess.run([sys.executable, '-I', '-c', script], check=True, capture_output=True, timeout=10)
        self.assertNotIn('Get-Credential', wrapper)
        self.assertIn('No GHCR token or OpenBao/CA passphrase required', wrapper)

    def test_runtime_cannot_claim_staging_from_only_a_tls_receipt(self):
        with self.assertRaises(ValueError): bundle.verify_proof({'redis_tls_delivery': {'tls_delivery': 'Passed'}})

    def test_complete_native_proof_cannot_waive_any_runtime_check(self):
        proof = {'redis_runtime': {'schema_version': 1, 'image': renderer.pin()['image'],
            **dict.fromkeys(bundle.CHECKS, 'Passed'), 'guard': 'Not run',
            'existing_database_openbao_eso_credentials': 'Preserved', 'application_ingress': 'CLOSED',
            'public_access': 'CLOSED', 'production_readiness': 'Not verified',
            'automatic_renewal': 'Not run', 'provider_backup': 'Not run', 'capacity': 'Not verified',
            'statefulset_uid': 'sts', 'pvc_uid': 'pvc', 'volume_uid': 'pv'}}
        with patch.object(bundle.previous, 'verify_proof'), patch.object(bundle.quarantine, 'verify_proof'):
            bundle.verify_proof(proof)
            for field in bundle.CHECKS:
                bad = copy.deepcopy(proof)
                bad['redis_runtime'][field] = 'Not run'
                with self.subTest(field=field), self.assertRaises(ValueError): bundle.verify_proof(bad)

    def test_publication_requires_exact_protected_run_before_download_or_scan(self):
        selected = renderer.pin()
        record = {'head_sha': selected['repository_revision'], 'run_attempt': selected['publication_attempt'],
            'status': 'completed', 'conclusion': 'success', 'head_branch': 'main', 'event': 'workflow_dispatch',
            'path': publication.publication.WORKFLOW, 'repository': {'full_name': publication.publication.REPOSITORY}}
        for field, value in (('head_sha', '0' * 40), ('run_attempt', 2), ('conclusion', 'failure'),
                             ('head_branch', 'untrusted'), ('event', 'pull_request')):
            with tempfile.TemporaryDirectory() as work, \
                    patch.object(publication.publication, 'run', return_value=json.dumps(record | {field: value}).encode()) as call, \
                    patch.object(publication, 'scan_inventory') as scan, self.assertRaises(ValueError):
                publication.verify(Path(work) / 'fresh')
            self.assertEqual(1, call.call_count)
            scan.assert_not_called()

    def test_initialized_storage_does_not_reuse_the_empty_reservation_requirement(self):
        data = Mock()
        data.lstat.return_value.st_mode = 0o40700
        data.lstat.return_value.st_uid = data.lstat.return_value.st_gid = 10001
        child = Mock()
        child.name = 'appendonlydir'
        child.is_dir.return_value, child.is_symlink.return_value = True, False
        data.iterdir.side_effect = lambda: iter([child])
        fs = Mock(f_blocks=100, f_frsize=1024, f_bavail=40)
        with patch.object(volume, 'DATA', data), patch.object(volume.os, 'statvfs', return_value=fs):
            volume.verify_data(initialized=True)
            with self.assertRaises(ValueError): volume.verify_data()
            child.is_symlink.return_value = True
            with self.assertRaises(ValueError): volume.verify_data(initialized=True)
            child.is_symlink.return_value = False
            fs.f_bavail = 29
            with self.assertRaises(ValueError): volume.verify_data(initialized=True)


if __name__ == '__main__': unittest.main()
