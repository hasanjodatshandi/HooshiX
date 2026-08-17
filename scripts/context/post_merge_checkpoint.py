#!/usr/bin/env python3
"""Create and verify durable post-merge Context Engine checkpoints."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

from context_engine import (
    MAX_CHECKPOINT_TEXT,
    SCHEMA_VERSION,
    ContextEngine,
    ContextError,
    _json_load,
    _slug,
    _utc_now,
)

CHECKPOINT_KIND = "post-merge"
CHECKPOINT_PATH_RE = re.compile(
    r"^context/checkpoints/[0-9]{8}T[0-9]{6}Z-[a-z0-9][a-z0-9-]{0,63}\.json$"
)
RECEIPT_FIELDS = {
    "objective",
    "scope",
    "completed",
    "decisions",
    "verification",
    "risks",
    "unfinished",
    "next_actions",
    "pull_request",
}


def _git(root: Path, *args: str, check: bool = True) -> tuple[int, str, str]:
    completed = subprocess.run(
        ["git", "-C", str(root), *args],
        check=False,
        capture_output=True,
        text=True,
        timeout=20,
    )
    if check and completed.returncode != 0:
        raise ContextError(completed.stderr.strip() or "git command failed")
    return completed.returncode, completed.stdout.strip(), completed.stderr.strip()


def _main_tip(root: Path) -> str:
    for ref in ("refs/remotes/origin/main", "refs/heads/main"):
        code, stdout, _ = _git(root, "rev-parse", "--verify", ref, check=False)
        if code == 0 and re.fullmatch(r"[0-9a-f]{40}", stdout):
            return stdout
    raise ContextError(
        "post-merge verification requires a current origin/main or local main ref"
    )


def _resolve_source_checkpoint(engine: ContextEngine, rel: str) -> tuple[Path, dict[str, Any]]:
    if not isinstance(rel, str) or not CHECKPOINT_PATH_RE.fullmatch(rel):
        raise ContextError("source checkpoint must be a canonical context/checkpoints JSON path")
    directory = (engine.root / engine.bootstrap_config()["checkpoint_directory"]).resolve()
    path = (engine.root / rel).resolve()
    if path.parent != directory or not path.is_file():
        raise ContextError(f"source checkpoint does not exist: {rel}")
    data = _json_load(path)
    errors = engine.validate_checkpoint(data, require_commit=False)
    if errors:
        raise ContextError("invalid source checkpoint: " + "; ".join(errors))
    if data.get("checkpoint_kind") == CHECKPOINT_KIND:
        raise ContextError("source checkpoint must be a work checkpoint, not post-merge")
    return path, data


def _derive_merge_state(
    engine: ContextEngine, base: str, merge_commit: str
) -> tuple[str, str, list[str]]:
    if not engine._git_object_exists(merge_commit):
        raise ContextError(f"merge revision is not a commit: {merge_commit}")
    context = engine.changed_context(base, merge_commit)
    base_sha = context["base"]
    subject_sha = context["head"]
    _, merge_base, _ = _git(engine.root, "merge-base", base_sha, subject_sha)
    if merge_base != base_sha:
        raise ContextError("post-merge base must be an ancestor of the merge commit")
    main_tip = _main_tip(engine.root)
    _, main_merge_base, _ = _git(engine.root, "merge-base", subject_sha, main_tip)
    if main_merge_base != subject_sha:
        raise ContextError("post-merge subject commit must be reachable from current main")
    changed_paths = sorted(
        item["path"]
        for item in context["changes"]
        if not item["path"].startswith("context/checkpoints/")
    )
    return base_sha, subject_sha, changed_paths


def validate_post_merge_checkpoint(
    engine: ContextEngine,
    data: dict[str, Any],
    *,
    verify_git: bool,
) -> list[str]:
    errors = engine.validate_checkpoint(data, require_commit=False)
    if data.get("checkpoint_kind") != CHECKPOINT_KIND:
        errors.append("checkpoint_kind must be post-merge")
    source_rel = data.get("source_checkpoint")
    try:
        _, source = _resolve_source_checkpoint(engine, source_rel)
    except ContextError as exc:
        errors.append(str(exc))
        source = None
    pull_request = data.get("pull_request")
    if not isinstance(pull_request, int) or isinstance(pull_request, bool) or pull_request < 1:
        errors.append("post-merge checkpoint requires a positive pull_request")
    elif source is not None and source.get("pull_request") != pull_request:
        errors.append("post-merge pull_request must match source checkpoint pull_request")
    if data.get("branch") != "main":
        errors.append("post-merge checkpoint branch must be main")
    if source is not None and source.get("recorded_at_utc", "") >= data.get("recorded_at_utc", ""):
        errors.append("post-merge checkpoint must be recorded after its source checkpoint")
    if verify_git and not errors:
        try:
            base_sha, subject_sha, changed_paths = _derive_merge_state(
                engine, data["base_commit"], data["subject_commit"]
            )
            if data["base_commit"] != base_sha or data["subject_commit"] != subject_sha:
                errors.append("post-merge commit identifiers must be canonical full SHAs")
            if data["changed_paths"] != changed_paths:
                errors.append("post-merge changed_paths differ from Git-derived merge diff")
        except ContextError as exc:
            errors.append(str(exc))
    return errors


def verify_tracked_post_merge_checkpoints(engine: ContextEngine) -> list[str]:
    directory = engine.root / engine.bootstrap_config()["checkpoint_directory"]
    errors: list[str] = []
    for path in sorted(directory.glob("*.json")):
        data = _json_load(path)
        kind = data.get("checkpoint_kind")
        if kind is None:
            if "source_checkpoint" in data:
                errors.append(
                    f"{path.relative_to(engine.root)}: source_checkpoint requires checkpoint_kind"
                )
            continue
        if kind == "work":
            if "source_checkpoint" in data:
                errors.append(
                    f"{path.relative_to(engine.root)}: work checkpoint cannot have source_checkpoint"
                )
            continue
        if kind != CHECKPOINT_KIND:
            errors.append(
                f"{path.relative_to(engine.root)}: unsupported checkpoint_kind {kind!r}"
            )
            continue
        for error in validate_post_merge_checkpoint(engine, data, verify_git=True):
            errors.append(f"{path.relative_to(engine.root)}: {error}")
    return errors


def create_post_merge_checkpoint(
    engine: ContextEngine,
    receipt_path: Path,
    *,
    base: str,
    merge_commit: str,
    source_checkpoint: str,
    output_dir: Path | None = None,
) -> Path:
    receipt = _json_load(receipt_path)
    unknown = sorted(set(receipt) - RECEIPT_FIELDS)
    if unknown:
        raise ContextError(
            "post-merge receipt has unsupported fields: " + ", ".join(unknown)
        )
    objective = receipt.get("objective")
    if not isinstance(objective, str) or not objective.strip() or len(objective) > MAX_CHECKPOINT_TEXT:
        raise ContextError("post-merge receipt objective is invalid or too long")
    pull_request = receipt.get("pull_request")
    if not isinstance(pull_request, int) or isinstance(pull_request, bool) or pull_request < 1:
        raise ContextError("post-merge receipt requires a positive pull_request")
    _, source = _resolve_source_checkpoint(engine, source_checkpoint)
    if source.get("pull_request") != pull_request:
        raise ContextError("post-merge receipt pull_request does not match source checkpoint")
    base_sha, subject_sha, changed_paths = _derive_merge_state(engine, base, merge_commit)
    now = _utc_now()
    checkpoint_id = (
        f"{now.strftime('%Y%m%dT%H%M%SZ')}-"
        f"{_slug(f'post-merge-pr-{pull_request}-{objective}')}"
    )
    data: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "checkpoint_id": checkpoint_id,
        "recorded_at_utc": now.isoformat().replace("+00:00", "Z"),
        "project_id": "hooshix",
        "repository": "hasanjodatshandi/HooshiX",
        "checkpoint_kind": CHECKPOINT_KIND,
        "source_checkpoint": source_checkpoint,
        "branch": "main",
        "base_commit": base_sha,
        "subject_commit": subject_sha,
        "pull_request": pull_request,
        "objective": objective,
        "scope": receipt.get(
            "scope", {"review_mode": "targeted", "routes": [], "bounded_contexts": []}
        ),
        "completed": receipt.get("completed", []),
        "decisions": receipt.get("decisions", []),
        "changed_paths": changed_paths,
        "verification": receipt.get(
            "verification", {"passed": [], "failed": [], "not_run": []}
        ),
        "risks": receipt.get("risks", []),
        "unfinished": receipt.get("unfinished", []),
        "next_actions": receipt.get("next_actions", []),
    }
    errors = validate_post_merge_checkpoint(engine, data, verify_git=True)
    if errors:
        raise ContextError("invalid post-merge checkpoint: " + "; ".join(errors))
    if output_dir is None:
        output_dir = engine.root / engine.bootstrap_config()["checkpoint_directory"]
    output_dir.mkdir(parents=True, exist_ok=True)
    path = output_dir / f"{checkpoint_id}.json"
    if path.exists():
        raise ContextError(f"checkpoint already exists: {path}")
    path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return path


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, help="repository root; defaults to current Git root")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("verify", help="verify tracked post-merge checkpoint semantics")
    create = sub.add_parser("create", help="create a post-merge checkpoint")
    create.add_argument("--input", required=True, type=Path)
    create.add_argument("--base", required=True)
    create.add_argument("--merge", required=True)
    create.add_argument("--source-checkpoint", required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        engine = ContextEngine(args.root)
        if args.command == "verify":
            errors = verify_tracked_post_merge_checkpoints(engine)
            if errors:
                print("Post-merge checkpoint verification FAILED:", file=sys.stderr)
                for error in errors:
                    print(f"- {error}", file=sys.stderr)
                return 1
            print("Post-merge checkpoint verification PASSED.")
            return 0
        path = create_post_merge_checkpoint(
            engine,
            args.input,
            base=args.base,
            merge_commit=args.merge,
            source_checkpoint=args.source_checkpoint,
        )
        print(path.relative_to(engine.root).as_posix())
        return 0
    except ContextError as exc:
        print(f"Post-merge checkpoint error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
