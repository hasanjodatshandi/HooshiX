#!/usr/bin/env python3
"""Publish/sign one reviewed upstream import on protected CI; never deploy it."""
from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

import verify_openbao_artifact as artifact
from verify_release import EXPECTED_CERTIFICATE_IDENTITY, EXPECTED_OIDC_ISSUER
from repair_import_referrers import repair_owned_import

REPOSITORY = "ghcr.io/hasanjodatshandi/hooshix/platform-openbao-private"
PACKAGE_API = "users/hasanjodatshandi/packages/container/hooshix%2Fplatform-openbao-private"
BUILD_TYPE = "https://github.com/hasanjodatshandi/HooshiX/openbao-upstream-import/v1"


def run(argv: list[str], timeout: int = 180) -> str:
    # No raw child output/errors: registry authentication and OIDC stay out of logs.
    try:
        return subprocess.run(argv, check=True, capture_output=True, text=True,
                              timeout=timeout).stdout
    except subprocess.CalledProcessError as error:
        reason = "tool-error"
        for needle, category in (("specified reference is not a multiarch image", "single-manifest-platform-selection"),
                                 ("UNAUTHORIZED", "registry-unauthorized"), ("DENIED", "registry-denied"),
                                 ("fallback tag manifest is not an OCI image index", "referrer-alias-conflict"),
                                 ("already exists. Use `-f`", "destination-conflict")):
            if needle in (error.stderr or ""):
                reason = category
                break
        print("OPENBAO_TOOL_FAILURE=" + reason, file=sys.stderr)
        raise  # Never print raw stderr, argv, tokens, response bodies or URLs.


def stage(name: str) -> None:
    print("OPENBAO_PUBLICATION_STEP=" + name, flush=True)


def context(env: dict[str, str]) -> tuple[str, str]:
    if (env.get("GITHUB_ACTIONS") != "true" or env.get("GITHUB_REF") != "refs/heads/main"
            or env.get("GITHUB_REPOSITORY") != "hasanjodatshandi/HooshiX"
            or env.get("GITHUB_EVENT_NAME") != "workflow_dispatch"
            or env.get("GITHUB_WORKFLOW_REF") != EXPECTED_CERTIFICATE_IDENTITY.removeprefix("https://github.com/")):
        raise ValueError("protected main dispatch required")
    revision = env.get("GITHUB_SHA", "")
    run_id, attempt = env.get("GITHUB_RUN_ID", ""), env.get("GITHUB_RUN_ATTEMPT", "")
    if not re.fullmatch(r"[a-f0-9]{40}", revision) or not all(
            re.fullmatch(r"[1-9][0-9]*", value) for value in (run_id, attempt)):
        raise ValueError("exact CI revision/invocation required")
    return revision, f"github:{run_id}:{attempt}"


def provenance(pin: dict, revision: str, invocation: str, image: str) -> dict:
    # Import provenance, explicitly NOT a claim that HooshiX built the upstream binary.
    return {"buildDefinition": {
        "buildType": BUILD_TYPE,
        "externalParameters": {"gitRevision": revision, "image": image,
                               "upstreamImage": pin["image"], "upstreamRevision": pin["source_revision"],
                               "platform": pin["platform"], "operation": "unchanged-upstream-import"},
        "internalParameters": {},
        "resolvedDependencies": [
            {"uri": pin["image"], "digest": {"sha256": pin["image"].split("sha256:")[1]}},
            {"uri": "git+https://github.com/hasanjodatshandi/HooshiX.git",
             "digest": {"gitCommit": revision}}]},
        "runDetails": {"builder": {"id": EXPECTED_CERTIFICATE_IDENTITY},
                       "metadata": {"invocationId": invocation}}}


def verify_payload(output: str, predicate_type: str, predicate: dict, digest: str) -> None:
    # Verify-attestation validates signature/identity; also bind the actual decoded payload.
    for line in output.splitlines():
        envelope = json.loads(line)
        statement = json.loads(base64.b64decode(envelope["payload"], validate=True))
        if (statement.get("_type") == "https://in-toto.io/Statement/v0.1"
                and statement.get("predicateType") == predicate_type
                and statement.get("predicate") == predicate
                and any(subject.get("digest") == {"sha256": digest}
                        for subject in statement.get("subject", []))):
            return
    raise ValueError("expected exact signed predicate/digest missing")


