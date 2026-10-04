import json
import contextlib
import io
import os
import subprocess
import ssl
import sys
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import rehearse_openbao_recovery as recovery


class OpenBaoRecoveryTest(unittest.TestCase):
    def test_only_fixed_public_step_labels_are_logged(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            recovery.step("image-pull")
            with self.assertRaises(recovery.RehearsalFailed):
                recovery.step("private-fixture-token")
        self.assertEqual("OPENBAO_STEP=image-pull\n", output.getvalue())

    def test_failure_receipt_does_not_print_exception_or_credentials(self):
        output = io.StringIO()
        with patch.object(sys, "argv", ["rehearse", "--ci"]), contextlib.redirect_stdout(output), \
                patch.object(recovery, "rehearse", side_effect=ValueError("private-fixture-token")):
            self.assertEqual(1, recovery.main())
        self.assertIn("OPENBAO_RECOVERY=Failed", output.getvalue())
        self.assertNotIn("private-fixture-token", output.getvalue())

    def test_tls_mount_exposes_no_other_fixture_data_and_failure_cleans_up(self):
        commands = []

        def run(args, **kwargs):
            commands.append(args)
            if args[1:2] == ["req"]:
                Path(args[args.index("-keyout") + 1]).touch()
            if args[1:2] == ["port"]:
                return b"127.0.0.1:18200\n"
            return b"OpenBao v2.6.4"

        client = Mock()
        client.base = "https://127.0.0.1:18200/v1/"
        client.wait_health.return_value = {"version": "2.6.4"}
        with patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}), patch.object(os, "getuid", return_value=1000), \
                patch.object(recovery, "command", side_effect=run), patch.object(recovery, "Client", return_value=client), \
                patch.object(recovery.urllib.request, "build_opener") as opener, \
                patch.object(recovery, "initialize", side_effect=recovery.RehearsalFailed("synthetic stop")), \
                contextlib.redirect_stdout(io.StringIO()):
            opener.return_value.open.side_effect = urllib.error.URLError(ssl.SSLCertVerificationError("untrusted"))
            with self.assertRaises(recovery.RehearsalFailed):
                recovery.rehearse()
        run_args = next(args for args in commands if "--detach" in args)
        mounts = [run_args[index + 1] for index, value in enumerate(run_args) if value == "--mount"]
        tls_mount = next(value for value in mounts if "dst=/openbao/tls," in value)
        tls_path = Path(tls_mount.split(",src=", 1)[1].split(",dst=", 1)[0])
        self.assertEqual("tls", tls_path.name)
        self.assertTrue(tls_mount.endswith(",readonly"))
        self.assertFalse(tls_path.parent.exists())
        name = run_args[run_args.index("--name") + 1]
        self.assertIn("--network", run_args)
        ip_index = run_args.index("--ip")
        self.assertEqual("192.0.2.2", run_args[ip_index + 1])
        self.assertIn(["/usr/bin/docker", "rm", "--force", "--volumes", name], commands)
        network = run_args[run_args.index("--network") + 1]
        self.assertIn(["/usr/bin/docker", "network", "rm", network], commands)

    def test_config_uses_raft_native_tls_and_protected_nonraw_audit(self):
        config = json.loads((recovery.SECRETS / "openbao-server.json").read_text())
        self.assertEqual({"raft": {"path": "/openbao/data", "node_id": "openbao-0"}}, config["storage"])
        listener = config["listener"][0]["tcp"]
        self.assertNotIn("tls_disable", listener)
        self.assertEqual("/openbao/tls/tls.key", listener["tls_key_file"])
        self.assertEqual("tls12", listener["tls_min_version"])
        self.assertEqual(1048576, listener["max_request_size"])
        self.assertEqual("10s", listener["max_request_duration"])
        self.assertEqual({"file_path": "/openbao/data/audit.jsonl", "mode": "0600", "log_raw": "false"},
                         config["audit"][0]["file"]["protected"]["options"])
        self.assertFalse(config["ui"])
        self.assertNotIn("initialize", config)
        self.assertNotIn("seal", config)  # Manual Shamir, never auto-unseal keys in configuration.

    def test_image_exact_pin_and_recovery_are_required_by_baseline(self):
        pin = json.loads((recovery.SECRETS / "openbao-image.json").read_text())
        self.assertEqual("2.6.4", pin["version"])
        self.assertRegex(pin["image"], r"^ghcr.io/openbao/openbao@sha256:[a-f0-9]{64}$")
        self.assertEqual("blocked-until-supply-chain-staging-and-recovery-evidence", pin["production_promotion"])
        workflow = (recovery.ROOT / ".github/workflows/repository-baseline.yml").read_text()
        baseline = workflow.split("  baseline:", 1)[1]
        self.assertIn("- openbao-recovery", baseline)
        self.assertIn("${{ needs.openbao-recovery.result }}", baseline)
        self.assertIn('if [ "${OPENBAO_RESULT}" != \'success\' ]', baseline)
        self.assertIn("python3 scripts/production/rehearse_openbao_recovery.py --ci", workflow)

    def test_refuses_non_ci_and_root_before_docker_or_secret_generation(self):
        for environment, uid in [({}, 1000), ({"GITHUB_ACTIONS": "true"}, 0)]:
            with patch.dict(os.environ, environment, clear=True), \
                    patch.object(os, "getuid", return_value=uid), patch.object(recovery, "command") as run:
                with self.assertRaises(recovery.RehearsalFailed):
                    recovery.rehearse()
                run.assert_not_called()

    def test_old_patch_and_prerelease_are_rejected_before_fixture_initialization(self):
        for version in (b"OpenBao v2.6.1", b"OpenBao v2.6.4-rc1"):
            with patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}), \
                    patch.object(os, "getuid", return_value=1000), \
                    patch.object(recovery, "command", return_value=version), \
                    patch.object(recovery, "initialize") as initialize:
                with self.assertRaises(recovery.RehearsalFailed):
                    recovery.rehearse()
                initialize.assert_not_called()

    def test_no_redirect_even_for_loopback(self):
        with self.assertRaises(recovery.RehearsalFailed):
            recovery.NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.invalid")

    def test_helper_captures_output_and_has_finite_timeout_and_sanitized_environment(self):
        with patch.object(subprocess, "run", return_value=subprocess.CompletedProcess([], 0, b"fixture")) as run:
            self.assertEqual(b"fixture", recovery.command(["/usr/bin/openssl", "version"]))
        args = run.call_args.kwargs
        self.assertEqual(30, args["timeout"])
        self.assertEqual(subprocess.DEVNULL, args["stderr"])
        self.assertEqual({"PATH", "HOME", "LC_ALL"}, set(args["env"]))
        self.assertNotIn("TOKEN", json.dumps(args["env"]))

    def test_helper_failure_and_output_bounds_never_repeat_or_publish_diagnostics(self):
        for result in [subprocess.CompletedProcess([], 1, b"private-fixture"),
                       subprocess.CompletedProcess([], 0, b"x" * 8193)]:
            with patch.object(subprocess, "run", return_value=result) as run:
                with self.assertRaises(recovery.RehearsalFailed) as error:
                    recovery.command(["/usr/bin/docker", "version"])
                self.assertNotIn("private-fixture", str(error.exception))
                self.assertEqual(1, run.call_count)

    def test_insufficient_share_does_not_unseal(self):
        from unittest.mock import Mock
        client = Mock()
        client.call.side_effect = [{"keys_base64": ["one", "two", "three"], "root_token": "fixture"},
                                   {"sealed": False, "progress": 0, "t": 2, "n": 3}]
        with self.assertRaises(recovery.RehearsalFailed):
            recovery.initialize(client)
        self.assertEqual(2, client.call.call_count)
        client.call.assert_any_call("sys/init", "PUT", {"secret_shares": 3, "secret_threshold": 2})


if __name__ == "__main__":
    unittest.main()
