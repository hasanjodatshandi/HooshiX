"""Fixed-source candidates retain every executable and the final vulnerability gate."""
import copy
import io
from contextlib import redirect_stdout
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
import warnings
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
            'org.opencontainers.image.source': 'https://github.com/hasanjodatshandi/HooshiX',
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
                          (self.selected['runtime_packages'] | self.selected['sidecar_packages'] |
                           {'brotli-libs': target.BROTLI_VERSION} |
                           ({'libcurl': target.LIBCURL_VERSION} if component == 'postgresql' else {})).items()] +
                         [{'name': 'barman', 'version': '3.20.1', 'type': 'python'},
                          {'name': 'certifi', 'version': target.CERTIFI_VERSION, 'type': 'python'},
                          {'name': 'postgresql', 'version': '18.6', 'type': 'binary'}]},
            'grype.json': {'source': {'target': {'imageID': self.image, 'userInput': self.image}}, 'matches': []},
            'cyclonedx.json': {'bomFormat': 'CycloneDX', 'components': [{}]},
            'database.json': {'valid': True, 'schemaVersion': '6', 'built': self.now.isoformat()}}

    def validate(self, component, evidence):
        with patch.object(target, 'load', side_effect=lambda p:
                self.selected if p == target.RECIPE else evidence[p.name]):
            # pins have their own loader; no simulated mutation of reviewed catalog.
            return target.validate_local(Path('evidence'), component, self.image, self.now)

    def test_fixed_python_recipe_preserves_original_signed_inputs(self):
        self.assertEqual('84494b7f034e89f47a5f0a88aaf0d053cd7c587e98010923b389781b8f61c612',
                         hashlib.sha256(target.RECIPE.read_bytes()).hexdigest())
        selected = target.recipe(target.CANDIDATE_RECIPE)
        self.assertEqual('3.14.10', selected['python_version'])
        self.assertEqual('3.24.3', selected['sidecar_alpine_version'])
        self.assertEqual('3.24.2', selected['alpine_version'])
        changed = {key for key in set(selected) | set(self.selected)
                   if selected.get(key) != self.selected.get(key)}
        self.assertEqual({'python_version', 'sidecar_runtime', 'sidecar_alpine_version'}, changed)
        with self.assertRaises(ValueError):
            target.recipe(Path('/arbitrary-recipe.json'))
        with self.assertRaises(ValueError):
            target.dockerfile('barman-sidecar', selected)
        dockerfile = target.dockerfile('barman-sidecar', selected, target.CANDIDATE_RECIPE)
        self.assertIn(selected['sidecar_runtime'], dockerfile)
        self.assertIn(hashlib.sha256(target.CANDIDATE_RECIPE.read_bytes()).hexdigest(), dockerfile)
        self.assertIn('update-ca-certificates --fresh', dockerfile)

    def test_public_cli_always_builds_fixed_candidate_without_an_optional_flag(self):
        with patch.object(sys, 'argv', ['build', '--component', 'barman-sidecar', '--directory', 'public-evidence']), \
                patch.object(target, 'build', return_value={'scan': 'Passed'}) as build, \
                redirect_stdout(io.StringIO()):
            target.main()
        build.assert_called_once_with('barman-sidecar', Path('public-evidence'), False,
                                      recipe_path=target.CANDIDATE_RECIPE)

    def test_fixed_candidate_native_smoke_rejects_previous_python(self):
        with patch.object(target, 'run', return_value='3.14.8'), self.assertRaises(ValueError):
            target.native_entrypoint('barman-sidecar', self.image, 'unix:///captured.sock',
                                     recipe_path=target.CANDIDATE_RECIPE)
        with patch.object(target, 'run', return_value='3.14.10') as native:
            target.native_entrypoint('barman-sidecar', self.image, 'unix:///captured.sock',
                                     recipe_path=target.CANDIDATE_RECIPE)
        self.assertTrue(any('barman-cloud-wal-restore' in ' '.join(c.args[0]) for c in native.call_args_list))

    def test_fixed_candidate_inventory_rejects_missing_old_or_mixed_interpreters(self):
        self.selected = target.recipe(target.CANDIDATE_RECIPE)
        original = self.evidence('barman-sidecar')
        original['syft.json']['source']['metadata']['labels']['io.hooshix.recipe.sha256'] = (
            hashlib.sha256(target.CANDIDATE_RECIPE.read_bytes()).hexdigest())
        original['syft.json']['distro']['versionID'] = '3.24.3'
        for versions in (['3.14.10'], [], ['3.14.8'], ['3.14.10', '3.14.8']):
            evidence = copy.deepcopy(original)
            evidence['syft.json']['artifacts'] += [{'name': 'python', 'version': v, 'type': 'binary'}
                                                   for v in versions]
            with patch.object(target, 'load', side_effect=lambda path:
                self.selected if path == target.CANDIDATE_RECIPE else evidence[path.name]):
                if versions == ['3.14.10']:
                    target.validate_local(Path('evidence'), 'barman-sidecar', self.image, self.now,
                                          recipe_path=target.CANDIDATE_RECIPE)
                else:
                    with self.assertRaises(ValueError):
                        target.validate_local(Path('evidence'), 'barman-sidecar', self.image, self.now,
                                              recipe_path=target.CANDIDATE_RECIPE)

    def test_all_candidates_remain_blocked_after_clean_scan(self):
        for component in target.COMPONENTS:
            receipt = self.validate(component, self.evidence(component))
            self.assertEqual('Passed', receipt['scan'])
            for key in ('deployment', 'staging', 'signature_provenance', 'production_promotion'):
                self.assertEqual('Not verified', receipt[key])

    def test_shared_native_smoke_retains_restricted_fixed_engine_execution(self):
        for component in target.COMPONENTS:
            with patch.object(target, 'run', return_value='3.14.8') as run:
                target.native_entrypoint(component, self.image, 'unix:///captured.sock')
            calls = [call.args[0] for call in run.call_args_list]
            self.assertEqual(8 if component == 'barman-sidecar' else 1, len(calls))
            for argv in calls:
                self.assertEqual(['docker', '--host', 'unix:///captured.sock', 'run', '--rm'], argv[:5])
                self.assertIn(self.image, argv)
                for flag in ('--read-only', '--cap-drop', 'ALL', '--network', 'none',
                             '--security-opt', 'no-new-privileges', '--memory', '--cpus', '--pids-limit'):
                    self.assertIn(flag, argv)
                self.assertNotIn('--publish', argv)
                self.assertNotIn('--privileged', argv)
            if component == 'barman-sidecar':
                self.assertEqual(4, sum(any(value.startswith('/venv/bin/barman-cloud-') for value in argv)
                                        for argv in calls))

    def test_shared_native_smoke_rejects_mutable_or_remote_execution_and_wrong_python(self):
        for component, image, endpoint in (('unknown', self.image, 'unix:///captured.sock'),
                ('postgresql', 'postgres:latest', 'unix:///captured.sock'),
                ('operator', self.image, 'tcp://remote.invalid:2375')):
            with patch.object(target, 'run') as run, self.assertRaises(ValueError):
                target.native_entrypoint(component, image, endpoint)
            run.assert_not_called()
        with patch.object(target, 'run', return_value='3.13.5'), self.assertRaises(ValueError):
            target.native_entrypoint('barman-sidecar', self.image, 'unix:///captured.sock')

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
        self.assertIn(target.CERTIFI_VERSION, sidecar)
        for digest in target.CERTIFI_HASHES: self.assertIn(digest, sidecar)
        self.assertIn('update-ca-certificates --fresh', sidecar)
        self.assertIn('emSign_ECC_Root_CA_-_G3.crt', sidecar)
        self.assertNotIn(target.pins()['components']['barman-sidecar']['image'], sidecar)
        postgresql = target.dockerfile('postgresql', self.selected)
        self.assertIn('FROM ' + self.selected['postgresql_runtime'], postgresql)
        self.assertIn('USER 26:26', postgresql)
        self.assertIn('adduser -D -H -u 26', postgresql)
        self.assertIn('libssl3=3.5.9-r0', postgresql)
        for generated in (postgresql, sidecar):
            self.assertIn('brotli-libs=1.2.1-r0', generated)
        self.assertIn('libcurl=8.23.0-r0', postgresql)
        self.assertNotIn('libcurl=', sidecar)
        self.assertIn('patched-upstream-runtime-build', postgresql)
        for generated in (operator, plugin, sidecar):
            self.assertIn('go mod verify', generated)
            self.assertIn('GOTOOLCHAIN=local', generated)
            self.assertIn('sha256sum -c -', generated)
            self.assertIn('patched-upstream-source-build', generated)

    def test_certifi_patch_preserves_other_pins_and_rejects_changed_or_duplicate_input(self):
        old = ('certifi==2026.7.22 \\\n'
            '    --hash=sha256:62f22742b58a1a33014a2b6b706588a8d7e2a88ae7bd1a6ebe8c992928483775 \\\n'
            '    --hash=sha256:741e2c3b351ddf169a738da9f2c048608ff7f2c5cc02f1ebc6b118bb090d5d55\n')
        prefix, suffix = '# synthetic lock\nbarman==3.20.1\n', 'requests==2.34.2\n'
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'requirements.txt'
            for body in (prefix + old + suffix, prefix + suffix, prefix + old + old + suffix,
                         prefix + old.replace('62f227', '000000') + suffix):
                path.write_text(body)
                result = subprocess.run([sys.executable, '-I', '-c', target.certifi_lock_patch(), str(path)],
                    capture_output=True, timeout=10)
                if body == prefix + old + suffix:
                    self.assertEqual(0, result.returncode)
                    fixed = path.read_text()
                    self.assertTrue(fixed.startswith(prefix) and fixed.endswith(suffix))
                    self.assertIn('certifi==' + target.CERTIFI_VERSION, fixed)
                    for digest in target.CERTIFI_HASHES: self.assertIn('--hash=sha256:' + digest, fixed)
                    self.assertNotIn('2026.7.22', fixed)
                else:
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual(body, path.read_text())

    def test_missing_old_or_additional_vulnerable_certifi_copy_rejected(self):
        for mode in ('missing', 'old', 'duplicate-old'):
            evidence = self.evidence('barman-sidecar')
            artifacts = evidence['syft.json']['artifacts']
            certifi = next(item for item in artifacts if item['name'] == 'certifi')
            if mode == 'missing': artifacts.remove(certifi)
            if mode == 'old': certifi['version'] = '2026.7.22'
            if mode == 'duplicate-old': artifacts.append(certifi | {'version': '2026.7.22'})
            with self.subTest(mode=mode), self.assertRaises(ValueError): self.validate('barman-sidecar', evidence)

    def test_generated_trust_programs_compile_without_python_escape_warnings(self):
        with warnings.catch_warnings():
            warnings.simplefilter('error', SyntaxWarning)
            for program in (target.certifi_lock_patch(), target.trust_material_patch(), target.trust_material_check()):
                compile(program, 'reviewed-trust-program', 'exec')

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

    def test_brotli_fixed_package_is_required_even_without_a_scanner_finding(self):
        for component in ('postgresql', 'barman-sidecar'):
            for change in ('missing', 'old', 'duplicate'):
                evidence = self.evidence(component)
                artifacts = evidence['syft.json']['artifacts']
                package = next(item for item in artifacts if item['name'] == 'brotli-libs')
                if change == 'missing': artifacts.remove(package)
                if change == 'old': package['version'] = '1.2.0-r1'
                if change == 'duplicate': artifacts.append(package | {'version': '1.2.0-r1'})
                with self.subTest(component=component, change=change), self.assertRaises(ValueError):
                    self.validate(component, evidence)

    def test_libcurl_fixed_package_is_required_even_without_a_scanner_finding(self):
        for change in ('missing', 'old', 'duplicate'):
            evidence = self.evidence('postgresql')
            artifacts = evidence['syft.json']['artifacts']
            artifacts[:] = [item for item in artifacts if item['name'] != 'libcurl']
            if change != 'missing':
                artifacts.append({'name': 'libcurl', 'version': '8.22.0-r0', 'type': 'apk'})
            if change == 'duplicate':
                artifacts.append({'name': 'libcurl', 'version': '8.23.0-r0', 'type': 'apk'})
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.validate('postgresql', evidence)

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
