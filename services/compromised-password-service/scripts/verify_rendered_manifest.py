#!/usr/bin/env python3
"""Verify security and single-server invariants in the rendered Helm manifest."""

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
    require(
        text,
        r'image: "registry\.invalid/hooshix/compromised-password-service@sha256:[0-9a-f]{64}"',
        "workload image must use an immutable digest",
    )
    require(text, r"serviceAccountName: compromised-password-service", "dedicated ServiceAccount is missing")
    require(text, r"automountServiceAccountToken: false", "ServiceAccount token automount must be disabled")
    require(text, r"runAsNonRoot: true", "runAsNonRoot must be enabled")
    require(text, r"allowPrivilegeEscalation: false", "privilege escalation must be disabled")
    require(text, r"readOnlyRootFilesystem: true", "root filesystem must be read-only")
    require(text, r"type: RuntimeDefault", "RuntimeDefault seccomp must be configured")
    require(text, r'drop: \["ALL"\]', "all Linux capabilities must be dropped")
    require(text, r"claimName: compromised-password-test-dataset", "dataset PVC is missing")
    require(text, r"readOnly: true", "dataset must be mounted read-only")
    require(text, r"emptyDir:\n\s+sizeLimit: 256Mi", "bounded temporary storage is missing")
    require(text, r"^  type: ClusterIP$", "service must remain ClusterIP-only")
    require(text, r"^kind: NetworkPolicy$", "deny-by-default scoped network policy is missing")
    require(text, r"policyTypes: \[\"Ingress\", \"Egress\"\]", "both ingress and egress must be governed")
    require(text, r"^kind: PeerAuthentication$", "PeerAuthentication is missing")
    require(text, r"mode: STRICT", "Ambient mTLS must be STRICT")
    require(text, r"^kind: AuthorizationPolicy$", "Istio AuthorizationPolicy is missing")
    require(
        text,
        r"prod\.sajtech\.internal/ns/platform-apps/sa/identity-service",
        "Identity workload principal is missing",
    )
    require(
        text,
        r"prod\.sajtech\.internal/ns/platform-observability/sa/prometheus",
        "Prometheus management principal is missing",
    )
    require(
        text,
        r"name: MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT\n\s+value: http://otel-collector\.platform-observability\.svc:4318/v1/traces",
        "trace-specific OTLP endpoint is missing",
    )

    for kind in ("HorizontalPodAutoscaler", "PodDisruptionBudget", "Ingress", "Gateway"):
        forbid(text, rf"^kind: {kind}$", f"{kind} is prohibited in this single-server service package")
    forbid(text, r"serviceAccountName: default", "default ServiceAccount is prohibited")
    forbid(text, r"image: .*:latest(?:\s|$)", "latest image tags are prohibited")
    forbid(text, r"hostNetwork: true", "host networking is prohibited")
    forbid(text, r"privileged: true", "privileged containers are prohibited")
    forbid(text, r"hostPath:", "hostPath is prohibited for this workload")
    forbid(text, r"^\s*- \{\}\s*$", "unrestricted NetworkPolicy peers are prohibited")
    forbid(
        text,
        r"name: OTEL_EXPORTER_OTLP_ENDPOINT",
        "generic OTLP endpoint is prohibited because metrics use Prometheus scrape",
    )

    print("Rendered compromised-password manifest verification PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
