"""Repair only exact imported-image aliases occupying OCI referrer index tags."""
import base64
import hashlib
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from verify_release import EXPECTED_CERTIFICATE_IDENTITY

INDEX = 'application/vnd.oci.image.index.v1+json'
IMAGE_TYPES = ('application/vnd.oci.image.manifest.v1+json',
               'application/vnd.docker.distribution.manifest.v2+json')
REPOSITORIES = frozenset('hasanjodatshandi/hooshix/platform-' + component + '-private'
                        for component in ('istio-istiod', 'istio-cni', 'istio-ztunnel', 'openbao'))
MAX_BYTES = 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def repair(image, exchange):
    """No deletion, index replacement, unknown digest replacement or image rewrite."""
    match = re.fullmatch(r'ghcr\.io/([^@]+)@sha256:([a-f0-9]{64})', image)
    if not match or match[1] not in REPOSITORIES:
        raise ValueError('owned import reference required')
    repository, digest = match.groups()
    path = '/v2/' + repository + '/manifests/'
    tag = 'sha256-' + digest
    status, headers, raw = exchange('GET', path + tag)
    if status == 404:
        return 'Absent'
    if status != 200 or len(raw) > MAX_BYTES:
        raise ValueError('referrer alias inspection failed')
    manifest = json.loads(raw)
    if manifest.get('schemaVersion') != 2:
        raise ValueError('manifest schema required')
    if manifest.get('mediaType') == INDEX and isinstance(manifest.get('manifests'), list):
        return 'Preserved'
    if (manifest.get('mediaType') not in IMAGE_TYPES
            or hashlib.sha256(raw).hexdigest() != digest):
        raise ValueError('only exact legacy image alias can be repaired')
    status, _, immutable = exchange('GET', path + 'sha256:' + digest)
    if status != 200 or immutable != raw:
        raise ValueError('immutable imported image must remain present')
    # Only the alias changes. The immutable image and candidate/import tags remain.
    index = json.dumps({'schemaVersion': 2, 'mediaType': INDEX, 'manifests': []},
                       separators=(',', ':')).encode()
    condition = {'If-Match': headers['ETag']} if headers.get('ETag') else {}
    status, _, _ = exchange('PUT', path + tag, index, condition)
    if status != 201:
        raise ValueError('referrer alias repair failed')
    status, _, actual = exchange('GET', path + tag)
    if status != 200 or actual != index:
        raise ValueError('referrer alias repair verification failed')
    return 'Repaired'


def repair_owned_import(image):
    """Only protected publisher CI uses its existing ephemeral package token."""
    if (os.environ.get('GITHUB_ACTIONS') != 'true'
            or os.environ.get('GITHUB_REF') != 'refs/heads/main'
            or os.environ.get('GITHUB_REPOSITORY') != 'hasanjodatshandi/HooshiX'
            or os.environ.get('GITHUB_EVENT_NAME') != 'workflow_dispatch'
            or os.environ.get('GITHUB_WORKFLOW_REF') != EXPECTED_CERTIFICATE_IDENTITY.removeprefix('https://github.com/')):
        raise ValueError('protected publisher context required')
    match = re.fullmatch(r'ghcr\.io/([^@]+)@sha256:[a-f0-9]{64}', image)
    if not match or match[1] not in REPOSITORIES:
        raise ValueError('owned import reference required')
    token = os.environ.get('GH_TOKEN', '')
    actor = os.environ.get('GITHUB_ACTOR', '')
    if not token or not re.fullmatch(r'[A-Za-z0-9-]+', actor):
        raise ValueError('ephemeral CI authentication required')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(method, url, authorization, data=None, extra=None):
        headers = {'Authorization': authorization, 'Accept': INDEX + ',' + ','.join(IMAGE_TYPES)}
        if data is not None:
            headers['Content-Type'] = INDEX
        headers.update(extra or {})
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with opener.open(req, timeout=20) as response:
                raw = response.read(MAX_BYTES + 1)
                if len(raw) > MAX_BYTES:
                    raise ValueError('bounded registry response required')
                return response.status, dict(response.headers), raw
        except urllib.error.HTTPError as error:
            return error.code, {}, b''  # Never expose response bodies, tokens or URLs.

    scope = urllib.parse.urlencode({'service': 'ghcr.io', 'scope': 'repository:' + match[1] + ':pull,push'})
    basic = base64.b64encode((actor + ':' + token).encode()).decode()
    status, _, raw = request('GET', 'https://ghcr.io/token?' + scope, 'Basic ' + basic)
    if status != 200:
        raise ValueError('registry authentication failed')
    bearer = json.loads(raw).get('token')
    if not isinstance(bearer, str) or not bearer or any(c.isspace() for c in bearer):
        raise ValueError('registry token required')
    def exchange(method, path, data=None, extra=None):
        return request(method, 'https://ghcr.io' + path, 'Bearer ' + bearer, data, extra)
    return repair(image, exchange)
