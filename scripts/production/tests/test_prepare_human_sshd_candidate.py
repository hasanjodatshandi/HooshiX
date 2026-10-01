import hashlib
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_human_sshd_candidate as candidate


class HumanSshCandidateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.private = self.root / "private"
        self.private.mkdir(mode=0o700)
        self.source = self.root / "sshd_config"
        self.tail = self.root / "human.tail"
        self.source.write_text("Include /etc/ssh/sshd_config.d/*.conf\n"
                               "Match User hooshixtunnel\n"
                               "    AllowTcpForwarding yes\n", encoding="utf-8")
        self.tail.write_bytes((Path(__file__).resolve().parents[3] /
                               "infrastructure/production/host/sshd-human-match.tail").read_bytes())
        self.expected = hashlib.sha256(self.source.read_bytes()).hexdigest()

    def test_prepares_private_uninstalled_candidate_without_changing_source(self):
        before = self.source.read_bytes()
        with patch.object(candidate.os, "geteuid", return_value=os.geteuid()):
            result = candidate.prepare(self.source, self.tail, self.private, self.expected)
        output = Path(result["candidate_path"])
        self.assertTrue(result["installed"] is False)
        self.assertEqual(before, self.source.read_bytes())
        self.assertEqual(0o600, output.stat().st_mode & 0o777)
        self.assertTrue(output.read_text(encoding="utf-8").endswith(
            "Match User hooshixadmin\n    DisableForwarding yes\n"
            "    AllowAgentForwarding no\n    AllowTcpForwarding no\n"
            "    AllowStreamLocalForwarding no\n    X11Forwarding no\n"
            "    PermitTunnel no\n    GatewayPorts no\n"))

    def test_rejects_source_drift_without_output(self):
        self.source.write_text("Match User other\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            candidate.prepare(self.source, self.tail, self.private, self.expected)
        self.assertEqual([], list(self.private.glob("*.conf")))

    def test_rejects_template_drift_without_output(self):
        self.tail.write_text("Match User hooshixtunnel\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            candidate.prepare(self.source, self.tail, self.private, self.expected)
        self.assertEqual([], list(self.private.glob("*.conf")))

    def test_rejects_insecure_destination_without_output(self):
        self.private.chmod(0o755)
        with self.assertRaises(ValueError):
            candidate.prepare(self.source, self.tail, self.private, self.expected)
        self.assertEqual([], list(self.private.glob("*.conf")))

    def test_rejects_root_and_bad_hash(self):
        with patch.object(candidate.os, "geteuid", return_value=0):
            with self.assertRaises(ValueError):
                candidate.prepare(self.source, self.tail, self.private, self.expected)
        with patch.object(candidate.os, "geteuid", return_value=os.geteuid()):
            with self.assertRaises(ValueError):
                candidate.prepare(self.source, self.tail, self.private, "bad")


if __name__ == "__main__":
    unittest.main()
