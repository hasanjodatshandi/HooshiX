"""Bounded version-specific JIT audit delivery, not an installed audit adapter.

The protected integration must supply OpenBao-materialized credentials and check
OS audit coverage/export health before calling this transport. This module does
not read the owner's bootstrap file, configure/probe a bucket, or grant access.
"""
from __future__ import annotations

import base64
import hashlib
import os
import re
import resource
import signal
import subprocess
import tempfile
import time
import uuid
from urllib.parse import quote

ENDPOINT = "https://c892683.parspack.net/c892683"
ENV = {"PATH": "/usr/bin:/bin", "LC_ALL": "C"}
MAX_PAYLOAD = 8192
MAX_RESPONSE = 32768
VERSION = re.compile(rb"[A-Za-z0-9_.+/=-]{1,256}")


class DeliveryDenied(ValueError):
    """Fixed public diagnostics only; never provider bodies or credential values."""


def _quoted(value: str) -> str:
    if (not isinstance(value, str) or not 1 <= len(value) <= 4096
            or any(ord(c) < 33 or ord(c) > 126 for c in value)):
        raise DeliveryDenied("invalid audit credentials")
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def parse_response(raw: bytes) -> tuple[bytes, bytes]:
    """Accept exactly one successful HTTP/1.1 response and unambiguous version."""
    if not isinstance(raw, bytes) or len(raw) > MAX_RESPONSE:
        raise DeliveryDenied("audit response exceeds bounds")
    headers, separator, body = raw.partition(b"\r\n\r\n")
    if not separator or len(headers) > 8192 or len(body) > MAX_PAYLOAD:
        raise DeliveryDenied("invalid audit HTTP response")
    lines = headers.split(b"\r\n")
    if not re.fullmatch(rb"HTTP/1\.1 200(?: [\x20-\x7e]*)?", lines[0]) or len(lines) > 65:
        raise DeliveryDenied("audit HTTP response not successful")
    versions = []
    for line in lines[1:]:
        key, colon, value = line.partition(b":")
        if (not colon or not re.fullmatch(rb"[!#$%&'*+.^_`|~0-9A-Za-z-]+", key)
                or any(c < 32 or c > 126 for c in value)):
            raise DeliveryDenied("invalid audit HTTP headers")
        if key.lower() == b"x-amz-version-id":
            versions.append(value.strip())
    if (len(versions) != 1 or not VERSION.fullmatch(versions[0])
            or versions[0] == b"null"):
        raise DeliveryDenied("audit object version missing or ambiguous")
    return versions[0], body


def _output_limit() -> None:
    resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_RESPONSE, MAX_RESPONSE))


def _transfer(config: bytes, payload_fd: int, deadline: float) -> bytes:
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise DeliveryDenied("audit delivery deadline exceeded")
    # -q must be first: no operator curlrc. No proxy, redirects, retries, raw
    # diagnostics, credentials in argv/environment or caller-selected URL.
    argv = ["/usr/bin/curl", "-q", "--config", "-", "--aws-sigv4", "aws:amz:us-east-1:s3",
            "--http1.1", "--proto", "=https", "--proxy", "", "--noproxy", "*",
            "--silent", "--include", "--connect-timeout", "2", "--max-time",
            str(min(3.0, remaining)), "--max-filesize", str(MAX_PAYLOAD), "--retry", "0"]
    with tempfile.TemporaryFile() as output:
        process = None
        try:
            process = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=output,
                                       stderr=subprocess.DEVNULL, env=ENV,
                                       pass_fds=(payload_fd,), start_new_session=True,
                                       preexec_fn=_output_limit)
            process.communicate(input=config, timeout=remaining)
        except (OSError, subprocess.TimeoutExpired):
            if process is not None:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.communicate(timeout=1)
            raise DeliveryDenied("audit transport unavailable; do not replay") from None
        if process.returncode != 0:
            raise DeliveryDenied("audit transport failed; do not replay")
        output.seek(0)
        raw = output.read(MAX_RESPONSE + 1)
    if time.monotonic() >= deadline:
        raise DeliveryDenied("audit delivery deadline exceeded")
    return raw


def deliver(payload: bytes, access_key: str, secret_key: str) -> dict[str, str]:
    """One PUT and one version-specific GET; acknowledge exact bytes only.

    No retry/delete/overwrite/configuration call. An ambiguous PUT is a denial,
    not permission to replay an operation. The caller owns durable reconciliation.
    """
    if not isinstance(payload, bytes) or not 1 <= len(payload) <= MAX_PAYLOAD:
        raise DeliveryDenied("invalid audit payload size")
    _quoted(access_key)
    _quoted(secret_key)
    if ":" in access_key:
        raise DeliveryDenied("invalid audit credentials")
    auth = "user = " + _quoted(access_key + ":" + secret_key) + "\n"
    digest = hashlib.sha256(payload).hexdigest()
    checksum = base64.b64encode(hashlib.sha256(payload).digest()).decode("ascii")
    url = f"{ENDPOINT}/hooshix-audit/jit-v1/{uuid.uuid4()}.json"
    deadline = time.monotonic() + 7
    # Unlinked, mode-0600 payload file, shared by fd only with the curl child.
    with tempfile.TemporaryFile() as source:
        source.write(payload)
        source.flush()
        source.seek(0)
        config = (auth + f'url = "{url}"\nrequest = "PUT"\n'
                  + f'data-binary = "@/proc/self/fd/{source.fileno()}"\n'
                  + 'header = "Content-Type: application/json"\nheader = "Expect:"\n'
                  + 'header = "If-None-Match: *"\n'
                  + 'header = "x-amz-sdk-checksum-algorithm: SHA256"\n'
                  + f'header = "x-amz-checksum-sha256: {checksum}"\n'
                  + f'header = "x-amz-content-sha256: {digest}"\n').encode("ascii")
        version, _ = parse_response(_transfer(config, source.fileno(), deadline))
        config = (auth + f'url = "{url}?versionId={quote(version.decode("ascii"), safe="")}"\n'
                  + 'request = "GET"\n').encode("ascii")
        fetched_version, content = parse_response(_transfer(config, source.fileno(), deadline))
    if fetched_version != version or content != payload:
        raise DeliveryDenied("audit readback mismatch")
    return {"sha256": digest, "version_id": version.decode("ascii")}
