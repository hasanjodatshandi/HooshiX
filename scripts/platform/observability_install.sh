#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
"$ROOT/scripts/platform/observability_preflight.sh"
"$ROOT/scripts/platform/staging_secrets_apply.sh"
k apply -f "$ROOT/infrastructure/observability/networkpolicy.yaml"
k apply -f "$ROOT/infrastructure/observability/authorizationpolicy.yaml"
k apply -f "$ROOT/infrastructure/observability/backends.yaml"
k apply -f "$ROOT/infrastructure/observability/collector.yaml"
k apply -f "$ROOT/infrastructure/observability/prometheus.yaml"
for d in loki tempo alertmanager grafana prometheus; do k rollout status deployment/$d -n platform-observability --timeout=90s; done
k rollout status daemonset/otel-collector -n platform-observability-node --timeout=90s
"$ROOT/scripts/platform/observability_foundation_verify.sh"
