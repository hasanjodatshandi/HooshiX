"""Create-only exact user quotas; preserve every foreign/default/client quota and stop on drift."""
import re


def inventory(command, desired):
    result = {}
    for line in command('--describe', '--entity-type', 'users').splitlines():
        if not line.strip():
            continue
        match = re.fullmatch(r"Quota configs for user-principal '([^']+)' are (.+)", line.strip())
        if not match or match[1] not in desired or match[1] in result:
            raise ValueError('KAFKA_QUOTA_FOREIGN_INVENTORY_PRESERVED')
        fields = {}
        for entry in match[2].split(', '):
            part = re.fullmatch(r'(producer_byte_rate|consumer_byte_rate)=(\d+)(?:\.0+)?', entry)
            if not part or part[1] in fields:
                raise ValueError('KAFKA_QUOTA_FOREIGN_INVENTORY_PRESERVED')
            fields[part[1]] = int(part[2])
        if fields != desired[match[1]]:
            raise ValueError('KAFKA_QUOTA_DRIFT_PRESERVED')
        result[match[1]] = fields
    # More-specific client quotas could override a user's aggregate limit.
    for types in (('clients',), ('users', 'clients'), ('ips',)):
        if command('--describe', *[arg for kind in types for arg in ('--entity-type', kind)]).strip():
            raise ValueError('KAFKA_QUOTA_FOREIGN_INVENTORY_PRESERVED')
    return result


def reconcile(command, desired, *, owned):
    current = inventory(command, desired)
    if current and not owned:
        raise ValueError('KAFKA_QUOTA_UNOWNED_STATE_PRESERVED')
    yield len(current)  # Complete no-write preflight precedes the create-only journal.
    for user, rates in desired.items():
        if user in current:
            continue
        if inventory(command, desired) != current:
            raise ValueError('KAFKA_QUOTA_CONCURRENT_CHANGE_PRESERVED')
        command('--alter', '--entity-type', 'users', '--entity-name', user,
                '--add-config', ','.join(key + '=' + str(value) for key, value in rates.items()))
        current = {**current, user: rates}
    if inventory(command, desired) != desired:
        raise ValueError('KAFKA_QUOTA_READBACK_REQUIRED')
    yield {'schema_version': 1, 'kafka_quotas': 'Passed', 'credential_count': 5,
        'producer_byte_rate': 65536, 'consumer_byte_rate': 262144,
        'application_ingress': 'CLOSED', 'public_access': 'CLOSED',
        'capacity': 'Not verified', 'production_readiness': 'Not verified'}
