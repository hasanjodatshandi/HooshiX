#!/usr/bin/env python3
"""Verify repository-level architecture and governance invariants.

This gate uses only the Python standard library so it can run before any
service-specific toolchain is present. It intentionally verifies repository
truth that is enforceable at bootstrap time and does not claim runtime,
security, or production readiness for services that do not exist yet.
"""

from __future__ import annotations

import json
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
    "docs/adr/decision-register.md",
    "docs/architecture/README.md",
    "docs/architecture/SOURCES.md",
    "docs/architecture/dependency-criticality-matrix.md",
    "docs/architecture/dependency-criticality.schema.json",
    "docs/architecture/dependency-criticality.yaml",
    "docs/architecture/implementation-status.md",
    "docs/engineering/build-and-ci-quality-enforcement.md",
    "docs/engineering/coding-standards.md",
    "scripts/baseline/tests/test_verify_repository.py",
    "scripts/baseline/verify_repository.py",
)

IGNORED_PATH_PARTS = {".git", "__pycache__", ".pytest_cache"}
IGNORED_SUFFIXES = {".pyc", ".pyo"}


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
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(root)
        if any(part in IGNORED_PATH_PARTS for part in relative.parts):
            continue
        if path.suffix in IGNORED_SUFFIXES:
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
    return errors


def validate_repository(root: Path = ROOT) -> list[str]:
    checks: Iterable[list[str]] = (
        validate_required_paths(root),
        validate_file_index(root),
        validate_adr_register(root),
        validate_dependency_registry(root),
        validate_source_references(root),
        validate_guarded_structure(root),
    )
    return [error for result in checks for error in result]


def main() -> int:
    errors = validate_repository(ROOT)
    if errors:
        print("Repository baseline verification FAILED:")
        for error in errors:
            print(f"- {error}")
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
