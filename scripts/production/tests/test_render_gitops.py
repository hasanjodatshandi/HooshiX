from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import render_gitops


class ProductionGitOpsRenderTest(unittest.TestCase):
    def setUp(self) -> None:
        self.manifest = {
            "public_hostname": "app.example.test",
            "service_capacity": {
                "authorization": {
                    "global_concurrency": 8,
                    "per_caller_concurrency": 4,
                    "global_queue_capacity": 8,
                    "per_caller_queue_capacity": 2,
                    "max_caller_buckets": 64,
                    "quota_max_active_buckets": 10000,
                    "quota_max_new_buckets_per_minute": 1000,
                },
                "identity": {
                    "argon2_max_concurrent_hashes": 2,
                    "compromised_password_max_in_flight": 16,
                    "quota_max_active_buckets": 10000,
                    "quota_max_new_buckets_per_minute": 1000,
                },
            },
            "compromised_password_dataset": {
                "manifest_sha256": "a" * 64,
                "max_prefix_cardinality": 2048,
                "max_serialized_response_bytes": 131072,
            },
        }

    def test_authorization_uses_measured_limits(self) -> None:
        values = render_gitops.build_values("authorization-service", self.manifest)
        self.assertIn("globalConcurrency: 8", values)
        self.assertIn("maxActiveBuckets: 10000", values)
        self.assertIn("postgresql-rw.platform-data.svc.cluster.local", values)

    def test_identity_keeps_phone_registration_off_by_default(self) -> None:
        values = render_gitops.build_values("identity-service", self.manifest)
        self.assertIn("phoneRegistrationEnabled: false", values)
        self.assertIn("argon2MaxConcurrentHashes: 2", values)
        self.assertIn("publicVerifierConfigMapName: identity-jwt-public", values)

    def test_compromised_password_requires_production_hibp_artifact(self) -> None:
        values = render_gitops.build_values("compromised-password-service", self.manifest)
        self.assertIn("requiredSourceKind: HIBP_PWNED_PASSWORDS_SHA1", values)
        self.assertIn("expectedManifestSha256: " + "a" * 64, values)
        self.assertNotIn("GENERATED_TEST_FIXTURE", values)

    def test_web_bff_uses_exact_https_public_origin(self) -> None:
        values = render_gitops.build_values("web-bff", self.manifest)
        self.assertIn("publicOrigin: https://app.example.test", values)

    def test_unsupported_service_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            render_gitops.build_values("unknown", self.manifest)

    def test_admission_policy_binds_frontend_digest_and_service_account(self) -> None:
        digest = "b" * 64
        manifest = {
            "git_revision": "c" * 40,
            "images": {
                component: f"registry.example.test/hooshix/{component}@sha256:{digest}"
                for component in render_gitops.verify_release.RELEASE_COMPONENTS
            },
            "cosign": {
                "certificate_identity": render_gitops.verify_release.EXPECTED_CERTIFICATE_IDENTITY,
                "certificate_oidc_issuer": render_gitops.verify_release.EXPECTED_OIDC_ISSUER,
            },
        }
        policy = render_gitops.admission_policy(manifest)
        self.assertIn("'web-frontend'", policy)
        self.assertIn(manifest["images"]["web-frontend"], policy)


if __name__ == "__main__":
    unittest.main()
