import subprocess
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import install_audit_prerequisite as installer

PLAN = ("0 upgraded, 3 newly installed, 0 to remove and 22 not upgraded.\n"
        + "\n".join(f"Inst {name} ({installer.VERSION} Ubuntu:26.04/resolute-updates [amd64])"
                    for name in installer.PACKAGES))
CONFIG = ("max_log_file = 8\nnum_logs = 5\nmax_log_file_action = ROTATE\n"
          "disk_full_action = SUSPEND\ndisk_error_action = SUSPEND\n")


class AuditPrerequisiteTest(unittest.TestCase):
    def test_exact_install_only_plan_and_already_installed(self):
        installer.validate_plan(PLAN)
        installer.validate_plan("0 upgraded, 0 newly installed, 0 to remove and 22 not upgraded.")

    def test_rejects_upgrade_removal_unreviewed_dependency_and_duplicate(self):
        for raw in (PLAN.replace("0 upgraded", "1 upgraded"), PLAN + "\nRemv sudo",
                    PLAN.replace("Inst auditd", "Inst sudo"),
                    PLAN.replace(installer.VERSION, "1:4.2.0-1"),
                    PLAN + f"\nInst auditd ({installer.VERSION} Ubuntu:26.04/resolute-updates [amd64])"):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                installer.validate_plan(raw)

    def test_finite_log_configuration_required(self):
        installer.validate_log_bounds(CONFIG)
        for raw in (CONFIG.replace("ROTATE", "KEEP_LOGS"), CONFIG.replace("8", "800"),
                    CONFIG + "num_logs=5\n", CONFIG.replace("SUSPEND", "IGNORE")):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                installer.validate_log_bounds(raw)

    def test_download_budget(self):
        installer.validate_download_bound("'http://archive.ubuntu.com/a' auditd_4.deb 500000 SHA512:a")
        installer.validate_download_bound("No downloads needed")
        for raw in ("'http://archive.ubuntu.com/a' a.deb 20000000 SHA512:a",
                    "'http://archive.ubuntu.com/a' a.deb nonsense SHA512:a"):
            with self.assertRaises(ValueError):
                installer.validate_download_bound(raw)

    def test_no_install_or_privilege_in_default_mode(self):
        def fake_read(argv):
            if "--print-architecture" in argv:
                return "amd64\n"
            return PLAN if "--simulate" in argv else ""

        with patch.object(Path, "read_text", return_value='ID=ubuntu\nVERSION_ID="26.04"\n'), \
                patch.object(installer, "read_command", side_effect=fake_read), \
                patch.object(installer.shutil, "disk_usage", return_value=SimpleNamespace(free=2**30)), \
                patch.object(installer.subprocess, "run") as run:
            result = installer.install(False)
        run.assert_not_called()
        self.assertFalse(result["applied"])
        self.assertFalse(result["standing_admin_removed"])
        self.assertEqual("Not verified", result["jit_readiness"])

    def test_non_root_install_rejected_before_package_transaction(self):
        with patch.object(Path, "read_text", return_value='ID=ubuntu\nVERSION_ID="26.04"\n'), \
                patch.object(installer, "read_command", return_value="amd64\n"), \
                patch.object(installer.os, "geteuid", return_value=1000), \
                patch.object(installer.subprocess, "run") as run, self.assertRaises(ValueError):
            installer.install(True)
        run.assert_not_called()

    def test_pinned_transaction_no_retry_no_policy_mutation(self):
        def fake_read(argv):
            if "--print-architecture" in argv:
                return "amd64\n"
            if "--simulate" in argv:
                return PLAN
            if "--print-uris" in argv:
                return ""
            if argv[0] == "/usr/bin/dpkg-query":
                return "install ok installed " + installer.VERSION
            if "is-active" in argv:
                return "active\n"
            return "enabled 1\nlost 0\n"

        with patch.object(Path, "read_text", side_effect=['ID=ubuntu\nVERSION_ID="26.04"\n', CONFIG]), \
                patch.object(installer, "read_command", side_effect=fake_read), \
                patch.object(installer.os, "geteuid", return_value=0), \
                patch.object(installer.shutil, "disk_usage", return_value=SimpleNamespace(free=2**30)), \
                patch.object(installer.subprocess, "run") as run:
            result = installer.install(True)
        self.assertEqual(1, run.call_count)
        self.assertNotIn("timeout", run.call_args.kwargs)
        command = run.call_args.args[0]
        self.assertIn("--no-remove", command)
        self.assertIn("APT::Get::AllowUnauthenticated=false", command)
        self.assertTrue(result["applied"])
        self.assertEqual("Not verified", result["audit_readiness"])

    def test_package_failure_does_not_retry_or_report_success(self):
        with patch.object(Path, "read_text", return_value='ID=ubuntu\nVERSION_ID="26.04"\n'), \
                patch.object(installer, "read_command", side_effect=["amd64", PLAN, ""]), \
                patch.object(installer.os, "geteuid", return_value=0), \
                patch.object(installer.shutil, "disk_usage", return_value=SimpleNamespace(free=2**30)), \
                patch.object(installer.subprocess, "run", side_effect=subprocess.CalledProcessError(1, [])) as run, \
                self.assertRaises(subprocess.CalledProcessError):
            installer.install(True)
        self.assertEqual(1, run.call_count)

    def test_verify_only_accepts_real_multi_word_status_without_installation(self):
        def fake_read(argv):
            if argv[0] == "/usr/bin/dpkg-query":
                return "install ok installed " + installer.VERSION
            if "is-active" in argv:
                return "active\n"
            return "enabled 1\nlost 0\nloginuid_immutable 0 unlocked\n"

        with patch.object(Path, "read_text", return_value=CONFIG), \
                patch.object(installer.os, "geteuid", return_value=0), \
                patch.object(installer, "read_command", side_effect=fake_read), \
                patch.object(installer.subprocess, "run") as run:
            result = installer.verify_installed()
        run.assert_not_called()
        self.assertTrue(result["installed"])
        self.assertEqual("Passed", result["kernel"])
        self.assertEqual("Not verified", result["jit_readiness"])

    def test_verify_only_rejects_duplicate_missing_and_unhealthy_kernel_fields(self):
        for status in ("enabled 1\nenabled 0\nlost 0\n", "enabled 1\nlost 1\n",
                       "enabled 0\nlost 0\n", "enabled 1\n", "bad\n"):
            with patch.object(Path, "read_text", return_value=CONFIG), \
                    patch.object(installer.os, "geteuid", return_value=0), \
                    patch.object(installer, "read_command", side_effect=[
                        *("install ok installed " + installer.VERSION for _ in installer.PACKAGES),
                        "active\n", status]), \
                    self.assertRaisesRegex(ValueError, "^POST_INSTALL_CHECK_FAILED=kernel$"):
                installer.verify_installed()

    def test_verify_only_reports_safe_stage_not_raw_errors(self):
        with patch.object(installer.os, "geteuid", return_value=0), \
                patch.object(installer, "read_command", side_effect=OSError("private-fixture")), \
                self.assertRaisesRegex(ValueError, "^POST_INSTALL_CHECK_FAILED=packages$"):
            installer.verify_installed()


if __name__ == "__main__":
    unittest.main()
