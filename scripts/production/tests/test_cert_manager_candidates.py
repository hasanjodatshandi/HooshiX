"""The three-image exception does not grant publication, signing or deployment bypasses."""
import copy
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from datetime import timedelta
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_cert_manager_candidates as build
import publish_cert_manager_candidates as publish
import test_postgresql_source_builds as fixtures


class CertManagerBuildTest(unittest.TestCase):
    def setUp(self):
        fixture = fixtures.PostgreSQLSourceBuildTest()
        fixture.setUp()
        self.now, self.engine = fixture.now, fixture.image
        self.selected = build.recipe()
        self.evidence = fixture.evidence('operator')
        labels = self.evidence['syft.json']['source']['metadata']['labels']
        labels['io.hooshix.source.revision'] = self.selected['source']['revision']
        labels['io.hooshix.recipe.sha256'] = hashlib.sha256(build.RECIPE.read_bytes()).hexdigest()
        self.evidence['syft.json']['artifacts'] = [
            {'name': 'stdlib', 'version': 'go1.26.9', 'locations': [{'path': '/app/cmd/controller/controller'}]},
            *[{'name': name, 'version': version} for name, version in build.MODULES.items()]]

    def validate(self, evidence, component='controller', registry=False, recipe_path=build.RECIPE):
        image = publish.repository(component) + '@sha256:' + 'd' * 64 if registry else self.engine
        original = build.load
        with patch.object(build, 'load', side_effect=lambda p:
                self.selected if p == recipe_path else evidence[p.name] if p.name in evidence else original(p)):
            return build.validate_inventory(Path('evidence'), component, image, self.now,
                                            self.engine if registry else None, recipe_path)

    def test_jose_candidate_preserves_archived_recipe_and_exact_fixed_modules(self):
        self.assertEqual('350941c06ad2bc5536ebc47b972643db4a56e8f831cef2617841e701cfdee4e9',
                         hashlib.sha256(build.RECIPE.read_bytes()).hexdigest())
        self.selected = build.recipe(build.CANDIDATE_RECIPE)
        self.assertEqual(build.recipe()['modules'] | {'github.com/go-jose/go-jose/v4': 'v4.1.6'},
                         self.selected['modules'])
        for component in build.COMPONENTS:
            generated = build.dockerfile(component, self.selected, build.CANDIDATE_RECIPE)
            self.assertEqual(2, generated.count('github.com/go-jose/go-jose/v4@v4.1.6'))
            self.assertIn('go test ./...', generated)
            self.assertNotIn('DefaultMaxRSAKeySize', generated)
            self.assertIn(hashlib.sha256(build.CANDIDATE_RECIPE.read_bytes()).hexdigest(), generated)
            self.assertEqual(component == 'controller', 'git apply --check' in generated)
        with self.assertRaises(ValueError): build.recipe(Path('/unreviewed.json'))
        original = build.load
        for field, value in (('sha256', '0' * 64), ('path', '/unreviewed.patch')):
            bad = copy.deepcopy(self.selected)
            bad['test_patch'][field] = value
            with patch.object(build, 'load', side_effect=lambda p:
                    bad if p == build.CANDIDATE_RECIPE else original(p)), self.assertRaises(ValueError):
                build.recipe(build.CANDIDATE_RECIPE)
        self.selected['modules']['github.com/go-jose/go-jose/v4'] = 'v4.1.5'
        with patch.object(build, 'load', side_effect=lambda p:
                self.selected if p == build.CANDIDATE_RECIPE else original(p)), self.assertRaises(ValueError):
            build.recipe(build.CANDIDATE_RECIPE)

    def test_jose_candidate_rejects_legacy_or_missing_controller_linkage(self):
        self.selected = build.recipe(build.CANDIDATE_RECIPE)
        evidence = copy.deepcopy(self.evidence)
        evidence['syft.json']['source']['metadata']['labels']['io.hooshix.recipe.sha256'] = \
            hashlib.sha256(build.CANDIDATE_RECIPE.read_bytes()).hexdigest()
        evidence['syft.json']['artifacts'].append(
            {'name': 'github.com/go-jose/go-jose/v4', 'version': 'v4.1.6'})
        self.validate(evidence, recipe_path=build.CANDIDATE_RECIPE)
        for name, version in (('github.com/go-jose/go-jose/v4', 'v4.1.4'),
                ('github.com/go-jose/go-jose/v3', 'v3.0.5'),
                ('gopkg.in/square/go-jose.v2', 'v2.6.0')):
            bad = copy.deepcopy(evidence)
            bad['syft.json']['artifacts'].append({'name': name, 'version': version})
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.validate(bad, recipe_path=build.CANDIDATE_RECIPE)
        evidence['syft.json']['artifacts'].pop()
        with self.assertRaises(ValueError): self.validate(evidence, recipe_path=build.CANDIDATE_RECIPE)
        evidence['syft.json']['artifacts'][0]['locations'][0]['path'] = '/app/cmd/webhook/webhook'
        self.validate(evidence, component='webhook', recipe_path=build.CANDIDATE_RECIPE)

    def test_clean_scan_preserves_blocked_promotion_for_all_three(self):
        for component in build.COMPONENTS:
            evidence = copy.deepcopy(self.evidence)
            evidence['syft.json']['artifacts'][0]['locations'][0]['path'] = f'/app/cmd/{component}/{component}'
            result = self.validate(evidence, component)
            self.assertEqual('Passed', result['scan'])
            for key in ('signature_provenance', 'staging', 'deployment', 'production_promotion'):
                self.assertEqual('Not verified', result[key])

    def test_old_compiler_linked_grpc_network_and_missing_binary_rejected(self):
        for name in ('stdlib', *build.MODULES):
            evidence = copy.deepcopy(self.evidence)
            next(item for item in evidence['syft.json']['artifacts'] if item['name'] == name)['version'] = 'old'
            with self.subTest(name=name), self.assertRaises(ValueError): self.validate(evidence)
        for mutate in (
                lambda e: e['syft.json']['artifacts'][0].update(locations=[]),
                lambda e: e['syft.json']['artifacts'].pop(1),
                lambda e: e['syft.json']['artifacts'].append({'name': 'stdlib', 'version': 'old', 'locations': []})):
            evidence = copy.deepcopy(self.evidence)
            mutate(evidence)
            with self.assertRaises(ValueError): self.validate(evidence)

    def test_findings_suppressions_incomplete_inventory_and_stale_database_fail_closed(self):
        for mutate in (
                lambda e: e['grype.json'].update(matches=[{'vulnerability': {'severity': 'Critical'}}]),
                lambda e: e['grype.json'].update(ignoredMatches=[{'vulnerability': {'severity': 'High'}}]),
                lambda e: e['syft.json']['descriptor']['configuration'].update(exclude=['/**']),
                lambda e: e['syft.json']['descriptor']['configuration']['catalogers'].update(used=[]),
                lambda e: e['database.json'].update(built=(self.now - timedelta(days=6)).isoformat()),
                lambda e: e['database.json'].update(built=(self.now + timedelta(seconds=1)).isoformat()),
                lambda e: e['grype.json']['source']['target'].update(userInput='mutable'),
                lambda e: e['syft.json']['source']['metadata'].update(architecture='arm64'),
                lambda e: e['syft.json']['source']['metadata']['labels'].clear()):
            evidence = copy.deepcopy(self.evidence)
            mutate(evidence)
            with self.assertRaises(ValueError): self.validate(evidence)

    def test_registry_and_engine_identity_are_not_interchangeable(self):
        evidence = copy.deepcopy(self.evidence)
        image = publish.repository('controller') + '@sha256:' + 'd' * 64
        evidence['syft.json']['source']['metadata']['userInput'] = image
        evidence['grype.json']['source']['target']['userInput'] = image
        result = self.validate(evidence, registry=True)
        self.assertEqual(image, result['registry_image'])
        self.assertNotIn('local_engine_digest', result)
        evidence['syft.json']['source']['metadata']['imageID'] = 'sha256:' + 'b' * 64
        with self.assertRaises(ValueError): self.validate(evidence, registry=True)

    def test_nested_module_and_binary_version_build_are_fixed(self):
        for component in build.COMPONENTS:
            generated = build.dockerfile(component, self.selected)
            self.assertEqual(2, generated.count('go get '))
            for fragment in (f'WORKDIR /src/cmd/{component}', 'GOWORK=off', 'GOTOOLCHAIN=local',
                    'GOSUMDB=sum.golang.org', 'go mod verify', 'sha256sum -c -',
                    'go mod tidy',
                    'AppVersion=v1.20.3', 'AppGitState=Modified', 'google.golang.org/grpc@v1.83.2',
                    f'cmd/{component}/go.sum', f'/app/cmd/{component}/{component}', 'USER 65532:65532'):
                self.assertIn(fragment, generated)
            self.assertNotIn('latest', generated)
        for key, value in (('version', '1.20.2'), ('go_version', 'old'), ('builder', 'golang:latest'),
                ('modules', {}), ('production_promotion', 'Passed'), ('source', {}), ('components', {})):
            selected = self.selected | {key: value}
            original = build.load
            with patch.object(build, 'load', side_effect=lambda p:
                    selected if p == build.RECIPE else original(p)), self.assertRaises(ValueError):
                build.recipe()

    def test_help_only_smoke_is_non_root_restricted_and_not_version_or_install_proof(self):
        config = {'User': '65532:65532', 'Entrypoint': ['/app/cmd/controller/controller'], 'Cmd': []}
        with patch.object(build, 'run', side_effect=[json.dumps(config), 'help']) as native:
            build.native_entrypoint('controller', self.engine, 'unix:///captured.sock')
        command = native.call_args.args[0]
        for flag in ('--read-only', '--network', 'none', '--cap-drop', 'ALL', '--security-opt',
                     'no-new-privileges', '--memory', '--cpus', '--pids-limit', '--help'):
            self.assertIn(flag, command)
        self.assertNotIn('--version', command)
        with patch.object(build, 'run', return_value=json.dumps(config | {'User': '0'})), self.assertRaises(ValueError):
            build.native_entrypoint('controller', self.engine, 'unix:///captured.sock')
        for component, image, endpoint in (('acmesolver', self.engine, 'unix:///captured.sock'),
                ('controller', 'mutable:tag', 'unix:///captured.sock'),
                ('controller', self.engine, 'tcp://remote:2375')):
            with patch.object(build, 'run') as native, self.assertRaises(ValueError):
                build.native_entrypoint(component, image, endpoint)
            native.assert_not_called()

    def test_partial_build_and_source_integrity_failure_cannot_create_receipt(self):
        for rejected in ('hash', 'build', 'native', 'scan'):
            with tempfile.TemporaryDirectory() as parent:
                directory = Path(parent) / 'candidate'
                selected = build.recipe(build.CANDIDATE_RECIPE)
                selected['source']['archive_sha256'] = hashlib.sha256(b'fixture').hexdigest()
                def native(argv, **kwargs):
                    if rejected == 'build': raise subprocess.CalledProcessError(1, argv)
                    Path(argv[argv.index('--iidfile') + 1]).write_text(self.engine)
                    return ''
                with patch.object(build, 'recipe', return_value=selected), \
                        patch.object(build.urllib.request, 'urlopen') as fetch, \
                        patch.object(build, 'run', side_effect=native), \
                        patch.object(build, 'native_entrypoint', side_effect=ValueError() if rejected == 'native' else None), \
                        patch.object(build, 'scan_inventory', side_effect=ValueError() if rejected == 'scan' else None), \
                        self.assertRaises((ValueError, subprocess.CalledProcessError)):
                    fetch.return_value.__enter__.return_value.read.return_value = b'wrong' if rejected == 'hash' else b'fixture'
                    build.build('controller', directory, docker_host='unix:///captured.sock')
                self.assertFalse((directory / 'receipt.json').exists())
        with patch.object(build.urllib.request, 'urlopen') as fetch, self.assertRaises(ValueError):
            build.build('controller', Path('unused'), docker_host='tcp://remote:2375')
        fetch.assert_not_called()

    def test_build_failure_evidence_retains_public_test_locations_without_raw_output(self):
        with tempfile.TemporaryDirectory() as parent:
            directory = Path(parent) / 'failed'
            selected = build.recipe(build.CANDIDATE_RECIPE)
            selected['source']['archive_sha256'] = hashlib.sha256(b'fixture').hexdigest()
            failure = subprocess.CalledProcessError(1, ['docker'], stderr=(
                '--- FAIL: TestSynthetic/invalid_key (0.01s)\nissuer_test.go:42: '
                'private-value-must-not-escape\npanic: private-value-must-not-escape'))
            with patch.object(build, 'recipe', return_value=selected), \
                    patch.object(build.urllib.request, 'urlopen') as fetch, \
                    patch.object(build, 'run', side_effect=failure), self.assertRaises(subprocess.CalledProcessError):
                fetch.return_value.__enter__.return_value.read.return_value = b'fixture'
                build.build('controller', directory, docker_host='unix:///captured.sock')
            text = (directory / 'build-failure.json').read_text()
            self.assertNotIn('private-value-must-not-escape', text)
            result = json.loads(text)
            self.assertEqual(['TestSynthetic/invalid_key'], result['tests'])
            self.assertEqual(['issuer_test.go:42'], result['locations'])
            self.assertIn('panic:', result['phrases'])
            self.assertFalse((directory / 'receipt.json').exists())


