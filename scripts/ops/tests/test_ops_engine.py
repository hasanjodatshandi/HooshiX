from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ops_engine import POLICY_FIELDS, OpsEngine, OpsError, load_policy  # noqa: E402


def make_policy(root: Path, *, max_output_bytes: int = 65536) -> tuple[Path, Path]:
    audit = root / "audit" / "ops.ndjson"
    policy = root / "ops-policy.json"
    allowed = root / "allowed"
    denied = allowed / "denied"
    allowed.mkdir(parents=True)
    denied.mkdir()
    data = {
        "schema_version": 1,
        "allowed_roots": [str(allowed)],
        "denied_roots": [str(denied)],
        "commands": {"python": sys.executable},
        "audit_log": str(audit),
        "require_elevated": False,
        "allow_process_execution": True,
        "allow_elevated_mutation": True,
        "allow_elevated_process_execution": True,
        "max_command_seconds": 5,
        "max_output_bytes": max_output_bytes,
        "max_file_bytes": 1024 * 1024,
        "max_list_entries": 100,
        "max_audit_bytes": 1024 * 1024,
        "audit_backups": 3,
    }
    policy.write_text(json.dumps(data), encoding="utf-8")
    return policy, allowed


class OpsEngineTest(unittest.TestCase):
    def test_policy_schema_fields_match_runtime_loader(self) -> None:
        schema_path = Path(__file__).resolve().parents[3] / "ops" / "policy.schema.json"
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        self.assertEqual(POLICY_FIELDS, set(schema["properties"]))
        self.assertEqual(POLICY_FIELDS, set(schema["required"]))

    def test_policy_rejects_missing_and_duplicate_roots(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            policy_path, allowed = make_policy(root)
            data = json.loads(policy_path.read_text(encoding="utf-8"))
            del data["denied_roots"]
            policy_path.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaisesRegex(OpsError, "missing required fields: denied_roots"):
                load_policy(policy_path)

            data["denied_roots"] = []
            data["allowed_roots"] = [str(allowed), str(allowed)]
            policy_path.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaisesRegex(OpsError, "duplicate canonical paths"):
                load_policy(policy_path)

    def test_policy_rejects_unknown_and_duplicate_fields(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            policy_path, _ = make_policy(root)
            data = json.loads(policy_path.read_text(encoding="utf-8"))
            data["allow_everything_typo"] = True
            policy_path.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaisesRegex(OpsError, "unsupported fields"):
                load_policy(policy_path)

            raw = json.dumps(data | {"allow_everything_typo": False})
            raw = raw.replace(
                '"schema_version": 1,',
                '"schema_version": 1, "schema_version": 1,',
                1,
            )
            raw = raw.replace(', "allow_everything_typo": false', '', 1)
            policy_path.write_text(raw, encoding="utf-8")
            with self.assertRaisesRegex(OpsError, "duplicate JSON key: schema_version"):
                load_policy(policy_path)

    def test_policy_rejects_relative_root(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            policy = root / "policy.json"
            policy.write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "allowed_roots": ["relative"],
                        "denied_roots": [],
                        "commands": {"python": sys.executable},
                        "audit_log": str(root / "audit.ndjson"),
                        "require_elevated": False,
                        "allow_process_execution": True,
                        "allow_elevated_mutation": True,
                        "allow_elevated_process_execution": True,
                        "max_command_seconds": 5,
                        "max_output_bytes": 4096,
                        "max_file_bytes": 4096,
                        "max_list_entries": 10,
                        "max_audit_bytes": 1024 * 1024,
                        "audit_backups": 3,
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(OpsError, "allowed root must be absolute"):
                load_policy(policy)

    def test_read_write_sha_precondition_and_delete(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            policy_path, allowed = make_policy(root)
            engine = OpsEngine.from_policy_path(policy_path)
            target = allowed / "source.txt"

            written = engine.write_text(str(target), "first…", purpose="create test file")
            self.assertEqual("first…", engine.read_text(str(target))["text"])
            self.assertEqual(written["sha256"], engine.stat(str(target))["sha256"])

            with self.assertRaisesRegex(OpsError, "does not match"):
                engine.write_text(
                    str(target),
                    "second",
                    purpose="stale update",
                    expected_sha256="0" * 64,
                )

            updated = engine.write_text(
                str(target),
                "second",
                purpose="validated update",
                expected_sha256=written["sha256"],
            )
            self.assertNotEqual(written["sha256"], updated["sha256"])
            deleted = engine.delete(str(target), purpose="remove test file")
            self.assertEqual("file", deleted["deleted_type"])
            self.assertFalse(target.exists())

    def test_denied_root_and_symlink_escape_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            policy_path, allowed = make_policy(root)
            engine = OpsEngine.from_policy_path(policy_path)
            denied_file = allowed / "denied" / "secret.txt"
            denied_file.write_text("secret", encoding="utf-8")
            with self.assertRaisesRegex(OpsError, "denied_root"):
                engine.read_text(str(denied_file))

            outside = root / "outside.txt"
            outside.write_text("outside", encoding="utf-8")
            link = allowed / "escape-link"
            try:
                link.symlink_to(outside)
            except (OSError, NotImplementedError):
                self.skipTest("symlink creation is unavailable")
            with self.assertRaisesRegex(OpsError, "resolves outside configured allowed_roots"):
                engine.read_text(str(link))

    def test_allowed_root_cannot_be_deleted(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            policy_path, allowed = make_policy(root)
            engine = OpsEngine.from_policy_path(policy_path)
            with self.assertRaisesRegex(OpsError, "allowed root"):
                engine.delete(str(allowed), purpose="must fail", recursive=True)

    def test_process_uses_alias_cwd_timeout_and_sanitized_environment(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            policy_path, allowed = make_policy(root)
            engine = OpsEngine.from_policy_path(policy_path)
            with mock.patch.dict(os.environ, {"CONTROL_PLANE_API_KEY": "must-not-reach-child"}, clear=False):
                result = engine.run_process(
                    "python",
                    [
                        "-c",
                        "import os; print(os.getcwd()); print(os.environ.get('CONTROL_PLANE_API_KEY', 'missing'))",
                    ],
                    cwd=str(allowed),
                    purpose="verify sanitized child environment",
                    timeout_seconds=3,
                )
            self.assertEqual(0, result["exit_code"])
            self.assertIn(str(allowed), result["stdout"])
            self.assertIn("missing", result["stdout"])
            self.assertNotIn("must-not-reach-child", result["stdout"])

    def test_process_output_is_bounded_and_audit_does_not_store_raw_arguments(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            policy_path, allowed = make_policy(root, max_output_bytes=1024)
            engine = OpsEngine.from_policy_path(policy_path)
            secret_argument = "not-for-audit-raw-value"
            result = engine.run_process(
                "python",
                ["-c", f"print('x' * 5000); print('{secret_argument}')"],
                cwd=str(allowed),
                purpose="purpose text must not be stored raw",
            )
            self.assertTrue(result["stdout_truncated"])
            self.assertLessEqual(len(result["stdout"].encode("utf-8")), 1024)
            audit_text = engine.policy.audit_log.read_text(encoding="utf-8")
            self.assertNotIn(secret_argument, audit_text)
            self.assertNotIn("purpose text must not be stored raw", audit_text)
            self.assertIn("arguments_sha256", audit_text)
            self.assertIn("purpose_sha256", audit_text)

    def test_process_timeout_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            policy_path, allowed = make_policy(root)
            engine = OpsEngine.from_policy_path(policy_path)
            result = engine.run_process(
                "python",
                ["-c", "import time; time.sleep(10)"],
                cwd=str(allowed),
                purpose="timeout test",
                timeout_seconds=1,
            )
            self.assertTrue(result["timed_out"])
            self.assertNotEqual(0, result["exit_code"])

    def test_audit_tail_returns_bounded_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            policy_path, allowed = make_policy(root)
            engine = OpsEngine.from_policy_path(policy_path)
            engine.mkdir(str(allowed / "one"), purpose="one")
            engine.mkdir(str(allowed / "two"), purpose="two")
            entries = engine.audit_tail(2)["entries"]
            self.assertEqual(2, len(entries))
            self.assertTrue(all("event_id" in item for item in entries))

    def test_process_execution_requires_policy_opt_in(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            policy_path, allowed = make_policy(root)
            data = json.loads(policy_path.read_text(encoding="utf-8"))
            data["allow_process_execution"] = False
            policy_path.write_text(json.dumps(data), encoding="utf-8")
            engine = OpsEngine.from_policy_path(policy_path)
            with self.assertRaisesRegex(OpsError, "process execution is disabled"):
                engine.run_process(
                    "python",
                    ["-c", "print('no')"],
                    cwd=str(allowed),
                    purpose="must be denied",
                )

    def test_elevated_mutation_and_execution_require_separate_opt_in(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            policy_path, allowed = make_policy(root)
            data = json.loads(policy_path.read_text(encoding="utf-8"))
            data["allow_elevated_mutation"] = False
            data["allow_elevated_process_execution"] = False
            policy_path.write_text(json.dumps(data), encoding="utf-8")
            with mock.patch("ops_engine._is_elevated", return_value=True):
                engine = OpsEngine.from_policy_path(policy_path)
                with self.assertRaisesRegex(OpsError, "elevated filesystem mutation"):
                    engine.write_text(
                        str(allowed / "blocked.txt"),
                        "blocked",
                        purpose="must be denied",
                    )
                with self.assertRaisesRegex(OpsError, "elevated process execution"):
                    engine.run_process(
                        "python",
                        ["-c", "print('blocked')"],
                        cwd=str(allowed),
                        purpose="must be denied",
                    )

    def test_audit_rotation_bounds_primary_log(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            policy_path, _ = make_policy(root)
            engine = OpsEngine.from_policy_path(policy_path)
            padding = "x" * 700_000
            engine._audit("test.large", "passed", {"padding": padding})
            engine._audit("test.large", "passed", {"padding": padding})
            self.assertLessEqual(
                engine.policy.audit_log.stat().st_size,
                engine.policy.max_audit_bytes,
            )
            self.assertTrue(Path(f"{engine.policy.audit_log}.1").exists())


if __name__ == "__main__":
    unittest.main()
