"""Exercise native Kafka TLS/ACL on the published digest; disposable local data only."""
from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import subprocess
import tempfile
import time
from pathlib import Path

import kafka_tls_material as node_tls
import install_kafka_runtime as installer
import render_kafka_runtime as runtime
from test_kafka_runtime_native import require
from test_postgresql_isolation_native import docker

ROOT = Path(__file__).resolve().parents[2]
PIN = ROOT / "infrastructure/production/kafka/published-candidate.json"
MOUNT = "/run/kafka-fixture"
HOST = "kafka-fixture"
TOPIC = "owned-events"
GROUP = "fixture-readers"


def selected() -> dict:
    value = json.loads(PIN.read_text())
    require(value.get("schema_version") == 1 and value.get("version") == "4.2.2"
            and re.fullmatch(r"ghcr.io/hasanjodatshandi/hooshix/platform-kafka-v2-private@sha256:[a-f0-9]{64}",
                             value.get("image", "")) is not None
            and re.fullmatch(r"sha256:[a-f0-9]{64}", value.get("image_configuration_digest", "")) is not None
            and value.get("production_promotion") == "blocked-until-runtime-staging-and-recovery-evidence",
            "PINNED_BLOCKED_KAFKA_PUBLICATION_REQUIRED")
    return value


def client_config(identity: str | None, *, trust: str = "ca.crt") -> str:
    properties = ("security.protocol=SSL\nssl.truststore.type=PEM\n"
                  f"ssl.truststore.location={MOUNT}/{trust}\n"
                  "ssl.endpoint.identification.algorithm=https\n"
                  "ssl.enabled.protocols=TLSv1.3,TLSv1.2\n"
                  "request.timeout.ms=5000\ndefault.api.timeout.ms=8000\n"
                  "max.block.ms=8000\ndelivery.timeout.ms=10000\n"
                  "acks=all\nenable.idempotence=true\nretries=1\n")
    if identity is not None:
        properties += f"ssl.keystore.type=PEM\nssl.keystore.location={MOUNT}/{identity}.pem\n"
    return properties


server_config = runtime.server_config


def certificates(work: Path) -> None:
    def openssl(*args):
        result = subprocess.run(["openssl", *args], capture_output=True, timeout=30, check=False)
        require(result.returncode == 0, "SYNTHETIC_CERTIFICATE_GENERATION_FAILED")

    for ca in ("ca", "rogue-ca"):
        openssl("req", "-x509", "-newkey", "rsa:2048", "-noenc", "-sha256", "-days", "1",
                "-subj", "/CN=Disposable Kafka " + ca, "-keyout", str(work / (ca + ".key")),
                "-out", str(work / (ca + ".crt")), "-addext", "basicConstraints=critical,CA:TRUE",
                "-addext", "keyUsage=critical,keyCertSign,cRLSign")
    for identity in ("broker", "admin", "writer", "reader", "outsider", "rogue"):
        subject = node_tls.COMMON_NAME if identity in ("broker", "admin") else identity
        openssl("req", "-new", "-newkey", "rsa:2048", "-noenc", "-sha256", "-subj", "/CN=" + subject,
                "-keyout", str(work / (identity + ".key")), "-out", str(work / (identity + ".csr")))
        extension = ("basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
                     "extendedKeyUsage=clientAuth" + (",serverAuth\nsubjectAltName=IP:127.0.0.1,DNS:" + HOST
                                                       if identity == "broker" else "") + "\n")
        (work / "leaf.cnf").write_text(extension)
        ca = "rogue-ca" if identity == "rogue" else "ca"
        openssl("x509", "-req", "-in", str(work / (identity + ".csr")), "-CA", str(work / (ca + ".crt")),
                "-CAkey", str(work / (ca + ".key")), "-set_serial", "0x" + os.urandom(16).hex(),
                "-days", "1", "-sha256", "-extfile", str(work / "leaf.cnf"),
                "-out", str(work / (identity + ".crt")))
        pem = work / (identity + ".pem")
        pem.write_bytes((work / (identity + ".key")).read_bytes() + (work / (identity + ".crt")).read_bytes())
        pem.chmod(0o600)
        (work / (identity + ".properties")).write_text(client_config(identity))
    (work / "untrusted-server.properties").write_text(client_config("admin", trust="rogue-ca.crt"))
    (work / "missing-client.properties").write_text(client_config(None))
    (work / "server.properties").write_text(server_config())
    (work / "client.properties").write_text(runtime.client_config())


