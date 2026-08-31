#!/usr/bin/env python3
"""Validate aggregate Stage 7 HIBP/provider/erasure staging evidence."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import re
from pathlib import Path

SCHEMA = "hooshix-stage7-external-runtime-v1"
SHA256 = re.compile(r"^[0-9a-f]{64}$")
REVISION = re.compile(r"^[0-9a-f]{40}$")


def _positive_number(value: object) -> bool:
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(value)
        and value > 0
    )


def _exact_keys(value: object, expected: set[str], label: str, errors: list[str]) -> dict:
    if not isinstance(value, dict) or set(value) != expected:
        errors.append(f"{label} structure is invalid")
        return {}
    return value


def _all_true(value: dict, fields: tuple[str, ...], label: str, errors: list[str]) -> None:
    for field in fields:
        if value.get(field) is not True:
            errors.append(f"{label}.{field} must be true")


def validate_evidence(data: object) -> list[str]:
    errors: list[str] = []
    root = _exact_keys(
        data,
        {
            "schema",
            "git_revision",
            "environment",
            "recorded_at",
            "hibp",
            "providers",
            "erasure",
            "passed",
        },
        "evidence",
        errors,
    )
    if root.get("schema") != SCHEMA:
        errors.append("schema is invalid")
    if not isinstance(root.get("git_revision"), str) or not REVISION.fullmatch(
        root["git_revision"]
    ):
        errors.append("git_revision must be a full lowercase SHA")
    if root.get("environment") != "staging":
        errors.append("environment must be staging")
    try:
        recorded_at = dt.datetime.fromisoformat(str(root.get("recorded_at")).replace("Z", "+00:00"))
        if recorded_at.tzinfo is None:
            raise ValueError
    except ValueError:
        errors.append("recorded_at must be an offset timestamp")

    hibp = _exact_keys(
        root.get("hibp"),
        {
            "executed",
            "source_kind",
            "source_sha256",
            "source_age_days",
            "record_count",
            "observed_max_prefix_cardinality",
            "observed_max_serialized_response_bytes",
            "cold_p99_ms",
            "warm_p99_ms",
            "saturation_concurrency",
            "rebuild_redeploy_recovery",
            "passed",
        },
        "hibp",
        errors,
    )
    _all_true(hibp, ("executed", "rebuild_redeploy_recovery", "passed"), "hibp", errors)
    if hibp.get("source_kind") != "HIBP_PWNED_PASSWORDS_COMPLETE_DOWNLOAD":
        errors.append("hibp.source_kind is not complete-corpus evidence")
    if not isinstance(hibp.get("source_sha256"), str) or not SHA256.fullmatch(
        hibp["source_sha256"]
    ):
        errors.append("hibp.source_sha256 is invalid")
    age = hibp.get("source_age_days")
    if not isinstance(age, int) or isinstance(age, bool) or not 0 <= age <= 35:
        errors.append("hibp.source_age_days is outside the readiness bound")
    for field in (
        "record_count",
        "observed_max_prefix_cardinality",
        "observed_max_serialized_response_bytes",
        "cold_p99_ms",
        "warm_p99_ms",
        "saturation_concurrency",
    ):
        if not _positive_number(hibp.get(field)):
            errors.append(f"hibp.{field} must be positive")

    providers = _exact_keys(
        root.get("providers"), {"google", "liara", "ippanel"}, "providers", errors
    )
    provider_fields = {
        "google": ("executed", "success", "state_nonce_pkce_replay_failure", "no_email_auto_link", "passed"),
        "liara": ("executed", "starttls_auth", "definitive_acceptance", "auth_failure", "ambiguity", "passed"),
        "ippanel": ("executed", "one_recipient", "definitive_acceptance", "recipient_delivery", "ambiguity", "passed"),
    }
    for provider, fields in provider_fields.items():
        evidence = _exact_keys(providers.get(provider), set(fields), provider, errors)
        _all_true(evidence, fields, provider, errors)

    erasure = _exact_keys(
        root.get("erasure"),
        {
            "executed",
            "redeploy_completed",
            "restore_completed",
            "participant_count",
            "identity_deleted",
            "no_reappearance",
            "passed",
        },
        "erasure",
        errors,
    )
    _all_true(
        erasure,
        ("executed", "redeploy_completed", "restore_completed", "identity_deleted", "no_reappearance", "passed"),
        "erasure",
        errors,
    )
    if erasure.get("participant_count") != 4:
        errors.append("erasure.participant_count must be four")

    components_passed = (
        hibp.get("passed") is True
        and all(isinstance(providers.get(name), dict) and providers[name].get("passed") is True for name in provider_fields)
        and erasure.get("passed") is True
    )
    if root.get("passed") is not components_passed:
        errors.append("passed does not match component evidence")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence", type=Path)
    args = parser.parse_args()
    try:
        data = json.loads(args.evidence.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        print(f"external runtime evidence cannot be loaded: {exception}")
        return 2
    errors = validate_evidence(data)
    if errors:
        for error in errors:
            print(error)
        return 1
    print("External runtime evidence PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
