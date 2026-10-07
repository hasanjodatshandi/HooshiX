import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_platform_publication_run as target
import rehearse_platform_admission as staging


class PlatformPublicationRunTest(unittest.TestCase):
    def test_only_successful_main_exact_release_workflow_is_eligible(self):
        valid = {"conclusion": "success", "status": "completed", "head_branch": "main",
            "head_sha": "a" * 40, "event": "workflow_dispatch", "path": target.WORKFLOW,
            "repository": {"full_name": target.REPOSITORY}}
        target.check_run(valid)
        for field, value in (("conclusion", "failure"), ("status", "in_progress"),
                             ("head_branch", "feature"), ("head_sha", "main"), ("event", "pull_request"),
                             ("path", ".github/workflows/other.yml"), ("repository", {"full_name": "other/repo"})):
            bad = copy.deepcopy(valid)
            bad[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                target.check_run(bad)

    def test_cli_is_bounded_native_and_suppresses_untrusted_diagnostics(self):
        completed = Mock(returncode=0, stdout=b"{}")
        with patch.object(subprocess, "run", return_value=completed) as native:
            self.assertEqual(b"{}", target.run(["api", "public-route"]))
            self.assertEqual(["gh", "api", "public-route"], native.call_args.args[0])
            self.assertEqual(subprocess.DEVNULL, native.call_args.kwargs["stderr"])
            self.assertEqual(90, native.call_args.kwargs["timeout"])
            completed.returncode = 1
            with self.assertRaises(ValueError):
                target.run(["api", "public-route"])
            completed.returncode = 0
            completed.stdout = b"x" * (256 * 1024 + 1)
            with self.assertRaises(ValueError):
                target.run(["api", "public-route"])

    def test_invalid_run_or_component_is_rejected_before_network(self):
        for run_id, component in (("../123", "mesh"), ("0", "mesh"), ("1", "arbitrary"),
                                   ("1;anything", "mesh"), ("1" * 21, "openbao")):
            with patch.object(target, "run") as command, self.assertRaises(ValueError):
                target.download(run_id, component, Path("/tmp/unused"))
            command.assert_not_called()

    def test_runner_token_only_in_memory_dockerconfig_with_fixed_secret_name(self):
        import base64
        with patch.dict(staging.os.environ, {"GITHUB_TOKEN": "disposable-unit-token"}):
            secret = staging.registry_secret("kyverno")
        config = json.loads(base64.b64decode(secret["data"][".dockerconfigjson"]))
        self.assertEqual("hasanjodatshandi:disposable-unit-token",
                         base64.b64decode(config["auths"]["ghcr.io"]["auth"]).decode())
        self.assertEqual("hooshix-ghcr-read", secret["metadata"]["name"])
        for value in ("", "contains\nnewline", "x" * 4097):
            with patch.dict(staging.os.environ, {"GITHUB_TOKEN": value}), self.assertRaises(ValueError):
                staging.registry_secret("kyverno")


if __name__ == "__main__":
    unittest.main()
