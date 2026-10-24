"""Disposable Kafka TLS/ACL proof preserves identity, denial, digest and cleanup boundaries."""
import json
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import test_kafka_tls_acl_native as target


class KafkaTlsAclTest(unittest.TestCase):
    def test_native_security_configuration_is_fail_closed(self):
        server = target.server_config()
        for required in ("SSL:SSL,CONTROLLER:SSL", "StandardAuthorizer", "ssl.client.auth=required",
                         "allow.everyone.if.no.acl.found=false", "unclean.leader.election.enable=false",
                         "default.replication.factor=1", "min.insync.replicas=1"):
            self.assertIn(required, server)
        self.assertIn('advertised.listeners=SSL://127.0.0.1:9092', server)
        self.assertNotIn("PLAINTEXT", server)
        self.assertNotIn("User:CN=writer", server)
        config = target.client_config("writer")
        for required in ("security.protocol=SSL", "ssl.endpoint.identification.algorithm=https",
                         "acks=all", "enable.idempotence=true", "writer.pem"):
            self.assertIn(required, config)
        self.assertNotIn("ssl.keystore", target.client_config(None))

    def run_fixture(self, *, wrong_digest=False, manifest_store=False, outage=False,
                    hidden_topic=False, reader_cluster_denial=False, consumer_records=None, cleanup_failure=False):
        calls, revoked = [], False
        pin = target.selected()

        def docker(*args, **kwargs):
            nonlocal revoked
            calls.append(args)
            code, out, error = 0, "", ""
            if args[:2] == ("image", "inspect"):
                engine_id = pin["image"].split("@")[1] if manifest_store else pin["image_configuration_digest"]
                out = json.dumps({"Id": "wrong" if wrong_digest else engine_id,
                                  "RepoDigests": [pin["image"]], "Os": "linux", "Architecture": "amd64"})
            elif args[0] == "run":
                out = "a" * 64
            elif args[0] == "rm":
                code = int(cleanup_failure)
            elif args[0] == "exec":
                text = " ".join(args)
                if "--remove" in args:
                    revoked = True
                if any(identity + ".properties" in text for identity in
                       ("missing-client", "rogue", "untrusted-server")) or "127.0.0.2:9092" in text:
                    code, error = 1, "TimeoutException" if outage else "SSLHandshakeException"
                elif "foreign-group" in args:
                    code = 1 if consumer_records is None else 0
                    records = 0 if consumer_records is None else consumer_records
                    error = f"GroupAuthorizationException\nProcessed a total of {records} messages\n"
                elif "kafka-acls.sh" in text and "writer.properties" in text:
                    code, error = 1, "ClusterAuthorizationException"
                elif hidden_topic and "outsider.properties" in text:
                    code, error = 1, "IllegalArgumentException: topic not found"
                elif reader_cluster_denial and "kafka-console-producer.sh" in text and "reader.properties" in text:
                    code, error = 1, "ClusterAuthorizationException"
                elif "outsider.properties" in text or ("kafka-console-producer.sh" in text and
                        ("reader.properties" in text or "other-events" in args or revoked)):
                    code, error = 1, "TopicAuthorizationException"
                elif "kafka-console-consumer.sh" in text:
                    out = "synthetic-event\n"
                elif 'kafka-metadata-quorum.sh' in text:
                    out = 'LeaderId: 1\n'
            return subprocess.CompletedProcess(args, code, stdout=out, stderr=error)

        with patch.object(target, "docker", side_effect=docker), \
                patch.object(target, "certificates"), patch.object(target.os, "getuid", return_value=1000), \
                patch.object(target.subprocess, "run", return_value=subprocess.CompletedProcess(
                    ["findmnt"], 0, stdout="tmpfs\n")):
            try:
                result = target.exercise("unix:///test.sock")
            except RuntimeError as error:
                return None, str(error), calls
        return result, None, calls

    def test_positive_and_negative_controls_use_published_image_and_always_cleanup(self):
        result, error, calls = self.run_fixture()
        self.assertIsNone(error)
        self.assertEqual("Passed", result["topic_role_group_acl"])
        self.assertEqual("Not run", result["deployment"])
        started = next(args for args in calls if args[0] == "run")
        self.assertIn(target.selected()["image_configuration_digest"], started)
        self.assertEqual("none", started[started.index("--network") + 1])
        self.assertIn("--read-only", started)
        self.assertIn("--pull=never", started)
        self.assertNotIn("--privileged", started)
        self.assertNotIn("-p", started)
        self.assertEqual("256", started[started.index("--pids-limit") + 1])
        for args in calls:
            if args[0] == "exec":
                if '/bin/sh' in args:
                    self.assertEqual(target.runtime.PROBE, args[-1])
                else:
                    self.assertIn("--command-config", args)
                self.assertNotIn("--producer.config", args)
                self.assertNotIn("--consumer.config", args)
        self.assertEqual(("rm", "--volumes", "-f", "a" * 64), calls[-1])

    def test_containerd_manifest_id_remains_bound_to_exact_published_digest(self):
        result, error, calls = self.run_fixture(manifest_store=True)
        self.assertIsNone(error)
        self.assertEqual("Passed", result["native_tls"])
        started = next(args for args in calls if args[0] == "run")
        self.assertIn(target.selected()["image"].split("@")[1], started)

    def test_outage_is_not_a_security_denial(self):
        result, error, calls = self.run_fixture(outage=True)
        self.assertIsNone(result)
        self.assertIn("EXPECTED_KAFKA_SECURITY_DENIAL_MISSING", error)
        self.assertEqual("rm", calls[-1][0])

    def test_hidden_or_missing_topic_is_not_authorization_evidence(self):
        result, error, calls = self.run_fixture(hidden_topic=True)
        self.assertIsNone(result)
        self.assertIn("EXPECTED_KAFKA_SECURITY_DENIAL_MISSING", error)
        self.assertEqual("rm", calls[-1][0])

    def test_read_only_principal_can_be_denied_at_idempotent_producer_initialization(self):
        result, error, _ = self.run_fixture(reader_cluster_denial=True)
        self.assertIsNone(error)
        self.assertEqual("Passed", result["topic_role_group_acl"])

    def test_consumer_zero_exit_needs_specific_denial_and_zero_processed_records(self):
        result, error, _ = self.run_fixture(consumer_records=0)
        self.assertIsNone(error)
        self.assertEqual("Passed", result["topic_role_group_acl"])
        result, error, _ = self.run_fixture(consumer_records=1)
        self.assertIsNone(result)
        self.assertIn("EXPECTED_KAFKA_SECURITY_DENIAL_MISSING", error)

    def test_remote_docker_never_reads_images_or_starts_workloads(self):
        with patch.object(target, "docker") as docker, self.assertRaisesRegex(RuntimeError, "LOCAL_NONROOT"):
            target.exercise("tcp://remote:2375")
        docker.assert_not_called()

    def test_wrong_digest_never_starts_container_and_cleanup_failure_never_passes(self):
        result, error, calls = self.run_fixture(wrong_digest=True)
        self.assertIsNone(result)
        self.assertEqual("EXACT_PUBLISHED_KAFKA_REQUIRED", error)
        self.assertFalse(any(args[0] == "run" for args in calls))
        result, error, _ = self.run_fixture(cleanup_failure=True)
        self.assertIsNone(result)
        self.assertEqual("DISPOSABLE_TLS_KAFKA_CLEANUP_FAILED", error)


if __name__ == "__main__":
    unittest.main()
