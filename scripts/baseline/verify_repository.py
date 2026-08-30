#!/usr/bin/env python3
"""Verify repository-level architecture and governance invariants.

This gate uses only the Python standard library so it can run before any
service-specific toolchain is present. It intentionally verifies repository
truth that is enforceable at bootstrap time and does not claim runtime,
security, or production readiness for services that do not exist yet.
"""

from __future__ import annotations

import json
import os
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[2]

ADR_FILE_RE = re.compile(r"^(?P<id>\d{4})-.+\.md$")
ADR_HEADING_RE = re.compile(r"^# ADR-(?P<id>\d{4}):", re.MULTILINE)
ADR_REGISTER_RE = re.compile(r"^\| ADR-(?P<id>\d{4}) \|", re.MULTILINE)
EDGE_START_RE = re.compile(r"^  - operation_id:\s*(?P<value>[A-Za-z0-9_.-]+)\s*$")
EDGE_FIELD_RE = re.compile(r"^    (?P<key>[a-z_]+):\s*(?P<value>.*)$")
POLICY_REF_RE = re.compile(r"^      - (?P<value>docs/[A-Za-z0-9_./-]+)\s*$")
CLASS_RE = re.compile(r"^  - (?P<value>[A-Z_]+)\s*$")
MATRIX_OPERATION_RE = re.compile(r"^\| `(?P<value>[A-Za-z0-9_.-]+)` \|", re.MULTILINE)
DOC_PATH_RE = re.compile(r"`(?P<path>docs/[A-Za-z0-9_./-]+\.(?:md|yaml|json))`")

REQUIRED_BASELINE_PATHS = (
    ".editorconfig",
    ".gitattributes",
    ".github/workflows/repository-baseline.yml",
    ".gitignore",
    "AGENTS.md",
    "FILE_INDEX.txt",
    "Makefile",
    "README.md",
    "context/bootstrap.json",
    "context/bootstrap.schema.json",
    "context/checkpoint.schema.json",
    "context/routes.json",
    "context/routes.schema.json",
    "docs/adr/decision-register.md",
    "docs/adr/0046-adopt-git-native-agent-context-engine-v1.md",
    "docs/adr/0047-adopt-openai-secure-mcp-tunnel-for-chatgpt-web-context-access-v1.md",
    "docs/adr/0048-adopt-policy-gated-developer-host-ops-mcp-v1.md",
    "docs/adr/0049-adopt-policy-gated-developer-host-desktop-mcp-v1.md",
    "docs/adr/0050-add-policy-bound-desktop-credential-broker-v1.md",
    "docs/adr/0051-separate-windows-mcp-runtime-from-wsl-workspace-v1.md",
    "docs/runbooks/chatgpt-web-secure-mcp-tunnel.md",
    "docs/runbooks/chatgpt-web-ops-mcp.md",
    "docs/runbooks/chatgpt-web-desktop-mcp.md",
    "docs/architecture/README.md",
    "docs/architecture/SOURCES.md",
    "docs/architecture/dependency-criticality-matrix.md",
    "docs/architecture/dependency-criticality.schema.json",
    "docs/architecture/dependency-criticality.yaml",
    "docs/architecture/implementation-status.md",
    "docs/engineering/build-and-ci-quality-enforcement.md",
    "docs/engineering/coding-standards.md",
    "scripts/ci/quality/verify_repository_sources.sh",
    "scripts/ci/security/service_security.sh",
    "scripts/context/context_engine.py",
    "scripts/context/post_merge_checkpoint.py",
    "scripts/context/tests/test_context_engine.py",
    "scripts/context/tests/test_post_merge_checkpoint.py",
    "scripts/baseline/tests/test_verify_repository.py",
    "scripts/baseline/verify_repository.py",
)

IGNORED_PATH_PARTS = {".git", ".gradle", ".local-runtime", ".platform-runtime", ".vscode", "build", "__pycache__", ".pytest_cache", ".ruff_cache", "node_modules", "dist", "test-results"}
IGNORED_SUFFIXES = {".pyc", ".pyo", ".tsbuildinfo"}

EXTERNALIZED_MCP_PATHS = (
    "scripts/context/mcp_server.py",
    "scripts/context/tests/test_mcp_server.py",
)
EXTERNALIZED_MCP_PREFIXES = ("scripts/ops/", "scripts/desktop/", "ops/", "desktop/")

