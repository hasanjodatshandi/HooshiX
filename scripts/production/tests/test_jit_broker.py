import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import jit_broker as broker
import jit_runtime as core


class BrokerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_request_cli_uses_local_identity_and_grants_nothing(self):
        result = subprocess.run([sys.executable, "-I", str(Path(broker.__file__)), "request",
                                 "--action", "service-inspect", "--target", "caddy.service",
                                 "--ticket", "OPS-TEST", "--seconds", "60"],
                                check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
        value = json.loads(result.stdout)
        self.assertEqual("service-inspect", value["action"])
        self.assertEqual(os.getuid(), __import__("pwd").getpwnam(value["requester"]).pw_uid)
        self.assertEqual(value["requester"], value["reviewer"])
        self.assertNotIn("command", value)
        self.assertEqual(core.canonical(value), result.stdout)

    def test_checkout_execute_and_revoke_cannot_gain_privilege(self):
        for command in ("execute", "revoke"):
            result = subprocess.run([sys.executable, "-I", str(Path(broker.__file__)), command],
                                    input=b"{}", stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
            self.assertEqual(1, result.returncode)
            self.assertIn(b"broker is not installed", result.stderr)

    def test_unknown_scope_and_identity_flags_rejected(self):
        result = subprocess.run([sys.executable, str(Path(broker.__file__)), "request",
                                 "--action", "shell", "--target", "ssh.service", "--ticket", "OPS-TEST"],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5, check=False)
        self.assertEqual(2, result.returncode)

    def test_root_cannot_render_operator_request(self):
        with patch.object(os, "geteuid", return_value=0), self.assertRaises(broker.BrokerDenied):
            broker.request_bytes(core, "service-inspect", "caddy.service", "OPS-1", 60)

    def test_symlink_and_writable_ancestor_rejected(self):
        with self.assertRaises(broker.BrokerDenied):
            broker.root_path(self.root / "missing", 0o600)
        source = self.root / "file"
        source.write_text("fixture")
        link = self.root / "link"
        link.symlink_to(source)
        with self.assertRaises(broker.BrokerDenied):
            broker.root_path(link, 0o600)
        def fake_stat(path):
            mode = stat.S_IFREG | 0o600 if path == source else stat.S_IFDIR | (0o777 if path == self.root else 0o755)
            return SimpleNamespace(st_uid=0, st_mode=mode, st_nlink=1)
        with patch.object(Path, "lstat", fake_stat), self.assertRaises(broker.BrokerDenied):
            broker.root_path(source, 0o600)

    def test_installed_root_paths_require_exact_modes(self):
        source = self.root / "file"
        def fake_stat(path):
            return SimpleNamespace(st_uid=0, st_mode=(stat.S_IFREG | 0o644) if path == source
                                   else stat.S_IFDIR | 0o755, st_nlink=1)
        with patch.object(Path, "lstat", fake_stat):
            broker.root_path(source, 0o644)
            with self.assertRaises(broker.BrokerDenied):
                broker.root_path(source, 0o600)

    def test_caller_uid_name_inventory_binding(self):
        config = self.root / "operator.json"
        config.write_text('{"schema_version":1,"operator":"operator","uid":1000}')
        def protected(*_):
            return os.open(config, os.O_RDONLY)
        with patch.object(broker, "root_path"), patch.object(os, "geteuid", return_value=0), \
                patch.object(core, "protected_fd", side_effect=protected), \
                patch.object(broker.pwd, "getpwnam", return_value=SimpleNamespace(pw_uid=1000)):
            self.assertEqual(("operator", 1000), broker.caller_identity(core, {"SUDO_USER": "operator", "SUDO_UID": "1000"}))
            for env in ({}, {"SUDO_USER": "other", "SUDO_UID": "1000"},
                        {"SUDO_USER": "operator", "SUDO_UID": "0"}):
                with self.assertRaises(broker.BrokerDenied):
                    broker.caller_identity(core, env)

    def test_envelope_rejects_authority_injection_duplicates_and_size(self):
        for raw in (b"[]", b"x" * 8193, b'{"request":{},"signature":"x","audit_ack":{}}',
                    b'{"request":{},"request":{},"signature":"x"}',
                    b'{"request":{},"signature":3}'):
            with self.assertRaises(broker.BrokerDenied):
                broker.envelope(core, raw)
        raw, sig = broker.envelope(core, b'{"request":{"schema_version":1},"signature":"fixture"}')
        self.assertEqual(b'{"schema_version":1}', raw)
        self.assertEqual(b"fixture", sig)

    def test_stdin_has_deadline_and_size_limit(self):
        read_fd, write_fd = os.pipe()
        try:
            os.write(write_fd, b"{}"); os.close(write_fd); write_fd = None
            self.assertEqual(b"{}", broker.read_envelope(read_fd))
        finally:
            os.close(read_fd)
            if write_fd is not None:
                os.close(write_fd)
        with patch.object(broker.select, "select", return_value=([], [], [])), self.assertRaises(broker.BrokerDenied):
            broker.read_envelope(0)
        with patch.object(broker.select, "select", return_value=([0], [], [])), \
                patch.object(os, "read", return_value=b"x" * 8193), self.assertRaises(broker.BrokerDenied):
            broker.read_envelope(0)

    def test_revoke_stops_only_operator_jobs_before_audit_outage(self):
        unit = "hooshix-jit-u1000-r" + "a" * 32 + ".service"
        sequence = []
        def command(argv, **_):
            sequence.append(argv[1])
            return f"{unit} loaded active running fixture\n".encode() if argv[1] == "list-units" else b""
        def outage(*_):
            sequence.append("audit")
            raise broker.BrokerDenied("fixture outage")
        with patch.object(broker, "bounded_call", side_effect=command), \
                patch.object(broker, "event_ack", side_effect=outage):
            with self.assertRaisesRegex(broker.BrokerDenied, "job stopped"):
                broker.revoke(core, "operator", 1000)
        self.assertEqual(["list-units", "stop", "audit"], sequence)

    def test_revoke_rejects_other_operator_and_arbitrary_service(self):
        for unit in ("ssh.service", "hooshix-jit-u1001-r" + "a" * 32 + ".service"):
            with patch.object(broker, "bounded_call", return_value=f"{unit} loaded active running\n".encode()) as call:
                with self.assertRaises(broker.BrokerDenied):
                    broker.revoke(core, "operator", 1000)
                self.assertEqual(1, call.call_count)

    def test_audit_failure_prevents_submitting_job(self):
        directory = self.root / "protected"
        directory.mkdir(mode=0o700)
        with patch.object(broker, "root_path"), \
                patch.object(core, "protected_fd", side_effect=lambda *_: os.open(directory, os.O_RDONLY)), \
                patch.object(core, "prepare_execution", side_effect=core.Denied("fixture audit unavailable")), \
                patch.object(broker.subprocess, "Popen") as submit:
            with self.assertRaises(core.Denied):
                broker.execute(core, b'{"request":{},"signature":"fixture"}', "operator", 1000)
            submit.assert_not_called()

    def test_bounded_command_hides_errors_and_limits_output(self):
        self.assertEqual(b"fixture\n", broker.bounded_call(["/usr/bin/printf", "fixture\n"]))
        with self.assertRaisesRegex(broker.BrokerDenied, "^protected command failed$"):
            broker.bounded_call(["/usr/bin/python3", "-c", "print('x'*20000)"])
        with patch.object(broker.subprocess, "Popen", side_effect=OSError("secret")):
            with self.assertRaisesRegex(broker.BrokerDenied, "^protected command unavailable$"):
                broker.bounded_call(["fixture"])

    def test_os_audit_health_required_before_delivery(self):
        for status in (b"enabled 0\nlost 0\n", b"enabled 1\nlost 2\n", b"invalid status shape here\n",
                       b"enabled 0\nenabled 1\nlost 0\n", b"enabled 1\nlost 4\nlost 0\n"):
            with patch.object(broker, "root_path"), \
                    patch.object(broker, "bounded_call", side_effect=[b"active\n", status]) as call:
                with self.assertRaises(broker.BrokerDenied):
                    broker.audit_delivery(b"fixture")
                self.assertEqual(2, call.call_count)
        with patch.object(broker, "root_path"), \
                patch.object(broker, "bounded_call", side_effect=[
                    b"active\n", b"enabled 2\nlost 0\nloginuid_immutable 0 unlocked\n", b"receipt"]):
            self.assertEqual(b"receipt", broker.audit_delivery(b"fixture"))

    def test_protected_helper_timeout_is_bounded(self):
        with self.assertRaisesRegex(broker.BrokerDenied, "^protected command unavailable$"):
            broker.bounded_call(["/usr/bin/python3", "-c", "__import__('time').sleep(20)"], timeout=0.05)

    def test_real_request_signature_bundle_roundtrip(self):
        packet = broker.request_bytes(core, "service-inspect", "caddy.service", "OPS-TEST", 60)
        request_file = self.root / "request.json"
        request_file.write_bytes(packet)
        request_file.chmod(0o600)
        key = self.root / "synthetic-key"
        subprocess.run(["/usr/bin/ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", str(key)],
                       check=True, timeout=5, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["/usr/bin/ssh-keygen", "-Y", "sign", "-f", str(key), "-n", core.NAMESPACE,
                        str(request_file)], check=True, timeout=5, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL)
        signature_file = request_file.with_suffix(".json.sig")
        signature_file.chmod(0o600)
        data, signature = broker.envelope(core, broker.bundle_bytes(core, request_file, signature_file))
        parsed = json.loads(data)
        pub = key.with_suffix(".pub").read_text().split()
        signers = self.root / "allowed_signers"
        signers.write_text(f'{parsed["reviewer"]} namespaces="{core.NAMESPACE}" {pub[0]} {pub[1]}\n')
        signers.chmod(0o600)
        request = core.parse_request(data, parsed["requester"], parsed["boot_id"],
                                     parsed["issued_boottime_ns"])
        core.verify_approval(request, signature, signers, self.root, os.getuid())
        request_file.write_bytes(packet + b"\n")
        with self.assertRaises(broker.BrokerDenied):
            broker.bundle_bytes(core, request_file, signature_file)

    def test_execute_records_outcome_without_replaying(self):
        data = json.loads(broker.request_bytes(core, "service-inspect", "caddy.service", "OPS-TEST", 60))
        raw = core.canonical({"request": data, "signature": "fixture"})
        directory = self.root / "protected"
        directory.mkdir(mode=0o700)
        process = SimpleNamespace(poll=lambda: 0, returncode=0)
        with patch.object(broker, "root_path"), \
                patch.object(core, "protected_fd", side_effect=lambda *_: os.open(directory, os.O_RDONLY)), \
                patch.object(core, "prepare_execution", return_value=["fixture"]), \
                patch.object(broker.subprocess, "Popen", return_value=process) as submit, \
                patch.object(broker, "event_ack") as ack:
            self.assertEqual(0, broker.execute(core, raw, data["requester"], os.getuid()))
            submit.assert_called_once()
            event = json.loads(ack.call_args.args[1])
            self.assertEqual("outcome", event["event"])
            self.assertEqual("Passed", event["result"])
            ack.side_effect = broker.BrokerDenied("fixture outage")
            with self.assertRaisesRegex(broker.BrokerDenied, "job ended.*do not replay"):
                broker.execute(core, raw, data["requester"], os.getuid())


if __name__ == "__main__":
    unittest.main()
