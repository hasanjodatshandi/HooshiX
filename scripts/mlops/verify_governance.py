#!/usr/bin/env python3
"""Validate the versioned HooshiX model-execution governance bundle."""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
SEMVER = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
IDENTIFIER = re.compile(r"^[a-z][a-z0-9-]{2,95}$")
EMAIL = re.compile(r"\b[^\s@]+@[^\s@]+\.[^\s@]+\b")
PHONE = re.compile(r"(?<!\d)(?:\+?\d[ -]?){10,15}(?!\d)")


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def require(errors: list[str], condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)


def validate_evaluation(root: Path, errors: list[str]) -> dict[str, Any]:
    path = root / "mlops/evaluations/conversation-v1.json"
    try:
        suite = load_json(path)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        errors.append(f"evaluation suite cannot be read: {exc}")
        return {}

    require(errors, isinstance(suite, dict), "evaluation suite root must be an object")
    if not isinstance(suite, dict):
        return {}

    require(errors, suite.get("schema_version") == 1, "evaluation schema_version must be 1")
    require(errors, SEMVER.fullmatch(str(suite.get("suite_version", ""))) is not None, "evaluation suite_version must be SemVer")
    require(errors, suite.get("data_classification") == "SYNTHETIC_NON_PII", "evaluation data must be classified SYNTHETIC_NON_PII")
    cases = suite.get("cases")
    require(errors, isinstance(cases, list) and len(cases) >= 10, "evaluation suite must contain at least 10 cases")
    if not isinstance(cases, list):
        return suite

    seen: set[str] = set()
    locales: set[str] = set()
    critical_categories: set[str] = set()
    allowed_categories = {"QUALITY", "SAFETY", "PRIVACY", "PROMPT_INJECTION", "AUTHORITY_BOUNDARY", "HIGH_STAKES"}
    for index, case in enumerate(cases):
        prefix = f"evaluation case {index}"
        require(errors, isinstance(case, dict), f"{prefix} must be an object")
        if not isinstance(case, dict):
            continue
        case_id = case.get("id")
        require(errors, isinstance(case_id, str) and IDENTIFIER.fullmatch(case_id) is not None, f"{prefix} id is invalid")
        require(errors, case_id not in seen, f"duplicate evaluation case id: {case_id}")
        if isinstance(case_id, str):
            seen.add(case_id)
        category = case.get("category")
        severity = case.get("severity")
        locale = case.get("locale")
        require(errors, category in allowed_categories, f"{prefix} category is invalid")
        require(errors, severity in {"HIGH", "CRITICAL"}, f"{prefix} severity is invalid")
        require(errors, locale in {"fa", "en"}, f"{prefix} locale is invalid")
        if isinstance(locale, str):
            locales.add(locale)
        if severity == "CRITICAL" and isinstance(category, str):
            critical_categories.add(category)
        for field in ("input", "expected_behavior"):
            require(errors, isinstance(case.get(field), str) and bool(case[field].strip()), f"{prefix} {field} is required")
        for field in ("required_concepts_any", "forbidden_fragments"):
            value = case.get(field)
            require(errors, isinstance(value, list) and bool(value) and all(isinstance(item, str) and item for item in value), f"{prefix} {field} must be a non-empty string list")
        max_tokens = case.get("max_output_tokens")
        require(errors, isinstance(max_tokens, int) and 1 <= max_tokens <= 2000, f"{prefix} max_output_tokens is invalid")
        serialized = json.dumps(case, ensure_ascii=False)
        require(errors, EMAIL.search(serialized) is None and PHONE.search(serialized) is None, f"{prefix} appears to contain direct contact PII")

    require(errors, locales == {"fa", "en"}, "evaluation suite must cover fa and en")
    require(errors, {"SAFETY", "PRIVACY", "PROMPT_INJECTION", "AUTHORITY_BOUNDARY"} <= critical_categories, "critical safety, privacy, injection, and authority cases are required")
    return suite


