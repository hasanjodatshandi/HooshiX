from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

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
                "conversation": {
                    "grpc_maximum_concurrent_calls": 32,
                    "provider_maximum_concurrent_calls": 4,
                    "provider_maximum_concurrent_per_tenant": 1,
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
            "secret_refs": {
                "conversation_content": "conversation-content",
                "conversation_provider": "conversation-provider",
                "notification_providers": "notification-providers",
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
        self.assertIn("erasureRuntimeEnabled: true", values)
        self.assertIn("argon2MaxConcurrentHashes: 2", values)
        self.assertIn("publicVerifierConfigMapName: identity-jwt-public", values)
        self.assertIn("allowedAudiences: [authorization-service, conversation-service]", values)

    def test_conversation_renders_events_but_keeps_provider_fail_closed(self) -> None:
        values = render_gitops.build_values("conversation-service", self.manifest)
        self.assertIn("eventRuntimeEnabled: true", values)
        self.assertIn("providerRuntimeEnabled: false", values)
        self.assertIn("providerCanaryPercent: 0", values)
        self.assertIn("grpcMaximumConcurrentCalls: 32", values)
        self.assertIn("providerMaximumConcurrentPerTenant: 1", values)

    def test_notification_delivery_remains_disabled_until_provider_gate(self) -> None:
        values = render_gitops.build_values("notification-service", self.manifest)
        self.assertIn("deliveryRuntimeEnabled: false", values)
        self.assertIn("providerSecretName: notification-providers", values)

    def test_compromised_password_requires_production_hibp_artifact(self) -> None:
        values = render_gitops.build_values("compromised-password-service", self.manifest)
        self.assertIn("requiredSourceKind: HIBP_PWNED_PASSWORDS_SHA1", values)
        self.assertIn("expectedManifestSha256: " + "a" * 64, values)
        self.assertNotIn("GENERATED_TEST_FIXTURE", values)

    def test_web_bff_uses_exact_https_public_origin(self) -> None:
        values = render_gitops.build_values("web-bff", self.manifest)
        self.assertIn("publicOrigin: https://app.example.test", values)
        self.assertIn("erasureRuntimeEnabled: true", values)
        self.assertIn("runtimeSecretName: web-bff-db-runtime", values)

    def test_frontend_accepts_only_the_waf_identity(self) -> None:
        values = render_gitops.build_values("web-frontend", self.manifest)
        self.assertIn("app.kubernetes.io/name: edge-waf", values)
        self.assertIn("prod.sajtech.internal/ns/platform-edge/sa/edge-waf", values)

    def test_unsupported_service_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            render_gitops.build_values("unknown", self.manifest)

    def test_every_release_component_has_a_chart_and_values(self) -> None:
        for component in render_gitops.verify_release.RELEASE_COMPONENTS:
            chart = (
                render_gitops.ROOT / "apps" / component / "deploy" / "helm" / component
                if component == "web-frontend"
                else render_gitops.ROOT / "services" / component / "deploy" / "helm" / component
            )
            self.assertTrue((chart / "Chart.yaml").is_file(), component)
            self.assertTrue(render_gitops.build_values(component, self.manifest).strip(), component)

    def test_render_writes_every_release_component(self) -> None:
        digest = "b" * 64
        self.manifest.update({
            "git_revision": "c" * 40,
            "images": {
                component: f"registry.example.test/hooshix/{component}@sha256:{digest}"
                for component in render_gitops.verify_release.RELEASE_COMPONENTS
            },
            "cosign": {
                "certificate_identity": render_gitops.verify_release.EXPECTED_CERTIFICATE_IDENTITY,
                "certificate_oidc_issuer": render_gitops.verify_release.EXPECTED_OIDC_ISSUER,
            },
        })
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "render"
            with patch.object(render_gitops, "verify_helm", return_value="helm"), patch.object(
                render_gitops, "render_service", side_effect=lambda _helm, component, *_: self.manifest["images"][component]
            ):
                render_gitops.render(self.manifest, output)
            self.assertEqual(
                {f"{component}.yaml" for component in render_gitops.verify_release.RELEASE_COMPONENTS}
                | {"release-admission.yaml"},
                {path.name for path in output.iterdir()},
            )

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
        self.assertIn("object.spec.initContainers.all", policy)


if __name__ == "__main__":
    unittest.main()
