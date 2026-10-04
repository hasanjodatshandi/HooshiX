import copy
import contextlib
import io
import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_openbao_artifact as verifier


class OpenBaoArtifactTest(unittest.TestCase):
    def setUp(self):
        self.pin = verifier.load(verifier.ROOT / "infrastructure/production/secrets/openbao-image.json")
        self.now = datetime(2026, 10, 4, tzinfo=timezone.utc)
        self.values = {"syft.json": {"source": {"type": "image", "metadata": {
            "manifestDigest": self.pin["image"].split("@")[1], "os": "linux",
            "architecture": "amd64", "repoDigests": [self.pin["image"]], "labels": {
                "org.opencontainers.image.version": "v" + self.pin["version"],
                "org.opencontainers.image.revision": self.pin["source_revision"]}}}, "artifacts": [{}]},
            "cyclonedx.json": {"bomFormat": "CycloneDX", "components": [{}]},
            "grype.json": {"matches": []}, "database.json": {
                "valid": True, "schemaVersion": "6.0.0", "built": self.now.isoformat()}}

    def validate(self, image=None):
        def read(path):
            return self.pin if path.name == "openbao-image.json" else self.values[path.name]
        with patch.object(verifier, "load", side_effect=read), patch.object(Path, "read_bytes", return_value=b"public"):
            return verifier.validate(Path("evidence"), "a" * 40, self.now, image=image)

    def test_mirror_must_keep_exact_owned_repository_digest_and_catalog_binding(self):
        mirror = "ghcr.io/hasanjodatshandi/hooshix/platform-openbao-private@" + self.pin["image"].split("@")[1]
        self.values["syft.json"]["source"]["metadata"]["repoDigests"] = [mirror]
        self.assertEqual(mirror, self.validate(image=mirror)["image"])
        for image in (mirror.replace("hasanjodatshandi", "other"), mirror.replace("platform-openbao", "other"),
                      mirror.replace("platform-openbao-private", "platform-openbao"),
                      mirror.rsplit("@", 1)[0] + ":latest", mirror[:-64] + "0" * 64):
            # A matching catalog must not make an unapproved repository valid.
            self.values["syft.json"]["source"]["metadata"]["repoDigests"] = [image]
            with self.subTest(image=image), self.assertRaises(ValueError):
                self.validate(image=image)
        self.values["syft.json"]["source"]["metadata"]["repoDigests"] = [mirror]
        with self.assertRaises(ValueError):
            self.validate()  # Upstream CI cannot silently accept mirror catalog metadata.

    def test_candidate_receipt_never_approves_promotion(self):
        receipt = self.validate()
        self.assertEqual("Passed", receipt["scan"])
        self.assertEqual("Not verified", receipt["production_promotion"])
        self.assertEqual("Not verified", receipt["signature_provenance"])
        self.assertEqual(self.pin["image"], receipt["image"])
        self.assertEqual(4, len(receipt["sha256"]))

    def test_wrong_digest_architecture_repository_and_nonimage_rejected(self):
        original = copy.deepcopy(self.values)
        for field, invalid in (("manifestDigest", "sha256:" + "0" * 64),
                               ("os", "windows"), ("architecture", "arm64"), ("repoDigests", [])):
            self.values = copy.deepcopy(original)
            self.values["syft.json"]["source"]["metadata"][field] = invalid
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.validate()
        self.values = original
        self.values["syft.json"]["source"]["type"] = "directory"
        with self.assertRaises(ValueError):
            self.validate()

    def test_database_invalid_stale_future_and_naive_time_rejected(self):
        original = copy.deepcopy(self.values["database.json"])
        for changes in ({"valid": False}, {"error": "failed"}, {"schemaVersion": ""},
                        {"built": (self.now - timedelta(days=6)).isoformat()},
                        {"built": (self.now + timedelta(seconds=1)).isoformat()},
                        {"built": "2026-10-04T00:00:00"}, {"built": "bad"}):
            self.values["database.json"] = original | changes
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.validate()

    def test_source_version_and_revision_must_match_security_patch(self):
        labels = self.values["syft.json"]["source"]["metadata"]["labels"]
        for key, invalid in (("org.opencontainers.image.version", "v2.6.1"),
                             ("org.opencontainers.image.revision", "0" * 40)):
            original = labels[key]
            labels[key] = invalid
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.validate()
            labels[key] = original

    def test_high_critical_unknown_schema_and_missing_scan_rejected(self):
        for severity in ("High", "Critical", "new-schema"):
            self.values["grype.json"] = {"matches": [{"vulnerability": {"severity": severity}}]}
            with self.subTest(severity=severity), self.assertRaises(ValueError):
                self.validate()
        self.values["grype.json"] = {}
        with self.assertRaises(ValueError):
            self.validate()

    def test_empty_sbom_and_tag_and_unblocked_pin_rejected(self):
        self.values["cyclonedx.json"]["components"] = []
        with self.assertRaises(ValueError):
            self.validate()
        self.values["cyclonedx.json"]["components"] = [{}]
        self.pin["production_promotion"] = "approved"
        with self.assertRaises(ValueError):
            self.validate()
        self.pin["production_promotion"] = "blocked-until-supply-chain-staging-and-recovery-evidence"
        self.pin["image"] = "ghcr.io/openbao/openbao-distroless:2.6.4"
        with self.assertRaises(ValueError):
            self.validate()

    def test_loader_rejects_symlinks_large_files_and_nonobjects(self):
        for symlink, size, payload in ((True, 1, b"{}"), (False, verifier.MAX_BYTES + 1, b"{}"),
                                       (False, 1, b"[]")):
            with patch.object(Path, "is_symlink", return_value=symlink), \
                    patch.object(Path, "is_file", return_value=True), \
                    patch.object(Path, "stat") as stat, patch.object(Path, "read_bytes", return_value=payload):
                stat.return_value.st_size = size
                with self.assertRaises(ValueError):
                    verifier.load(Path("input"))

    def test_ci_gate_has_no_credentials_and_requires_scan_success(self):
        workflow = (verifier.ROOT / ".github/workflows/repository-baseline.yml").read_text()
        job = workflow.split("  openbao-artifact:\n")[1].split("  compromised-password-security:\n")[0]
        self.assertIn("--fail-on high", job)
        self.assertIn("timeout 180 grype db update", job)
        self.assertIn("--from registry --platform linux/amd64", job)
        self.assertIn("if: ${{ always() }}", job)
        for forbidden in ("secrets.", "id-token:", "continue-on-error", "--only-fixed", "cosign sign"):
            self.assertNotIn(forbidden, job)
        self.assertIn("- openbao-artifact", workflow)
        self.assertIn('if [ "${OPENBAO_ARTIFACT_RESULT}" !=', workflow)

    def test_failed_scan_diagnostics_escape_commands_and_do_not_validate(self):
        scan = {"matches": [{"vulnerability": {"severity": "High", "id": "CVE-test\n::error::%"},
                             "artifact": {"name": "pkg", "version": "1"}}]}
        output = io.StringIO()
        with patch.object(sys, "argv", ["verify", "--report", "--evidence-dir", "ci", "--revision", "a" * 40]), \
                patch.object(verifier, "load", return_value=scan), \
                patch.object(verifier, "validate") as validate, contextlib.redirect_stdout(output):
            self.assertEqual(0, verifier.main())
            validate.assert_not_called()
        self.assertIn("OPENBAO_BLOCKING_FINDINGS=1", output.getvalue())
        self.assertNotIn("\n::error::", output.getvalue())
        self.assertIn("%25", output.getvalue())


if __name__ == "__main__":
    unittest.main()
