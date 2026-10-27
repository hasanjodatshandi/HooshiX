"""Exact existing application topic/group ownership; no wildcard or broker administration."""
import re

import kafka_client_tls as clients

COMMAND = 'hooshix.identity.erasure.command.v1'
RECEIPT = 'hooshix.identity.erasure.receipt.v1'
LIFECYCLE = 'hooshix.identity.tenant.lifecycle.v1'
TOPICS = (COMMAND, RECEIPT, LIFECYCLE, *(name + '.DLT' for name in (COMMAND, RECEIPT, LIFECYCLE)))


def topics():
    return {name: {'partitions': 1, 'replication_factor': 1, 'config': {
        'cleanup.policy': 'delete', 'retention.ms': str((14 if name.endswith('.DLT') else 35) * 86400000),
        'min.insync.replicas': '1', 'unclean.leader.election.enable': 'false'}} for name in TOPICS}


def groups(service):
    clients.entry(service)
    short = service.removesuffix('-service')
    result = ['hooshix-' + short + '-erasure-v1']
    if service == 'identity-service':
        result.append('hooshix-identity-erasure-receipt-v1')
    if service == 'conversation-service':
        result.append('hooshix-conversation-tenant-lifecycle-v1')
    return result


def permissions():
    """Tuples mirror Kafka's resource/pattern/principal/host/operation/permission fields."""
    result = set()
    def grant(service, kind, name, operation):
        result.add((kind, name, 'LITERAL', 'User:' + clients.entry(service)['principal'],
                    '*', operation.upper(), 'ALLOW'))
    for service in clients.SERVICES:
        grants = [(COMMAND, 'Read'), (RECEIPT, 'Write'), (COMMAND + '.DLT', 'Write')]
        if service == 'identity-service':
            grants += [(COMMAND, 'Write'), (RECEIPT, 'Read'), (LIFECYCLE, 'Write'),
                       (RECEIPT + '.DLT', 'Write')]
        if service == 'conversation-service':
            grants += [(LIFECYCLE, 'Read'), (LIFECYCLE + '.DLT', 'Write')]
        for name, operation in grants:
            grant(service, 'TOPIC', name, operation)
            grant(service, 'TOPIC', name, 'Describe')
        for group in groups(service):
            grant(service, 'GROUP', group, 'Read')
    return result


def parse_acls(output):
    """Reject unclassified stdout instead of accepting an incomplete ACL inventory."""
    result, resource, entries = set(), None, 0
    for line in output.splitlines():
        line = line.strip()
        if not line:
            continue
        header = re.fullmatch(r'Current ACLs for resource `ResourcePattern\(resourceType=(\w+), '
            r'name=([^,]+), patternType=(\w+)\)`:', line)
        if header:
            if resource and not entries:
                raise ValueError('KAFKA_ACL_INVENTORY_REJECTED')
            resource = header.groups()
            entries = 0
            continue
        entry = re.fullmatch(r'\(principal=([^,]+), host=([^,]+), operation=(\w+), permissionType=(\w+)\)', line)
        if not resource or not entry:
            raise ValueError('KAFKA_ACL_INVENTORY_REJECTED')
        result.add((*resource, *entry.groups()))
        entries += 1
    if resource and not entries:
        raise ValueError('KAFKA_ACL_INVENTORY_REJECTED')
    return result


def topic_matches(output, name):
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if len(lines) != 2:
        return False
    head, partition = lines
    pairs = dict(re.findall(r'([\w.]+)=([^,\s]+)', head))
    return (re.search(r'Topic: ' + re.escape(name) + r'\s', head) is not None
        and re.search(r'PartitionCount: 1\s+ReplicationFactor: 1\s', head) is not None
        and all(pairs.get(k) == v for k, v in topics()[name]['config'].items())
        and re.search(r'Partition: 0\s+Leader: 1\s+Replicas: 1\s+Isr: 1(?:\s|$)', partition) is not None)


def reconcile(command, *, owned):
    """Preflight all resources before writing; reentry only with an existing exact ownership journal."""
    listed = [line.strip() for line in command('topics', '--list').splitlines() if line.strip()]
    present = set(listed)
    if len(present) != len(listed) or not present <= set(TOPICS) | {'__consumer_offsets'}:
        raise ValueError('KAFKA_FOREIGN_TOPICS_PRESERVED')
    existing = parse_acls(command('acls', '--list'))
    desired = permissions()
    if not existing <= desired or not owned and (present or existing):
        raise ValueError('KAFKA_FOREIGN_ACLS_OR_UNOWNED_STATE_PRESERVED')
    for name in sorted(present & set(TOPICS)):
        if not topic_matches(command('topics', '--describe', '--topic', name), name):
            raise ValueError('KAFKA_TOPIC_DRIFT_PRESERVED')
    # Caller persists a create-only journal after the complete no-write preflight.
    yield {'topics': len(present & set(TOPICS)), 'acl_entries': len(existing)}
    for name, spec in topics().items():
        if name not in present:
            command('topics', '--create', '--topic', name, '--partitions', '1', '--replication-factor', '1',
                *[arg for k, v in spec['config'].items() for arg in ('--config', k + '=' + v)])
    pending = desired - existing
    for kind, name, pattern, principal, host, _, permission in sorted(pending):
        operations = sorted({a[5] for a in pending if a[:5] == (kind, name, pattern, principal, host)})
        if not operations:
            continue
        command('acls', '--add', '--allow-principal', principal, '--allow-host', host,
            '--resource-pattern-type', 'literal', '--' + kind.lower(), name,
            *[arg for op in operations for arg in ('--operation', op.title())])
        pending = {a for a in pending if a[:5] != (kind, name, pattern, principal, host)}
    if {line.strip() for line in command('topics', '--list').splitlines() if line.strip()} \
            - {'__consumer_offsets'} != set(TOPICS):
        raise ValueError('KAFKA_TOPIC_READBACK_REQUIRED')
    if any(not topic_matches(command('topics', '--describe', '--topic', name), name) for name in TOPICS):
        raise ValueError('KAFKA_TOPIC_READBACK_REQUIRED')
    if parse_acls(command('acls', '--list')) != desired:
        raise ValueError('KAFKA_ACL_READBACK_REQUIRED')
    yield {'schema_version': 1, 'kafka_topic_acl': 'Passed', 'topic_count': 6,
        'credential_count': 5, 'group_count': 7, 'acl_entries': len(desired),
        'application_ingress': 'CLOSED', 'public_access': 'CLOSED',
        'quotas': 'Not run', 'capacity': 'Not verified', 'production_readiness': 'Not verified'}
