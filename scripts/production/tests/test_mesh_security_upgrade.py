"""Limited source upgrade preserves initialized-store and original bootstrap contracts."""
import copy
import json
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import render_mesh_security_upgrade as renderer
import rehearse_platform_admission as staging
import upgrade_mesh_security as target


class MeshSecurityUpgradeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.plan = renderer.render()

    def test_exact_two_component_source_lineage_and_preserved_ztunnel(self):
        self.assertEqual(['istiod', 'cni'], [c['component'] for c in self.plan['changes']])
        self.assertEqual(6, len(self.plan['source_policies']))
        for component in renderer.COMPONENTS:
            expected = renderer.predicate(component)
            params = expected['buildDefinition']['externalParameters']
            self.assertEqual(component, params['component'])
            self.assertEqual('1.26.9', params['go_version'])
            self.assertEqual('v0.60.0', params['modules']['golang.org/x/net'])
            self.assertEqual(renderer.pin()['repository_revision'], params['repository_revision'])
            self.assertEqual(renderer.build.base_image(component), params['runtime_base'])
            policy = renderer.source_policy(component)
            expression = policy['spec']['validations'][2]['expression']
            self.assertEqual(renderer.provenance_expression(expected), expression)
            self.assertIn('["module_files_sha256"]["go.mod"] == ', expression)
            self.assertIn('["internalParameters"].size() == 0', expression)
            self.assertTrue(expression.startswith('variables.provenanceVerified && '))
            self.assertNotIn('gitRevision', expression)
            self.assertNotIn('messageExpression', policy['spec']['validations'][2])
        with self.assertRaises(ValueError):
            renderer.predicate('openbao')
        for key in ('boundary_before', 'boundary_after'):
            self.assertIn(renderer.admission.mesh_publication.targets()['ztunnel']['image'],
                          self.plan[key]['spec']['validations'][0]['expression'])

    def test_post_initialization_probe_never_requests_raw_schema_diagnostics(self):
        calls = []
        def kube(*args, **options):
            calls.append(options)
            return b''
        staging.admission_result(kube, {'kind': 'Pod'}, lambda check, **_: check(),
                                 public_schema=False)
        self.assertFalse(calls[0]['public_schema'])

    def fixture(self):
        resources = {}
        for change in self.plan['changes']:
            resources[(change['kind'], change['name'], target.NAMESPACE)] = {
                'apiVersion': 'apps/v1', 'kind': change['kind'],
                'metadata': {'uid': change['component'], 'resourceVersion': '1'},
                'spec': {'template': {'spec': copy.deepcopy(change['expected_pod'])}}}
        resources[('ValidatingPolicy', target.BOUNDARY, None)] = {
            **copy.deepcopy(self.plan['boundary_before']), 'metadata': {
                'name': target.BOUNDARY, 'uid': 'boundary', 'resourceVersion': '1'}}
        calls = []
        def get(kind, name, namespace=None):
            return copy.deepcopy(resources.get((kind, name, namespace)))
        def kube(*args, body=None, **options):
            calls.append((args, body, options))
            if args[:3] == ('-n', target.NAMESPACE, 'get') and args[3] == 'secret/cacerts':
                return b'ca-uid:1'
            if 'get' in args:
                resource = {'apiVersion': 'v1', 'metadata': {'uid': 'preserved', 'name': args[3]},
                            'spec': {'preserved': True}}
                if args[3] == 'pods':
                    resource['status'] = {'phase': 'Running', 'containerStatuses': [{'ready': True}]}
                if args[3] in ('pods', 'persistentvolumeclaims'):
                    return json.dumps({'items': [resource]}).encode()
                return json.dumps(resource).encode()
            if 'patch' in args:
                if args[0] == '-n':
                    kind, name, namespace = args[3], args[4], args[1]
                else:
                    kind, name, namespace = args[1], args[2], None
                resource = resources[(kind, name, namespace)]
                ops = json.loads(args[-1])
                self.assertEqual(resource['metadata']['resourceVersion'], ops[0]['value'])
                if ops[-1]['path'] == '/spec':
                    resource['spec'] = ops[-1]['value']
                else:
                    self.assertEqual(resource['spec']['template']['spec']['containers'][0]['image'], ops[1]['value'])
                    resource['spec']['template']['spec']['containers'][0]['image'] = ops[-1]['value']
                resource['metadata']['resourceVersion'] = '2'
            if args[0] == 'create' and '--dry-run=server' not in args:
                resources[(body['kind'], body['metadata']['name'], None)] = {
                    **copy.deepcopy(body), 'metadata': {**body['metadata'], 'resourceVersion': '1'}}
            return b'{}'
        return resources, calls, get, kube

    def test_image_only_conditional_upgrade_and_completed_reentry(self):
        resources, calls, get, kube = self.fixture()
        self.assertEqual('Passed', target.execute(self.plan, get=get, kube=kube)['mesh_security_upgrade'])
        image_patches = [c for c in calls if c[0][:3] == ('-n', target.NAMESPACE, 'patch')]
        self.assertEqual(2, len(image_patches))
        for args, _, _ in image_patches:
            self.assertEqual(['test', 'test', 'replace'], [p['op'] for p in json.loads(args[-1])])
            self.assertEqual('/spec/template/spec/containers/0/image', json.loads(args[-1])[-1]['path'])
        before = copy.deepcopy(resources)
        calls.clear()
        target.execute(self.plan, get=get, kube=kube)
        self.assertEqual(before, resources)
        self.assertFalse(any('patch' in c[0] for c in calls))
        mutations = [c for c in calls if 'patch' in c[0] or c[0][0] in ('create', 'apply', 'delete')]
        self.assertTrue(all('--dry-run=server' in c[0] for c in mutations))

    def test_api_added_nested_defaults_preserve_rendered_constraints(self):
        expected = {'ports': [{'containerPort': 15012}], 'env': [
            {'name': 'POD_NAME', 'valueFrom': {'fieldRef': {'fieldPath': 'metadata.name'}}}]}
        actual = copy.deepcopy(expected)
        actual['ports'][0]['protocol'] = 'TCP'
        actual['env'][0]['valueFrom']['fieldRef']['apiVersion'] = 'v1'
        self.assertTrue(target.rendered_fields_match(actual, expected))
        actual['ports'][0]['containerPort'] = 22
        self.assertFalse(target.rendered_fields_match(actual, expected))
        actual = copy.deepcopy(expected)
        actual['env'].append({'name': 'UNREVIEWED', 'value': 'true'})
        self.assertFalse(target.rendered_fields_match(actual, expected))
        expected = {'resources': {'limits': {'cpu': 1}}, 'env': [{'name': 'PLATFORM', 'value': ''}]}
        actual = {'resources': {'limits': {'cpu': '1'}}, 'env': [{'name': 'PLATFORM'}]}
        self.assertTrue(target.rendered_fields_match(actual, expected))
        actual['env'][0]['value'] = 'unexpected'
        self.assertFalse(target.rendered_fields_match(actual, expected))
        actual['env'][0].pop('value')
        actual['resources']['limits']['cpu'] = '2'
        self.assertFalse(target.rendered_fields_match(actual, expected))

    def test_predecessor_boundary_privilege_and_image_drift_fail_before_writes(self):
        for field in ('image', 'securityContext', 'command'):
            resources, calls, get, kube = self.fixture()
            pod = resources[('daemonset', 'istio-cni-node', target.NAMESPACE)]['spec']['template']['spec']
            pod['containers'][0][field] = 'unreviewed'
            with self.subTest(field=field), self.assertRaises(ValueError):
                target.execute(self.plan, get=get, kube=kube)
            self.assertFalse(any('patch' in c[0] or c[0][0] in ('create', 'apply') for c in calls))

    def test_failed_rollout_retains_partial_state_without_rollback_or_reinit(self):
        resources, calls, get, kube = self.fixture()
        def failing(*args, **kwargs):
            if 'rollout' in args:
                raise ValueError('fixture rollout failed')
            return kube(*args, **kwargs)
        with self.assertRaises(ValueError):
            target.execute(self.plan, get=get, kube=failing)
        pod = resources[('deployment', 'istiod', target.NAMESPACE)]['spec']['template']['spec']
        self.assertEqual(renderer.pin()['images']['istiod'], pod['containers'][0]['image'])
        self.assertFalse(any('delete' in c[0] or 'init' in c[0] for c in calls))

    def test_replaced_or_modified_workload_after_preflight_is_not_patched(self):
        for field in ('uid', 'spec'):
            resources, calls, get, kube = self.fixture()
            reads = 0
            def drifting(kind, name, namespace=None):
                nonlocal reads
                if kind == 'deployment' and name == 'istiod':
                    reads += 1
                    if reads == 2:
                        resource = resources[(kind, name, namespace)]
                        resource['metadata']['resourceVersion'] = '42'
                        if field == 'uid':
                            resource['metadata']['uid'] = 'replacement-istiod'
                        else:
                            resource['spec']['template']['spec']['containers'][0]['command'] = ['changed']
                return get(kind, name, namespace)
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'changed after preflight'):
                target.execute(self.plan, get=drifting, kube=kube)
            self.assertFalse(any(c[0][:3] == ('-n', target.NAMESPACE, 'patch') for c in calls))

    def test_upgrade_staging_cannot_authorize_initial_commissioning(self):
        import build_platform_commissioning_bundle as bundle
        from datetime import datetime, timezone
        revision = 'a' * 40
        record = {'path': bundle.WORKFLOW, 'status': 'completed', 'conclusion': 'success',
            'repository': {'full_name': bundle.publication.REPOSITORY},
            'head_repository': {'full_name': bundle.publication.REPOSITORY},
            'event': 'pull_request', 'head_sha': revision}
        receipt = {'revision': revision, 'scope': 'Disposable Calico/Ambient initialized-store mesh security upgrade',
            'upgrade_publication': renderer.pin(), 'mesh_images': {
                **{n: v['image'] for n, v in renderer.admission.mesh_publication.targets().items()},
                **renderer.pin()['images']}, 'observed_at': datetime.now(timezone.utc).isoformat(),
            'checks': {name: 'Passed' for name in bundle.FOUNDATION},
            'platform_checks': {name: 'Passed' for name in bundle.CHECKS | {
                'image_only_mesh_upgrade_initialized_bao_preserved',
                'source_identity_istiod_positive_negative', 'source_identity_cni_positive_negative'}}}
        with patch.object(bundle, 'git', return_value='same-tree'):
            bundle.check_staging(record, receipt, revision, mesh_security_upgrade=True)
            with self.assertRaises(ValueError):
                bundle.check_staging(record, receipt, revision)
            receipt['mesh_images']['ztunnel'] = 'changed'
            with self.assertRaises(ValueError):
                bundle.check_staging(record, receipt, revision, mesh_security_upgrade=True)

    def test_public_scan_validates_embedded_fixed_binaries_and_fresh_database(self):
        now = datetime(2026, 10, 12, tzinfo=timezone.utc)
        selected = renderer.pin()
        files = {'publication.json': {'schema_version': 1, 'publication': 'Passed',
            'provenance_kind': 'patched-upstream-source-build',
            'repository_revision': selected['repository_revision'], 'images': selected['images']}}
        for component in renderer.COMPONENTS:
            digest = 'sha256:' + 'a' * 64
            receipt = {'schema_version': 1, 'component': component, 'image_config_digest': digest,
                'scan': 'Passed', 'recipe_sha256': selected['recipe_sha256'],
                'provenance_kind': 'patched-upstream-source-build', 'production_promotion': 'Not verified',
                'module_files_sha256': selected['module_files_sha256']}
            image = selected['images'][component]
            labels = {'io.hooshix.provenance.kind': receipt['provenance_kind'],
                'io.hooshix.source.revision': renderer.build.recipe()['sources']['istio']['revision'],
                'io.hooshix.recipe.sha256': selected['recipe_sha256']}
            files[component] = {'receipt.json': receipt, 'source-provenance.json': renderer.predicate(component),
                'syft.json': {'source': {'type': 'image', 'metadata': {
                    'imageID': digest, 'manifestDigest': image.split('@')[1], 'repoDigests': [image],
                    'os': 'linux', 'architecture': 'amd64', 'labels': labels}}, 'artifacts': [
                    {'name': 'stdlib', 'version': 'go1.26.9', 'locations': [
                        {'path': p} for p in renderer.build.BINARIES[component].values()]},
                    {'name': 'golang.org/x/net', 'version': 'v0.60.0'}]},
                'cyclonedx.json': {'bomFormat': 'CycloneDX', 'components': [{}]},
                'grype.json': {'matches': []}, 'database.json': {'valid': True, 'error': None,
                    'schemaVersion': '6.0.0', 'built': now.isoformat()}}
        original = copy.deepcopy(files)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def save():
                for name, data in files.items():
                    if name == 'publication.json':
                        (root / name).write_text(json.dumps(data))
                    else:
                        (root / name).mkdir(exist_ok=True)
                        for filename, value in data.items():
                            (root / name / filename).write_text(json.dumps(value))
            save()
            renderer.validate_publication(root, now)
            for fault in ('component', 'old-toolchain', 'missing-binary', 'high', 'stale'):
                files = copy.deepcopy(original)
                part = files['cni']
                if fault == 'component':
                    part['source-provenance.json']['buildDefinition']['externalParameters']['component'] = 'istiod'
                elif fault == 'old-toolchain':
                    part['syft.json']['artifacts'][0]['version'] = 'go1.26.7'
                elif fault == 'missing-binary':
                    part['syft.json']['artifacts'][0]['locations'].pop()
                elif fault == 'high':
                    part['grype.json']['matches'] = [{'vulnerability': {'severity': 'High'}}]
                else:
                    part['database.json']['built'] = (now - timedelta(days=6)).isoformat()
                save()
                with self.subTest(fault=fault), self.assertRaises(ValueError):
                    renderer.validate_publication(root, now)

    def test_bundle_requires_reviewed_main_and_exact_staging_and_no_private_material(self):
        import build_mesh_security_upgrade_bundle as bundle
        revision = 'a' * 40
        def git(*args):
            if args[0] == 'status':
                return ''
            if args[0] == 'ls-remote':
                return revision + '\trefs/heads/main'
            return revision
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'public-bundle'
            with patch.object(bundle.original, 'git', side_effect=git), \
                    patch.object(bundle.original, 'staged_receipt', return_value={
                        'observed_at': datetime.now(timezone.utc).isoformat()}) as staging, \
                    patch.object(bundle.publication, 'download') as download:
                manifest = bundle.build(output, '12345')
                self.assertTrue(staging.call_args.kwargs['mesh_security_upgrade'])
                download.assert_called_once()
                plan = json.loads((output / 'plan.json').read_bytes())
                self.assertEqual('Passed', plan['staging']['result'])
                self.assertEqual(set(bundle.SOURCES) | {bundle.WRAPPER, 'plan.json'}, set(manifest['files']))
                self.assertFalse(any(suffix in name for name in manifest['files']
                                     for suffix in ('.pem', '.pgp', 'credentials', 'encrypted')))
                with self.assertRaises(ValueError):
                    bundle.build(output, '12345')

    def test_publication_bound_to_authenticated_exact_run(self):
        import verify_mesh_security_publication as download
        record = {'conclusion': 'success', 'status': 'completed', 'head_branch': 'main',
            'event': 'workflow_dispatch', 'path': download.publication.WORKFLOW,
            'repository': {'full_name': download.publication.REPOSITORY},
            'head_sha': '0' * 40, 'run_attempt': renderer.pin()['publication_attempt']}
        with patch.object(download.publication, 'run', return_value=json.dumps(record).encode()), \
                self.assertRaises(ValueError):
            download.download(Path('/does-not-exist'))


if __name__ == '__main__':
    unittest.main()
