"""Closed namespace-local Redis connection projection from existing service credentials."""
import openbao_scoped_auth as auth
import redis_secret_delivery as source

NAMESPACE = auth.NAMESPACE
MANAGER = 'hooshix-application-redis-delivery'
HOST = 'security-redis.platform-data.svc.cluster.local:6379'
ENTRIES = {
    'authorization-quota-redis': ('authorization-service', 'security-redis-authorization', 'AUTHORIZATION_QUOTA_REDIS_URI'),
    'identity-quota-redis': ('identity-service', 'security-redis-identity', 'quota_redis_uri'),
    'web-bff-redis': ('web-bff', 'security-redis-webbff', 'WEB_BFF_REDIS_URI'),
}


def external(name):
    if name not in ENTRIES:
        raise ValueError('REDIS_APP_FIXED_IDENTITY_REQUIRED')
    service, source_name, property_name = ENTRIES[name]
    item = source.entry(source_name)
    value = auth.resource('external-secrets.io/v1', 'ExternalSecret', name, spec={
        'refreshInterval': '1m', 'secretStoreRef': {'kind': 'SecretStore', 'name': 'hooshix-' + service},
        'target': {'name': name, 'creationPolicy': 'Owner', 'deletionPolicy': 'Retain',
            'template': {'engineVersion': 'v2', 'type': 'Opaque',
                'metadata': {'labels': {'app.kubernetes.io/part-of': 'hooshix-platform',
                                        'app.kubernetes.io/managed-by': MANAGER}},
                'data': {property_name: '{{ printf "rediss://%s:%s@' + HOST + '" .username .password }}'}}},
        'data': [{'secretKey': field, 'remoteRef': {'key': item['key'], 'property': field}}
                 for field in ('username', 'password')]})
    value['metadata']['labels']['app.kubernetes.io/managed-by'] = MANAGER
    return value