def publish(directory: Path, env: dict[str, str]) -> dict:
    revision, invocation = context(env)
    pin = artifact.load(artifact.ROOT / "infrastructure/production/secrets/openbao-image.json")
    if (not re.fullmatch(r"ghcr\.io/openbao/openbao-distroless@sha256:[a-f0-9]{64}", pin["image"])
            or pin["platform"] != "linux/amd64"
            or pin["production_promotion"] != "blocked-until-supply-chain-staging-and-recovery-evidence"):
        raise ValueError("pinned blocked candidate required")
    digest = pin["image"].split("@sha256:")[1]
    image = REPOSITORY + "@sha256:" + digest
    tag = REPOSITORY + ":candidate-" + digest
    directory.mkdir(mode=0o700)  # Fresh per-run evidence; no stale success receipt reuse.
    (directory / "attempt.json").write_text(json.dumps({
        "schema_version": 1, "repository_revision": revision,
        "upstream_image": pin["image"], "publication": "Not verified",
        "purpose": "attempt-only-not-success-receipt"}, sort_keys=True) + "\n")
    versions = {name: run([name, "version"]) for name in ("syft", "grype", "cosign")}
    for name, version in (("syft", "1.51.0"), ("grype", "0.117.0"), ("cosign", "v3.0.6")):
        if not re.search(r"(?<![\w.])" + re.escape(version) + r"(?![\w.])", versions[name]):
            raise ValueError("pinned release tools required")
    stage("copy")
    # The pin is already the single linux/amd64 manifest, not its multiarch index.
    # Cosign 3.0.6 --platform only accepts indexes; destination scan verifies architecture.
    run(["cosign", "copy", "--attachment-tag-prefix", "import-", pin["image"], tag], timeout=300)
    stage("private-package")
    if run(["gh", "api", PACKAGE_API, "--jq", ".visibility"], timeout=20).strip() != "private":
        raise ValueError("owned package must be private before signing")
    stage("scan")
    run(["grype", "db", "update"], timeout=180)
    (directory / "database.json").write_text(run(["grype", "db", "status", "-o", "json"]))
    run(["syft", "scan", image, "--from", "registry", "--platform", "linux/amd64",
         "-o", "syft-json=" + str(directory / "syft.json"),
         "-o", "cyclonedx-json=" + str(directory / "cyclonedx.json")], timeout=300)
    (directory / "grype.json").write_text(run(
        ["grype", "sbom:" + str(directory / "syft.json"), "--fail-on", "high", "-o", "json"]))
    receipt = artifact.validate(directory, revision, datetime.now(timezone.utc), image=image)
    predicate = provenance(pin, revision, invocation, image)
    path = directory / "import-provenance.json"
    path.write_text(json.dumps(predicate, sort_keys=True) + "\n")
    stage("sign-attest")
    print("OPENBAO_REFERRER_ALIAS=" + repair_owned_import(image), flush=True)
    run(["cosign", "sign", "--yes", image])
    for kind, filename in (("slsaprovenance1", path), ("cyclonedx", directory / "cyclonedx.json")):
        run(["cosign", "attest", "--yes", "--predicate", str(filename), "--type", kind, image])
    flags = ["--certificate-identity", EXPECTED_CERTIFICATE_IDENTITY,
             "--certificate-oidc-issuer", EXPECTED_OIDC_ISSUER]
    stage("verify-payload")
    run(["cosign", "verify", *flags, image])
    for kind, uri, expected in (("slsaprovenance1", "https://slsa.dev/provenance/v1", predicate),
                                ("cyclonedx", "https://cyclonedx.org/bom", artifact.load(directory / "cyclonedx.json"))):
        output = run(["cosign", "verify-attestation", "--type", kind, *flags, image])
        verify_payload(output, uri, expected, digest)
    stage("wrong-signer")
    try:
        run(["cosign", "verify", "--certificate-identity", EXPECTED_CERTIFICATE_IDENTITY + ".wrong",
             "--certificate-oidc-issuer", EXPECTED_OIDC_ISSUER, image])
    except subprocess.CalledProcessError:
        pass
    else:
        raise ValueError("wrong signer unexpectedly accepted")
    run(["cosign", "verify", *flags, image])  # Positive control after negative check.
    receipt.update({"registry_visibility": "private", "publication": "Passed",
                    "signature_provenance": "Passed", "wrong_signer": "Passed",
                    "provenance_kind": "unchanged-upstream-import",
                    "upstream_build_provenance": "Not verified", "upstream_image": pin["image"],
                    "tools": {"syft": "1.51.0", "grype": "0.117.0", "cosign": "3.0.6"},
                    "signer": EXPECTED_CERTIFICATE_IDENTITY, "issuer": EXPECTED_OIDC_ISSUER,
                    "staging": "Not verified", "runtime_admission": "Not verified",
                    "deployment": "Not verified"})
    receipt["sha256"][path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
    return receipt


def main() -> int:
    try:
        revision, _ = context(dict(os.environ))
        if run(["git", "rev-parse", "HEAD"]).strip() != revision:
            raise ValueError("checkout revision mismatch")
        directory = Path(os.environ["RUNNER_TEMP"]) / "openbao-release-evidence"
        receipt = publish(directory, dict(os.environ))
        (directory / "receipt.json").write_text(json.dumps(receipt, sort_keys=True, indent=2) + "\n")
    except (ValueError, KeyError, OSError, subprocess.SubprocessError):
        print("OPENBAO_PUBLICATION=Failed; no successful publication receipt", file=sys.stderr)
        return 1
    print("OPENBAO_PUBLICATION=Passed; candidate only; deployment Not verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
