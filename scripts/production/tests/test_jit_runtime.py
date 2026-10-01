import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import jit_runtime as jit


class JitRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.private = self.root / "private"
        self.private.mkdir(mode=0o700)
        self.ledger = self.root / "ledger"
        self.ledger.mkdir(mode=0o700)
        self.boot = str(uuid.uuid4())
        self.now = time.clock_gettime_ns(time.CLOCK_BOOTTIME)
        self.data = {"schema_version": 1, "request_id": str(uuid.uuid4()),
                     "requester": "operator", "reviewer": "operator",
                     "action": "service-inspect", "target": "caddy.service",
                     "ticket": "OPS-10", "duration_seconds": 30,
                     "boot_id": self.boot, "issued_boottime_ns": self.now}

    def request(self):
        return jit.parse_request(jit.canonical(self.data), "operator", self.boot, self.now)

    def key_and_signature(self, namespace=jit.NAMESPACE):
        key = self.private / "fixture-key"
        # Synthetic disposable key, never enrolled or used on Production.
        subprocess.run(["/usr/bin/ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", str(key)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
        pub = key.with_suffix(".pub").read_text().split()
        signers = self.private / "allowed_signers"
        signers.write_text(f'operator namespaces="{jit.NAMESPACE}" {pub[0]} {pub[1]}\n')
        signers.chmod(0o600)
        result = subprocess.run(["/usr/bin/ssh-keygen", "-Y", "sign", "-f", str(key),
                                 "-n", namespace, "-"], input=self.request().payload,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True, timeout=5)
        return signers, result.stdout

    def test_valid_canonical_request_and_fixed_command(self):
        request = self.request()
        argv = jit.service_command(request, 1000, self.now)
        self.assertEqual(argv[-8:], ["--", "/usr/bin/systemctl", "show", "--property=ActiveState",
                                    "--property=SubState", "--value", "--", "caddy.service"])
        for prop in ("RuntimeMaxSec=25s", "ExitType=cgroup", "KillMode=control-group",
                     "KillSignal=SIGKILL", "NoNewPrivileges=yes", "Delegate=no"):
            self.assertIn(prop, argv)

    def test_invalid_scope_identity_types_and_lifetime(self):
        cases = {"schema_version": [True, 2], "request_id": [str(uuid.uuid1()), "../bad"],
                 "requester": ["other", []], "reviewer": ["other", 1],
                 "action": ["shell", [], "service-inspect;id"], "target": ["ssh.service", [], "--all"],
                 "ticket": ["", "secret\nvalue", "hasan@example.com"],
                 "duration_seconds": [True, 9, 1801, 30.0],
                 "issued_boottime_ns": [True, -1, self.now + 1, self.now - 301_000_000_000]}
        for key, values in cases.items():
            for value in values:
                with self.subTest(key=key, value=value):
                    data = self.data | {key: value}
                    with self.assertRaises(jit.Denied):
                        jit.parse_request(jit.canonical(data), "operator", self.boot, self.now)

    def test_duplicate_unknown_oversize_and_malformed_json(self):
        for raw in (b'{"schema_version":1,"schema_version":1}', b"[]", b"\xff", b"{", b"x" * 8193,
                    jit.canonical(self.data | {"command": "id"})):
            with self.subTest(raw=raw[:20]), self.assertRaises(jit.Denied):
                jit.parse_request(raw, "operator", self.boot, self.now)

    def test_reboot_and_expiry_fail_closed(self):
        with self.assertRaises(jit.Denied):
            jit.parse_request(jit.canonical(self.data), "operator", str(uuid.uuid4()), self.now)
        for future in (self.now + 25_000_000_000, self.now + 30_000_000_000):
            with self.assertRaises(jit.Denied):
                self.request().remaining_seconds(future)
        with patch.object(time, "time", side_effect=AssertionError("wall clock is not authority")):
            self.request()

    def test_real_signature_and_tampering(self):
        signers, signature = self.key_and_signature()
        jit.verify_approval(self.request(), signature, signers, self.private, os.geteuid())
        self.data["target"] = "k3s.service"
        with self.assertRaises(jit.Denied):
            jit.verify_approval(self.request(), signature, signers, self.private, os.geteuid())

    def test_wrong_namespace_and_unknown_key(self):
        signers, signature = self.key_and_signature("wrong-namespace")
        with self.assertRaises(jit.Denied):
            jit.verify_approval(self.request(), signature, signers, self.private, os.geteuid())
        signers.write_text(signers.read_text().replace("operator", "unknown"))
        with self.assertRaises(jit.Denied):
            jit.verify_approval(self.request(), signature, signers, self.private, os.geteuid())

    def test_unsafe_paths_and_wildcard_reviewers_rejected(self):
        signers, signature = self.key_and_signature()
        signers.chmod(0o644)
        with self.assertRaises(jit.Denied):
            jit.verify_approval(self.request(), signature, signers, self.private, os.geteuid())
        signers.chmod(0o600)
        signers.write_text(signers.read_text().replace("operator", "*"))
        with self.assertRaises(jit.Denied):
            jit.verify_approval(self.request(), signature, signers, self.private, os.geteuid())
        link = self.root / "link"
        link.symlink_to(self.ledger, target_is_directory=True)
        with self.assertRaises(jit.Denied):
            jit.consume_once(self.request(), link, os.geteuid())
        with self.assertRaises(jit.Denied):
            jit.protected_fd(signers, os.geteuid() + 1)

    def test_durable_replay_and_concurrent_duplicate(self):
        request = self.request()
        def consume(_):
            try:
                jit.consume_once(request, self.ledger, os.geteuid())
                return "Passed"
            except jit.Denied:
                return "Denied"
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(consume, range(4)))
        self.assertEqual(1, results.count("Passed"))
        record = self.ledger / request.data["request_id"]
        self.assertEqual(request.payload, record.read_bytes())
        self.assertEqual(0o600, record.stat().st_mode & 0o777)
        with self.assertRaises(jit.Denied):
            jit.consume_once(request, self.ledger, os.geteuid())

    def test_bounded_ledger(self):
        with patch.object(jit, "MAX_RECORDS", 1):
            jit.consume_once(self.request(), self.ledger, os.geteuid())
            self.data["request_id"] = str(uuid.uuid4())
            with self.assertRaises(jit.Denied):
                jit.consume_once(self.request(), self.ledger, os.geteuid())

    def test_invalid_audit_receipts(self):
        request = self.request()
        good = {"sha256": hashlib.sha256(request.payload).hexdigest(), "version_id": "synthetic-v1"}
        jit.validate_audit_ack(request, jit.canonical(good))
        for bad in (None, b"", b"[]", b"x" * 8193, jit.canonical(good | {"version_id": "null"}),
                    jit.canonical(good | {"sha256": "wrong"}), jit.canonical(good | {"version_id": ""}),
                    jit.canonical(good | {"approved": True})):
            with self.assertRaises(jit.Denied):
                jit.validate_audit_ack(request, bad)

    def test_audit_failure_consumes_request_without_command(self):
        signers, signature = self.key_and_signature()
        args = dict(caller="operator", caller_uid=1000, boot_id=self.boot, signers=signers,
                    ledger=self.ledger, private=self.private, protected_uid=os.geteuid())
        with self.assertRaisesRegex(jit.Denied, "missing"):
            jit.prepare_execution(jit.canonical(self.data), signature, audit=None, **args)
        with self.assertRaisesRegex(jit.Denied, "already consumed"):
            jit.prepare_execution(jit.canonical(self.data), signature, audit=None, **args)

    def test_recheck_deadline_after_slow_audit(self):
        signers, signature = self.key_and_signature()
        def audit(payload):
            return jit.canonical({"sha256": hashlib.sha256(payload).hexdigest(), "version_id": "test-v1"})
        with patch.object(time, "clock_gettime_ns", side_effect=[self.now, self.now + 30_000_000_000]):
            with self.assertRaisesRegex(jit.Denied, "expired"):
                jit.prepare_execution(jit.canonical(self.data), signature, caller="operator", caller_uid=1000,
                                      boot_id=self.boot, signers=signers, ledger=self.ledger,
                                      private=self.private, protected_uid=os.geteuid(), audit=audit)

    def test_end_to_end_admission_and_audit_outage(self):
        signers, signature = self.key_and_signature()
        args = dict(caller="operator", caller_uid=1000, boot_id=self.boot, signers=signers,
                    ledger=self.ledger, private=self.private, protected_uid=os.geteuid())
        def audit(payload):
            return jit.canonical({"sha256": hashlib.sha256(payload).hexdigest(), "version_id": "test-v1"})
        argv = jit.prepare_execution(jit.canonical(self.data), signature, audit=audit, **args)
        self.assertIn("--unit=hooshix-jit-u1000.service", argv)
        self.data["request_id"] = str(uuid.uuid4())
        with patch.object(jit, "verify_approval"):
            def unavailable(_):
                raise RuntimeError("sensitive provider diagnostic")
            with self.assertRaisesRegex(jit.Denied, "^audit delivery unavailable$"):
                jit.prepare_execution(jit.canonical(self.data), b"fixture", audit=unavailable, **args)

    def test_hardlink_and_fifo_cannot_be_signer_inventory(self):
        signers, _ = self.key_and_signature()
        os.link(signers, self.root / "second-link")
        with self.assertRaises(jit.Denied):
            jit.protected_fd(signers, os.geteuid())
        fifo = self.private / "fifo"
        os.mkfifo(fifo, 0o600)
        with self.assertRaises(jit.Denied):
            jit.protected_fd(fifo, os.geteuid())


if __name__ == "__main__":
    unittest.main()
