"""Supervised create-only Kafka topics/ACLs on the existing closed broker; never open application traffic."""
import base64
import hashlib
import json
import sys

import install_kafka_client_tls as delivery
import kafka_topic_acl as contract

runtime = delivery.runtime
require = runtime.require
MANAGER, JOURNAL = 'hooshix-kafka-topic-acl', 'hooshix-kafka-topic-acl-v1'


def command(kube, tool, *args):
    require(tool in ('topics', 'acls'), 'KAFKA_TOPIC_FIXED_TOOL_REQUIRED')
    return kube('-n', runtime.NS, 'exec', 'kafka-0', '-c', 'kafka', '--',
        '/usr/bin/timeout', '-s', 'KILL', '24', '/usr/bin/env',
        'KAFKA_HEAP_OPTS=-Xms32m -Xmx128m',
        'KAFKA_JVM_PERFORMANCE_OPTS=' + runtime.TOOL_JVM_OPTIONS,
        '/opt/kafka/bin/kafka-' + tool + '.sh', '--bootstrap-server', '127.0.0.1:9092',
        '--command-config', '/etc/kafka/commissioned/client.properties', *args,
        timeout=40).decode()


def credential_snapshot(kube, ca):
    result = []
    for service in contract.clients.SERVICES:
        external = contract.clients.external(service)
        name = external['metadata']['name']
        current = runtime.get(kube, 'externalsecret', name, contract.clients.NAMESPACE)
        secret = runtime.get(kube, 'secret', name, contract.clients.NAMESPACE)
        require(current and secret and delivery.ready(current)
            and delivery.desired_fields_match(current, external)
            and secret.get('type') == 'Opaque'
            and secret['metadata'].get('ownerReferences') == contract.clients.owner(current)
            and secret['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == contract.clients.MANAGER,
            'KAFKA_TOPIC_EXISTING_CLIENT_DELIVERY_REQUIRED')
        values = {k: base64.b64decode(v, validate=True).decode() for k, v in secret['data'].items()}
        root = values[contract.clients.FIELDS['ca.crt']]
        require(hashlib.sha256(root.encode()).hexdigest() == ca, 'KAFKA_TOPIC_EXISTING_ROOT_REQUIRED')
        chain = contract.clients.platform.ca_import.pem_certificate_blocks(
            values[contract.clients.FIELDS['tls.crt']].encode())
        require(len(chain) == 2, 'KAFKA_TOPIC_EXISTING_CHAIN_REQUIRED')
        contract.clients.validate(service, values, root, chain[1].decode())
        result.append((secret['metadata']['uid'], secret['data'], current['metadata']['uid'], current['spec']))
    return result


def _execute(plan, *, kube=runtime.host.kube, guard=runtime.volume.foundation):
    require(plan.get('kafka_topic_acl') is True and plan.get('schema_version') == 1
        and plan.get('installation_id') == 'hooshix-production'
        and plan.get('public_access') == plan.get('application_ingress') == 'CLOSED',
        'KAFKA_TOPIC_CLOSED_PLAN_REQUIRED')
    units = guard(initialized=True)
    before = delivery.snapshot(kube, plan)
    credentials = credential_snapshot(kube, plan['public_ca_sha256'])
    _, cluster = runtime.storage_identity(kube)
    sts = runtime.get(kube, 'statefulset', 'kafka', runtime.NS)
    pvc = runtime.get(kube, 'pvc', runtime.volume.CLAIM, runtime.NS)
    fingerprint = hashlib.sha256(json.dumps({'topics': contract.topics(),
        'acls': sorted(contract.permissions())}, sort_keys=True).encode()).hexdigest()
    journal = {'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': JOURNAL,
        'namespace': runtime.NS, 'labels': {'app.kubernetes.io/managed-by': MANAGER}},
        'immutable': True, 'data': {'contract_sha256': fingerprint, 'cluster_identity_sha256': cluster,
            'statefulset_uid': sts['metadata']['uid'], 'pvc_uid': pvc['metadata']['uid']}}
    old = runtime.get(kube, 'configmap', JOURNAL, runtime.NS)
    require(not old or old.get('immutable') is True and old.get('data') == journal['data']
        and old['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == MANAGER
        and not old['metadata'].get('deletionTimestamp') and not old['metadata'].get('ownerReferences'),
        'KAFKA_TOPIC_FOREIGN_JOURNAL_PRESERVED')
    reconciliation = contract.reconcile(lambda *args: command(kube, *args), owned=old is not None)
    next(reconciliation)  # Complete global no-write preflight before the ownership journal.
    if old is None:
        kube('create', '-f', '-', body=journal)
    current = runtime.get(kube, 'configmap', JOURNAL, runtime.NS)
    require(current and delivery.desired_fields_match(current, journal), 'KAFKA_TOPIC_JOURNAL_READBACK_REQUIRED')
    result = next(reconciliation)
    require(before == delivery.snapshot(kube, plan)
        and credentials == credential_snapshot(kube, plan['public_ca_sha256'])
        and cluster == runtime.storage_identity(kube)[1]
        and guard(initialized=True) == units, 'KAFKA_TOPIC_EXISTING_STATE_CHANGED')
    return {**result, 'existing_state': 'Preserved', 'contract_sha256': fingerprint,
            'cluster_identity_sha256': cluster, 'pvc_uid': pvc['metadata']['uid'],
            'source_revision': plan['repository_source_revision']}


def execute(plan, *, kube=runtime.host.kube, guard=runtime.volume.foundation):
    try:
        return _execute(plan, kube=kube, guard=guard)
    except (ValueError, contract.clients.custody.BootstrapFailed) as error:
        allowed = {'KAFKA_TOPIC_CLOSED_PLAN_REQUIRED', 'KAFKA_TOPIC_EXISTING_CLIENT_DELIVERY_REQUIRED',
            'KAFKA_TOPIC_EXISTING_ROOT_REQUIRED', 'KAFKA_TOPIC_EXISTING_CHAIN_REQUIRED',
            'KAFKA_TOPIC_FOREIGN_JOURNAL_PRESERVED', 'KAFKA_TOPIC_JOURNAL_READBACK_REQUIRED',
            'KAFKA_TOPIC_EXISTING_STATE_CHANGED', 'KAFKA_FOREIGN_TOPICS_PRESERVED',
            'KAFKA_FOREIGN_ACLS_OR_UNOWNED_STATE_PRESERVED', 'KAFKA_TOPIC_DRIFT_PRESERVED',
            'KAFKA_TOPIC_READBACK_REQUIRED', 'KAFKA_ACL_READBACK_REQUIRED', 'KAFKA_ACL_INVENTORY_REJECTED',
            'ESO_UNSEALED_BAO_REQUIRED', 'KAFKA_CLIENT_READY_CLOSED_BROKER_REQUIRED',
            'KAFKA_CLIENT_CLOSED_BOUNDARY_REQUIRED', 'KAFKA_CLIENT_EXISTING_STORE_REQUIRED'}
        code = str(error) if str(error) in allowed else 'OTHER_PREFLIGHT_OR_NATIVE_FAILURE'
        print('KAFKA_TOPIC_ACL_FAILURE=' + code, file=sys.stderr, flush=True)
        raise


def main():
    return runtime.main(executor=execute, operation='KAFKA_TOPIC_ACL')


if __name__ == '__main__':
    raise SystemExit(main())
