"""Synthetic issuer and native OpenBao/ESO TLS delivery; never production CA custody."""
import json
import hashlib
import secrets
from contextlib import contextmanager
from unittest.mock import Mock, patch

import install_redis_tls as installer
import redis_secret_delivery as contract
import redis_tls_material as certificates
import openbao_scoped_auth as auth
from rehearse_redis_secrets import preserve_fixture


@contextmanager
def synthetic_memory(ca):
    # Non-production fixture keys may run on a swap-enabled disposable runner/WSL.
    # This helper is NOT included in owner bundles; target ram_work never gets this substitution.
    certificates.require(hashlib.sha256(ca.encode()).hexdigest() != certificates.custody.ROOT_SHA256,
                         'REDIS_TLS_SYNTHETIC_ROOT_REQUIRED')
    ordinary = certificates.Path.read_text
    def read(path, *args, **options):
        return 'Filename Type Size Used Priority\n' if str(path) == '/proc/swaps' else ordinary(path, *args, **options)
    with patch.object(certificates.Path, 'read_text', read): yield


@contextmanager
def fixture_issuer(root, root_key):
    with certificates.ram_work() as work:
        native = certificates.platform.native
        openssl = certificates.custody.OPENSSL
        certificates.custody.create(work / 'root.crt', root.encode())
        native([openssl, 'req', '-new', '-newkey', 'rsa:4096', '-sha256', '-nodes',
            '-subj', '/CN=synthetic-redis-intermediate', '-keyout', str(work / 'issuer.key'),
            '-out', str(work / 'issuer.csr')], timeout=60)
        certificates.custody.create(work / 'issuer.cnf',
            b'basicConstraints=critical,CA:TRUE,pathlen:0\nkeyUsage=critical,keyCertSign,cRLSign\n')
        native([openssl, 'x509', '-req', '-in', str(work / 'issuer.csr'), '-CA', str(work / 'root.crt'),
            '-CAkey', str(root_key), '-set_serial', '0x' + secrets.token_hex(16), '-days', '120',
            '-sha256', '-extfile', str(work / 'issuer.cnf'), '-out', str(work / 'issuer.crt')])
        password = secrets.token_urlsafe(32).encode()
        certificates.custody.native([openssl, 'pkcs8', '-topk8', '-in', str(work / 'issuer.key'),
            '-v2', 'aes-256-cbc', '-out', str(work / 'issuer.enc')], passphrase=password)
        yield (work / 'issuer.crt').read_text(), work / 'issuer.enc', password


def rehearse(k, plan, api, root, ca, root_key, audiences, owned_node):
    def kube(*args, body=None, **options):
        return k(*args, data=json.dumps(body).encode() if body is not None else None, **options)

    def token_request(namespace, account, requested):
        return k('-n', namespace, 'create', 'token', account, '--duration=10m',
                 *['--audience=' + a for a in requested]).decode().strip()

    before = preserve_fixture(kube, owned_node)
    with synthetic_memory(ca), fixture_issuer(ca, root_key) as (issuer, key, password):
        options = dict(kube=kube, api=api, root=root, ca=ca, issuer=issuer,
                       audiences=audiences, token_request=token_request)
        result = installer.execute(plan, **options, issue=lambda: certificates.issue(ca, issuer, key, password))
        first = kube('-n', contract.NAMESPACE, 'get', 'secret', contract.TLS_NAME,
                     '-o', 'jsonpath={.metadata.uid}:{.metadata.resourceVersion}')
        version = api.call('hooshix/data/' + installer.ENTRY['key'], token=root)['data']['metadata']['version']
        no_issue = Mock(side_effect=ValueError('REDIS_TLS_REENTRY_MUST_NOT_REISSUE'))
        installer.execute(plan, **options, issue=no_issue)
        no_issue.assert_not_called()
        if first != kube('-n', contract.NAMESPACE, 'get', 'secret', contract.TLS_NAME,
                        '-o', 'jsonpath={.metadata.uid}:{.metadata.resourceVersion}') or version != api.call(
                'hooshix/data/' + installer.ENTRY['key'], token=root)['data']['metadata']['version']:
            raise ValueError('REDIS_TLS_REENTRY_CHANGED_MATERIAL')
        result['idempotent_reconciliation'] = 'Passed'
        # Existing Redis identities must not acquire the new private-key leaf.
        installer.previous.verify_auth(api, root, token_request, audiences)
        for owner in contract.OWNERS.values():
            jwt = token_request(auth.NAMESPACE, 'eso-' + owner, [*audiences, auth.AUDIENCE])
            token = api.call('auth/' + auth.MOUNT + '/login', 'POST',
                {'role': 'eso-' + owner, 'jwt': jwt})['auth']['client_token']
            try: api.call('hooshix/data/' + installer.ENTRY['key'], token=token, expected=403)
            finally: api.call('auth/token/revoke-self', 'POST', {}, token, 204)
        result['existing_identity_tls_denials'] = 'Passed'
        material = api.call('hooshix/data/' + installer.ENTRY['key'], token=root)['data']['data']
        markers = [root, material['tls.key'], *material['tls.key'].splitlines()[1:-1]]
        for namespace, name in [(contract.NAMESPACE, 'deployment/' + contract.ACCOUNT), ('hooshix-secrets', 'openbao-0')]:
            logs = k('-n', namespace, 'logs', name, '--tail=200')
            if any(marker.encode() in logs for marker in markers):
                raise ValueError('REDIS_TLS_CONTAINER_LOG_LEAK')
        result['container_log_privacy'] = 'Passed'
    if preserve_fixture(kube, owned_node) != before:
        raise ValueError('REDIS_TLS_EXISTING_DATABASE_CHANGED')
    from rehearse_redis_secrets import quarantine
    pv = quarantine.get(kube, 'pv', quarantine.volume.VOLUME)
    result['bound_database_preservation'] = 'Passed' if pv['status']['phase'] == 'Bound' else 'Not run'
    return result
