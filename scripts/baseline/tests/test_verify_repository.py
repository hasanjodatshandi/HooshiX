from __future__ import annotations

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


if __name__ == "__main__":
    unittest.main()
