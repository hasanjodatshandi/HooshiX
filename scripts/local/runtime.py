#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import secrets
import signal
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / ".local-runtime"
COMPOSE = ROOT / "infrastructure" / "local" / "compose.yaml"
COMPOSE_ENV = RUNTIME / "compose.env"
SECRETS_JSON = RUNTIME / "runtime-secrets.json"
KEYS = RUNTIME / "keys"
LOGS = RUNTIME / "logs"
PIDS = RUNTIME / "pids"
DATASET = RUNTIME / "compromised-password"
HOST_TIME = RUNTIME / "host-time-status"
POSTGRES_PORT = 15432
REDIS_PORT = 16379
KAFKA_PORT = 19092
LOCAL_SECRET_RE = re.compile(r"^[A-Za-z0-9_-]{32,128}$")
SERVICE_PORTS = {
    "compromised-password-service": {"grpc": 19090, "management": 19091},
    "notification-service": {"grpc": 19100, "management": 19102},
    "authorization-service": {"grpc": 19200, "management": 19202},
    "identity-service": {"grpc": 19300, "management": 19302},
    "web-bff": {"https": 18443, "management": 19402},
}
DATABASES = {
    "authorization": ("authorization_migration", "authorization_runtime", "authorization-service"),
    "identity": ("identity_migration", "identity_runtime", "identity-service"),
    "notification": ("notification_migration", "notification_runtime", "notification-service"),
    "web_bff": ("web_bff_migration", "web_bff_runtime", "web-bff"),
}
BUILD_ORDER = ["authorization-service", "compromised-password-service", "identity-service", "notification-service", "web-bff"]
START_ORDER = ["compromised-password-service", "notification-service", "authorization-service", "identity-service", "web-bff"]


def run(args: list[str], *, cwd: Path = ROOT, env: dict[str, str] | None = None,
        input_text: str | None = None, capture: bool = False, timeout: int = 120):
    return subprocess.run(args, cwd=cwd, env=env, input=input_text, text=True, check=True,
                          stdout=subprocess.PIPE if capture else None,
                          stderr=subprocess.PIPE if capture else None, timeout=timeout)


def output(args: list[str], *, cwd: Path = ROOT, timeout: int = 30) -> str:
    return run(args, cwd=cwd, capture=True, timeout=timeout).stdout.strip()


def compose_args(*args: str) -> list[str]:
    return ["docker", "compose", "--env-file", str(COMPOSE_ENV), "-f", str(COMPOSE), *args]


def ensure_dirs() -> None:
    os.umask(0o077)
    for path in (RUNTIME, KEYS, LOGS, PIDS, DATASET):
        path.mkdir(parents=True, exist_ok=True)
        path.chmod(0o700)


def verify_required_tools() -> None:
    java = subprocess.run(["java", "-version"], text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=15).stdout
    if "25.0.4" not in java:
        raise SystemExit(f"Java 25.0.4 is required; found: {java.splitlines()[0] if java else 'unknown'}")
    vendor = output(["bash", "-lc", "java -XshowSettings:properties -version 2>&1 | grep 'java.vendor =' | head -1"])
    if "Eclipse Adoptium" not in vendor and "Temurin" not in vendor:
        raise SystemExit(f"Eclipse Temurin is required; found: {vendor}")
    run(["docker", "info"], capture=True, timeout=20)
    run(["docker", "compose", "version"], capture=True, timeout=20)
    run(["openssl", "version"], capture=True, timeout=20)


def validate_local_secret(name: str, value: object) -> str:
    if not isinstance(value, str) or LOCAL_SECRET_RE.fullmatch(value) is None:
        raise SystemExit(f"Invalid local secret state for {name}; delete .local-runtime to regenerate")
    return value


