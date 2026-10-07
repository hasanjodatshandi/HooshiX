"""CI-only native Cosign contract on an in-memory loopback registry; no real keys."""
import hashlib
import json
import os
import subprocess
import tempfile
import threading
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from publish_openbao_candidate import verify_payload
from repair_import_referrers import INDEX, repair


def rehearse():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('RUNNER_ENVIRONMENT') != 'github-hosted':
        raise ValueError('disposable CI runner required')
    manifests, blobs, uploads = {}, {}, {}

    class Registry(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def handle_request(self):
            path = urllib.parse.urlsplit(self.path).path
            length = int(self.headers.get('Content-Length', '0'))
            if length > 1024 * 1024:
                self.send_error(413)
                return
            body = self.rfile.read(length)
            status, raw, media, headers = 404, b'', 'application/json', {}
            if path == '/v2/':
                status, raw = 200, b'{}'
            elif '/referrers/' in path:
                pass  # Model GHCR: fallback index, not the Referrers API.
            elif '/manifests/' in path:
                if self.command == 'PUT':
                    media = self.headers['Content-Type']
                    digest = 'sha256:' + hashlib.sha256(body).hexdigest()
                    repository = path.split('/manifests/')[0]
                    manifests[path] = manifests[repository + '/manifests/' + digest] = (body, media)
                    status, headers = 201, {'Docker-Content-Digest': digest}
                elif path in manifests:
                    raw, media = manifests[path]
                    status, headers = 200, {'Docker-Content-Digest': 'sha256:' + hashlib.sha256(raw).hexdigest()}
            elif '/blobs/uploads/' in path:
                if self.command == 'POST':
                    uploads[path + 'fixture'] = b''
                    status, headers = 202, {'Location': path + 'fixture'}
                elif self.command == 'PATCH':
                    uploads[path] = uploads.get(path, b'') + body
                    status, headers = 202, {'Location': path}
                elif self.command == 'PUT':
                    digest = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)['digest'][0]
                    blobs[digest] = uploads.pop(path, b'') + body
                    status, headers = 201, {'Docker-Content-Digest': digest}
            elif '/blobs/' in path:
                digest = path.split('/blobs/')[1]
                if digest in blobs:
                    status, raw = 200, blobs[digest]
                    headers = {'Docker-Content-Digest': digest}
            self.send_response(status)
            self.send_header('Content-Type', media)
            self.send_header('Content-Length', str(len(raw)))
            for key, value in headers.items():
                self.send_header(key, value)
            self.end_headers()
            if self.command != 'HEAD':
                self.wfile.write(raw)

        do_GET = do_HEAD = do_POST = do_PATCH = do_PUT = handle_request

    server = ThreadingHTTPServer(('127.0.0.1', 0), Registry)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix='hooshix-referrer-fixture-') as temporary:
            folder = Path(temporary)
            env = {'PATH': os.environ['PATH'], 'HOME': temporary, 'COSIGN_PASSWORD': ''}
            def native(argv, expected=0, expected_error=None):
                print('IMPORT_REFERRER_FIXTURE_STEP=' + argv[1], flush=True)
                result = subprocess.run(argv, cwd=folder, env=env, capture_output=True,
                                        text=True, timeout=60, check=False)
                if result.returncode != expected:
                    # Fixed markers only; synthetic keys still never enter artifacts.
                    for needle, label in (('501', 'fixture-method-missing'),
                                          ('fallback tag manifest', 'referrer-index-conflict'),
                                          ('verification failed', 'verification-failed'),
                                          ('connection refused', 'fixture-connection-refused')):
                        if needle in result.stderr:
                            print('IMPORT_REFERRER_FIXTURE_ERROR=' + label, flush=True)
                    raise ValueError('native fixture command failed: ' + argv[1])
                if expected_error and expected_error not in result.stderr:
                    raise ValueError('native collision did not reproduce the expected failure')
                return result.stdout
            native(['cosign', 'version'])
            config = json.dumps({'architecture': 'amd64', 'os': 'linux',
                                 'rootfs': {'type': 'layers', 'diff_ids': []}}).encode()
            config_digest = 'sha256:' + hashlib.sha256(config).hexdigest()
            blobs[config_digest] = config
            raw = json.dumps({'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.manifest.v1+json',
                              'config': {'mediaType': 'application/vnd.oci.image.config.v1+json',
                                         'digest': config_digest, 'size': len(config)}, 'layers': []}).encode()
            digest = hashlib.sha256(raw).hexdigest()
            repo = 'hasanjodatshandi/hooshix/platform-istio-istiod-private'
            prefix = '/v2/' + repo + '/manifests/'
            manifests['/v2/upstream/manifests/sha256:' + digest] = (raw, 'application/vnd.oci.image.manifest.v1+json')
            base = '127.0.0.1:' + str(server.server_port)
            image = base + '/' + repo + '@sha256:' + digest
            native(['cosign', 'copy', '--attachment-tag-prefix', 'import-',
                    base + '/upstream@sha256:' + digest, base + '/' + repo + ':candidate'])
            if prefix + 'sha256-' + digest in manifests:
                raise ValueError('copy polluted reserved referrer tag')
            native(['cosign', 'generate-key-pair'])
            sign = ['cosign', 'sign', '--yes', '--key', 'cosign.key', '--use-signing-config=false', '--tlog-upload=false', image]
            # Native copy created this exact bad alias before the fix.
            manifests[prefix + 'sha256-' + digest] = (raw, 'application/vnd.oci.image.manifest.v1+json')
            native(sign, expected=1, expected_error='fallback tag manifest is not an OCI image index')
            def exchange(method, path, data=None, extra=None):
                req = urllib.request.Request('http://' + base + path, data=data, method=method,
                                             headers={'Content-Type': INDEX, **(extra or {})})
                with urllib.request.urlopen(req, timeout=10) as response:
                    return response.status, dict(response.headers), response.read(1024 * 1024 + 1)
            if repair('ghcr.io/' + repo + '@sha256:' + digest, exchange) != 'Repaired':
                raise ValueError('fixture repair failed')
            native(sign)
            native(['cosign', 'verify', '--key', 'cosign.pub', '--insecure-ignore-tlog', image])
            native(['cosign', 'generate-key-pair', '--output-key-prefix', 'wrong'])
            native(['cosign', 'verify', '--key', 'wrong.pub', '--insecure-ignore-tlog', image], expected=1)
            for kind, uri in (('slsaprovenance1', 'https://slsa.dev/provenance/v1'),
                              ('cyclonedx', 'https://cyclonedx.org/bom')):
                predicate = ({'buildDefinition': {'buildType': 'fixture'}, 'runDetails': {}}
                             if kind == 'slsaprovenance1' else {'bomFormat': 'CycloneDX', 'specVersion': '1.6', 'version': 1})
                path = folder / 'predicate.json'
                path.write_text(json.dumps(predicate))
                native(['cosign', 'attest', '--yes', '--key', 'cosign.key', '--use-signing-config=false',
                        '--tlog-upload=false', '--predicate', str(path), '--type', kind, image])
                output = native(['cosign', 'verify-attestation', '--key', 'cosign.pub',
                                 '--insecure-ignore-tlog', '--type', kind, image])
                verify_payload(output, uri, predicate, digest)
            if repair('ghcr.io/' + repo + '@sha256:' + digest, exchange) != 'Preserved':
                raise ValueError('existing signed index not preserved')
            if manifests[prefix + 'sha256:' + digest][0] != raw:
                raise ValueError('immutable image changed')
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
    print('IMPORT_REFERRER_NATIVE_CONTRACT=Passed; synthetic loopback only; publication Not verified')


if __name__ == '__main__':
    try:
        rehearse()
    except (ValueError, KeyError, OSError, subprocess.SubprocessError):
        raise SystemExit('IMPORT_REFERRER_NATIVE_CONTRACT=Failed') from None
