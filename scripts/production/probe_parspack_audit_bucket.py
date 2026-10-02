#!/usr/bin/env python3
"""Read-only S3 compatibility probe; credential values never enter argv or output."""

import argparse
import getpass
import json
import os
import re
import stat
import subprocess
import sys
import xml.etree.ElementTree as ET
from urllib.parse import urlsplit


def validate_endpoint(value: str) -> str:
    parsed = urlsplit(value)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or not parsed.hostname.endswith(".parspack.net")
        or parsed.username is not None
        or parsed.password is not None
        or parsed.port is not None
        or parsed.path not in ("", "/")
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError("expected an HTTPS ParsPack endpoint without path or credentials")
    return f"https://{parsed.hostname}"


def validate_bucket(value: str) -> str:
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{2,62}", value):
        raise ValueError("expected a simple lowercase ParsPack bucket name")
    return value


def curl_quoted(value: str) -> str:
    if not value or any(ord(character) < 33 or ord(character) > 126 for character in value):
        raise ValueError("keys must be non-empty printable ASCII without spaces")
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _unique_fields(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate credential fields")
        result[key] = value
    return result


def load_private_credentials(path: str, endpoint: str) -> tuple[str, str]:
    """Read only a bounded owner-private regular file; never expose its values."""
    parent = os.stat(os.path.dirname(os.path.abspath(path)))
    if parent.st_uid != os.getuid() or stat.S_IMODE(parent.st_mode) != 0o700:
        raise ValueError("credential directory must be owner-only mode 0700")
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        metadata = os.fstat(fd)
        if (
            not stat.S_ISREG(metadata.st_mode)
            or metadata.st_uid != os.getuid()
            or stat.S_IMODE(metadata.st_mode) != 0o600
            or metadata.st_nlink != 1
            or metadata.st_size > 8192
        ):
            raise ValueError("credentials require an owner-only mode-0600 regular file")
        with os.fdopen(fd, "rb", closefd=False) as stream:
            raw = stream.read(8193)
        if len(raw) > 8192:
            raise ValueError("credential file exceeds size limit")
        try:
            data = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_fields)
        except (ValueError, UnicodeError, RecursionError):
            raise ValueError("invalid credential JSON") from None
    finally:
        os.close(fd)
    endpoint_fields = {"endpoint", "endpoint_url", "end_point_url"}
    if (
        not isinstance(data, dict)
        or set(data) - ({"access_key", "secret_key"} | endpoint_fields)
        or not {"access_key", "secret_key"}.issubset(data)
        or len(set(data) & endpoint_fields) > 1
    ):
        raise ValueError("invalid credential fields")
    for name in ("access_key", "secret_key"):
        if not isinstance(data[name], str):
            raise ValueError("invalid credential format")
        curl_quoted(data[name])
    if ":" in data["access_key"]:
        raise ValueError("invalid credential format")
    for name in endpoint_fields & set(data):
        value = data[name]
        if not isinstance(value, str):
            raise ValueError("invalid credential endpoint")
        value = value.strip()
        if "://" not in value:
            value = "https://" + value
        if validate_endpoint(value) != endpoint:
            raise ValueError("credential endpoint differs from approved CLI endpoint")
    return data["access_key"], data["secret_key"]


def xml_value(root: ET.Element, name: str) -> str | None:
    matches = [node for node in root.iter() if node.tag.rsplit("}", 1)[-1] == name]
    return matches[0].text if len(matches) == 1 else None


