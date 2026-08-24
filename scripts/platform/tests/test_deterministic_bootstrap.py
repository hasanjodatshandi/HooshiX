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
        self.assertIn("ETCD_TMPFS_PARENT=/dev/shm/hooshix-kind", creator)
        self.assertIn("ETCD_TMPFS_DIR=$ETCD_TMPFS_PARENT/etcd", creator)
        self.assertIn('-v "$ETCD_TMPFS_PARENT:/hooshix-kind"', creator)
        self.assertIn('find /hooshix-kind -mindepth 1 -delete', creator)
        self.assertIn('chown "$HOST_UID:$HOST_GID" /hooshix-kind', creator)
        self.assertIn('rmdir "$ETCD_TMPFS_PARENT"', creator)
        self.assertNotIn('-v /dev/shm:', creator)

    def test_kind_operator_authority_and_tmpfs_mount_are_verified(self) -> None:
        verifier = (ROOT / "scripts/platform/kind_verify.sh").read_text(encoding="utf-8")
        cluster = (ROOT / "infrastructure/kind/cluster.yaml").read_text(encoding="utf-8")
        staging = (ROOT / "scripts/platform/staging_verify.sh").read_text(encoding="utf-8")
        self.assertIn("auth can-i", verifier)
        self.assertIn("kind operator context lacks expected local cluster-admin authority", verifier)
        self.assertIn("hostPath: /dev/shm/hooshix-kind/etcd", cluster)
        self.assertIn("/dev/shm/hooshix-kind/etcd", staging)

    def test_pre_edge_istio_verify_is_foundation_only(self) -> None:
        istio = (ROOT / "scripts/platform/istio_verify.sh").read_text(encoding="utf-8")
        edge = (ROOT / "scripts/platform/edge_verify.sh").read_text(encoding="utf-8")
        platform = (ROOT / "scripts/platform/platform_verify.sh").read_text(encoding="utf-8")
        self.assertIn("istio_foundation_verify.sh", istio)
        self.assertNotIn("mesh_identity_verify.sh", istio)
        self.assertIn("mesh_identity_verify.sh", edge)
        self.assertIn("edge_verify.sh", platform)

if __name__ == "__main__":
    unittest.main()
