"""Private Redis publication cannot grant deployment or sign an unverified image."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import publish_redis_candidate as target


class RedisPublicationTest(unittest.TestCase):
    def setUp(self):
        self.revision = 'a' * 40
        self.engine, self.config, self.manifest = ['sha256:' + char * 64 for char in 'ecd']
        self.env = {'GITHUB_ACTIONS': 'true', 'GITHUB_REF': 'refs/heads/main',
            'GITHUB_REPOSITORY': 'hasanjodatshandi/HooshiX', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
            'GITHUB_WORKFLOW_REF': target.EXPECTED_CERTIFICATE_IDENTITY.removeprefix('https://github.com/'),
            'GITHUB_SHA': self.revision, 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1',
            'RELEASE_KIND': 'redis-candidate'}

    def execute(self, directory, reject=None):
        steps, commands = [], []

        def resolution(path):
            digest = 'sha256:' + 'f' * 64 if reject == 'manifest' and path.name == 'syft.json' else self.manifest
            path.write_text(json.dumps({'source': {'type': 'image', 'metadata': {
                'imageID': self.engine if reject == 'config' else self.config,
                'manifestDigest': digest, 'os': 'linux', 'architecture': 'amd64',
                'repoDigests': [target.REPOSITORY + '@' + digest]}}}))

        def native(argv, **kwargs):
            commands.append(argv)
            if argv[0] == 'git': return self.revision
            if argv[0] == 'gh':
                return 'public' if reject == 'visibility' or reject == 'visibility-after-sign' and 'sign' in steps else 'private'
            if argv[0] == 'syft': resolution(Path(argv[-1].removeprefix('syft-json=')))
            if argv[:4] == ['docker', '--host', 'unix:///captured.sock', 'push'] and reject == 'push':
                raise subprocess.CalledProcessError(1, argv)
            return ''

        def candidate(folder):
            steps.append('native-build')
            folder.mkdir()
            if reject == 'build-native': raise ValueError('native rejected')
            return {'local_image_id': self.engine, 'image_configuration_digest': self.config,
                    'native': {'tls': 'Passed'}}

        def scan(folder, image, source):
            self.assertEqual('registry', source)
            self.assertEqual(target.REPOSITORY + '@' + self.manifest, image)
            resolution(folder / 'syft.json')

        def checked(folder, now):
            steps.append('registry-scan')
            if reject == 'scan': raise ValueError('scan rejected')
            return {'scan': 'Passed'}

        def signed(folder, image, predicate):
            self.assertEqual(['native-build', 'registry-scan'], steps)
            self.assertEqual(target.REPOSITORY + '@' + self.manifest, image)
            steps.append('sign')
            if reject == 'signature': raise ValueError('signature rejected')

        with patch.object(target, 'run', side_effect=native), \
                patch.object(target, 'local_endpoint', return_value='unix:///captured.sock'), \
                patch.object(target, 'build', side_effect=candidate), \
                patch.object(target, 'scan_inventory', side_effect=scan), \
                patch.object(target, 'validate_candidate_scan', side_effect=checked), \
                patch.object(target, 'sign_and_verify', side_effect=signed):
            if reject:
                with self.assertRaises((ValueError, subprocess.CalledProcessError)):
                    target.publish(directory, self.env)
                self.assertFalse((directory / 'publication.json').exists())
                if reject not in ('signature', 'visibility-after-sign'):
                    self.assertNotIn('sign', steps)
            else:
                result = target.publish(directory, self.env)
                self.assertEqual('private', result['registry_visibility'])
                self.assertEqual('Passed', result['signature_provenance_sbom'])
                self.assertEqual(self.config, result['image_configuration_digest'])
                for key in ('staging', 'runtime_admission', 'deployment'):
                    self.assertEqual('Not run', result[key])
                self.assertEqual('CLOSED', result['application_ingress'])
                self.assertEqual('Not verified', result['production_readiness'])
                self.assertEqual(result, json.loads((directory / 'publication.json').read_text()))
        return commands

    def test_exact_registry_config_and_scan_before_signature_not_engine_or_tag_identity(self):
        with tempfile.TemporaryDirectory() as parent:
            commands = self.execute(Path(parent) / 'attempt')
        tag = next(argv for argv in commands if argv[:4] == ['docker', '--host', 'unix:///captured.sock', 'tag'])
        self.assertEqual(self.engine, tag[4])
        self.assertEqual(target.REPOSITORY + ':candidate-' + self.revision + '-github-123-1', tag[5])
        self.assertEqual(1, sum(argv[:4] == ['docker', '--host', 'unix:///captured.sock', 'push'] for argv in commands))

    def test_partial_failures_never_emit_success_or_sign_unclean_image(self):
        for reject in ('visibility', 'build-native', 'push', 'config', 'manifest', 'scan',
                       'signature', 'visibility-after-sign'):
            with self.subTest(reject=reject), tempfile.TemporaryDirectory() as parent:
                self.execute(Path(parent) / 'attempt', reject)

    def test_protected_main_context_and_matching_checkout_required_before_build(self):
        for key in ('GITHUB_ACTIONS', 'GITHUB_REF', 'GITHUB_REPOSITORY', 'GITHUB_EVENT_NAME',
                    'GITHUB_WORKFLOW_REF', 'GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'RELEASE_KIND'):
            with patch.object(target, 'run', return_value=self.revision), \
                    patch.object(target, 'build') as build, self.assertRaises(ValueError):
                target.publish(Path('unused'), self.env | {key: 'wrong'})
            build.assert_not_called()
        with patch.object(target, 'run', return_value='b' * 40), \
                patch.object(target, 'build') as build, self.assertRaises(ValueError):
            target.publish(Path('unused'), self.env)
        build.assert_not_called()

    def test_existing_directory_cannot_replay_or_overwrite_receipt(self):
        with tempfile.TemporaryDirectory() as parent, \
                patch.object(target, 'run', return_value=self.revision), \
                patch.object(target, 'local_endpoint', return_value='unix:///captured.sock'), \
                patch.object(target, 'build') as build, self.assertRaises(FileExistsError):
            target.publish(Path(parent), self.env)
        build.assert_not_called()

    def test_only_actual_404_can_be_missing_and_only_private_is_accepted(self):
        for code in (404, 401, 403):
            with patch.object(target, 'run', side_effect=subprocess.CalledProcessError(1, ['gh'], stderr=f'(HTTP {code})')):
                if code == 404: self.assertIsNone(target.package_visibility(allow_missing=True))
                else:
                    with self.assertRaises(subprocess.CalledProcessError): target.package_visibility(allow_missing=True)
                with self.assertRaises(subprocess.CalledProcessError): target.package_visibility()
        for visibility in ('public', 'internal', '', 'private\npublic'):
            with patch.object(target, 'run', return_value=visibility), self.assertRaises(ValueError):
                target.package_visibility(allow_missing=True)

    def test_truthful_runtime_repack_provenance_and_fixed_private_destination(self):
        predicate = target.source_predicate(self.revision, 'github:123:1')
        definition = predicate['buildDefinition']
        parameters = definition['externalParameters']
        self.assertEqual('patched-upstream-runtime-build', parameters['operation'])
        self.assertEqual('security-redis', parameters['component'])
        self.assertEqual('8.2.8', parameters['recipe']['version'])
        self.assertEqual(self.revision, parameters['repository_revision'])
        self.assertEqual(4, len(definition['resolvedDependencies']))
        self.assertEqual(64, len(parameters['recipe_sha256']))
        self.assertEqual(64, len(parameters['dockerfile_sha256']))
        self.assertEqual('ghcr.io/hasanjodatshandi/hooshix/platform-security-redis-private', target.REPOSITORY)

    def test_release_dispatch_keeps_existing_signer_privileges_and_no_production_credentials(self):
        root = Path(__file__).resolve().parents[3]
        workflow = (root / '.github/workflows/production-release.yml').read_text()
        for required in ('- redis-candidate', "inputs.release_kind == 'redis-candidate'",
                'redis-candidate)\n              python3 scripts/production/publish_redis_candidate.py',
                'environment: production-release', 'id-token: write', 'packages: write',
                'Require successful baseline for this exact main revision',
                "inputs.release_kind == 'redis-candidate' && 'redis'",
                '!${{ runner.temp }}/redis-release-evidence/**/source.tar.gz'):
            self.assertIn(required, workflow)
        for forbidden in ('continue-on-error', 'KUBECONFIG', 'secrets.PRODUCTION', 'sudo '):
            self.assertNotIn(forbidden, workflow)


if __name__ == '__main__':
    unittest.main()
