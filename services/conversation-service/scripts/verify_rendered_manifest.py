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
    require(
        text,
        r"apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: conversation-service",
        "valid Conversation Deployment missing",
    )
    require(text, r"replicas: 1", "single-server replica count missing")
    forbid(text, r"kind: (HorizontalPodAutoscaler|PodDisruptionBudget)", "single-server must not render HPA/PDB")
    require(text, r"registry\.invalid/hooshix/conversation-service@sha256:[a-f0-9]{64}", "immutable digest missing")
    require(text, r"name: migrate", "migration init container missing")
    require(text, r"conversation-db-runtime", "runtime DB Secret missing")
    require(text, r"conversation-db-migration", "migration DB Secret missing")
    require(text, r"conversation-content", "content key-ring Secret missing")
    require(text, r"identity-jwt-public", "Identity JWT verifier ConfigMap missing")
    require(text, r"CONVERSATION_AUTHORIZATION_TARGET", "Authorization target missing")
    require(text, r"name: grpc[\s\S]*?containerPort: 9090", "bounded private gRPC listener missing")
    require(text, r"CONVERSATION_GRPC_MAXIMUM_CONCURRENT_CALLS[\s\S]*?32", "gRPC concurrency bound missing")
    require(text, r"authorization-service", "Authorization egress missing")
    require(text, r"CONVERSATION_PROVIDER_RUNTIME_ENABLED[\s\S]*?false", "provider kill switch must default off")
    require(text, r"CONVERSATION_EVENT_RUNTIME_ENABLED[\s\S]*?true", "event runtime render missing")
    require(text, r"conversation-kafka", "Kafka connection Secret missing")
    require(text, r"kafka\.platform-data\.svc", "Kafka bootstrap target missing")
    require(text, r"CONVERSATION_IDENTITY_ERASURE_TARGET", "Identity erasure target missing")
    require(text, r"identity-service", "Identity erasure egress missing")
    require(text, r"readOnlyRootFilesystem: true", "read-only root filesystem missing")
    require(text, r"allowPrivilegeEscalation: false", "privilege escalation hardening missing")
    require(text, r"drop:\s*\[\"ALL\"\]", "capability drop missing")
    require(text, r"mode: STRICT", "STRICT mTLS missing")
    require(text, r"istio\.io/use-waypoint: \"platform-apps-waypoint\"", "Conversation Service waypoint binding missing")
    require(
        text,
        r"kind: AuthorizationPolicy[\s\S]*?name: conversation-service-waypoint[\s\S]*?targetRefs:[\s\S]*?kind: Service[\s\S]*?name: conversation-service",
        "Conversation waypoint policy missing",
    )
    require(
        text,
        r"name: conversation-service-ztunnel[\s\S]*?principals: \[\"prod\.sajtech\.internal/ns/platform-apps/sa/platform-apps-waypoint\"\]",
        "ztunnel waypoint principal binding missing",
    )
    for method in (
        "CreateConversation",
        "ListConversations",
        "GetConversation",
        "ArchiveConversation",
        "DeleteConversation",
        "ListMessages",
        "CreateModelRun",
        "GetModelRun",
        "CancelModelRun",
        "SubmitRunFeedback",
    ):
        require(
            text,
            rf"/hooshix\.conversation\.v1\.ConversationService/{method}",
            f"Conversation waypoint path missing: {method}",
        )
    require(text, r"app\.kubernetes\.io/name: web-bff", "Web BFF ingress selector missing")
    require(
        text,
        r"prod\.sajtech\.internal/ns/platform-apps/sa/web-bff",
        "exact Web BFF workload principal missing",
    )
    require(text, r"conversation-postgresql", "database egress missing")
    require(text, r"otel-collector", "telemetry egress missing")
    forbid(text, r"api\.openai\.com", "provider egress must remain absent from foundation")
    forbid(text, r"type: (LoadBalancer|NodePort)", "Conversation must not expose a public Service")
    forbid(text, r"image:\s*[^\n]*:latest", "latest image tag is prohibited")


if __name__ == "__main__":
    main()
