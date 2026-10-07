"""Download public evidence from successful main release runs; no deployment authority."""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
from pathlib import Path

REPOSITORY = "hasanjodatshandi/HooshiX"
WORKFLOW = ".github/workflows/production-release.yml"


def run(arguments: list[str]) -> bytes:
    result = subprocess.run(["gh", *arguments], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            timeout=90, check=False)
    if result.returncode or len(result.stdout) > 256 * 1024:
        raise ValueError("bounded authenticated publication download failed")
    return result.stdout


def check_run(record: dict) -> None:
    if (record.get("conclusion") != "success" or record.get("status") != "completed"
            or record.get("head_branch") != "main" or record.get("event") != "workflow_dispatch"
            or record.get("path") != WORKFLOW or record.get("repository", {}).get("full_name") != REPOSITORY
            or not re.fullmatch(r"[a-f0-9]{40}", record.get("head_sha", ""))):
        raise ValueError("successful reviewed-main release workflow required")


def download(run_id: str, component: str, output: Path) -> None:
    if not re.fullmatch(r"[1-9][0-9]{0,19}", run_id) or component not in ("mesh", "openbao"):
        raise ValueError("fixed repository numeric run required")
    record = json.loads(run(["api", "repos/" + REPOSITORY + "/actions/runs/" + run_id]))
    check_run(record)
    attempt = record["run_attempt"]
    if type(attempt) is not int or not 1 <= attempt <= 1000:
        raise ValueError("bounded run attempt required")
    artifacts = json.loads(run(["api", "repos/" + REPOSITORY + "/actions/runs/" + run_id + "/artifacts"]))
    artifact_name = component + "-publication-" + run_id + "-" + str(attempt)
    selected = [a for a in artifacts["artifacts"] if a["name"] == artifact_name]
    if len(selected) != 1 or selected[0]["expired"] or not 0 < selected[0]["size_in_bytes"] < 8 * 1024 * 1024:
        raise ValueError("bounded exact public publication artifact required")
    directory = output / component
    if directory.exists() or directory.is_symlink():
        raise ValueError("new publication download directory required")
    run(["run", "download", run_id, "--repo", REPOSITORY, "--name", artifact_name, "--dir", str(directory)])
    receipt = directory / "receipt.json"
    if receipt.is_symlink() or receipt.stat().st_size > 32 * 1024:
        raise ValueError("bounded regular publication receipt required")
    data = json.loads(receipt.read_bytes())
    if data["repository_revision"] != record["head_sha"] or data["component"] != component:
        raise ValueError("publication receipt differs from authenticated workflow run")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mesh-run", required=True)
    parser.add_argument("--openbao-run", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        runtime = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True)
        if os.environ.get("GITHUB_ACTIONS") != "true" or args.output.is_symlink() or not args.output.resolve(strict=True).is_relative_to(runtime):
            raise ValueError("disposable runner-owned output required")
        download(args.mesh_run, "mesh", args.output)
        download(args.openbao_run, "openbao", args.output)
        print("PLATFORM_PUBLICATIONS=Passed; deployment remains Not verified")
        return 0
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError):
        print("PLATFORM_PUBLICATIONS=Failed; no cluster or registry writes")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