REPORTING_PROTOCOL_FIELDS = (
    "Outcome:",
    "Remaining work:",
    "Continuation action:",
    "Retryable:",
    "Human action required:",
)
REPORTING_PROTOCOL_VALUE_MARKERS = (
    "completed | partial | blocked | failed",
    "continue | stop | human",
    "yes | no",
)
REPORTING_PROTOCOL_PATHS = (
    "AGENTS.md",
    "docs/engineering/agent-communication-and-reporting.md",
)

SERVICE_SECURITY_WORKFLOWS = (
    ".github/workflows/authorization-service.yml",
    ".github/workflows/compromised-password-service.yml",
    ".github/workflows/identity-service.yml",
    ".github/workflows/notification-service.yml",
    ".github/workflows/web-bff.yml",
)
SERVICE_SECURITY_MODES = (
    "gitleaks-fixtures",
    "gitleaks-scan",
    "osv-install",
    "osv-scan",
)
SOURCE_QUALITY_MARKERS = (
    "readonly actionlint_version='1.7.12'",
    "readonly actionlint_sha256='8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8'",
    "readonly shellcheck_version='0.11.0'",
    "readonly shellcheck_sha256='8c3be12b05d5c177a04c29e3c78ce89ac86f1595681cab149b65b97c4e227198'",
    "readonly ruff_version='0.16.5'",
    "readonly ruff_sha256='65b8bae7e43f12a91b71036a52176012b3aefb725d5ae263e2771474110a0983'",
    "--select E9,F63,F7,F82",
    "--exclude=SC1090,SC2034,SC2154",
    '"${actionlint}" -shellcheck="${shellcheck}"',
)


@dataclass(frozen=True)
class DependencyEdge:
    operation_id: str
    fields: dict[str, str]
    policy_refs: tuple[str, ...] = field(default_factory=tuple)

    @property
    def dependency_id(self) -> str:
        return self.fields.get("dependency_id", "")


