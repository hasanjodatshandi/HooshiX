"""Synthetic signed-stack TLS topic/group tests; private keys enter disposable tmpfs over stdin only."""
import shlex

import kafka_client_tls as clients
from test_kafka_topic_acl_native import verify_access
from install_kafka_topic_acl import command as admin_command


def verify(k, values):
    def command(tool, *args, identity='admin', payload=None, denied=None):
        if identity == 'admin':
            return admin_command(k, tool, *args)
        clients.entry(identity)
        if tool not in ('console-producer', 'console-consumer', 'acls'):
            raise ValueError('KAFKA_TOPIC_FIXTURE_TOOL_REQUIRED')
        properties = ('security.protocol=SSL\nssl.keystore.type=PEM\n'
            'ssl.truststore.type=PEM\nssl.truststore.location=/run/kafka/ca.crt\n'
            'ssl.endpoint.identification.algorithm=HTTPS\nrequest.timeout.ms=5000\n'
            'default.api.timeout.ms=8000\nmax.block.ms=8000\ndelivery.timeout.ms=10000\n'
            'acks=all\nenable.idempotence=true\nretries=1\n')
        argv = ['/opt/kafka/bin/kafka-' + tool + '.sh', '--bootstrap-server', '127.0.0.1:9092',
                '--command-config', '$CONFIG', *args]
        invocation = shlex.join(argv).replace("'$CONFIG'", '"$d/client.properties"')
        script = '''set -eu
d=$(mktemp -d /tmp/hooshix-topic-proof.XXXXXXXX)
trap 'rm -f "$d/client.pem" "$d/client.properties" "$d/out" "$d/err"; rmdir "$d"' EXIT
umask 077
cat > "$d/client.pem"
'''
        script += 'printf %s ' + shlex.quote(properties) + ' > "$d/client.properties"\n'
        script += 'printf "ssl.keystore.location=%s/client.pem\\n" "$d" >> "$d/client.properties"\n'
        script += 'export KAFKA_HEAP_OPTS="-Xms32m -Xmx128m"\n'
        script += 'export KAFKA_JVM_PERFORMANCE_OPTS="-XX:+UseSerialGC -XX:TieredStopAtLevel=1"\nstatus=0\n'
        prefix = "printf 'synthetic-event\\n' | " if payload else ''
        script += prefix + '/usr/bin/timeout -s KILL 24 ' + invocation + ' > "$d/out" 2> "$d/err" || status=$?\n'
        if denied:
            if denied not in ('TopicAuthorizationException', 'GroupAuthorizationException', 'ClusterAuthorizationException'):
                raise ValueError('KAFKA_TOPIC_FIXED_DENIAL_REQUIRED')
            script += 'grep -q ' + denied + ' "$d/out" "$d/err"\n'
            script += 'test "$status" -ne 0'
            if tool == 'console-consumer':
                script += ' || grep -q "Processed a total of 0 messages" "$d/err"'
            script += '\nprintf "DENIED\\n"\n'
        else:
            script += 'test "$status" -eq 0\n'
            if tool == 'console-consumer':
                script += 'test "$(cat "$d/out")" = synthetic-event\nprintf "synthetic-event\\n"\n'
        pem = values[identity][clients.FIELDS['tls.crt']] + values[identity][clients.FIELDS['tls.key']]
        result = k('-n', 'platform-data', 'exec', '-i', 'kafka-0', '-c', 'kafka', '--',
                   '/bin/sh', '-ec', script, data=pem.encode(), timeout=40).decode()
        if denied and result.strip() != 'DENIED':
            raise ValueError('KAFKA_TOPIC_EXPLICIT_DENIAL_REQUIRED')
        return result
    verify_access(command)
    return {'positive_produce_consume': 'Passed', 'foreign_topic_group_admin_denials': 'Passed'}
