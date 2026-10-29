"""Global preflight, foreign-state preservation, interrupted acknowledgement and exact owner reentry."""
import copy
import contextlib
import hashlib
import io
import json
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import kafka_quota_commissioning as contract
import kafka_quota_profile as profile
import install_kafka_quotas as installer
import build_kafka_quota_bundle as bundle
import bootstrap_kafka_quota_operator as operator


class Broker:
    def __init__(self):
        self.users, self.foreign, self.writes = {}, {}, []

    def command(self, *args):
        if '--describe' in args:
            types = tuple(args[i + 1] for i, x in enumerate(args) if x == '--entity-type')
            if types != ('users',):
                return self.foreign.get(types, '')
            return ''.join("Quota configs for user-principal '" + user + "' are "
                + ', '.join(k + '=' + str(v) + '.0' for k, v in rates.items()) + '\n'
                for user, rates in self.users.items())
        self.writes.append(args)
        user = args[args.index('--entity-name') + 1]
        self.users[user] = {k: int(v) for k, v in
            (entry.split('=') for entry in args[args.index('--add-config') + 1].split(','))}
        return 'Completed updating config for user ' + user + '.\n'


class KafkaQuotaCommissioningTest(unittest.TestCase):
    def test_bundle_retained_mode_is_explicit_and_modifies_only_expected_boundary(self):
        revision = 'a' * 40
        boundary = {'metadata': {'name': 'hooshix-platform-data-closed'}, 'spec': {
            'validations': [{'expression': 'image == ' + json.dumps(contract.CURRENT_ESO)}]}}
        runtime = {'public_ca_sha256': 'b' * 64, 'statefulset': {'unchanged': True},
                   'objects': [], 'source_policies': [], 'boundary': boundary}
        staging = bundle.previous.previous.staging
        def git(*args):
            if args[0] == 'status': return ''
            if args[0] == 'ls-remote': return revision + '\trefs/heads/main'
            return revision
        with tempfile.TemporaryDirectory() as temp, patch.object(staging, 'git', side_effect=git), \
                patch.object(staging, 'staged_receipt', return_value={'observed_at': '2026-10-29T00:00:00+00:00'}), \
                patch.object(bundle, 'verify_proof'), \
                patch.object(bundle.previous.previous, 'data_boundary', return_value={}), \
                patch.object(bundle.previous.previous.renderer, 'render', return_value=runtime):
            for mode in (False, True):
                out = Path(temp) / str(mode)
                bundle.build(out, '1', Path('/unused-chart'), 'unused-helm', True, mode)
                plan = json.loads((out / 'plan.json').read_bytes())
                self.assertIs(plan['retained_eso_admission'], mode)
                self.assertEqual(contract.retained_boundary(boundary) if mode else boundary,
                                 plan['kafka_runtime']['boundary'])
                self.assertEqual(runtime['statefulset'], plan['kafka_runtime']['statefulset'])
                self.assertEqual([], plan['kafka_runtime']['source_policies'])
                self.assertEqual(boundary, runtime['boundary'])

    def test_retained_binding_changes_only_one_fixed_image_and_keeps_original_immutable(self):
        boundary = {'metadata': {'name': 'hooshix-platform-data-closed'}, 'spec': {
            'failurePolicy': 'Fail', 'validationActions': ['Deny'],
            'validations': [{'expression': 'object.image == ' + json.dumps(contract.CURRENT_ESO)},
                            {'expression': 'protected.kafka && protected.redis'}]}}
        saved = copy.deepcopy(boundary)
        changed = contract.retained_boundary(boundary)
        self.assertEqual(saved, boundary)
        expected = copy.deepcopy(boundary)
        expected['spec']['validations'][0]['expression'] = 'object.image == ' + json.dumps(contract.RETAINED_ESO)
        self.assertEqual(expected, changed)
        for bad in (changed, {**boundary, 'metadata': {'name': 'foreign'}},
                    {**boundary, 'spec': {'validations': [{'expression': 'unknown'}]}},
                    {**boundary, 'spec': {'validations': boundary['spec']['validations'] * 2}}):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                contract.retained_boundary(bad)

    def test_retained_mode_requires_exact_live_predecessor_image_identity_and_ready_state(self):
        deployment = {'metadata': {'uid': 'retained-uid'}, 'status': {'availableReplicas': 1},
            'spec': {'template': {'spec': {'serviceAccountName': 'hooshix-postgresql-eso',
                'containers': [{'name': 'external-secrets', 'image': contract.RETAINED_ESO}]}}}}
        plan = {'retained_eso_admission': True}
        with patch.object(installer.runtime, 'get', return_value=deployment) as get:
            self.assertEqual(('retained-uid', deployment['spec']), installer.retained_eso(None, plan))
            self.assertIsNone(installer.retained_eso(None, {}))
            self.assertEqual(1, get.call_count)
            for case in ('current-image', 'foreign-account', 'not-ready', 'multiple-containers', 'non-bool'):
                bad = copy.deepcopy(deployment)
                pod = bad['spec']['template']['spec']
                if case == 'current-image': pod['containers'][0]['image'] = contract.CURRENT_ESO
                if case == 'foreign-account': pod['serviceAccountName'] = 'default'
                if case == 'not-ready': bad['status']['availableReplicas'] = 0
                if case == 'multiple-containers': pod['containers'] *= 2
                with self.subTest(case=case), patch.object(installer.runtime, 'get', return_value=bad), \
                        self.assertRaises(ValueError):
                    installer.retained_eso(None, {'retained_eso_admission': 'true'} if case == 'non-bool' else plan)

    def test_owner_download_is_private_and_wrong_profile_receipt_is_rejected(self):
        revision = 'a' * 40
        plan = {'kafka_quotas': True, 'application_ingress': 'CLOSED', 'public_access': 'CLOSED',
                'kafka_quota_profile': json.loads(profile.PATH.read_bytes())}
        payload = json.dumps(plan).encode()
        proof = {'kafka_quotas': 'Passed', 'plan_sha256': hashlib.sha256(payload).hexdigest(),
            'credential_count': 5, 'producer_byte_rate': 65536, 'consumer_byte_rate': 262144,
            'source_revision': revision, 'capacity': 'Not verified', 'production_readiness': 'Not verified',
            'application_ingress': 'CLOSED', 'public_access': 'CLOSED', 'existing_state': 'Preserved',
            'contract_sha256': hashlib.sha256(json.dumps(profile.load(), sort_keys=True).encode()).hexdigest()}
        for valid in (True, False):
            receipt = {**proof, 'producer_byte_rate': 65536 if valid else 1}
            downloaded = []
            def command(argv, **kwargs):
                if argv[0] == 'git':
                    return revision.encode() if 'rev-parse' in argv else b''
                if argv[0] == 'wslpath':
                    return argv[-1].encode()
                if argv[0] == operator.operator.SCP and argv[-2].startswith('hooshix-server:'):
                    path = Path(argv[-1])
                    self.assertTrue(path.is_file())
                    self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
                    path.write_bytes(json.dumps(receipt).encode())
                    downloaded.append(path)
                return b''
            with self.subTest(valid=valid), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                (root / 'bundle.json').write_text(json.dumps({'files': {}}))
                local_os = SimpleNamespace(umask=lambda _: None, getuid=lambda: 1000,
                                          uname=lambda: SimpleNamespace(release='microsoft'))
                with patch.object(sys, 'argv', ['operator', '--bundle', str(root), '--rescue-and-second-session-ready']), \
                        patch.multiple(operator.operator, command=command, BASE=root / 'custody',
                            custody_directory=lambda _: None, sys_tty=lambda: True,
                            stage_sources=lambda _: ('/var/tmp/synthetic-quota-owner', [])), \
                        patch.object(operator.bundle, 'validate_bundle', return_value=payload), \
                        patch.object(operator, 'os', local_os), patch.object(operator.resource, 'setrlimit'), \
                        patch('builtins.input', return_value='READY'), \
                        patch.object(operator.subprocess, 'run', return_value=SimpleNamespace(returncode=0)), \
                        contextlib.redirect_stdout(io.StringIO()) as output:
                    self.assertEqual(0 if valid else 1, operator.main())
                self.assertEqual(1, len(downloaded))
                self.assertIn('KAFKA_QUOTAS=' + ('Passed' if valid else 'Failed'), output.getvalue())

    def test_exact_apply_and_read_only_reentry(self):
        broker, desired = Broker(), profile.load()
        run = contract.reconcile(broker.command, desired, owned=False)
        self.assertEqual(0, next(run))
        self.assertEqual([], broker.writes)
        self.assertEqual('Passed', next(run)['kafka_quotas'])
        self.assertEqual(desired, broker.users)
        self.assertEqual(5, len(broker.writes))
        broker.writes.clear()
        run = contract.reconcile(broker.command, desired, owned=True)
        self.assertEqual(5, next(run))
        self.assertEqual('Passed', next(run)['kafka_quotas'])
        self.assertEqual([], broker.writes)

    def test_late_foreign_default_client_ip_partial_or_unowned_state_stops_before_writes(self):
        desired = profile.load()
        user = list(desired)[-1]
        for case in ('foreign-user', 'default', 'client', 'combined', 'ip', 'partial', 'drift', 'unowned'):
            broker = Broker()
            broker.users[list(desired)[0]] = desired[list(desired)[0]]
            if case == 'foreign-user':
                broker.users['foreign'] = desired[user]
            elif case == 'default':
                broker.users['<default>'] = desired[user]
            elif case in ('partial', 'drift'):
                broker.users[user] = {'producer_byte_rate': 1 if case == 'drift' else 65536}
            elif case != 'unowned':
                kind = {'client': ('clients',), 'combined': ('users', 'clients'), 'ip': ('ips',)}[case]
                broker.foreign[kind] = 'configured foreign quota'
            with self.subTest(case=case), self.assertRaises(ValueError):
                next(contract.reconcile(broker.command, desired, owned=case != 'unowned'))
            self.assertEqual([], broker.writes)

    def test_interrupted_acknowledgement_resumes_only_missing_principals(self):
        broker, desired = Broker(), profile.load()
        def interrupted(*args):
            result = broker.command(*args)
            if '--alter' in args:
                raise TimeoutError('synthetic lost acknowledgement')
            return result
        run = contract.reconcile(interrupted, desired, owned=False)
        next(run)
        with self.assertRaises(TimeoutError):
            next(run)
        self.assertEqual(1, len(broker.users))
        broker.writes.clear()
        run = contract.reconcile(broker.command, desired, owned=True)
        next(run)
        self.assertEqual('Passed', next(run)['kafka_quotas'])
        self.assertEqual(4, len(broker.writes))

    def test_unknown_duplicate_nan_truncated_or_extra_quota_output_rejected(self):
        user = next(iter(profile.load()))
        prefix = "Quota configs for user-principal '" + user + "' are "
        for row in ('unknown text', prefix + 'producer_byte_rate=NaN',
                    prefix + 'producer_byte_rate=65536.0, producer_byte_rate=65536.0',
                    prefix + 'producer_byte_rate=65536.0',
                    prefix + 'producer_byte_rate=65536.0, consumer_byte_rate=262144.0, request_percentage=1.0'):
            with self.subTest(row=row), self.assertRaises(ValueError):
                contract.inventory(lambda *args: row if args[-1] == 'users' else '', profile.load())

    def test_concurrent_inventory_change_is_detected_before_first_alter(self):
        broker, desired = Broker(), profile.load()
        run = contract.reconcile(broker.command, desired, owned=False)
        next(run)
        user = next(iter(desired))
        broker.users[user] = desired[user]
        with self.assertRaises(ValueError):
            next(run)
        self.assertEqual([], broker.writes)

    def test_installer_journals_after_preflight_and_preserves_all_other_state(self):
        broker, desired, objects, writes = Broker(), profile.load(), {}, []
        plan = {'schema_version': 1, 'installation_id': 'hooshix-production', 'kafka_quotas': True,
            'public_access': 'CLOSED', 'application_ingress': 'CLOSED', 'public_ca_sha256': 'a' * 64,
            'repository_source_revision': 'a' * 40,
            'kafka_quota_profile': json.loads(profile.PATH.read_bytes())}
        def get(kube, kind, name, ns):
            return objects.get(name) if kind == 'configmap' else {'metadata': {'uid': kind + '-uid'}}
        def kube(*args, body=None, **kw):
            self.assertEqual(('create', '-f', '-'), args)
            self.assertEqual({}, broker.users)
            objects[body['metadata']['name']] = copy.deepcopy(body)
            writes.append(copy.deepcopy(body))
        with patch.object(installer.topics.delivery, 'snapshot', return_value=['stable']), \
                patch.object(installer.topics, 'credential_snapshot', return_value=['same-keys']), \
                patch.object(installer.topics, 'broker_epoch', return_value=['uid', 0]), \
                patch.object(installer, 'topic_state', return_value=['topic-journal', 'stable']), \
                patch.object(installer.runtime, 'get', side_effect=get), \
                patch.object(installer.runtime, 'storage_identity', return_value=('cluster', 'hash')), \
                patch.object(installer, 'command', side_effect=lambda _, *args: broker.command(*args)):
            first = installer.execute(plan, kube=kube, guard=lambda **_: ['active'])
            self.assertEqual(desired, broker.users)
            broker.writes.clear()
            self.assertEqual(first, installer.execute(plan, kube=kube, guard=lambda **_: ['active']))
            self.assertEqual([], broker.writes)
            self.assertEqual(1, len(writes))
            self.assertTrue(writes[0]['immutable'])
            objects[installer.JOURNAL]['data']['pvc_uid'] = 'foreign'
            with self.assertRaises(ValueError):
                installer.execute(plan, kube=kube, guard=lambda **_: ['active'])
            self.assertEqual(1, len(writes))

    def test_exact_server_loader_has_all_dependencies_and_rejects_changed_source(self):
        root = Path(__file__).resolve().parents[1]
        sources = [(name, hashlib.sha256((root / name).read_bytes()).hexdigest()) for name in bundle.SOURCES]
        source = operator.loader(str(root), sources, 'a' * 64)
        source = source.replace("raise SystemExit(sys.modules['install_kafka_quotas'].main())",
                                "assert callable(sys.modules['install_kafka_quotas'].main)")
        result = subprocess.run([sys.executable, '-I', '-c', source], capture_output=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr.decode())
        sources[-1] = (sources[-1][0], '0' * 64)
        bad = subprocess.run([sys.executable, '-I', '-c', operator.loader(str(root), sources, 'a' * 64)],
                             capture_output=True, timeout=20)
        self.assertNotEqual(0, bad.returncode)

    def test_bundle_gate_requires_actual_partial_recovery_and_closed_state(self):
        proof = {'schema_version': 1, 'kafka_quotas': 'Passed', 'credential_count': 5,
            'producer_byte_rate': 65536, 'consumer_byte_rate': 262144,
            'idempotent_reconciliation': 'Passed', 'partial_recovery': 'Passed',
            'retained_current_digest_denied': 'Passed',
            'existing_state': 'Preserved', 'application_ingress': 'CLOSED', 'public_access': 'CLOSED',
            'capacity': 'Not verified', 'production_readiness': 'Not verified'}
        with patch.object(bundle.topics, 'verify_proof'):
            bundle.verify_proof({'kafka_client_tls_delivery': {'kafka_quotas': proof}})
            for key in proof:
                with self.subTest(key=key), self.assertRaises(ValueError):
                    bundle.verify_proof({'kafka_client_tls_delivery': {'kafka_quotas': {**proof, key: 'wrong'}}})


if __name__ == '__main__':
    unittest.main()
