from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from context_engine import ContextEngine, ContextError  # noqa: E402


def git(root: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(root), *args],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def write(root: Path, rel: str, text: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def make_repo() -> tuple[tempfile.TemporaryDirectory[str], Path, ContextEngine]:
    temp = tempfile.TemporaryDirectory()
    root = Path(temp.name)
    git(root, "init", "-b", "main")
    git(root, "config", "user.email", "context@example.test")
    git(root, "config", "user.name", "Context Test")

    required_docs = [
        "AGENTS.md",
        "docs/engineering/current-only-documentation-policy.md",
        "docs/engineering/repository-change-workflow.md",
        "docs/architecture/README.md",
        "docs/architecture/SOURCES.md",
        "docs/adr/decision-register.md",
        "docs/architecture/implementation-status.md",
        "docs/architecture/security-architecture.md",
    ]
    for rel in required_docs:
        write(root, rel, f"# {rel}\nAuthorization context and password rules.\n")
    write(root, "context/bootstrap.schema.json", "{}\n")
    write(root, "context/routes.schema.json", "{}\n")
    write(root, "context/checkpoint.schema.json", "{}\n")
    write(root, "context/checkpoints/README.md", "# Checkpoints\n")
    bootstrap = {
        "schema_version": 1,
        "project": {"id": "hooshix", "repository": "hasanjodatshandi/HooshiX"},
        "authority_paths": [
            "AGENTS.md",
            "docs/engineering/current-only-documentation-policy.md",
            "docs/engineering/repository-change-workflow.md",
            "docs/architecture/README.md",
            "docs/architecture/SOURCES.md",
            "context/routes.json",
            "docs/adr/decision-register.md",
            "docs/architecture/implementation-status.md",
        ],
        "full_read_roots": ["docs"],
        "routing_registry": "context/routes.json",
        "routing_schema": "context/routes.schema.json",
        "checkpoint_schema": "context/checkpoint.schema.json",
        "checkpoint_directory": "context/checkpoints",
        "generated_task_matrix": "docs/architecture/TASK-REVIEW-MATRIX.md",
        "retrieval": {
            "max_query_chars": 256,
            "max_results": 20,
            "max_excerpt_chars": 480,
            "max_file_bytes": 1048576,
            "excluded_filename_patterns": ["*.pem", ".env", ".env.*"],
        },
    }
    routes = {
        "schema_version": 1,
        "global_sources": ["AGENTS.md", "docs/architecture/SOURCES.md"],
        "full_read_triggers": [
            {
                "id": "new-service",
                "description": "New service needs full review.",
                "match_terms": ["new service", "سرویس جدید"],
            }
        ],
        "routes": [
            {
                "id": "identity",
                "title": "Identity",
                "review_mode": "targeted",
                "match_terms": ["identity", "password", "رمز عبور"],
                "minimum_sources": ["docs/architecture/security-architecture.md"],
                "summary": "Review Identity security.",
            },
            {
                "id": "service-boundary",
                "title": "Service boundary",
                "review_mode": "full-read",
                "match_terms": ["new service"],
                "minimum_sources": ["docs/architecture/implementation-status.md"],
                "summary": "Full read.",
            },
        ],
    }
    write(root, "context/bootstrap.json", json.dumps(bootstrap, indent=2) + "\n")
    write(root, "context/routes.json", json.dumps(routes, ensure_ascii=False, indent=2) + "\n")
    engine = ContextEngine(root)
    write(root, "docs/architecture/TASK-REVIEW-MATRIX.md", engine.render_task_matrix(routes))
    write(
        root,
        "src/example.txt",
        "Identity password policy and Authorization context.\nSecond line.\n",
    )
    write(root, "private.pem", "-----BEGIN PRIVATE KEY-----\nnot-real\n")
    git(root, "add", ".")
    git(root, "commit", "-m", "fixture")
    return temp, root, engine


class ContextEngineTest(unittest.TestCase):
    def test_clean_bootstrap_is_trusted(self) -> None:
        temp, root, engine = make_repo()
        self.addCleanup(temp.cleanup)
        result = engine.bootstrap()
        self.assertTrue(result["verification"]["valid"])
        self.assertTrue(result["verification"]["trusted_for_targeted_review"])
        self.assertRegex(result["repository_commit"], r"^[0-9a-f]{40}$")

    def test_dirty_authority_invalidates_targeted_trust_but_dirty_source_does_not(self) -> None:
        temp, root, engine = make_repo()
        self.addCleanup(temp.cleanup)
        write(root, "src/example.txt", "changed source\n")
        self.assertTrue(engine.bootstrap()["verification"]["trusted_for_targeted_review"])
        write(root, "AGENTS.md", "changed authority\n")
        result = engine.bootstrap()
        self.assertFalse(result["verification"]["trusted_for_targeted_review"])
        self.assertTrue(result["verification"]["authority_worktree_dirty"])

    def test_router_targets_known_task_and_escalates_new_service(self) -> None:
        temp, root, engine = make_repo()
        self.addCleanup(temp.cleanup)
        targeted = engine.route_task("Fix identity password validation")
        self.assertEqual("targeted", targeted["review_mode"])
        self.assertEqual("identity", targeted["route"])
        full = engine.route_task("Create a new service for identity password data")
        self.assertEqual("full-read", full["review_mode"])
        self.assertTrue(full["escalation_reasons"])

    def test_router_escalates_unknown_task(self) -> None:
        temp, root, engine = make_repo()
        self.addCleanup(temp.cleanup)
        result = engine.route_task("frobnicate the unrelated widget")
        self.assertEqual("full-read", result["review_mode"])
        self.assertIsNone(result["route"])

    def test_matrix_drift_fails_verification(self) -> None:
        temp, root, engine = make_repo()
        self.addCleanup(temp.cleanup)
        write(root, "docs/architecture/TASK-REVIEW-MATRIX.md", "stale\n")
        errors = engine.validate()
        self.assertTrue(any("TASK-REVIEW-MATRIX" in error for error in errors))

    def test_search_returns_provenance_and_excludes_sensitive_filename(self) -> None:
        temp, root, engine = make_repo()
        self.addCleanup(temp.cleanup)
        result = engine.search("Identity password", 10)
        paths = [item["path"] for item in result["results"]]
        self.assertIn("src/example.txt", paths)
        self.assertNotIn("private.pem", paths)
        match = next(item for item in result["results"] if item["path"] == "src/example.txt")
        self.assertRegex(match["head_blob_sha"], r"^[0-9a-f]{40}$")
        self.assertRegex(match["worktree_sha256"], r"^[0-9a-f]{64}$")

    def test_checkpoint_creation_derives_git_state_and_rejects_secret_fields(self) -> None:
        temp, root, engine = make_repo()
        self.addCleanup(temp.cleanup)
        base = engine.head()
        write(root, "src/new.txt", "new work\n")
        git(root, "add", "src/new.txt")
        git(root, "commit", "-m", "work")
        receipt = {
            "objective": "Implement identity validation",
            "scope": {
                "review_mode": "targeted",
                "routes": ["identity"],
                "bounded_contexts": ["Identity"],
            },
            "completed": ["Added validation"],
            "decisions": [
                {
                    "summary": "Follow current security authority",
                    "authority_refs": ["docs/architecture/security-architecture.md"],
                }
            ],
            "verification": {
                "passed": [{"check": "unit", "evidence": "passed fixture"}],
                "failed": [],
                "not_run": [],
            },
            "risks": [],
            "unfinished": [],
            "next_actions": [],
        }
        receipt_path = root / "receipt.json"
        receipt_path.write_text(json.dumps(receipt), encoding="utf-8")
        path = engine.create_checkpoint(receipt_path, base=base)
        data = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(engine.head(), data["subject_commit"])
        self.assertIn("src/new.txt", data["changed_paths"])
        self.assertFalse(engine.validate_checkpoint(data))

        data["api_key"] = "fake"
        errors = engine.validate_checkpoint(data, require_commit=False)
        self.assertTrue(any("prohibited" in error for error in errors))

    def test_changed_context_rejects_shell_metacharacters(self) -> None:
        temp, root, engine = make_repo()
        self.addCleanup(temp.cleanup)
        with self.assertRaises(ContextError):
            engine.changed_context("HEAD;touch /tmp/bad")


if __name__ == "__main__":
    unittest.main()
