import contextlib
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import rehearse_openbao_kubernetes as rehearsal


class OpenBaoKubernetesTest(unittest.TestCase):
    def test_non_runner_and_root_are_denied_before_commands(self):
        for environment, uid in (({}, 1000), ({"GITHUB_ACTIONS": "true", "RUNNER_TEMP": "/tmp"}, 0)):
            with patch.dict(os.environ, environment, clear=True), patch.object(os, "getuid", return_value=uid), \
                    patch.object(rehearsal, "run") as command, self.assertRaises(rehearsal.RehearsalFailed):
                rehearsal.rehearse(Path("/tmp"), Path("/tmp/receipt.json"))
            command.assert_not_called()

    def test_paths_must_be_new_and_inside_runner_temp_without_symlinks(self):
        with tempfile.TemporaryDirectory() as temp, tempfile.TemporaryDirectory() as outside:
            root = Path(temp)
            tools = root / "tools"
            tools.mkdir()
            receipt = root / "receipt.json"
            with patch.dict(os.environ, {"GITHUB_ACTIONS": "true", "RUNNER_TEMP": temp}), \
                    patch.object(os, "getuid", return_value=1000):
                rehearsal.runner_paths(tools, receipt)
                with self.assertRaises(rehearsal.RehearsalFailed):
                    rehearsal.runner_paths(Path(outside), receipt)
                link = root / "link"
                link.symlink_to(tools)
                with self.assertRaises(rehearsal.RehearsalFailed):
                    rehearsal.runner_paths(link, receipt)
                receipt.touch()
                with self.assertRaises(rehearsal.RehearsalFailed):
                    rehearsal.runner_paths(tools, receipt)

    def test_command_is_native_bounded_and_keeps_secret_input_out_of_argv_and_logs(self):
        result = Mock(returncode=0, stdout=b"private-response")
        with patch.object(subprocess, "run", return_value=result) as native:
            self.assertEqual(b"private-response", rehearsal.run(["kubectl", "apply", "-f", "-"], data=b"private-input"))
            arguments, options = native.call_args
            self.assertNotIn(b"private-input", arguments[0])
            self.assertEqual(b"private-input", options["input"])
            self.assertEqual(subprocess.DEVNULL, options["stderr"])
            self.assertNotIn("shell", options)
            self.assertNotIn("KUBECONFIG", options["env"])
            with self.assertRaises(rehearsal.RehearsalFailed):
                rehearsal.run(["kubectl"], bound=1)
            result.returncode = 2
            self.assertEqual(b"private-response", rehearsal.run(["kubectl"], expected=2))
            with self.assertRaises(rehearsal.RehearsalFailed):
                rehearsal.run(["kubectl"])

    def test_failed_partial_cluster_creation_is_cleaned_without_reading_operator_state(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            tools = root / "tools"
            tools.mkdir()
            for tool in ("kind", "kubectl"):
                (tools / tool).write_bytes(b"synthetic-tool")
            pinned = rehearsal.pins()
            digest = hashlib.sha256(b"synthetic-tool").hexdigest()
            pinned["KIND_LINUX_AMD64_SHA256"] = digest
            pinned["KUBECTL_LINUX_AMD64_SHA256"] = digest
            commands = []

            def command(args, **_):
                commands.append(args)
                if args[1:] == ["version"]:
                    return b"kind v0.32.0"
                if args[1:] == ["version", "--client", "-o", "json"]:
                    return json.dumps({"clientVersion": {"gitVersion": "v1.35.6"}}).encode()
                if args[1:3] == ["create", "cluster"]:
                    raise rehearsal.RehearsalFailed("private diagnostic")
                return b""

            output = io.StringIO()
            with patch.dict(os.environ, {"GITHUB_ACTIONS": "true", "RUNNER_TEMP": temp}), \
                    patch.object(os, "getuid", return_value=1000), patch.object(rehearsal, "pins", return_value=pinned), \
                    patch.object(rehearsal, "run", side_effect=command), contextlib.redirect_stdout(output), \
                    self.assertRaises(rehearsal.RehearsalFailed):
                rehearsal.rehearse(tools, root / "receipt.json")
            create = next(args for args in commands if args[1:3] == ["create", "cluster"])
            name = create[create.index("--name") + 1]
            self.assertTrue(name.startswith("hooshix-bao-ci-"))
            self.assertIn([str(tools / "kind"), "delete", "cluster", "--name", name], commands)
            self.assertFalse((root / "receipt.json").exists())
            self.assertEqual([tools], list(root.iterdir()))
            self.assertNotIn("private diagnostic", output.getvalue())

    def test_fixed_step_labels_and_failure_receipt_do_not_reveal_errors(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            rehearsal.step("cleanup")
            with self.assertRaises(rehearsal.RehearsalFailed):
                rehearsal.step("private-token")
            with patch.object(sys, "argv", ["rehearse", "--ci", "--tools-dir", "/tmp", "--receipt", "/tmp/x"]), \
                    patch.object(rehearsal, "rehearse", side_effect=ValueError("private-token")):
                self.assertEqual(1, rehearsal.main())
        self.assertNotIn("private-token", output.getvalue())
        self.assertIn("OPENBAO_KUBERNETES=Failed", output.getvalue())

    def test_ci_job_blocks_baseline_and_retains_only_public_receipt(self):
        workflow = (rehearsal.ROOT / ".github/workflows/repository-baseline.yml").read_text()
        job = workflow.split("  openbao-kubernetes:", 1)[1].split("  compromised-password-security:", 1)[0]
        self.assertIn("sha256sum --check --strict", job)
        self.assertIn("timeout-minutes: 15", job)
        self.assertIn("--ci", job)
        self.assertIn("path: ${{ runner.temp }}/openbao-kubernetes-receipt.json", job)
        self.assertNotIn("secrets.", job)
        self.assertNotIn("id-token:", job)
        baseline = workflow.split("  baseline:", 1)[1]
        self.assertIn("- openbao-kubernetes", baseline)
        self.assertIn("${{ needs.openbao-kubernetes.result }}", baseline)
        self.assertIn('if [ "${OPENBAO_KUBERNETES_RESULT}" != \'success\' ]', baseline)


if __name__ == "__main__":
    unittest.main()
