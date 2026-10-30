"""Protected whole-set publication must not grant deployment or weaken registry gates."""
import copy
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from contextlib import redirect_stdout
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import publish_postgresql_candidates as target
import test_postgresql_source_builds as fixtures


class PostgreSQLPublicationTest(unittest.TestCase):
    def setUp(self):
        self.revision = 'a' * 40
        self.config = 'sha256:' + 'c' * 64
        self.engine = 'sha256:' + 'e' * 64
        self.env = {'GITHUB_ACTIONS': 'true', 'GITHUB_REF': 'refs/heads/main',
            'GITHUB_REPOSITORY': 'hasanjodatshandi/HooshiX',
            'GITHUB_EVENT_NAME': 'workflow_dispatch',
            'GITHUB_WORKFLOW_REF': target.EXPECTED_CERTIFICATE_IDENTITY.removeprefix('https://github.com/'),
            'GITHUB_SHA': self.revision, 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1',
            'RELEASE_KIND': 'postgresql-candidate'}

    def execute(self, directory, *, reject=None, visibility='private'):
        calls, scans, signatures = [], [], []
        def native(argv, **kwargs):
            calls.append(argv)
            if argv[0] == 'git': return self.revision
            if argv[0] == 'gh': return 'internal' if reject == 'visibility' else visibility
            if argv[0] == 'syft':
                self.write_resolution(Path(argv[-1].removeprefix('syft-json=')), reject)
                return ''
            self.assertEqual(['docker', '--host', 'unix:///captured.sock'], argv[:3])
            if argv[3] == 'tag': self.assertEqual(self.engine, argv[4])
            return ''
        def build(component, folder, **kwargs):
            self.assertEqual({'docker_host': 'unix:///captured.sock', 'recipe_path': target.CANDIDATE_RECIPE}, kwargs)
            folder.mkdir()
            return {'image_config_digest': self.config, 'local_engine_digest': self.engine}
        def scan(folder, image, source):
            scans.append(image)
            self.write_resolution(folder / 'syft.json', reject)
        def validate(folder, component, image, now, config, *, recipe_path):
            self.assertEqual(target.CANDIDATE_RECIPE, recipe_path)
            if reject == 'scan' and component == 'barman-sidecar': raise ValueError('blocked')
            return {'image_config_digest': config, 'scan': 'Passed'}
        def sign(folder, image, predicate):
            self.assertEqual(4, len(scans))
            signatures.append(image)
            if reject == 'signature': raise ValueError('invalid attestation')
        with patch.object(target, 'run', side_effect=native), \
                patch.object(target, 'local_endpoint', return_value='unix:///captured.sock'), \
                patch.object(target, 'build', side_effect=build), \
                patch.object(target, 'scan_inventory', side_effect=scan), \
                patch.object(target, 'validate_inventory', side_effect=validate), \
                patch.object(target, 'sign_and_verify', side_effect=sign):
            if reject:
                with self.assertRaises(ValueError): target.publish(directory, self.env)
                self.assertFalse((directory / 'publication.json').exists())
                if reject != 'signature': self.assertFalse(signatures)
            else:
                result = target.publish(directory, self.env)
                self.assertEqual(4, len(signatures))
                self.assertEqual(set(target.COMPONENTS), set(result['images']))
                self.assertEqual('Passed', result['publication'])
                self.assertEqual({key: visibility for key in target.COMPONENTS}, result['registry_visibility'])
                for name in ('staging', 'deployment', 'runtime_admission', 'production_promotion'):
                    self.assertEqual('Not verified', result[name])
                self.assertEqual(result, json.loads((directory / 'publication.json').read_text()))
        return calls

    def write_resolution(self, path, reject):
        component = path.parent.parent.name
        digest = 'sha256:' + ('f' if reject == 'manifest' and path.name == 'syft.json' else 'd') * 64
        metadata = {'imageID': self.engine if reject == 'config' else self.config,
                    'manifestDigest': digest, 'os': 'linux', 'architecture': 'amd64',
                    'repoDigests': [target.repository(component) + '@' + digest]}
        path.write_text(json.dumps({'source': {'type': 'image', 'metadata': metadata}}))

    def test_set_binds_engine_config_and_registry_without_promotion(self):
        with tempfile.TemporaryDirectory() as parent:
            calls = self.execute(Path(parent) / 'evidence')
        self.assertEqual(4, sum(argv[:4] == ['docker', '--host', 'unix:///captured.sock', 'push'] for argv in calls))

    def test_partial_failures_never_report_success_or_sign_unclean_set(self):
        for reject in ('visibility', 'config', 'manifest', 'scan', 'signature'):
            with self.subTest(reject=reject), tempfile.TemporaryDirectory() as parent:
                self.execute(Path(parent) / 'evidence', reject=reject)

    def test_guards_precede_build_and_do_not_reuse_directory(self):
        for key in ('GITHUB_REF', 'GITHUB_ACTIONS', 'GITHUB_WORKFLOW_REF', 'GITHUB_EVENT_NAME', 'RELEASE_KIND'):
            with tempfile.TemporaryDirectory() as parent:
                directory = Path(parent) / 'evidence'
                with patch.object(target, 'run', return_value=self.revision), \
                        patch.object(target, 'build') as build, self.assertRaises(ValueError):
                    target.publish(directory, self.env | {key: 'wrong'})
                build.assert_not_called()
                self.assertFalse(directory.exists())
        with tempfile.TemporaryDirectory() as parent:
            with patch.object(target, 'run', return_value='b' * 40), \
                    patch.object(target, 'build') as build, self.assertRaises(ValueError):
                target.publish(Path(parent) / 'evidence', self.env)
            build.assert_not_called()
        with tempfile.TemporaryDirectory() as parent:
            with patch.object(target, 'run', return_value=self.revision), \
                    patch.object(target, 'local_endpoint', return_value='unix:///captured.sock'), \
                    patch.object(target, 'build') as build, self.assertRaises(FileExistsError):
                target.publish(Path(parent), self.env)
            build.assert_not_called()

    def test_only_actual_404_permits_new_package(self):
        for status in (404, 401, 403):
            failure = subprocess.CalledProcessError(1, ['gh'], stderr=f'gh: failure (HTTP {status})')
            with patch.object(target, 'run', side_effect=failure):
                if status == 404: target.package_visibility('operator', allow_missing=True)
                else:
                    with self.assertRaises(subprocess.CalledProcessError):
                        target.package_visibility('operator', allow_missing=True)
                with self.assertRaises(subprocess.CalledProcessError): target.package_visibility('operator')

    def test_exact_four_destinations_do_not_adopt_old_operator_package(self):
        self.assertEqual({
            'operator': 'hooshix/platform-postgresql-operator-v2-private',
            'postgresql': 'hooshix/platform-postgresql-postgresql-private',
            'barman-plugin': 'hooshix/platform-postgresql-barman-plugin-private',
            'barman-sidecar': 'hooshix/platform-postgresql-barman-sidecar-private'}, target.PACKAGES)
        guide = (target.RECIPE.parents[3] / 'docs/operations/stage10-production-deployment-guide-fa.md').read_text()
        for package in target.PACKAGES.values():
            self.assertIn('`' + package.removeprefix('hooshix/') + '`', guide)

    def test_visibility_failure_exposes_only_fixed_actionable_metadata(self):
        for value, category in (('internal', 'internal'),
                                ('untrusted-value-do-not-print', 'invalid')):
            output = io.StringIO()
            with patch.object(target, 'run', return_value=value), redirect_stdout(output), \
                    self.assertRaises(ValueError):
                target.package_visibility('operator')
            self.assertEqual('POSTGRESQL_PACKAGE_VISIBILITY=Rejected; component=operator; '
                             f'actual={category}; required=private-or-public\n', output.getvalue())

    def test_owner_approved_public_set_still_requires_all_scans_and_signatures(self):
        with tempfile.TemporaryDirectory() as parent:
            self.execute(Path(parent) / 'evidence', visibility='public')

    def test_exception_never_accepts_application_or_arbitrary_package(self):
        for component in ('application', 'identity-service', 'operator-other', ''):
            with patch.object(target, 'run') as native, self.assertRaises(ValueError):
                target.package_visibility(component, allow_missing=True)
            native.assert_not_called()

    def test_application_release_retains_private_only_gate(self):
        workflow = (target.RECIPE.parents[3] / '.github/workflows/production-release.yml').read_text()
        application_job = workflow.split('  openbao-candidate:')[0]
        self.assertIn('[[ "$visibility" == private ]]', application_job)
        self.assertIn('Seven private packages required', application_job)

    def test_registry_inventory_requires_exact_config_and_reference(self):
        fixture = fixtures.PostgreSQLSourceBuildTest()
        fixture.setUp()
        original = fixture.evidence('operator')
        image = target.repository('operator') + '@sha256:' + 'd' * 64
        original['syft.json']['source']['metadata']['userInput'] = image
        original['grype.json']['source']['target']['userInput'] = image
        for mutation in (None, 'config', 'reference', 'grype'):
            evidence = copy.deepcopy(original)
            if mutation == 'config':
                evidence['syft.json']['source']['metadata']['imageID'] = self.engine
                evidence['grype.json']['source']['target']['imageID'] = self.engine
            if mutation == 'reference': evidence['syft.json']['source']['metadata']['userInput'] = image + '.wrong'
            if mutation == 'grype': evidence['grype.json']['source']['target']['userInput'] = image + '.wrong'
            with patch('build_postgresql_candidates.load', side_effect=lambda p:
                       fixture.selected if p == target.RECIPE else evidence[p.name]):
                if mutation:
                    with self.assertRaises(ValueError):
                        target.validate_inventory(Path('evidence'), 'operator', image, fixture.now, fixture.image)
                else:
                    result = target.validate_inventory(Path('evidence'), 'operator', image, fixture.now, fixture.image)
                    self.assertEqual(image, result['registry_image'])
                    self.assertNotIn('local_engine_digest', result)
        with self.assertRaises(ValueError):
            target.validate_inventory(Path('evidence'), 'operator', 'mutable:tag', fixture.now, fixture.image)

    def test_provenance_distinguishes_runtime_and_source_builds(self):
        selected = target.recipe()
        for component in target.COMPONENTS:
            predicate = target.source_predicate(component, selected, self.revision, 'github:123:1')
            parameters = predicate['buildDefinition']['externalParameters']
            self.assertEqual(target.provenance(component), parameters['operation'])
            self.assertEqual(self.revision, parameters['repository_revision'])
            self.assertEqual(selected, parameters['recipe'])
            self.assertEqual(64, len(parameters['dockerfile_sha256']))
            self.assertEqual(4 if component == 'postgresql' else 5,
                             len(predicate['buildDefinition']['resolvedDependencies']))

    def test_workflow_retains_protected_signer_and_ephemeral_authentication(self):
        workflow = (target.RECIPE.parents[3] / '.github/workflows/production-release.yml').read_text()
        for required in ('environment: production-release', 'github.ref == \'refs/heads/main\'',
                         'packages: write', 'id-token: write', 'GHCR_TOKEN: ${{ github.token }}',
                         'release_kind == \'postgresql-candidate\'', 'RELEASE_KIND: ${{ inputs.release_kind }}',
                         'scripts/production/publish_postgresql_candidates.py',
                         'Exact main baseline must pass first', 'persist-credentials: false',
                         '!${{ runner.temp }}/postgresql-release-evidence/**/source.tar.gz'):
            self.assertIn(required, workflow)
        for forbidden in ('pull_request_target', '--insecure-ignore', 'ghcr-read-credentials.json'):
            self.assertNotIn(forbidden, workflow)

    def test_wrong_signer_and_mismatched_attestation_are_not_success(self):
        image = target.repository('operator') + '@sha256:' + 'd' * 64
        for reject in ('wrong-signer-accepted', 'payload', None):
            with tempfile.TemporaryDirectory() as parent:
                folder = Path(parent)
                (folder / 'cyclonedx.json').write_text('{"bomFormat":"CycloneDX"}')
                calls = []
                def native(argv, **kwargs):
                    calls.append(argv)
                    if target.EXPECTED_CERTIFICATE_IDENTITY + '.wrong' in argv and reject != 'wrong-signer-accepted':
                        raise subprocess.CalledProcessError(1, argv)
                    return ''
                with patch.object(target, 'run', side_effect=native), \
                        patch.object(target, 'verify_payload', side_effect=ValueError('payload') if reject == 'payload' else None):
                    if reject:
                        with self.assertRaises(ValueError): target.sign_and_verify(folder, image, {})
                    else:
                        target.sign_and_verify(folder, image, {})
                        self.assertEqual(['cosign', 'verify'], calls[-1][:2])
                        self.assertNotIn(target.EXPECTED_CERTIFICATE_IDENTITY + '.wrong', calls[-1])


if __name__ == '__main__':
    unittest.main()
