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


class PlatformCommissioningTest(unittest.TestCase):
    def plan(self):
        return {'schema_version': 1, 'installation_id': 'hooshix-production',
                'profile': 'production-single-server',
                'kyverno_upgrade': {'version': '1.19.1'},
                'mesh': {'stages': [{'component': name} for name in ('base', 'istiod', 'cni', 'ztunnel')]},
                'openbao': bao.candidate('hooshix-openbao-local'),
                'admission_prerequisites': admission.reporting_permissions(),
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
