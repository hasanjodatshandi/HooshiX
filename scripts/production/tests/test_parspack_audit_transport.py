import base64
import hashlib
import os
import subprocess
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import parspack_audit_transport as transport

RECORD_ID = "74f8c0cd-1f8b-4a0f-9561-8f047e7206fb"


def deliver(payload, access="fixture", secret="fixture", record_id=RECORD_ID):
    return transport.deliver(payload, access, secret, record_id=record_id)


def response(version=b"fixture+/=", body=b"", status=b"200 OK", extra=b""):
    return (b"HTTP/1.1 " + status + b"\r\nx-amz-version-id: " + version
            + b"\r\n" + extra + b"\r\n" + body)


class AuditTransportTest(unittest.TestCase):
    def test_exact_version_readback_and_receipt(self):
        payload = b'{"schema_version":1,"event":"synthetic"}'
        with patch.object(transport, "_transfer", side_effect=[response(), response(body=payload)]) as call:
            receipt = deliver(payload, "synthetic-access", "synthetic-secret")
        self.assertEqual({"sha256": hashlib.sha256(payload).hexdigest(),
                          "version_id": "fixture+/="}, receipt)
        put, get = [args.args[0].decode() for args in call.call_args_list]
        self.assertIn('request = "PUT"', put)
        self.assertIn('request = "GET"', get)
        self.assertIn("versionId=fixture%2B%2F%3D", get)
        self.assertEqual(put.split('url = "')[1].split('"')[0],
                         get.split('url = "')[1].split('?')[0])
        self.assertIn('data-binary = "@/proc/self/fd/', put)
        self.assertIn(f"/jit-v1/{RECORD_ID}.json", put)
        self.assertIn('header = "If-None-Match: *"', put)
        self.assertIn('header = "x-amz-sdk-checksum-algorithm: SHA256"', put)
        self.assertIn(base64.b64encode(hashlib.sha256(payload).digest()).decode(), put)
        self.assertEqual(call.call_args_list[0].args[2], call.call_args_list[1].args[2])
        with self.assertRaises(OSError):
            os.fstat(call.call_args_list[0].args[1])

    def test_missing_null_duplicate_and_invalid_version_deny(self):
        candidates = [b"HTTP/1.1 200 OK\r\n\r\n", response(b"null"), response(b""),
                      response(b"x" * 257), response(b"a?b"),
                      response(extra=b"X-Amz-Version-Id: other\r\n")]
        for raw in candidates:
            with self.subTest(raw=raw), patch.object(transport, "_transfer", return_value=raw) as call:
                with self.assertRaises(transport.DeliveryDenied):
                    deliver(b"fixture")
                self.assertEqual(1, call.call_count)

    def test_redirects_errors_and_non_http_deny_without_retry(self):
        for raw in [response(status=b"301 Redirect"), response(status=b"403 Denied"),
                    response(status=b"500 Error"), b"not HTTP", response().replace(b"HTTP/1.1", b"HTTP/2")]:
            with patch.object(transport, "_transfer", return_value=raw) as call:
                with self.assertRaises(transport.DeliveryDenied):
                    deliver(b"fixture")
                self.assertEqual(1, call.call_count)

    def test_wrong_bytes_or_wrong_version_deny(self):
        for raw in [response(body=b"wrong"), response(b"different", body=b"fixture")]:
            with patch.object(transport, "_transfer", side_effect=[response(), raw]) as call:
                with self.assertRaisesRegex(transport.DeliveryDenied, "readback mismatch"):
                    deliver(b"fixture")
                self.assertEqual(2, call.call_count)

    def test_ambiguous_put_denies_without_get_or_retry(self):
        with patch.object(transport, "_transfer", side_effect=transport.DeliveryDenied("transport")) as call:
            with self.assertRaises(transport.DeliveryDenied):
                deliver(b"fixture")
            self.assertEqual(1, call.call_count)

    def test_input_limits_before_network(self):
        for payload in [b"", b"x" * 8193, "text", None]:
            with patch.object(transport, "_transfer") as call:
                with self.assertRaises(transport.DeliveryDenied):
                    deliver(payload)
                call.assert_not_called()
        for access, secret in [("bad:key", "fixture"), ("fixture", "bad\nkey"),
                               ("fixture", ""), (None, "fixture"), ("fixture", "x" * 4097)]:
            with patch.object(transport, "_transfer") as call:
                with self.assertRaises(transport.DeliveryDenied):
                    deliver(b"fixture", access, secret)
                call.assert_not_called()

    def test_record_identity_validated_before_network(self):
        for record_id in [None, 1, "../escape", RECORD_ID.upper(), "0" * 36,
                          "74f8c0cd-1f8b-1a0f-9561-8f047e7206fb"]:
            with patch.object(transport, "_transfer") as call:
                with self.assertRaises(transport.DeliveryDenied):
                    deliver(b"fixture", record_id=record_id)
                call.assert_not_called()

    def test_same_durable_record_maps_to_same_object_without_internal_retry(self):
        with patch.object(transport, "_transfer", side_effect=transport.DeliveryDenied("ambiguous")) as call:
            for _ in range(2):
                with self.assertRaises(transport.DeliveryDenied):
                    deliver(b"fixture")
            self.assertEqual(2, call.call_count)
            self.assertEqual(call.call_args_list[0].args[0], call.call_args_list[1].args[0])

    def test_untrusted_headers_and_oversize_responses_deny(self):
        candidates = [response(extra=b" folded: value\r\n"), response(extra=b"Bad\nName: x\r\n"),
                      response(extra=b"Valid: bad\rvalue\r\n"), response(body=b"x" * 8193),
                      response(extra=b"Valid: " + b"x" * 8192 + b"\r\n"),
                      b"x" * 32769, response(extra=b"X: y\r\n" * 65)]
        for raw in candidates:
            with self.assertRaises(transport.DeliveryDenied):
                transport.parse_response(raw)

    def test_case_insensitive_header_and_escaped_config(self):
        self.assertEqual((b"fixture+/=", b"fixture"), transport.parse_response(
            response(body=b"fixture").replace(b"x-amz-version-id", b"X-Amz-Version-Id")))
        self.assertEqual('"a\\\\b\\\"c"', transport._quoted('a\\b"c'))

    def test_curl_fixed_destination_no_secret_argv_or_environment(self):
        config = b'user = "synthetic-access:synthetic-secret"\n'
        seen = {}
        def spawn(argv, **kwargs):
            seen.update(argv=argv, **kwargs)
            kwargs["stdout"].write(response())
            return SimpleNamespace(returncode=0, communicate=lambda **_: None)
        with patch.object(transport.subprocess, "Popen", side_effect=spawn):
            self.assertEqual(response(), transport._transfer(config, 7, transport.time.monotonic() + 5))
        self.assertNotIn("synthetic-secret", str(seen["argv"]) + str(seen["env"]))
        self.assertEqual(["/usr/bin/curl", "-q"], seen["argv"][:2])
        self.assertEqual(transport.ENV, seen["env"])
        self.assertNotIn("--location", seen["argv"])
        self.assertEqual("0", seen["argv"][seen["argv"].index("--retry") + 1])
        self.assertEqual("=https", seen["argv"][seen["argv"].index("--proto") + 1])
        self.assertEqual("", seen["argv"][seen["argv"].index("--proxy") + 1])
        self.assertEqual(subprocess.DEVNULL, seen["stderr"])

    def test_deadline_before_transfer(self):
        with patch.object(transport.subprocess, "Popen") as call:
            with self.assertRaises(transport.DeliveryDenied):
                transport._transfer(b"fixture", 7, transport.time.monotonic() - 1)
            call.assert_not_called()

    def test_timeout_kills_process_group_and_suppresses_diagnostics(self):
        attempts = iter([subprocess.TimeoutExpired(["fixture"], 1), None])
        def communicate(**_):
            value = next(attempts)
            if value:
                raise value
        process = SimpleNamespace(pid=7654321, communicate=communicate)
        with patch.object(transport.subprocess, "Popen", return_value=process), \
                patch.object(transport.os, "killpg") as kill:
            with self.assertRaisesRegex(transport.DeliveryDenied, "^audit transport unavailable; do not replay$"):
                transport._transfer(b"fixture", 7, transport.time.monotonic() + 5)
            kill.assert_called_once_with(process.pid, transport.signal.SIGKILL)

    def test_curl_error_never_exposes_provider_body(self):
        def spawn(*_, **kwargs):
            kwargs["stdout"].write(b"synthetic-provider-secret")
            return SimpleNamespace(returncode=23, communicate=lambda **_: None)
        with patch.object(transport.subprocess, "Popen", side_effect=spawn):
            with self.assertRaisesRegex(transport.DeliveryDenied, "^audit transport failed; do not replay$"):
                transport._transfer(b"fixture", 7, transport.time.monotonic() + 5)

    def test_native_curl_refuses_non_https_protocol_without_network(self):
        with transport.tempfile.TemporaryFile() as source:
            with self.assertRaisesRegex(transport.DeliveryDenied, "^audit transport failed; do not replay$"):
                transport._transfer(b'url = "file:///dev/null"\n', source.fileno(),
                                    transport.time.monotonic() + 5)


if __name__ == "__main__":
    unittest.main()
