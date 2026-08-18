from __future__ import annotations

import base64
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from desktop_engine import DesktopEngine, RunnerResult, ScreenshotResult, load_policy  # noqa: E402
from mcp_server import (  # noqa: E402
    CLIENT_CAPABILITIES_META_KEY,
    CLIENT_INFO_META_KEY,
    MODERN_VERSION,
    PROTOCOL_META_KEY,
    McpDesktopServer,
    _write_response,
)


def modern_meta() -> dict[str, object]:
    return {
        PROTOCOL_META_KEY: MODERN_VERSION,
        CLIENT_CAPABILITIES_META_KEY: {},
        CLIENT_INFO_META_KEY: {"name": "desktop-test", "version": "1.0"},
    }


class FakeRunner:
    def run(self, args: list[str], *, timeout_seconds: int) -> RunnerResult:
        if args == ["ui", "list-windows", "--json"]:
            value = [
                {
                    "hwnd": 1001,
                    "processId": 11,
                    "processName": "Notepad",
                    "title": "Test",
                    "width": 800,
                    "height": 600,
                    "isForeground": True,
                }
            ]
        else:
            value = {"ok": True}
        return RunnerResult(0, False, 1, json.dumps(value).encode("utf-8"), b"")


def make_engine(temp_dir: str) -> DesktopEngine:
    root = Path(temp_dir)
    fake_winapp = root / "winapp.exe"
    fake_winapp.write_bytes(b"fake")
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
                "audit_log": str(root / "audit" / "desktop.ndjson"),
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
                "max_output_bytes": 1048576,
                "max_screenshot_bytes": 1048576,
                "max_text_chars": 4096,
                "max_inspect_depth": 8,
                "max_audit_bytes": 1048576,
                "audit_backups": 3,
            }
        ),
        encoding="utf-8",
    )
    return DesktopEngine(load_policy(policy_path), FakeRunner(), runtime_version="0.6.0")


