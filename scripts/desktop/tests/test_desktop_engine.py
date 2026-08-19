from __future__ import annotations

import hashlib
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from desktop_engine import (  # noqa: E402
    OPTIONAL_POLICY_FIELDS,
    POLICY_FIELDS,
    DesktopEngine,
    DesktopError,
    RunnerResult,
    SubprocessWinAppRunner,
    load_policy,
)


WINDOWS = [
    {
        "hwnd": 1001,
        "processId": 11,
        "processName": "Notepad",
        "title": "Visible developer note",
        "width": 800,
        "height": 600,
        "isForeground": True,
        "className": "Notepad",
    },
    {
        "hwnd": 2002,
        "processId": 22,
        "processName": "Consent",
        "title": "Sensitive broker",
        "width": 400,
        "height": 300,
        "isForeground": False,
    },
    {
        "hwnd": 3003,
        "processId": 33,
        "processName": "CalculatorApp",
        "title": "Calculator",
        "width": 500,
        "height": 500,
        "isForeground": False,
    },
]


def rr_json(value: object, *, exit_code: int = 0, truncated: bool = False) -> RunnerResult:
    return RunnerResult(
        exit_code=exit_code,
        timed_out=False,
        duration_ms=2,
        stdout=json.dumps(value, ensure_ascii=False).encode("utf-8"),
        stderr=b"",
        stdout_truncated=truncated,
    )


class FakeTextInput:
    def __init__(self) -> None:
        self.calls: list[tuple[int, str]] = []

    def send_text(self, hwnd: int, text: str) -> dict[str, int]:
        self.calls.append((hwnd, text))
        units = len(text.replace("\r\n", "\r").replace("\n", "\r").encode("utf-16-le")) // 2
        return {"characters": len(text), "utf16_code_units": units, "chunks": 1}


def fake_process_identity(process_id: int) -> tuple[str, str]:
    identities = {
        11: (r"C:\Program Files\WindowsApps\Notepad\notepad.exe", "1" * 64),
        22: (r"C:\Windows\System32\consent.exe", "2" * 64),
        33: (r"C:\Program Files\WindowsApps\Calculator\calculatorapp.exe", "3" * 64),
    }
    if process_id not in identities:
        raise OSError("unknown process")
    return identities[process_id]


class FakeCredentialInput:
    def __init__(self) -> None:
        self.calls: list[tuple[int, str]] = []

    def send_credential(self, hwnd: int, expected_process_id: int, credential_target: str, *, focus_unique_password: bool = False) -> dict[str, object]:
        self.calls.append((hwnd, expected_process_id, credential_target, focus_unique_password))
        return {"credential_applied": True, "settle_ms": 500}


class FakeRunner:
    def __init__(self, handler=None) -> None:
        self.calls: list[tuple[list[str], int]] = []
        self.handler = handler or self._default

    def run(self, args: list[str], *, timeout_seconds: int) -> RunnerResult:
        self.calls.append((list(args), timeout_seconds))
        return self.handler(args)

    @staticmethod
    def _default(args: list[str]) -> RunnerResult:
        if args == ["ui", "list-windows", "--json"]:
            return rr_json(WINDOWS)
        return rr_json({"ok": True})


