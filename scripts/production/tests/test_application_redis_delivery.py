"""Application Redis projections keep service identity and TLS boundaries explicit."""
import base64
import copy
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import application_redis_delivery as contract
import install_application_redis_delivery as installer
import build_application_redis_bundle as bundle
import activate_openbao_operator as transport
from test_application_postgresql_delivery import Fixture as PostgreSQLFixture


class Fixture(PostgreSQLFixture):
    def __init__(self):
        super().__init__()
        for index, (name, item) in enumerate(contract.source.ENTRIES.items()):
            value = {'username': item['username'], 'password': str(index + 20).zfill(64),
                     'managed_by': contract.source.MANAGER, 'installation_id': 'hooshix-production'}
            self.values['hooshix/data/' + item['key']] = value
            secret = self.put('Secret', name, contract.source.NAMESPACE, type='kubernetes.io/basic-auth',
                data={field: base64.b64encode(value[field].encode()).decode() for field in ('username', 'password')},
                labels={'app.kubernetes.io/managed-by': contract.source.MANAGER})
            external = contract.source.external(name)
            external['metadata']['uid'] = 'source-' + name
            external['status'] = {'conditions': [{'type': 'Ready', 'status': 'True'}]}
            self.objects[('externalsecret', contract.source.NAMESPACE, name)] = external
            secret['metadata']['ownerReferences'] = installer.pg.owner(external)
        for service, _, _ in contract.ENTRIES.values():
            store = contract.auth.store(service, ['api'])
            store['metadata']['labels']['app.kubernetes.io/managed-by'] = installer.ESO_MANAGER
            store['metadata']['uid'] = 'store-' + service
            store['status'] = {'conditions': [{'type': 'Ready', 'status': 'True'}]}
            self.objects[('secretstore', contract.NAMESPACE, 'hooshix-' + service)] = store
            self.auth['sys/policies/acl/hooshix-read-' + service] = {'policy': contract.auth.policy(service)}
            self.auth['auth/' + contract.auth.MOUNT + '/role/eso-' + service] = contract.auth.role(service)

    def kube(self, *args, body=None, **options):
        if args[0] == 'create':
            self.creates.append(copy.deepcopy(body))
            value = copy.deepcopy(body)
            name = value['metadata']['name']
            value['metadata']['uid'] = 'external-' + name
            value['status'] = {'conditions': [{'type': 'Ready', 'status': 'True'}]}
            self.objects[('externalsecret', contract.NAMESPACE, name)] = value
            _, source_name, prop = contract.ENTRIES[name]
            item = contract.source.entry(source_name)
            source = self.values['hooshix/data/' + item['key']]
            uri = 'rediss://' + source['username'] + ':' + source['password'] + '@' + contract.HOST
            secret = self.put('Secret', name, contract.NAMESPACE, type='Opaque',
                data={prop: base64.b64encode(uri.encode()).decode()},
                labels={'app.kubernetes.io/managed-by': contract.MANAGER})
            secret['metadata']['ownerReferences'] = installer.pg.owner(value)
            return b''
        return super().kube(*args, body=body, **options)

    def execute(self):
        plan = {'schema_version': 1, 'installation_id': 'hooshix-production',
            'application_redis_delivery': True, 'application_ingress': 'CLOSED', 'public_access': 'CLOSED',
            'public_ca_sha256': hashlib.sha256(self.ca.encode()).hexdigest(), 'existing_eso_image': self.image}
        return installer.execute(plan, kube=self.kube, api=self, root='test-root', ca=self.ca,
                                 audiences=['api'], token_request=None)


