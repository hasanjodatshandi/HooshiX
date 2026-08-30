#!/usr/bin/env python3
"""Validate public production release metadata without reading secret values."""
from __future__ import annotations

import argparse
import datetime as dt
import ipaddress
import json
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[2]
SERVICES = (
    "authorization-service",
    "compromised-password-service",
    "identity-service",
    "notification-service",
    "web-bff",
)
RELEASE_COMPONENTS = SERVICES + ("web-frontend",)
TOP_LEVEL = {
    "schema_version",
    "profile",
    "git_revision",
    "public_hostname",
    "images",
    "cosign",
    "external_l4_source_cidrs",
    "capacity_evidence",
    "service_capacity",
    "compromised_password_dataset",
    "external_evidence",
    "secret_refs",
}
EXTERNAL_EVIDENCE = {
    "external_blackbox_monitor",
    "offhost_security_audit_sink",
    "offsite_postgresql_backup_target",
    "openbao_unseal_custody",
    "upstream_ddos_provider",
    "wireguard_peer_inventory",
    "cold_dr_exercise",
    "notification_provider_delivery",
}
SECRET_REFS = {
    "production_tls",
    "postgresql_backup",
    "openbao",
    "redis_tls",
    "kafka_tls",
    "notification_providers",
}
PLACEHOLDER = re.compile(r"(?i)(^|[^a-z])(tbd|todo|unknown|placeholder|example|not verified|none)([^a-z]|$)")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
IMAGE = re.compile(r"^[a-z0-9][a-z0-9._:-]*(?:/[a-z0-9][a-z0-9._-]*)+@sha256:[0-9a-f]{64}$")
DNS_NAME = re.compile(r"^[a-z0-9](?:[-a-z0-9.]*[a-z0-9])?$")
EXPECTED_CERTIFICATE_IDENTITY = "https://github.com/hasanjodatshandi/HooshiX/.github/workflows/production-release.yml@refs/heads/main"
EXPECTED_OIDC_ISSUER = "https://token.actions.githubusercontent.com"
HOST = re.compile(r"^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])$")


def _need(errors: list[str], condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)


def _non_placeholder(value: object) -> bool:
    return isinstance(value, str) and 3 <= len(value) <= 1024 and not PLACEHOLDER.search(value)


def _positive_ints(errors: list[str], data: object, keys: tuple[str, ...], prefix: str) -> None:
    if not isinstance(data, dict):
        errors.append(f"{prefix} must be an object")
        return
    _need(errors, set(data) == set(keys), f"{prefix} keys are invalid")
    for key in keys:
        value = data.get(key)
        _need(errors, isinstance(value, int) and not isinstance(value, bool) and value > 0,
              f"{prefix}.{key} must be a measured positive integer")


def _parse_time(value: object) -> dt.datetime | None:
    if not isinstance(value, str):
        return None
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.astimezone(dt.timezone.utc)


