"""Candidate Redis security controls do not approve a production installation."""
import copy
import hashlib
import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import render_redis_security as target
import build_redis_candidate as candidate


class RedisSecurityTest(unittest.TestCase):
    def setUp(self):
        self.hashes = {name: hashlib.sha256(('synthetic-' + name).encode()).hexdigest()
                       for name in target.USERS}

    def test_tls_only_and_bounded_persistence_configuration(self):
        config = target.server_config(64 * 1024**2)
        for line in ('port 0', 'tls-port 6379', 'maxmemory-policy noeviction',
                     'appendonly yes', 'appendfsync everysec', 'aof-load-truncated no',
                     'no-appendfsync-on-rewrite no', 'protected-mode yes', 'databases 1'):
            self.assertIn(line + '\n', config)
        self.assertIn('tls-auth-clients no\n', config)  # TLS + ACL; mesh identity is independent.
        self.assertNotIn('requirepass', config)
        for invalid in (True, False, 0, -1, '67108864', 16 * 1024**3 + 1):
            with self.subTest(value=invalid), self.assertRaises(ValueError):
                target.server_config(invalid)

    def test_runtime_key_namespaces_and_commands_are_allowlisted(self):
        acl = target.acl_file(self.hashes)
        self.assertTrue(acl.startswith('user default reset off\n'))
        for user, patterns in target.PATTERNS.items():
            line = next(line for line in acl.splitlines() if line.startswith('user ' + user + ' '))
            self.assertIn('-@all ', line)
            for pattern in patterns:
                self.assertIn('~' + pattern + ' ', line)
            self.assertIn('+eval', line)
            self.assertIn('+info|memory', line)
            for forbidden in ('+@all', '~*', '+config', '+acl', '+monitor', '+keys',
                              '+scan', '+flushall', '+flushdb', '+script', '+module'):
                self.assertNotIn(forbidden, line)
        for user in ('health', 'monitor'):
            line = next(line for line in acl.splitlines() if line.startswith('user ' + user + ' '))
            self.assertNotIn('~', line)
            self.assertNotIn('+get', line)

    def test_hash_validation_rejects_partial_shared_or_injected_credentials(self):
        for hashes in ({}, self.hashes | {'extra': 'a' * 64},
                       self.hashes | {'identity': self.hashes['authorization']},
                       self.hashes | {'identity': 'password'},
                       self.hashes | {'identity': 'a' * 64 + '\nuser default on nopass +@all'}):
            with self.subTest(hashes=hashes), self.assertRaises(ValueError):
                target.acl_file(hashes)

    def test_source_pin_and_security_contract_cannot_be_promoted_or_weakened(self):
        pin = self.pin = target.pins()
        policy = target.load(target.ROOT / 'infrastructure/production/data/data-policy.json')
        for change in ({'production_promotion': 'Passed'}, {'application_ingress': 'OPEN'},
                       {'image': 'redis:latest'}, {'version': '8.2.9'},
                       {'runtime_users': {'identity': ['*']}}):
            with self.subTest(change=change), patch.object(target, 'load',
                    side_effect=[pin | change, policy]), self.assertRaises(ValueError):
                target.pins()
        for key, value in (('tls_required', False), ('maxmemory_policy', 'allkeys-lru'),
                           ('appendfsync', 'no'), ('acl_isolation', False), ('aof', False)):
            changed = copy.deepcopy(policy)
            changed['redis'][key] = value
            with patch.object(target, 'load', side_effect=[pin, changed]), self.assertRaises(ValueError):
                target.pins()


