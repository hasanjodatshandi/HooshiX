import contextlib
import io
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import probe_parspack_audit_bucket as subject


class ParsPackAuditProbeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        os.chmod(self.directory.name, 0o700)
        self.private = pathlib.Path(self.directory.name) / "credentials.json"
        self.data = {"access_key": "fake-access", "secret_key": "fake-secret"}
        self.write_credentials(self.data)

    def write_credentials(self, data):
        self.private.write_text(json.dumps(data), encoding="utf-8")
        self.private.chmod(0o600)

    def test_private_file_reads_keys_and_optional_endpoint_aliases(self):
        for field in (None, "endpoint", "endpoint_url", "end_point_url"):
            data = dict(self.data)
            if field:
                data[field] = "c892683.parspack.net/"
            self.write_credentials(data)
            self.assertEqual(
                subject.load_private_credentials(str(self.private), "https://c892683.parspack.net"),
                ("fake-access", "fake-secret"),
            )

    def test_invalid_schema_or_keys_are_rejected_without_values(self):
        variants = [
            [], {}, {"access_key": "fake-access"},
            {**self.data, "secret_key": ""}, {**self.data, "secret_key": 17},
            {**self.data, "secret_key": "fake-secret\nurl=evil"},
            {**self.data, "access_key": "fake:access"},
            {**self.data, "endpoint": "https://evil.example"},
            {**self.data, "endpoint": "https://c426797.parspack.net"},
            {**self.data, "endpoint": "https://user:fake-secret@c892683.parspack.net"},
            {**self.data, "end_point_url": "c892683.parspack.net", "endpoint": "c892683.parspack.net"},
            {**self.data, "extra": "fake-secret"},
        ]
        for data in variants:
            with self.subTest(data=data):
                self.write_credentials(data)
                with self.assertRaises(ValueError) as caught:
                    subject.load_private_credentials(str(self.private), "https://c892683.parspack.net")
                self.assertNotIn("fake-secret", str(caught.exception))
                self.assertNotIn("fake-access", str(caught.exception))

    def test_wrong_file_or_parent_modes_are_rejected(self):
        self.private.chmod(0o644)
        with self.assertRaises(ValueError):
            subject.load_private_credentials(str(self.private), "https://c892683.parspack.net")
        self.private.chmod(0o600)
        os.chmod(self.directory.name, 0o755)
        with self.assertRaises(ValueError):
            subject.load_private_credentials(str(self.private), "https://c892683.parspack.net")

    def test_symlink_hardlink_and_fifo_are_rejected(self):
        link = self.private.with_name("link.json")
        link.symlink_to(self.private)
        with self.assertRaises(OSError):
            subject.load_private_credentials(str(link), "https://c892683.parspack.net")
        link.unlink()
        os.link(self.private, link)
        with self.assertRaises(ValueError):
            subject.load_private_credentials(str(link), "https://c892683.parspack.net")
        link.unlink()
        os.mkfifo(link, 0o600)
        with self.assertRaises(ValueError):
            subject.load_private_credentials(str(link), "https://c892683.parspack.net")

    def test_oversized_duplicate_and_malformed_json_are_rejected(self):
        for raw in (
            'x' * 8193, '{"access_key":"a","access_key":"b","secret_key":"fake-secret"}',
            '{"secret_key":"fake-secret" invalid}',
            '[' * 1100 + '"fake-secret"' + ']' * 1100,
        ):
            self.private.write_text(raw, encoding="utf-8")
            with self.assertRaises(ValueError) as caught:
                subject.load_private_credentials(str(self.private), "https://c892683.parspack.net")
            self.assertNotIn("fake-secret", str(caught.exception))

    @patch("probe_parspack_audit_bucket.os.fstat")
    def test_file_owned_by_another_user_is_rejected(self, fstat):
        metadata = self.private.stat()
        fstat.return_value.st_uid = os.getuid() + 1
        fstat.return_value.st_mode = metadata.st_mode
        with self.assertRaises(ValueError):
            subject.load_private_credentials(str(self.private), "https://c892683.parspack.net")

    @patch("probe_parspack_audit_bucket.subprocess.run")
    def test_transport_failures_do_not_leak_curl_error_details(self, run):
        for failure in (
            subprocess.TimeoutExpired("fake-secret", 20), OSError("fake-secret"),
        ):
            run.side_effect = failure
            self.assertEqual(
                subject.probe("https://c892683.parspack.net", "c892683", "a", "b", "object-lock"),
                "transport error",
            )

    @patch("probe_parspack_audit_bucket.subprocess.run")
    def test_untrusted_xml_values_are_not_echoed(self, run):
        run.return_value.returncode = 0
        for field, value in (("Mode", "fake-secret"), ("Days", "fake-secret")):
            xml = (
                '<ObjectLockConfiguration><ObjectLockEnabled>Enabled</ObjectLockEnabled>'
                '<Rule><DefaultRetention><Mode>COMPLIANCE</Mode><Days>40</Days>'
                '</DefaultRetention></Rule></ObjectLockConfiguration>'
            )
            xml = xml.replace(f'<{field}>{"COMPLIANCE" if field == "Mode" else "40"}</{field}>',
                              f'<{field}>{value}</{field}>')
            run.return_value.stdout = xml.encode() + b"\nHTTP_STATUS:200"
            self.assertNotIn("fake-secret", subject.probe(
                "https://c892683.parspack.net", "c892683", "a", "b", "object-lock",
            ))

    @patch("probe_parspack_audit_bucket.subprocess.run")
    def test_xml_entities_oversize_and_unknown_error_codes_are_rejected(self, run):
        run.return_value.returncode = 0
        for body, status, expected in (
            (b'<!DOCTYPE foo [<!ENTITY x "secret">]><foo>&x;</foo>', b'200', 'unsupported XML'),
            (b'x' * 65537, b'200', 'unsupported XML'),
            (b'<Error><Code>fake-secret</Code></Error>', b'403', 'unknown'),
            (b'<VersioningConfiguration><Status>Enabled</Status><Status>Suspended</Status>'
             b'</VersioningConfiguration>', b'200', 'not configured'),
        ):
            run.return_value.stdout = body + b'\nHTTP_STATUS:' + status
            result = subject.probe('https://c892683.parspack.net', 'c892683', 'a', 'b', 'versioning')
            self.assertIn(expected, result)
            self.assertNotIn('secret', result)

    @patch("probe_parspack_audit_bucket.probe")
    def test_strict_configuration_check_fails_closed_without_claiming_audit_readiness(self, probe):
        expected = "HTTP 200; Object Lock enabled; default retention COMPLIANCE / 40 days"
        for lock, versioning, code in (
            (expected, "HTTP 200; Versioning Enabled", 0),
            ("HTTP 403; AccessDenied", "HTTP 200; Versioning Enabled", 1),
            (expected, "HTTP 200; Versioning not configured", 1),
            (expected.replace("COMPLIANCE", "GOVERNANCE"), "HTTP 200; Versioning Enabled", 1),
            (expected.replace("40 days", "30 days"), "HTTP 200; Versioning Enabled", 1),
            ("transport error", "transport error", 1),
        ):
            probe.side_effect = [lock, versioning]
            argv = ["probe", "--endpoint", "https://c892683.parspack.net", "--bucket", "c892683",
                    "--credentials-file", str(self.private), "--require-compliance-days", "40"]
            out = io.StringIO()
            with patch.object(sys, "argv", argv), contextlib.redirect_stdout(out):
                self.assertEqual(subject.main(), code)
            self.assertIn("audit readiness remain unverified", out.getvalue())
            self.assertNotIn("fake-secret", out.getvalue())

    @patch("probe_parspack_audit_bucket.probe")
    def test_private_file_errors_do_not_send_requests_or_echo_keys(self, probe):
        self.private.write_text('{"secret_key":"fake-secret" invalid}', encoding="utf-8")
        argv = ["probe", "--endpoint", "https://c892683.parspack.net", "--bucket", "c892683",
                "--credentials-file", str(self.private)]
        err = io.StringIO()
        with patch.object(sys, "argv", argv), contextlib.redirect_stderr(err):
            self.assertEqual(subject.main(), 2)
        probe.assert_not_called()
        self.assertNotIn("fake-secret", err.getvalue())

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

    def test_bucket_rejects_path_or_different_case(self):
        self.assertEqual(subject.validate_bucket("c426797"), "c426797")
        for value in ("../other", "C426797", "c426797/other", "ab"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                subject.validate_bucket(value)

    @patch("probe_parspack_audit_bucket.subprocess.run")
    def test_signed_probe_keeps_keys_out_of_argv_and_classifies_lock(self, run):
        run.return_value.returncode = 0
        run.return_value.stdout = (
            b"<ObjectLockConfiguration><ObjectLockEnabled>Enabled</ObjectLockEnabled>"
            b"<Rule><DefaultRetention><Mode>COMPLIANCE</Mode><Days>30</Days>"
            b"</DefaultRetention></Rule></ObjectLockConfiguration>\nHTTP_STATUS:200"
        )
        result = subject.probe(
            "https://c426797.parspack.net", "c426797", "access", "secret", "object-lock"
        )
        self.assertIn("COMPLIANCE / 30 days", result)
        args, kwargs = run.call_args
        self.assertEqual(args[0][1:4], ["-q", "--config", "-"])
        self.assertNotIn("access", " ".join(args[0]))
        self.assertNotIn("secret", " ".join(args[0]))
        self.assertIn(b"access:secret", kwargs["input"])
        self.assertEqual(args[0][-1], "https://c426797.parspack.net/c426797?object-lock")

    @patch("probe_parspack_audit_bucket.subprocess.run")
    def test_missing_object_lock_is_not_reported_as_success(self, run):
        run.return_value.returncode = 0
        run.return_value.stdout = (
            b"<Error><Code>ObjectLockConfigurationNotFoundError</Code></Error>"
            b"\nHTTP_STATUS:404"
        )
        self.assertEqual(
            subject.probe("https://c426797.parspack.net", "c426797", "access", "secret", "object-lock"),
            "HTTP 404; ObjectLockConfigurationNotFoundError",
        )

    @patch("probe_parspack_audit_bucket.subprocess.run")
    def test_html_landing_page_does_not_count_as_bucket_api(self, run):
        run.return_value.returncode = 0
        run.return_value.stdout = b"<html>landing page</html>\nHTTP_STATUS:200"
        self.assertEqual(
            subject.probe("https://c426797.parspack.net", "c426797", "access", "secret", "object-lock"),
            "HTTP 200; unexpected XML response",
        )


if __name__ == "__main__":
    unittest.main()
