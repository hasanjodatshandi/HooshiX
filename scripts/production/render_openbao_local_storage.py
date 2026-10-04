"""Render fixed, review-only single-node OpenBao local PV/StorageClass; no apply."""
import argparse
import json

STORAGE_CLASS = 'hooshix-openbao-local'
NODE = 'hooshix-production-1'
PV_NAME = 'hooshix-openbao-local-8gib'


def candidate():
    annotation = {'argocd.argoproj.io/sync-options': 'Prune=confirm'}
    return {'apiVersion': 'v1', 'kind': 'List', 'items': [
        {'apiVersion': 'storage.k8s.io/v1', 'kind': 'StorageClass',
         'metadata': {'name': STORAGE_CLASS, 'annotations': annotation.copy()},
         'provisioner': 'kubernetes.io/no-provisioner', 'reclaimPolicy': 'Retain',
         'volumeBindingMode': 'WaitForFirstConsumer', 'allowVolumeExpansion': False},
        {'apiVersion': 'v1', 'kind': 'PersistentVolume',
         'metadata': {'name': PV_NAME, 'annotations': annotation.copy()},
         'spec': {'capacity': {'storage': '8Gi'}, 'volumeMode': 'Filesystem',
                  'accessModes': ['ReadWriteOnce'], 'persistentVolumeReclaimPolicy': 'Retain',
                  'storageClassName': STORAGE_CLASS,
                  'local': {'path': '/var/lib/hooshixstorage/openbao/data'},
                  'claimRef': {'namespace': 'hooshix-secrets', 'name': 'data-openbao-0'},
                  'nodeAffinity': {'required': {'nodeSelectorTerms': [{'matchExpressions': [
                      {'key': 'kubernetes.io/hostname', 'operator': 'In', 'values': [NODE]}]}]}}}}]}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', action='store_true', required=True)
    parser.parse_args()
    print(json.dumps(candidate(), sort_keys=True, indent=2))
