import hashlib
import json
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_host_audit as verifier

ROOT = Path(__file__).resolve().parents[3]
HOST = ROOT / "infrastructure/production/host"


class HostAuditPolicyTest(unittest.TestCase):
    def test_policy_preserves_execution_metadata_without_command_argument_records(self):
        rules = (HOST / "audit.rules").read_text().splitlines()
        self.assertTrue(rules)
        self.assertTrue(all(line.startswith(("-w ", "-a ")) for line in rules))
        self.assertIn("-w /etc/ssh/sshd_config -p wa -k ssh_config", rules)
        self.assertIn("-w /etc/sudoers.d -p wa -k sudo_config", rules)
        self.assertIn("-w /etc/rancher/k3s -p wa -k k3s_config", rules)
        self.assertIn("-w /etc/mysql -p wa -k database_config", rules)
        self.assertTrue(any(" -S execve,execveat " in rule and "auid>=1000" in rule
                            for rule in rules))
        self.assertFalse(any("/var/lib/rancher/k3s/server " in rule for rule in rules))
        self.assertFalse(any(rule.startswith("-e 2") for rule in rules))
        self.assertEqual(len(rules), len(set(rules)))
        self.assertEqual(["-a never,exclude -F msgtype=EXECVE",
                          "-a never,exclude -F msgtype=PROCTITLE"],
                         [rule for rule in rules if "never" in rule])

    def test_mariadb_audit_excludes_sql_text_and_has_finite_local_retention(self):
        raw = (HOST / "mariadb-audit.cnf").read_text()
        settings = dict(line.split("=", 1) for line in raw.splitlines()
                        if "=" in line and not line.lstrip().startswith("#"))
        settings = {key.strip(): value.strip() for key, value in settings.items()}
        self.assertEqual("CONNECT", settings["server_audit_events"])
        self.assertEqual("ON", settings["server_audit_logging"])
        self.assertEqual("FILE", settings["server_audit_output_type"])
        self.assertEqual("/var/log/mariadb/audit.log", settings["server_audit_file_path"])
        self.assertEqual("10485760", settings["server_audit_file_rotate_size"])
        self.assertEqual("5", settings["server_audit_file_rotations"])
        self.assertTrue(all("QUERY" not in value for value in settings.values()))


POLICY = (HOST / "audit.rules").read_bytes()
DIGEST = hashlib.sha256(POLICY).hexdigest()
STATUS = ("enabled 1\npid 123\nlost 0\nbacklog 0\nbacklog_limit 8192\n"
          "loginuid_immutable 0 unlocked\n")


