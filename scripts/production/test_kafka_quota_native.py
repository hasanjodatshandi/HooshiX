"""Real bandwidth throttling on disposable network-none Kafka; never contacts the VPS."""
import argparse
import json
import re
import subprocess
from pathlib import Path

import kafka_quota_profile as profile
from rehearse_redis_tls import fixture_issuer, synthetic_memory
from test_kafka_tls_acl_native import MOUNT, client_config, docker, exercise, require

SOURCE = Path(__file__).with_name('fixtures') / 'KafkaQuotaProbe.java'


def verify(container, work, values, docker_host):
    desired = profile.load()
    names = list(profile.clients.SERVICES)
    require(set(values) == set(names), 'FIVE_SYNTHETIC_QUOTA_IDENTITIES_REQUIRED')
    # Both certificates and compiled fixtures remain in the existing disposable tmpfs.
    for service in names:
        material = values[service]
        pem = work / (service + '.pem')
        pem.write_text(material[profile.clients.FIELDS['tls.key']]
                       + material[profile.clients.FIELDS['tls.crt']])
        pem.chmod(0o600)
        config = work / (service + '.properties')
        config.write_text(client_config(service))
        config.chmod(0o600)
    jar = work / 'kafka-clients.jar'
    copied = docker('cp', container + ':/opt/kafka/libs/kafka-clients-4.2.2.jar', str(jar),
                    timeout=15, docker_host=docker_host)
    require(copied.returncode == 0, 'PINNED_KAFKA_CLIENT_FIXTURE_REQUIRED')
    compiled = subprocess.run(['javac', '--release', '21', '-cp', str(jar), '-d', str(work), str(SOURCE)],
                              capture_output=True, timeout=30, check=False)
    require(compiled.returncode == 0, 'KAFKA_QUOTA_FIXTURE_COMPILE_FAILED')
    rates = desired[profile.clients.entry(names[0])['principal']]
    result = docker('exec', container, 'java', '-Xms32m', '-Xmx192m', '-XX:+UseSerialGC',
        '-XX:TieredStopAtLevel=1', '-cp', MOUNT + ':/opt/kafka/libs/*', 'KafkaQuotaProbe',
        str(rates['producer_byte_rate']), str(rates['consumer_byte_rate']), *names,
        timeout=420, docker_host=docker_host)
    failures = ('EXACT_QUOTA_PROFILE_REQUIRED', 'PRODUCER_QUOTA_NOT_OBSERVED',
        'SYNTHETIC_PAYLOAD_MISMATCH', 'CONSUMER_QUOTA_NOT_OBSERVED',
        'EMPTY_DISPOSABLE_QUOTA_STATE_REQUIRED', 'EXACT_USER_QUOTA_READBACK_REQUIRED',
        'CLIENT_QUOTA_MUTATION_ALLOWED', 'WRONG_QUOTA_DENIAL', 'QUOTA_CLIENT_CLEANUP_FAILED',
        'USER_QUOTAS_CHANGED_BY_CLIENTS')
    code = next((code for code in failures if code in result.stderr), 'KAFKA_QUOTA_NATIVE_PROOF_FAILED')
    require(result.returncode == 0 and result.stdout.strip() == 'KAFKA_QUOTA_PROOF=Passed', code)
    return {'schema_version': 1, 'profile': 'isolated-low-load-v1', 'identities': len(desired),
        **rates, 'principal_quota_readback': 'Passed', 'produce_fetch_throttling': 'Passed',
        'two_client_ids_per_principal': 'Passed', 'quota_mutation_denial': 'Passed',
        'target_installation': 'Not run', 'production_capacity': 'Not verified',
        'application_ingress': 'CLOSED', 'public_access': 'CLOSED'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--disposable', required=True, action='store_true')
    p.parse_args()
    try:
        profile.load()
        endpoint = docker('context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}')
        require(endpoint.returncode == 0, 'LOCAL_DOCKER_REQUIRED')
        quota_evidence = {}
        def check(container, work):
            ca = (work / 'ca.crt').read_text()
            with synthetic_memory(ca), fixture_issuer(ca, work / 'ca.key') as (issuer, key, password):
                values = {service: profile.clients.issue(service, ca, issuer, key, password)
                          for service in profile.clients.SERVICES}
                quota_evidence.update(verify(container, work, values, json.loads(endpoint.stdout)))
        receipt = exercise(json.loads(endpoint.stdout), client_tls_check=check)
        receipt['isolated_quota_profile'] = quota_evidence
    except (RuntimeError, OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        code = str(error) if re.fullmatch(r'[A-Z][A-Z0-9_]{1,120}', str(error)) else type(error).__name__
        p.exit(1, 'KAFKA_QUOTA_PROFILE=Failed; reason=' + code + '; no VPS changes; raw errors suppressed\n')
    print(json.dumps(receipt, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
