"""Ciphertext-only fixed-destination snapshot PUT and version-specific GET."""
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

import openbao_snapshot_crypto as crypto

BUCKET = 'c456170'
ENDPOINT = f'https://{BUCKET}.parspack.net/{BUCKET}/openbao-backups/'
ENV = {'PATH': '/usr/bin:/bin', 'LC_ALL': 'C'}
MAX_RESPONSE = crypto.MAX_CIPHERTEXT + 8192
VERSION = re.compile(rb'[A-Za-z0-9_.+/=-]{1,256}')


class DeliveryDenied(ValueError):
    """Fixed public diagnostics only; never provider bodies or credentials."""


def quoted(value):
    if (not isinstance(value, str) or not 1 <= len(value) <= 4096
            or any(ord(c) < 33 or ord(c) > 126 for c in value)):
        raise DeliveryDenied('snapshot credential format rejected')
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'


def response(raw, maximum):
    if not isinstance(raw, bytes) or len(raw) > MAX_RESPONSE:
        raise DeliveryDenied('snapshot response bound')
    headers, separator, body = raw.partition(b'\r\n\r\n')
    lines = headers.split(b'\r\n')
    if (not separator or len(headers) > 8192 or len(body) > maximum
            or not re.fullmatch(rb'HTTP/1\.1 200(?: [\x20-\x7e]*)?', lines[0])
            or len(lines) > 65):
        raise DeliveryDenied('snapshot response rejected')
    versions = []
    for line in lines[1:]:
        key, colon, value = line.partition(b':')
        if (not colon or not re.fullmatch(rb"[!#$%&'*+.^_`|~0-9A-Za-z-]+", key)
                or any(c < 32 or c > 126 for c in value)):
            raise DeliveryDenied('snapshot response rejected')
        if key.lower() == b'x-amz-version-id':
            versions.append(value.strip())
    if len(versions) != 1 or not VERSION.fullmatch(versions[0]) or versions[0] == b'null':
        raise DeliveryDenied('snapshot response version rejected')
    return versions[0], body


def transfer(config, payload_fd, deadline):
    remaining = min(50, deadline - time.monotonic())
    if remaining <= 0:
        raise DeliveryDenied('snapshot transport deadline')
    argv = ['/usr/bin/curl', '-q', '--config', '-', '--aws-sigv4', 'aws:amz:us-east-1:s3',
            '--http1.1', '--proto', '=https', '--proxy', '', '--noproxy', '*', '--silent',
            '--include', '--connect-timeout', '5', '--max-time', str(remaining),
            '--max-filesize', str(crypto.MAX_CIPHERTEXT), '--retry', '0']
    process = None
    with tempfile.TemporaryFile() as output:
        def limits():
            resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_RESPONSE, MAX_RESPONSE))
            resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
        try:
            process = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=output,
                stderr=subprocess.DEVNULL, env=ENV, pass_fds=(payload_fd,),
                start_new_session=True, preexec_fn=limits)
            process.communicate(config, timeout=remaining + 2)
            if process.returncode != 0 or time.monotonic() >= deadline:
                raise DeliveryDenied('snapshot transport failed; retain local ciphertext')
            output.seek(0)
            raw = output.read(MAX_RESPONSE + 1)
            if len(raw) > MAX_RESPONSE:
                raise DeliveryDenied('snapshot response bound')
            return raw
        except (OSError, subprocess.SubprocessError):
            raise DeliveryDenied('snapshot transport failed; retain local ciphertext') from None
        finally:
            if process is not None and process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate(timeout=5)


def deliver(encrypted, access_key, secret_key, snapshot_id):
    crypto.validate_ciphertext(encrypted)
    return _deliver(encrypted, access_key, secret_key, snapshot_id, '.snap.pgp',
                    'application/octet-stream', maximum=crypto.MAX_CIPHERTEXT)


def deliver_receipt(payload, access_key, secret_key, snapshot_id):
    if not isinstance(payload, bytes) or not 1 <= len(payload) <= 8192:
        raise DeliveryDenied('snapshot receipt size rejected')
    return _deliver(payload, access_key, secret_key, snapshot_id, '.receipt.json',
                    'application/json', maximum=8192)


def _deliver(payload, access_key, secret_key, snapshot_id, suffix, content_type, *, maximum):
    if not isinstance(payload, bytes) or not 1 <= len(payload) <= maximum:
        raise DeliveryDenied('snapshot payload size rejected')
    try:
        identifier = uuid.UUID(snapshot_id)
        if identifier.version != 4 or str(identifier) != snapshot_id:
            raise ValueError
    except (TypeError, ValueError, AttributeError):
        raise DeliveryDenied('snapshot identity rejected') from None
    quoted(access_key)
    quoted(secret_key)
    if ':' in access_key:
        raise DeliveryDenied('snapshot credential format rejected')
    digest = hashlib.sha256(payload).hexdigest()
    checksum = base64.b64encode(hashlib.sha256(payload).digest()).decode('ascii')
    auth = 'user = ' + quoted(access_key + ':' + secret_key) + '\n'
    url = ENDPOINT + snapshot_id + suffix
    deadline = time.monotonic() + 110
    with tempfile.TemporaryFile() as source:
        source.write(payload)
        source.flush()
        source.seek(0)
        config = (auth + f'url = "{url}"\nrequest = "PUT"\n'
                  + f'data-binary = "@/proc/self/fd/{source.fileno()}"\n'
                  + f'header = "Content-Type: {content_type}"\nheader = "Expect:"\n'
                  + 'header = "If-None-Match: *"\n'
                  + f'header = "x-amz-content-sha256: {digest}"\n'
                  + 'header = "x-amz-sdk-checksum-algorithm: SHA256"\n'
                  + f'header = "x-amz-checksum-sha256: {checksum}"\n').encode('ascii')
        version, _ = response(transfer(config, source.fileno(), deadline), maximum=8192)
        config = (auth + f'url = "{url}?versionId={quote(version.decode("ascii"), safe="")}"\n'
                  + 'request = "GET"\n').encode('ascii')
        fetched_version, fetched = response(transfer(config, source.fileno(), deadline), maximum=maximum)
    if fetched_version != version or fetched != payload:
        raise DeliveryDenied('snapshot version-specific readback mismatch')
    return {('ciphertext_sha256' if suffix == '.snap.pgp' else 'receipt_sha256'): digest,
            'version_id': version.decode('ascii'),
            'bucket': BUCKET, 'object_key': 'openbao-backups/' + snapshot_id + suffix}, fetched
