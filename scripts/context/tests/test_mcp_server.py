from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from mcp_server import (  # noqa: E402
    CLIENT_CAPABILITIES_META_KEY,
    CLIENT_INFO_META_KEY,
    LEGACY_VERSION,
    MODERN_VERSION,
    PROTOCOL_META_KEY,
    McpContextServer,
)
from test_context_engine import make_repo  # noqa: E402


def modern_meta() -> dict:
    return {
        PROTOCOL_META_KEY: MODERN_VERSION,
        CLIENT_INFO_META_KEY: {"name": "test", "version": "1.0"},
        CLIENT_CAPABILITIES_META_KEY: {},
    }


class McpContextServerTest(unittest.TestCase):
    def test_modern_discover_and_readonly_tool_list(self) -> None:
        temp, root, engine = make_repo()
        self.addCleanup(temp.cleanup)
        server = McpContextServer(engine)
        discover = server.dispatch(
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "server/discover",
                "params": {"_meta": modern_meta()},
            }
        )
        self.assertEqual(MODERN_VERSION, discover["result"]["supportedVersions"][0])
        self.assertEqual("complete", discover["result"]["resultType"])
        self.assertEqual("private", discover["result"]["cacheScope"])

        listed = server.dispatch(
            {
                "jsonrpc": "2.0",
                "id": 2,
                "method": "tools/list",
                "params": {"_meta": modern_meta()},
            }
        )
        names = [tool["name"] for tool in listed["result"]["tools"]]
        self.assertEqual(
            [
                "project.bootstrap",
                "project.context_for_task",
                "project.search",
                "project.latest_checkpoint",
                "project.changed_context",
            ],
            names,
        )
        self.assertTrue(
            all(tool["annotations"]["readOnlyHint"] for tool in listed["result"]["tools"])
        )
        self.assertFalse(any("write" in name or "create" in name for name in names))

    def test_server_entrypoint_is_independent_of_process_working_directory(self) -> None:
        script = Path(__file__).resolve().parents[1] / "mcp_server.py"
        requests = [
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "server/discover",
                "params": {"_meta": modern_meta()},
            },
            {
                "jsonrpc": "2.0",
                "id": 2,
                "method": "tools/call",
                "params": {
                    "_meta": modern_meta(),
                    "name": "project.bootstrap",
                    "arguments": {},
                },
            },
        ]
        with tempfile.TemporaryDirectory() as temp_dir:
            completed = subprocess.run(
                [sys.executable, str(script)],
                cwd=temp_dir,
                input="".join(json.dumps(request) + "\n" for request in requests),
                capture_output=True,
                text=True,
                timeout=10,
                check=False,
            )

        self.assertEqual(0, completed.returncode, completed.stderr)
        responses = [json.loads(line) for line in completed.stdout.splitlines() if line.strip()]
        self.assertEqual(2, len(responses))
        self.assertEqual(MODERN_VERSION, responses[0]["result"]["supportedVersions"][0])
        self.assertEqual(
            "hooshix-context-engine",
            responses[0]["result"]["_meta"]["io.modelcontextprotocol/serverInfo"]["name"],
        )
        self.assertFalse(responses[1]["result"]["isError"])
        bootstrap = json.loads(responses[1]["result"]["content"][0]["text"])
        self.assertEqual(str(script.parents[2].resolve()), bootstrap["repository_root"])
        self.assertTrue(bootstrap["verification"]["valid"])

    def test_modern_tool_call_works_without_discover_when_meta_selects_era(self) -> None:
        temp, root, engine = make_repo()
        self.addCleanup(temp.cleanup)
        server = McpContextServer(engine)
        response = server.dispatch(
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "tools/call",
                "params": {
                    "_meta": modern_meta(),
                    "name": "project.context_for_task",
                    "arguments": {"task": "identity password validation"},
                },
            }
        )
        self.assertFalse(response["result"]["isError"])
        self.assertEqual("complete", response["result"]["resultType"])
        self.assertIn(
            '"review_mode": "targeted"', response["result"]["content"][0]["text"]
        )

    def test_modern_request_rejects_wrong_version(self) -> None:
        temp, root, engine = make_repo()
        self.addCleanup(temp.cleanup)
        server = McpContextServer(engine)
        bad_meta = modern_meta()
        bad_meta[PROTOCOL_META_KEY] = "2099-01-01"
        response = server.dispatch(
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "server/discover",
                "params": {"_meta": bad_meta},
            }
        )
        self.assertEqual(-32022, response["error"]["code"])

    def test_legacy_initialize_and_tool_call(self) -> None:
        temp, root, engine = make_repo()
        self.addCleanup(temp.cleanup)
        server = McpContextServer(engine)
        initialized = server.dispatch(
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": LEGACY_VERSION,
                    "capabilities": {},
                    "clientInfo": {"name": "legacy-test", "version": "1.0"},
                },
            }
        )
        self.assertEqual(LEGACY_VERSION, initialized["result"]["protocolVersion"])
        response = server.dispatch(
            {
                "jsonrpc": "2.0",
                "id": 2,
                "method": "tools/call",
                "params": {
                    "name": "project.search",
                    "arguments": {"query": "Identity", "limit": 2},
                },
            }
        )
        self.assertFalse(response["result"]["isError"])
        self.assertNotIn("resultType", response["result"])

    def test_unknown_tool_is_invalid_params(self) -> None:
        temp, root, engine = make_repo()
        self.addCleanup(temp.cleanup)
        server = McpContextServer(engine)
        response = server.dispatch(
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "tools/call",
                "params": {
                    "_meta": modern_meta(),
                    "name": "project.write_file",
                    "arguments": {},
                },
            }
        )
        self.assertEqual(-32602, response["error"]["code"])


if __name__ == "__main__":
    unittest.main()
