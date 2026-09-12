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
    require(text, r"kind: Deployment\nmetadata:\n  name: conversation-service", "Conversation Deployment missing")
    require(text, r"replicas: 1", "single-server replica count missing")
    forbid(text, r"kind: (HorizontalPodAutoscaler|PodDisruptionBudget)", "single-server must not render HPA/PDB")
    require(text, r"registry\.invalid/hooshix/conversation-service@sha256:[a-f0-9]{64}", "immutable digest missing")
    require(text, r"name: migrate", "migration init container missing")
    require(text, r"conversation-db-runtime", "runtime DB Secret missing")
    require(text, r"conversation-db-migration", "migration DB Secret missing")
    require(text, r"conversation-content", "content key-ring Secret missing")
    require(text, r"CONVERSATION_PROVIDER_RUNTIME_ENABLED[\s\S]*?false", "provider kill switch must default off")
    require(text, r"readOnlyRootFilesystem: true", "read-only root filesystem missing")
    require(text, r"allowPrivilegeEscalation: false", "privilege escalation hardening missing")
    require(text, r"drop:\s*\[\"ALL\"\]", "capability drop missing")
    require(text, r"mode: STRICT", "STRICT mTLS missing")
    require(text, r"conversation-postgresql", "database egress missing")
    require(text, r"otel-collector", "telemetry egress missing")
    forbid(text, r"api\.openai\.com", "provider egress must remain absent from foundation")
    forbid(text, r"type: (LoadBalancer|NodePort)", "Conversation must not expose a public Service")
    forbid(text, r"image:\s*[^\n]*:latest", "latest image tag is prohibited")


if __name__ == "__main__":
    main()
