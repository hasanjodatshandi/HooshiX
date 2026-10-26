"""Real five-principal topic/group isolation on the existing disposable Kafka fixture only."""
import kafka_client_tls as clients
import kafka_topic_acl as contract
from test_kafka_tls_acl_native import docker, MOUNT, require


def exercise_acl(container, values, docker_host):
    def command(tool, *args):
        # Fixtures have no network and are removed by the existing TLS harness.
        result = docker('exec', '-i', container, '/usr/bin/timeout', '-s', 'KILL', '24',
            '/usr/bin/env', 'KAFKA_HEAP_OPTS=-Xms32m -Xmx128m',
            'KAFKA_JVM_PERFORMANCE_OPTS=-XX:+UseSerialGC -XX:TieredStopAtLevel=1',
            '/opt/kafka/bin/kafka-' + tool + '.sh', '--bootstrap-server', '127.0.0.1:9092',
            '--command-config', MOUNT + '/admin.properties', *args,
            timeout=30, docker_host=docker_host)
        require(result.returncode == 0, 'KAFKA_APPLICATION_OPERATION_FAILED_' + tool.upper())
        return result.stdout

    first = contract.reconcile(command, owned=False)
    require(next(first) == {'topics': 0, 'acl_entries': 0}, 'EMPTY_KAFKA_ACL_FIXTURE_REQUIRED')
    receipt = next(first)
    second = contract.reconcile(command, owned=True)
    require(next(second)['topics'] == 6 and next(second) == receipt,
            'KAFKA_TOPIC_ACL_IDEMPOTENCE_REQUIRED')

    def kube(*args, data=None, timeout=40):
        prefix = ('-n', 'platform-data', 'exec')
        require(args[:3] == prefix, 'KAFKA_TOPIC_DISPOSABLE_EXEC_REQUIRED')
        offset = 4 if args[3] == '-i' else 3
        require(args[offset:offset+4] == ('kafka-0', '-c', 'kafka', '--'),
                'KAFKA_TOPIC_DISPOSABLE_EXEC_REQUIRED')
        result = docker('exec', '-i', container, *args[offset+4:],
            input_text=data.decode() if data else None, timeout=timeout, docker_host=docker_host)
        require(result.returncode == 0, 'KAFKA_TOPIC_NATIVE_TRANSPORT_PROOF_FAILED')
        return result.stdout.encode()
    from rehearse_kafka_topic_acl import verify
    verify(kube, values)
    receipt.update(positive_produce_consume='Passed', foreign_topic_group_admin_denials='Passed',
                   idempotent_reconciliation='Passed')
    return receipt


def verify_access(command):
    def produce(service, topic, denied=None):
        command('console-producer', '--sync', '--topic', topic, identity=service,
            payload='synthetic-event\n', denied=denied)

    def consume(service, topic, group, denied=None):
        result = command('console-consumer', '--topic', topic, '--group', group, '--from-beginning',
            '--max-messages', '1', '--timeout-ms', '8000', identity=service, denied=denied)
        if not denied:
            require(result.strip() == 'synthetic-event', 'KAFKA_APPLICATION_CONSUMPTION_REQUIRED')

    produce('identity-service', contract.COMMAND)
    produce('identity-service', contract.LIFECYCLE)
    for service in clients.SERVICES:
        group = contract.groups(service)[0]
        consume(service, contract.COMMAND, group)
        produce(service, contract.RECEIPT)
        produce(service, contract.COMMAND + '.DLT')
        consume(service, contract.COMMAND, 'foreign-group', 'GroupAuthorizationException')
        command('acls', '--list', identity=service, denied='ClusterAuthorizationException')
        if service != 'identity-service':
            produce(service, contract.COMMAND, 'TopicAuthorizationException')
            produce(service, contract.LIFECYCLE, 'TopicAuthorizationException')
            produce(service, contract.RECEIPT + '.DLT', 'TopicAuthorizationException')
            consume(service, contract.RECEIPT, group, 'TopicAuthorizationException')
        if service != 'conversation-service':
            produce(service, contract.LIFECYCLE + '.DLT', 'TopicAuthorizationException')
        if service not in ('identity-service', 'conversation-service'):
            consume(service, contract.LIFECYCLE, group, 'TopicAuthorizationException')
    consume('identity-service', contract.RECEIPT, contract.groups('identity-service')[1])
    consume('conversation-service', contract.LIFECYCLE, contract.groups('conversation-service')[1])
    produce('identity-service', contract.RECEIPT + '.DLT')
    produce('conversation-service', contract.LIFECYCLE + '.DLT')
    # Positive controls distinguish authorization denials from a dead broker.
    command('topics', '--describe', '--topic', contract.COMMAND)
    require(contract.parse_acls(command('acls', '--list')) == contract.permissions(),
            'KAFKA_APPLICATION_ACL_PRESERVATION_REQUIRED')
