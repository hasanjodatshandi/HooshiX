"""Limited source upgrade preserves initialized-store and original bootstrap contracts."""
import copy
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import render_mesh_security_upgrade as renderer
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
            self.assertIn(renderer.admission.literal(expected), expression)
            self.assertTrue(expression.startswith('variables.provenanceVerified && '))
            self.assertNotIn('gitRevision', expression)
            self.assertNotIn('messageExpression', policy['spec']['validations'][2])
        with self.assertRaises(ValueError):
            renderer.predicate('openbao')
        for key in ('boundary_before', 'boundary_after'):
            self.assertIn(renderer.admission.mesh_publication.targets()['ztunnel']['image'],
                          self.plan[key]['spec']['validations'][0]['expression'])

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
