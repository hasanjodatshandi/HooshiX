import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import call, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import openbao_snapshot_agent as agent
import openbao_snapshot_crypto as crypto
import bootstrap_openbao_snapshot as bootstrap
import render_openbao_snapshot as renderer
import build_openbao_snapshot_candidate as builder

IMAGE = renderer.REPOSITORY + '@sha256:' + 'a' * 64
RECIPIENTS = ['A' * 256, 'B' * 256, 'C' * 256]


class SnapshotScheduleRenderTest(unittest.TestCase):
    def render(self):
        return renderer.candidate(IMAGE)

    def test_hourly_manifest_is_private_scoped_and_non_replaying(self):
        manifest = self.render()
        by_kind = {item['kind']: item for item in manifest}
        cron = by_kind['CronJob']['spec']
        self.assertEqual('0 * * * *', cron['schedule'])
        self.assertEqual('Forbid', cron['concurrencyPolicy'])
        self.assertEqual(0, cron['jobTemplate']['spec']['backoffLimit'])
        pod = cron['jobTemplate']['spec']['template']['spec']
        self.assertEqual('openbao-snapshot', pod['serviceAccountName'])
        self.assertFalse(pod['automountServiceAccountToken'])
        self.assertEqual(10001, pod['securityContext']['fsGroup'])
        identity = next(volume for volume in pod['volumes'] if 'projected' in volume)
        self.assertEqual('hooshix-openbao', identity['projected']['sources'][0]
                         ['serviceAccountToken']['audience'])
        policy = by_kind['NetworkPolicy']['spec']
        self.assertEqual([], policy['ingress'])
        self.assertEqual(renderer.PROVIDER_IP,
                         policy['egress'][2]['to'][0]['ipBlock']['cidr'])
        authz = by_kind['AuthorizationPolicy']['spec']
        self.assertEqual(['prod.sajtech.internal/ns/hooshix-secrets/sa/openbao-snapshot'],
                         authz['rules'][0]['from'][0]['source']['principals'])
        serialized = json.dumps(manifest)
        self.assertEqual({'ServiceAccount', 'NetworkPolicy', 'AuthorizationPolicy', 'CronJob'},
                         {item['kind'] for item in manifest})
        self.assertFalse(any('secretKeyRef' in json.dumps(item) for item in manifest))
        for forbidden in ('private-access', 'private-secret', 'root-token', 'ConfigMap'):
            self.assertNotIn(forbidden, serialized)

    def test_renderer_emits_separate_yaml_documents(self):
        output = io.StringIO()
        with patch.object(sys, 'argv', ['render_openbao_snapshot.py', '--image', IMAGE]), \
                contextlib.redirect_stdout(output):
            self.assertIsNone(renderer.main())
        self.assertIn('\n---\n', output.getvalue())

    def test_wrong_or_mutable_image_rejected(self):
        for image in ('docker.io/example/snapshot:latest', IMAGE.replace('a' * 64, 'b' * 64) + ':tag'):
            with self.subTest(image=image), self.assertRaises(ValueError):
                renderer.candidate(image)


