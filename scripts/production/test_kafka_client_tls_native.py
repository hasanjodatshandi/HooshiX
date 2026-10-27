"""Verify actual application PEM credentials with the exact published local Kafka digest."""
import argparse
import json
import re
import subprocess

import kafka_client_tls as contract
from rehearse_kafka_client_tls import broker_denial
from rehearse_redis_tls import fixture_issuer, synthetic_memory
from test_kafka_tls_acl_native import docker, exercise, require
from test_kafka_topic_acl_native import exercise_acl


def exercise_clients(docker_host):
    topic_evidence = {}
    def check(container, work):
        ca = (work / 'ca.crt').read_text()
        def kube(*args, data=None, timeout=30):
            require(args[:8] == ('-n', 'platform-data', 'exec', '-i', 'kafka-0', '-c', 'kafka', '--'),
                    'DISPOSABLE_KAFKA_CLIENT_EXEC_REQUIRED')
            result = docker('exec', '-i', container, *args[8:], input_text=data.decode(),
                            timeout=timeout, docker_host=docker_host)
            require(result.returncode == 0, 'DISPOSABLE_KAFKA_CLIENT_AUTHENTICATION_OR_DENIAL_FAILED')
            return result.stdout.encode()
        with synthetic_memory(ca), fixture_issuer(ca, work / 'ca.key') as (issuer, key, password):
            values = {}
            for service in contract.SERVICES:
                values[service] = contract.issue(service, ca, issuer, key, password)
                broker_denial(kube, values[service])
            topic_evidence.update(exercise_acl(container, values, docker_host))
        cleanup = docker('exec', container, '/bin/sh', '-ec',
            'find /tmp -maxdepth 1 -name "hooshix-client-proof.*" -print',
            timeout=5, docker_host=docker_host)
        require(cleanup.returncode == 0 and not cleanup.stdout.strip(), 'KAFKA_CLIENT_PRIVATE_TMPFS_CLEANUP_REQUIRED')
    result = exercise(docker_host, client_tls_check=check)
    result.update(application_client_count=5, application_client_tls_admin_denial='Passed',
                  application_client_private_tmpfs_cleanup='Passed', kafka_topic_acl=topic_evidence)
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--disposable', required=True, action='store_true')
    p.parse_args()
    try:
        endpoint = docker('context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}')
        require(endpoint.returncode == 0, 'LOCAL_DOCKER_REQUIRED')
        result = exercise_clients(json.loads(endpoint.stdout))
    except (RuntimeError, OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        code = str(error) if re.fullmatch(r'[A-Z][A-Z0-9_]{1,120}', str(error)) else type(error).__name__
        p.exit(1, 'KAFKA_CLIENT_TLS_NATIVE=Failed; reason=' + code + '; no target changes; raw errors suppressed\n')
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
