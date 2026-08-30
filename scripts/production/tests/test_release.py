from __future__ import annotations

import copy
import datetime as dt
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_release


class ProductionReleaseManifestTest(unittest.TestCase):
    def setUp(self) -> None:
        self.now = dt.datetime(2026, 8, 22, tzinfo=dt.timezone.utc)
        digest = "a" * 64
        self.manifest = {
            "schema_version": 1,
            "profile": "production-single-server",
            "git_revision": "b" * 40,
            "public_hostname": "app.example.test",
            "images": {
                service: f"registry.example.test/hooshix/{service}@sha256:{digest}"
                for service in verify_release.RELEASE_COMPONENTS
            },
            "cosign": {
                "certificate_identity": "https://github.com/hasanjodatshandi/HooshiX/.github/workflows/production-release.yml@refs/heads/main",
                "certificate_oidc_issuer": "https://token.actions.githubusercontent.com",
            },
            "external_l4_source_cidrs": ["203.0.113.0/24"],
            "capacity_evidence": {
                "reference": "evidence/capacity/2026-08-22",
                "passed": True,
                "cpu_headroom_percent": 35,
                "memory_headroom_percent": 40,
            },
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
                "source_kind": "HIBP_PWNED_PASSWORDS_SHA1",
                "artifact_sha256": "c" * 64,
                "manifest_sha256": "d" * 64,
                "retrieval_completed_at": "2026-08-01T00:00:00Z",
                "max_prefix_cardinality": 2048,
                "max_serialized_response_bytes": 131072,
            },
            "external_evidence": {
                "external_blackbox_monitor": "evidence/monitor/2026-08-22",
                "offhost_security_audit_sink": "evidence/audit/2026-08-22",
                "offsite_postgresql_backup_target": "evidence/postgresql-backup/2026-08-22",
                "openbao_unseal_custody": "evidence/openbao-custody/2026-08-22",
                "upstream_ddos_provider": "evidence/ddos/2026-08-22",
                "wireguard_peer_inventory": "evidence/wireguard/2026-08-22",
                "cold_dr_exercise": "evidence/cold-dr/2026-08-22",
                "notification_provider_delivery": "evidence/notification/2026-08-22",
            },
            "secret_refs": {
                "production_tls": "production-tls",
                "postgresql_backup": "postgresql-backup",
                "openbao": "openbao-bootstrap",
                "redis_tls": "redis-tls",
                "kafka_tls": "kafka-tls",
                "notification_providers": "notification-providers",
            },
        }

    def errors(self, manifest: dict) -> list[str]:
        return verify_release.validate_manifest(manifest, now=self.now)

    def test_valid_manifest_passes(self) -> None:
        self.assertEqual([], self.errors(self.manifest))

    def test_rejects_tagged_or_non_digest_image(self) -> None:
        data = copy.deepcopy(self.manifest)
        data["images"]["identity-service"] = "registry.example.test/hooshix/identity-service:latest"
        self.assertTrue(any("images.identity-service" in e for e in self.errors(data)))

    def test_requires_frontend_release_image(self) -> None:
        data = copy.deepcopy(self.manifest)
        del data["images"]["web-frontend"]
        self.assertTrue(any("six application release components" in e for e in self.errors(data)))

    def test_rejects_wildcard_signer(self) -> None:
        data = copy.deepcopy(self.manifest)
        data["cosign"]["certificate_identity"] = "https://github.com/hasanjodatshandi/*"
        self.assertTrue(any("certificate_identity" in e for e in self.errors(data)))

    def test_rejects_default_route_cidr(self) -> None:
        data = copy.deepcopy(self.manifest)
        data["external_l4_source_cidrs"] = ["0.0.0.0/0"]
        self.assertTrue(any("default-route" in e for e in self.errors(data)))

    def test_rejects_stale_hibp_dataset(self) -> None:
        data = copy.deepcopy(self.manifest)
        data["compromised_password_dataset"]["retrieval_completed_at"] = "2026-06-01T00:00:00Z"
        self.assertTrue(any("no older than 35 days" in e for e in self.errors(data)))

    def test_rejects_insufficient_capacity_headroom(self) -> None:
        data = copy.deepcopy(self.manifest)
        data["capacity_evidence"]["memory_headroom_percent"] = 29.9
        self.assertTrue(any("memory_headroom_percent" in e for e in self.errors(data)))

    def test_rejects_zero_service_capacity(self) -> None:
        data = copy.deepcopy(self.manifest)
        data["service_capacity"]["identity"]["argon2_max_concurrent_hashes"] = 0
        self.assertTrue(any("argon2_max_concurrent_hashes" in e for e in self.errors(data)))

    def test_rejects_placeholder_external_evidence(self) -> None:
        data = copy.deepcopy(self.manifest)
        data["external_evidence"]["cold_dr_exercise"] = "TBD"
        self.assertTrue(any("cold_dr_exercise" in e for e in self.errors(data)))

    def test_rejects_secret_value_in_reference_field(self) -> None:
        data = copy.deepcopy(self.manifest)
        data["secret_refs"]["openbao"] = "https://user:password@example.test"
        self.assertTrue(any("secret_refs.openbao" in e for e in self.errors(data)))

    def test_rejects_unknown_top_level_field(self) -> None:
        data = copy.deepcopy(self.manifest)
        data["unsafe_extra"] = True
        self.assertTrue(any("top-level" in e for e in self.errors(data)))


if __name__ == "__main__":
    unittest.main()