def probe(endpoint: str, bucket: str, access_key: str, secret_key: str, query: str) -> str:
    endpoint = validate_endpoint(endpoint)
    bucket = validate_bucket(bucket)
    if endpoint != f"https://{bucket}.parspack.net":
        raise ValueError("endpoint and bucket must match")
    if query not in ("object-lock", "versioning"):
        raise ValueError("unsupported read-only query")
    if ":" in access_key:
        raise ValueError("access key cannot contain a colon")
    curl_quoted(access_key)
    curl_quoted(secret_key)
    config = f"user = {curl_quoted(access_key + ':' + secret_key)}\n"
    command = [
        "curl", "-q", "--config", "-", "--aws-sigv4", "aws:amz:us-east-1:s3",
        "--silent", "--show-error", "--connect-timeout", "5", "--max-time", "15",
        "--max-filesize", "65536", "--write-out", "\nHTTP_STATUS:%{http_code}",
        "--url", f"{endpoint}/{bucket}?{query}",
    ]
    try:
        result = subprocess.run(
            command, input=config.encode("ascii"), stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, timeout=20, check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return "transport error"
    if result.returncode != 0:
        return f"transport error (curl exit {result.returncode})"
    body, marker, status = result.stdout.rpartition(b"\nHTTP_STATUS:")
    if not marker or not re.fullmatch(rb"[1-5][0-9]{2}", status):
        return "invalid HTTP response"
    if len(body) > 65536 or b"<!DOCTYPE" in body.upper() or b"<!ENTITY" in body.upper():
        return f"HTTP {status.decode('ascii')}; unsupported XML response"
    try:
        root = ET.fromstring(body)
    except ET.ParseError:
        return f"HTTP {status.decode('ascii')}; non-XML response"
    root_name = root.tag.rsplit("}", 1)[-1]
    if status != b"200":
        code = xml_value(root, "Code") or "unknown"
        if code not in {
            "AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch", "NoSuchBucket",
            "ObjectLockConfigurationNotFoundError", "InvalidRequest", "InvalidArgument",
            "RequestTimeTooSkewed", "PermanentRedirect", "AuthorizationHeaderMalformed",
            "SlowDown", "ServiceUnavailable", "InternalError", "NotImplemented", "MethodNotAllowed",
        }:
            code = "unknown"
        return f"HTTP {status.decode('ascii')}; {code}"
    if query == "object-lock":
        if root_name != "ObjectLockConfiguration":
            return "HTTP 200; unexpected XML response"
        enabled = xml_value(root, "ObjectLockEnabled")
        mode = xml_value(root, "Mode")
        if mode not in ("COMPLIANCE", "GOVERNANCE", None):
            mode = "unexpected response"
        days = xml_value(root, "Days")
        years = xml_value(root, "Years")
        if enabled != "Enabled":
            return "HTTP 200; Object Lock not enabled"
        retention = "none"
        if days and not years and re.fullmatch(r"[0-9]{1,5}", days) and 1 <= int(days) <= 36500:
            retention = f"{int(days)} days"
        elif years and not days and re.fullmatch(r"[0-9]{1,3}", years) and 1 <= int(years) <= 100:
            retention = f"{int(years)} years"
        return f"HTTP 200; Object Lock enabled; default retention {mode or 'none'} / {retention}"
    if root_name != "VersioningConfiguration":
        return "HTTP 200; unexpected XML response"
    versioning = xml_value(root, "Status") or "not configured"
    if versioning not in ("Enabled", "Suspended", "not configured"):
        versioning = "unexpected response"
    return f"HTTP 200; Versioning {versioning}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", required=True, help="HTTPS endpoint from ParsPack panel")
    parser.add_argument("--bucket", required=True, help="private audit bucket name")
    parser.add_argument(
        "--credentials-file", help="local private JSON input; not a production secret store",
    )
    parser.add_argument(
        "--require-compliance-days", type=int,
        help="exit nonzero unless COMPLIANCE retention matches and Versioning is Enabled",
    )
    args = parser.parse_args()
    try:
        endpoint = validate_endpoint(args.endpoint)
        bucket = validate_bucket(args.bucket)
    except ValueError as error:
        parser.error(str(error))
    if endpoint != f"https://{bucket}.parspack.net":
        parser.error("this probe requires the endpoint and bucket name to match")
    if args.require_compliance_days is not None and not 1 <= args.require_compliance_days <= 36500:
        parser.error("expected retention days between 1 and 36500")
    if not sys.platform.startswith("linux"):
        parser.error("run this probe from WSL/Linux, not Windows PowerShell")
    if not args.credentials_file and (not sys.stdin.isatty() or not sys.stderr.isatty()):
        parser.error("run this probe in your own interactive terminal")
    access_key = secret_key = ""
    try:
        if args.credentials_file:
            access_key, secret_key = load_private_credentials(args.credentials_file, endpoint)
        else:
            access_key = getpass.getpass("ParsPack Access Key (hidden): ")
            secret_key = getpass.getpass("ParsPack Secret Key (hidden): ")
        lock = probe(endpoint, bucket, access_key, secret_key, "object-lock")
        versioning = probe(endpoint, bucket, access_key, secret_key, "versioning")
        print("Object Lock:", lock)
        print("Versioning:", versioning)
    except (OSError, ValueError):
        print("Invalid private credential input; details suppressed", file=sys.stderr)
        return 2
    finally:
        access_key = secret_key = ""
    print("Read-only API probe only; deletion/overwrite denial and audit readiness remain unverified.")
    if args.require_compliance_days is not None:
        expected_lock = (
            "HTTP 200; Object Lock enabled; default retention COMPLIANCE / "
            f"{args.require_compliance_days} days"
        )
        passed = lock == expected_lock and versioning == "HTTP 200; Versioning Enabled"
        print("READ_ONLY_CONFIGURATION=" + ("Passed" if passed else "Not verified"))
        return 0 if passed else 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
