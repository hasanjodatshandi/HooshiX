#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
"$ROOT/scripts/platform/istio_foundation_verify.sh"
"$ROOT/scripts/platform/mesh_identity_verify.sh"
echo "Istio Ambient full integration verification PASSED"
