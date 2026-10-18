"""Existing supervised root/custody transport for bounded Redis secret delivery only."""
import json
import bootstrap_postgresql_secrets as pg
import commission_platform as platform
import install_redis_secrets as installer
import install_redis_local_volume as volume
import redis_secret_delivery as contract


def token_request(namespace, account, audiences):
    pg.host.require((namespace == contract.NAMESPACE
        and account in {e['account'] for e in contract.AUTH_ENTRIES.values()}
        or (namespace, account) == ('default', 'default')), 'REDIS_SECRET_TOKENREQUEST_BOUNDARY')
    pg.host.require(isinstance(audiences, list) and len(audiences) <= 5
        and all(isinstance(a, str) and pg.re.fullmatch(r'[a-zA-Z0-9:/_.-]{1,256}', a) for a in audiences),
        'REDIS_SECRET_AUDIENCE_BOUNDARY')
    return pg.kube('-n', namespace, 'create', 'token', account, '--duration=10m',
                   *['--audience=' + a for a in audiences]).decode().strip()


def execute(plan, **kwargs):
    units = volume.foundation()
    ordinary = kwargs['kube']

    def kube(*args, **options):
        # Only this fixed public policy gets the larger budget, never Secrets or arbitrary lists.
        if args == ('get', 'validatingpolicy', 'hooshix-platform-data-closed', '-o', 'json'):
            return platform.kube(*args, output_bound=512 * 1024, timeout=30)
        return ordinary(*args, **options)

    kwargs['kube'] = kube
    reserved = []
    for obj in volume.candidate():
        value = json.loads(kube('get', obj['kind'], obj['metadata']['name'], '-o', 'json'))
        pg.host.require(volume.matches(value, obj), 'REDIS_SECRET_REVIEWED_VOLUME_REQUIRED')
        reserved.append(value)
    pg.host.require(not kube('-n', contract.NAMESPACE, 'get', 'pvc', volume.CLAIM,
        '--ignore-not-found', '-o', 'json').strip(), 'REDIS_SECRET_EXISTING_RUNTIME_PRESERVED')
    result = installer.execute(plan, **kwargs)
    pg.host.require(volume.foundation() == units, 'REDIS_SECRET_FOUNDATION_CHANGED')
    for obj, old in zip(volume.candidate(), reserved, strict=True):
        value = json.loads(kube('get', obj['kind'], obj['metadata']['name'], '-o', 'json'))
        pg.host.require(volume.matches(value, obj) and value['metadata']['uid'] == old['metadata']['uid'],
                        'REDIS_SECRET_VOLUME_CHANGED')
    return result


def commission(request, revision):
    result = pg.commission(request, revision, execute=execute, token_requester=token_request, audit_label='Redis')
    result['root_mesh_openbao_application_eso_cnpg_barman_storage'] = 'Preserved'
    return result


def main(revision):
    return pg.main(revision, commissioner=commission)
