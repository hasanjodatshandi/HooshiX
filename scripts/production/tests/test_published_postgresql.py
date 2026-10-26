"""Published bytes are verified without building, signing or granting deployment."""
import base64
import copy
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_published_postgresql as target


class PublishedPostgreSQLTest(unittest.TestCase):
    def setUp(self):
        self.selected = target.candidate()
        self.publication = self.selected['publication']
        self.engine = 'sha256:' + 'e' * 64

    def test_reviewed_set_preserves_upstream_and_promotion_boundaries(self):
        self.assertEqual('github:40333333697:1', self.publication['invocation_id'])
        self.assertEqual(set(target.COMPONENTS), set(self.publication['images']))
        self.assertEqual('Passed', self.publication['publication'])
        self.assertEqual('blocked-until-supply-chain-staging-and-recovery-evidence',
                         target.pins()['production_promotion'])
        text = json.dumps(self.publication, indent=2) + '\n'
        self.assertEqual(self.selected['publication_sha256'], hashlib.sha256(text.encode()).hexdigest())
        workflow = (target.PIN.parents[3] / '.github/workflows/platform-commissioning.yml').read_text()
        self.assertIn('gh run download 40333333697 --repo hasanjodatshandi/HooshiX', workflow)
        self.assertIn('--name postgresql-publication-40333333697-1', workflow)

    def test_partial_untrusted_or_promoted_catalog_rejected(self):
        def image(p):
            p['images']['operator'] = p['images']['operator'].replace('-v2-private@', '-private@')
        mutations = [lambda s: s.update(recipe_sha256='0' * 64),
            lambda s: s.update(publication_sha256='invalid'),
            lambda s: s['publication'].update(repository_revision='main'),
            lambda s: s['publication'].update(invocation_id='github:0:1'),
            lambda s: s['publication'].update(publication='Not verified'),
            lambda s: s['publication'].update(signature_provenance='Not verified'),
            lambda s: s['publication'].update(staging='Passed'),
            lambda s: s['publication'].update(deployment='Passed'),
            lambda s: s['publication'].update(production_promotion='Passed'),
            lambda s: s['publication']['images'].pop('barman-sidecar'),
            lambda s: image(s['publication']),
            lambda s: s['publication']['registry_visibility'].update(operator='internal'),
            lambda s: s['publication']['provenance_kinds'].update(postgresql='patched-upstream-source-build'),
            lambda s: s['publication']['image_config_digests'].update(operator='mutable-tag')]
        for mutate in mutations:
            selected = copy.deepcopy(self.selected)
            mutate(selected)
            with patch.object(target, 'load', return_value={'published_security_candidate': selected}), \
                    self.assertRaises((ValueError, KeyError)):
                target.candidate()

    def test_cosign_uses_fresh_cache_and_never_inherits_trust_overrides(self):
        with patch.dict(os.environ, {'SIGSTORE_ROOT_FILE': '/untrusted', 'COSIGN_REPOSITORY': 'evil.invalid',
                                    'TUF_ROOT': '/existing', 'TUF_MIRROR': 'https://evil.invalid'}), \
                patch.object(target.subprocess, 'run') as run:
            target.cosign(['initialize', '--mirror', target.SIGSTORE_TUF_MIRROR], Path('/fresh/scoped/cache'))
        options = run.call_args.kwargs
        self.assertEqual('/fresh/scoped/cache', options['env']['TUF_ROOT'])
        for key in ('SIGSTORE_ROOT_FILE', 'COSIGN_REPOSITORY', 'TUF_MIRROR'):
            self.assertNotIn(key, options['env'])
        self.assertTrue(options['check'] and options['capture_output'])
        self.assertEqual(180, options['timeout'])
        self.assertNotIn('--root', run.call_args.args[0])
        self.assertNotIn('--staging', run.call_args.args[0])

    def execute(self, parent, reject=None):
        evidence, output = Path(parent) / 'evidence', Path(parent) / 'acceptance'
        evidence.mkdir()
        receipt = json.dumps(self.publication, indent=2) + '\n'
        (evidence / 'publication.json').write_text(receipt + (' ' if reject == 'receipt' else ''))
        if reject == 'existing-output': output.mkdir()
        calls, scans, smokes, sql = [], [], [], []
        def read(path):
            if path == evidence / 'publication.json': return self.publication
            if path.name == 'syft.json': return {'source': {'component': path.parent.parent.name}}
            if path.name == 'cyclonedx.json': return {'bomFormat': 'CycloneDX', 'components': [{}]}
            raise AssertionError('unexpected evidence read')
        refreshed = []
        def refresh(folder, image, source):
            component = next(c for c, value in self.publication['images'].items() if value == image)
            self.assertEqual(output / component / 'registry', folder)
            self.assertEqual('registry', source)
            self.assertTrue(folder.is_dir())
            if reject == 'fresh-scan': raise subprocess.CalledProcessError(2, ['grype'])
            for name in ('syft.json', 'cyclonedx.json', 'grype.json', 'database.json', 'scanner-config.json'):
                (folder / name).write_text(json.dumps({'fresh': component, 'file': name}) + '\n')
            refreshed.append(component)
        def scan(folder, component, image, now, config):
            self.assertIn(component, refreshed)
            self.assertEqual(output / component / 'registry', folder)
            self.assertEqual(self.publication['images'][component], image)
            self.assertEqual(self.publication['image_config_digests'][component], config)
            scans.append(component)
            if reject == 'scan' and component == 'barman-sidecar': raise ValueError('blocked scan')
        def native(argv, **kwargs):
            calls.append(argv)
            self.assertEqual(4, len(scans))
            self.assertNotIn(argv[0], ('gh', 'kubectl', 'ssh'))
            if argv[:2] == ['cosign', 'version']: return 'GitVersion: v3.0.6'
            if argv[:2] == ['cosign', 'initialize']:
                self.assertEqual(['--mirror', target.SIGSTORE_TUF_MIRROR], argv[2:])
                if reject == 'trust': raise subprocess.CalledProcessError(1, argv)
                return ''
            if argv[0] == 'cosign':
                self.assertIn(target.EXPECTED_OIDC_ISSUER, argv)
                wrong = target.EXPECTED_CERTIFICATE_IDENTITY + '.wrong' in argv
                if wrong:
                    if reject == 'wrong-signer-accepted': return ''
                    raise subprocess.CalledProcessError(1, argv)
                self.assertIn(target.EXPECTED_CERTIFICATE_IDENTITY, argv)
                if reject == 'signature': raise subprocess.CalledProcessError(1, argv)
                if argv[1] == 'verify': return ''
                component = next(key for key, value in self.publication['images'].items() if value == argv[-1])
                kind = argv[argv.index('--type') + 1]
                predicate = (target.source_predicate(component, target.recipe(),
                    self.publication['repository_revision'], self.publication['invocation_id'])
                    if kind == 'slsaprovenance1' else read(Path('cyclonedx.json')))
                if reject == 'predicate': predicate = {'wrong': True}
                statement = {'_type': 'https://in-toto.io/Statement/v0.1',
                    'predicateType': 'https://slsa.dev/provenance/v1' if kind == 'slsaprovenance1'
                                     else 'https://cyclonedx.org/bom',
                    'predicate': predicate,
                    'subject': [{'digest': {'sha256': argv[-1].split('@sha256:')[1]}}]}
                if reject == 'subject': statement['subject'] = []
                return json.dumps({'payload': base64.b64encode(json.dumps(statement).encode()).decode()})
            self.assertEqual(['docker', '--host', 'unix:///captured.sock'], argv[:3])
            image = argv[-1]
            if argv[3] == 'pull':
                self.assertEqual(['--platform', 'linux/amd64'], argv[4:6])
                if reject == 'pull': raise subprocess.CalledProcessError(1, argv)
                return ''
            self.assertEqual(['image', 'inspect'], argv[3:5])
            component = next(key for key, value in self.publication['images'].items() if value == image)
            inspected = {'Id': self.engine, 'RepoDigests': [image], 'Os': 'linux', 'Architecture': 'amd64',
                'Config': {'User': '26:26' if component in ('postgresql', 'barman-sidecar') else '65532:65532',
                    'Entrypoint': ['/usr/local/bin/postgres'] if component == 'postgresql' else ['/manager'],
                    'Cmd': []}}
            if reject == 'digest': inspected['RepoDigests'] = []
            if reject == 'platform': inspected['Architecture'] = 'arm64'
            if reject == 'root': inspected['Config']['User'] = '0:0'
            return json.dumps(inspected)
        def smoke(component, engine, endpoint):
            self.assertEqual(self.engine, engine)  # Config and containerd engine ID are not conflated.
            smokes.append(component)
            if reject == 'smoke': raise RuntimeError('native failure')
        def isolation(engine, docker_host):
            self.assertEqual(self.engine, engine)
            self.assertEqual('unix:///captured.sock', docker_host)
            self.assertEqual(4, len(smokes))
            sql.append(engine)
            if reject == 'sql': raise RuntimeError('database boundary failure')
        def signature(args, cache):
            self.assertEqual(output.resolve() / 'sigstore-tuf', cache)
            return native(['cosign', *args])
        with patch.object(target, 'candidate', return_value=self.selected), \
                patch.object(target, 'load', side_effect=read), \
                patch.object(target, 'validate_inventory', side_effect=scan), \
                patch.object(target, 'scan_inventory', side_effect=refresh), \
                patch.object(target, 'published_image', side_effect=lambda repo, source, config:
                             self.publication['images'][source['component']]), \
                patch.object(target, 'run', side_effect=native), \
                patch.object(target, 'cosign', side_effect=signature), \
                patch.object(target, 'local_endpoint', return_value='unix:///captured.sock'), \
                patch.object(target, 'native_entrypoint', side_effect=smoke), \
                patch.object(target, 'exercise_candidate', side_effect=isolation), redirect_stdout(io.StringIO()):
            if reject:
                with self.assertRaises((ValueError, RuntimeError, OSError, subprocess.SubprocessError)):
                    target.verify(evidence, output)
                self.assertFalse((output / 'acceptance.json').exists())
                if reject in ('receipt', 'scan', 'fresh-scan', 'existing-output'): self.assertFalse(calls or smokes or sql)
            else:
                result = target.verify(evidence, output)
                self.assertEqual(result, json.loads((output / 'acceptance.json').read_text()))
                self.assertEqual(set(self.publication['images']), set(refreshed))
                for component, hashes in result['scan_evidence_sha256'].items():
                    self.assertEqual(5, len(hashes))
                    for name, digest in hashes.items():
                        self.assertEqual(hashlib.sha256((output / component / 'registry' / name).read_bytes()).hexdigest(), digest)
                for name in ('signature_provenance', 'wrong_signer', 'native_entrypoints', 'native_database_isolation'):
                    self.assertEqual('Passed', result[name])
                for name in ('staging', 'runtime_admission', 'physical_recovery', 'deployment', 'production_promotion'):
                    self.assertEqual('Not verified', result[name])
                self.assertEqual(4, len(smokes))
                self.assertEqual([self.engine], sql)
                self.assertFalse(any(argv[0] == 'cosign' and argv[1] in ('sign', 'attest', 'copy') for argv in calls))
        return calls

    def test_whole_set_reverified_and_smoked_without_any_production_authority(self):
        with tempfile.TemporaryDirectory() as parent:
            calls = self.execute(parent)
        self.assertEqual(4, sum(argv[:4] == ['docker', '--host', 'unix:///captured.sock', 'pull'] for argv in calls))

    def test_every_partial_failure_preserves_no_success_receipt(self):
        for reject in ('receipt', 'scan', 'fresh-scan', 'existing-output', 'trust', 'signature', 'predicate', 'subject',
                       'wrong-signer-accepted', 'pull', 'digest', 'platform', 'root', 'smoke', 'sql'):
            with self.subTest(reject=reject), tempfile.TemporaryDirectory() as parent:
                self.execute(parent, reject)


if __name__ == '__main__':
    unittest.main()
