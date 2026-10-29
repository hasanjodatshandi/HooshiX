"""Owner-approved isolated bandwidth profile; not a target installer or capacity claim."""
import json
from pathlib import Path

import kafka_client_tls as clients

PATH = Path(__file__).resolve().parents[2] / 'infrastructure/production/kafka/quota-profile.json'


def load(path=PATH):
    value = json.loads(path.read_bytes())
    expected = {'schema_version': 1, 'profile': 'isolated-low-load-v1',
        'scope': 'authenticated-user', 'producer_byte_rate': 65536, 'consumer_byte_rate': 262144,
        'target_installation': 'Not run', 'production_capacity': 'Not verified',
        'application_ingress': 'CLOSED', 'public_access': 'CLOSED'}
    if value != expected or any(type(value[k]) is not int
            for k in ('schema_version', 'producer_byte_rate', 'consumer_byte_rate')):
        raise ValueError('KAFKA_EXACT_ISOLATED_QUOTA_PROFILE_REQUIRED')
    return {clients.entry(service)['principal']: {key: value[key]
        for key in ('producer_byte_rate', 'consumer_byte_rate')} for service in clients.SERVICES}
