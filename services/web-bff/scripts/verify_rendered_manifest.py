#!/usr/bin/env python3
import re
import sys
from pathlib import Path


def require(text, pattern, message):
    if re.search(pattern, text, re.MULTILINE) is None:
        raise SystemExit(message)


def forbid(text, pattern, message):
    if re.search(pattern, text, re.MULTILINE) is not None:
        raise SystemExit(message)


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify_rendered_manifest.py <rendered.yaml>")
    text = Path(sys.argv[1]).read_text(encoding="utf-8")
    require(text, r"kind: Deployment\nmetadata:\n  name: web-bff", "Web BFF Deployment missing")
    require(text, r"replicas: 1", "single-server replica count missing")
    forbid(text, r"kind: (HorizontalPodAutoscaler|PodDisruptionBudget|Ingress|Gateway)", "Web BFF must not render HA or direct public ingress objects")
    require(text, r"registry\.invalid/hooshix/web-bff@sha256:[a-f0-9]{64}", "immutable image digest missing")
    require(text, r"WEB_BFF_RUNTIME_ENABLED", "runtime kill switch missing")
    require(text, r"WEB_BFF_REQUIRE_FETCH_METADATA", "Fetch Metadata gate missing")
    require(text, r"WEB_BFF_PUBLIC_ORIGIN", "exact public origin missing")
    require(text, r"WEB_BFF_IDENTITY_TARGET", "Identity target missing")
    require(text, r"WEB_BFF_AUTHORIZATION_TARGET", "Authorization target missing")
    require(text, r"WEB_BFF_LOCATOR_KEY_RING_PATH", "session locator key ring missing")
    require(text, r"WEB_BFF_CSRF_KEY_RING_PATH", "CSRF key ring missing")
    require(text, r"WEB_BFF_REFRESH_KEY_RING_PATH", "refresh encryption key ring missing")
    require(text, r"readOnlyRootFilesystem: true", "read-only root filesystem missing")
    require(text, r"allowPrivilegeEscalation: false", "privilege escalation hardening missing")
    require(text, r"mode: STRICT", "STRICT mTLS missing")
    require(text, r"prod\.sajtech\.internal/ns/platform-edge/sa/caddy-coraza", "edge-only application principal missing")
    require(text, r"identity-service", "Identity egress missing")
    require(text, r"authorization-service", "Authorization egress missing")
    require(text, r"security-redis", "Redis egress missing")
    require(text, r"otel-collector", "telemetry egress missing")
    forbid(text, r"principals:\s*\[[^\]]*\*", "wildcard Istio principal is prohibited")
    forbid(text, r"image:\s*[^\n]*:latest", "latest image tag is prohibited")


if __name__ == "__main__":
    main()