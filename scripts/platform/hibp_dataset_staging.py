#!/usr/bin/env python3
"""Validate and stage a complete-corpus HIBP release for local Kubernetes evidence."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import stat
from pathlib import Path

SOURCE_KIND = "HIBP_PWNED_PASSWORDS_COMPLETE_DOWNLOAD"
PREFIX_BOUND = 4096
RESPONSE_BOUND = 131_072
SHA256 = re.compile(r"^[0-9a-f]{64}$")
REVISION = re.compile(r"^[0-9a-f]{40}$")
SAFE_TOKEN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+:/-]{0,127}$")
MANIFEST_KEYS = {
    "manifest_version",
    "format_version",
    "sqlite_schema_version",
    "source_kind",
    "hash_mode",
    "retrieval_started_at_utc",
    "retrieval_completed_at_utc",
    "source_artifact_sha256",
    "acquisition_tool",
    "builder_git_revision",
    "source_line_count",
    "record_count",
    "duplicate_line_count",
    "max_prefix_cardinality",
    "max_serialized_response_bytes",
    "prefix_cardinality_bound",
    "serialized_response_bytes_bound",
    "content_sha256",
    "sqlite_artifact_sha256",
}


def _regular_non_symlink(path: Path, label: str) -> None:
    metadata = path.lstat()
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
        raise ValueError(f"{label} must be a regular non-symlink file")


def _positive_int(data: dict, field: str) -> int:
    value = data.get(field)
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise ValueError(f"HIBP release {field} must be positive")
    return value


def _timestamp(value: object, field: str) -> dt.datetime:
    if not isinstance(value, str):
        raise ValueError(f"HIBP release {field} is invalid")
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exception:
        raise ValueError(f"HIBP release {field} is invalid") from exception
    if parsed.tzinfo is None:
        raise ValueError(f"HIBP release {field} must include an offset")
    return parsed.astimezone(dt.timezone.utc)


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(8 * 1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def validate_release(
    sqlite: Path,
    manifest: Path,
    expected_revision: str,
    now: dt.datetime,
) -> tuple[dict, str]:
    sqlite = sqlite.absolute()
    manifest = manifest.absolute()
    _regular_non_symlink(sqlite, "HIBP SQLite artifact")
    _regular_non_symlink(manifest, "HIBP release manifest")
    if manifest.stat().st_size > 64 * 1024:
        raise ValueError("HIBP release manifest is oversized")
    try:
        data = json.loads(manifest.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exception:
        raise ValueError("HIBP release manifest is invalid JSON") from exception
    if not isinstance(data, dict) or set(data) != MANIFEST_KEYS:
        raise ValueError("HIBP release manifest structure is invalid")
    if (data["manifest_version"], data["format_version"], data["sqlite_schema_version"]) != (
        2,
        1,
        1,
    ):
        raise ValueError("HIBP release manifest version is unsupported")
    if data["source_kind"] != SOURCE_KIND or data["hash_mode"] != "SHA1":
        raise ValueError("HIBP release source/hash identity is invalid")
    for field in (
        "source_artifact_sha256",
        "content_sha256",
        "sqlite_artifact_sha256",
    ):
        if not isinstance(data[field], str) or SHA256.fullmatch(data[field]) is None:
            raise ValueError(f"HIBP release {field} is invalid")
    tool = data["acquisition_tool"]
    if (
        not isinstance(tool, dict)
        or set(tool) != {"name", "version", "sha256"}
        or not isinstance(tool["name"], str)
        or SAFE_TOKEN.fullmatch(tool["name"]) is None
        or not isinstance(tool["version"], str)
        or SAFE_TOKEN.fullmatch(tool["version"]) is None
        or not isinstance(tool["sha256"], str)
        or SHA256.fullmatch(tool["sha256"]) is None
    ):
        raise ValueError("HIBP release acquisition-tool identity is invalid")
    if not isinstance(expected_revision, str) or REVISION.fullmatch(expected_revision) is None:
        raise ValueError("expected Git revision is invalid")
    if data["builder_git_revision"] != expected_revision:
        raise ValueError("HIBP release builder revision does not match current HEAD")

    started = _timestamp(data["retrieval_started_at_utc"], "retrieval_started_at_utc")
    completed = _timestamp(data["retrieval_completed_at_utc"], "retrieval_completed_at_utc")
    if started > completed:
        raise ValueError("HIBP release retrieval interval is invalid")
    normalized_now = now.astimezone(dt.timezone.utc)
    age = normalized_now - completed
    if age < dt.timedelta(0) or age > dt.timedelta(days=35):
        raise ValueError("HIBP release is outside the 35-day readiness window")

    source_lines = _positive_int(data, "source_line_count")
    records = _positive_int(data, "record_count")
    observed_prefix = _positive_int(data, "max_prefix_cardinality")
    observed_response = _positive_int(data, "max_serialized_response_bytes")
    prefix_bound = _positive_int(data, "prefix_cardinality_bound")
    response_bound = _positive_int(data, "serialized_response_bytes_bound")
    duplicates = data["duplicate_line_count"]
    if (
        not isinstance(duplicates, int)
        or isinstance(duplicates, bool)
        or duplicates < 0
        or source_lines - records != duplicates
    ):
        raise ValueError("HIBP release duplicate counts are inconsistent")
    if observed_prefix > prefix_bound or observed_response > response_bound:
        raise ValueError("HIBP release exceeds its compatibility bounds")
    if prefix_bound != PREFIX_BOUND or response_bound != RESPONSE_BOUND:
        raise ValueError("HIBP release does not use the current reviewed compatibility envelope")
    if _sha256_file(sqlite) != data["sqlite_artifact_sha256"]:
        raise ValueError("HIBP SQLite artifact digest mismatch")
    return data, hashlib.sha256(manifest.read_bytes()).hexdigest()


def stage_private_values(
    data: dict,
    manifest_digest: str,
    overlay: Path,
    state: Path,
) -> None:
    overlay.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    private_metadata = overlay.parent.lstat()
    if (
        not stat.S_ISDIR(private_metadata.st_mode)
        or stat.S_ISLNK(private_metadata.st_mode)
        or private_metadata.st_uid != os.getuid()
        or stat.S_IMODE(private_metadata.st_mode) != 0o700
    ):
        raise ValueError("staging private directory must be user-owned mode 0700")
    overlay.write_text(
        "dataset:\n"
        "  existingClaim: compromised-password-hibp-dataset\n"
        f"  expectedManifestSha256: {manifest_digest}\n"
        f"  requiredSourceKind: {SOURCE_KIND}\n"
        f"  maxPrefixCardinality: {data['prefix_cardinality_bound']}\n"
        f"  maxSerializedResponseBytes: {data['serialized_response_bytes_bound']}\n",
        encoding="ascii",
    )
    os.chmod(overlay, 0o600)
    state.parent.mkdir(parents=True, exist_ok=True)
    state.write_text(
        f"COMPROMISED_PASSWORD_MANIFEST_SHA256={manifest_digest}\n", encoding="ascii"
    )
    os.chmod(state, 0o600)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sqlite", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--overlay-output", required=True, type=Path)
    parser.add_argument("--state-output", required=True, type=Path)
    args = parser.parse_args()
    try:
        data, digest = validate_release(
            args.sqlite, args.manifest, args.revision, dt.datetime.now(dt.timezone.utc)
        )
        stage_private_values(data, digest, args.overlay_output, args.state_output)
    except (OSError, ValueError) as exception:
        print(f"HIBP staging validation failed: {exception}")
        return 1
    print("HIBP staging release validation PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
