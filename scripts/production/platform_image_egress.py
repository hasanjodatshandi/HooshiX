"""Platform-owned public HTTPS egress for Kyverno image verification only."""

NAME = 'hooshix-image-verifier-https'
LABELS = {'app.kubernetes.io/part-of': 'hooshix-platform',
          'app.kubernetes.io/managed-by': 'hooshix-platform-commissioning'}
# IPv4-only Calico target. Do not grant private, metadata or reserved networks.
EXCLUDED = ('0.0.0.0/8', '10.0.0.0/8', '100.64.0.0/10', '127.0.0.0/8',
            '169.254.0.0/16', '172.16.0.0/12', '192.0.0.0/24', '192.0.2.0/24',
            '192.168.0.0/16', '198.18.0.0/15', '198.51.100.0/24',
            '203.0.113.0/24', '224.0.0.0/4', '240.0.0.0/4')


def candidate():
    # Standard NetworkPolicy is IP/port-based, NOT a DNS allow-list. Registry
    # blob hosts change address; TLS and exact signed artifact identity remain
    # independent mandatory controls. Existing DNS/API/ingress policy is retained.
    return {'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy',
            'metadata': {'name': NAME, 'namespace': 'kyverno', 'labels': dict(LABELS)},
            'spec': {'podSelector': {'matchLabels': {'app.kubernetes.io/part-of': 'kyverno'},
                     'matchExpressions': [{'key': 'app.kubernetes.io/component', 'operator': 'In',
                                          'values': ['admission-controller', 'reports-controller']}]},
                     'policyTypes': ['Egress'], 'egress': [{
                         'ports': [{'port': 443, 'protocol': 'TCP'}],
                         'to': [{'ipBlock': {'cidr': '0.0.0.0/0', 'except': list(EXCLUDED)}}]}]}}