def validate_manifest(data: object, now: dt.datetime | None = None) -> list[str]:
    errors: list[str] = []
    if not isinstance(data, dict):
        return ["release manifest must be a JSON object"]
    _need(errors, set(data) == TOP_LEVEL, "release manifest top-level keys are invalid")
    _need(errors, data.get("schema_version") == 1, "schema_version must be 1")
    _need(errors, data.get("profile") == "production-single-server",
          "profile must be production-single-server")
    revision = data.get("git_revision")
    _need(errors, isinstance(revision, str) and bool(re.fullmatch(r"[0-9a-f]{40}", revision)),
          "git_revision must be an exact lowercase 40-hex commit")

    hostname = data.get("public_hostname")
    _need(errors, isinstance(hostname, str) and hostname == hostname.lower()
          and "*" not in hostname and hostname != "localhost" and bool(HOST.fullmatch(hostname)),
          "public_hostname must be an exact lowercase DNS hostname")

    images = data.get("images")
    if not isinstance(images, dict):
        errors.append("images must be an object")
    else:
        _need(errors, set(images) == set(RELEASE_COMPONENTS),
              "images must contain exactly the six application release components")
        for component in RELEASE_COMPONENTS:
            image = images.get(component)
            valid = isinstance(image, str) and bool(IMAGE.fullmatch(image))
            if valid:
                repository = image.rsplit("@sha256:", 1)[0]
                final_segment = repository.rsplit("/", 1)[-1]
                valid = "/" in repository and "*" not in repository and ":" not in final_segment
            _need(errors, valid, f"images.{component} must be an untagged immutable repository@sha256 digest")

    cosign = data.get("cosign")
    if not isinstance(cosign, dict):
        errors.append("cosign must be an object")
    else:
        _need(errors, set(cosign) == {"certificate_identity", "certificate_oidc_issuer"},
              "cosign keys are invalid")
        identity = cosign.get("certificate_identity")
        issuer = cosign.get("certificate_oidc_issuer")
        identity_url = urlparse(identity) if isinstance(identity, str) else None
        issuer_url = urlparse(issuer) if isinstance(issuer, str) else None
        _need(errors, bool(_non_placeholder(identity) and "*" not in identity and identity_url
              and identity_url.scheme == "https" and identity_url.netloc and not identity_url.username
              and not identity_url.password and not identity_url.query and not identity_url.fragment),
              "cosign.certificate_identity must be an exact non-placeholder HTTPS identity")
        _need(errors, identity == EXPECTED_CERTIFICATE_IDENTITY, "cosign.certificate_identity must be the protected HooshiX production-release workflow on main")
        _need(errors, bool(issuer_url and issuer_url.scheme == "https" and issuer_url.netloc and "*" not in issuer
              and not issuer_url.username and not issuer_url.password and not issuer_url.query and not issuer_url.fragment),
              "cosign.certificate_oidc_issuer must be an exact HTTPS issuer")
        _need(errors, issuer == EXPECTED_OIDC_ISSUER, "cosign.certificate_oidc_issuer must be GitHub Actions OIDC")

    cidrs = data.get("external_l4_source_cidrs")
    if not isinstance(cidrs, list) or not cidrs:
        errors.append("external_l4_source_cidrs must be a non-empty array")
    else:
        _need(errors, len(set(cidrs)) == len(cidrs), "external_l4_source_cidrs must be unique")
        for value in cidrs:
            try:
                network = ipaddress.ip_network(value, strict=True)
                valid = network.prefixlen > 0
            except (TypeError, ValueError):
                valid = False
            _need(errors, valid, f"external_l4_source_cidrs contains invalid or default-route CIDR: {value!r}")

    capacity = data.get("capacity_evidence")
    if not isinstance(capacity, dict):
        errors.append("capacity_evidence must be an object")
    else:
        _need(errors, set(capacity) == {"reference", "passed", "cpu_headroom_percent", "memory_headroom_percent"},
              "capacity_evidence keys are invalid")
        _need(errors, _non_placeholder(capacity.get("reference")),
              "capacity_evidence.reference must be real evidence")
        _need(errors, capacity.get("passed") is True, "capacity_evidence.passed must be true")
        for key in ("cpu_headroom_percent", "memory_headroom_percent"):
            value = capacity.get(key)
            _need(errors, isinstance(value, (int, float)) and not isinstance(value, bool) and value >= 30,
                  f"capacity_evidence.{key} must be at least 30")

    service_capacity = data.get("service_capacity")
    if not isinstance(service_capacity, dict) or set(service_capacity) != {"authorization", "identity"}:
        errors.append("service_capacity must contain exactly authorization and identity")
    else:
        _positive_ints(errors, service_capacity["authorization"], (
            "global_concurrency", "per_caller_concurrency", "global_queue_capacity",
            "per_caller_queue_capacity", "max_caller_buckets", "quota_max_active_buckets",
            "quota_max_new_buckets_per_minute"), "service_capacity.authorization")
        _positive_ints(errors, service_capacity["identity"], (
            "argon2_max_concurrent_hashes", "compromised_password_max_in_flight",
            "quota_max_active_buckets", "quota_max_new_buckets_per_minute"),
            "service_capacity.identity")

    dataset = data.get("compromised_password_dataset")
    if not isinstance(dataset, dict):
        errors.append("compromised_password_dataset must be an object")
    else:
        expected = {"source_kind", "artifact_sha256", "manifest_sha256", "retrieval_completed_at",
                    "max_prefix_cardinality", "max_serialized_response_bytes"}
        _need(errors, set(dataset) == expected, "compromised_password_dataset keys are invalid")
        _need(errors, dataset.get("source_kind") == "HIBP_PWNED_PASSWORDS_SHA1",
              "compromised password source must be the approved HIBP SHA-1 corpus")
        for key in ("artifact_sha256", "manifest_sha256"):
            _need(errors, isinstance(dataset.get(key), str) and bool(HEX64.fullmatch(dataset[key])),
                  f"compromised_password_dataset.{key} must be lowercase SHA-256")
        for key in ("max_prefix_cardinality", "max_serialized_response_bytes"):
            value = dataset.get(key)
            _need(errors, isinstance(value, int) and not isinstance(value, bool) and value > 0,
                  f"compromised_password_dataset.{key} must be positive complete-corpus evidence")
        retrieved = _parse_time(dataset.get("retrieval_completed_at"))
        _need(errors, retrieved is not None, "compromised_password_dataset.retrieval_completed_at is invalid")
        if retrieved is not None:
            clock = now or dt.datetime.now(dt.timezone.utc)
            if clock.tzinfo is None:
                clock = clock.replace(tzinfo=dt.timezone.utc)
            age = clock.astimezone(dt.timezone.utc) - retrieved
            _need(errors, dt.timedelta(0) <= age <= dt.timedelta(days=35),
                  "compromised password dataset must be current and no older than 35 days")

    evidence = data.get("external_evidence")
    if not isinstance(evidence, dict):
        errors.append("external_evidence must be an object")
    else:
        _need(errors, set(evidence) == EXTERNAL_EVIDENCE, "external_evidence keys are invalid")
        for key in EXTERNAL_EVIDENCE:
            _need(errors, _non_placeholder(evidence.get(key)), f"external_evidence.{key} must be real evidence")

    secret_refs = data.get("secret_refs")
    if not isinstance(secret_refs, dict):
        errors.append("secret_refs must be an object")
    else:
        _need(errors, set(secret_refs) == SECRET_REFS, "secret_refs keys are invalid")
        for key in SECRET_REFS:
            value = secret_refs.get(key)
            _need(errors, isinstance(value, str) and len(value) <= 253 and bool(DNS_NAME.fullmatch(value)),
                  f"secret_refs.{key} must be a Kubernetes secret/reference name only")
    return errors


def validate_git_revision(revision: str) -> list[str]:
    errors: list[str] = []
    try:
        subprocess.run(["git", "-C", str(ROOT), "cat-file", "-e", f"{revision}^{{commit}}"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except subprocess.CalledProcessError:
        return ["git_revision does not resolve to a commit in the current repository"]
    candidates = ("origin/main", "main")
    reachable = False
    for candidate in candidates:
        result = subprocess.run(["git", "-C", str(ROOT), "merge-base", "--is-ancestor", revision, candidate],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if result.returncode == 0:
            reachable = True
            break
    if not reachable:
        errors.append("git_revision must be reachable from current main/origin/main")
    return errors


def load_manifest(path: Path) -> object:
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--skip-git-reachability", action="store_true")
    args = parser.parse_args()
    try:
        data = load_manifest(args.manifest)
    except (OSError, json.JSONDecodeError) as exc:
        print(f"Production release verification FAILED: {exc}", file=sys.stderr)
        return 1
    errors = validate_manifest(data)
    if not errors and not args.skip_git_reachability:
        errors.extend(validate_git_revision(data["git_revision"]))
    if errors:
        print("Production release verification FAILED:")
        for error in errors:
            print(f"- {error}")
        return 1
    print("Production release verification PASSED.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
