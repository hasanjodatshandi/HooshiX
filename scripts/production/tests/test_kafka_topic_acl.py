"""Application contract parity, preflight conflicts and non-destructive reentry."""
import copy
import contextlib
import hashlib
import io
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import kafka_topic_acl as contract
import install_kafka_topic_acl as installer
import bootstrap_kafka_topic_acl_operator as operator
import build_kafka_topic_acl_bundle as bundle


def acl_text(entries):
    lines = []
    for kind, name, pattern, principal, host, operation, permission in sorted(entries):
        lines += [f'Current ACLs for resource `ResourcePattern(resourceType={kind}, name={name}, patternType={pattern})`:',
            f' (principal={principal}, host={host}, operation={operation}, permissionType={permission})']
    return '\n'.join(lines)


class Broker:
    def __init__(self):
        self.topics, self.acls, self.writes = {}, set(), []

    def command(self, tool, *args):
        def option(name):
            return args[args.index(name) + 1]
        if args == ('--list',):
            return '\n'.join(self.topics) + '\n' if tool == 'topics' else acl_text(self.acls)
        if tool == 'topics' and '--describe' in args:
            name = option('--topic')
            configs = ','.join(k + '=' + v for k, v in self.topics[name].items())
            return f'Topic: {name}\tPartitionCount: 1\tReplicationFactor: 1\tConfigs: {configs}\n' \
                + f'Topic: {name}\tPartition: 0\tLeader: 1\tReplicas: 1\tIsr: 1\tElr: N/A\n'
        self.writes.append((tool, args))
        if tool == 'topics':
            self.topics[option('--topic')] = dict(args[i+1].split('=', 1)
                for i, v in enumerate(args) if v == '--config')
        else:
            kind = 'TOPIC' if '--topic' in args else 'GROUP'
            for i, arg in enumerate(args):
                if arg == '--operation':
                    self.acls.add((kind, option('--' + kind.lower()), 'LITERAL',
                        option('--allow-principal'), '*', args[i+1].upper(), 'ALLOW'))
        return ''


