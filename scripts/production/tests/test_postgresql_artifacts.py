"""PostgreSQL artifact gates never substitute for installation evidence."""
import copy
import json
import os
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
            'manifestDigest': pin['image'].split('@')[1], 'imageID': 'sha256:' + 'a' * 64, 'os': 'linux',
            'architecture': 'amd64', 'repoDigests': [pin['image']],
            'labels': pin['image_labels']}}, 'artifacts': [{}], 'descriptor': {
                'name': 'syft', 'version': '1.51.0', 'configuration': {
                'search': {'scope': 'squashed'}, 'catalogers': {
                'requested': {'default': ['image', 'file']}, 'used': [
                    'go-module-binary-cataloger', 'binary-classifier-cataloger',
                    'python-installed-package-cataloger', 'dpkg-db-cataloger',
                    'rpm-db-cataloger', 'apk-db-cataloger', 'elf-binary-package-cataloger']}}}},
            'cyclonedx.json': {'bomFormat': 'CycloneDX', 'components': [{}]},
            'grype.json': {'source': {'target': {'imageID': 'sha256:' + 'a' * 64}}, 'matches': []},
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
                ('version', '1.30.2'), ('source_revision', 'main'), ('image_labels', {})]:
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

    def test_suppressed_high_critical_or_invalid_schema_cannot_pass(self):
        for ignored in ([{'vulnerability': {'severity': 'Critical'}}],
                        [{'vulnerability': {'severity': 'High'}}],
                        [{'vulnerability': {'severity': 'invalid'}}], None, {}):
            self.files['grype.json']['ignoredMatches'] = ignored
            with self.subTest(ignored=ignored), self.assertRaises(ValueError):
                self.validate()

    def test_excluded_or_restricted_inventory_cannot_pass(self):
        original = copy.deepcopy(self.files)
        for mutate in (
                lambda c: c.update(exclude=['/venv/**']),
                lambda c: c['catalogers']['requested'].update(select=['go-module-binary-cataloger']),
                lambda c: c['catalogers'].update(used=['go-module-binary-cataloger']),
                lambda c: c['search'].update(scope='unknown')):
            self.files = copy.deepcopy(original)
            mutate(self.files['syft.json']['descriptor']['configuration'])
            with self.assertRaises(ValueError): self.validate()

    def test_equivalent_default_cataloger_order_passes_without_weakening_selection(self):
        self.files['syft.json']['descriptor']['configuration']['catalogers']['requested']['default'] = ['file', 'image']
        self.assertEqual('Passed', self.validate()['scan'])

    def test_foreign_scan_configuration_cannot_pass_matching_upstream_manifest(self):
        self.files['grype.json']['source']['target']['imageID'] = 'sha256:' + 'b' * 64
        with self.assertRaises(ValueError): self.validate()

    def test_live_scan_isolates_config_environment_and_preserves_threshold_failure(self):
        with tempfile.TemporaryDirectory() as parent:
            directory = Path(parent)
            files = self.evidence('operator')
            def execute(argv, **kwargs):
                environment = kwargs['env']
                self.assertNotIn('SYFT_EXCLUDE', environment)
                self.assertNotIn('SYFT_DEFAULT_CATALOGERS', environment)
                self.assertNotIn('GRYPE_IGNORE', environment)
                self.assertNotIn('DOCKER_CONTEXT', environment)
                self.assertEqual('unix:///captured.sock', environment['DOCKER_HOST'])
                if argv[1] == 'version':
                    return subprocess.CompletedProcess(argv, 0, stdout='1.51.0' if argv[0] == 'syft' else '0.117.0')
                self.assertIn('--config', argv)
                if argv[:2] == ['grype', 'db']:
                    return subprocess.CompletedProcess(argv, 0, stdout=json.dumps(files['database.json']))
                if argv[:2] == ['syft', 'scan']:
                    self.assertEqual(2, argv.count('--override-default-catalogers'))
                    (directory / 'syft.json').write_text(json.dumps(files['syft.json']))
                    return subprocess.CompletedProcess(argv, 0)
                self.assertIn('--fail-on', argv)
                return subprocess.CompletedProcess(argv, 2, stdout=json.dumps({'matches': [
                    {'vulnerability': {'severity': 'Critical'}}]}))
            with patch.dict(os.environ, {'SYFT_EXCLUDE': '/venv/**', 'SYFT_DEFAULT_CATALOGERS': 'go',
                                          'GRYPE_IGNORE': 'critical', 'DOCKER_CONTEXT': 'remote',
                                          'DOCKER_HOST': 'tcp://remote:2375'}), \
                    patch.object(target.subprocess, 'run', side_effect=execute), self.assertRaises(subprocess.CalledProcessError):
                target.scan_inventory(directory, 'reviewed-image', 'docker', docker_host='unix:///captured.sock')
            self.assertEqual('Critical', json.loads((directory / 'grype.json').read_text())['matches'][0]['vulnerability']['severity'])
            self.assertFalse((directory / 'receipt.json').exists())

    def test_remote_or_registry_endpoint_override_cannot_launch_scanner(self):
        with tempfile.TemporaryDirectory() as parent, patch.object(target.subprocess, 'run') as native:
            for source, endpoint in [('docker', 'tcp://remote:2375'), ('registry', 'unix:///captured.sock')]:
                with self.subTest(source=source), self.assertRaises(ValueError):
                    target.scan_inventory(Path(parent), 'reviewed-image', source, docker_host=endpoint)
            native.assert_not_called()
            self.assertFalse((Path(parent) / 'scanner-config.json').exists())

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
                'build_postgresql_candidates.py', 'timeout 180 grype db update', '120h', 'if-no-files-found: error'):
            self.assertIn(required, workflow)
        for component in target.REPOSITORIES:
            self.assertIn(component, workflow)
        for forbidden in ('secrets.', 'id-token:', 'packages: write', 'continue-on-error',
                '--only-fixed', 'cosign sign', 'kubectl', 'pull_request_target'):
            self.assertNotIn(forbidden, workflow)


if __name__ == '__main__':
    unittest.main()
