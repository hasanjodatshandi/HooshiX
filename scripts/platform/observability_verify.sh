#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
"$ROOT/scripts/platform/observability_metrics_verify.sh"
"$ROOT/scripts/platform/observability_trace_verify.sh"
"$ROOT/scripts/platform/observability_logs_verify.sh"
"$ROOT/scripts/platform/observability_grafana_verify.sh"
"$ROOT/scripts/platform/observability_fault_verify.sh"
echo "Full local observability verification PASSED"
