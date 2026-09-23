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
    class ResponseFixture:
        def __init__(self, value):
            self.value = value

        def __enter__(self):
            return self

        def __exit__(self, _exc_type, _exc_value, _traceback):
            return False

        def read(self, _limit):
            return json.dumps(self.value).encode()

    def test_provider_call_accepts_completed_refusal_content(self):
        response = self.ResponseFixture({
            "status": "completed",
            "model": "gpt-test-2026-01-01",
            "output": [{"type": "message", "content": [{"type": "refusal", "refusal": "cannot help"}]}],
            "usage": {"input_tokens": 10, "input_tokens_details": {"cached_tokens": 2}, "output_tokens": 5},
        })
        with patch.object(runner.urllib.request, "urlopen", return_value=response):
            result = runner.provider_call(
                "fixture-api-key",
                {"provider_model_id": "gpt-test-2026-01-01"},
                "fixture prompt",
                {"input": "fixture input", "max_output_tokens": 10},
            )

        self.assertEqual(result.outcome, "COMPLETED_REFUSAL")
        self.assertEqual(result.text, "cannot help")
        self.assertEqual(result.cached_tokens, 2)

    def test_provider_call_classifies_incomplete_without_usage(self):
        response = self.ResponseFixture({
            "status": "incomplete",
            "model": "gpt-test-2026-01-01",
            "output": [],
            "usage": None,
            "incomplete_details": {"reason": "max_output_tokens"},
        })
        with patch.object(runner.urllib.request, "urlopen", return_value=response):
            with self.assertRaises(runner.ProviderCallError) as raised:
                runner.provider_call(
                    "fixture-api-key",
                    {"provider_model_id": "gpt-test-2026-01-01"},
                    "fixture prompt",
                    {"input": "fixture input", "max_output_tokens": 10},
                )

        self.assertEqual(raised.exception.outcome, "INCOMPLETE_MAX_OUTPUT_TOKENS")
        self.assertEqual(raised.exception.input_tokens, 0)

    def test_receipt_is_signed_and_contains_no_prompt_input_or_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            api_key, signing_key, receipt = root / "api-key", root / "signing-key", root / "receipt.json"
            api_key.write_text("fixture-api-key")
            signing_key.write_bytes(b"s" * 32)

            def fixture(_api_key, _model, _prompt, case):
                return runner.ProviderResult(case["required_concepts_any"][0], 10, 2, 5, 25, "COMPLETED_TEXT")

            with patch.object(runner, "provider_call", fixture), patch.object(
                runner, "repository_commit", return_value="a" * 40
            ):
                result = runner.run(api_key, signing_key, receipt)

            serialized = receipt.read_text()
            suite = json.loads((runner.ROOT / "mlops/evaluations/conversation-v1.json").read_text())
            self.assertEqual(result["case_count"], len(suite["cases"]))
            self.assertEqual(result["schema_version"], 2)
            self.assertEqual(result["critical_pass_rate_basis_points"], 10000)
            self.assertEqual(result["repository_commit"], "a" * 40)
            self.assertEqual(result["evaluator_version"], "1.1.0")
            self.assertTrue(result["promotion_passed"])
            self.assertTrue(all(result["gates"].values()))
            self.assertTrue(all(item["provider_outcome"] == "COMPLETED_TEXT" for item in result["results"]))
            self.assertTrue(runner.verify_signature(result, b"s" * 32))
            result["passed_count"] -= 1
            self.assertFalse(runner.verify_signature(result, b"s" * 32))
            self.assertEqual(receipt.stat().st_mode & 0o777, 0o600)
            self.assertNotIn("fixture-api-key", serialized)
            for case in suite["cases"]:
                self.assertNotIn(case["input"], serialized)
                self.assertNotIn(case["required_concepts_any"][0], serialized)

    def test_provider_error_is_bounded_and_usage_is_charged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            api_key, signing_key, receipt = root / "api-key", root / "signing-key", root / "receipt.json"
            api_key.write_text("fixture-api-key")
            signing_key.write_bytes(b"s" * 32)

            def fixture(_api_key, _model, _prompt, _case):
                raise runner.ProviderCallError(
                    "INCOMPLETE_MAX_OUTPUT_TOKENS",
                    input_tokens=10,
                    cached_tokens=2,
                    output_tokens=5,
                    latency_ms=25,
                )

            with patch.object(runner, "provider_call", fixture), patch.object(
                runner, "repository_commit", return_value="a" * 40
            ):
                result = runner.run(api_key, signing_key, receipt)

            self.assertEqual(result["error_count"], result["case_count"])
            self.assertFalse(result["promotion_passed"])
            self.assertTrue(
                all(item["provider_outcome"] == "INCOMPLETE_MAX_OUTPUT_TOKENS" for item in result["results"]))
            self.assertGreater(result["p95_cost_micro_usd"], 0)
            self.assertNotIn("fixture-api-key", receipt.read_text())


if __name__ == "__main__":
    unittest.main()
