#!/usr/bin/env python3
"""Run the destructive-to-test-state five-participant staging erasure recovery rehearsal."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import shutil
import subprocess
import tempfile
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTEXT = "kind-platform-local"
APP_NAMESPACE = "platform-apps"
DATA_NAMESPACE = "platform-data"
APPLICATIONS = (
    "authorization-service",
    "compromised-password-service",
    "conversation-service",
    "identity-service",
    "notification-service",
    "web-bff",
)
IMAGE_REPOSITORIES = {
    application: f"localhost:5001/hooshix/{application}" for application in APPLICATIONS
}
PARTICIPANT_DATABASES = {
    "authorization": ("authorization_erasure_inbox", "authorization_erasure_evidence"),
    "conversation": ("conversation_erasure_inbox", "conversation_erasure_evidence"),
    "identity": ("identity_erasure_command_inbox", "identity_erasure_evidence"),
    "notification": ("notification_erasure_inbox", "notification_erasure_evidence"),
    "web_bff": ("web_bff_erasure_inbox", "web_bff_erasure_evidence"),
}
STATE_ROOT = ROOT / ".platform-runtime" / "stage7"
EVIDENCE_PATH = STATE_ROOT / "staging-erasure-recovery.json"
DEFAULT_ROLLOUT_TIMEOUT_SECONDS = 120
COMPROMISED_PASSWORD_ROLLOUT_TIMEOUT_SECONDS = 7500


class RehearsalError(RuntimeError):
    pass


def _command(
    arguments: list[str],
    *,
    input_text: str | None = None,
    capture: bool = False,
    timeout: int = 120,
    stdout_file=None,
) -> str:
    try:
        completed = subprocess.run(
            arguments,
            cwd=ROOT,
            input=input_text,
            text=True,
            check=True,
            stdout=stdout_file if stdout_file is not None else (subprocess.PIPE if capture else subprocess.DEVNULL),
            stderr=subprocess.PIPE,
            timeout=timeout,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise RehearsalError("bounded staging command failed") from error
    return completed.stdout.strip() if capture and completed.stdout is not None else ""


def _kubectl(*arguments: str, input_text: str | None = None, capture: bool = False, timeout: int = 120) -> str:
    return _command(
        ["kubectl", "--context", CONTEXT, *arguments],
        input_text=input_text,
        capture=capture,
        timeout=timeout,
    )


def _psql(sql: str, database: str, *, capture: bool = False) -> str:
    if database not in {*PARTICIPANT_DATABASES, "postgres"}:
        raise ValueError("database is outside the staging rehearsal allow-list")
    return _kubectl(
        "-n",
        DATA_NAMESPACE,
        "exec",
        "-i",
        "deployment/postgresql",
        "--",
        "psql",
        "-X",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        "postgres",
        "-d",
        database,
        "-At" if capture else "-q",
        input_text=sql,
        capture=capture,
        timeout=45,
    )


def erasure_seed_sql(user_id: uuid.UUID, request_id: uuid.UUID, event_id: uuid.UUID) -> str:
    return f"""
INSERT INTO identity_user(user_id,status,created_at,updated_at)
VALUES ('{user_id}','DELETING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO identity_erasure_request(
  erasure_request_id,user_id,state,participant_policy_version,accepted_at,updated_at)