def make_policy(
    root: Path,
    *,
    allow_all_apps: bool = False,
    allowed_apps: list[str] | None = None,
    denied_apps: list[str] | None = None,
    allow_screenshot: bool = True,
    allow_capture_screen: bool = False,
    allow_uia_mutation: bool = True,
    allow_mouse_input: bool = True,
    allow_keyboard_input: bool = True,
    allow_system_keys: bool = False,
    max_screenshot_bytes: int = 1024 * 1024,
    allow_credential_input: bool = False,
    credential_bindings: list[dict[str, str]] | None = None,
) -> Path:
    fake_winapp = root / "fake-winapp.exe"
    fake_winapp.write_bytes(b"fake")
    policy = root / "desktop-policy.json"
    policy.write_text(
        json.dumps(
            {
                "schema_version": 1,
                "winapp_path": str(fake_winapp),
                "expected_winapp_version": "0.6.0",
                "allow_all_apps": allow_all_apps,
                "allowed_apps": ["notepad"] if allowed_apps is None else allowed_apps,
                "denied_apps": ["consent", "credentialuibroker", "logonui"] if denied_apps is None else denied_apps,
                "audit_log": str(root / "audit" / "desktop.ndjson"),
                "capture_temp_dir": str(root / "captures"),
                "require_interactive_session": False,
                "require_non_elevated": False,
                "allow_screenshot": allow_screenshot,
                "allow_capture_screen": allow_capture_screen,
                "allow_uia_mutation": allow_uia_mutation,
                "allow_mouse_input": allow_mouse_input,
                "allow_keyboard_input": allow_keyboard_input,
                "allow_system_keys": allow_system_keys,
                "allow_credential_input": allow_credential_input,
                "credential_bindings": [] if credential_bindings is None else credential_bindings,
                "max_command_seconds": 10,
                "max_output_bytes": 1024 * 1024,
                "max_screenshot_bytes": max_screenshot_bytes,
                "max_text_chars": 4096,
                "max_inspect_depth": 8,
                "max_audit_bytes": 1024 * 1024,
                "audit_backups": 3,
            }
        ),
        encoding="utf-8",
    )
    return policy


def make_engine(root: Path, *, runner: FakeRunner | None = None, **policy_options) -> tuple[DesktopEngine, FakeRunner, Path]:
    policy_path = make_policy(root, **policy_options)
    effective_runner = runner or FakeRunner()
    engine = DesktopEngine(load_policy(policy_path), effective_runner, runtime_version="0.6.0", text_input=FakeTextInput(), credential_input=FakeCredentialInput(), credential_process_identity=fake_process_identity)
    return engine, effective_runner, policy_path


