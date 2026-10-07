"""Exercise public mesh publication gates without credentials or network."""
import json
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import publish_mesh_candidate as target
from test_openbao_publication import envelope


class MeshPublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name) / 'evidence'
        self.env = {'GITHUB_ACTIONS': 'true', 'GITHUB_REF': 'refs/heads/main',
                    'GITHUB_REPOSITORY': 'hasanjodatshandi/HooshiX',
                    'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_SHA': 'a' * 40,
                    'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '2',
                    'GITHUB_WORKFLOW_REF': target.EXPECTED_CERTIFICATE_IDENTITY.removeprefix('https://github.com/')}
        self.calls = []
        self.visibility, self.severity, self.wrong_signer, self.bad_digest = 'private', None, False, False
        self.severity_component = None

    def native(self, argv, timeout=180):
        self.calls.append(argv)
        if argv[1] == 'version':
            return {'syft': '1.51.0', 'grype': '0.117.0', 'cosign': 'v3.0.6'}[argv[0]]
        if argv[:2] == ['gh', 'api']:
            return self.visibility
        if argv[:3] == ['grype', 'db', 'status']:
            return json.dumps({'valid': True, 'schemaVersion': '6', 'built': datetime.now(timezone.utc).isoformat()})
        if argv[:2] == ['syft', 'scan']:
            folder = Path(next(value.removeprefix('syft-json=') for value in argv if value.startswith('syft-json='))).parent
            image = argv[2]
            metadata = {'manifestDigest': 'sha256:' + '0' * 64 if self.bad_digest else image.split('@')[1],
                        'os': 'linux', 'architecture': 'amd64', 'repoDigests': [image]}
            (folder / 'syft.json').write_text(json.dumps({'source': {'type': 'image', 'metadata': metadata}, 'artifacts': [{}]}))
            (folder / 'cyclonedx.json').write_text(json.dumps({'bomFormat': 'CycloneDX', 'components': [{}]}))
        if argv[0] == 'grype' and argv[1].startswith('sbom:'):
            component = Path(argv[1].removeprefix('sbom:')).parent.name
            severity = self.severity if self.severity_component in (None, component) else None
            return json.dumps({'matches': [] if severity is None else [{'vulnerability': {'severity': severity}}]})
        if argv[:2] == ['cosign', 'verify-attestation']:
            component = next(key for key, repo in target.REPOSITORIES.items() if argv[-1].startswith(repo))
            folder = self.directory / component
            filename, uri = ('import-provenance.json', 'https://slsa.dev/provenance/v1') if 'slsaprovenance1' in argv else ('cyclonedx.json', 'https://cyclonedx.org/bom')
            return envelope(json.loads((folder / filename).read_text()), uri, argv[-1].split('@sha256:')[1])
        if argv[:2] == ['cosign', 'verify'] and any(value.endswith('.wrong') for value in argv):
            if not self.wrong_signer:
                raise subprocess.CalledProcessError(1, argv)
        return ''

    def publish(self):
        with patch.object(target, 'run', side_effect=self.native):
            return target.publish(self.directory, self.env)

    def test_three_private_same_digest_imports_all_scanned_before_any_signing(self):
        result = self.publish()
        self.assertEqual('Passed', result['publication'])
        self.assertEqual(set(target.COMPONENTS), set(result['components']))
        for key in ('deployment', 'runtime_admission', 'staging', 'production_promotion', 'upstream_build_provenance'):
            self.assertEqual('Not verified', result[key])
        signs = [index for index, argv in enumerate(self.calls) if argv[:2] == ['cosign', 'sign']]
        scans = [index for index, argv in enumerate(self.calls) if argv[0] == 'grype' and argv[1].startswith('sbom:')]
        self.assertEqual(3, len(signs))
        self.assertLess(max(scans), min(signs))
        self.assertEqual(1, self.calls.count(['grype', 'db', 'update']))
        for argv in self.calls:
            if argv[:2] == ['cosign', 'copy']:
                self.assertNotIn('-f', argv)
                self.assertNotIn('--platform', argv)
                self.assertTrue(argv[-1].endswith('-123-2'))
        self.assertEqual(0o700, self.directory.stat().st_mode & 0o777)

    def test_docker_hub_canonical_alias_preserves_exact_repository_and_digest(self):
        self.publish()
        folder = self.directory / 'istiod'
        path = folder / 'syft.json'
        sbom = json.loads(path.read_text())
        image = target.targets()['istiod']['upstream']
        sbom['source']['metadata']['repoDigests'] = ['index.' + image]
        path.write_text(json.dumps(sbom))
        result = target.validate(folder, image, datetime.now(timezone.utc))
        self.assertEqual('Passed', result['scan'])
        for invalid in ('index.docker.io/other/pilot@' + image.split('@')[1],
                        'index.' + image.replace('sha256:', 'sha256:0'),
                        'attacker.' + image):
            sbom['source']['metadata']['repoDigests'] = [invalid]
            path.write_text(json.dumps(sbom))
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                target.validate(folder, image, datetime.now(timezone.utc))

    def test_docker_hub_alias_cannot_replace_expected_ghcr_import(self):
        self.publish()
        folder = self.directory / 'istiod'
        path = folder / 'syft.json'
        sbom = json.loads(path.read_text())
        image = target.targets()['istiod']['image']
        sbom['source']['metadata']['repoDigests'] = [target.targets()['istiod']['upstream']]
        path.write_text(json.dumps(sbom))
        with self.assertRaises(ValueError):
            target.validate(folder, image, datetime.now(timezone.utc))

    def test_approved_public_imports_record_actual_visibility_and_keep_signing(self):
        self.visibility = 'public'
        result = self.publish()
        for component in result['components'].values():
            self.assertEqual('public', component['registry_visibility'])
            self.assertEqual('Passed', component['signature_provenance'])
            self.assertEqual('Passed', component['wrong_signer'])
        self.assertEqual(3, sum(argv[:2] == ['cosign', 'sign'] for argv in self.calls))

    def test_visibility_exception_cannot_target_openbao_or_application_packages(self):
        with patch.object(target, 'run') as native:
            for component in ('openbao', 'conversation', 'istiod/other', 'other'):
                with self.subTest(component=component), self.assertRaises(ValueError):
                    target.visibility(component)
            native.assert_not_called()
        import publish_openbao_candidate as openbao
        self.assertIn('!= "private"', (target.ROOT / 'scripts/production/publish_openbao_candidate.py').read_text())
        self.assertNotEqual(openbao.REPOSITORY, target.REPOSITORIES['istiod'])

    def test_unapproved_visibility_bad_digest_or_high_critical_vulnerabilities_never_sign(self):
        for index, (visibility, severity, digest) in enumerate((('', None, False),
                ('internal', None, False), ('private', 'High', False), ('private', 'Critical', False),
                ('private', 'Unrecognized', False), ('private', None, True),
                ('public', 'High', False), ('public', 'Critical', False), ('public', None, True))):
            self.directory = Path(self.temp.name) / str(index)
            self.visibility, self.severity, self.bad_digest = visibility, severity, digest
            self.calls = []
            with self.subTest(index=index), self.assertRaises(ValueError):
                self.publish()
            self.assertFalse(any(argv[:2] == ['cosign', 'sign'] for argv in self.calls))
            self.assertFalse((self.directory / 'receipt.json').exists())

    def test_wrong_signer_acceptance_and_nonmain_are_rejected(self):
        self.wrong_signer = True
        with self.assertRaises(ValueError):
            self.publish()
        self.assertFalse((self.directory / 'receipt.json').exists())
        with patch.object(target, 'run') as native, self.assertRaises(ValueError):
            target.publish(self.directory, self.env | {'GITHUB_REF': 'refs/heads/feature'})
        native.assert_not_called()

    def test_last_component_scan_failure_prevents_signing_all_components(self):
        self.severity, self.severity_component = 'High', 'ztunnel'
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual(3, sum(argv[0] == 'grype' and argv[1].startswith('sbom:') for argv in self.calls))
        self.assertFalse(any(argv[:2] == ['cosign', 'sign'] for argv in self.calls))

    def test_visibility_change_during_signing_prevents_success_receipt(self):
        def changed(argv, timeout=180):
            if argv[:2] == ['cosign', 'sign']:
                self.visibility = 'public'
            return self.native(argv, timeout)
        with patch.object(target, 'run', side_effect=changed), self.assertRaises(ValueError):
            target.publish(self.directory, self.env)
        self.assertFalse((self.directory / 'receipt.json').exists())

    def test_stale_database_wrong_architecture_and_empty_sbom_rejected(self):
        self.publish()
        folder = self.directory / 'istiod'
        now = datetime.now(timezone.utc)
        original = (folder / 'database.json').read_text()
        for built in (now + timedelta(seconds=2), now - timedelta(days=6)):
            (folder / 'database.json').write_text(json.dumps({'valid': True, 'schemaVersion': '6', 'built': built.isoformat()}))
            with self.assertRaises(ValueError):
                target.validate(folder, target.targets()['istiod']['image'], now)
        (folder / 'database.json').write_text(original)
        syft = json.loads((folder / 'syft.json').read_text())
        syft['source']['metadata']['architecture'] = 'arm64'
        (folder / 'syft.json').write_text(json.dumps(syft))
        with self.assertRaises(ValueError):
            target.validate(folder, target.targets()['istiod']['image'], datetime.now(timezone.utc))
        syft['source']['metadata']['architecture'] = 'amd64'
        (folder / 'syft.json').write_text(json.dumps(syft))
        (folder / 'cyclonedx.json').write_text(json.dumps({'bomFormat': 'CycloneDX', 'components': []}))
        with self.assertRaises(ValueError):
            target.validate(folder, target.targets()['istiod']['image'], datetime.now(timezone.utc))

    def test_native_failure_never_yields_bundle_receipt(self):
        for index, tool in enumerate(('copy', 'scan', 'sign', 'verify-attestation')):
            self.directory = Path(self.temp.name) / ('failure-' + str(index))
            def failed(argv, timeout=180):
                if argv[1] == tool:
                    raise subprocess.CalledProcessError(1, argv, stderr='synthetic private error')
                return self.native(argv, timeout)
            with patch.object(target, 'run', side_effect=failed), self.assertRaises(subprocess.CalledProcessError):
                target.publish(self.directory, self.env)
            self.assertFalse((self.directory / 'receipt.json').exists())

    def test_threshold_failure_preserves_public_json_and_never_signs(self):
        report = json.dumps({'matches': [{'vulnerability': {'severity': 'High'}}]})
        def failed(argv, timeout=180):
            if argv[0] == 'grype' and argv[1].startswith('sbom:'):
                raise subprocess.CalledProcessError(2, argv, output=report,
                                                    stderr='synthetic private error')
            return self.native(argv, timeout)
        with patch.object(target, 'run', side_effect=failed), self.assertRaises(subprocess.CalledProcessError):
            target.publish(self.directory, self.env)
        self.assertEqual(report, (self.directory / 'istiod/grype.json').read_text())
        self.assertFalse((self.directory / 'receipt.json').exists())
        self.assertFalse(any(argv[:2] == ['cosign', 'sign'] for argv in self.calls))

    def test_threshold_exit_even_with_empty_matches_is_failure(self):
        for index, output in enumerate(('secret-not-json', '[]', '{}',
                                         '{"matches": []}', 'x' * (target.MAX_BYTES + 1))):
            folder = Path(self.temp.name) / str(index)
            folder.mkdir()
            error = subprocess.CalledProcessError(2, ['grype'], output=output, stderr='private')
            with patch.object(target, 'run', side_effect=error), self.assertRaises(subprocess.CalledProcessError):
                target.scan(folder)
            self.assertEqual(output == '{"matches": []}', (folder / 'grype.json').exists())

    def test_tool_failure_never_exports_raw_diagnostics(self):
        error = subprocess.CalledProcessError(1, ['grype'], output='private', stderr='private')
        with patch.object(target, 'run', side_effect=error), self.assertRaises(subprocess.CalledProcessError):
            target.scan(Path(self.temp.name))
        self.assertFalse((Path(self.temp.name) / 'grype.json').exists())

    def test_oversized_success_report_is_rejected(self):
        with patch.object(target, 'run', return_value='x' * (target.MAX_BYTES + 1)), self.assertRaises(ValueError):
            target.scan(Path(self.temp.name))
        self.assertFalse((Path(self.temp.name) / 'grype.json').exists())

    def test_upstream_scan_ci_is_blocking_and_has_no_publication_or_credentials(self):
        workflow = (target.ROOT / '.github/workflows/repository-baseline.yml').read_text()
        job = workflow.split('  mesh-artifact:\n')[1].split('  openbao-kubernetes:\n')[0]
        for expected in ('persist-credentials: false', 'targets().items()', "target['upstream']",
                         'scan(folder)', 'validate(folder', 'if: ${{ always() }}', 'retention-days: 30',
                         'SYFT_GOLANG_CAPTURE_SYMBOLS: all'):
            self.assertIn(expected, job)
        for forbidden in ('secrets.', 'packages: write', 'id-token: write', 'kubectl',
                          'KUBECONFIG', 'continue-on-error', 'cosign sign', 'target[\'image\']'):
            self.assertNotIn(forbidden, job)
        aggregate = workflow.split('  baseline:\n')[1]
        self.assertIn('      - mesh-artifact', aggregate)
        self.assertIn('needs.mesh-artifact.result', aggregate)
        self.assertIn('"${MESH_ARTIFACT_RESULT}" != \'success\'', aggregate)

    def test_fixed_images_and_protected_manual_workflow_only(self):
        selected = target.targets()
        self.assertEqual(set(target.COMPONENTS), set(selected))
        for value in selected.values():
            self.assertTrue(value['upstream'].startswith('docker.io/istio/'))
            self.assertEqual(value['upstream'].split('@')[1], value['image'].split('@')[1])
        workflow = (target.ROOT / '.github/workflows/production-release.yml').read_text().split('  openbao-candidate:\n')[1]
        for expected in ('mesh-candidate', 'environment: production-release', 'packages: write',
                         '--event push --status success', 'publish_mesh_candidate.py', 'if: always()', '--password-stdin',
                         'SYFT_GOLANG_CAPTURE_SYMBOLS: all'):
            self.assertIn(expected, workflow)
        for forbidden in ('secrets.', 'kubectl', 'KUBECONFIG', 'continue-on-error', 'pull_request_target'):
            self.assertNotIn(forbidden, workflow)


if __name__ == '__main__':
    unittest.main()
