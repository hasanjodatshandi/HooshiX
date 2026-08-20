#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
python3 "$ROOT/scripts/local/runtime.py" down || true
"$ROOT/scripts/platform/kind_inotify_verify.sh"
"$ROOT/scripts/platform/kind_create.sh"
"$ROOT/scripts/platform/registry_up.sh"
"$ROOT/scripts/platform/waf_build.sh"
"$ROOT/scripts/platform/istio_install.sh"
"$ROOT/scripts/platform/kyverno_install.sh"
"$ROOT/scripts/platform/edge_install.sh"
"$ROOT/scripts/platform/staging_dataset_install.sh"
"$ROOT/scripts/platform/staging_data_install.sh"
"$ROOT/scripts/platform/observability_install.sh"
"$ROOT/scripts/platform/staging_build_all.sh"
"$ROOT/scripts/platform/staging_deploy_all.sh"
"$ROOT/scripts/platform/platform_verify.sh"
