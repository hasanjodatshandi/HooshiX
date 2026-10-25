"""The commissioned Kafka runtime stays signed, TLS-only, private and recoverable."""
import base64
import copy
import contextlib
import hashlib
import io
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

    def test_private_source_policies_keep_the_kyverno_registry_secret_reference(self):
        self.assertTrue(all(policy['spec']['credentials'] == {'secrets': [admission.PULL_SECRET]}
                            for policy in self.plan['source_policies']))

    def test_private_image_pull_uses_only_the_approved_namespaced_secret(self):
        pod = self.plan['statefulset']['spec']['template']['spec']
        self.assertEqual([{'name': admission.PULL_SECRET}], pod['imagePullSecrets'])

    def test_read_only_kafka_root_has_bounded_memory_backed_runtime_scratch(self):
        pod = self.plan['statefulset']['spec']['template']['spec']
        volumes = {volume['name']: volume for volume in pod['volumes']}
        mounts = {mount['name']: mount['mountPath'] for mount in pod['containers'][0]['volumeMounts']}
        self.assertEqual({'logs': '/opt/kafka/logs', 'tmp': '/tmp'},
                         {name: mounts[name] for name in ('logs', 'tmp')})
        self.assertEqual({'medium': 'Memory', 'sizeLimit': '16Mi'}, volumes['logs']['emptyDir'])
        self.assertEqual({'medium': 'Memory', 'sizeLimit': '32Mi'}, volumes['tmp']['emptyDir'])

    def test_registry_pull_secret_is_copied_from_owned_source_without_output(self):
        auth = base64.b64encode(b'hasanjodatshandi:synthetic-read-only-token').decode()
        payload = json.dumps({'auths': {'ghcr.io': {'auth': auth}}}).encode()
        source = {'metadata': {'name': admission.PULL_SECRET, 'namespace': 'hooshix-secrets',
            'labels': dict(installer.host.LABELS)}, 'type': 'kubernetes.io/dockerconfigjson',
            'data': {'.dockerconfigjson': base64.b64encode(payload).decode()}}
        client = Mock()
        with patch.object(installer, 'get', return_value=source), \
                patch.object(installer.host, 'write_registry_secret') as write, \
                contextlib.redirect_stdout(io.StringIO()) as output:
            installer.ensure_registry_pull_secret(client)
        write.assert_called_once_with(renderer.NS, source['data']['.dockerconfigjson'], kube_call=client)
        self.assertEqual('', output.getvalue())

    def test_registry_pull_secret_rejects_unowned_or_malformed_source(self):
        auth = base64.b64encode(b'hasanjodatshandi:synthetic-token').decode()
        payload = json.dumps({'auths': {'ghcr.io': {'auth': auth}}}).encode()
        valid = {'metadata': {'name': admission.PULL_SECRET, 'namespace': 'hooshix-secrets',
            'labels': dict(installer.host.LABELS)}, 'type': 'kubernetes.io/dockerconfigjson',
            'data': {'.dockerconfigjson': base64.b64encode(payload).decode()}}
        extra_registry = base64.b64encode(json.dumps({'auths': {
            'ghcr.io': {'auth': auth}, 'other.example': {'auth': auth}}}).encode()).decode()
        malformed_auth = base64.b64encode(json.dumps({'auths': {'ghcr.io': {'auth': 'not-base64'}}}).encode()).decode()
        invalid = [None, valid | {'type': 'Opaque'}, valid | {'data': {'other': 'private'}},
                   'malformed-source', valid | {'metadata': {'labels': None}},
                   {'metadata': valid['metadata'] | {'labels': {}}, 'type': valid['type'], 'data': valid['data']},
                   valid | {'metadata': valid['metadata'] | {'ownerReferences': [{'uid': 'foreign'}]}},
                   valid | {'metadata': valid['metadata'] | {'deletionTimestamp': '2026-10-24T00:00:00Z'}},
                   valid | {'data': {'.dockerconfigjson': 'not-base64'}},
                   valid | {'data': {'.dockerconfigjson': base64.b64encode(b'{}').decode()}},
                   valid | {'data': {'.dockerconfigjson': extra_registry}},
                   valid | {'data': {'.dockerconfigjson': malformed_auth}}]
        for source in invalid:
            with patch.object(installer, 'get', return_value=source), \
                    patch.object(installer.host, 'write_registry_secret') as write, \
                    self.assertRaises(ValueError):
                installer.ensure_registry_pull_secret(Mock())
            write.assert_not_called()

    def test_configuration_has_no_plaintext_listener_and_denies_default_acl(self):
        config = renderer.server_config()
        for expected in ('process.roles=broker,controller', 'controller.quorum.bootstrap.servers=',
                'advertised.listeners=SSL://127.0.0.1:9092',
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

    def test_readiness_deadline_covers_the_startup_probe_budget(self):
        probe = self.plan['statefulset']['spec']['template']['spec']['containers'][0]['startupProbe']
        startup_budget = probe['failureThreshold'] * probe['periodSeconds']
        self.assertGreaterEqual(installer.READINESS_TIMEOUT_SECONDS, startup_budget + 60)

    def test_short_lived_cli_and_probe_cancel_the_actual_jvm(self):
        self.assertTrue(renderer.PROBE.startswith('exec /usr/bin/env '))
        self.assertEqual(installer.TOOL_JVM_OPTIONS, renderer.TOOL_JVM_OPTIONS)
        self.assertIn('KAFKA_JVM_PERFORMANCE_OPTS="' + installer.TOOL_JVM_OPTIONS + '"', renderer.PROBE)
        self.assertEqual('-XX:+UseSerialGC -XX:TieredStopAtLevel=1', installer.TOOL_JVM_OPTIONS)
        container = self.plan['statefulset']['spec']['template']['spec']['containers'][0]
        self.assertEqual([{'name': 'KAFKA_HEAP_OPTS', 'value': '-Xms512m -Xmx512m'}], container['env'])

    def test_readiness_snapshot_exposes_only_bounded_status_classes(self):
        objects = {
            'statefulset': {'metadata': {'generation': 2}, 'spec': {'replicas': 1},
                'status': {'observedGeneration': 2, 'replicas': 1, 'readyReplicas': 0}},
            'pod': {'status': {'phase': 'Pending', 'conditions': [{
                'type': 'PodScheduled', 'status': 'False', 'reason': 'FailedScheduling',
                'message': 'PRIVATE_EVENT_MESSAGE'}], 'initContainerStatuses': [{
                'name': 'tls-material', 'ready': False, 'restartCount': 0,
                'state': {'waiting': {'reason': 'ErrImagePull', 'message': 'PRIVATE_IMAGE_ERROR'}}}],
                'containerStatuses': []}},
            'pod-events': {'items': [{'type': 'Warning', 'reason': 'FailedScheduling',
                'message': 'PRIVATE_EVENT_MESSAGE'}, {'type': 'Warning', 'reason': 'OtherSecretValue',
                'message': 'PRIVATE_SECOND_EVENT'}, {'type': 'Warning',
                'message': 'PRIVATE_THIRD_EVENT'}]},
            'statefulset-events': {'items': []},
        }

        def kube(*args, **_kwargs):
            if 'events' in args:
                selector = args[args.index('--field-selector') + 1]
                kind = 'pod-events' if 'involvedObject.kind=Pod' in selector else 'statefulset-events'
            else:
                kind = next(value for value in ('statefulset', 'pod') if value in args)
                if kind == 'pod' and objects[kind] is None:
                    return b''
            return json.dumps(objects[kind]).encode()

        result = rehearsal.readiness_snapshot(kube)
        serialized = json.dumps(result)
        self.assertEqual(1, result['statefulset']['desired_replicas'])
        self.assertEqual(2, result['statefulset']['observed_generation'])
        self.assertEqual(0, result['statefulset']['ready_replicas'])
        self.assertEqual('ErrImagePull', result['pod']['containers'][0]['reason'])
        self.assertEqual([{'reason': 'FailedScheduling', 'count': 1}, {'reason': 'Other', 'count': 2}],
                         result['warning_events']['pod']['reasons'])
        for private_value in ('PRIVATE_EVENT_MESSAGE', 'PRIVATE_IMAGE_ERROR', 'PRIVATE_SECOND_EVENT',
                              'PRIVATE_THIRD_EVENT'):
            self.assertNotIn(private_value, serialized)

    def test_readiness_snapshot_captures_statefulset_pod_creation_failure(self):
        objects = {
            'statefulset': {'metadata': {'generation': 3}, 'spec': {'replicas': 1},
                'status': {'observedGeneration': 3, 'replicas': 0, 'readyReplicas': 0,
                    'conditions': [{'type': 'ReplicaFailure', 'status': 'True', 'reason': 'FailedCreate',
                                    'message': 'PRIVATE_ADMISSION_DETAIL'}]}},
            'pod': None,
            'pod-events': {'items': []},
            'statefulset-events': {'items': [{'type': 'Warning', 'reason': 'FailedCreate',
                'message': 'Error creating pod: forbidden; policy hooshix-platform-data-closed '
                           'rejected closed Kafka runtime contract rejected PRIVATE_ADMISSION_DETAIL'}]},
        }

        def kube(*args, **_kwargs):
            if 'events' in args:
                selector = args[args.index('--field-selector') + 1]
                kind = 'pod-events' if 'involvedObject.kind=Pod' in selector else 'statefulset-events'
            else:
                kind = next(value for value in ('statefulset', 'pod') if value in args)
                if kind == 'pod' and objects[kind] is None:
                    return b''
            return json.dumps(objects[kind]).encode()

        result = rehearsal.readiness_snapshot(kube)
        serialized = json.dumps(result)
        self.assertEqual(1, result['statefulset']['desired_replicas'])
        self.assertEqual(0, result['statefulset']['replicas'])
        self.assertEqual([{'type': 'ReplicaFailure', 'status': 'True', 'reason': 'FailedCreate'}],
                         result['statefulset']['conditions'])
        self.assertEqual([{'reason': 'FailedCreate', 'count': 1}],
                         result['warning_events']['statefulset']['reasons'])
        self.assertEqual([{'class': 'ADMISSION_REJECTED', 'count': 1}],
                         result['warning_events']['statefulset']['failed_create_classes'])
        self.assertEqual(['hooshix-platform-data-closed'],
                         result['warning_events']['statefulset']['known_admission_policies'])
        self.assertEqual(['KAFKA_RUNTIME_BOUNDARY'],
                         result['warning_events']['statefulset']['known_admission_failures'])
        self.assertFalse(result['pod']['present'])
        self.assertNotIn('PRIVATE_ADMISSION_DETAIL', serialized)
        self.assertIn('hooshix-platform-data-closed', rehearsal.SAFE_KAFKA_ADMISSION_POLICIES)

    def test_memory_snapshot_emits_only_known_cgroup_counters_and_byte_counts(self):
        result = rehearsal.memory_snapshot(Mock(return_value=(
            b'low 0\nhigh 0\nmax 10\noom 2\noom_kill 1\n'
            b'PRIVATE_KEY 999\noom_group_kill PRIVATE_VALUE\n123\n2147483648\n')))
        self.assertEqual({'low': 0, 'high': 0, 'max': 10, 'oom': 2, 'oom_kill': 1,
                          'current_bytes': 123, 'limit_bytes': 2147483648}, result)
        self.assertNotIn('PRIVATE', json.dumps(result))

    def test_failed_create_diagnostic_classes_are_fixed_and_bounded(self):
        cases = (
            ('pods exceeded quota PRIVATE_QUOTA', 'RESOURCE_QUOTA_EXCEEDED'),
            ('failed calling webhook PRIVATE_WEBHOOK', 'ADMISSION_WEBHOOK_UNAVAILABLE'),
            ('violates PodSecurity restricted PRIVATE_POLICY', 'POD_SECURITY_REJECTED'),
            ('serviceaccount was not found PRIVATE_REFERENCE', 'REFERENCED_OBJECT_MISSING'),
            ('unknown private detail PRIVATE_OTHER', 'OTHER'),
        )
        for message, expected in cases:
            with self.subTest(expected=expected):
                result = rehearsal.failed_create_class(message)
                self.assertEqual(expected, result)
                self.assertIn(result, rehearsal.SAFE_CREATE_FAILURE_CLASSES)

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
                'object.spec.imagePullSecrets.size() == 1',
                'object.spec.imagePullSecrets[0].name == "hooshix-ghcr-read"',
                '!has(c.lifecycle)', '!has(c.envFrom)', '!has(c.volumeDevices)'):
            self.assertIn(expected, expressions)
        self.assertEqual(['Deny'], changed['spec']['validationActions'])
        self.assertIn('object.spec.serviceAccountName == "kafka" ||',
                      changed['spec']['validations'][0]['expression'])
        self.assertIn('object.spec.serviceAccountName != "kafka" ||',
                      changed['spec']['validations'][1]['expression'])
        self.assertIn('ownerReferences[0].uid == "stateful-uid"',
                      changed['spec']['validations'][1]['expression'])

    def test_boundary_binds_only_to_the_existing_owned_statefulset_uid(self):
        identity = {'metadata': {'uid': 'stateful-uid'}}
        actual = installer.boundary_for_statefulset(self.plan, identity['metadata']['uid'])
        self.assertEqual('hooshix-platform-data-closed', actual['metadata']['name'])
        expressions = ' '.join(item['expression'] for item in actual['spec']['validations'])
        self.assertEqual(1, sum(item['expression'].count('object.metadata.ownerReferences[0].uid == "stateful-uid"')
                                for item in actual['spec']['validations']))
        self.assertNotIn(renderer.BOUNDARY_UID_PLACEHOLDER, expressions)
        with self.assertRaisesRegex(ValueError, 'KAFKA_STATEFULSET_UID_REQUIRED'):
            installer.boundary_for_statefulset(self.plan, 'bad uid')
        broken = copy.deepcopy(self.plan)
        broken['boundary']['spec']['validations'][-1]['expression'] = 'false'
        with self.assertRaisesRegex(ValueError, 'KAFKA_BOUNDARY_UID_TEMPLATE_REQUIRED'):
            installer.boundary_for_statefulset(broken, 'stateful-uid')

    def test_admission_candidate_matches_the_quiescent_statefulset_pod(self):
        stateful = copy.deepcopy(self.plan['statefulset'])
        stateful['metadata'].update(namespace=renderer.NS, uid='stateful-uid')
        candidate = installer.admission_candidate(stateful)
        self.assertEqual(('Pod', renderer.NS, 'kafka-0'),
            (candidate['kind'], candidate['metadata']['namespace'], candidate['metadata']['name']))
        self.assertEqual(stateful['spec']['template']['spec'], candidate['spec'])
        self.assertEqual(stateful['spec']['template']['metadata']['labels'], candidate['metadata']['labels'])
        self.assertEqual([{'apiVersion': 'apps/v1', 'kind': 'StatefulSet', 'name': 'kafka',
            'uid': 'stateful-uid', 'controller': True, 'blockOwnerDeletion': True}],
            candidate['metadata']['ownerReferences'])

    def test_admission_preflight_waits_for_positive_policy_then_checks_exact_negative(self):
        stateful = copy.deepcopy(self.plan['statefulset'])
        stateful['metadata'].update(namespace=renderer.NS, uid='stateful-uid')
        denied = RuntimeError('PRIVATE_REHEARSAL_FAILURE')
        kube = Mock(side_effect=[b'', denied, b'', b'', b''])

        with patch.object(installer.time, 'sleep') as sleep:
            installer.preflight_admission(kube, stateful)

        self.assertEqual(5, kube.call_count)
        self.assertEqual(2, sleep.call_args.args[0])
        positive = kube.call_args_list[3]
        self.assertIn('--dry-run=server', positive.args)
        negative = kube.call_args_list[4]
        self.assertEqual(1, negative.kwargs['expected'])
        self.assertEqual(b'closed Kafka runtime contract rejected', negative.kwargs['required_error'])
        self.assertEqual('00000000-0000-4000-8000-000000000001',
            negative.kwargs['body']['metadata']['ownerReferences'][0]['uid'])

    def test_admission_preflight_fails_closed_when_the_exact_pod_remains_denied(self):
        stateful = copy.deepcopy(self.plan['statefulset'])
        stateful['metadata'].update(namespace=renderer.NS, uid='stateful-uid')
        kube = Mock(side_effect=[b'', RuntimeError('PRIVATE_REHEARSAL_FAILURE')])

        with patch.object(installer.time, 'monotonic', side_effect=[0, 0, 0, installer.ADMISSION_RETRY_SECONDS]), \
                patch.object(installer.time, 'sleep') as sleep, \
                self.assertRaisesRegex(ValueError, 'KAFKA_ADMISSION_POSITIVE_PREFLIGHT_FAILED'):
            installer.preflight_admission(kube, stateful)

        self.assertEqual(2, kube.call_count)
        sleep.assert_not_called()

    def test_admission_preflight_rejects_a_broken_kafka_owner_contract(self):
        stateful = copy.deepcopy(self.plan['statefulset'])
        stateful['metadata'].update(namespace=renderer.NS, uid='stateful-uid')
        kube = Mock(side_effect=[b'', b'', RuntimeError('PRIVATE_REHEARSAL_FAILURE')])

        with self.assertRaisesRegex(ValueError, '^KAFKA_ADMISSION_NEGATIVE_PREFLIGHT_FAILED$') as failure:
            installer.preflight_admission(kube, stateful)

        self.assertEqual(3, kube.call_count)
        self.assertEqual(1, kube.call_args.kwargs['expected'])
        self.assertNotIn('PRIVATE_REHEARSAL_FAILURE', str(failure.exception.__cause__))

    def test_admission_preflight_uses_dry_run_updates_for_an_existing_pod(self):
        stateful = copy.deepcopy(self.plan['statefulset'])
        stateful['metadata'].update(namespace=renderer.NS, uid='stateful-uid')
        existing = installer.admission_candidate(stateful)
        existing['metadata']['uid'] = 'pod-uid'
        kube = Mock(side_effect=[json.dumps(existing).encode(), b'', b''])

        installer.preflight_admission(kube, stateful)

        self.assertEqual(3, kube.call_count)
        positive = kube.call_args_list[1]
        self.assertIn('patch', positive.args)
        self.assertIn('--dry-run=server', positive.args)
        self.assertIn('hooshix.io/admission-preflight', json.loads(
            positive.args[positive.args.index('-p') + 1])['metadata']['annotations'])
        negative = kube.call_args_list[2]
        self.assertEqual(1, negative.kwargs['expected'])
        self.assertEqual(b'closed Kafka runtime contract rejected', negative.kwargs['required_error'])
        self.assertEqual('00000000-0000-4000-8000-000000000001', json.loads(
            negative.args[negative.args.index('-p') + 1])['metadata']['ownerReferences'][0]['uid'])

    def test_admission_preflight_rejects_an_existing_pod_with_contract_drift(self):
        stateful = copy.deepcopy(self.plan['statefulset'])
        stateful['metadata'].update(namespace=renderer.NS, uid='stateful-uid')
        existing = installer.admission_candidate(stateful)
        existing['metadata']['labels']['app.kubernetes.io/name'] = 'foreign'
        kube = Mock(return_value=json.dumps(existing).encode())

        with self.assertRaisesRegex(ValueError, 'KAFKA_EXISTING_POD_CONTRACT_REQUIRED'):
            installer.preflight_admission(kube, stateful)

        kube.assert_called_once()

    def test_admission_policy_snapshot_is_bounded_and_excludes_private_messages(self):
        policy = {'metadata': {'generation': 8}, 'status': {'conditionStatus': {
            'ready': True, 'message': 'PRIVATE_MESSAGE', 'conditions': [
                {'type': 'WebhookConfigured', 'status': 'True', 'reason': 'Succeeded',
                    'observedGeneration': 8, 'message': 'PRIVATE_CONDITION'},
                {'type': 'RBACPermissionsGranted', 'status': 'Unknown', 'reason': 'InternalError'},
                {'type': 'Unrecognized', 'status': 'True', 'reason': 'PRIVATE_REASON'}]}}}

        with patch.object(rehearsal.installer, 'get', return_value=policy):
            snapshot = rehearsal.admission_policy_snapshot(Mock())

        self.assertEqual({'present': True, 'generation': 8, 'ready': True, 'conditions': [
            {'type': 'WebhookConfigured', 'status': 'True', 'reason': 'Succeeded',
                'observed_generation': 8},
            {'type': 'RBACPermissionsGranted', 'status': 'Unknown', 'reason': 'Other',
                'observed_generation': None}]}, snapshot)
        serialized = json.dumps(snapshot)
        for secret in ('PRIVATE_MESSAGE', 'PRIVATE_CONDITION', 'PRIVATE_REASON', 'InternalError'):
            self.assertNotIn(secret, serialized)

    def test_owned_service_accepts_api_omitted_false_without_mutating_the_snapshot(self):
        desired = next(item for item in self.plan['objects'] if item['kind'] == 'Service')
        actual = copy.deepcopy(desired)
        actual['spec'].pop('publishNotReadyAddresses')
        actual['metadata']['annotations'] = {installer.HASH: installer.digest(actual['spec'])}
        before = copy.deepcopy(actual)
        installer.owned(actual, desired)
        self.assertEqual(before, actual)

    def test_owned_service_rejects_true_and_exposes_only_the_public_field_path(self):
        desired = next(item for item in self.plan['objects'] if item['kind'] == 'Service')
        actual = copy.deepcopy(desired)
        actual['spec']['publishNotReadyAddresses'] = True
        actual['metadata']['annotations'] = {installer.HASH: installer.digest(actual['spec']),
                                             'private': 'PRIVATE_VALUE'}
        output = io.StringIO()
        with contextlib.redirect_stderr(output), self.assertRaisesRegex(
                ValueError, 'FOREIGN_OR_CHANGED_KAFKA_RESOURCE_PRESERVED'):
            installer.owned(actual, desired)
        self.assertIn('spec.publishNotReadyAddresses', output.getvalue())
        self.assertNotIn('PRIVATE_VALUE', output.getvalue())

    def test_owned_quiescent_statefulset_is_resumable_only_without_other_drift(self):
        desired = copy.deepcopy(self.plan['statefulset'])
        staged = copy.deepcopy(desired)
        staged['spec']['replicas'] = 0
        staged['metadata']['annotations'] = {installer.HASH: installer.digest(staged['spec'])}
        installer.owned(staged, desired)

        staged['spec']['template']['spec']['containers'][0]['image'] = 'unreviewed:latest'
        staged['metadata']['annotations'][installer.HASH] = installer.digest(staged['spec'])
        with self.assertRaisesRegex(ValueError, 'FOREIGN_OR_CHANGED_KAFKA_RESOURCE_PRESERVED'):
            installer.owned(staged, desired)

    def test_activate_statefulset_is_atomic_and_hashes_the_final_defaulted_spec(self):
        spec = copy.deepcopy(self.plan['statefulset']['spec'])
        spec['replicas'] = 0
        stateful = {'metadata': {'annotations': {installer.HASH: installer.digest(spec)}}, 'spec': spec}
        kube = Mock()

        installer.activate_statefulset(kube, stateful)

        args = kube.call_args.args
        self.assertEqual(('patch', 'statefulset', 'kafka', '-n', 'platform-data', '--type=json'), args[:6])
        patch = json.loads(args[7])
        self.assertEqual([spec, 0, installer.digest(spec)],
                         [patch[0]['value'], patch[1]['value'], patch[2]['value']])
        self.assertEqual([1, installer.digest(spec | {'replicas': 1})],
                         [patch[3]['value'], patch[4]['value']])

    def test_activate_statefulset_does_not_patch_an_already_active_statefulset(self):
        kube = Mock()
        installer.activate_statefulset(kube, {'spec': {'replicas': 1}})
        kube.assert_not_called()

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
        self.assertEqual('KAFKA_BOUNDARY_DRIFT_PRESERVED',
            rehearsal.safe_failure_code(ValueError('KAFKA_BOUNDARY_DRIFT_PRESERVED')))
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
        self.assertEqual('builtins.ValueError',
            rehearsal.safe_failure_type(ValueError('GHCR_TOKEN_MUST_STAY_PRIVATE')))
        unsafe_type = type('invalid exception name', (Exception,), {})
        self.assertEqual('Other', rehearsal.safe_failure_type(unsafe_type()))

    def test_staging_failure_location_excludes_exception_details(self):
        def fail():
            raise UnicodeDecodeError('utf-8', b'PRIVATE_SECRET_BYTES', 0, 1, 'PRIVATE_ERROR_DETAIL')

        try:
            fail()
        except UnicodeDecodeError as error:
            location = rehearsal.safe_failure_location(error)
        self.assertRegex(location, r'\.fail:[0-9]+$')
        self.assertNotIn('PRIVATE_SECRET_BYTES', location)
        self.assertNotIn('PRIVATE_ERROR_DETAIL', location)

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

    def test_staging_exec_failure_reports_only_the_fixed_operation(self):
        def k(*args, **kwargs):
            if 'exec' in args:
                raise RuntimeError('PRIVATE_ERROR')
            return json.dumps(prior_boundary()).encode()

        def execute(*args, **kwargs):
            kwargs['kube']('-n', renderer.NS, 'exec', 'kafka-0', '-c', 'kafka', '--',
                '/opt/kafka/bin/kafka-metadata-quorum.sh', 'PRIVATE_ARGUMENT')

        output = io.StringIO()
        with patch.object(rehearsal.volume, 'reserve'), \
                patch.object(rehearsal.renderer, 'render', return_value={}), \
                patch.object(rehearsal.installer, 'execute', side_effect=execute), \
                patch.dict('os.environ', {'GITHUB_SHA': 'a' * 40, 'GITHUB_RUN_ID': '12345'}), \
                contextlib.redirect_stdout(output), self.assertRaises(RuntimeError):
            rehearsal.rehearse(k, lambda *_a, **_k: None, lambda *_a, **_k: None,
                'fixture-ca', 'hooshix-bao-ci-0123456789ab-control-plane', {})
        self.assertIn('KAFKA_RUNTIME_EXEC_FAILURE=quorum', output.getvalue())
        self.assertNotIn('PRIVATE_ERROR', output.getvalue())
        self.assertNotIn('PRIVATE_ARGUMENT', output.getvalue())

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
                           {'data_admission_boundary': 'Not run'},
                           {'host_storage_guard': 'Passed'}):
                with self.subTest(change=change), self.assertRaises(ValueError):
                    bundle.verify_proof({'kafka_closed_runtime': good | change})

    def test_bundle_preserves_only_the_explicit_known_retained_quarantine(self):
        base = prior_boundary()
        with patch.object(bundle.postgresql, 'render', return_value={'boundary': base}):
            retained = bundle.data_boundary(Path('chart'), 'helm', True)
            current = bundle.data_boundary(Path('chart'), 'helm')
        expected = bundle.redis.boundary(bundle.quarantine.boundary(
            base, bundle.previous.installer.RETAINED_DATABASE_IMAGES), bundle.redis.pod())
        self.assertEqual(expected, retained)
        self.assertNotEqual(current['spec'], retained['spec'])
        expression = json.dumps(retained['spec'])
        for image in bundle.previous.installer.RETAINED_DATABASE_IMAGES.values():
            self.assertIn(image, expression)
        plan = renderer.render(retained)
        self.assertEqual(retained, plan['previous_boundary'])
        self.assertNotIn('StatefulSet', json.dumps(plan['objects']))


if __name__ == '__main__':
    unittest.main()