def generate_runtime_secrets() -> dict[str, str]:
    ensure_dirs()
    names = ["postgres_admin", "authorization_migration", "authorization_runtime",
             "identity_migration", "identity_runtime", "notification_migration", "notification_runtime",
             "web_bff_migration", "web_bff_runtime",
             "web_bff_tls"]
    if SECRETS_JSON.exists():
        try:
            values = json.loads(SECRETS_JSON.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise SystemExit("Invalid local secret state; delete .local-runtime to regenerate") from exc
    else:
        values = {}
    if not isinstance(values, dict):
        raise SystemExit("Invalid local secret state; delete .local-runtime to regenerate")
    for name in names:
        values.setdefault(name, secrets.token_urlsafe(32))
        values[name] = validate_local_secret(name, values[name])
    SECRETS_JSON.write_text(json.dumps(values, sort_keys=True) + "\n", encoding="utf-8")
    COMPOSE_ENV.write_text(f"LOCAL_POSTGRES_ADMIN_PASSWORD={values['postgres_admin']}\n", encoding="utf-8")
    SECRETS_JSON.chmod(0o600)
    COMPOSE_ENV.chmod(0o600)
    return values


def write_symmetric_ring(name: str) -> Path:
    path = KEYS / f"{name}.properties"
    if path.exists():
        path.chmod(0o600)
        return path
    encoded = base64.b64encode(secrets.token_bytes(32)).decode("ascii")
    path.write_text(f"active_key_id=local-k1\nkey.local-k1={encoded}\n", encoding="utf-8")
    path.chmod(0o600)
    return path


def generate_key_material(values: dict[str, str]) -> dict[str, Path]:
    ensure_dirs()
    paths = {name: write_symmetric_ring(name) for name in (
        "authorization-intent", "authorization-quota", "identity-fingerprint",
        "identity-challenge", "identity-handoff", "identity-mfa", "identity-refresh", "identity-quota",
        "notification-fingerprint", "notification-delivery", "web-bff-locator",
        "web-bff-csrf", "web-bff-refresh", "web-bff-quota")}
    private_path = KEYS / "identity-jwt-private.properties"
    public_path = KEYS / "identity-jwt-public.properties"
    if private_path.exists() != public_path.exists():
        raise SystemExit("Incomplete local Identity JWT material; delete .local-runtime/keys to regenerate")
    if not private_path.exists():
        pem = RUNTIME / "identity-jwt-local.pem"
        private_der = RUNTIME / "identity-jwt-private.der"
        public_der = RUNTIME / "identity-jwt-public.der"
        for path in (pem, private_der, public_der):
            path.unlink(missing_ok=True)
        try:
            run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:3072", "-out", str(pem)], capture=True)
            run(["openssl", "pkcs8", "-topk8", "-nocrypt", "-in", str(pem), "-outform", "DER", "-out", str(private_der)], capture=True)
            run(["openssl", "pkey", "-in", str(pem), "-pubout", "-outform", "DER", "-out", str(public_der)], capture=True)
            private_b64 = base64.b64encode(private_der.read_bytes()).decode("ascii")
            public_b64 = base64.b64encode(public_der.read_bytes()).decode("ascii")
            private_path.write_text(f"active_key_id=local-jwt-k1\nkey.local-jwt-k1={private_b64}\n", encoding="utf-8")
            public_path.write_text(f"current_key_id=local-jwt-k1\nkey.local-jwt-k1={public_b64}\n", encoding="utf-8")
        finally:
            for path in (pem, private_der, public_der):
                path.unlink(missing_ok=True)
    private_path.chmod(0o600)
    public_path.chmod(0o600)
    paths["identity-jwt-private"] = private_path
    paths["identity-jwt-public"] = public_path

    tls_dir = RUNTIME / "tls"
    tls_dir.mkdir(parents=True, exist_ok=True)
    tls_dir.chmod(0o700)
    tls_key = tls_dir / "web-bff.key"
    tls_cert = tls_dir / "web-bff.crt"
    tls_p12 = tls_dir / "web-bff.p12"
    if tls_cert.exists() != tls_p12.exists():
        raise SystemExit("Incomplete local Web BFF TLS material; delete .local-runtime/tls to regenerate")
    if not tls_p12.exists():
        tls_key.unlink(missing_ok=True)
        try:
            run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-nodes", "-days", "30",
                 "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1",
                 "-keyout", str(tls_key), "-out", str(tls_cert)], capture=True)
            run(["openssl", "pkcs12", "-export", "-out", str(tls_p12), "-inkey", str(tls_key),
                 "-in", str(tls_cert), "-name", "hooshix-local", "-passout", f"pass:{values['web_bff_tls']}"], capture=True)
        finally:
            tls_key.unlink(missing_ok=True)
    tls_p12.chmod(0o600)
    tls_cert.chmod(0o644)
    paths["web-bff-tls"] = tls_p12
    paths["web-bff-tls-cert"] = tls_cert
    HOST_TIME.write_text("synchronized\n", encoding="utf-8")
    HOST_TIME.chmod(0o600)
    return paths


def build_services() -> None:
    for service in BUILD_ORDER:
        print(f"build {service}", flush=True)
        run(["./gradlew", "--no-daemon", "bootJar"], cwd=ROOT / "services" / service, timeout=120)


def service_jar(service: str) -> Path:
    jars = [path for path in sorted((ROOT / "services" / service / "build" / "libs").glob(f"{service}-*.jar"))
            if "plain" not in path.name]
    if len(jars) != 1:
        raise SystemExit(f"Expected one Boot JAR for {service}; found {len(jars)}")
    return jars[0]


