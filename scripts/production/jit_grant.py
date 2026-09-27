#!/usr/bin/env python3
"""Verify two-person, time-bounded JIT grants using OpenSSH signatures.

The grant payload is signed by two distinct identities with ``ssh-keygen -Y``.
This tool never creates production approver keys and never grants authority by
itself; the host-side wrapper must map the verified scope to a bounded sudo
policy and remove it at expiry.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

MAX_WRITE_MINUTES = 30
IDENTITY_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._@:+-]{0,127}$")
SCOPE_RE = re.compile(r"^[a-z][a-z0-9_.-]{0,63}$")


def parse_time(value: object) -> datetime:
    if not isinstance(value, str):
        raise ValueError("timestamp must be an ISO-8601 string")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError("timestamp is invalid") from exc
    if parsed.tzinfo is None:
        raise ValueError("timestamp must include a timezone")
    return parsed.astimezone(timezone.utc)


def canonical_payload(grant: dict) -> bytes:
    fields = {
        "schema_version": grant.get("schema_version"),
        "grant_id": grant.get("grant_id"),
        "subject": grant.get("subject"),
        "scope": grant.get("scope"),
        "reason": grant.get("reason"),
        "ticket": grant.get("ticket"),
        "issued_at": grant.get("issued_at"),
        "expires_at": grant.get("expires_at"),
    }
    return (json.dumps(fields, ensure_ascii=True, sort_keys=True, separators=(",", ":")) + "\n").encode()


def validate_shape(grant: dict, now: datetime) -> tuple[datetime, datetime]:
    if grant.get("schema_version") != 1:
        raise ValueError("schema_version must be 1")
    for name in ("grant_id", "subject", "reason", "ticket"):
        value = grant.get(name)
        if not isinstance(value, str) or not value.strip() or len(value) > 256:
            raise ValueError(f"{name} is invalid")
    scope = grant.get("scope")
    if not isinstance(scope, list) or not scope or len(scope) > 16 or any(
        not isinstance(item, str) or not SCOPE_RE.fullmatch(item) for item in scope
    ):
        raise ValueError("scope is invalid")
    if scope != sorted(set(scope)):
        raise ValueError("scope must be sorted and unique")
    issued = parse_time(grant.get("issued_at"))
    expires = parse_time(grant.get("expires_at"))
    if issued > now + timedelta(minutes=2):
        raise ValueError("issued_at is in the future")
    if expires <= now:
        raise ValueError("grant is expired")
    if expires <= issued or expires - issued > timedelta(minutes=MAX_WRITE_MINUTES):
        raise ValueError("grant lifetime exceeds the 30-minute maximum")
    return issued, expires


def verify_signature(payload: bytes, signature: Path, allowed_signers: Path, identity: str, namespace: str) -> None:
    if not IDENTITY_RE.fullmatch(identity):
        raise ValueError("approver identity is invalid")
    if not signature.is_file() or not allowed_signers.is_file():
        raise ValueError("signature or allowed-signers file is missing")
    command = [
        "ssh-keygen",
        "-Y",
        "verify",
        "-f",
        str(allowed_signers),
        "-I",
        identity,
        "-n",
        namespace,
        "-s",
        str(signature),
    ]
    result = subprocess.run(command, input=payload, capture_output=True, check=False, timeout=10)
    if result.returncode != 0:
        raise ValueError(f"signature verification failed for {identity}")


def verify(grant_path: Path, approvals: list[tuple[str, Path]], allowed_signers: Path, namespace: str, now: datetime) -> dict:
    grant = json.loads(grant_path.read_text(encoding="utf-8"))
    if not isinstance(grant, dict):
        raise ValueError("grant must be an object")
    _, expires = validate_shape(grant, now)
    if len(approvals) != 2:
        raise ValueError("exactly two approvals are required")
    identities = [identity for identity, _ in approvals]
    if len(set(identities)) != 2:
        raise ValueError("approver identities must be distinct")
    payload = canonical_payload(grant)
    for identity, signature in approvals:
        verify_signature(payload, signature, allowed_signers, identity, namespace)
    return {
        "grant_id": grant["grant_id"],
        "subject": grant["subject"],
        "scope": grant["scope"],
        "expires_at": expires.isoformat().replace("+00:00", "Z"),
        "approvers": sorted(identities),
        "verification": "PASSED",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("grant", type=Path)
    parser.add_argument("--approval", action="append", required=True, metavar="IDENTITY=PATH")
    parser.add_argument("--allowed-signers", type=Path, required=True)
    parser.add_argument("--namespace", default="hooshix-jit-v1")
    args = parser.parse_args()
    try:
        approvals = []
        for item in args.approval:
            identity, separator, path = item.partition("=")
            if not separator or not identity or not path:
                raise ValueError("--approval must be IDENTITY=PATH")
            approvals.append((identity, Path(path)))
        receipt = verify(args.grant, approvals, args.allowed_signers, args.namespace, datetime.now(timezone.utc))
    except (OSError, json.JSONDecodeError, ValueError, subprocess.SubprocessError) as exc:
        print(f"JIT_GRANT_REJECTED: {exc}")
        return 1
    print(json.dumps(receipt, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
