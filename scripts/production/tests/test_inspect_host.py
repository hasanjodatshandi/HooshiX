import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import inspect_host


class HostInventoryTest(unittest.TestCase):
    def test_forwarding_flags_include_unix_sockets_and_override(self):
        self.assertEqual(
            {"disableforwarding": "yes", "allowstreamlocalforwarding": "no",
             "allowtcpforwarding": "local"},
            inspect_host.summarize_sshd("disableforwarding yes\nallowstreamlocalforwarding no\nallowtcpforwarding local\n"),
        )

    def test_management_config_allow_list_rejects_secret_values(self):
        result = inspect_host.summarize_sshd("permitrootlogin no\npasswordauthentication no\nforcecommand private-fixture\nbanner private-fixture\nallowtcpforwarding private-fixture\n")
        self.assertEqual({"permitrootlogin": "no", "passwordauthentication": "no"}, result)
        self.assertNotIn("private-fixture", json.dumps(result))

    def test_peer_inventory_contains_fingerprint_and_routes_only(self):
        import base64
        key = base64.b64encode(bytes(range(32))).decode()
        result = inspect_host.summarize_peers(key + "\t10.77.47.2/32\n")
        self.assertEqual(["10.77.47.2/32"], result[0]["allowed_ips"])
        self.assertEqual(64, len(result[0]["public_key_sha256"]))
        self.assertNotIn(key, json.dumps(result))

    def test_malformed_peer_inventory_is_sanitized(self):
        with patch.object(inspect_host.os, "geteuid", return_value=0), \
                patch.object(inspect_host, "run", return_value="private-fixture"):
            result = inspect_host.collect()
        self.assertIsNone(result["management"]["wireguard_peers"])
        self.assertNotIn("private-fixture", json.dumps(result))
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
        self.assertEqual("Not verified", result["management"]["sshd_effective_sample"]["status"])

    def test_management_sample_applies_match_without_exposing_raw_config(self):
        def fake_run(args):
            if args == ["/usr/sbin/sshd", "-T", "-C", inspect_host.SSH_MANAGEMENT_SAMPLE]:
                return "\n".join(f"{flag} {value}"
                                 for flag, value in inspect_host.SSH_SAMPLE_EXPECTED.items()) + "\nbanner private-fixture\n"
            return None

        with patch.object(inspect_host.os, "geteuid", return_value=0), \
                patch.object(inspect_host, "run", side_effect=fake_run) as run:
            result = inspect_host.collect()
        sample = result["management"]["sshd_effective_sample"]
        self.assertEqual(2, result["schema_version"])
        self.assertEqual("Passed", sample["status"])
        self.assertEqual("Passed", sample["probe_status"])
        self.assertEqual("no", sample["settings"]["allowstreamlocalforwarding"])
        self.assertEqual("yes", sample["settings"]["disableforwarding"])
        self.assertNotIn("private-fixture", json.dumps(result))
        run.assert_any_call(["/usr/sbin/sshd", "-T", "-C", inspect_host.SSH_MANAGEMENT_SAMPLE])

    def test_successful_probe_does_not_pass_unsafe_effective_policy(self):
        observed = dict(inspect_host.SSH_SAMPLE_EXPECTED)
        observed.update({"disableforwarding": "no", "allowtcpforwarding": "yes",
                         "allowagentforwarding": "yes", "allowstreamlocalforwarding": "yes",
                         "x11forwarding": "yes"})
        def fake_run(args):
            if args == ["/usr/sbin/sshd", "-T", "-C", inspect_host.SSH_MANAGEMENT_SAMPLE]:
                return "\n".join(f"{flag} {value}" for flag, value in observed.items())
            return None

        with patch.object(inspect_host.os, "geteuid", return_value=0), \
                patch.object(inspect_host, "run", side_effect=fake_run):
            sample = inspect_host.collect()["management"]["sshd_effective_sample"]
        self.assertEqual("Passed", sample["probe_status"])
        self.assertEqual("Failed", sample["status"])

    def test_unknown_or_incomplete_ssh_sample_is_not_verified(self):
        self.assertEqual("Not verified", inspect_host.assess_ssh_sample({"pubkeyauthentication": "yes"}))
        self.assertEqual("Failed", inspect_host.assess_ssh_sample({"allowtcpforwarding": "local"}))

    def test_empty_successful_sshd_output_does_not_claim_probe_passed(self):
        with patch.object(inspect_host.os, "geteuid", return_value=0), \
                patch.object(inspect_host, "run", return_value=""):
            result = inspect_host.collect()
        self.assertEqual("Not verified", result["management"]["sshd_effective_sample"]["status"])
        self.assertEqual("Not verified", result["management"]["sshd_effective_sample"]["probe_status"])

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
