from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from desktop_engine import DesktopEngine, DesktopError, RunnerResult, load_policy  # noqa: E402


class CountingRunner:
    def __init__(self) -> None:
        self.calls = 0

    def run(self, args: list[str], *, timeout_seconds: int) -> RunnerResult:
        self.calls += 1
        windows = [
            {
                "hwnd": 1001,
                "processId": 1,
                "processName": "Notepad",
                "title": "test",
                "width": 800,
                "height": 600,
                "isForeground": True,
            }
        ]
        return RunnerResult(0, False, 1, json.dumps(windows).encode("utf-8"), b"")


class DesktopAuditFailClosedTest(unittest.TestCase):
    def test_sensitive_observation_does_not_start_when_audit_is_unavailable(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake_winapp = root / "winapp.exe"
            fake_winapp.write_bytes(b"fake")
            audit_dir = root / "audit"
            audit_path = audit_dir / "desktop.ndjson"
            policy_path = root / "policy.json"
            policy_path.write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "winapp_path": str(fake_winapp),
                        "expected_winapp_version": "0.6.0",
                        "allow_all_apps": False,
                        "allowed_apps": ["notepad"],
                        "denied_apps": ["consent", "credentialuibroker", "logonui"],
                        "audit_log": str(audit_path),
                        "capture_temp_dir": str(root / "captures"),
                        "require_interactive_session": False,
                        "require_non_elevated": False,
                        "allow_screenshot": True,
                        "allow_capture_screen": False,
                        "allow_uia_mutation": True,
                        "allow_mouse_input": True,
                        "allow_keyboard_input": True,
                        "allow_system_keys": False,
                        "max_command_seconds": 10,
                        "max_output_bytes": 65536,
                        "max_screenshot_bytes": 1048576,
                        "max_text_chars": 4096,
                        "max_inspect_depth": 8,
                        "max_audit_bytes": 1048576,
                        "audit_backups": 2,
                    }
                ),
                encoding="utf-8",
            )
            runner = CountingRunner()
            engine = DesktopEngine(load_policy(policy_path), runner, runtime_version="0.6.0")

            audit_path.unlink()
            audit_dir.rmdir()
            audit_dir.write_text("block audit directory recreation", encoding="utf-8")

            with self.assertRaises(DesktopError) as caught:
                engine.list_windows()
            self.assertEqual("AUDIT_UNAVAILABLE", caught.exception.code)
            self.assertEqual(0, runner.calls)

    def test_target_authorization_does_not_observe_windows_when_authorization_audit_is_unavailable(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake_winapp = root / "winapp.exe"
            fake_winapp.write_bytes(b"fake")
            policy_path = root / "policy.json"
            policy_path.write_text(json.dumps({
                "schema_version": 1, "winapp_path": str(fake_winapp), "expected_winapp_version": "0.6.0",
                "allow_all_apps": False, "allowed_apps": ["notepad"], "denied_apps": [],
                "audit_log": str(root / "audit" / "desktop.ndjson"), "capture_temp_dir": str(root / "captures"),
                "require_interactive_session": False, "require_non_elevated": False,
                "allow_screenshot": True, "allow_capture_screen": False, "allow_uia_mutation": True,
                "allow_mouse_input": True, "allow_keyboard_input": True, "allow_system_keys": False,
                "max_command_seconds": 10, "max_output_bytes": 65536, "max_screenshot_bytes": 1048576,
                "max_text_chars": 4096, "max_inspect_depth": 8, "max_audit_bytes": 1048576, "audit_backups": 2
            }), encoding="utf-8")
            runner = CountingRunner()
            engine = DesktopEngine(load_policy(policy_path), runner, runtime_version="0.6.0")
            original_audit = engine._audit
            def fail_authorization(action: str, outcome: str, fields: dict) -> None:
                if action == "desktop.window_authorize" and outcome == "started":
                    raise DesktopError("audit unavailable", code="AUDIT_UNAVAILABLE")
                original_audit(action, outcome, fields)
            engine._audit = fail_authorization  # type: ignore[method-assign]
            with self.assertRaises(DesktopError) as caught:
                engine.inspect(1001)
            self.assertEqual("AUDIT_UNAVAILABLE", caught.exception.code)
            self.assertEqual(0, runner.calls)

    def test_mutation_does_not_run_when_started_audit_cannot_be_written(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake_winapp = root / "winapp.exe"
            fake_winapp.write_bytes(b"fake")
            audit_dir = root / "audit"
            audit_path = audit_dir / "desktop.ndjson"
            policy_path = root / "policy.json"
            policy_path.write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "winapp_path": str(fake_winapp),
                        "expected_winapp_version": "0.6.0",
                        "allow_all_apps": False,
                        "allowed_apps": ["notepad"],
                        "denied_apps": [],
                        "audit_log": str(audit_path),
                        "capture_temp_dir": str(root / "captures"),
                        "require_interactive_session": False,
                        "require_non_elevated": False,
                        "allow_screenshot": True,
                        "allow_capture_screen": False,
                        "allow_uia_mutation": True,
                        "allow_mouse_input": True,
                        "allow_keyboard_input": True,
                        "allow_system_keys": False,
                        "max_command_seconds": 10,
                        "max_output_bytes": 65536,
                        "max_screenshot_bytes": 1048576,
                        "max_text_chars": 4096,
                        "max_inspect_depth": 8,
                        "max_audit_bytes": 1048576,
                        "audit_backups": 2,
                    }
                ),
                encoding="utf-8",
            )
            runner = CountingRunner()
            engine = DesktopEngine(load_policy(policy_path), runner, runtime_version="0.6.0")
            # Window authorization is a read through WinApp. Break audit after that authorization would occur
            # by forcing the first action audit itself to fail.
            original_audit = engine._audit
            calls_before = runner.calls

            def fail_started(action: str, outcome: str, fields: dict) -> None:
                if action == "desktop.click" and outcome == "started":
                    raise DesktopError("audit unavailable", code="AUDIT_UNAVAILABLE")
                original_audit(action, outcome, fields)

            engine._audit = fail_started  # type: ignore[method-assign]
            with self.assertRaises(DesktopError) as caught:
                engine.click(1001, "SettingsButton")
            self.assertEqual("AUDIT_UNAVAILABLE", caught.exception.code)
            # One WinApp call is allowed for fresh HWND/process authorization; the click command itself must not run.
            self.assertEqual(calls_before + 1, runner.calls)


if __name__ == "__main__":
    unittest.main()
