"""Published cert-manager verification cannot build, sign, install or grant promotion."""
import base64
import copy
import hashlib
import io
import json
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_published_cert_manager as target


class PublishedCertManagerTest(unittest.TestCase):
    def setUp(self):
        self.selected = target.candidate()
        self.publication = self.selected['publication']
        self.engine = 'sha256:' + 'e' * 64

    def test_exact_publication_does_not_change_source_recipe_or_promotion(self):
        self.assertEqual('github:38409447796:1', self.publication['invocation_id'])
        self.assertEqual(set(target.COMPONENTS), set(self.publication['images']))
        text = json.dumps(self.publication, indent=2) + '\n'
        self.assertEqual(self.selected['publication_sha256'], hashlib.sha256(text.encode()).hexdigest())
        self.assertEqual(self.selected['recipe_sha256'], hashlib.sha256(target.RECIPE.read_bytes()).hexdigest())
        self.assertNotIn('published_security_candidate', target.recipe())

    def test_unreviewed_partial_or_promoted_catalog_cannot_authorize_use(self):
        original = target.load(target.PIN)
        for mutate in (
                lambda c: c.update(production_promotion='Passed'),
                lambda c: c.update(version='1.20.2'),
                lambda c: c.update(platform='linux/arm64'),
                lambda c: c['published_security_candidate'].update(recipe_sha256='0' * 64),
                lambda c: c['published_security_candidate'].update(publication_sha256='invalid'),
                lambda c: c['published_security_candidate']['publication'].update(repository_revision='main'),
                lambda c: c['published_security_candidate']['publication'].update(invocation_id='github:0:1'),
                lambda c: c['published_security_candidate']['publication'].update(publication='Not verified'),
                lambda c: c['published_security_candidate']['publication'].update(signature_provenance='Not verified'),
                lambda c: c['published_security_candidate']['publication'].update(staging='Passed'),
                lambda c: c['published_security_candidate']['publication'].update(deployment='Passed'),
                lambda c: c['published_security_candidate']['publication']['images'].pop('webhook'),
                lambda c: c['published_security_candidate']['publication']['images'].update(controller='ghcr.io/other/image@sha256:' + 'a' * 64),
                lambda c: c['published_security_candidate']['publication']['registry_visibility'].update(controller='internal'),
                lambda c: c['published_security_candidate']['publication']['image_config_digests'].update(controller='mutable:tag'),
                lambda c: c['published_security_candidate']['publication']['provenance_kinds'].update(controller='unchanged-upstream-import')):
            catalog = copy.deepcopy(original)
            mutate(catalog)
            with patch.object(target, 'load', return_value=catalog), self.assertRaises((ValueError, KeyError)):
                target.candidate()

    def execute(self, parent, reject=None):
        evidence, output = Path(parent) / 'evidence', Path(parent) / 'acceptance'
        evidence.mkdir()
        (evidence / 'publication.json').write_text(json.dumps(self.publication, indent=2) + '\n' +
                                                 (' ' if reject == 'receipt' else ''))
        if reject == 'existing-output': output.mkdir()
        calls, scans, smokes = [], [], []
        def read(path):
            if path.name == 'publication.json': return self.publication
            if path.name == 'syft.json': return {'source': {'component': path.parent.parent.name}}
            if path.name == 'cyclonedx.json': return {'bomFormat': 'CycloneDX', 'components': [{}]}
            raise AssertionError('unexpected evidence read')
        def scan(folder, component, image, now, config):
            self.assertEqual(self.publication['images'][component], image)
            self.assertEqual(self.publication['image_config_digests'][component], config)
            scans.append(component)
            if reject == 'scan' and component == 'webhook': raise ValueError('scan')
        def signed(args, cache):
            self.assertEqual(3, len(scans))
            self.assertEqual(output.resolve() / 'sigstore-tuf', cache)
            calls.append(['cosign', *args])
            if args[0] == 'version': return 'GitVersion: v3.0.6'
            if args[0] == 'initialize':
                self.assertEqual(['initialize', '--mirror', target.SIGSTORE_TUF_MIRROR], args)
                if reject == 'trust': raise subprocess.CalledProcessError(1, args)
                return ''
            self.assertIn(target.EXPECTED_OIDC_ISSUER, args)
            if target.EXPECTED_CERTIFICATE_IDENTITY + '.wrong' in args:
                if reject == 'wrong-signer-accepted': return ''
                raise subprocess.CalledProcessError(1, args)
            self.assertIn(target.EXPECTED_CERTIFICATE_IDENTITY, args)
            if reject == 'signature': raise subprocess.CalledProcessError(1, args)
            if args[0] == 'verify': return ''
            component = next(c for c, image in self.publication['images'].items() if image == args[-1])
            kind = args[args.index('--type') + 1]
            predicate = (target.source_predicate(component, target.recipe(), self.publication['repository_revision'],
                         self.publication['invocation_id']) if kind == 'slsaprovenance1' else read(Path('cyclonedx.json')))
            if reject == 'predicate': predicate = {'wrong': True}
            statement = {'_type': 'https://in-toto.io/Statement/v0.1',
                'predicateType': 'https://slsa.dev/provenance/v1' if kind == 'slsaprovenance1' else 'https://cyclonedx.org/bom',
                'predicate': predicate, 'subject': [{'digest': {'sha256': args[-1].split('@sha256:')[1]}}]}
            if reject == 'subject': statement['subject'] = []
            return json.dumps({'payload': base64.b64encode(json.dumps(statement).encode()).decode()})
        def native(args, **kwargs):
            calls.append(args)
            self.assertEqual(['docker', '--host', 'unix:///captured.sock'], args[:3])
            if args[3] == 'pull':
                self.assertEqual(['--platform', 'linux/amd64'], args[4:6])
                if reject == 'pull': raise subprocess.CalledProcessError(1, args)
                return ''
            image = args[-1]
            inspected = {'Id': self.engine, 'RepoDigests': [image], 'Os': 'linux', 'Architecture': 'amd64'}
            if reject == 'digest': inspected['RepoDigests'] = []
            if reject == 'platform': inspected['Architecture'] = 'arm64'
            return json.dumps(inspected)
        def smoke(component, engine, endpoint):
            self.assertEqual(self.engine, engine)
            self.assertEqual('unix:///captured.sock', endpoint)
            smokes.append(component)
            if reject == 'smoke': raise ValueError('smoke')
        with patch.object(target, 'candidate', return_value=self.selected), \
                patch.object(target, 'load', side_effect=read), \
                patch.object(target, 'validate_inventory', side_effect=scan), \
                patch.object(target, 'published_image', side_effect=lambda repo, source, config:
                    self.publication['images'][source['component']]), \
                patch.object(target, 'cosign', side_effect=signed), \
                patch.object(target, 'run', side_effect=native), \
                patch.object(target, 'local_endpoint', return_value='unix:///captured.sock'), \
                patch.object(target, 'native_entrypoint', side_effect=smoke), redirect_stdout(io.StringIO()):
            if reject:
                with self.assertRaises((ValueError, OSError, subprocess.SubprocessError)): target.verify(evidence, output)
                self.assertFalse((output / 'acceptance.json').exists())
                if reject in ('receipt', 'scan', 'existing-output'): self.assertFalse(calls or smokes)
            else:
                result = target.verify(evidence, output)
                self.assertEqual(3, len(smokes))
                self.assertEqual(result, json.loads((output / 'acceptance.json').read_text()))
                for key in ('scan', 'signature_provenance', 'wrong_signer', 'native_entrypoints'):
                    self.assertEqual('Passed', result[key])
                for key in ('certificate_issuance', 'staging', 'runtime_admission', 'deployment', 'production_promotion'):
                    self.assertEqual('Not verified', result[key])
                for args in calls:
                    self.assertNotIn(args[0], ('ssh', 'kubectl', 'gh', 'helm'))
                    self.assertFalse(args[0] == 'cosign' and args[1] in ('sign', 'attest', 'copy'))

    def test_complete_existing_set_verified_without_signing_or_installation(self):
        with tempfile.TemporaryDirectory() as parent: self.execute(parent)

    def test_each_partial_failure_preserves_no_success_receipt(self):
        for reject in ('receipt', 'scan', 'existing-output', 'trust', 'signature', 'predicate', 'subject',
                       'wrong-signer-accepted', 'pull', 'digest', 'platform', 'smoke'):
            with self.subTest(reject=reject), tempfile.TemporaryDirectory() as parent: self.execute(parent, reject)


if __name__ == '__main__':
    unittest.main()
