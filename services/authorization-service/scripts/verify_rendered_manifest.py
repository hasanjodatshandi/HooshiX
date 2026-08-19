#!/usr/bin/env python3
import re
import sys
from pathlib import Path


def require(text: str, pattern: str, message: str) -> None:
    if re.search(pattern, text, re.MULTILINE) is None:
        raise SystemExit(message)


def forbid(text: str, pattern: str, message: str) -> None:
    if re.search(pattern, text, re.MULTILINE) is not None:
        raise SystemExit(message)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify_rendered_manifest.py <rendered.yaml>")
    text = Path(sys.argv[1]).read_text(encoding="utf-8")
    require(text, r"kind: Deployment\nmetadata:\n  name: authorization-service", "Authorization Deployment missing")
    require(text, r"replicas: 1", "single-server replica count missing")
    forbid(text, r"kind: (HorizontalPodAutoscaler|PodDisruptionBudget)", "single-server must not render HPA/PDB")
    require(text, r"registry\.invalid/hooshix/authorization-service@sha256:[a-f0-9]{64}", "immutable image digest missing")
    require(text, r"name: migrate", "migration init container missing")
    require(text, r"authorization-db-runtime", "runtime database secret missing")
    require(text, r"authorization-db-migration", "migration database secret missing")
    require(text, r"AUTHORIZATION_RUNTIME_ENABLED", "runtime kill switch missing")
    require(text, r"AUTHORIZATION_IDENTITY_JWT_VERIFIER_BUNDLE_PATH", "Identity JWT verifier bundle missing")
    require(text, r"AUTHORIZATION_QUOTA_KEY_RING_PATH", "quota HMAC key ring missing")
    require(text, r"AUTHORIZATION_HOST_TIME_STATUS_PATH", "host-time safety evidence missing")
    require(text, r"readOnlyRootFilesystem: true", "read-only root filesystem missing")
    require(text, r"allowPrivilegeEscalation: false", "privilege escalation hardening missing")
    require(text, r"drop:\s*\[\"ALL\"\]", "capability drop missing")
    require(text, r"mode: STRICT", "STRICT mTLS missing")
    require(text, r"/hooshix\.authorization\.v1\.AuthorizationService/CheckPermission", "resource CheckPermission policy missing")
    require(text, r"/hooshix\.authorization\.v1\.AuthorizationService/CheckPlatformPermission", "Identity platform check policy missing")
    require(text, r"/hooshix\.authorization\.v1\.AuthorizationService/CreateRole", "BFF management policy missing")
    require(text, r"authorization-postgresql", "database egress missing")
    require(text, r"security-redis", "security Redis egress missing")
    require(text, r"otel-collector", "telemetry egress missing")
    forbid(text, r"principals:\s*\[[^\]]*\*", "wildcard Istio principal is prohibited")
    forbid(text, r"image:\s*[^\n]*:latest", "latest image tag is prohibited")


if __name__ == "__main__":
    main()