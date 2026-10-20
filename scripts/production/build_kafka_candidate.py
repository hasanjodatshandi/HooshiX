"""Build, scan and smoke-test one patched upstream Kafka image; never publish or install."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from test_kafka_runtime_native import exercise
from test_postgresql_isolation_native import docker
from verify_openbao_artifact import load
from verify_postgresql_artifacts import require_complete_inventory, require_unsuppressed_scan, scan_inventory

ROOT = Path(__file__).resolve().parents[2]
PIN = ROOT / "infrastructure/production/kafka/candidate.json"
MAX_ARTIFACT_BYTES = 64 * 1024 * 1024
MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2/"
EXPECTED_REPLACEMENTS = {
    "jackson-core-2.21.6.jar", "jackson-databind-2.21.6.jar",
    "jackson-dataformat-csv-2.21.6.jar", "jackson-dataformat-yaml-2.21.6.jar",
    "jackson-datatype-jdk8-2.21.6.jar", "jackson-jakarta-rs-base-2.21.6.jar",
    "jackson-jakarta-rs-json-provider-2.21.6.jar", "jackson-module-blackbird-2.21.6.jar",
    "jackson-module-jakarta-xmlbind-annotations-2.21.6.jar", "lz4-java-1.11.2.jar",
}


def pins() -> dict:
    pin = load(PIN)
    if (pin.get("schema_version") != 1 or pin.get("component") != "kafka"
            or pin.get("version") != "4.2.2"
            or pin.get("upstream_image") != "apache/kafka@sha256:daa39dc85acd37b148fad74d6b22b60fa5f7fa05048a5c8aa3b654140c63dd58"
            or pin.get("platform") != "linux/amd64"
            or pin.get("production_promotion") != "blocked-until-protected-publication-runtime-staging-and-recovery-evidence"
            or pin.get("application_ingress") != "CLOSED"):
        raise ValueError("reviewed blocked Kafka candidate required")
    if pin.get("apk_packages") != {"libcrypto3": "3.5.9-r0", "libexpat": "2.9.0-r0",
                                   "libssl3": "3.5.9-r0", "openssl": "3.5.9-r0", "zlib": "1.3.2-r1"}:
        raise ValueError("exact patched Alpine packages required")
    dependencies = pin.get("maven_overrides")
    if (not isinstance(dependencies, list) or len(dependencies) != len(EXPECTED_REPLACEMENTS)
            or {item.get("replaces") for item in dependencies} != EXPECTED_REPLACEMENTS):
        raise ValueError("complete exact Maven override set required")
    for item in dependencies:
        if (item.get("version") != ("1.11.4" if item["artifact"] == "lz4-java" else "2.21.7")
                or not re.fullmatch(r"[a-f0-9]{64}", item.get("sha256", ""))
                or not re.fullmatch(r"[A-Za-z0-9_./-]+\.jar", item.get("path", ""))
                or ".." in item["path"]):
            raise ValueError("pinned patched artifact required")
    return pin


def download_overrides(context: Path, pin: dict) -> dict:
    overlay = context / "overlay-jars"
    overlay.mkdir(mode=0o700)
    hashes = {}
    for item in pin["maven_overrides"]:
        name = f"{item['artifact']}-{item['version']}.jar"
        with urllib.request.urlopen(MAVEN_CENTRAL + item["path"], timeout=40) as response:
            content = response.read(MAX_ARTIFACT_BYTES + 1)
        digest = hashlib.sha256(content).hexdigest()
        if len(content) > MAX_ARTIFACT_BYTES or digest != item["sha256"]:
            raise ValueError("exact Maven artifact integrity required")
        (overlay / name).write_bytes(content)
        hashes[name] = digest
    return hashes


def validate_scan(directory: Path, image: str) -> dict:
    syft, cyclonedx, grype, database = (load(directory / name) for name in
                                         ("syft.json", "cyclonedx.json", "grype.json", "database.json"))
    require_complete_inventory(syft)
    require_unsuppressed_scan(grype)
    source = syft["source"]
    metadata = source["metadata"]
    grype_source = grype["source"]["target"]
    pin = pins()
    if (source["type"] != "image" or metadata["architecture"] != "amd64"
            or metadata["os"] != "linux" or metadata["userInput"] != image
            or not re.fullmatch(r"sha256:[a-f0-9]{64}", metadata["imageID"])
            or grype_source["userInput"] != image
            or grype_source["imageID"] != metadata["imageID"]
            or metadata["labels"].get("org.opencontainers.image.version") != "4.2.2"
            or metadata["labels"].get("org.opencontainers.image.source") != "https://github.com/hasanjodatshandi/HooshiX"
            or metadata["labels"].get("io.hooshix.provenance.kind") != "upstream-runtime-security-overlay"
            or metadata["labels"].get("io.hooshix.kafka.upstream.image") != pin["upstream_image"]
            or cyclonedx.get("bomFormat") != "CycloneDX" or not cyclonedx.get("components")
            or not isinstance(grype.get("matches"), list)):
        raise ValueError("exact Kafka candidate inventory required")
    packages: dict[str, set[str]] = {}
    for artifact in syft["artifacts"]:
        packages.setdefault(artifact["name"], set()).add(artifact["version"])
    for name, version in pin["apk_packages"].items():
        if packages.get(name) != {version}:
            raise ValueError("exact Alpine security package set required")
    for item in pin["maven_overrides"]:
        if packages.get(item["artifact"]) != {item["version"]}:
            raise ValueError("exact patched Maven dependency set required")
    severity = {name: 0 for name in ("Negligible", "Low", "Medium", "High", "Critical", "Unknown")}
    for match in grype["matches"]:
        level = match["vulnerability"]["severity"]
        if level not in severity:
            raise ValueError("scanner severity schema required")
        severity[level] += 1
    built = datetime.fromisoformat(database["built"].replace("Z", "+00:00"))
    if (not database.get("valid") or database.get("error") or built.tzinfo is None
            or not 0 <= (datetime.now(timezone.utc) - built).total_seconds() <= 432000
            or severity["High"] or severity["Critical"]):
        raise ValueError("fresh scanner database and zero High/Critical required")
    return {"scanner_image_id": metadata["imageID"], "scan": "Passed",
            "severity_counts": severity, "database_built_at": built.isoformat(),
            "inventory_sha256": {name: hashlib.sha256((directory / name).read_bytes()).hexdigest()
                                 for name in ("syft.json", "cyclonedx.json", "grype.json", "database.json")}}


def build(directory: Path) -> dict:
    pin = pins()
    endpoint = docker("context", "inspect", "--format", "{{json .Endpoints.docker.Host}}")
    if (endpoint.returncode != 0 or not os.environ.get("DOCKER_HOST", "unix://").startswith("unix://")
            or not isinstance((docker_host := json.loads(endpoint.stdout)), str)
            or not docker_host.startswith("unix://")):
        raise ValueError("local Unix Docker required")
    if directory.exists() or directory.is_symlink():
        raise ValueError("fresh candidate directory required")
    directory.mkdir(mode=0o700)
    context = directory / "context"
    context.mkdir(mode=0o700)
    recipe = ROOT / "infrastructure/production/kafka/Dockerfile"
    (context / "Dockerfile").write_bytes(recipe.read_bytes())
    jars = download_overrides(context, pin)
    image_id_file = directory / "image-id.txt"
    result = docker("build", "--platform", "linux/amd64", "--iidfile", str(image_id_file),
                    str(context), timeout=600, docker_host=docker_host)
    if result.returncode != 0:
        raise RuntimeError("KAFKA_CANDIDATE_BUILD_FAILED")
    image = image_id_file.read_text().strip()
    if not re.fullmatch(r"sha256:[a-f0-9]{64}", image):
        raise ValueError("immutable image configuration digest required")
    inspection = docker("image", "inspect", "--format", "{{json .Config}}", image,
                        timeout=20, docker_host=docker_host)
    if inspection.returncode != 0:
        raise ValueError("candidate image inspection required")
    config = json.loads(inspection.stdout)
    if (config.get("User") != "appuser" or config.get("Entrypoint") != ["/__cacert_entrypoint.sh"]
            or config.get("Cmd") != ["/etc/kafka/docker/run"]):
        raise ValueError("upstream non-root Kafka runtime contract required")
    version = docker("run", "--rm", "--network", "none", "--entrypoint",
                     "/opt/kafka/bin/kafka-topics.sh", image, "--version",
                     timeout=20, docker_host=docker_host)
    if version.returncode != 0 or version.stdout.strip() != "4.2.2":
        raise ValueError("exact Kafka runtime version required")
    scan_inventory(directory, image, "docker", docker_host=docker_host)
    scan = validate_scan(directory, image)
    native = exercise(image, docker_host)
    receipt = {"schema_version": 1, "component": "kafka", "version": pin["version"],
               "observed_at": datetime.now(timezone.utc).isoformat(), "local_image_id": image,
               "upstream_image": pin["upstream_image"], "apk_packages": pin["apk_packages"],
               "maven_overrides": jars, **scan, "native_smoke": native,
               "recipe_sha256": hashlib.sha256(recipe.read_bytes()).hexdigest(),
               "upstream_build_provenance": "Not verified", "publication": "Not run",
               "signature_provenance_sbom": "Not run", "staging": "Not run",
               "deployment": "Not run", "application_ingress": "CLOSED",
               "production_readiness": "Not verified"}
    (directory / "candidate.json").write_text(json.dumps(receipt, sort_keys=True, indent=2) + "\n")
    return receipt


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = build(args.directory)
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError, RuntimeError) as error:
        code = str(error) if isinstance(error, (ValueError, RuntimeError)) else type(error).__name__
        parser.exit(1, "KAFKA_CANDIDATE=Failed; reason=" + code + "; no publication or target writes\n")
    print(json.dumps(result, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
