from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_governance as verifier  # noqa: E402


class MlopsGovernanceVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.repository_root = Path(__file__).resolve().parents[3]

    def copy_bundle(self, destination: Path) -> None:
        shutil.copytree(self.repository_root / "mlops", destination / "mlops")

    def update_governance(self, root: Path, mutate) -> None:
        path = root / "mlops/governance/v3/governance.json"
        value = json.loads(path.read_text(encoding="utf-8"))
        mutate(value)
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def test_current_bundle_is_valid(self) -> None:
        self.assertEqual([], verifier.validate(self.repository_root))

    def test_pending_data_control_cannot_enable_execution(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.copy_bundle(root)
            self.update_governance(root, lambda value: value["provider_data_controls"].update(approval_status="PENDING_ORGANIZATION_VERIFICATION"))
            errors = verifier.validate(root)
            self.assertTrue(any("provider approval must be recorded" in error for error in errors))

    def test_production_or_real_user_scope_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.copy_bundle(root)
            self.update_governance(root, lambda value: value["provider_data_controls"].update(approval_scope="PRODUCTION"))
            self.assertTrue(any("scope must prohibit" in error for error in verifier.validate(root)))

    def test_prompt_tamper_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.copy_bundle(root)
            prompt = root / "mlops/prompts/conversation-system-v2.txt"
            prompt.write_text(prompt.read_text(encoding="utf-8") + "tampered\n", encoding="utf-8")
            self.assertTrue(any("prompt sha256" in error for error in verifier.validate(root)))

    def test_prompt_path_escape_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.copy_bundle(root)
            outside = root.parent / f"{root.name}-outside-prompt.txt"
            outside.write_text("outside\n", encoding="utf-8")
            try:
                self.update_governance(root, lambda value: value["prompt_catalog"][0].update(path=f"../{outside.name}"))
                self.assertTrue(any("inside the repository" in error for error in verifier.validate(root)))
            finally:
                outside.unlink(missing_ok=True)

    def test_malformed_policy_shape_is_reported_without_crashing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.copy_bundle(root)
            self.update_governance(root, lambda value: value.update(promotion_policy=[]))
            self.assertTrue(any("promotion_policy must be an object" in error for error in verifier.validate(root)))

    def test_malformed_price_is_reported_without_crashing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.copy_bundle(root)
            self.update_governance(root, lambda value: value["price_catalog"][0].update(maximum_request_reservation_micro_usd="unbounded"))
            self.assertTrue(any("reservation must be a positive integer" in error for error in verifier.validate(root)))

    def test_critical_eval_regression_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.copy_bundle(root)
            self.update_governance(root, lambda value: value["promotion_policy"].update(minimum_critical_eval_pass_rate_basis_points=9900))
            self.assertTrue(any("critical eval pass rate" in error for error in verifier.validate(root)))

    def test_contact_pii_in_eval_fixture_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.copy_bundle(root)
            path = root / "mlops/evaluations/conversation-v2.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["cases"][0]["input"] = "Contact alice@example.com"
            path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            self.assertTrue(any("contact PII" in error for error in verifier.validate(root)))


if __name__ == "__main__":
    unittest.main()