class SnapshotScheduleRunTest(unittest.TestCase):
    def test_credentials_never_logged_and_token_revoked_before_external_transfer(self):
        with tempfile.TemporaryDirectory() as directory:
            token_path = Path(directory) / 'token'
            token_path.write_text('a' * 120)
            calls = []
            def request(_opener, path, **kwargs):
                calls.append(('request', path))
                if path == agent.AUTH_PATH:
                    return b'{"auth":{"client_token":"fixture-bao-token"}}'
                if path == agent.KV_PATH:
                    return json.dumps({'data': {'data': {'access_key': 'private-access',
                        'secret_key': 'private-secret', 'recipients': RECIPIENTS}}}).encode()
                return b'\x1f\x8b\x08fixture-snapshot'
            def revoke(_opener, token):
                self.assertEqual('fixture-bao-token', token)
                calls.append(('revoke', token))
            def upload(_cipher, access, key, snapshot_id):
                self.assertEqual(('private-access', 'private-secret'), (access, key))
                calls.append(('upload', snapshot_id))
                return {'object_key': 'openbao-backups/' + snapshot_id + '.snap.pgp',
                        'version_id': 'v1', 'ciphertext_sha256': 'c' * 64}, _cipher
            with patch.object(agent, 'TOKEN_PATH', token_path), \
                    patch.object(agent, 'client', return_value=object()), \
                    patch.object(agent, 'request', side_effect=request), \
                    patch.object(agent, 'revoke', side_effect=revoke), \
                    patch.object(agent.crypto, 'public_keys'), \
                    patch.object(agent.crypto, 'seal', return_value=b'\xc1' + b'x' * 200), \
                    patch.object(agent.cloud, 'deliver', side_effect=upload), \
                    patch.object(agent.cloud, 'deliver_receipt', side_effect=lambda data, *_: (
                        {'object_key': 'openbao-backups/receipt'}, data)), \
                    contextlib.redirect_stdout(io.StringIO()) as output:
                agent.run()
            value = json.loads(output.getvalue())
            self.assertEqual('Passed', value['readback'] if 'readback' in value else value['receipt_readback'])
            self.assertLess(calls.index(('revoke', 'fixture-bao-token')),
                            next(i for i, value in enumerate(calls) if value[0] == 'upload'))
            self.assertNotIn('private-access', output.getvalue())
            self.assertNotIn('private-secret', output.getvalue())

    def test_wrong_kv_shape_stops_before_snapshot_or_upload(self):
        with tempfile.TemporaryDirectory() as directory:
            token_path = Path(directory) / 'token'
            token_path.write_text('b' * 120)
            calls = []
            def request(_opener, path, **kwargs):
                calls.append(path)
                return (b'{"auth":{"client_token":"fixture-token"}}' if path == agent.AUTH_PATH
                        else b'{"data":{"data":{"extra":"reject"}}}')
            with patch.object(agent, 'TOKEN_PATH', token_path), patch.object(agent, 'client', return_value=object()), \
                    patch.object(agent, 'request', side_effect=request), patch.object(agent, 'revoke') as revoke, \
                    patch.object(agent.cloud, 'deliver') as upload:
                with self.assertRaises(agent.errors.SnapshotFailed):
                    agent.run()
            self.assertEqual([agent.AUTH_PATH, agent.KV_PATH], calls)
            revoke.assert_called_once()
            self.assertEqual('fixture-token', revoke.call_args.args[1])
            upload.assert_not_called()


class SnapshotBootstrapTest(unittest.TestCase):
    class API:
        def __init__(self):
            self.values = {}
            self.writes = []

        def call(self, path, method='GET', body=None, token=None, expected=200):
            if method == 'GET':
                if path not in self.values:
                    return {}
                return self.values[path]
            self.writes.append((path, method, body))
            if path.startswith('sys/policies/acl/'):
                self.values[path] = {'data': {'policy': body['policy']}}
            elif path.startswith('auth/hooshix-kubernetes/role/'):
                self.values[path] = {'data': body}
            elif path == bootstrap.KV_PATH:
                self.values[path] = {'data': {'data': body['data'], 'metadata': {'version': 1}}}
            return {}

    def test_bootstrap_is_cas_initialized_idempotent_and_keeps_values_out_of_receipt(self):
        api = self.API()
        with patch.object(bootstrap.host, 'public_keys', return_value='d' * 64):
            bootstrap.configure(api, 'root-token', 'private-access', 'private-secret', RECIPIENTS)
            writes = list(api.writes)
            bootstrap.configure(api, 'root-token', 'private-access', 'private-secret', RECIPIENTS)
        self.assertEqual(3, len(writes))
        self.assertEqual(writes, api.writes)
        credential_write = next(body for path, method, body in writes if path == bootstrap.KV_PATH)
        self.assertEqual({'cas': 0}, credential_write['options'])
        self.assertEqual({'access_key', 'secret_key', 'recipients'}, set(credential_write['data']))
        self.assertEqual('private-access', credential_write['data']['access_key'])
        self.assertEqual('private-secret', credential_write['data']['secret_key'])

    def test_existing_bucket_credentials_are_never_overwritten(self):
        api = self.API()
        api.values[bootstrap.KV_PATH] = {'data': {'data': {'access_key': 'other',
            'secret_key': 'other', 'recipients': RECIPIENTS}, 'metadata': {'version': 1}}}
        with patch.object(bootstrap.host, 'public_keys', return_value='d' * 64), \
                self.assertRaises(bootstrap.host.custody.BootstrapFailed):
            bootstrap.configure(api, 'root-token', 'private-access', 'private-secret', RECIPIENTS)
        self.assertEqual([], api.writes)


class SnapshotCandidateReleaseTest(unittest.TestCase):
    def test_exact_private_recipe_and_protected_release_workflow(self):
        selected = builder.recipe()
        self.assertEqual('linux/amd64', selected['platform'])
        self.assertEqual(builder.PACKAGES, selected['packages'])
        workflow = (builder.ROOT / '.github/workflows/production-release.yml').read_text()
        job = workflow.split('  openbao-candidate:\n')[1]
        for required in ('openbao-snapshot-candidate', 'environment: production-release',
                         'scripts/production/publish_openbao_snapshot_candidate.py',
                         'openbao-snapshot-release-evidence'):
            self.assertIn(required, job)
        self.assertIn('never deploys', workflow)


if __name__ == '__main__':
    unittest.main()
