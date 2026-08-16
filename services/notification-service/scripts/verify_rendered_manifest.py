#!/usr/bin/env python3
"""Verify Notification service single-server security invariants in rendered Helm YAML."""

from __future__ import annotations

import re
import sys
from pathlib import Path


def require(text: str, pattern: str, message: str) -> None:
    if re.search(pattern, text, re.MULTILINE) is None:
        raise AssertionError(message)


def forbid(text: str, pattern: str, message: str) -> None:
    if re.search(pattern, text, re.MULTILINE) is not None:
        raise AssertionError(message)


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify_rendered_manifest.py <rendered.yaml>", file=sys.stderr)
        return 2
    text = Path(sys.argv[1]).read_text(encoding="utf-8")
    require(text, r"^kind: Deployment$", "Deployment is missing")
    require(text, r"^  replicas: 1$", "single-server profile must render one replica")
    require(text, r'image: "registry\.invalid/hooshix/notification-service@sha256:[0-9a-f]{64}"', "immutable image digest is missing")
    require(text, r"name: migrate", "Flyway migration init container is missing")
    require(text, r"--spring\.profiles\.active=migration", "migration profile is missing")
    require(text, r'secretName: "notification-db-runtime"', "runtime DB Secret is missing")
    require(text, r'secretName: "notification-db-migration"', "migration DB Secret is missing")
    require(text, r'secretName: "notification-fingerprint"', "fingerprint key-ring Secret is missing")
    require(text, r'secretName: "notification-delivery"', "delivery key-ring Secret is missing")
    require(text, r'serviceAccountName: "notification-service"', "dedicated ServiceAccount is missing")
    require(text, r"automountServiceAccountToken: false", "ServiceAccount token automount must be disabled")
    require(text, r"runAsNonRoot: true", "runAsNonRoot must be enabled")
    require(text, r"allowPrivilegeEscalation: false", "privilege escalation must be disabled")
    require(text, r"readOnlyRootFilesystem: true", "root filesystem must be read-only")
    require(text, r"type: RuntimeDefault", "RuntimeDefault seccomp must be configured")
    require(text, r'drop: \["ALL"\]', "all Linux capabilities must be dropped")
    require(text, r"emptyDir:\n\s+sizeLimit: 256Mi", "bounded temporary storage is missing")
    require(text, r"^  type: ClusterIP$", "service must remain ClusterIP-only")
    require(text, r"^kind: NetworkPolicy$", "NetworkPolicy is missing")
    require(text, r'policyTypes: \["Ingress", "Egress"\]', "ingress and egress must both be governed")
    require(text, r"^kind: PeerAuthentication$", "PeerAuthentication is missing")
    require(text, r"mode: STRICT", "Ambient mTLS must be STRICT")
    require(text, r"^kind: AuthorizationPolicy$", "Istio AuthorizationPolicy is missing")
    require(text, r"prod\.sajtech\.internal/ns/platform-apps/sa/identity-service", "Identity workload principal is missing")
    require(text, r"prod\.sajtech\.internal/ns/platform-observability/sa/prometheus", "Prometheus principal is missing")
    require(text, r"notification-postgresql", "Notification database egress selector is missing")
    require(text, r'MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT\n\s+value: "http://otel-collector\.platform-observability\.svc:4318/v1/traces"', "trace-specific OTLP endpoint is missing")
    for kind in ("HorizontalPodAutoscaler", "PodDisruptionBudget", "Ingress", "Gateway"):
        forbid(text, rf"^kind: {kind}$", f"{kind} is prohibited in this single-server slice")
    forbid(text, r'serviceAccountName: "?default"?', "default ServiceAccount is prohibited")
    forbid(text, r"image: .*:latest(?:\s|$)", "latest image tags are prohibited")
    forbid(text, r"hostNetwork: true", "host networking is prohibited")
    forbid(text, r"privileged: true", "privileged containers are prohibited")
    forbid(text, r"hostPath:", "hostPath is prohibited")
    forbid(text, r"^\s*- \{\}\s*$", "unrestricted NetworkPolicy peers are prohibited")
    print("Rendered Notification manifest verification PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
