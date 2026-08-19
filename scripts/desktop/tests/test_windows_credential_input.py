from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from windows_credential_input import (  # noqa: E402
    MAX_CREDENTIAL_UTF16_CODE_UNITS,
    IsolatedWindowsCredentialInput,
    WindowsCredentialInputError,
)


class IsolatedWindowsCredentialInputTest(unittest.TestCase):
    def _backend(self, root: Path, *, timeout_seconds: int = 30) -> IsolatedWindowsCredentialInput:
        helper = root / "windows_credential_input_helper.ps1"
        helper.write_text("# fixed helper", encoding="utf-8")
        backend = IsolatedWindowsCredentialInput.__new__(IsolatedWindowsCredentialInput)
        backend.helper_path = helper
        backend.timeout_seconds = timeout_seconds
        backend.powershell_path = root / "powershell.exe"
        backend.powershell_path.write_bytes(b"fake")
        return backend

    def test_child_environment_is_allowlisted_and_excludes_secret_like_values(self) -> None:
        with mock.patch.dict(
            os.environ,
            {
                "CONTROL_PLANE_API_KEY": "must-not-pass",
                "SESSION_TOKEN": "must-not-pass",
                "SYSTEMROOT": r"C:\Windows",
                "TEMP": r"C:\Temp",
            },
            clear=False,
        ):
            env = IsolatedWindowsCredentialInput._environment()
        self.assertEqual(r"C:\Windows", env["SYSTEMROOT"])
        self.assertEqual(r"C:\Temp", env["TEMP"])
        self.assertNotIn("CONTROL_PLANE_API_KEY", env)
        self.assertNotIn("SESSION_TOKEN", env)

    def test_helper_receives_only_non_secret_reference_over_stdin(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            backend = self._backend(Path(temp_dir))
            target = "HooshiX/Desktop/test-login"
            completed = subprocess.CompletedProcess(
                args=[],
                returncode=0,
                stdout=json.dumps({"credential_applied": True, "settle_ms": 500}).encode("utf-8"),
                stderr=b"",
            )
            with mock.patch("windows_credential_input.subprocess.run", return_value=completed) as run:
                result = backend.send_credential(1001, 77, target, focus_unique_password=True)
            argv = run.call_args.args[0]
            kwargs = run.call_args.kwargs
            payload = json.loads(kwargs["input"].decode("utf-8"))
            self.assertEqual(
                {
                    "hwnd": 1001,
                    "expected_process_id": 77,
                    "credential_target": target,
                    "focus_unique_password": True,
                    "max_utf16_code_units": MAX_CREDENTIAL_UTF16_CODE_UNITS,
                },
                payload,
            )
            self.assertNotIn(target, repr(argv))
            self.assertNotIn(target, repr(kwargs["env"]))
            self.assertEqual({"credential_applied": True, "settle_ms": 500}, result)

    def test_invalid_process_identity_or_focus_strategy_is_rejected_before_helper_start(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            backend = self._backend(Path(temp_dir))
            for process_id in (0, -1, True, "77"):
                with self.subTest(process_id=process_id):
                    with mock.patch("windows_credential_input.subprocess.run") as run:
                        with self.assertRaises(WindowsCredentialInputError):
                            backend.send_credential(1001, process_id, "HooshiX/Desktop/test")
                    run.assert_not_called()
            with mock.patch("windows_credential_input.subprocess.run") as run:
                with self.assertRaises(WindowsCredentialInputError):
                    backend.send_credential(1001, 77, "HooshiX/Desktop/test", focus_unique_password="yes")
                run.assert_not_called()

    def test_invalid_target_is_rejected_before_helper_start(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            backend = self._backend(Path(temp_dir))
            for target in ("", "bad\nname", "x" * 257):
                with self.subTest(target=target[:10]):
                    with mock.patch("windows_credential_input.subprocess.run") as run:
                        with self.assertRaises(WindowsCredentialInputError) as caught:
                            backend.send_credential(1001, 77, target)
                    self.assertEqual("CREDENTIAL_INPUT_UNAVAILABLE", caught.exception.code)
                    run.assert_not_called()

    def test_structured_helper_failure_never_republishes_unstructured_stderr(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            backend = self._backend(Path(temp_dir))
            error = {
                "code": "CREDENTIAL_FOCUS_CHANGED",
                "message": "Windows credential input did not complete safely",
                "data": {"partial_input_possible": True},
            }
            stderr = ("diagnostic-must-not-return\n" + json.dumps(error) + "\n").encode("utf-8")
            completed = subprocess.CompletedProcess(args=[], returncode=2, stdout=b"", stderr=stderr)
            with mock.patch("windows_credential_input.subprocess.run", return_value=completed):
                with self.assertRaises(WindowsCredentialInputError) as caught:
                    backend.send_credential(1001, 77, "HooshiX/Desktop/test")
            self.assertEqual("CREDENTIAL_FOCUS_CHANGED", caught.exception.code)
            self.assertTrue(caught.exception.data["partial_input_possible"])
            self.assertNotIn("diagnostic-must-not-return", str(caught.exception))

    def test_timeout_reports_possible_partial_input_without_secret_data(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            backend = self._backend(Path(temp_dir), timeout_seconds=30)
            with mock.patch(
                "windows_credential_input.subprocess.run",
                side_effect=subprocess.TimeoutExpired(cmd=["powershell.exe"], timeout=30),
            ):
                with self.assertRaises(WindowsCredentialInputError) as caught:
                    backend.send_credential(1001, 77, "HooshiX/Desktop/test")
            self.assertEqual("CREDENTIAL_INPUT_TIMEOUT", caught.exception.code)
            self.assertEqual({"partial_input_possible": True}, caught.exception.data)

    def test_success_protocol_is_exact_and_does_not_accept_extra_secret_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            backend = self._backend(Path(temp_dir))
            completed = subprocess.CompletedProcess(
                args=[],
                returncode=0,
                stdout=json.dumps(
                    {"credential_applied": True, "settle_ms": 500, "credential": "must-not-return"}
                ).encode("utf-8"),
                stderr=b"",
            )
            with mock.patch("windows_credential_input.subprocess.run", return_value=completed):
                with self.assertRaises(WindowsCredentialInputError) as caught:
                    backend.send_credential(1001, 77, "HooshiX/Desktop/test")
            self.assertEqual("CREDENTIAL_INPUT_PROTOCOL_ERROR", caught.exception.code)

    def test_repository_helper_reads_generic_credential_only_after_password_focus_check(self) -> None:
        helper = Path(__file__).resolve().parents[1] / "windows_credential_input_helper.ps1"
        source = helper.read_text(encoding="utf-8")
        self.assertIn("CredReadW", source)
        self.assertIn("CredFree", source)
        self.assertIn("Current.IsPassword", source)
        self.assertIn("IsPasswordProperty, true", source)
        self.assertIn("CREDENTIAL_PASSWORD_TARGET_AMBIGUOUS", source)
        self.assertIn("GetWindowThreadProcessId", source)
        self.assertIn("Automation.Compare", source)
        self.assertIn("RuntimeEnvironment]::GetRuntimeDirectory", source)
        self.assertIn("-ReferencedAssemblies @($uiaClientAssembly, $uiaTypesAssembly)", source)
        self.assertIn("GetForegroundWindow", source)
        self.assertIn("KEYEVENTF_UNICODE", source)
        self.assertIn("Thread.Sleep(5)", source)
        self.assertIn("Thread.Sleep(500)", source)
        cred_read = source.index("CredReadW(credentialTarget")
        self.assertLess(source.index("RequireFocusedPassword(hwnd, expectedProcessId)"), cred_read)
        self.assertLess(source.index("FocusUniquePassword(hwnd, expectedProcessId)"), cred_read)
        self.assertNotIn("Marshal.PtrToString", source)
        self.assertNotIn("CredentialBlob).ToString", source)


if __name__ == "__main__":
    unittest.main()
