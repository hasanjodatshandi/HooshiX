"""CI-only static Kafka volume proof; no mesh, secrets, broker or production target."""
import argparse
import hashlib
import json
import os
import re
import tempfile
import uuid
from datetime import datetime, timezone
from pathlib import Path

import install_kafka_local_volume as volume
import rehearse_openbao_kubernetes as fixture
from rehearse_postgresql_local_volume import exercise
from verify_published_postgresql import candidate


def rehearse(tools, receipt):
    fixture.runner_paths(tools, receipt)
    pins = fixture.pins()
    if not re.fullmatch(r'kindest/node:v' + re.escape(pins['KUBERNETES_VERSION']) + r'@sha256:[a-f0-9]{64}',
                        pins['KIND_NODE_IMAGE']):
        raise ValueError('PINNED_VOLUME_FIXTURE_NODE_REQUIRED')
    for tool, key in (('kind', 'KIND_LINUX_AMD64_SHA256'), ('kubectl', 'KUBECTL_LINUX_AMD64_SHA256')):
        binary = tools / tool
        if binary.is_symlink() or hashlib.sha256(binary.read_bytes()).hexdigest() != pins[key]:
            raise ValueError('PINNED_VOLUME_FIXTURE_TOOLS_REQUIRED')
    kind, kubectl = str(tools / 'kind'), str(tools / 'kubectl')
    image = candidate()['publication']['images']['postgresql']
    name = 'hooshix-kafka-volume-' + uuid.uuid4().hex[:12]
    result, started = None, False
    with tempfile.TemporaryDirectory(prefix='hooshix-kafka-volume-', dir=os.environ['RUNNER_TEMP']) as directory:
        kubeconfig = str(Path(directory) / 'kubeconfig')
        def kube(*args, data=None, timeout=30):
            return fixture.run([kubectl, '--kubeconfig', kubeconfig, '--context', 'kind-' + name,
                '--request-timeout=15s', *args], data=data, timeout=timeout)
        try:
            if name in fixture.run([kind, 'get', 'clusters']).decode().splitlines():
                raise ValueError('EXISTING_CLUSTER_MUST_NOT_BE_REUSED')
            started = True
            fixture.run([kind, 'create', 'cluster', '--name', name, '--image', pins['KIND_NODE_IMAGE'],
                '--kubeconfig', kubeconfig, '--wait', '120s'], timeout=240)
            Path(kubeconfig).chmod(0o600)
            server = json.loads(kube('version', '-o', 'json'))['serverVersion']['gitVersion']
            if server != 'v' + pins['KUBERNETES_VERSION']:
                raise ValueError('PINNED_VOLUME_FIXTURE_API_REQUIRED')
            result = exercise(kube, fixture.run, fixture.wait, name + '-control-plane', image,
                              module=volume, runtime_uid=10001, profile='kafka')
        finally:
            if started:
                fixture.run([kind, 'delete', 'cluster', '--name', name, '--kubeconfig', kubeconfig], timeout=120)
                if name in fixture.run([kind, 'get', 'clusters']).decode().splitlines():
                    raise ValueError('OWNED_VOLUME_FIXTURE_CLEANUP_REQUIRED')
    evidence = {'schema_version': 1, 'scope': 'Disposable static Kafka LocalPV only; no broker/TLS/mesh',
        'revision': fixture.run(['git', '-C', str(fixture.ROOT), 'rev-parse', 'HEAD']).decode().strip(),
        'observed_at': datetime.now(timezone.utc).isoformat(), 'kafka_local_volume': result,
        'checks': {'cleanup': 'Passed'}, 'production_readiness': 'Not verified'}
    with receipt.open('x', encoding='utf-8') as output:
        json.dump(evidence, output, indent=2)
        output.write('\n')
    receipt.chmod(0o600)
    print('KAFKA_LOCAL_VOLUME_NATIVE=Passed; public evidence only; target Not run')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ci', required=True, action='store_true')
    parser.add_argument('--tools-dir', required=True, type=Path)
    parser.add_argument('--receipt', required=True, type=Path)
    args = parser.parse_args()
    rehearse(args.tools_dir, args.receipt)
