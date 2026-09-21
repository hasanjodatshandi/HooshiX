#!/usr/bin/env python3
"""Run the synthetic Conversation suite and emit a signed, content-free receipt."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import re
import subprocess
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
EVALUATOR_VERSION = "1.0.0"


def load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def percentile(values: list[int], percentile_value: float) -> int:
    if not values:
        return 0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int((len(ordered) - 1) * percentile_value)))
    return ordered[index]


def repository_commit() -> str:
    subprocess.run(["git", "diff", "--quiet", "HEAD", "--"], cwd=ROOT, check=True)
    value = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if re.fullmatch(r"[0-9a-f]{40}", value) is None:
        raise ValueError("repository commit is invalid")
    return value


def provider_call(api_key: str, model: dict[str, Any], prompt: str, case: dict[str, Any]) -> tuple[str, int, int, int, int]:
    body = json.dumps({
        "model": model["provider_model_id"],
        "instructions": prompt,
        "input": [{"role": "user", "content": case["input"]}],
        "max_output_tokens": case["max_output_tokens"],
        "reasoning": {"effort": "none"},
        "store": False,
        "background": False,
        "tools": [],
    }).encode()
    request = urllib.request.Request(
        "https://api.openai.com/v1/responses",
        data=body,
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    started = time.monotonic()
    with urllib.request.urlopen(request, timeout=60) as response:
        raw = response.read(524_289)
    elapsed = int((time.monotonic() - started) * 1000)
    if len(raw) > 524_288:
        raise ValueError("provider response exceeds bound")
    result = json.loads(raw)
    if result.get("status") != "completed":
        raise ValueError("provider response is not completed")
    output = "\n".join(
        part["text"]
        for item in result.get("output", []) if item.get("type") == "message"
        for part in item.get("content", []) if part.get("type") == "output_text"
    )
    usage = result.get("usage", {})
    input_tokens = int(usage["input_tokens"])
    cached_tokens = int(usage.get("input_tokens_details", {}).get("cached_tokens", 0))
    if cached_tokens < 0 or cached_tokens > input_tokens:
        raise ValueError("provider cached usage is invalid")
    return output, input_tokens, cached_tokens, int(usage["output_tokens"]), elapsed


def score(case: dict[str, Any], output: str) -> bool:
    normalized = output.casefold()
    required = any(item.casefold() in normalized for item in case["required_concepts_any"])
    forbidden = any(item.casefold() in normalized for item in case["forbidden_fragments"])
    return required and not forbidden


def verify_signature(receipt: dict[str, Any], signing_key: bytes) -> bool:
    payload = dict(receipt)
    signature = payload.pop("signature", None)
    recorded_digest = payload.pop("payload_sha256", None)
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
    expected_digest = hashlib.sha256(canonical).hexdigest()
    expected_signature = hmac.new(signing_key, canonical, hashlib.sha256).hexdigest()
    return (
        isinstance(signature, dict)
        and signature.get("algorithm") == "HMAC-SHA256"
        and hmac.compare_digest(str(signature.get("value", "")), expected_signature)
        and hmac.compare_digest(str(recorded_digest), expected_digest)
    )


def run(api_key_path: Path, signing_key_path: Path, output_path: Path) -> dict[str, Any]:
    suite_path = ROOT / "mlops/evaluations/conversation-v1.json"
    governance_path = ROOT / "mlops/governance/v1/governance.json"
    prompt_path = ROOT / "mlops/prompts/conversation-system-v1.txt"
    suite, governance = load(suite_path), load(governance_path)
    model, price = governance["model_catalog"][0], governance["price_catalog"][0]
    api_key = api_key_path.read_text(encoding="utf-8").strip()
    signing_key = signing_key_path.read_bytes()
    if not api_key or len(signing_key) < 32:
        raise ValueError("private evaluation credentials are invalid")
    results, latencies, costs, errors = [], [], [], 0
    for case in suite["cases"]:
        try:
            text, input_tokens, cached_tokens, output_tokens, latency = provider_call(
                api_key, model, prompt_path.read_text(encoding="utf-8"), case)
            passed = score(case, text)
            cost = ((input_tokens - cached_tokens) * price["input"] + 999_999) // 1_000_000
            cost += (cached_tokens * price["cached_input"] + 999_999) // 1_000_000
            cost += (output_tokens * price["output"] + 999_999) // 1_000_000
            latencies.append(latency)
            costs.append(cost)
        except (OSError, ValueError, KeyError, urllib.error.URLError, json.JSONDecodeError):
            passed, latency, cost = False, 0, 0
            errors += 1
        results.append({"case_id": case["id"], "category": case["category"], "severity": case["severity"], "passed": passed})
    critical = [item for item in results if item["severity"] == "CRITICAL"]
    passed_count = sum(item["passed"] for item in results)
    critical_passed = sum(item["passed"] for item in critical)
    pass_rate = passed_count * 10_000 // len(results)
    critical_pass_rate = critical_passed * 10_000 // len(critical)
    p95_latency = percentile(latencies, 0.95)
    p99_latency = percentile(latencies, 0.99)
    p95_cost = percentile(costs, 0.95)
    promotion = governance["promotion_policy"]
    gates = {
        "total_pass_rate": pass_rate >= promotion["minimum_total_eval_pass_rate_basis_points"],
        "critical_pass_rate": critical_pass_rate >= promotion["minimum_critical_eval_pass_rate_basis_points"],
        "error_count": errors <= promotion["maximum_eval_error_count"],
        "p95_latency": p95_latency <= promotion["maximum_p95_latency_ms"],
        "p99_latency": p99_latency <= promotion["maximum_p99_latency_ms"],
        "p95_cost": p95_cost <= promotion["maximum_p95_cost_micro_usd"],
    }
    receipt = {
        "schema_version": 1,
        "evaluator_version": EVALUATOR_VERSION,
        "repository_commit": repository_commit(),
        "generated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "suite_id": suite["suite_id"], "suite_version": suite["suite_version"],
        "suite_sha256": digest(suite_path), "governance_sha256": digest(governance_path),
        "prompt_sha256": digest(prompt_path), "model_alias": model["logical_id"],
        "provider_model_id": model["provider_model_id"], "prompt_version": model["prompt_version"],
        "price_version": model["price_version"], "case_count": len(results),
        "passed_count": passed_count, "critical_count": len(critical),
        "critical_passed_count": critical_passed, "error_count": errors,
        "pass_rate_basis_points": pass_rate,
        "critical_pass_rate_basis_points": critical_pass_rate,
        "p95_latency_ms": p95_latency, "p99_latency_ms": p99_latency,
        "p95_cost_micro_usd": p95_cost, "gates": gates,
        "promotion_passed": all(gates.values()), "results": results,
    }
    canonical = json.dumps(receipt, sort_keys=True, separators=(",", ":")).encode()
    receipt["payload_sha256"] = hashlib.sha256(canonical).hexdigest()
    receipt["signature"] = {"algorithm": "HMAC-SHA256", "value": hmac.new(signing_key, canonical, hashlib.sha256).hexdigest()}
    if not verify_signature(receipt, signing_key):
        raise ValueError("evaluation receipt signature verification failed")
    output_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    output_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    output_path.chmod(0o600)
    return receipt


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api-key-file", type=Path, required=True)
    parser.add_argument("--signing-key-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    receipt = run(args.api_key_file, args.signing_key_file, args.output)
    print(json.dumps({key: receipt[key] for key in ("case_count", "passed_count", "critical_count", "critical_passed_count", "error_count", "pass_rate_basis_points", "critical_pass_rate_basis_points", "p95_latency_ms", "p99_latency_ms", "p95_cost_micro_usd", "promotion_passed")}))
    return 0 if receipt["promotion_passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
