"""Exact isolated scope, units, authenticated principals and fail-closed profile validation."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import kafka_quota_profile as profile


class KafkaQuotaProfileTest(unittest.TestCase):
    def test_exact_rates_cover_only_five_authenticated_application_users(self):
        value = profile.load()
        self.assertEqual(5, len(value))
        self.assertEqual({profile.clients.entry(s)['principal'] for s in profile.clients.SERVICES}, set(value))
        for quota in value.values():
            self.assertEqual({'producer_byte_rate': 64 * 1024, 'consumer_byte_rate': 256 * 1024}, quota)
        self.assertNotIn('CN=kafka-node-1', value)
        self.assertTrue(all('compromised' not in user for user in value))

    def test_drift_unknown_fields_wrong_scope_and_production_claims_are_rejected(self):
        original = json.loads(profile.PATH.read_bytes())
        changes = [{key: 'wrong'} for key in original]
        changes += [{'producer_byte_rate': 65536.0}, {'schema_version': True},
                    {'consumer_byte_rate': 262144.0}, {'request_percentage': 5}]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'profile.json'
            for change in changes:
                with self.subTest(change=change):
                    path.write_text(json.dumps({**original, **change}))
                    with self.assertRaises(ValueError):
                        profile.load(path)

    def test_profile_is_reloaded_without_shared_mutable_state(self):
        first = profile.load()
        first[next(iter(first))]['producer_byte_rate'] = 1
        self.assertTrue(all(q['producer_byte_rate'] == 65536 for q in profile.load().values()))


if __name__ == '__main__':
    unittest.main()
