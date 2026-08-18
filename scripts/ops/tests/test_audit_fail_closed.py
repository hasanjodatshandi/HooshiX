from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ops_engine import OpsEngine, OpsError  # noqa: E402


class OpsAuditFailClosedTest(unittest.TestCase):
    def test_filesystem_mutations_do_not_start_when_audit_is_unavailable(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            work = root / "work"
            work.mkdir()
            delete_target = work / "keep.txt"
            delete_target.write_text("keep", encoding="utf-8")

            audit_dir = root / "audit"
            audit_path = audit_dir / "ops.ndjson"
            policy_path = root / "ops-policy.json"
            policy_path.write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "allowed_roots": [str(work)],
                        "denied_roots": [],
                        "commands": {"python": sys.executable},
                        "audit_log": str(audit_path),
                        "require_elevated": False,
                        "allow_process_execution": False,
                        "allow_elevated_mutation": True,
                        "allow_elevated_process_execution": False,
                        "max_command_seconds": 30,
                        "max_output_bytes": 65536,
                        "max_file_bytes": 65536,
                        "max_list_entries": 100,
                        "max_audit_bytes": 1048576,
                        "audit_backups": 2,
                    }
                ),
                encoding="utf-8",
            )

            engine = OpsEngine.from_policy_path(policy_path)

            audit_path.unlink()
            audit_dir.rmdir()
            audit_dir.write_text("block audit directory recreation", encoding="utf-8")

            write_target = work / "blocked-write.txt"
            with self.assertRaises(OpsError) as write_error:
                engine.write_text(
                    str(write_target),
                    "blocked",
                    purpose="verify audit fail-closed write",
                )
            self.assertEqual(write_error.exception.code, "AUDIT_UNAVAILABLE")
            self.assertFalse(write_target.exists())

            mkdir_target = work / "blocked-directory"
            with self.assertRaises(OpsError) as mkdir_error:
                engine.mkdir(
                    str(mkdir_target),
                    purpose="verify audit fail-closed mkdir",
                )
            self.assertEqual(mkdir_error.exception.code, "AUDIT_UNAVAILABLE")
            self.assertFalse(mkdir_target.exists())

            with self.assertRaises(OpsError) as delete_error:
                engine.delete(
                    str(delete_target),
                    purpose="verify audit fail-closed delete",
                )
            self.assertEqual(delete_error.exception.code, "AUDIT_UNAVAILABLE")
            self.assertTrue(delete_target.exists())