class RedisCandidateScanTest(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 10, 17, tzinfo=timezone.utc)
        pin = self.pin = target.pins()
        self.files = {'syft.json': {'source': {'type': 'image', 'metadata': {
            'imageID': 'sha256:' + 'a' * 64, 'architecture': 'amd64', 'os': 'linux',
            'labels': {'org.opencontainers.image.version': pin['version'],
                       'io.hooshix.provenance.kind': pin['provenance_kind'],
                       'io.hooshix.redis.source.revision': pin['source_revision']}}},
            'distro': {'id': 'alpine', 'versionID': '3.24.2'},
            'artifacts': [{'name': name, 'version': version} for name, version in {
                'libcrypto3': '3.5.9-r0', 'libssl3': '3.5.9-r0', 'zlib': '1.3.2-r1',
                'libgcc': '15.2.0-r5', 'libstdc++': '15.2.0-r5'}.items()],
            'descriptor': {'name': 'syft', 'version': '1.51.0', 'configuration': {
                'search': {'scope': 'squashed'}, 'catalogers': {
                    'requested': {'default': ['image', 'file']}, 'used': [
                        'go-module-binary-cataloger', 'binary-classifier-cataloger',
                        'python-installed-package-cataloger', 'dpkg-db-cataloger',
                        'rpm-db-cataloger', 'apk-db-cataloger', 'elf-binary-package-cataloger']}}}},
            'grype.json': {'source': {'target': {'imageID': 'sha256:' + 'a' * 64}}, 'matches': []},
            'cyclonedx.json': {'bomFormat': 'CycloneDX', 'components': [{}]},
            'database.json': {'valid': True, 'schemaVersion': '6', 'built': self.now.isoformat()}}

    def validate(self):
        with patch.object(candidate, 'pins', return_value=self.pin), \
                patch.object(candidate, 'load', side_effect=lambda path: self.files[path.name]), \
                patch.object(Path, 'read_bytes', return_value=b'public'):
            return candidate.validate_candidate_scan(Path('evidence'), self.now)

    def test_complete_scan_receipt_does_not_grant_deployment_or_signature_authority(self):
        result = self.validate()
        self.assertEqual('Passed', result['scan'])
        self.assertEqual(4, len(result['sha256']))
        self.assertNotIn('production_readiness', result)
        self.assertNotIn('signature', result)

    def test_unsuppressed_high_critical_invalid_or_missing_scan_is_rejected(self):
        for severity in ('High', 'Critical', 'unknown-schema'):
            for key in ('matches', 'ignoredMatches'):
                self.files['grype.json'][key] = [{'vulnerability': {'severity': severity}}]
                with self.subTest(severity=severity, key=key), self.assertRaises(ValueError):
                    self.validate()
                self.files['grype.json'][key] = []
        self.files['grype.json'].pop('matches')
        with self.assertRaises(ValueError):
            self.validate()

    def test_stale_future_invalid_or_naive_scanner_database_is_rejected(self):
        original = self.files['database.json'].copy()
        for change in ({'valid': False}, {'schemaVersion': ''},
                       {'built': (self.now - timedelta(days=6)).isoformat()},
                       {'built': (self.now + timedelta(seconds=1)).isoformat()},
                       {'built': '2026-10-17T00:00:00'}):
            self.files['database.json'] = original | change
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.validate()

    def test_wrong_configuration_architecture_source_packages_and_distro_are_rejected(self):
        original = copy.deepcopy(self.files)
        cases = [('imageID', 'sha256:' + 'b' * 64), ('architecture', 'arm64'), ('os', 'windows')]
        for key, value in cases:
            self.files = copy.deepcopy(original)
            self.files['syft.json']['source']['metadata'][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.validate()
        for mutate in (
                lambda data: data['source'].update(type='directory'),
                lambda data: data['distro'].update(versionID='3.22.0'),
                lambda data: data['artifacts'][0].update(version='old'),
                lambda data: data['source']['metadata'].update(labels={}),
                lambda data: data['descriptor']['configuration'].update(exclude=['/usr/**'])):
            self.files = copy.deepcopy(original)
            mutate(self.files['syft.json'])
            with self.assertRaises(ValueError):
                self.validate()

    def test_dockerfile_preserves_exact_inputs_nonroot_entrypoint_and_source_offer(self):
        pin = target.pins()
        text = (target.PIN.parent / 'Dockerfile').read_text()
        for item in (pin['image'], pin['runtime_base'], pin['source_archive_sha256'],
                     'USER 10001:10001', 'ENTRYPOINT ["/usr/local/bin/redis-server"]',
                     '/usr/share/redis-source/source.tar.gz', 'libssl3=3.5.9-r0'):
            self.assertIn(item, text)
        self.assertNotIn(':latest', text)
        self.assertNotIn('nopass', text)


if __name__ == '__main__':
    unittest.main()
