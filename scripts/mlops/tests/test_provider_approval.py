from __future__ import annotations

import copy
import json
import os
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import provider_approval  # noqa: E402
import run_evaluation  # noqa: E402


class ProviderApprovalTest(unittest.TestCase):
    def setUp(self) -> None:
        self.now = datetime(2026, 9, 23, 12, 0, tzinfo=timezone.utc)

    def private(self, path: Path, value: bytes) -> None:
        path.write_bytes(value)
        path.chmod(0o600)

    def evaluation(self, path: Path, key: bytes) -> None:
        payload = {
            "schema_version": 2, "promotion_passed": True,
            "case_count": 12, "passed_count": 12, "critical_count": 8,
            "critical_passed_count": 8, "error_count": 0,
            "prompt_sha256": "a" * 64, "prompt_version": "2.0.0",
            "price_version": "2026-09-12", "suite_id": "conversation-v2",
            "suite_version": "2.0.0", "repository_commit": "b" * 40,
        }
        canonical = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
        payload["payload_sha256"] = __import__("hashlib").sha256(canonical).hexdigest()
        payload["signature"] = {
            "algorithm": "HMAC-SHA256",
            "value": __import__("hmac").new(key, canonical, __import__("hashlib").sha256).hexdigest(),
        }
        self.private(path, (json.dumps(payload) + "\n").encode())

    def issue(self, root: Path) -> tuple[Path, Path, Path]:
        key_path, evaluation_path, receipt_path = root / "key", root / "evaluation.json", root / "approval.json"
        key = b"k" * 32
        self.private(key_path, key)
        self.evaluation(evaluation_path, key)
        provider_approval.issue(
            organization_id="org-abcdefgh", project_id="proj_abcdefgh",
            approver_reference="github:hasanjodatshandi", evaluation_path=evaluation_path,
            signing_key_path=key_path, output_path=receipt_path, now=self.now)
        return key_path, evaluation_path, receipt_path

    def test_issue_and_verify_private_staging_receipt(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            key, evaluation, receipt = self.issue(Path(temp_dir))
            verified = provider_approval.verify(receipt, evaluation, key, now=self.now + timedelta(days=1))
            self.assertEqual("STAGING", verified["environment"])
            self.assertFalse(verified["approval"]["production_authorized"])
            self.assertEqual(0o600, receipt.stat().st_mode & 0o777)

    def test_tamper_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            key, evaluation, receipt = self.issue(Path(temp_dir))
            value = json.loads(receipt.read_text())
            value["provider_account"]["api_call_logging"] = "ENABLED"
            receipt.write_text(json.dumps(value), encoding="utf-8")
            receipt.chmod(0o600)
            with self.assertRaisesRegex(ValueError, "signature"):
                provider_approval.verify(receipt, evaluation, key, now=self.now)

    def test_expired_receipt_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            key, evaluation, receipt = self.issue(Path(temp_dir))
            with self.assertRaisesRegex(ValueError, "validity window"):
                provider_approval.verify(receipt, evaluation, key, now=self.now + timedelta(days=31))

    def test_non_private_receipt_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            key, evaluation, receipt = self.issue(Path(temp_dir))
            os.chmod(receipt, 0o644)
            with self.assertRaisesRegex(ValueError, "0600"):
                provider_approval.verify(receipt, evaluation, key, now=self.now)

    def test_evaluation_binding_is_required(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            key, evaluation, receipt = self.issue(Path(temp_dir))
            other = copy.deepcopy(json.loads(evaluation.read_text()))
            other["repository_commit"] = "c" * 40
            unsigned = dict(other)
            unsigned.pop("payload_sha256")
            unsigned.pop("signature")
            canonical = json.dumps(unsigned, sort_keys=True, separators=(",", ":")).encode()
            key_bytes = key.read_bytes()
            other["payload_sha256"] = __import__("hashlib").sha256(canonical).hexdigest()
            other["signature"] = {"algorithm": "HMAC-SHA256", "value": __import__("hmac").new(key_bytes, canonical, __import__("hashlib").sha256).hexdigest()}
            evaluation.write_text(json.dumps(other), encoding="utf-8")
            evaluation.chmod(0o600)
            with self.assertRaisesRegex(ValueError, "does not match"):
                provider_approval.verify(receipt, evaluation, key, now=self.now)


if __name__ == "__main__":
    unittest.main()
