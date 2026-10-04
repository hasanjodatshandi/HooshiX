"""Exercise the bounded publisher without network, credentials or image downloads."""
import base64
from contextlib import redirect_stderr
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import publish_openbao_candidate as publisher


def envelope(predicate, uri, digest):
    statement = {"_type": "https://in-toto.io/Statement/v0.1", "predicate": predicate,
                 "predicateType": uri, "subject": [{"digest": {"sha256": digest}}]}
    return json.dumps({"payload": base64.b64encode(json.dumps(statement).encode()).decode()})


class OpenBaoPublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name) / "evidence"
        self.env = {"GITHUB_ACTIONS": "true", "GITHUB_REF": "refs/heads/main",
                    "GITHUB_REPOSITORY": "hasanjodatshandi/HooshiX",
                    "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_SHA": "a" * 40,
                    "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1",
                    "GITHUB_WORKFLOW_REF": publisher.EXPECTED_CERTIFICATE_IDENTITY.removeprefix("https://github.com/")}
        self.pin = publisher.artifact.load(publisher.artifact.ROOT / "infrastructure/production/secrets/openbao-image.json")
        self.digest = self.pin["image"].split("@sha256:")[1]
        self.image = publisher.REPOSITORY + "@sha256:" + self.digest
        self.calls = []
        self.visibility, self.severity, self.wrong_digest = "private\n", None, False
        self.fail = None

    def mock_run(self, argv, timeout=180):
        self.calls.append(argv)
        if argv[:2] == ["cosign", "copy"] and any(value.startswith("--platform") for value in argv):
            # Cosign 3.0.6 SignedEntityForPlatform rejects a single-image manifest.
            raise subprocess.CalledProcessError(1, argv, stderr="specified reference is not a multiarch image")
        if self.fail and argv[:len(self.fail)] == self.fail:
            raise subprocess.CalledProcessError(1, argv, stderr="synthetic sensitive error")
        if argv[1] == "version":
            return {"syft": "Version: 1.51.0", "grype": "Version: 0.117.0", "cosign": "GitVersion: v3.0.6"}[argv[0]]
        if argv[:2] == ["gh", "api"]:
            return self.visibility
        if argv[:4] == ["grype", "db", "status", "-o"]:
            return json.dumps({"valid": True, "schemaVersion": "6.0.0", "built": datetime.now(timezone.utc).isoformat()})
        if argv[:2] == ["syft", "scan"]:
            metadata = {"manifestDigest": "sha256:" + ("0" * 64 if self.wrong_digest else self.digest),
                        "os": "linux", "architecture": "amd64", "repoDigests": [self.image], "labels": {
                            "org.opencontainers.image.version": "v" + self.pin["version"],
                            "org.opencontainers.image.revision": self.pin["source_revision"]}}
            (self.directory / "syft.json").write_text(json.dumps({"source": {"type": "image", "metadata": metadata}, "artifacts": [{}]}))
            (self.directory / "cyclonedx.json").write_text(json.dumps({"bomFormat": "CycloneDX", "components": [{}]}))
        if argv[:2] == ["grype", "sbom:" + str(self.directory / "syft.json")]:
            return json.dumps({"matches": [] if self.severity is None else [{"vulnerability": {"severity": self.severity}}]})
        if argv[:2] == ["cosign", "verify-attestation"]:
            kind = argv[argv.index("--type") + 1]
            if kind == "slsaprovenance1":
                predicate = json.loads((self.directory / "import-provenance.json").read_text())
                return envelope(predicate, "https://slsa.dev/provenance/v1", self.digest)
            return envelope(json.loads((self.directory / "cyclonedx.json").read_text()), "https://cyclonedx.org/bom", self.digest)
        if argv[:2] == ["cosign", "verify"] and any(value.endswith(".wrong") for value in argv):
            raise subprocess.CalledProcessError(1, argv)
        return ""

    def publish(self):
        with patch.object(publisher, "run", side_effect=self.mock_run):
            return publisher.publish(self.directory, self.env)

    def assert_not_signed(self):
        self.assertFalse(any(argv[:2] == ["cosign", "sign"] for argv in self.calls))

    def test_private_same_digest_signed_candidate_never_deploys(self):
        receipt = self.publish()
        self.assertEqual("Passed", receipt["publication"])
        self.assertEqual("Passed", receipt["signature_provenance"])
        self.assertEqual(self.image, receipt["image"])
        self.assertEqual("unchanged-upstream-import", receipt["provenance_kind"])
        for key in ("deployment", "runtime_admission", "staging", "production_promotion", "upstream_build_provenance"):
            self.assertEqual("Not verified", receipt[key])
        copy_call = next(argv for argv in self.calls if argv[:2] == ["cosign", "copy"])
        self.assertEqual(["cosign", "copy", self.pin["image"],
                          publisher.REPOSITORY + ":candidate-" + self.digest], copy_call)
        self.assertNotIn("-f", copy_call)
        self.assertLess(self.calls.index(next(argv for argv in self.calls if argv[0] == "gh")),
                        self.calls.index(["cosign", "sign", "--yes", self.image]))
        self.assertEqual(0o700, self.directory.stat().st_mode & 0o777)

    def test_nonprivate_visibility_copy_api_db_scan_and_sign_errors_fail_closed(self):
        for failure in (["cosign", "copy"], ["gh", "api"], ["grype", "db", "update"],
                        ["syft", "scan"], ["grype", "sbom:" + str(self.directory / "syft.json")],
                        ["cosign", "sign"], ["cosign", "attest"], ["cosign", "verify-attestation"]):
            with self.subTest(failure=failure):
                self.directory = Path(self.temp.name) / ("evidence-" + str(len(self.calls)))
                self.fail = ["grype", "sbom:" + str(self.directory / "syft.json")] if failure[0] == "grype" and failure[1].startswith("sbom:") else failure
                with self.assertRaises(subprocess.CalledProcessError):
                    self.publish()
                self.assertFalse((self.directory / "receipt.json").exists())
                attempt = json.loads((self.directory / "attempt.json").read_text())
                self.assertEqual("Not verified", attempt["publication"])
                self.assertEqual("attempt-only-not-success-receipt", attempt["purpose"])
        self.fail = None
        for index, visibility in enumerate(("public", "internal", "", "private\npublic")):
            self.directory = Path(self.temp.name) / ("visibility-" + str(index))
            self.calls = []
            self.visibility = visibility
            with self.subTest(visibility=visibility), self.assertRaises(ValueError):
                self.publish()
            self.assert_not_signed()

    def test_wrong_digest_and_blocking_vulnerabilities_stop_before_signing(self):
        for index, (severity, wrong_digest) in enumerate((("High", False), ("Critical", False), (None, True))):
            self.directory = Path(self.temp.name) / ("scan-" + str(index))
            self.calls = []
            self.severity, self.wrong_digest = severity, wrong_digest
            with self.subTest(severity=severity, wrong_digest=wrong_digest), self.assertRaises(ValueError):
                self.publish()
            self.assert_not_signed()

    def test_nonmain_wrong_workflow_repo_event_and_unbounded_ci_input_rejected(self):
        for field, value in (("GITHUB_REF", "refs/heads/feature"), ("GITHUB_WORKFLOW_REF", "other"),
                             ("GITHUB_REPOSITORY", "other/repo"), ("GITHUB_EVENT_NAME", "pull_request"),
                             ("GITHUB_RUN_ID", "1\n::error::"), ("GITHUB_SHA", "branch")):
            env = self.env | {field: value}
            with self.subTest(field=field), patch.object(publisher, "run") as run, self.assertRaises(ValueError):
                publisher.publish(self.directory, env)
            run.assert_not_called()

    def test_wrong_tool_version_and_wrong_signer_acceptance_cannot_create_receipt(self):
        def wrong_tool(argv, timeout=180):
            return "Version: 9.0.0" if argv[:2] == ["syft", "version"] else self.mock_run(argv, timeout)
        with patch.object(publisher, "run", side_effect=wrong_tool), self.assertRaises(ValueError):
            publisher.publish(self.directory, self.env)
        self.assert_not_signed()
        self.directory = Path(self.temp.name) / "wrong-signer"
        def wrong_signer(argv, timeout=180):
            if argv[:2] == ["cosign", "verify"] and any(value.endswith(".wrong") for value in argv):
                return "unexpected success"
            return self.mock_run(argv, timeout)
        with patch.object(publisher, "run", side_effect=wrong_signer), self.assertRaises(ValueError):
            publisher.publish(self.directory, self.env)
        self.assertFalse((self.directory / "receipt.json").exists())

    def test_signed_payload_wrong_type_predicate_digest_and_missing_fail_closed(self):
        predicate = {"public": "value"}
        publisher.verify_payload(envelope(predicate, "test", self.digest), "test", predicate, self.digest)
        for output in ("", envelope(predicate, "other", self.digest), envelope({}, "test", self.digest),
                       envelope(predicate, "test", "0" * 64)):
            with self.subTest(output=output), self.assertRaises(ValueError):
                publisher.verify_payload(output, "test", predicate, self.digest)

    def test_workflow_is_manual_protected_existing_signer_not_a_deploy_path(self):
        text = (publisher.artifact.ROOT / ".github/workflows/production-release.yml").read_text()
        job = text.split("  openbao-candidate:\n")[1]
        for expected in ("environment: production-release", "inputs.release_kind == 'openbao-candidate'",
                         "--commit", "--event push --status success", "packages: write", "actions: read",
                         "GHCR_TOKEN: ${{ github.token }}", "--password-stdin", "if: always()",
                         "publish_openbao_candidate.py", "retention-days: 30"):
            self.assertIn(expected, job)
        for forbidden in ("secrets.", "KUBECONFIG", "kubectl", "continue-on-error", "pull_request_target"):
            self.assertNotIn(forbidden, job)
        self.assertIn("verify_release.py", text.split("  openbao-candidate:\n")[0])

    def test_tool_diagnostics_are_finite_categories_and_never_raw_errors(self):
        for message, category in (("specified reference is not a multiarch image", "single-manifest-platform-selection"),
                                  ("UNAUTHORIZED", "registry-unauthorized"), ("DENIED", "registry-denied"),
                                  ("already exists. Use `-f`", "destination-conflict"),
                                  ("other native error", "tool-error")):
            output = io.StringIO()
            error = subprocess.CalledProcessError(1, ["cosign", "copy", "synthetic-private-argv"],
                                                  output="synthetic-private-stdout",
                                                  stderr=message + "\nsynthetic-private-token\n::error::untrusted")
            with self.subTest(category=category), patch.object(publisher.subprocess, "run", side_effect=error), \
                    redirect_stderr(output), self.assertRaises(subprocess.CalledProcessError):
                publisher.run(["cosign", "copy", "synthetic-private-argv"], timeout=300)
            self.assertEqual("OPENBAO_TOOL_FAILURE=" + category + "\n", output.getvalue())


if __name__ == "__main__":
    unittest.main()
