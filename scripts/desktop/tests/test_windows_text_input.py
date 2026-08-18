from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from windows_text_input import (  # noqa: E402
    IsolatedWindowsUnicodeTextInput,
    WindowsTextInputError,
    _normalized_utf16_code_units,
)


class IsolatedWindowsUnicodeTextInputTest(unittest.TestCase):
    def _backend(self, root: Path, *, timeout_seconds: int = 30) -> IsolatedWindowsUnicodeTextInput:
        helper = root / "windows_text_input_helper.ps1"
        helper.write_text("# fixed helper", encoding="utf-8")
        backend = IsolatedWindowsUnicodeTextInput.__new__(IsolatedWindowsUnicodeTextInput)
        backend.helper_path = helper
        backend.timeout_seconds = timeout_seconds
        backend.powershell_path = root / "powershell.exe"
        backend.powershell_path.write_bytes(b"fake")
        return backend

    def test_normalized_utf16_units_preserve_unicode_and_normalize_newline(self) -> None:
        self.assertEqual(4, _normalized_utf16_code_units("A\n😀"))
        self.assertEqual(3, _normalized_utf16_code_units("A\r\nB"))

    def test_estimate_includes_per_code_unit_pacing_drain_and_startup_slack(self) -> None:
        estimate = IsolatedWindowsUnicodeTextInput.estimated_seconds("x" * 100)
        self.assertAlmostEqual(6.0, estimate, places=3)

    def test_child_environment_is_allowlisted_and_excludes_tunnel_credentials(self) -> None:
        with mock.patch.dict(
            os.environ,
            {
                "CONTROL_PLANE_API_KEY": "must-not-pass",
                "OPENAI_ADMIN_KEY": "must-not-pass",
                "SYSTEMROOT": r"C:\Windows",
                "TEMP": r"C:\Temp",
            },
            clear=False,
        ):
            env = IsolatedWindowsUnicodeTextInput._environment()
        self.assertEqual(r"C:\Windows", env["SYSTEMROOT"])
        self.assertEqual(r"C:\Temp", env["TEMP"])
        self.assertNotIn("CONTROL_PLANE_API_KEY", env)
        self.assertNotIn("OPENAI_ADMIN_KEY", env)

    def test_helper_passes_raw_text_only_over_stdin_not_argv_or_environment(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            backend = self._backend(root)
            marker = "Private ✓ فارسی marker"
            response = {
                "utf16_code_units": _normalized_utf16_code_units(marker),
                "chunks": _normalized_utf16_code_units(marker),
                "settle_ms": 500,
                "foreground_still_target": True,
            }
            completed = subprocess.CompletedProcess(
                args=[],
                returncode=0,
                stdout=json.dumps(response).encode("utf-8"),
                stderr=b"",
            )
            with mock.patch("windows_text_input.subprocess.run", return_value=completed) as run:
                result = backend.send_text(1001, marker)
            argv = run.call_args.args[0]
            kwargs = run.call_args.kwargs
            self.assertNotIn(marker, repr(argv))
            self.assertIn(marker, kwargs["input"].decode("utf-8"))
            self.assertNotIn(marker, repr(kwargs["env"]))
            self.assertEqual(len(marker), result["characters"])
            self.assertIn("-NoProfile", argv)
            self.assertIn("-NonInteractive", argv)
            self.assertEqual(str(backend.helper_path), argv[-1])

    def test_text_that_cannot_fit_timeout_is_rejected_before_helper_start(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            backend = self._backend(Path(temp_dir), timeout_seconds=6)
            with mock.patch("windows_text_input.subprocess.run") as run:
                with self.assertRaises(WindowsTextInputError) as caught:
                    backend.send_text(1001, "x" * 200)
            self.assertEqual("TEXT_INPUT_LIMIT", caught.exception.code)
            run.assert_not_called()

    def test_structured_helper_failure_is_propagated_without_raw_text(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            backend = self._backend(Path(temp_dir))
            marker = "raw-marker-must-not-return"
            error = {
                "code": "FOREGROUND_CHANGED",
                "message": "Windows text input did not complete safely",
                "data": {"partial_input_possible": True, "delivered_code_units": 4},
            }
            completed = subprocess.CompletedProcess(
                args=[],
                returncode=2,
                stdout=b"",
                stderr=json.dumps(error).encode("utf-8"),
            )
            with mock.patch("windows_text_input.subprocess.run", return_value=completed):
                with self.assertRaises(WindowsTextInputError) as caught:
                    backend.send_text(1001, marker)
            self.assertEqual("FOREGROUND_CHANGED", caught.exception.code)
            self.assertTrue(caught.exception.data["partial_input_possible"])
            self.assertNotIn(marker, str(caught.exception))

    def test_structured_error_is_found_after_powershell_diagnostic_prefix_without_republishing_prefix(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            backend = self._backend(Path(temp_dir))
            marker = "marker-must-not-leak"
            error = {
                "code": "TEXT_INPUT_PARTIAL",
                "message": "Windows text input did not complete safely",
                "data": {"partial_input_possible": True, "delivered_code_units": 2},
            }
            stderr = (
                "PowerShell diagnostic that must not be republished\n"
                + json.dumps(error)
                + "\n"
            ).encode("utf-8")
            completed = subprocess.CompletedProcess(args=[], returncode=2, stdout=b"", stderr=stderr)
            with mock.patch("windows_text_input.subprocess.run", return_value=completed):
                with self.assertRaises(WindowsTextInputError) as caught:
                    backend.send_text(1001, marker)
            self.assertEqual("TEXT_INPUT_PARTIAL", caught.exception.code)
            self.assertEqual(2, caught.exception.data["delivered_code_units"])
            self.assertNotIn("PowerShell diagnostic", str(caught.exception))
            self.assertNotIn(marker, str(caught.exception))

    def test_repository_helper_uses_raw_stdin_bytes_and_explicit_utf8_decode(self) -> None:
        helper = Path(__file__).resolve().parents[1] / "windows_text_input_helper.ps1"
        source = helper.read_text(encoding="utf-8")
        self.assertIn("[Console]::OpenStandardInput()", source)
        self.assertIn("$utf8.GetString($memory.ToArray())", source)
        self.assertNotIn("[Console]::In.ReadToEnd()", source)
        self.assertIn("KEYEVENTF_UNICODE", source)
        self.assertIn("GetForegroundWindow", source)
        self.assertIn("Thread.Sleep(5)", source)
        self.assertIn("Thread.Sleep(500)", source)

    def test_helper_timeout_reports_possible_partial_input(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            backend = self._backend(Path(temp_dir), timeout_seconds=30)
            with mock.patch(
                "windows_text_input.subprocess.run",
                side_effect=subprocess.TimeoutExpired(cmd=["powershell.exe"], timeout=30),
            ):
                with self.assertRaises(WindowsTextInputError) as caught:
                    backend.send_text(1001, "short text")
            self.assertEqual("TEXT_INPUT_TIMEOUT", caught.exception.code)
            self.assertTrue(caught.exception.data["partial_input_possible"])

    def test_helper_output_is_bounded_and_must_be_utf8_json_object(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            backend = self._backend(Path(temp_dir))
            completed = subprocess.CompletedProcess(args=[], returncode=0, stdout=b"not-json", stderr=b"")
            with mock.patch("windows_text_input.subprocess.run", return_value=completed):
                with self.assertRaises(WindowsTextInputError) as caught:
                    backend.send_text(1001, "short text")
            self.assertEqual("TEXT_INPUT_PROTOCOL_ERROR", caught.exception.code)


if __name__ == "__main__":
    unittest.main()
