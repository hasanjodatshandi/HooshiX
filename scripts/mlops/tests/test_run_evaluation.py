import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

MODULE_PATH = Path(__file__).resolve().parents[1] / "run_evaluation.py"
SPEC = importlib.util.spec_from_file_location("run_evaluation", MODULE_PATH)
runner = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(runner)


class EvaluationRunnerTest(unittest.TestCase):
    def test_receipt_is_signed_and_contains_no_prompt_input_or_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            api_key, signing_key, receipt = root / "api-key", root / "signing-key", root / "receipt.json"
            api_key.write_text("fixture-api-key")
            signing_key.write_bytes(b"s" * 32)

            def fixture(_api_key, _model, _prompt, case):
                return case["required_concepts_any"][0], 10, 2, 5, 25

            with patch.object(runner, "provider_call", fixture), patch.object(
                runner, "repository_commit", return_value="a" * 40
            ):
                result = runner.run(api_key, signing_key, receipt)

            serialized = receipt.read_text()
            suite = json.loads((runner.ROOT / "mlops/evaluations/conversation-v1.json").read_text())
            self.assertEqual(result["case_count"], len(suite["cases"]))
            self.assertEqual(result["critical_pass_rate_basis_points"], 10000)
            self.assertEqual(result["repository_commit"], "a" * 40)
            self.assertEqual(result["evaluator_version"], "1.0.0")
            self.assertTrue(result["promotion_passed"])
            self.assertTrue(all(result["gates"].values()))
            self.assertTrue(runner.verify_signature(result, b"s" * 32))
            result["passed_count"] -= 1
            self.assertFalse(runner.verify_signature(result, b"s" * 32))
            self.assertEqual(receipt.stat().st_mode & 0o777, 0o600)
            self.assertNotIn("fixture-api-key", serialized)
            for case in suite["cases"]:
                self.assertNotIn(case["input"], serialized)
                self.assertNotIn(case["required_concepts_any"][0], serialized)


if __name__ == "__main__":
    unittest.main()
