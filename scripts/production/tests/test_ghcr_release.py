"""Exercise the owned/private registry boundary without credentials or network."""
import json
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / ".github/workflows/production-release.yml"


class GhcrReleaseTest(unittest.TestCase):
    def setUp(self):
        self.workflow = WORKFLOW.read_text()
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.manifest = Path(self.temporary.name) / "manifest.json"
        self.images = {name: "ghcr.io/hasanjodatshandi/hooshix/" + name + "@sha256:" + "a" * 64
                       for name in ("authorization-service", "compromised-password-service",
                                    "conversation-service", "identity-service", "notification-service",
                                    "web-bff", "web-frontend")}

    def private_check(self, visibility="private", status=0):
        self.manifest.write_text(json.dumps({"images": self.images}))
        section = self.workflow.split("      - name: Require private owned packages before signing\n")[1]
        script = textwrap.dedent(section.split("        run: |\n")[1].split("      - name:")[0])
        harness = """gh() { printf '%s\\n' "$TEST_VISIBILITY"; return "$TEST_STATUS"; }
timeout() { shift; "$@"; }
"""
        return subprocess.run(["/bin/bash", "-c", harness + script], capture_output=True,
                              timeout=5, check=False, env={
                                  "PATH": "/usr/bin:/bin", "RELEASE_MANIFEST": str(self.manifest),
                                  "RUNNER_TEMP": self.temporary.name,
                                  "TEST_VISIBILITY": visibility, "TEST_STATUS": str(status)})

    def test_private_packages_pass_without_network(self):
        self.assertEqual(0, self.private_check().returncode)

    def test_public_internal_missing_and_malformed_visibility_fail_closed(self):
        for visibility in ("public", "internal", "", "private\npublic"):
            with self.subTest(visibility=visibility):
                self.assertNotEqual(0, self.private_check(visibility).returncode)

    def test_api_failure_is_not_private_confirmation(self):
        self.assertNotEqual(0, self.private_check("private", 1).returncode)

    def test_missing_package_cannot_skip_private_gate(self):
        del self.images["web-frontend"]
        self.assertNotEqual(0, self.private_check().returncode)

    def test_metadata_rejects_other_registry_owner_and_prefix_confusion(self):
        section = self.workflow.split('          python3 - "$RELEASE_MANIFEST" "$GITHUB_OUTPUT"')[1]
        script = textwrap.dedent(section.split("\n", 1)[1].split("          PY\n")[0])
        for image in (self.images["identity-service"], "ghcr.io/other/hooshix/app@sha256:" + "a" * 64,
                      "ghcr.io/hasanjodatshandi/hooshix-evil/app@sha256:" + "a" * 64):
            self.manifest.write_text(json.dumps({"images": {"identity-service": image},
                                                "git_revision": "b" * 40, "cosign": {
                                                    "certificate_identity": "test", "certificate_oidc_issuer": "test"}}))
            result = subprocess.run(["/usr/bin/python3", "-c", script, str(self.manifest),
                                     str(Path(self.temporary.name) / "outputs")], capture_output=True,
                                    timeout=5, check=False, env={"PATH": "/usr/bin:/bin"})
            self.assertEqual(image == self.images["identity-service"], result.returncode == 0)

    def test_ephemeral_token_never_replaces_readiness_or_signer_controls(self):
        for required in ("packages: write", "GHCR_TOKEN: ${{ github.token }}", "--password-stdin",
                         "timeout 30 docker", "timeout 20 gh api", "environment: production-release",
                         "github.ref == 'refs/heads/main'", "python3 scripts/production/verify_release.py",
                         'rm -f -- "$RUNNER_TEMP/docker-config/config.json"', "if: always()"):
            self.assertIn(required, self.workflow)
        for forbidden in ("PRODUCTION_REGISTRY_DOCKER_CONFIG_JSON", "secrets.PRODUCTION", "continue-on-error"):
            self.assertNotIn(forbidden, self.workflow)
        self.assertIn("COSIGN_IDENTITY:", self.workflow)
        self.assertNotIn("KUBECONFIG", self.workflow)


if __name__ == "__main__":
    unittest.main()
