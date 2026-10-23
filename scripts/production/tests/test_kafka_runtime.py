"""The commissioned Kafka runtime stays signed, TLS-only, private and recoverable."""
import copy
import hashlib
import json
import re
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_kafka_runtime_bundle as bundle
import install_kafka_runtime as installer
import render_platform_admission as admission
import render_kafka_runtime as renderer
import rehearse_kafka_runtime as rehearsal


def prior_boundary():
    return admission.policy('ValidatingPolicy', 'hooshix-platform-data-closed', 'platform-data',
        [admission.validation('existing closed data contract', 'false')])


class KafkaRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.plan = renderer.render(prior_boundary())

    def test_runtime_is_the_exact_private_published_digest(self):
        pin = renderer.pin()
        self.assertEqual(pin['image'], self.plan['image'])
        self.assertRegex(self.plan['image'], r'@sha256:[a-f0-9]{64}$')
        self.assertEqual(pin['repository_revision'], self.plan['repository_revision'])
        self.assertEqual(3, len(self.plan['source_policies']))
        self.assertEqual({self.plan['image']}, {container['image']
            for container in self.plan['statefulset']['spec']['template']['spec']['containers']
            + self.plan['statefulset']['spec']['template']['spec']['initContainers']})

    def test_configuration_has_no_plaintext_listener_and_denies_default_acl(self):
        config = renderer.server_config()
        for expected in ('process.roles=broker,controller', 'controller.quorum.bootstrap.servers=',
                'ssl.client.auth=required', 'ssl.endpoint.identification.algorithm=https',
                'authorizer.class.name=org.apache.kafka.metadata.authorizer.StandardAuthorizer',
                'allow.everyone.if.no.acl.found=false', 'auto.create.topics.enable=false',
                'unclean.leader.election.enable=false'):
            self.assertIn(expected, config)
        for forbidden in ('PLAINTEXT', 'allow.everyone.if.no.acl.found=true', 'controller.quorum.voters='):
            self.assertNotIn(forbidden, config)

    def test_only_init_container_reads_tls_and_runtime_is_locked_down(self):
        pod = self.plan['statefulset']['spec']['template']['spec']
        self.assertFalse(pod['automountServiceAccountToken'])
        self.assertEqual(1, len(pod['initContainers']))
        self.assertEqual(1, len(pod['containers']))
        self.assertEqual({'name': 'source', 'mountPath': '/source', 'readOnly': True},
            next(m for m in pod['initContainers'][0]['volumeMounts'] if m['name'] == 'source'))
        self.assertNotIn('source', {m['name'] for m in pod['containers'][0]['volumeMounts']})
        self.assertTrue(pod['containers'][0]['securityContext']['readOnlyRootFilesystem'])
        self.assertEqual(['ALL'], pod['containers'][0]['securityContext']['capabilities']['drop'])
        self.assertTrue(pod['containers'][0]['securityContext']['runAsNonRoot'])

    def test_service_and_two_network_boundaries_are_closed(self):
        service = next(x for x in self.plan['objects'] if x['kind'] == 'Service')
        network = next(x for x in self.plan['objects'] if x['kind'] == 'NetworkPolicy')
        mesh = next(x for x in self.plan['objects'] if x['kind'] == 'AuthorizationPolicy')
        self.assertEqual('None', service['spec']['clusterIP'])
        self.assertEqual([{'name': 'tls', 'port': 9092, 'targetPort': 'tls'}], service['spec']['ports'])
        self.assertEqual([], network['spec']['ingress'])
        self.assertEqual({'selector': {'matchLabels': {'app.kubernetes.io/name': 'kafka'}},
                          'action': 'ALLOW', 'rules': []}, mesh['spec'])

    def test_startup_formats_only_empty_storage_and_rejects_unknown_existing_state(self):
        start = renderer.start_script()
        self.assertIn('find "$data" -mindepth 1 -maxdepth 1', start)
        self.assertIn('format --standalone', start)
        self.assertIn('KAFKA_EXISTING_DATA_REJECTED', start)
        self.assertNotIn('--ignore-formatted', start)
        self.assertNotIn('rm -', start)

    def test_pod_exception_is_exactly_one_owned_workload_and_keeps_old_rules(self):
        previous = prior_boundary()
        changed = renderer.boundary(previous, renderer.pod(), 'stateful-uid')
        expressions = ' '.join(rule['expression'] for rule in changed['spec']['validations'])
        self.assertTrue(changed['spec']['validations'][0]['expression'].endswith('|| (false)'))
        for expected in ('serviceAccountName == "kafka"', 'object.metadata.name == "kafka-0"',
                'object.metadata.labels["app.kubernetes.io/name"] == "kafka"',
                'ownerReferences.size() == 1', 'r.apiVersion == "apps/v1"',
                'r.name == "kafka" && r.controller',
                'object.metadata.ownerReferences[0].uid == "stateful-uid"',
                'object.spec.volumes.all(v, !has(v.hostPath))',
                '!has(c.lifecycle)', '!has(c.envFrom)', '!has(c.volumeDevices)'):
            self.assertIn(expected, expressions)
        self.assertEqual(['Deny'], changed['spec']['validationActions'])

    def test_boundary_binds_only_to_the_existing_owned_statefulset_uid(self):
        identity = {'metadata': {'uid': 'stateful-uid'}}
        actual = installer.boundary_for_statefulset(self.plan, identity['metadata']['uid'])
        expressions = ' '.join(item['expression'] for item in actual['spec']['validations'])
        self.assertEqual(2, sum(item['expression'].count('object.metadata.ownerReferences[0].uid == "stateful-uid"')
                                for item in actual['spec']['validations']))
        self.assertNotIn(renderer.BOUNDARY_UID_PLACEHOLDER, expressions)
        with self.assertRaisesRegex(ValueError, 'KAFKA_STATEFULSET_UID_REQUIRED'):
            installer.boundary_for_statefulset(self.plan, 'bad uid')
        broken = copy.deepcopy(self.plan)
        broken['boundary']['spec']['validations'][0]['expression'] = 'false'
        with self.assertRaisesRegex(ValueError, 'KAFKA_BOUNDARY_UID_TEMPLATE_REQUIRED'):
            installer.boundary_for_statefulset(broken, 'stateful-uid')

    def test_unowned_extra_or_foreign_pod_is_rejected(self):
        stateful = {'metadata': {'uid': 'expected'}}
        listed = {'items': [{'metadata': {'name': 'kafka-0', 'labels': {
            'app.kubernetes.io/managed-by': installer.MANAGER}, 'ownerReferences': [{
            'kind': 'StatefulSet', 'uid': 'expected', 'controller': True}]}}]}
        kube = Mock(return_value=json.dumps(listed).encode())
        self.assertEqual(1, len(installer.pod_inventory(kube, stateful, required=True)))
        listed['items'].append(copy.deepcopy(listed['items'][0]))
        kube.return_value = json.dumps(listed).encode()
        with self.assertRaisesRegex(ValueError, 'UNEXPECTED_KAFKA_POD_COUNT'):
            installer.pod_inventory(kube, stateful)
        listed['items'] = [copy.deepcopy(listed['items'][0])]
        listed['items'][0]['metadata']['ownerReferences'][0]['uid'] = 'foreign'
        kube.return_value = json.dumps(listed).encode()
        with self.assertRaisesRegex(ValueError, 'UNOWNED_KAFKA_POD_PRESERVED'):
            installer.pod_inventory(kube, stateful)

    def test_invalid_plan_fails_before_any_cluster_read(self):
        kube = Mock()
        with self.assertRaisesRegex(ValueError, 'CLOSED_KAFKA_RUNTIME_PLAN_REQUIRED'):
            installer.execute({}, kube=kube)
        kube.assert_not_called()

    def test_staging_failure_diagnostic_only_emits_allowlisted_invariant_codes(self):
        self.assertEqual('RESERVED_VOLUME_DRIFT_PRESERVED',
            rehearsal.safe_failure_code(ValueError('RESERVED_VOLUME_DRIFT_PRESERVED')))
        self.assertEqual('EMPTY_QUARANTINED_KAFKA_REQUIRED',
            rehearsal.safe_failure_code(ValueError('EMPTY_QUARANTINED_KAFKA_REQUIRED')))
        self.assertEqual('EXISTING_TYPED_REDIS_SECRETS_REQUIRED',
            rehearsal.safe_failure_code(ValueError('EXISTING_TYPED_REDIS_SECRETS_REQUIRED')))
        self.assertIsNone(rehearsal.safe_failure_code(ValueError('GHCR_TOKEN_MUST_STAY_PRIVATE')))
        self.assertIsNone(rehearsal.safe_failure_code(ValueError('RESERVED_VOLUME_DRIFT_PRESERVED', 'extra')))
        bootstrap_failure = rehearsal.installer.host.custody.BootstrapFailed
        self.assertEqual('UNLISTED_STATIC_BOOTSTRAP_CODE',
            rehearsal.safe_failure_code(bootstrap_failure('UNLISTED_STATIC_BOOTSTRAP_CODE')))
        self.assertIsNone(rehearsal.safe_failure_code(bootstrap_failure('private details')))
        self.assertEqual('ValueError', rehearsal.safe_failure_type(ValueError('GHCR_TOKEN_MUST_STAY_PRIVATE')))
        unsafe_type = type('GHCR_TOKEN_MUST_STAY_PRIVATE', (Exception,), {})
        self.assertEqual('Other', rehearsal.safe_failure_type(unsafe_type()))

    def test_native_rehearsal_uses_disposable_node_for_all_preserved_local_volumes(self):
        node = 'hooshix-bao-ci-0123456789ab-control-plane'
        previous = json.dumps(prior_boundary()).encode()
        captured = {}

        def stop_after_node_binding(*args, **kwargs):
            captured['nodes'] = (rehearsal.volume.NODE, installer.volume.NODE,
                renderer.volume.NODE, rehearsal.tls.redis.volume.NODE,
                rehearsal.tls.redis.pg.volume.NODE)
            raise RuntimeError('FIXTURE_NODE_BOUND')

        with patch.object(rehearsal.volume, 'reserve'), \
                patch.object(rehearsal.renderer, 'render', return_value={}), \
                patch.object(rehearsal.installer, 'execute', side_effect=stop_after_node_binding), \
                patch.dict('os.environ', {'GITHUB_SHA': 'a' * 40, 'GITHUB_RUN_ID': '12345'}):
            with self.assertRaisesRegex(RuntimeError, 'FIXTURE_NODE_BOUND'):
                rehearsal.rehearse(lambda *args, **kwargs: previous, lambda *_args, **_kwargs: None,
                    lambda *_args, **_kwargs: None, 'fixture-ca', node, {})
        self.assertEqual((node, node, node, node, node), captured['nodes'])

    def test_staging_source_revision_is_bound_to_exact_owner_bundle(self):
        wrapper = (Path(__file__).resolve().parents[1] / 'run-kafka-runtime.ps1').read_text()
        source_block = wrapper.split('$sources = @(', 1)[1].split(')', 1)[0]
        wrapper_sources = tuple(re.findall(r"'([a-z0-9_-]+\.py)'", source_block))
        self.assertEqual(bundle.SOURCES, wrapper_sources)
        for expected in ('$actualEntries = @(Get-ChildItem -LiteralPath $PSScriptRoot -Force)',
                '$plan.repository_source_revision -cne $manifest.source_revision',
                '$plan.staging.source_revision -cne $manifest.source_revision',
                '[string]$plan.staging.run_id -cne [string]$manifest.staging_run',
                'BOUNDED_REGULAR_BUNDLE_MANIFEST_REQUIRED'):
            self.assertIn(expected, wrapper)
        self.plan['repository_source_revision'] = 'a' * 40
        self.plan['staging'] = {'source_revision': 'b' * 40, 'run_id': '39912933274',
            'observed_at': '2026-10-23T00:00:00+00:00', 'result': 'Passed'}
        with self.assertRaisesRegex(ValueError, 'CLOSED_KAFKA_RUNTIME_PLAN_REQUIRED'):
            installer.execute(self.plan, kube=Mock())

    def test_owner_bundle_requires_closed_runtime_and_accurate_nonproduction_scope(self):
        good = {'schema_version': 1, 'image': renderer.pin()['image'],
            'business_topics': 'None', 'storage_guard': 'Not run',
            'host_storage_guard': 'Not run; disposable local path',
            'existing_database_redis_openbao_eso_tls': 'Preserved',
            'application_ingress': 'CLOSED', 'public_access': 'CLOSED',
            'statefulset_uid': 'stateful', 'pvc_uid': 'claim', 'volume_uid': 'volume',
            'cluster_identity_sha256': 'a' * 64, 'capacity': 'Not verified',
            'production_readiness': 'Not verified',
            **dict.fromkeys(bundle.CHECKS, 'Passed'),
            **dict.fromkeys(('automatic_renewal', 'provider_backup', 'root_revocation'), 'Not run')}
        receipt = {'kafka_closed_runtime': good}
        with patch.object(bundle.previous, 'verify_proof'):
            bundle.verify_proof(receipt)
            for change in ({'application_ingress': 'OPEN'}, {'business_topics': 'present'},
                           {'production_readiness': 'Passed'}, {'mutual_tls_health': 'Not run'},
                           {'host_storage_guard': 'Passed'}):
                with self.subTest(change=change), self.assertRaises(ValueError):
                    bundle.verify_proof({'kafka_closed_runtime': good | change})


if __name__ == '__main__':
    unittest.main()