class KafkaTopicACLTest(unittest.TestCase):
    def test_all_groups_and_topic_defaults_match_current_service_contracts(self):
        root = Path(__file__).resolve().parents[3]
        groups = []
        for service in contract.clients.SERVICES:
            config = (root / 'services' / service / 'src/main/resources/application.yaml').read_text()
            for name in contract.groups(service):
                self.assertIn(':' + name + '}', config)
            self.assertIn(contract.COMMAND, config)
            self.assertIn(contract.RECEIPT, config)
            groups += contract.groups(service)
        self.assertEqual(7, len(set(groups)))
        self.assertEqual(6, len(contract.topics()))
        for name, spec in contract.topics().items():
            self.assertEqual(1, spec['partitions'])
            self.assertEqual(1, spec['replication_factor'])
            self.assertEqual(str((14 if name.endswith('.DLT') else 35) * 86400000), spec['config']['retention.ms'])

    def test_permissions_have_no_wildcard_resource_prefix_admin_create_or_delete(self):
        acls = contract.permissions()
        self.assertEqual(acls, contract.parse_acls(acl_text(acls)))
        for kind, name, pattern, principal, host, op, permission in acls:
            self.assertIn(kind, ('TOPIC', 'GROUP'))
            self.assertNotIn('*', name)
            self.assertEqual('LITERAL', pattern)
            self.assertIn(op, ('READ', 'WRITE', 'DESCRIBE'))
            self.assertEqual('ALLOW', permission)
            self.assertTrue(principal.startswith('User:CN='))
        command_writers = {a[3] for a in acls if a[1] == contract.COMMAND and a[5] == 'WRITE'}
        self.assertEqual({'User:' + contract.clients.entry('identity-service')['principal']}, command_writers)

    def test_create_readback_and_reentry_do_not_mutate_existing_state(self):
        broker = Broker()
        run = contract.reconcile(broker.command, owned=False)
        self.assertEqual({'topics': 0, 'acl_entries': 0}, next(run))
        self.assertEqual([], broker.writes)
        first = next(run)
        state = copy.deepcopy((broker.topics, broker.acls))
        broker.writes.clear()
        second = contract.reconcile(broker.command, owned=True)
        next(second)
        self.assertEqual(first, next(second))
        self.assertEqual(state, (broker.topics, broker.acls))
        self.assertEqual([], broker.writes)

    def test_foreign_late_config_acl_or_unowned_match_stops_before_any_write(self):
        for case in ('foreign-topic', 'late-drift', 'wildcard-acl', 'unowned-match'):
            broker = Broker()
            if case == 'foreign-topic':
                broker.topics['foreign'] = {}
            elif case in ('late-drift', 'unowned-match'):
                name = contract.TOPICS[-1]
                broker.topics[name] = dict(contract.topics()[name]['config'])
                if case == 'late-drift':
                    broker.topics[name]['retention.ms'] = '1'
            else:
                broker.acls.add(('TOPIC', '*', 'LITERAL', 'User:CN=foreign', '*', 'ALL', 'ALLOW'))
            with self.subTest(case=case), self.assertRaises(ValueError):
                next(contract.reconcile(broker.command, owned=case != 'unowned-match'))
            self.assertEqual([], broker.writes)

    def test_partial_create_failure_resumes_without_recreating_the_first_topic(self):
        broker = Broker()
        def interrupted(tool, *args):
            if '--create' in args and broker.topics:
                raise TimeoutError('synthetic interrupted acknowledgement')
            return broker.command(tool, *args)
        run = contract.reconcile(interrupted, owned=False)
        next(run)
        with self.assertRaises(TimeoutError):
            next(run)
        preserved = copy.deepcopy(broker.topics)
        self.assertEqual(1, len(preserved))
        broker.writes.clear()
        run = contract.reconcile(broker.command, owned=True)
        next(run)
        self.assertEqual('Passed', next(run)['kafka_topic_acl'])
        created = [args[args.index('--topic')+1] for tool, args in broker.writes if tool == 'topics']
        self.assertTrue(set(preserved).isdisjoint(created))
        for name, value in preserved.items():
            self.assertEqual(value, broker.topics[name])

    def test_unknown_or_incomplete_acl_output_fails_closed(self):
        for output in ('unexpected response', '(principal=User:x, host=*, operation=READ, permissionType=ALLOW)',
                       'Current ACLs for resource `ResourcePattern(resourceType=TOPIC, name=unparsed, patternType=LITERAL)`:'):
            with self.assertRaises(ValueError):
                contract.parse_acls(output)

    def test_supervised_installer_preflights_then_journals_and_reenters_without_kube_writes(self):
        broker, objects, writes = Broker(), {}, []
        def get(kube, kind, name, namespace):
            return objects.get(name) if kind == 'configmap' else {'metadata': {'uid': kind + '-uid'}}
        def kube(*args, body=None):
            self.assertEqual(('create', '-f', '-'), args)
            writes.append(copy.deepcopy(body))
            objects[body['metadata']['name']] = copy.deepcopy(body)
        plan = {'schema_version': 1, 'installation_id': 'hooshix-production', 'kafka_topic_acl': True,
                'repository_source_revision': 'a' * 40,
                'public_access': 'CLOSED', 'application_ingress': 'CLOSED', 'public_ca_sha256': 'a' * 64}
        with patch.object(installer.delivery, 'snapshot', return_value=['unchanged']), \
                patch.object(installer, 'credential_snapshot', return_value=['same-client-keys']), \
                patch.object(installer.runtime, 'get', side_effect=get), \
                patch.object(installer.runtime, 'storage_identity', return_value=('cluster', 'identity')), \
                patch.object(installer, 'command', side_effect=lambda _, *args: broker.command(*args)):
            first = installer.execute(plan, kube=kube, guard=lambda **_: ['active'])
            mutations = len(broker.writes)
            self.assertEqual(first, installer.execute(plan, kube=kube, guard=lambda **_: ['active']))
            self.assertEqual(mutations, len(broker.writes))
            self.assertEqual(1, len(writes))
            self.assertTrue(writes[0]['immutable'])
            objects[installer.JOURNAL]['data']['pvc_uid'] = 'foreign'
            with self.assertRaises(ValueError):
                installer.execute(plan, kube=kube, guard=lambda **_: ['active'])
            self.assertEqual(1, len(writes))

    def test_public_loader_imports_exact_shipped_source_set_without_server_side_repo_imports(self):
        root = Path(__file__).resolve().parents[1]
        sources = [(name, hashlib.sha256((root / name).read_bytes()).hexdigest()) for name in bundle.SOURCES]
        source = operator.loader(str(root), sources, 'a' * 64)
        source = source.replace("raise SystemExit(sys.modules['install_kafka_topic_acl'].main())",
                                "assert callable(sys.modules['install_kafka_topic_acl'].main)")
        result = subprocess.run([sys.executable, '-I', '-c', source], capture_output=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr.decode())
        sources[-1] = (sources[-1][0], '0' * 64)
        bad = operator.loader(str(root), sources, 'a' * 64)
        result = subprocess.run([sys.executable, '-I', '-c', bad], capture_output=True, timeout=20)
        self.assertNotEqual(0, result.returncode)

    def test_bundle_gate_requires_native_positive_negative_and_closed_state(self):
        proof = {'schema_version': 1, 'kafka_topic_acl': 'Passed', 'topic_count': 6, 'credential_count': 5,
            'group_count': 7, 'acl_entries': len(contract.permissions()), 'positive_produce_consume': 'Passed',
            'foreign_topic_group_admin_denials': 'Passed', 'idempotent_reconciliation': 'Passed',
            'existing_state': 'Preserved', 'application_ingress': 'CLOSED', 'public_access': 'CLOSED',
            'quotas': 'Not run', 'production_readiness': 'Not verified'}
        with patch.object(bundle.previous, 'verify_proof'):
            bundle.verify_proof({'kafka_client_tls_delivery': {'kafka_topic_acl': proof}})
            for key in proof:
                invalid = {**proof, key: 'wrong'}
                with self.subTest(key=key), self.assertRaises(ValueError):
                    bundle.verify_proof({'kafka_client_tls_delivery': {'kafka_topic_acl': invalid}})

    def test_failure_output_never_contains_unclassified_private_text(self):
        private = 'private-key-or-provider-body-must-not-be-printed'
        with patch.object(installer, '_execute', side_effect=ValueError(private)), \
                contextlib.redirect_stderr(io.StringIO()) as output, self.assertRaises(ValueError):
            installer.execute({})
        self.assertNotIn(private, output.getvalue())
        self.assertIn('OTHER_PREFLIGHT_OR_NATIVE_FAILURE', output.getvalue())


if __name__ == '__main__':
    unittest.main()
