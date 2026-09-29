#!/usr/bin/env python3
"""Read-only S3 compatibility probe; credentials exist only in process memory."""

import argparse
import getpass
import re
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


def curl_quoted(value: str) -> str:
    if not value or any(ord(character) < 33 or ord(character) > 126 for character in value):
        raise ValueError("keys must be non-empty printable ASCII without spaces")
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def xml_value(root: ET.Element, name: str) -> str | None:
    for node in root.iter():
        if node.tag.rsplit("}", 1)[-1] == name:
            return node.text
    return None


def probe(endpoint: str, access_key: str, secret_key: str, query: str) -> str:
    if ":" in access_key:
        raise ValueError("access key cannot contain a colon")
    config = f"user = {curl_quoted(access_key + ':' + secret_key)}\n"
    command = [
        "curl", "-q", "--config", "-", "--aws-sigv4", "aws:amz:us-east-1:s3",
        "--silent", "--show-error", "--connect-timeout", "5", "--max-time", "15",
        "--max-filesize", "65536", "--write-out", "\nHTTP_STATUS:%{http_code}",
        "--url", f"{endpoint}/?{query}",
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
    try:
        root = ET.fromstring(body)
    except ET.ParseError:
        return f"HTTP {status.decode('ascii')}; non-XML response"
    if status != b"200":
        code = xml_value(root, "Code") or "unknown"
        if not re.fullmatch(r"[A-Za-z0-9]{1,80}", code):
            code = "unknown"
        return f"HTTP {status.decode('ascii')}; {code}"
    if query == "object-lock":
        enabled = xml_value(root, "ObjectLockEnabled")
        mode = xml_value(root, "Mode")
        days = xml_value(root, "Days")
        years = xml_value(root, "Years")
        if enabled != "Enabled":
            return "HTTP 200; Object Lock not enabled"
        retention = f"{days} days" if days and days.isdecimal() else None
        retention = retention or (f"{years} years" if years and years.isdecimal() else "none")
        return f"HTTP 200; Object Lock enabled; default retention {mode or 'none'} / {retention}"
    versioning = xml_value(root, "Status") or "not configured"
    if versioning not in ("Enabled", "Suspended", "not configured"):
        versioning = "unexpected response"
    return f"HTTP 200; Versioning {versioning}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", required=True, help="HTTPS endpoint from ParsPack panel")
    args = parser.parse_args()
    try:
        endpoint = validate_endpoint(args.endpoint)
    except ValueError as error:
        parser.error(str(error))
    if not sys.stdin.isatty() or not sys.stderr.isatty():
        parser.error("run this probe in your own interactive terminal")
    access_key = getpass.getpass("ParsPack Access Key (hidden): ")
    secret_key = getpass.getpass("ParsPack Secret Key (hidden): ")
    try:
        print("Object Lock:", probe(endpoint, access_key, secret_key, "object-lock"))
        print("Versioning:", probe(endpoint, access_key, secret_key, "versioning"))
    except ValueError as error:
        print(f"Invalid credential format: {error}", file=sys.stderr)
        return 2
    finally:
        access_key = secret_key = ""
    print("Read-only API probe only; deletion/overwrite denial and audit readiness remain unverified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
