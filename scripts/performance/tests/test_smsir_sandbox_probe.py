from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location(
    "smsir_sandbox_probe", ROOT / "scripts/performance/smsir_sandbox_probe.py"
)
probe = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(probe)


class SmsIrSandboxProbeTest(unittest.TestCase):
    def test_writes_only_simulated_identifier_free_aggregate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            key = root / "key"
            key.write_text("fixture-sandbox-key\n", encoding="utf-8")
            key.chmod(0o600)
            output = root / "evidence.json"
            responses = (
                (200, {"status": 1, "data": {"messageId": 89545112, "cost": 1}}),
                (401, {"status": 10, "message": "rejected"}),
                (400, {"status": 104, "message": "invalid"}),
            )
            with mock.patch.object(probe, "_post", side_effect=responses) as post:
                evidence = probe.run(key, output)

            self.assertEqual(3, post.call_count)
            self.assertTrue(evidence["passed"])
            self.assertFalse(evidence["real_delivery_claimed"])
            serialized = output.read_text(encoding="utf-8")
            self.assertNotIn("89545112", serialized)
            self.assertNotIn("fixture-sandbox-key", serialized)
            self.assertNotIn("mobile", serialized.lower())
            self.assertEqual(0o600, output.stat().st_mode & 0o777)
            self.assertEqual(evidence, json.loads(serialized))

    def test_rejects_unsafe_key_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            key = Path(directory) / "key"
            key.write_text("fixture-sandbox-key\n", encoding="utf-8")
            key.chmod(0o644)
            with self.assertRaisesRegex(ValueError, "0600"):
                probe._load_secret(key)

    def test_rejects_unsafe_key_parent_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            root.chmod(0o755)
            key = root / "key"
            key.write_text("fixture-sandbox-key\n", encoding="utf-8")
            key.chmod(0o600)
            with self.assertRaisesRegex(ValueError, "0700"):
                probe._load_secret(key)

    def test_private_output_replaces_a_symlink_without_following_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "target"
            target.write_text("do-not-overwrite\n", encoding="utf-8")
            output = root / "evidence.json"
            output.symlink_to(target)

            probe._write_private_json(output, {"passed": True})

            self.assertEqual("do-not-overwrite\n", target.read_text(encoding="utf-8"))
            self.assertFalse(output.is_symlink())
            self.assertEqual(0o600, output.stat().st_mode & 0o777)


if __name__ == "__main__":
    unittest.main()
