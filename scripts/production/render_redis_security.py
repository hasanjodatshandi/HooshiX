"""Redis TLS/ACL candidate configuration; no credentials, publication or target writes."""
from __future__ import annotations

import argparse
import json

from render_redis_acl import PATTERNS as PATTERNS, USERS as USERS, acl_file as render_acl

from verify_openbao_artifact import ROOT, load

PIN = ROOT / 'infrastructure/production/redis/candidate.json'
SECRET_PATH = '/run/secrets/security-redis'


def pins():
    pin = load(PIN)
    policy = load(ROOT / 'infrastructure/production/data/data-policy.json')['redis']
    if (pin.get('schema_version') != 1 or pin.get('owner') != 'platform'
            or pin.get('version') != '8.2.8' or policy['version'] != pin['version']
            or pin.get('platform') != 'linux/amd64'
            or pin.get('image') != 'docker.io/library/redis@sha256:'
                '3790f265260957949f41e767efd9b66d5e3f277913b63a56c0183d55a47e7347'
            or pin.get('runtime_base') != 'docker.io/library/alpine@sha256:'
                'd56c381f961d307a21b3ca004cf1e3910f106644aefb1f43e654c8a56c4fd395'
            or pin.get('source_revision') != '382bda1c5a98bc0c0376930cf30ffd92eb3c5c18'
            or pin.get('source_archive_sha256') !=
                '94100f4fb2067b28becdb957d3f56253e322cb3ed800238dfb29e44086bce066'
            or pin.get('provenance_kind') != 'patched-upstream-runtime-build'
            or pin.get('runtime_users') != PATTERNS or pin.get('application_ingress') != 'CLOSED'
            or pin.get('production_promotion') !=
                'blocked-until-supply-chain-staging-and-recovery-evidence'
            or policy['tls_required'] is not True or policy['acl_isolation'] is not True
            or policy['maxmemory_policy'] != 'noeviction' or policy['aof'] is not True
            or policy['appendfsync'] != 'everysec' or policy['instances'] != 1):
        raise ValueError('reviewed blocked Redis candidate and security policy required')
    return pin


def server_config(maxmemory_bytes):
    pins()
    if type(maxmemory_bytes) is not int or not 16 * 1024**2 <= maxmemory_bytes <= 16 * 1024**3:
        raise ValueError('explicit bounded memory budget required; capacity approval is separate')
    return '\n'.join(('bind 0.0.0.0', 'protected-mode yes', 'port 0', 'tls-port 6379',
        f'tls-cert-file {SECRET_PATH}/tls.crt', f'tls-key-file {SECRET_PATH}/tls.key',
        f'tls-ca-cert-file {SECRET_PATH}/ca.crt', 'tls-auth-clients no',
        'tls-protocols "TLSv1.2 TLSv1.3"', f'aclfile {SECRET_PATH}/users.acl',
        'daemonize no', 'logfile ""', 'dir /data', 'save ""', 'appendonly yes',
        'appendfsync everysec', 'no-appendfsync-on-rewrite no', 'aof-load-truncated no',
        f'maxmemory {maxmemory_bytes}', 'maxmemory-policy noeviction', 'maxclients 128',
        'databases 1', ''))


def acl_file(password_hashes):
    """Only the secret-delivery boundary supplies these hashes; never print this result."""
    pins()
    return render_acl(password_hashes)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--maxmemory-bytes', type=int, required=True)
    args = parser.parse_args()
    print(json.dumps({'schema_version': 1, 'source_image': pins()['image'],
        'server_config': server_config(args.maxmemory_bytes), 'runtime_users': PATTERNS,
        'application_ingress': 'CLOSED', 'deployment': 'Not run',
        'production_readiness': 'Not verified'}, indent=2))


if __name__ == '__main__':
    main()
