"""Source rebuilds cannot inherit unchanged-import or deployment approval."""
import copy
import hashlib
import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_patched_platform as target


class PatchedPlatformTest(unittest.TestCase):
    def setUp(self):
        self.selected = target.recipe()
        self.now = datetime(2026, 10, 10, tzinfo=timezone.utc)
        self.digest = 'sha256:' + 'a' * 64
        self.files = {'syft.json': {'source': {'type': 'image', 'metadata': {
            'imageID': self.digest, 'os': 'linux', 'architecture': 'amd64', 'labels': {
                'io.hooshix.provenance.kind': 'patched-upstream-source-build',
                'io.hooshix.source.revision': self.selected['sources']['eso']['revision'],
                'io.hooshix.recipe.sha256': hashlib.sha256(target.RECIPE.read_bytes()).hexdigest()}}},
            'artifacts': [{'name': 'stdlib', 'version': 'go1.26.9',
                           'locations': [{'path': '/usr/bin/external-secrets'}]},
                          {'name': 'golang.org/x/net', 'version': 'v0.60.0'}]},
            'cyclonedx.json': {'bomFormat': 'CycloneDX', 'components': [{}]},
            'grype.json': {'matches': []}, 'database.json': {'valid': True,
                'schemaVersion': '6.0.0', 'built': self.now.isoformat()}}

    def validate(self):
        with patch.object(target, 'recipe', return_value=self.selected), \
                patch.object(target, 'load', side_effect=lambda path: self.files[path.name]):
            return target.validate_local(Path('public'), 'eso', self.digest, self.now)

    def test_good_scan_is_candidate_only(self):
        result = self.validate()
        self.assertEqual('Passed', result['scan'])
        self.assertEqual('Not verified', result['production_promotion'])
        self.assertEqual('patched-upstream-source-build', result['provenance_kind'])

    def test_stale_database_unknown_severity_high_and_critical_fail_closed(self):
        for severity in ('High', 'Critical', 'changed-schema'):
            self.files['grype.json']['matches'] = [{'vulnerability': {'severity': severity}}]
            with self.subTest(severity=severity), self.assertRaises(ValueError):
                self.validate()
        self.files['grype.json']['matches'] = []
        self.files['database.json']['built'] = (self.now - timedelta(days=6)).isoformat()
        with self.assertRaises(ValueError):
            self.validate()

    def test_old_dependency_or_toolchain_and_missing_binary_rejected(self):
        original = copy.deepcopy(self.files)
        for index, version in ((0, 'go1.26.8'), (1, 'v0.58.0')):
            self.files = copy.deepcopy(original)
            self.files['syft.json']['artifacts'][index]['version'] = version
            with self.assertRaises(ValueError):
                self.validate()
        self.files = copy.deepcopy(original)
        self.files['syft.json']['artifacts'][0]['locations'] = []
        with self.assertRaises(ValueError):
            self.validate()

    def test_image_source_recipe_and_platform_mismatch_rejected(self):
        original = copy.deepcopy(self.files)
        for field, value in (('imageID', 'sha256:' + 'b' * 64), ('architecture', 'arm64')):
            self.files = copy.deepcopy(original)
            self.files['syft.json']['source']['metadata'][field] = value
            with self.assertRaises(ValueError):
                self.validate()
        for field in ('io.hooshix.source.revision', 'io.hooshix.recipe.sha256',
                      'io.hooshix.provenance.kind'):
            self.files = copy.deepcopy(original)
            self.files['syft.json']['source']['metadata']['labels'][field] = 'wrong'
            with self.assertRaises(ValueError):
                self.validate()

    def test_all_executables_and_runtime_base_are_preserved(self):
        for component in target.COMPONENTS:
            text = target.dockerfile(component, self.selected)
            self.assertIn('FROM ' + target.base_image(component), text)
            self.assertIn('GOTOOLCHAIN=local GOWORK=off', text)
            self.assertIn('GOSUMDB=sum.golang.org', text)
            self.assertIn('go mod verify', text)
            self.assertIn('-mod=readonly', text)
            for path in target.BINARIES[component].values():
                self.assertIn(' ' + path + '\n', text)
            self.assertNotIn('ENTRYPOINT', text)
            self.assertNotIn('USER ', text)
        cni = target.dockerfile('cni', self.selected)
        self.assertEqual(2, cni.count('RUN go build'))
        self.assertIn('-tags \'all_providers\'', target.dockerfile('eso', self.selected))
        self.assertIn('version.fullVersion=2.6.4', target.dockerfile('openbao', self.selected))

    def test_unprotected_publication_stops_before_any_build(self):
        with patch.object(target, 'build') as builder, self.assertRaises(ValueError):
            target.publish(Path('unused'), {})
        builder.assert_not_called()

    def test_failed_component_scan_prevents_all_signing(self):
        commands = []
        def fake_run(argv, **kwargs):
            commands.append(argv)
            return 'b' * 40
        with patch.object(target, 'context', return_value=('b' * 40, 'github:1:1')), \
                patch.object(target, 'run', side_effect=fake_run), \
                patch.object(Path, 'mkdir'), patch.object(target, 'build', side_effect=ValueError), \
                self.assertRaises(ValueError):
            target.publish(Path('unused'), {})
        self.assertFalse(any(argv[0] in ('docker', 'cosign') for argv in commands))

    def test_ci_has_no_signing_or_credentials_and_release_requires_main(self):
        workflow = (target.ROOT / '.github/workflows/patched-platform-source.yml').read_text()
        for forbidden in ('id-token: write', 'packages: write', 'secrets.', '--publish',
                          'continue-on-error', 'pull_request_target'):
            self.assertNotIn(forbidden, workflow)
        release = (target.ROOT / '.github/workflows/production-release.yml').read_text()
        self.assertIn('--publish', release)
        self.assertIn('Exact main baseline must pass first', release)
        self.assertIn('environment: production-release', release)


if __name__ == '__main__':
    unittest.main()
