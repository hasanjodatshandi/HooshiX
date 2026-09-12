#!/usr/bin/env python3
"""Exercise the official SMS.ir Sandbox contract without exposing credentials or identifiers."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import ssl
import stat
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

ENDPOINT = "https://api.sms.ir/v1/send/verify"
MAXIMUM_RESPONSE_BYTES = 64 * 1024
SANDBOX_TEMPLATE_ID = 123456


def _load_secret(path: Path) -> str:
    parent = path.parent.lstat()
    if (
        stat.S_ISLNK(parent.st_mode)
        or not stat.S_ISDIR(parent.st_mode)
        or parent.st_uid != os.getuid()
        or stat.S_IMODE(parent.st_mode) != 0o700
    ):
        raise ValueError("SMS.ir API key parent must be a user-owned mode 0700 directory")
    info = path.lstat()
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        raise ValueError("SMS.ir API key path must be a regular non-symlink file")
    if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600:
        raise ValueError("SMS.ir API key file must be user-owned mode 0600")
    value = path.read_text(encoding="utf-8").strip()
    if not value or "\n" in value or "\r" in value:
        raise ValueError("SMS.ir API key file structure is invalid")
    return value


def _write_private_json(path: Path, value: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    parent = path.parent.lstat()
    if stat.S_ISLNK(parent.st_mode) or not stat.S_ISDIR(parent.st_mode):
        raise ValueError("SMS.ir evidence parent must be a regular directory")
    if parent.st_uid != os.getuid():
        raise ValueError("SMS.ir evidence parent must be user-owned")
    path.parent.chmod(0o700)
    descriptor, temporary_name = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(value, handle, sort_keys=True)
            handle.write("\n")
        temporary.chmod(0o600)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _post(api_key: str, payload: dict[str, object]) -> tuple[int, dict[str, object] | None]:
    request = urllib.request.Request(
        ENDPOINT,
        data=json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
        method="POST",
        headers={
            "Accept": "application/json",
            "Content-Type": "application/json",
            "X-API-KEY": api_key,
        },
    )
    try:
        with urllib.request.urlopen(
            request, timeout=10, context=ssl.create_default_context()
        ) as response:
            status = response.status
            raw = response.read(MAXIMUM_RESPONSE_BYTES + 1)
    except urllib.error.HTTPError as error:
        status = error.code
        raw = error.read(MAXIMUM_RESPONSE_BYTES + 1)
    if len(raw) > MAXIMUM_RESPONSE_BYTES:
        raise ValueError("SMS.ir response exceeded the safety bound")
    try:
        decoded = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return status, None
    return status, decoded if isinstance(decoded, dict) else None


def _provider_status(body: dict[str, object] | None) -> int | None:
    if body is None:
        return None
    value = body.get("status")
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def run(key_path: Path, output: Path) -> dict[str, object]:
    key = _load_secret(key_path)
    valid_payload = {
        "mobile": "9190000904",
        "templateId": SANDBOX_TEMPLATE_ID,
        "parameters": [{"name": "Code", "value": "12345"}],
    }
    valid_http, valid_body = _post(key, valid_payload)
    data = valid_body.get("data") if valid_body else None
    simulated_acceptance = bool(
        valid_http == 200
        and _provider_status(valid_body) == 1
        and isinstance(data, dict)
        and isinstance(data.get("messageId"), int)
        and data["messageId"] > 0
    )

    invalid_http, invalid_body = _post("invalid-sandbox-key", valid_payload)
    auth_failure = invalid_http == 401 or _provider_status(invalid_body) == 10

    invalid_payload = dict(valid_payload)
    invalid_payload["mobile"] = ""
    validation_http, validation_body = _post(key, invalid_payload)
    request_validation = validation_http == 400 or _provider_status(validation_body) not in (
        None,
        1,
    )

    passed = simulated_acceptance and auth_failure and request_validation
    evidence = {
        "schema": "hooshix-smsir-sandbox-probe-v1",
        "recorded_at": dt.datetime.now(dt.UTC).isoformat().replace("+00:00", "Z"),
        "provider": "SMSIR_SANDBOX",
        "executed": True,
        "tls_hostname_verified": True,
        "authentication_success": simulated_acceptance,
        "request_validation": request_validation,
        "simulated_acceptance": simulated_acceptance,
        "auth_failure": auth_failure,
        "real_delivery_claimed": False,
        "passed": passed,
    }
    _write_private_json(output, evidence)
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api-key-file", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    try:
        evidence = run(arguments.api_key_file, arguments.output)
    except (OSError, ValueError, urllib.error.URLError) as error:
        print(f"SMS.ir Sandbox probe FAILED: {type(error).__name__}")
        return 1
    print(
        "SMS.ir Sandbox probe "
        + ("PASSED" if evidence["passed"] else "FAILED")
        + "; simulated only; no real delivery claimed"
    )
    return 0 if evidence["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
