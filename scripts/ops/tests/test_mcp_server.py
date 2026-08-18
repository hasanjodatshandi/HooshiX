from __future__ import annotations

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

from mcp_server import (  # noqa: E402
    CLIENT_CAPABILITIES_META_KEY,
    CLIENT_INFO_META_KEY,
    MODERN_VERSION,
    PROTOCOL_META_KEY,
    McpOpsServer,
    _write_response,
)
from ops_engine import OpsEngine  # noqa: E402


def modern_meta() -> dict[str, object]:
    return {
        PROTOCOL_META_KEY: MODERN_VERSION,
        CLIENT_CAPABILITIES_META_KEY: {},
        CLIENT_INFO_META_KEY: {"name": "ops-test", "version": "1.0"},
    }


def make_engine(temp_dir: str) -> tuple[OpsEngine, Path]:
    root = Path(temp_dir)
    allowed = root / "allowed"
    allowed.mkdir()
    policy = root / "policy.json"
    policy.write_text(
        json.dumps(
            {
                "schema_version": 1,
                "allowed_roots": [str(allowed)],
                "denied_roots": [],
                "commands": {"python": sys.executable},
                "audit_log": str(root / "audit.ndjson"),
                "require_elevated": False,
                "allow_process_execution": True,
                "allow_elevated_mutation": True,
                "allow_elevated_process_execution": True,
                "max_command_seconds": 5,
                "max_output_bytes": 65536,
                "max_file_bytes": 1024 * 1024,
                "max_list_entries": 100,
                "max_audit_bytes": 1024 * 1024,
                "audit_backups": 3,
            }
        ),
        encoding="utf-8",
    )
    return OpsEngine.from_policy_path(policy), policy


class McpOpsServerTest(unittest.TestCase):
    def test_tool_surface_is_separate_and_mutation_is_explicit(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            engine, _ = make_engine(temp_dir)
            server = McpOpsServer(engine)
            response = server.dispatch(
                {"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {"_meta": modern_meta()}}
            )
            tools = response["result"]["tools"]
            names = [tool["name"] for tool in tools]
            self.assertEqual(
                [
                    "ops.status",
                    "ops.audit_tail",
                    "filesystem.stat",
                    "filesystem.list",
                    "filesystem.read_text",
                    "filesystem.write_text",
                    "filesystem.mkdir",
                    "filesystem.delete",
                    "process.run",
                ],
                names,
            )
            by_name = {tool["name"]: tool for tool in tools}
            self.assertTrue(by_name["filesystem.read_text"]["annotations"]["readOnlyHint"])
            self.assertFalse(by_name["filesystem.write_text"]["annotations"]["readOnlyHint"])
            self.assertTrue(by_name["filesystem.delete"]["annotations"]["destructiveHint"])
            self.assertTrue(by_name["process.run"]["annotations"]["destructiveHint"])
            self.assertTrue(by_name["process.run"]["annotations"]["openWorldHint"])
            self.assertFalse(any(name.startswith("project.") for name in names))

    def test_status_and_write_round_trip(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            engine, _ = make_engine(temp_dir)
            server = McpOpsServer(engine)
            status = server.dispatch(
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "tools/call",
                    "params": {"_meta": modern_meta(), "name": "ops.status", "arguments": {}},
                }
            )
            self.assertFalse(status["result"]["isError"])
            self.assertEqual("developer-host-ops", status["result"]["structuredContent"]["mode"])

            target = Path(temp_dir) / "allowed" / "hello.txt"
            write = server.dispatch(
                {
                    "jsonrpc": "2.0",
                    "id": 2,
                    "method": "tools/call",
                    "params": {
                        "_meta": modern_meta(),
                        "name": "filesystem.write_text",
                        "arguments": {"path": str(target), "text": "hello…", "purpose": "test write"},
                    },
                }
            )
            self.assertFalse(write["result"]["isError"])
            self.assertEqual("hello…", target.read_text(encoding="utf-8"))

    def test_policy_denial_is_tool_error_not_protocol_crash(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            engine, _ = make_engine(temp_dir)
            server = McpOpsServer(engine)
            response = server.dispatch(
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "tools/call",
                    "params": {
                        "_meta": modern_meta(),
                        "name": "filesystem.read_text",
                        "arguments": {"path": str(Path(temp_dir) / "outside.txt")},
                    },
                }
            )
            self.assertTrue(response["result"]["isError"])
            self.assertIn("PATH_DENIED", response["result"]["content"][0]["text"])

    def test_entrypoint_fails_closed_when_policy_is_missing(self) -> None:
        script = Path(__file__).resolve().parents[1] / "mcp_server.py"
        environment = dict(os.environ)
        environment.pop("HOOSHIX_OPS_POLICY", None)
        completed = subprocess.run(
            [sys.executable, str(script)],
            input="",
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
            env=environment,
        )
        self.assertEqual(2, completed.returncode)
        self.assertEqual("", completed.stdout)
        self.assertIn("Ops policy path is required", completed.stderr)

    def test_entrypoint_is_independent_of_working_directory(self) -> None:
        script = Path(__file__).resolve().parents[1] / "mcp_server.py"
        with tempfile.TemporaryDirectory() as temp_dir, tempfile.TemporaryDirectory() as cwd:
            _, policy = make_engine(temp_dir)
            request = {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "tools/call",
                "params": {"_meta": modern_meta(), "name": "ops.status", "arguments": {}},
            }
            completed = subprocess.run(
                [sys.executable, str(script), "--policy", str(policy)],
                cwd=cwd,
                input=json.dumps(request) + "\n",
                capture_output=True,
                text=True,
                timeout=10,
                check=False,
            )
            self.assertEqual(0, completed.returncode, completed.stderr)
            response = json.loads(completed.stdout.strip())
            self.assertFalse(response["result"]["isError"])
            self.assertEqual("developer-host-ops", response["result"]["structuredContent"]["mode"])

    def test_response_writer_always_emits_utf8_bytes(self) -> None:
        raw_stdout = io.BytesIO()
        non_utf8_text_stdout = io.TextIOWrapper(raw_stdout, encoding="cp1252")
        response = {"jsonrpc": "2.0", "id": 1, "result": {"text": "عملیات…"}}
        with mock.patch.object(sys, "stdout", non_utf8_text_stdout):
            _write_response(response)
        payload = raw_stdout.getvalue()
        self.assertEqual(response, json.loads(payload.decode("utf-8")))
        self.assertIn("…".encode("utf-8"), payload)


if __name__ == "__main__":
    unittest.main()
