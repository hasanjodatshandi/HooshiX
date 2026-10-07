"""Narrow alias repair preserves immutable images and all existing indexes."""
import hashlib
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch, MagicMock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import repair_import_referrers as target


class ImportReferrerTest(unittest.TestCase):
    def setUp(self):
        self.raw = json.dumps({'schemaVersion': 2, 'mediaType': target.IMAGE_TYPES[0],
                               'config': {}, 'layers': []}).encode()
        self.digest = hashlib.sha256(self.raw).hexdigest()
        self.image = 'ghcr.io/hasanjodatshandi/hooshix/platform-istio-istiod-private@sha256:' + self.digest
        self.alias = self.raw
        self.status = 200
        self.calls = []

    def exchange(self, method, path, data=None, extra=None):
        self.calls.append((method, path, data, extra))
        if method == 'PUT':
            self.alias = data
            return 201, {}, b''
        return self.status, {'ETag': 'fixture'}, self.raw if '/sha256:' in path else self.alias

    def test_exact_alias_repaired_and_image_never_overwritten_or_deleted(self):
        self.assertEqual('Repaired', target.repair(self.image, self.exchange))
        writes = [c for c in self.calls if c[0] != 'GET']
        self.assertEqual(1, len(writes))
        self.assertEqual('PUT', writes[0][0])
        self.assertTrue(writes[0][1].endswith('/sha256-' + self.digest))
        self.assertEqual({'If-Match': 'fixture'}, writes[0][3])
        self.assertEqual([], json.loads(self.alias)['manifests'])
        self.assertEqual(self.digest, hashlib.sha256(self.raw).hexdigest())

    def test_existing_index_with_attestations_preserved_byte_for_byte(self):
        self.alias = json.dumps({'schemaVersion': 2, 'mediaType': target.INDEX,
                                'manifests': [{'digest': 'sha256:' + 'b' * 64}]}).encode()
        original = self.alias
        self.assertEqual('Preserved', target.repair(self.image, self.exchange))
        self.assertEqual(original, self.alias)
        self.assertFalse(any(c[0] != 'GET' for c in self.calls))

    def test_missing_alias_left_for_native_cosign_to_create(self):
        self.status = 404
        self.assertEqual('Absent', target.repair(self.image, self.exchange))
        self.assertEqual(1, len(self.calls))

    def test_wrong_digest_type_schema_auth_and_oversized_responses_never_write(self):
        for status, raw in ((403, self.raw), (500, self.raw), (200, b'{}'),
                            (200, self.raw + b' '), (200, b'not-json'),
                            (200, b'x' * (target.MAX_BYTES + 1)),
                            (200, json.dumps({'schemaVersion': 2, 'mediaType': target.INDEX}).encode())):
            with self.subTest(status=status, raw_size=len(raw)):
                self.calls = []
                self.status, self.alias = status, raw
                with self.assertRaises(ValueError):
                    target.repair(self.image, self.exchange)
                self.assertFalse(any(c[0] != 'GET' for c in self.calls))

    def test_unknown_registry_owner_repository_or_digest_rejected_before_io(self):
        for image in (self.image.replace('ghcr.io', 'evil.example'),
                      self.image.replace('hasanjodatshandi', 'other'),
                      self.image.replace('istio-istiod', 'application'), self.image + '/extra'):
            with self.subTest(image=image), self.assertRaises(ValueError):
                target.repair(image, self.exchange)
        self.assertEqual([], self.calls)

    def test_ci_auth_never_available_on_nonmain_local_or_untrusted_context(self):
        with patch.dict(target.os.environ, {}, clear=True), self.assertRaises(ValueError):
            target.repair_owned_import(self.image)

    def test_protected_transport_is_scoped_bounded_and_does_not_follow_redirects(self):
        env = {'GITHUB_ACTIONS': 'true', 'GITHUB_REF': 'refs/heads/main',
               'GITHUB_REPOSITORY': 'hasanjodatshandi/HooshiX', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
               'GITHUB_WORKFLOW_REF': target.EXPECTED_CERTIFICATE_IDENTITY.removeprefix('https://github.com/'),
               'GITHUB_ACTOR': 'owner', 'GH_TOKEN': 'synthetic-ephemeral-token'}
        def response(raw):
            result = MagicMock()
            result.__enter__.return_value = result
            result.status, result.headers = 200, {}
            result.read.return_value = raw
            return result
        auth = response(b'{"token":"synthetic-scoped-token"}')
        index = response(json.dumps({'schemaVersion': 2, 'mediaType': target.INDEX, 'manifests': []}).encode())
        opener = MagicMock()
        opener.open.side_effect = [auth, index]
        with patch.dict(target.os.environ, env, clear=True), \
                patch.object(target.urllib.request, 'build_opener', return_value=opener):
            self.assertEqual('Preserved', target.repair_owned_import(self.image))
        for call in opener.open.call_args_list:
            self.assertTrue(call.args[0].full_url.startswith('https://ghcr.io/'))
            self.assertEqual(20, call.kwargs['timeout'])
        self.assertIn('pull%2Cpush', opener.open.call_args_list[0].args[0].full_url)
        auth.read.assert_called_once_with(target.MAX_BYTES + 1)
        index.read.assert_called_once_with(target.MAX_BYTES + 1)
        self.assertIsNone(target.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://evil.example'))
        for key, value in (('GITHUB_REF', 'refs/heads/feature'), ('GITHUB_REPOSITORY', 'other/repo'),
                           ('GITHUB_EVENT_NAME', 'pull_request'), ('GITHUB_WORKFLOW_REF', 'other'),
                           ('GITHUB_ACTOR', 'owner\n'), ('GH_TOKEN', '')):
            with self.subTest(key=key), patch.dict(target.os.environ, env | {key: value}, clear=True), \
                    patch.object(target.urllib.request, 'build_opener') as network, self.assertRaises(ValueError):
                target.repair_owned_import(self.image)
            network.assert_not_called()

    def test_immutable_manifest_mismatch_or_write_failure_cannot_succeed(self):
        def wrong_image(method, path, data=None, extra=None):
            return (200, {}, b'other') if '/sha256:' in path else self.exchange(method, path, data, extra)
        with self.assertRaises(ValueError):
            target.repair(self.image, wrong_image)
        self.assertFalse(any(c[0] != 'GET' for c in self.calls))
        def denied(method, path, data=None, extra=None):
            return (403, {}, b'') if method == 'PUT' else self.exchange(method, path, data, extra)
        with self.assertRaises(ValueError):
            target.repair(self.image, denied)


if __name__ == '__main__':
    unittest.main()
