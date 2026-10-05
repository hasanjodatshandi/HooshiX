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

    def test_public_package_bad_digest_or_high_critical_vulnerabilities_never_sign(self):
        for index, (visibility, severity, digest) in enumerate((('public', None, False),
                ('internal', None, False), ('private', 'High', False), ('private', 'Critical', False),
                ('private', 'Unrecognized', False), ('private', None, True))):
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

    def test_fixed_images_and_protected_manual_workflow_only(self):
        selected = target.targets()
        self.assertEqual(set(target.COMPONENTS), set(selected))
        for value in selected.values():
            self.assertTrue(value['upstream'].startswith('docker.io/istio/'))
            self.assertEqual(value['upstream'].split('@')[1], value['image'].split('@')[1])
        workflow = (target.ROOT / '.github/workflows/production-release.yml').read_text().split('  openbao-candidate:\n')[1]
        for expected in ('mesh-candidate', 'environment: production-release', 'packages: write',
                         '--event push --status success', 'publish_mesh_candidate.py', 'if: always()', '--password-stdin'):
            self.assertIn(expected, workflow)
        for forbidden in ('secrets.', 'kubectl', 'KUBECONFIG', 'continue-on-error', 'pull_request_target'):
            self.assertNotIn(forbidden, workflow)


if __name__ == '__main__':
    unittest.main()
