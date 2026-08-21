from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")


class DeterministicBootstrapTest(unittest.TestCase):
    def test_kyverno_linux_amd64_images_are_pinned_and_mirrored_by_digest(self) -> None:
        pins = {}
        for line in (ROOT / "infrastructure/kyverno/pins.env").read_text(encoding="utf-8").splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                pins[key] = value

        amd64_keys = (
            "KYVERNO_ADMISSION_AMD64_DIGEST",
            "KYVERNO_PRE_AMD64_DIGEST",
            "KYVERNO_BACKGROUND_AMD64_DIGEST",
            "KYVERNO_CLEANUP_AMD64_DIGEST",
            "KYVERNO_REPORTS_AMD64_DIGEST",
        )
        for key in amd64_keys:
            self.assertRegex(pins.get(key, ""), DIGEST_RE, key)

        installer = (ROOT / "scripts/platform/kyverno_install.sh").read_text(encoding="utf-8")
        verifier = (ROOT / "scripts/platform/kyverno_verify.sh").read_text(encoding="utf-8")
        self.assertIn("KYVERNO_LOCAL_REPOSITORY_PREFIX=localhost:5001/hooshix/vendor/kyverno", installer)
        self.assertIn("timeout 120s docker pull --platform linux/amd64", installer)
        self.assertIn("timeout 120s docker push", installer)
        self.assertIn('[[ "$mirrored" == "${repository}@${digest}" ]]', installer)
        self.assertIn("kubernetes.io/service-name=kyverno-svc", installer)
        self.assertIn("--dry-run=server", installer)
        self.assertIn("timeout 10s kubectl", installer)
        self.assertIn('[[ "$webhook_ready" -eq 1 ]]', installer)
        self.assertNotIn("kind load docker-image", installer)
        self.assertIn("localhost:5001/hooshix/vendor/kyverno", verifier)
        for key in amd64_keys:
            self.assertIn(key, installer)
            self.assertIn(key, verifier)

    def test_calico_host_pull_is_platform_specific_and_bounded(self) -> None:
        creator = (ROOT / "scripts/platform/kind_create.sh").read_text(encoding="utf-8")
        self.assertIn("timeout 120s docker pull --platform linux/amd64", creator)
        self.assertIn("CALICO_CNI_AMD64_DIGEST", creator)
        self.assertIn("CALICO_NODE_AMD64_DIGEST", creator)
        self.assertIn("CALICO_CONTROLLERS_AMD64_DIGEST", creator)


if __name__ == "__main__":
    unittest.main()
