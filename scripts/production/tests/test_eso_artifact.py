"""The ESO upstream scan cannot silently become deployment approval."""
import copy
import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import publish_mesh_candidate as scanner
import verify_eso_artifact as target


class ESOArtifactTest(unittest.TestCase):
    def setUp(self):
        self.pin = target.pin()
        self.now = datetime(2026, 10, 10, tzinfo=timezone.utc)
        self.files = {'syft.json': {'source': {'type': 'image', 'metadata': {
            'manifestDigest': self.pin['image'].split('@')[1], 'os': 'linux',
            'architecture': 'amd64', 'repoDigests': [self.pin['image']]}}, 'artifacts': [{}]},
            'cyclonedx.json': {'bomFormat': 'CycloneDX', 'components': [{}]},
            'grype.json': {'matches': []},
            'database.json': {'valid': True, 'schemaVersion': '6.0.0', 'built': self.now.isoformat()}}

    def validate(self):
        with patch.object(target, 'pin', return_value=self.pin), \
                patch.object(scanner, 'load', side_effect=lambda p: self.files[p.name]), \
                patch.object(Path, 'read_bytes', return_value=b'public'):
            return target.validate(Path('evidence'), 'a' * 40, self.now)

    def test_scan_success_never_claims_signed_or_deployed(self):
        receipt = self.validate()
        self.assertEqual('Passed', receipt['scan'])
        self.assertEqual('external-secrets', receipt['component'])
        self.assertEqual(432000, receipt['maximum_database_age_seconds'])
        for field in ('signature_provenance', 'upstream_build_provenance',
                      'native_secret_delivery', 'deployment', 'production_promotion'):
            self.assertEqual('Not verified', receipt[field])

    def test_pin_rejects_other_registry_tag_version_chart_and_approval(self):
        cases = [('image', 'ghcr.io/other/eso@sha256:' + 'a' * 64),
                 ('image', 'ghcr.io/external-secrets/external-secrets:latest'),
                 ('platform', 'linux/arm64'), ('version', '2.9.0'),
                 ('index_digest', 'sha256:bad'), ('upstream_tag_revision', '*'),
                 ('compressed_bytes', True), ('production_promotion', 'Passed')]
        for key, value in cases:
            bad = self.pin | {key: value}
            with self.subTest(key=key), patch.object(target, 'load', side_effect=[bad,
                    {'external_secrets_operator': {'version': '2.8.0'}}]), self.assertRaises(ValueError):
                target.pin()
        for key, value in [('url', 'https://other/chart.tgz'), ('sha256', '*'), ('version', '2.7.0')]:
            bad = copy.deepcopy(self.pin)
            bad['chart'][key] = value
            with self.subTest(chart=key), patch.object(target, 'load', side_effect=[bad,
                    {'external_secrets_operator': {'version': '2.8.0'}}]), self.assertRaises(ValueError):
                target.pin()

    def test_scan_rejects_wrong_digest_platform_empty_inventory_and_stale_database(self):
        original = copy.deepcopy(self.files)
        for key, value in [('manifestDigest', 'sha256:' + '0' * 64),
                           ('architecture', 'arm64'), ('repoDigests', [])]:
            self.files = copy.deepcopy(original)
            self.files['syft.json']['source']['metadata'][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.validate()
        self.files = copy.deepcopy(original)
        self.files['database.json']['built'] = (self.now - timedelta(days=6)).isoformat()
        with self.assertRaises(ValueError):
            self.validate()
        self.files = copy.deepcopy(original)
        self.files['cyclonedx.json']['components'] = []
        with self.assertRaises(ValueError):
            self.validate()

    def test_high_critical_and_unknown_severity_fail_closed(self):
        for severity in ('High', 'Critical', 'new-schema'):
            self.files['grype.json']['matches'] = [{'vulnerability': {'severity': severity}}]
            with self.subTest(severity=severity), self.assertRaises(ValueError):
                self.validate()
        with self.assertRaises(ValueError):
            target.validate(Path('evidence'), 'main', self.now)

    def test_required_ci_has_no_credentials_signing_or_gate_bypass(self):
        workflow = (target.ROOT / '.github/workflows/repository-baseline.yml').read_text()
        job = workflow.split('  eso-artifact:\n')[1].split('  mesh-artifact:\n')[0]
        for required in ('--fail-on high', '--from registry --platform linux/amd64',
                         'sha256sum --check --strict', 'if: ${{ always() }}'):
            self.assertIn(required, job)
        for forbidden in ('secrets.', 'id-token:', 'packages: write', 'continue-on-error',
                          '--only-fixed', 'cosign sign', 'kubectl'):
            self.assertNotIn(forbidden, job)
        self.assertIn('- eso-artifact', workflow)
        self.assertIn('if [ "${ESO_ARTIFACT_RESULT}" !=', workflow)


if __name__ == '__main__':
    unittest.main()
