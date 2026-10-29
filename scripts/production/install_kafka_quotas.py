"""Supervised closed-broker quota commissioning; no topic/ACL/key/image or network mutation."""
import hashlib
import json
import sys

import install_kafka_topic_acl as topics
import kafka_quota_commissioning as contract
import kafka_quota_profile as profile

runtime = topics.runtime
require = runtime.require
MANAGER, JOURNAL = 'hooshix-kafka-quotas', 'hooshix-kafka-quotas-v1'


def command(kube, *args):
    return kube('-n', runtime.NS, 'exec', 'kafka-0', '-c', 'kafka', '--',
        '/usr/bin/timeout', '-s', 'KILL', '24', '/usr/bin/env',
        'KAFKA_HEAP_OPTS=-Xms32m -Xmx128m',
        'KAFKA_JVM_PERFORMANCE_OPTS=' + runtime.TOOL_JVM_OPTIONS,
        '/opt/kafka/bin/kafka-configs.sh', '--bootstrap-server', '127.0.0.1:9092',
        '--command-config', '/etc/kafka/commissioned/client.properties', *args,
        timeout=40).decode()


def topic_state(kube, identity):
    journal = runtime.get(kube, 'configmap', topics.JOURNAL, runtime.NS)
    require(journal and journal.get('immutable') is True
        and journal['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == topics.MANAGER
        and not journal['metadata'].get('deletionTimestamp') and not journal['metadata'].get('ownerReferences')
        and journal.get('data') == identity, 'KAFKA_QUOTA_EXISTING_TOPIC_JOURNAL_REQUIRED')
    state = next(topics.contract.reconcile(lambda *args: topics.command(kube, *args), owned=True))
    require(state == {'topics': 6, 'acl_entries': len(topics.contract.permissions())},
            'KAFKA_QUOTA_COMPLETE_TOPICS_ACLS_REQUIRED')
    return journal['metadata']['uid'], journal['data']


def _execute(plan, *, kube=runtime.host.kube, guard=runtime.volume.foundation):
    require(plan.get('schema_version') == 1 and plan.get('installation_id') == 'hooshix-production'
        and plan.get('kafka_quotas') is True
        and plan.get('public_access') == plan.get('application_ingress') == 'CLOSED',
        'KAFKA_QUOTA_CLOSED_PLAN_REQUIRED')
    desired = profile.quotas(plan['kafka_quota_profile'])
    units, epoch = guard(initialized=True), topics.broker_epoch(kube)
    before = topics.delivery.snapshot(kube, plan)
    credentials = topics.credential_snapshot(kube, plan['public_ca_sha256'])
    cluster = runtime.storage_identity(kube)[1]
    sts = runtime.get(kube, 'statefulset', 'kafka', runtime.NS)
    pvc = runtime.get(kube, 'pvc', runtime.volume.CLAIM, runtime.NS)
    identity = {'cluster_identity_sha256': cluster, 'statefulset_uid': sts['metadata']['uid'],
                'pvc_uid': pvc['metadata']['uid']}
    topic_identity = {**identity, 'contract_sha256': hashlib.sha256(json.dumps({
        'topics': topics.contract.topics(), 'acls': sorted(topics.contract.permissions())},
        sort_keys=True).encode()).hexdigest()}
    existing_topics = topic_state(kube, topic_identity)
    journal = {'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': JOURNAL,
        'namespace': runtime.NS, 'labels': {'app.kubernetes.io/managed-by': MANAGER}},
        'immutable': True, 'data': {**identity, 'contract_sha256': hashlib.sha256(
            json.dumps(desired, sort_keys=True).encode()).hexdigest()}}
    old = runtime.get(kube, 'configmap', JOURNAL, runtime.NS)
    require(not old or old.get('immutable') is True and old.get('data') == journal['data']
        and old['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == MANAGER
        and not old['metadata'].get('deletionTimestamp') and not old['metadata'].get('ownerReferences'),
        'KAFKA_QUOTA_FOREIGN_JOURNAL_PRESERVED')
    reconciliation = contract.reconcile(lambda *args: command(kube, *args), desired, owned=old is not None)
    next(reconciliation)
    if old is None:
        kube('create', '-f', '-', body=journal)
    current = runtime.get(kube, 'configmap', JOURNAL, runtime.NS)
    require(current and topics.delivery.desired_fields_match(current, journal),
            'KAFKA_QUOTA_JOURNAL_READBACK_REQUIRED')
    result = next(reconciliation)
    require(before == topics.delivery.snapshot(kube, plan)
        and credentials == topics.credential_snapshot(kube, plan['public_ca_sha256'])
        and epoch == topics.broker_epoch(kube) and cluster == runtime.storage_identity(kube)[1]
        and existing_topics == topic_state(kube, topic_identity)
        and guard(initialized=True) == units, 'KAFKA_QUOTA_EXISTING_STATE_CHANGED')
    return {**result, **identity, 'existing_state': 'Preserved',
        'contract_sha256': journal['data']['contract_sha256'],
        'source_revision': plan['repository_source_revision']}


def execute(plan, *, kube=runtime.host.kube, guard=runtime.volume.foundation):
    try:
        return _execute(plan, kube=kube, guard=guard)
    except (ValueError, topics.contract.clients.custody.BootstrapFailed) as error:
        allowed = {'KAFKA_QUOTA_CLOSED_PLAN_REQUIRED', 'KAFKA_QUOTA_EXISTING_TOPIC_JOURNAL_REQUIRED',
            'KAFKA_QUOTA_COMPLETE_TOPICS_ACLS_REQUIRED', 'KAFKA_QUOTA_FOREIGN_JOURNAL_PRESERVED',
            'KAFKA_QUOTA_JOURNAL_READBACK_REQUIRED', 'KAFKA_QUOTA_EXISTING_STATE_CHANGED',
            'KAFKA_QUOTA_FOREIGN_INVENTORY_PRESERVED', 'KAFKA_QUOTA_DRIFT_PRESERVED',
            'KAFKA_QUOTA_UNOWNED_STATE_PRESERVED', 'KAFKA_QUOTA_CONCURRENT_CHANGE_PRESERVED',
            'KAFKA_QUOTA_READBACK_REQUIRED', 'KAFKA_CLIENT_READY_CLOSED_BROKER_REQUIRED',
            'KAFKA_CLIENT_CLOSED_BOUNDARY_REQUIRED', 'ESO_UNSEALED_BAO_REQUIRED',
            'KAFKA_EXACT_ISOLATED_QUOTA_PROFILE_REQUIRED'}
        code = str(error) if str(error) in allowed else 'OTHER_PREFLIGHT_OR_NATIVE_FAILURE'
        print('KAFKA_QUOTA_FAILURE=' + code, file=sys.stderr, flush=True)
        raise


def main():
    return runtime.main(executor=execute, operation='KAFKA_QUOTAS')


if __name__ == '__main__':
    raise SystemExit(main())
