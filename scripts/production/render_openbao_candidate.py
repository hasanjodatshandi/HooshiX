#!/usr/bin/env python3
"""Render public, isolated OpenBao foundation resources; never authorize promotion."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SECRETS = ROOT / "infrastructure/production/secrets"
NAMESPACE = "hooshix-secrets"
LABELS = {"app.kubernetes.io/name": "openbao"}
STATUS = "bao status -format=json >/dev/null 2>&1"
ALIVE = STATUS + "; code=$?; test \"$code\" -eq 0 || test \"$code\" -eq 2"


def candidate(storage_class: str) -> dict:
    # No default storage class: the target's persistence/backup semantics need review.
    if (len(storage_class) > 253 or not all(
            re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label)
            for label in storage_class.split("."))):
        raise ValueError("reviewed storage class required")
    pin = json.loads((SECRETS / "openbao-image.json").read_text())
    if (pin["version"] != "2.6.4" or not re.fullmatch(
            r"ghcr\.io/openbao/openbao-ubi@sha256:[a-f0-9]{64}", pin["image"]) or
            pin["production_promotion"] != "blocked-until-supply-chain-staging-and-recovery-evidence"):
        raise ValueError("reviewed image pin required")
    config = json.loads((SECRETS / "openbao-server.json").read_text())

    def resource(api: str, kind: str, name: str, **body) -> dict:
        return {"apiVersion": api, "kind": kind,
                "metadata": {"name": name, "namespace": NAMESPACE}, **body}

    def probe(command: str, period: int, failures: int) -> dict:
        return {"exec": {"command": ["/bin/sh", "-c", command]},
                "timeoutSeconds": 5, "periodSeconds": period, "failureThreshold": failures}

    namespace = {"apiVersion": "v1", "kind": "Namespace", "metadata": {
        "name": NAMESPACE, "annotations": {"argocd.argoproj.io/sync-options": "Prune=confirm"},
        "labels": {"istio.io/dataplane-mode": "ambient", "pod-security.kubernetes.io/enforce": "restricted",
                   "pod-security.kubernetes.io/enforce-version": "v1.35"}}}
    account = resource("v1", "ServiceAccount", "openbao", automountServiceAccountToken=False)
    config_map = resource("v1", "ConfigMap", "openbao-config",
                          data={"server.json": json.dumps(config, sort_keys=True, indent=2) + "\n"})
    service = resource("v1", "Service", "openbao", spec={
        "type": "ClusterIP", "clusterIP": "None", "publishNotReadyAddresses": True,
        "selector": LABELS, "ports": [{"name": "https-api", "port": 8200, "targetPort": 8200},
                                       {"name": "https-raft", "port": 8201, "targetPort": 8201}]})
    container = {"name": "openbao", "image": pin["image"], "imagePullPolicy": "IfNotPresent",
        # Bypass the upstream entrypoint's development/chown behavior.
        "command": ["bao"], "args": ["server", "-config=/openbao/config/server.json"],
        "env": [{"name": "BAO_ADDR", "value": "https://127.0.0.1:8200"},
                {"name": "BAO_CACERT", "value": "/openbao/tls/ca.crt"},
                {"name": "BAO_CLIENT_TIMEOUT", "value": "3s"},
                {"name": "BAO_MAX_RETRIES", "value": "0"},
                {"name": "HOME", "value": "/tmp"}],
        "ports": [{"name": "https-api", "containerPort": 8200},
                  {"name": "https-raft", "containerPort": 8201}],
        "securityContext": {"allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True,
                            "capabilities": {"drop": ["ALL"]}},
        "resources": {"requests": {"cpu": "250m", "memory": "256Mi", "ephemeral-storage": "32Mi"},
                      "limits": {"cpu": "1", "memory": "512Mi", "ephemeral-storage": "64Mi"}},
        "startupProbe": probe(ALIVE, 5, 60), "livenessProbe": probe(ALIVE, 15, 3),
        "readinessProbe": probe(STATUS, 5, 2),
        "volumeMounts": [{"name": "config", "mountPath": "/openbao/config", "readOnly": True},
                         {"name": "tls", "mountPath": "/openbao/tls", "readOnly": True},
                         {"name": "data", "mountPath": "/openbao/data"},
                         {"name": "tmp", "mountPath": "/tmp"}]}
    workload = resource("apps/v1", "StatefulSet", "openbao", spec={
        "serviceName": "openbao", "replicas": 1, "selector": {"matchLabels": LABELS},
        "updateStrategy": {"type": "OnDelete"},
        "persistentVolumeClaimRetentionPolicy": {"whenDeleted": "Retain", "whenScaled": "Retain"},
        "template": {"metadata": {"labels": LABELS}, "spec": {
            "serviceAccountName": "openbao", "automountServiceAccountToken": False,
            "terminationGracePeriodSeconds": 30,
            "securityContext": {"runAsNonRoot": True, "runAsUser": 10001, "runAsGroup": 10001,
                                "fsGroup": 10001, "fsGroupChangePolicy": "OnRootMismatch",
                                "seccompProfile": {"type": "RuntimeDefault"}},
            "containers": [container], "volumes": [
                {"name": "config", "configMap": {"name": "openbao-config", "defaultMode": 292}},
                {"name": "tls", "secret": {"secretName": "openbao-server-tls", "defaultMode": 288,
                    "items": [{"key": key, "path": key} for key in ("tls.crt", "tls.key", "ca.crt")]}},
                {"name": "tmp", "emptyDir": {"medium": "Memory", "sizeLimit": "16Mi"}}]}},
        "volumeClaimTemplates": [{"metadata": {"name": "data", "annotations": {
            "argocd.argoproj.io/sync-options": "Prune=confirm"}}, "spec": {
                "accessModes": ["ReadWriteOnce"], "storageClassName": storage_class,
                "resources": {"requests": {"storage": "8Gi"}}}}]})
    workload["metadata"]["annotations"] = {"argocd.argoproj.io/sync-options": "Prune=confirm"}
    # Foundation deliberately has NO client ingress, API egress or TokenReview grant.
    # Those edges require a separately reviewed ESO/host-materialization identity.
    network = resource("networking.k8s.io/v1", "NetworkPolicy", "openbao-isolation",
                       spec={"podSelector": {}, "policyTypes": ["Ingress", "Egress"],
                             "ingress": [], "egress": [{"to": [{
                                 "namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "kube-system"}},
                                 "podSelector": {"matchLabels": {"k8s-app": "kube-dns"}}}],
                                 "ports": [{"protocol": "UDP", "port": 53}, {"protocol": "TCP", "port": 53}]}]})
    mesh = resource("security.istio.io/v1", "PeerAuthentication", "default",
                    spec={"mtls": {"mode": "STRICT"}})
    authorization = resource("security.istio.io/v1", "AuthorizationPolicy", "default-deny", spec={})
    return {"apiVersion": "v1", "kind": "List", "items": [
        namespace, account, config_map, service, network, mesh, authorization, workload]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", action="store_true", required=True,
                        help="review-only output; no production promotion authority")
    parser.add_argument("--storage-class", required=True)
    args = parser.parse_args()
    try:
        result = candidate(args.storage_class)
    except (ValueError, KeyError, OSError):
        parser.exit(1, "OPENBAO_CANDIDATE=Failed; inspect public configuration\n")
    print(json.dumps(result, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