def start_dependencies() -> None:
    run(compose_args("up", "-d", "postgres", "redis", "kafka"), timeout=120)
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        pg = subprocess.run(compose_args("exec", "-T", "postgres", "pg_isready", "-U", "postgres", "-d", "postgres"),
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        redis = subprocess.run(compose_args("exec", "-T", "redis", "redis-cli", "ping"),
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        kafka = subprocess.run(
            compose_args("exec", "-T", "kafka", "/opt/kafka/bin/kafka-broker-api-versions.sh",
                         "--bootstrap-server", "localhost:9092"),
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if pg.returncode == 0 and redis.returncode == 0 and kafka.returncode == 0:
            return
        time.sleep(1)
    raise SystemExit("Local PostgreSQL/Redis/Kafka did not become ready")


def provision_kafka_topics() -> None:
    topic_configs = {
        "hooshix.identity.erasure.command.v1": 35 * 24 * 60 * 60 * 1000,
        "hooshix.identity.erasure.receipt.v1": 35 * 24 * 60 * 60 * 1000,
        "hooshix.identity.erasure.command.v1.DLT": 14 * 24 * 60 * 60 * 1000,
        "hooshix.identity.erasure.receipt.v1.DLT": 14 * 24 * 60 * 60 * 1000,
    }
    for topic, retention_ms in topic_configs.items():
        run(
            compose_args(
                "exec", "-T", "kafka", "/opt/kafka/bin/kafka-topics.sh",
                "--bootstrap-server", "localhost:9092", "--create", "--if-not-exists",
                "--topic", topic, "--partitions", "1", "--replication-factor", "1",
                "--config", f"retention.ms={retention_ms}", "--config", "cleanup.policy=delete"),
            timeout=30)


def psql(sql: str, *, database: str = "postgres") -> None:
    run(compose_args("exec", "-T", "postgres", "psql", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", database),
        input_text=sql, timeout=30)


def psql_query(sql: str, *, database: str = "postgres") -> str:
    return run(
        compose_args(
            "exec", "-T", "postgres", "psql", "-At", "-v", "ON_ERROR_STOP=1",
            "-U", "postgres", "-d", database),
        input_text=sql, capture=True, timeout=30).stdout.strip()


def erasure_smoke_seed_sql(user_id: uuid.UUID, request_id: uuid.UUID,
                           event_id: uuid.UUID) -> str:
    return f"""
INSERT INTO identity_user(user_id,status,created_at,updated_at)
VALUES ('{user_id}','DELETING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO identity_erasure_request(
  erasure_request_id,user_id,state,participant_policy_version,accepted_at,updated_at)
VALUES ('{request_id}','{user_id}','IN_PROGRESS','1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO identity_erasure_participant(erasure_request_id,participant,state,updated_at)
SELECT '{request_id}',participant,'PENDING',CURRENT_TIMESTAMP
FROM unnest(ARRAY[
  'IDENTITY_SERVICE','AUTHORIZATION_SERVICE','NOTIFICATION_SERVICE','WEB_BFF'
]::text[]) AS participant;
INSERT INTO identity_erasure_event_outbox(
  event_id,erasure_request_id,event_type,participant_policy_version,state,attempt_count,
  next_attempt_at,occurred_at,retain_until,updated_at)
VALUES (
  '{event_id}','{request_id}','COMMAND','1','PENDING',0,
  CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '35 days',CURRENT_TIMESTAMP);
"""


def wait_for_erasure(user_id: uuid.UUID, request_id: uuid.UUID, timeout_seconds: int) -> None:
    expected_participants = (
        "AUTHORIZATION_SERVICE:COMPLETED,IDENTITY_SERVICE:COMPLETED,"
        "NOTIFICATION_SERVICE:COMPLETED,WEB_BFF:COMPLETED")
    query = f"""
SELECT r.state || '|' || u.status || '|' ||
       string_agg(p.participant || ':' || p.state, ',' ORDER BY p.participant)
FROM identity_erasure_request r
JOIN identity_user u ON u.user_id=r.user_id
JOIN identity_erasure_participant p ON p.erasure_request_id=r.erasure_request_id
WHERE r.erasure_request_id='{request_id}'
GROUP BY r.state,u.status;
"""
    deadline = time.monotonic() + max(1, timeout_seconds)
    last = "missing"
    while time.monotonic() < deadline:
        last = psql_query(query, database="identity") or "missing"
        if last == f"COMPLETED|DELETED|{expected_participants}":
            return
        time.sleep(1)
    raise SystemExit(
        f"Erasure Kafka smoke did not complete for request {request_id}; last state: {last}")


def verify_erasure_participant_evidence(request_id: uuid.UUID) -> None:
    checks = {
        "authorization": (
            "authorization_erasure_inbox", "authorization_erasure_evidence"),
        "identity": ("identity_erasure_command_inbox", "identity_erasure_evidence"),
        "notification": ("notification_erasure_inbox", "notification_erasure_evidence"),
        "web_bff": ("web_bff_erasure_inbox", "web_bff_erasure_evidence"),
    }
    for database, (inbox, evidence) in checks.items():
        result = psql_query(
            f"""
SELECT
  (SELECT count(*) FROM {inbox}
   WHERE erasure_request_id='{request_id}' AND state='COMPLETED') || '|' ||
  (SELECT count(*) FROM {evidence}
   WHERE erasure_request_id='{request_id}' AND event_code='ERASURE_COMPLETED');
""",
            database=database,
        )
        if result != "1|1":
            raise SystemExit(
                f"Erasure participant evidence is incomplete for {database}; aggregate={result}")


def smoke_erasure(timeout_seconds: int = 60) -> None:
    status(require_ready=True)
    user_id, request_id, event_id = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
    psql(erasure_smoke_seed_sql(user_id, request_id, event_id), database="identity")
    wait_for_erasure(user_id, request_id, timeout_seconds)
    verify_erasure_participant_evidence(request_id)
    print(json.dumps({
        "erasure_request_id": str(request_id),
        "event_id": str(event_id),
        "state": "COMPLETED",
        "user_state": "DELETED",
        "participants": 4,
    }, indent=2))


def pg_dump_args(database: str) -> list[str]:
    if database not in DATABASES:
        raise ValueError("database is outside the service-owned local restore set")
    return compose_args(
        "exec", "-T", "postgres", "pg_dump", "-U", "postgres", "-d", database,
        "--format=plain", "--clean", "--if-exists")


def dump_erasure_restore_databases(destination: Path) -> dict[str, Path]:
    snapshots: dict[str, Path] = {}
    for database in DATABASES:
        path = destination / f"{database}.sql"
        with path.open("w", encoding="utf-8") as handle:
            subprocess.run(
                pg_dump_args(database),
                cwd=ROOT,
                text=True,
                check=True,
                stdout=handle,
                stderr=subprocess.PIPE,
                timeout=120,
            )
        path.chmod(0o600)
        snapshots[database] = path
    return snapshots


def restore_erasure_databases(snapshots: dict[str, Path]) -> None:
    if set(snapshots) != set(DATABASES):
        raise ValueError("restore snapshot set does not match service-owned databases")
    for database in DATABASES:
        path = snapshots[database]
        if not path.is_file() or path.stat().st_mode & 0o077:
            raise ValueError("restore snapshot is missing or has unsafe permissions")
        with path.open("r", encoding="utf-8") as handle:
            subprocess.run(
                compose_args(
                    "exec", "-T", "postgres", "psql", "-X", "-v", "ON_ERROR_STOP=1",
                    "-U", "postgres", "-d", database),
                cwd=ROOT,
                text=True,
                check=True,
                stdin=handle,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
                timeout=120,
            )


def smoke_erasure_recovery(timeout_seconds: int = 120) -> None:
    status(require_ready=True)
    user_id, request_id, event_id = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
    stop_processes()
    psql(erasure_smoke_seed_sql(user_id, request_id, event_id), database="identity")
    with tempfile.TemporaryDirectory(prefix="erasure-restore-", dir=RUNTIME) as temp_dir:
        snapshots = dump_erasure_restore_databases(Path(temp_dir))

        up(skip_build=True)
        wait_for_erasure(user_id, request_id, timeout_seconds)
        verify_erasure_participant_evidence(request_id)

        up(skip_build=True)
        wait_for_erasure(user_id, request_id, timeout_seconds)
        verify_erasure_participant_evidence(request_id)

        stop_processes()
        restore_erasure_databases(snapshots)
        up(skip_build=True)
        wait_for_erasure(user_id, request_id, timeout_seconds)
        verify_erasure_participant_evidence(request_id)

    revision = output(["git", "rev-parse", "HEAD"])
    print(json.dumps({
        "schema": "hooshix-local-erasure-recovery-v1",
        "git_revision": revision,
        "environment": "developer-local",
        "redeploy_completed": True,
        "restore_reconciliation_completed": True,
        "participant_count": 4,
        "identity_deleted": True,
        "no_reappearance": True,
        "passed": True,
    }, indent=2))


def provision_databases(values: dict[str, str]) -> None:
    statements = []
    for _, (migration, runtime, _) in DATABASES.items():
        for role in (migration, runtime):
            password = values[role]
            statements.append(
                "DO $$ BEGIN "
                f"IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='{role}') THEN "
                f"CREATE ROLE {role} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD '{password}'; "
                "ELSE "
                f"ALTER ROLE {role} WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD '{password}'; "
                "END IF; END $$;")
    psql("\n".join(statements) + "\n")
    for database, (migration, runtime, _) in DATABASES.items():
        psql(f"SELECT 'CREATE DATABASE \"{database}\" OWNER {migration}' WHERE NOT EXISTS "
             f"(SELECT 1 FROM pg_database WHERE datname='{database}')\\gexec\n"
             f"ALTER DATABASE \"{database}\" OWNER TO {migration};\n"
             f"REVOKE CONNECT ON DATABASE \"{database}\" FROM PUBLIC;\n"
             f"GRANT CONNECT ON DATABASE \"{database}\" TO {migration};\n"
             f"GRANT CONNECT ON DATABASE \"{database}\" TO {runtime};\n")
        psql("REVOKE CREATE ON SCHEMA public FROM PUBLIC;\n", database=database)


def migration_env(service: str, database: str, role: str, password: str) -> dict[str, str]:
    env = os.environ.copy()
    env.update({
        "SPRING_PROFILES_ACTIVE": "migration",
        "SPRING_DATASOURCE_URL": f"jdbc:postgresql://127.0.0.1:{POSTGRES_PORT}/{database}",
        "SPRING_DATASOURCE_USERNAME": role,
        "SPRING_DATASOURCE_PASSWORD": password,
        "MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED": "false",
        "MANAGEMENT_TRACING_ENABLED": "false",
    })
    if service == "authorization-service":
        env.update({
            "AUTHORIZATION_DATABASE_URL": env["SPRING_DATASOURCE_URL"],
            "AUTHORIZATION_DATABASE_USERNAME": role,
            "AUTHORIZATION_DATABASE_PASSWORD": password,
        })
    elif service == "identity-service":
        env["IDENTITY_DATABASE_URL"] = env["SPRING_DATASOURCE_URL"]
    elif service == "notification-service":
        env["NOTIFICATION_DATABASE_URL"] = env["SPRING_DATASOURCE_URL"]
    elif service == "web-bff":
        env.update({
            "WEB_BFF_DATABASE_URL": env["SPRING_DATASOURCE_URL"],
            "WEB_BFF_DATABASE_USERNAME": role,
            "WEB_BFF_DATABASE_PASSWORD": password,
        })
    return env


def migrate_databases(values: dict[str, str]) -> None:
    for database, (migration_role, _, service) in DATABASES.items():
        log_path = LOGS / f"{service}-migration.log"
        with log_path.open("w", encoding="utf-8") as log:
            completed = subprocess.run(
                ["java", "-jar", str(service_jar(service)), "--spring.profiles.active=migration"],
                cwd=ROOT / "services" / service,
                env=migration_env(service, database, migration_role, values[migration_role]),
                stdout=log, stderr=subprocess.STDOUT, text=True, timeout=45)
        if completed.returncode != 0:
            raise SystemExit(f"Migration failed for {service}; inspect {log_path}")
    for database, (_, runtime_role, _) in DATABASES.items():
        psql(f"GRANT USAGE ON SCHEMA public TO {runtime_role};\n"
             f"GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO {runtime_role};\n"
             f"GRANT USAGE,SELECT,UPDATE ON ALL SEQUENCES IN SCHEMA public TO {runtime_role};\n",
             database=database)
        if database == "notification":
            template_privilege_sql = (
                "REVOKE ALL PRIVILEGES ON TABLE "
                "notification_template_definition, notification_template_version, "
                "notification_template_activation, notification_template_audit "
                "FROM notification_runtime;"
                + chr(10)
                + "GRANT SELECT ON TABLE "
                "notification_template_definition, notification_template_version, "
                "notification_template_activation, notification_template_audit "
                "TO notification_runtime;"
                + chr(10)
            )
            psql(template_privilege_sql, database=database)


def build_compromised_password_dataset() -> dict[str, str]:
    source = DATASET / "source.txt"
    database = DATASET / "compromised-password.sqlite"
    manifest = DATASET / "compromised-password.manifest.json"
    digest = hashlib.sha1(b"hooshix-local-generated-fixture", usedforsecurity=False).hexdigest().upper()
    source.write_text(f"{digest}:1\n", encoding="ascii")
    source_sha = hashlib.sha256(source.read_bytes()).hexdigest()
    revision = output(["git", "rev-parse", "HEAD"])
    now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    tool_sha = hashlib.sha256(b"hooshix-local-runtime").hexdigest()
    database.unlink(missing_ok=True)
    manifest.unlink(missing_ok=True)
    args = " ".join([
        "--source-kind GENERATED_TEST_FIXTURE", f"--input {source}", f"--output {database}",
        f"--manifest {manifest}", f"--source-sha256 {source_sha}", f"--retrieval-started-at {now}",
        f"--retrieval-completed-at {now}", "--acquisition-tool-name hooshix-local-runtime",
        "--acquisition-tool-version 1.0.0", f"--acquisition-tool-sha256 {tool_sha}",
        f"--build-git-revision {revision}", "--max-prefix-cardinality 16",
        "--max-serialized-response-bytes 4096"])
    run(["./gradlew", "--no-daemon", "buildCompromisedPasswordDataset", f"--args={args}"],
        cwd=ROOT / "services" / "compromised-password-service", timeout=90)
    return {"database": str(database), "manifest": str(manifest),
            "manifest_sha": hashlib.sha256(manifest.read_bytes()).hexdigest()}


def stop_processes() -> None:
    if not PIDS.exists():
        return
    records = []
    for path in sorted(PIDS.glob("*.pid")):
        try:
            records.append((path.stem, int(path.read_text(encoding="ascii").strip())))
        except (ValueError, OSError):
            path.unlink(missing_ok=True)
    for service, pid in reversed(records):
        alive, verified_pid = pid_alive(service)
        if not alive or verified_pid != pid:
            continue
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        alive = []
        for name, pid in records:
            is_alive, verified_pid = pid_alive(name)
            if is_alive and verified_pid == pid:
                alive.append((name, pid))
        if not alive:
            break
        time.sleep(0.25)
    for service, pid in records:
        alive, verified_pid = pid_alive(service)
        if not alive or verified_pid != pid:
            continue
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    for path in PIDS.glob("*.pid"):
        path.unlink(missing_ok=True)


def start_process(service: str, env: dict[str, str]) -> None:
    log_path = LOGS / f"{service}.log"
    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(["java", "-jar", str(service_jar(service))],
                                   cwd=ROOT / "services" / service, env=env,
                                   stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                   text=True, start_new_session=True)
    pid_file = PIDS / f"{service}.pid"
    pid_file.write_text(f"{process.pid}\n", encoding="ascii")
    pid_file.chmod(0o600)


def runtime_envs(values: dict[str, str], keys: dict[str, Path], dataset: dict[str, str]) -> dict[str, dict[str, str]]:
    base = os.environ.copy()
    base.update({
        "MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED": "false",
        "MANAGEMENT_TRACING_ENABLED": "false",
        "MANAGEMENT_SERVER_ADDRESS": "127.0.0.1",
    })

    cp = base.copy()
    cp.update({
        "SPRING_PROFILES_ACTIVE": "local",
        "HOOSHIX_COMPROMISED_PASSWORD_GRPC_PORT": str(SERVICE_PORTS["compromised-password-service"]["grpc"]),
        "HOOSHIX_COMPROMISED_PASSWORD_GRPC_BIND_ADDRESS": "127.0.0.1",
        "HOOSHIX_COMPROMISED_PASSWORD_MAX_CONCURRENT_LOOKUPS": "4",
        "HOOSHIX_COMPROMISED_PASSWORD_DATASET_PATH": dataset["database"],
        "HOOSHIX_COMPROMISED_PASSWORD_DATASET_MANIFEST_PATH": dataset["manifest"],
        "HOOSHIX_COMPROMISED_PASSWORD_DATASET_EXPECTED_MANIFEST_SHA256": dataset["manifest_sha"],
        "HOOSHIX_COMPROMISED_PASSWORD_DATASET_REQUIRED_SOURCE_KIND": "GENERATED_TEST_FIXTURE",
        "HOOSHIX_COMPROMISED_PASSWORD_DATASET_MAX_PREFIX_CARDINALITY": "16",
        "HOOSHIX_COMPROMISED_PASSWORD_DATASET_MAX_SERIALIZED_RESPONSE_BYTES": "4096",
        "MANAGEMENT_SERVER_PORT": str(SERVICE_PORTS["compromised-password-service"]["management"]),
    })

    notification = base.copy()
    notification.update({
        "SPRING_PROFILES_ACTIVE": "local",
        "NOTIFICATION_DATABASE_URL": f"jdbc:postgresql://127.0.0.1:{POSTGRES_PORT}/notification",
        "SPRING_DATASOURCE_USERNAME": "notification_runtime",
        "SPRING_DATASOURCE_PASSWORD": values["notification_runtime"],
        "NOTIFICATION_GRPC_PORT": str(SERVICE_PORTS["notification-service"]["grpc"]),
        "NOTIFICATION_GRPC_BIND_ADDRESS": "127.0.0.1",
        "NOTIFICATION_FINGERPRINT_KEY_RING_PATH": str(keys["notification-fingerprint"]),
        "NOTIFICATION_DELIVERY_KEY_RING_PATH": str(keys["notification-delivery"]),
        "NOTIFICATION_ERASURE_RUNTIME_ENABLED": "true",
        "NOTIFICATION_IDENTITY_ERASURE_TARGET": f"dns:///localhost:{SERVICE_PORTS['identity-service']['grpc']}",
        "KAFKA_BOOTSTRAP_SERVERS": f"127.0.0.1:{KAFKA_PORT}",
        "MANAGEMENT_SERVER_PORT": str(SERVICE_PORTS["notification-service"]["management"]),
    })

    authorization = base.copy()
    authorization.update({
        "SPRING_PROFILES_ACTIVE": "local",
        "AUTHORIZATION_DATABASE_URL": f"jdbc:postgresql://127.0.0.1:{POSTGRES_PORT}/authorization",
        "AUTHORIZATION_DATABASE_USERNAME": "authorization_runtime",
        "AUTHORIZATION_DATABASE_PASSWORD": values["authorization_runtime"],
        "AUTHORIZATION_GRPC_PORT": str(SERVICE_PORTS["authorization-service"]["grpc"]),
        "AUTHORIZATION_GRPC_BIND_ADDRESS": "127.0.0.1",
        "AUTHORIZATION_RUNTIME_ENABLED": "true",
        "AUTHORIZATION_FINGERPRINT_KEY_RING_PATH": str(keys["authorization-intent"]),
        "AUTHORIZATION_QUOTA_KEY_RING_PATH": str(keys["authorization-quota"]),
        "AUTHORIZATION_IDENTITY_JWT_VERIFIER_BUNDLE_PATH": str(keys["identity-jwt-public"]),
        "AUTHORIZATION_QUOTA_REDIS_URI": f"redis://127.0.0.1:{REDIS_PORT}",
        "AUTHORIZATION_HOST_TIME_STATUS_PATH": str(HOST_TIME),
        "AUTHORIZATION_ERASURE_RUNTIME_ENABLED": "true",
        "AUTHORIZATION_IDENTITY_ERASURE_TARGET": f"dns:///localhost:{SERVICE_PORTS['identity-service']['grpc']}",
        "KAFKA_BOOTSTRAP_SERVERS": f"127.0.0.1:{KAFKA_PORT}",
        "MANAGEMENT_SERVER_PORT": str(SERVICE_PORTS["authorization-service"]["management"]),
    })

    identity = base.copy()
    identity.update({
        "SPRING_PROFILES_ACTIVE": "local",
        "IDENTITY_DATABASE_URL": f"jdbc:postgresql://127.0.0.1:{POSTGRES_PORT}/identity",
        "SPRING_DATASOURCE_USERNAME": "identity_runtime",
        "SPRING_DATASOURCE_PASSWORD": values["identity_runtime"],
        "IDENTITY_GRPC_PORT": str(SERVICE_PORTS["identity-service"]["grpc"]),
        "IDENTITY_GRPC_BIND_ADDRESS": "127.0.0.1",
        "IDENTITY_REGISTRATION_RUNTIME_ENABLED": "true",
        "IDENTITY_AUTHENTICATION_RUNTIME_ENABLED": "true",
        "IDENTITY_TENANT_RUNTIME_ENABLED": "true",
        "IDENTITY_ERASURE_RUNTIME_ENABLED": "true",
        "IDENTITY_PHONE_REGISTRATION_ENABLED": "true",
        "IDENTITY_COMPROMISED_PASSWORD_TARGET": f"dns:///localhost:{SERVICE_PORTS['compromised-password-service']['grpc']}",
        "IDENTITY_NOTIFICATION_TARGET": f"dns:///localhost:{SERVICE_PORTS['notification-service']['grpc']}",
        "IDENTITY_AUTHORIZATION_TARGET": f"dns:///localhost:{SERVICE_PORTS['authorization-service']['grpc']}",
        "IDENTITY_NOTIFICATION_DISPATCH_ENABLED": "true",
        "IDENTITY_FINGERPRINT_KEY_RING_PATH": str(keys["identity-fingerprint"]),
        "IDENTITY_CHALLENGE_KEY_RING_PATH": str(keys["identity-challenge"]),
        "IDENTITY_HANDOFF_KEY_RING_PATH": str(keys["identity-handoff"]),
        "IDENTITY_MFA_KEY_RING_PATH": str(keys["identity-mfa"]),
        "IDENTITY_REFRESH_KEY_RING_PATH": str(keys["identity-refresh"]),
        "IDENTITY_QUOTA_KEY_RING_PATH": str(keys["identity-quota"]),
        "IDENTITY_QUOTA_HOST_TIME_STATUS_PATH": str(HOST_TIME),
        "IDENTITY_ARGON2_MAX_CONCURRENT_HASHES": "2",
        "IDENTITY_QUOTA_MAX_ACTIVE_BUCKETS": "10000",
        "IDENTITY_QUOTA_MAX_NEW_BUCKETS_PER_MINUTE": "1000",
        "QUOTA_REDIS_URI": f"redis://127.0.0.1:{REDIS_PORT}",
        "IDENTITY_JWT_PRIVATE_KEY_RING_PATH": str(keys["identity-jwt-private"]),
        "IDENTITY_JWT_PUBLIC_VERIFIER_BUNDLE_PATH": str(keys["identity-jwt-public"]),
        "IDENTITY_JWT_ALLOWED_AUDIENCES": "authorization-service",
        "KAFKA_BOOTSTRAP_SERVERS": f"127.0.0.1:{KAFKA_PORT}",
        "MANAGEMENT_SERVER_PORT": str(SERVICE_PORTS["identity-service"]["management"]),
    })

    bff = base.copy()
    bff.update({
        "SPRING_PROFILES_ACTIVE": "local",
        "WEB_BFF_RUNTIME_ENABLED": "true",
        "WEB_BFF_ERASURE_RUNTIME_ENABLED": "true",
        "WEB_BFF_DATABASE_URL": f"jdbc:postgresql://127.0.0.1:{POSTGRES_PORT}/web_bff",
        "WEB_BFF_DATABASE_USERNAME": "web_bff_runtime",
        "WEB_BFF_DATABASE_PASSWORD": values["web_bff_runtime"],
        "WEB_BFF_HTTP_PORT": str(SERVICE_PORTS["web-bff"]["https"]),
        "WEB_BFF_MANAGEMENT_PORT": str(SERVICE_PORTS["web-bff"]["management"]),
        "WEB_BFF_PUBLIC_ORIGIN": f"https://localhost:{SERVICE_PORTS['web-bff']['https']}",
        "SERVER_ADDRESS": "127.0.0.1",
        "SERVER_SSL_ENABLED": "true",
        "SERVER_SSL_KEY_STORE": f"file:{keys['web-bff-tls']}",
        "SERVER_SSL_KEY_STORE_PASSWORD": values["web_bff_tls"],
        "SERVER_SSL_KEY_STORE_TYPE": "PKCS12",
        "SERVER_SSL_KEY_ALIAS": "hooshix-local",
        "MANAGEMENT_SERVER_SSL_ENABLED": "false",
        "WEB_BFF_IDENTITY_TARGET": f"dns:///localhost:{SERVICE_PORTS['identity-service']['grpc']}",
        "WEB_BFF_AUTHORIZATION_TARGET": f"dns:///localhost:{SERVICE_PORTS['authorization-service']['grpc']}",
        "WEB_BFF_REDIS_URI": f"redis://127.0.0.1:{REDIS_PORT}",
        "WEB_BFF_LOCATOR_KEY_RING_PATH": str(keys["web-bff-locator"]),
        "WEB_BFF_CSRF_KEY_RING_PATH": str(keys["web-bff-csrf"]),
        "WEB_BFF_REFRESH_ENCRYPTION_KEY_RING_PATH": str(keys["web-bff-refresh"]),
        "WEB_BFF_QUOTA_KEY_RING_PATH": str(keys["web-bff-quota"]),
        "WEB_BFF_OIDC_QUOTA_HOST_TIME_STATUS_PATH": str(HOST_TIME),
        "WEB_BFF_OIDC_QUOTA_MAX_ACTIVE_BUCKETS": "10000",
        "WEB_BFF_OIDC_QUOTA_MAX_NEW_BUCKETS_PER_MINUTE": "1000",
        "KAFKA_BOOTSTRAP_SERVERS": f"127.0.0.1:{KAFKA_PORT}",
    })
    return {
        "compromised-password-service": cp,
        "notification-service": notification,
        "authorization-service": authorization,
        "identity-service": identity,
        "web-bff": bff,
    }


def http_health(port: int, group: str = "readiness") -> tuple[bool, str]:
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/actuator/health/{group}", timeout=2) as response:
            body = response.read(4096).decode("utf-8", errors="replace")
            return response.status == 200 and '"status":"UP"' in body, body
    except (urllib.error.URLError, TimeoutError, ConnectionError) as exc:
        return False, str(exc)


def service_command_matches(service: str, argv: list[str]) -> bool:
    if len(argv) < 3 or Path(argv[0]).name != "java" or argv[1] != "-jar":
        return False
    jar = Path(argv[2])
    expected_dir = ROOT / "services" / service / "build" / "libs"
    return jar.parent == expected_dir and jar.name.startswith(service + "-") and jar.suffix == ".jar"


def process_matches_service(service: str, pid: int) -> bool:
    try:
        raw = Path(f"/proc/{pid}/cmdline").read_bytes()
    except OSError:
        return False
    argv = [part.decode("utf-8", errors="strict") for part in raw.split(b"\0") if part]
    return service_command_matches(service, argv)


def pid_alive(service: str) -> tuple[bool, int | None]:
    path = PIDS / f"{service}.pid"
    if not path.exists():
        return False, None
    try:
        pid = int(path.read_text(encoding="ascii").strip())
        os.kill(pid, 0)
        stat = Path(f"/proc/{pid}/stat")
        if stat.exists() and stat.read_text(encoding="ascii", errors="replace").split()[2] == "Z":
            return False, None
        if not process_matches_service(service, pid):
            return False, None
        return True, pid
    except (ValueError, OSError, IndexError, UnicodeDecodeError):
        return False, None


def grpc_reachable(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1):
            return True
    except OSError:
        return False



def wait_service(service: str, timeout_seconds: int = 35) -> None:
    deadline = time.monotonic() + timeout_seconds
    last = "no response"
    while time.monotonic() < deadline:
        alive, _ = pid_alive(service)
        if not alive:
            raise SystemExit(f"{service} exited during startup; inspect {LOGS / (service + '.log')}")
        ready, last = http_health(SERVICE_PORTS[service]["management"])
        grpc = grpc_reachable(SERVICE_PORTS[service]["grpc"]) if "grpc" in SERVICE_PORTS[service] else True
        if ready and grpc:
            return
        time.sleep(0.5)
    raise SystemExit(f"{service} did not become Ready: {last}")

def wait_services() -> None:
    pending = set(START_ORDER)
    last: dict[str, str] = {}
    deadline = time.monotonic() + 60
    while pending and time.monotonic() < deadline:
        for service in list(pending):
            alive, _ = pid_alive(service)
            if not alive:
                raise SystemExit(f"{service} exited during startup; inspect {LOGS / (service + '.log')}")
            ready, detail = http_health(SERVICE_PORTS[service]["management"])
            last[service] = detail
            if ready:
                pending.remove(service)
        if pending:
            time.sleep(1)
    if pending:
        details = "; ".join(f"{name}: {last.get(name, 'no response')}" for name in sorted(pending))
        raise SystemExit(f"Services did not become Ready: {details}")


def status(require_ready: bool = False) -> None:
    rows = []
    failed = False
    for service in START_ORDER:
        alive, pid = pid_alive(service)
        ready, _ = http_health(SERVICE_PORTS[service]["management"])
        has_grpc = "grpc" in SERVICE_PORTS[service]
        grpc = grpc_reachable(SERVICE_PORTS[service]["grpc"]) if has_grpc else True
        rows.append({"service": service, "pid": pid, "process": "UP" if alive else "DOWN",
                     "readiness": "UP" if ready else "DOWN",
                     "grpc": ("UP" if grpc else "DOWN") if has_grpc else "N/A"})
        if require_ready and (not alive or not ready or not grpc):
            failed = True
    dependencies = {"postgres": "DOWN", "redis": "DOWN", "kafka": "DOWN"}
    if COMPOSE_ENV.exists():
        for name in dependencies:
            completed = subprocess.run(compose_args("ps", "--status", "running", "--services", name),
                                       text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            if completed.returncode == 0 and name in completed.stdout.split():
                dependencies[name] = "UP"
            elif require_ready:
                failed = True
    print(json.dumps({"runtime_root": str(RUNTIME), "dependencies": dependencies,
                      "services": rows,
                      "web_bff": f"https://localhost:{SERVICE_PORTS['web-bff']['https']}"}, indent=2))
    if failed:
        raise SystemExit(1)


def up(skip_build: bool = False) -> None:
    verify_required_tools()
    ensure_dirs()
    values = generate_runtime_secrets()
    if not skip_build:
        build_services()
    stop_processes()
    start_dependencies()
    provision_kafka_topics()
    provision_databases(values)
    migrate_databases(values)
    keys = generate_key_material(values)
    dataset = build_compromised_password_dataset()
    envs = runtime_envs(values, keys, dataset)
    for service in START_ORDER:
        start_process(service, envs[service])
        wait_service(service)
    wait_services()
    status(require_ready=True)


def logs(lines: int) -> None:
    for service in START_ORDER:
        path = LOGS / f"{service}.log"
        print(f"===== {service} =====")
        if not path.exists():
            print("no log")
            continue
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines()[-lines:]:
            print(line)


def down(remove_data: bool = False) -> None:
    stop_processes()
    if COMPOSE_ENV.exists():
        args = compose_args("down")
        if remove_data:
            args.append("--volumes")
        run(args, timeout=60)
    print("HooshiX local integrated runtime stopped")


def main() -> None:
    parser = argparse.ArgumentParser(description="HooshiX local integrated runtime")
    sub = parser.add_subparsers(dest="command", required=True)
    up_parser = sub.add_parser("up")
    up_parser.add_argument("--skip-build", action="store_true")
    sub.add_parser("status")
    logs_parser = sub.add_parser("logs")
    logs_parser.add_argument("--lines", type=int, default=40)
    down_parser = sub.add_parser("down")
    down_parser.add_argument("--remove-data", action="store_true")
    erasure_parser = sub.add_parser("smoke-erasure")
    erasure_parser.add_argument("--timeout-seconds", type=int, default=60)
    recovery_parser = sub.add_parser("smoke-erasure-recovery")
    recovery_parser.add_argument("--timeout-seconds", type=int, default=120)
    args = parser.parse_args()
    if args.command == "up":
        up(args.skip_build)
    elif args.command == "status":
        status()
    elif args.command == "logs":
        logs(max(1, min(args.lines, 500)))
    elif args.command == "down":
        down(args.remove_data)
    elif args.command == "smoke-erasure":
        smoke_erasure(max(1, min(args.timeout_seconds, 300)))
    elif args.command == "smoke-erasure-recovery":
        smoke_erasure_recovery(max(30, min(args.timeout_seconds, 300)))


if __name__ == "__main__":
    main()
