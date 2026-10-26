"""Disposable signed-stack proof: five client identities, ESO isolation and broker admin denial."""
import hashlib
import json
import re
from unittest.mock import Mock, patch

import build_kafka_client_tls_bundle as bundle
import install_kafka_client_tls as installer
import kafka_client_tls as contract
from rehearse_redis_tls import fixture_issuer, synthetic_memory


def broker_denial(k, values):
    # Only disposable fixture Pods receive these ephemeral synthetic keys over stdin.
    script = '''set -eu
d=$(mktemp -d /tmp/hooshix-client-proof.XXXXXXXX)
trap 'rm -f "$d/client.pem" "$d/client.properties" "$d/result"; rmdir "$d"' EXIT
umask 077
cat > "$d/client.pem"
printf '%s\\n' 'security.protocol=SSL' 'ssl.keystore.type=PEM' "ssl.keystore.location=$d/client.pem" \\
  'ssl.truststore.type=PEM' 'ssl.truststore.location=/run/kafka/ca.crt' \\
  'ssl.endpoint.identification.algorithm=HTTPS' 'request.timeout.ms=5000' \\
  'default.api.timeout.ms=10000' > "$d/client.properties"
export KAFKA_HEAP_OPTS='-Xms32m -Xmx128m'
export KAFKA_JVM_PERFORMANCE_OPTS='-XX:+UseSerialGC -XX:TieredStopAtLevel=1'
status=0
/usr/bin/timeout -s KILL 20 /opt/kafka/bin/kafka-acls.sh --bootstrap-server 127.0.0.1:9092 \\
  --command-config "$d/client.properties" --list > "$d/result" 2>&1 || status=$?
test "$status" -ne 0
grep -q 'ClusterAuthorizationException' "$d/result"
printf 'CLIENT_TLS_AND_ADMIN_DENIAL=Passed\\n'
'''
    pem = values[contract.FIELDS['tls.crt']] + values[contract.FIELDS['tls.key']]
    result = k('-n', 'platform-data', 'exec', '-i', 'kafka-0', '-c', 'kafka', '--',
               '/bin/sh', '-ec', script, data=pem.encode(), timeout=40)
    if result.strip() != b'CLIENT_TLS_AND_ADMIN_DENIAL=Passed':
        raise ValueError('KAFKA_CLIENT_NATIVE_ADMIN_DENIAL_REQUIRED')


def rehearse(k, runtime_plan, api, root, ca, root_key, audiences, node):
    if not re.fullmatch('hooshix-bao-ci-[a-f0-9]{12}-control-plane', node):
        raise ValueError('OWNED_KAFKA_CLIENT_FIXTURE_REQUIRED')
    def kube(*args, body=None, **options):
        return k(*args, data=json.dumps(body).encode() if body is not None else None, **options)
    def token_request(namespace, account, requested):
        return k('-n', namespace, 'create', 'token', account, '--duration=10m',
            *['--audience=' + a for a in requested]).decode().strip()
    runtime = installer.runtime
    plan = bundle.plan(runtime_plan)
    plan['public_ca_sha256'] = hashlib.sha256(ca.encode()).hexdigest()
    with patch.object(runtime.volume, 'NODE', node), patch.object(runtime.tls.redis.volume, 'NODE', node), \
            patch.object(runtime.tls.redis.pg.volume, 'NODE', node), synthetic_memory(ca), \
            fixture_issuer(ca, root_key) as (issuer, key, password):
        options = dict(kube=kube, api=api, root=root, ca=ca, issuer=issuer,
                       audiences=audiences, token_request=token_request)
        result = installer.execute(plan, **options,
            issue=lambda service: contract.issue(service, ca, issuer, key, password))
        before = {}
        for service in contract.SERVICES:
            path = 'hooshix/data/' + contract.entry(service)['key']
            stored = api.call(path, token=root)['data']
            secret = runtime.get(kube, 'secret', contract.entry(service)['name'], contract.NAMESPACE)
            before[service] = (stored['metadata']['version'], secret['metadata']['uid'], secret['data'])
            values = {k: v for k, v in stored['data'].items() if k not in installer.MARKERS}
            broker_denial(k, values)
        result['broker_client_tls_denial'] = 'Passed'
        no_issue = Mock(side_effect=ValueError('KAFKA_CLIENT_REENTRY_MUST_NOT_REISSUE'))
        installer.execute(plan, **options, issue=no_issue)
        no_issue.assert_not_called()
        for service in contract.SERVICES:
            path = 'hooshix/data/' + contract.entry(service)['key']
            secret = runtime.get(kube, 'secret', contract.entry(service)['name'], contract.NAMESPACE)
            if before[service] != (api.call(path, token=root)['data']['metadata']['version'],
                                   secret['metadata']['uid'], secret['data']):
                raise ValueError('KAFKA_CLIENT_REENTRY_CHANGED_MATERIAL')
        result['idempotent_reconciliation'] = 'Passed'
        for namespace, account in ((contract.NAMESPACE, 'eso-compromised-password-service'),
                                   ('platform-data', 'eso-kafka-tls')):
            jwt = token_request(namespace, account, [*audiences, contract.auth.AUDIENCE])
            token = api.call('auth/' + contract.auth.MOUNT + '/login', 'POST',
                {'role': account, 'jwt': jwt})['auth']['client_token']
            try:
                for service in contract.SERVICES:
                    api.call('hooshix/data/' + contract.entry(service)['key'], token=token, expected=403)
            finally:
                api.call('auth/token/revoke-self', 'POST', {}, token, 204)
        result['existing_identity_denials'] = 'Passed'
        markers = [root]
        for service in contract.SERVICES:
            value = api.call('hooshix/data/' + contract.entry(service)['key'], token=root)['data']['data']
            markers.extend(value[contract.FIELDS['tls.key']].splitlines()[1:-1])
        for namespace, target in ((contract.NAMESPACE, 'deployment/hooshix-eso'),
                                  ('hooshix-secrets', 'openbao-0'), ('platform-data', 'kafka-0')):
            logs = k('-n', namespace, 'logs', target, '--tail=200')
            if any(marker.encode() in logs for marker in markers):
                raise ValueError('KAFKA_CLIENT_CONTAINER_LOG_LEAK')
        result['container_log_privacy'] = 'Passed'
        result['broker_and_datastore_preservation'] = 'Passed'
    return result
