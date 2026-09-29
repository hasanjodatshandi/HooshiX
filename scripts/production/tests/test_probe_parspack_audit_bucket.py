import pathlib
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import probe_parspack_audit_bucket as subject


class ParsPackAuditProbeTest(unittest.TestCase):
    def test_endpoint_is_https_and_fixed_to_parspack_host(self):
        self.assertEqual(
            subject.validate_endpoint("https://c426797.parspack.net/"),
            "https://c426797.parspack.net",
        )
        for value in (
            "http://c426797.parspack.net", "https://evil.example",
            "https://c426797.parspack.net.evil.example",
            "https://key:secret@c426797.parspack.net",
            "https://c426797.parspack.net/other", "https://c426797.parspack.net:8443",
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                subject.validate_endpoint(value)

    @patch("probe_parspack_audit_bucket.subprocess.run")
    def test_signed_probe_keeps_keys_out_of_argv_and_classifies_lock(self, run):
        run.return_value.returncode = 0
        run.return_value.stdout = (
            b"<ObjectLockConfiguration><ObjectLockEnabled>Enabled</ObjectLockEnabled>"
            b"<Rule><DefaultRetention><Mode>COMPLIANCE</Mode><Days>30</Days>"
            b"</DefaultRetention></Rule></ObjectLockConfiguration>\nHTTP_STATUS:200"
        )
        result = subject.probe("https://c426797.parspack.net", "access", "secret", "object-lock")
        self.assertIn("COMPLIANCE / 30 days", result)
        args, kwargs = run.call_args
        self.assertEqual(args[0][1:4], ["-q", "--config", "-"])
        self.assertNotIn("access", " ".join(args[0]))
        self.assertNotIn("secret", " ".join(args[0]))
        self.assertIn(b"access:secret", kwargs["input"])

    @patch("probe_parspack_audit_bucket.subprocess.run")
    def test_missing_object_lock_is_not_reported_as_success(self, run):
        run.return_value.returncode = 0
        run.return_value.stdout = (
            b"<Error><Code>ObjectLockConfigurationNotFoundError</Code></Error>"
            b"\nHTTP_STATUS:404"
        )
        self.assertEqual(
            subject.probe("https://c426797.parspack.net", "access", "secret", "object-lock"),
            "HTTP 404; ObjectLockConfigurationNotFoundError",
        )


if __name__ == "__main__":
    unittest.main()
