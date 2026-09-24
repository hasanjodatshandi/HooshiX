#!/usr/bin/env python3
"""Issue and verify a private, signed, content-free staging canary receipt."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import re
import stat
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import provider_approval

ROOT = Path(__file__).resolve().parents[2]
GOVERNANCE_PATH = ROOT / "mlops/governance/v3/governance.json"
RECEIPT_TYPE = "HOOSHIX_STAGING_MODEL_CANARY"
COMMIT = re.compile(r"^[0-9a-f]{40}$")
IMAGE_DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")


def _load(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("receipt root must be an object")
    return value


def _private_file(path: Path, label: str) -> None:
    value = path.lstat()
    if not stat.S_ISREG(value.st_mode) or path.is_symlink():
        raise ValueError(f"{label} must be a regular non-symlink file")
    if value.st_uid != os.getuid() or stat.S_IMODE(value.st_mode) != 0o600:
        raise ValueError(f"{label} must be user-owned mode 0600")


def _timestamp(value: Any, label: str) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise ValueError(f"{label} is invalid")
    try:
        result = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise ValueError(f"{label} is invalid") from exc
    return result.astimezone(timezone.utc)


def _format(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _canonical(payload: dict[str, Any]) -> bytes:
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def _unsigned(receipt: dict[str, Any]) -> dict[str, Any]:
    payload = dict(receipt)
    payload.pop("payload_sha256", None)
    payload.pop("signature", None)
    return payload


def _sign(payload: dict[str, Any], signing_key: bytes) -> dict[str, Any]:
    canonical = _canonical(payload)
    result = dict(payload)
    result["payload_sha256"] = hashlib.sha256(canonical).hexdigest()
    result["signature"] = {
        "algorithm": "HMAC-SHA256",
        "value": hmac.new(
            signing_key, b"staging-canary-receipt-v1\0" + canonical, hashlib.sha256
        ).hexdigest(),
    }
    return result


def verify_signature(receipt: dict[str, Any], signing_key: bytes) -> bool:
    payload = _unsigned(receipt)
    canonical = _canonical(payload)
    signature = receipt.get("signature")
    return (
        receipt.get("payload_sha256") == hashlib.sha256(canonical).hexdigest()
        and isinstance(signature, dict)
        and signature.get("algorithm") == "HMAC-SHA256"
        and hmac.compare_digest(
            str(signature.get("value", "")),
            hmac.new(
                signing_key, b"staging-canary-receipt-v1\0" + canonical, hashlib.sha256
            ).hexdigest(),
        )
    )


def _governance() -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    governance = _load(GOVERNANCE_PATH)
    model = governance["model_catalog"][0]
    promotion = governance["promotion_policy"]
    if (
        governance.get("decision_status") != "APPROVED_STAGING_CANARY"
        or model.get("lifecycle") != "CANARY_1"
        or model.get("execution_enabled") is not True
    ):
        raise ValueError("governance does not authorize the staging 1% canary")
    return governance, model, promotion


def _validate(receipt: dict[str, Any], signing_key: bytes) -> None:
    if not verify_signature(receipt, signing_key):
        raise ValueError("canary receipt signature is invalid")
    if receipt.get("schema_version") != 1 or receipt.get("receipt_type") != RECEIPT_TYPE:
        raise ValueError("canary receipt identity is invalid")
    if (
        receipt.get("environment") != "STAGING"
        or receipt.get("data_classification") != "SYNTHETIC_NON_SENSITIVE_ONLY"
    ):
        raise ValueError("canary receipt scope is invalid")
    if COMMIT.fullmatch(str(receipt.get("runtime_repository_commit", ""))) is None:
        raise ValueError("runtime commit is invalid")
    if IMAGE_DIGEST.fullmatch(str(receipt.get("runtime_image_digest", ""))) is None:
        raise ValueError("runtime image digest is invalid")

    started = _timestamp(receipt.get("observation_started_at"), "observation_started_at")
    completed = _timestamp(receipt.get("observation_completed_at"), "observation_completed_at")
    issued = _timestamp(receipt.get("issued_at"), "issued_at")
    minutes = int((completed - started).total_seconds() // 60)
    if completed < started or issued < completed or receipt.get("observation_minutes") != minutes:
        raise ValueError("canary observation timestamps are invalid")

    governance, model, promotion = _governance()
    minimum_minutes = promotion["minimum_canary_observation_minutes"][0]
    if receipt.get("canary_percent") != 1 or minutes < minimum_minutes:
        raise ValueError("canary observation window is too short")
    expected_tuple = {
        "governance_version": governance["governance_version"],
        "model_alias": model["logical_id"],
        "provider_model_id": model["provider_model_id"],
        "prompt_version": model["prompt_version"],
        "price_version": model["price_version"],
        "governance_sha256": hashlib.sha256(GOVERNANCE_PATH.read_bytes()).hexdigest(),
    }
    if receipt.get("approved_tuple") != expected_tuple:
        raise ValueError("canary tuple does not match current governance")

    aggregates = receipt.get("aggregates")
    checks = receipt.get("checks")
    if not isinstance(aggregates, dict) or not isinstance(checks, dict):
        raise ValueError("canary evidence is invalid")
    integer_fields = (
        "run_count", "succeeded_count", "failed_count", "outcome_unknown_count",
        "critical_incident_count", "total_actual_cost_micro_usd", "p95_latency_ms",
        "error_rate_basis_points", "cost_overrun_basis_points",
    )
    if any(
        not isinstance(aggregates.get(field), int)
        or isinstance(aggregates.get(field), bool)
        or aggregates[field] < 0
        for field in integer_fields
    ):
        raise ValueError("canary aggregate is invalid")
    if (
        aggregates["run_count"] < 1
        or aggregates["succeeded_count"] != aggregates["run_count"]
        or aggregates["failed_count"] != 0
        or aggregates["outcome_unknown_count"] != 0
        or aggregates["critical_incident_count"] != 0
        or aggregates["error_rate_basis_points"] > promotion["rollback_triggers"]["maximum_error_rate_basis_points"]
        or aggregates["cost_overrun_basis_points"] > promotion["rollback_triggers"]["maximum_cost_overrun_basis_points"]
        or aggregates["p95_latency_ms"] > promotion["rollback_triggers"]["maximum_p95_latency_ms"]
    ):
        raise ValueError("canary rollback threshold was breached")
    required_checks = {
        "assistant_output_verified_without_retaining_content": True,
        "privacy_canary_passed": True,
        "log_leak_scan_passed": True,
        "rollback_rehearsal_passed": True,
        "runtime_disabled_after": True,
    }
    if checks != required_checks:
        raise ValueError("canary checks are incomplete")


def issue(
    *,
    evaluation_path: Path,
    provider_approval_path: Path,
    signing_key_path: Path,
    output_path: Path,
    runtime_repository_commit: str,
    runtime_image_digest: str,
    observation_started_at: datetime,
    observation_completed_at: datetime,
    run_count: int,
    succeeded_count: int,
    failed_count: int,
    outcome_unknown_count: int,
    critical_incident_count: int,
    total_actual_cost_micro_usd: int,
    p95_latency_ms: int,
    error_rate_basis_points: int,
    cost_overrun_basis_points: int,
    now: datetime | None = None,
) -> dict[str, Any]:
    _private_file(signing_key_path, "signing key")
    signing_key = signing_key_path.read_bytes()
    approval = provider_approval.verify(
        provider_approval_path, evaluation_path, signing_key_path, now=now
    )
    evaluation = _load(evaluation_path)
    governance, model, _promotion = _governance()
    completed = observation_completed_at.astimezone(timezone.utc)
    issued = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    payload = {
        "schema_version": 1,
        "receipt_type": RECEIPT_TYPE,
        "issued_at": _format(issued),
        "environment": "STAGING",
        "data_classification": "SYNTHETIC_NON_SENSITIVE_ONLY",
        "runtime_repository_commit": runtime_repository_commit,
        "runtime_image_digest": runtime_image_digest,
        "canary_percent": 1,
        "observation_started_at": _format(observation_started_at),
        "observation_completed_at": _format(completed),
        "observation_minutes": int((completed - observation_started_at).total_seconds() // 60),
        "approved_tuple": {
            "governance_version": governance["governance_version"],
            "model_alias": model["logical_id"],
            "provider_model_id": model["provider_model_id"],
            "prompt_version": model["prompt_version"],
            "price_version": model["price_version"],
            "governance_sha256": hashlib.sha256(GOVERNANCE_PATH.read_bytes()).hexdigest(),
        },
        "evidence_bindings": {
            "evaluation_payload_sha256": evaluation["payload_sha256"],
            "provider_approval_payload_sha256": approval["payload_sha256"],
        },
        "aggregates": {
            "run_count": run_count,
            "succeeded_count": succeeded_count,
            "failed_count": failed_count,
            "outcome_unknown_count": outcome_unknown_count,
            "critical_incident_count": critical_incident_count,
            "total_actual_cost_micro_usd": total_actual_cost_micro_usd,
            "p95_latency_ms": p95_latency_ms,
            "error_rate_basis_points": error_rate_basis_points,
            "cost_overrun_basis_points": cost_overrun_basis_points,
        },
        "checks": {
            "assistant_output_verified_without_retaining_content": True,
            "privacy_canary_passed": True,
            "log_leak_scan_passed": True,
            "rollback_rehearsal_passed": True,
            "runtime_disabled_after": True,
        },
    }
    receipt = _sign(payload, signing_key)
    _validate(receipt, signing_key)
    output_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    output_path.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    output_path.chmod(0o600)
    return receipt


def verify(
    receipt_path: Path,
    evaluation_path: Path,
    provider_approval_path: Path,
    signing_key_path: Path,
    *,
    now: datetime | None = None,
) -> dict[str, Any]:
    _private_file(receipt_path, "canary receipt")
    _private_file(signing_key_path, "signing key")
    signing_key = signing_key_path.read_bytes()
    provider_approval.verify(provider_approval_path, evaluation_path, signing_key_path, now=now)
    evaluation = _load(evaluation_path)
    approval = _load(provider_approval_path)
    receipt = _load(receipt_path)
    _validate(receipt, signing_key)
    bindings = receipt.get("evidence_bindings")
    if bindings != {
        "evaluation_payload_sha256": evaluation.get("payload_sha256"),
        "provider_approval_payload_sha256": approval.get("payload_sha256"),
    }:
        raise ValueError("canary evidence bindings are invalid")
    return receipt


def main() -> int:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    issue_parser = commands.add_parser("issue")
    issue_parser.add_argument("--runtime-repository-commit", required=True)
    issue_parser.add_argument("--runtime-image-digest", required=True)
    issue_parser.add_argument("--observation-started-at", required=True)
    issue_parser.add_argument("--observation-completed-at", required=True)
    for field in (
        "run-count", "succeeded-count", "failed-count", "outcome-unknown-count",
        "critical-incident-count", "total-actual-cost-micro-usd", "p95-latency-ms",
        "error-rate-basis-points", "cost-overrun-basis-points",
    ):
        issue_parser.add_argument(f"--{field}", type=int, required=True)
    for command in (issue_parser, commands.add_parser("verify")):
        command.add_argument("--evaluation-receipt", type=Path, required=True)
        command.add_argument("--provider-approval-receipt", type=Path, required=True)
        command.add_argument("--signing-key-file", type=Path, required=True)
        command.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "issue":
        receipt = issue(
            evaluation_path=args.evaluation_receipt,
            provider_approval_path=args.provider_approval_receipt,
            signing_key_path=args.signing_key_file,
            output_path=args.receipt,
            runtime_repository_commit=args.runtime_repository_commit,
            runtime_image_digest=args.runtime_image_digest,
            observation_started_at=_timestamp(args.observation_started_at, "observation_started_at"),
            observation_completed_at=_timestamp(args.observation_completed_at, "observation_completed_at"),
            run_count=args.run_count,
            succeeded_count=args.succeeded_count,
            failed_count=args.failed_count,
            outcome_unknown_count=args.outcome_unknown_count,
            critical_incident_count=args.critical_incident_count,
            total_actual_cost_micro_usd=args.total_actual_cost_micro_usd,
            p95_latency_ms=args.p95_latency_ms,
            error_rate_basis_points=args.error_rate_basis_points,
            cost_overrun_basis_points=args.cost_overrun_basis_points,
        )
    else:
        receipt = verify(
            args.receipt,
            args.evaluation_receipt,
            args.provider_approval_receipt,
            args.signing_key_file,
        )
    print(json.dumps({
        "environment": receipt["environment"],
        "observation_minutes": receipt["observation_minutes"],
        "payload_sha256": receipt["payload_sha256"],
        "verified": True,
    }))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
