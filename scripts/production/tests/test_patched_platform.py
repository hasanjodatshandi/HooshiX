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

    def test_registry_resolution_binds_repository_manifest_config_and_platform(self):
        repository = 'ghcr.io/hasanjodatshandi/hooshix/platform-eso-v2-patched-private'
        image = repository + '@sha256:' + 'b' * 64
        source = {'type': 'image', 'metadata': {'manifestDigest': 'sha256:' + 'b' * 64,
            'imageID': self.digest, 'repoDigests': [image], 'os': 'linux', 'architecture': 'amd64'}}
        self.assertEqual(image, target.published_image(repository, source, self.digest))
        for field, value in (('manifestDigest', 'latest'), ('imageID', 'sha256:' + 'c' * 64),
                ('repoDigests', []), ('repoDigests', 'not-a-list'),
                ('repoDigests', ['ghcr.io/other/image@sha256:' + 'b' * 64]),
                ('os', 'windows'), ('architecture', 'arm64')):
            bad = copy.deepcopy(source)
            bad['metadata'][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                target.published_image(repository, bad, self.digest)
        source['type'] = 'directory'
        with self.assertRaises(ValueError):
            target.published_image(repository, source, self.digest)

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

    def test_publication_resolves_registry_then_scans_all_digests_before_signing(self):
        commands = []
        repositories = {component: 'ghcr.io/hasanjodatshandi/hooshix/platform-' + name + '-patched-private'
            for component, name in (('eso', 'eso-v2'), ('openbao', 'openbao'),
                                    ('istiod', 'istiod'), ('cni', 'cni'))}

        def fake_run(argv, **kwargs):
            commands.append(argv)
            if argv[0] == 'git':
                return 'b' * 40
            if argv[0] == 'gh':
                return 'private'
            if argv[0] == 'cosign':
                self.assertEqual(4, scanned.call_count)
                if any(arg.endswith('.wrong') for arg in argv):
                    raise target.subprocess.CalledProcessError(1, argv)
            return ''

        def evidence(path):
            if path.name == 'cyclonedx.json':
                return {'bomFormat': 'CycloneDX', 'components': [{}]}
            image = repositories[path.parent.name] + '@sha256:' + 'c' * 64
            return {'source': {'type': 'image', 'metadata': {'manifestDigest': 'sha256:' + 'c' * 64,
                'imageID': self.digest, 'repoDigests': [image], 'os': 'linux', 'architecture': 'amd64'}}}

        receipt = {'image_config_digest': self.digest, 'recipe_sha256': 'd' * 64,
                   'module_files_sha256': {'go.mod': 'e' * 64, 'go.sum': 'f' * 64}}
        with patch.object(target, 'context', return_value=('b' * 40, 'github:1:1')), \
                patch.object(target, 'recipe', return_value=self.selected), \
                patch.object(target, 'base_image', return_value='base@sha256:' + 'a' * 64), \
                patch.object(target, 'run', side_effect=fake_run), patch.object(Path, 'mkdir'), \
                patch.object(Path, 'write_text'), patch.object(target, 'build', return_value=('tag', receipt)), \
                patch.object(target, 'load', side_effect=evidence), patch.object(target, 'scan'), \
                patch.object(target, 'registry_scan') as scanned, patch.object(target, 'verify_payload'):
            result = target.publish(Path('unused'), {})
        self.assertEqual('Passed', result['publication'])
        self.assertEqual('Not verified', result['production_promotion'])
        self.assertEqual({component: repository + '@sha256:' + 'c' * 64
            for component, repository in repositories.items()}, result['images'])
        self.assertEqual(['users/hasanjodatshandi/packages/container/'
            + repository.removeprefix('ghcr.io/hasanjodatshandi/').replace('/', '%2F')
            for repository in repositories.values()], [argv[2] for argv in commands if argv[0] == 'gh'])
        scans = [argv for argv in commands if argv[0] == 'syft']
        self.assertEqual(8, len(scans))
        self.assertTrue(all('@sha256:' in argv[2] for argv in scans[1::2]))
        self.assertFalse(any('.RepoDigests' in ' '.join(argv) for argv in commands))

    def test_nonprivate_package_stops_before_registry_scan_or_signing(self):
        for visibility in ('public', 'internal', '', 'private\npublic'):
            commands = []
            def fake_run(argv, **kwargs):
                commands.append(argv)
                return 'b' * 40 if argv[0] == 'git' else visibility
            with self.subTest(visibility=visibility), \
                    patch.object(target, 'context', return_value=('b' * 40, 'github:1:1')), \
                    patch.object(target, 'run', side_effect=fake_run), patch.object(Path, 'mkdir'), \
                    patch.object(Path, 'write_text'), patch.object(target, 'build',
                        return_value=('tag', {'image_config_digest': self.digest})), \
                    self.assertRaises(ValueError):
                target.publish(Path('unused'), {})
            self.assertEqual('users/hasanjodatshandi/packages/container/hooshix%2Fplatform-eso-v2-patched-private',
                             next(argv[2] for argv in commands if argv[0] == 'gh'))
            self.assertFalse(any(argv[0] in ('syft', 'grype', 'cosign') for argv in commands))

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

    def test_native_recovery_failure_prevents_publication_and_signing(self):
        commands = []
        digest = 'sha256:' + 'a' * 64
        def fake_run(argv, **kwargs):
            commands.append(argv)
            if argv[0] == 'git':
                return 'b' * 40
            raise target.subprocess.CalledProcessError(1, argv)
        with patch.object(target, 'context', return_value=('b' * 40, 'github:1:1')), \
                patch.object(target, 'run', side_effect=fake_run), \
                patch.object(Path, 'mkdir'), \
                patch.object(target, 'build', return_value=('candidate', {'image_config_digest': digest})) as builder, \
                self.assertRaises(target.subprocess.CalledProcessError):
            target.publish(Path('unused'), {})
        self.assertEqual(4, builder.call_count)
        self.assertEqual(['--ci', '--image-config-digest', digest], commands[1][-3:])
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

    def test_baseline_scans_selected_final_candidates_and_retains_scheduled_monitor(self):
        workflow = (target.ROOT / '.github/workflows/repository-baseline.yml').read_text()
        for component in ('openbao', 'eso'):
            self.assertIn('--component ' + component, workflow)
        self.assertIn("component in ('istiod', 'cni')", workflow)
        self.assertIn('build(component, folder)', workflow)
        self.assertIn('--ci --image-config-digest "$image"', workflow)
        self.assertIn("if: github.event_name == 'schedule'", workflow)
        self.assertIn("os.environ['GITHUB_EVENT_NAME'] != 'schedule'", workflow)
        self.assertIn("if [ \"$GITHUB_EVENT_NAME\" != 'schedule' ]", workflow)
        self.assertIn('scan(folder)', workflow)  # Unchanged Rust ztunnel still scanned.
        baseline = workflow.split('  baseline:', 1)[1]
        for job in ('openbao-artifact', 'eso-artifact', 'mesh-artifact'):
            self.assertIn('- ' + job, baseline)
            self.assertIn('${{ needs.' + job + '.result }}', baseline)


if __name__ == '__main__':
    unittest.main()
