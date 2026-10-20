"""Run one isolated KRaft produce/consume smoke; no registry or target access."""
from __future__ import annotations

import os
import re
import subprocess
import tempfile
import time
from pathlib import Path

from test_postgresql_isolation_native import docker


def require(condition: bool, reason: str) -> None:
    if not condition:
        raise RuntimeError(reason)


def exercise(image: str, docker_host: str) -> dict:
    require(bool(re.fullmatch(r"sha256:[a-f0-9]{64}", image)), "IMMUTABLE_KAFKA_IMAGE_REQUIRED")
    require(docker_host.startswith("unix://") and os.environ.get("DOCKER_HOST", "unix://").startswith("unix://"),
            "LOCAL_UNIX_DOCKER_REQUIRED")
    with tempfile.TemporaryDirectory(prefix="hooshix-kafka-smoke-") as temporary:
        directory = Path(temporary)
        directory.chmod(0o755)  # Public synthetic configuration only; data stays in container tmpfs.
        config = directory / "server.properties"
        config.write_text("""process.roles=broker,controller
node.id=1
controller.quorum.voters=1@127.0.0.1:9093
listeners=PLAINTEXT://127.0.0.1:9092,CONTROLLER://127.0.0.1:9093
advertised.listeners=PLAINTEXT://127.0.0.1:9092
controller.listener.names=CONTROLLER
inter.broker.listener.name=PLAINTEXT
listener.security.protocol.map=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
log.dirs=/var/lib/kafka/data
metadata.log.dir=/var/lib/kafka/data
num.partitions=1
default.replication.factor=1
min.insync.replicas=1
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
unclean.leader.election.enable=false
auto.create.topics.enable=false
group.initial.rebalance.delay.ms=0
""")
        config.chmod(0o644)
        start = "cluster_id=$(/opt/kafka/bin/kafka-storage.sh random-uuid); " \
                "/opt/kafka/bin/kafka-storage.sh format -t \"$cluster_id\" " \
                "-c /run/kafka/server.properties; " \
                "exec /opt/kafka/bin/kafka-server-start.sh /run/kafka/server.properties"
        result = docker("run", "-d", "--network", "none", "--read-only", "--cap-drop", "ALL",
                        "--security-opt", "no-new-privileges", "--memory", "2g", "--cpus", "1",
                        "--hostname", "hooshix-kafka-smoke", "--add-host",
                        "hooshix-kafka-smoke:127.0.0.1",
                        "--pids-limit", "128", "--tmpfs",
                        "/var/lib/kafka/data:rw,nosuid,nodev,noexec,size=256m,uid=1000,gid=1000",
                        "--tmpfs", "/opt/kafka/logs:rw,nosuid,nodev,noexec,size=16m,uid=1000,gid=0",
                        "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=32m,uid=1000,gid=0",
                        "--mount", f"type=bind,src={config},dst=/run/kafka/server.properties,readonly",
                        "-e", "KAFKA_HEAP_OPTS=-Xms128m -Xmx256m", "--entrypoint", "/bin/sh",
                        image, "-ec", start, timeout=30, docker_host=docker_host)
        container = result.stdout.strip()
        require(result.returncode == 0 and bool(re.fullmatch(r"[a-f0-9]{64}", container)),
                "DISPOSABLE_KAFKA_START_FAILED")
        try:
            deadline = time.monotonic() + 120
            probe = ["/opt/kafka/bin/kafka-broker-api-versions.sh", "--bootstrap-server", "127.0.0.1:9092"]
            while time.monotonic() < deadline:
                try:
                    ready = docker("exec", container, *probe, timeout=20, docker_host=docker_host)
                except subprocess.TimeoutExpired:
                    continue
                if ready.returncode == 0:
                    break
                state = docker("inspect", "--format", "{{.State.Running}}", container,
                               timeout=8, docker_host=docker_host)
                if state.returncode != 0 or state.stdout.strip() != "true":
                    oom = docker("inspect", "--format", "{{.State.OOMKilled}}", container,
                                 timeout=8, docker_host=docker_host)
                    reason = "DISPOSABLE_KAFKA_OOM" if oom.stdout.strip() == "true" else "DISPOSABLE_KAFKA_EXITED"
                    raise RuntimeError(reason)
                time.sleep(1)
            else:
                raise RuntimeError("KAFKA_KRAFT_STARTUP_DEADLINE_EXCEEDED")

            topics = docker("exec", container, "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server",
                            "127.0.0.1:9092", "--create", "--if-not-exists", "--topic",
                            "hooshix-candidate-smoke", "--partitions", "1", "--replication-factor", "1",
                            "--config", "min.insync.replicas=1", timeout=15, docker_host=docker_host)
            require(topics.returncode == 0, "KAFKA_TOPIC_SMOKE_FAILED")
            produced = docker("exec", "-i", container, "/opt/kafka/bin/kafka-console-producer.sh",
                              "--bootstrap-server", "127.0.0.1:9092", "--topic", "hooshix-candidate-smoke",
                              input_text="synthetic-event\n", timeout=15, docker_host=docker_host)
            require(produced.returncode == 0, "KAFKA_PRODUCER_SMOKE_FAILED")
            consumed = docker("exec", container, "/opt/kafka/bin/kafka-console-consumer.sh",
                              "--bootstrap-server", "127.0.0.1:9092", "--topic", "hooshix-candidate-smoke",
                              "--from-beginning", "--max-messages", "1", "--timeout-ms", "8000",
                              timeout=15, docker_host=docker_host)
            require(consumed.returncode == 0 and consumed.stdout.strip() == "synthetic-event",
                    "KAFKA_CONSUMER_SMOKE_FAILED")
            version = docker("exec", container, "/opt/kafka/bin/kafka-topics.sh", "--version",
                             timeout=20, docker_host=docker_host)
            require(version.returncode == 0 and version.stdout.strip() == "4.2.2",
                    "EXACT_KAFKA_VERSION_REQUIRED")
            return {"kraft": "Passed", "local_produce_consume": "Passed", "version": "4.2.2",
                    "network": "none", "tls_acl": "Not run", "production_readiness": "Not verified"}
        finally:
            removed = docker("rm", "-f", container, timeout=15, docker_host=docker_host)
            require(removed.returncode == 0, "DISPOSABLE_KAFKA_CLEANUP_FAILED")


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--disposable", action="store_true", required=True)
    args = parser.parse_args()
    endpoint = docker("context", "inspect", "--format", "{{json .Endpoints.docker.Host}}")
    if endpoint.returncode != 0:
        parser.exit(1, "KAFKA_NATIVE_SMOKE=Failed; local Docker unavailable\n")
    try:
        result = exercise(args.image, __import__("json").loads(endpoint.stdout))
    except (OSError, subprocess.SubprocessError, RuntimeError, ValueError) as error:
        reason = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        parser.exit(1, "KAFKA_NATIVE_SMOKE=Failed; reason=" + reason + "; no persistent data or target changes\n")
    print(__import__("json").dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
