"""One-shot encrypted OpenBao snapshot to the reviewed versioned ParsPack bucket."""
from __future__ import annotations

import hashlib
import json
import os
import re
import ssl
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

import openbao_snapshot_crypto as crypto
import openbao_snapshot_errors as errors
import parspack_snapshot_transport as cloud

ADDRESS = 'https://openbao.hooshix-secrets.svc:8200/v1/'
AUTH_ROLE = 'hooshix-openbao-snapshot'
AUTH_PATH = 'auth/hooshix-kubernetes/login'
KV_PATH = 'hooshix/data/production/openbao-backup'
TOKEN_PATH = Path('/var/run/hooshix/token/token')
CA_PATH = '/var/run/hooshix/ca/ca.crt'
MAX_RESPONSE = crypto.MAX_SNAPSHOT + 65536


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def client():
    tls = ssl.create_default_context(cafile=CA_PATH)
    return urllib.request.build_opener(urllib.request.HTTPSHandler(context=tls), NoRedirect())


def request(opener, path, *, token=None, body=None, maximum=65536):
    errors.require(re.fullmatch(r'[a-zA-Z0-9_/-]{1,256}', path) is not None,
                   'SNAPSHOT_API_PATH_REJECTED')
    headers = {'Accept': 'application/json'}
    if token is not None:
        errors.require(isinstance(token, str) and 10 <= len(token) <= 1024
                       and all(33 <= ord(c) <= 126 for c in token), 'SNAPSHOT_TOKEN_REJECTED')
        headers['X-Vault-Token'] = token
    data = None if body is None else json.dumps(body, separators=(',', ':')).encode()
    if data is not None:
        headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(ADDRESS + path, data=data,
                                 method='GET' if body is None else 'POST', headers=headers)
    try:
        with opener.open(req, timeout=90 if path.endswith('snapshot') else 10) as response:
            errors.require(response.status == 200, 'SNAPSHOT_API_STATUS_REJECTED')
            content = response.read(maximum + 1)
        errors.require(len(content) <= maximum, 'SNAPSHOT_API_RESPONSE_BOUND')
        return content
    except (OSError, ValueError, urllib.error.URLError):
        raise errors.SnapshotFailed('SNAPSHOT_API_FAILED_STATE_PRESERVED') from None


def revoke(opener, token):
    req = urllib.request.Request(ADDRESS + 'auth/token/revoke-self', data=b'{}', method='POST',
                                 headers={'Content-Type': 'application/json',
                                          'X-Vault-Token': token})
    try:
        with opener.open(req, timeout=10) as response:
            errors.require(response.status == 204 and not response.read(1),
                           'SNAPSHOT_TOKEN_REVOCATION_FAILED')
    except (OSError, ValueError, urllib.error.URLError):
        raise errors.SnapshotFailed('SNAPSHOT_TOKEN_REVOCATION_FAILED') from None


def run():
    jwt = TOKEN_PATH.read_text(encoding='ascii').strip()
    errors.require(100 <= len(jwt) <= 8192 and all(33 <= ord(c) <= 126 for c in jwt),
                   'SNAPSHOT_PROJECTED_IDENTITY_REJECTED')
    opener = client()
    login = json.loads(request(opener, AUTH_PATH, body={'role': AUTH_ROLE, 'jwt': jwt}))
    token = login.get('auth', {}).get('client_token')
    errors.require(isinstance(token, str) and 10 <= len(token) <= 1024,
                   'SNAPSHOT_AUTHENTICATION_REJECTED')
    try:
        secret = json.loads(request(opener, KV_PATH, token=token)).get('data', {}).get('data')
        errors.require(isinstance(secret, dict)
                       and set(secret) == {'access_key', 'secret_key', 'recipients'},
                       'SNAPSHOT_BUCKET_CREDENTIALS_REJECTED')
        access, key = secret['access_key'], secret['secret_key']
        errors.require(isinstance(access, str) and ':' not in access,
                       'SNAPSHOT_BUCKET_CREDENTIALS_REJECTED')
        # Credentials and the public recovery recipients are held only in OpenBao.
        recipients = secret['recipients']
        crypto.public_keys(recipients)
        snapshot = request(opener, 'sys/storage/raft/snapshot', token=token,
                           maximum=crypto.MAX_SNAPSHOT)
        errors.require(snapshot.startswith(b'\x1f\x8b\x08'), 'SNAPSHOT_ARCHIVE_REQUIRED')
        snapshot_hash = hashlib.sha256(snapshot).hexdigest()
        identifier = str(uuid.uuid4())
        encrypted = crypto.seal(snapshot, recipients)
        del snapshot
    finally:
        revoke(opener, token)
    del token, jwt
    snapshot_object, fetched = cloud.deliver(encrypted, access, key, identifier)
    errors.require(fetched == encrypted, 'SNAPSHOT_READBACK_MISMATCH')
    receipt = {'schema_version': 1, 'snapshot_id': identifier, 'snapshot_sha256': snapshot_hash,
               'snapshot_encryption': 'OpenPGP-AES256', 'snapshot_readback': 'Passed',
               'snapshot_object': snapshot_object}
    receipt_bytes = json.dumps(receipt, sort_keys=True, separators=(',', ':')).encode()
    receipt_object, fetched_receipt = cloud.deliver_receipt(receipt_bytes, access, key, identifier)
    errors.require(fetched_receipt == receipt_bytes, 'SNAPSHOT_RECEIPT_READBACK_MISMATCH')
    del encrypted, fetched, receipt_bytes, fetched_receipt, recipients, secret, access, key
    receipt.update({'receipt_readback': 'Passed', 'receipt_object': receipt_object,
                   'observed_at_epoch': int(time.time())})
    sys.stdout.write(json.dumps(receipt, sort_keys=True, separators=(',', ':')) + '\n')


if __name__ == '__main__':
    os.umask(0o077)
    try:
        run()
    except (errors.SnapshotFailed, cloud.DeliveryDenied, OSError, ValueError,
            KeyError, TypeError, urllib.error.URLError):
        sys.stderr.write('OPENBAO_SNAPSHOT_JOB=Failed; state retained; do not replay ambiguous upload\n')
        raise SystemExit(1)
