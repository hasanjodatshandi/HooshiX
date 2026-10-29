"""Disposable signed-stack proof: five client identities, ESO isolation and broker admin denial."""
import hashlib
import json
import re
import traceback
from pathlib import Path
from unittest.mock import Mock, patch

import build_kafka_client_tls_bundle as bundle
import install_kafka_client_tls as installer
import kafka_client_tls as contract
from rehearse_redis_tls import fixture_issuer, synthetic_memory

SAFE_FAILURES = frozenset({
    'KAFKA_CLIENT_READY_CLOSED_BROKER_REQUIRED', 'KAFKA_CLIENT_CLOSED_BOUNDARY_REQUIRED',
    'KAFKA_CLIENT_EXISTING_BROKER_RESOURCE_REQUIRED', 'KAFKA_CLIENT_ACTIVE_SOURCE_VERIFICATION_REQUIRED',
    'KAFKA_CLIENT_RESTRICTED_NAMESPACE_REQUIRED', 'KAFKA_CLIENT_EXISTING_APPLICATION_ESO_REQUIRED',
    'KAFKA_CLIENT_EXISTING_AUDIENCES_REQUIRED', 'KAFKA_CLIENT_EXISTING_STORE_REQUIRED',
    'KAFKA_CLIENT_FOREIGN_EXTERNAL_PRESERVED', 'KAFKA_CLIENT_FOREIGN_TARGET_PRESERVED',
    'KAFKA_CLIENT_EXISTING_AUTH_ROLE_REQUIRED', 'KAFKA_CLIENT_EXISTING_AUTH_POLICY_REQUIRED',
    'KAFKA_CLIENT_ORPHANED_DELIVERY_PRESERVED', 'KAFKA_CLIENT_ESO_READBACK_REQUIRED',
    'KAFKA_CLIENT_EXISTING_STATE_CHANGED', 'KAFKA_CLIENT_NATIVE_ADMIN_DENIAL_REQUIRED'})


def failure_details(error):
    from rehearse_kafka_runtime import safe_failure_code
    code = error.args[0] if error.args and isinstance(error.args[0], str) else ''
    result = {'code': code if code in SAFE_FAILURES else safe_failure_code(error) or 'OTHER'}
    allowed = {'kafka_client_tls.py', 'install_kafka_client_tls.py', 'rehearse_kafka_client_tls.py',
               'install_kafka_runtime.py', 'install_eso.py'}
    frames = traceback.extract_tb(error.__traceback__)
    if frames and Path(frames[-1].filename).name in allowed:
        result.update(source=Path(frames[-1].filename).name, line=frames[-1].lineno)
    return result


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
        try:
            result = installer.execute(plan, **options,
                issue=lambda service: contract.issue(service, ca, issuer, key, password))
        except Exception as error:
            print('KAFKA_CLIENT_REHEARSAL_FAILURE=' + json.dumps(failure_details(error)), flush=True)
            raise
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
        import install_kafka_topic_acl as topic_installer
        topics = topic_installer.execute
        legacy_topic_plan = {**plan, 'kafka_topic_acl': True,
            'repository_source_revision': runtime_plan['repository_source_revision']}
        original_command, added = topic_installer.command, 0
        def interrupt_partial(kube, tool, *args):
            nonlocal added
            if tool == 'acls' and '--add' in args:
                if added == 4:
                    raise ValueError('KAFKA_FIXTURE_PARTIAL_ACL_STOP')
                added += 1
            return original_command(kube, tool, *args)
        with patch.object(topic_installer, 'command', side_effect=interrupt_partial):
            try:
                topic_installer._execute(legacy_topic_plan, kube=kube, guard=lambda **_: None)
            except ValueError as error:
                if str(error) != 'KAFKA_FIXTURE_PARTIAL_ACL_STOP':
                    raise
            else:
                raise ValueError('KAFKA_PARTIAL_FIXTURE_INTERRUPTION_REQUIRED')
        if added != 4:
            raise ValueError('KAFKA_PARTIAL_FIXTURE_ACL_COUNT_REQUIRED')
        import render_kafka_runtime as health_renderer
        upgraded = health_renderer.render(runtime_plan['previous_boundary'])
        topic_plan = {**plan, 'kafka_topic_acl': True,
                      'kafka_runtime': {key: upgraded[key] for key in ('statefulset','objects','boundary','source_policies')},
                      'kafka_health_upgrade': plan['kafka_runtime'],
                      'repository_source_revision': runtime_plan['repository_source_revision']}
        result['kafka_topic_acl'] = topics(topic_plan, kube=kube, guard=lambda **_: None)
        if topics(topic_plan, kube=kube, guard=lambda **_: None) != result['kafka_topic_acl']:
            raise ValueError('KAFKA_TOPIC_ACL_REENTRY_CHANGED_STATE')
        from rehearse_kafka_topic_acl import verify as verify_topics
        result['kafka_topic_acl'].update(verify_topics(k, {service: {
            key: value for key, value in api.call('hooshix/data/' + contract.entry(service)['key'], token=root)
                ['data']['data'].items() if key not in installer.MARKERS} for service in contract.SERVICES}))
        result['kafka_topic_acl']['idempotent_reconciliation'] = 'Passed'
        result['kafka_topic_acl']['partial_recovery'] = 'Passed'
        from rehearse_kafka_quotas import rehearse as quotas
        result['kafka_quotas'] = quotas(kube, topic_plan)
    return result
