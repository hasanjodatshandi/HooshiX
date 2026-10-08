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
import parspack_audit_transport as audit

ENDPOINT = 'https://c892683.parspack.net/c892683/openbao-backups/'
ENV = {'PATH': '/usr/bin:/bin', 'LC_ALL': 'C'}
MAX_RESPONSE = crypto.MAX_CIPHERTEXT + 8192


def response(raw):
    if not isinstance(raw, bytes) or len(raw) > MAX_RESPONSE:
        raise audit.DeliveryDenied('snapshot response bound')
    headers, separator, body = raw.partition(b'\r\n\r\n')
    if not separator or len(headers) > 8192 or len(body) > crypto.MAX_CIPHERTEXT:
        raise audit.DeliveryDenied('snapshot response rejected')
    # Reuse strict status, unambiguous version and header validation; never parse provider errors.
    version, _ = audit.parse_response(headers + b'\r\n\r\n')
    return version, body


def transfer(config, payload_fd, deadline):
    remaining = min(50, deadline - time.monotonic())
    if remaining <= 0:
        raise audit.DeliveryDenied('snapshot transport deadline')
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
                raise audit.DeliveryDenied('snapshot transport failed; retain local ciphertext')
            output.seek(0)
            raw = output.read(MAX_RESPONSE + 1)
            if len(raw) > MAX_RESPONSE:
                raise audit.DeliveryDenied('snapshot response bound')
            return raw
        except (OSError, subprocess.SubprocessError):
            raise audit.DeliveryDenied('snapshot transport failed; retain local ciphertext') from None
        finally:
            if process is not None and process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate(timeout=5)


def deliver(encrypted, access_key, secret_key, snapshot_id):
    crypto.validate_ciphertext(encrypted)
    try:
        identifier = uuid.UUID(snapshot_id)
        if identifier.version != 4 or str(identifier) != snapshot_id:
            raise ValueError
    except (TypeError, ValueError, AttributeError):
        raise audit.DeliveryDenied('snapshot identity rejected') from None
    audit._quoted(access_key)
    audit._quoted(secret_key)
    if ':' in access_key:
        raise audit.DeliveryDenied('snapshot credential format rejected')
    digest = hashlib.sha256(encrypted).hexdigest()
    checksum = base64.b64encode(hashlib.sha256(encrypted).digest()).decode('ascii')
    auth = 'user = ' + audit._quoted(access_key + ':' + secret_key) + '\n'
    url = ENDPOINT + snapshot_id + '.snap.pgp'
    deadline = time.monotonic() + 110
    with tempfile.TemporaryFile() as source:
        source.write(encrypted)
        source.flush()
        source.seek(0)
        config = (auth + f'url = "{url}"\nrequest = "PUT"\n'
                  + f'data-binary = "@/proc/self/fd/{source.fileno()}"\n'
                  + 'header = "Content-Type: application/octet-stream"\nheader = "Expect:"\n'
                  + 'header = "If-None-Match: *"\n'
                  + f'header = "x-amz-content-sha256: {digest}"\n'
                  + 'header = "x-amz-sdk-checksum-algorithm: SHA256"\n'
                  + f'header = "x-amz-checksum-sha256: {checksum}"\n').encode('ascii')
        version, _ = response(transfer(config, source.fileno(), deadline))
        config = (auth + f'url = "{url}?versionId={quote(version.decode("ascii"), safe="")}"\n'
                  + 'request = "GET"\n').encode('ascii')
        fetched_version, fetched = response(transfer(config, source.fileno(), deadline))
    if fetched_version != version or fetched != encrypted:
        raise audit.DeliveryDenied('snapshot version-specific readback mismatch')
    return {'ciphertext_sha256': digest, 'version_id': version.decode('ascii'),
            'bucket': 'c892683', 'object_key': 'openbao-backups/' + snapshot_id + '.snap.pgp'}, fetched
