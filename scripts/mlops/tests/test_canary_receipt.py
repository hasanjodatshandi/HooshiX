from __future__ import annotations

import copy
import hashlib
import hmac
import json
import os
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import canary_receipt  # noqa: E402
import provider_approval  # noqa: E402


class CanaryReceiptTest(unittest.TestCase):
    def setUp(self) -> None:
        self.started = datetime(2026, 9, 24, 12, 0, tzinfo=timezone.utc)
        self.completed = self.started + timedelta(minutes=60)

    @staticmethod
    def private(path: Path, value: bytes) -> None:
        path.write_bytes(value)
        path.chmod(0o600)

    def evidence(self, root: Path) -> tuple[Path, Path, Path]:
        key_path = root / "key"
        evaluation_path = root / "evaluation.json"
        approval_path = root / "approval.json"
        key = b"k" * 32
        self.private(key_path, key)
        payload = {
            "schema_version": 2, "promotion_passed": True, "case_count": 12,
            "passed_count": 12, "critical_count": 8, "critical_passed_count": 8,
            "error_count": 0, "prompt_sha256": "a" * 64, "prompt_version": "2.0.0",
            "price_version": "2026-09-12", "suite_id": "conversation-v2",
            "suite_version": "2.0.0", "repository_commit": "b" * 40,
        }
        canonical = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
        payload["payload_sha256"] = hashlib.sha256(canonical).hexdigest()
        payload["signature"] = {
            "algorithm": "HMAC-SHA256",
            "value": hmac.new(key, canonical, hashlib.sha256).hexdigest(),
        }
        self.private(evaluation_path, (json.dumps(payload) + "\n").encode())
        provider_approval.issue(
            organization_id="org-abcdefgh", project_id="proj_abcdefgh",
            approver_reference="github:hasanjodatshandi", evaluation_path=evaluation_path,
            signing_key_path=key_path, output_path=approval_path, now=self.started,
        )
        return key_path, evaluation_path, approval_path

    def issue(self, root: Path, **changes):
        key, evaluation, approval = self.evidence(root)
        values = {
            "evaluation_path": evaluation,
            "provider_approval_path": approval,
            "signing_key_path": key,
            "output_path": root / "canary.json",
            "runtime_repository_commit": "c" * 40,
            "runtime_image_digest": "sha256:" + "d" * 64,
            "observation_started_at": self.started,
            "observation_completed_at": self.completed,
            "run_count": 1, "succeeded_count": 1, "failed_count": 0,
            "outcome_unknown_count": 0, "critical_incident_count": 0,
            "total_actual_cost_micro_usd": 1210, "p95_latency_ms": 3717,
            "error_rate_basis_points": 0, "cost_overrun_basis_points": 0,
            "assistant_output_verified": True, "privacy_canary_passed": True,
            "log_leak_scan_passed": True, "rollback_rehearsal_passed": True,
            "runtime_disabled_after": True,
            "now": self.completed,
        }
        values.update(changes)
        receipt = canary_receipt.issue(**values)
        return key, evaluation, approval, values["output_path"], receipt

    def test_issue_and_verify_content_free_receipt(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            key, evaluation, approval, path, receipt = self.issue(Path(temp_dir))
            verified = canary_receipt.verify(path, evaluation, approval, key, now=self.completed)
            self.assertEqual(60, verified["observation_minutes"])
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            serialized = json.dumps(receipt)
            for forbidden in ("run_id", "tenant_id", "user_message", "model_output", "organization_id", "project_id"):
                self.assertNotIn(forbidden, serialized)

    def test_short_observation_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with self.assertRaisesRegex(ValueError, "too short"):
                self.issue(
                    Path(temp_dir),
                    observation_completed_at=self.started + timedelta(minutes=59),
                    now=self.started + timedelta(minutes=59),
                )

    def test_threshold_breach_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with self.assertRaisesRegex(ValueError, "threshold"):
                self.issue(Path(temp_dir), failed_count=1, succeeded_count=0, error_rate_basis_points=10000)

    def test_missing_executed_check_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with self.assertRaisesRegex(ValueError, "checks"):
                self.issue(Path(temp_dir), rollback_rehearsal_passed=False)

    def test_tamper_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            key, evaluation, approval, path, _receipt = self.issue(Path(temp_dir))
            value = json.loads(path.read_text())
            value["aggregates"]["total_actual_cost_micro_usd"] = 0
            path.write_text(json.dumps(value), encoding="utf-8")
            path.chmod(0o600)
            with self.assertRaisesRegex(ValueError, "signature"):
                canary_receipt.verify(path, evaluation, approval, key, now=self.completed)


if __name__ == "__main__":
    unittest.main()