class CertManagerPublicationTest(unittest.TestCase):
    def setUp(self):
        self.revision, self.engine, self.config = 'a' * 40, 'sha256:' + 'e' * 64, 'sha256:' + 'c' * 64
        self.env = {'GITHUB_ACTIONS': 'true', 'GITHUB_REF': 'refs/heads/main',
            'GITHUB_REPOSITORY': 'hasanjodatshandi/HooshiX', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
            'GITHUB_WORKFLOW_REF': publish.EXPECTED_CERTIFICATE_IDENTITY.removeprefix('https://github.com/'),
            'GITHUB_SHA': self.revision, 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1',
            'RELEASE_KIND': 'cert-manager-candidate'}

    def execute(self, directory, reject=None, visibility='private'):
        scans, signatures, calls = [], [], []
        def resolution(path):
            component = path.parent.parent.name
            digest = 'sha256:' + ('f' if reject == 'manifest' and path.name == 'syft.json' else 'd') * 64
            path.write_text(json.dumps({'source': {'type': 'image', 'metadata': {
                'imageID': self.engine if reject == 'config' else self.config, 'manifestDigest': digest,
                'os': 'linux', 'architecture': 'amd64', 'repoDigests': [publish.repository(component) + '@' + digest]}}}))
        def native(argv, **kwargs):
            calls.append(argv)
            if argv[0] == 'git': return self.revision
            if argv[0] == 'gh': return 'internal' if reject == 'visibility' else visibility
            if argv[0] == 'syft': resolution(Path(argv[-1].removeprefix('syft-json=')))
            return ''
        def candidate(component, folder, **kwargs):
            self.assertEqual({'docker_host': 'unix:///captured.sock'}, kwargs)
            folder.mkdir()
            return {'local_engine_digest': self.engine, 'image_config_digest': self.config}
        def scan(folder, image, source):
            scans.append(image)
            resolution(folder / 'syft.json')
        def checked(folder, component, image, now, config, recipe_path):
            self.assertEqual(build.CANDIDATE_RECIPE, recipe_path)
            if reject == 'scan': raise ValueError('scan')
            return {'scan': 'Passed'}
        def signed(folder, image, predicate):
            self.assertEqual(3, len(scans))
            self.assertEqual('v4.1.6', predicate['buildDefinition']['externalParameters'][
                'recipe']['modules']['github.com/go-jose/go-jose/v4'])
            signatures.append(image)
            if reject == 'signature': raise ValueError('signature')
        with patch.object(publish, 'run', side_effect=native), \
                patch.object(publish, 'local_endpoint', return_value='unix:///captured.sock'), \
                patch.object(publish, 'build', side_effect=candidate), \
                patch.object(publish, 'scan_inventory', side_effect=scan), \
                patch.object(publish, 'validate_inventory', side_effect=checked), \
                patch.object(publish, 'sign_and_verify', side_effect=signed):
            if reject:
                with self.assertRaises(ValueError): publish.publish(directory, self.env)
                self.assertFalse((directory / 'publication.json').exists())
                if reject != 'signature': self.assertFalse(signatures)
            else:
                result = publish.publish(directory, self.env)
                self.assertEqual(3, len(signatures))
                self.assertEqual({key: visibility for key in build.COMPONENTS}, result['registry_visibility'])
                for key in ('staging', 'runtime_admission', 'deployment', 'production_promotion'):
                    self.assertEqual('Not verified', result[key])
                self.assertEqual(result, json.loads((directory / 'publication.json').read_text()))
        return calls

    def test_approved_private_or_public_set_requires_all_scans_before_signing(self):
        for visibility in ('private', 'public'):
            with tempfile.TemporaryDirectory() as parent:
                calls = self.execute(Path(parent) / 'attempt', visibility=visibility)
            self.assertEqual(3, sum(argv[:4] == ['docker', '--host', 'unix:///captured.sock', 'push'] for argv in calls))

    def test_partial_failures_never_sign_unclean_set_or_emit_success(self):
        for reject in ('visibility', 'config', 'manifest', 'scan', 'signature'):
            with self.subTest(reject=reject), tempfile.TemporaryDirectory() as parent:
                self.execute(Path(parent) / 'attempt', reject)

    def test_head_mismatch_and_existing_attempt_fail_before_build(self):
        with tempfile.TemporaryDirectory() as parent:
            with patch.object(publish, 'run', return_value='b' * 40), \
                    patch.object(publish, 'build') as candidate, self.assertRaises(ValueError):
                publish.publish(Path(parent) / 'attempt', self.env)
            candidate.assert_not_called()
            with patch.object(publish, 'run', return_value=self.revision), \
                    patch.object(publish, 'local_endpoint', return_value='unix:///captured.sock'), \
                    patch.object(publish, 'build') as candidate, self.assertRaises(FileExistsError):
                publish.publish(Path(parent), self.env)
            candidate.assert_not_called()

    def test_protected_context_and_actual_404_only(self):
        for key in ('GITHUB_REF', 'GITHUB_EVENT_NAME', 'GITHUB_WORKFLOW_REF', 'GITHUB_ACTIONS', 'RELEASE_KIND'):
            with patch.object(publish, 'run', return_value=self.revision), \
                    patch.object(publish, 'build') as candidate, self.assertRaises(ValueError):
                publish.publish(Path('unused'), self.env | {key: 'wrong'})
            candidate.assert_not_called()
        for code in (404, 401, 403):
            with patch.object(publish, 'run', side_effect=subprocess.CalledProcessError(1, ['gh'], stderr=f'(HTTP {code})')):
                if code == 404: publish.package_visibility('controller', allow_missing=True)
                else:
                    with self.assertRaises(subprocess.CalledProcessError): publish.package_visibility('controller', allow_missing=True)
                with self.assertRaises(subprocess.CalledProcessError): publish.package_visibility('controller')
        for component in ('acmesolver', 'startupapicheck', 'identity-service', 'operator'):
            with patch.object(publish, 'run') as native, self.assertRaises(ValueError):
                publish.package_visibility(component, allow_missing=True)
            native.assert_not_called()

    def test_exact_destinations_provenance_and_credential_boundaries(self):
        self.assertEqual({'controller', 'cainjector', 'webhook'}, set(publish.PACKAGES))
        for component in build.COMPONENTS:
            self.assertEqual('hooshix/platform-cert-manager-' + component, publish.PACKAGES[component])
            predicate = publish.source_predicate(component, build.recipe(), self.revision, 'github:123:1')
            external = predicate['buildDefinition']['externalParameters']
            self.assertEqual('patched-upstream-source-build', external['operation'])
            self.assertEqual(5, len(predicate['buildDefinition']['resolvedDependencies']))
            self.assertEqual(self.revision, external['repository_revision'])
        workflow = (build.ROOT / '.github/workflows/production-release.yml').read_text()
        self.assertIn('[[ "$visibility" == private ]]', workflow.split('  openbao-candidate:')[0])
        for fragment in ('environment: production-release', 'Exact main baseline must pass first',
                         'scripts/production/publish_cert_manager_candidates.py',
                         '!${{ runner.temp }}/cert-manager-release-evidence/**/source.tar.gz'):
            self.assertIn(fragment, workflow)
        candidate = (build.ROOT / '.github/workflows/cert-manager-artifacts.yml').read_text()
        for forbidden in ('packages: write', 'id-token: write', 'github.token', 'pull_request_target', 'cosign sign'):
            self.assertNotIn(forbidden, candidate)


if __name__ == '__main__':
    unittest.main()
