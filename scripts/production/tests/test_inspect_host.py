import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import inspect_host


class HostInventoryTest(unittest.TestCase):
    def test_allow_list_excludes_credentials_bodies_and_annotations(self):
        raw = json.dumps({"items": [{
            "metadata": {"namespace": "kyverno", "name": "controller-1",
                         "annotations": {"credential": "private-fixture"}},
            "spec": {"containers": [{"env": [{"value": "private-fixture"}]}]},
            "status": {"containerStatuses": [{"ready": True, "restartCount": 2,
                                                "state": {"message": "private-fixture"}}]},
        }]})
        result = inspect_host.summarize_pods(raw)
        self.assertNotIn("private-fixture", json.dumps(result))
        self.assertEqual(2, result[0]["restart_count"])
        self.assertEqual(1, result[0]["ready_count"])

    def test_non_root_never_calls_cluster(self):
        with patch.object(inspect_host.os, "geteuid", return_value=1000), \
                patch.object(inspect_host, "run", return_value="active\n") as run:
            result = inspect_host.collect()
        self.assertTrue(all(call.args[0][0] == "/usr/bin/systemctl" for call in run.call_args_list))
        self.assertEqual("Not verified", result["production_readiness"])
        self.assertIsNone(result["pods"])

    def test_command_failure_does_not_claim_readiness(self):
        with patch.object(inspect_host.os, "geteuid", return_value=0), \
                patch.object(inspect_host, "run", return_value=None):
            result = inspect_host.collect()
        self.assertEqual("Not verified", result["api_ready"])
        self.assertEqual("Not verified", result["pod_inventory"])

    def test_malformed_cluster_response_is_sanitized(self):
        with patch.object(inspect_host.os, "geteuid", return_value=0), \
                patch.object(inspect_host, "run", return_value="private-fixture"):
            result = inspect_host.collect()
        self.assertNotIn("private-fixture", json.dumps(result))
        self.assertEqual("Failed: malformed API response", result["pod_inventory"])

    def test_timeout_discards_command_and_partial_output(self):
        error = inspect_host.subprocess.TimeoutExpired(["private-fixture"], 20,
                                                       output="private-fixture")
        with patch.object(inspect_host.subprocess, "run", side_effect=error):
            self.assertIsNone(inspect_host.run(["/usr/bin/systemctl", "is-active", "k3s"]))


if __name__ == "__main__":
    unittest.main()
