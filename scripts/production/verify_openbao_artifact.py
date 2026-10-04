#!/usr/bin/env python3
"""Validate public CI scan evidence; never approve OpenBao promotion."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAX_BYTES = 32 * 1024 * 1024
MAX_DB_AGE = timedelta(days=5)  # Pinned Grype default; candidate evidence only.


def load(path: Path) -> dict:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_BYTES:
        raise ValueError("bounded regular evidence file required")
    value = json.loads(path.read_bytes())
    if not isinstance(value, dict):
        raise ValueError("JSON object required")
    return value


def validate(directory: Path, revision: str, now: datetime) -> dict:
    if not re.fullmatch(r"[a-f0-9]{40}", revision):
        raise ValueError("commit required")
    pin = load(ROOT / "infrastructure/production/secrets/openbao-image.json")
    if (not re.fullmatch(r"ghcr\.io/openbao/openbao@sha256:[a-f0-9]{64}", pin["image"])
            or pin["platform"] != "linux/amd64"
            or pin["production_promotion"] != "blocked-until-supply-chain-staging-and-recovery-evidence"):
        raise ValueError("blocked immutable candidate required")
    names = ("syft.json", "cyclonedx.json", "grype.json", "database.json")
    syft, cdx, scan, db = (load(directory / name) for name in names)
    source = syft["source"]
    metadata = source["metadata"]
    if (source["type"] != "image" or metadata["manifestDigest"] != pin["image"].split("@")[1]
            or metadata["os"] != "linux" or metadata["architecture"] != "amd64"
            or pin["image"] not in metadata["repoDigests"]
            or metadata["labels"]["org.opencontainers.image.version"] != "v" + pin["version"]
            or metadata["labels"]["org.opencontainers.image.revision"] != pin["source_revision"]):
        raise ValueError("catalog source does not match pinned image")
    if not syft.get("artifacts") or cdx.get("bomFormat") != "CycloneDX" or not cdx.get("components"):
        raise ValueError("nonempty SBOM required")
    built = datetime.fromisoformat(db["built"].replace("Z", "+00:00"))
    if (db.get("valid") is not True or db.get("error") or not db.get("schemaVersion")
            or built.tzinfo is None or not timedelta(0) <= now - built <= MAX_DB_AGE):
        raise ValueError("fresh valid database required")
    if not isinstance(scan.get("matches"), list):
        raise ValueError("scan matches required")
    counts = {severity: 0 for severity in ("Negligible", "Low", "Medium", "High", "Critical", "Unknown")}
    for match in scan["matches"]:
        severity = match["vulnerability"]["severity"]
        if severity not in counts:
            raise ValueError("unknown scanner schema")
        counts[severity] += 1
    if counts["High"] or counts["Critical"]:
        raise ValueError("High/Critical vulnerability gate failed")
    return {"schema_version": 1, "component": "openbao", "owner": "platform",
            "environment": "candidate", "image": pin["image"], "platform": pin["platform"],
            "source_revision": pin["source_revision"], "repository_revision": revision,
            "observed_at": now.isoformat(), "database_built_at": built.isoformat(),
            "maximum_database_age_seconds": int(MAX_DB_AGE.total_seconds()),
            "scan": "Passed", "severity_counts": counts, "approved_exceptions": [],
            "production_promotion": "Not verified", "signature_provenance": "Not verified",
            "sha256": {name: hashlib.sha256((directory / name).read_bytes()).hexdigest() for name in names}}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-dir", required=True, type=Path)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--report", action="store_true", help="public diagnostics only; never writes a receipt")
    args = parser.parse_args()
    if args.report:
        try:
            scan = load(args.evidence_dir / "grype.json")
            findings = [match for match in scan["matches"]
                        if match["vulnerability"]["severity"] in ("High", "Critical")]
            print(f"OPENBAO_BLOCKING_FINDINGS={len(findings)}")
            for match in findings[:20]:
                detail = json.dumps({"id": match["vulnerability"]["id"],
                                     "severity": match["vulnerability"]["severity"],
                                     "package": match["artifact"]["name"],
                                     "version": match["artifact"]["version"],
                                     "fix": match["vulnerability"].get("fix")}, ensure_ascii=True)
                # JSON escapes control characters; escape workflow-command delimiters too.
                print("::notice title=OpenBao blocking vulnerability::" + detail.replace("%", "%25"))
        except (ValueError, KeyError, TypeError, OSError):
            print("OPENBAO_SCAN_DIAGNOSTICS=Inconclusive; inspect failed job and artifacts")
        return 0  # Reporting is not the required scan/validation gate.
    try:
        result = validate(args.evidence_dir, args.revision, datetime.now(timezone.utc))
    except (ValueError, KeyError, TypeError, OSError):
        parser.exit(1, "OPENBAO_ARTIFACT=Failed; inspect public CI scan artifacts\n")
    print(json.dumps(result, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