@dataclass(frozen=True)
class DependencyRegistry:
    version: int
    classes: tuple[str, ...]
    edges: tuple[DependencyEdge, ...]


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _strip_scalar(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
        return value[1:-1]
    return value


def collect_repository_files(root: Path) -> set[str]:
    files: set[str] = set()
    for current, directories, filenames in os.walk(root, followlinks=False):
        current_path = Path(current)
        directories[:] = [
            directory
            for directory in directories
            if directory not in IGNORED_PATH_PARTS
        ]
        for filename in filenames:
            path = current_path / filename
            try:
                if path.is_symlink():
                    continue
                if path.suffix in IGNORED_SUFFIXES:
                    continue
                relative = path.relative_to(root)
            except (OSError, ValueError):
                continue
            if any(part in IGNORED_PATH_PARTS for part in relative.parts):
                continue
            files.add(relative.as_posix())
    return files


def validate_required_paths(root: Path) -> list[str]:
    return [
        f"missing required baseline path: {path}"
        for path in REQUIRED_BASELINE_PATHS
        if not (root / path).is_file()
    ]


def validate_file_index(root: Path) -> list[str]:
    index_path = root / "FILE_INDEX.txt"
    if not index_path.is_file():
        return ["FILE_INDEX.txt is missing"]

    expected = collect_repository_files(root)
    indexed_lines = [line.strip() for line in read_text(index_path).splitlines() if line.strip()]
    indexed = set(indexed_lines)
    errors: list[str] = []

    if len(indexed_lines) != len(indexed):
        errors.append("FILE_INDEX.txt contains duplicate paths")

    if indexed_lines != sorted(indexed_lines):
        errors.append("FILE_INDEX.txt must be sorted lexicographically")

    missing = sorted(expected - indexed)
    stale = sorted(indexed - expected)
    if missing:
        errors.append("FILE_INDEX.txt is missing: " + ", ".join(missing))
    if stale:
        errors.append("FILE_INDEX.txt contains non-existent paths: " + ", ".join(stale))
    return errors


def validate_adr_register(root: Path) -> list[str]:
    adr_dir = root / "docs/adr"
    errors: list[str] = []
    ids_to_files: dict[str, list[Path]] = {}

    for path in sorted(adr_dir.glob("*.md")):
        match = ADR_FILE_RE.match(path.name)
        if not match:
            continue
        adr_id = match.group("id")
        ids_to_files.setdefault(adr_id, []).append(path)

        heading = ADR_HEADING_RE.search(read_text(path))
        if heading is None:
            errors.append(f"{path.relative_to(root)} is missing '# ADR-{adr_id}:' heading")
        elif heading.group("id") != adr_id:
            errors.append(
                f"{path.relative_to(root)} heading ADR-{heading.group('id')} "
                f"does not match filename ADR-{adr_id}"
            )

    for adr_id, paths in sorted(ids_to_files.items()):
        if len(paths) > 1:
            rendered = ", ".join(path.relative_to(root).as_posix() for path in paths)
            errors.append(f"ADR-{adr_id} is reused by multiple files: {rendered}")

    register_path = adr_dir / "decision-register.md"
    register_ids = ADR_REGISTER_RE.findall(read_text(register_path))
    if len(register_ids) != len(set(register_ids)):
        errors.append("decision-register.md contains duplicate ADR identifiers")

    file_ids = set(ids_to_files)
    register_set = set(register_ids)
    missing = sorted(file_ids - register_set)
    stale = sorted(register_set - file_ids)
    if missing:
        errors.append(
            "decision-register.md is missing ADRs: "
            + ", ".join(f"ADR-{item}" for item in missing)
        )
    if stale:
        errors.append(
            "decision-register.md references missing ADR files: "
            + ", ".join(f"ADR-{item}" for item in stale)
        )
    return errors


def parse_dependency_registry(text: str) -> DependencyRegistry:
    version_match = re.search(r"^version:\s*(\d+)\s*$", text, re.MULTILINE)
    if version_match is None:
        raise ValueError("dependency registry is missing numeric version")

    classes: list[str] = []
    in_classes = False
    in_edges = False
    current_operation: str | None = None
    current_fields: dict[str, str] = {}
    current_policy_refs: list[str] = []
    current_policy_refs_declared = False
    edges: list[DependencyEdge] = []

    def finish_edge() -> None:
        nonlocal current_operation, current_fields, current_policy_refs
        nonlocal current_policy_refs_declared
        if current_operation is None:
            return
        edges.append(
            DependencyEdge(
                operation_id=current_operation,
                fields=dict(current_fields),
                policy_refs=tuple(current_policy_refs),
            )
        )
        current_operation = None
        current_fields = {}
        current_policy_refs = []
        current_policy_refs_declared = False

    for line in text.splitlines():
        if line == "classes:":
            in_classes = True
            in_edges = False
            continue
        if line == "edges:":
            finish_edge()
            in_classes = False
            in_edges = True
            continue

        if in_classes:
            match = CLASS_RE.match(line)
            if match:
                classes.append(match.group("value"))
            continue

        if not in_edges:
            continue

        start = EDGE_START_RE.match(line)
        if start:
            finish_edge()
            current_operation = start.group("value")
            continue

        if current_operation is None:
            continue

        policy_ref = POLICY_REF_RE.match(line)
        if policy_ref:
            current_policy_refs.append(policy_ref.group("value"))
            continue

        field_match = EDGE_FIELD_RE.match(line)
        if field_match:
            key = field_match.group("key")
            if key == "policy_refs":
                if current_policy_refs_declared:
                    raise ValueError(
                        f"{current_operation} contains duplicate field: policy_refs"
                    )
                current_policy_refs_declared = True
                continue
            if key in current_fields:
                raise ValueError(f"{current_operation} contains duplicate field: {key}")
            current_fields[key] = _strip_scalar(field_match.group("value"))

    finish_edge()
    return DependencyRegistry(
        version=int(version_match.group(1)),
        classes=tuple(classes),
        edges=tuple(edges),
    )


def validate_dependency_registry(root: Path) -> list[str]:
    yaml_path = root / "docs/architecture/dependency-criticality.yaml"
    schema_path = root / "docs/architecture/dependency-criticality.schema.json"
    matrix_path = root / "docs/architecture/dependency-criticality-matrix.md"
    errors: list[str] = []

    try:
        registry = parse_dependency_registry(read_text(yaml_path))
    except ValueError as exc:
        return [str(exc)]

    try:
        schema = json.loads(read_text(schema_path))
    except json.JSONDecodeError as exc:
        return [f"dependency-criticality.schema.json is invalid JSON: {exc}"]

    expected_version = schema.get("properties", {}).get("version", {}).get("const")
    expected_classes = tuple(schema.get("properties", {}).get("classes", {}).get("const", []))
    edge_schema = schema.get("properties", {}).get("edges", {}).get("items", {})
    required_fields = set(edge_schema.get("required", [])) - {"operation_id", "policy_refs"}
    allowed_fields = set(edge_schema.get("properties", {})) - {"operation_id", "policy_refs"}

    if registry.version != expected_version:
        errors.append(
            f"dependency registry version {registry.version} does not match schema const {expected_version}"
        )
    if registry.classes != expected_classes:
        errors.append("dependency registry classes do not exactly match schema classes")
    if not registry.edges:
        errors.append("dependency registry has no edges")

    seen_pairs: set[tuple[str, str]] = set()
    operation_ids: list[str] = []
    for edge in registry.edges:
        operation_ids.append(edge.operation_id)
        missing_fields = sorted(
            field_name for field_name in required_fields if not edge.fields.get(field_name)
        )
        if missing_fields:
            errors.append(
                f"{edge.operation_id} is missing required fields: {', '.join(missing_fields)}"
            )

        unknown_fields = sorted(set(edge.fields) - allowed_fields)
        if unknown_fields:
            errors.append(
                f"{edge.operation_id} has unsupported fields: {', '.join(unknown_fields)}"
            )

        dependency_class = edge.fields.get("class", "")
        if dependency_class not in registry.classes:
            errors.append(
                f"{edge.operation_id} uses unknown dependency class: "
                f"{dependency_class or '<missing>'}"
            )

        pair = (edge.operation_id, edge.dependency_id)
        if pair in seen_pairs:
            errors.append(f"duplicate dependency edge: {edge.operation_id} -> {edge.dependency_id}")
        seen_pairs.add(pair)

        if not edge.policy_refs:
            errors.append(f"{edge.operation_id} has no policy_refs")
        if len(edge.policy_refs) != len(set(edge.policy_refs)):
            errors.append(f"{edge.operation_id} has duplicate policy_refs")
        for ref in edge.policy_refs:
            if not (root / ref).is_file():
                errors.append(f"{edge.operation_id} references missing policy file: {ref}")

    if len(operation_ids) != len(set(operation_ids)):
        errors.append("dependency registry reuses an operation_id")

    matrix_ids = MATRIX_OPERATION_RE.findall(read_text(matrix_path))
    if len(matrix_ids) != len(set(matrix_ids)):
        errors.append("dependency-criticality-matrix.md contains duplicate operation IDs")
    if matrix_ids != operation_ids:
        missing = [operation for operation in operation_ids if operation not in matrix_ids]
        stale = [operation for operation in matrix_ids if operation not in operation_ids]
        order_only = not missing and not stale
        if missing:
            errors.append("dependency matrix is missing operations: " + ", ".join(missing))
        if stale:
            errors.append(
                "dependency matrix contains non-canonical operations: " + ", ".join(stale)
            )
        if order_only:
            errors.append("dependency matrix operation order differs from canonical YAML")

    return errors


def validate_source_references(root: Path) -> list[str]:
    sources_path = root / "docs/architecture/SOURCES.md"
    errors: list[str] = []
    for match in DOC_PATH_RE.finditer(read_text(sources_path)):
        target = match.group("path")
        if not (root / target).is_file():
            errors.append(f"SOURCES.md references missing path: {target}")
    return sorted(set(errors))


def validate_agent_reporting_contract(root: Path) -> list[str]:
    errors: list[str] = []
    texts: dict[str, str] = {}
    for relative in REPORTING_PROTOCOL_PATHS:
        path = root / relative
        if not path.is_file():
            errors.append(f"missing reporting protocol authority: {relative}")
            continue
        text = read_text(path)
        texts[relative] = text
        for field_name in REPORTING_PROTOCOL_FIELDS:
            if field_name not in text:
                errors.append(f"{relative} is missing reporting protocol field: {field_name}")

    canonical = texts.get("docs/engineering/agent-communication-and-reporting.md")
    if canonical is not None:
        for marker in REPORTING_PROTOCOL_VALUE_MARKERS:
            if marker not in canonical:
                errors.append(
                    "agent-communication-and-reporting.md is missing reporting protocol token set: "
                    + marker
                )
        completed_markers = (
            "`Outcome: completed`",
            "`Remaining work: None`",
            "`Continuation action: stop`",
            "`Retryable: no`",
            "`Human action required: None`",
        )
        for marker in completed_markers:
            if marker not in canonical:
                errors.append(
                    "agent-communication-and-reporting.md is missing completed-terminal invariant: "
                    + marker
                )
    return errors


def validate_ci_source_quality(root: Path) -> list[str]:
    errors: list[str] = []
    security_script = root / "scripts/ci/security/service_security.sh"
    quality_script = root / "scripts/ci/quality/verify_repository_sources.sh"
    baseline_workflow = root / ".github/workflows/repository-baseline.yml"

    for path in (security_script, quality_script):
        if not path.is_file():
            errors.append(f"missing CI source-quality script: {path.relative_to(root)}")
        elif not os.access(path, os.X_OK):
            errors.append(f"CI source-quality script is not executable: {path.relative_to(root)}")

    if quality_script.is_file():
        quality_text = read_text(quality_script)
        for marker in SOURCE_QUALITY_MARKERS:
            if marker not in quality_text:
                errors.append(f"repository source-quality gate is missing: {marker}")

    if security_script.is_file():
        security_text = read_text(security_script)
        for mode in SERVICE_SECURITY_MODES:
            if f"{mode})" not in security_text:
                errors.append(f"shared service security script is missing mode: {mode}")

    invocation_prefix = "scripts/ci/security/service_security.sh "
    for relative in SERVICE_SECURITY_WORKFLOWS:
        workflow = root / relative
        if not workflow.is_file():
            errors.append(f"missing service security workflow: {relative}")
            continue
        workflow_text = read_text(workflow)
        for mode in SERVICE_SECURITY_MODES:
            invocation = invocation_prefix + mode
            if workflow_text.count(invocation) != 1:
                errors.append(
                    f"service security workflow must invoke {mode} exactly once: {relative}"
                )

    if not baseline_workflow.is_file():
        errors.append("missing repository baseline workflow")
    elif "run: make script-static-verify" not in read_text(baseline_workflow):
        errors.append("repository baseline workflow does not enforce script-static-verify")

    return errors



def validate_contract_package_boundary(root: Path) -> list[str]:
    errors: list[str] = []
    contract_proto = root / "contracts/protobuf-contracts/src/main/proto"
    if not contract_proto.is_dir():
        errors.append("missing canonical contract package proto directory")
        return errors

    for service in (root / "services").glob("*/src/main/proto"):
        errors.append(f"service-local canonical proto ownership exists: {service.relative_to(root)}")

    for service in (root / "services").glob("*/build.gradle.kts"):
        text = read_text(service)
        if "../" in text and "src/main/proto" in text:
            errors.append(f"service build references external proto source path: {service.relative_to(root)}")

    contract_build = root / "contracts/protobuf-contracts/build.gradle.kts"
    if not contract_build.is_file():
        errors.append("missing contract package build definition")

    build_text = read_text(contract_build) if contract_build.is_file() else ""
    version_match = re.search(r'^version = "(\d+\.\d+\.\d+)"$', build_text, re.MULTILINE)
    contract_version = version_match.group(1) if version_match else None
    if contract_version is None:
        errors.append("contract package version is missing or is not semantic versioning")
    if not re.search(r'api\("build\.buf:protovalidate:\d+\.\d+\.\d+"\)', build_text):
        errors.append("contract package is missing a pinned Protovalidate runtime")
    if "prepareBufDependencies" not in build_text:
        errors.append("contract package is missing reproducible local Buf validation schema preparation")

    proto_files = sorted(contract_proto.rglob("*.proto"))
    if not proto_files:
        errors.append("canonical contract package contains no proto schemas")
    for proto in proto_files:
        relative = proto.relative_to(contract_proto)
        text = read_text(proto)
        if not any(re.fullmatch(r"v\d+", part) for part in relative.parts):
            errors.append(f"contract proto path is not versioned: {relative}")
        if not re.search(r"^package [A-Za-z0-9_.]+\.v\d+;$", text, re.MULTILINE):
            errors.append(f"contract proto package is not versioned: {relative}")
        if not re.search(
            r'^option java_package = "[A-Za-z0-9_.]+\.v\d+";$', text, re.MULTILINE
        ):
            errors.append(f"contract Java package is not versioned: {relative}")
        request_types = re.findall(r"\brpc\s+\w+\s*\(\s*(\w+)\s*\)", text)
        if request_types and 'import "buf/validate/validate.proto";' not in text:
            errors.append(f"contract service schema is missing validation import: {relative}")
        for request_type in request_types:
            block = proto_message_block(text, request_type)
            if block is None or "buf.validate" not in block:
                errors.append(
                    f"RPC request has no schema validation: {relative}:{request_type}"
                )

        package_match = re.search(r"^package hooshix\.([a-z]+)\.(v\d+);$", text, re.MULTILINE)
        if request_types and package_match:
            example_dir = (
                root
                / "contracts/protobuf-contracts/examples"
                / package_match.group(1)
                / package_match.group(2)
            )
            for service_name in re.findall(r"\bservice\s+(\w+)\s*\{", text):
                service_block = proto_named_block(text, "service", service_name)
                rpc_names = (
                    re.findall(r"\brpc\s+(\w+)\s*\(", service_block)
                    if service_block is not None
                    else []
                )
                if not any(
                    (example_dir / f"{camel_to_kebab(rpc_name)}.valid.json").is_file()
                    for rpc_name in rpc_names
                ):
                    errors.append(
                        f"published service has no valid consumer example: "
                        f"{relative}:{service_name}"
                    )

    if contract_version is not None:
        for service_build in sorted((root / "services").glob("*/build.gradle.kts")):
            text = read_text(service_build)
            dependency = re.search(
                r'com\.sajtech\.hooshix:protobuf-contracts:(\d+\.\d+\.\d+)', text
            )
            if dependency and dependency.group(1) != contract_version:
                errors.append(
                    f"contract consumer version mismatch: {service_build.relative_to(root)} "
                    f"uses {dependency.group(1)} instead of {contract_version}"
                )

    for service_name in (
        "authorization-service",
        "compromised-password-service",
        "identity-service",
        "notification-service",
    ):
        java_root = root / "services" / service_name / "src/main/java"
        if java_root.is_dir() and not any(
            "ContractValidationServerInterceptor" in read_text(path)
            for path in java_root.rglob("*.java")
        ):
            errors.append(f"gRPC server does not install contract validation: {service_name}")

    return errors


def proto_message_block(text: str, message_name: str) -> str | None:
    return proto_named_block(text, "message", message_name)


def proto_named_block(text: str, keyword: str, name: str) -> str | None:
    start_match = re.search(rf"\b{re.escape(keyword)}\s+{re.escape(name)}\s*\{{", text)
    if start_match is None:
        return None
    start = start_match.start()
    depth = 0
    for index in range(start_match.end() - 1, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[start : index + 1]
    return None


def camel_to_kebab(value: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "-", value).lower()

def validate_guarded_structure(root: Path) -> list[str]:
    errors: list[str] = []
    reference_service = root / "services/reference-data-service"
    if reference_service.exists():
        errors.append(
            "services/reference-data-service exists before ADR-0041 trigger evidence "
            "is represented in the current architecture"
        )

    for forbidden in (root / "services/common", root / "services/shared"):
        if forbidden.exists():
            errors.append(
                f"forbidden cross-service dumping root exists: {forbidden.relative_to(root)}"
            )

    repository_files = collect_repository_files(root)
    for path in EXTERNALIZED_MCP_PATHS:
        if path in repository_files:
            errors.append(f"externalized MCP runtime path returned to HooshiX: {path}")
    for prefix in EXTERNALIZED_MCP_PREFIXES:
        matches = sorted(path for path in repository_files if path.startswith(prefix))
        if matches:
            errors.append(
                f"externalized MCP runtime prefix returned to HooshiX: {prefix} "
                + ", ".join(matches)
            )
    return errors


def validate_repository(root: Path = ROOT) -> list[str]:
    checks: tuple[tuple[str, callable], ...] = (
        ("required_paths", lambda: validate_required_paths(root)),
        ("file_index", lambda: validate_file_index(root)),
        ("adr_register", lambda: validate_adr_register(root)),
        ("dependency_registry", lambda: validate_dependency_registry(root)),
        ("source_references", lambda: validate_source_references(root)),
        ("agent_reporting", lambda: validate_agent_reporting_contract(root)),
        ("ci_source_quality", lambda: validate_ci_source_quality(root)),
        ("contract_package_boundary", lambda: validate_contract_package_boundary(root)),
        ("guarded_structure", lambda: validate_guarded_structure(root)),
    )
    errors: list[str] = []
    for name, validator in checks:
        try:
            result = validator()
            errors.extend([f"{name}: {error!r}" for error in result if error])
        except Exception as exc:
            raise
    return errors


def main() -> int:
    errors = validate_repository(ROOT)
    if errors:
        print("Repository baseline verification FAILED:")
        print(f"error_count={len(errors)}")
        for error in errors:
            print(f"- {error!r}")
        return 1

    adr_count = len(
        [path for path in (ROOT / "docs/adr").glob("*.md") if ADR_FILE_RE.match(path.name)]
    )
    registry = parse_dependency_registry(
        read_text(ROOT / "docs/architecture/dependency-criticality.yaml")
    )
    print(
        "Repository baseline verification PASSED "
        f"({len(collect_repository_files(ROOT))} indexed files, "
        f"{adr_count} ADRs, {len(registry.edges)} dependency edges)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