VALUES ('{request_id}','{user_id}','IN_PROGRESS','1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO identity_erasure_participant(erasure_request_id,participant,state,updated_at)
SELECT '{request_id}',participant,'PENDING',CURRENT_TIMESTAMP
FROM unnest(ARRAY[
  'IDENTITY_SERVICE','AUTHORIZATION_SERVICE','CONVERSATION_SERVICE','NOTIFICATION_SERVICE','WEB_BFF'
]::text[]) AS participant;
INSERT INTO identity_erasure_event_outbox(
  event_id,erasure_request_id,event_type,participant_policy_version,state,attempt_count,
  next_attempt_at,occurred_at,retain_until,updated_at)
VALUES (
  '{event_id}','{request_id}','COMMAND','1','PENDING',0,
  CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '35 days',CURRENT_TIMESTAMP);
"""


def snapshot_command(database: str) -> list[str]:
    if database not in PARTICIPANT_DATABASES:
        raise ValueError("database is outside the participant snapshot set")
    return [
        "kubectl",
        "--context",
        CONTEXT,
        "-n",
        DATA_NAMESPACE,
        "exec",
        "deployment/postgresql",
        "--",
        "pg_dump",
        "-U",
        "postgres",
        "-d",
        database,
        "--format=custom",
    ]


def restore_command(database: str) -> list[str]:
    if database not in PARTICIPANT_DATABASES:
        raise ValueError("database is outside the participant restore set")
    return [
        "kubectl",
        "--context",
        CONTEXT,
        "-n",
        DATA_NAMESPACE,
        "exec",
        "-i",
        "deployment/postgresql",
        "--",
        "pg_restore",
        "-U",
        "postgres",
        "-d",
        database,
        "--clean",
        "--if-exists",
        "--exit-on-error",
        "--single-transaction",
    ]


def _snapshot_databases(directory: Path) -> dict[str, Path]:
    snapshots: dict[str, Path] = {}
    for database in PARTICIPANT_DATABASES:
        destination = directory / f"{database}.dump"
        with destination.open("wb") as output:
            _command(snapshot_command(database), timeout=180, stdout_file=output)
        destination.chmod(0o600)
        if destination.stat().st_size == 0:
            raise RehearsalError("staging database snapshot is empty")
        snapshots[database] = destination
    return snapshots


def _restore_databases(snapshots: dict[str, Path]) -> None:
    if set(snapshots) != set(PARTICIPANT_DATABASES):
        raise ValueError("restore snapshot set does not match participant databases")
    for database, snapshot in snapshots.items():
        if not snapshot.is_file() or snapshot.stat().st_mode & 0o077:
            raise RehearsalError("staging snapshot is missing or has unsafe permissions")
        try:
            with snapshot.open("rb") as source:
                completed = subprocess.run(
                    restore_command(database),
                    cwd=ROOT,
                    stdin=source,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.PIPE,
                    check=True,
                    timeout=180,
                )
                del completed
        except (OSError, subprocess.SubprocessError) as error:
            raise RehearsalError("bounded staging database restore failed") from error


def _scale(replicas: int) -> None:
    for application in APPLICATIONS:
        _kubectl(
            "-n",
            APP_NAMESPACE,
            "scale",
            "deployment/" + application,
            "--replicas=" + str(replicas),
            timeout=45,
        )
    if replicas == 0:
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            output = _kubectl(
                "-n",
                APP_NAMESPACE,
                "get",
                "pods",
                "-l",
                "app.kubernetes.io/name in (" + ",".join(APPLICATIONS) + ")",
                "--no-headers",
                capture=True,
                timeout=15,
            )
            if not output:
                return
            time.sleep(1)
        raise RehearsalError("application pods did not stop within the bound")
    for application in APPLICATIONS:
        rollout_timeout = (
            COMPROMISED_PASSWORD_ROLLOUT_TIMEOUT_SECONDS
            if application == "compromised-password-service"
            else DEFAULT_ROLLOUT_TIMEOUT_SECONDS
        )
        _kubectl(
            "-n",
            APP_NAMESPACE,
            "rollout",
            "status",
            "deployment/" + application,
            f"--timeout={rollout_timeout}s",
            timeout=rollout_timeout + 10,
        )


def _wait_complete(user_id: uuid.UUID, request_id: uuid.UUID, timeout_seconds: int) -> None:
    expected = (
        "COMPLETED|DELETED|AUTHORIZATION_SERVICE:COMPLETED,CONVERSATION_SERVICE:COMPLETED,"
        "IDENTITY_SERVICE:COMPLETED,"
        "NOTIFICATION_SERVICE:COMPLETED,WEB_BFF:COMPLETED"
    )
    query = f"""
SELECT r.state || '|' || u.status || '|' ||
       string_agg(p.participant || ':' || p.state, ',' ORDER BY p.participant)
FROM identity_erasure_request r
JOIN identity_user u ON u.user_id=r.user_id
JOIN identity_erasure_participant p ON p.erasure_request_id=r.erasure_request_id
WHERE r.erasure_request_id='{request_id}' AND u.user_id='{user_id}'
GROUP BY r.state,u.status;
"""
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if _psql(query, "identity", capture=True) == expected:
            return
        time.sleep(1)
    raise RehearsalError("five-participant erasure did not complete within the bound")


def _verify_participants(request_id: uuid.UUID) -> None:
    for database, (inbox, evidence) in PARTICIPANT_DATABASES.items():
        result = _psql(
            f"""
SELECT
  (SELECT count(*) FROM {inbox}
   WHERE erasure_request_id='{request_id}' AND state='COMPLETED') || '|' ||
  (SELECT count(*) FROM {evidence}
   WHERE erasure_request_id='{request_id}' AND event_code='ERASURE_COMPLETED');
""",
            database,
            capture=True,
        )
        if result != "1|1":
            raise RehearsalError("participant erasure evidence is incomplete")


def staging_evidence(revision: str) -> dict[str, object]:
    if re.fullmatch(r"[0-9a-f]{40}", revision) is None:
        raise ValueError("staging evidence requires a full Git revision")
    return {
        "schema": "hooshix-staging-erasure-recovery-v1",
        "git_revision": revision,
        "environment": "staging",
        "recorded_at": dt.datetime.now(dt.UTC).isoformat().replace("+00:00", "Z"),
        "executed": True,
        "redeploy_completed": True,
        "restore_completed": True,
        "participant_count": 5,
        "identity_deleted": True,
        "no_reappearance": True,
        "passed": True,
    }


def _write_private_json(path: Path, value: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.parent.chmod(0o700)
    descriptor, temporary_name = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(value, handle, sort_keys=True)
            handle.write("\n")
        temporary.chmod(0o600)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def parse_image_state(text: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in text.splitlines():
        key, separator, value = line.partition("=")
        if not separator or not key or not value or key in values:
            raise RehearsalError("staging image provenance state is invalid")
        values[key] = value
    expected = {
        "BUILD_GIT_REVISION",
        "BUILD_SOURCE_STATE",
        "BUILD_WORKTREE_SHA256",
    }
    for application in APPLICATIONS:
        prefix = application.upper().replace("-", "_")
        expected.update({prefix + "_REPOSITORY", prefix + "_DIGEST"})
    if set(values) != expected:
        raise RehearsalError("staging image provenance state is invalid")
    if (
        re.fullmatch(r"[0-9a-f]{40}", values["BUILD_GIT_REVISION"]) is None
        or values["BUILD_SOURCE_STATE"] != "clean"
        or re.fullmatch(r"[0-9a-f]{64}", values["BUILD_WORKTREE_SHA256"]) is None
    ):
        raise RehearsalError("staging image provenance state is invalid")
    for application, repository in IMAGE_REPOSITORIES.items():
        prefix = application.upper().replace("-", "_")
        if values[prefix + "_REPOSITORY"] != repository or re.fullmatch(
            r"sha256:[0-9a-f]{64}", values[prefix + "_DIGEST"]
        ) is None:
            raise RehearsalError("staging image provenance state is invalid")
    return values


def _preflight() -> str:
    if shutil.which("kubectl") is None:
        raise RehearsalError("kubectl is unavailable")
    revision = _command(["git", "rev-parse", "HEAD"], capture=True, timeout=10)
    if _command(["git", "status", "--porcelain"], capture=True, timeout=10):
        raise RehearsalError("formal staging erasure evidence requires a clean worktree")
    if _command(["kubectl", "config", "current-context"], capture=True, timeout=10) != CONTEXT:
        raise RehearsalError("unexpected Kubernetes context")
    state = ROOT / ".platform-runtime" / "staging" / "images.env"
    values = parse_image_state(state.read_text(encoding="utf-8"))
    if (
        values["BUILD_GIT_REVISION"] != revision
    ):
        raise RehearsalError("staging images are not clean and bound to current HEAD")
    _command(
        [
            "python3",
            str(ROOT / "scripts/platform/git_provenance.py"),
            "--root",
            str(ROOT),
            "verify",
            "--revision",
            revision,
            "--source-state",
            "clean",
            "--worktree-sha256",
            values["BUILD_WORKTREE_SHA256"],
        ],
        timeout=30,
    )
    for application in APPLICATIONS:
        replicas = _kubectl(
            "-n",
            APP_NAMESPACE,
            "get",
            "deployment/" + application,
            "-o",
            "jsonpath={.spec.replicas}:{.status.readyReplicas}",
            capture=True,
        )
        if replicas != "1:1":
            raise RehearsalError("staging applications are not ready at one replica")
        prefix = application.upper().replace("-", "_")
        deployed_image = _kubectl(
            "-n",
            APP_NAMESPACE,
            "get",
            "deployment/" + application,
            "-o",
            "jsonpath={.spec.template.spec.containers[0].image}",
            capture=True,
        )
        expected_image = values[prefix + "_REPOSITORY"] + "@" + values[prefix + "_DIGEST"]
        if deployed_image != expected_image:
            raise RehearsalError("staging deployment image does not match provenance state")
    return revision


def run(timeout_seconds: int) -> dict[str, object]:
    revision = _preflight()
    STATE_ROOT.mkdir(parents=True, exist_ok=True)
    STATE_ROOT.chmod(0o700)
    user_id, request_id, event_id = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
    stopped = False
    snapshots: dict[str, Path] = {}
    with tempfile.TemporaryDirectory(prefix="staging-erasure-", dir=STATE_ROOT) as directory:
        snapshot_directory = Path(directory)
        snapshot_directory.chmod(0o700)
        try:
            _scale(0)
            stopped = True
            _psql(erasure_seed_sql(user_id, request_id, event_id), "identity")
            snapshots = _snapshot_databases(snapshot_directory)

            stopped = False
            _scale(1)
            _wait_complete(user_id, request_id, timeout_seconds)
            _verify_participants(request_id)
            time.sleep(5)
            _wait_complete(user_id, request_id, timeout_seconds)
            _verify_participants(request_id)

            for application in APPLICATIONS:
                _kubectl(
                    "-n",
                    APP_NAMESPACE,
                    "rollout",
                    "restart",
                    "deployment/" + application,
                )
            stopped = False
            _scale(1)
            _wait_complete(user_id, request_id, timeout_seconds)
            _verify_participants(request_id)

            _scale(0)
            stopped = True
            _restore_databases(snapshots)
            stopped = False
            _scale(1)
            _wait_complete(user_id, request_id, timeout_seconds)
            _verify_participants(request_id)
            time.sleep(5)
            _wait_complete(user_id, request_id, timeout_seconds)
            _verify_participants(request_id)
        except Exception:
            if snapshots:
                try:
                    if not stopped:
                        _scale(0)
                        stopped = True
                    _restore_databases(snapshots)
                except Exception:
                    raise RehearsalError(
                        "staging rehearsal failed and automatic snapshot recovery also failed; applications remain stopped"
                    )
            if stopped:
                _scale(1)
            raise
    evidence = staging_evidence(revision)
    _write_private_json(EVIDENCE_PATH, evidence)
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--timeout-seconds", type=int, default=240)
    arguments = parser.parse_args()
    timeout_seconds = max(60, min(arguments.timeout_seconds, 600))
    try:
        evidence = run(timeout_seconds)
    except (OSError, ValueError, RehearsalError) as error:
        print(f"Staging erasure recovery FAILED: {error}")
        return 1
    print(
        "Staging erasure recovery PASSED: "
        f"participants={evidence['participant_count']} redeploy=true restore=true no_reappearance=true"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