def exercise(docker_host: str, *, client_tls_check=None) -> dict:
    require(isinstance(docker_host, str) and docker_host.startswith("unix://")
            and os.environ.get("DOCKER_HOST", "unix://").startswith("unix://")
            and os.getuid() != 0, "LOCAL_NONROOT_UNIX_DOCKER_REQUIRED")
    pin = selected()
    inspected = docker("image", "inspect", "--format", "{{json .}}", pin["image"],
                       timeout=15, docker_host=docker_host)
    require(inspected.returncode == 0, "PULL_REVIEWED_PRIVATE_DIGEST_FIRST")
    image = json.loads(inspected.stdout)
    # Classic and containerd stores expose config and manifest IDs respectively.
    engine_ids = {pin["image_configuration_digest"], pin["image"].split("@")[1]}
    require(image["Id"] in engine_ids and pin["image"] in image["RepoDigests"]
            and image["Os"] == "linux" and image["Architecture"] == "amd64", "EXACT_PUBLISHED_KAFKA_REQUIRED")
    filesystem = subprocess.run(["findmnt", "-n", "-o", "FSTYPE", "--target", "/dev/shm"],
                                capture_output=True, text=True, timeout=5, check=True)
    require(filesystem.stdout.strip() == "tmpfs", "DISPOSABLE_RAM_FILESYSTEM_REQUIRED")
    with tempfile.TemporaryDirectory(prefix="hooshix-kafka-tls-", dir="/dev/shm") as temporary:
        work = Path(temporary)
        work.chmod(0o700)
        certificates(work)
        uid, gid = os.getuid(), os.getgid()
        start = runtime.start_script()
        started = docker("run", "-d", "--pull=never", "--network", "none", "--user", f"{uid}:{gid}", "--read-only",
                         "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--memory", "2g",
                         "--cpus", "1", "--pids-limit", "256", "--hostname", HOST,
                         "--add-host", HOST + ":127.0.0.1", "--tmpfs",
                         f"/var/lib/kafka/data:rw,nosuid,nodev,noexec,size=256m,uid={uid},gid={gid}",
                         "--tmpfs", f"/opt/kafka/logs:rw,nosuid,nodev,noexec,size=16m,uid={uid},gid={gid}",
                         "--tmpfs", f"/tmp:rw,nosuid,nodev,noexec,size=32m,uid={uid},gid={gid}",
                         "--mount", f"type=bind,src={work},dst={MOUNT},readonly",
                         "--mount", f"type=bind,src={work},dst=/run/kafka,readonly",
                         "--mount", f"type=bind,src={work},dst=/etc/kafka/commissioned,readonly",
                         "-e", "KAFKA_HEAP_OPTS=-Xms512m -Xmx512m", "--entrypoint", "/bin/sh",
                         image["Id"], "-ec", start, timeout=30, docker_host=docker_host)
        container = started.stdout.strip()
        require(started.returncode == 0 and re.fullmatch(r"[a-f0-9]{64}", container) is not None,
                "DISPOSABLE_TLS_KAFKA_START_FAILED")
        try:
            def command(tool, identity, *args, expected=None, address=HOST, input_text=None):
                result = docker("exec", "-i", container, "/opt/kafka/bin/kafka-" + tool + ".sh",
                                "--bootstrap-server", address + ":9092", "--command-config",
                                f"{MOUNT}/{identity}.properties", *args, input_text=input_text,
                                timeout=25, docker_host=docker_host)
                evidence = result.stdout + result.stderr
                category = next((name for name in ("TopicAuthorizationException", "GroupAuthorizationException",
                    "ClusterAuthorizationException", "SSLHandshakeException", "TimeoutException",
                    "IllegalArgumentException") if name in evidence), "Unclassified")
                if expected is not None:
                    denials = (expected,) if isinstance(expected, str) else expected
                    # ConsoleConsumer 4.2 can log a receive failure and still exit zero.
                    empty_consumer_denial = (tool == "console-consumer" and result.returncode == 0
                        and "Processed a total of 0 messages" in result.stderr)
                    require((result.returncode != 0 or empty_consumer_denial)
                            and (tool != "console-consumer" or "Processed a total of 0 messages" in result.stderr)
                            and any(denial in evidence for denial in denials),
                            "EXPECTED_KAFKA_SECURITY_DENIAL_MISSING_" + tool.upper() + "_" + identity.upper()
                            + "_" + category + "_EXIT_" + str(result.returncode))
                else:
                    require(result.returncode == 0, "KAFKA_SECURITY_OPERATION_FAILED_" + tool.upper() + "_" + identity.upper())
                return result.stdout

            deadline = time.monotonic() + 120
            while True:
                try:
                    command("topics", "admin", "--list")
                    break
                except (RuntimeError, subprocess.TimeoutExpired):
                    state = docker("inspect", "--format", "{{.State.Running}}", container,
                                   timeout=5, docker_host=docker_host)
                    require(state.returncode == 0 and state.stdout.strip() == "true", "TLS_KAFKA_EXITED")
                    require(time.monotonic() < deadline, "TLS_KAFKA_STARTUP_DEADLINE_EXCEEDED")
                    time.sleep(1)
            probe = runtime.pod()['containers'][0]['readinessProbe']
            health = docker('exec', container, *probe['exec']['command'],
                            timeout=probe['timeoutSeconds'], docker_host=docker_host)
            require(health.returncode == 0, 'RENDERED_KAFKA_HEALTH_PROBE_FAILED')
            liveness = runtime.pod()['containers'][0]['livenessProbe']
            for script, success in ((runtime.LIVENESS, True),
                    (runtime.LIVENESS.replace('-verify_ip 127.0.0.1', '-verify_ip 127.0.0.2'), False),
                    (runtime.LIVENESS.replace('/run/kafka/ca.crt', MOUNT + '/rogue-ca.crt'), False),
                    (runtime.LIVENESS.replace('-cert /run/kafka/broker.pem', '-cert ' + MOUNT + '/rogue.pem')
                        .replace('-key /run/kafka/broker.pem', '-key ' + MOUNT + '/rogue.pem'), False),
                    (runtime.LIVENESS.replace('127.0.0.1:9092', '127.0.0.1:1'), False)):
                checked = docker('exec', container, '/bin/sh', '-ec', script,
                                 timeout=liveness['timeoutSeconds'], docker_host=docker_host)
                require((checked.returncode == 0) == success and not checked.stdout and not checked.stderr,
                        'KAFKA_AUTHENTICATED_LIVENESS_POSITIVE_NEGATIVE_REQUIRED')
            cancelled = docker('exec', container, '/usr/bin/timeout', '-s', 'KILL', '2',
                '/bin/sh', '-ec', runtime.PROBE.replace('127.0.0.1:9092', '127.0.0.1:1'),
                timeout=10, docker_host=docker_host)
            require(cancelled.returncode == 137, 'RENDERED_KAFKA_PROBE_CANCELLATION_REQUIRED')
            processes = docker('exec', container, '/bin/sh', '-ec',
                "ps -o stat,comm | awk '$2 == \"java\" && $1 !~ /Z/ {n++} END {print n+0}'",
                timeout=5, docker_host=docker_host)
            require(processes.returncode == 0 and processes.stdout.strip() == '1',
                    'CANCELLED_KAFKA_PROBE_LEFT_LIVE_JVM')
            def closed_command(*args):
                require(args[:7] == ('-n', runtime.NS, 'exec', runtime.NAME + '-0', '-c', 'kafka', '--'),
                        'LOCAL_KAFKA_VERIFICATION_EXEC_REQUIRED')
                timed_probe = ('/usr/bin/timeout -s KILL ' + str(probe['timeoutSeconds'])
                    + ' /bin/sh -ec ' + shlex.quote(runtime.PROBE))
                concurrent = (timed_probe + ' & first=$!; ' + timed_probe + ' & second=$!; '
                    + shlex.join(args[7:]) + '; wait "$first"; wait "$second"')
                result = docker('exec', container, '/bin/sh', '-ec', concurrent,
                                timeout=30, docker_host=docker_host)
                require(result.returncode == 0, 'RENDERED_KAFKA_VERIFICATION_FAILED')
                return result.stdout.encode()

            for _ in range(3):
                cluster_id, _ = installer.storage_identity(closed_command)
                installer.quorum_and_topics(closed_command, cluster_id)
            if client_tls_check is not None:
                client_tls_check(container, work)
            for topic in (TOPIC, "other-events"):
                command("topics", "admin", "--create", "--topic", topic, "--partitions", "1",
                        "--replication-factor", "1", "--config", "min.insync.replicas=1")
            writer_acl = ("--allow-principal", "User:CN=writer", "--operation", "Write", "--operation",
                          "Describe", "--topic", TOPIC)
            command("acls", "admin", "--add", *writer_acl)
            command("acls", "admin", "--add", "--allow-principal", "User:CN=reader", "--operation", "Read",
                    "--operation", "Describe", "--topic", TOPIC)
            command("acls", "admin", "--add", "--allow-principal", "User:CN=reader", "--operation", "Read",
                    "--group", GROUP)
            command("console-producer", "writer", "--sync", "--topic", TOPIC, input_text="synthetic-event\n")
            def consume(identity, group=GROUP, expected=None):
                return command("console-consumer", identity, "--topic", TOPIC, "--group", group,
                               "--from-beginning", "--max-messages", "1", "--timeout-ms", "8000", expected=expected)
            require(consume("reader").strip() == "synthetic-event", "TLS_KAFKA_CONSUMER_FAILED")
            command("console-producer", "outsider", "--sync", "--topic", TOPIC,
                    input_text="denied\n", expected="TopicAuthorizationException")
            command("console-producer", "writer", "--sync", "--topic", "other-events",
                    input_text="denied\n", expected="TopicAuthorizationException")
            command("console-producer", "reader", "--sync", "--topic", TOPIC,
                    input_text="denied\n", expected=("TopicAuthorizationException", "ClusterAuthorizationException"))
            consume("reader", group="foreign-group", expected="GroupAuthorizationException")
            command("acls", "writer", "--add", "--allow-principal", "User:CN=writer", "--operation", "All",
                    "--cluster", expected="ClusterAuthorizationException")
            command("acls", "admin", "--remove", "--force", *writer_acl)
            command("console-producer", "writer", "--sync", "--topic", TOPIC,
                    input_text="revoked\n", expected="TopicAuthorizationException")
            for identity in ("missing-client", "rogue", "untrusted-server"):
                command("topics", identity, "--list", expected="SSLHandshakeException")
            command("topics", "admin", "--list", address="127.0.0.2", expected="SSLHandshakeException")
            # A healthy positive control prevents outages from masquerading as authorization denial.
            command("topics", "admin", "--describe", "--topic", TOPIC)
            return {"schema_version": 1, "image": pin["image"], "version": pin["version"],
                    "native_tls": "Passed", "mutual_client_auth": "Passed", "hostname_validation": "Passed",
                    "topic_role_group_acl": "Passed", "default_deny": "Passed", "acl_admin_denial": "Passed",
                    "writer_revocation": "Passed", "acks_all_idempotent_produce_consume": "Passed",
                    "rendered_runtime_probe": "Passed", "single_node_quorum": "Passed",
                    "probe_cancellation": "Passed",
                    "network": "none", "credentials": "synthetic-disposable-only",
                    "staging": "Not run", "deployment": "Not run", "production_readiness": "Not verified"}
        finally:
            removed = docker("rm", "--volumes", "-f", container, timeout=15, docker_host=docker_host)
            require(removed.returncode == 0, "DISPOSABLE_TLS_KAFKA_CLEANUP_FAILED")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--disposable", required=True, action="store_true")
    parser.parse_args()
    try:
        endpoint = docker("context", "inspect", "--format", "{{json .Endpoints.docker.Host}}")
        require(endpoint.returncode == 0, "LOCAL_DOCKER_REQUIRED")
        result = exercise(json.loads(endpoint.stdout))
    except (RuntimeError, OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        reason = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        parser.exit(1, "KAFKA_TLS_ACL=Failed; reason=" + reason + "; no VPS or persistent data changes\n")
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
