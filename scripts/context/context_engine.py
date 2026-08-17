#!/usr/bin/env python3
"""Git-native, repository-authoritative context compiler for HooshiX."""

from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

SCHEMA_VERSION = 1
DEFAULT_BOOTSTRAP = "context/bootstrap.json"
SECRET_KEY_RE = re.compile(
    r"(?:password|passwd|token|secret|private[_-]?key|api[_-]?key|credential)", re.IGNORECASE
)
PRIVATE_KEY_MARKER = "-----BEGIN PRIVATE KEY-----"
MAX_CHECKPOINT_TEXT = 2000


class ContextError(RuntimeError):
    """Expected context-engine validation or Git failure."""


def _json_load(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ContextError(f"missing required file: {path}") from exc
    except json.JSONDecodeError as exc:
        raise ContextError(f"invalid JSON in {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ContextError(f"expected JSON object in {path}")
    return value


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _norm(text: str) -> str:
    return " ".join(text.casefold().split())


def _slug(value: str) -> str:
    value = re.sub(r"[^a-z0-9]+", "-", value.casefold()).strip("-")
    return (value or "checkpoint")[:64].rstrip("-")


def _utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(microsecond=0)


@dataclass(frozen=True)
class GitResult:
    stdout: str


class ContextEngine:
    def __init__(self, root: Path | str | None = None) -> None:
        if root is None:
            root = self.discover_root()
        self.root = Path(root).resolve()
        self.bootstrap_path = self.root / DEFAULT_BOOTSTRAP

    @staticmethod
    def discover_root() -> Path:
        completed = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
        )
        if completed.returncode != 0:
            raise ContextError("not inside a Git repository")
        return Path(completed.stdout.strip()).resolve()

    def _git(self, args: Iterable[str], *, check: bool = True) -> GitResult:
        completed = subprocess.run(
            ["git", "-C", str(self.root), *args],
            check=False,
            capture_output=True,
            text=True,
            timeout=20,
        )
        if check and completed.returncode != 0:
            stderr = completed.stderr.strip() or "git command failed"
            raise ContextError(stderr)
        return GitResult(completed.stdout)

    def head(self) -> str:
        return self._git(["rev-parse", "HEAD"]).stdout.strip()

    def branch(self) -> str:
        value = self._git(["branch", "--show-current"]).stdout.strip()
        return value or "DETACHED"

    def bootstrap_config(self) -> dict[str, Any]:
        return _json_load(self.bootstrap_path)

    def routes_config(self) -> dict[str, Any]:
        bootstrap = self.bootstrap_config()
        return _json_load(self.root / str(bootstrap["routing_registry"]))

    def _status_paths(self) -> dict[str, str]:
        raw = self._git(["status", "--porcelain=v1", "-z"]).stdout
        result: dict[str, str] = {}
        records = raw.split("\0")
        index = 0
        while index < len(records):
            record = records[index]
            index += 1
            if not record:
                continue
            status = record[:2]
            path = record[3:]
            if status[0] in {"R", "C"} and index < len(records):
                path = records[index]
                index += 1
            result[path] = status
        return result

    def _tracked_files(self) -> list[str]:
        raw = self._git(["ls-files", "-z"]).stdout
        return [item for item in raw.split("\0") if item]

    def _blob_sha(self, path: str) -> str | None:
        result = self._git(["rev-parse", "--verify", f"HEAD:{path}"], check=False).stdout.strip()
        return result or None

    def _git_object_exists(self, revision: str) -> bool:
        completed = subprocess.run(
            ["git", "-C", str(self.root), "cat-file", "-e", f"{revision}^{{commit}}"],
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
        )
        return completed.returncode == 0

    def validate(self) -> list[str]:
        errors: list[str] = []
        for schema_path in (
            "context/bootstrap.schema.json",
            "context/routes.schema.json",
            "context/checkpoint.schema.json",
        ):
            try:
                _json_load(self.root / schema_path)
            except ContextError as exc:
                errors.append(str(exc))

        try:
            bootstrap = self.bootstrap_config()
        except ContextError as exc:
            return errors + [str(exc)]

        if bootstrap.get("schema_version") != SCHEMA_VERSION:
            errors.append("bootstrap schema_version must be 1")
        project = bootstrap.get("project")
        if not isinstance(project, dict):
            errors.append("bootstrap project must be an object")
        elif project.get("id") != "hooshix" or project.get("repository") != "hasanjodatshandi/HooshiX":
            errors.append("bootstrap project identity does not match HooshiX")

        authority_paths = bootstrap.get("authority_paths")
        if not isinstance(authority_paths, list) or not authority_paths:
            errors.append("bootstrap authority_paths must be a non-empty list")
            authority_paths = []
        for value in authority_paths:
            if not isinstance(value, str) or not value:
                errors.append("bootstrap contains invalid authority path")
            elif not (self.root / value).is_file():
                errors.append(f"bootstrap authority path does not exist: {value}")

        required_path_fields = (
            "routing_registry",
            "routing_schema",
            "checkpoint_schema",
            "generated_task_matrix",
        )
        for key in required_path_fields:
            value = bootstrap.get(key)
            if not isinstance(value, str) or not value:
                errors.append(f"bootstrap {key} must be a path string")
            elif not (self.root / value).is_file():
                errors.append(f"bootstrap {key} does not exist: {value}")

        checkpoint_directory = bootstrap.get("checkpoint_directory")
        if not isinstance(checkpoint_directory, str) or not (self.root / checkpoint_directory).is_dir():
            errors.append("bootstrap checkpoint_directory does not exist")

        retrieval = bootstrap.get("retrieval")
        if not isinstance(retrieval, dict):
            errors.append("bootstrap retrieval must be an object")
        else:
            bounds = {
                "max_query_chars": (1, 1024),
                "max_results": (1, 100),
                "max_excerpt_chars": (80, 4096),
                "max_file_bytes": (1024, 10 * 1024 * 1024),
            }
            for name, (minimum, maximum) in bounds.items():
                value = retrieval.get(name)
                if not isinstance(value, int) or not minimum <= value <= maximum:
                    errors.append(f"bootstrap retrieval {name} is outside supported bounds")
            patterns = retrieval.get("excluded_filename_patterns")
            if not isinstance(patterns, list) or not patterns or not all(isinstance(x, str) and x for x in patterns):
                errors.append("bootstrap retrieval excluded_filename_patterns must be non-empty strings")

        try:
            routes = self.routes_config()
        except (ContextError, KeyError) as exc:
            errors.append(str(exc))
            return errors

        errors.extend(self._validate_routes(routes))

        matrix_path = self.root / str(bootstrap.get("generated_task_matrix", ""))
        if matrix_path.is_file():
            expected = self.render_task_matrix(routes)
            if matrix_path.read_text(encoding="utf-8") != expected:
                errors.append("TASK-REVIEW-MATRIX.md differs from canonical context/routes.json")

        checkpoint_dir = self.root / str(bootstrap.get("checkpoint_directory", "context/checkpoints"))
        if checkpoint_dir.is_dir():
            for path in sorted(checkpoint_dir.glob("*.json")):
                try:
                    errors.extend(
                        f"{path.relative_to(self.root)}: {item}"
                        for item in self.validate_checkpoint(_json_load(path), require_commit=False)
                    )
                except ContextError as exc:
                    errors.append(str(exc))
        return errors

    def _validate_routes(self, routes: dict[str, Any]) -> list[str]:
        errors: list[str] = []
        if routes.get("schema_version") != SCHEMA_VERSION:
            errors.append("routes schema_version must be 1")
        global_sources = routes.get("global_sources")
        if not isinstance(global_sources, list) or not global_sources:
            errors.append("routes global_sources must be non-empty")
            global_sources = []
        for path in global_sources:
            if not isinstance(path, str) or not (self.root / path).is_file():
                errors.append(f"route global source does not exist: {path}")

        triggers = routes.get("full_read_triggers")
        if not isinstance(triggers, list) or not triggers:
            errors.append("routes full_read_triggers must be non-empty")
            triggers = []
        trigger_ids: set[str] = set()
        for trigger in triggers:
            if not isinstance(trigger, dict):
                errors.append("full-read trigger must be an object")
                continue
            trigger_id = trigger.get("id")
            if not isinstance(trigger_id, str) or not trigger_id:
                errors.append("full-read trigger has invalid id")
            elif trigger_id in trigger_ids:
                errors.append(f"duplicate full-read trigger id: {trigger_id}")
            else:
                trigger_ids.add(trigger_id)
            if not isinstance(trigger.get("description"), str) or not trigger["description"].strip():
                errors.append(f"full-read trigger {trigger_id!r} has no description")
            terms = trigger.get("match_terms")
            if not isinstance(terms, list) or not terms or not all(isinstance(term, str) and term.strip() for term in terms):
                errors.append(f"full-read trigger {trigger_id!r} has invalid match_terms")

        route_items = routes.get("routes")
        if not isinstance(route_items, list) or not route_items:
            errors.append("routes routes must be non-empty")
            return errors
        route_ids: set[str] = set()
        for route in route_items:
            if not isinstance(route, dict):
                errors.append("route must be an object")
                continue
            route_id = route.get("id")
            if not isinstance(route_id, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]*", route_id):
                errors.append(f"route has invalid id: {route_id!r}")
                continue
            if route_id in route_ids:
                errors.append(f"duplicate route id: {route_id}")
            route_ids.add(route_id)
            if route.get("review_mode") not in {"targeted", "full-read"}:
                errors.append(f"route {route_id} has invalid review_mode")
            for key in ("title", "summary"):
                if not isinstance(route.get(key), str) or not route[key].strip():
                    errors.append(f"route {route_id} has invalid {key}")
            terms = route.get("match_terms")
            if not isinstance(terms, list) or not terms or not all(isinstance(term, str) and term.strip() for term in terms):
                errors.append(f"route {route_id} has invalid match_terms")
            sources = route.get("minimum_sources")
            if not isinstance(sources, list) or not sources:
                errors.append(f"route {route_id} has no minimum_sources")
                continue
            if len(sources) != len(set(sources)):
                errors.append(f"route {route_id} contains duplicate minimum_sources")
            for path in sources:
                if not isinstance(path, str) or not (self.root / path).is_file():
                    errors.append(f"route {route_id} source does not exist: {path}")
        return errors

    def render_task_matrix(self, routes: dict[str, Any] | None = None) -> str:
        routes = routes or self.routes_config()
        lines = [
            "# Task Review Matrix — Generated Current View",
            "",
            "This file is generated from the canonical machine-readable registry `../../context/routes.json`. Do not edit this table or escalation list independently.",
            "",
            "Use this matrix only for `targeted` review. A matched global escalation trigger, an ambiguous/unmatched route, current-source disagreement, or a route marked `full-read` requires `full-read`.",
            "",
            "Always read these current sources first:",
            "",
        ]
        for path in routes["global_sources"]:
            lines.append(f"- `{path}`")
        lines.extend(["", "| Change area | Review mode | Minimum current sources |", "| --- | --- | --- |"])
        for route in routes["routes"]:
            sources = "; ".join(f"`{path}`" for path in route["minimum_sources"])
            summary = route["summary"].replace("|", "\\|")
            lines.append(f"| {route['title']} | `{route['review_mode']}` | {sources}. {summary} |")
        lines.extend(["", "## Escalation rules", ""])
        for trigger in routes["full_read_triggers"]:
            lines.append(f"- **{trigger['id']}** — {trigger['description']}")
        lines.extend(
            [
                "",
                "Targeted review does not permit skipping executable evidence, current implementation inspection, or current diff review.",
                "",
            ]
        )
        return "\n".join(lines)

    def bootstrap(self) -> dict[str, Any]:
        config = self.bootstrap_config()
        errors = self.validate()
        statuses = self._status_paths()
        authorities: list[dict[str, Any]] = []
        authority_dirty = False
        for rel in config["authority_paths"]:
            path = self.root / rel
            status = statuses.get(rel)
            if status is not None:
                authority_dirty = True
            authorities.append(
                {
                    "path": rel,
                    "git_status": status or "clean",
                    "head_blob_sha": self._blob_sha(rel),
                    "worktree_sha256": _sha256_file(path) if path.is_file() else None,
                }
            )
        return {
            "schema_version": SCHEMA_VERSION,
            "project": config["project"],
            "repository_root": str(self.root),
            "repository_commit": self.head(),
            "branch": self.branch(),
            "dirty_paths": sorted(statuses),
            "authorities": authorities,
            "verification": {
                "valid": not errors,
                "errors": errors,
                "authority_worktree_dirty": authority_dirty,
                "trusted_for_targeted_review": not errors and not authority_dirty,
                "trust_reason": (
                    "verified current authority inputs"
                    if not errors and not authority_dirty
                    else "targeted review is not verified; inspect errors/dirty authority inputs"
                ),
            },
        }

    def route_task(self, task: str, route_id: str | None = None) -> dict[str, Any]:
        if not isinstance(task, str) or not task.strip():
            raise ContextError("task must be a non-empty string")
        if len(task) > 4000:
            raise ContextError("task exceeds 4000 characters")
        routes = self.routes_config()
        normalized = _norm(task)
        trigger_matches: list[dict[str, str]] = []
        for trigger in routes["full_read_triggers"]:
            matched = [term for term in trigger["match_terms"] if _norm(term) in normalized]
            if matched:
                trigger_matches.append({"id": trigger["id"], "matched_term": matched[0]})

        route_by_id = {route["id"]: route for route in routes["routes"]}
        selected: dict[str, Any] | None = None
        selection_reason = ""
        scores: list[tuple[int, str, dict[str, Any], list[str]]] = []
        if route_id is not None:
            selected = route_by_id.get(route_id)
            if selected is None:
                raise ContextError(f"unknown route_id: {route_id}")
            selection_reason = "explicit route_id"
        else:
            for route in routes["routes"]:
                matches = [term for term in route["match_terms"] if _norm(term) in normalized]
                if matches:
                    score = sum(max(1, len(_norm(term).split())) for term in matches)
                    scores.append((score, route["id"], route, matches))
            scores.sort(key=lambda item: (-item[0], item[1]))
            if scores and (len(scores) == 1 or scores[0][0] > scores[1][0]):
                selected = scores[0][2]
                selection_reason = "deterministic term match: " + ", ".join(scores[0][3][:5])
            elif scores:
                selection_reason = "ambiguous route score tie"
            else:
                selection_reason = "no deterministic route match"

        review_mode = "full-read"
        escalation_reasons: list[str] = []
        if trigger_matches:
            escalation_reasons.extend(
                f"global trigger {item['id']} matched {item['matched_term']!r}" for item in trigger_matches
            )
        if selected is None:
            escalation_reasons.append(selection_reason)
        elif selected["review_mode"] == "full-read":
            escalation_reasons.append(f"route {selected['id']} requires full-read")
        elif not trigger_matches:
            review_mode = "targeted"

        minimum_sources = list(routes["global_sources"])
        if selected is not None:
            for path in selected["minimum_sources"]:
                if path not in minimum_sources:
                    minimum_sources.append(path)
        if review_mode == "full-read":
            bootstrap = self.bootstrap_config()
            minimum_sources = list(routes["global_sources"])
            for root in bootstrap["full_read_roots"]:
                minimum_sources.append(f"{root}/**")

        return {
            "repository_commit": self.head(),
            "branch": self.branch(),
            "task": task,
            "review_mode": review_mode,
            "route": selected["id"] if selected else None,
            "route_title": selected["title"] if selected else None,
            "selection_reason": selection_reason,
            "escalation_reasons": escalation_reasons,
            "minimum_sources": minimum_sources,
            "route_summary": selected["summary"] if selected else None,
        }

    def _is_sensitive_filename(self, rel: str, patterns: list[str]) -> bool:
        name = Path(rel).name
        return any(fnmatch.fnmatch(name, pattern) or fnmatch.fnmatch(rel, pattern) for pattern in patterns)

    def classify_path(self, rel: str) -> str:
        if rel == "AGENTS.md":
            return "AGENT_POLICY"
        if rel.startswith("context/checkpoints/"):
            return "HISTORICAL_EVIDENCE"
        if (
            rel.startswith("docs/adr/")
            or rel.startswith("docs/architecture/")
            or rel.startswith("docs/engineering/")
            or rel.startswith("docs/technology/")
        ):
            return "CURRENT_REPOSITORY_POLICY_OR_ARCHITECTURE"
        if rel.startswith("docs/operations/") or rel.startswith("docs/runbooks/"):
            return "OPERATIONAL_GUIDANCE"
        if rel.startswith("context/"):
            return "CONTEXT_GOVERNANCE"
        return "SOURCE"

    def search(self, query: str, limit: int | None = None) -> dict[str, Any]:
        config = self.bootstrap_config()["retrieval"]
        if not isinstance(query, str) or not query.strip():
            raise ContextError("query must be a non-empty string")
        if len(query) > config["max_query_chars"]:
            raise ContextError(f"query exceeds {config['max_query_chars']} characters")
        if limit is None:
            limit = min(10, config["max_results"])
        if not isinstance(limit, int) or limit < 1 or limit > config["max_results"]:
            raise ContextError(f"limit must be between 1 and {config['max_results']}")

        normalized_query = _norm(query)
        tokens = [
            token
            for token in re.split(r"\W+", normalized_query, flags=re.UNICODE)
            if len(token) >= 2
        ]
        if not tokens:
            tokens = [normalized_query]
        scored: list[tuple[int, str, int, str]] = []
        for rel in self._tracked_files():
            if self._is_sensitive_filename(rel, config["excluded_filename_patterns"]):
                continue
            path = self.root / rel
            try:
                if path.stat().st_size > config["max_file_bytes"]:
                    continue
                raw = path.read_bytes()
            except (FileNotFoundError, OSError):
                continue
            if b"\x00" in raw[:8192]:
                continue
            try:
                text = raw.decode("utf-8")
            except UnicodeDecodeError:
                continue
            lower = text.casefold()
            path_lower = rel.casefold()
            phrase_hits = lower.count(normalized_query)
            token_hits = sum(lower.count(token) for token in tokens)
            path_hits = sum(1 for token in tokens if token in path_lower)
            score = phrase_hits * 20 + token_hits * 2 + path_hits * 6
            if score <= 0:
                continue
            lines = text.splitlines()
            best_line = 0
            best_line_score = -1
            for index, line in enumerate(lines):
                line_lower = line.casefold()
                line_score = (20 if normalized_query in line_lower else 0) + sum(
                    2 for token in tokens if token in line_lower
                )
                if line_score > best_line_score:
                    best_line_score = line_score
                    best_line = index
            start = max(0, best_line - 1)
            end = min(len(lines), best_line + 2)
            excerpt = "\n".join(lines[start:end]).strip()
            if len(excerpt) > config["max_excerpt_chars"]:
                excerpt = excerpt[: config["max_excerpt_chars"] - 1] + "…"
            scored.append((score, rel, start + 1, excerpt))

        scored.sort(key=lambda item: (-item[0], item[1], item[2]))
        statuses = self._status_paths()
        results: list[dict[str, Any]] = []
        for score, rel, line_start, excerpt in scored[:limit]:
            path = self.root / rel
            results.append(
                {
                    "score": score,
                    "path": rel,
                    "line_start": line_start,
                    "excerpt": excerpt,
                    "classification": self.classify_path(rel),
                    "source_state": "WORKTREE_MODIFIED" if rel in statuses else "HEAD",
                    "head_blob_sha": self._blob_sha(rel),
                    "worktree_sha256": _sha256_file(path),
                }
            )
        return {
            "repository_commit": self.head(),
            "branch": self.branch(),
            "query": query,
            "results": results,
        }

    def changed_context(self, base: str, head: str = "HEAD") -> dict[str, Any]:
        if not re.fullmatch(r"[A-Za-z0-9._/@{}^~:+-]{1,200}", base):
            raise ContextError("base revision contains unsupported characters")
        if not re.fullmatch(r"[A-Za-z0-9._/@{}^~:+-]{1,200}", head):
            raise ContextError("head revision contains unsupported characters")
        if not self._git_object_exists(base):
            raise ContextError(f"base revision is not a commit: {base}")
        resolved_head = self._git(["rev-parse", head]).stdout.strip()
        raw = self._git(["diff", "--name-status", "--no-renames", f"{base}..{head}"]).stdout
        items: list[dict[str, str]] = []
        for line in raw.splitlines():
            if not line.strip():
                continue
            status, path = line.split("\t", 1)
            items.append(
                {"status": status, "path": path, "classification": self.classify_path(path)}
            )
        return {
            "base": self._git(["rev-parse", base]).stdout.strip(),
            "head": resolved_head,
            "changes": items,
        }

    def validate_checkpoint(self, data: dict[str, Any], *, require_commit: bool = True) -> list[str]:
        errors: list[str] = []
        required = {
            "schema_version",
            "checkpoint_id",
            "recorded_at_utc",
            "project_id",
            "repository",
            "branch",
            "base_commit",
            "subject_commit",
            "objective",
            "scope",
            "completed",
            "decisions",
            "changed_paths",
            "verification",
            "risks",
            "unfinished",
            "next_actions",
        }
        missing = sorted(required - data.keys())
        if missing:
            errors.append("missing fields: " + ", ".join(missing))
            return errors
        if data.get("schema_version") != SCHEMA_VERSION:
            errors.append("schema_version must be 1")
        if data.get("project_id") != "hooshix" or data.get("repository") != "hasanjodatshandi/HooshiX":
            errors.append("checkpoint project identity does not match HooshiX")
        if not re.fullmatch(
            r"[0-9]{8}T[0-9]{6}Z-[a-z0-9][a-z0-9-]{0,63}",
            str(data.get("checkpoint_id", "")),
        ):
            errors.append("checkpoint_id has invalid format")
        try:
            dt = datetime.fromisoformat(str(data["recorded_at_utc"]).replace("Z", "+00:00"))
            if dt.tzinfo is None:
                raise ValueError
        except ValueError:
            errors.append("recorded_at_utc must be an offset-aware ISO-8601 timestamp")
        for field in ("base_commit", "subject_commit"):
            value = data.get(field)
            if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{40}", value):
                errors.append(f"{field} must be a full lowercase commit SHA")
            elif require_commit and not self._git_object_exists(value):
                errors.append(f"{field} does not resolve to a commit")
        if (
            not isinstance(data.get("objective"), str)
            or not data["objective"].strip()
            or len(data["objective"]) > MAX_CHECKPOINT_TEXT
        ):
            errors.append("objective is invalid or too long")
        scope = data.get("scope")
        if not isinstance(scope, dict) or scope.get("review_mode") not in {"targeted", "full-read"}:
            errors.append("scope.review_mode must be targeted or full-read")
        for list_field in ("completed", "changed_paths", "risks", "unfinished", "next_actions"):
            value = data.get(list_field)
            if not isinstance(value, list) or not all(
                isinstance(item, str) and item and len(item) <= MAX_CHECKPOINT_TEXT for item in value
            ):
                errors.append(f"{list_field} must be a list of bounded non-empty strings")
        decisions = data.get("decisions")
        if not isinstance(decisions, list):
            errors.append("decisions must be a list")
        else:
            for decision in decisions:
                if not isinstance(decision, dict) or set(decision) != {"summary", "authority_refs"}:
                    errors.append("decision must contain summary and authority_refs only")
                    continue
                if not isinstance(decision["summary"], str) or not decision["summary"].strip():
                    errors.append("decision summary must be non-empty")
                refs = decision["authority_refs"]
                if not isinstance(refs, list) or not refs:
                    errors.append("decision authority_refs must be non-empty")
                else:
                    for ref in refs:
                        if not isinstance(ref, str) or not (self.root / ref).is_file():
                            errors.append(f"decision authority_ref does not exist: {ref}")
        verification = data.get("verification")
        if not isinstance(verification, dict) or set(verification) != {"passed", "failed", "not_run"}:
            errors.append("verification must contain passed, failed, and not_run")
        else:
            for category, values in verification.items():
                if not isinstance(values, list):
                    errors.append(f"verification.{category} must be a list")
                    continue
                for item in values:
                    if not isinstance(item, dict) or set(item) != {"check", "evidence"}:
                        errors.append(
                            f"verification.{category} entries require check and evidence"
                        )
        errors.extend(self._checkpoint_secret_checks(data))
        return errors

    def _checkpoint_secret_checks(self, data: Any, path: str = "") -> list[str]:
        errors: list[str] = []
        if isinstance(data, dict):
            for key, value in data.items():
                current = f"{path}.{key}" if path else str(key)
                if SECRET_KEY_RE.search(str(key)):
                    errors.append(
                        f"checkpoint field name is prohibited for secret safety: {current}"
                    )
                errors.extend(self._checkpoint_secret_checks(value, current))
        elif isinstance(data, list):
            for index, value in enumerate(data):
                errors.extend(self._checkpoint_secret_checks(value, f"{path}[{index}]"))
        elif isinstance(data, str) and PRIVATE_KEY_MARKER in data:
            errors.append(f"checkpoint contains private-key material at {path}")
        return errors

    def create_checkpoint(
        self,
        receipt_path: Path,
        *,
        base: str,
        output_dir: Path | None = None,
    ) -> Path:
        receipt = _json_load(receipt_path)
        allowed = {
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
        unknown = sorted(set(receipt) - allowed)
        if unknown:
            raise ContextError(
                "checkpoint receipt has unsupported fields: " + ", ".join(unknown)
            )
        subject = self.head()
        if not self._git_object_exists(base):
            raise ContextError(f"base revision is not a commit: {base}")
        base_sha = self._git(["rev-parse", base]).stdout.strip()
        raw = self._git(
            ["diff", "--name-only", "--no-renames", f"{base_sha}..{subject}"]
        ).stdout
        changed = sorted(
            path
            for path in raw.splitlines()
            if path and not path.startswith("context/checkpoints/")
        )
        now = _utc_now()
        checkpoint_id = (
            f"{now.strftime('%Y%m%dT%H%M%SZ')}-"
            f"{_slug(str(receipt.get('objective', 'checkpoint')))}"
        )
        data: dict[str, Any] = {
            "schema_version": SCHEMA_VERSION,
            "checkpoint_id": checkpoint_id,
            "recorded_at_utc": now.isoformat().replace("+00:00", "Z"),
            "project_id": "hooshix",
            "repository": "hasanjodatshandi/HooshiX",
            "branch": self.branch(),
            "base_commit": base_sha,
            "subject_commit": subject,
            "objective": receipt.get("objective", ""),
            "scope": receipt.get(
                "scope", {"review_mode": "targeted", "routes": [], "bounded_contexts": []}
            ),
            "completed": receipt.get("completed", []),
            "decisions": receipt.get("decisions", []),
            "changed_paths": changed,
            "verification": receipt.get(
                "verification", {"passed": [], "failed": [], "not_run": []}
            ),
            "risks": receipt.get("risks", []),
            "unfinished": receipt.get("unfinished", []),
            "next_actions": receipt.get("next_actions", []),
        }
        if "pull_request" in receipt:
            data["pull_request"] = receipt["pull_request"]
        errors = self.validate_checkpoint(data)
        if errors:
            raise ContextError("invalid checkpoint: " + "; ".join(errors))
        if output_dir is None:
            output_dir = self.root / self.bootstrap_config()["checkpoint_directory"]
        output_dir.mkdir(parents=True, exist_ok=True)
        path = output_dir / f"{checkpoint_id}.json"
        if path.exists():
            raise ContextError(f"checkpoint already exists: {path}")
        path.write_text(
            json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        return path

    def latest_checkpoint(self) -> dict[str, Any] | None:
        directory = self.root / self.bootstrap_config()["checkpoint_directory"]
        paths = sorted(directory.glob("*.json"), reverse=True)
        for path in paths:
            data = _json_load(path)
            errors = self.validate_checkpoint(data, require_commit=False)
            if not errors:
                return {
                    "path": path.relative_to(self.root).as_posix(),
                    "checkpoint": data,
                    "current_head": self.head(),
                    "is_current_head": data.get("subject_commit") == self.head(),
                }
        return None


def _print_json(value: Any) -> None:
    print(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, help="repository root; defaults to current Git root")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("verify", help="validate context contracts and generated view")
    sub.add_parser("bootstrap", help="emit live verified bootstrap")

    route = sub.add_parser("route", help="compile context route for a task")
    route.add_argument("--task", required=True)
    route.add_argument("--route-id")

    search = sub.add_parser("search", help="search tracked repository text with provenance")
    search.add_argument("--query", required=True)
    search.add_argument("--limit", type=int)

    changed = sub.add_parser("changed", help="show Git-aware changed context")
    changed.add_argument("--base", required=True)
    changed.add_argument("--head", default="HEAD")

    sub.add_parser("matrix", help="render TASK-REVIEW-MATRIX.md to stdout")
    sub.add_parser("latest-checkpoint", help="emit newest valid historical checkpoint")

    checkpoint = sub.add_parser(
        "checkpoint-create",
        help="create a commit-bound checkpoint from a bounded receipt JSON",
    )
    checkpoint.add_argument("--input", required=True, type=Path)
    checkpoint.add_argument("--base", required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        engine = ContextEngine(args.root)
        if args.command == "verify":
            errors = engine.validate()
            if errors:
                print("Context verification FAILED:", file=sys.stderr)
                for error in errors:
                    print(f"- {error}", file=sys.stderr)
                return 1
            print("Context verification PASSED.")
            return 0
        if args.command == "bootstrap":
            _print_json(engine.bootstrap())
        elif args.command == "route":
            _print_json(engine.route_task(args.task, args.route_id))
        elif args.command == "search":
            _print_json(engine.search(args.query, args.limit))
        elif args.command == "changed":
            _print_json(engine.changed_context(args.base, args.head))
        elif args.command == "matrix":
            sys.stdout.write(engine.render_task_matrix())
        elif args.command == "latest-checkpoint":
            _print_json(engine.latest_checkpoint())
        elif args.command == "checkpoint-create":
            path = engine.create_checkpoint(args.input, base=args.base)
            print(path.relative_to(engine.root).as_posix())
        return 0
    except ContextError as exc:
        print(f"Context engine error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
