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

    def test_reporting_contract_requires_machine_readable_terminal_fields(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            canonical = root / "docs/engineering/agent-communication-and-reporting.md"
            canonical.parent.mkdir(parents=True)
            contract = """Outcome:
completed | partial | blocked | failed
Remaining work:
None | <remaining items>
Continuation action:
continue | stop | human
Retryable:
yes | no
Human action required:
None | <exact action>
`Outcome: completed`
`Remaining work: None`
`Continuation action: stop`
`Retryable: no`
`Human action required: None`
"""
            canonical.write_text(contract, encoding="utf-8")
            (root / "AGENTS.md").write_text(contract, encoding="utf-8")
            self.assertEqual([], verifier.validate_agent_reporting_contract(root))
            canonical.write_text(
                contract.replace("Human action required:", "Human intervention:"),
                encoding="utf-8",
            )
            errors = verifier.validate_agent_reporting_contract(root)
            self.assertTrue(any("Human action required:" in error for error in errors))

    def test_current_ci_source_quality_boundary_is_valid(self) -> None:
        repository_root = Path(__file__).resolve().parents[3]

        self.assertEqual([], verifier.validate_ci_source_quality(repository_root))

    def test_ci_source_quality_rejects_a_missing_service_security_mode(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            quality = root / "scripts/ci/quality/verify_repository_sources.sh"
            security = root / "scripts/ci/security/service_security.sh"
            baseline = root / ".github/workflows/repository-baseline.yml"
            quality.parent.mkdir(parents=True)
            security.parent.mkdir(parents=True)
            baseline.parent.mkdir(parents=True)
            quality.write_text("\n".join(verifier.SOURCE_QUALITY_MARKERS), encoding="utf-8")
            quality.chmod(0o755)
            security.write_text(
                "\n".join(f"{mode})" for mode in verifier.SERVICE_SECURITY_MODES),
                encoding="utf-8",
            )
            security.chmod(0o755)
            baseline.write_text("run: make script-static-verify\n", encoding="utf-8")
            for relative in verifier.SERVICE_SECURITY_WORKFLOWS:
                workflow = root / relative
                workflow.parent.mkdir(parents=True, exist_ok=True)
                workflow.write_text(
                    "\n".join(
                        "scripts/ci/security/service_security.sh " + mode
                        for mode in verifier.SERVICE_SECURITY_MODES
                        if not (
                            relative.endswith("identity-service.yml")
                            and mode == "osv-scan"
                        )
                    ),
                    encoding="utf-8",
                )

            errors = verifier.validate_ci_source_quality(root)

            self.assertTrue(
                any(
                    "identity-service.yml" in error and "osv-scan" in error
                    for error in errors
                )
            )

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

    def test_current_contract_package_boundary_is_valid(self) -> None:
        repository_root = Path(__file__).resolve().parents[3]

        self.assertEqual([], verifier.validate_contract_package_boundary(repository_root))

    def test_contract_gate_rejects_unversioned_unvalidated_schema(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            contract = root / "contracts/protobuf-contracts"
            proto = contract / "src/main/proto/hooshix/sample/sample.proto"
            proto.parent.mkdir(parents=True)
            proto.write_text(
                '''syntax = "proto3";
package hooshix.sample;
option java_package = "com.sajtech.sample.contract";
service SampleService { rpc Get(GetRequest) returns (GetResponse); }
message GetRequest { string id = 1; }
message GetResponse {}
''',
                encoding="utf-8",
            )
            (contract / "build.gradle.kts").write_text(
                'version = "1.0.0"\n', encoding="utf-8"
            )

            errors = verifier.validate_contract_package_boundary(root)

            self.assertTrue(any("proto path is not versioned" in error for error in errors))
            self.assertTrue(any("package is not versioned" in error for error in errors))
            self.assertTrue(any("missing validation import" in error for error in errors))
            self.assertTrue(any("has no schema validation" in error for error in errors))

    def test_contract_gate_rejects_consumer_version_drift(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            contract = root / "contracts/protobuf-contracts"
            proto = contract / "src/main/proto/hooshix/sample/v1/sample.proto"
            proto.parent.mkdir(parents=True)
            proto.write_text(
                '''syntax = "proto3";
package hooshix.sample.v1;
import "buf/validate/validate.proto";
option java_package = "com.sajtech.sample.contract.v1";
service SampleService { rpc Get(GetRequest) returns (GetResponse); }
message GetRequest { string id = 1 [(buf.validate.field).string.uuid = true]; }
message GetResponse {}
''',
                encoding="utf-8",
            )
            examples = contract / "examples/sample/v1"
            examples.mkdir(parents=True)
            (examples / "get.valid.json").write_text("{}\n", encoding="utf-8")
            (contract / "build.gradle.kts").write_text(
                '''version = "1.5.0"
api("build.buf:protovalidate:1.2.2")
val prepareBufDependencies = true
''',
                encoding="utf-8",
            )
            consumer = root / "services/sample-service/build.gradle.kts"
            consumer.parent.mkdir(parents=True)
            consumer.write_text(
                'implementation("com.sajtech.hooshix:protobuf-contracts:1.3.0")\n',
                encoding="utf-8",
            )

            errors = verifier.validate_contract_package_boundary(root)

            self.assertTrue(any("consumer version mismatch" in error for error in errors))

            (examples / "get.valid.json").unlink()
            errors = verifier.validate_contract_package_boundary(root)
            self.assertTrue(
                any(
                    "published service has no valid consumer example" in error
                    for error in errors
                )
            )

    def test_compromised_password_gradle_wrapper_is_executable(self) -> None:
        repository_root = Path(__file__).resolve().parents[3]
        wrapper = repository_root / "services/compromised-password-service/gradlew"

        self.assertTrue(wrapper.stat().st_mode & stat.S_IXUSR)


if __name__ == "__main__":
    unittest.main()
