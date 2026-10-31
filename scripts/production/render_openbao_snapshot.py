"""Render the private hourly OpenBao snapshot job; no secret values are accepted."""
from __future__ import annotations

import argparse
import json
import re
import sys

NAMESPACE = 'hooshix-secrets'
REPOSITORY = 'ghcr.io/hasanjodatshandi/hooshix/platform-openbao-snapshot-private'
PROVIDER_IP = '178.239.158.123/32'
SA = 'openbao-snapshot'
LABELS = {'app.kubernetes.io/name': 'openbao-snapshot',
          'app.kubernetes.io/part-of': 'hooshix-platform',
          'app.kubernetes.io/managed-by': 'hooshix-openbao-snapshot'}


def resource(kind, name, spec, api='v1'):
    return {'apiVersion': api, 'kind': kind,
            'metadata': {'name': name, 'namespace': NAMESPACE, 'labels': LABELS.copy()},
            'spec': spec}


def candidate(image):
    if (not isinstance(image, str) or not image.startswith(REPOSITORY + '@sha256:')
            or re.fullmatch(re.escape(REPOSITORY) + r'@sha256:[a-f0-9]{64}', image) is None):
        raise ValueError('private immutable snapshot image required')
    job_labels = {'app.kubernetes.io/name': 'openbao-snapshot'}
    resources = [
        {'apiVersion': 'v1', 'kind': 'ServiceAccount',
         'metadata': {'name': SA, 'namespace': NAMESPACE, 'labels': LABELS.copy()},
         'automountServiceAccountToken': False},
        resource('NetworkPolicy', 'openbao-snapshot', {
            'podSelector': {'matchLabels': job_labels},
            'policyTypes': ['Ingress', 'Egress'], 'ingress': [],
            'egress': [
                {'to': [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': 'kube-system'}},
                         'podSelector': {'matchLabels': {'k8s-app': 'kube-dns'}}}],
                 'ports': [{'protocol': 'UDP', 'port': 53}, {'protocol': 'TCP', 'port': 53}]},
                {'to': [{'podSelector': {'matchLabels': {'app.kubernetes.io/name': 'openbao'}}}],
                 'ports': [{'protocol': 'TCP', 'port': 8200}]},
                {'to': [{'ipBlock': {'cidr': PROVIDER_IP}}],
                 'ports': [{'protocol': 'TCP', 'port': 443}]},
            ]}),
        resource('AuthorizationPolicy', 'openbao-snapshot', {
            'selector': {'matchLabels': {'app.kubernetes.io/name': 'openbao'}},
            'action': 'ALLOW', 'rules': [{'from': [{'source': {
                'principals': ['prod.sajtech.internal/ns/hooshix-secrets/sa/' + SA]}}],
                'to': [{'operation': {'ports': ['8200']}}]}]}, 'security.istio.io/v1'),
        {'apiVersion': 'batch/v1', 'kind': 'CronJob',
         'metadata': {'name': 'openbao-snapshot', 'namespace': NAMESPACE, 'labels': LABELS.copy()},
         'spec': {'schedule': '0 * * * *', 'timeZone': 'Etc/UTC', 'concurrencyPolicy': 'Forbid',
                  'startingDeadlineSeconds': 300, 'successfulJobsHistoryLimit': 1,
                  'failedJobsHistoryLimit': 2, 'suspend': False,
                  'jobTemplate': {'metadata': {'labels': LABELS.copy()}, 'spec': {
                      'backoffLimit': 0, 'activeDeadlineSeconds': 900,
                      'ttlSecondsAfterFinished': 604800,
                      'template': {'metadata': {'labels': LABELS.copy()}, 'spec': {
                          'serviceAccountName': SA, 'automountServiceAccountToken': False,
                          'restartPolicy': 'Never', 'terminationGracePeriodSeconds': 15,
                          'securityContext': {'fsGroup': 10001, 'runAsNonRoot': True, 'runAsUser': 10001,
                              'runAsGroup': 10001, 'seccompProfile': {'type': 'RuntimeDefault'}},
                          'containers': [{'name': 'snapshot', 'image': image,
                              'imagePullPolicy': 'IfNotPresent',
                              'command': ['/usr/local/bin/python3', '-I', '/app/openbao_snapshot_agent.py'],
                              'env': [{'name': 'PYTHONDONTWRITEBYTECODE', 'value': '1'},
                                      {'name': 'TMPDIR', 'value': '/tmp'}],
                              'securityContext': {'allowPrivilegeEscalation': False,
                                  'readOnlyRootFilesystem': True, 'capabilities': {'drop': ['ALL']}},
                              'resources': {'requests': {'cpu': '100m', 'memory': '128Mi'},
                                            'limits': {'cpu': '500m', 'memory': '512Mi'}},
                              'volumeMounts': [
                                  {'name': 'tmp', 'mountPath': '/tmp'},
                                  {'name': 'ca', 'mountPath': '/var/run/hooshix/ca', 'readOnly': True},
                                  {'name': 'identity', 'mountPath': '/var/run/hooshix/token', 'readOnly': True}]}],
                          'volumes': [
                              {'name': 'tmp', 'emptyDir': {'medium': 'Memory', 'sizeLimit': '64Mi'}},
                              {'name': 'ca', 'secret': {'secretName': 'openbao-server-tls', 'defaultMode': 288,
                                  'items': [{'key': 'ca.crt', 'path': 'ca.crt'}]}},
                              {'name': 'identity', 'projected': {'defaultMode': 256, 'sources': [
                                  {'serviceAccountToken': {'path': 'token', 'audience': 'hooshix-openbao',
                                                           'expirationSeconds': 600}}]}}
                          ]}}}}}}
    ]
    return resources


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    args = parser.parse_args()
    try:
        output = candidate(args.image)
        sys.stdout.write('\n---\n'.join(json.dumps(item, sort_keys=True, indent=2)
                                      for item in output) + '\n')
    except (OSError, ValueError, TypeError, KeyError):
        parser.exit(1, 'OPENBAO_SNAPSHOT_CANDIDATE=Failed; no resource output\n')


if __name__ == '__main__':
    main()
