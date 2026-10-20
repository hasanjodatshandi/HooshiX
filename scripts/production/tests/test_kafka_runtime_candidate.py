"""Kafka candidate remains pinned, scanned, isolated, private, and non-deploying."""
import copy
import io
import json
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_kafka_candidate as build
import publish_kafka_candidate as publish


class KafkaCandidateScanTest(unittest.TestCase):
    def setUp(self):
        self.pin = build.pins()
        self.image = "sha256:" + "a" * 64
        self.now = datetime(2026, 10, 20, tzinfo=timezone.utc)
        self.files = {
            "syft.json": {"source": {"type": "image", "metadata": {
                "imageID": self.image, "userInput": self.image, "architecture": "amd64", "os": "linux",
                "labels": {"org.opencontainers.image.version": "4.2.2",
                    "org.opencontainers.image.source": "https://github.com/hasanjodatshandi/HooshiX",
                    "io.hooshix.provenance.kind": "upstream-runtime-security-overlay",
                    "io.hooshix.kafka.upstream.image": self.pin["upstream_image"]}}},
                "artifacts": [{"name": name, "version": version} for name, version in {
                    **self.pin["apk_packages"],
                    **{item["artifact"]: item["version"] for item in self.pin["maven_overrides"]}}.items()],
                "descriptor": {"name": "syft", "version": "1.51.0", "configuration": {
                    "exclude": [], "search": {"scope": "squashed"}, "catalogers": {
                        "requested": {"default": ["file", "image"]}, "used": [
                            "go-module-binary-cataloger", "binary-classifier-cataloger",
                            "python-installed-package-cataloger", "dpkg-db-cataloger",
                            "rpm-db-cataloger", "apk-db-cataloger", "elf-binary-package-cataloger"]}}}},
            "cyclonedx.json": {"bomFormat": "CycloneDX", "components": [{}]},
            "grype.json": {"source": {"target": {"userInput": self.image, "imageID": self.image}},
                            "matches": []},
            "database.json": {"valid": True, "built": self.now.isoformat()}}

    def validate(self):
        def load(path):
            return self.pin if path == build.PIN else self.files[path.name]
        with patch.object(build, "load", side_effect=load), \
                patch.object(Path, "read_bytes", return_value=b"fixture"), \
                patch.object(build, "datetime", wraps=datetime) as clock:
            clock.now.return_value = self.now
            return build.validate_scan(Path("evidence"), self.image)

    def test_pinned_complete_scan_passes_without_promotion(self):
        result = self.validate()
        self.assertEqual("Passed", result["scan"])
        self.assertEqual(0, result["severity_counts"]["High"])
        self.assertEqual(0, result["severity_counts"]["Critical"])

    def test_rejects_high_critical_stale_and_wrong_artifacts(self):
        original = copy.deepcopy(self.files)
        for severity in ("High", "Critical"):
            self.files["grype.json"]["matches"] = [{"vulnerability": {"severity": severity}}]
            with self.subTest(severity=severity), self.assertRaises(ValueError):
                self.validate()
            self.files = copy.deepcopy(original)
        for changed in (
            lambda: self.files["database.json"].update(built=(self.now - timedelta(days=6)).isoformat()),
            lambda: self.files["syft.json"]["source"]["metadata"].update(architecture="arm64"),
            lambda: self.files["syft.json"]["source"]["metadata"]["labels"].update(
                **{"org.opencontainers.image.source": "https://github.com/apache/kafka"}),
            lambda: self.files["syft.json"]["artifacts"][0].update(version="wrong"),
            lambda: self.files["grype.json"]["source"]["target"].update(imageID="sha256:" + "b" * 64),
        ):
            self.files = copy.deepcopy(original)
            changed()
            with self.assertRaises(ValueError):
                self.validate()


