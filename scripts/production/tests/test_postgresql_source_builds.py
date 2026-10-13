"""Fixed-source candidates retain every executable and the final vulnerability gate."""
import copy
import hashlib
import json
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_postgresql_candidates as target


class PostgreSQLSourceBuildTest(unittest.TestCase):
    def setUp(self):
        self.selected = target.recipe()
        self.now = datetime(2026, 10, 13, tzinfo=timezone.utc)
        self.image = 'sha256:' + 'a' * 64

    def evidence(self, component):
        source = self.selected['sources'][target.source_key(component)]
        return {'syft.json': {'source': {'type': 'image', 'metadata': {
            'imageID': self.image, 'userInput': self.image, 'os': 'linux', 'architecture': 'amd64', 'labels': {
            'io.hooshix.provenance.kind': target.provenance(component),
            'io.hooshix.source.revision': source['revision'],
            'io.hooshix.recipe.sha256': hashlib.sha256(target.RECIPE.read_bytes()).hexdigest()}}},
            'descriptor': {'name': 'syft', 'version': '1.51.0', 'configuration': {
                'search': {'scope': 'squashed'}, 'catalogers': {
                'requested': {'default': ['image', 'file']}, 'used': [
                    'go-module-binary-cataloger', 'binary-classifier-cataloger',
                    'python-installed-package-cataloger', 'dpkg-db-cataloger',
                    'rpm-db-cataloger', 'apk-db-cataloger', 'elf-binary-package-cataloger']}}},
            'distro': {'id': 'alpine', 'versionID': '3.24.2'},
            'artifacts': ([{'name': 'stdlib', 'version': 'go1.26.9', 'locations': [{'path': destination}]}
                          for destination in target.BINARIES.get(component, {}).values()] +
                         [{'name': 'golang.org/x/net', 'version': 'v0.60.0'}] if component != 'postgresql' else []) +
                         [{'name': name, 'version': version, 'type': 'apk'} for name, version in
                          (self.selected['runtime_packages'] | self.selected['sidecar_packages']).items()] +
                         [{'name': 'barman', 'version': '3.20.1', 'type': 'python'},
                          {'name': 'postgresql', 'version': '18.6', 'type': 'binary'}]},
            'grype.json': {'source': {'target': {'imageID': self.image, 'userInput': self.image}}, 'matches': []},
            'cyclonedx.json': {'bomFormat': 'CycloneDX', 'components': [{}]},
            'database.json': {'valid': True, 'schemaVersion': '6', 'built': self.now.isoformat()}}

    def validate(self, component, evidence):
        with patch.object(target, 'load', side_effect=lambda p:
                self.selected if p == target.RECIPE else evidence[p.name]):
            # pins have their own loader; no simulated mutation of reviewed catalog.
            return target.validate_local(Path('evidence'), component, self.image, self.now)

    def test_all_candidates_remain_blocked_after_clean_scan(self):
        for component in target.COMPONENTS:
            receipt = self.validate(component, self.evidence(component))
            self.assertEqual('Passed', receipt['scan'])
            for key in ('deployment', 'staging', 'signature_provenance', 'production_promotion'):
                self.assertEqual('Not verified', receipt[key])

    def test_both_operator_architectures_rebuilt_and_no_vulnerable_plugin_base(self):
        operator = target.dockerfile('operator', self.selected)
        for arch in ('amd64', 'arm64'):
            self.assertIn('GOARCH=' + arch + ' go build', operator)
            self.assertIn('/operator/manager_' + arch, operator)
        self.assertIn('buildVersion=1.30.1', operator)
        plugin = target.dockerfile('barman-plugin', self.selected)
        self.assertIn('FROM ' + self.selected['plugin_runtime'], plugin)
        self.assertIn('USER 65532:65532', plugin)
        self.assertNotIn('ubi', plugin)
        sidecar = target.dockerfile('barman-sidecar', self.selected)
        self.assertIn('FROM ' + self.selected['sidecar_runtime'], sidecar)
        self.assertIn('--require-hashes', sidecar)
        self.assertIn('pip check', sidecar)
        self.assertNotIn(target.pins()['components']['barman-sidecar']['image'], sidecar)
        postgresql = target.dockerfile('postgresql', self.selected)
        self.assertIn('FROM ' + self.selected['postgresql_runtime'], postgresql)
        self.assertIn('USER 26:26', postgresql)
        self.assertIn('adduser -D -H -u 26', postgresql)
        self.assertIn('libssl3=3.5.9-r0', postgresql)
        self.assertIn('patched-upstream-runtime-build', postgresql)
        for generated in (operator, plugin, sidecar):
            self.assertIn('go mod verify', generated)
            self.assertIn('GOTOOLCHAIN=local', generated)
            self.assertIn('sha256sum -c -', generated)
            self.assertIn('patched-upstream-source-build', generated)

    def test_old_or_missing_embedded_binary_never_passes(self):
        for alternate in ('old-arm64', 'missing-arm64', 'old-extra-binary', 'old-net'):
            evidence = self.evidence('operator')
            artifacts = evidence['syft.json']['artifacts']
            if alternate == 'old-arm64': artifacts[1]['version'] = 'go1.27.1'
            if alternate == 'missing-arm64': del artifacts[1]
            if alternate == 'old-extra-binary': artifacts.append({'name': 'stdlib', 'version': 'go1.26.6', 'locations': []})
            if alternate == 'old-net': next(item for item in artifacts if item['name'] == 'golang.org/x/net')['version'] = 'v0.58.0'
            with self.subTest(alternate=alternate), self.assertRaises(ValueError):
                self.validate('operator', evidence)

    def test_base_package_findings_are_not_suppressed_by_go_fix(self):
        for severity in ('High', 'Critical', 'unknown-schema'):
            evidence = self.evidence('barman-sidecar')
            evidence['grype.json']['matches'] = [{'artifact': {'name': 'libc6'},
                'vulnerability': {'severity': severity, 'fix': {'state': 'not-fixed', 'versions': []}}}]
            with self.subTest(severity=severity), self.assertRaises(ValueError):
                self.validate('barman-sidecar', evidence)

    def test_ignored_critical_and_high_cannot_produce_clean_source_receipt(self):
        for component in target.COMPONENTS:
            for severity in ('High', 'Critical', 'invalid'):
                evidence = self.evidence(component)
                evidence['grype.json']['ignoredMatches'] = [{'vulnerability': {'severity': severity}}]
                with self.subTest(component=component, severity=severity), self.assertRaises(ValueError):
                    self.validate(component, evidence)

    def test_sidecar_inventory_exclusions_or_go_only_cataloging_rejected(self):
        for mutate in (
                lambda c: c.update(exclude=['/venv/**', '/var/lib/dpkg/**']),
                lambda c: c['catalogers'].update(requested={'default': ['go-module-binary-cataloger']}),
                lambda c: c['catalogers'].update(used=['go-module-binary-cataloger'])):
            evidence = self.evidence('barman-sidecar')
            mutate(evidence['syft.json']['descriptor']['configuration'])
            with self.assertRaises(ValueError): self.validate('barman-sidecar', evidence)

    def test_mismatched_reports_provenance_architecture_and_stale_database_rejected(self):
        original = self.evidence('operator')
        for mutate in (
                lambda e: e['grype.json']['source']['target'].update(imageID='sha256:' + 'b' * 64),
                lambda e: e['syft.json']['source']['metadata'].update(userInput='sha256:' + 'b' * 64),
                lambda e: e['grype.json']['source']['target'].update(userInput='sha256:' + 'b' * 64),
                lambda e: e['syft.json']['source']['metadata'].update(architecture='arm64'),
                lambda e: e['syft.json']['source']['metadata']['labels'].clear(),
                lambda e: e['database.json'].update(built=(self.now - timedelta(days=6)).isoformat()),
                lambda e: e['database.json'].update(built=(self.now + timedelta(seconds=1)).isoformat()),
                lambda e: e['cyclonedx.json'].update(components=[])):
            evidence = copy.deepcopy(original)
            mutate(evidence)
            with self.assertRaises(ValueError): self.validate('operator', evidence)

    def test_containerd_engine_index_is_not_mislabeled_as_configuration_digest(self):
        evidence = self.evidence('operator')
        configuration = 'sha256:' + 'c' * 64
        evidence['syft.json']['source']['metadata']['imageID'] = configuration
        evidence['grype.json']['source']['target']['imageID'] = configuration
        result = self.validate('operator', evidence)
        self.assertEqual(self.image, result['local_engine_digest'])
        self.assertEqual(configuration, result['image_config_digest'])

    def test_recipe_rejects_unreviewed_builder_modules_runtime_and_source(self):
        cases = [self.selected | {'builder': 'golang:latest'}, self.selected | {'plugin_runtime': 'scratch'},
                 self.selected | {'go_version': '1.27.1'}, self.selected | {'production_promotion': 'Passed'},
                 self.selected | {'modules': target.MODULES | {'golang.org/x/net': 'v0.58.0'}},
                 self.selected | {'sidecar_runtime': 'python:latest'},
                 self.selected | {'postgresql_runtime': 'postgres:latest'},
                 self.selected | {'python_version': '3.13.5'},
                 self.selected | {'runtime_packages': target.RUNTIME_PACKAGES | {'libssl3': '3.5.8-r0'}}]
        for key, value in (('repository', 'other/repository'), ('revision', 'main'), ('archive_sha256', 'bad')):
            selected = copy.deepcopy(self.selected)
            selected['sources']['operator'][key] = value
            cases.append(selected)
        for selected in cases:
            with patch.object(target, 'load', return_value=selected), self.assertRaises(ValueError):
                target.recipe()

    def test_unknown_component_and_remote_docker_cannot_fetch_source(self):
        with tempfile.TemporaryDirectory() as parent:
            directory = Path(parent) / 'evidence'
            with patch.object(target, 'run', return_value='"tcp://remote:2375"'), \
                    patch.object(target.urllib.request, 'urlopen') as fetch, self.assertRaises(ValueError):
                target.build('operator', directory)
            fetch.assert_not_called()
            self.assertFalse(directory.exists())
            with patch.object(target, 'run') as native, self.assertRaises(ValueError):
                target.build('unknown', directory)
            native.assert_not_called()

    def test_missing_old_or_duplicate_runtime_inventory_is_rejected(self):
        for component in ('postgresql', 'barman-sidecar'):
            for change in ('missing', 'old', 'duplicate', 'wrong-distro', 'old-application'):
                evidence = self.evidence(component)
                artifacts = evidence['syft.json']['artifacts']
                ssl = next(item for item in artifacts if item['name'] == 'libssl3')
                if change == 'missing': artifacts.remove(ssl)
                if change == 'old': ssl['version'] = '3.5.8-r0'
                if change == 'duplicate': artifacts.insert(0, ssl | {'version': '3.5.8-r0'})
                if change == 'wrong-distro': evidence['syft.json']['distro']['id'] = 'debian'
                if change == 'old-application':
                    name = 'postgresql' if component == 'postgresql' else 'barman'
                    artifacts.append({'name': name, 'version': 'old'})
                with self.subTest(component=component, change=change), self.assertRaises(ValueError):
                    self.validate(component, evidence)

    def test_export_pins_endpoint_and_build_identity_and_never_precedes_scan(self):
        payload = b'public-source-fixture'
        selected = copy.deepcopy(self.selected)
        selected['sources']['postgresql']['archive_sha256'] = hashlib.sha256(payload).hexdigest()
        for export, rejected, empty in ((False, False, False), (True, False, False),
                                        (True, True, False), (True, False, True)):
            with self.subTest(export=export, rejected=rejected, empty=empty), tempfile.TemporaryDirectory() as parent:
                directory = Path(parent) / 'candidate'
                scanned = []
                def native(argv, **kwargs):
                    if argv[:3] == ['docker', 'context', 'inspect']:
                        return '"unix:///captured.sock"'
                    self.assertEqual(['docker', '--host', 'unix:///captured.sock'], argv[:3])
                    if argv[3] == 'build':
                        (directory / 'image-id.txt').write_text(self.image)
                    if argv[3:5] == ['image', 'inspect']:
                        self.assertEqual(self.image, argv[-1])
                        return json.dumps({'User': '26:26', 'Entrypoint': ['/usr/local/bin/postgres'], 'Cmd': []})
                    if argv[3:5] == ['image', 'save']:
                        self.assertTrue(scanned)
                        self.assertEqual(self.image, argv[-1])
                        (directory / 'candidate-image.tar').write_bytes(b'' if empty else b'bounded-image')
                    return ''
                with patch.object(target, 'recipe', return_value=selected), \
                        patch.object(target.urllib.request, 'urlopen') as fetch, \
                        patch.object(target, 'run', side_effect=native), \
                        patch.object(target, 'scan_inventory', side_effect=lambda *a, **kw: scanned.append(kw)), \
                        patch.object(target, 'validate_local', side_effect=ValueError('blocked') if rejected else None,
                                     return_value={'scan': 'Passed'}), \
                        patch('test_postgresql_isolation_native.exercise_candidate') as exercise:
                    fetch.return_value.__enter__.return_value.read.return_value = payload
                    if rejected or empty:
                        with self.assertRaises(ValueError): target.build('postgresql', directory, export)
                        self.assertFalse((directory / 'receipt.json').exists())
                        if rejected: exercise.assert_not_called()
                    else:
                        receipt = target.build('postgresql', directory, export)
                        exercise.assert_called_once_with(self.image, docker_host='unix:///captured.sock')
                        self.assertEqual(export, 'image_archive_sha256' in receipt)
                    self.assertEqual([{'docker_host': 'unix:///captured.sock'}], scanned)
                    self.assertEqual(export and not rejected and not empty, (directory / 'candidate-image.tar').exists())


if __name__ == '__main__':
    unittest.main()
