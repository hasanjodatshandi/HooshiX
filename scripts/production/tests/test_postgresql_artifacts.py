"""PostgreSQL artifact gates never substitute for installation evidence."""
import copy
import json
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import publish_mesh_candidate as scanner
import verify_postgresql_artifacts as target


class PostgreSQLArtifactTest(unittest.TestCase):
    def setUp(self):
        self.selected = target.pins()
        self.now = datetime(2026, 10, 12, tzinfo=timezone.utc)
        self.component = 'operator'
        self.files = self.evidence(self.component)

    def evidence(self, component):
        pin = self.selected['components'][component]
        return {'syft.json': {'source': {'type': 'image', 'metadata': {
            'manifestDigest': pin['image'].split('@')[1], 'os': 'linux',
            'architecture': 'amd64', 'repoDigests': [pin['image']],
            'labels': pin['image_labels']}}, 'artifacts': [{}]},
            'cyclonedx.json': {'bomFormat': 'CycloneDX', 'components': [{}]},
            'grype.json': {'matches': []},
            'database.json': {'valid': True, 'schemaVersion': '6', 'built': self.now.isoformat()}}

    def validate(self):
        with patch.object(target, 'pins', return_value=self.selected), \
                patch.object(target, 'load', side_effect=lambda p: self.files[p.name]), \
                patch.object(scanner, 'load', side_effect=lambda p: self.files[p.name]), \
                patch.object(Path, 'read_bytes', return_value=b'public'):
            return target.validate(Path('evidence'), self.component, 'a' * 40, self.now)

    def test_four_distinct_pins_and_success_never_claim_publication_or_installation(self):
        self.assertEqual(set(target.REPOSITORIES), set(self.selected['components']))
        for component in target.REPOSITORIES:
            self.component, self.files = component, self.evidence(component)
            result = self.validate()
            self.assertEqual('Passed', result['scan'])
            self.assertEqual(component, result['component'])
            self.assertEqual(432000, result['maximum_database_age_seconds'])
            for field in ('signature_provenance', 'upstream_build_provenance',
                          'staging', 'deployment', 'production_promotion'):
                self.assertEqual('Not verified', result[field])

    def test_pin_rejects_foreign_tag_missing_component_platform_and_approval(self):
        policy = json.loads((target.ROOT / 'infrastructure/production/data/data-policy.json').read_text())
        cases = []
        for key, value in [('platform', 'linux/arm64'), ('production_promotion', 'Passed')]:
            cases.append(self.selected | {key: value})
        missing = copy.deepcopy(self.selected)
        del missing['components']['barman-sidecar']
        cases.append(missing)
        for key, value in [('image', 'ghcr.io/other/cnpg@sha256:' + 'a' * 64),
                ('image', 'ghcr.io/cloudnative-pg/cloudnative-pg:latest'),
                ('index_digest', 'sha256:bad'), ('compressed_bytes', True),
                ('version', '1.30.1'), ('source_revision', 'main'), ('image_labels', {})]:
            selected = copy.deepcopy(self.selected)
            selected['components']['operator'][key] = value
            cases.append(selected)
        for key, value in [('url', 'https://attacker/manifest.yaml'), ('sha256', '*')]:
            selected = copy.deepcopy(self.selected)
            selected['components']['operator']['manifest'][key] = value
            cases.append(selected)
        for selected in cases:
            with self.subTest(selected=selected), \
                    patch.object(target, 'load', side_effect=[selected, policy]), self.assertRaises(ValueError):
                target.pins()
        policy['postgresql']['version'] = '18.5'
        with patch.object(target, 'load', side_effect=[self.selected, policy]), self.assertRaises(ValueError):
            target.pins()

    def test_wrong_digest_architecture_repository_labels_empty_and_stale_fail_closed(self):
        original = copy.deepcopy(self.files)
        for key, value in [('manifestDigest', 'sha256:' + '0' * 64),
                ('architecture', 'arm64'), ('repoDigests', []), ('labels', {})]:
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

    def test_high_critical_and_invalid_severity_fail_closed(self):
        for severity in ('High', 'Critical', 'unknown-schema'):
            self.files['grype.json']['matches'] = [{'vulnerability': {'severity': severity}}]
            with self.subTest(severity=severity), self.assertRaises(ValueError):
                self.validate()

    def test_invalid_component_or_revision_cannot_launch_tools_or_create_directory(self):
        with tempfile.TemporaryDirectory() as parent:
            directory = Path(parent) / 'evidence'
            with patch.object(target.subprocess, 'run') as native:
                for component, revision in [('other', 'a' * 40), ('operator', 'main')]:
                    with self.subTest(component=component), self.assertRaises(ValueError):
                        target.scan_candidate(directory, component, revision)
                native.assert_not_called()
            self.assertFalse(directory.exists())

    def test_existing_evidence_or_failed_scanner_cannot_reuse_success(self):
        with tempfile.TemporaryDirectory() as parent:
            directory = Path(parent) / 'evidence'
            directory.mkdir()
            with patch.object(target.subprocess, 'run') as native, self.assertRaises(FileExistsError):
                target.scan_candidate(directory, 'operator', 'a' * 40)
            native.assert_not_called()
            directory = Path(parent) / 'failed'
            with patch.object(target.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, ['syft'])), \
                    self.assertRaises(subprocess.CalledProcessError):
                target.scan_candidate(directory, 'operator', 'a' * 40)
            self.assertFalse((directory / 'receipt.json').exists())

    def test_credential_free_workflow_retains_gates_and_all_components(self):
        workflow = (target.ROOT / '.github/workflows/postgresql-artifacts.yml').read_text()
        for required in ('permissions:\n  contents: read', 'persist-credentials: false',
                'fail-fast: false', 'max-parallel: 2', 'if: ${{ always() }}',
                '--scan', '120h', 'if-no-files-found: error'):
            self.assertIn(required, workflow)
        for component in target.REPOSITORIES:
            self.assertIn(component, workflow)
        for forbidden in ('secrets.', 'id-token:', 'packages: write', 'continue-on-error',
                '--only-fixed', 'cosign sign', 'kubectl', 'pull_request_target'):
            self.assertNotIn(forbidden, workflow)


if __name__ == '__main__':
    unittest.main()