class DesktopEngineTest(unittest.TestCase):
    def test_policy_schema_fields_match_runtime_loader(self) -> None:
        schema_path = Path(__file__).resolve().parents[3] / "desktop" / "policy.schema.json"
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        self.assertEqual(POLICY_FIELDS, set(schema["properties"]))
        self.assertEqual(POLICY_FIELDS - OPTIONAL_POLICY_FIELDS, set(schema["required"]))

    def test_policy_rejects_unknown_duplicate_and_ambiguous_app_state(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            policy = make_policy(root)
            data = json.loads(policy.read_text(encoding="utf-8"))
            data["allow_magic"] = True
            policy.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaisesRegex(DesktopError, "unsupported fields"):
                load_policy(policy)

            data.pop("allow_magic")
            data["allowed_apps"] = ["Notepad.exe", "notepad"]
            policy.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaisesRegex(DesktopError, "duplicate normalized"):
                load_policy(policy)

            data["allowed_apps"] = ["notepad"]
            data["denied_apps"] = ["NOTEPAD.EXE"]
            policy.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaisesRegex(DesktopError, "overlap"):
                load_policy(policy)

            raw = json.dumps(json.loads(make_policy(root).read_text(encoding="utf-8")))
            raw = raw.replace('"schema_version": 1,', '"schema_version": 1, "schema_version": 1,', 1)
            policy.write_text(raw, encoding="utf-8")
            with self.assertRaisesRegex(DesktopError, "duplicate JSON key: schema_version"):
                load_policy(policy)

    def test_policy_requires_explicit_allowed_apps_and_capability_dependencies(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            policy = make_policy(root, allowed_apps=[])
            with self.assertRaisesRegex(DesktopError, "allowed_apps must be non-empty"):
                load_policy(policy)

            policy = make_policy(root)
            data = json.loads(policy.read_text(encoding="utf-8"))
            data["allow_capture_screen"] = True
            data["allow_screenshot"] = False
            policy.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaisesRegex(DesktopError, "requires allow_screenshot"):
                load_policy(policy)

            data["allow_capture_screen"] = False
            data["allow_system_keys"] = True
            data["allow_keyboard_input"] = False
            policy.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaisesRegex(DesktopError, "requires allow_keyboard_input"):
                load_policy(policy)

    def test_legacy_policy_without_credential_fields_defaults_broker_off(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            policy_path = make_policy(root)
            data = json.loads(policy_path.read_text(encoding="utf-8"))
            data.pop("allow_credential_input")
            data.pop("credential_bindings")
            policy_path.write_text(json.dumps(data), encoding="utf-8")
            policy = load_policy(policy_path)
            self.assertFalse(policy.allow_credential_input)
            self.assertEqual((), policy.credential_bindings)

    def test_credential_policy_requires_explicit_capability_and_authorized_unique_bindings(self) -> None:
        binding = {
            "credential_id": "notepad-main",
            "app": "notepad",
            "executable_path": r"C:\Program Files\WindowsApps\Notepad\notepad.exe",
            "executable_sha256": "1" * 64,
            "target_selector": "PasswordBox",
            "credential_target": "HooshiX/Desktop/notepad-main",
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            policy_path = make_policy(root, credential_bindings=[binding])
            with self.assertRaisesRegex(DesktopError, "require allow_credential_input"):
                load_policy(policy_path)

            policy_path = make_policy(root, allow_credential_input=True, credential_bindings=[binding])
            policy = load_policy(policy_path)
            self.assertEqual("notepad-main", policy.credential_bindings[0].credential_id)
            self.assertEqual("notepad", policy.credential_bindings[0].app)

            duplicate = dict(binding)
            duplicate["credential_target"] = "HooshiX/Desktop/other-target"
            policy_path = make_policy(root, allow_credential_input=True, credential_bindings=[binding, duplicate])
            with self.assertRaisesRegex(DesktopError, "duplicate credential_id"):
                load_policy(policy_path)

            denied = dict(binding)
            denied["app"] = "consent"
            denied["executable_path"] = r"C:\Windows\System32\consent.exe"
            denied["executable_sha256"] = "2" * 64
            policy_path = make_policy(root, allow_all_apps=True, denied_apps=[], allow_credential_input=True, credential_bindings=[denied])
            with self.assertRaisesRegex(DesktopError, "not authorized for credential use"):
                load_policy(policy_path)

            coordinate = dict(binding)
            coordinate["target_selector"] = "10,20"
            policy_path = make_policy(root, allow_credential_input=True, credential_bindings=[coordinate])
            with self.assertRaisesRegex(DesktopError, "coordinate-only"):
                load_policy(policy_path)

            unsupported_strategy = dict(binding)
            unsupported_strategy["target_selector"] = "@first-password"
            policy_path = make_policy(root, allow_credential_input=True, credential_bindings=[unsupported_strategy])
            with self.assertRaisesRegex(DesktopError, "unsupported credential target strategy"):
                load_policy(policy_path)

    def test_list_windows_filters_by_real_process_policy(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            engine, runner, _ = make_engine(Path(temp_dir))
            result = engine.list_windows()
            self.assertEqual([1001], [item["hwnd"] for item in result["windows"]])
            self.assertEqual("Notepad", result["windows"][0]["processName"])
            self.assertEqual(1, len(runner.calls))

    def test_allow_all_still_honors_denied_apps(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            engine, _, _ = make_engine(Path(temp_dir), allow_all_apps=True)
            hwnds = [item["hwnd"] for item in engine.list_windows()["windows"]]
            self.assertEqual([1001, 3003], hwnds)
            with self.assertRaisesRegex(DesktopError, "denied"):
                engine.inspect(2002)

    def test_target_action_revalidates_hwnd_process_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            calls = 0

            def handler(args: list[str]) -> RunnerResult:
                nonlocal calls
                if args == ["ui", "list-windows", "--json"]:
                    calls += 1
                    process = "Notepad" if calls == 1 else "Consent"
                    return rr_json([{**WINDOWS[0], "processName": process}])
                return rr_json({"ok": True})

            engine, _, _ = make_engine(Path(temp_dir), runner=FakeRunner(handler))
            engine.inspect(1001)
            with self.assertRaisesRegex(DesktopError, "denied"):
                engine.inspect(1001)

    def test_inspect_bounds_depth_and_rejects_coordinate_selector(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            engine, runner, _ = make_engine(Path(temp_dir))
            result = engine.inspect(1001, selector="SettingsButton", depth=5, interactive=True)
            self.assertEqual({"ok": True}, result)
            self.assertIn("--interactive", runner.calls[-1][0])
            self.assertIn("5", runner.calls[-1][0])
            with self.assertRaisesRegex(DesktopError, "coordinate-only"):
                engine.inspect(1001, selector="10,20")
            with self.assertRaisesRegex(DesktopError, "depth"):
                engine.inspect(1001, depth=9)

    def test_screenshot_returns_png_and_removes_temp_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            created: list[Path] = []

            def handler(args: list[str]) -> RunnerResult:
                if args == ["ui", "list-windows", "--json"]:
                    return rr_json(WINDOWS)
                if args[:2] == ["ui", "screenshot"]:
                    output = Path(args[args.index("--output") + 1])
                    output.write_bytes(b"\x89PNG\r\n\x1a\n" + b"png-data")
                    created.append(output)
                    return rr_json({"filePath": str(output), "width": 800, "height": 600, "hwnd": 1001})
                return rr_json({"ok": True})

            engine, _, _ = make_engine(root, runner=FakeRunner(handler))
            result = engine.screenshot(1001)
            self.assertTrue(result.png.startswith(b"\x89PNG"))
            self.assertEqual(hashlib.sha256(result.png).hexdigest(), result.metadata["sha256"])
            self.assertTrue(created)
            self.assertFalse(created[0].exists())

    def test_screenshot_cleanup_failure_is_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            def handler(args: list[str]) -> RunnerResult:
                if args == ["ui", "list-windows", "--json"]:
                    return rr_json(WINDOWS)
                if args[:2] == ["ui", "screenshot"]:
                    output = Path(args[args.index("--output") + 1])
                    output.write_bytes(b"\x89PNG\r\n\x1a\n" + b"png-data")
                    return rr_json({"filePath": str(output), "width": 800, "height": 600, "hwnd": 1001})
                return rr_json({"ok": True})
            engine, _, _ = make_engine(root, runner=FakeRunner(handler))
            original_unlink = Path.unlink
            def guarded_unlink(path, *args, **kwargs):
                if path.parent == engine.policy.capture_temp_dir:
                    raise OSError("simulated capture cleanup failure")
                return original_unlink(path, *args, **kwargs)
            with mock.patch.object(Path, "unlink", guarded_unlink):
                with self.assertRaises(DesktopError) as caught:
                    engine.screenshot(1001)
            self.assertEqual("SCREENSHOT_CLEANUP_FAILED", caught.exception.code)
            audit = engine.policy.audit_log.read_text(encoding="utf-8")
            self.assertIn('"action":"desktop.screenshot","outcome":"failed"', audit)
            self.assertNotIn('"action":"desktop.screenshot","outcome":"passed"', audit)

    def test_capture_screen_and_mouse_uia_keyboard_require_policy_opt_in(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            engine, _, _ = make_engine(root, allow_screenshot=False, allow_uia_mutation=False, allow_mouse_input=False, allow_keyboard_input=False)
            with self.assertRaisesRegex(DesktopError, "screenshot is disabled"):
                engine.screenshot(1001)
            with self.assertRaisesRegex(DesktopError, "UI Automation mutation"):
                engine.invoke(1001, "SettingsButton")
            with self.assertRaisesRegex(DesktopError, "mouse input"):
                engine.click(1001, "SettingsButton")
            with self.assertRaisesRegex(DesktopError, "keyboard input"):
                engine.type_text(1001, "hello")

            engine, _, _ = make_engine(root)
            with self.assertRaisesRegex(DesktopError, "capture-screen"):
                engine.screenshot(1001, capture_screen=True)

    def test_drag_rejects_coordinate_endpoints(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            engine, _, _ = make_engine(Path(temp_dir))
            with self.assertRaisesRegex(DesktopError, "coordinate-only"):
                engine.drag(1001, "10,20", "SettingsButton")
            with self.assertRaisesRegex(DesktopError, "coordinate-only"):
                engine.drag(1001, "SettingsButton", "30,40")

    def test_keyboard_grammar_blocks_raw_text_virtual_and_system_keys(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            engine, runner, _ = make_engine(Path(temp_dir))
            engine.key_press(1001, "ctrl+a delete")
            self.assertIn("ctrl+vk=0x41 delete", runner.calls[-1][0])
            for keys in ("text=secret", "vk=0x42", r"a\sb", "alt+f4", "win+r", "ctrl+shift+esc", "win+l", "ctrl+alt+delete"):
                with self.subTest(keys=keys):
                    with self.assertRaises(DesktopError):
                        engine.key_press(1001, keys)

    def test_use_credential_is_policy_bound_reauthorizes_and_redacts_audit(self) -> None:
        binding = {
            "credential_id": "notepad-main",
            "app": "notepad",
            "executable_path": r"C:\Program Files\WindowsApps\Notepad\notepad.exe",
            "executable_sha256": "1" * 64,
            "target_selector": "PasswordBox",
            "credential_target": "HooshiX/Desktop/notepad-main",
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            engine, runner, _ = make_engine(
                root,
                allow_credential_input=True,
                credential_bindings=[binding],
            )
            result = engine.use_credential(1001, "notepad-main")
            self.assertEqual({"hwnd": 1001, "credential_applied": True}, result)
            self.assertEqual([(1001, 11, "HooshiX/Desktop/notepad-main", False)], engine.credential_input.calls)
            self.assertEqual(2, sum(1 for args, _ in runner.calls if args == ["ui", "list-windows", "--json"]))
            self.assertIn(
                ["ui", "focus", "PasswordBox", "-w", "1001", "--json"],
                [args for args, _ in runner.calls],
            )
            audit = engine.policy.audit_log.read_text(encoding="utf-8")
            self.assertNotIn("notepad-main", audit)
            self.assertNotIn("HooshiX/Desktop/notepad-main", audit)
            self.assertNotIn("PasswordBox", audit)
            self.assertIn("credential_ref_sha256", audit)
            self.assertIn("selector_sha256", audit)

    def test_use_credential_unique_password_strategy_skips_unstable_winapp_selector(self) -> None:
        binding = {
            "credential_id": "notepad-main",
            "app": "notepad",
            "executable_path": r"C:\Program Files\WindowsApps\Notepad\notepad.exe",
            "executable_sha256": "1" * 64,
            "target_selector": "@unique-password",
            "credential_target": "HooshiX/Desktop/notepad-main",
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            engine, runner, _ = make_engine(root, allow_credential_input=True, credential_bindings=[binding])
            result = engine.use_credential(1001, "notepad-main")
            self.assertTrue(result["credential_applied"])
            self.assertEqual([(1001, 11, "HooshiX/Desktop/notepad-main", True)], engine.credential_input.calls)
            self.assertFalse(any(args[:2] == ["ui", "focus"] for args, _ in runner.calls))

    def test_use_credential_fails_closed_for_disabled_unknown_or_wrong_app_binding(self) -> None:
        binding = {
            "credential_id": "calculator-main",
            "app": "calculatorapp",
            "executable_path": r"C:\Program Files\WindowsApps\Calculator\calculatorapp.exe",
            "executable_sha256": "3" * 64,
            "target_selector": "PasswordBox",
            "credential_target": "HooshiX/Desktop/calculator-main",
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            disabled, _, _ = make_engine(root)
            with self.assertRaises(DesktopError) as caught:
                disabled.use_credential(1001, "anything")
            self.assertEqual("CREDENTIAL_INPUT_DENIED", caught.exception.code)

            engine, _, _ = make_engine(
                root,
                allow_all_apps=True,
                allow_credential_input=True,
                credential_bindings=[binding],
            )
            with self.assertRaises(DesktopError) as unknown:
                engine.use_credential(1001, "missing")
            self.assertEqual("CREDENTIAL_UNAVAILABLE", unknown.exception.code)
            with self.assertRaises(DesktopError) as mismatch:
                engine.use_credential(1001, "calculator-main")
            self.assertEqual("CREDENTIAL_BINDING_MISMATCH", mismatch.exception.code)
            self.assertEqual([], engine.credential_input.calls)

    def test_use_credential_rejects_executable_path_or_hash_mismatch_before_helper(self) -> None:
        binding = {
            "credential_id": "notepad-main",
            "app": "notepad",
            "executable_path": r"C:\Program Files\WindowsApps\Notepad\notepad.exe",
            "executable_sha256": "1" * 64,
            "target_selector": "PasswordBox",
            "credential_target": "HooshiX/Desktop/notepad-main",
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            engine, _, _ = make_engine(root, allow_credential_input=True, credential_bindings=[binding])
            engine.credential_process_identity = lambda pid: (r"C:\Malicious\notepad.exe", "1" * 64)
            with self.assertRaises(DesktopError) as path_error:
                engine.use_credential(1001, "notepad-main")
            self.assertEqual("CREDENTIAL_BINDING_MISMATCH", path_error.exception.code)
            self.assertEqual([], engine.credential_input.calls)

            engine.credential_process_identity = lambda pid: (binding["executable_path"], "f" * 64)
            with self.assertRaises(DesktopError) as hash_error:
                engine.use_credential(1001, "notepad-main")
            self.assertEqual("CREDENTIAL_BINDING_MISMATCH", hash_error.exception.code)
            self.assertEqual([], engine.credential_input.calls)

    def test_key_press_maps_valid_alphanumeric_chords_to_explicit_virtual_keys(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            engine, runner, _ = make_engine(Path(temp_dir))
            engine.key_press(1001, "ctrl+s alt+4 enter")
            args = runner.calls[-1][0]
            self.assertIn("ctrl+vk=0x53 alt+vk=0x34 enter", args)
            self.assertNotIn("ctrl+s alt+4 enter", args)

    def test_internal_chord_mapping_does_not_expand_public_raw_vk_grammar(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            engine, _, _ = make_engine(Path(temp_dir))
            self.assertEqual("ctrl+vk=0x53", engine._normalize_winapp_chords("ctrl+s"))
            self.assertEqual("ctrl+vk=0x41 shift+vk=0x39 enter", engine._normalize_winapp_chords("ctrl+a shift+9 enter"))
            with self.assertRaises(DesktopError):
                engine.key_press(1001, "ctrl+vk=0x53")

    def test_system_key_policy_can_enable_bounded_shell_combos_but_not_hard_blocks(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            engine, runner, _ = make_engine(Path(temp_dir), allow_system_keys=True)
            engine.key_press(1001, "win+r")
            self.assertIn("--allow-system-keys", runner.calls[-1][0])
            with self.assertRaisesRegex(DesktopError, "prohibited"):
                engine.key_press(1001, "win+l")

    def test_type_text_and_audit_never_store_raw_text_selector_or_window_title(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            engine, runner, _ = make_engine(root)
            secretish_marker = "literal-marker-that-must-not-be-audited"
            selector = "RichEditBox-private-selector"
            result = engine.type_text(1001, secretish_marker, target_selector=selector)
            self.assertEqual(1001, result["hwnd"])
            self.assertEqual((1001, secretish_marker), engine.text_input.calls[-1])
            self.assertNotIn(secretish_marker, repr(runner.calls))
            self.assertIn(["ui", "focus", selector, "-w", "1001", "--json"], [call[0] for call in runner.calls])
            audit = engine.policy.audit_log.read_text(encoding="utf-8")
            self.assertNotIn(secretish_marker, audit)
            self.assertNotIn(selector, audit)
            self.assertNotIn("Visible developer note", audit)
            self.assertIn("text_sha256", audit)
            self.assertIn("selector_sha256", audit)

    def test_winapp_protocol_failure_is_bounded_and_does_not_republish_stderr(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            raw_stderr = b"sensitive-window-text"

            def handler(args: list[str]) -> RunnerResult:
                if args == ["ui", "list-windows", "--json"]:
                    return RunnerResult(1, False, 1, b"", raw_stderr)
                return rr_json({})

            engine, _, _ = make_engine(Path(temp_dir), runner=FakeRunner(handler))
            with self.assertRaises(DesktopError) as caught:
                engine.list_windows()
            self.assertEqual("WINAPP_COMMAND_FAILED", caught.exception.code)
            self.assertNotIn(raw_stderr.decode(), str(caught.exception))
            self.assertEqual(hashlib.sha256(raw_stderr).hexdigest(), caught.exception.data["stderr_sha256"])

    def test_runtime_state_requires_non_elevated_interactive_session_when_configured(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            policy_path = make_policy(Path(temp_dir))
            data = json.loads(policy_path.read_text(encoding="utf-8"))
            data["require_non_elevated"] = True
            data["require_interactive_session"] = True
            policy_path.write_text(json.dumps(data), encoding="utf-8")
            policy = load_policy(policy_path)
            with self.assertRaises(DesktopError) as elevated:
                DesktopEngine._validate_runtime_state(policy, elevated=True, interactive=True)
            self.assertEqual("ELEVATION_DENIED", elevated.exception.code)
            with self.assertRaises(DesktopError) as noninteractive:
                DesktopEngine._validate_runtime_state(policy, elevated=False, interactive=False)
            self.assertEqual("INTERACTIVE_SESSION_REQUIRED", noninteractive.exception.code)
            DesktopEngine._validate_runtime_state(policy, elevated=False, interactive=True)

    def test_version_probe_requires_exact_policy_version(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            policy = load_policy(make_policy(Path(temp_dir)))
            ok = FakeRunner(lambda args: RunnerResult(0, False, 1, b"0.6.0\n", b""))
            self.assertEqual("0.6.0", DesktopEngine._probe_version(policy, ok))
            mismatch = FakeRunner(lambda args: RunnerResult(0, False, 1, b"0.6.1\n", b""))
            with self.assertRaises(DesktopError) as caught:
                DesktopEngine._probe_version(policy, mismatch)
            self.assertEqual("WINAPP_VERSION_MISMATCH", caught.exception.code)

    def test_child_environment_excludes_tunnel_credentials_and_opts_out_of_telemetry(self) -> None:
        with mock.patch.dict(os.environ, {"CONTROL_PLANE_API_KEY": "must-not-pass", "PATH": "safe-path"}, clear=False):
            env = SubprocessWinAppRunner._environment()
        self.assertNotIn("CONTROL_PLANE_API_KEY", env)
        self.assertEqual("1", env["WINAPP_CLI_TELEMETRY_OPTOUT"])
        self.assertEqual("1", env["HOOSHIX_DESKTOP_CHILD"])

    def test_audit_rotation_bounds_primary_log(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            engine, _, _ = make_engine(Path(temp_dir))
            padding = "x" * 700_000
            engine._audit("test.large", "passed", {"padding": padding})
            engine._audit("test.large", "passed", {"padding": padding})
            self.assertLessEqual(engine.policy.audit_log.stat().st_size, engine.policy.max_audit_bytes)
            self.assertTrue(Path(f"{engine.policy.audit_log}.1").exists())


if __name__ == "__main__":
    unittest.main()
