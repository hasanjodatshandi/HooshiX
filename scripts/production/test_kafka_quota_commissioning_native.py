"""Real Kafka 4.2.2 CLI inventory and interrupted quota acknowledgement on a disposable broker."""
import argparse
import json
import subprocess

import kafka_quota_commissioning as contract
import kafka_quota_profile as profile
from test_kafka_tls_acl_native import docker, exercise, MOUNT, require


def verify(container, work, docker_host):
    del work
    writes = 0

    def command(*args):
        nonlocal writes
        if '--alter' in args:
            writes += 1
        result = docker('exec', container, '/usr/bin/timeout', '-s', 'KILL', '24', '/usr/bin/env',
            'KAFKA_HEAP_OPTS=-Xms32m -Xmx128m',
            'KAFKA_JVM_PERFORMANCE_OPTS=-XX:+UseSerialGC -XX:TieredStopAtLevel=1',
            '/opt/kafka/bin/kafka-configs.sh', '--bootstrap-server', '127.0.0.1:9092',
            '--command-config', MOUNT + '/admin.properties', *args, timeout=40, docker_host=docker_host)
        require(result.returncode == 0, 'KAFKA_QUOTA_NATIVE_CLI_FAILED')
        return result.stdout

    desired = profile.load()
    def interrupted(*args):
        result = command(*args)
        if '--alter' in args:
            raise TimeoutError('synthetic lost acknowledgement')
        return result
    partial = contract.reconcile(interrupted, desired, owned=False)
    require(next(partial) == 0, 'KAFKA_QUOTA_EMPTY_FIXTURE_REQUIRED')
    try:
        next(partial)
    except TimeoutError:
        pass
    else:
        raise ValueError('KAFKA_QUOTA_INTERRUPTED_ACK_REQUIRED')
    resumed = contract.reconcile(command, desired, owned=True)
    require(next(resumed) == 1, 'KAFKA_QUOTA_PARTIAL_READBACK_REQUIRED')
    require(next(resumed)['kafka_quotas'] == 'Passed' and writes == 5,
            'KAFKA_QUOTA_EXACT_RESUME_REQUIRED')
    again = contract.reconcile(command, desired, owned=True)
    next(again)
    next(again)
    require(writes == 5, 'KAFKA_QUOTA_READ_ONLY_REENTRY_REQUIRED')
    command('--alter', '--entity-type', 'clients', '--entity-name', 'synthetic-foreign-client',
            '--add-config', 'producer_byte_rate=1')
    before = writes
    try:
        next(contract.reconcile(command, desired, owned=True))
    except ValueError as error:
        require(str(error) == 'KAFKA_QUOTA_FOREIGN_INVENTORY_PRESERVED',
                'KAFKA_QUOTA_NATIVE_FOREIGN_CLASS_REQUIRED')
    else:
        raise ValueError('KAFKA_QUOTA_NATIVE_FOREIGN_DENIAL_REQUIRED')
    require(writes == before, 'KAFKA_QUOTA_NATIVE_FOREIGN_WRITE_REJECTED')
    return {'schema_version': 1, 'native_cli_inventory': 'Passed', 'partial_ack_recovery': 'Passed',
        'idempotent_reconciliation': 'Passed', 'foreign_client_quota_preserved': 'Passed',
        'target_installation': 'Not run', 'production_capacity': 'Not verified'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--disposable', required=True, action='store_true')
    parser.parse_args()
    try:
        endpoint = docker('context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}')
        require(endpoint.returncode == 0, 'LOCAL_DOCKER_REQUIRED')
        host = json.loads(endpoint.stdout)
        proof = {}
        def check(container, work):
            proof.update(verify(container, work, host))
        exercise(host, client_tls_check=check)
    except (RuntimeError, OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        parser.exit(1, 'KAFKA_QUOTA_COMMISSIONING_NATIVE=Failed; no VPS changes; raw errors suppressed\n')
    print(json.dumps(proof, sort_keys=True))


if __name__ == '__main__':
    main()