def validate_governance(root: Path, suite: dict[str, Any], errors: list[str]) -> None:
    path = root / "mlops/governance/v1/governance.json"
    try:
        governance = load_json(path)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        errors.append(f"governance bundle cannot be read: {exc}")
        return

    require(errors, isinstance(governance, dict), "governance root must be an object")
    if not isinstance(governance, dict):
        return

    require(errors, governance.get("schema_version") == 1, "governance schema_version must be 1")
    require(errors, SEMVER.fullmatch(str(governance.get("governance_version", ""))) is not None, "governance_version must be SemVer")
    require(errors, governance.get("decision_status") == "APPROVED_ARCHITECTURE_RUNTIME_DISABLED", "Stage 8 decision status must keep runtime disabled")
    models = governance.get("model_catalog")
    prompts = governance.get("prompt_catalog")
    prices = governance.get("price_catalog")
    require(errors, isinstance(models, list) and len(models) == 1, "v1 must define exactly one model candidate")
    require(errors, isinstance(prompts, list) and len(prompts) == 1, "v1 must define exactly one prompt candidate")
    require(errors, isinstance(prices, list) and len(prices) == 1, "v1 must define exactly one price snapshot")
    if not all(isinstance(value, list) and len(value) == 1 for value in (models, prompts, prices)):
        return
    model, prompt, price = models[0], prompts[0], prices[0]
    require(errors, all(isinstance(value, dict) for value in (model, prompt, price)), "catalog entries must be objects")
    if not all(isinstance(value, dict) for value in (model, prompt, price)):
        return

    approval = governance.get("provider_data_controls", {})
    require(errors, isinstance(approval, dict), "provider_data_controls must be an object")
    if not isinstance(approval, dict):
        approval = {}
    pending = approval.get("approval_status") != "APPROVED"
    require(errors, model.get("provider") == "openai" and model.get("endpoint") == "responses", "model must use the reviewed OpenAI Responses boundary")
    require(errors, re.fullmatch(r"gpt-[a-z0-9.-]+-20[0-9]{2}-[0-9]{2}-[0-9]{2}", str(model.get("provider_model_id", ""))) is not None, "provider model must be an exact dated snapshot")
    require(errors, model.get("lifecycle") == "CANDIDATE", "initial model must remain CANDIDATE")
    require(errors, model.get("execution_enabled") is False, "Stage 8 model execution must remain disabled")
    require(errors, not pending or approval.get("runtime_must_remain_disabled_while_pending") is True, "pending provider approval must fail closed")
    require(errors, model.get("store") is False and model.get("background") is False and model.get("tools_enabled") is False, "model requests must be stateless, foreground, and tool-free")
    request_settings = approval.get("required_request_settings", {})
    require(errors, request_settings == {"store": False, "background": False, "tools": []}, "provider request settings must be exact")
    require(errors, approval.get("provider_state_references_allowed") is False, "provider state references must be prohibited")

    resolved_root = root.resolve()
    prompt_path = (root / str(prompt.get("path", ""))).resolve()
    prompt_inside_root = prompt_path.is_relative_to(resolved_root)
    require(errors, prompt_inside_root and prompt_path.is_file(), "prompt path must resolve inside the repository")
    if prompt_inside_root and prompt_path.is_file():
        digest = hashlib.sha256(prompt_path.read_bytes()).hexdigest()
        require(errors, digest == prompt.get("sha256"), "prompt sha256 does not match prompt content")

    require(errors, model.get("prompt_id") == prompt.get("prompt_id") and model.get("prompt_version") == prompt.get("prompt_version"), "model prompt reference is inconsistent")
    require(errors, model.get("price_id") == price.get("price_id") and model.get("price_version") == price.get("price_version"), "model price reference is inconsistent")
    require(errors, model.get("evaluation_suite_id") == suite.get("suite_id") and model.get("evaluation_suite_version") == suite.get("suite_version"), "model evaluation-suite reference is inconsistent")

    require(errors, price.get("unit") == "MICRO_USD_PER_MILLION_TOKENS", "price unit must use integer micro-USD")
    input_price, output_price = price.get("input"), price.get("output")
    max_input_tokens, max_output_tokens = model.get("max_input_tokens"), model.get("max_output_tokens")
    require(errors, isinstance(input_price, int) and input_price > 0, "input price must be a positive integer")
    require(errors, isinstance(output_price, int) and output_price > 0, "output price must be a positive integer")
    require(errors, isinstance(max_input_tokens, int) and 1 <= max_input_tokens <= 16000, "model max_input_tokens is invalid")
    require(errors, isinstance(max_output_tokens, int) and 1 <= max_output_tokens <= 4096, "model max_output_tokens is invalid")
    require(errors, price.get("source") == "https://developers.openai.com/api/docs/models/gpt-5.4", "price source must be the reviewed official model page")
    if all(isinstance(value, int) for value in (input_price, output_price, max_input_tokens, max_output_tokens)):
        worst_case = (max_input_tokens * input_price + 999999) // 1000000
        worst_case += (max_output_tokens * output_price + 999999) // 1000000
        require(errors, price.get("maximum_request_reservation_micro_usd") == worst_case, "maximum request reservation must equal worst-case catalog price")

    promotion = governance.get("promotion_policy", {})
    require(errors, isinstance(promotion, dict), "promotion_policy must be an object")
    if not isinstance(promotion, dict):
        promotion = {}
    require(errors, promotion.get("minimum_critical_eval_pass_rate_basis_points") == 10000, "critical eval pass rate must be 100%")
    require(errors, promotion.get("maximum_eval_error_count") == 0, "evaluation errors must block promotion")
    require(errors, promotion.get("hard_provider_deadline_ms") == 60000, "provider deadline must preserve ADR-0054")
    approvals = promotion.get("required_approvals", [])
    require(errors, isinstance(approvals, list) and all(isinstance(item, str) for item in approvals) and set(approvals) == {"product-owner", "security-owner", "privacy-owner", "platform-owner"}, "promotion owner approvals are incomplete")
    steps = promotion.get("canary_steps_percent")
    valid_steps = isinstance(steps, list) and bool(steps) and all(isinstance(step, int) for step in steps)
    require(errors, valid_steps and steps == sorted(set(steps)) and steps[0] == 1 and steps[-1] == 100, "canary steps must be unique, ordered, and reach 100%")
    triggers = promotion.get("rollback_triggers", {})
    require(errors, isinstance(triggers, dict), "rollback_triggers must be an object")
    if not isinstance(triggers, dict):
        triggers = {}
    require(errors, triggers.get("confirmed_critical_safety_or_privacy_incidents") == 1, "one confirmed critical incident must trigger rollback")

    safety = governance.get("safety_policy", {})
    require(errors, isinstance(safety, dict), "safety_policy must be an object")
    if not isinstance(safety, dict):
        safety = {}
    require(errors, safety.get("model_output_is_untrusted") is True and safety.get("model_output_can_grant_authority") is False and safety.get("model_output_can_execute_side_effects") is False, "model output authority boundary is invalid")
    blocked_capabilities = safety.get("blocked_capabilities", [])
    require(errors, isinstance(blocked_capabilities, list) and all(isinstance(item, str) for item in blocked_capabilities) and set(blocked_capabilities) >= {"tools", "mcp", "web-search", "code-execution", "external-actions"}, "v1 blocked capabilities are incomplete")

    feedback = governance.get("feedback_policy", {})
    require(errors, isinstance(feedback, dict), "feedback_policy must be an object")
    if not isinstance(feedback, dict):
        feedback = {}
    require(errors, feedback.get("free_text_enabled") is False and feedback.get("training_use_enabled") is False, "v1 feedback must not collect free text or train models")
    require(errors, feedback.get("tenant_and_erasure_scope_required") is True, "feedback must participate in tenant and erasure scope")
    drift = governance.get("drift_policy", {})
    require(errors, isinstance(drift, dict), "drift_policy must be an object")
    if not isinstance(drift, dict):
        drift = {}
    require(errors, drift.get("automatic_model_training_from_production_content") is False, "production content must not train models automatically")
    rerun_triggers = drift.get("mandatory_rerun_triggers", [])
    require(errors, isinstance(rerun_triggers, list) and all(isinstance(item, str) for item in rerun_triggers) and set(rerun_triggers) >= {"model-change", "prompt-change", "price-change", "provider-policy-change", "safety-incident"}, "drift rerun triggers are incomplete")


def validate(root: Path = ROOT) -> list[str]:
    errors: list[str] = []
    for relative, expected_id in (
        ("mlops/schemas/evaluation-suite.schema.json", "https://hooshix.internal/schemas/mlops/evaluation-suite-v1.json"),
        ("mlops/schemas/governance.schema.json", "https://hooshix.internal/schemas/mlops/governance-v1.json"),
    ):
        try:
            schema = load_json(root / relative)
            require(errors, schema.get("$id") == expected_id, f"{relative} has an invalid $id")
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            errors.append(f"{relative} cannot be read: {exc}")
    suite = validate_evaluation(root, errors)
    validate_governance(root, suite, errors)
    return errors


def main() -> int:
    errors = validate(ROOT)
    if errors:
        print("MLOps governance verification FAILED:")
        for error in errors:
            print(f"- {error}")
        return 1
    print("MLOps governance verification PASSED (versioned catalogs, prompt, eval suite, and fail-closed policies).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
