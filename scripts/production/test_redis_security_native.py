"""Isolated synthetic TLS/ACL/AOF proof; never contacts a VPS or publishes an image."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import subprocess
import tempfile
import time
from pathlib import Path

from render_redis_security import PATTERNS, SECRET_PATH, USERS, acl_file, server_config
from test_postgresql_isolation_native import docker


def require(condition, code):
    if not condition:
        raise RuntimeError(code)


def exercise(image, endpoint):
    require(bool(re.fullmatch(r'sha256:[a-f0-9]{64}', image)), 'IMMUTABLE_LOCAL_IMAGE_REQUIRED')
    require(endpoint.startswith('unix://') and os.getuid() != 0, 'NONROOT_LOCAL_FIXTURE_REQUIRED')
    require(subprocess.run(['findmnt', '-n', '-o', 'FSTYPE', '--target', '/dev/shm'],
            capture_output=True, text=True, check=True, timeout=10).stdout.strip() == 'tmpfs',
            'RAM_BACKED_FIXTURE_REQUIRED')
    passwords = {user: secrets.token_urlsafe(32) for user in USERS}
    hashes = {user: hashlib.sha256(value.encode()).hexdigest() for user, value in passwords.items()}
    with tempfile.TemporaryDirectory(prefix='hooshix-redis-', dir='/dev/shm') as temporary:
        directory = Path(temporary)
        config, data = directory / 'config', directory / 'data'
        config.mkdir(mode=0o700)
        data.mkdir(mode=0o700)
        for stem in ('tls', 'wrong'):
            result = subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048',
                '-noenc', '-days', '1', '-subj', '/CN=security-redis.platform-data.svc',
                '-addext', 'subjectAltName=DNS:security-redis.platform-data.svc,IP:127.0.0.1',
                '-keyout', str(config / (stem + '.key')), '-out', str(config / (stem + '.crt'))],
                capture_output=True, timeout=30, check=False)
            require(result.returncode == 0, 'SYNTHETIC_TLS_FIXTURE_FAILED')
        (config / 'ca.crt').write_bytes((config / 'tls.crt').read_bytes())
        (config / 'redis.conf').write_text(server_config(16 * 1024**2))
        (config / 'users.acl').write_text(acl_file(hashes))
        (config / 'health-password').write_text(passwords['health'])
        for path in config.iterdir():
            path.chmod(0o600)
        started = docker('run', '-d', '--pull', 'never', '--network', 'none', '--read-only',
            '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--user',
            f'{os.getuid()}:{os.getgid()}', '--memory', '128m', '--cpus', '1', '--pids-limit', '64',
            '--mount', f'type=bind,src={config},dst={SECRET_PATH},readonly',
            '--mount', f'type=bind,src={config / "health-password"},dst=/run/secrets/health/password,readonly',
            '--mount', f'type=bind,src={data},dst=/data', '--entrypoint', '/usr/local/bin/redis-server',
            image, SECRET_PATH + '/redis.conf', timeout=30, docker_host=endpoint)
        container = started.stdout.strip()
        require(started.returncode == 0 and bool(re.fullmatch('[a-f0-9]{64}', container)),
                'DISPOSABLE_REDIS_START_FAILED')
        try:
            def command(user, *args, ca='ca.crt', tls=True):
                argv = ['exec', '-i', container, '/bin/sh', '-ec',
                    'IFS= read -r REDISCLI_AUTH; export REDISCLI_AUTH; exec "$@"', 'fixture',
                    'redis-cli', '--raw', '-h', '127.0.0.1', '-p', '6379']
                if user is not None:
                    argv += ['--user', user]
                if tls:
                    argv += ['--tls', '--cacert', SECRET_PATH + '/' + ca]
                return docker(*argv, *args, input_text=passwords.get(user, '') + '\n',
                              timeout=5, docker_host=endpoint)

            def value(user, *args):
                result = command(user, *args)
                require(result.returncode == 0, 'SYNTHETIC_REDIS_COMMAND_FAILED')
                return result.stdout.strip()

            def ready():
                deadline = time.monotonic() + 30
                while command('health', 'PING').stdout.strip() != 'PONG':
                    require(time.monotonic() < deadline, 'REDIS_STARTUP_DEADLINE_EXCEEDED')
                    time.sleep(0.5)

            ready()
            from render_redis_security import HEALTH
            probe = docker('exec', container, '/bin/sh', '-ec', HEALTH, docker_host=endpoint)
            require(probe.returncode == 0 and not probe.stdout and not probe.stderr,
                    'CLOSED_RUNTIME_TLS_HEALTH_PROBE_FAILED')
            version = docker('exec', container, 'redis-server', '--version', docker_host=endpoint)
            require(version.returncode == 0 and 'v=8.2.8 ' in version.stdout, 'EXACT_REDIS_VERSION_REQUIRED')
            for section, markers in {'memory': ('maxmemory:16777216', 'maxmemory_policy:noeviction'),
                                     'persistence': ('aof_enabled:1', 'aof_last_write_status:ok')}.items():
                observed = value('monitor', 'INFO', section)
                require(all(marker in observed.splitlines() for marker in markers), 'REDIS_RUNTIME_POLICY_FAILED')
            require('NOAUTH' in value(None, 'PING'), 'DEFAULT_ACCOUNT_NOT_DISABLED')
            plaintext = command('health', 'PING', tls=False)
            require(plaintext.returncode != 0 and 'PONG' not in plaintext.stdout, 'PLAINTEXT_NOT_DENIED')
            untrusted = command('health', 'PING', ca='wrong.crt')
            require(untrusted.returncode != 0 and 'certificate verify failed' in untrusted.stderr.lower(),
                    'UNTRUSTED_CA_NOT_DENIED')
            require(value('health', 'PING') == 'PONG', 'TLS_NEGATIVE_CONTROL_UNAVAILABLE')
            keys = {user: patterns[0].removesuffix('*') + 'synthetic-proof'
                    for user, patterns in PATTERNS.items()}
            denials = 0
            for user, own in keys.items():
                require('8.2.8' in value(user, 'HELLO', '3'), 'CLIENT_HANDSHAKE_FAILED')
                require(value(user, 'CLIENT', 'SETINFO', 'LIB-NAME', 'synthetic-fixture') == 'OK',
                        'CLIENT_LIBRARY_HANDSHAKE_FAILED')
                require(value(user, 'SET', own, 'synthetic-value') == 'OK', 'OWN_NAMESPACE_WRITE_FAILED')
                require(value(user, 'GET', own) == 'synthetic-value', 'OWN_NAMESPACE_READ_FAILED')
                require(value(user, 'EVAL', "return redis.call('GET',KEYS[1])", '1', own)
                        == 'synthetic-value', 'OWN_NAMESPACE_LUA_FAILED')
                require('used_memory:' in value(user, 'EVAL', "return redis.call('INFO','memory')", '0'),
                        'QUOTA_MEMORY_OBSERVATION_FAILED')
                for other, foreign in keys.items():
                    if other == user:
                        continue
                    for args in (('GET', foreign), ('SET', foreign, 'denied'),
                                 ('EVAL', "return redis.call('GET',KEYS[1])", '1', foreign)):
                        require('NOPERM' in value(user, *args), 'CROSS_OWNER_DENIAL_MISSING')
                        denials += 1
                    hidden = value(user, 'EVAL', f"return redis.call('GET','{foreign}')", '1', own)
                    require('ACL' in hidden or 'permissions' in hidden, 'DYNAMIC_LUA_KEY_DENIAL_MISSING')
                    denials += 1
                for args in (('CONFIG', 'GET', '*'), ('ACL', 'LIST'), ('KEYS', '*'),
                             ('SCAN', '0'), ('FLUSHALL',), ('FLUSHDB',), ('SCRIPT', 'FLUSH'),
                             ('INFO', 'clients'), ('MODULE', 'LIST')):
                    require('NOPERM' in value(user, *args), 'ADMIN_OR_ENUMERATION_DENIAL_MISSING')
            for user in ('health', 'monitor'):
                require('NOPERM' in value(user, 'GET', keys['identity']), 'PROBE_DATA_DENIAL_MISSING')
            # Exercise real noeviction OOM without an unbounded allocator or host data.
            allocated = []
            for index in range(24):
                key = 'identity:quota:v1:synthetic-fill-' + str(index)
                result = value('identity', 'EVAL',
                    "return redis.call('SET',KEYS[1],string.rep('x',1048576))", '1', key)
                if 'OOM' in result:
                    break
                require(result == 'OK', 'MEMORY_PRESSURE_FIXTURE_FAILED')
                allocated.append(key)
            else:
                raise RuntimeError('NOEVICTION_OOM_NOT_OBSERVED')
            require('evicted_keys:0' in value('monitor', 'INFO', 'stats').splitlines(), 'SECURITY_STATE_EVICTED')
            for key in allocated:
                require(value('identity', 'DEL', key) == '1', 'PRESSURE_FIXTURE_CLEANUP_FAILED')
            require(docker('stop', '--time', '10', container, docker_host=endpoint).returncode == 0,
                    'SYNTHETIC_REDIS_STOP_FAILED')
            require(docker('start', container, docker_host=endpoint).returncode == 0,
                    'SYNTHETIC_REDIS_RESTART_FAILED')
            ready()
            for user, key in keys.items():
                require(value(user, 'GET', key) == 'synthetic-value', 'AOF_RESTART_RECOVERY_FAILED')
            logs = docker('logs', container, docker_host=endpoint)
            require(logs.returncode == 0 and all(secret not in logs.stdout + logs.stderr
                    for secret in (*passwords.values(), *hashes.values())), 'REDIS_LOG_PRIVACY_FAILED')
        finally:
            require(docker('rm', '-fv', container, docker_host=endpoint).returncode == 0,
                    'DISPOSABLE_REDIS_CLEANUP_FAILED')
    return {'schema_version': 1, 'local_image_id': image, 'redis': '8.2.8',
        'tls_plaintext_and_untrusted_ca_denial': 'Passed', 'acl_isolation': 'Passed',
        'cross_owner_denials': denials, 'lua_permissions': 'Passed', 'noeviction_oom': 'Passed',
        'aof_process_restart': 'Passed', 'log_privacy': 'Passed', 'fixture_cleanup': 'Passed',
        'scope': 'local RAM-backed synthetic fixture; no VPS or cloud access',
        'application_client_compatibility': 'Not verified', 'capacity': 'Not verified',
        'deployment': 'Not run', 'production_readiness': 'Not verified'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image-id', required=True)
    args = parser.parse_args()
    try:
        selected = docker('context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}')
        require(selected.returncode == 0 and os.environ.get('DOCKER_HOST', 'unix://').startswith('unix://'),
                'LOCAL_DOCKER_REQUIRED')
        result = exercise(args.image_id, json.loads(selected.stdout))
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError) as error:
        code = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        parser.exit(1, 'REDIS_NATIVE_SECURITY=Failed; reason=' + code + '\n')
    print(json.dumps(result, sort_keys=True, indent=2))


if __name__ == '__main__':
    main()
