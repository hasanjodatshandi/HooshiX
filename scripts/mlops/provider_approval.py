#!/usr/bin/env python3
"""Issue and verify a private, signed staging provider-control approval receipt."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import re
import stat
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import run_evaluation

ROOT = Path(__file__).resolve().parents[2]
RECEIPT_TYPE = "HOOSHIX_PROVIDER_CONTROL_APPROVAL"
ROLES = ["platform-owner", "privacy-owner", "product-owner", "security-owner"]
ORG_ID = re.compile(r"^org-[A-Za-z0-9]{8,128}$")
PROJECT_ID = re.compile(r"^proj_[A-Za-z0-9]{8,128}$")


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


def _canonical(payload: dict[str, Any]) -> bytes:
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def _unsigned(receipt: dict[str, Any]) -> dict[str, Any]:
    payload = dict(receipt)
    payload.pop("payload_sha256", None)
    payload.pop("signature", None)
    return payload


def _sign(payload: dict[str, Any], signing_key: bytes) -> dict[str, Any]:
    canonical = _canonical(payload)
    receipt = dict(payload)
    receipt["payload_sha256"] = hashlib.sha256(canonical).hexdigest()
    receipt["signature"] = {
        "algorithm": "HMAC-SHA256",
        "value": hmac.new(signing_key, b"provider-control-approval-v1\0" + canonical, hashlib.sha256).hexdigest(),
    }
    return receipt


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
            hmac.new(signing_key, b"provider-control-approval-v1\0" + canonical, hashlib.sha256).hexdigest(),
        )
    )


def _evaluation(path: Path, signing_key: bytes) -> dict[str, Any]:
    _private_file(path, "evaluation receipt")
    receipt = _load(path)
    if not run_evaluation.verify_signature(receipt, signing_key):
        raise ValueError("evaluation receipt signature is invalid")
    if receipt.get("schema_version") != 2 or receipt.get("promotion_passed") is not True:
        raise ValueError("evaluation receipt did not pass promotion gates")
    if receipt.get("case_count") != receipt.get("passed_count"):
        raise ValueError("evaluation receipt contains failed cases")
    if receipt.get("critical_count") != receipt.get("critical_passed_count"):
        raise ValueError("evaluation receipt contains failed critical cases")
    if receipt.get("error_count") != 0:
        raise ValueError("evaluation receipt contains provider errors")
    return receipt


def _tuple(evaluation: dict[str, Any]) -> dict[str, Any]:
    governance = _load(ROOT / "mlops/governance/v2/governance.json")
    model = governance["model_catalog"][0]
    return {
        "model_id": model["provider_model_id"],
        "prompt_sha256": evaluation["prompt_sha256"],
        "prompt_version": evaluation["prompt_version"],
        "price_version": evaluation["price_version"],
        "suite_id": evaluation["suite_id"],
        "suite_version": evaluation["suite_version"],
        "evaluation_payload_sha256": evaluation["payload_sha256"],
        "evaluated_repository_commit": evaluation["repository_commit"],
    }


def issue(
    *,
    organization_id: str,
    project_id: str,
    approver_reference: str,
    evaluation_path: Path,
    signing_key_path: Path,
    output_path: Path,
    now: datetime | None = None,
) -> dict[str, Any]:
    if ORG_ID.fullmatch(organization_id) is None:
        raise ValueError("organization id is invalid")
    if PROJECT_ID.fullmatch(project_id) is None:
        raise ValueError("project id is invalid")
    if re.fullmatch(r"github:[A-Za-z0-9-]{1,39}", approver_reference) is None:
        raise ValueError("approver reference is invalid")
    _private_file(signing_key_path, "signing key")
    signing_key = signing_key_path.read_bytes()
    if len(signing_key) < 32:
        raise ValueError("signing key is too short")
    evaluation = _evaluation(evaluation_path, signing_key)
    issued_at = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    payload = {
        "schema_version": 1,
        "receipt_type": RECEIPT_TYPE,
        "receipt_id": str(uuid.uuid4()),
        "issued_at": issued_at.isoformat().replace("+00:00", "Z"),
        "expires_at": (issued_at + timedelta(days=30)).isoformat().replace("+00:00", "Z"),
        "environment": "STAGING",
        "data_classification": "SYNTHETIC_NON_SENSITIVE_ONLY",
        "provider": "OPENAI",
        "provider_account": {
            "organization_id": organization_id,
            "project_id": project_id,
            "project_residency": "GLOBAL",
            "project_data_retention": "NONE_STANDARD_ABUSE_MONITORING_UP_TO_30_DAYS",
            "api_call_logging": "DISABLED",
        },
        "approval": {
            "approver_reference": approver_reference,
            "roles": ROLES,
            "subprocessor_and_region_review": "ACKNOWLEDGED_FOR_STAGING",
            "real_user_data_authorized": False,
            "production_authorized": False,
        },
        "approved_tuple": _tuple(evaluation),
    }
    receipt = _sign(payload, signing_key)
    output_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    output_path.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    output_path.chmod(0o600)
    verify(output_path, evaluation_path, signing_key_path, now=issued_at)
    return receipt


def _timestamp(value: Any, label: str) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise ValueError(f"{label} is invalid")
    try:
        return datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise ValueError(f"{label} is invalid") from exc


def verify(
    receipt_path: Path,
    evaluation_path: Path,
    signing_key_path: Path,
    *,
    now: datetime | None = None,
) -> dict[str, Any]:
    _private_file(receipt_path, "provider approval receipt")
    _private_file(signing_key_path, "signing key")
    signing_key = signing_key_path.read_bytes()
    receipt = _load(receipt_path)
    if not verify_signature(receipt, signing_key):
        raise ValueError("provider approval receipt signature is invalid")
    evaluation = _evaluation(evaluation_path, signing_key)
    current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    issued_at = _timestamp(receipt.get("issued_at"), "issued_at")
    expires_at = _timestamp(receipt.get("expires_at"), "expires_at")
    account = receipt.get("provider_account")
    approval = receipt.get("approval")
    if receipt.get("schema_version") != 1 or receipt.get("receipt_type") != RECEIPT_TYPE:
        raise ValueError("provider approval receipt identity is invalid")
    if receipt.get("environment") != "STAGING" or receipt.get("data_classification") != "SYNTHETIC_NON_SENSITIVE_ONLY":
        raise ValueError("provider approval receipt scope is invalid")
    if issued_at > current or expires_at <= current or expires_at - issued_at != timedelta(days=30):
        raise ValueError("provider approval receipt validity window is invalid")
    if not isinstance(account, dict) or ORG_ID.fullmatch(str(account.get("organization_id", ""))) is None:
        raise ValueError("provider organization binding is invalid")
    if PROJECT_ID.fullmatch(str(account.get("project_id", ""))) is None:
        raise ValueError("provider project binding is invalid")
    expected_controls = {
        "project_residency": "GLOBAL",
        "project_data_retention": "NONE_STANDARD_ABUSE_MONITORING_UP_TO_30_DAYS",
        "api_call_logging": "DISABLED",
    }
    if any(account.get(key) != value for key, value in expected_controls.items()):
        raise ValueError("provider account controls are invalid")
    if not isinstance(approval, dict) or approval.get("roles") != ROLES:
        raise ValueError("provider approval roles are invalid")
    if approval.get("real_user_data_authorized") is not False or approval.get("production_authorized") is not False:
        raise ValueError("provider approval exceeds staging scope")
    if approval.get("subprocessor_and_region_review") != "ACKNOWLEDGED_FOR_STAGING":
        raise ValueError("provider region/subprocessor acknowledgement is invalid")
    if receipt.get("approved_tuple") != _tuple(evaluation):
        raise ValueError("provider approval tuple does not match evaluation evidence")
    return receipt


def main() -> int:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    issue_parser = commands.add_parser("issue")
    issue_parser.add_argument("--organization-id", required=True)
    issue_parser.add_argument("--project-id", required=True)
    issue_parser.add_argument("--approver-reference", required=True)
    for command in (issue_parser, commands.add_parser("verify")):
        command.add_argument("--evaluation-receipt", type=Path, required=True)
        command.add_argument("--signing-key-file", type=Path, required=True)
        command.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "issue":
        receipt = issue(
            organization_id=args.organization_id,
            project_id=args.project_id,
            approver_reference=args.approver_reference,
            evaluation_path=args.evaluation_receipt,
            signing_key_path=args.signing_key_file,
            output_path=args.receipt,
        )
    else:
        receipt = verify(args.receipt, args.evaluation_receipt, args.signing_key_file)
    print(json.dumps({
        "receipt_id": receipt["receipt_id"],
        "environment": receipt["environment"],
        "expires_at": receipt["expires_at"],
        "payload_sha256": receipt["payload_sha256"],
        "verified": True,
    }))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
