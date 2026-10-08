"""One-attempt verified loopback HTTPS for supervised init/unseal, not probes."""
from __future__ import annotations

import contextlib
import json
import re
import select
import ssl
import subprocess
import urllib.error
import urllib.request

import bootstrap_intermediate_csr as custody

BOUND = 32768


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise custody.BootstrapFailed('ACTIVATION_API_REDIRECT_DENIED')


class Client:
    def __init__(self, port, certificate):
        custody.require(type(port) is int and 1024 <= port <= 65535, 'LOOPBACK_PORT_REJECTED')
        context = ssl.create_default_context(cadata=certificate)
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context), NoRedirect())
        self.base = 'https://127.0.0.1:' + str(port) + '/v1/'

    def call(self, operation, path=None, body=None):
        if operation == 'status':
            custody.require(path is None and body is None, 'API_REQUEST_REJECTED')
            path, method, timeout, data = 'sys/seal-status', 'GET', 3, None
        else:
            custody.require(operation == 'write' and path in ('sys/init', 'sys/unseal')
                            and isinstance(body, dict), 'API_REQUEST_REJECTED')
            method, timeout, data = 'PUT', 60 if path == 'sys/init' else 20, json.dumps(body).encode()
            custody.require(len(data) <= BOUND, 'API_REQUEST_BOUND')
        request = urllib.request.Request(self.base + path, method=method, data=data,
                                         headers={'Content-Type': 'application/json'})
        try:
            # No retry, redirect, proxy environment, token or plaintext persistence.
            with self.opener.open(request, timeout=timeout) as response:
                content = response.read(BOUND + 1)
                custody.require(response.status == 200 and len(content) <= BOUND, 'API_RESPONSE_REJECTED')
            value = json.loads(content)
            custody.require(isinstance(value, dict), 'API_RESPONSE_REJECTED')
            return value
        except (OSError, ValueError, urllib.error.URLError):
            raise custody.BootstrapFailed('ACTIVATION_API_FAILED_STATE_PRESERVED') from None


@contextlib.contextmanager
def forward(certificate):
    # kubectl selects an unused ephemeral port; never bind a public interface.
    args = [custody.K3S, 'kubectl', '--request-timeout=15s', '-n', 'hooshix-secrets',
            'port-forward', '--address=127.0.0.1', '--pod-running-timeout=15s',
            'pod/openbao-0', ':8200']
    process = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, env=custody.ENV)
    try:
        ready, _, _ = select.select([process.stdout], [], [], 15)
        custody.require(ready, 'ACTIVATION_LOOPBACK_START_TIMEOUT')
        line = process.stdout.readline(128)
        match = re.fullmatch(rb'Forwarding from 127\.0\.0\.1:([0-9]{1,5}) -> 8200\r?\n', line)
        custody.require(match is not None and process.poll() is None, 'ACTIVATION_LOOPBACK_REJECTED')
        yield Client(int(match[1]), certificate).call
    finally:
        # Initialize's durable ciphertext write happens INSIDE this context,
        # before even a forwarding teardown error can lose the response.
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        process.stdout.close()
