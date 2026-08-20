from __future__ import annotations

import stat
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_repository as verifier  # noqa: E402


class RepositoryBaselineVerifierTest(unittest.TestCase):
    def test_dependency_parser_reads_classes_fields_and_policy_refs(self) -> None:
        registry = verifier.parse_dependency_registry(
            """version: 2
classes:
  - AUTHORITATIVE_SECURITY
edges:
  - operation_id: sample.operation
    caller_owner: Sample
    dependency_id: sample.Dependency
    class: AUTHORITATIVE_SECURITY
    failure_action: \"fail closed\"
    retry_owner: none
    fallback: none
    owner: Sample
    policy_refs:
      - docs/adr/0001-sample.md
"""
        )

        self.assertEqual(2, registry.version)
        self.assertEqual(("AUTHORITATIVE_SECURITY",), registry.classes)
        self.assertEqual(1, len(registry.edges))
        self.assertEqual("sample.operation", registry.edges[0].operation_id)
        self.assertEqual("sample.Dependency", registry.edges[0].dependency_id)
        self.assertEqual(("docs/adr/0001-sample.md",), registry.edges[0].policy_refs)

    def test_dependency_parser_rejects_duplicate_scalar_fields(self) -> None:
        text = """version: 2
classes:
  - AUTHORITATIVE_SECURITY
edges:
  - operation_id: sample.operation
    caller_owner: Sample
    caller_owner: ConflictingSample
"""

        with self.assertRaisesRegex(ValueError, "duplicate field: caller_owner"):
            verifier.parse_dependency_registry(text)

    def test_dependency_parser_rejects_duplicate_policy_ref_sections(self) -> None:
        text = """version: 2
classes:
  - AUTHORITATIVE_SECURITY
edges:
  - operation_id: sample.operation
    policy_refs:
      - docs/adr/0001-sample.md
    policy_refs:
      - docs/adr/0002-sample.md
"""

        with self.assertRaisesRegex(ValueError, "duplicate field: policy_refs"):
            verifier.parse_dependency_registry(text)

    def test_file_index_detects_missing_and_stale_paths(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "FILE_INDEX.txt").write_text(
                "FILE_INDEX.txt\nstale.txt\n", encoding="utf-8"
            )
            (root / "actual.txt").write_text("data\n", encoding="utf-8")

            errors = verifier.validate_file_index(root)

            self.assertTrue(any("actual.txt" in error for error in errors))
            self.assertTrue(any("stale.txt" in error for error in errors))

    def test_repository_files_exclude_generated_local_runtime_and_gradle_state(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "tracked.txt").write_text("source\n", encoding="utf-8")
            generated = [
                root / ".local-runtime/keys/private.properties",
                root / ".platform-runtime/staging/files/generated-secret",
                root / "services/sample/build/generated.txt",
                root / "services/sample/.gradle/cache.bin",
            ]
            for path in generated:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("generated\n", encoding="utf-8")

            files = verifier.collect_repository_files(root)

            self.assertIn("tracked.txt", files)
            for path in generated:
                self.assertNotIn(path.relative_to(root).as_posix(), files)

    def test_adr_register_detects_identifier_reuse(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            adr_dir = root / "docs/adr"
            adr_dir.mkdir(parents=True)
            (adr_dir / "0001-first.md").write_text(
                "# ADR-0001: First\n", encoding="utf-8"
            )
            (adr_dir / "0001-second.md").write_text(
                "# ADR-0001: Second\n", encoding="utf-8"
            )
            (adr_dir / "decision-register.md").write_text(
                "| ADR-0001 | first |\n", encoding="utf-8"
            )

            errors = verifier.validate_adr_register(root)

            self.assertTrue(any("reused by multiple files" in error for error in errors))

    def test_guard_rejects_premature_reference_data_service(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "services/reference-data-service").mkdir(parents=True)

            errors = verifier.validate_guarded_structure(root)

            self.assertTrue(any("ADR-0041 trigger" in error for error in errors))

    def test_guard_rejects_externalized_mcp_runtime_paths(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            ops_path = root / "scripts/ops/mcp_server.py"
            ops_path.parent.mkdir(parents=True)
            ops_path.write_text("external runtime\n", encoding="utf-8")
            context_path = root / "scripts/context/mcp_server.py"
            context_path.parent.mkdir(parents=True)
            context_path.write_text("external adapter\n", encoding="utf-8")

            errors = verifier.validate_guarded_structure(root)

            self.assertTrue(any("externalized MCP runtime prefix" in error for error in errors))
            self.assertTrue(any("externalized MCP runtime path" in error for error in errors))

    def test_compromised_password_gradle_wrapper_is_executable(self) -> None:
        repository_root = Path(__file__).resolve().parents[3]
        wrapper = repository_root / "services/compromised-password-service/gradlew"

        self.assertTrue(wrapper.stat().st_mode & stat.S_IXUSR)


if __name__ == "__main__":
    unittest.main()
