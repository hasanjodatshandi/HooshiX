from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import external_runtime_evidence


class ExternalRuntimeEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.evidence = {
            "schema": external_runtime_evidence.SCHEMA,
            "git_revision": "a" * 40,
            "environment": "staging",
            "recorded_at": "2026-08-31T10:00:00Z",
            "hibp": {
                "executed": True,
                "source_kind": "HIBP_PWNED_PASSWORDS_COMPLETE_DOWNLOAD",
                "source_sha256": "b" * 64,
                "source_age_days": 1,
                "record_count": 1,
                "observed_max_prefix_cardinality": 1,
                "observed_max_serialized_response_bytes": 1,
                "cold_p99_ms": 1,
                "warm_p99_ms": 1,
                "saturation_concurrency": 2,
                "rebuild_redeploy_recovery": True,
                "passed": True,
            },
            "providers": {
                "email": {
                    "provider": "GOOGLE_GMAIL",
                    "executed": True,
                    "starttls_auth": True,
                    "hostname_verified": True,
                    "definitive_acceptance": True,
                    "auth_failure": True,
                    "ambiguity": True,
                    "passed": True,
                },
                "sms": {
                    "provider": "SMSIR_SANDBOX",
                    "executed": True,
                    "tls_hostname_verified": True,
                    "authentication_success": True,
                    "request_validation": True,
                    "simulated_acceptance": True,
                    "auth_failure": True,
                    "adapter_ambiguity": True,
                    "passed": True,
                },
            },
            "erasure": {
                "executed": True,
                "redeploy_completed": True,
                "restore_completed": True,
                "participant_count": 4,
                "identity_deleted": True,
                "no_reappearance": True,
                "passed": True,
            },
            "passed": True,
        }

    def test_accepts_complete_aggregate_evidence_without_identifiers_or_secrets(self) -> None:
        self.assertEqual([], external_runtime_evidence.validate_evidence(self.evidence))

    def test_rejects_fixture_corpus_stale_source_and_missing_provider_execution(self) -> None:
        evidence = copy.deepcopy(self.evidence)
        evidence["hibp"]["source_kind"] = "GENERATED_TEST_FIXTURE"
        evidence["hibp"]["source_age_days"] = 36
        evidence["providers"]["sms"]["executed"] = False
        errors = external_runtime_evidence.validate_evidence(evidence)
        self.assertTrue(any("complete-corpus" in error for error in errors))
        self.assertTrue(any("readiness bound" in error for error in errors))
        self.assertTrue(any("sms.executed" in error for error in errors))

    def test_rejects_partial_erasure_and_unknown_evidence_fields(self) -> None:
        evidence = copy.deepcopy(self.evidence)
        evidence["erasure"]["participant_count"] = 3
        evidence["providers"]["email"]["credential"] = "must-not-be-recorded"
        errors = external_runtime_evidence.validate_evidence(evidence)
        self.assertTrue(any("participant_count" in error for error in errors))
        self.assertTrue(any("email structure" in error for error in errors))

    def test_rejects_any_sandbox_real_delivery_claim(self) -> None:
        evidence = copy.deepcopy(self.evidence)
        evidence["providers"]["sms"]["real_delivery_claimed"] = True
        errors = external_runtime_evidence.validate_evidence(evidence)
        self.assertTrue(any("sms structure" in error for error in errors))

    def test_rejects_wrong_provider_identities(self) -> None:
        evidence = copy.deepcopy(self.evidence)
        evidence["providers"]["email"]["provider"] = "GENERIC_SMTP"
        evidence["providers"]["sms"]["provider"] = "OTHER"
        errors = external_runtime_evidence.validate_evidence(evidence)
        self.assertTrue(any("email.provider" in error for error in errors))
        self.assertTrue(any("sms.provider" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