class HostAuditVerificationTest(unittest.TestCase):
    def run_verification(self, rules=POLICY.decode(), status=STATUS):
        with patch.object(verifier.os, "geteuid", return_value=0), \
                patch.object(verifier, "protected_rules", return_value=POLICY), \
                patch.object(verifier, "command", side_effect=["active\n", status, rules]):
            return verifier.verify(DIGEST)

    def test_kernel_aliases_compare_and_success_never_grants_readiness(self):
        raw = POLICY.decode().replace("-k ", "-F key=").replace("auid!=4294967295", "auid!=-1")
        raw = raw.replace("msgtype=EXECVE", "msgtype=1309").replace("msgtype=PROCTITLE", "msgtype=1327")
        result = self.run_verification(rules=raw)
        self.assertEqual(25, result["active_rule_count"])
        self.assertEqual("Passed", result["local_rule_verification"])
        for key in ("event_coverage", "audit_readiness", "jit_readiness", "production_readiness"):
            self.assertEqual("Not verified", result[key])
        self.assertNotIn("-w /etc/", json.dumps(result))

    def test_same_count_with_weakened_rule_or_missing_privacy_rule_is_rejected(self):
        for before, after in (("-p wa -k sudo_config", "-p r -k sudo_config"),
                              ("msgtype=EXECVE", "msgtype=USER_AUTH"),
                              ("-a always,exit", "-a never,exit")):
            with self.subTest(before=before), self.assertRaisesRegex(ValueError, "active_rules"):
                self.run_verification(rules=POLICY.decode().replace(before, after))

    def test_extra_suppression_missing_and_duplicate_rules_are_rejected(self):
        for raw in (POLICY.decode() + "-a never,task\n", "No rules\n",
                    POLICY.decode() + POLICY.decode().splitlines()[0] + "\n",
                    "\n".join(POLICY.decode().splitlines()[1:])):
            with self.subTest(raw=raw[:40]), self.assertRaisesRegex(ValueError, "active_rules"):
                self.run_verification(rules=raw)

    def test_filter_group_dump_order_allowed_but_rule_priority_changes_rejected(self):
        lines = POLICY.decode().splitlines()
        regrouped = "\n".join(lines[2:] + lines[:2])
        self.assertEqual("Passed", self.run_verification(rules=regrouped)["local_rule_verification"])
        lines[2], lines[3] = lines[3], lines[2]
        with self.assertRaisesRegex(ValueError, "active_rules"):
            self.run_verification(rules="\n".join(lines))

    def test_lost_disabled_missing_daemon_duplicate_and_backlog_pressure_fail_closed(self):
        for raw in (STATUS.replace("lost 0", "lost 1"), STATUS.replace("enabled 1", "enabled 0"),
                    STATUS.replace("pid 123", "pid 0"), STATUS + "lost 0\n",
                    STATUS.replace("backlog 0", "backlog 6144"), STATUS.replace("lost 0\n", "")):
            with self.subTest(raw=raw), self.assertRaisesRegex(ValueError, "kernel"):
                self.run_verification(status=raw)

    def test_reference_digest_mismatch_stops_before_command_io(self):
        with patch.object(verifier.os, "geteuid", return_value=0), \
                patch.object(verifier, "protected_rules", return_value=POLICY + b"\n"), \
                patch.object(verifier, "command") as command, \
                self.assertRaisesRegex(ValueError, "policy_digest"):
            verifier.verify(DIGEST)
        command.assert_not_called()

    def test_non_root_cannot_inspect_protected_state(self):
        with patch.object(verifier.os, "geteuid", return_value=1000), \
                patch.object(verifier, "protected_rules") as read, \
                self.assertRaisesRegex(ValueError, "permissions"):
            verifier.verify(DIGEST)
        read.assert_not_called()

    def test_failures_never_expose_command_output(self):
        for error in (subprocess.CalledProcessError(1, [], stderr=b"synthetic-private-value"),
                      subprocess.TimeoutExpired([], 5, output=b"synthetic-private-value"),
                      OSError("synthetic-private-value")):
            with patch.object(verifier.os, "geteuid", return_value=0), \
                    patch.object(verifier, "protected_rules", return_value=POLICY), \
                    patch.object(verifier, "command", side_effect=error), \
                    self.assertRaisesRegex(ValueError, "^HOST_AUDIT_CHECK_FAILED=daemon$"):
                verifier.verify(DIGEST)

    def test_file_bytes_and_ownership_validation(self):
        from types import SimpleNamespace
        directory = SimpleNamespace(st_mode=0o40750, st_uid=0)
        regular = dict(st_mode=0o100640, st_uid=0, st_gid=0, st_nlink=1, st_size=len(POLICY))
        for raw, overrides in ((POLICY, {}), (b"\xef\xbb\xbf" + POLICY, {}),
                               (POLICY.replace(b"\n", b"\r\n"), {}),
                               (POLICY, {"st_mode": 0o100666}), (POLICY, {"st_uid": 1000}),
                               (POLICY, {"st_nlink": 2}), (POLICY, {"st_mode": 0o10640}),
                               (POLICY, {"st_size": verifier.LIMIT + 1})):
            with patch.object(Path, "lstat", return_value=directory), \
                    patch.object(verifier.os, "open", return_value=3) as opened, \
                    patch.object(verifier.os, "fstat", return_value=SimpleNamespace(**(regular | overrides))), \
                    patch.object(verifier.os, "read", return_value=raw), \
                    patch.object(verifier.os, "close") as closed:
                if raw == POLICY and not overrides:
                    self.assertEqual(POLICY, verifier.protected_rules(verifier.RULE_FILE))
                else:
                    with self.assertRaises(ValueError):
                        verifier.protected_rules(verifier.RULE_FILE)
                self.assertTrue(opened.call_args.args[1] & verifier.os.O_NOFOLLOW)
                closed.assert_called_once_with(3)

    def test_writable_or_symlink_parent_is_rejected_before_open(self):
        from types import SimpleNamespace
        for mode in (0o40777, 0o120755):
            with patch.object(Path, "lstat", return_value=SimpleNamespace(st_mode=mode, st_uid=0)), \
                    patch.object(verifier.os, "open") as opened, self.assertRaises(ValueError):
                verifier.protected_rules(verifier.RULE_FILE)
            opened.assert_not_called()

    def test_command_is_bounded_read_only_and_output_limit_enforced(self):
        from types import SimpleNamespace
        for raw in (b"active\n", b"x" * (verifier.LIMIT + 1)):
            with patch.object(verifier.subprocess, "run", return_value=SimpleNamespace(stdout=raw)) as run:
                if len(raw) > verifier.LIMIT:
                    with self.assertRaises(ValueError):
                        verifier.command(["/usr/bin/systemctl", "is-active", "auditd.service"])
                else:
                    self.assertEqual("active\n", verifier.command(["/usr/bin/systemctl", "is-active", "auditd.service"]))
                self.assertEqual(5, run.call_args.kwargs["timeout"])
                self.assertEqual(subprocess.DEVNULL, run.call_args.kwargs["stdin"])
                self.assertEqual(verifier.ENV, run.call_args.kwargs["env"])
                self.assertNotIn("shell", run.call_args.kwargs)


if __name__ == "__main__":
    unittest.main()
