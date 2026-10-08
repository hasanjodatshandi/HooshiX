import base64
import contextlib
import copy
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import Mock, mock_open, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_platform_commissioning_bundle as bundle
import commission_platform as host
import rehearse_platform_admission as staging
import render_openbao_candidate as bao
import render_platform_admission as admission
import platform_image_egress as egress


class PlatformCommissioningTest(unittest.TestCase):
    def plan(self):
        return {'schema_version': 1, 'installation_id': 'hooshix-production',
                'profile': 'production-single-server',
                'kyverno_upgrade': {'version': '1.19.1'},
                'mesh': {'stages': [{'component': name} for name in ('base', 'istiod', 'cni', 'ztunnel')]},
                'openbao': bao.candidate('hooshix-openbao-local'),
                'admission_prerequisites': admission.reporting_permissions(),
                'image_verifier_egress': egress.candidate(),
                'admission': {'items': [{'spec': {'failurePolicy': 'Fail', 'validationActions': ['Deny']}}] * 6},
                'commissioning_evidence': {'source_revision': 'a' * 40, 'staging': 'Passed',
                                          'run_id': 12, 'observed_at': datetime.now(timezone.utc).isoformat()}}

    def test_plan_hash_tree_freshness_and_fail_closed_metadata(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'plan.json'
            valid = self.plan()
            for field, bad in (('schema_version', 2), ('profile', 'other'),
                               ('commissioning_evidence', {'source_revision': 'a' * 40, 'staging': 'Passed',
                                                           'run_id': 1, 'observed_at': '2026-01-01T00:00:00+00:00'})):
                value = copy.deepcopy(valid)
                value[field] = bad
                path.write_text(json.dumps(value))
                digest = host.hashlib.sha256(path.read_bytes()).hexdigest()
                with self.assertRaises(host.custody.BootstrapFailed):
                    host.read_plan(path, digest, 'a' * 40)
            path.write_text(json.dumps(valid))
            digest = host.hashlib.sha256(path.read_bytes()).hexdigest()
            self.assertEqual(valid, host.read_plan(path, digest, 'a' * 40))
            for digest, revision in (('b' * 64, 'a' * 40), (digest, 'main'), (digest, 'b' * 40)):
                with self.assertRaises(host.custody.BootstrapFailed):
                    host.read_plan(path, digest, revision)
            link = Path(temp) / 'link'
            link.symlink_to(path)
            with self.assertRaises(OSError):
                host.read_plan(link, digest, 'a' * 40)

    def test_reporting_subresource_permissions_cannot_grant_write_or_secrets(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'plan.json'
            for field, value in (('verbs', ['get', 'list', 'watch', 'update']),
                                 ('resources', ['secrets']), ('apiGroups', ['*'])):
                plan = self.plan()
                plan['admission_prerequisites']['items'][0]['rules'][0][field] = value
                path.write_text(json.dumps(plan))
                digest = host.hashlib.sha256(path.read_bytes()).hexdigest()
                with self.assertRaises(host.custody.BootstrapFailed):
                    host.read_plan(path, digest, 'a' * 40)

    def test_verifier_egress_is_exact_and_never_grants_app_or_private_access(self):
        import ipaddress
        policy = egress.candidate()
        spec = policy['spec']
        self.assertEqual(['Egress'], spec['policyTypes'])
        self.assertNotIn('ingress', spec)
        self.assertEqual({'app.kubernetes.io/part-of': 'kyverno'}, spec['podSelector']['matchLabels'])
        self.assertEqual(['admission-controller', 'reports-controller'],
                         spec['podSelector']['matchExpressions'][0]['values'])
        self.assertEqual([{'port': 443, 'protocol': 'TCP'}], spec['egress'][0]['ports'])
        block = spec['egress'][0]['to'][0]['ipBlock']
        self.assertEqual('0.0.0.0/0', block['cidr'])
        for address in ('10.42.0.1', '10.43.0.1', '169.254.169.254', '127.0.0.1',
                        '172.16.0.1', '192.168.0.1', '100.64.0.1', '224.0.0.1'):
            self.assertTrue(any(ipaddress.ip_address(address) in ipaddress.ip_network(c)
                                for c in block['except']))
        for address in ('140.82.121.34', '34.36.47.134'):
            self.assertFalse(any(ipaddress.ip_address(address) in ipaddress.ip_network(c)
                                 for c in block['except']))
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'plan.json'
            for value in (None, {'spec': {}}, policy | {'metadata': {}}):
                plan = self.plan()
                plan['image_verifier_egress'] = value
                path.write_text(json.dumps(plan))
                with self.assertRaises(host.custody.BootstrapFailed):
                    host.read_plan(path, host.hashlib.sha256(path.read_bytes()).hexdigest(), 'a' * 40)

    def test_egress_install_create_reentry_and_foreign_conflict_preserve_other_policies(self):
        value = egress.candidate()
        with patch.object(host, 'get', return_value=None) as get, patch.object(host, 'create') as create:
            host.install_image_verifier_egress(value)
            get.assert_called_once_with('networkpolicy', egress.NAME, 'kyverno')
            create.assert_called_once_with(value)
        with patch.object(host, 'get', return_value=value), patch.object(host, 'create') as create, \
                patch.object(host, 'apply') as apply:
            host.install_image_verifier_egress(value)
            create.assert_not_called()
            apply.assert_not_called()
        for path in ('metadata', 'spec'):
            foreign = copy.deepcopy(value)
            foreign[path] = {}
            with patch.object(host, 'get', return_value=foreign), patch.object(host, 'create') as create, \
                    patch.object(host, 'apply') as apply, self.assertRaises(host.custody.BootstrapFailed):
                host.install_image_verifier_egress(value)
            create.assert_not_called()
            apply.assert_not_called()

    def test_no_root_or_unexpected_target_denied_before_execution(self):
        with patch.object(os, 'geteuid', return_value=1000), patch.object(host, 'native') as native, \
                self.assertRaises(host.custody.BootstrapFailed):
            host.preflight()
        native.assert_not_called()

    def test_native_boundary_has_bounded_timeout_suppressed_errors_and_password_fd_only(self):
        result = Mock(returncode=0, stdout=b'public')
        with patch.object(subprocess, 'run', return_value=result) as command:
            self.assertEqual(b'public', host.native(['openssl', 'x509'], data=b'private-input', password=b'private-passphrase'))
            args, options = command.call_args
            self.assertNotIn('private-passphrase', json.dumps(args))
            self.assertNotIn('private-input', json.dumps(args))
            self.assertEqual(subprocess.DEVNULL, options['stderr'])
            self.assertEqual(b'private-input', options['input'])
            self.assertEqual(1, len(options['pass_fds']))
            self.assertNotIn('shell', options)
            result.returncode = 1
            with self.assertRaises(host.custody.BootstrapFailed):
                host.native(['openssl'])

    def test_existing_registry_secret_cannot_be_overwritten(self):
        with patch.object(host.getpass, 'getpass', return_value='synthetic-private-token'), \
                patch.object(host, 'open', mock_open(), create=True), \
                patch.object(host, 'get', return_value={'metadata': {'labels': {}}}), \
                patch.object(host, 'create') as create, patch.object(host, 'apply') as apply, \
                self.assertRaises(host.custody.BootstrapFailed):
            host.registry_credentials()
        create.assert_not_called()
        apply.assert_not_called()

    def test_observed_large_crd_response_is_allowed_only_for_exact_public_reads(self):
        # Actual target maximum was 2,287,098 bytes; use a valid padded JSON
        # fixture without a production schema, identity, annotation or secret.
        response = b'{"kind":"CustomResourceDefinition"}' + b' ' * 2287098
        with patch.object(subprocess, 'run', return_value=Mock(returncode=0, stdout=response)), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            for name in host.LARGE_PUBLIC_CRDS:
                self.assertEqual('CustomResourceDefinition', host.get('customresourcedefinition', name)['kind'])
            for kind, name, namespace in (('secret', 'credential', 'kyverno'),
                                         ('configmap', 'kyverno', 'kyverno'),
                                         ('customresourcedefinition', 'foreign.example.com', None),
                                         ('customresourcedefinition', 'policies.kyverno.io', 'kyverno')):
                with self.assertRaises(host.custody.BootstrapFailed):
                    host.get(kind, name, namespace)
            self.assertEqual('', output.getvalue())
        with patch.object(subprocess, 'run', return_value=Mock(returncode=0, stdout=b' ' * (host.CRD_BOUND + 1))), \
                self.assertRaises(host.custody.BootstrapFailed):
            host.get('customresourcedefinition', 'policies.kyverno.io')
        with patch.object(subprocess, 'run', return_value=Mock(returncode=1, stdout=b'{}')), \
                self.assertRaises(host.custody.BootstrapFailed):
            host.get('customresourcedefinition', 'policies.kyverno.io')

    def test_resume_builder_requires_authenticated_identical_predecessor_plan(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            previous = self.plan()
            previous.pop('image_verifier_egress')  # authenticated pre-repair plan
            source = 'a' * 40
            observed = previous['commissioning_evidence']['observed_at']
            content = json.dumps(previous).encode()
            digest = host.hashlib.sha256(content).hexdigest()
            (root / 'plan.json').write_bytes(content)
            (root / 'bundle.json').write_text(json.dumps({'schema_version': 1, 'source_revision': source,
                                                        'files': {'plan.json': digest}}))
            current = copy.deepcopy(previous)
            current['image_verifier_egress'] = egress.candidate()
            current['commissioning_evidence']['source_revision'] = 'b' * 40
            with patch.object(bundle, 'git', return_value=''), \
                    patch.object(bundle, 'staged_receipt', return_value={'observed_at': observed}) as staging:
                self.assertEqual({'source_revision': source, 'plan_sha256': digest},
                                 bundle.resume_record(root, current, 'b' * 40, root))
                self.assertEqual(source, staging.call_args.args[-1])
                for field in ('openbao', 'admission', 'kyverno_upgrade', 'profile', 'image_verifier_egress'):
                    altered = copy.deepcopy(current)
                    altered[field] = {'unreviewed': True}
                    with self.assertRaises(ValueError):
                        bundle.resume_record(root, altered, 'b' * 40, root)
            with patch.object(bundle, 'git', side_effect=ValueError('not ancestor')), self.assertRaises(ValueError):
                bundle.resume_record(root, current, 'b' * 40, root)
            with patch.object(bundle, 'git', return_value=''), \
                    patch.object(bundle, 'staged_receipt', return_value={'observed_at': 'different'}), \
                    self.assertRaises(ValueError):
                bundle.resume_record(root, current, 'b' * 40, root)
            (root / 'plan.json').write_bytes(content + b' ')
            with patch.object(bundle, 'git', return_value=''), self.assertRaises(ValueError):
                bundle.resume_record(root, current, 'b' * 40, root)

    def test_exact_predecessor_marker_retained_and_conflicts_stop(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            marker = root / 'plan.json'
            previous = {'source_revision': 'a' * 40, 'plan_sha256': 'a' * 64}
            content = json.dumps(previous).encode()
            marker.write_bytes(content)
            with patch.object(host, 'STATE', root), patch.object(host.custody, 'protected'), \
                    patch.object(host.custody, 'create') as create, patch.object(host, 'get') as get:
                host.commissioning_marker({'resume_from': previous}, 'b' * 64, 'b' * 40)
                self.assertEqual(content, marker.read_bytes())
                for plan in ({}, {'resume_from': previous | {'plan_sha256': 'c' * 64}}):
                    with self.assertRaises(host.custody.BootstrapFailed):
                        host.commissioning_marker(plan, 'b' * 64, 'b' * 40)
                create.assert_not_called()
                get.assert_not_called()
                marker.write_bytes(b'null')
                with self.assertRaises(host.custody.BootstrapFailed):
                    host.commissioning_marker({}, 'b' * 64, 'b' * 40)
                marker.unlink()
                with self.assertRaises(host.custody.BootstrapFailed):
                    host.commissioning_marker({'resume_from': previous}, 'b' * 64, 'b' * 40)
                create.assert_not_called()
                get.assert_not_called()

    def test_registry_token_only_in_memory_three_exact_secrets(self):
        with patch.object(host.getpass, 'getpass', return_value='synthetic-private-token'), \
                patch.object(host, 'open', mock_open(), create=True), \
                patch.object(host, 'get', return_value=None), patch.object(host, 'create') as create, \
                contextlib.redirect_stdout(io.StringIO()) as output:
            host.registry_credentials()
        self.assertEqual('', output.getvalue())
        self.assertEqual(['kyverno', 'istio-system', 'hooshix-secrets'],
                         [c.args[0]['metadata']['namespace'] for c in create.call_args_list])
        for call in create.call_args_list:
            data = json.loads(base64.b64decode(call.args[0]['data']['.dockerconfigjson']))
            self.assertEqual(b'hasanjodatshandi:synthetic-private-token',
                             base64.b64decode(data['auths']['ghcr.io']['auth']))

    def registry_fixture(self, namespace='kyverno'):
        return {'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {
            'name': 'hooshix-ghcr-read', 'namespace': namespace, 'labels': dict(host.LABELS),
            'resourceVersion': '123', 'uid': 'fixture-uid', 'annotations': {'fixture': 'preserve'}},
            'type': 'kubernetes.io/dockerconfigjson', 'data': {'.dockerconfigjson': 'old-synthetic-data'}}

    def test_owned_registry_rotation_preserves_metadata_and_uses_conditional_update(self):
        for namespace in ('kyverno', 'istio-system', 'hooshix-secrets'):
            existing = self.registry_fixture(namespace)
            before = copy.deepcopy(existing)
            with patch.object(host, 'get', return_value=existing), patch.object(host, 'kube') as kube, \
                    patch.object(host, 'apply') as apply, patch.object(host, 'create') as create, \
                    contextlib.redirect_stdout(io.StringIO()) as output:
                host.write_registry_secret(namespace, 'new-synthetic-data')
            self.assertEqual('', output.getvalue())
            kube.assert_called_once_with('replace', '--field-manager=' + host.MANAGER, '-f', '-',
                                         body=before | {'data': {'.dockerconfigjson': 'new-synthetic-data'}})
            apply.assert_not_called()
            create.assert_not_called()

    def test_registry_reentry_with_identical_value_does_not_write(self):
        existing = self.registry_fixture()
        with patch.object(host, 'get', return_value=existing), patch.object(host, 'kube') as kube, \
                patch.object(host, 'create') as create:
            host.write_registry_secret('kyverno', existing['data']['.dockerconfigjson'])
        kube.assert_not_called()
        create.assert_not_called()

    def test_registry_rotation_rejects_foreign_shape_missing_version_and_immutable(self):
        for field, value in (('name', 'foreign'), ('namespace', 'other'), ('labels', {}),
                             ('resourceVersion', None), ('resourceVersion', ''), ('resourceVersion', 123)):
            existing = self.registry_fixture()
            existing['metadata'][field] = value
            with patch.object(host, 'get', return_value=existing), patch.object(host, 'kube') as kube, \
                    self.assertRaises(host.custody.BootstrapFailed):
                host.write_registry_secret('kyverno', 'new-synthetic-data')
            kube.assert_not_called()
        for field, value in (('type', 'Opaque'), ('data', {'.dockerconfigjson': 'old', 'other': 'private'}),
                             ('immutable', True)):
            existing = self.registry_fixture() | {field: value}
            with patch.object(host, 'get', return_value=existing), patch.object(host, 'kube') as kube, \
                    self.assertRaises(host.custody.BootstrapFailed):
                host.write_registry_secret('kyverno', 'new-synthetic-data')
            kube.assert_not_called()
        with patch.object(host, 'get') as get, self.assertRaises(host.custody.BootstrapFailed):
            host.write_registry_secret('unapproved', 'new-synthetic-data')
        get.assert_not_called()

    def test_registry_conflict_fails_once_without_secret_diagnostics_or_force(self):
        result = Mock(returncode=1, stdout=b'', stderr=b'conflict private-secret-canary')
        with patch.object(host, 'get', return_value=self.registry_fixture()), \
                patch.object(subprocess, 'run', return_value=result) as run, \
                contextlib.redirect_stdout(io.StringIO()) as output, \
                self.assertRaisesRegex(host.custody.BootstrapFailed, '^NATIVE_OPERATION_FAILED_STATE_PRESERVED$'):
            host.write_registry_secret('kyverno', 'new-secret-canary')
        self.assertEqual('', output.getvalue())
        run.assert_called_once()
        args, options = run.call_args
        self.assertNotIn('canary', json.dumps(args))
        self.assertNotIn('--force', args[0])
        self.assertEqual(subprocess.DEVNULL, options['stderr'])
        self.assertEqual('123', json.loads(options['input'])['metadata']['resourceVersion'])

    def test_staging_is_authenticated_and_bound_to_reviewed_tree_and_all_checks(self):
        record = {'path': bundle.WORKFLOW, 'status': 'completed', 'conclusion': 'success',
                  'repository': {'full_name': bundle.publication.REPOSITORY},
                  'head_repository': {'full_name': bundle.publication.REPOSITORY},
                  'event': 'pull_request', 'head_sha': 'a' * 40}
        receipt = {'revision': 'a' * 40, 'scope': 'Disposable Calico/Ambient/Kyverno signed platform staging',
                   'platform_checks': dict.fromkeys(bundle.CHECKS, 'Passed'),
                   'checks': dict.fromkeys(bundle.FOUNDATION, 'Passed'),
                   'observed_at': datetime.now(timezone.utc).isoformat()}
        with patch.object(bundle, 'git', return_value='c' * 40):
            bundle.check_staging(record, receipt, 'b' * 40)
            for field, value in (('conclusion', 'failure'), ('path', 'other.yml'),
                                 ('head_repository', {'full_name': 'untrusted/fork'})):
                bad = record | {field: value}
                with self.assertRaises(ValueError):
                    bundle.check_staging(bad, receipt, 'b' * 40)
            bad = copy.deepcopy(receipt)
            bad['platform_checks']['mtls_positive'] = 'Not verified'
            with self.assertRaises(ValueError):
                bundle.check_staging(record, bad, 'b' * 40)
        with patch.object(bundle, 'git', side_effect=['x' * 40, 'y' * 40]), self.assertRaises(ValueError):
            bundle.check_staging(record, receipt, 'b' * 40)

    def test_stable_policy_readiness_uses_nested_v1_status(self):
        command = Mock()
        policy = admission.image_policy('istio-system', ['image'], 'a' * 40)
        staging.policy_ready(command, policy)
        self.assertIn('--for=jsonpath={.status.conditionStatus.ready}=true', command.call_args.args)
        self.assertNotIn('--for=condition=Ready', command.call_args.args)

    def test_network_status_probe_does_not_inherit_server_arguments(self):
        instance = staging.Staging.__new__(staging.Staging)
        instance.plan = {'openbao': bao.candidate('standard'), 'admission': {'items': [{}, {}, {}]}}
        instance.checks = {}
        created = []

        def kube(*args, data=None, **_):
            if args[0] == 'apply':
                value = json.loads(data)
                if value.get('kind') == 'Pod':
                    created.append(value)
            if 'get' in args:
                name = args[args.index('pod') + 1]
                return json.dumps({'status': {'containerStatuses': [{'state': {'terminated': {
                    'exitCode': 2 if name == 'mtls-positive' else 1}}}]}}).encode()
            if 'logs' in args:
                return b'{"sealed":true}' if args[-1] == 'mtls-positive' else b'fixture denied'
            return b''

        with contextlib.redirect_stdout(io.StringIO()):
            instance.network(kube, lambda check, **_: check())
        self.assertEqual(3, len(created))
        for pod in created:
            self.assertEqual([], pod['spec']['containers'][0]['args'])
            self.assertEqual(['/usr/bin/bao', 'status'], pod['spec']['containers'][0]['command'][:2])
        self.assertEqual(['server', '-config=/openbao/config/server.json'],
                         instance.plan['openbao']['items'][-1]['spec']['template']['spec']['containers'][0]['args'])

    def test_target_admission_requires_exact_denial_and_positive_server_dry_run(self):
        plan = {'mesh': {'stages': [{'manifest': {'items': [{'kind': 'Deployment', 'spec': {
            'template': {'spec': {'serviceAccountName': 'istiod', 'containers': [{'image': 'mesh@sha256:x'}]}}}}]}}]},
            'openbao': bao.candidate('hooshix-openbao-local')}
        with patch.object(host, 'apply') as apply, patch.object(host, 'kube') as kube:
            host.target_admission(plan)
        self.assertEqual('istiod', apply.call_args.args[0]['metadata']['name'])
        denial, positive = kube.call_args_list
        self.assertIn('--dry-run=server', denial.args)
        self.assertEqual(1, denial.kwargs['expected'])
        self.assertEqual(b'platform bootstrap identity/security exception rejected', denial.kwargs['required_error'])
        self.assertEqual(0, positive.kwargs['expected'])
        self.assertNotEqual(denial.kwargs['body']['spec']['containers'][0]['image'],
                            positive.kwargs['body']['spec']['containers'][0]['image'])

    def test_wrong_native_denial_reason_does_not_pass(self):
        with patch.object(subprocess, 'run', return_value=Mock(returncode=1, stdout=b'', stderr=b'unauthorized')), \
                self.assertRaises(host.custody.BootstrapFailed):
            host.native(['kubectl'], expected=1, required_error=b'platform bootstrap identity/security exception rejected')

    def test_failed_kernel_audit_stops_phase_before_marker(self):
        with patch.object(host, 'kernel_audit', side_effect=host.custody.BootstrapFailed('UNHEALTHY')), \
                patch.object(host, 'native') as native, self.assertRaises(host.custody.BootstrapFailed):
            host.progress('openbao', 'a' * 40)
        native.assert_not_called()

    def test_native_tls_signing_with_synthetic_encrypted_ca_and_no_real_operator_state(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            key, cert = root / 'ca-key.enc.pem', root / 'root.crt'
            password = 'synthetic-fixture-only-long-passphrase'
            host.native(['/usr/bin/openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-days', '2',
                         '-subj', '/CN=disposable-fixture-ca', '-addext', 'basicConstraints=critical,CA:TRUE',
                         '-addext', 'keyUsage=critical,keyCertSign,cRLSign', '-keyout', str(key), '-out', str(cert),
                         '-passout', 'stdin'], data=password.encode() + b'\n', timeout=30)
            files = {'ca-cert.pem': cert.read_bytes(), 'root-cert.pem': cert.read_bytes(),
                     'cert-chain.pem': cert.read_bytes(), 'signing-receipt.json': b'{}'}
            ca = {'metadata': {'labels': host.ca_import.LABELS}, 'data': {
                k: base64.b64encode(v).decode() for k, v in files.items() if k != 'signing-receipt.json'}}

            def public(_files, _marker, directory):
                for name in ('root-cert.pem', 'ca-cert.pem'):
                    (directory / name).write_bytes(files[name])

            with patch.object(host, 'STATE', root), patch.object(host.custody, 'STATE', root), \
                    patch.object(host.ca_import, 'read_public', return_value=files), \
                    patch.object(host.custody, 'public_receipt', return_value={}), \
                    patch.object(host.ca_import, 'validate_public', side_effect=public), \
                    patch.object(host, 'get', side_effect=[ca, None]), \
                    patch.object(host.getpass, 'getpass', return_value=password), patch.object(host, 'create') as create:
                host.tls_secret(root, 'a' * 40)
            self.assertEqual(1, create.call_count)
            self.assertEqual('openbao-server-tls', create.call_args.args[0]['metadata']['name'])
            self.assertEqual({'tls.crt', 'tls.key', 'ca.crt'}, set(create.call_args.args[0]['data']))
            self.assertEqual(['ca-key.enc.pem', 'root.crt'], sorted(p.name for p in root.iterdir()))


if __name__ == '__main__':
    unittest.main()