class ApplicationRedisDeliveryTest(unittest.TestCase):
    def test_each_existing_service_store_reads_only_its_own_credentials(self):
        expected = {
            'authorization-quota-redis': ('authorization-service', 'authorization', 'AUTHORIZATION_QUOTA_REDIS_URI'),
            'identity-quota-redis': ('identity-service', 'identity', 'quota_redis_uri'),
            'web-bff-redis': ('web-bff', 'webbff', 'WEB_BFF_REDIS_URI'),
        }
        for name, (service, username, prop) in expected.items():
            with self.subTest(name=name):
                obj = contract.external(name)
                spec = obj['spec']
                self.assertEqual('platform-apps', obj['metadata']['namespace'])
                self.assertEqual({'kind': 'SecretStore', 'name': 'hooshix-' + service}, spec['secretStoreRef'])
                self.assertEqual('Owner', spec['target']['creationPolicy'])
                self.assertEqual('Retain', spec['target']['deletionPolicy'])
                self.assertEqual('v2', spec['target']['template']['engineVersion'])
                self.assertEqual({prop}, set(spec['target']['template']['data']))
                self.assertEqual('{{ printf "rediss://%s:%s@security-redis.platform-data.svc.cluster.local:6379" .username .password }}',
                                 spec['target']['template']['data'][prop])
                self.assertEqual({'username', 'password'}, {item['secretKey'] for item in spec['data']})
                self.assertEqual({'production/' + service + '/security-redis'},
                                 {item['remoteRef']['key'] for item in spec['data']})
                self.assertEqual(username, contract.source.entry(contract.ENTRIES[name][1])['username'])
                self.assertNotIn('redis://', json.dumps(obj).replace('rediss://', ''))

    def test_unknown_identity_rejected(self):
        with self.assertRaises(ValueError):
            contract.external('security-redis-acl')

    def test_existing_credentials_project_without_provider_writes_and_reentry_is_read_only(self):
        fixture = Fixture()
        original = copy.deepcopy(fixture.values)
        self.assertEqual('Passed', fixture.execute()['application_redis_delivery'])
        self.assertEqual(3, len(fixture.creates))
        self.assertEqual([], fixture.writes)
        self.assertEqual(original, fixture.values)
        fixture.execute()
        self.assertEqual(3, len(fixture.creates))
        self.assertEqual([], fixture.writes)

    def test_foreign_secret_or_drift_stops_before_any_create(self):
        for mode in ('foreign-secret', 'store-drift', 'store-owner', 'source-drift', 'auth-drift'):
            fixture = Fixture()
            if mode == 'foreign-secret':
                fixture.put('Secret', 'web-bff-redis', contract.NAMESPACE)
            elif mode == 'store-drift':
                fixture.objects[('secretstore', contract.NAMESPACE, 'hooshix-identity-service')]['spec']['provider']['vault']['server'] = 'https://wrong.invalid'
            elif mode == 'store-owner':
                fixture.objects[('secretstore', contract.NAMESPACE, 'hooshix-identity-service')]['metadata']['labels']['app.kubernetes.io/managed-by'] = contract.auth.LABELS['app.kubernetes.io/managed-by']
            elif mode == 'source-drift':
                fixture.objects[('secret', contract.source.NAMESPACE, 'security-redis-identity')]['data']['username'] = base64.b64encode(b'wrong').decode()
            else:
                fixture.auth['sys/policies/acl/hooshix-read-identity-service']['policy'] = 'path "*" { capabilities = ["read"] }'
            with self.subTest(mode=mode), self.assertRaises((ValueError, KeyError)):
                fixture.execute()
            self.assertEqual([], fixture.creates)
            self.assertEqual([], fixture.writes)

    def test_partial_creation_resumes_without_replacing_owned_secret(self):
        fixture = Fixture()
        kube = fixture.kube
        def interrupted(*args, **kwargs):
            if args[0] == 'create' and len(fixture.creates) == 1:
                raise RuntimeError('injected interruption')
            return kube(*args, **kwargs)
        fixture.kube = interrupted
        with self.assertRaises(RuntimeError): fixture.execute()
        first = fixture.creates[0]['metadata']['name']
        uid = fixture.objects[('secret', contract.NAMESPACE, first)]['metadata']['uid']
        fixture.kube = kube
        self.assertEqual('Passed', fixture.execute()['application_redis_delivery'])
        self.assertEqual(3, len(fixture.creates))
        self.assertEqual(uid, fixture.objects[('secret', contract.NAMESPACE, first)]['metadata']['uid'])
        self.assertEqual([], fixture.writes)

    def test_existing_external_secret_cannot_add_sources_or_template_data(self):
        for field in ('dataFrom', 'templateFrom', 'template-data', 'version'):
            fixture = Fixture()
            obj = contract.external('identity-quota-redis')
            obj['metadata']['uid'] = 'foreign-drift'
            if field == 'dataFrom': obj['spec']['dataFrom'] = [{'extract': {'key': 'foreign'}}]
            elif field == 'templateFrom': obj['spec']['target']['template']['templateFrom'] = [{'configMap': {'name': 'foreign'}}]
            elif field == 'template-data': obj['spec']['target']['template']['data']['foreign'] = '{{ .password }}'
            else: obj['spec']['data'][0]['remoteRef']['version'] = '1'
            fixture.objects[('externalsecret', contract.NAMESPACE, 'identity-quota-redis')] = obj
            with self.subTest(field=field), self.assertRaises(ValueError): fixture.execute()
            self.assertEqual([], fixture.creates)

    def test_native_proof_requires_closed_state_and_exact_existing_image(self):
        proof = Fixture().execute()
        proof.update(scoped_authentication='Passed', idempotent_reconciliation='Passed',
                     container_log_privacy='Passed', existing_eso_image=bundle.pg.published_pin()['image'])
        bundle.verify_proof({'application_redis_delivery': proof})
        for key, value in [('application_ingress', 'OPEN'), ('kv_writes', 'Passed'),
                           ('credential_count', 4), ('scoped_authentication', 'Not run')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                bundle.verify_proof({'application_redis_delivery': {**proof, key: value}})

    def test_hash_bound_source_graph_loads_without_repository_import_path(self):
        source = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            hashes = []
            for name in bundle.SOURCES:
                content = (source / name).read_bytes()
                self.assertLessEqual(len(content), 32768)
                (folder / name).write_bytes(content)
                hashes.append((name, hashlib.sha256(content).hexdigest()))
            script = transport.bootstrap(temporary, 'a' * 40, hashes, 'bootstrap_application_redis_delivery')
            script = script[:script.rindex('raise SystemExit(')] + (
                'assert len(sys.modules["application_redis_delivery"].ENTRIES)==3\n'
                'print("SOURCE_GRAPH=Passed")\n')
            result = subprocess.run([sys.executable, '-I', '-c', script], capture_output=True,
                                    text=True, timeout=15, check=False)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('SOURCE_GRAPH=Passed', result.stdout.strip())