class McpDesktopServerTest(unittest.TestCase):
    def test_tool_surface_is_exact_and_separate(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            server = McpDesktopServer(make_engine(temp_dir))
            response = server.dispatch(
                {"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {"_meta": modern_meta()}}
            )
            tools = response["result"]["tools"]
            names = [tool["name"] for tool in tools]
            self.assertEqual(
                [
                    "desktop.status",
                    "desktop.audit_tail",
                    "desktop.list_windows",
                    "desktop.inspect",
                    "desktop.screenshot",
                    "desktop.invoke",
                    "desktop.focus",
                    "desktop.click",
                    "desktop.hover",
                    "desktop.drag",
                    "desktop.type_text",
                    "desktop.key_press",
                ],
                names,
            )
            self.assertFalse(any(name.startswith("project.") or name.startswith("filesystem.") or name == "process.run" for name in names))
            by_name = {tool["name"]: tool for tool in tools}
            self.assertTrue(by_name["desktop.inspect"]["annotations"]["readOnlyHint"])
            self.assertTrue(by_name["desktop.screenshot"]["annotations"]["readOnlyHint"])
            self.assertTrue(by_name["desktop.click"]["annotations"]["destructiveHint"])
            self.assertTrue(by_name["desktop.type_text"]["annotations"]["destructiveHint"])
            self.assertFalse(by_name["desktop.status"]["annotations"]["openWorldHint"])
            self.assertTrue(by_name["desktop.list_windows"]["annotations"]["openWorldHint"])

    def test_status_and_list_windows_return_structured_content(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            server = McpDesktopServer(make_engine(temp_dir))
            for name in ("desktop.status", "desktop.list_windows"):
                response = server.dispatch(
                    {
                        "jsonrpc": "2.0",
                        "id": name,
                        "method": "tools/call",
                        "params": {"_meta": modern_meta(), "name": name, "arguments": {}},
                    }
                )
                self.assertFalse(response["result"]["isError"])
                structured = response["result"]["structuredContent"]
                text = response["result"]["content"][0]["text"]
                self.assertEqual(structured, json.loads(text))

    def test_screenshot_result_uses_mcp_image_content_without_base64_in_structured_content(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            server = McpDesktopServer(make_engine(temp_dir))
            png = b"\x89PNG\r\n\x1a\nimage"
            shot = ScreenshotResult(
                metadata={"hwnd": 1001, "bytes": len(png), "sha256": "abc"},
                png=png,
            )
            result = server._tool_result(shot)
            self.assertEqual("image", result["content"][1]["type"])
            self.assertEqual(png, base64.b64decode(result["content"][1]["data"]))
            self.assertEqual("image/png", result["content"][1]["mimeType"])
            self.assertNotIn("data", result["structuredContent"])
            self.assertNotIn(base64.b64encode(png).decode("ascii"), json.dumps(result["structuredContent"]))

    def test_policy_denial_is_tool_error_not_protocol_crash(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            server = McpDesktopServer(make_engine(temp_dir))
            response = server.dispatch(
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "tools/call",
                    "params": {
                        "_meta": modern_meta(),
                        "name": "desktop.inspect",
                        "arguments": {"hwnd": 999999},
                    },
                }
            )
            self.assertTrue(response["result"]["isError"])
            self.assertIn("WINDOW_NOT_FOUND", response["result"]["content"][0]["text"])

    def test_tools_call_notification_without_request_id_does_not_execute_action(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            engine = make_engine(temp_dir)
            server = McpDesktopServer(engine)
            before = engine.audit_tail(100)["entries"]
            response = server.dispatch(
                {
                    "jsonrpc": "2.0",
                    "method": "tools/call",
                    "params": {"_meta": modern_meta(), "name": "desktop.list_windows", "arguments": {}},
                }
            )
            after = engine.audit_tail(100)["entries"]
            self.assertIsNone(response)
            self.assertEqual(before, after)

    def test_unknown_tool_and_unsupported_arguments_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            server = McpDesktopServer(make_engine(temp_dir))
            unknown = server.dispatch(
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "tools/call",
                    "params": {"_meta": modern_meta(), "name": "process.run", "arguments": {}},
                }
            )
            self.assertEqual(-32602, unknown["error"]["code"])
            extra = server.dispatch(
                {
                    "jsonrpc": "2.0",
                    "id": 2,
                    "method": "tools/call",
                    "params": {
                        "_meta": modern_meta(),
                        "name": "desktop.status",
                        "arguments": {"unexpected": True},
                    },
                }
            )
            self.assertEqual(-32602, extra["error"]["code"])

    def test_entrypoint_fails_closed_when_policy_is_missing(self) -> None:
        script = Path(__file__).resolve().parents[1] / "mcp_server.py"
        environment = dict(os.environ)
        environment.pop("HOOSHIX_DESKTOP_POLICY", None)
        with tempfile.TemporaryDirectory() as temp_dir:
            completed = subprocess.run(
                [sys.executable, "-B", str(script)],
                input="",
                capture_output=True,
                text=True,
                timeout=10,
                check=False,
                env=environment,
                cwd=temp_dir,
            )
        self.assertEqual(2, completed.returncode)
        self.assertEqual("", completed.stdout)
        self.assertIn("Desktop policy path is required", completed.stderr)

    def test_response_writer_always_emits_utf8_bytes(self) -> None:
        raw_stdout = io.BytesIO()
        non_utf8_text_stdout = io.TextIOWrapper(raw_stdout, encoding="cp1252")
        response = {"jsonrpc": "2.0", "id": 1, "result": {"text": "دسکتاپ…"}}
        with mock.patch.object(sys, "stdout", non_utf8_text_stdout):
            _write_response(response)
        payload = raw_stdout.getvalue()
        self.assertEqual(response, json.loads(payload.decode("utf-8")))
        self.assertIn("…".encode("utf-8"), payload)


if __name__ == "__main__":
    unittest.main()
