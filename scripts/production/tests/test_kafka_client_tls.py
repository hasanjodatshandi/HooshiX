"""Real client-only crypto, fixed service bindings and create-only reconciliation."""
import base64
import contextlib
import copy
import hashlib
import io
import json
import sys
import unittest
import zlib
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import kafka_client_tls as contract
import install_kafka_client_tls as installer
import bootstrap_kafka_client_tls as bootstrap
import build_kafka_client_tls_bundle as bundle
import activate_openbao_operator as operator
import redis_tls_material as shared
from rehearse_redis_tls import fixture_issuer, synthetic_memory
from test_postgresql_secrets import CredentialAPI


class KafkaClientTLSTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.memory = synthetic_memory('synthetic-client-root')
        cls.memory.__enter__()
        cls.addClassCleanup(cls.memory.__exit__, None, None, None)
        cls.ram = shared.ram_work()
        cls.work = cls.ram.__enter__()
        cls.addClassCleanup(cls.ram.__exit__, None, None, None)
        shared.platform.native([shared.custody.OPENSSL, 'req', '-x509', '-newkey', 'rsa:3072', '-nodes',
            '-days', '365', '-subj', '/CN=synthetic-client-root', '-keyout', str(cls.work / 'root.key'),
            '-out', str(cls.work / 'root.crt')], timeout=60)
        cls.root = (cls.work / 'root.crt').read_text()
        cls.signer = fixture_issuer(cls.root, cls.work / 'root.key')
        cls.issuer, cls.key, cls.password = cls.signer.__enter__()
        cls.addClassCleanup(cls.signer.__exit__, None, None, None)
        cls.values = {s: contract.issue(s, cls.root, cls.issuer, cls.key, cls.password)
                      for s in contract.SERVICES}

    def test_fixed_five_services_and_spring_configtree_bindings(self):
        self.assertEqual(5, len(contract.SERVICES))
        for service in contract.SERVICES:
            external = contract.external(service)
            self.assertEqual('platform-apps', external['metadata']['namespace'])
            self.assertEqual('hooshix-' + service, external['spec']['secretStoreRef']['name'])
            self.assertEqual('Opaque', external['spec']['target']['template']['type'])
            self.assertEqual({contract.entry(service)['key']},
                {v['remoteRef']['key'] for v in external['spec']['data']})
            self.assertEqual({*contract.FIELDS.values(), *contract.SETTINGS},
                {v['secretKey'] for v in external['spec']['data']})
            self.assertEqual('Retain', external['spec']['target']['deletionPolicy'])
            contract.validate(service, self.values[service], self.root, self.issuer)
        self.assertEqual(5, len({v[contract.FIELDS['tls.key']] for v in self.values.values()}))
        for value in ('../identity-service', 'compromised-password-service', 'kafka-node-1'):
            with self.assertRaises(shared.custody.BootstrapFailed):
                contract.entry(value)

    def test_wrong_service_key_root_and_insecure_config_are_rejected(self):
        first, second = contract.SERVICES[:2]
        invalid = [self.values[second]]
        for field, value in ((contract.FIELDS['tls.key'], self.values[second][contract.FIELDS['tls.key']]),
                             (contract.FIELDS['ca.crt'], 'foreign'),
                             ('spring.kafka.security.protocol', 'PLAINTEXT'),
                             ('spring.kafka.properties.ssl.endpoint.identification.algorithm', '')):
            invalid.append({**self.values[first], field: value})
        for values in invalid:
            with self.subTest(fields=tuple(values)), self.assertRaises(shared.custody.BootstrapFailed):
                contract.validate(first, values, self.root, self.issuer)
        before = hashlib.sha256(self.key.read_bytes()).hexdigest()
        with self.assertRaises(shared.custody.BootstrapFailed):
            contract.issue(first, self.root, self.issuer, self.key, b'wrong-passphrase')
        self.assertEqual(before, hashlib.sha256(self.key.read_bytes()).hexdigest())

    def test_late_foreign_history_and_orphan_conflicts_prevent_all_issuance(self):
        last = contract.SERVICES[-1]
        path = 'hooshix/data/' + contract.entry(last)['key']
        for case in ('foreign', 'history', 'orphan'):
            api = CredentialAPI()
            if case == 'foreign': api.values[path] = {'foreign': 'preserve'}
            if case == 'history': api.history.add(path.replace('/data/', '/metadata/'))
            issue = Mock(side_effect=lambda s: self.values[s])
            with self.subTest(case=case), self.assertRaises(shared.custody.BootstrapFailed):
                installer.credentials(api, 'root', self.root, self.issuer, issue,
                                      [last] if case == 'orphan' else [])
            issue.assert_not_called()
            self.assertEqual([], api.writes)

    def test_existing_credentials_are_reused_without_rotation(self):
        api = CredentialAPI()
        for service in contract.SERVICES:
            api.values['hooshix/data/' + contract.entry(service)['key']] = {
                **self.values[service], **installer.MARKERS}
        issue = Mock(side_effect=AssertionError('must not issue'))
        values, missing = installer.credentials(api, 'root', self.root, self.issuer, issue)
        self.assertEqual(self.values, values)
        self.assertEqual([], missing)
        issue.assert_not_called()
        self.assertEqual([], api.writes)

    def test_full_delivery_creates_only_five_externals_and_reentry_is_read_only(self):
        api, objects, secrets = CredentialAPI(), {}, {}
        original = api.call
        def call(path, *args, **kwargs):
            for service in contract.SERVICES:
                if path == 'auth/' + contract.auth.MOUNT + '/role/eso-' + service:
                    return {'data': contract.auth.role(service)}
                if path == 'sys/policies/acl/hooshix-read-' + service:
                    return {'data': {'policy': contract.auth.policy(service)}}
            return original(path, *args, **kwargs)
        api.call = Mock(side_effect=call)
        def kube(*args, body=None, **kwargs):
            verb = next(a for a in args if a in ('get', 'create', 'wait'))
            if verb == 'get':
                offset = args.index('get')
                kind, name = args[offset + 1:offset + 3]
                value = (secrets if kind == 'secret' else objects).get(name)
                return json.dumps(value).encode() if value else b''
            if verb == 'create':
                self.assertEqual('ExternalSecret', body['kind'])
                name = body['metadata']['name']
                objects[name] = copy.deepcopy(body)
                objects[name]['metadata']['uid'] = 'external-' + name
                objects[name]['status'] = {'conditions': [{'type': 'Ready', 'status': 'True'}]}
                service = next(s for s in contract.SERVICES if contract.entry(s)['name'] == name)
                secrets[name] = {'type': 'Opaque', 'metadata': {
                    'labels': {'app.kubernetes.io/managed-by': contract.MANAGER},
                    'ownerReferences': contract.owner(objects[name])},
                    'data': {k: base64.b64encode(v.encode()).decode() for k, v in self.values[service].items()}}
            return b''
        plan = {'schema_version': 1, 'installation_id': 'hooshix-production', 'kafka_client_tls': True,
            'database': 'Not run', 'public_ca_sha256': hashlib.sha256(self.root.encode()).hexdigest(),
            'application_ingress': 'CLOSED', 'public_access': 'CLOSED'}
        issue = Mock(side_effect=lambda s: self.values[s])
        with patch.object(installer, 'snapshot', return_value=['retained']) as snapshot, \
                patch.object(installer, 'verify_auth') as verify, \
                contextlib.redirect_stdout(io.StringIO()) as output:
            for _ in range(2):
                result = installer.execute(plan, kube=kube, api=api, root='root', ca=self.root,
                    issuer=self.issuer, audiences=['api'], token_request=Mock(), issue=issue)
        self.assertEqual(5, len(api.writes))
        self.assertEqual(5, issue.call_count)
        self.assertEqual(5, len(objects))
        self.assertEqual(4, snapshot.call_count)
        self.assertEqual(2, verify.call_count)
        self.assertEqual('', output.getvalue())
        self.assertEqual('CLOSED', result['application_ingress'])
        self.assertEqual('Not run', result['topics_acl'])

    def test_supervisor_has_only_fixed_client_tokenrequests_and_large_public_policy_read(self):
        with patch.object(bootstrap.pg, 'kube', return_value=b'jwt') as kube:
            self.assertEqual('jwt', bootstrap.token_request('platform-apps', 'eso-identity-service', ['api']))
            with self.assertRaises(shared.custody.BootstrapFailed):
                bootstrap.token_request('platform-data', 'eso-kafka-tls', ['api'])
            self.assertEqual(1, kube.call_count)
        with patch.object(bootstrap.material.platform, 'kube', return_value=b'{}') as large:
            bootstrap.kube('get', 'validatingpolicy', 'hooshix-platform-data-closed', '--ignore-not-found', '-o', 'json')
            self.assertEqual(512 * 1024, large.call_args.kwargs['output_bound'])
        payload = operator.bootstrap('/public', 'a' * 40, [], 'bootstrap_kafka_client_tls')
        self.assertIn('bootstrap_kafka_client_tls', payload)
        self.assertIn('O_NOFOLLOW', payload)

    def test_owner_source_loader_imports_exact_shipped_sources_in_isolation(self):
        # Match the real isolated root loader, without target execution or secrets.
        program = operator.bootstrap('/unused', 'a' * 40, [], 'bootstrap_kafka_client_tls')
        self.assertIn('32769', program)
        root = Path(__file__).resolve().parents[1]
        import subprocess
        source = "import sys,types,pathlib\n"
        source += 'root=pathlib.Path(' + repr(str(root)) + ')\n'
        source += 'for name in ' + repr(bundle.SOURCES) + ':\n'
        source += " content=(root/name).read_bytes(); assert 0<len(content)<=32768\n"
        source += " m=types.ModuleType(name[:-3]);m.__file__=str(root/name);sys.modules[name[:-3]]=m\n"
        source += " exec(compile(content,name,'exec'),m.__dict__)\n"
        result = subprocess.run([sys.executable, '-I', '-c', source], capture_output=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr.decode())

    def test_dual_purpose_client_certificate_cannot_be_delivered(self):
        unsafe = contract.EXTENSIONS.replace(b'clientAuth', b'clientAuth,serverAuth')
        with patch.object(contract, 'EXTENSIONS', unsafe), self.assertRaises(shared.custody.BootstrapFailed):
            contract.issue(contract.SERVICES[0], self.root, self.issuer, self.key, self.password)

    def test_native_bundle_proof_rejects_missing_security_and_open_access(self):
        proof = {'schema_version': 1, 'credential_count': 5, 'namespace': 'platform-apps',
            'tls_lifetime_days': 30, 'existing_state': 'Preserved', 'public_access': 'CLOSED',
            'application_ingress': 'CLOSED', 'topics_acl': 'Not run', 'automatic_renewal': 'Not run',
            'root_revocation': 'Not run', 'production_readiness': 'Not verified',
            **{key: 'Passed' for key in bundle.CHECKS}}
        with patch.object(bundle.previous, 'verify_proof'):
            bundle.verify_proof({'kafka_client_tls_delivery': proof})
            for key, value in [('credential_count', 6), ('topics_acl', 'Passed'),
                               ('application_ingress', 'OPEN'), *[(k, 'Not run') for k in bundle.CHECKS]]:
                with self.subTest(key=key), self.assertRaises(ValueError):
                    bundle.verify_proof({'kafka_client_tls_delivery': {**proof, key: value}})

    def test_public_plan_keeps_only_needed_contracts_within_existing_rpc_bound(self):
        from test_kafka_runtime import prior_boundary
        runtime = bundle.previous.renderer.render(prior_boundary())
        plan = bundle.plan(runtime)
        self.assertEqual({'objects', 'statefulset', 'boundary', 'source_policies'}, set(plan['kafka_runtime']))
        raw = json.dumps(plan, sort_keys=True).encode()
        packed = base64.b64encode(zlib.compress(raw)).decode()
        self.assertEqual(plan, bootstrap.pg.decode_plan(packed, hashlib.sha256(raw).hexdigest()))
        self.assertLessEqual(len(packed), 24000)

    def test_snapshot_preserves_closed_broker_and_rejects_contract_drift(self):
        from test_kafka_runtime import prior_boundary
        runtime = bundle.previous.renderer.render(prior_boundary())
        plan = bundle.plan(runtime)
        live = copy.deepcopy(runtime['statefulset'])
        live['metadata'].update(uid='stateful-uid', annotations={installer.runtime.HASH:
                                                               installer.runtime.digest(live['spec'])})
        live['status'] = {'readyReplicas': 1}
        boundary = installer.runtime.boundary_for_statefulset(runtime, 'stateful-uid')
        boundary['metadata']['uid'] = 'boundary-uid'
        boundary['status'] = {'conditionStatus': {'ready': True}}
        objects = {('statefulset', 'kafka'): live,
                   ('validatingpolicy', boundary['metadata']['name']): boundary,
                   ('namespace', 'platform-apps'): {'metadata': {'uid': 'apps-uid', 'labels': {
                       'istio.io/dataplane-mode': 'ambient', 'pod-security.kubernetes.io/enforce': 'restricted'}}},
                   ('deployment', 'hooshix-eso'): {'metadata': {'uid': 'eso-uid'},
                       'spec': {'template': {'spec': {'serviceAccountName': 'hooshix-eso'}}},
                       'status': {'availableReplicas': 1}}}
        for desired in [*runtime['objects'], *runtime['source_policies']]:
            if desired['kind'] == 'PersistentVolumeClaim': continue
            item = copy.deepcopy(desired)
            item['metadata']['uid'] = 'uid-' + item['metadata']['name']
            if 'spec' in item:
                item['metadata']['annotations'] = {installer.runtime.HASH: installer.runtime.digest(item['spec'])}
            if item['kind'] == 'ImageValidatingPolicy':
                item['status'] = {'conditionStatus': {'ready': True}}
            objects[item['kind'].lower(), item['metadata']['name']] = item
        for service in contract.SERVICES:
            item = contract.auth.store(service, ['api'])
            item['metadata']['uid'] = 'store-' + service
            item['status'] = {'conditions': [{'type': 'Ready', 'status': 'True'}]}
            objects['secretstore', item['metadata']['name']] = item
        def get(kube, kind, name, namespace=None):
            return objects.get((kind.lower(), name))
        with patch.object(installer.runtime, 'get', side_effect=get), \
                patch.object(installer.runtime.tls, 'preserved', return_value=[]), \
                patch.object(installer.runtime, 'binding', return_value='pv-uid'), \
                patch.object(installer.runtime, 'node_secret', return_value=[]), \
                patch.object(installer.runtime, 'isolation') as isolation, \
                patch.object(installer.runtime, 'pod_inventory') as inventory:
            first = installer.snapshot(Mock(), plan)
            self.assertEqual(first, installer.snapshot(Mock(), plan))
            isolation.assert_called()
            inventory.assert_called()
            # Preserve the preceding fixture's verified API-defaulted contract.
            predecessor = copy.deepcopy(runtime['previous_boundary'])
            predecessor['spec']['matchConstraints']['matchPolicy'] = 'Equivalent'
            verified_runtime = bundle.previous.renderer.render(predecessor)
            live_boundary = installer.runtime.boundary_for_statefulset(verified_runtime, 'stateful-uid')
            boundary['spec'] = live_boundary['spec']
            with self.assertRaisesRegex(shared.custody.BootstrapFailed, 'KAFKA_CLIENT_CLOSED_BOUNDARY_REQUIRED'):
                installer.snapshot(Mock(), plan)
            self.assertTrue(installer.snapshot(Mock(), bundle.plan(verified_runtime)))
            live['spec']['replicas'] = 0
            with self.assertRaises(ValueError):
                installer.snapshot(Mock(), plan)

    def test_fixture_failure_classification_never_prints_private_exception_text(self):
        from rehearse_kafka_client_tls import failure_details
        private = 'PRIVATE-KEY-OR-TOKEN-MUST-NOT-APPEAR'
        self.assertEqual({'code': 'OTHER'}, failure_details(ValueError(private)))
        code = 'KAFKA_CLIENT_CLOSED_BOUNDARY_REQUIRED'
        self.assertEqual({'code': code}, failure_details(shared.custody.BootstrapFailed(code)))
        self.assertNotIn(private, json.dumps(failure_details(KeyError(private))))

    def test_foreign_external_or_target_stops_before_auth_issuance_and_cas(self):
        plan = {'schema_version': 1, 'installation_id': 'hooshix-production', 'kafka_client_tls': True,
            'database': 'Not run', 'public_ca_sha256': hashlib.sha256(self.root.encode()).hexdigest(),
            'application_ingress': 'CLOSED', 'public_access': 'CLOSED'}
        foreign = {'metadata': {'labels': {'app.kubernetes.io/managed-by': 'foreign'}}}
        for existing in ([foreign], [None, foreign]):
            api, issue = Mock(), Mock()
            with self.subTest(existing_count=len(existing)), \
                    patch.object(installer, 'snapshot', return_value=[]), \
                    patch.object(installer.runtime, 'get', side_effect=existing), \
                    self.assertRaises(shared.custody.BootstrapFailed):
                installer.execute(plan, kube=Mock(), api=api, root='root', ca=self.root,
                    issuer=self.issuer, audiences=['api'], token_request=Mock(), issue=issue)
            api.call.assert_not_called()
            issue.assert_not_called()

    def test_native_client_probe_requires_exact_denial_and_never_passes_keys_in_argv(self):
        from rehearse_kafka_client_tls import broker_denial
        values = self.values[contract.SERVICES[0]]
        kube = Mock(return_value=b'CLIENT_TLS_AND_ADMIN_DENIAL=Passed\n')
        broker_denial(kube, values)
        self.assertNotIn(values[contract.FIELDS['tls.key']], ' '.join(kube.call_args.args))
        self.assertIn(values[contract.FIELDS['tls.key']].encode(), kube.call_args.kwargs['data'])
        self.assertIn('/usr/bin/timeout -s KILL 20', kube.call_args.args[-1])
        self.assertIn('ClusterAuthorizationException', kube.call_args.args[-1])
        self.assertIn('rmdir', kube.call_args.args[-1])
        with self.assertRaises(ValueError):
            broker_denial(Mock(return_value=b''), values)


if __name__ == '__main__':
    unittest.main()
