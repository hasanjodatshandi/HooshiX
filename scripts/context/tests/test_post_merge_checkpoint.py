from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from context_engine import ContextEngine, ContextError  # noqa: E402
from post_merge_checkpoint import (  # noqa: E402
    create_post_merge_checkpoint,
    validate_post_merge_checkpoint,
    verify_tracked_post_merge_checkpoints,
)
from test_context_engine import git, make_repo, write  # noqa: E402


SOURCE_CHECKPOINT = "context/checkpoints/source.json"


def create_source_checkpoint(
    root: Path, engine: ContextEngine, *, base: str, pull_request: int = 42
) -> str:
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
        "next_actions": ["Run protected CI after final PR head"],
        "pull_request": pull_request,
    }
    receipt_path = root / "source-receipt.json"
    receipt_path.write_text(json.dumps(receipt), encoding="utf-8")
    path = engine.create_checkpoint(receipt_path, base=base)
    return path.relative_to(root).as_posix()


def post_merge_receipt(root: Path, *, pull_request: int = 42) -> Path:
    receipt = {
        "objective": "Finalize identity validation after merge",
        "scope": {
            "review_mode": "targeted",
            "routes": ["identity"],
            "bounded_contexts": ["Identity"],
        },
        "completed": ["Merged implementation and verified final main state"],
        "decisions": [
            {
                "summary": "Current Git remains authoritative",
                "authority_refs": ["AGENTS.md"],
            }
        ],
        "verification": {
            "passed": [{"check": "protected CI", "evidence": "passed after merge"}],
            "failed": [],
            "not_run": [],
        },
        "risks": [],
        "unfinished": [],
        "next_actions": [],
        "pull_request": pull_request,
    }
    path = root / "post-merge-receipt.json"
    path.write_text(json.dumps(receipt), encoding="utf-8")
    return path


class PostMergeCheckpointTest(unittest.TestCase):
    def test_create_post_merge_checkpoint_binds_merge_and_excludes_checkpoint_transport(self) -> None:
        temp, root, engine = make_repo()
        self.addCleanup(temp.cleanup)
        base = engine.head()
        write(root, "src/new.txt", "merged work\n")
        git(root, "add", "src/new.txt")
        git(root, "commit", "-m", "work")
        source = create_source_checkpoint(root, engine, base=base)
        git(root, "add", source)
        git(root, "commit", "-m", "work checkpoint")
        merge_commit = engine.head()

        path = create_post_merge_checkpoint(
            engine,
            post_merge_receipt(root),
            base=base,
            merge_commit=merge_commit,
            source_checkpoint=source,
        )
        data = json.loads(path.read_text(encoding="utf-8"))

        self.assertEqual("post-merge", data["checkpoint_kind"])
        self.assertEqual(source, data["source_checkpoint"])
        self.assertEqual("main", data["branch"])
        self.assertEqual(base, data["base_commit"])
        self.assertEqual(merge_commit, data["subject_commit"])
        self.assertEqual(["src/new.txt"], data["changed_paths"])
        self.assertEqual([], data["next_actions"])
        self.assertEqual([], validate_post_merge_checkpoint(engine, data, verify_git=True))
        self.assertEqual([], verify_tracked_post_merge_checkpoints(engine))

    def test_post_merge_checkpoint_rejects_pull_request_mismatch(self) -> None:
        temp, root, engine = make_repo()
        self.addCleanup(temp.cleanup)
        base = engine.head()
        write(root, "src/new.txt", "merged work\n")
        git(root, "add", "src/new.txt")
        git(root, "commit", "-m", "work")
        source = create_source_checkpoint(root, engine, base=base, pull_request=42)
        git(root, "add", source)
        git(root, "commit", "-m", "work checkpoint")

        with self.assertRaises(ContextError):
            create_post_merge_checkpoint(
                engine,
                post_merge_receipt(root, pull_request=43),
                base=base,
                merge_commit=engine.head(),
                source_checkpoint=source,
            )

    def test_verifier_detects_tampered_git_derived_paths(self) -> None:
        temp, root, engine = make_repo()
        self.addCleanup(temp.cleanup)
        base = engine.head()
        write(root, "src/new.txt", "merged work\n")
        git(root, "add", "src/new.txt")
        git(root, "commit", "-m", "work")
        source = create_source_checkpoint(root, engine, base=base)
        git(root, "add", source)
        git(root, "commit", "-m", "work checkpoint")
        path = create_post_merge_checkpoint(
            engine,
            post_merge_receipt(root),
            base=base,
            merge_commit=engine.head(),
            source_checkpoint=source,
        )
        data = json.loads(path.read_text(encoding="utf-8"))
        data["changed_paths"] = ["wrong/path.txt"]
        path.write_text(json.dumps(data), encoding="utf-8")

        errors = verify_tracked_post_merge_checkpoints(engine)
        self.assertTrue(any("changed_paths differ" in error for error in errors))

    def test_post_merge_checkpoint_rejects_non_main_subject(self) -> None:
        temp, root, engine = make_repo()
        self.addCleanup(temp.cleanup)
        base = engine.head()
        write(root, "src/main.txt", "main work\n")
        git(root, "add", "src/main.txt")
        git(root, "commit", "-m", "main work")
        source = create_source_checkpoint(root, engine, base=base)
        git(root, "add", source)
        git(root, "commit", "-m", "work checkpoint")
        main_tip = engine.head()
        git(root, "switch", "-c", "side")
        write(root, "src/side.txt", "side work\n")
        git(root, "add", "src/side.txt")
        git(root, "commit", "-m", "side work")
        side_commit = engine.head()
        git(root, "switch", "main")
        self.assertEqual(main_tip, engine.head())

        with self.assertRaises(ContextError):
            create_post_merge_checkpoint(
                engine,
                post_merge_receipt(root),
                base=base,
                merge_commit=side_commit,
                source_checkpoint=source,
            )

    def test_post_merge_checkpoint_rejects_shell_metacharacters(self) -> None:
        temp, root, engine = make_repo()
        self.addCleanup(temp.cleanup)
        base = engine.head()
        write(root, "src/new.txt", "merged work\n")
        git(root, "add", "src/new.txt")
        git(root, "commit", "-m", "work")
        source = create_source_checkpoint(root, engine, base=base)
        git(root, "add", source)
        git(root, "commit", "-m", "work checkpoint")

        with self.assertRaises(ContextError):
            create_post_merge_checkpoint(
                engine,
                post_merge_receipt(root),
                base=base,
                merge_commit="HEAD;touch-/tmp/bad",
                source_checkpoint=source,
            )


if __name__ == "__main__":
    unittest.main()
