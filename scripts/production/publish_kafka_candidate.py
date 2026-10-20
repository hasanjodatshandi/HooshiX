"""Publish one reviewed Kafka security candidate on protected CI; never deploy."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from build_kafka_candidate import build, pins, validate_scan
from build_patched_platform import published_image
from build_postgresql_candidates import local_endpoint
from publish_openbao_candidate import context, run
from publish_postgresql_candidates import sign_and_verify
from verify_openbao_artifact import load
from verify_postgresql_artifacts import scan_inventory
from verify_release import EXPECTED_CERTIFICATE_IDENTITY

REPOSITORY = "ghcr.io/hasanjodatshandi/hooshix/platform-kafka-private"
PACKAGE_API = "users/hasanjodatshandi/packages/container/hooshix%2Fplatform-kafka-private"
BUILD_TYPE = "https://github.com/hasanjodatshandi/HooshiX/kafka-runtime-security-build/v1"


def package_visibility(*, allow_missing: bool = False) -> str | None:
    try:
        visibility = run(["gh", "api", PACKAGE_API, "--jq", ".visibility"], timeout=20).strip()
    except subprocess.CalledProcessError as error:
        if allow_missing and "(HTTP 404)" in (error.stderr or ""):
            return None
        raise
    if visibility != "private":
        print("KAFKA_PACKAGE_VISIBILITY=Rejected; required=private", flush=True)
        raise ValueError("private Kafka package required")
    return visibility


def source_predicate(pin: dict, revision: str, invocation: str) -> dict:
    dependencies = [{"uri": pin["upstream_image"],
                     "digest": {"sha256": pin["upstream_image"].split("sha256:")[1]}}]
    dependencies.extend({"uri": "https://repo.maven.apache.org/maven2/" + item["path"],
                         "digest": {"sha256": item["sha256"]}}
                        for item in pin["maven_overrides"])
    dependencies.append({"uri": "git+https://github.com/hasanjodatshandi/HooshiX.git",
                         "digest": {"gitCommit": revision}})
    recipe = Path(__file__).resolve().parents[2] / "infrastructure/production/kafka/candidate.json"
    dockerfile = recipe.parent / "Dockerfile"
    return {"buildDefinition": {"buildType": BUILD_TYPE,
        "externalParameters": {"component": "kafka", "operation": "upstream-runtime-security-overlay",
            "recipe": pin, "recipe_sha256": hashlib.sha256(recipe.read_bytes()).hexdigest(),
            "dockerfile_sha256": hashlib.sha256(dockerfile.read_bytes()).hexdigest(),
            "platform": "linux/amd64", "repository_revision": revision},
        "internalParameters": {}, "resolvedDependencies": dependencies},
        "runDetails": {"builder": {"id": EXPECTED_CERTIFICATE_IDENTITY},
                       "metadata": {"invocationId": invocation}}}


def publish(directory: Path, env: dict[str, str]) -> dict:
    revision, invocation = context(env)
    if (env.get("RELEASE_KIND") != "kafka-candidate"
            or run(["git", "rev-parse", "HEAD"]).strip() != revision):
        raise ValueError("exact protected Kafka dispatch required")
    endpoint = local_endpoint()
    pin = pins()
    directory.mkdir(mode=0o700)
    package_visibility(allow_missing=True)
    print("KAFKA_PUBLICATION_STEP=build-scan-native", flush=True)
    built = build(directory / "candidate")
    print("KAFKA_PUBLICATION_STEP=push-registry-scan", flush=True)
    package_visibility(allow_missing=True)
    target = REPOSITORY + ":candidate-" + revision + "-" + invocation.replace(":", "-")
    run(["docker", "--host", endpoint, "tag", built["local_image_id"], target])
    run(["docker", "--host", endpoint, "push", target], timeout=300)
    package_visibility()
    folder = directory / "registry"
    folder.mkdir(mode=0o700)
    (folder / "scanner-config.json").write_text("{}\n")
    run(["syft", "scan", target, "--from", "registry", "--platform", "linux/amd64",
         "--config", str(folder / "scanner-config.json"),
         "-o", "syft-json=" + str(folder / "registry-resolution.json")], timeout=300)
    image = published_image(REPOSITORY, load(folder / "registry-resolution.json")["source"],
                            built["scanner_image_id"])
    scan_inventory(folder, image, "registry")
    if published_image(REPOSITORY, load(folder / "syft.json")["source"],
                       built["scanner_image_id"]) != image:
        raise ValueError("registry manifest/configuration changed")
    checked = validate_scan(folder, image)
    (folder / "receipt.json").write_text(json.dumps(checked, sort_keys=True, indent=2) + "\n")
    print("KAFKA_PUBLICATION_STEP=sign-verify", flush=True)
    sign_and_verify(folder, image, source_predicate(pin, revision, invocation))
    result = {"schema_version": 1, "component": "kafka", "version": pin["version"],
        "repository_revision": revision, "invocation_id": invocation, "image": image,
        "image_configuration_digest": built["scanner_image_id"],
        "upstream_image": pin["upstream_image"], "registry_visibility": package_visibility(),
        "publication": "Passed", "scan": "Passed", "signature_provenance_sbom": "Passed",
        "native_smoke": built["native_smoke"], "staging": "Not run", "deployment": "Not run",
        "application_ingress": "CLOSED", "production_readiness": "Not verified"}
    (directory / "publication.json").write_text(json.dumps(result, sort_keys=True, indent=2) + "\n")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path)
    args = parser.parse_args()
    try:
        publish(args.directory, dict(os.environ))
    except (ValueError, KeyError, TypeError, OSError, RuntimeError, subprocess.SubprocessError):
        parser.exit(1, "KAFKA_PUBLICATION=Failed; state retained; no deployment or automatic replay\n")
    print("KAFKA_PUBLICATION=Passed; private signed candidate only; production Not verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