class KafkaPublicationTest(unittest.TestCase):
    def setUp(self):
        self.revision = "a" * 40
        self.config = "sha256:" + "c" * 64
        self.manifest = "sha256:" + "d" * 64
        self.env = {"GITHUB_ACTIONS": "true", "GITHUB_REF": "refs/heads/main",
            "GITHUB_REPOSITORY": "hasanjodatshandi/HooshiX", "GITHUB_EVENT_NAME": "workflow_dispatch",
            "GITHUB_WORKFLOW_REF": publish.EXPECTED_CERTIFICATE_IDENTITY.removeprefix("https://github.com/"),
            "GITHUB_SHA": self.revision, "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1",
            "RELEASE_KIND": "kafka-candidate"}

    def test_protected_publication_is_private_signed_and_never_deploys(self):
        calls = []

        def run(argv, **kwargs):
            calls.append(argv)
            if argv[0] == "git": return self.revision
            if argv[0] == "syft":
                path = Path(argv[-1].removeprefix("syft-json="))
                digest = self.manifest
                path.write_text(json.dumps({"source": {"type": "image", "metadata": {
                    "imageID": self.config, "manifestDigest": digest, "os": "linux", "architecture": "amd64",
                    "repoDigests": [publish.REPOSITORY + "@" + digest]}}}))
            return ""

        def resolution(path):
            digest = self.manifest
            path.write_text(json.dumps({"source": {"type": "image", "metadata": {
                "imageID": self.config, "manifestDigest": digest, "os": "linux", "architecture": "amd64",
                "repoDigests": [publish.REPOSITORY + "@" + digest]}}}))

        def candidate(folder):
            folder.mkdir()
            return {"local_image_id": "sha256:" + "b" * 64, "scanner_image_id": self.config,
                    "native_smoke": {"kraft": "Passed"}}

        def scan(folder, image, source):
            self.assertEqual("registry", source)
            self.assertEqual(publish.REPOSITORY + "@" + self.manifest, image)
            resolution(folder / "syft.json")

        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "evidence"
            with patch.object(publish, "context", return_value=(self.revision, "github:123:1")), \
                    patch.object(publish, "run", side_effect=run), \
                    patch.object(publish.subprocess, "run", return_value=subprocess.CompletedProcess(
                        ["gh", "api"], 0, stdout="private\n")) as metadata, \
                    patch.object(publish, "local_endpoint", return_value="unix:///captured.sock"), \
                    patch.object(publish, "pins", return_value={"version": "4.2.2", "upstream_image": "apache/kafka@sha256:" + "e" * 64,
                        "maven_overrides": []}), patch.object(publish, "build", side_effect=candidate), \
                    patch.object(publish, "scan_inventory", side_effect=scan), \
                    patch.object(publish, "validate_scan", return_value={"scan": "Passed"}), \
                    patch.object(publish, "sign_and_verify") as sign:
                result = publish.publish(directory, self.env)
        self.assertEqual("Passed", result["publication"])
        self.assertEqual("private", result["registry_visibility"])
        self.assertEqual("Not run", result["deployment"])
        self.assertEqual("CLOSED", result["application_ingress"])
        self.assertIn(["docker", "--host", "unix:///captured.sock", "push",
                       "ghcr.io/hasanjodatshandi/hooshix/platform-kafka-v2-private:"
                       "candidate-" + self.revision + "-github-123-1"], calls)
        self.assertEqual(4, metadata.call_count)
        metadata.assert_called_with(
            ["gh", "api", "users/hasanjodatshandi/packages/container/hooshix%2Fplatform-kafka-v2-private",
             "--jq", ".visibility"], check=True, capture_output=True, text=True, timeout=20)
        sign.assert_called_once()

    def test_non_candidate_or_public_package_stops_before_build(self):
        for env, visibility in ((self.env | {"RELEASE_KIND": "applications"}, "private"),
                                (self.env, "public")):
            with tempfile.TemporaryDirectory() as temporary, \
                    patch.object(publish, "context", return_value=(self.revision, "github:123:1")), \
                    patch.object(publish, "run", side_effect=lambda argv, **kwargs:
                        self.revision if argv[0] == "git" else visibility), \
                    patch.object(publish.subprocess, "run", return_value=subprocess.CompletedProcess(
                        ["gh", "api"], 0, stdout=visibility)), \
                    patch.object(publish, "local_endpoint", return_value="unix:///captured.sock"), \
                    patch.object(publish, "build") as build_candidate, \
                    self.assertRaises(ValueError):
                publish.publish(Path(temporary) / "evidence", env)
            build_candidate.assert_not_called()

    def test_visibility_rejection_reports_only_bounded_category(self):
        for visibility, actual in (("public", "public"), ("internal", "internal"),
                                   ("untrusted-secret-like-value", "invalid")):
            output = io.StringIO()
            with patch.object(publish.subprocess, "run", return_value=subprocess.CompletedProcess(
                    ["gh", "api"], 0, stdout=visibility)), redirect_stdout(output), \
                    self.assertRaises(ValueError):
                publish.package_visibility()
            self.assertEqual(f"KAFKA_PACKAGE_VISIBILITY=Rejected; actual={actual}; required=private\n",
                             output.getvalue())

    def test_unverified_visibility_stops_before_build_or_push_without_raw_errors(self):
        for failure in (subprocess.CalledProcessError(1, ["gh"], stderr="secret (HTTP 404)"),
                        subprocess.CalledProcessError(1, ["gh"], stderr="secret (HTTP 403)"),
                        subprocess.CalledProcessError(1, ["gh"], stderr="secret (HTTP 401)"),
                        subprocess.TimeoutExpired(["gh"], 20, stderr="secret"), OSError("secret")):
            output = io.StringIO()
            with self.subTest(failure=type(failure).__name__), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(publish, "context", return_value=(self.revision, "github:123:1")), \
                    patch.object(publish, "run", return_value=self.revision) as command, \
                    patch.object(publish.subprocess, "run", side_effect=failure), \
                    patch.object(publish, "local_endpoint", return_value="unix:///captured.sock"), \
                    patch.object(publish, "build") as build_candidate, \
                    patch.object(publish, "sign_and_verify") as sign, redirect_stdout(output), \
                    self.assertRaisesRegex(ValueError, "visibility could not be verified"):
                publish.publish(Path(temporary) / "evidence", self.env)
            build_candidate.assert_not_called()
            sign.assert_not_called()
            command.assert_called_once_with(["git", "rev-parse", "HEAD"])
            self.assertEqual("KAFKA_PACKAGE_VISIBILITY=Not verified; release stopped\n", output.getvalue())

    def test_visibility_is_rechecked_after_build_before_registry_push(self):
        for response in (subprocess.CompletedProcess(["gh"], 0, stdout="public"),
                         subprocess.CalledProcessError(1, ["gh"], stderr="secret (HTTP 404)")):
            with self.subTest(response=type(response).__name__), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(publish, "context", return_value=(self.revision, "github:123:1")), \
                    patch.object(publish, "run", return_value=self.revision) as command, \
                    patch.object(publish.subprocess, "run", side_effect=[
                        subprocess.CompletedProcess(["gh"], 0, stdout="private"), response]), \
                    patch.object(publish, "local_endpoint", return_value="unix:///captured.sock"), \
                    patch.object(publish, "pins", return_value={"version": "4.2.2"}), \
                    patch.object(publish, "build", return_value={"local_image_id": self.config}) as build_candidate, \
                    patch.object(publish, "sign_and_verify") as sign, \
                    self.assertRaisesRegex(ValueError, "private Kafka package"):
                publish.publish(Path(temporary) / "evidence", self.env)
            build_candidate.assert_called_once()
            sign.assert_not_called()
            command.assert_called_once_with(["git", "rev-parse", "HEAD"])

    def test_visibility_change_after_push_cannot_scan_sign_or_create_success_receipt(self):
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(publish, "context", return_value=(self.revision, "github:123:1")), \
                patch.object(publish, "run", return_value=self.revision) as command, \
                patch.object(publish.subprocess, "run", side_effect=[
                    subprocess.CompletedProcess(["gh"], 0, stdout=value)
                    for value in ("private", "private", "public")]), \
                patch.object(publish, "local_endpoint", return_value="unix:///captured.sock"), \
                patch.object(publish, "build", return_value={"local_image_id": self.config}), \
                patch.object(publish, "scan_inventory") as scan, \
                patch.object(publish, "sign_and_verify") as sign:
            directory = Path(temporary) / "evidence"
            with self.assertRaisesRegex(ValueError, "private Kafka package"):
                publish.publish(directory, self.env)
            self.assertTrue(any(call.args[0][:4] == ["docker", "--host", "unix:///captured.sock", "push"]
                                for call in command.call_args_list))
            self.assertFalse((directory / "publication.json").exists())
            scan.assert_not_called()
            sign.assert_not_called()

    def test_source_provenance_binds_all_overlays_and_repository(self):
        pin = build.pins()
        predicate = publish.source_predicate(pin, self.revision, "github:123:1")
        definition = predicate["buildDefinition"]
        self.assertEqual(publish.BUILD_TYPE, definition["buildType"])
        self.assertEqual(self.revision, definition["externalParameters"]["repository_revision"])
        self.assertEqual(len(pin["maven_overrides"]) + 2, len(definition["resolvedDependencies"]))

    def test_workflow_requires_main_private_package_and_never_installs(self):
        root = Path(__file__).resolve().parents[3]
        workflow = (root / ".github/workflows/production-release.yml").read_text()
        for expected in ("- kafka-candidate", "inputs.release_kind == 'kafka-candidate'",
                         "scripts/production/publish_kafka_candidate.py", "environment: production-release",
                         "packages: write", "Exact main baseline must pass first", "RELEASE_KIND: ${{ inputs.release_kind }}",
                         "inputs.release_kind == 'kafka-candidate' && 'kafka'"):
            self.assertIn(expected, workflow)
        for forbidden in ("KUBECONFIG", "secrets.PRODUCTION", "sudo ", "kubectl apply"):
            self.assertNotIn(forbidden, workflow)


if __name__ == "__main__":
    unittest.main()
